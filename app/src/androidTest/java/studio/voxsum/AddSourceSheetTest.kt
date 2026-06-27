package studio.voxsum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.ui.AddSourceSheet

/**
 * Isolated tests for the "Add audio" bottom sheet: all five sources render, and tapping each invokes
 * its callback (the row fires onDismiss + the source action).
 */
@RunWith(AndroidJUnit4::class)
class AddSourceSheetTest {

    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Spies {
        var file = false; var record = false; var podcast = false; var youtube = false; var session = false; var dismiss = false
    }

    private fun host(s: Spies) {
        compose.setContent {
            AddSourceSheet(
                onPickFile = { s.file = true }, onRecord = { s.record = true },
                onPodcast = { s.podcast = true }, onYouTube = { s.youtube = true },
                onOpenSession = { s.session = true }, onDismiss = { s.dismiss = true },
            )
        }
    }

    @Test fun rendersAllFiveSources() {
        host(Spies())
        listOf(R.string.source_audio_file, R.string.source_record, R.string.source_podcast,
            R.string.source_youtube, R.string.source_session).forEach {
            compose.onNodeWithText(ctx.getString(it)).assertIsDisplayed()
        }
    }

    @Test fun tappingRecordFiresRecordAndDismiss() {
        val s = Spies(); host(s)
        compose.onNodeWithText(ctx.getString(R.string.source_record)).performClick()
        assertTrue("onRecord", s.record)
        assertTrue("onDismiss", s.dismiss)
    }

    @Test fun tappingPodcastFiresPodcast() {
        val s = Spies(); host(s)
        compose.onNodeWithText(ctx.getString(R.string.source_podcast)).performClick()
        assertTrue(s.podcast)
    }

    @Test fun tappingYouTubeFiresYouTube() {
        val s = Spies(); host(s)
        compose.onNodeWithText(ctx.getString(R.string.source_youtube)).performClick()
        assertTrue(s.youtube)
    }

    @Test fun tappingOpenSessionFiresOpenSession() {
        val s = Spies(); host(s)
        compose.onNodeWithText(ctx.getString(R.string.source_session)).performClick()
        assertTrue(s.session)
    }

    @Test fun tappingFileFiresPickFile() {
        val s = Spies(); host(s)
        compose.onNodeWithText(ctx.getString(R.string.source_audio_file)).performClick()
        assertTrue(s.file)
    }
}
