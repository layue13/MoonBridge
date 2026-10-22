# Minimal Uranium 1.7.10 backend through StrataProxy

Run date: 2026-09-25 (Asia/Shanghai)

- StrataProxy source revision before this smoke script: `446ff76da0214db872d9df6be34419f2e6c3f16d`.
- Backend: local `rfg-netty-compat-bundle/Uranium-1710-rfg-bridge-d7a68f934f-dirty-server.jar`, SHA-256 `B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2`.
- Backend JVM: Zulu OpenJDK 8u492. Proxy and protocol probe JVM: Zulu OpenJDK 25.0.4.1.
- Both listeners bound to `127.0.0.1`; proxy used `OFFLINE` authentication and one static backend. The copied server bundle already had `eula=true`.
- Probe: the compiled `cc.uraniummc.rfg.smoke.MinecraftProtocolProbe` from the local Uranium RFG test sources. It is a synthetic Java protocol client, not a Forge game client.

From this repository with PowerShell 7 and an installed StrataProxy distribution:

```powershell
.\gradlew.bat :proxy-core:installDist
.\smoke\local-uranium.ps1 `
  -BundlePath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle' `
  -ProbeClassesPath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\classes\java\test'
```

Observed successful run:

```text
NETTY_PERSISTENT_CONNECTION_PASS keepAlives=17
NETTY_PROTOCOL_PASS status=2 ping=2 fragmented=true FML_login=true JoinGame=true
REAL_URANIUM_PROXY_PASS serverPort=62416 proxyPort=62417
```

The Uranium log reported `Done (2.721s)`, `NettyProbe ... logged in with entity id 555`, then a normal disconnect after approximately 35 seconds. StrataProxy logged that it was listening on `127.0.0.1:62417`; its run log had no errors. Raw logs were left in the ignored local `build/local-uranium-proxy-bd851140d1b44ee29f9216a9d608f91f` directory.

This verifies protocol 5 status and ping (including a fragmented request), offline FML login through the proxy, receipt of Join Game, and a persistent PLAY connection responding to 17 backend Keep Alive requests against this minimal Uranium bundle. It does not verify a real Forge client, target modpack compatibility, online Mojang authentication, backend transfer, cross-host deployment, or production performance.
