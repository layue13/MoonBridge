# Real Prism Forge client through StrataProxy

Run date: 2026-09-25 (Asia/Shanghai). Proxy revision under test: `562e00be8e176784e458f0b99b5815a15a1b1212` (the successful run used the same code before committing). Windows 11; proxy JVM: Zulu 25.0.4.1; Uranium JVM: Zulu 8u492. All listeners used loopback and offline authentication.

- Client: a local Prism Launcher instance named `1.7.10`, with Minecraft 1.7.10, Forge 10.13.4.1614, LWJGL 2.9.4-nightly-20150209, and no additional modpack mods. The server's FML log identified the joining client as FML 7.10.99.99, Forge 10.13.4.1614, and MCP 9.05.
- Backend: two copies of `Uranium-1710-rfg-bridge-d7a68f934f-dirty-server.jar`, SHA-256 `B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2`.
- Registration: `old` came from static configuration; the installed smoke plugin dynamically registered `new` through `Servers.register` and requested transfer through `Players.transfer`.

From this repository after `.\gradlew.bat :proxy-core:installDist`:

```powershell
.\smoke\local-uranium.ps1 -BundlePath <Uranium bundle directory> -PrismClient
.\smoke\local-uranium-transfer.ps1 -BundlePath <Uranium bundle directory> -InstalledPlugin -PrismClient
```

The first command reported `REAL_PRISM_URANIUM_PASS` and held the client for 10 seconds after Uranium logged in `PrismSmoke`. The FML server log reported the modded connection established. Raw logs are in the ignored local `build/local-uranium-proxy-574d57f92eb64b6e887a231cbc73ce45` directory.

An initial transfer attempt on `24e6de0` failed: the replacement Uranium received `C06PacketPlayerPosLook` while the Forge transfer was still negotiating and disconnected the player after a server-side exception. This exposed that buffered old-world client movement was forwarded to the replacement too early. The fix allows only Forge control traffic and translated Keep Alive replies toward the replacement until Forge handshake and world transition are ready. The synthetic transfer regression now injects an old-world movement frame before the client handshake response and verifies that it is dropped; it also verifies that new-world movement is forwarded after `NETWORK_READY`.

After the fix, the transfer script reported:

```text
REAL_PRISM_URANIUM_TRANSFER_PASS dynamicRegistration=true status=NETWORK_READY holdSeconds=10
REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true
```

The proxy logged dynamic registration and `SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY` at 21:30:56. The old Uranium logged the player's login and disconnect at 21:30:55; the target logged the player's login at 21:30:56, and its FML log recorded handshake phases 2 through 5 and a modded connection established. The target remained connected for the script's 10-second hold. The target's later socket close at 21:31:06 was caused by the script stopping the test client. Raw logs are in the ignored local `build/local-uranium-transfer-af03f573f37046fca8718737cea03e69` directory.

After the smoke scripts gained `-PrismInstance` and instance-specific process cleanup, the default `1.7.10` transfer command passed again with the same two success markers. That run's raw logs are in `build/local-uranium-transfer-8f5c72896a7b4942bb05ad01cdf7224c`; no matching Prism client process remained after script cleanup.

The final parameterized first-login script was rerun with the default instance and reported `REAL_PRISM_URANIUM_PASS` with a 10-second hold; its raw logs are in `build/local-uranium-proxy-97465608db9a419eb1328b3f4bbbfd4b`. Cleanup left no matching client process. For a Prism instance whose displayed launch name differs from its directory name, pass both `-PrismInstance` and `-PrismInstanceFolder` so the smoke scripts can identify the game process precisely.

This verifies one real, minimal Forge client transferring between local Uranium backends through the installed plugin API. It does not verify the target modpack, mod-specific plugin channels, Mojang online authentication, cross-host deployment, or production capacity and latency.
