package studio.voxsum.core.asr

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Head-to-head accuracy of the two VAD-segmented backends on a labelled corpus: X-ASR (zipformer,
 * the default) vs Nemotron (multilingual). Reports CER for Chinese and WER for English.
 *
 * Not a unit test — a measurement harness, skipped unless pointed at models and a corpus:
 *   VOXSUM_NATIVE_LIB_DIR  dir holding libvoxsum-mosslite.so + libLiteRt.so
 *   VOXSUM_XASR_DIR        xasr_q8_octav.tflite + tokens.txt
 *   VOXSUM_NEMOTRON_DIR    nemotron_*.tflite + tokenizer.json
 *   VOXSUM_BENCH_TSV       lines of "<wav path>\t<reference>"
 *   VOXSUM_BENCH_LANG      "zh" (CER, Nemotron slot 4) or "en" (WER, slot 0)
 *   VOXSUM_BENCH_N         clip count (default 25)
 *
 * Both engines see the SAME audio, capped at Nemotron's fixed 11 s encoder window so neither is
 * penalised for windowing — this measures the acoustic models, not the segmentation around them.
 */
class AsrAccuracyBenchTest {

    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }

    /**
     * WAV → mono float. Handles BOTH encodings that show up in speech corpora: 16-bit integer PCM
     * (format 1) and 32-bit IEEE float (format 3 — what FLEURS ships). Reading float data as
     * shorts yields noise, which both engines dutifully transcribe as nothing.
     */
    private fun readWav(f: File): Pair<FloatArray, Int> {
        val b = f.readBytes()
        require(String(b, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file: $f" }
        var p = 12
        var channels = 1
        var rate = 16_000
        var bits = 16
        var format = 1
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val sz = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            when (id) {
                "fmt " -> {
                    val bb = ByteBuffer.wrap(b, p + 8, sz).order(ByteOrder.LITTLE_ENDIAN)
                    format = bb.short.toInt()
                    channels = bb.short.toInt()
                    rate = bb.int
                    bb.int; bb.short              // byte rate, block align
                    bits = bb.short.toInt()
                }
                "data" -> {
                    val bb = ByteBuffer.wrap(b, p + 8, sz).order(ByteOrder.LITTLE_ENDIAN)
                    val frames = sz / (bits / 8) / channels
                    val out = FloatArray(frames)
                    for (i in 0 until frames) {
                        var acc = 0f
                        for (c in 0 until channels) {
                            acc += when {
                                format == 3 && bits == 32 -> bb.float
                                bits == 16 -> bb.short.toFloat() / 32768f
                                else -> error("unsupported wav: format=$format bits=$bits ($f)")
                            }
                        }
                        out[i] = acc / channels
                    }
                    return out to rate
                }
            }
            p += 8 + sz + (sz and 1)
        }
        error("no data chunk in $f")
    }

    /** Punctuation/space-insensitive comparison — every engine punctuates differently. */
    private fun normalize(s: String, zh: Boolean): String {
        val stripped = s.lowercase().replace(Regex("[\\p{P}\\p{S}]"), "")
        return if (zh) stripped.replace(Regex("\\s+"), "") else stripped.replace(Regex("\\s+"), " ").trim()
    }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        val prev = IntArray(b.size + 1) { it }
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.size]
    }

    private fun units(s: String, zh: Boolean) =
        if (zh) s.map { it.toString() } else s.split(" ").filter { it.isNotEmpty() }

    @Test fun compareXasrAndNemotron() {
        val libDir = env("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
        val xasrDir = env("VOXSUM_XASR_DIR")?.let(::File)
        val nemDir = env("VOXSUM_NEMOTRON_DIR")?.let(::File)
        val tsv = env("VOXSUM_BENCH_TSV")?.let(::File)
        assumeTrue(
            "set VOXSUM_NATIVE_LIB_DIR + VOXSUM_XASR_DIR + VOXSUM_NEMOTRON_DIR + VOXSUM_BENCH_TSV",
            libDir != null && xasrDir != null && nemDir != null && tsv != null && tsv.exists(),
        )
        val so = File(libDir!!, "libvoxsum-mosslite.so")
        assumeTrue("libvoxsum-mosslite.so not built", so.exists())
        System.load(so.absolutePath)

        val zh = (env("VOXSUM_BENCH_LANG") ?: "zh") == "zh"
        val slot = if (zh) 4 else 0        // zh-CN / en-US prompt slots
        val limit = env("VOXSUM_BENCH_N")?.toIntOrNull() ?: 25
        val show = env("VOXSUM_BENCH_SHOW")?.toIntOrNull() ?: 3

        val clips = tsv!!.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) null else File(parts[0]).takeIf(File::exists)?.let { it to parts[1] }
        }.take(limit)
        assumeTrue("no usable clips in $tsv", clips.isNotEmpty())

        val xasr = XasrLiteEngine.load(File(xasrDir!!, "xasr_q8_octav.tflite"), File(xasrDir, "tokens.txt"), threads = 8)
        requireNotNull(xasr) { "X-ASR failed to load" }
        val nem = NemotronLiteEngine.load(
            File(nemDir!!, "nemotron_encoder_q8.tflite"), File(nemDir, "nemotron_prompt_fuse_fp32.tflite"),
            File(nemDir, "nemotron_decoder_fp16.tflite"), File(nemDir, "nemotron_joint_fp16.tflite"),
            File(nemDir, "tokenizer.json"), threads = 8,
        )
        requireNotNull(nem) { "Nemotron failed to load" }

        var xErr = 0; var nErr = 0; var total = 0
        var xSec = 0.0; var nSec = 0.0; var audioSec = 0.0
        xasr.use { x ->
            nem.use { n ->
                clips.forEachIndexed { i, (wav, ref) ->
                    val (pcmAll, rate) = readWav(wav)
                    if (rate != 16_000) return@forEachIndexed
                    // Cap both engines at Nemotron's fixed encoder window so the comparison is
                    // acoustic, not a test of who windows long audio better.
                    val pcm = pcmAll.copyOf(min(pcmAll.size, NemotronLiteEngine.MAX_DECODE_SEC * 16_000))
                    audioSec += pcm.size / 16_000.0

                    var t0 = System.nanoTime()
                    val xText = runCatching { x.decode(pcm).text }.getOrDefault("")
                    xSec += (System.nanoTime() - t0) / 1e9
                    t0 = System.nanoTime()
                    val nText = runCatching { n.decode(pcm, slot).text }.getOrDefault("")
                    nSec += (System.nanoTime() - t0) / 1e9

                    val r = units(normalize(ref, zh), zh)
                    if (r.isEmpty()) return@forEachIndexed
                    total += r.size
                    xErr += editDistance(r, units(normalize(xText, zh), zh))
                    nErr += editDistance(r, units(normalize(nText, zh), zh))
                    if (i < show) {
                        println("--- clip ${i + 1}  (${"%.1f".format(pcm.size / 16_000.0)} s)")
                        println("    REF   $ref")
                        println("    XASR  $xText")
                        println("    NEMO  $nText")
                    }
                }
            }
        }
        val metric = if (zh) "CER" else "WER"
        println(
            """
            ===== ASR accuracy (${clips.size} clips, ${"%.0f".format(audioSec)} s audio, lang=${if (zh) "zh" else "en"}) =====
              X-ASR     $metric ${"%.2f".format(100.0 * xErr / total)}%   RTF ${"%.3f".format(xSec / audioSec)}
              Nemotron  $metric ${"%.2f".format(100.0 * nErr / total)}%   RTF ${"%.3f".format(nSec / audioSec)}
            """.trimIndent(),
        )
    }
}
