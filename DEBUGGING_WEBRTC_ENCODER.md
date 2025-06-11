# WebRTC Encoder Debugging Implementation

This document describes the debugging changes implemented based on the analysis in `ref_pan.md` to investigate and resolve WebRTC video encoder issues.

## Problem Summary

The issue identified is a mismatch between:
- **Requested format**: 640x480 landscape @ 30fps
- **Encoder initialization**: 720x1280 portrait @ 60fps (from logs showing `OMX.hisi.video.encoder.avc`)

This mismatch suggests either incorrect dimension/rotation handling or issues with the hardware encoder initialization.

## Implemented Changes

### 1. Enhanced Logging in GetUserMediaImpl.java

**File**: `android/src/main/java/com/cloudwebrtc/webrtc/GetUserMediaImpl.java`

**Changes**:
- Added detailed logging for requested vs actual camera formats
- Added logging when `VideoCapturer.startCapture()` is called
- Changed default resolution from 1280x720 to 640x480 for testing

**Key logs to monitor**:
```
I/FlutterWebRTCPlugin: getUserMedia(video) requested: 640x480@30fps
I/FlutterWebRTCPlugin: getUserMedia(video) actual camera format chosen: [actual_width]x[actual_height]
D/FlutterWebRTCPlugin: VideoCapturer.startCapture called with: 640x480@30
```

### 2. Encoder Factory Debugging

**File**: `android/src/main/java/org/webrtc/video/CustomVideoEncoderFactory.java`

**Changes**:
- Added comprehensive logging for encoder creation
- Enhanced the `setForceSWCodec()` method with logging
- Added ability to track which encoder (HW/SW) is being used

**Key logs to monitor**:
```
D/CustomVideoEncoderFactory: createEncoder called for: H264, forceSWCodec: false
D/CustomVideoEncoderFactory: Using hardware encoder for: H264
```

### 3. Testing Configuration

**Default Resolution Change**:
- Changed `DEFAULT_WIDTH` from 1280 to 640
- Changed `DEFAULT_HEIGHT` from 720 to 480
- Kept `DEFAULT_FPS` at 30

## Testing Instructions

### Phase 1: Capture Format Analysis

1. **Build and run** the application with the enhanced logging
2. **Monitor logs** for the format discrepancy patterns:
   ```bash
   adb logcat | grep -E "(getUserMedia|CustomVideoEncoderFactory|HardwareVideoEncoder)"
   ```

3. **Look for**:
   - Requested vs actual camera format differences
   - Encoder initialization parameters
   - Any rotation-related logs

### Phase 2: Software Encoder Testing

To test if the issue is hardware encoder specific:

1. **Temporarily force software encoding**:
   
   In `CustomVideoEncoderFactory.java`, change:
   ```java
   private boolean forceSWCodec = false; // <--- SET TO TRUE FOR TESTING TEMPORARILY
   ```
   to:
   ```java
   private boolean forceSWCodec = true; // <--- FORCE SOFTWARE ENCODING
   ```

2. **Test streaming** with software encoding forced
3. **Compare behavior** - if software encoding works flawlessly, it confirms hardware encoder issues

### Phase 3: Runtime Software Encoding Control

For dynamic testing, you can call:
```java
// From your Flutter/Dart code, expose a method to toggle:
customVideoEncoderFactory.setForceSWCodec(true);  // Force SW
customVideoEncoderFactory.setForceSWCodec(false); // Use HW
```

## Expected Debugging Outcomes

### Scenario A: Format Mismatch Confirmed
If logs show actual camera format differs significantly from requested:
- Example: Requested 640x480, actual 1280x720
- This indicates camera hardware selection issues
- Solution: Improve format negotiation or scaling logic

### Scenario B: Rotation Issues
If logs show correct dimensions but wrong orientation in encoder:
- Example: Camera provides 640x480, encoder gets 480x640
- This indicates rotation handling issues in the WebRTC pipeline
- Solution: Fix rotation metadata handling

### Scenario C: Hardware Encoder Issues  
If software encoding works but hardware encoding fails:
- Confirms hardware encoder (`OMX.hisi.video.encoder.avc`) compatibility issues
- Solution: Add device-specific codec blacklisting or fallback logic

### Scenario D: Frame Rate Mismatch
If logs show correct dimensions but wrong frame rate:
- Example: Requested 30fps, encoder gets 60fps
- This indicates FPS adaptation issues
- Solution: Fix frame rate negotiation in VideoSource

## Key Log Patterns to Watch

1. **Normal Operation**:
   ```
   I/FlutterWebRTCPlugin: getUserMedia(video) requested: 640x480@30fps
   I/FlutterWebRTCPlugin: getUserMedia(video) actual camera format chosen: 640x480
   D/FlutterWebRTCPlugin: VideoCapturer.startCapture called with: 640x480@30
   D/CustomVideoEncoderFactory: Using hardware encoder for: H264
   I/org.webrtc.Logging: HardwareVideoEncoder: initEncode ... width: 640 height: 480 framerate_fps: 30
   ```

2. **Problem Pattern**:
   ```
   I/FlutterWebRTCPlugin: getUserMedia(video) requested: 640x480@30fps
   I/FlutterWebRTCPlugin: getUserMedia(video) actual camera format chosen: 1280x720
   D/FlutterWebRTCPlugin: VideoCapturer.startCapture called with: 640x480@30
   D/CustomVideoEncoderFactory: Using hardware encoder for: H264
   I/org.webrtc.Logging: HardwareVideoEncoder: initEncode ... width: 720 height: 1280 framerate_fps: 60
   ```

## Reverting Changes

After debugging, remember to:

1. **Revert default resolution**:
   ```java
   private static final int DEFAULT_WIDTH = 1280;
   private static final int DEFAULT_HEIGHT = 720;
   ```

2. **Disable force software encoding**:
   ```java
   private boolean forceSWCodec = false;
   ```

3. **Optionally remove debug logs** if they're too verbose for production

## Next Steps

Based on debugging results:

1. **If hardware encoder is the issue**: Implement device-specific codec selection or fallback mechanisms
2. **If rotation is the issue**: Fix VideoSource rotation handling
3. **If camera format is the issue**: Improve camera format negotiation
4. **If frame rate is the issue**: Fix FPS adaptation logic

The enhanced logging will provide the data needed to determine which specific component in the WebRTC pipeline is causing the encoder mismatch. 