# Rendering and loading performance plan

## Product constraint

Radar and cloud playback is observation-only: `-30`, `-20`, `-10`, then `0` minutes. No motion estimate, wind-assisted advection, or rendered pixel may become future weather or decision input.

## Implemented CPU pipeline

The loading path is split by user-visible priority:

1. Download and decode the current wide radar, current local radar, and official one-hour rainfall concurrently.
2. Publish those critical fields immediately.
3. In independent coroutines, load radar history, cloud imagery, and wind. Each completed family is emitted without waiting for the slowest family.
4. Download historical frames concurrently. Bound bitmap decoding, satellite reprojection, and LOD harmonization to two `Dispatchers.Default` workers so network work does not occupy CPU workers and preprocessing cannot saturate every core.
5. Generate deterministic demo supplements only if a live supplemental source actually fails.

The first satellite optimization pass additionally:

- downsamples source bitmaps during decode to at most twice the requested grid dimension;
- caches the global geostationary-to-grid pixel lookup by image/grid geometry;
- selects only the required lower-quartile rank instead of sorting all 25 neighborhood samples;
- publishes global and Taiwan current cloud frames independently, then applies cross-LOD tone harmonization;
- debounces and serializes full snapshot cache writes instead of rewriting after every enrichment event.

Cloud acquisition is Taiwan-first. As soon as the current Taiwan-and-surroundings frame is published, the current East Asia frame and Taiwan history start in parallel; the current East Asia result is published before either region's remaining history. East Asia is processed at a maximum grid dimension of 320. The former global full-disk product is no longer downloaded. `O-B0032-003` supplies the smaller official East Asia black-and-white IR image. Its fixed 800-to-320 Lambert pixel lookup is generated during the Android build and bundled as a read-only resource; runtime reprojection remains only as a dimension/bounds compatibility fallback.

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
