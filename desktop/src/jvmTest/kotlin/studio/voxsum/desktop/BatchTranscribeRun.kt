package studio.voxsum.desktop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import studio.voxsum.desktop.asr.SpeechEngineFactory
import studio.voxsum.desktop.audio.AudioDecoder
import java.io.File

/**
 * Batch-transcribe a directory of audio files to transcript format v1, headlessly on x86.
 *
 * WHY THIS EXISTS. The dataset was being built by driving the Android app over adb, and that path
 * is unreliable for long unattended runs: ColorOS drops USB debugging on replug, and when it does
 * every adb call fails silently — the runner spun through the whole pool capturing nothing, three
 * times. x86 has no such failure mode and is roughly an order of magnitude faster than the phone,
 * so bulk transcript production belongs here. The phone stays the place we measure DEVICE
 * behaviour (memory, wall clock), which is the one thing x86 cannot tell us.
 *
 * Same acoustic model as the Android app (X-ASR zipformer transducer), same deployed Kotlin
 * segmentation, so the transcripts are the same shape the summarizer sees in production.
 *
 *   VOXSUM_AUDIO_DIR=/path/with/mp3s \
 *   VOXSUM_TRANSCRIPT_OUT=/path/for/txt \
 *   ./gradlew :desktop:jvmTest --tests '*BatchTranscribeRun*' --rerun-tasks -i
 *
 * Optional: VOXSUM_ASR_THREADS (default 8), VOXSUM_DIARIZE=1 to add speaker labels.
 */
class BatchTranscribeRun {

    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }

    /** v1 clock: `M:SS` under an hour, `H:MM:SS` from one hour. */
    private fun clock(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private suspend fun collect(flow: Flow<TranscriptEvent>): List<TranscriptEvent.Utterance> {
        val out = mutableListOf<TranscriptEvent.Utterance>()
        flow.collect { e -> if (e is TranscriptEvent.Utterance) out += e }
        return out
    }

    @Test fun transcribeDirectory() = runBlocking {
        val dir = env("VOXSUM_AUDIO_DIR")?.let(::File)
        val out = env("VOXSUM_TRANSCRIPT_OUT")?.let(::File)
        assumeTrue("set VOXSUM_AUDIO_DIR", dir?.isDirectory == true)
        assumeTrue("set VOXSUM_TRANSCRIPT_OUT", out != null)
        out!!.mkdirs()

        // Load the natives by ABSOLUTE path, exactly as the app does. The engines' own
        // ensureLib() uses System.loadLibrary, which resolves through java.library.path — unset
        // in a test JVM, so every native method fails with UnsatisfiedLinkError even though the
        // .so exports the symbols. Set VOXSUM_NATIVE_LIB_DIR to desktop/appResources/linux-x64.
        NativeLibs.ensureLoaded()

        val audio = dir!!.listFiles { f -> f.extension.lowercase() in setOf("mp3", "m4a", "wav", "ogg") }
            ?.sortedBy { it.name } ?: emptyList()
        println("[batch] ${audio.size} file(s) in ${dir.absolutePath}")

        val models = ModelManager(appDataDir)
        val threads = env("VOXSUM_ASR_THREADS")?.toIntOrNull() ?: 8
        val config = TranscriptionConfig(
            asrBackend = AsrBackend.XASR.id,
            // Diarization is OFF by default here: these are podcasts, mostly single-host, and v1
            // explicitly permits a line with no speaker field. Speaker labels on a solo show are
            // noise, and the diarization stage roughly doubles the wall clock.
            diarizationEnabled = env("VOXSUM_DIARIZE") == "1",
        )

        // Script conversion, as the app applies it at ASR-emit time. Every ASR model here emits
        // SIMPLIFIED Chinese; the deployed pipeline converts to Traditional for a zh-TW target.
        // Skipping it would put half the dataset in the wrong script — and the existing Android
        // transcripts are Traditional, so it would also make the corpus internally inconsistent.
        val convert: (String) -> String =
            OpenCcConverter.getTranscriptTraditional().let { c -> { t: String -> c.convert(t) } }

        for (f in audio) {
            val dst = File(out, f.nameWithoutExtension + ".txt")
            if (dst.exists()) { println("[batch] ${f.name}: have it"); continue }
            val t0 = System.currentTimeMillis()
            val pcm = try {
                AudioDecoder.decodeToPcm16k(f)
            } catch (t: Throwable) {
                println("[batch] ${f.name}: DECODE FAILED ${t.message}"); continue
            }
            val secs = pcm.size / 16000.0
            val utts = try {
                SpeechEngineFactory.create(AsrBackend.XASR, models, config, threads).use { asr ->
                    val plain = collect(asr.transcribe(pcm))
                    // Diarization is a SEPARATE stage, not something the ASR engine does because
                    // the config flag is set. Passing diarizationEnabled to the factory and
                    // stopping there produced speaker-less output while reporting success — the
                    // flag was decorative. X-ASR is ASR-only; speakers come from
                    // segmentation + CAM++ embeddings + clustering, invoked here.
                    if (config.diarizationEnabled && plain.isNotEmpty()) {
                        DiarizationEngine(
                            embeddingModel = models.embeddingModel.absolutePath,
                            numThreads = threads,
                            numClusters = config.numSpeakers,
                            segmentationModel = models.segmentationModel
                                .takeIf { config.preciseDiarization && it.exists() }?.absolutePath,
                        ).let { d ->
                            try { d.assignSpeakers(pcm16k = pcm, utterances = plain).first }
                            finally { d.close() }
                        }
                    } else plain
                }
            } catch (t: Throwable) {
                println("[batch] ${f.name}: ASR FAILED ${t.message}"); continue
            }
            if (utts.isEmpty()) { println("[batch] ${f.name}: no utterances"); continue }

            // transcript format v1. Speakers by first appearance; no speaker field when the
            // backend did not diarize.
            val order = LinkedHashMap<Int, Int>()
            utts.forEach { u -> u.speaker?.let { order.getOrPut(it) { order.size + 1 } } }
            val text = utts.joinToString("\n") { u ->
                val who = u.speaker?.let { "S${order[it]}" }
                val body = convert(u.text.trim().replace('\n', ' '))
                if (who != null) "[${clock(u.startSec)}] $who: $body" else "[${clock(u.startSec)}] $body"
            } + "\n"
            dst.writeText(text)
            val wall = (System.currentTimeMillis() - t0) / 1000.0
            println("[batch] ${f.name}: ${utts.size} utts, ${secs.toInt() / 60}m${secs.toInt() % 60}s audio, " +
                "%.0fs wall (%.1fx realtime) -> ${dst.name}".format(wall, secs / wall))
        }
        println("[batch] DONE — ${out.listFiles { f -> f.extension == "txt" }?.size ?: 0} transcript(s)")
    }
}
