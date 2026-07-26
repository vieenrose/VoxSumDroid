# Keep JNI entry points reachable from native code.
# (studio.voxsum.core.llm.LlmEngine and the sherpa-onnx API are gone since the LiteRT migration;
#  their rules were removed. The MOSS/X-ASR/Nemotron JNI binds by class+method name — those classes
#  are referenced from Kotlin so R8 keeps them, and the release dex is checked in CI.)
-keep class studio.voxsum.core.llm.TextGen$TokenCallback { *; }

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

# LiteRT-LM: its NATIVE code reads the Kotlin config objects back through JNI. Verified on-device:
# nativeCreateConversation does CallIntMethodV on SamplerConfig.getTopK(), so R8 renaming the
# getters makes GetMethodID return null and ART aborts the process ("JNI DETECTED ERROR IN
# APPLICATION: mid == null") the moment a conversation is created — i.e. on EVERY summarize/title
# in a minified build, while debug builds are fine. Keep the whole surface: the JNI reads several
# of these types by name and there is no upstream consumer rule shipped with the AAR.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# Nicer crash traces in release.
-keepattributes SourceFile,LineNumberTable
