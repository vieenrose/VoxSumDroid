package studio.voxsum

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent

/**
 * Activity-level UI flow tests: inject real pipeline events into TranscriptionService.events (exactly
 * as the service emits them) and assert the screen reflects each — the transcript list, the always-
 * visible status line (progress / line+speaker count / error). Complements the isolated component tests.
 *
 * These assert against the Session screen, which MainActivity only reaches for a started or reopened
 * session, so each test opens a seeded one first — see [SessionFixture] for why it has to be a real
 * embedded session rather than a bare audio file.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptFlowTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    /** Seeding must finish BEFORE the activity launches, so it lives in a rule outside the compose one. */
    private val seed = object : ExternalResource() {
        override fun before() { SessionFixture.clear(); SessionFixture.seed() }
    }

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(SessionFixture.notificationPermission)
        .around(seed)
        .around(compose)

    private fun str(resId: Int, vararg args: Any) = compose.activity.getString(resId, *args)

    @Before fun openSession() = SessionFixture.open(compose)

    @After fun tidy() = SessionFixture.cleanUp()

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun statusEventUpdatesStatusLine() {
        // Each phase now owns the status line via a Status event (Progress drives only the bar).
        val msg = "Identifying speakers (uitest)"
        SessionFixture.emit(TranscriptEvent.Status(msg))
        waitForText(msg)
        compose.onNodeWithText(msg, substring = true).assertIsDisplayed()
    }

    @Test fun downloadProgressIgnoredWhenNotRunning() {
        // DownloadProgress drives the status line ONLY during an active run — the handler guards on
        // `running` so a late buffered event (e.g. after Stop) can't re-stick the UI. A reopened
        // session is not running, so its label must NOT appear. A follow-up Status (which updates
        // unconditionally) acts as an ordering marker: once it shows, the DownloadProgress before it
        // was already processed — and skipped.
        val dl = "Downloading summary model 42% (uitest)"
        val marker = "Marker after download (uitest)"
        SessionFixture.emit(TranscriptEvent.DownloadProgress(0.42f, dl))
        SessionFixture.emit(TranscriptEvent.Status(marker))
        waitForText(marker)
        compose.onAllNodesWithText(dl, substring = true).assertCountEquals(0)
    }

    @Test fun completeRendersUtterancesAndLineSpeakerCount() {
        SessionFixture.emit(
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
        SessionFixture.emit(TranscriptEvent.Failed("disk full"))
        val expected = str(R.string.status_error, "disk full")
        waitForText(expected)
        // The error surfaces in BOTH the status line and a snackbar — assert at least one is shown.
        compose.onAllNodesWithText(expected, substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun loadingAnAudioSourceShowsThePlayerAndPlayToggles() {
        // Stage a real, short 16 kHz wav into the app's cache and load it as the audio source — a
        // RecordingSaved event is exactly how the recording flow hands the player its WAV.
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("en.wav").use { it.readBytes() }
        val wav = File(compose.activity.cacheDir, "uitest_player.wav").apply { writeBytes(bytes) }
        SessionFixture.emit(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))

        // The docked player appears with a Play control (the MediaPlayer prepares off-thread).
        val play = str(R.string.cd_play)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(play).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(play).assertIsDisplayed().performClick()

        // Tapping Play starts playback (self-healing prepare), flipping the control to Pause.
        val pause = str(R.string.cd_pause)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(pause).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(pause).assertIsDisplayed()
    }
}
