package studio.voxsum.desktop.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.events.TranscriptEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VibeVoice-ASR-BitNet, driven as a SUBPROCESS over `asr_infer`.
 *
 * WHY A SUBPROCESS, when every other backend here is in-process LiteRT:
 * VibeASR's LM half runs on a *fork* of llama.cpp carrying the I2_S/BitNet ternary
 * kernels, and the desktop already links a different, much newer llama.cpp for the
 * Gemma summarizer. Two llama.cpp builds with the same SONAME in one process is the
 * collision the MOSS-TD work already had to engineer around; a process boundary
 * makes it a non-issue for free, at the cost of one model load per run.
 *
 * The binary is the HYBRID build (-DVIBEASR_LITERT=ON): the audio front end runs on
 * LiteRT/XNNPACK and only the Qwen2.5 decoder stays on ggml. Measured on a Boox
 * Tab Mini C that is RTF 3.65 -> 2.79 against the all-ggml build, because XNNPACK
 * wins convolutional encoding while ggml wins autoregressive decode.
 *
 * TIMESTAMP CAVEAT: `asr_infer` returns plain text with no time markers, so the
 * transcript is split at sentence boundaries and timings are apportioned by
 * character count across the clip. Those boundaries are therefore ESTIMATES — good
 * enough to drive the transcript player, but not frame-accurate the way X-ASR's
 * VAD segments or MOSS-TD's emitted markers are. A persistent `asr_stream_server`
 * with real timings is the follow-up; this keeps the integration honest and small.
 */
class VibeSubprocessEngine(
    private val binary: File,
    private val vaeModel: File,
    private val lmModel: File,
    private val numThreads: Int = 4,
    private val weightCache: File? = null,
    private val workDir: File = File(System.getProperty("java.io.tmpdir")),
) : SpeechEngine {

    override fun transcribe(pcm16k: FloatArray): Flow<TranscriptEvent> = flow {
        emit(TranscriptEvent.Status("Transcribing with VibeVoice-ASR…"))
        val totalS = pcm16k.size / SAMPLE_RATE.toDouble()
        val text = runBinary(pcm16k)
        if (text.isBlank()) {
            emit(TranscriptEvent.Complete(emptyList()))
            return@flow
        }
        val utterances = apportion(text, totalS).mapIndexed { i, (start, end, sentence) ->
            TranscriptEvent.Utterance(index = i, text = sentence, startSec = start, endSec = end)
        }
        utterances.forEach { emit(it) }
        emit(TranscriptEvent.Progress(1f))
        emit(TranscriptEvent.Complete(utterances))
    }.flowOn(Dispatchers.IO)

    /** Live capture would mean a process launch per chunk — a model load each time.
     *  Not supported until the streaming server lands. */
    override fun transcribeLive(chunks: Flow<FloatArray>): Flow<TranscriptEvent> =
        throw UnsupportedOperationException("VibeVoice-ASR has no streaming mode yet")

    override fun decodeSlice(samples: FloatArray): String = runCatching { runBinary(samples) }
        .getOrDefault("")

    override fun close() {}

    private fun runBinary(pcm: FloatArray, windowed: Boolean = true): String {
        val wav = File.createTempFile("vibeasr-", ".wav", workDir)
        try {
            writeWav16kMono(wav, pcm)
            val cmd = buildList {
                add(binary.absolutePath)
                addAll(listOf("--vae-model", vaeModel.absolutePath))
                addAll(listOf("--lm-model", lmModel.absolutePath))
                addAll(listOf("--audio", wav.absolutePath))
                addAll(listOf("-t", numThreads.toString()))
                add("--greedy")
                // 512 covers ~42 s of audio at ~12 tokens/s and costs 14 MB of KV;
                // asr_infer's own default of 16384 costs 448 MB for headroom that a
                // windowed pipeline can never use.
                addAll(listOf("-c", "512"))
                // NOT an optimization — a correctness requirement. The whole-file
                // pass measured 79.7% WER on a 5-minute clip: the i8_s VAE's
                // features decay past ~10-20 s of input (cosine vs f32 drops
                // 0.92 -> 0.78 across one 60 s clip) and the LM transcribes the
                // rot fluently. asr_infer re-normalizes each window itself.
                if (windowed) addAll(listOf("--window-secs", "10"))
            }
            val pb = ProcessBuilder(cmd).directory(workDir)
            // The binary needs libllama/libggml (its own fork) and libLiteRt, which
            // are staged beside it. Its build-tree RPATH does not survive being
            // copied out of the build directory, so point the loader at the binary's
            // own directory rather than relying on it.
            binary.parentFile?.let { dir ->
                val existing = System.getenv("LD_LIBRARY_PATH")
                pb.environment()["LD_LIBRARY_PATH"] =
                    if (existing.isNullOrBlank()) dir.absolutePath
                    else "${dir.absolutePath}:$existing"
            }
            weightCache?.let {
                it.parentFile?.mkdirs()
                // Without this XNNPACK repacks the encoder into ~1.2 GB of anonymous
                // RAM and repays ~7.7 s on every launch; with it, 576 MB and 0.1 s.
                pb.environment()["VIBEASR_LITERT_CACHE"] = it.absolutePath
            }
            pb.redirectErrorStream(false)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val err = proc.errorStream.bufferedReader().use { it.readText() }
            val rc = proc.waitFor()
            if (rc != 0) {
                // A binary from before --window-secs rejects the flag; fall back to
                // the whole-file pass rather than failing outright. Long audio will
                // be degraded, but the user asked for a transcript, not an error.
                if (windowed && err.contains("Unknown argument"))
                    return runBinary(pcm, windowed = false)
                error("asr_infer exited $rc: ${err.lines().takeLast(3).joinToString(" | ")}")
            }
            return out.trim()
        } finally {
            wav.delete()
        }
    }

    /**
     * Split into sentences and spread the clip's duration over them in proportion to
     * their length. Character count is a crude proxy for speaking time — it is wrong
     * for a pause-heavy clip, and wrong in the other direction across scripts, since
     * a Chinese character carries far more time than a Latin letter. It is used
     * because the binary reports no timings at all, not because it is accurate.
     */
    internal fun apportion(text: String, totalS: Double): List<Triple<Double, Double, String>> {
        val parts = SENTENCE.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val weights = parts.map { it.length.coerceAtLeast(1).toDouble() }
        val sum = weights.sum()
        val out = ArrayList<Triple<Double, Double, String>>(parts.size)
        var t = 0.0
        for (i in parts.indices) {
            val dur = totalS * weights[i] / sum
            // Last segment ends exactly at totalS so rounding cannot leave a gap.
            val end = if (i == parts.lastIndex) totalS else t + dur
            out.add(Triple(t, end, parts[i]))
            t = end
        }
        return out
    }

    private fun writeWav16kMono(file: File, pcm: FloatArray) {
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buf.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes)
        for (v in pcm) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(s.toShort())
        }
        file.writeBytes(buf.array())
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        // Sentence enders across the scripts this model covers, plus newlines. The
        // lookbehind keeps the punctuation attached to the sentence it ends.
        private val SENTENCE = Regex("(?<=[.!?。！？；;])\\s*|\\n+")
    }
}
