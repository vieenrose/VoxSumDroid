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
    private const val HEADER = 44
}

/** Append-only writer: floats in [-1,1] → little-endian PCM16, with a self-patching WAV header. */
class WavWriter(private val file: File) : AutoCloseable {
    private val out = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private var samples = 0L
    private val buf = ByteArray(1 shl 16)

    init {
        out.write(ByteArray(44))   // placeholder header; real sizes written on close()
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

    override fun close() {
        out.flush(); out.close()
        // Patch the 44-byte canonical header now that the data size is known. WAV stores chunk sizes as
        // UNSIGNED 32-bit, so cap at the format maximum: a >4 GB recording (~37 h at 16 kHz mono) then
        // writes a valid (maxed-out) header instead of an Int that wraps to a tiny wrong size. WavSlicer
        // derives the sample count from the file length, not these fields, so diarization is unaffected.
        val pcmBytes = samples * 2
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
