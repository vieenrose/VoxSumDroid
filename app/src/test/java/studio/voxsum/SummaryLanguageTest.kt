package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.config.SummaryLanguage
import java.util.Locale

/**
 * Pins the summary-language routing table: id round-trips, the prompt clause each language injects,
 * the OpenCC s2tw gate (Traditional only), and the locale-based default ("summarize in your language").
 * Pure JVM — no device, no models. The on-device matrix test then checks the models actually obey.
 */
class SummaryLanguageTest {

    @Test fun fromIdRoundTripsEveryEntry() {
        SummaryLanguage.entries.forEach { lang ->
            assertEquals(lang, SummaryLanguage.fromId(lang.id))
        }
    }

    @Test fun fromIdFallsBackToAuto() {
        assertEquals(SummaryLanguage.AUTO, SummaryLanguage.fromId(null))
        assertEquals(SummaryLanguage.AUTO, SummaryLanguage.fromId(""))
        assertEquals(SummaryLanguage.AUTO, SummaryLanguage.fromId("gemma-4-e2b-it-qat")) // a stale/unknown id
    }

    @Test fun autoMatchesTranscriptAndConvertsNothing() {
        assertNull("AUTO must not force a language", SummaryLanguage.AUTO.promptName)
        assertFalse(SummaryLanguage.AUTO.convertsToTraditional)
    }

    @Test fun onlyTraditionalTriggersOpenCc() {
        SummaryLanguage.entries.forEach { lang ->
            assertEquals(
                "OpenCC s2tw must fire only for Traditional Chinese, not ${lang.id}",
                lang == SummaryLanguage.TRADITIONAL,
                lang.convertsToTraditional,
            )
        }
    }

    @Test fun everyConcreteLanguageInjectsAPromptName() {
        SummaryLanguage.entries.filter { it != SummaryLanguage.AUTO }.forEach { lang ->
            assertTrue("${lang.id} must inject a prompt language name", !lang.promptName.isNullOrBlank())
        }
        // Spot-check the script-sensitive Chinese variants spell out the script the model should use.
        assertTrue(SummaryLanguage.TRADITIONAL.promptName!!.contains("Traditional"))
        assertTrue(SummaryLanguage.SIMPLIFIED.promptName!!.contains("Simplified"))
    }

    @Test fun localeDefaultPicksTheUsersLanguage() {
        assertEquals(SummaryLanguage.TRADITIONAL, SummaryLanguage.defaultFor(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals(SummaryLanguage.TRADITIONAL, SummaryLanguage.defaultFor(Locale.forLanguageTag("zh-TW")))
        assertEquals(SummaryLanguage.TRADITIONAL, SummaryLanguage.defaultFor(Locale.forLanguageTag("zh-HK")))
        assertEquals(SummaryLanguage.SIMPLIFIED, SummaryLanguage.defaultFor(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals(SummaryLanguage.SIMPLIFIED, SummaryLanguage.defaultFor(Locale.forLanguageTag("zh-CN")))
        assertEquals(SummaryLanguage.ENGLISH, SummaryLanguage.defaultFor(Locale.forLanguageTag("en-US")))
        assertEquals(SummaryLanguage.FRENCH, SummaryLanguage.defaultFor(Locale.forLanguageTag("fr-FR")))
        assertEquals(SummaryLanguage.JAPANESE, SummaryLanguage.defaultFor(Locale.forLanguageTag("ja-JP")))
        assertEquals(SummaryLanguage.KOREAN, SummaryLanguage.defaultFor(Locale.forLanguageTag("ko-KR")))
        // A language we don't offer falls back to matching the transcript.
        assertEquals(SummaryLanguage.AUTO, SummaryLanguage.defaultFor(Locale.forLanguageTag("de-DE")))
    }
}
