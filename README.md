<div align="center">

# How’s the Weather

### 不只報數字，把雨畫給你看

一款為台灣日常移動打造的 Android 視覺化天氣判讀 App。<br>
先用摘要抓住重點，再把雷達、雨量、雲層與風場直接疊在地圖上。<br>
不讓冰冷的數字替你做決定，而是讓你看見天氣，自行判斷現在是否適合出門。

`Kotlin` · `Jetpack Compose` · `Material 3` · `Google Maps` · `CWA Open Data`

[核心體驗](#核心體驗) · [畫面巡覽](#畫面巡覽) · [開始使用](#run) · [資料邊界](#data-boundaries)

</div>

<table>
  <tr>
    <td width="33.33%" align="center">
      <img src="docs/Image00005.jpg" alt="展開的出門決策卡與雷達地圖" width="260"><br>
      <strong>重點先行</strong><br>
      <sub>摘要快速抓方向，最終判斷留給你</sub>
    </td>
    <td width="33.33%" align="center">
      <img src="docs/Image00007.jpg" alt="未來一小時累積雨量與地圖" width="260"><br>
      <strong>雨區看得見</strong><br>
      <sub>從地圖直觀看懂雨在哪裡、離你多近</sub>
    </td>
    <td width="33.33%" align="center">
      <img src="docs/Image00002.jpg" alt="逐時與本週天氣預報" width="260"><br>
      <strong>數字有脈絡</strong><br>
      <sub>用逐時與本週預報補足地圖之外的細節</sub>
    </td>
  </tr>
</table>

## 核心體驗

<table>
  <tr>
    <td width="50%">
      <strong>01 · 視覺判斷，不是數字堆疊</strong><br>
      <sub>把氣象網格轉成地圖 overlay，雨區、強弱與空間關係不必靠一串數字想像。</sub>
    </td>
    <td width="50%">
      <strong>02 · 以你的目的地為中心</strong><br>
      <sub>地圖標記、摘要、天氣圖層與時間軸始終指向同一位置，判斷才真正與行程有關。</sub>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <strong>03 · 看見雨勢如何變化</strong><br>
      <sub>回看雷達觀測，再切換一小時與 0–48 小時雨量，用時間脈絡理解雨勢走向。</sub>
    </td>
    <td width="50%">
      <strong>04 · 多種 overlay，一張熟悉的地圖</strong><br>
      <sub>區域雷達、累積雨量、雲層與風場疊加在 Google Maps 上，直接對照道路與地點。</sub>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <strong>05 · 摘要是提示，不是命令</strong><br>
      <sub>一句話先整理重點，同時保留完整視覺證據，讓使用者自己做最後判斷。</sub>
    </td>
    <td width="50%">
      <strong>06 · 資訊密度由你決定</strong><br>
      <sub>拖曳摘要面板，在完整資訊與大地圖間自然切換；資料精度與來源也清楚標示。</sub>
    </td>
  </tr>
</table>

## 畫面巡覽

<table>
  <tr>
    <td width="50%" align="center">
      <img src="docs/Image00006.jpg" alt="收合決策卡後的大地圖模式" width="360"><br>
      <strong>可調整的決策卡與地圖</strong><br>
      <sub>收合後保留摘要與降雨機率，把更多空間交給可互動地圖。</sub>
    </td>
    <td width="50%" align="center">
      <img src="docs/Image00001.jpg" alt="雲層、風場與歷史時間軸" width="360"><br>
      <strong>多圖層天氣證據</strong><br>
      <sub>切換雷達、雨量與雲層，並獨立疊加風向、風速和粒子風場。</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <img src="docs/Image00004.jpg" alt="目前雷達觀測與過去九十分鐘時間軸" width="360"><br>
      <strong>回看雨區如何移動</strong><br>
      <sub>透過播放控制與過去 90 分鐘時間軸檢視雷達觀測。</sub>
    </td>
    <td width="50%" align="center">
      <img src="docs/Image00003.jpg" alt="十二至二十四小時累積雨量預報" width="360"><br>
      <strong>中短期累積雨量地圖</strong><br>
      <sub>以預報區間、數值色階、等值線和原始格點提示判讀雨量。</sub>
    </td>
  </tr>
</table>

---

## Visual weather, numerical integrity

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

## Verification

```powershell
./gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin
```
