#!/usr/bin/env bash
set -u -o pipefail

usage() { echo "Usage: U1_BASE_URL=http://printer:7125 U1_SCREEN_URL=http://printer/ [U1_API_KEY=...] $0 OUTPUT_DIR" >&2; }
if [[ $# -ne 1 || -z "${U1_BASE_URL:-}" || -z "${U1_SCREEN_URL:-}" ]]; then usage; exit 2; fi
out_dir=$1
timeout_seconds=${U1_TIMEOUT_SECONDS:-10}
max_bytes=$((1024 * 1024))
if ! [[ $timeout_seconds =~ ^[0-9]+$ ]] || (( timeout_seconds < 1 || timeout_seconds > 120 )); then
  echo 'U1_TIMEOUT_SECONDS must be an integer from 1 to 120.' >&2; exit 2
fi

valid_base_url() {
  local url=$1 authority
  case "$url" in http://*|https://*) ;; *) return 1;; esac
  [[ "$url" != *\?* && "$url" != *\#* && "$url" != *[[:space:]]* ]] || return 1
  authority=${url#*://}; authority=${authority%%/*}
  [[ -n "$authority" && "$authority" != *@* ]]
}
if ! valid_base_url "$U1_BASE_URL" || ! valid_base_url "$U1_SCREEN_URL"; then
  echo 'Printer and screen URLs must be HTTP(S) base URLs without userinfo, query, or fragment.' >&2; exit 2
fi
command -v curl >/dev/null 2>&1 || { echo 'curl is required.' >&2; exit 2; }
curl_version=$(curl --version | head -n 1 | awk '{print $2}')
IFS=. read -r curl_major curl_minor _ <<< "$curl_version"
if ! [[ ${curl_major:-} =~ ^[0-9]+$ && ${curl_minor:-} =~ ^[0-9]+$ ]] ||
   (( curl_major < 7 || (curl_major == 7 && 10#$curl_minor < 63) )); then
  echo 'curl 7.63 or newer is required for bounded response capture.' >&2; exit 2
fi
mkdir -p -- "$out_dir" || exit 2

# A private curl config avoids exposing the API key in the curl process arguments.
umask 077
header_config=''
tmp_files=()
cleanup() { if ((${#tmp_files[@]})); then rm -f -- "${tmp_files[@]}"; fi; }
trap cleanup EXIT
if [[ -n "${U1_API_KEY:-}" ]]; then
  case "$U1_API_KEY" in *$'\r'*|*$'\n'*) echo 'U1_API_KEY must not contain CR or LF.' >&2; exit 2;; esac
  escaped_key=${U1_API_KEY//\\/\\\\}
  escaped_key=${escaped_key//\"/\\\"}
  header_config=$(mktemp) || { echo 'Could not create a private temporary header file.' >&2; exit 2; }
  tmp_files+=("$header_config")
  printf 'header = "X-Api-Key: %s"\n' "$escaped_key" > "$header_config"
fi

check_get() {
  local name=$1 url=$2 file=$3 output="$out_dir/$3" status_file body_file code rc size oversized=0
  status_file=$(mktemp) || { echo "$name: FAILED (cannot create temporary status file)"; return 1; }
  body_file=$(mktemp) || { rm -f -- "$status_file"; echo "$name: FAILED (cannot create temporary body file)"; return 1; }
  tmp_files+=("$status_file" "$body_file")
  local args=(--silent --max-time "$timeout_seconds" --output - --write-out '%{stderr}%{http_code}')
  [[ -n "$header_config" ]] && args+=(--config "$header_config")
  # head reads at most one byte beyond the cap; that extra byte identifies overflow.
  curl "${args[@]}" "$url" 2>"$status_file" | head -c "$((max_bytes + 1))" > "$body_file"
  rc=$?
  code=$(<"$status_file")
  rm -f -- "$status_file"
  code=${code: -3}
  size=$(wc -c < "$body_file"); size=${size//[[:space:]]/}
  if (( size > max_bytes )); then oversized=1; fi
  if [[ -n "${U1_API_KEY:-}" ]]; then
    local content
    content=$(head -c "$max_bytes" "$body_file"; printf '\001')
    content=${content%$'\001'}
    content=${content//"$U1_API_KEY"/'[REDACTED]'}
    printf '%s' "$content" > "$output"
  else
    head -c "$max_bytes" "$body_file" > "$output"
  fi
  if (( rc == 0 && oversized == 0 )) && [[ $code =~ ^2[0-9][0-9]$ ]]; then
    echo "$name: OK (HTTP $code)"; return 0
  fi
  if (( oversized )); then echo "$name: FAILED (HTTP ${code:-000}; response exceeded limit)"
  elif (( rc != 0 )); then echo "$name: FAILED (HTTP ${code:-000}; request/read failed)"
  else echo "$name: FAILED (HTTP ${code:-000})"; fi
  return 1
}

check_head() {
  local name=$1 url=$2 code rc
  local args=(--silent --head --max-time "$timeout_seconds" --output /dev/null --write-out '%{http_code}')
  # No redirect following; no API key is sent to the screen endpoint.
  code=$(curl "${args[@]}" "$url" 2>/dev/null); rc=$?
  if (( rc == 0 )) && [[ $code =~ ^2[0-9][0-9]$ ]]; then echo "$name: OK (HTTP $code)"; return 0; fi
  echo "$name: FAILED (HTTP ${code:-000})"; return 1
}

base=${U1_BASE_URL%/}
screen=${U1_SCREEN_URL%/}/screen/
failed=0
check_get 'Moonraker objects' "$base/printer/objects/query?print_task_config&filament_detect" 'printer-objects.json' || failed=1
check_get 'Webcams' "$base/server/webcams/list" 'webcams.json' || failed=1
check_get 'Server info' "$base/server/info" 'server-info.json' || failed=1
check_head 'Remote screen' "$screen" || failed=1
exit "$failed"
