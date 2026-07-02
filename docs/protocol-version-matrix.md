# Protocol Version Matrix

This matrix is the compatibility contract for Minecraft protocol-specific behavior in StrataProxy. It must be updated together with `MinecraftProtocolProfile` whenever a new client/server version is added.

Status meanings:

- `supported`: implemented in `MinecraftProtocolProfile` and covered by focused tests.
- `partial`: selected behavior exists, but the version is not production-compatible as a full multi-version profile.
- `not supported`: do not advertise stable compatibility. Add a profile and tests first.

## Current Runtime Matrix

| Minecraft Java version | Protocol | Status | Login compression | Serverbound command packets | Custom payload format | Backend switch | Legacy Forge/FML |
| --- | ---: | --- | --- | --- | --- | --- | --- |
| 1.7.10 | 5 | supported for targeted local/legacy switching work | no vanilla login compression | `0x01` chat message | serverbound unsigned-short length, clientbound varshort length | respawn switch; Forge-safe Join Game + Respawn path | partial: REGISTER tracking, reset injection, handshake observation/gates, queued non-FML payloads, plugin-channel replay |
| 1.8.x | 47 | supported for targeted compression/legacy switching work | yes, Login Set Compression `0x03` | `0x01` chat message | remaining bytes in both directions | compressed/uncompressed respawn switch; Forge-safe Join Game + Respawn path | partial: compressed handshake observation/gates, compressed race suppression, compressed REGISTER replay |
| 1.20.1 | 763 | partial | yes | `0x03`, `0x04`, `0x05` | serverbound modern custom payload support only where explicit samplers exist | not supported by legacy backend replacement path | no legacy Forge/FML state machine |
| Other 1.9+ versions | varies | not supported | do not infer from version number alone | not defined | not defined | not supported | not supported |
| Current/latest Java releases | varies | not supported until profiled | must be read from the target version's protocol data | must be profiled | must be profiled | must be profiled | modern mod loaders need separate state machines |

Important compression rule: protocol 5 must not negotiate vanilla login compression. Some legacy proxies have a play-state 1.7.x compression packet, but StrataProxy does not treat that as normal login compression.

## Required Profile Fields

A version is not compatibility-supported until these fields are known and tested:

| Field | Why it matters |
| --- | --- |
| protocol number | Route filtering and status response selection |
| login packet ids | Login success, disconnect, encryption request, Set Compression |
| compression framing | Whether packet payloads are compressed after login negotiation |
| serverbound command packet ids | Proxy commands such as `/server` must be intercepted without forwarding to the backend |
| custom payload packet ids | Forge/Fabric/Bungee/Velocity plugin messages depend on this |
| custom payload length format | 1.7.10 uses legacy length encodings; 1.8 differs; modern versions use namespaced payloads |
| Join Game and Respawn packet ids/layouts | Backend switching depends on converting the first backend Join Game safely |
| player list, scoreboard, team clear packets | Safe server switching needs client state cleanup |
| Forge/mod-loader handshake phases | Large modded server switching needs reset, registry, and queued payload behavior |
| forwarding/login plugin behavior | Velocity modern and Bungee-style identity forwarding are version-sensitive |

## Test Gates Per Version

| Gate | 1.7.10 | 1.8.x | 1.20.1 | New versions |
| --- | --- | --- | --- | --- |
| command interception | covered | covered through shared legacy profile paths | covered | required |
| backend switch over real TCP | covered for protocol 5 `/server` | unit covered for compressed switch | not covered | required before claiming switch support |
| login compression | explicitly disabled | covered | covered in existing modern paths | required |
| custom payload parsing | covered | covered compressed/uncompressed | partial | required |
| Forge handshake tracking | covered | covered compressed/uncompressed | not applicable | Forge/Fabric-specific design required |
| Forge race suppression | covered | covered compressed/uncompressed | not applicable | required where legacy Forge applies |
| plugin channel REGISTER replay | covered | covered compressed/uncompressed | not applicable | required where plugin channels are replayed |
| real modded server acceptance | not yet proven | not yet proven | not applicable | required for production claims |

## Implementation Rule

Do not add packet ids directly inside relay handlers for a new version. Add or extend a profile first, then wire the relay/controller logic through profile capabilities. If a behavior cannot be expressed through `MinecraftProtocolProfile`, that is a signal to add a dedicated state-machine abstraction rather than another version check in the relay.

## Roadmap

1. Extract explicit `ConnectionStateMachine`: login, compression, play, and mod-loader phases.
2. Extract `TransferOrchestrator`: server switch lifecycle, initial clientbound frames, rollback, and metrics.
3. Keep `MinecraftProtocolProfile` as data: packet ids, formats, layouts, and capability flags only.
4. Add version profiles in groups, with tests before advertising support:
   - 1.9-1.12.2 legacy Forge era.
   - 1.13-1.16.5 flattening/namespaced payload transition.
   - 1.17-1.18.2 modern network changes.
   - 1.19-1.20.6 signed chat and login/configuration transition.
   - 1.21+ and 26.x current releases.

## References

- Minecraft Wiki protocol version data documents that each build exposes `protocol_version` in `version.json` from the client/server jar for modern builds.
- Mojang announced that Java Edition version numbers move to year-based `26.x` style starting in 2026; version names and protocol numbers must therefore be treated as separate data.
