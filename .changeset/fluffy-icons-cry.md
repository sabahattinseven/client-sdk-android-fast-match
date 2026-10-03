---
"client-sdk-android": patch
---

Update libwebrtc to 144.7559.15. Relative to 144.7559.09 this brings the webrtc-sdk fixes for the network-thread SIGABRT when `getStats` runs against a stopped transceiver (webrtc-sdk/webrtc#262), the `AudioRecord` release-under-live-reader abort on rapid capture stop/start cycles (webrtc-sdk/webrtc#266), and `prewarmRecording()`/`requestStartRecording()` no longer throwing an `AssertionError` after a failed `AudioRecord` init (webrtc-sdk/webrtc#288, 144.7559.15 only). Upstream LiveKit pins 144.7559.14; 144.7559.15 adds #288 plus three iOS/macOS-only backports.
