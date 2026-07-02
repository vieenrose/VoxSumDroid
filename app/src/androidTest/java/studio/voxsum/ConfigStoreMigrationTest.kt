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
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.displayLocale
import studio.voxsum.core.prefs.AndroidKeyValueStore
import studio.voxsum.core.prefs.KeyValueStore

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

    @Before fun clear() {
        KeyValueStore.forName = { name -> AndroidKeyValueStore(ctx, name) }
        prefs().edit().clear().commit()
    }
    @After fun cleanup() { prefs().edit().clear().commit() }

    @Test fun legacyTraditionalChineseTrueMigratesToZhHant() {
        prefs().edit().putBoolean("traditionalChinese", true).commit()
        assertEquals("zh-Hant", ConfigStore.load(ctx.displayLocale()).targetLanguage)
    }

    @Test fun legacyTraditionalChineseFalseMigratesToAuto() {
        prefs().edit().putBoolean("traditionalChinese", false).commit()
        assertEquals("auto", ConfigStore.load(ctx.displayLocale()).targetLanguage)
    }

    @Test fun savedTargetLanguageWinsOverLegacyBoolean() {
        prefs().edit().putString("summaryLanguage", "ja").putBoolean("traditionalChinese", true).commit()
        assertEquals("ja", ConfigStore.load(ctx.displayLocale()).targetLanguage)
    }

    @Test fun freshInstallDefaultsToDeviceLanguage() {
        // No summaryLanguage and no legacy boolean → the user's display-language default.
        assertEquals(TargetLanguage.defaultFor(ctx.displayLocale()).id, ConfigStore.load(ctx.displayLocale()).targetLanguage)
    }
}
