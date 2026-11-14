param(
    [Parameter(Mandatory = $true)] [string] $OutputDirectory,
    [int] $TimeoutSeconds = 10
)

$ErrorActionPreference = 'Stop'
$maxBytes = 1MB
$baseUrl = $env:U1_BASE_URL
$screenUrl = $env:U1_SCREEN_URL
$apiKey = $env:U1_API_KEY
if ($env:U1_TIMEOUT_SECONDS) { $TimeoutSeconds = [int]$env:U1_TIMEOUT_SECONDS }

if ([string]::IsNullOrWhiteSpace($baseUrl)) { throw 'Set U1_BASE_URL to the printer Moonraker base URL.' }
if ([string]::IsNullOrWhiteSpace($screenUrl)) { throw 'Set U1_SCREEN_URL to the screen base URL (usually http://printer/). It may use a different port from Moonraker.' }
foreach ($url in @($baseUrl, $screenUrl)) {
    $parsed = $null
    if (-not [Uri]::TryCreate($url, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -notin @('http', 'https') -or $parsed.UserInfo -or $parsed.Query -or $parsed.Fragment) {
        throw 'Printer and screen URLs must be HTTP(S) base URLs without userinfo, query, or fragment.'
    }
}
if ($TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 120) { throw 'TimeoutSeconds must be between 1 and 120.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Invoke-Check([string] $Name, [string] $Url, [string] $Method, [string] $FileName, [bool] $UseApiKey) {
    $request = [System.Net.HttpWebRequest]::Create($Url)
    $request.Method = $Method
    $request.Timeout = $TimeoutSeconds * 1000
    $request.ReadWriteTimeout = $TimeoutSeconds * 1000
    $request.AllowAutoRedirect = $false
    if ($UseApiKey -and -not [string]::IsNullOrEmpty($apiKey)) { $request.Headers.Add('X-Api-Key', $apiKey) }
    $response = $null
    $stream = $null
    $memory = $null
    $statusCode = 0
    $body = ''
    $requestFailed = $false
    $tooLarge = $false
    try {
        $response = $request.GetResponse()
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) { $response = $_.Exception.Response }
        else { $requestFailed = $true }
    } catch {
        $requestFailed = $true
    }
    try {
        if ($response) {
            $statusCode = [int]$response.StatusCode
            if ($Method -ne 'HEAD') {
                $stream = $response.GetResponseStream()
                $memory = New-Object System.IO.MemoryStream
                $buffer = New-Object byte[] 8192
                while ($true) {
                    $remaining = $maxBytes + 1 - [int]$memory.Length
                    if ($remaining -le 0) { $tooLarge = $true; break }
                    $count = $stream.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
                    if ($count -eq 0) { break }
                    $memory.Write($buffer, 0, $count)
                }
                if ($memory.Length -gt $maxBytes) { $tooLarge = $true }
                if ($response.ContentLength -ge 0 -and $memory.Length -ne $response.ContentLength) { $requestFailed = $true }
                $bytes = $memory.ToArray()
                $bodyLength = [Math]::Min([int]$memory.Length, $maxBytes)
                $body = [System.Text.Encoding]::UTF8.GetString($bytes, 0, $bodyLength)
            }
        }
    } catch {
        $requestFailed = $true
    } finally {
        if ($stream) { $stream.Dispose() }
        if ($memory) { $memory.Dispose() }
        if ($response) { $response.Close() }
    }
    if ($Method -ne 'HEAD') {
        if (-not [string]::IsNullOrEmpty($apiKey)) { $body = $body.Replace($apiKey, '[REDACTED]') }
        [System.IO.File]::WriteAllText((Join-Path $OutputDirectory $FileName), $body, (New-Object System.Text.UTF8Encoding($false)))
    }
    $ok = -not $requestFailed -and -not $tooLarge -and $statusCode -ge 200 -and $statusCode -lt 300
    if ($ok) { Write-Host "$Name`: OK (HTTP $statusCode)" }
    elseif ($tooLarge) { Write-Host "$Name`: FAILED (HTTP $statusCode; response exceeded limit)" }
    elseif ($requestFailed) { Write-Host "$Name`: FAILED (HTTP $statusCode; request/read failed)" }
    else { Write-Host "$Name`: FAILED (HTTP $statusCode)" }
    return $ok
}

$base = $baseUrl.TrimEnd('/')
$allOk = $true
$allOk = (Invoke-Check 'Moonraker objects' "$base/printer/objects/query?print_task_config&filament_detect" 'GET' 'printer-objects.json' $true) -and $allOk
$allOk = (Invoke-Check 'Webcams' "$base/server/webcams/list" 'GET' 'webcams.json' $true) -and $allOk
$allOk = (Invoke-Check 'Server info' "$base/server/info" 'GET' 'server-info.json' $true) -and $allOk
$screen = $screenUrl.TrimEnd('/') + '/screen/'
$allOk = (Invoke-Check 'Remote screen' $screen 'HEAD' 'screen-head.txt' $false) -and $allOk
if (-not $allOk) { exit 1 }
exit 0
