# Moonraker connections and transfers

The printer transport owns the Moonraker HTTP client. Bare HTTP hosts default to port 7125; HTTPS and explicit reverse-proxy paths retain their standard ports. Explicit ports and path prefixes survive normalization. Credentials, query parameters and fragments are not accepted in a configured base URL.

## Credentials

Optional API keys live in `MoonrakerApiKeyStore`, under Android's application-private `noBackupFilesDir`. They are not fields in `Printer`, printer configuration JSON, or settings exports, including exports that opt into Bambu credentials. The records use atomic replacement and bounded reads. This is private filesystem storage, not application-level Keystore encryption.

A record is keyed by a hash of the stable printer ID and bound to its complete normalized endpoint. Reusing an ID with a different host, port or path does not retrieve the old key. Endpoint changes leave the old record bound to its previous endpoint until it is explicitly replaced or removed. Deleting a printer removes its record. The editor never prefills a key: blank keeps a matching saved key, explicit removal clears it, and a supplied replacement is used by connection tests before saving.

Printer configuration and key records use separate storage writes. If an endpoint-and-key change is interrupted between them, the endpoint binding fails closed and the key may need to be entered again. These writes do not form a cross-store transaction.

Per-printer credential revisions participate in transport selection. Changing an inactive printer's key does not restart the active connection. An active credential change invalidates outstanding ViewModel operations and recreates the transport even when ordinary printer configuration is unchanged.

The client attaches `X-Api-Key` only within the configured scheme, host, port and route prefix. A network interceptor removes it from redirect hops outside that scope. WebSocket camera wakeup explicitly applies the same scope and does not follow redirects. Snapshot downloads use the transport's scoped client; third-party camera URLs receive no Moonraker key. Keys and signed camera URLs are omitted from diagnostics, and upload errors redact a server-echoed key.

## Upload and start

U1 send preparation carries a frozen printer ID, connection fingerprint and generation through G-code remapping. A printer switch cannot retarget a completed remap to the newly selected printer. The repository captures the matching transport before upload and rechecks that binding before starting a print.

Uploads stream the file once, report file-payload bytes, and remain pending until the server confirms a recognizable Moonraker upload response. Body completion is not upload success. Cancellation cancels the underlying HTTP call. Uploads do not automatically follow redirects or replay POSTs after an I/O error: a lost response cannot establish whether the server accepted the file.

Progress is associated with a specific send attempt. Late callbacks and cleanup from an earlier canceled attempt cannot change the next attempt's UI.

`uploadAndStartJob` checks readiness before upload and again before requesting start. Moonraker must report connected, ready Klippy, ready webhooks and a terminal print state. Printing, paused, missing and unknown states block a start. A previous job's `error` state is terminal when Klippy and webhooks are ready. Upload Only does not perform these print-start checks.

The coordinator checks cancellation and transport identity after each pre-start suspension. A switch after a start request has an uncertain outcome and is not reported as proof that printing did not start. Preflight is a snapshot; firmware remains the final authority on accepting a start.

Cancellation requires confirmation for the displayed printer, and pause/resume/cancel failures appear on screen. Camera JPEG decoding runs off the UI thread; the camera wakeup loop is used only for the `camera/monitor.jpg` fallback.

## Live spool mapping

Opening the U1 print mapping dialog requests fresh spool information once, with an eight-second aggregate timeout and cancellation of the underlying HTTP call. Results are bound to the selected printer and transport. Loading and unavailable states are explicit; cached slot data is not presented as a fresh response.

Live loaded-slot colors and materials inform the picker and color suggestions. They do not replace saved slicing presets or the material recorded at slice time. Empty-slot and material-mismatch notices are advisory. A delayed response preserves a supplied mapping or a manual selection; automatic suggestions apply only to an untouched dialog without an initial mapping. An explicit suggestion uses loaded candidates with usable colors and retains the current mapping when none are available.

## Verification boundary

JVM tests use local HTTP fixtures and fake transports. Isolated Compose/WebView tests run on a Linux Android emulator using separate test APK copies without the native engine. These checks do not establish native slicing quality, live-site login compatibility, or physical printer behavior; those require the owner's ARM64 phone and printer.
