package studio.voxsum.desktop.ui

import java.util.Locale

enum class UiLang { EN, FR, ZH_HANT, ZH_HANS }

/**
 * Localized string catalog for the desktop UI (EN / FR / Traditional & Simplified Chinese).
 *
 * Language is picked once from the JVM default locale. FR/ZH values reused from Android's
 * strings.xml keep the same wording; desktop-only strings were translated to match the app's
 * existing tone. Simplified is derived from the Traditional column via OpenCC. Keep each EN value
 * EXACTLY equal to the current source literal so call-site replacement is mechanical.
 */
object Strings {
    val lang: UiLang = run {
        val loc = Locale.getDefault()
        when (loc.language) {
            "fr" -> UiLang.FR
            "zh" -> {
                // Traditional for TW/HK/MO or an explicit Hant script tag; Simplified otherwise.
                val script = loc.script
                val country = loc.country
                if (script == "Hant" || country == "TW" || country == "HK" || country == "MO") UiLang.ZH_HANT
                else UiLang.ZH_HANS
            }
            else -> UiLang.EN
        }
    }

    // ---- Window / picker titles ----
    val windowTitle: String get() = t("VoxSum for Linux", "VoxSum pour Linux", "VoxSum Linux 版")
    val pickAudioFile: String get() = t("Pick an audio file", "Choisir un fichier audio", "選擇音訊檔案")
    val saveSessionAsM4a: String get() = t("Save session as .m4a", "Enregistrer la session en .m4a", "另存工作階段為 .m4a")

    // ---- Session save status ----
    val savingSession: String get() = t("Saving session…", "Enregistrement de la session…", "正在儲存工作階段…")
    val sessionSaved: String get() = t("Session saved", "Session enregistrée", "工作階段已儲存")
    val savedTranscriptTooLarge: String get() = t(
        "Saved (transcript too large to embed)",
        "Enregistré (transcription trop volumineuse à intégrer)",
        "已儲存（逐字稿太大無法內嵌）",
    )
    val saveFailed: String get() = t("Save failed", "Échec de l'enregistrement", "儲存失敗")

    // ---- Toolbar ----
    val open: String get() = t("Open", "Ouvrir", "開啟")
    val online: String get() = t("Online", "En ligne", "線上")
    val stop: String get() = t("Stop", "Arrêter", "停止")
    val record: String get() = t("Record", "Enregistrer", "錄音")
    val reRun: String get() = t("Re-run", "Relancer", "重新執行")
    val reTranscribe: String get() = t("Re-transcribe", "Retranscrire", "重新轉錄")
    val reSummarize: String get() = t("Re-summarize", "Refaire le résumé", "重新摘要")
    val reTitle: String get() = t("Re-title", "Regénérer le titre", "重新產生標題")
    val detectSpeakerNames: String get() = t("Detect speaker names", "Détecter les noms des locuteurs", "偵測語者名稱")
    val reDiarize: String get() = t("Re-detect speakers", "Redétecter les locuteurs", "重新辨識語者")
    val showMore: String get() = t("Show more", "Afficher plus", "顯示更多")
    val showLess: String get() = t("Show less", "Afficher moins", "顯示較少")
    val extractActionItems: String get() = t("Extract action items", "Extraire les actions", "擷取行動項目")
    val find: String get() = t("Find", "Rechercher", "尋找")
    val export: String get() = t("Export", "Exporter", "匯出")
    val save: String get() = t("Save", "Enregistrer", "儲存")
    val theme: String get() = t("Theme", "Thème", "主題")
    val fontSmaller: String get() = t("Smaller text", "Texte plus petit", "縮小文字")
    val fontLarger: String get() = t("Larger text", "Texte plus grand", "放大文字")
    val models: String get() = t("Models", "Modèles", "模型")
    val preferences: String get() = t("Preferences", "Préférences", "偏好設定")

    // ---- Sessions sidebar ----
    val sessions: String get() = t("SESSIONS", "SESSIONS", "工作階段")
    val noSessionsYet: String get() = t("No sessions yet", "Aucune session pour l'instant", "尚無工作階段")
    val addAudio: String get() = t("Add audio", "Ajouter de l'audio", "加入音訊")

    // ---- Detail / transcript ----
    val searchTranscriptHint: String get() = t("Search transcript…", "Rechercher dans la transcription…", "搜尋逐字稿…")
    val recognitionSettingsChanged: String get() = t(
        "Recognition settings changed — transcript may be out of date.",
        "Réglages de reconnaissance modifiés — la transcription est peut-être obsolète.",
        "辨識設定已變更 — 逐字稿可能已過期。",
    )
    val transcriptEditedSummaryStale: String get() = t(
        "Transcript edited — summary may be out of date.",
        "Transcription modifiée — le résumé est peut-être obsolète.",
        "逐字稿已編輯 — 摘要可能已過期。",
    )
    val summarySettingsChanged: String get() = t(
        "Summary settings changed — summary may be out of date.",
        "Réglages du résumé modifiés — le résumé est peut-être obsolète.",
        "摘要設定已變更 — 摘要可能已過期。",
    )
    val actionItems: String get() = t("Action items", "Actions à suivre", "行動項目")
    val noActionItems: String get() = t("No action items", "Aucune action", "無行動項目")
    val untitled: String get() = t("Untitled", "Sans titre", "未命名")
    val noSummaryYet: String get() = t("No summary yet", "Pas encore de résumé", "尚無摘要")
    val sessionCover: String get() = t("Session cover", "Couverture de la session", "工作階段封面")
    val asrLabel: String get() = t("ASR:", "ASR :", "辨識：")
    val llmLabel: String get() = t("LLM:", "LLM :", "摘要：")
    val ready: String get() = t("Ready", "Prêt", "就緒")
    val done: String get() = t("Done", "Terminé", "完成")
    val stopped: String get() = t("Stopped", "Arrêté", "已停止")
    fun error(msg: String?): String = t("Error: $msg", "Erreur : $msg", "錯誤：$msg")

    // ---- Player ----
    val play: String get() = t("Play", "Lecture", "播放")
    val pause: String get() = t("Pause", "Pause", "暫停")

    // ---- Utterance row / speaker editing ----
    val ok: String get() = t("OK", "OK", "確定")
    val reassignSpeaker: String get() = t("Reassign speaker", "Réassigner le locuteur", "變更此句的語者")
    val moveThisLineTo: String get() = t("Move this line to:", "Déplacer cette ligne vers :", "將此句移至：")
    val mergeThisSpeakerInto: String get() = t("Merge this speaker into:", "Fusionner ce locuteur dans :", "將此語者合併至：")
    fun speakerN(n: Int): String = t("Speaker $n", "Locuteur $n", "語者 $n")
    val cancel: String get() = t("Cancel", "Annuler", "取消")
    val editLine: String get() = t("Edit line", "Modifier la ligne", "編輯此句")
    val edit: String get() = t("Edit", "Modifier", "編輯")

    // ---- Settings dialog ----
    val settings: String get() = t("Settings", "Paramètres", "設定")
    val speechRecognition: String get() = t("Speech recognition", "Reconnaissance vocale", "語音辨識")
    val language: String get() = t("Language", "Langue", "語言")
    val itnCheckbox: String get() = t(
        "  Inverse text normalization (numbers, punctuation)",
        "  Normalisation inverse du texte (chiffres, ponctuation)",
        "  反向文字正規化（數字、標點）",
    )
    fun vadSensitivity(value: String): String =
        t("VAD sensitivity: $value", "Sensibilité VAD : $value", "語音偵測靈敏度：$value")
    val summaryModel: String get() = t("Summary model", "Modèle de résumé", "摘要模型")
    val lowRam: String get() = t("low RAM", "faible RAM", "低記憶體")
    val needs4gb: String get() = t("needs ~4 GB RAM", "~4 Go de RAM requis", "需要約 4 GB RAM")
    val needs6gb: String get() = t("needs ~6 GB RAM", "~6 Go de RAM requis", "需要約 6 GB RAM")
    fun modelSubtitle(mb: Long, ram: String): String = "$mb MB · $ram"   // unit-only, language-neutral
    val speakers: String get() = t("Speakers", "Locuteurs", "語者")
    val preciseDiarization: String get() = t(
        "Precise speaker boundaries (slower)",
        "Frontières de locuteurs précises (plus lent)",
        "精確語者邊界（較慢）",
    )
    val identifySpeakers: String get() = t("  Identify speakers", "  Identifier les locuteurs", "  辨識語者")
    val speakerCountHint: String get() = t(
        "Speaker count hint (blank = auto): ",
        "Nombre de locuteurs (vide = auto) : ",
        "語者數提示（留空 = 自動）：",
    )
    val targetLanguage: String get() = t("Target language", "Langue cible", "目標語言")
    val auto: String get() = t("Auto", "Auto", "自動")
    val summaryStyle: String get() = t("Summary style", "Style du résumé", "摘要風格")
    val customSummaryPrompt: String get() = t("Custom summary prompt", "Invite de résumé personnalisée", "自訂摘要提示詞")
    val about: String get() = t("About", "À propos", "關於")
    val aboutLicense: String get() = t("Free & open source · GPL-3.0", "Libre et open source · GPL-3.0", "自由開放原始碼 · GPL-3.0")
    val openSourceComponents: String get() = t("Open-source components", "Composants open source", "開放原始碼元件")

    // ---- Add-source dialog ----
    val addOnlineAudio: String get() = t("Add online audio", "Ajouter de l'audio en ligne", "加入線上音訊")
    val podcastTab: String get() = t(" Podcast", " Podcast", " Podcast")
    val youtubeTab: String get() = t(" YouTube", " YouTube", " YouTube")
    val searchPodcastsHint: String get() = t("Search podcasts…", "Rechercher des podcasts…", "搜尋 Podcast…")
    val searching: String get() = t("Searching…", "Recherche…", "搜尋中…")
    fun searchFailed(msg: String?): String =
        t("Search failed: $msg", "Échec de la recherche : $msg", "搜尋失敗：$msg")
    val noPodcastsFound: String get() = t("No podcasts found", "Aucun podcast trouvé", "找不到 Podcast")
    val search: String get() = t("Search", "Rechercher", "搜尋")
    val podcastsHeader: String get() = t("PODCASTS", "PODCASTS", "PODCAST")
    val loadingEpisodes: String get() = t("Loading episodes…", "Chargement des épisodes…", "載入單集中…")
    fun failed(msg: String?): String = t("Failed: $msg", "Échec : $msg", "失敗：$msg")
    val noEpisodes: String get() = t("No episodes", "Aucun épisode", "沒有單集")
    val episodesHeader: String get() = t("EPISODES", "ÉPISODES", "單集")
    val selectPodcastToSeeEpisodes: String get() = t(
        "Select a podcast to see episodes.",
        "Sélectionnez un podcast pour voir les épisodes.",
        "選擇 Podcast 以檢視單集。",
    )
    fun artistEpisodeCount(artist: String, count: Int): String =
        t("$artist · $count ep", "$artist · $count ép", "$artist · $count 集")
    fun downloadFailed(msg: String?): String =
        t("Download failed: $msg", "Échec du téléchargement : $msg", "下載失敗：$msg")
    val get: String get() = t("Get", "Obtenir", "取得")
    val youtubeUrlOrSearchHint: String get() = t("YouTube URL or search…", "URL YouTube ou recherche…", "YouTube 網址或搜尋…")
    val resolving: String get() = t("Resolving…", "Résolution…", "解析中…")
    val go: String get() = t("Go", "OK", "前往")
    val download: String get() = t("Download", "Télécharger", "下載")

    // ---- Models dialog ----
    val downloadedModels: String get() = t("Downloaded models", "Modèles téléchargés", "已下載的模型")
    val noModelsDownloadedYet: String get() = t("No models downloaded yet.", "Aucun modèle téléchargé.", "尚未下載任何模型。")
    fun totalMb(mb: Long): String = t("Total: $mb MB", "Total : $mb Mo", "總計：$mb MB")
    val delete: String get() = t("Delete", "Supprimer", "刪除")
    val close: String get() = t("Close", "Fermer", "關閉")

    // ---- Empty state ----
    val emptyHeadline: String get() = t(
        "Transcribe & summarize, fully offline",
        "Transcription et résumé, 100 % hors ligne",
        "完全離線轉錄與摘要",
    )
    val emptySubtitle: String get() = t(
        "Add an audio file, record a meeting, or paste a YouTube link to begin.",
        "Ajoutez un fichier audio, enregistrez une réunion ou collez un lien YouTube pour commencer.",
        "加入音訊檔案、錄製會議，或貼上 YouTube 連結以開始。",
    )
    val pillarPrivateTitle: String get() = t("Private by design", "Confidentiel par conception", "隱私至上")
    val pillarPrivateDesc: String get() = t("Audio never leaves your device", "L'audio ne quitte jamais votre appareil", "音訊永不離開您的裝置")
    val pillarOfflineTitle: String get() = t("Works offline", "Fonctionne hors ligne", "離線運作")
    val pillarOfflineDesc: String get() = t(
        "On a plane, a train, anywhere — no network",
        "En avion, en train, partout — sans réseau",
        "在飛機、高鐵或任何無網路環境皆可使用",
    )
    val pillarCostTitle: String get() = t("No subscription", "Sans abonnement", "無需訂閱")
    val pillarCostDesc: String get() = t("Yours to keep, no cloud fees", "À vous pour toujours, sans frais cloud", "一次擁有，無雲端費用")

    // The Chinese argument is authored in Traditional (one column to maintain); the Simplified
    // interface is produced from it at runtime with the app's own OpenCC (t2s), cached per string.
    private val simplifiedCache = HashMap<String, String>()

    private fun t(en: String, fr: String, zhHant: String): String = when (lang) {
        UiLang.FR -> fr
        UiLang.ZH_HANT -> zhHant
        UiLang.ZH_HANS -> simplifiedCache.getOrPut(zhHant) {
            studio.voxsum.desktop.OpenCcConverter
                .get(studio.voxsum.core.text.ChineseScript.SIMPLIFIED)
                .convert(zhHant)
        }
        else -> en
    }
}
