# 冥想音檔播放器 — 專案指南

給下一個接手的人／AI。這份文件只寫**讀程式碼看不出來的東西**：為什麼這樣設計、
哪些地方動了會壞、以及已經量測過的事實（不要重新猜）。

---

## 1. 這個 app 解決什麼問題

一段引導式冥想音檔，使用者想在某幾個段落之間**插入 5–50 分鐘的靜默**讓自己實際打坐，
靜默結束後音檔自動繼續。

用音訊編輯軟體「真的」插入靜音會產生幾百 MB 的新檔、破壞原檔、也無法快速切換要不要留白。

**所以核心設計是「虛擬插入」**：原始音檔完全不動，插入點只是一份設定，
播放時才在播放器的時間軸上組出「音訊 → 靜音 → 音訊」。

---

## 2. 三個不變量（破壞其中任一個都會出現難查的位置偏移 bug）

### 2.1 `Gap.atMs` 永遠是「原始時間軸」的毫秒
不是插入後的位置。這讓使用者增刪其他插入點、或切換總開關時，既有插入點都不會漂移。

### 2.2 兩條時間軸要分清楚
| 名稱 | 意義 | 誰在用 |
|---|---|---|
| **original** | 原始音檔的時間 | DB 的 `Gap.atMs`、波形 X 軸、seek 的輸入 |
| **effective** | 插入靜默後播放器看到的時間 | `player.currentPosition` / `duration`、進度顯示 |

換算只能透過 `EffectiveTimeline.toOriginal()` / `toEffective()`，不要自己算。
`toOriginal` 是**有損**的（靜默段內所有位置都映射到該插入點），這是刻意的。

### 2.3 重建播放來源時，位置要「用舊時間軸換回 original，再用新時間軸換回去」
`PlayerViewModel` 為此保留 `loadedTimeline` 欄位，跟 UI 的 `state.timeline` **分開存** ——
因為 `observeTrack` 會在重建前就把 state 更新成新時間軸。
（這裡踩過一次 bug：每次編輯插入點播放位置就偏掉。）

---

## 3. 為什麼是 `ConcatenatingMediaSource2`（整個方案的關鍵）

Javadoc：*"Concatenates multiple MediaSources, combining everything in one single Timeline.Window."*

合併成**單一 window** 的結果：
- `player.duration` **自動就是**插入後的總長 → 需求「看到插入後總播放時間」不用寫任何程式
- `player.currentPosition` 是全域位置 → seek bar 不需換算
- `player.currentTimeline.windowCount == 1`（`PlaybackEngineTest` 有斷言守著）

組裝在 `playback/MediaSourceBuilder.kt`：`ClippingMediaSource`（音訊切片）
+ `SilenceMediaSource`（靜默）依序 `add()`。

⚠️ **每個切片都要獨立的 `MediaSource` 實例**，同一個物件不能掛在多個 parent 下。
⚠️ `ClippingMediaSource` 舊的三個建構子已 `@Deprecated`，要用 `Builder`。

---

## 4. 音量淡變不是「體驗加分」，是技術上的必需

`SilenceMediaSource` 的輸出格式**寫死** 44100Hz / stereo / PCM 16-bit。
來源若是 48kHz 或單聲道，跨越邊界時 ExoPlayer 得重設 AudioTrack，會有極短的不連續。

**淡出到 0 剛好遮蔽它**。所以不能把淡變當可選功能砍掉。

- 播放期：`playback/FadeController.kt`，40ms 輪詢位置套曲線（非 sample-accurate，聽不出來）
- 匯出期：`export/FadeAudioProcessor.kt`，每個 clip 自己知道要在頭或尾淡變
- **曲線共用 `domain/FadeCurve.kt`**（餘弦 S 曲線），免得兩邊聽感走鐘

---

## 5. 波形刻意畫在「原始時間軸」

若依插入後比例繪製，30 分鐘音檔插 50 分鐘靜默後音訊只佔畫面 37% 寬，
拖放編輯插入點幾乎不能用。

所以：
- **主波形（`WaveformCanvas`）= 原始時間軸**，插入點是垂直標記線
- **下方細長條（`TotalTimelineBar`）= 插入後真實比例**，讓使用者對總長有正確直覺
- 播到靜默段時顯示「靜默中 · 剩餘 mm:ss」，否則使用者會以為卡住

### 點插入點列表會跳轉，靠的是 `toEffective` 的「嚴格小於」
`GapRow` 的時間文字可點，呼叫 `seekToOriginal(gap.atMs)`。位置剛好等於插入點時
`toEffective` 會落在該靜默段的**起點**（見 2.2 與 `EffectiveTimeline.toEffective` 的註解），
所以波形指示線會精準對齊那條標記線，同時顯示「靜默中 · 剩餘 …」。**這是刻意的**，
不要為了「讓使用者聽到進入留白前的音訊」改成 `atMs - 幾秒`，指示線會偏離標記線。

只讓文字可點、不是整列 —— 左右兩側是 `Checkbox` 與刪除鍵，整列可點會搶掉它們的觸控區。

---

## 6. 效能：已量測的事實（**不要重新猜**）

在 Pixel 10 上用一個真實的 20:10 m4a（AAC 48kHz 立體聲，56,700 個 frame）量過。
下面四個「看起來很合理」的假設**全都錯**：

| 假設 | 實測 |
|---|---|
| 取樣點太多（3000 → 512 bucket） | 3271ms → 3324ms，**完全沒變快** |
| 計算迴圈太慢 | 修好後快 14 倍，但只佔總時間 **4%** |
| codec 管線被串行化 | 管線化後 139s → 111s，只快 20% |
| 執行緒被排到小核 | 提高優先權後反而 124s，**無效** |

**真正的瓶頸：MediaCodec 每個緩衝的跨行程往返佔 87%**
（`queueInputBuffer` 46%、release 14%、dequeue 25%）。
每個 AAC frame（21ms 音訊）約 2ms，56,700 個就是 110 秒。這是解碼路徑的固有成本。

### 唯一有效的解法：少解一些 frame
`data/waveform/WaveformExtractor.kt` 有**兩條路徑**：

| 路徑 | 條件 | 精度 |
|---|---|---|
| `extractSampled` | `BUCKETS * WINDOW_US < 檔長` （長檔） | 每個 bucket 只解一小段連續視窗 |
| `extractSequential` | 短檔 | 解完全部，精度無損 |

**抽樣用「連續視窗」而不是固定間隔抽單點** —— 後者會 aliasing，
低頻正弦波可能持續命中零交越點，把大聲段畫成安靜。改壞這點很難從畫面上發現。

### 兩個調校旋鈕（速度 ↔ 波形細緻度）
每個 bucket 的 **seek + flush 約 21ms，比視窗解碼還貴**，所以 `BUCKETS` 直接決定總時間：

| 設定 | 20 分鐘檔耗時 | 波形 |
|---|---|---|
| 512 bucket / 80ms 視窗 | 13.3 s | 尖銳雜亂（只涵蓋每區間 1%） |
| 256 bucket / 80ms 視窗 | 7.5 s | 偏雜 |
| **192 bucket / 200ms 視窗（目前）** | **9.7 s** | 貼近真實包絡 |

`WaveformCanvas` 的 bar 間距是 3dp，實際只畫得出 **約 130–150 條**，所以 `BUCKETS` 沒必要超過 ~200。

⚠️ **改動 `BUCKETS` 或 `WINDOW_US` 時，必須同步遞增 `WaveformRepository.FORMAT_VERSION`**，
否則會讀到舊格式的快取。

---

## 7. 建置環境（有兩個會直接讓 build 掛掉的陷阱）

Gradle 9.6.1 / **AGP 9.3.1** / Kotlin 2.3.10 / KSP 2.3.10 / compileSdk 37 / minSdk 26 / targetSdk 36
Media3 1.10.1、Room 2.8.4、WorkManager 2.11.2、Compose BOM 2026.06.01

### ⚠️ 陷阱 1：AGP 9 內建 Kotlin，**不可以**加 `org.jetbrains.kotlin.android`
AGP 9.0 起內建 Kotlin 支援，那個 plugin 已不相容。加回去 build 會失敗。
`kotlin.plugin.compose` 與 `ksp` 仍要保留。`jvmTarget` 由 `compileOptions.targetCompatibility` 帶出。

### ⚠️ 陷阱 2：`AndroidManifest.xml` 必須覆寫 WorkManager 的 service type
WorkManager 自己的 `SystemForegroundService` 沒宣告 `foregroundServiceType`，
匯出時呼叫 `setForeground(DATA_SYNC)` 會丟 `IllegalArgumentException` 並讓 app **崩潰迴圈**：

```xml
<service android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:replace="android:foregroundServiceType" />
```
光加 `FOREGROUND_SERVICE_DATA_SYNC` 權限是不夠的。

### 其他不能拿掉的東西
- `PlaybackService` 的 `setWakeMode(C.WAKE_MODE_LOCAL)` — 50 分鐘靜默期間會被 doze 中斷
- `MainActivity` 的 `Modifier.safeDrawingPadding()` — Android 15+ 強制 edge-to-edge，否則內容被狀態列蓋住
- debug build 的 `BuildConfig.MIN_GAP_MS = 5000`（release 是 300000）—— 否則每次驗證淡變都要等 5 分鐘

---

## 8. 檔案地圖

```
domain/              純 Kotlin，無 Android 依賴，全部有單元測試
  EffectiveTimeline  ★ 正確性核心：segments / totalDuration / 位置換算 / volumeAt
  Gap                atMs（原始時間軸）/ durationMs / enabled
  FadeCurve          播放與匯出共用的餘弦曲線

data/
  FileKey            檔名+大小+修改時間 → SHA-256（主鍵用它而非 SAF URI，URI 會失效）
  TrackRepository    選檔、持久化、SAF 永久授權回收
  db/                Room；gaps 用字串編碼存同一列（GapCodec），不開子表
  waveform/
    WaveformExtractor  ★ 兩條路徑（抽樣／循序），見第 6 節
    PeakAccumulator    循序路徑的熱迴圈（bulk copy + 遞增 bucket）
    PeakScanner        抽樣路徑的視窗掃描
    BucketMapper       frame ↔ bucket 對映
    WaveformRepository 以 fileKey 快取到 cacheDir

playback/
  MediaSourceBuilder ★ Clipping + Silence → ConcatenatingMediaSource2
  PlaybackService    MediaSessionService；自訂 LOAD 指令
  PlayerConnection   UI 端的 MediaController 包裝
  FadeController     40ms 輪詢調音量

export/
  AudioExporter      Transformer + EditedMediaItemSequence.addGap
  FadeAudioProcessor 匯出期的淡變
  ExportWorker       WorkManager，前景通知帶進度

ui/  home/（最近清單）  player/（波形、插入點編輯、匯出）
```

### 播放控制列為什麼排成兩列
六顆鍵（從頭開始 / 播放暫停 / ∓15s / ∓5s）塞不進一列：360dp 螢幕扣掉 padding 只剩約 328dp，
`OutlinedButton` 預設 contentPadding 就佔 48dp。所以拆兩列、每顆 `weight(1f)`。
**不要為了「播放鍵居中」併回一列** —— 那得把 contentPadding 壓到 8dp，
大字級設定下會截字。

### 為什麼用自訂 session 指令而不是 MediaItem
插入靜音必須用 `ExoPlayer.setMediaSource()`，而 `MediaController` 只認得 `MediaItem`，
無法傳遞組合好的 `MediaSource`。所以 UI 透過 `PlaybackCommands.ACTION_LOAD` 把參數
（uri / 時長 / 編碼後的 gaps / fadeMs / 起始位置）送進服務，由服務端組裝。

靜音總開關不在協定裡：關閉時 UI 直接送空的 gaps 字串，服務端不必知道這個概念。

### DI
手動 `AppContainer`（約 30 行），沒有 Hilt。依賴只有 DB / TrackRepository /
WaveformRepository 三個，Hilt 的樣板成本大於收益。

---

## 9. 測試守著什麼

**50 個單元測試 + 7 個儀器化測試**，都在 Pixel 10 上跑過。

| 測試 | 守著什麼 |
|---|---|
| `EffectiveTimelineTest`（29） | 位置換算往返一致性、淡變邊界、gap 在 0 或結尾、同位置合併、未排序輸入 |
| `PeakAccumulatorTest`（7） | ★ **與改寫前的演算法逐格輸出完全相同**（`assertArrayEquals`）—— 效能改寫的護欄 |
| `PeakScannerTest`（5） | 峰值、`Short.MIN_VALUE` 夾住、暫存陣列不跨緩衝污染 |
| `BucketMapperTest`（5） | 遞增邊界與原除法公式**逐點等價** |
| `PlaybackEngineTest`（6） | `player.duration == totalDuration`、單一 window、實際跨越靜默且靜默期音量為 0、匯出檔長度、快取命中不重新解碼、抽樣與循序兩條路徑的包絡 |
| `WaveformBenchmarkTest`（1） | 新舊實作在**同一次執行**對比（跨 build 比會被溫控干擾） |

**動效能相關的程式碼時，`PeakAccumulatorTest` 的等價測試是最重要的護欄** ——
效能改寫最怕悄悄改了結果。

### 指令
```bash
./gradlew testDebugUnitTest                 # 純 JVM，快
./gradlew connectedDebugAndroidTest         # 需要裝置；跑完會把 app 卸載
./gradlew installDebug                      # 驗證 UI 前要重裝
```
多台裝置時用 `ANDROID_SERIAL` 指定，否則 Gradle 會因目標不明確失敗。

---

## 10. 已知限制

1. **匯出格式是 M4A/AAC**（Transformer 的容器限制），來源是 mp3 時會重編碼一次。不影響原檔。
2. **VBR 且缺 Xing header 的 mp3**，切片起點只能對齊到 frame，有數十毫秒誤差。
3. **抽樣模式的波形是估值**，每個 bucket 只看區間裡的一小段，可能漏掉瞬時的最大聲。
   對包絡平滑的引導冥想影響極小；若要完全精確得付 110 秒。
4. **SAF 永久授權有數量上限**（新版 Android 512 筆），`TrackRepository` 只保留最近 100 筆並回收其餘。
5. **`cacheDir` 會被系統回收**（低儲存空間時），波形快取消失只會重新解碼，不影響正確性；
   但**不要把大型測試素材寫進 `cacheDir`** —— 會出現「剛寫好卻 ENOENT」。

---

## 11. 診斷

`WaveformExtractor` 會用 tag `WaveformExtractor` 記錄擷取耗時與走哪條路徑：
```
adb logcat -s WaveformExtractor:I
```
效能問題**先看這行再動手**。這個專案已經因為憑直覺猜瓶頸而白做過兩輪。
