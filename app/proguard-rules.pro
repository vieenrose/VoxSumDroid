# Keep JNI entry points reachable from native code.
-keep class studio.voxsum.core.llm.LlmEngine { *; }
# The native token-streaming callback looks up TokenCallback.onToken by name via JNI GetMethodID.
# Without this keep, R8 renames onToken in release → GetMethodID returns null → JNI abort (SIGABRT)
# the moment summarization starts. The nested interface is a SEPARATE class, so the LlmEngine keep
# above does NOT cover it.
-keep class studio.voxsum.core.llm.LlmEngine$TokenCallback { *; }
# sherpa-onnx Kotlin API is called from its own JNI by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

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

# Nicer crash traces in release.
-keepattributes SourceFile,LineNumberTable
