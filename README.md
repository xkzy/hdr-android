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
| Optimize for saturation | if enabled, uses a heuristic algorithm that concentrates exposures around mid-tones where saturation is typically highest, allowing the same number of frames to achieve better saturation coverage, or fewer frames to achieve the same coverage. Uses adaptive exposure spacing instead of uniform EV steps. |

## Saturation optimization heuristic

When the "Optimize for saturation" mode is enabled, the bracketing strategy uses adaptive exposure spacing instead of uniform EV steps. This heuristic concentrates exposures around mid-tones (where color saturation is typically highest) rather than spreading them equally across the full dynamic range.

**Uniform spacing (default):** with 5 steps and 1 EV per step, captures at EV offsets `[-2, -1, 0, +1, +2]`

**Optimized spacing:** with 5 steps and 1 EV per step, captures at compressed offsets like `[-0.8, -0.4, 0, +0.4, +0.8]`

The compression uses a square-root scaling that prioritizes saturation coverage:
- Same number of frames achieve better saturation diversity
- Alternatively, fewer frames achieve comparable saturation results (e.g., 3 optimized frames may match 5 uniform frames in saturation coverage)
- Reduces shutter speed variation and motion blur risk during the bracket

This is particularly useful on handheld shots where shorter capture times reduce subject motion and cumulative hand drift between frames, while still maintaining the saturation diversity the argmax fusion requires.

## Capture pipeline

Each shoot runs, in order:

1. **Metering.** A short live auto-exposure/auto-white-balance pass on the preview stream
   (`CameraBracketController.meterAutoExposure`) reads a real converged exposure time from
   the scene — replacing what used to be a hardcoded ~8ms guess — and centers the bracket's
   per-step EV offsets on it. It also captures the "as-shot" white-balance gains/color
   matrix, used below to render every RAW frame with the same colors.
2. **Anti motion blur** (see below) — a steadiness gate before the shoot, and per-frame
   blur detection/retakes during it.
3. **Manual bracket capture**, at the sensor's largest available resolution for whichever
   format this device shoots (see RAW vs JPEG below) rather than a fixed preview-sized
   still.
4. **Alignment.** `ImageAligner` compensates for hand shake *between* frames — the hand can
   drift a few pixels over the second or two a multi-step bracket takes, even with fx
   locked — by finding the translational offset that best cross-correlates each frame
   against the first, then cropping every frame to their common overlap. This does not
   handle a moving subject in the scene; per-pixel argmax fusion has no way to deghost that
   without full per-object motion estimation, which is out of scope here.
5. **Fusion** via `SaturationFusion`, and save.

### RAW vs JPEG

On a device that reports the `RAW` capability and a `RAW_SENSOR` output size,
`CameraBracketController` captures unprocessed sensor data instead of JPEG and
`RawDemosaic` turns it into a `Bitmap` itself: black-level subtraction, the metered
white-balance gains, bilinear Bayer demosaic (from whatever CFA arrangement — RGGB /
GRBG / GBRG / BGGR — the sensor reports), the sensor->sRGB color-correction matrix, then
the sRGB gamma. This hands the fusion stage the sensor's real bit depth (typically 10-14
bits) instead of the camera's baked-in 8-bit JPEG tone curve — genuine headroom for the
per-pixel saturation comparison — at the cost of the ISP's own noise reduction,
sharpening, and lens-shading correction that a JPEG would already have applied, none of
which is reimplemented here. It's a pure-Kotlin demosaic with no native/GPU acceleration,
so on a high-megapixel sensor it is measurably slower than the JPEG path. On a device
without RAW capability, capture falls back to JPEG at the sensor's max resolution, same
as before. Note this pipeline decodes straight to RGB rather than writing a `.dng` file —
DNG is a container for these same raw bytes/metadata meant for external RAW processors,
and since fusion needs demosaiced pixels in-process anyway, that intermediate step is
skipped.

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

- `CameraBracketController.kt` — opens the camera, meters exposure/white-balance, computes
  per-step ISO/exposure-time from the EV/ISO-weight settings, fires manual captures
  (AE/AF locked off) at the sensor's max resolution (RAW or JPEG), and retakes frames the
  IMU flags as blurred.
- `RawDemosaic.kt` — RAW_SENSOR -> sRGB `Bitmap` pipeline (black level, white balance,
  Bayer demosaic, color correction, gamma), used when the device supports RAW capture.
- `ImageAligner.kt` — cross-correlation-based translational alignment/crop to remove
  hand-shake drift between bracket frames before fusion.
- `MotionMonitor.kt` — wraps the gyroscope/accelerometer to detect handheld shake for the
  anti motion blur gating above.
- `SaturationFusion.kt` — the argmax-saturation fusion core; parallelized by row-band.
- `MainActivity.kt` — preview, permission handling, wiring, saves the fused JPEG to
  `Pictures/HDRFusion` via MediaStore.

## Remaining known simplifications

- Black level uses a single static per-camera value (`SENSOR_BLACK_LEVEL_PATTERN`) rather
  than each frame's `SENSOR_DYNAMIC_BLACK_LEVEL`, which would track sensor drift (e.g. with
  temperature) more precisely frame-to-frame.
- Frame alignment is translation-only; it doesn't correct rotation, scale, or perspective
  changes, and can't deghost a moving subject (see above).
- The RAW demosaic doesn't reimplement the ISP's noise reduction, sharpening, or
  lens-shading (vignetting) correction.
