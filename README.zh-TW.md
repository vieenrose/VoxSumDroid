<p align="center">
  <img src="docs/screenshots/app-icon.png" width="112" alt="VoxSum app icon" />
</p>

# VoxSum for Android（繁體中文）

**[English →](README.md)**

完全**離線**、在裝置端運行的 [VoxSum Studio](https://huggingface.co/spaces/Luigi/VoxSum-bak) 行動版 —
在手機上完成音訊的轉錄、語者分離與摘要。無伺服器、無帳號、無雲端。
選擇一個音訊檔案（或一集 Podcast），語音辨識、語者分離與 LLM 摘要全部在裝置本機執行。

> **狀態：可運作。** 完整流程已在實機（Pixel 6）端到端驗證：VAD 分段的 ASR → 語者分離 → 摘要，
> 並搭配與逐字稿同步的播放器。以 **APK** 形式發佈（見 [Releases](https://github.com/vieenrose/VoxSumDroid/releases)）。

## 為什麼選擇 VoxSum — 你的話語，仍屬於你

這不只是一款 App，更是全新的逐字稿哲學：**將權力交還使用者。**

| 🛡️ 絕對隱私 | ✈️ 完全離線 | 💰 無需訂閱 |
|---|---|---|
| 音訊**永不離開您的裝置** — 所有步驟皆在本機處理，機密錄音從根本杜絕雲端外洩風險。專為律師、醫師、記者與高階主管而設計。 | 模型就緒後即無需網路 — 在飛機、高鐵或任何無訊號之處，生產力永不中斷。 | 一次擁有，永久使用 — 無用量計費、無週期性費用。適合學生、創作者與預算有限者。 |

## 下載

從 [**Releases 頁面**](https://github.com/vieenrose/VoxSumDroid/releases/latest)
取得最新的已簽署 APK（`voxsum-v<版本>.apk`）後側載安裝。Android 可能會提示允許從瀏覽器／檔案管理員安裝。
ASR／語者分離／LLM 模型**不**內建於 App，會在首次使用時下載一次（並經 SHA-256 驗證），之後即可完全離線使用。

## 螢幕截圖

| 首頁 | 加入音訊 | 逐字稿 | 摘要 |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/01-home-zhtw.png" width="200" alt="首頁"> | <img src="docs/screenshots/02-add-source.png" width="200" alt="加入音訊"> | <img src="docs/screenshots/03-transcript.png" width="200" alt="逐字稿"> | <img src="docs/screenshots/04-summary.png" width="200" alt="摘要"> |

## 功能特色

- **四種語音辨識後端**，每次執行可自由選擇：
  - **SenseVoice**（多語言 — 中／英／日／韓／粵語，支援語言與 ITN 選項）
  - **Moonshine**（英語、快速）
  - **Zipformer zh-en**（transducer）
  - **Qwen3-ASR**（大型、高準確度）
- **即時錄音** — 錄製會議並**邊說邊轉錄**：麥克風串流直接進入 VAD/ASR 迴圈，逐句即時出現，停止後再執行語者分離與摘要（錄音會存成 WAV，可於同步播放器中播放）。
- **VAD 分段串流轉錄** — 偵測到語音即逐句顯示（Silero VAD），而非一次性等待整批完成。
- **語者分離** — 每句以 **CAM++（中＋英）語者嵌入**搭配自適應分群，並提供彩色**時間軸**、各語者色票，以及語者統計面板。嵌入模型由裝置端實測選定：fp16 CAM++ 在中／英語上比舊有的 eres2net 基準**約快 1.5 倍且更準確**（[權重與基準測試](https://huggingface.co/Luigi/campplus-zh-en-onnx)）。
- **裝置端摘要** — 透過 llama.cpp 執行本機 GGUF LLM，以 map-reduce 對逐字稿產生標題與摘要（以 Markdown 呈現）。可選用原版的 **Gemma 系列**：Gemma 3 270M、Gemma 3 1B（預設）、Gemma 3n E2B、Gemma 3n E4B、Gemma 4 E2B、Gemma 4 E4B，各自套用正確的對話模板。E2B/E4B 變體需要高 RAM 裝置。
- **繁體中文（zh-TW）輸出** — 可選的 OpenCC `s2tw` 轉換，套用於逐字稿、標題與摘要（例如 平台 → 平臺），全程於裝置端完成。
- **雙語介面（English／繁體中文）** — 整個 UI 皆已在地化；可於 Android 的「個別 App 語言」設定中選擇，或跟隨系統語系。
- **與逐字稿同步的播放器** — 點任一句即可跳轉，作用中該句自動高亮，±5 秒快轉、音量／靜音，且**轉錄進行中即可播放**。
- **行內編輯** — 可直接在逐字稿中編輯句子文字與重新命名語者。
- **匯出** — 逐字稿可匯出為 **SRT / VTT / TXT / JSON**；摘要可匯出為 **Markdown / 純文字**（透過系統檔案選擇器）。
- **Podcast 匯入** — 搜尋與瀏覽 Podcast（iTunes Search + RSS）並下載單集直接進入流程。
- **YouTube** — 貼上影片連結，音訊將被解析（NewPipeExtractor）並下載，再如同一般來源般轉錄。（部分影片受地區或 token 限制而無法解析，會以清楚的錯誤訊息處理。）
- **隱私為本** — 模型就緒後，轉錄與摘要皆無需網路。無 Google Play 服務、無分析追蹤、無專有相依套件。

## 技術細節與從原始碼建置

技術堆疊、從原始碼建置步驟與授權條款，請見[英文說明](README.md#stack)。

## 授權

GPL-3.0-or-later（見 [`LICENSE`](LICENSE)）。內含的原始碼相依套件各自保留其授權。
