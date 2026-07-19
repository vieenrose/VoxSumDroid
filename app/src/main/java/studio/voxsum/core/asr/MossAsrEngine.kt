package studio.voxsum.core.asr

import java.io.File

/**
 * Android MOSS-TD engine — JNI over RapidSpeech.cpp (`libvoxsum-moss.so`). Holds one RapidSpeech
 * context (arch `MossTD`, the ASR+diarization GGUF) plus an optional CAM++ speaker context. Its
 * `transcribeWindow` / `embed` surface mirrors the desktop `MossSubprocessEngine` exactly, so the
 * shared [studio.voxsum.core.asr.moss.MossPipeline] drives both platforms through the same lambdas.
 *
 * MOSS-TD is a batch backend (est. slower-than-realtime on phone cores) — call this off the main
 * thread from the transcription service's queue, not the live recording path.
 */
class MossAsrEngine private constructor(
    private var ctx: Long,
    private var spkCtx: Long,
) : AutoCloseable {

    val hasSpeakerEmbedding: Boolean get() = spkCtx != 0L

    /** Decode one window → the raw `[start][Sxx]text[end]` transcript (window-local seconds). */
    fun transcribeWindow(pcm: FloatArray): String = nativeTranscribe(ctx, pcm)

    /** CAM++ embeddings for [ranges] (sample [a,b) spans into [pcm]); null per range that failed /
     *  was too short. All-null when the speaker model isn't loaded. */
    fun embed(pcm: FloatArray, ranges: List<IntRange>): List<FloatArray?> {
        if (spkCtx == 0L || ranges.isEmpty()) return List(ranges.size) { null }
        val starts = IntArray(ranges.size) { ranges[it].first }
        val ends = IntArray(ranges.size) { ranges[it].last + 1 }   // half-open [a,b)
        @Suppress("UNCHECKED_CAST")
        return (nativeEmbed(spkCtx, pcm, starts, ends) as Array<FloatArray?>).toList()
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
         * per-window `[Sxx]` tags only (no cross-window linking).
         */
        fun create(model: File, speakerModel: File?, threads: Int): MossAsrEngine? {
            ensureLib()
            val c = nativeInit(model.absolutePath, threads)
            if (c == 0L) return null
            val s = speakerModel?.takeIf { it.exists() }
                ?.let { nativeInitSpeaker(it.absolutePath, threads) } ?: 0L
            return MossAsrEngine(c, s)
        }

        @JvmStatic private external fun nativeInit(modelPath: String, threads: Int): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeTranscribe(ctx: Long, pcm: FloatArray): String
        @JvmStatic private external fun nativeInitSpeaker(modelPath: String, threads: Int): Long
        @JvmStatic private external fun nativeFreeSpeaker(ctx: Long)
        @JvmStatic private external fun nativeEmbed(
            ctx: Long, pcm: FloatArray, starts: IntArray, ends: IntArray,
        ): Array<FloatArray?>
    }
}
