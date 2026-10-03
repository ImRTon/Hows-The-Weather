# How’s the Weather — Project Guidance

## Product intent

This Android app should answer a practical question before the user leaves: **will it rain, and is waiting likely to help?** The primary experience is a decision aid, not a professional meteorological console. Raw weather layers remain available for users who want to inspect the evidence behind the answer.

The product is currently a Traditional Chinese, phone-first personal MVP built with Kotlin, Jetpack Compose, Material 3, and Google Maps Compose.

## Current design

### Home composition

The home screen is one vertically split workspace:

- The upper panel is the decision card.
- The lower panel is the interactive Google map.
- A 48 dp accessible drag handle (and the decision card itself) continuously resizes the panels and settles at three anchors. Anchor heights are measured from the card content, not screen fractions, so every device wraps the same content without device-dependent padding; each is capped so the map keeps at least 180 dp:
  - `Decision`: exactly fits the expanded content (headline, priority cards, 12-hour strip, current observation).
  - `Balanced`: exactly fits the headline and the three priority forecast cards.
  - `Map`: decision card collapses to a 48 dp one-line summary, with the rain probability aligned to the right.
- Release physics: a slow release springs to the nearest anchor; a fling advances at least one anchor in its direction and may skip further when its decay projection reaches it. The settle spring inherits the release velocity, and a new touch interrupts it in place.
- Drag and settle frames must not recompose the map: read the live panel height only in layout/draw lambdas. Summary/expanded content crossfades from the live height rather than switching at anchors.
- Tapping the handle cycles through the same anchors.
- Keep the visual divider between the decision card and map continuous across the full width; the short drag bar overlays that divider without creating a black gap.
- Preserve the selected anchor and theme through state restoration.

The decision card leads with one plain-language conclusion, followed by source time and supporting detail. It should never require users to decode a heatmap before knowing whether leaving is sensible.

### Map experience

- Default target is Taipei until device location is granted; long-pressing the map selects another target.
- The base map is chosen at build time with `MAP_PROVIDER` (`google` default, or `osm`) in `local.properties`. There is no in-app switch. `PersistentWeatherMap` stays provider-neutral through `WeatherBaseMap`; SDK-specific code lives only in `GoogleWeatherMap.kt` and `OsmWeatherMap.kt`, and both consume the same `WeatherTileProvider` tiles.
- The OSM backend (osmdroid) uses `tilesScaledToDpi` so its zoom numbers match Google's 256 dp world, keeps the configured tile attribution visible, and uses separate light/dark tile URLs instead of recoloring tiles.
- The target marker, decision card, selected weather grid, and time control must always refer to the same location and effective time.
- Primary layers are mutually exclusive:
  - `降雨雷達`: official numerical radar at “now,” official one-hour accumulated rainfall for the future product.
  - `雲層 β`: neutral cloud rendering and experimental motion extrapolation.
- Wind is an independent optional overlay.
- Map controls must remain compact and must not obscure the decision target or timeline.
- In `DEMO_MAP_ID` mode, do not pass a Map ID to Google Maps; use the bundled JSON map style. With a real Map ID, use Cloud Styling and do not combine it with bundled `MapStyleOptions`.
- If the base map has not loaded after a reasonable timeout, show a specific Maps SDK configuration message rather than leaving an unexplained blank surface.

### Status and caption policy

- Successful data-source information must not appear as a persistent snackbar.
- Snackbars are for transient actions and actionable failures; clear them after display.
- Do not place long captions over the map.
- Show the short resolution badge (`雷達原始格點約 1.25 km`) only at street-level zoom (`zoom >= 11`).
- Experimental or demo provenance must appear only when the affected layer is active.
- Prefer direct labels such as `未來 1 小時 · 累積預報` over technical internal terminology.

## Design philosophy

### Decision first, evidence second

The app should answer the user’s question before exposing controls. Weather layers explain the decision; they do not replace it. Collapsing either panel must preserve enough context to understand what is selected.

### Honest precision

Google Maps can zoom to buildings, but the weather data cannot. Never imply street-level forecast accuracy from map zoom or smooth interpolation.

`F-B0046-001` is one official **future-one-hour accumulated rainfall grid**, not a native 0–60 minute sequence. Do not invent minute-level onset or easing times from it. The live decision therefore reports the one-hour accumulated amount and explicitly states the temporal limitation. Minute windows are permitted only when a real validated time series supports them.

Cloud optical-flow output is visual guidance. It must never feed the official rainfall decision or reminder logic, and must always be labeled experimental.

### Calm information density

The interface should feel precise and technical without behaving like a dense dashboard. Use progressive disclosure: headline first, chart and controls when space allows, legend only on demand. Avoid persistent explanatory banners, decorative telemetry, and duplicated source labels.

### Theme identity

- Dark mode uses deep navy surfaces rather than pure black, cool cyan interaction accents, restrained map detail, and high-contrast weather fields.
- Light mode uses warm off-white surfaces, graphite text, deep teal accents, and reduced POI noise.
- Brand colors are fixed; do not enable Material You dynamic color because weather semantics must remain stable.
- Light and dark weather palettes share physical thresholds and hue meaning. Adjust contrast, opacity, and contour treatment—not the meaning of a color.

### Accessibility is structural

- Touch targets are at least 48 dp.
- The panel handle exposes button semantics and a spoken description.
- Weather intensity must be communicated with text, units, and shape/position in addition to color.
- Maintain readable contrast over both map themes and translucent surfaces.

## Weather-data integrity

### Official live sources

- `O-A0059-001`: QPESUMS numerical radar reflectivity (`dBZ`).
- `F-B0046-001`: official future-one-hour accumulated rainfall (`mm/1h`).
- `O-C0042-008`: intended satellite source; the current cloud UI still uses demo frames until the live image pipeline is connected and verified.
- `O-A0001-001`: intended wind source; current wind observations are demo data until the live station adapter is connected and verified.

When `CWA_API_KEY` is present, `HomeViewModel` selects the live CWA source. If download or parsing fails, the app may fall back to deterministic demo grids, but it must show the actual failure reason and must not present demo data as official.

### Coordinates and missing values

- CWA JSON grid rows arrive from south to north; `WeatherGrid` stores north-first rows. Preserve this conversion.
- Missing sentinels such as `-99` and `-999` become `Float.NaN`. Missing data is transparent and must never be interpreted as zero rain.
- Validate width, height, value count, bounds, unit, source ID, and effective time for every grid.

### No Jet heatmaps

Radar and rainfall production paths must consume numerical grids. Never download or display CWA’s pre-colored Jet/rainbow images as a fallback.

The renderer uses:

- bounded bilinear interpolation only between adjacent source cells;
- a 256-entry OKLab-interpolated LUT;
- cold cyan/blue for weak values, then violet, magenta, orange, and warm light tones for stronger values;
- marching-style threshold contours from the same numerical grid;
- transparent pixels for values below the visible threshold or outside valid data.

Physical stops:

- Radar: `5, 15, 25, 35, 45, 55, 65 dBZ`; contours at `20, 35, 50 dBZ`.
- Rain: `0.1, 0.5, 2.5, 10, 25, 50, 100`; contours at `2.5, 10, 40` using the displayed rain unit.

Do not change the decision result based on palette, opacity, contours, rendered pixels, cloud extrapolation, or wind visualization.

## Architecture expectations

- Keep domain decisions independent of Android graphics and Compose.
- `WeatherDataSource` owns acquisition; parsers own schema conversion; `WeatherGrid` owns numerical sampling; `WeatherTileProvider` owns map rendering.
- The decision engine accepts numerical forecast values only.
- `HomeUiState` is the single UI source of truth for target, time, panel anchor, layers, active grid, decision, and transient message.
- Keep CWA and Google credentials in `local.properties`; never commit them. Remember that a CWA key embedded in an APK is recoverable, so a public release requires a backend proxy.
- Keep live and demo provenance explicit in the data model.

## Validation requirements

Before handing off a change, run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

### Physical-device installation policy

- Install or update the app on a phone **only through Android Studio**: select the connected device, choose the `app` run configuration, then use **Run > Run 'app'** (`Shift+F10`). This is the required device-install instruction for the current debug APK.
- Never install, update, downgrade, or remove the phone app through `adb install`, `adb uninstall`, `pm install`, `pm uninstall`, Gradle `installDebug`, `bundletool`, a file manager, or any other non-Android-Studio path.
- Never run `connectedAndroidTest`, `connectedDebugAndroidTest`, or another workflow that installs an instrumentation/test APK on the phone. Do not create or install an extra test app/package for device verification, because it can replace, remove, or disturb the primary app installation.
- Command-line device access is limited to read-only diagnostics such as `adb logcat`, `dumpsys`, and UI inspection. It must not mutate installed packages or app data.
- The Gradle validation command above may assemble an APK, but it does not authorize installing that APK. After a build, the user must deploy it through Android Studio `Run 'app'` before device verification continues.

At minimum, preserve tests for:

- CWA lower-left-origin parsing and row reversal;
- missing sentinels remaining missing;
- bilinear sampling and out-of-bounds behavior;
- dry, rain, stable decrease, continuing rain, and unavailable decisions;
- the official hourly product never inventing a minute event window.

For map-related changes, also verify on a device that the Google logo, roads, and labels load before judging weather overlay alignment. Android key restrictions for the current debug build use package `com.rton.howstheweather`; obtain the active SHA-1 with `./gradlew.bat :app:signingReport` rather than hard-coding a certificate fingerprint in documentation.
