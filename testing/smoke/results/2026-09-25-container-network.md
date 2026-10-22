# Container network smoke test

Run date: 2026-09-25 (Asia/Shanghai). Repository revision: `9c35ec92d246417e4d75bb548b3553b96cb38815`. Host: Windows 11 with Docker Desktop Linux. The proxy ran in `eclipse-temurin:25-jdk` (`sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2`), and a synthetic protocol-5 backend and client each ran in a separate Python 3.12.3 container (`docker.gitea.com/runner-images:ubuntu-latest`, `sha256:fd911d7417bfbf0f454530e447da95b58001e1df41bbc5e1a8dd35d432575aae`). All three used one temporary Docker bridge network. The backend was reached through Docker container DNS and TCP, not through host loopback. The script used the installed proxy distribution and ran `:proxy-core:installDist` before starting containers.

Command: `./smoke/container-network.ps1` from the repository root in PowerShell 7. The run completed with exit code 0:

```text
CLIENT_OK mode=static backend=sp-backend-500fc603f7 login=1 play_probes=1
static: cross-container DNS/TCP login and PLAY probe passed
CLIENT_OK mode=dns backend=sp-backend-500fc603f7 login=1 play_probes=1
dns: cross-container DNS/TCP login and PLAY probe passed
CLIENT_OK mode=agent backend=sp-backend-500fc603f7 login=1 play_probes=2
agent: cross-container DNS/TCP login and PLAY probe passed
```

In static mode, the configured backend address was the other container's DNS name. In DNS mode, the discovery plugin registered that name's resolved address. In Agent mode, a separate client container registered `tcp://<backend-container>:25565` over the private bridge using signed HTTP; the proxy connected to it, and an existing player completed another PLAY roundtrip after Agent unregister. The Agent client also checked that the status maximum fell to one while that session remained connected. The script removed its containers, bridge network, and temporary config files; an explicit post-run inspection found none remaining.

The backend sends a minimal synthetic Join Game and Position and Look marker, then echoes two-byte PLAY frames. This test checks discovery, registration, backend name resolution, offline login and forwarding across container network namespaces. It does not represent a physical second host, a real Forge handshake, Mojang online authentication, target modpack traffic or production latency/capacity.
