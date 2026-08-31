# Building the HDR Fusion App

## Prerequisites

- Android Studio (latest version recommended)
- Android SDK 34 (compileSdk)
- Minimum SDK 26
- Java 17 or later
- Gradle 8.2+

## Building from Android Studio

1. **Clone the repository**:
   ```bash
   git clone https://github.com/xkzy/hdr-android.git
   cd hdr-android
   git checkout claude/saturation-over-vibrance-s4ysao
   ```

2. **Open in Android Studio**:
   - Open Android Studio
   - Select "Open an Existing Project"
   - Navigate to the `hdr-android` directory
   - Let Gradle sync automatically

3. **Build the APK**:
   - **Debug APK** (for testing): 
     - `Build → Build Bundle(s) / APK(s) → Build APK(s)`
     - Or: `./gradlew assembleDebug`
   - **Release APK** (for distribution):
     - `Build → Build Bundle(s) / APK(s) → Build Bundle(s)`
     - Or: `./gradlew assembleRelease` (requires keystore signing)

4. **Locate the APK**:
   - Debug: `app/build/outputs/apk/debug/app-debug.apk`
   - Release: `app/build/outputs/bundle/release/app-release.aab` (or `app/build/outputs/apk/release/`)

## Building from Command Line

```bash
# Download dependencies and build debug APK
./gradlew assembleDebug

# Build release APK (requires signing configuration)
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug

# Build with verbose output for debugging
./gradlew assembleDebug --stacktrace
```

## Installation on Device

1. **Connect your Android device** with USB debugging enabled
2. **Install the APK**:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Features in This Build

All of the following have been implemented and are ready to use:

### 1. **Saturation-based HDR Fusion**
   - Per-pixel argmax fusion using HSV saturation metric
   - Clipping penalties for over/underexposed pixels
   - Replaces older "vibrance" terminology with accurate saturation

### 2. **Anti-Motion Blur (IMU-based)**
   - Gyroscope/accelerometer integration for motion detection
   - Pre-shoot stillness gating
   - Per-frame blur estimation and automatic retakes
   - Graceful fallback if IMU unavailable

### 3. **Metered Auto-Exposure**
   - Live AE/AWB metering pass before bracket
   - Replaces hardcoded 8ms exposure guess
   - Converges on real scene exposure

### 4. **RAW Sensor Capture with Demosaic**
   - Full sensor bit depth (10-14 bits) for better headroom
   - Bilinear Bayer demosaic for all CFA patterns (RGGB, GRBG, GBRG, BGGR)
   - Per-channel white-balance gains and color-space matrix correction
   - sRGB gamma encoding
   - Graceful fallback to JPEG if RAW unavailable

### 5. **Frame Alignment**
   - Cross-correlation based translational alignment
   - Coarse-to-fine pyramid search to remove hand-shake drift
   - Automatic crop to common overlap region

### 6. **Adaptive Saturation Heuristic**
   - Non-uniform EV spacing that clusters exposures around mid-tones
   - Concentrates diversity where saturation is richest
   - Fewer frames achieve same coverage as uniform spacing

### 7. **Burst Stacking Mode**
   - Single metered ISO (peak brightness ~127) with fixed shutter
   - Rapid frame capture minimizes motion blur
   - Frame averaging reduces noise (SNR improves as √N)
   - Synthetic exposure synthesis via weighted brightness adjustment
   - Creates bracket diversity without varying sensor settings per frame

### 8. **SNR-Based Adaptive Capture**
   - Laplacian-based noise floor estimation
   - Captures frames until target SNR is reached
   - Auto-adapts to scene lighting (bright: 3-4 frames, dim: 10-15+ frames)
   - Practical range: 10-100 for target SNR
   - Logs real-time noise metrics

## UI Configuration

All capture parameters are adjustable in the app:

- **Shoot steps**: Number of bracketed frames (2-9)
- **Stops (EV) per step**: EV spacing between frames
- **Base ISO**: ISO for center frame
- **ISO weight**: 0=shutter-only, 1=ISO-only bracketing
- **fx (focal length)**: Optional lens focal length lock (blank=auto)
- **Optimize for saturation**: Enables adaptive EV spacing
- **Burst stacking**: Enables fixed-exposure burst mode
- **Target SNR**: Adaptive frame count based on noise (0=disabled)

## Troubleshooting

### Build Fails with "Could not resolve com.android.tools.build:gradle:8.2.2"
- Check internet connection
- Update Android Studio to latest version
- Clear Gradle cache: `./gradlew clean`
- Sync project: `File → Sync Now` in Android Studio

### APK Installation Fails
- Ensure minimum SDK 26 device
- Enable USB debugging on device
- Verify device is recognized: `adb devices`
- Check storage space on device

### Camera Permission Issues
- Grant camera permission when app prompts
- Check Settings → Apps → HDR Fusion → Permissions → Camera

### Low-Light Performance
- Use burst stacking mode for best results
- Set target SNR 30-50 for low-light
- Increase shot steps if available light is very dim

## Next Steps

1. Build the debug APK using `./gradlew assembleDebug`
2. Install on an Android device: `adb install app/build/outputs/apk/debug/app-debug.apk`
3. Test with various lighting conditions
4. Adjust shoot steps, stops, ISO, and SNR target for your use case
5. Enable burst stacking + SNR targeting for handheld low-light scenes

For release distribution, build a signed release APK with your own keystore.
