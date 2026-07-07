package studio.voxsum.core.diarization;

import kotlin.jvm.functions.Function3;

/**
 * JNI-compatible progress callback for OfflineSpeakerDiarization.processWithCallback.
 *
 * The sherpa JNI (offline-speaker-diarization.cc) resolves the callback method by the EXACT
 * signature {@code invoke(IIJ)Ljava/lang/Integer;} — primitive parameters, BOXED return. A
 * Kotlin lambda's erased {@code Function3.invoke(Object,Object,Object)} doesn't match, so
 * passing one makes every chunk log "Failed to get the callback" and the call return with a
 * pending NoSuchMethodError. Only Java can declare that exact method NEXT TO the boxed
 * Function3 override (Kotlin forbids the same-name/same-params overload), and upstream's
 * kotlin-api (a submodule — not patchable) types the parameter as a function, so this shim
 * must also implement Function3.
 *
 * (The desktop port has a Kotlin twin — its vendored kotlin-api takes Any, so no Function3
 * is needed there. Keep the two in sync.)
 */
public final class SegProgress implements Function3<Integer, Integer, Long, Integer> {

    /** Receives (processedChunks, totalChunks) per native chunk. */
    public interface Chunk {
        void on(int processed, int total);
    }

    private final Chunk chunk;

    public SegProgress(Chunk chunk) {
        this.chunk = chunk;
    }

    /** JNI entry point — resolved by exact signature (IIJ)Ljava/lang/Integer;. */
    public Integer invoke(int processed, int total, long arg) {
        chunk.on(processed, total);
        return 0;
    }

    @Override
    public Integer invoke(Integer processed, Integer total, Long arg) {
        return invoke(processed.intValue(), total.intValue(), arg.longValue());
    }
}
