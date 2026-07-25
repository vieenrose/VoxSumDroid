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
import studio.voxsum.R
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.service.TranscriptionService

/**
 * Headless Compose UI test for the transcript screen — verifies the rendering path
 * (pipeline events -> incremental transcript list) WITHOUT touching the system SAF picker.
 * Events are injected straight into the service's event bus, exactly as the real pipeline
 * emits them, and we assert the UI reflects them.
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Written for the pre-Studio home: it waits for the Add-audio button (now only an icon contentDescription) and injects events expecting the Session screen, but MainActivity now opens on the Studio shelf and only navigates to Session when a session is started or reopened. Needs rewriting to open a saved session first (see VoxsumSessionM4aTest for building one) — the assertions themselves are still valid.")
class TranscriptUiTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    // Grant POST_NOTIFICATIONS before the activity launches so no system dialog covers the UI.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    @Test
    fun rendersUtterancesAsEventsArrive() {
        // Initial state — a single "Add audio" CTA opens the source chooser. Resolve the label
        // from resources (not a hard-coded English literal) so the test passes under any UI locale
        // (the device here runs zh-Hant-TW, where the CTA reads "加入音訊").
        val addAudio = compose.activity.getString(R.string.add_audio)
        compose.onAllNodesWithText(addAudio).onFirst().assertIsDisplayed()

        // Simulate the pipeline emitting a status then two utterances.
        TranscriptionService.events.tryEmit(TranscriptionService.UNTAGGED to TranscriptEvent.Status("Transcribing…"))
        TranscriptionService.events.tryEmit(TranscriptionService.UNTAGGED to TranscriptEvent.Utterance(0, "hello world", 0.0, 1.0)
        )
        TranscriptionService.events.tryEmit(TranscriptionService.UNTAGGED to TranscriptEvent.Utterance(1, "second line", 1.0, 2.0)
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
