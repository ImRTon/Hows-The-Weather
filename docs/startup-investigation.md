# 重開 App 地圖載入調查（2026-11-08）

## 量測方式

使用已連線的實體手機（24129PN74G）、目前 debug 建置，由使用者透過 Android Studio Run 'app' 部署。加入 `WeatherStartup` 分段紀錄後，先開啟一次，再由使用者從最近使用清單滑掉並重新開啟。兩次程序 PID 不同（3758、6253），確認第二次是程序重新啟動。

所有裝置指令僅讀取 logcat、dumpsys、快取目錄或複製既有氣象快照；未以 adb 安裝、停止 App、清除資料或安裝測試套件。

時間零點是 Activity.onCreate 的紀錄點，不包含點擊圖示到 Activity 建立前的系統啟動時間。

## 實測結果

| 階段 | 診斷版首次開啟 | 滑掉後正常重開 |
| --- | ---: | ---: |
| 雲層素材同步初始化（主執行緒） | 416 ms | 408 ms |
| MapsInitializer.initialize 呼叫 | 83 ms | 72 ms |
| Google MapEffect 取得 map（距 Activity 建立） | 2,804 ms | 2,135 ms |
| 氣象快照還原整段等待，含 dispatcher／主執行緒排程 | 8,988 ms | 8,128 ms |
| 首批廣域雷達圖磚實際 renderTile 呼叫 | 3,144–3,225 ms/張 | 3,077–3,091 ms/張 |
| 後續廣域雷達圖磚 renderTile 呼叫 | 40–112 ms/張 | 31–77 ms/張 |
| onMapLoaded，中央載入提示移除（距 Activity 建立） | 9,213 ms | 10,436 ms |

首批圖磚並行執行，這些時間彼此重疊，不能逐項相加。renderTile 計時已在取得每個 tile 的 lock 之後開始，涵蓋取樣、著色、輪廓、Bitmap／PNG 作業與執行緒被排程暫停的時間；不包含等待同一 tile lock 的時間。另有 ART 紀錄其他 tileoverlay 執行緒等待該 lock 約 3.05 秒。

MapEffect 取得 map 代表 SDK 物件可使用，不表示道路、文字和氣象圖層已全部顯示。第二次約 9.09 秒還觸發八秒地圖 timeout，約 10.44 秒才收到完成回呼；不能將這次 timeout 視為金鑰／計費配置失敗。

## 已確認的原因

1. **有快取仍重做氣象運算。** 第二次核心雷達 metadata、雷達影像與 F-B0046-001 都記錄到 HTTP 304；內容沿用快取，但雷達重新解碼、預報重新解析，氣象圖磚也重新產生。兩次套用的廣域雷達觀測時間均為 2026-11-08 11:20（臺灣時間），不是因為換了一批雷達資料。
2. **已解析的磁碟快照來得太晚。** 兩次 `weather.snapshot hit=true`，但還原都在八秒以上才回到呼叫端，晚於網路核心資料，失去快速顯示快取的效果。現有實測涵蓋 IO 工作與 coroutine 排程；尚未把這八秒全歸因於磁碟或解壓。
3. **快照讀取結構有可重現的額外成本。** 真實快照約 628 KB，解壓後約 6.95 MB。程式以 `DataInputStream(GZIPInputStream(BufferedInputStream(file)))` 逐一讀浮點數；解壓後沒有 buffer。以相同快照在桌面 JDK 做逐 4 byte 讀取對照，原路徑約 148–189 ms；在 GZIP 後加 buffer 約 33–54 ms，checksum 相同。這證明改善方向，但桌面數字不能當作手機改善幅度。
4. **氣象素材先阻塞 UI。** 即使首屏是雷達，ViewModel 建立時仍同步讀衛星投影表、解碼背景圖並轉換像素，實機固定約 0.41 秒，發生在地圖開始建立前。
5. **載入提示等得比 SDK 就緒晚。** 中央提示以 `onMapLoaded` 為條件，不能由提示判斷是否正在下載 Google 圖資。實測 SDK 已可用後，提示仍持續八秒左右。

## 尚未證實的部分

- 尚未取得 Google Maps SDK 內部逐請求的網路／圖磚命中資料；CWA 的 OkHttp 診斷無法攔截 Google SDK 自己的請求。因此不能宣稱 Google 底圖每次全部重抓，也不能宣稱完全沒有重抓。
- logcat 出現 Java Cronet fallback 警告（HTTP caching 功能受限），但尚未證明它影響了哪些 Google 底圖請求或是否另有 SDK 圖資快取。不能單憑此警告斷定整套地圖快取失效。
- SDK 本身有主執行緒 client-parameters 等待：原版 307 ms，診斷版正常重開 164 ms。這是實際成本，但不足以單獨解釋十秒等待。
- 目前為 debug 建置，含 JIT／首次類別載入及 Studio 部署環境；尚無 release 對照，也未以停用氣象圖層做 A/B，因此尚不能把完整的 SDK-ready 到 onMapLoaded 間隔都歸因於氣象圖磚。

## 建議修正順序

1. 縮短快照解碼路徑，分離首屏必需與選用資料，讓有效快取在更新前可被顯示。
2. 將雲層素材改為背景延遲初始化。
3. 沿用相同版本的已解析 grid，並為自有氣象 PNG 圖磚考慮有界磁碟快取，減少程序重啟後的重算。
4. 避免首屏同時處理所有非必要氣象產品；保留啟用圖層及產品時效的規則。
5. 將底圖就緒、氣象更新及真正失敗分開呈現，避免完整繪製遲到就顯示配置錯誤。

本次僅加入 debug 診斷，未改變天氣判斷、快取時效、圖層呈現或載入提示條件。

## 驗證與資料位置

`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` 通過；159 個單元測試，0 failure、0 error。

原始 log 與本機對照程式位於已忽略的 `build/startup-investigation/`，不納入版本控制。`WeatherStartup` 不紀錄 URL、query、header、body 或座標；HTTP 僅記錄 host、產品代碼、cache/network 狀態。

## 第一批啟動改善（2026-11-08，尚待實機重測）

- `WeatherSnapshotCache` 在 GZIP 解壓之後、壓縮之前加上緩衝，避免每筆浮點數直接觸發壓縮串流作業。沿用版本 10 快照，既有快取不需作廢，時效與數值不變。
- `CwaWeatherDataSource` 改用執行緒安全的延遲 parser factory。ViewModel 建立時不再讀衛星投影表或解碼固定背景；第一次衛星請求在 IO dispatcher 初始化素材。
- 首次 refresh 讓快照先行，最多等待 750 ms 才啟動其餘氣象請求。沒有快取或快速完成時立即繼續；慢速磁碟超過上限時讓還原與網路並行，仍以 `criticalApplied` 防止舊快照蓋掉新資料。地圖建立不等待這個 suspend 工作。
- 保留現有 `WeatherStartup` 記錄，以便比較同一裝置的還原耗時、首批圖磚與 `onMapLoaded`。這一批尚未加入 PNG 磁碟快取、相同產品的解析重用，亦未更動載入提示條件，因此不能宣稱已消除全部十秒等待。

驗證：`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` 成功；160 個測試，0 failure、0 error。新增跨緩衝區的大型 grid 往返測試（含 NaN），並驗證目前天氣請求不會初始化衛星素材。本機 JDK 曾因 Unix-domain socket 暫存路徑發生 loopback 錯誤；僅在驗證程序設定 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Git_Projects\HowsTheWeather\build` 後成功，未修改專案 JVM 設定。

實機驗證尚未進行。需由使用者在 Android Studio 選擇已連線手機與 `app`，執行 **Run > Run 'app'**（Shift+F10）後，再重現「滑掉後重開」。比較前述指標，並確認 Google logo、道路與文字可見；桌面測試不能代替手機啟動量測。

## 第一批實機回測與第二批調整

使用者回報仍慢，重新讀取 debug 級別 `WeatherStartup` 紀錄（注意 `:I` 會濾掉這些紀錄）。PID 31230 的程序重開結果：快照還原 414 ms、Google map 物件就緒 1,581 ms、載入提示消失 8,536 ms，8,340 ms 時仍誤觸 timeout。第一批快照改善已生效，但沒有解決完整地圖繪製等待。原始記錄存於已忽略的 `build/startup-investigation/after-buffer-reopen.log`。

首批氣象 tile 約 2,417 ms；後續多數約 26–100 ms。啟動期間仍同時載入衛星、定量降水與已啟用的風場，衛星素材背景初始化這次含排程耗時達 4,153 ms。這些是重疊工作，不能相加，也不能據此斷言底圖下載花了多少時間。

第二批改為：Google 地圖首次 `onMapLoaded` 後才掛上氣象 tile overlays，將首張底圖完成與自有圖磚運算分離；若底圖一直無回呼，八秒後仍允許快取氣象圖層顯示。雲層／定量降水補充資料及風場等待首張底圖與決策預報完成，再開始載入；底圖等待以啟動後八秒為上限，後續刷新不重等。中央提示仍依實際完成回呼移除，未把 SDK 物件就緒當作道路已顯示。

第二批實機效果待 Android Studio 部署後驗證，不能沿用第一批測量作為改善後數字。

## 後續程式路徑追查（不執行測試、不操作裝置）

使用者回報第二批仍慢。本節只檢查目前原始碼與既有 `after-buffer-reopen.log`，不把舊紀錄當作第二批實測。

- 初始 target 固定臺北；viewport 只存 `SavedStateHandle`，沒有磁碟偏好還原。新的 task 沒有 saved state 時從臺北、zoom 12.075137 建立。`PersistentWeatherMap` 有定位權限便自動讀取位置，之後透過 `MapEffect(cameraRequest)` 播放 650 ms 相機動畫。定位不是建立地圖的前置等待，但可能讓首屏先後載入兩個範圍。
- 程式沒有把初始 zoom 設為 3。既有 PID 31230 紀錄在 1,581 ms 啟動相機動畫、2,364 ms 開始 zoom 3 圖磚；不能僅憑這些紀錄斷言 zoom 3 是動畫或 SDK 初始化哪一者造成。
- `WeatherTileProvider.preloadGrid` 所謂 viewport 實際是最近 16 個請求座標，沒有按目前可見範圍或縮放剔除。既有紀錄在 7,487 ms 又於 DefaultDispatcher 生成 zoom 3 圖磚，與這條預繪製路徑相符。過渡視角圖磚可能繼續參與後續資料更新的預繪製。
- 自有 PNG 圖磚只有 24 MiB 靜態記憶體 LRU，沒有磁碟圖磚快取。程序重啟即使位置、產品時間相同，也必須重新取樣、著色、畫輪廓並編碼 PNG。HTTP 快取和數值快照不等於 PNG 快取；網路取回快取內容後仍呼叫 parser。
- 中央轉圈僅由 `onMapLoaded` 結束，沒有獨立的道路／地名可見狀態。八秒逾時直接顯示配置錯誤，不是 SDK 回報的錯誤原因。
- 第二批的門控是 `onMapLoaded` 或八秒之後才掛氣象層。如果底圖完成通知本身遲到，這個改動會把氣象內容也延後至八秒，不能加速 SDK 本身；它只改變工作順序，沒有消除上述重算成本。

可定位的修正方向：在首張地圖建立時使用已知目標、避免初始自動定位的過場動畫；預繪製只涵蓋現行視口；為相同產品／渲染版本建立有界磁碟 PNG 快取；重新設計第二批的八秒門控及逾時訊息。Google 內部快取失效與各原因在第二批的耗時比例，不能由原始碼確定。

## 第三批修正

- 地圖建立時，若已有定位相機請求，直接用它作為初始 camera，並同時傳給 `GoogleMapOptions`。首屏尚未完成或裝置定位造成的移動使用 `moveCamera`，不播放啟動飛行動畫。尚未取得位置時仍以臺北為預設；這次沒有新增定位／viewport 的磁碟保存。
- 移除以最近 16 張請求推估 viewport 的 `preloadGrid`，由 SDK 依實際可見視角要求圖磚，避免後續天氣更新重算過渡視角。
- 新增 `cacheDir/weather-tiles` PNG 快取，上限 32 MiB／512 筆，記憶體快取仍保留。背景圖磚執行緒才計算完整格點與樣式 SHA-256；key 包含 renderer 版本與 z/x/y。使用暫存檔再更名，CRC 驗證損毀檔案，儲存失敗不阻止即時渲染。空白圖磚也可重用。
- 移除第二批以底圖完成或八秒作為氣象掛載與補充資料啟動條件的門控；補充資料仍等決策預報結束。轉圈仍依真實 `onMapLoaded`，逾時改為載入較久及檢查提示，不再宣稱已確認配置失敗。

依使用者要求不執行測試、不操作手機。`:app:compileDebugKotlin --console=plain` 成功；未執行單元測試、完整 APK 建置或 lint，尚無實機耗時數字。新 PNG 快取需第一次生成後才有可重用內容，資料或樣式改變仍會生成新圖磚。

## 第三批部署後：底圖仍慢的實機證據

使用者確認轉圈時道路、地名尚未出現，並授權讀取現有手機紀錄。2026-11-08 16:39:40 重啟 PID 32406：map 物件就緒 2,855 ms、onMapLoaded 8,302 ms；快照還原 1,861 ms。原始診斷存於 `build/startup-investigation/after-tile-cache.log`。

這個程序沒有 `weather.tile.render` 記錄，但有 `frameFingerprint` 鎖等待 396／401 ms，證明新版快取程式已執行；不能再以 PNG 重繪解釋完整八秒。Google SDK `TilePrep` 有 881 ms、163 ms、214 ms、236 ms 的鎖等待，`ClientParamsBlocking` 記錄主執行緒等待 557 ms，主執行緒多次跳過 118、109、43、34 幀。時間可能重疊，不相加。

同一程序啟動時有 `studio.deploy: Startup agent attached to VM` 與載入 `instruments-83583b27.jar`，並出現 Google SDK 的大型 JIT 編譯。這證明測量環境有 Studio 插樁，不證明八秒全由 Live Edit 造成。Google 官方亦提醒 Live Edit 修改的類別可能有效能成本：https://developer.android.com/develop/ui/compose/tooling/iterative-development#limitations-of-live-edit 。應先移除此變因再定位 SDK 內部剩餘耗時。

已修正本次新增的逐浮點數串流指紋計算：改用 FloatBuffer 分塊複製，再做 SHA-256，保留完整內容識別；新增 fingerprint 耗時與磁碟命中事件。renderer 版本升為 4（浮點數 NaN 原始表示可能不同），舊版 PNG 不混用。`:app:compileDebugKotlin` 通過，未執行測試或部署，尚不能宣稱實機時間改善。

## 指紋修正後的回測與無插樁對照版本

最新 PID 10254（16:43:51）仍於 7,897 ms 收到 onMapLoaded；map 物件就緒 2,202 ms。指紋計算 12–14 ms，3,557–3,577 ms 已有多筆 zoom 12 磁碟命中，沒有 tile.render；衛星初始化在 8,542 ms 才開始，不能解釋這次首屏延遲。前一次 PID 1656 為 6,021 ms，顯示啟動時間也有波動。紀錄位於 `build/startup-investigation/latest-startup.log`。

PID 10254 仍載入 `startup_agents/caf3f227-agent.so`、`instruments-83583b27.jar`，並記錄 `Applying transforms with cached classes`。不能宣稱這次已排除 Studio 插樁，也不能斷言它是全部延遲原因。

新增 `performance` build type：release 設定、不可偵錯、不縮減程式、相同 applicationId 與 debug signingConfig，保留 WeatherStartup 計時。用途是排除偵錯／JVMTI 插樁，不是已證實的修復。由 Android Studio Gradle Sync 後在 Build Variants 選 `performance`，仍透過 Run 'app' 更新原 App；不要卸載、清資料或安裝額外套件。未執行測試或命令列安裝。
