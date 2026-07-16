# Rendering and loading performance plan

## Product constraint

Radar and cloud playback is observation-only: `-90` through `0` minutes at 10-minute intervals. No motion estimate, wind-assisted advection, or rendered pixel may become future weather or decision input.

## Implemented CPU pipeline

The loading path is split by user-visible priority and never ties the global loading state to optional layers:

1. Race a lightweight last-known-good snapshot against the live request; cache decompression must never delay the first live radar request. The startup cache retains current frames and wind, not the 90-minute animation history.
2. Download, decode, and publish the current wide radar by itself, then end the global loading state. It does not wait for the regional radar or official one-hour forecast.
3. Load the official one-hour decision forecast and regional radar in separate high-priority jobs. Radar LOD automatically switches between wide and regional products using zoom hysteresis and coverage bounds; while regional data is pending, the visible wide radar remains in place.
4. Load only current cloud frames and quantitative precipitation as cancellable background enrichment. The four quantitative products use two-way bounded parallelism.
5. Load the 90-minute radar or cloud history only when playback or a negative timeline position requests it. A radar interaction preempts lower-priority enrichment; cloud history may begin as soon as a current cloud frame exists.
6. Persist the wind switch. When it was on, load wind immediately; otherwise reuse cache and start a delayed idle preload. Live wind emits CWA station observations first and upgrades to the WRF model grid when ready.
7. Bound historical downloads to three and bitmap/GRIB preprocessing to two `Dispatchers.Default` workers so preprocessing cannot saturate every core.
8. Give every HTTP call a total timeout and coroutine cancellation hook. Cap the entire first live radar load at 15 seconds; on failure retain the last official cache or report the layer unavailable.
9. Merge independently completed official observations in `HomeViewModel`, preserving compatible cached history and preferring model wind over station wind, so completion order cannot regress the visible state.

The first satellite optimization pass additionally:

- downsamples source bitmaps during decode to at most twice the requested grid dimension;
- caps the Taiwan cloud output at 400 cells on its longest axis instead of processing the source at full output resolution;
- caches the global geostationary-to-grid pixel lookup by image/grid geometry;
- selects only the required lower-quartile rank instead of sorting all 25 neighborhood samples;
- publishes global and Taiwan current cloud frames independently, then applies cross-LOD tone harmonization;
- debounces and serializes full snapshot cache writes instead of rewriting after every enrichment event.

Cloud acquisition is Taiwan-first. The current Taiwan-and-surroundings frame is published before the current East Asia frame; historical frames do not start until the user requests playback or history. East Asia is processed at a maximum grid dimension of 320. The former global full-disk product is no longer downloaded. `O-B0032-003` supplies the smaller official East Asia black-and-white IR image. Its fixed 800-to-320 Lambert pixel lookup is generated during the Android build and bundled as a read-only resource; runtime reprojection remains only as a dimension/bounds compatibility fallback.

WRF GRIB decoding now projects the Taiwan bounds into the Lambert source grid before unpacking values. It downloads the two required U/V messages in parallel but unpacks only the bounded Taiwan window, with row-level cancellation checks, rather than allocating and decoding both full model grids.

Wind particles no longer project screen coordinates and sample the wind grid again for every particle on every animation frame. Particle position/vector/color data is rebuilt only when the map, canvas, or wind source changes; steady animation runs at 30 fps with a two-segment trail. This keeps the persisted-on wind state responsive without restoring the former per-frame computation.

The production tile renderer already uses the 256-entry OKLab LUT, low-zoom preview tiles, request coalescing, and an encoded-tile LRU cache. GPU work remains a measured follow-up because Google Maps still requires PNG tile bytes.

The two-worker limit is an initial safety value, not a permanent tuning constant. Measure it on a low/mid-range physical device before raising it. Memory pressure matters more than peak throughput because each decoded image temporarily owns compressed bytes, a bitmap, an ARGB array, and a float grid.

## Measurement gates

Add release-like Macrobenchmark and Perfetto traces before another renderer rewrite. Record cold and warm runs separately and report p50/p95 for:

- process start to decision card;
- process start to current radar;
- layer tap to first cloud/wind visual;
- historical frame switch to visible tiles;
- bitmap decode, image-to-grid conversion, satellite reprojection, tile sampling, contour generation, and PNG encoding;
- peak Java/native heap, dropped frames, and cache hit rate.

Targets for the first optimization pass on the chosen reference device:

- cached decision/current radar visible in under 1 second;
- network current radar visible without waiting for any history, cloud, or wind request;
- layer switching shows a cached frame within one rendered frame and an uncached viewport within 500 ms;
- no main-thread image conversion, grid harmonization, tile rendering, or cache compression.

### 2026-07-15 physical-device check

- Debug cold Activity launch reported by Android: 749 ms with existing app data retained.
- After startup enrichment settled, the process and every `DefaultDispatcher` worker sampled at 0% CPU.
- The previous failure signature—one `DefaultDispatcher` worker remaining near a full core for minutes—was not reproduced.
- After the radar publication regression fix, the on-device startup cache settled at about 642 KiB and the radar range control visibly exposed `廣域雷達 ↔ 區域雷達`.
- A subsequent thread-level trace identified the persisted wind animation—not radar/cloud acquisition—as the remaining steady UI load. After precomputation, the 12.5 fps baseline produced a short visible-window process sample of about 0.3% CPU on the same device; the user-facing target is now 30 fps and should be re-profiled separately.

## GPU feasibility

The current Google Maps `TileProvider` boundary returns encoded PNG bytes. A GPU can shade a tile, but reading it back and PNG-encoding it can erase much of the gain. Therefore GPU work should begin as an instrumented prototype, not a direct production replacement.

### Prototype A — optimized CPU tiles (baseline)

- Keep numerical `WeatherGrid` as the source of truth.
- Replace per-pixel geographic sampling with per-tile affine increments and direct array access.
- Store contour samples in one flat `FloatArray`, pool pixel buffers, and cache by grid/time/style/tile.
- Coalesce identical in-flight tile requests and prioritize visible zoom tiles.

This baseline is lower risk and establishes whether GPU complexity is justified.

### Prototype B — OpenGL ES offscreen tile shader

- Upload the numerical grid as a single-channel float/normalized texture and the 256-color OKLab LUT as a 1D texture.
- In a fragment shader, perform bounded bilinear interpolation, missing-value transparency, threshold coloring, opacity, and contour edge detection from the same numerical values.
- Render only requested 256×256 tiles into an FBO.
- Read back and encode PNG because Google Maps still consumes tile bytes.
- Cache encoded output and reuse textures across frames.

Adopt this only if traces show sampling/contours dominate and GPU render plus readback/encoding beats the optimized CPU baseline at p95 without increasing memory instability.

### Prototype C — GPU-composed geographic overlay

Render one regional texture and present it as a geographic ground overlay, avoiding per-tile PNG work. This is attractive for the rectangular Taiwan radar/cloud products, but it must prove correct map projection, zoom quality, labels/roads visibility, and overlay ordering. The East Asia Lambert product would still require explicit reprojection before it could use the same path.

A custom GL surface synchronized to the Google Maps camera is the final option. It offers the cleanest all-GPU path but has the highest gesture, lifecycle, accessibility, and projection risk; pursue it only if the first two prototypes fail the latency target.

## Rollout

1. Land tracing and the optimized CPU baseline.
2. Benchmark on one low-range and one mid-range device with radar contours on/off and cloud global/Taiwan LOD.
3. Build Prototype B behind a developer flag; compare visual output pixel-by-pixel against the CPU renderer at threshold boundaries and missing cells.
4. Try Prototype C only for regional products if PNG readback remains the bottleneck.
5. Keep CPU rendering as a fallback for unsupported GPU/driver combinations.

No GPU path may change physical thresholds, palette meaning, missing-value semantics, decision input, or the observation-only timeline.
