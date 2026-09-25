# Forge transfer between two local Uranium 1.7.10 backends

Run date: 2026-09-25 (Asia/Shanghai)

- StrataProxy source revision before adding this smoke: `70e12b17641a2b77e9022a796c82bbd86ead10fb`.
- Backend bundle: `rfg-netty-compat-bundle/Uranium-1710-rfg-bridge-d7a68f934f-dirty-server.jar`, SHA-256 `B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2`.
- Both Uranium processes ran on Zulu OpenJDK 8u492. The transfer probe and `ProxySessionListener` ran on Zulu OpenJDK 25.0.4.1.
- All listeners were bound to loopback. Uranium was configured for offline login; the source bundle already contained `eula=true`.
- The proxy used two static registrations with capacity 10. The Java probe called the actual `ProxySessionListener.transfer` method. It did not load a plugin or run the installed `ProxyMain` launcher.

From this repository with PowerShell 7 and an installed StrataProxy distribution:

```powershell
.\gradlew.bat :proxy-core:installDist
.\smoke\local-uranium-transfer.ps1 `
  -BundlePath 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle'
```

Successful repeated run after adding backend-log assertions:

```text
REAL_URANIUM_TRANSFER_PASS reset=1 serverHellos=1 respawns=2 keepAlivesAfterReady=2 oldPort=50439 newPort=50440
REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true
```

The client received the initial Forge ServerHello and Join Game from the old backend, then one FML reset, one replacement ServerHello, two Respawns, and two Keep Alive requests after the transfer completed with `NETWORK_READY`. The proxy's player view named `new` as the current server. The old Uranium log recorded the player's login and disconnect; the new log recorded the player's login. Raw logs were left in the ignored local `build/local-uranium-transfer-f09bcaef729a4568872d2f4a591293e7` directory.

This verifies a real minimal Uranium-to-Uranium Forge network transition using a synthetic protocol-5 client. It does not verify actual Forge client registry handling, target modpack behavior, online Mojang authentication, plugin-driven transfer, cross-host deployment, or production performance.
