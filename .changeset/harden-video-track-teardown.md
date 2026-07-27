---
"client-sdk-android": patch
---

Harden video track teardown against crashes on disconnect/unpublish. `LocalVideoTrack.dispose()` is now idempotent — repeat calls (e.g. Room cleanup on disconnect followed by release or app-side ownership) no longer throw `IllegalStateException` from `VideoSource.dispose()` — and it closes frame-delivery resources before freeing the native source, so an in-flight frame from the asynchronously-stopping capturer can no longer reach freed native memory. Stopping transceivers on unpublish no longer throws when a transceiver wrapper was invalidated by an intervening `getTransceivers()`/`getSenders()` call, and local participant cleanup now proceeds past individual track failures during disconnect. `startCapture()`/`stopCapture()` on a disposed track are ignored with a warning instead of acting on released resources.
