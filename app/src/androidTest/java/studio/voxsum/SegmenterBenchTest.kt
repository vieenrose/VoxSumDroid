package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * On-device efficiency micro-benchmark for the segmentation-first diarizer's expensive stage:
 * sherpa OfflineSpeakerDiarization.process (pyannote segmentation-3.0 + CAM++ internal pass) over
 * a pushed 16 kHz WAV. Reports wall time + island count to logcat and a JSON in the app's
 * external files dir. NOT a pass/fail test — skips when inputs are missing.
 *
 *   adb push excerpt.wav /data/local/tmp/segbench.wav
 *   adb push model.onnx /data/local/tmp/pyannote-seg.onnx
 *   adb shell am instrument -w -r -e class studio.voxsum.SegmenterBenchTest \
 *       studio.voxsum.test/androidx.test.runner.AndroidJUnitRunner
 *   adb pull /sdcard/Android/data/studio.voxsum/files/segbench.json
 */
@RunWith(AndroidJUnit4::class)
class SegmenterBenchTest {

    private fun readWav16k(f: File): FloatArray {
        val bytes = f.readBytes()
        val n = (bytes.size - 44) / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[44 + 2 * i].toInt() and 0xff
            val hi = bytes[44 + 2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768f
        }
        return out
    }

    @Test
    fun bench() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val wav = File(args.getString("wav") ?: "/data/local/tmp/segbench.wav")
        val segModel = File(args.getString("seg") ?: "/data/local/tmp/pyannote-seg.onnx")
        assumeTrue("no WAV at $wav", wav.exists())
        assumeTrue("no seg model at $segModel", segModel.exists())
        val threads = args.getString("threads")?.toIntOrNull() ?: 2

        val models = studio.voxsum.core.models.ModelManager(app.filesDir)
        if (!models.diarizationReady()) {
            kotlinx.coroutines.runBlocking { models.ensureDiarizationModels { } }
        }
        val camppp = models.embeddingModel.absolutePath

        val pcm = readWav16k(wav)
        val audioSec = pcm.size / 16000.0

        val t0 = System.nanoTime()
        val sd = com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization(
            config = com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig(
                segmentation = com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig(
                    pyannote = com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = segModel.absolutePath,
                    ),
                    numThreads = threads,
                ),
                embedding = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig(
                    model = camppp, numThreads = threads,
                ),
                clustering = com.k2fsa.sherpa.onnx.FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.2f,
                minDurationOff = 0.3f,
            ),
        )
        val loadMs = (System.nanoTime() - t0) / 1_000_000

        val t1 = System.nanoTime()
        val islands = sd.process(pcm)
        val processMs = (System.nanoTime() - t1) / 1_000_000
        sd.release()

        val rtf = processMs / 1000.0 / audioSec
        val msg = String.format(
            Locale.US,
            "{\"audioSec\":%.1f,\"loadMs\":%d,\"processMs\":%d,\"rtf\":%.3f,\"islands\":%d}",
            audioSec, loadMs, processMs, rtf, islands.size,
        )
        Log.i("SegmenterBench", msg)
        File(app.getExternalFilesDir(null), "segbench_t" + threads + ".json").writeText(msg)
    }
}
