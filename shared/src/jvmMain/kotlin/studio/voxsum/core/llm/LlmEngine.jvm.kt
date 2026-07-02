package studio.voxsum.core.llm

// No-op: studio.voxsum.desktop.NativeLibs already System.load()s libvoxsum-llm.so by absolute
// path before LlmEngine is ever touched (see NativeLibs' own doc comment for why
// System.loadLibrary can't be relied on for a packaged desktop app).
internal actual fun loadVoxsumLlmLibrary() {}
