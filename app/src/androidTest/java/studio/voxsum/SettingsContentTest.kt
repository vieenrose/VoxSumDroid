package studio.voxsum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
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
import studio.voxsum.core.config.TargetLanguage
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
        compose.onNodeWithText(AsrBackend.SENSEVOICE.shortName).performScrollTo().performClick()
        assertEquals(AsrBackend.SENSEVOICE.id, changed?.asrBackend)
    }

    @Test fun selectingLlmReportsIt() {
        var changed: TranscriptionConfig? = null
        host(baseCfg.copy(llmModelId = LlmRegistry.ALL[0].id), onChange = { changed = it })
        compose.onNodeWithText(LlmRegistry.ALL[1].displayName).performScrollTo().performClick()
        assertEquals(LlmRegistry.ALL[1].id, changed?.llmModelId)
    }

    @Test fun togglingDiarizationReportsIt() {
        var changed: TranscriptionConfig? = null
        host(baseCfg.copy(diarizationEnabled = true), onChange = { changed = it })
        // x-asr backend → the diarization Switch is the only toggleable node.
        compose.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
        assertEquals(false, changed?.diarizationEnabled)
    }

    @Test fun selectingTargetLanguageReportsIt() {
        var changed: TranscriptionConfig? = null
        host(onChange = { changed = it })
        compose.onNodeWithText(TargetLanguage.ENGLISH.autonym).performScrollTo().performClick()
        assertEquals(TargetLanguage.ENGLISH.id, changed?.targetLanguage)
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
        compose.onNodeWithText(AsrBackend.SENSEVOICE.shortName).assertIsNotEnabled()
    }

    @Test fun aboutSectionShowsTheAppVersion() {
        host()
        compose.onNodeWithText("VoxSum v${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
    }
}
