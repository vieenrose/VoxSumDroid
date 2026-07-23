package studio.voxsum.core.asr

import java.io.File

/**
 * Android MOSS-TD engine — JNI over `libvoxsum-moss.so`, which folds two static trees:
 * the vendored MIT `moss_td` port (flat C API, ASR) and rapidspeech-core (CAM++ speaker
 * embeddings only). Its `transcribeWindow` / `embedUnit` surface mirrors the desktop
 * engine exactly, so the shared [studio.voxsum.core.asr.moss.MossPipeline] drives both
 * platforms through the same lambdas.
 *
 * MOSS-TD is a batch backend (slower than realtime on phone cores) — call this off the
 * main thread from the transcription service's queue, not the live recording path.
 */
class MossAsrEngine private constructor(
    private var ctx: Long,
    private var spkCtx: Long,
) : AutoCloseable {

    val hasSpeakerEmbedding: Boolean get() = spkCtx != 0L

    /** Decode one window → the raw `[start][Sxx]text` transcript (window-local seconds).
     *  [maxNewTokens] caps generation — pass the pipeline's budget, the GGUF default truncates. */
    fun transcribeWindow(pcm: FloatArray, maxNewTokens: Int): String =
        nativeTranscribe(ctx, pcm, maxNewTokens)

    /** CAM++ embedding of one speaker unit's pooled audio; null if too short / failed /
     *  the speaker model isn't loaded. */
    fun embedUnit(pcm: FloatArray): FloatArray? {
        if (spkCtx == 0L || pcm.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return (nativeEmbed(spkCtx, pcm, intArrayOf(0), intArrayOf(pcm.size)) as Array<FloatArray?>)
            .firstOrNull()
    }

    override fun close() {
        if (ctx != 0L) { nativeFree(ctx); ctx = 0L }
        if (spkCtx != 0L) { nativeFreeSpeaker(spkCtx); spkCtx = 0L }
    }

    companion object {
        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) { System.loadLibrary("voxsum-moss"); loaded = true }
        }

        /**
         * Load the MOSS-TD model (+ optional CAM++ speaker model). Returns null if the ASR model
         * fails to load. The speaker model is best-effort — a failure there yields an engine with
         * per-window `[Sxx]` tags only (no cross-window linking). `threads <= 0` = all cores.
         */
        fun create(model: File, speakerModel: File?, threads: Int = 0): MossAsrEngine? {
            ensureLib()
            val c = nativeInit(model.absolutePath, threads)
            if (c == 0L) return null
            val s = speakerModel?.takeIf { it.exists() }
                ?.let { nativeInitSpeaker(it.absolutePath, 2) } ?: 0L
            return MossAsrEngine(c, s)
        }

        /** CAM++-only handle for the LiteRT ASR path: cross-window speaker embeddings still come
         *  from rapidspeech-core's rs_speaker_*; ASR ([transcribeWindow]) must not be called. */
        fun createSpeakerOnly(speakerModel: File): MossAsrEngine? {
            ensureLib()
            val s = nativeInitSpeaker(speakerModel.absolutePath, 2)
            if (s == 0L) return null
            return MossAsrEngine(0L, s)
        }

        @JvmStatic private external fun nativeInit(modelPath: String, threads: Int): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeTranscribe(ctx: Long, pcm: FloatArray, maxNew: Int): String
        @JvmStatic private external fun nativeInitSpeaker(modelPath: String, threads: Int): Long
        @JvmStatic private external fun nativeFreeSpeaker(ctx: Long)
        @JvmStatic private external fun nativeEmbed(
            ctx: Long, pcm: FloatArray, starts: IntArray, ends: IntArray,
        ): Array<FloatArray?>
    }
}
