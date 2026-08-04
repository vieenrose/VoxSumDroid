package studio.voxsum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.SummaryScript
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.ui.SettingsContent

/**
 * Isolated tests for [SettingsContent]: every control reports via onChange(config.copy(...)), so we
 * host it (in a scroll container, since it overflows the viewport) with a config + spy and assert the
 * resulting config — proving the settings wiring that feeds ConfigStore persistence. Uses the x-asr
 * backend so the SenseVoice-only language chips/ITN switch are hidden, leaving each control unambiguous.
 */
@RunWith(AndroidJUnit4::class)
class SettingsContentTest {

    @get:Rule val compose = createComposeRule()

    private val baseCfg = TranscriptionConfig(asrBackend = AsrBackend.XASR.id)

    private fun host(cfg: TranscriptionConfig = baseCfg, enabled: Boolean = true, onChange: (TranscriptionConfig) -> Unit = {}) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsContent(cfg, readyAsr = setOf(cfg.asrBackend), readyLlm = setOf(cfg.llmModelId), enabled = enabled, onChange = onChange)
            }
        }
    }

    @Test fun rendersAllAsrAndLlmModelCards() {
        host()
        AsrBackend.entries.forEach { compose.onNodeWithText(it.shortName).assertExists() }
        LlmRegistry.ALL.forEach { compose.onNodeWithText(it.displayName).assertExists() }
    }

    @Test fun selectingAsrBackendReportsIt() {
        var changed: TranscriptionConfig? = null
        host(onChange = { changed = it })
        compose.onNodeWithText(AsrBackend.XASR.shortName).performScrollTo().performClick()
        assertEquals(AsrBackend.XASR.id, changed?.asrBackend)
    }

    /** There is exactly ONE summarizer now (Qwen3.5-0.8B replaced Gemma 4 E2B/E4B and the
     *  TQ3 engine), so "selecting a different model" is no longer expressible. Assert instead
     *  that clicking the single card reports that model — which also guards the case where a
     *  stored id from a removed model must normalize to DEFAULT_ID. */
    @Test fun selectingLlmReportsIt() {
        var changed: TranscriptionConfig? = null
        host(baseCfg.copy(llmModelId = "gemma-4-e2b-litertlm"), onChange = { changed = it })
        compose.onNodeWithText(LlmRegistry.ALL[0].displayName).performScrollTo().performClick()
        assertEquals(LlmRegistry.ALL[0].id, changed?.llmModelId)
    }

    @Test fun togglingDiarizationReportsIt() {
        var changed: TranscriptionConfig? = null
        host(baseCfg.copy(diarizationEnabled = true), onChange = { changed = it })
        // Settings now has several toggleables (hardware chips, the precise-diarization switch),
        // so anchor on the row's own label instead of assuming a single one.
        val label = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.settings_identify_speakers)
        // Each switch carries its row's label as a contentDescription. Sibling matching cannot work
        // here: a bare Row contributes no semantics node, so every switch is a sibling of every
        // label — and merging the Row does not help, a Switch is itself a merging node.
        compose.onNode(isToggleable() and hasContentDescription(label))
            .performScrollTo().assertIsOn().performClick()
        assertEquals(false, changed?.diarizationEnabled)
    }

    @Test fun selectingChineseScriptReportsIt() {
        var changed: TranscriptionConfig? = null
        host(onChange = { changed = it })
        compose.onNodeWithText(SummaryScript.SIMPLIFIED.autonym).performScrollTo().performClick()
        assertEquals(SummaryScript.SIMPLIFIED.id, changed?.summaryScript)
    }

    @Test fun editingSummaryPromptReportsIt() {
        var changed: TranscriptionConfig? = null
        host(baseCfg.copy(summaryPrompt = "Summarize."), onChange = { changed = it })
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("X")
        assertNotNull("prompt edit should report a change", changed)
        assertNotEquals("Summarize.", changed?.summaryPrompt)
    }

    @Test fun disabledStateDisablesTheModelCards() {
        host(enabled = false)
        compose.onNodeWithText(AsrBackend.XASR.shortName).assertIsNotEnabled()
    }

    @Test fun aboutSectionShowsTheAppVersion() {
        host()
        compose.onNodeWithText("VoxSum v${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
    }
}
