# Keep JNI entry points reachable from native code.
# (The MOSS/X-ASR/Nemotron JNI binds by class+method name — those classes are referenced from
#  Kotlin so R8 keeps them, and the release dex is checked in CI.)
-keep class studio.voxsum.core.llm.TextGen$TokenCallback { *; }

# llama.cpp bridge: llm_jni.cpp exports Java_studio_voxsum_core_llm_LlmEngine_native*, so the
# class name, its package and the native method names must all survive R8 — otherwise the symbols
# stop matching and every load/generate throws UnsatisfiedLinkError in RELEASE builds only, which
# is exactly the class of failure -PminifyDebug exists to catch. The bridge also calls back into
# TextGen$TokenCallback.onToken by name (kept above).
-keep class studio.voxsum.core.llm.LlmEngine { *; }
-keepclasseswithmembernames class studio.voxsum.core.llm.LlmEngine {
    native <methods>;
}

# Same JNI-reflection story as TokenCallback: offline-speaker-diarization.cc resolves the
# diarization progress callback by EXACT name+signature — GetMethodID("invoke",
# "(IIJ)Ljava/lang/Integer;") on the object passed to processWithCallback. The specialized
# invoke has no Java-side callers, so R8 stripped it in release → pending NoSuchMethodError
# inside JNI → native SIGABRT the moment precise diarization started (0.18.3/0.18.4 on-device).
-keep class studio.voxsum.core.diarization.SegProgress { *; }

# NewPipeExtractor (YouTube) bundles Mozilla Rhino, which references desktop-JVM classes
# (java.beans.*, javax.*) that don't exist on Android. R8 fails the release build on these
# missing references unless told to ignore them. Rhino + the extractor also use reflection,
# so keep them intact.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn javax.servlet.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**

# (The com.google.ai.edge.litertlm keep rules went with the LiteRT-LM summarizer — the AAR is no
#  longer a dependency. llama.cpp's JNI reads no Kotlin objects reflectively: the sampler settings
#  cross the boundary as primitives, so there is no equivalent of the SamplerConfig.getTopK abort.)

# Nicer crash traces in release.
-keepattributes SourceFile,LineNumberTable
