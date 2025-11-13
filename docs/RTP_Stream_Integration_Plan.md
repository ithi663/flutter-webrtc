# RTP Stream Integration Plan

This plan covers two ways to use an RTP/RTSP video stream in the app:

- Remote track (receive-only) for local playback
- Local track (publishable) behaving like the camera: can be sent and locally previewed

The plan focuses on iOS first (Flutter plugin native code), with reusable patterns for other platforms.


## 1) Remote Track (receive-only, local playback)

Goal: Render an RTP/RTSP camera stream as a WebRTC remote track and play it locally. No upstream sending.

### High-level approaches
- Gateway approach (recommended)
  - Use a server gateway that ingests RTSP/RTP and re-publishes as WebRTC (WHEP, WebRTC SFU, or custom).
  - The app connects via standard WebRTC receive-only transceiver and renders the remote track with existing renderers.
- In-app gateway (advanced)
  - Embed GStreamer with `webrtcbin` to negotiate WebRTC and inject decoded RTP into `webrtcbin` as a remote track. This is complex and increases app footprint; consider only if a serverless solution is mandatory.

### Architecture (Gateway)
- RTSP IP camera → Gateway (RTSP in, WebRTC out) → App RTCPeerConnection (recvonly) → Remote `RTCVideoTrack` → Flutter renderer

### iOS/Flutter changes (Gateway)
- No changes to the plugin core for rendering remote tracks (already supported).
- App code establishes a PC and adds a recvonly transceiver:
  - Create peer connection with ICE servers.
  - Add video transceiver with `direction = recvonly`.
  - Perform signaling with gateway (WHIP/WHEP/Custom) to get a remote track.
  - Attach remote `RTCVideoTrack` to existing Flutter renderer.

### Notes
- Latency: tune gateway buffering for low-latency RTSP ingest (TCP vs UDP, RTP jitter buffers).
- Security/Access: Gateway handles auth to the RTSP source.
- Scale: Many clients can connect to the gateway; the app remains thin.


## 2) Local Track (publish + local preview)

Goal: Use an RTP/RTSP feed as a local `RTCVideoTrack`, same as camera: publish over WebRTC and preview locally.

### High-level approach
- Implement a custom `RTCVideoCapturer` that decodes the network stream and pushes frames to an `RTCVideoSource`.
- Create an `RTCVideoTrack` from the source. Add to a local `MediaStream` and attach to senders/transceivers. Also attach the track to a local renderer for preview.

### Architecture (In-app decode → Local Track)
- RTSP/RTP client + Decoder (FFmpegKit/GStreamer/MobileVLCKit) → CVPixelBuffer → `RTCVideoFrame` → `RTCVideoSource` (delegate) → `RTCVideoTrack`

### Native (iOS) implementation details
- New class: `FlutterRtpStreamCapturer : RTCVideoCapturer`
  - Methods: `startCapture(url, options)`, `stopCapture`.
  - Owns the decoder pipeline and a background thread/queue.
  - For each decoded frame:
    - Provide `CVPixelBuffer` in a supported format (BGRA/NV12).
    - Wrap as `RTCCVPixelBuffer` and convert to I420 if needed.
    - Compute `timeStampNs` using `mach_absolute_time` (see `FlutterSocketConnectionFrameReader`).
    - Determine `RTCVideoRotation` (likely 0 for fixed-orientation cameras).
    - Call `[self.delegate capturer:self didCaptureVideoFrame:frame]`.
- Decoder options:
  - FFmpegKit (ffmpeg-kit-ios)
    - Pros: Easy RTSP, wide codec support. Cons: Licensing, software decode unless integrating VideoToolbox.
  - GStreamer
    - Pros: Robust RTSP; hardware decode (`vtdec`); appsink to obtain `CVPixelBuffer`. Cons: Larger footprint, steeper learning curve.
  - MobileVLCKit
    - Pros: Simple RTSP playback. Cons: Licensing; need a way to extract frames as `CVPixelBuffer`.
- Prefer GStreamer for production-grade hardware decode and stability; FFmpegKit acceptable for simpler streams.

### Plugin wiring (`FlutterWebRTCPlugin.m`)
- Add new method channel API: `createNetworkVideoTrack`
  - Args: `{ url, username?, password?, width?, height?, fps?, decoder: 'gstreamer'|'ffmpeg'|'vlc', hwAccel?: true }`
  - Steps:
    1. Create `RTCVideoSource`.
    2. Instantiate `FlutterRtpStreamCapturer` with `delegate = videoSource`.
    3. Start capture with provided URL/options.
    4. Create `RTCVideoTrack` from the source; wrap as `LocalVideoTrack` for processing pipeline compatibility.
    5. Store a stop handler in `videoCapturerStopHandlers[trackId]` to stop and clean resources.
    6. Return `{ trackId }` to Flutter.
- Expose `disposeNetworkVideoTrack(trackId)` to stop the capturer and free resources.

### Flutter-side API (Dart)
- Add convenience wrappers:
  - `NetworkVideoTrack.create({ url, credentials, width, height, fps, decoder }) -> trackId`
  - `NetworkVideoTrack.dispose(trackId)`
- Usage:
  - Create or reuse a local `MediaStream`.
  - Add the returned track to the stream and to a sender/transceiver (`sendonly` or `sendrecv`).
  - Attach the same track to the local renderer for preview.

### Night vision / processing pipeline
- Since `LocalVideoTrack` is used, existing processing hooks (e.g., `VideoProcessingAdapter`, `NightVisionProcessor`) can be reused to post-process frames.

### Lifecycle and threading
- Decoder runs on a background queue.
- Frame delivery to WebRTC occurs on the capturer’s expected thread; ensure thread-safety and backpressure strategy (drop frames if behind).
- Handle network loss: auto-retry or propagate error via event channel.


## Cross-cutting considerations
- Performance
  - Hardware decode strongly recommended (VideoToolbox) for 1080p/4K.
  - Avoid unnecessary copies: keep frames as `CVPixelBuffer` and convert once.
- Latency
  - Tune decoder buffers; choose UDP over TCP where possible for RTSP.
  - Consider B-frame handling if the pipeline adds delay.
- Battery/thermal
  - Provide FPS/scale-down options; allow app to set target resolution/fps.
- Licensing
  - FFmpeg, GStreamer, VLC have different licenses; ensure compatibility with App Store policies.
- Background mode
  - iOS may pause decoding/network in background; design behavior accordingly.


## Testing strategy
- Unit: synthetic frame generator feeding `FlutterRtpStreamCapturer` to validate timestamps, rotations, color formats.
- Integration: connect to a known RTSP source (H.264 @ 720p/1080p) and verify:
  - Local preview FPS/latency
  - Publish to a PC/SFU and validate bitrate/resolution
  - Recovery from network drop and RTSP reconnect
- Soak tests: 1–4 hours continuous playback/publish, monitor memory/thermal.


## Milestones and estimate
- M1: Design and scaffolding (APIs, capturer skeleton, plugin wiring) — 1–2 days
- M2: Decoder integration (choose library, build scripts, minimal pipeline) — 3–5 days
- M3: Frame path to `RTCVideoTrack` + preview — 2–3 days
- M4: Publish upstream (sender/transceiver integration, bitrate tuning) — 2 days
- M5: Error handling, reconnection, cleanup, docs — 2 days
- M6: Performance passes (hardware decode, buffer tuning) — 3–5 days


## Deliverables
- `FlutterRtpStreamCapturer.{h,m}` (iOS) with decoder adapter
- Plugin methods: `createNetworkVideoTrack`, `disposeNetworkVideoTrack`
- Dart helpers: `NetworkVideoTrack.create/ dispose`
- Example updates showcasing:
  - Remote track via gateway (receive-only playback)
  - Local track from RTSP (publish + local preview)
- Documentation (this plan + API usage examples)

## Dart usage examples

### Recv-only via Gateway (WHEP or SFU)

```dart
// 1) Create a recvonly transceiver and attach remote track to a renderer
final pc = await createPeerConnection({ 'iceServers': [{'urls': 'stun:stun.l.google.com:19302'}]});
final transceiver = await pc.addTransceiver(
  kind: RTCRtpMediaType.RTCRtpMediaTypeVideo,
  init: RTCRtpTransceiverInit(direction: TransceiverDirection.RecvOnly),
);

// 2) Do signaling with your gateway (WHEP or SFU) to obtain SDP answer
final offer = await pc.createOffer({'offerToReceiveVideo': 1});
await pc.setLocalDescription(offer);
// send offer.sdp to gateway; receive answer.sdp back
await pc.setRemoteDescription(RTCSessionDescription(answerSdp, 'answer'));

// 3) Render when remote track arrives
final renderer = RTCVideoRenderer();
await renderer.initialize();
pc.onTrack = (RTCTrackEvent e) {
  if (e.track.kind == 'video') {
    renderer.srcObject = e.streams.first;
  }
};
```

### Local track from RTSP (publish + local preview)

```dart
// 1) Create a local MediaStream
final localStream = await createLocalMediaStream('network');

// 2) Create the network-backed video track (iOS implementation first)
final track = await NetworkVideoTrack.create(
  url: 'rtsp://user:pass@ip:554/stream',
  width: 1280,
  height: 720,
  fps: 30,
  decoder: 'gstreamer',
  hwAccel: true,
);

// 3) Add to stream for preview and/or publishing
await localStream.addTrack(track);

// Optional local preview
final localRenderer = RTCVideoRenderer();
await localRenderer.initialize();
localRenderer.srcObject = localStream;

// 4) Publish via transceiver or addTrack
final pc = await createPeerConnection(config);
await pc.addTransceiver(
  track: track,
  init: RTCRtpTransceiverInit(direction: TransceiverDirection.SendOnly),
);

// Cleanup
await NetworkVideoTrack.dispose(track);
await localStream.removeTrack(track);
await localRenderer.dispose();
```

## Limitations & risks (initial phase)

- Decoder integration starts on iOS; other platforms follow.
- GStreamer/FFmpegKit/VLC carry licensing and footprint trade-offs; validate for App Store.
- Initial skeleton may not render frames until decoder is wired; publishing pipeline is unaffected.

## Implementation progress (Nov 13, 2025)

- **Completed**
  - Documentation: added Dart usage examples and limitations.
  - iOS plugin: added `createNetworkVideoTrack` and `disposeNetworkVideoTrack` method handlers.
  - iOS capturer: created `FlutterRtpStreamCapturer` subclass of `RTCVideoCapturer` with a synthetic frame generator for bring-up (BGRA → I420, proper timestamps).
  - Wiring: created `RTCVideoSource` (non-screencast) + `VideoProcessingAdapter`, built `RTCVideoTrack`, wrapped in `LocalVideoTrack`, stored stop handlers.
  - Dart API: added `NetworkVideoTrack.create/ dispose` helper and exported from `flutter_webrtc.dart`.
- **In progress / pending**
  - Decoder selection and integration (GStreamer preferred for hw-accel; FFmpegKit acceptable to start).
  - Event channel for network stream state (connected, buffering, error, reconnecting).
  - Example app updates to showcase preview + publish.

## Milestone status

- **M1: Design and scaffolding (APIs, capturer skeleton, plugin wiring)** — Done
- **M2: Decoder integration (choose library, build scripts, minimal pipeline)** — Pending (awaiting choice)
- **M3: Frame path to `RTCVideoTrack` + preview** — Partially Done (synthetic frames operational; validate with decoder next)
- **M4: Publish upstream (sender/transceiver integration, bitrate tuning)** — Pending
- **M5: Error handling, reconnection, cleanup, docs** — Pending
- **M6: Performance passes (hardware decode, buffer tuning)** — Pending

## Immediate next actions

- Decide decoder (GStreamer vs FFmpegKit) for iOS.
- Implement decoder adapter to output `CVPixelBuffer` → `RTCVideoFrame` via capturer delegate.
- Add status events and update the example app to test end-to-end.
