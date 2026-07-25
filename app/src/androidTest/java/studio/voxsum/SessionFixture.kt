package studio.voxsum

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.rules.TestRule
import kotlinx.coroutines.runBlocking
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.library.SessionLibrary
import studio.voxsum.core.session.RecentSessions
import studio.voxsum.data.SpeakerName
import studio.voxsum.service.TranscriptionService
import java.io.File

/**
 * Puts the app on the Session screen, which is where the transcript UI lives.
 *
 * MainActivity opens on the Studio shelf and only navigates to Session when a session is started or
 * reopened, so a UI test that wants the transcript has to arrive there the way a user does. Starting
 * one is not an option (it would run the real pipeline and download models), and opening a bare WAV
 * is worse — `openSessionUri` sees no embedded session and kicks off a transcription. So we seed a
 * genuine `session.m4a` through the app's own library calls and tap its row.
 */
object SessionFixture {

    const val TITLE = "UITest Session"

    /**
     * Utterances of the seeded session. Timed far from any injected event (100 s+): the Complete
     * handler merges by START TIME to preserve in-flight edits, so a seeded line sharing a startSec
     * with an injected one would keep the SEEDED text and the assertion would read the wrong line.
     */
    val SEEDED = listOf(
        TranscriptEvent.Utterance(0, "seeded opening line", 100.0, 101.0, speaker = 0),
        TranscriptEvent.Utterance(1, "seeded reply line", 101.0, 102.0, speaker = 1),
    )

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Grant POST_NOTIFICATIONS before the activity launches so no system dialog covers the UI —
     * but only where the permission exists. It arrived in API 33, and asking for it on an older
     * device fails the test in the RULE with "SecurityException: Error granting runtime
     * permission", before any test body runs (minSdk here is 26, and the Boox is API 30).
     */
    val notificationPermission: TestRule =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    /** Wipe the library + recents so the shelf holds exactly the row we seed. */
    fun clear() {
        SessionLibrary.root(ctx).deleteRecursively()
        ctx.getSharedPreferences("voxsum_recents", 0).edit().clear().commit()
    }

    /** Seed one finished session (audio + transcript + speakers embedded) and return its entry. */
    fun seed(): SessionLibrary.Entry = runBlocking {
        val wav = File(ctx.cacheDir, "fixture_src.wav").apply {
            writeBytes(
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open("en.wav").use { it.readBytes() },
            )
        }
        val entry = requireNotNull(SessionLibrary.promoteRecording(ctx, wav, durationSec = 2)) {
            "could not promote the fixture recording into the library"
        }
        requireNotNull(
            SessionLibrary.attachResults(
                ctx, entry, SEEDED,
                speakerNames = mapOf(0 to SpeakerName("Alice", "user", ""), 1 to SpeakerName("Bob", "user", "")),
                summary = "• seeded summary", actionItems = "- seeded action",
                title = TITLE, asrModelId = "x-asr", llmModelId = "gemma",
            ),
        ) { "could not embed the fixture session" }
    }

    /**
     * Tap the seeded row on the Studio shelf, then select the Transcript tab — a reopened session
     * lands on Summary, so the transcript list is not composed until the tab is chosen. Opening
     * decodes the embedded audio, so allow real time for it; the seeded transcript rendering is the
     * signal that we arrived (and that the session recovered rather than falling through to a
     * transcription). Labels come from resources so this holds under any UI locale.
     */
    fun <A : androidx.activity.ComponentActivity> open(compose: AndroidComposeTestRule<*, A>) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(TITLE, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(TITLE, substring = true).onFirst().performClick()

        val transcriptTab = ctx.getString(R.string.tab_transcript)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(transcriptTab, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(transcriptTab, substring = true).onFirst().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(SEEDED[0].text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Recent rows point at files this fixture deletes — drop them so no test sees a dead entry. */
    fun cleanUp() {
        RecentSessions.list(ctx).forEach { RecentSessions.remove(ctx, it.uri) }
        clear()
    }

    /** Emit a pipeline event the way the service does — untagged, so no generation filter drops it. */
    fun emit(event: TranscriptEvent) {
        TranscriptionService.events.tryEmit(TranscriptionService.UNTAGGED to event)
    }
}
