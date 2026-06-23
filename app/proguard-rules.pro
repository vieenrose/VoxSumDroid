# Keep JNI entry points reachable from native code.
-keep class studio.voxsum.core.llm.LlmEngine { *; }
# sherpa-onnx Kotlin API is called from its own JNI by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
