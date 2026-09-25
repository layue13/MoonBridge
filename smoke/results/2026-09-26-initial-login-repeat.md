# Initial-login EOF repeat on local Uranium

Date: 2026-09-26 (Asia/Shanghai). Windows 11, local loopback, Java 25 proxy and Java 8 Uranium. The smoke starts two fresh Uranium instances, an installed proxy with the external transfer plugin, and a synthetic protocol-5 Forge client. Each run requests old → new → old and checks both backend login logs.

Command per run:

```powershell
.\smoke\local-uranium-transfer.ps1 -BundlePath <Uranium bundle> -InstalledPlugin -ReturnToOld
```

At `a09fc07`, three runs passed and the fourth failed while the probe waited for the **initial Login Success** (`EOFException` at `UraniumTransferProbe.java:72`). The proxy log confirmed that the plugin's placement callback saw `[old, new]`; the old Uranium log had no player login. The failure directory is `build/local-uranium-transfer-4a2cb3e895d14a07bee6cae884c65247` in this workspace. An earlier single run also failed at the same probe line, followed by two successful runs.

The next local build added failure-path DEBUG diagnostics to `Session` and an optional `-DebugSession` flag for this smoke. One validation run, six further runs with success-path DEBUG, and eight runs with only failure-path DEBUG all passed. The added diagnostics did not capture a failing session. These outcomes establish an intermittent initial-login failure in this environment; they do **not** identify a root cause or prove that the logging change fixed it.

If it recurs, run the smoke with `-DebugSession` and inspect `proxy.stdout.log` in the printed run directory for the selected backend, login deadline, channel closure, and any frame-handling exception. The target Forge modpack has not been tested.
