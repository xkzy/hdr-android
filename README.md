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

## Files

- `CameraBracketController.kt` — opens the camera, computes per-step ISO/exposure-time
  from the EV/ISO-weight settings, fires manual captures (AE/AF locked off), decodes JPEGs.
- `SaturationFusion.kt` — the argmax-saturation fusion core; parallelized by row-band.
- `MainActivity.kt` — preview, permission handling, wiring, saves the fused JPEG to
  `Pictures/HDRFusion` via MediaStore.

## Known simplifications (call these out if you productionize this)

- No image alignment/deghosting — assumes a static scene/tripod, since fusion is strictly
  per-pixel across frames of identical framing (that's why fx is locked).
- Base exposure time is a hardcoded default rather than a metered auto-exposure read; wire
  up a short AE-converge pass before the bracket for real scenes.
- Still-capture size is fixed at 1920x1080 for simplicity; swap in the sensor's max JPEG
  size from `StreamConfigurationMap` for full resolution.
- JPEG (8-bit) capture is used for simplicity; for real HDR headroom, capture RAW/DNG
  (`ImageFormat.RAW_SENSOR`) instead and demosaic before fusing.
