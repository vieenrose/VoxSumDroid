package studio.voxsum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent

/**
 * Headless Compose UI test for the transcript screen — verifies the INCREMENTAL rendering path
 * (per-utterance events append to the list without a full rebuild) WITHOUT touching the system SAF
 * picker. Events are injected straight into the service's event bus, exactly as the real pipeline
 * emits them, and we assert the UI reflects them.
 *
 * The screen is reached by opening a seeded session; see [SessionFixture].
 */
@RunWith(AndroidJUnit4::class)
class TranscriptUiTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    private val seed = object : ExternalResource() {
        override fun before() { SessionFixture.clear(); SessionFixture.seed() }
    }

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(SessionFixture.notificationPermission)
        .around(seed)
        .around(compose)

    @Before fun openSession() = SessionFixture.open(compose)

    @After fun tidy() = SessionFixture.cleanUp()

    @Test
    fun rendersUtterancesAsEventsArrive() {
        // Simulate the pipeline emitting a status then two utterances. Utterance events APPEND, so
        // these land after the seeded lines — the transcript grows rather than being rebuilt.
        SessionFixture.emit(TranscriptEvent.Status("Transcribing…"))
        SessionFixture.emit(TranscriptEvent.Utterance(2, "hello world", 200.0, 201.0))
        SessionFixture.emit(TranscriptEvent.Utterance(3, "second line", 201.0, 202.0))

        // The list updates incrementally; wait for the rendered text.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("hello world", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("hello world", substring = true).assertIsDisplayed()
        compose.onNodeWithText("second line", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Transcribing…", substring = true).assertIsDisplayed()
        // The seeded lines are still there — appended, not replaced.
        compose.onNodeWithText(SessionFixture.SEEDED[0].text, substring = true).assertIsDisplayed()
    }
}
