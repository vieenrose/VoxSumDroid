package studio.voxsum.desktop

import java.io.File

// Loads the native libraries the Kotlin sherpa-onnx/llama.cpp wrappers need, before any code
// touches them. Deliberately NOT via System.loadLibrary("...") -- that resolves the name through
// java.library.path, which is only known at packaging/install time (the resources dir Compose
// Desktop hands us at runtime, via compose.application.resources.dir -- see resolveLibDir), and
// java.library.path can't be changed after the JVM's first native-load call without JDK-internal
// reflection that's blocked by the module system on modern JDKs (confirmed empirically: JDK 21's
// ClassLoader no longer exposes a sys_paths field to patch). So instead: System.load() the two
// leaf libraries by absolute path here; their own $ORIGIN-relative RPATH pulls in the rest
// (libggml family, libllama, libonnxruntime -- see desktop/scripts/flatten-native-libs.sh). The
// vendored sherpa-onnx wrapper classes in shared/jvmMain/kotlin/com/k2fsa/sherpa/onnx have had
// their own System.loadLibrary calls removed for the same reason -- once loaded into the process
// by any means, their native symbols resolve for every JNI caller regardless of which Kotlin
// class references them.
object NativeLibs {
    @Volatile private var loaded = false

    /** True once libvoxsum-mosslite.so is in the process — gates the LiteRT backends. */
    @Volatile private var liteRtLoaded = false

    /** Whether the LiteRT engines (Nemotron / X-ASR / MOSS-TD) are usable in this run. */
    fun liteRtAvailable(): Boolean { ensureLoaded(); return liteRtLoaded }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        val dir = resolveLibDir()
        checkNotNull(dir) {
            "Native libraries not found. Run desktop/scripts/build-native.sh then " +
                "desktop/scripts/flatten-native-libs.sh, or set VOXSUM_NATIVE_LIB_DIR."
        }
        System.load(File(dir, "libvoxsum-llm.so").absolutePath)
        System.load(File(dir, "libsherpa-onnx-jni.so").absolutePath)
        // LiteRT engines (Nemotron / X-ASR / MOSS-TD + VAD/diarization pods), the
        // same app/src/main/cpp/mosslite sources Android builds. Optional while the
        // desktop still ships the sherpa backends: absent .so = those backends stay
        // unavailable rather than the whole app failing to start.
        File(dir, "libvoxsum-mosslite.so").takeIf(File::exists)?.let {
            runCatching { System.load(it.absolutePath) }
                .onFailure { e -> System.err.println("LiteRT engines unavailable: ${e.message}") }
                .onSuccess { liteRtLoaded = true }
        }
        loaded = true
    }

    /** The staged native-resources dir (appResources/linux-x64), where flatten-native-libs.sh puts
     *  the .so's AND build-moss.sh stages the moss-td-test / rs-speaker-embed executables. Public so
     *  the MOSS subprocess backend can locate its binaries the same way. */
    fun libDir(): File? = resolveLibDir()

    /** Dev override, then the packaged/dev-run resources dir Compose Desktop always sets -- the
     *  appResources/linux-x64 source files land flat at its root, not in a linux-x64 subdir. */
    private fun resolveLibDir(): File? {
        System.getenv("VOXSUM_NATIVE_LIB_DIR")?.let { return File(it) }
        val resourcesDir = System.getProperty("compose.application.resources.dir") ?: return null
        val candidate = File(resourcesDir)
        return if (File(candidate, "libvoxsum-llm.so").exists()) candidate else null
    }
}
