# Extra rules for `-PminifyDebug`: run the app THROUGH R8 (to expose JNI-by-name breakage) while
# keeping our own classes intact, because the instrumented tests reference them by their real names
# and would otherwise fail to resolve. Third-party code — notably com.google.ai.edge.litertlm — is
# still minified, which is exactly the surface being verified.
-keep class studio.voxsum.** { *; }

# The androidTest APK does not bundle the Kotlin stdlib — it links against the app's copy. Under
# R8 the unused parts are gone, so the runner dies with ClassNotFoundException (kotlin.LazyKt)
# before any test runs. Keep it for these verification builds only.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
