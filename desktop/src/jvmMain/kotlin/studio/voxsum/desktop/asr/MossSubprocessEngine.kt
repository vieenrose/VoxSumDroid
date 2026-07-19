package studio.voxsum.desktop.asr

import studio.voxsum.core.asr.moss.MOSS_SR
import studio.voxsum.desktop.NativeLibs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Desktop MOSS-TD backend: spawns the RapidSpeech.cpp `moss-td-test` CLI once per audio window
 * and the `rs-speaker-embed` CLI for CAM++ speaker vectors — the exact invocation the reference
 * demo Space (`Luigi/moss-transcribe-diarize-cpp`) uses. This is the "decode this PCM" +
 * "embed these ranges" pair that [studio.voxsum.core.asr.moss.MossPipeline] injects; all windowing
 * and speaker-linking logic is the shared pure-Kotlin pipeline, not here.
 *
 * The binaries + models are located, not bundled per-call: [mossBin]/[speakerBin] are staged into
 * appResources/linux-x64 by desktop/scripts/build-moss.sh.
 */
class MossSubprocessEngine(
    private val mossBin: File,
    private val model: File,
    private val speakerBin: File?,
    private val speakerModel: File?,
    private val threads: Int,
) {
    val hasSpeakerEmbedding: Boolean get() = speakerBin != null && speakerModel != null

    /** Decode one window → the raw `[start][Sxx]text[end]` TRANSCRIPTION block (window-local seconds). */
    fun transcribeWindow(pcm: FloatArray): String {
        val wav = File.createTempFile("moss-win", ".wav")
        try {
            writeWav16(wav, pcm)
            val out = run(
                listOf(mossBin.absolutePath, model.absolutePath, wav.absolutePath),
                timeoutSec = maxOf(1800L, (pcm.size / MOSS_SR) * 6L),
            )
            return TRANSCRIPTION.find(out)?.groupValues?.get(1)?.trim() ?: ""
        } finally {
            wav.delete()
        }
    }

    /** CAM++ embeddings for [ranges] (sample [a,b) spans into [pcm]); null per range that was too
     *  short / failed. Returns all-null when the speaker model isn't provisioned. */
    fun embed(pcm: FloatArray, ranges: List<IntRange>): List<FloatArray?> {
        if (ranges.isEmpty()) return emptyList()
        val bin = speakerBin; val spk = speakerModel
        if (bin == null || spk == null) return List(ranges.size) { null }
        val f32 = File.createTempFile("moss-emb", ".f32")
        try {
            writeF32(f32, pcm)
            val args = ArrayList<String>()
            args += bin.absolutePath; args += spk.absolutePath; args += f32.absolutePath
            for (r in ranges) args += "${r.first}:${r.last + 1}"   // half-open [a,b)
            val out = run(args, timeoutSec = 600L)
            // One line per range: "nil" or >=64 space-separated floats. Keep the last N lines.
            val lines = out.lineSequence()
                .map { it.trim() }
                .filter { it == "nil" || it.split(Regex("\\s+")).size >= 64 }
                .toList()
                .takeLast(ranges.size)
            val res = ArrayList<FloatArray?>(ranges.size)
            for (l in lines) {
                if (l == "nil") { res += null; continue }
                val v = l.split(Regex("\\s+")).map { it.toFloat() }.toFloatArray()
                var n = 0.0
                for (x in v) n += x.toDouble() * x
                val norm = (kotlin.math.sqrt(n) + 1e-9).toFloat()
                res += FloatArray(v.size) { v[it] / norm }
            }
            while (res.size < ranges.size) res += null
            return res
        } catch (t: Throwable) {
            return List(ranges.size) { null }
        } finally {
            f32.delete()
        }
    }

    private fun run(command: List<String>, timeoutSec: Long): String {
        val pb = ProcessBuilder(command)
        pb.environment()["RS_THREADS"] = threads.toString()
        pb.environment()["RS_AUDIO_KV_WINDOW"] = "45"   // v6.x models are trained for this
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
        private val TRANSCRIPTION = Regex("""(?s)===== TRANSCRIPTION =====\n(.*?)\n=====""")

        /** Resolve the staged binaries + models. Returns null when the moss binary or model is absent.
         *  The binaries live in a `moss/` subdir of the native-resources dir (isolated from
         *  llama.cpp's libggml.so — see build-moss.sh). */
        fun create(model: File, speakerModel: File?, threads: Int): MossSubprocessEngine? {
            val dir = NativeLibs.libDir()?.let { File(it, "moss") } ?: return null
            val mossBin = File(dir, "moss-td-test").takeIf { it.canExecute() } ?: return null
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
