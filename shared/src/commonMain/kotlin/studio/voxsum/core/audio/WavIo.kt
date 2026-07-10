package studio.voxsum.core.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming reader/writer for 16 kHz mono 16-bit PCM WAV — the canonical on-disk audio the whole
 * pipeline streams from/to, so memory scales with a chunk, not the recording length.
 *
 * [WavWriter] appends float chunks straight to a file (placeholder header up front, patched on
 * [close]); [WavSlicer] reads a [startSec, endSec) range back as floats for per-utterance diarization
 * without ever holding the full waveform.
 */
object WavIo {
    const val SAMPLE_RATE = 16_000
    const val HEADER = 44

    /**
     * True if [file] is ALREADY a canonical 16 kHz mono 16-bit PCM WAV (the format the whole
     * pipeline produces — library captures and decode outputs). Lets a re-save skip the expensive
     * decode-to-work-WAV pass and stream the file straight to Opus/AAC. Strict on purpose: a false
     * positive would embed a non-canonical WAV the transcoder assumes is 16 kHz mono.
     */
    fun isCanonical16kMono(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < HEADER) return false
        val h = ByteArray(HEADER)
        file.inputStream().use { if (it.read(h) < HEADER) return false }
        fun str(o: Int, s: String) = s.toByteArray().withIndex().all { (i, b) -> h[o + i] == b }
        fun u16(o: Int) = (h[o].toInt() and 0xFF) or ((h[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = (h[o].toInt() and 0xFF) or ((h[o + 1].toInt() and 0xFF) shl 8) or
            ((h[o + 2].toInt() and 0xFF) shl 16) or ((h[o + 3].toInt() and 0xFF) shl 24)
        str(0, "RIFF") && str(8, "WAVE") && str(12, "fmt ") &&
            u16(20) == 1 &&               // PCM
            u16(22) == 1 &&               // mono
            u32(24) == SAMPLE_RATE &&     // 16 kHz
            u16(34) == 16                 // 16-bit
    }.getOrDefault(false)
}

/**
 * Overwrite the leading 44 bytes of [file] with a canonical 16 kHz mono PCM16 WAV header sized for
 * [sampleCount] samples. Used both to finalize a clean recording and to *repair* one that a process
 * kill left with a placeholder header (see [studio.voxsum.core.audio.RecordingRecovery]). Opening a
 * separate RandomAccessFile at offset 0 is safe while a streaming writer appends at the end — the
 * two touch disjoint byte ranges. WAV chunk sizes are UNSIGNED 32-bit, so cap at the format maximum
 * (a >4 GB / ~37 h recording writes a maxed-out header rather than an Int that wraps to a wrong size).
 */
fun patchWavHeader(file: File, sampleCount: Long) {
    val pcmBytes = sampleCount * 2
    val dataSize = minOf(pcmBytes, 0xFFFF_FFFFL).toInt()
    val riffSize = minOf(36 + pcmBytes, 0xFFFF_FFFFL).toInt()
    RandomAccessFile(file, "rw").use { raf ->
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(riffSize); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(WavIo.SAMPLE_RATE); h.putInt(WavIo.SAMPLE_RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataSize)
        raf.seek(0); raf.write(h.array())
    }
}

/** Append-only writer: floats in [-1,1] → little-endian PCM16, with a self-patching WAV header. */
class WavWriter(private val file: File) : AutoCloseable {
    private val out = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private var samples = 0L
    private val buf = ByteArray(1 shl 16)

    init {
        out.write(ByteArray(44))   // placeholder header; real sizes written on close()/checkpoint()
    }

    fun write(chunk: FloatArray, len: Int = chunk.size) {
        var i = 0
        var b = 0
        while (i < len) {
            if (b + 2 > buf.size) { out.write(buf, 0, b); b = 0 }
            val s = (chunk[i] * 32767f).toInt().coerceIn(-32768, 32767)
            buf[b++] = (s and 0xFF).toByte()
            buf[b++] = ((s shr 8) and 0xFF).toByte()
            i++
        }
        if (b > 0) out.write(buf, 0, b)
        samples += len
    }

    /** Total samples written so far (for utterance timing while streaming). */
    fun sampleCount(): Long = samples

    /**
     * Flush buffered PCM to the OS and finalize the header so the file is a valid, playable WAV up to
     * this instant. Called periodically during recording (see [AudioRecorder]) so that if the process
     * is killed mid-meeting, the on-disk file is recoverable up to the last checkpoint rather than
     * left with a placeholder header. Flushing hands the bytes to the kernel, which survives a process
     * kill (this guards against kills, not power loss — no fsync needed).
     */
    fun checkpoint() {
        out.flush()
        patchWavHeader(file, samples)
    }

    override fun close() {
        out.flush(); out.close()
        // Patch the canonical header now that the final data size is known. WavSlicer derives the
        // sample count from the file length, not these fields, so diarization is unaffected.
        patchWavHeader(file, samples)
    }
}

/** Random-access reader: pulls a [from, to) sample slice back as floats (for diarization). */
class WavSlicer(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val dataOffset = 44L   // canonical header written by WavWriter

    val totalSamples: Long get() = ((raf.length() - dataOffset) / 2).coerceAtLeast(0)

    /** Read samples in [from, to) (clamped) as floats in [-1,1]. */
    fun read(fromSample: Long, toSample: Long): FloatArray {
        val n = totalSamples
        val a = fromSample.coerceIn(0, n)
        val b = toSample.coerceIn(a, n)
        val count = (b - a).toInt()
        if (count <= 0) return FloatArray(0)
        val bytes = ByteArray(count * 2)
        synchronized(raf) {
            raf.seek(dataOffset + a * 2)
            raf.readFully(bytes)
        }
        val out = FloatArray(count)
        var j = 0
        for (i in 0 until count) {
            val s = (bytes[j].toInt() and 0xFF) or (bytes[j + 1].toInt() shl 8)   // signed LE16
            out[i] = s / 32768f
            j += 2
        }
        return out
    }

    override fun close() = raf.close()
}
