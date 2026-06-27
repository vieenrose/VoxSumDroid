package studio.voxsum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.ui.VoxSumTopBar

/**
 * Isolated tests for [VoxSumTopBar]: the conditional action set (blank slate vs running vs recording),
 * the export/re-run overflow menus (enabled state + items firing), settings, status, and progress.
 */
@RunWith(AndroidJUnit4::class)
class VoxSumTopBarTest {

    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Host the top bar with all-default params; override only what a test needs. Captured flags map. */
    private fun bar(
        status: String = "",
        running: Boolean = false,
        progress: Float = 0f,
        transcriptAvailable: Boolean = false,
        showSourceActions: Boolean = true,
        isRecording: Boolean = false,
        recSeconds: Int = 0,
        downloadPending: Boolean = false,
        canReTranscribe: Boolean = false,
        canReSummarize: Boolean = false,
        canReDetect: Boolean = false,
        fired: MutableMap<String, Boolean> = mutableMapOf(),
    ): MutableMap<String, Boolean> {
        compose.setContent {
            VoxSumTopBar(
                downloadPending = downloadPending, status = status, running = running, progress = progress,
                transcriptAvailable = transcriptAvailable, showSourceActions = showSourceActions,
                isRecording = isRecording, recSeconds = recSeconds,
                onAddSource = { fired["add"] = true }, onStop = { fired["stop"] = true },
                canReTranscribe = canReTranscribe, onReTranscribe = { fired["retx"] = true },
                canReSummarize = canReSummarize, onReSummarize = { fired["resum"] = true },
                canReDetect = canReDetect, isDetecting = false, onReDetect = { fired["redet"] = true },
                canExtractActions = false, onExtractActions = { fired["actions"] = true },
                onSearch = { fired["search"] = true },
                onSettings = { fired["settings"] = true },
                onCoverPreview = { fired["cover"] = true }, onSaveSession = { fired["save"] = true },
                onShareSession = { fired["share"] = true },
                onCopyTranscript = { fired["copytx"] = true }, onShareTranscript = { fired["sharetx"] = true },
                onExportTxt = { fired["txt"] = true }, onExportSrt = { fired["srt"] = true },
                onExportVtt = { fired["vtt"] = true }, onExportMarkdown = { fired["md"] = true }, onExportPdf = { fired["pdf"] = true },
            )
        }
        return fired
    }

    @Test fun showsWordmark() {
        bar()
        compose.onNodeWithText(ctx.getString(R.string.app_name)).assertIsDisplayed()
    }

    @Test fun blankSlateHidesSourceActions() {
        bar(showSourceActions = false)
        compose.onNodeWithContentDescription(ctx.getString(R.string.add_audio)).assertDoesNotExist()
    }

    @Test fun idleShowsAddSourceWhichFires() {
        val f = bar(showSourceActions = true, running = false)
        compose.onNodeWithContentDescription(ctx.getString(R.string.add_audio)).performClick()
        assertTrue(f["add"] == true)
    }

    @Test fun runningShowsStopWhichFires() {
        val f = bar(running = true, isRecording = false)
        compose.onNodeWithContentDescription(ctx.getString(R.string.stop)).performClick()
        assertTrue(f["stop"] == true)
    }

    @Test fun recordingShowsTimerAndStop() {
        val f = bar(running = true, isRecording = true, recSeconds = 65)
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithContentDescription(ctx.getString(R.string.cd_stop_recording)).performClick()
        assertTrue(f["stop"] == true)
    }

    @Test fun settingsButtonFires() {
        val f = bar()
        compose.onNodeWithContentDescription(ctx.getString(R.string.cd_settings)).performClick()
        assertTrue(f["settings"] == true)
    }

    @Test fun exportMenuDisabledWithoutTranscript() {
        bar(transcriptAvailable = false)
        compose.onNodeWithContentDescription(ctx.getString(R.string.cd_export)).assertIsNotEnabled()
    }

    @Test fun exportMenuOpensAndSaveFires() {
        val f = bar(transcriptAvailable = true)
        compose.onNodeWithContentDescription(ctx.getString(R.string.cd_export)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.session_save)).performClick()
        assertTrue(f["save"] == true)
    }

    @Test fun exportMenuTranscriptExportsFire() {
        val f = bar(transcriptAvailable = true)
        compose.onNodeWithContentDescription(ctx.getString(R.string.cd_export)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.export_srt)).performClick()
        assertTrue(f["srt"] == true)
    }

    @Test fun exportMenuCopyTranscriptFires() {
        val f = bar(transcriptAvailable = true)
        compose.onNodeWithContentDescription(ctx.getString(R.string.cd_export)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.export_copy_transcript)).performClick()
        assertTrue(f["copytx"] == true)
    }

    @Test fun searchButtonFiresWhenTranscriptAvailable() {
        val f = bar(transcriptAvailable = true)
        compose.onNodeWithContentDescription(ctx.getString(R.string.search_transcript)).performClick()
        assertTrue(f["search"] == true)
    }

    @Test fun reRunMenuHiddenWhenNothingApplies() {
        bar(canReTranscribe = false, canReSummarize = false, canReDetect = false)
        compose.onNodeWithContentDescription(ctx.getString(R.string.re_run)).assertDoesNotExist()
    }

    @Test fun reRunMenuOpensAndReSummarizeFires() {
        val f = bar(canReSummarize = true)
        compose.onNodeWithContentDescription(ctx.getString(R.string.re_run)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.re_summarize)).performClick()
        assertTrue(f["resum"] == true)
    }

    @Test fun statusAndProgressShownWhileRunning() {
        bar(status = "Decoding…", running = true, progress = 0.5f)
        compose.onNodeWithText("Decoding…").assertIsDisplayed()
    }
}
