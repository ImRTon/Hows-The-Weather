# 衛星原圖固定線條遮罩

App 的「雲圖」只取開放資料即時原圖：台灣 `O-C0042-004` 為 800×800，東亞 `O-B0032-003` 為 1200×1200。透過 fileapi metadata 取得實際觀測時間、範圍及 ProductURL，再下載原始 JPEG；不讀衛星動畫清單，不下載雲圖歷史影格，也沒有歷史時間軸或播放。

API key 只以 Authorization header 傳送至 opendata.cwa.gov.tw；OkHttp 跨主機重新導向會移除該 header。metadata／原圖保留 ETag、Last-Modified 快取；網路與 metadata 解析在 IO，影像解碼、清理與投影在背景 dispatcher。動畫 parser 的單一 Matcher 修正保留給雷達歷史使用。

## 原圖版型資產

- `cloud_native_annotations_*.png`：固定海岸線、國界、座標文字與格線。以同版型跨時間的穩定白色細線建立，不無條件加粗。
- `cloud_native_exclusions_*.png`：標題、外框、底部座標文字及 CWA 標誌。台灣裁切矩形 `[5,36,795,778)`；東亞 `[1,55,1199,1176)`。
- `cloud_native_background_*.png`：跨時間樣本的低亮度單一底值，避免逐像素背景含有雲塊。

`native_taiwan.json` 與 `native_east_asia.json` 記錄原圖時間、SHA-256、樣本來源、裁切與判斷狀態。**目前各只有 4 個時間點，跨約 22 小時，mask 是樣本不足的初稿，不代表已通過充分的跨時段驗證。** 本機持續累積原圖，新的預覽不會自動覆寫 App 資產。`taiwan.json`、`east_asia.json` 的 144 張動畫訓練樣本僅保留為先前比較紀錄，App 不使用那些版型。

原圖與動畫資產名稱不同，sourceId 包含 `fileapi-native`；parser 會拒絕把動畫來源交給原圖 mask。即使台灣兩者同為 800×800，也不能互換。

## 原始解析度與輕量補值

完整解碼原圖，在原像素座標 inpaint 後裁掉標題／外框。台灣原生裁切為 790×742，東亞為 1198×1121；兩者都保留原始影像座標及裁切偏移再做投影。台灣採官方橫麥卡托（中央經緯度 121、23.7，左下 116.1417、19.0295，右上 126.2109、28.2065），東亞採官方 Lambert。分別輸出 800×800、1200×1200 經緯度網格，沒有降低格數；投影落在裁切外的區域為 NaN。metadata 的經緯度範圍只是輸出涵蓋範圍，不能當作原圖每列每欄等距經緯度。

固定遮罩預先建立每個線條像素上下左右最近的有效像素索引（最多搜尋 12 像素）；交點四向都找不到時改找四個對角。每張圖最多取四點平均，不迭代、不重建每幀積分表。原尺寸模板共用固定陣列，不縮放背景或 mask。補值以效能為優先；白線下原本的雲訊號無法由展示圖還原。

解析快取版本 13 排除先前動畫版型、降解析度雲圖，以及錯把台灣原圖視為等距經緯度矩形的舊網格。

## 本機工具

原圖累積與預覽：

```powershell
python -B scripts/review_cloud_masks.py --collect-once --build --native-only --allow-draft
python -B scripts/review_cloud_masks.py --watch --build --native-only --allow-draft
```

每 10 分鐘檢查一次，原始 JPEG 與 metadata 存在 `build/cloud-debug/native-archive`，不重新編碼、不重複計數相同觀測。這是本機程序，睡眠或結束時不會下載；不是 Android 背景工作或 Windows 開機服務。停止方式為建立 `build/cloud-debug/STOP_NATIVE_COLLECTOR`；重新啟動前先移除停止檔。

檢視 `build/cloud-review/latest_taiwan/overlay.png`、`latest_east_asia/overlay.png`：紅色是線條，黃色是裁切／排除區域。手動匯出原圖資產：

```powershell
python -B scripts/build_native_cloud_templates.py --allow-draft --output-dir app/src/main/res/raw --manifest-dir docs/cloud-templates
```

不足 12 個觀測或不足 6 小時時，必須明確加上 `--allow-draft`，manifest 保留初稿狀態。不得宣稱少量樣本已通過跨時段驗證。動畫工具 `generate_cloud_backgrounds.py` 現在預設只輸出 `build/cloud-debug/animation-templates`，不會覆寫 App 的原圖資產。

離線回歸：

```powershell
python -B -m unittest discover -s scripts -p 'test_*cloud*.py' -v
```

Android 測試涵蓋原圖來源、時間、URL、Authorization、尺寸及無動畫回退，雲圖歷史不下載，還有固定鄰點對每個新影格取值、原像素裁切與 Lambert 投影偏移。2026-11-10 實機動畫清單解析 ANR 的原始紀錄保存在 `build/freeze-debug/`；手機更新只能透過 Android Studio Run，不能以 adb 安裝。

原圖經緯線對點方法、限制與結果請見 [georeference-check.md](georeference-check.md)。
