# 本機 mask 預覽與原圖累積

> 2026-11-10 已確認改回開放資料即時原圖（台灣 800×800／東亞 1200×1200），只顯示最新一筆。本機恢復累積原圖；App 使用 cloud_native_* 資產，動畫遮罩僅保留比較。現行來源與匯出步驟請見 [README.md](README.md)。

先前比對階段只使用 `scripts/review_cloud_masks.py`，不寫入 Android 程式或 `app/src/main/res`。四個版型各自產出，禁止用動畫遮罩套最新原圖，即使台灣尺寸相同。

| 本機輸出目錄 | 來源 | 原始尺寸 |
| --- | --- | --- |
| `build/cloud-review/latest_taiwan` | 公開 S3 `O-C0042-004` 原圖 | 800×800 |
| `build/cloud-review/latest_east_asia` | 公開 S3 `O-B0032-003` 原圖 | 1200×1200 |
| `build/cloud-review/history_taiwan` | 官網 `TWI_IR1_Gray_800` 歷史動畫 | 800×800 |
| `build/cloud-review/history_east_asia` | 官網 `LCC_IR1_Gray_1000` 歷史動畫 | 1000×1000 |

原圖累積：

```powershell
python -B scripts/review_cloud_masks.py --collect-once
python -B scripts/review_cloud_masks.py --watch --build --allow-draft
```

`--watch` 在這台電腦每 10 分鐘檢查一次，電腦睡眠、關機或程序結束期間不會下載；沒有註冊開機服務或 Windows 排程。停止方式是在專案內建立 `build/cloud-debug/STOP_NATIVE_COLLECTOR` 檔案，程序會在一秒內察覺；正在進行的下載或 mask 計算會先完成。重新執行前移除該停止檔。關閉終端不保證已脫離終端啟動的程序會停止。

原始 JPG 位元組與來源 metadata 存在 `build/cloud-debug/native-archive/<dataset>/`，檔名以影像觀測時間區分。保存下載時間、來源、原尺寸及 SHA-256。相同觀測不重複計數；下載前後 metadata 改變時放棄該次樣本，下次重試。使用公開 S3 metadata 與影像，不需要 API key。下載前後 metadata 一致可排除常見更新競態，但不能保證上游兩個檔案的發布是原子操作；原始 metadata 一併留下供檢查。

歷史動畫目前使用 `taiwan.json`、`east_asia.json` 記錄的 144 個路徑與本機原始下載快取；不把它們複製進最新原圖的 archive。

離線重建與測試：

```powershell
python -B scripts/review_cloud_masks.py --build --allow-draft
python -B -m unittest discover -s scripts -p 'test_*cloud*.py' -v
```

每個輸出目錄包含：

- `annotations.png`：白色為要 inpaint 的固定線條，不無條件加粗。
- `exclusions.png`：白色為裁除的標題／外框／底部座標文字，以及另外排除的 CWA 標誌。
- `overlay.png`：原圖上紅色顯示線條 mask，黃色顯示排除區域；保持原始寬高。
- `overlay_cropped.png`：依各版型的像素矩形實際裁切，保持每個原像素，不 resize。
- `manifest.json`：原始樣本、觀測時間、樣本數、裁切座標、涵蓋率及狀態。

不足 12 個不同觀測時間、或跨度不足 6 小時時，預設拒絕製作 mask。只有明確指定 `--allow-draft` 才輸出 `DRAFT_INSUFFICIENT_HISTORY` 初稿。達門檻也只代表可供目視審核，仍須檢查正常雲層誤入遮罩及線條漏抓，不會自動套進 Android。累積的 JPG 全部保留；計算最多均勻選取 144 張，限制記憶體用量。

裁切矩形以原圖像素記錄；目前僅作預覽。未來接 App 時，裁切後必須保留相對於原始影像的偏移和投影對應，不能把裁掉標題後的圖重新拉伸到原地理範圍。
