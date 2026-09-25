# Initial-login EOF repeat on local Uranium

Date: 2026-09-26 (Asia/Shanghai). Windows 11, local loopback, Java 25 proxy and Java 8 Uranium. The smoke starts two fresh Uranium instances, an installed proxy with the external transfer plugin, and a synthetic protocol-5 Forge client. Each run requests old → new → old and checks both backend login logs.

Command per run:

```powershell
.\smoke\local-uranium-transfer.ps1 -BundlePath <Uranium bundle> -InstalledPlugin -ReturnToOld
```

At `a09fc07`, three runs passed and the fourth failed while the probe waited for the **initial Login Success** (`EOFException` at `UraniumTransferProbe.java:72`). The proxy log confirmed that the plugin's placement callback saw `[old, new]`; the old Uranium log had no player login. The failure directory is `build/local-uranium-transfer-4a2cb3e895d14a07bee6cae884c65247` in this workspace. An earlier single run also failed at the same probe line, followed by two successful runs.

The next local build added failure-path DEBUG diagnostics to `Session` and an optional `-DebugSession` flag for this smoke. One validation run, six further runs with success-path DEBUG, and eight runs with only failure-path DEBUG all passed. The added diagnostics did not capture a failing session. These outcomes establish an intermittent initial-login failure in this environment; they do **not** identify a root cause or prove that the logging change fixed it.

If it recurs, run the smoke with `-DebugSession` and inspect `proxy.stdout.log` in the printed run directory for the selected backend, login deadline, channel closure, and any frame-handling exception. The target Forge modpack has not been tested.

Follow-up: a focused test demonstrated that `tcp://127.0.0.1` was submitted to the asynchronous backend resolver as an unresolved address: when the resolver deliberately held the literal IP, Login Success timed out. The connection helper now constructs a resolved socket address for numeric IPs and retains asynchronous resolution for hostnames. The focused test failed before this change and passed afterward; the full `check` and installed DNS/Agent smoke passed. Six fresh Uranium roundtrips with the default logging configuration also passed. This removes an unnecessary DNS dependency for numeric addresses. It does not establish that DNS resolution caused the two earlier EOFs, which remain unresolved until a failing run yields a closing reason.

Another focused test found that the login-stage deadline itself closed the client connection with raw EOF after Login Start. The proxy now sends a Login Disconnect packet with `Login timed out.` before closing when that deadline expires. The test failed with EOF before the change and passed after it. This fixes the timeout response, but it does not identify why the earlier intermittent initial login stopped progressing.
