# Client mod: own WebSocket codec instead of Netty 4.0.10's — 2026-10-23

## Decision rule (set before the measured runs)

Encoder (`ClientFrameEncoder`) is kept only if all hold:
- median messages per 1000 packets is at most a third of the base median;
- median event-loop CPU ns/packet is not above the base median;
- a lone packet still leaves in the event-loop task right after its write (`ClientFrameEncoderTest.lonePacketLeavesInTheTaskRightAfterItsWrite`).

Decoder (`ClientFrameDecoder`) is kept only if, in both decode views (codec only, and codec plus vanilla's splitter), the median event-loop CPU ns/byte and the median allocated bytes/byte are each at least 25% below the base median, and the candidate's min–max range does not overlap the base's for either metric.

If the decoder misses and the encoder meets its rule, only the encoder is kept.

Order: six JVMs, base/candidate/candidate/base/base/candidate (the starting variant alternates per pair), each with one warm-up phase and three measured phases per mode. Results are the median and [min–max] of the 9 measured phases per variant.
