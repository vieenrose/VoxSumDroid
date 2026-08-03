package studio.voxsum.core.agentic

// GENERATED FROM agentic/contract.py BY agentic/gen_kotlin_prompts.py — DO NOT EDIT.
// Any change made here is a train/deploy divergence: the model is fine-tuned on exactly
// these strings. Edit the contract and regenerate.

object Prompts {

    // op A — notes for one chunk the model can see in full
    private const val A_EN = "Write meeting notes for this part of a transcript.\n\nFormat — exactly these five keys, in this order. Under each key write SEVERAL short bullets\n(3-5 where there is that much to say), one idea per bullet, each a single sentence ending\nwith the timestamp that supports it:\n\nSUMMARY:\n- the team compared two casing designs for the remote [0:12]\n- the prototype budget was raised to 40k [1:40]\n- marketing asked for a decision before friday [2:05]\nDECISIONS:\n- the flip-open case was dropped as too costly [1:03]\n- the launch date moved to Q3 [1:52]\nACTIONS:\n- rachel: send the revised cost sheet to finance [2:20]\n- sam: re-test the battery life at 20 degrees [2:48]\nOPEN:\n- whether the battery target is still achievable [3:04]\nTOPICS:\n- remote control casing [0:40]\n- prototype budget [1:35]\n\nThe bullets above are FORMAT EXAMPLES ONLY. They describe a different, made-up meeting\nabout a remote control. Never copy them — none of that content belongs in your answer.\n\nKeep every bullet under 25 words and about ONE thing. Do NOT merge several topics into one\nlong sentence — write them as separate bullets. Write only what this part of the transcript\nactually says. If a section has nothing, put exactly \"-\" on its line. No preamble.\n\nTranscript:\n{chunk}"
    private const val A_ZH = "請為以下這段逐字稿寫會議筆記。\n\n格式——就是這五個鍵、依此順序。每個鍵底下要寫「數點」簡短的條列（有內容的話寫 3-5 點），\n一點只講一件事，每點都是一句話，結尾附上支持該點的時間戳記：\n\nSUMMARY:\n- 團隊比較了遙控器的兩種外殼設計 [0:12]\n- 原型預算提高到四萬 [1:40]\n- 行銷部要求週五前做出決定 [2:05]\nDECISIONS:\n- 翻蓋式外殼因成本過高而不採用 [1:03]\n- 上市時程改到第三季 [1:52]\nACTIONS:\n- 淑芬: 把修訂後的成本表寄給財務 [2:20]\n- 建宏: 在攝氏二十度重測電池續航 [2:48]\nOPEN:\n- 電池續航目標是否仍可達成 [3:04]\nTOPICS:\n- 遙控器外殼 [0:40]\n- 原型預算 [1:35]\n\n上面那些條列「只是格式範例」，講的是另一場虛構的遙控器會議。絕對不可以照抄——\n遙控器、外殼、預算、淑芬、建宏這些內容都不屬於你的答案。\n\n每一點不要超過 30 個字，而且只講「一件事」。不要把好幾個主題塞進一個長句子——請分成好幾點寫。\n只寫「下面這段逐字稿」真的有講到的內容。若某區段沒有內容，該行只寫「-」。不要前言、不要評論。\n\n逐字稿:\n{chunk}"

    // op B — merge one section, with the transcript lines its anchors point at
    private const val B_EN = "These are notes for the {section} section of one meeting, gathered from different\nparts of the transcript. Write at most {cap} bullets that describe THE MEETING AS A WHOLE.\n\nThis is the only step that sees the whole meeting, so the result must read as notes about\nthe meeting, not as a list of what each part contained. Combine points that are about the\nsame thing into one bullet, and drop what turned out not to matter once the whole meeting\nis in view.\n\nRules:\n- end every bullet with EXACTLY ONE timestamp, copied unchanged from the notes, like [9:10].\n  Never write a range like [7:21-17:38] and never leave a bullet without a timestamp.\n- if two bullets disagree, keep the one with the LATER timestamp and drop the earlier one —\n  a later statement supersedes an earlier one\n- do not add anything that is not in the notes or the transcript lines below\nOutput only the bullets.\n\nNotes:\n{items}\n\nTranscript lines these notes came from:\n{evidence}"
    private const val B_ZH = "以下是同一場會議「{section}」區段的筆記，來自逐字稿的不同部分。請寫出最多 {cap} 點，\n描述「整場會議」。\n\n這是唯一能看到整場會議的步驟，所以結果要像是「整場會議的筆記」，而不是「每一段各有什麼」的清單。\n講同一件事的點要合併成一點；從整場會議來看不重要的，就刪掉。\n\n規則：\n- 每一點結尾都要有「剛好一個」時間戳記，照筆記原樣抄寫，例如 [9:10]。\n  不可以寫成區間如 [7:21-17:38]，也不可以有任何一點沒有時間戳記。\n- 若兩點互相矛盾，保留時間較晚的那一點、刪去較早的——後面的說法會取代前面的\n- 不要加入筆記與下方逐字稿以外的內容\n只輸出條列。\n\n筆記:\n{items}\n\n這些筆記依據的逐字稿原文:\n{evidence}"

    // op C — title
    private const val C_EN = "Write ONE short title (at most 8 words) for these meeting notes.\nOutput only the title — no quotes, no list, no preamble.\n\n{notes}"
    private const val C_ZH = "請為以下會議筆記取一個簡短標題（8 個字以內）。\n只輸出標題本身——不要引號、不要條列、不要前言。\n\n{notes}"

    /** Output token budget per op, shared with training so sampling limits agree. */
    const val MAX_CHUNK_NOTES = 640
    const val MAX_MERGE = 420
    const val MAX_TITLE = 24

    val MAX_BULLETS: Map<Section, Int> = mapOf(Section.SUMMARY to 5, Section.DECISIONS to 5, Section.ACTIONS to 6, Section.OPEN to 4, Section.TOPICS to 6)

    fun chunkNotes(zh: Boolean, chunk: String): String =
        (if (zh) A_ZH else A_EN).replace("{chunk}", chunk)

    fun mergeSection(zh: Boolean, section: Section, cap: Int, items: String,
                     evidence: String): String =
        (if (zh) B_ZH else B_EN)
            .replace("{section}", section.name)
            .replace("{cap}", cap.toString())
            .replace("{items}", items)
            .replace("{evidence}", evidence)

    fun title(zh: Boolean, notes: String): String =
        (if (zh) C_ZH else C_EN).replace("{notes}", notes)
}
