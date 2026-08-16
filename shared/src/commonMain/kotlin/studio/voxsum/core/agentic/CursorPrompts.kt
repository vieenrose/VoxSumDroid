package studio.voxsum.core.agentic

/**
 * The `sys-v1` protocol prompts and the NOTES v2 renderer.
 *
 * **These strings are part of the model contract, not copy.** The checkpoint was fine-tuned
 * and evaluated against these exact bytes; upstream's §7.8 makes a silent edit here an
 * invalidation of train/eval comparability, and the practical symptom of drift is not an
 * error but quietly worse notes. If a change is genuinely needed, it needs a new
 * PROMPT_VERSION and a re-measured checkpoint — not an edit.
 *
 * The caps and prefix length are spelled out in the prompt AND enforced in [CursorState].
 * [CursorPromptsTest] asserts the two agree, so a cap changed in one place fails the build
 * rather than silently instructing the model to a bound the harness does not keep.
 *
 * Ported from `src/voxsum/prompts.py` and `src/voxsum/render.py` @ bc8c6ada.
 */
internal object CursorPrompts {

    const val PROMPT_VERSION = "sys-v1"

    /** An empty section is exactly `-` on one line (harness §3). */
    const val EMPTY_SECTION = "-"

    /** Rendered form of [CursorSections.CAPS], as it appears in both SYS prompts. */
    const val CAPS_LINE = "SUMMARY 5, DECISIONS 5, ACTIONS 6, OPEN 4, TOPICS 6"

    val SYS_EN = """
        You curate one evolving set of meeting NOTES as a transcript streams past you.

        You are shown the current NOTES (STATE) and the next block of transcript lines (CHUNK).
        Reply with edit operations only — one per line, no prose, no explanation, no markdown.

        Sections: SUMMARY, DECISIONS, ACTIONS, OPEN, TOPICS. Caps: $CAPS_LINE.

        Operations:
        ADD <SECTION> - <bullet> [m:ss]
        UPD <SECTION> «<old bullet prefix>» -> <new bullet> [m:ss]
        DEL <SECTION> «<bullet prefix>»
        CMP <SECTION>            (then up to the cap of rewritten bullets, one `- ` per line)
        TITLE: <short title>
        NOP

        Rules:
        - Every ADD and UPD bullet ends with an [m:ss] copied exactly from a line in THIS CHUNK.
        - «prefix» is the first ${CursorSections.MIN_PREFIX} or more characters of a bullet already in STATE,
          copied exactly.
        - When this chunk changes something already in STATE — a decision reversed or approved, a
          deadline moved, an action reassigned — use UPD to revise that bullet. Do not add a second
          bullet that contradicts the first.
        - Use DEL only when this chunk shows an existing bullet is wrong.
        - Keep bullets short and factual: 20 words or fewer, stating what was decided or agreed.
        - NOP alone is a complete, correct answer when this chunk changes nothing.
    """.trimIndent() + "\n"

    val SYS_ZH = """
        你負責維護一份會議筆記（NOTES），逐段閱讀逐字稿並持續更新。

        系統會提供目前的筆記（STATE）與下一段逐字稿（CHUNK）。
        只回覆編輯指令，一行一個，不要加任何說明、前言或 markdown。

        區段：SUMMARY, DECISIONS, ACTIONS, OPEN, TOPICS。上限：$CAPS_LINE。

        指令：
        ADD <SECTION> - <條目> [m:ss]
        UPD <SECTION> «<原條目前綴>» -> <新條目> [m:ss]
        DEL <SECTION> «<條目前綴>»
        CMP <SECTION>            （接著寫出重寫後的條目，每行以 `- ` 開頭，不超過上限）
        TITLE: <簡短標題>
        NOP

        規則：
        - 每個 ADD 與 UPD 的條目結尾都要有 [m:ss]，必須從「本段」的某一行原樣抄錄。
        - «前綴» 是 STATE 中既有條目開頭至少 ${CursorSections.MIN_PREFIX} 個字元，必須原樣抄錄。
        - 當本段推翻或改變 STATE 中已有的內容——決議被否決或通過、期限改變、負責人更換——
          請用 UPD 修改那一條，不要另外新增一條與前一條矛盾的條目。
        - 只有在本段證明既有條目有誤時才使用 DEL。
        - 條目簡短具體，20 字以內，寫清楚決定或共識了什麼。
        - 若本段沒有任何需要更動，單獨回覆 NOP 就是完整且正確的答案。
    """.trimIndent() + "\n"

    fun system(zh: Boolean): String = if (zh) SYS_ZH else SYS_EN

    /**
     * Render NOTES v2 from state. Sections always all present, in fixed order.
     *
     * The final notes are rendered by the HARNESS — the model never writes them. That is why
     * a malformed generation can cost ops but never the document's shape.
     */
    fun renderState(
        state: CursorState,
        enforceCaps: Boolean = true,
        zh: Boolean = false,
        promoteDecisions: Boolean = false,
        enforceChain: Boolean = false,
        /** Grounding check for promoted bullets — see CursorNotesGuards.promoteDecisionSummaries.
         *  Null promotes UNVERIFIED, which is a testing affordance, not a shipping configuration. */
        verifyPromotion: ((String, String, Int?) -> String?)? = null,
        /** Supporting lines per anchor, for the deterministic promotion grounding check. */
        evidenceFor: ((Int?) -> List<String>)? = null,
    ): String {
        // Clone whenever anything here MUTATES, not only for the caps: the guards below edit the
        // state, and rendering must never be observable to the caller. Without this a render with
        // guards on would silently rewrite the agent's live state mid-run.
        val mutates = enforceCaps || promoteDecisions || enforceChain
        val s = if (mutates) state.clone() else state
        if (enforceCaps) s.enforceCaps()
        // Guards run on the PRODUCT render only — see CursorNotesGuards. buildStepPrompt leaves
        // both off, because the STATE block is the model's whole memory and it was fine-tuned
        // against un-promoted notes.
        if (promoteDecisions) CursorNotesGuards.promoteDecisionSummaries(s, zh, verifyPromotion, evidenceFor)
        if (enforceChain) CursorNotesGuards.enforceDecisionChain(s)
        val lines = mutableListOf("TITLE: ${s.title}".trimEnd())
        for (section in CursorSections.BULLET_SECTIONS) {
            lines.add("$section:")
            val bullets = s.bullets(section)
            if (bullets.isEmpty()) lines.add(EMPTY_SECTION)
            else bullets.forEach { lines.add(it.render()) }
        }
        return lines.joinToString("\n") + "\n"
    }

    /**
     * The per-step user content: STATE then CHUNK, in that fixed order.
     *
     * Order is contractual. STATE is small and stable in shape, CHUNK is the varying part,
     * and the model always reads them in the same places — which is what makes the step
     * learnable and keeps the prefix cacheable.
     */
    fun buildStepPrompt(state: CursorState, chunk: CursorChunker.Chunk): String =
        "STATE:\n${renderState(state)}\nCHUNK:\n${chunk.render()}"

    // --- coverage fallback -------------------------------------------------------
    //
    // The stateless per-window prompt. Upstream uses it as the map step of its map-reduce
    // BASELINE; we use it only for the NOP-collapse fallback (§5.3), where the point is
    // precisely that it needs no STATE — the model has stopped engaging with STATE, so
    // asking it about STATE again is not the way out.

    private val MAP_EN = """
        You are summarising ONE block of a meeting transcript, in isolation.

        Reply with bullets only, one per line, in this form:
        TOPICS - Discussion topic [0:00]

        SECTION is one of SUMMARY, DECISIONS, ACTIONS, OPEN, TOPICS.
        Every bullet ends with an [m:ss] copied exactly from a line in this block.
        Keep bullets short and factual: 20 words or fewer.
        If this block contains nothing worth recording, reply NONE.
    """.trimIndent() + "\n"

    private val MAP_ZH = """
        你正在為會議逐字稿的「其中一段」做摘要，只看這一段。

        只回覆條目，一行一個，格式如下：
        TOPICS - 討論議題 [0:00]

        SECTION 是 SUMMARY, DECISIONS, ACTIONS, OPEN, TOPICS 其中之一。
        每個條目結尾都要有 [m:ss]，必須從本段的某一行原樣抄錄。
        條目簡短具體，20 字以內。
        若本段沒有值得記錄的內容，請回覆 NONE。
    """.trimIndent() + "\n"

    fun windowSystem(zh: Boolean): String = if (zh) MAP_ZH else MAP_EN

    /** The window step's user content: the chunk alone. No STATE — that is the point. */
    fun buildWindowPrompt(chunk: CursorChunker.Chunk): String = "CHUNK:\n${chunk.render()}"
}
