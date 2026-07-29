package studio.voxsum.desktop

import java.io.File
import kotlinx.coroutines.runBlocking
import studio.voxsum.core.config.TranscriptionConfig

/**
 * Headless full-pipeline transcription, for re-validating each ASR backend with
 * the byte-identical code path the app ships:
 *
 *   VoxSum --bench <audio> <backendId> [zhTarget]
 *
 * Transcript to stdout (one utterance per line, speaker-tagged when diarized);
 * wall time and peak RSS to stderr. Exit 0 only if utterances were produced.
 */
fun runBenchCli(args: Array<String>) {
    val file = File(args[1])
    require(file.exists()) { "no such audio: $file" }
    val backendId = args.getOrNull(2) ?: "x-asr"
    val config = TranscriptionConfig(asrBackend = backendId)

    NativeLibs.ensureLoaded()
    val t0 = System.nanoTime()
    val utterances = runBlocking { benchTranscribe(file, config) }
    val wall = (System.nanoTime() - t0) / 1e9

    // BENCH_DUMP_FORMAT: also write the unified summarizer-input format, so the
    // exact text the LLM would receive can be validated against real models.
    System.getenv("BENCH_DUMP_FORMAT")?.let { path ->
        java.io.File(path).writeText(studio.voxsum.core.llm.TranscriptFormat.format(utterances))
    }
    for (u in utterances) {
        val tag = u.speaker?.let { "[S$it] " } ?: ""
        println("$tag${u.text}")
    }
    val peakKb = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLongOrNull() ?: 0
    System.err.println("BENCH backend=$backendId wall=%.1fs utterances=%d peakRSS=%dMB"
        .format(wall, utterances.size, peakKb / 1024))
    kotlin.system.exitProcess(if (utterances.isEmpty()) 2 else 0)
}
