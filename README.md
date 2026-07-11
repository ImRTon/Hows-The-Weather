# How’s the Weather

Material 3 Android MVP for answering a practical question: **can I leave now, or when will the rain ease?**

The app renders CWA numerical weather grids itself. Radar and quantitative rainfall never fall back to CWA’s pre-colored Jet/rainbow images. The renderer uses a perceptually interpolated OKLab lookup table, bounded bilinear sampling, and value contours on Google Maps tiles.

## Run

1. Copy `local.properties.example` to `local.properties` and keep your existing `sdk.dir` entry.
2. Add an Android-restricted `MAPS_API_KEY`, optional cloud styling `MAP_ID`, and personal `CWA_API_KEY`.
3. Open the project in Android Studio and run the `app` configuration.

With `CWA_API_KEY`, the app loads the official numerical radar and one-hour accumulated-rainfall grids. Without it—or when the request fails—it starts with deterministic numerical demo grids so the panel interaction, custom renderer, themes, timeline, wind markers, and decision engine remain testable. The fallback reason is shown in the app.

## Data boundaries

- `F-B0046-001`: official one-hour accumulated-rainfall grid. It is a single field, not a native 0–60 minute sequence, so the app does not invent minute-level onset or easing times from it.
- `O-A0059-001`: numerical QPESUMS radar reflectivity.
- `O-C0042-008`: satellite imagery, remapped to neutral luminance; extrapolated cloud frames are explicitly experimental.
- `O-A0001-001`: station wind observations.

The current repository includes the verified CWA common-JSON parser, lower-left-origin row conversion, live source, and demo fallback. Production credentials stay outside version control. A public release should proxy CWA through a backend because an APK cannot keep that key secret.

## Important modules

- `domain/`: weather grid, decision types, and stable event-window rules.
- `render/`: Web Mercator tile generation, OKLab LUT, contours, and native-resolution grid cue.
- `cloud/`: OpenCV Farneback optical-flow extrapolator; its output is never accepted by the decision engine.
- `ui/`: resizable three-anchor decision/map home screen.

## Verification

```powershell
./gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin
```
