package studio.voxsum

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.service.TranscriptionService

/**
 * Activity-level UI flow tests: inject real pipeline events into TranscriptionService.events (exactly
 * as the service emits them) and assert the screen reflects each — the transcript list, the always-
 * visible status line (progress / line+speaker count / error). Complements the isolated component tests.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptFlowTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    private fun str(resId: Int, vararg args: Any) = compose.activity.getString(resId, *args)

    /** Ensure the screen has composed (so its event collector is active) before injecting events. */
    private fun awaitReady() {
        compose.onAllNodesWithText(compose.activity.getString(R.string.add_audio)).onFirst().assertIsDisplayed()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun progressEventUpdatesStatusLine() {
        awaitReady()
        TranscriptionService.events.tryEmit(TranscriptEvent.Progress(0.5f))
        val expected = str(R.string.status_transcribing, 50)
        waitForText(expected)
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
    }

    @Test fun completeRendersUtterancesAndLineSpeakerCount() {
        awaitReady()
        TranscriptionService.events.tryEmit(
            TranscriptEvent.Complete(
                utterances = listOf(
                    TranscriptEvent.Utterance(0, "first line here", 0.0, 1.0, speaker = 0),
                    TranscriptEvent.Utterance(1, "second line here", 1.0, 2.0, speaker = 1),
                ),
                speakerCount = 2,
            ),
        )
        waitForText("first line here")
        compose.onNodeWithText("first line here", substring = true).assertIsDisplayed()
        compose.onNodeWithText("second line here", substring = true).assertIsDisplayed()
        // Status reflects the final line + speaker count.
        compose.onNodeWithText(str(R.string.status_transcript_lines_speakers, 2, 2), substring = true).assertIsDisplayed()
    }

    @Test fun failedEventShowsErrorStatus() {
        awaitReady()
        TranscriptionService.events.tryEmit(TranscriptEvent.Failed("disk full"))
        val expected = str(R.string.status_error, "disk full")
        waitForText(expected)
        // The error surfaces in BOTH the status line and a snackbar — assert at least one is shown.
        compose.onAllNodesWithText(expected, substring = true).onFirst().assertIsDisplayed()
    }
}
