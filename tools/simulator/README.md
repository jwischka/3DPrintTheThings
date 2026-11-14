# U1 simulator

Run `python3 tools/simulator/u1_simulator.py` and point a development client at `http://127.0.0.1:18725` with API key `simulator-test-key`. The server stores only simulated file hashes and status in memory. It has no connection to printer hardware and does not interpret G-code.

`GET /__sim/info` identifies the fixture. Control requests require `X-Simulator-Control: simulator-control`: `POST /__sim/reset` resets state, `POST /__sim/configure` accepts `state`, `connected`, four Boolean `loaded` slots, `progress`, and `upload_mode` (`ok`, `reject`, `disconnect`, `malformed`, or `slow`). `GET /__sim/state` returns simulated events and stored file summaries. These fixed keys belong only to the fixture.

The default bind address is loopback. Linux CI uses `--host 0.0.0.0 --allow-remote` so its disposable emulator can reach the host at `10.0.2.2:18725`. Android tests verify the simulator marker before sending any request that changes state. They never use the owner's printer address.

Run server tests with `python3 -m unittest discover -s tools/simulator -v`. Android instrumentation exercises the real Moonraker client against this server, including upload confirmation, cancellation, failure handling and reconnect behavior. It supplements the in-process fake-transport tests; it does not model firmware timing, heaters, motion, WebSockets or real printing.
