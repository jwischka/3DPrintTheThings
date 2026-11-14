#!/usr/bin/env python3
"""In-memory U1/Moonraker fixture. It never connects to a real printer."""
import argparse
import hashlib
import json
import socket
import threading
import time
from pathlib import Path
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MARKER = "3DPrintTheThings U1 simulator"
API_KEY = "simulator-test-key"
CONTROL_KEY = "simulator-control"
MAX_UPLOAD = 8 * 1024 * 1024
FIXTURE_SHA256 = "3bc24cffc43194b17e30507fc6fb06fb6c39bf239087f5c960c1dc9d9dd96e1a"
FIXTURE_PATH = Path(__file__).resolve().parents[1] / "parity/fixtures/cube_10mm.stl"


class State:
    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.print_state = "standby"
            self.connected = True
            self.loaded = [True, True, True, False]
            self.upload_mode = "ok"
            self.progress = 0.0
            self.files = {}
            self.events = []

    def event(self, kind, **fields):
        self.events.append({"kind": kind, **fields})
        self.events = self.events[-1000:]

    def snapshot(self):
        with self.lock:
            return {"simulator": MARKER, "state": self.print_state,
                    "connected": self.connected, "loaded": self.loaded[:],
                    "files": dict(self.files), "events": list(self.events)}


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, require_key=True):
        self.state = State()
        self.require_key = require_key
        self.model_fixture = FIXTURE_PATH.read_bytes()
        if not 0 < len(self.model_fixture) <= 1024 * 1024 or hashlib.sha256(self.model_fixture).hexdigest() != FIXTURE_SHA256:
            raise ValueError("Pinned model fixture is missing or changed")
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass  # No request headers, filenames or credentials in console logs.

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def finish(self):
        try:
            super().finish()
        finally:
            if getattr(self, "upload_seen", False):
                with self.server.state.lock:
                    self.server.state.event("upload_finished")

    def reply(self, value, status=200):
        body = json.dumps(value, separators=(",", ":")).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            self.close_connection = True

    def allowed(self, control=False):
        if control:
            valid = self.headers.get("X-Simulator-Control") == CONTROL_KEY
        else:
            valid = not self.server.require_key or self.headers.get("X-Api-Key") == API_KEY
        if not valid:
            self.reply({"error": {"message": "Authorization required"}}, 401)
        return valid

    def body(self, limit):
        try:
            count = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            count = -1
        if count < 0 or self.headers.get("Transfer-Encoding"):
            self.reply({"error": {"message": "Content-Length required"}}, 411)
            return None
        if count > limit:
            self.reply({"error": {"message": "Fixture body limit exceeded"}}, 413)
            return None
        result = bytearray()
        try:
            while len(result) < count:
                chunk = self.rfile.read(min(65536, count - len(result)))
                if not chunk:
                    return None
                result.extend(chunk)
        except (OSError, TimeoutError):
            return None
        return bytes(result)

    def do_GET(self):
        parsed = urlsplit(self.path)
        state = self.server.state
        if parsed.path == "/__sim/info":
            return self.reply({"simulator": MARKER})
        if parsed.path.startswith("/__sim/fixture/"):
            if not self.allowed(control=True):
                return
            if parsed.path != "/__sim/fixture/cube_10mm.stl":
                return self.reply({"error": "Unknown model fixture"}, 404)
            data = self.server.model_fixture
            self.send_response(200)
            self.send_header("Content-Type", "model/stl")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True
            return
        if parsed.path == "/__sim/state":
            if self.allowed(control=True):
                self.reply(state.snapshot())
            return
        if not self.allowed():
            return
        with state.lock:
            if parsed.path == "/server/info":
                return self.reply({"result": {"klippy_connected": state.connected,
                    "klippy_state": "ready" if state.connected else "disconnected"}})
            if parsed.path == "/printer/objects/query":
                objects = {
                    "webhooks": {"state": "ready" if state.connected else "shutdown"},
                    "print_stats": {"state": state.print_state, "filename": "fixture.gcode",
                                    "print_duration": 12.0, "filament_used": 4.0},
                    "virtual_sdcard": {"progress": state.progress},
                    "heater_bed": {"temperature": 22.0, "target": 0.0},
                    "print_task_config": {
                        "filament_exist": state.loaded[:],
                        "filament_type": ["PLA", "PLA", "PLA", "NONE"],
                        "filament_sub_type": ["Matte", "Matte", "Matte", "NONE"],
                        "filament_vendor": ["Fixture"] * 4,
                        "filament_color_rgba": ["FF0000FF", "00FF00FF", "0000FFFF", "00000000"],
                    },
                    "filament_detect": {"info": [{"BED_TEMP": 60, "HOTEND_MAX_TEMP": 220}] * 4},
                }
                for number in range(4):
                    objects["extruder" + (str(number) if number else "")] = {"temperature": 23.0, "target": 0.0}
                selected = {key: value for key, value in objects.items()
                            if key in parse_qs(parsed.query, keep_blank_values=True)}
                return self.reply({"result": {"status": selected}})
            if parsed.path == "/server/webcams/list":
                return self.reply({"result": {"webcams": []}})
            if parsed.path == "/printer/objects/list":
                return self.reply({"result": {"objects": ["print_task_config", "filament_detect"]}})
        self.reply({"error": {"message": "Unsupported fixture endpoint"}}, 404)

    def do_POST(self):
        parsed = urlsplit(self.path)
        state = self.server.state
        if parsed.path.startswith("/__sim/"):
            if not self.allowed(control=True):
                return
            raw = self.body(4096)
            if raw is None:
                return
            if parsed.path == "/__sim/reset":
                state.reset()
                return self.reply(state.snapshot())
            if parsed.path != "/__sim/configure":
                return self.reply({"error": "Unknown control endpoint"}, 404)
            try:
                config = json.loads(raw)
                if not isinstance(config, dict) or set(config) - {"state", "connected", "loaded", "upload_mode", "progress"}:
                    raise ValueError()
                if "state" in config and config["state"] not in ("standby", "printing", "paused", "complete", "error"):
                    raise ValueError()
                if "connected" in config and type(config["connected"]) is not bool:
                    raise ValueError()
                if "loaded" in config and (not isinstance(config["loaded"], list) or len(config["loaded"]) != 4 or
                                           any(type(value) is not bool for value in config["loaded"])):
                    raise ValueError()
                if "upload_mode" in config and config["upload_mode"] not in ("ok", "reject", "disconnect", "malformed", "slow"):
                    raise ValueError()
                if "progress" in config and (type(config["progress"]) not in (int, float) or not 0 <= config["progress"] <= 1):
                    raise ValueError()
            except (ValueError, TypeError):
                return self.reply({"error": "Invalid fixture configuration"}, 400)
            with state.lock:
                for key, value in config.items():
                    setattr(state, "print_state" if key == "state" else key, value)
            return self.reply(state.snapshot())
        if not self.allowed():
            return
        if parsed.path == "/server/files/upload":
            self.upload_seen = True
            with state.lock:
                mode = state.upload_mode
                state.event("upload_request")
            if mode == "reject":
                return self.reply({"error": {"message": "Simulated storage full"}}, 507)
            if mode == "disconnect":
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if mode == "slow":
                time.sleep(2)
            raw = self.body(MAX_UPLOAD)
            if raw is None:
                return
            try:
                content_type = self.headers.get("Content-Type", "")
                if not content_type.startswith("multipart/form-data;"):
                    raise ValueError()
                message = BytesParser(policy=policy.default).parsebytes(
                    ("Content-Type: " + content_type + "\r\nMIME-Version: 1.0\r\n\r\n").encode() + raw)
                files = [part for part in message.iter_parts() if part.get_param("name", header="content-disposition") == "file"]
                if len(files) != 1:
                    raise ValueError()
                filename = files[0].get_filename()
                data = files[0].get_payload(decode=True)
                if not filename or any(char in filename for char in "/\\\x00\r\n") or filename in (".", "..") or not data:
                    raise ValueError()
            except (ValueError, TypeError, AttributeError):
                return self.reply({"error": {"message": "Invalid multipart fixture"}}, 400)
            with state.lock:
                state.files[filename] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                state.event("upload_accepted", filename=filename)
            if mode == "malformed":
                return self.reply({"unexpected": "Response lost its confirmation"})
            return self.reply({"result": {"item": {"path": filename, "root": "gcodes"}, "action": "create_file"}})
        raw = self.body(4096)
        if raw is None:
            return
        with state.lock:
            if parsed.path == "/printer/print/start":
                filename = parse_qs(parsed.query).get("filename", [""])[0]
                if not state.connected or state.print_state not in ("standby", "complete", "error"):
                    return self.reply({"error": {"message": "Printer is busy or offline"}}, 409)
                if filename not in state.files:
                    return self.reply({"error": {"message": "File not uploaded"}}, 404)
                state.print_state = "printing"
                state.event("start", filename=filename)
            elif parsed.path == "/printer/print/pause" and state.print_state == "printing":
                state.print_state = "paused"
                state.event("pause")
            elif parsed.path == "/printer/print/resume" and state.print_state == "paused":
                state.print_state = "printing"
                state.event("resume")
            elif parsed.path == "/printer/print/cancel":
                state.print_state = "standby"
                state.event("cancel")
            else:
                return self.reply({"error": {"message": "Unsupported fixture operation"}}, 400)
        self.reply({"result": "ok"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18725)
    parser.add_argument("--allow-remote", action="store_true", help="Allow CI emulator access to this simulated server")
    args = parser.parse_args()
    if args.host not in ("127.0.0.1", "::1", "localhost") and not args.allow_remote:
        parser.error("Non-loopback binding requires --allow-remote")
    with Server((args.host, args.port)) as server:
        print(f"{MARKER}: listening on port {server.server_port}; no hardware connection", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
