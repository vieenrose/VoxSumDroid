package studio.voxsum.core.config

import android.content.Context
import java.util.Locale

/** The device's current display locale — respects Android 13+ per-app language, unlike
 *  [Locale.getDefault] alone. Feeds the shared (platform-agnostic) TargetLanguage/ConfigStore
 *  locale defaulting. */
fun Context.displayLocale(): Locale = resources.configuration.locales[0] ?: Locale.getDefault()
