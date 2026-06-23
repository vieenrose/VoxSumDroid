package studio.voxsum

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit PCM WAV reader → mono float [-1,1]. Test wavs are 16 kHz mono. */
fun readWav16kMono(input: InputStream): FloatArray {
    val bytes = input.use { it.readBytes() }
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12 // skip "RIFF"<size>"WAVE"
    var dataOffset = -1
    var dataLen = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val size = bb.getInt(pos + 4)
        if (id == "data") { dataOffset = pos + 8; dataLen = size; break }
        pos += 8 + size + (size and 1)
    }
    require(dataOffset >= 0) { "no data chunk in wav" }
    val n = minOf(dataLen, bytes.size - dataOffset) / 2
    val out = FloatArray(n)
    val shorts = bb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    shorts.position(dataOffset)
    for (i in 0 until n) out[i] = shorts.short / 32768f
    return out
}
