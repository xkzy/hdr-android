# HDR Fusion (argmax-saturation)

Camera2-based Android app that shoots an exposure bracket and fuses it by picking,
independently for every pixel (i,j), the frame whose (i,j) has the highest **saturation**
(HSV saturation = (max(R,G,B) - min(R,G,B)) / max(R,G,B), with a penalty for clipped
highlights/shadows).

## Configurable capture parameters (in-app)

| Field | Meaning |
|---|---|
| Shoot steps | number of bracketed frames (2-9) |
| Stops (EV) per step | EV spacing between consecutive frames, centered on the base exposure |
| Base ISO | ISO used at the center frame |
| ISO weight | 0..1 — how much of each EV step is realized via ISO vs shutter speed. 0 = classic shutter-only bracketing, 1 = ISO-only |
| fx (focal length, mm) | locks `LENS_FOCAL_LENGTH` for every frame in the bracket. Leave blank to use the first available focal length. **This must stay fixed across the bracket** — the fusion algorithm assumes pixel (i,j) is the same scene point in every frame, so any focal-length or framing change between shots will misalign the fusion. |

## Anti motion blur

The camera stays fixed (fx locked, see above), but a handheld shot can still blur an
individual frame if the hand moves during *that frame's own exposure*. If the device has
a gyroscope (or, failing that, an accelerometer), `MotionMonitor` uses it to:

- gate the start of the bracket on the hand being steady (`waitForStillness`, capped at a
  couple seconds so a shoot never blocks indefinitely);
- estimate, per frame, the rotation angle covered during that frame's exposure window
  (peak angular velocity × exposure time) and retake the frame (up to 3 attempts) if it's
  estimated to be blurred.

The per-frame retake only runs when the camera's exposure timestamps and the sensor's
timestamps are confirmed to share a clock (`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`); on
devices that don't guarantee this, the pre-shoot stillness gate still applies since it
only compares the IMU stream against itself. On devices with no gyroscope or
accelerometer at all, motion gating is skipped entirely and capture behaves as before.

## Files

- `CameraBracketController.kt` — opens the camera, computes per-step ISO/exposure-time
  from the EV/ISO-weight settings, fires manual captures (AE/AF locked off), decodes JPEGs,
  and retakes frames the IMU flags as blurred.
- `MotionMonitor.kt` — wraps the gyroscope/accelerometer to detect handheld shake for the
  anti motion blur gating above.
- `SaturationFusion.kt` — the argmax-saturation fusion core; parallelized by row-band.
- `MainActivity.kt` — preview, permission handling, wiring, saves the fused JPEG to
  `Pictures/HDRFusion` via MediaStore.

## Known simplifications (call these out if you productionize this)

- No image alignment/deghosting across frames — assumes a static scene/tripod, since
  fusion is strictly per-pixel across frames of identical framing (that's why fx is
  locked); the anti motion blur gating above only guards against blur *within* a single
  frame's own exposure, not the subject or camera moving between frames.
- Base exposure time is a hardcoded default rather than a metered auto-exposure read; wire
  up a short AE-converge pass before the bracket for real scenes.
- Still-capture size is fixed at 1920x1080 for simplicity; swap in the sensor's max JPEG
  size from `StreamConfigurationMap` for full resolution.
- JPEG (8-bit) capture is used for simplicity; for real HDR headroom, capture RAW/DNG
  (`ImageFormat.RAW_SENSOR`) instead and demosaic before fusing.
