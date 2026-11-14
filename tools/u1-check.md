# U1 read-only checks

These scripts make only GET requests for Moonraker status and a HEAD request for `/screen/`; they never issue printer commands. Set `U1_BASE_URL` to the Moonraker base URL (for example `http://192.168.1.20:7125`) and `U1_SCREEN_URL` to the HTTP base URL serving the screen (often `http://192.168.1.20`, port 80). Base URLs must not contain credentials, a query, or a fragment. If configured, set `U1_API_KEY`; it is sent only to Moonraker, never followed across redirects, and scrubbed from saved response bodies. Choose an output directory for the three JSON responses (each capped at 1 MiB). The screen HEAD result is reported, not saved as a body. Redirects and non-2xx statuses fail the check.

PowerShell 5.1+:

```powershell
$env:U1_BASE_URL = 'http://192.168.1.20:7125'
$env:U1_SCREEN_URL = 'http://192.168.1.20'
# Optional: $env:U1_API_KEY = '...'
.\u1-check.ps1 -OutputDirectory .\u1-capture
```

Bash with curl:

```bash
export U1_BASE_URL='http://192.168.1.20:7125'
export U1_SCREEN_URL='http://192.168.1.20'
# Optional: export U1_API_KEY='...'
bash ./u1-check.sh ./u1-capture
```

Both scripts accept `U1_TIMEOUT_SECONDS` (default 10, maximum 120). Bash requires curl 7.63 or newer for bounded streaming capture. Oversized bodies and request/read failures are reported as failures even when the HTTP status is 200. Treat captured printer information as local test data and review it before sharing.
