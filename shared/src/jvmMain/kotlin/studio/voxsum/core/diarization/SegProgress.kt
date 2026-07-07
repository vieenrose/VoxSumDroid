package studio.voxsum.core.diarization

/**
 * JNI-compatible progress callback for OfflineSpeakerDiarization.processWithCallback.
 *
 * The sherpa JNI (offline-speaker-diarization.cc) resolves the callback method by the EXACT
 * signature `invoke(IIJ)Ljava/lang/Integer;` — primitive parameters, BOXED return. A Kotlin
 * lambda's erased `Function3.invoke(Object,Object,Object)` doesn't match, so passing one makes
 * every chunk log "Failed to get the callback" and the call return with a pending
 * NoSuchMethodError. This class emits precisely that method: primitive params stay primitive
 * and the `Int?` return compiles to java.lang.Integer.
 *
 * (The Android port has a Java twin of this class — upstream's kotlin-api there types the
 * callback parameter as a function, which only a Java class can satisfy while also exposing
 * the specialized method. Keep the two in sync.)
 */
class SegProgress(private val onChunk: (processed: Int, total: Int) -> Unit) {
    @Suppress("unused") // called from JNI only
    fun invoke(processed: Int, total: Int, arg: Long): Int? {
        onChunk(processed, total)
        return 0
    }
}
