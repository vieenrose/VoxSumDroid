package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.voxsum.core.asr.AsrEngine

/**
 * Pure-JVM tests for [AsrEngine.cleanTranscript], the zh-en decode-output normalizer that shapes every
 * transcript line (strip U+FFFD, collapse a CJK stutter, drop spaces between Chinese characters, tighten
 * CJK/ASCII punctuation while preserving English word spacing). The on-device AsrEngineTest proves the
 * native decode path; this pins the text cleanup.
 */
class AsrCleanTranscriptTest {

    @Test fun stripsReplacementChar() {
        assertEquals("ab", AsrEngine.cleanTranscript("a�b"))
    }

    @Test fun collapsesCjkStutterOfThreeOrMore() {
        assertEquals("好", AsrEngine.cleanTranscript("好好好好"))   // 4 → 1
        assertEquals("你好", AsrEngine.cleanTranscript("你好好好"))  // trailing 3 → 1
        // A double is normal emphasis, not a stutter — left intact.
        assertEquals("好好", AsrEngine.cleanTranscript("好好"))
    }

    @Test fun dropsSpacesBetweenChineseCharacters() {
        assertEquals("第二种", AsrEngine.cleanTranscript("第二 种"))
    }

    @Test fun tightensPunctuationSpacingForTheXAsrCase() {
        // The documented raw x-asr output → fully tightened Chinese line.
        assertEquals("礼拜二，第二种", AsrEngine.cleanTranscript("礼拜二 ， 第二种"))
    }

    @Test fun preservesEnglishWordSpacing() {
        assertEquals("today is good", AsrEngine.cleanTranscript("today is good"))
    }

    @Test fun emptyStringIsNoop() {
        assertEquals("", AsrEngine.cleanTranscript(""))
    }
}
