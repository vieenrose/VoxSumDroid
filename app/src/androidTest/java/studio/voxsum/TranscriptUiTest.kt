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
 * Headless Compose UI test for the transcript screen — verifies the rendering path
 * (pipeline events -> incremental transcript list) WITHOUT touching the system SAF picker.
 * Events are injected straight into the service's event bus, exactly as the real pipeline
 * emits them, and we assert the UI reflects them.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptUiTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    // Grant POST_NOTIFICATIONS before the activity launches so no system dialog covers the UI.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    @Test
    fun rendersUtterancesAsEventsArrive() {
        // Initial state — a single "Add audio" CTA opens the source chooser.
        compose.onAllNodesWithText("Add audio").onFirst().assertIsDisplayed()

        // Simulate the pipeline emitting a status then two utterances.
        TranscriptionService.events.tryEmit(TranscriptEvent.Status("Transcribing…"))
        TranscriptionService.events.tryEmit(
            TranscriptEvent.Utterance(0, "hello world", 0.0, 1.0)
        )
        TranscriptionService.events.tryEmit(
            TranscriptEvent.Utterance(1, "second line", 1.0, 2.0)
        )

        // The list updates incrementally; wait for the rendered text.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("hello world", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("hello world", substring = true).assertIsDisplayed()
        compose.onNodeWithText("second line", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Transcribing…", substring = true).assertIsDisplayed()
    }
}
