# Night Vision: Enable/Disable Guide
# Night Vision: Implementation Guide

This guide explains how to toggle the Night Vision effect for video rendering in the Flutter WebRTC plugin and adjust its intensity.

## Overview

- The Night Vision effect brightens shadows while preserving color.
- On Android, it uses a GPU shader drawer (`NightVisionRenderer`) integrated in `FlutterRTCVideoRenderer`.
- You control it from Dart using extension methods on `RTCVideoRenderer`.

Key files:
- `lib/rtc_video_renderer_extensions.dart` (exports platform extensions)
- `lib/src/native/rtc_video_renderer_extensions.dart` (Dart API - Android native path)
- `android/src/main/java/com/cloudwebrtc/webrtc/FlutterRTCVideoRenderer.java` (native toggle + intensity)
- `android/src/main/java/com/cloudwebrtc/webrtc/video/NightVisionRenderer.java` (GLSL shader implementation)

## Step-by-step Implementation

1) Create and initialize a renderer

```dart
import 'package:flutter_webrtc/flutter_webrtc.dart';

final _renderer = RTCVideoRenderer();
await _renderer.initialize();
```

2) Attach a track or stream and render it

```dart
// Example: attach a stream you obtained elsewhere
// _renderer.srcObject = mediaStream;

// In the UI
RTCVideoView(_renderer);
```

3) Enable Night Vision

```dart
await _renderer.setRemoteNightVision(true);
```

4) Adjust intensity (0.0 – 1.0)

```dart
await _renderer.setRemoteNightVisionIntensity(0.7);
```

5) Disable Night Vision

```dart
await _renderer.setRemoteNightVision(false);
```

6) Cleanup when done

```dart
await _renderer.dispose();
```

Notes:
- On Android, intensity updates do not reinitialize the drawer; uniforms are updated live.
- Calls must be made after the renderer is initialized (non-null `textureId`).

## Prerequisites

- Initialize your renderer and attach a video track.
- The renderer must have a non-null `textureId` (i.e., initialized and in use).

```dart
import 'package:flutter_webrtc/flutter_webrtc.dart';

final _renderer = RTCVideoRenderer();
await _renderer.initialize();
// Bind to a track, e.g., via RTCVideoView(_renderer)
```

## Quick Start

Enable Night Vision and set intensity:

```dart
// Turn ON Night Vision
await _renderer.setRemoteNightVision(true);

// Optional: adjust intensity (0.0 – 1.0). Try 0.6–0.8 for strong shadow lift.
await _renderer.setRemoteNightVisionIntensity(0.7);
```

Disable Night Vision:

```dart
await _renderer.setRemoteNightVision(false);
```

## API Reference (Dart)

- `RTCVideoRenderer.setRemoteNightVision(bool enabled)`
  - Enables/disables Night Vision for the given renderer.
  - Android: switches the GL drawer to/from `NightVisionRenderer`.

- `RTCVideoRenderer.setRemoteNightVisionIntensity(double intensity)`
  - Updates Night Vision strength in the range `0.0 .. 1.0`.
  - Can be called any time while Night Vision is enabled.

Both methods are exported by `flutter_webrtc.dart` via:
- `lib/rtc_video_renderer_extensions.dart` → `lib/src/native/rtc_video_renderer_extensions.dart`

## Recommended Settings

- Start with:
  - Intensity: `0.6 – 0.8`
  - Then tune per scene to avoid over-amplifying noise.

## Troubleshooting

- Error: `Cannot set night vision: RTCVideoRenderer not initialized`
  - Ensure you called `await renderer.initialize()` and that it’s bound to a view/track.

- No visible change after toggling
  - Confirm the renderer is actively displaying frames.
  - Ensure the call site targets the same `RTCVideoRenderer` instance used by your `RTCVideoView`.

- CPU track-level Night Vision
  - `MediaStreamTrack.setNightVision(...)` exists for completeness but is not used on Android (GPU path is preferred). Prefer the renderer-level API above.

## Native Mapping (Android)

- Method channel calls from Dart:
  - `videoRendererSetNightVision` → `FlutterRTCVideoRenderer.enableNightVision(...)` / `disableNightVision()`
  - `videoRendererSetNightVisionIntensity` → `FlutterRTCVideoRenderer.setNightVisionIntensity(...)`

See:
- `android/src/main/java/com/cloudwebrtc/webrtc/MethodCallHandlerImpl.java`
- `android/src/main/java/com/cloudwebrtc/webrtc/FlutterRTCVideoRenderer.java`

## Complete Example (Widget)

```dart
import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

class NightVisionViewer extends StatefulWidget {
  const NightVisionViewer({super.key});
  @override
  State<NightVisionViewer> createState() => _NightVisionViewerState();
}

class _NightVisionViewerState extends State<NightVisionViewer> {
  final _renderer = RTCVideoRenderer();
  bool _nightVision = false;
  double _intensity = 0.7; // 0.0 – 1.0

  @override
  void initState() {
    super.initState();
    () async {
      await _renderer.initialize();
      // TODO: attach your stream/track when available, e.g.:
      // _renderer.srcObject = myMediaStream;
      setState(() {});
    }();
  }

  Future<void> _applyNightVision() async {
    await _renderer.setRemoteNightVision(_nightVision);
    if (_nightVision) {
      await _renderer.setRemoteNightVisionIntensity(_intensity);
    }
  }

  @override
  void dispose() {
    _renderer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Expanded(child: RTCVideoView(_renderer)),
        SwitchListTile(
          title: const Text('Night Vision'),
          value: _nightVision,
          onChanged: (v) async {
            setState(() => _nightVision = v);
            await _applyNightVision();
          },
        ),
        if (_nightVision)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Intensity'),
                Slider(
                  value: _intensity,
                  min: 0.0,
                  max: 1.0,
                  onChanged: (v) async {
                    setState(() => _intensity = v);
                    // Updating intensity live while enabled
                    await _renderer.setRemoteNightVisionIntensity(_intensity);
                  },
                ),
              ],
            ),
          ),
      ],
    );
  }
}
```

## Notes

- Intensity outside `0.0 .. 1.0` will throw.
- Toggling does not require restarting the renderer.
- For best results, adjust intensity based on ambient lighting.
