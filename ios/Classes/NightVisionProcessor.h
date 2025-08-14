#import <CoreImage/CoreImage.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <WebRTC/WebRTC.h>
#import "VideoProcessingAdapter.h"

/**
 * Night Vision Processor for iOS using Core Image framework.
 * Provides real-time video enhancement for low-light conditions
 * using GPU-accelerated Core Image filters and Metal Performance Shaders.
 */
@interface NightVisionProcessor : NSObject <ExternalVideoProcessingDelegate>

// Configuration properties
@property(nonatomic, assign) BOOL enabled;
@property(nonatomic, assign) float intensity;            // 0.0 - 1.0
@property(nonatomic, assign) float gamma;                // Dynamic gamma correction factor
@property(nonatomic, assign) float brightnessThreshold;  // Threshold for applying enhancement

// Core Image processing context
@property(nonatomic, strong) CIContext* ciContext;

// Core Image filters for night vision enhancement
@property(nonatomic, strong) CIFilter* gammaFilter;
@property(nonatomic, strong) CIFilter* contrastFilter;
@property(nonatomic, strong) CIFilter* noiseReductionFilter;
@property(nonatomic, strong) CIFilter* brightnessFilter;
@property(nonatomic, strong) CIFilter* grayscaleFilter;

// Metal device for GPU acceleration
@property(nonatomic, strong) id<MTLDevice> metalDevice;

/**
 * Initialize the night vision processor
 */
- (instancetype)init;

/**
 * Initialize with specific Metal device
 */
- (instancetype)initWithMetalDevice:(id<MTLDevice>)device;

/**
 * Process a video frame with night vision enhancement
 */
- (RTCVideoFrame*)processFrame:(RTCVideoFrame*)frame;

/**
 * Process a remote video frame (for remote stream enhancement)
 */
- (RTCVideoFrame*)processRemoteFrame:(RTCVideoFrame*)frame;

/**
 * Set night vision intensity
 * @param intensity Value between 0.0 (disabled) and 1.0 (maximum enhancement)
 */
- (void)setIntensity:(float)intensity;

/**
 * Set brightness threshold for applying enhancement
 * @param threshold Value between 0.0 and 1.0
 */
- (void)setBrightnessThreshold:(float)threshold;

/**
 * Enable or disable night vision processing
 */
- (void)setEnabled:(BOOL)enabled;

/**
 * Clean up resources
 */
- (void)dispose;

@end