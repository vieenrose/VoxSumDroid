package studio.voxsum.desktop.asr

import studio.voxsum.core.asr.moss.MOSS_SR
import studio.voxsum.desktop.NativeLibs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Desktop MOSS-TD backend: spawns the vendored port's `rs-moss-td` CLI once per audio window
 * (`rs-moss-td transcribe <model> <wav> --max-new N` → raw `[start][Sxx]text` on stdout) and the
 * `rs-speaker-embed` CLI for CAM++ speaker vectors. This is the "decode this PCM" + "embed this
 * pooled audio" pair that [studio.voxsum.core.asr.moss.MossPipeline] injects; all windowing and
 * speaker-linking logic is the shared pure-Kotlin pipeline, not here.
 *
 * Per-window model reload is a deliberate simplicity trade: a desktop loads the 0.76 GB q4mix in
 * a couple of seconds against a multi-minute decode. (The HF Space dlopens `libmoss_td.so` via
 * ctypes instead; the JVM equivalent would need JNA/JNI plumbing for no measured win.)
 */
class MossSubprocessEngine(
    private val mossBin: File,
    private val model: File,
    private val speakerBin: File?,
    private val speakerModel: File?,
    private val threads: Int,
) {
    val hasSpeakerEmbedding: Boolean get() = speakerBin != null && speakerModel != null

    /** Decode one window → the raw `[start][Sxx]text` transcript (window-local seconds). */
    fun transcribeWindow(pcm: FloatArray, maxNewTokens: Int): String {
        val wav = File.createTempFile("moss-win", ".wav")
        try {
            writeWav16(wav, pcm)
            return run(
                listOf(
                    mossBin.absolutePath, "transcribe", model.absolutePath, wav.absolutePath,
                    "--max-new", maxNewTokens.toString(),
                ),
                timeoutSec = maxOf(1800L, (pcm.size / MOSS_SR) * 6L),
            ).trim()
        } finally {
            wav.delete()
        }
    }

    /** CAM++ embedding of one speaker unit's pooled audio; null if too short / failed /
     *  the speaker model isn't provisioned. */
    fun embedUnit(pcm: FloatArray): FloatArray? {
        val bin = speakerBin; val spk = speakerModel
        if (bin == null || spk == null || pcm.isEmpty()) return null
        val f32 = File.createTempFile("moss-emb", ".f32")
        try {
            writeF32(f32, pcm)
            val out = run(
                listOf(bin.absolutePath, spk.absolutePath, f32.absolutePath, "0:${pcm.size}"),
                timeoutSec = 600L,
            )
            // One line for the single range: "nil" or >=64 space-separated floats.
            val line = out.lineSequence().map { it.trim() }
                .lastOrNull { it == "nil" || it.split(Regex("\\s+")).size >= 64 } ?: return null
            if (line == "nil") return null
            val v = line.split(Regex("\\s+")).map { it.toFloat() }.toFloatArray()
            var n = 0.0
            for (x in v) n += x.toDouble() * x
            val norm = (kotlin.math.sqrt(n) + 1e-9).toFloat()
            return FloatArray(v.size) { v[it] / norm }
        } catch (t: Throwable) {
            return null
        } finally {
            f32.delete()
        }
    }

    private fun run(command: List<String>, timeoutSec: Long): String {
        val pb = ProcessBuilder(command)
        pb.environment()["MTD_THREADS"] = threads.toString()   // vendored port
        pb.environment()["RS_THREADS"] = threads.toString()    // rs-speaker-embed
        pb.redirectErrorStream(false)
        val proc = pb.start()
        proc.errorStream.close()
        val bytes = proc.inputStream.readBytes()   // drain stdout so the child never blocks on a full pipe
        if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw RuntimeException("moss subprocess timed out after ${timeoutSec}s")
        }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        /** Resolve the staged binaries + models. Returns null when the moss binary or model is absent.
         *  The binaries live in a `moss/` subdir of the native-resources dir (isolated from
         *  llama.cpp's libggml.so — see build-moss.sh). */
        fun create(model: File, speakerModel: File?, threads: Int): MossSubprocessEngine? {
            val dir = NativeLibs.libDir()?.let { File(it, "moss") } ?: return null
            val mossBin = File(dir, "rs-moss-td").takeIf { it.canExecute() } ?: return null
            val spkBin = File(dir, "rs-speaker-embed").takeIf { it.canExecute() }
            val spk = speakerModel?.takeIf { it.exists() }
            return MossSubprocessEngine(
                mossBin = mossBin,
                model = model,
                speakerBin = if (spk != null) spkBin else null,
                speakerModel = if (spkBin != null) spk else null,
                threads = threads,
            )
        }

        private fun writeF32(dest: File, pcm: FloatArray) {
            val buf = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) buf.putFloat(v)
            dest.writeBytes(buf.array())
        }

        /** Minimal 16 kHz mono PCM-16 WAV (RIFF). */
        private fun writeWav16(dest: File, pcm: FloatArray) {
            val nBytes = pcm.size * 2
            val bo = ByteArrayOutputStream(44 + nBytes)
            fun i32(v: Int) { bo.write(v); bo.write(v ushr 8); bo.write(v ushr 16); bo.write(v ushr 24) }
            fun i16(v: Int) { bo.write(v); bo.write(v ushr 8) }
            bo.write("RIFF".toByteArray()); i32(36 + nBytes); bo.write("WAVE".toByteArray())
            bo.write("fmt ".toByteArray()); i32(16); i16(1); i16(1)   // PCM, mono
            i32(MOSS_SR); i32(MOSS_SR * 2); i16(2); i16(16)           // byte-rate, block-align, bits
            bo.write("data".toByteArray()); i32(nBytes)
            val samples = ByteBuffer.allocate(nBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) {
                val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
                samples.putShort(s.toShort())
            }
            bo.write(samples.array())
            dest.writeBytes(bo.toByteArray())
        }
    }
}
