package studio.voxsum.desktop

/** Open-source components bundled or used by the desktop app, with their licenses. Library names
 *  and SPDX identifiers are language-neutral, so this is one shared string (not localized). */
object Licenses {
    val COMPONENTS: String = listOf(
        "Compose Multiplatform / Kotlin — Apache-2.0",
        "sherpa-onnx (ASR & diarization) — Apache-2.0",
        "ONNX Runtime — MIT",
        "llama.cpp / ggml (summarization) — MIT",
        "Silero VAD — MIT",
        "OpenCC (Chinese conversion data) — Apache-2.0",
        "Apache PDFBox (PDF export) — Apache-2.0",
        "NewPipeExtractor (YouTube) — GPL-3.0",
    ).joinToString("\n") { "• $it" }
}
