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