# Forge transfer between two local Uranium 1.7.10 backends

Run date: 2026-09-25 (Asia/Shanghai)

- StrataProxy source revision before adding this smoke: `70e12b17641a2b77e9022a796c82bbd86ead10fb`.
- Backend bundle: `rfg-netty-compat-bundle/Uranium-1710-rfg-bridge-d7a68f934f-dirty-server.jar`, SHA-256 `B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2`.
- Both Uranium processes ran on Zulu OpenJDK 8u492. The transfer probe and `ProxySessionListener` ran on Zulu OpenJDK 25.0.4.1.
- All listeners were bound to loopback. Uranium was configured for offline login; the source bundle already contained `eula=true`.
- Direct mode used two static registrations with capacity 10 and called `ProxySessionListener.transfer`. Installed-plugin mode used one static `old` registration; a disposable external plugin registered `new` via `Servers.register`, checked its metadata in the `ServerView` placement snapshot, selected `old`, and requested `new` via public `Players.transfer`.

From this repository with PowerShell 7 and an installed StrataProxy distribution:

```powershell
.\gradlew.bat :proxy-core:installDist
.\smoke\local-uranium-transfer.ps1 `
  -BundlePath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle'
.\smoke\local-uranium-transfer.ps1 `
  -BundlePath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle' `
  -InstalledPlugin
```

Successful direct run after adding backend-log assertions and the installed-plugin mode:

```text
REAL_URANIUM_TRANSFER_PASS mode=direct reset=1 serverHellos=1 respawns=2 keepAlivesAfterReady=2
REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true
```

Installed-plugin run:

```text
REAL_URANIUM_TRANSFER_PASS mode=plugin reset=1 serverHellos=1 respawns=2 keepAlivesAfterReady=2
REAL_URANIUM_PLUGIN_TRANSFER_PASS dynamicRegistration=true status=NETWORK_READY
REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true
```

The client received the initial Forge ServerHello and Join Game from the old backend, then one FML reset, one replacement ServerHello, two Respawns, and two Keep Alive requests after the transfer completed with `NETWORK_READY`. In direct mode the proxy's player view named `new` as the current server. In installed-plugin mode Logback recorded `SMOKE_PLUGIN_REGISTER_PASS name=new` and `SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY`. Both backend logs recorded the player's login, and the old log recorded its disconnect. Raw logs were left in the ignored local `build/local-uranium-transfer-d443101d9d0746c7a5df6e4106f64bee` and `build/local-uranium-transfer-ddf20156f3db4e80baf5fe4b4db62aed` directories, respectively.

This verifies a real minimal Uranium-to-Uranium Forge network transition, including static and plugin-owned dynamic registrations in one directory and the installed plugin API path, using a synthetic protocol-5 client. It does not verify actual Forge client registry handling, target modpack behavior, online Mojang authentication, cross-host deployment, or production performance.
