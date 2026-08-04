package studio.voxsum.core.diarization

import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.asr.LiteVad
import studio.voxsum.core.asr.VadSegmenter
import studio.voxsum.core.events.TranscriptEvent
import java.io.File

/**
 * Field repro: a 41-minute two-host zh-TW podcast came back tagged as ONE speaker.
 *
 * Runs the REAL [DiarizationEngine] on the real audio, on x86, both ways — legacy
 * (SpectralClustering over per-utterance embeddings) and segmentation-first (pyannote) — and
 * reports the speaker count each produces. Running it here rather than on the reference ARM device
 * is deliberate: the diarization code is shared, x86 is ~40x faster, and instrumenting the device
 * would mean uninstalling the user's release build and destroying their session library.
 *
 * Utterance boundaries come from the VAD rather than from ASR. That is the one fidelity gap: the
 * app's boundaries come from its ASR engine's own VAD pass, so segment edges can differ slightly.
 * It is the right trade for the question being asked — whether the CLUSTERER collapses to k=1 —
 * because it removes a 40-minute ASR run from every iteration.
 *
 *   VOXSUM_NATIVE_LIB_DIR=desktop/appResources/linux-x64 \
 *   VOXSUM_MODELS=~/.local/share/VoxSum/models \
 *   VOXSUM_TEST_WAV=/tmp/yd_s5e57.wav ./gradlew :shared:jvmTest --tests '*DiarizationFieldRepro*'
 */
class DiarizationFieldRepro {

    private val libDir = System.getenv("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
    private val modelsDir = System.getenv("VOXSUM_MODELS")?.let(::File)
    private val wav = System.getenv("VOXSUM_TEST_WAV")?.let(::File)

    /** 16 kHz mono PCM s16le WAV → float samples. Deliberately minimal: no decoder dependency, so
     *  this test lives in :shared and needs nothing from :desktop. */
    private fun readWav(f: File): FloatArray {
        val b = f.readBytes()
        var pos = 12
        var dataOff = -1
        var dataLen = 0
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

    @Test fun reportSpeakerCountBothPaths() {
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR", libDir?.let { File(it, "libvoxsum-mosslite.so").exists() } == true)
        assumeTrue("set VOXSUM_MODELS", modelsDir?.isDirectory == true)
        assumeTrue("set VOXSUM_TEST_WAV", wav?.exists() == true)
        System.load(File(libDir, "libvoxsum-mosslite.so").absolutePath)

        val pcm = readWav(wav!!)
        println("[repro] ${pcm.size} samples = ${"%.1f".format(pcm.size / 16000.0 / 60)} min")

        // VAD → utterances (text is irrelevant to clustering; only the time spans are used).
        val vadFile = File(modelsDir, "silero-vad.tflite")
        assumeTrue("no silero-vad.tflite in $modelsDir", vadFile.exists())
        val vad = LiteVad.load(vadFile)
        assumeTrue("LiteVad failed to load $vadFile", vad != null)
        val segs = vad!!.use { v ->
            val seg = VadSegmenter(v)
            seg.accept(pcm)
            seg.flush()
            seg.segments.toList()
        }
        val utts = segs.mapIndexed { i, s ->
            TranscriptEvent.Utterance(
                index = i,
                text = "",
                startSec = s.startSample / 16000.0,
                endSec = (s.startSample + s.samples.size) / 16000.0,
            )
        }
        println("[repro] ${utts.size} VAD segments, total speech " +
            "${"%.1f".format(utts.sumOf { it.endSec - it.startSec } / 60)} min")

        // The LiteRT CAM++ — the artifact LiteSpeakerEmbedder can actually load. Hardcoding the
        // ONNX here is what produced the first (bogus) 1-speaker result.
        val emb = File(modelsDir, "campplus_cn_common_500f.tflite")
        val segm = File(modelsDir, "pyannote_segmentation_3_0.onnx")
        assumeTrue("no CAM++ at $emb", emb.exists())

        for ((label, segModel) in listOf("legacy (spectral)" to null, "segmentation-first" to segm)) {
            if (segModel != null && !segModel.exists()) { println("[repro] $label: model missing, skipped"); continue }
            val t0 = System.currentTimeMillis()
            DiarizationEngine(
                embeddingModel = emb.absolutePath,
                numThreads = 4,
                numClusters = -1,                       // auto-k, exactly as the app defaults
                segmentationModel = segModel?.absolutePath,
            ).use { d ->
                assumeTrue("speaker model did not load — see stderr", d.embedderReady)
                val (tagged, count) = d.assignSpeakers(pcm16k = pcm, utterances = utts)
                val distinct = tagged.mapNotNull { it.speaker }.distinct().sorted()
                println("[repro] %-20s -> speakerCount=%d distinct=%s usedSegmenter=%s in %ds"
                    .format(label, count, distinct, d.usedSegmenter,
                        (System.currentTimeMillis() - t0) / 1000))
            }
        }
    }
}
