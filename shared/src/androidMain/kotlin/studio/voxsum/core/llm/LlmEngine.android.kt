package studio.voxsum.core.llm

internal actual fun loadVoxsumLlmLibrary() {
    System.loadLibrary("voxsum-llm")
}
