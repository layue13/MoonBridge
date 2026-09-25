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

## Revalidation on the current greenfield branch

On 2026-09-25 at commit `7925b868afb72cad3d26a5b6a6861bc76648a940`, after the initial-placement cancellation fix, the installed distribution was tested again with:

```powershell
.\smoke\local-uranium-transfer.ps1 -BundlePath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle' -InstalledPlugin -PrismClient
```

The Uranium server JAR had SHA-256 `B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2`, matching the earlier run. The script reported `REAL_PRISM_URANIUM_TRANSFER_PASS dynamicRegistration=true status=NETWORK_READY holdSeconds=10` and `REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true`. The proxy log recorded `SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY`; both FML logs recorded handshake acknowledgements 2 through 5. The old backend logged the player in at 23:52:06 and disconnected at 23:52:07; the replacement logged in at 23:52:07 and stayed connected until the script stopped the client at 23:52:18. The script left no matching Prism 1.7.10 game process. Raw logs are in the ignored local `build/local-uranium-transfer-aa802d503ade43ac94aacbcdc4ac8189` directory.

This revalidation confirms the current branch still handles this minimal Forge transfer. The target modpack and real online authentication remain unverified.

## Revalidation after online Forge transfer coverage

On 2026-09-26 (Asia/Shanghai), commit `c909f6a0e2b2151dd36be765802427b03e540ab7` was rebuilt from source with `./gradlew.bat :proxy-core:installDist --rerun-tasks --no-daemon` and the installed distribution was exercised with:

```powershell
.\smoke\local-uranium-transfer.ps1 -BundlePath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle' -InstalledPlugin -PrismClient
```

The script reported `REAL_PRISM_URANIUM_TRANSFER_PASS dynamicRegistration=true status=NETWORK_READY holdSeconds=10` and `REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true`. The Uranium JAR SHA-256 was `B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2`. The proxy log recorded `SMOKE_PLUGIN_REGISTER_PASS` at 01:15:57 and `SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY` at 01:16:08. The old backend logged `PrismSmoke` in at 01:16:07 and disconnected at 01:16:08; the replacement logged the player in at 01:16:08, recorded `Server side modded connection established` in its FML log, and remained connected until the smoke script stopped the client at 01:16:19. No matching Prism game process remained after cleanup. Raw logs are in ignored local directory `build/local-uranium-transfer-a1560de67b4c477f8fd5bdd25399bdfe`.

This checks the current branch with one real minimal Forge client and two local Uranium backends. It does not cover the target modpack, real Mojang authentication, cross-host operation, or target traffic performance.

## Two-hop protocol probe; Prism launcher limitation

On 2026-09-26 (Asia/Shanghai), the installed distribution was exercised with a disposable plugin and the protocol probe:

```powershell
.\smoke\local-uranium-transfer.ps1 -BundlePath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle' -InstalledPlugin -ReturnToOld
```

The run reported `REAL_URANIUM_ROUNDTRIP_PASS resets=1 serverHellos=1 respawns=2 keepAlivesAfterReady=2`, `REAL_URANIUM_PLUGIN_ROUNDTRIP_PASS dynamicRegistration=true transfers=2 status=NETWORK_READY`, and `REAL_URANIUM_ROUNDTRIP_LOGS_PASS oldLogins=2 newLogin=true newDisconnected=true`. The proxy logged both `SMOKE_PLUGIN_TRANSFER_PASS` and `SMOKE_PLUGIN_RETURN_PASS`. The old backend logged two `NettyProbe` logins; both backends logged a completed FML handshake. Raw logs are in ignored local directory `build/local-uranium-transfer-354d12ea24e54f83bead8ee47f3f7e6e`.

The single-transfer installed-plugin probe also passed after these smoke-plugin changes, with `REAL_URANIUM_PLUGIN_TRANSFER_PASS` and backend login/disconnect markers. Its raw logs are in `build/local-uranium-transfer-0e15153ae1f74cfd8870fce0d58087af`.

Two attempts to run `-InstalledPlugin -PrismClient -ReturnToOld` did not start the Minecraft client. Prism Launcher reported `部分组件元数据加载失败` during component resolution, and neither proxy nor Uranium logged a client login. Those attempts do not establish whether a real Forge client can complete a round trip. Their raw proxy/backend logs are in `build/local-uranium-transfer-6f41df45b2ac499cb7e59d64e4bff96e` and `build/local-uranium-transfer-6a09156996a7448e8133b38d8e863dc2`. The smoke script now stops the Prism launcher process it starts, including after a failed launch.
