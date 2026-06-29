package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.config.TargetLanguage
import java.util.Locale

/**
 * Pins the summary-language routing table: id round-trips, the prompt clause each language injects,
 * the OpenCC s2tw gate (Traditional only), and the locale-based default ("summarize in your language").
 * Pure JVM — no device, no models. The on-device matrix test then checks the models actually obey.
 */
class TargetLanguageTest {

    @Test fun fromIdRoundTripsEveryEntry() {
        TargetLanguage.entries.forEach { lang ->
            assertEquals(lang, TargetLanguage.fromId(lang.id))
        }
    }

    @Test fun fromIdFallsBackToAuto() {
        assertEquals(TargetLanguage.AUTO, TargetLanguage.fromId(null))
        assertEquals(TargetLanguage.AUTO, TargetLanguage.fromId(""))
        assertEquals(TargetLanguage.AUTO, TargetLanguage.fromId("gemma-4-e2b-it-qat")) // a stale/unknown id
    }

    @Test fun autoMatchesTranscriptAndConvertsNothing() {
        assertNull("AUTO must not force a language", TargetLanguage.AUTO.promptName)
        assertFalse(TargetLanguage.AUTO.convertsToTraditional)
    }

    @Test fun onlyTraditionalTriggersOpenCc() {
        TargetLanguage.entries.forEach { lang ->
            assertEquals(
                "OpenCC s2tw must fire only for Traditional Chinese, not ${lang.id}",
                lang == TargetLanguage.TRADITIONAL,
                lang.convertsToTraditional,
            )
        }
    }

    @Test fun everyConcreteLanguageInjectsAPromptName() {
        TargetLanguage.entries.filter { it != TargetLanguage.AUTO }.forEach { lang ->
            assertTrue("${lang.id} must inject a prompt language name", !lang.promptName.isNullOrBlank())
        }
        // Spot-check the script-sensitive Chinese variants spell out the script the model should use.
        assertTrue(TargetLanguage.TRADITIONAL.promptName!!.contains("Traditional"))
        assertTrue(TargetLanguage.SIMPLIFIED.promptName!!.contains("Simplified"))
    }

    @Test fun localeDefaultPicksTheUsersLanguage() {
        assertEquals(TargetLanguage.TRADITIONAL, TargetLanguage.defaultFor(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals(TargetLanguage.TRADITIONAL, TargetLanguage.defaultFor(Locale.forLanguageTag("zh-TW")))
        assertEquals(TargetLanguage.TRADITIONAL, TargetLanguage.defaultFor(Locale.forLanguageTag("zh-HK")))
        assertEquals(TargetLanguage.SIMPLIFIED, TargetLanguage.defaultFor(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals(TargetLanguage.SIMPLIFIED, TargetLanguage.defaultFor(Locale.forLanguageTag("zh-CN")))
        assertEquals(TargetLanguage.ENGLISH, TargetLanguage.defaultFor(Locale.forLanguageTag("en-US")))
        assertEquals(TargetLanguage.FRENCH, TargetLanguage.defaultFor(Locale.forLanguageTag("fr-FR")))
        assertEquals(TargetLanguage.JAPANESE, TargetLanguage.defaultFor(Locale.forLanguageTag("ja-JP")))
        assertEquals(TargetLanguage.KOREAN, TargetLanguage.defaultFor(Locale.forLanguageTag("ko-KR")))
        // A language we don't offer falls back to matching the transcript.
        assertEquals(TargetLanguage.AUTO, TargetLanguage.defaultFor(Locale.forLanguageTag("de-DE")))
    }
}
