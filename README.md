# How’s the Weather

Material 3 Android MVP for answering a practical question: **can I leave now, or when will the rain ease?**

The app renders CWA numerical weather grids itself. Radar and quantitative rainfall never fall back to CWA’s pre-colored Jet/rainbow images. The renderer uses a perceptually interpolated OKLab lookup table, bounded bilinear sampling, and value contours on Google Maps tiles.

## Run

1. Copy `local.properties.example` to `local.properties` and keep your existing `sdk.dir` entry.
2. Add an Android-restricted `MAPS_API_KEY`, optional cloud styling `MAP_ID`, personal `CWA_API_KEY`, and optional `MOENV_API_KEY` for current AQI.
3. Open the project in Android Studio and run the `app` configuration.

With `CWA_API_KEY`, the app loads the official transparent radar overlays, satellite images, and one-hour accumulated-rainfall grid. Without it—or when a request fails—it starts with deterministic numerical demo grids so the panel interaction, custom renderer, themes, timeline, wind markers, and decision engine remain testable. The fallback reason is shown in the app.

The map timeline plays observations chronologically from the previous 30 minutes to now, while the decision card remains based only on the official rainfall product.

## Data boundaries

- `F-B0046-001`: official one-hour accumulated-rainfall grid. It is a single field, not a native 0–60 minute sequence, so the app does not invent minute-level onset or easing times from it.
- `O-A0058-005` / `O-A0058-006`: official transparent, annotation-free radar layers for wide/local LOD. Their finite palette is decoded to dBZ bins; transparent or non-palette pixels remain missing.
- `O-B0032-003` / `O-C0042-004`: neutral infrared satellite imagery for East Asia/Taiwan LOD. The East Asia image uses CWA's documented Lambert projection; the Taiwan product uses its exact `GeoInfo` bounds. No global cloud image is downloaded.
- `O-A0001-001`: station wind observations.
- `F-D0047-*`: native three-day and one-week town forecasts. The UI preserves their 3-hour and 12-hour periods.
- `AQX_P_432`: Ministry of Environment hourly AQI observations from the nearest valid station; this is current air quality, not a future AQI forecast.

The current repository includes the verified CWA common-JSON parser, lower-left-origin row conversion, live source, and demo fallback. Production credentials stay outside version control. A public release should proxy CWA through a backend because an APK cannot keep that key secret.

## Important modules

- `domain/`: weather grid, decision types, and stable event-window rules.
- `render/`: Web Mercator tile generation, OKLab LUT, contours, and native-resolution grid cue.
- `data/`: staged current/history acquisition, bounded background image preprocessing, cache, and CWA parsers.
- `ui/`: resizable three-anchor decision/map home screen.

The staged CPU/GPU rendering proposal and measurement gates are documented in [`docs/rendering-performance-plan.md`](docs/rendering-performance-plan.md).

## Verification

```powershell
./gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin
```
