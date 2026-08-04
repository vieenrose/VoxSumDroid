package studio.voxsum.core.diarization

import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.asr.LiteSpeakerEmbedder
import studio.voxsum.core.asr.MossLiteEngine
import studio.voxsum.core.asr.moss.MOSS_SR
import java.io.File

/**
 * MOSS-TD as an INDEPENDENT second opinion on speaker count.
 *
 * MOSS-TD diarizes natively in one pass — speakers come from the same model that transcribes, with
 * CAM++ only linking them across windows — so it shares no code with DiarizationEngine's
 * embed-then-cluster pipeline. When the two per-utterance and segmentation-first paths disagree
 * (measured: 2 vs 3 on a real 10-minute meeting) and no labels exist, a genuinely independent
 * estimator is the cheapest evidence available.
 *
 * NOT ground truth. It is a model, and it is the strongest ASR we have (4.2/10.6 CER against
 * x-asr's 8.6/14.5 on the held-out zh-TW set) but that says nothing about its diarization. Treat
 * agreement as evidence and disagreement as a reason to get labels.
 *
 *   VOXSUM_NATIVE_LIB_DIR=desktop/appResources/linux-x64 \
 *   VOXSUM_MODELS=~/.local/share/VoxSum/models \
 *   VOXSUM_TEST_WAV=/tmp/meeting_10min.wav \
 *   ./gradlew :shared:jvmTest --tests '*MossSecondOpinion*' --rerun-tasks
 */
class MossSecondOpinion {

    private val libDir = System.getenv("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
    private val modelsDir = System.getenv("VOXSUM_MODELS")?.let(::File)
    private val wav = System.getenv("VOXSUM_TEST_WAV")?.let(::File)

    private fun readWav(f: File): FloatArray {
        val b = f.readBytes()
        var pos = 12; var dataOff = -1; var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val sz = (b[pos + 4].toInt() and 0xFF) or ((b[pos + 5].toInt() and 0xFF) shl 8) or
                ((b[pos + 6].toInt() and 0xFF) shl 16) or ((b[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "data") { dataOff = pos + 8; dataLen = sz; break }
            pos += 8 + sz + (sz and 1)
        }
        require(dataOff > 0) { "no data chunk in $f" }
        val n = minOf(dataLen, b.size - dataOff) / 2
        return FloatArray(n) { i ->
            val lo = b[dataOff + i * 2].toInt() and 0xFF
            val hi = b[dataOff + i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    @Test fun reportMossSpeakerCount() {
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR", libDir?.let { File(it, "libvoxsum-mosslite.so").exists() } == true)
        assumeTrue("set VOXSUM_MODELS", modelsDir?.isDirectory == true)
        assumeTrue("set VOXSUM_TEST_WAV", wav?.exists() == true)
        System.load(File(libDir, "libvoxsum-mosslite.so").absolutePath)

        val enc = File(modelsDir, "moss_td_encoder_q8.tflite")
        val emb = File(modelsDir, "moss_td_embedder_q8.tflite")
        val dec = File(modelsDir, "moss_td_decoder_v2_q4b32_ekv2560.tflite")
        val vocab = File(modelsDir, "moss_td_vocab.json")
        val merges = File(modelsDir, "moss_td_merges.txt")
        listOf(enc, emb, dec, vocab, merges).forEach {
            assumeTrue("missing MOSS-TD component ${it.name}", it.exists())
        }

        val pcm = readWav(wav!!)
        println("[moss] ${"%.1f".format(pcm.size / MOSS_SR.toDouble() / 60)} min")

        val cores = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors()))
        val engine = MossLiteEngine.create(
            encoder = enc, embedder = emb, decoder = dec, vocabJson = vocab,
            cacheDir = File(System.getProperty("java.io.tmpdir"), "voxsum-moss-cache").apply { mkdirs() },
            encThreads = cores, decThreads = cores, context = "", mergesTxt = merges,
        )
        assumeTrue("MOSS-TD engine failed to load", engine != null)

        val spk = File(modelsDir, "campplus_cn_common_500f.tflite")
            .takeIf { it.exists() }?.let { LiteSpeakerEmbedder.load(it) }
        println("[moss] cross-window speaker linker: ${if (spk != null) "CAM++ loaded" else "ABSENT (per-window tags only)"}")

        // Same call the app makes: MossPipeline does the windowing, MOSS-TD tags speakers inside
        // each window, and CAM++ (embedUnit) only links those tags ACROSS windows.
        val t0 = System.currentTimeMillis()
        val durS = pcm.size.toDouble() / MOSS_SR
        val linked = kotlinx.coroutines.runBlocking {
            studio.voxsum.core.asr.moss.MossPipeline.run(
                durS = durS,
                getWindow = { off, len ->
                    if (off >= pcm.size) FloatArray(0) else pcm.copyOfRange(off, minOf(pcm.size, off + len))
                },
                decodeWindow = { p, maxNew -> engine!!.transcribeWindow(p, maxNew) },
                embedUnit = spk?.let { e ->
                    val f: suspend (FloatArray) -> FloatArray? = { p -> e.embed(p) }
                    f
                },
            )
        }
        val labels = linked.mapNotNull { it.speaker }.distinct().sorted()
        println("[moss] ${linked.size} segments in ${(System.currentTimeMillis() - t0) / 1000}s")
        println("[moss] SPEAKERS: ${labels.size} $labels")
        linked.take(12).forEach {
            println("[moss]   S%-2s %6.1f-%6.1f  %s".format(
                it.speaker ?: -1, it.start, it.end, it.text.take(46)))
        }
        runCatching { engine?.close() }
        runCatching { spk?.close() }
    }
}
