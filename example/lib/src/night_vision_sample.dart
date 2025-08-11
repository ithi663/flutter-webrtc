import 'dart:core';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

class NightVisionSample extends StatefulWidget {
  static String tag = 'night_vision_sample';

  @override
  _NightVisionSampleState createState() => _NightVisionSampleState();
}

class _NightVisionSampleState extends State<NightVisionSample> {
  MediaStream? _localStream;
  RTCVideoRenderer _localRenderer = RTCVideoRenderer();
  RTCVideoRenderer _remoteRenderer = RTCVideoRenderer();
  RTCPeerConnection? _pc1;
  RTCPeerConnection? _pc2;
  bool _offer = false;
  bool _inCalling = false;

  // Night vision controls
  bool _localNightVisionEnabled = false;
  bool _remoteNightVisionEnabled = false;
  double _localNightVisionIntensity = 0.7;
  double _remoteNightVisionIntensity = 0.7;

  final Map<String, dynamic> _config = {
    'iceServers': [
      {'url': 'stun:stun.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  final Map<String, dynamic> _dcConstraints = {
    'mandatory': {
      'OfferToReceiveAudio': false,
      'OfferToReceiveVideo': true,
    },
    'optional': [],
  };

  @override
  void initState() {
    super.initState();
    initRenderers();
  }

  @override
  void deactivate() {
    super.deactivate();
    _localRenderer.dispose();
    _remoteRenderer.dispose();
  }

  initRenderers() async {
    await _localRenderer.initialize();
    await _remoteRenderer.initialize();
  }

  // Platform-specific getDisplayMedia method
  _getUserMedia() async {
    final Map<String, dynamic> mediaConstraints = {
      'audio': false,
      'video': {
        'mandatory': {
          'minWidth': '640',
          'minHeight': '480',
          'minFrameRate': '30',
        },
        'facingMode': 'user',
        'optional': [],
      }
    };

    try {
      var stream = await navigator.mediaDevices.getUserMedia(mediaConstraints);
      _localRenderer.srcObject = stream;
      _localStream = stream;
    } catch (e) {
      print(e.toString());
    }
    if (!mounted) return;

    setState(() {});
  }

  _createPeerConnection() async {
    _pc1 = await createPeerConnection(_config);
    _pc1!.onIceCandidate = (e) {
      if (e.candidate != null) {
        _pc2!.addCandidate(e);
      }
    };

    _pc1!.onIceConnectionState = (e) {
      print('pc1 IceConnectionState $e');
    };

    _pc2 = await createPeerConnection(_config);
    _pc2!.onIceCandidate = (e) {
      if (e.candidate != null) {
        _pc1!.addCandidate(e);
      }
    };

    _pc2!.onIceConnectionState = (e) {
      print('pc2 IceConnectionState $e');
    };

    _pc2!.onTrack = (event) {
      print('pc2 onTrack - ' + event.track.id!);
      if (event.track.kind == 'video') {
        _remoteRenderer.srcObject = event.streams[0];
      }
    };

    // Use addTrack instead of addStream for Unified Plan
    if (_localStream != null) {
      _localStream!.getTracks().forEach((track) {
        _pc1!.addTrack(track, _localStream!);
      });
    }
  }

  _createOffer() async {
    RTCSessionDescription description = await _pc1!.createOffer(_dcConstraints);
    await _pc1!.setLocalDescription(description);
    await _pc2!.setRemoteDescription(description);

    RTCSessionDescription description2 = await _pc2!.createAnswer(_dcConstraints);
    await _pc2!.setLocalDescription(description2);
    await _pc1!.setRemoteDescription(description2);
  }

  _hangUp() async {
    try {
      await _localStream?.dispose();
      await _pc1?.close();
      await _pc2?.close();
      _localRenderer.srcObject = null;
      _remoteRenderer.srcObject = null;
    } catch (e) {
      print(e.toString());
    }
    setState(() {
      _localStream = null;
      _pc1 = null;
      _pc2 = null;
      _offer = false;
      _inCalling = false;
      _localNightVisionEnabled = false;
      _remoteNightVisionEnabled = false;
    });
  }

  _makeCall() async {
    await _getUserMedia();
    await _createPeerConnection();
    await _createOffer();
    setState(() {
      _inCalling = true;
    });
  }

  // Night Vision Control Methods
  _toggleLocalNightVision() async {
    if (_localStream == null) return;

    setState(() {
      _localNightVisionEnabled = !_localNightVisionEnabled;
    });

    // Apply GPU night vision to the local renderer (Android uses GPU-only path)
    try {
      await _localRenderer.setRemoteNightVision(_localNightVisionEnabled);
      print('Local night vision ${_localNightVisionEnabled ? 'enabled' : 'disabled'}');
    } catch (e) {
      print('Error setting local night vision: $e');
    }
  }

  _setLocalNightVisionIntensity(double intensity) async {
    if (_localStream == null) return;

    setState(() {
      _localNightVisionIntensity = intensity;
    });

    // Update GPU night vision intensity on the local renderer
    try {
      await _localRenderer.setRemoteNightVisionIntensity(intensity);
      print('Local night vision intensity set to: $intensity');
    } catch (e) {
      print('Error setting local night vision intensity: $e');
    }
  }

  _toggleRemoteNightVision() async {
    if (_remoteRenderer.srcObject == null) return;

    setState(() {
      _remoteNightVisionEnabled = !_remoteNightVisionEnabled;
    });

    try {
      await _remoteRenderer.setRemoteNightVision(_remoteNightVisionEnabled);
      print('Remote night vision ${_remoteNightVisionEnabled ? 'enabled' : 'disabled'}');
    } catch (e) {
      print('Error setting remote night vision: $e');
    }
  }

  _setRemoteNightVisionIntensity(double intensity) async {
    if (_remoteRenderer.srcObject == null) return;

    setState(() {
      _remoteNightVisionIntensity = intensity;
    });

    try {
      await _remoteRenderer.setRemoteNightVisionIntensity(intensity);
      print('Remote night vision intensity set to: $intensity');
    } catch (e) {
      print('Error setting remote night vision intensity: $e');
    }
  }

  Widget _buildVideoView() {
    return Expanded(
      child: Row(
        children: [
          Expanded(
            child: Container(
              margin: EdgeInsets.fromLTRB(0.0, 0.0, 0.0, 0.0),
              width: MediaQuery.of(context).size.width,
              decoration: BoxDecoration(color: Colors.black54),
              child: RTCVideoView(_localRenderer, mirror: true),
            ),
          ),
          Expanded(
            child: Container(
              margin: EdgeInsets.fromLTRB(0.0, 0.0, 0.0, 0.0),
              width: MediaQuery.of(context).size.width,
              decoration: BoxDecoration(color: Colors.black54),
              child: RTCVideoView(_remoteRenderer),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildControlPanel() {
    return Container(
      padding: EdgeInsets.all(16.0),
      color: Colors.grey[100],
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Call Controls
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              ElevatedButton(
                onPressed: _inCalling ? null : _makeCall,
                child: Text('Start Call'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.green,
                  foregroundColor: Colors.white,
                ),
              ),
              ElevatedButton(
                onPressed: _inCalling ? _hangUp : null,
                child: Text('Hang Up'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.red,
                  foregroundColor: Colors.white,
                ),
              ),
            ],
          ),

          if (_inCalling) ...[
            SizedBox(height: 20),
            Divider(),

            // Local Night Vision Controls
            Text(
              'Local Night Vision (Your Camera)',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 8),
            Row(
              children: [
                Text('Enable: '),
                Switch(
                  value: _localNightVisionEnabled,
                  onChanged: (value) => _toggleLocalNightVision(),
                ),
                Spacer(),
                Text('Intensity: ${(_localNightVisionIntensity * 100).round()}%'),
              ],
            ),
            Slider(
              value: _localNightVisionIntensity,
              min: 0.0,
              max: 1.0,
              divisions: 20,
              onChanged:
                  _localNightVisionEnabled ? (value) => _setLocalNightVisionIntensity(value) : null,
              label: '${(_localNightVisionIntensity * 100).round()}%',
            ),

            SizedBox(height: 16),

            // Remote Night Vision Controls
            Text(
              'Remote Night Vision (Enhance Incoming Video)',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 8),
            Row(
              children: [
                Text('Enable: '),
                Switch(
                  value: _remoteNightVisionEnabled,
                  onChanged: (value) => _toggleRemoteNightVision(),
                ),
                Spacer(),
                Text('Intensity: ${(_remoteNightVisionIntensity * 100).round()}%'),
              ],
            ),
            Slider(
              value: _remoteNightVisionIntensity,
              min: 0.0,
              max: 1.0,
              divisions: 20,
              onChanged: _remoteNightVisionEnabled
                  ? (value) => _setRemoteNightVisionIntensity(value)
                  : null,
              label: '${(_remoteNightVisionIntensity * 100).round()}%',
            ),

            SizedBox(height: 16),

            // Information Panel
            // Container(
            //   padding: EdgeInsets.all(12),
            //   decoration: BoxDecoration(
            //     color: Colors.blue[50],
            //     border: Border.all(color: Colors.blue[200]!),
            //     borderRadius: BorderRadius.circular(8),
            //   ),
            //   child: Column(
            //     crossAxisAlignment: CrossAxisAlignment.start,
            //     children: [
            //       Text(
            //         'Night Vision Demo',
            //         style: TextStyle(fontWeight: FontWeight.bold, color: Colors.blue[800]),
            //       ),
            //       SizedBox(height: 8),
            //       Text(
            //         '• Local Night Vision: Enhances your camera feed before sending to others',
            //         style: TextStyle(fontSize: 12),
            //       ),
            //       Text(
            //         '• Remote Night Vision: Enhances incoming video on your side only',
            //         style: TextStyle(fontSize: 12),
            //       ),
            //       Text(
            //         '• Higher intensity = more enhancement but may introduce artifacts',
            //         style: TextStyle(fontSize: 12),
            //       ),
            //       Text(
            //         '• Works best in low-light conditions',
            //         style: TextStyle(fontSize: 12),
            //       ),
            //     ],
            //   ),
            // ),
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Night Vision Demo'),
        backgroundColor: Colors.indigo,
        foregroundColor: Colors.white,
      ),
      body: Column(
        children: [
          _buildVideoView(),
          _buildControlPanel(),
        ],
      ),
    );
  }
}
