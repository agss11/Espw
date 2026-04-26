# ESP Detector V2

Real-time object detection Android app with game-style ESP (Extra Sensory Perception) overlay.

## Features

- **Real-time Detection** — YOLOv8n INT8 model, 80 COCO classes, ~18 FPS on mid-range phones
- **ESP Overlay** — Game-style bounding boxes with glow effects, corner brackets, crosshairs
- **Snaplines** — Tracking lines from screen top to each target
- **Distance Estimation** — Real-time distance calculation based on camera FOV and object size
- **Radar** — Semi-circular minimap showing target positions and distances
- **Trajectory Tracking** — Motion trails and prediction arrows for moving targets
- **Target Lock** — "LOCKED" indicator on closest target to screen center
- **30fps Interpolated Rendering** — Smooth overlay animation via Choreographer
- **iPhone-style Zoom** — Pinch-to-zoom + arc dial with preset buttons (1x-1000x)
- **GPS HUD** — Real-time coordinates, speed, satellite count
- **Satellite Monitor** — Sky plot visualization of all visible GNSS satellites
- **Category Filtering** — Toggle Person / Vehicle / Animal / Object detection
- **Bilingual** — Chinese / English language switch
- **Boot Animation** — Cyberpunk-style splash screen with real hardware info

## Architecture

```
Camera (1080p preview + 640x480 analysis)
  |
  v
CpuPreprocessor (YUV->RGB, rotate, letterbox, 320x320)
  |
  v
YoloDetector (TFLite INT8, GPU Delegate)
  |
  v
DetectionStabilizer (IOU tracking, EMA smoothing, trajectory)
  |
  v
OverlayView (Choreographer 30fps, interpolation, ESP rendering)
```

## Tech Stack

- **Language**: Java
- **Camera**: Camera2 API
- **ML**: TensorFlow Lite + GPU Delegate
- **Model**: YOLOv8n INT8 quantized (3.2MB)
- **Rendering**: Android Canvas + Hardware Acceleration
- **Location**: GNSS API + Fused Location

## Requirements

- Android 8.0+ (API 26)
- Camera permission
- Location permission (optional, for GPS features)

## Build

```bash
# Clone
git clone https://github.com/YOUR_USERNAME/ESP-Detector.git
cd ESP-Detector

# Build
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Device Compatibility

The app dynamically reads camera FOV, sensor orientation, and screen dimensions at runtime. Tested on Samsung Galaxy A36 but designed to work on any Android device with a back camera.

## Screenshots

_Coming soon_

## License

MIT License - see [LICENSE](LICENSE) for details.
