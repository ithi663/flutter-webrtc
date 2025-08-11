# Android Night Vision: GPU-only Refactor Plan

## Objectives
- Use GPU-only processing for night vision to maximize performance and stability.
- Keep viewer-side effect at renderers; do not alter the encoded stream.
- Preserve existing APIs; deprecate CPU (sender-side) path gracefully.

## Scope & Compatibility
- Platform: Android module only.
- Dart API unchanged. CPU track methods become no-ops with logs (Android).
- Allow enabling night vision on any renderer (remote or local preview).

## Summary
- Remove reliance on `NightVisionProcessor` (CPU) and use `NightVisionRenderer` (GLSL) via `FlutterRTCVideoRenderer` exclusively.
- Simplify method-channel logic to always toggle the GPU drawer for the target `FlutterRTCVideoRenderer`.
- Clean up unused sink-based paths and reduce logging in hot paths.

---

## Changes by Component

### android/src/main/java/com/cloudwebrtc/webrtc/MethodCallHandlerImpl.java
- videoRendererSetNightVision:
  - Remove `isRemote` guard; always call `renderer.enableNightVision(0.7f)` or `renderer.disableNightVision()`.
- videoRendererSetNightVisionIntensity:
  - Remove `isRemote` guard; always call `renderer.setNightVisionIntensity(intensity.floatValue())`.
- videoTrackSetNightVision (CPU path):
  - Replace with no-op + warning log: "CPU night vision disabled on Android; use renderer-based night vision".
  - If an existing `nightVisionProcessor` is attached:
    - Remove from `LocalVideoTrack.processors`.
    - Call `nightVisionProcessor.release()`.
    - Set `localVideoTrack.nightVisionProcessor = null`.
- videoTrackSetNightVisionIntensity (CPU path):
  - No-op + warning log.

### android/src/main/java/com/cloudwebrtc/webrtc/FlutterRTCVideoRenderer.java
- Ensure UI-thread execution for `enableNightVision(...)`, `disableNightVision(...)`, `setNightVisionIntensity(...)` (wrap or document precondition).
- Keep intensity updates uniform-only (no reinit).
- On dispose, call `disableNightVision()` before releasing the renderer.
- (If removing sink) delete unused `NightVisionVideoSink` field/imports.

### android/src/main/java/com/cloudwebrtc/webrtc/video/NightVisionRenderer.java
- No functional changes required.
- Optional: mark config fields `volatile` or ensure updates occur on GL thread.

### android/src/main/java/com/cloudwebrtc/webrtc/video/LocalVideoTrack.java
- No API changes.
- Ensure cleanup helper exists to remove a processor and release it when CPU path is disabled.

### android/src/main/java/com/cloudwebrtc/webrtc/video/NightVisionProcessor.java
- Keep for compatibility but no longer used by Android handler.
- Optional cleanup:
  - Remove unused `NightVisionRenderer` member and excessive logging.
  - Note: If retained for other platforms, mark as deprecated in comments.

### android/src/main/java/com/cloudwebrtc/webrtc/video/NightVisionVideoSink.java
- Remove file and references (optional cleanup) since drawer-based path supersedes it.

### Dart layer (lib/src/native/*.dart)
- Keep existing `setRemoteNightVision` and `setRemoteNightVisionIntensity`.
- Optionally add neutral names in future (e.g., `setNightVision`, `setNightVisionIntensity`). Document that Android ignores `isRemote`.

---

## Implementation Phases

### Phase 1 — Renderer GPU Path (Required)
- Update `MethodCallHandlerImpl` handlers for renderer methods (remove `isRemote` guard).
- Verify `FlutterRTCVideoRenderer` intensity changes avoid reinit; only uniforms update.

### Phase 2 — Deprecate CPU Path (Required)
- Convert `videoTrackSetNightVision` and `videoTrackSetNightVisionIntensity` into no-ops with warning logs.
- If a CPU processor was previously attached, remove and release it to avoid leaks.

### Phase 3 — Cleanup (Optional but Recommended)
- Remove `NightVisionVideoSink.java` and any references.
- Prune unused fields and heavy logs in `NightVisionProcessor.java` if kept.

### Phase 4 — Documentation & API Notes
- Update README/CHANGELOG:
  - Night vision is GPU-only on Android and viewer-side only (does not affect encoded stream).
  - CPU path is disabled on Android but methods are retained for compatibility.
- Note that enabling night vision on local preview is supported (Android ignores `isRemote`).

### Phase 5 — Stability & Performance Hardening
- Reduce logs in hot paths (`NightVisionRenderer`) to periodic stats only.
- Verify all renderer state changes occur on UI thread.
- If flicker on reinit is observed, consider pre-creating drawers or minimizing reinit calls.

### Phase 6 — Testing & Validation
- Automated:
  - Instrumentation harness to toggle enable/disable and sweep intensities; assert no GL errors.
- Manual:
  - Example that toggles and adjusts intensity on both remote renderer and local preview.
- Performance:
  - Compare FPS/frame times with/without effect using `NightVisionRenderer` stats on representative devices.

---

## Risks & Mitigations
- Drawer reinit hitch: minimize reinit; use uniform-only updates for intensity; avoid redundant enable calls.
- API behavior change: CPU path no-ops; mitigate with clear logs and docs; retain method signatures.

## Migration Notes for App Developers
- Use `setRemoteNightVision`/`setRemoteNightVisionIntensity` on any `RTCVideoRenderer` (Android ignores `isRemote`).
- Expect viewer-side enhancement only; the transmitted media is unchanged.
- CPU-based track methods are accepted but have no effect on Android.

## Acceptance Criteria
- Enabling/disabling night vision works for remote and local preview renderers.
- Intensity updates are smooth without GL errors or reinitialization.
- No CPU processing occurs on Android; CPU processor not created or attached.
- No regressions in rendering or leaks; logs document deprecation clearly.
