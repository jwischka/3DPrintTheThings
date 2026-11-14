import hashlib
import json
import threading
import unittest
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from http.client import HTTPConnection, RemoteDisconnected
from http.client import IncompleteRead

from u1_simulator import API_KEY, CONTROL_KEY, FIXTURE_SHA256, MARKER, MAX_UPLOAD, Server


class SimulatorTest(unittest.TestCase):
    def setUp(self):
        self.server = Server(("127.0.0.1", 0))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def request(self, path, data=None, headers=None):
        request = Request(self.base + path, data=data,
                          headers=headers if headers is not None else {"X-Api-Key": API_KEY})
        try:
            with urlopen(request, timeout=3) as reply:
                return reply.status, json.load(reply)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def configure(self, **settings):
        return self.request("/__sim/configure", json.dumps(settings).encode(),
                            {"X-Simulator-Control": CONTROL_KEY})

    def upload(self, filename="fixture.gcode", contents=b"G90\nM82\nG1 X1 E1\n"):
        body = (f'--boundary\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                'Content-Type: application/octet-stream\r\n\r\n').encode() + contents + b"\r\n--boundary--\r\n"
        return self.request("/server/files/upload", body,
                            {"X-Api-Key": API_KEY, "Content-Type": "multipart/form-data; boundary=boundary"})

    def test_identity_and_authentication(self):
        self.assertEqual(MARKER, self.request("/__sim/info", headers={})[1]["simulator"])
        self.assertEqual(401, self.request("/server/info", headers={})[0])
        self.assertEqual(401, self.request("/__sim/configure", b"{}", headers={})[0])
        self.assertTrue(self.request("/server/info")[1]["result"]["klippy_connected"])

    def test_pinned_model_fixture_is_bounded_and_requires_control_header(self):
        path = "/__sim/fixture/cube_10mm.stl"
        self.assertEqual(401, self.request(path, headers={})[0])
        with urlopen(Request(self.base + path, headers={"X-Simulator-Control": CONTROL_KEY}), timeout=3) as reply:
            content = reply.read()
            self.assertEqual("model/stl", reply.headers["Content-Type"])
            self.assertEqual(len(content), int(reply.headers["Content-Length"]))
        self.assertEqual(FIXTURE_SHA256, hashlib.sha256(content).hexdigest())
        self.assertLessEqual(len(content), 1024 * 1024)
        self.assertEqual(404, self.request("/__sim/fixture/missing.stl",
                                           headers={"X-Simulator-Control": CONTROL_KEY})[0])

    def test_truncated_model_fixture_advertises_full_size_then_closes(self):
        path = "/__sim/fixture/truncated-cube_10mm.stl"
        with urlopen(Request(self.base + path, headers={"X-Simulator-Control": CONTROL_KEY}), timeout=3) as reply:
            advertised = int(reply.headers["Content-Length"])
            with self.assertRaises(IncompleteRead) as failure:
                reply.read()
        self.assertEqual(advertised // 2, len(failure.exception.partial))
        self.assertGreater(advertised, len(failure.exception.partial))

    def test_upload_then_simulated_print_controls(self):
        self.assertEqual(404, self.request("/printer/print/start?filename=fixture.gcode", b"")[0])
        self.assertEqual(200, self.upload()[0])
        file = self.server.state.snapshot()["files"]["fixture.gcode"]
        self.assertEqual(hashlib.sha256(b"G90\nM82\nG1 X1 E1\n").hexdigest(), file["sha256"])
        for operation, expected in (("start?filename=fixture.gcode", "printing"),
                                    ("pause", "paused"), ("resume", "printing"), ("cancel", "standby")):
            self.assertEqual(200, self.request("/printer/print/" + operation, b"")[0])
            self.assertEqual(expected, self.server.state.snapshot()["state"])

    def test_busy_and_disconnected_start_are_rejected(self):
        self.upload()
        self.configure(state="paused")
        self.assertEqual(409, self.request("/printer/print/start?filename=fixture.gcode", b"")[0])
        self.configure(state="standby", connected=False)
        self.assertEqual(409, self.request("/printer/print/start?filename=fixture.gcode", b"")[0])
        self.assertFalse(any(event["kind"] == "start" for event in self.server.state.snapshot()["events"]))

    def test_boolean_spools_and_reconnection(self):
        self.configure(loaded=[False, True, False, True], connected=False)
        slots = self.request("/printer/objects/query?print_task_config&webhooks")[1]["result"]["status"]
        self.assertEqual([False, True, False, True], slots["print_task_config"]["filament_exist"])
        self.assertEqual("shutdown", slots["webhooks"]["state"])
        self.configure(connected=True)
        self.assertEqual("ready", self.request("/server/info")[1]["result"]["klippy_state"])

    def test_rejected_and_disconnected_uploads_never_create_files(self):
        self.configure(upload_mode="reject")
        self.assertEqual(507, self.upload()[0])
        self.configure(upload_mode="disconnect")
        with self.assertRaises((RemoteDisconnected, ConnectionResetError, URLError)):
            self.upload()
        self.assertEqual({}, self.server.state.snapshot()["files"])

    def test_uncertain_upload_can_exist_without_confirmation(self):
        self.configure(upload_mode="malformed")
        status, reply = self.upload()
        self.assertEqual(200, status)
        self.assertNotIn("result", reply)
        self.assertIn("fixture.gcode", self.server.state.snapshot()["files"])
        self.assertEqual("standby", self.server.state.snapshot()["state"])

    def test_bounds_validation_and_no_gcode_execution_endpoint(self):
        self.assertEqual(400, self.configure(loaded=[1, 1, 1, 0])[0])
        self.assertEqual(400, self.configure(progress=2)[0])
        self.assertEqual(400, self.upload(filename="../unsafe.gcode")[0])
        self.assertEqual(400, self.request("/printer/gcode/script", b'{"script":"G28"}')[0])
        # Read the early 413 before sending bytes the fixture must reject.
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.putrequest("POST", "/server/files/upload")
            connection.putheader("X-Api-Key", API_KEY)
            connection.putheader("Content-Length", str(MAX_UPLOAD + 1))
            connection.endheaders()
            response = connection.getresponse()
            self.assertEqual(413, response.status)
            response.read()
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
