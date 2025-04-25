#import "AudioRenderer.h"
#import "MediaRecorderImpl.h"

@implementation AudioRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _recorder = nil;
        NSLog(@"[AudioRenderer] Initialized");
    }
    return self;
}

- (void)renderPCMBuffer:(AVAudioPCMBuffer *)pcmBuffer {
    // Log audio buffer details
    static NSInteger bufferCounter = 0;
    bufferCounter++;
    
    if (bufferCounter % 100 == 0) {  // Log every 100 buffers to avoid flooding the console
        NSLog(@"[AudioRenderer] Received PCM buffer #%ld - Format: %@, Channels: %d, Sample Rate: %.0f Hz, Frame Length: %d", 
              (long)bufferCounter, 
              pcmBuffer.format.description,
              (int)pcmBuffer.format.channelCount,
              pcmBuffer.format.sampleRate,
              (int)pcmBuffer.frameLength);
    }
    
    // Forward the PCM buffer to the MediaRecorderImpl if available
    if (_recorder != nil) {
        if ([_recorder isRecording]) {
            // Scale down the audio samples
            float volumeFactor = 0.2f; // Adjust this value to change volume
            
            if (pcmBuffer.format.commonFormat == AVAudioPCMFormatFloat32) {
                float *channelData = pcmBuffer.floatChannelData[0]; // Assuming mono or taking the first channel
                for (AVAudioFrameCount i = 0; i < pcmBuffer.frameLength; ++i) {
                    channelData[i] *= volumeFactor;
                }
            } else if (pcmBuffer.format.commonFormat == AVAudioPCMFormatInt16) {
                int16_t *channelData = pcmBuffer.int16ChannelData[0]; // Assuming mono or taking the first channel
                for (AVAudioFrameCount i = 0; i < pcmBuffer.frameLength; ++i) {
                    channelData[i] = (int16_t)(channelData[i] * volumeFactor);
                }
            } else {
                 if (bufferCounter % 100 == 0) { // Log only periodically
                     NSLog(@"[AudioRenderer] Unsupported audio format for volume scaling: %@", pcmBuffer.format.description);
                 }
            }

            [_recorder renderPCMBuffer:pcmBuffer];
            
            if (bufferCounter % 100 == 0) {
                NSLog(@"[AudioRenderer] Successfully forwarded buffer #%ld to recorder", (long)bufferCounter);
            }
        } else {
            if (bufferCounter % 100 == 0) {
                NSLog(@"[AudioRenderer] Warning: Recorder exists but is not recording. Buffer #%ld not forwarded.", (long)bufferCounter);
            }
        }
    } else {
        if (bufferCounter % 100 == 0) {
            NSLog(@"[AudioRenderer] Warning: No recorder available. Buffer #%ld dropped.", (long)bufferCounter);
        }
    }
}

- (void)setRecorder:(MediaRecorderImpl *)recorder {
    _recorder = recorder;
    NSLog(@"[AudioRenderer] Recorder set: %@, Recording state: %@", 
          recorder ? @"YES" : @"NO", 
          (recorder && [recorder isRecording]) ? @"RECORDING" : @"NOT RECORDING");
}

- (void)clearRecorder {
    NSLog(@"[AudioRenderer] Recorder cleared. Previous recorder: %@", _recorder ? @"WAS SET" : @"WAS NOT SET");
    _recorder = nil;
}

@end 