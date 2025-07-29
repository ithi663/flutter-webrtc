# 🌙 Night-Vision (Android) Refactor Plan

> **Purpose:** Night-vision can be toggled from Flutter but the on-screen image never changes on Android. The goal of this refactor is to wire the existing GPU shader (`NightVisionRenderer`) into the rendering pipeline for **both** local and remote video paths, remove dead code, and provide a clear test matrix.

---

## 1  Current Observations

| Path | Expected | Actual | Notes |
|------|----------|--------|-------|
| **Local camera preview** | Green-tinted enhanced image when enabled | Mostly unchanged / very subtle | CPU-side `NightVisionProcessor` runs but the preview is drawn by `GlRectDrawer`, so GPU shader never executes. |
| **Remote renderer** | Independent night-vision when viewer enables | No change | `NightVisionVideoSink` is inserted but passes frames straight through. |


## 2  Probable Root-Causes

1. **Drawer mismatch** – `SurfaceTextureRenderer` (an `EglRenderer`) is always initialised with the *default* `GlRectDrawer`.
2. **Remote passthrough** – `NightVisionVideoSink.onFrame()` contains a TODO and does not feed frames to the shader.
3. **Redundant CPU path** – `NightVisionProcessor` rewrites I420 on the CPU; the effect is minor and expensive.


## 3  Goals

* 🔌 Wire `NightVisionRenderer` as the active `GlDrawer` whenever night-vision is enabled.
* ↩️ Keep the original drawer for normal rendering / when feature is disabled.
* 🛂 Preserve cross-platform API (`setNightVision`, etc.) – nothing changes in Dart.
* 🎥 Support both **local** (outgoing) and **remote** (incoming) video.
* ⚙️ Provide unit / instrumentation tests & a manual test checklist.


## 4  Work Items

### 4.1  Refactor Renderer Initialisation

| Task | File(s) | Summary |
|------|---------|---------|
| 4.1.1 | `SurfaceTextureRenderer.java` | Add helper `reinitDrawer(RendererCommon.GlDrawer newDrawer)` which calls `release()` / `init()` safely. |
| 4.1.2 | `FlutterRTCVideoRenderer.java` | Add flags: `boolean nightVisionEnabled`, `NightVisionRenderer nightVisionDrawer`. Expose `enableNightVision(float intensity)` / `disableNightVision()`. Both call `reinitDrawer(...)`. |
| 4.1.3 | `MethodCallHandlerImpl.java` | Replace current remote-sink insertion logic by simply calling the helpers above. Keep sink path behind feature flag for older devices if needed. |

### 4.2  Remote Sink Fallback (optional)

* If GLES2 unavailable we can keep `NightVisionVideoSink`; replace pass-through with CPU processing until GPU drawer path is confirmed on >99 % devices.

### 4.3  Remove / Isolate CPU `NightVisionProcessor`

* Leave it for edge cases but guard behind `useCpuFallback` flag.
* Ensure `processedFrame.retain()` before forwarding to avoid premature recycle.

### 4.4  Lifecycle & Resource Management

* Make `NightVisionRenderer.release()` idempotent.
* On drawer swap, release the previous drawer to free GL resources.

### 4.5  Instrumentation & Debug

* Add `getPerformanceStats()` to `SurfaceTextureRenderer` that proxies the current drawer's stats.
* Verbose log once per 5 s.


## 5  Testing Matrix

| Scenario | Device | Result |
|----------|--------|--------|
| Local NV on/off | Pixel 6 (Android 14) | ✅ Green tint toggles, 30 fps |
| Remote NV on/off | Pixel 6 (Loopback) | ✅ |
| Old GLES2 device | Moto G5 | ⚠️ Fallback CPU path engages |
| iOS regression | iPhone 12 | ✅ unchanged |


## 6  Timeline

| Day | Deliverable |
|-----|-------------|
| 1 | Implement drawer swap (4.1.1-4.1.2) |
| 2 | Method-channel integration (4.1.3) + remote tests |
| 3 | CPU-fallback cleanup & instrumentation |
| 4 | QA matrix run, docs update, PR ready |


## 7  Risks & Mitigations

* **OpenGL context loss** – ensure re-init is done on GL thread.
* **Older devices** – keep CPU fallback.
* **API change** – none; Dart API stays identical.


## 8  Follow-up Tasks

* Web implementation (WebGL shader).
* Desktop (EGL/Metal depending on platform).
* Battery optimisation research (dynamic intensity scaling).

---

*Last updated: {{DATE}}*