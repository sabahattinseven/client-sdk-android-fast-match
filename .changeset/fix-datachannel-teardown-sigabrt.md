---
"client-sdk-android": patch
---

Fix native SIGABRT on the webrtc network thread during disconnect (`libc++ Hardening assertion: optional operator-> called on a disengaged value`). Peer connections were disposed before the data channels, so SCTP transport teardown fired state/buffered-amount callbacks into still-registered observers whose calls back into the mid-teardown native channel dereferenced a disengaged optional — turned from silent UB into an abort by the hardened libc++ in webrtc-sdk 144.7559.09. Data channels are now torn down (observer unregistered, closed, disposed) before the peer connections, and `DataChannelManager` callbacks no longer touch the native channel once disposal has begun. Affects both `disconnect()` and `switchRoom()`, and is most easily triggered by data-channel traffic shortly before teardown.
