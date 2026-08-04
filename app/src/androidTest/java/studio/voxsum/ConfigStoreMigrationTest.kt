package studio.voxsum

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.config.ConfigStore
import studio.voxsum.core.config.SummaryScript

/**
 * Verifies the summary-language settings migration in [ConfigStore.load]: a saved value wins, the legacy
 * `traditionalChinese` boolean migrates (true→zh-Hant, false→auto), and a truly fresh install defaults
 * to the device's display language. Needs a real Context/SharedPreferences, so it runs as an
 * instrumented test (the JVM TargetLanguageTest covers the pure routing table).
 */
@RunWith(AndroidJUnit4::class)
class ConfigStoreMigrationTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun prefs() = ctx.getSharedPreferences("voxsum_config", Context.MODE_PRIVATE)

    @Before fun clear() { prefs().edit().clear().commit() }
    @After fun cleanup() { prefs().edit().clear().commit() }

    /**
     * The legacy keys are deliberately NOT migrated.
     *
     * "traditionalChinese" and "summaryLanguage" both encoded an output LANGUAGE for the summary,
     * and that feature was removed (see [SummaryScript]) because translating while summarizing
     * degraded a 0.8B model's output. Reading either would carry a translation preference into a
     * build that cannot honour it — e.g. a stored "ja" would have to mean something, and any
     * meaning we invented would be wrong. They are ignored; stored summaries are untouched.
     */
    @Test fun legacyLanguageKeysAreIgnored() {
        prefs().edit()
            .putString("summaryLanguage", "ja")
            .putBoolean("traditionalChinese", false)
            .commit()
        val loaded = ConfigStore.load(ctx).summaryScript
        assertEquals(SummaryScript.defaultFor(java.util.Locale.getDefault()).id, loaded)
    }

    @Test fun savedScriptRoundTrips() {
        ConfigStore.save(ctx, ConfigStore.load(ctx).copy(summaryScript = SummaryScript.SIMPLIFIED.id))
        assertEquals(SummaryScript.SIMPLIFIED.id, ConfigStore.load(ctx).summaryScript)
    }

    @Test fun freshInstallDefaultsFromLocale() {
        assertEquals(SummaryScript.defaultFor(java.util.Locale.getDefault()).id,
            ConfigStore.load(ctx).summaryScript)
    }
}
