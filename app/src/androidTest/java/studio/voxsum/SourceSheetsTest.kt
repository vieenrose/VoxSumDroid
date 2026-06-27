package studio.voxsum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.ui.PodcastPanel
import studio.voxsum.ui.YouTubeSheet

/**
 * Tests the podcast + YouTube source UIs. Static state (render, button enablement, the URL-vs-search
 * label toggle) is asserted offline; one live-network smoke test per source confirms a real search
 * completes (the busy indicator clears) rather than hanging. Requires network for the @* live tests.
 */
@RunWith(AndroidJUnit4::class)
class SourceSheetsTest {

    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int) = ctx.getString(id)

    private fun searchCleared() = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(str(R.string.dl_searching), substring = true).fetchSemanticsNodes().isEmpty()
    }

    // --- PodcastPanel ------------------------------------------------------------------------

    @Test fun podcastSearchButtonDisabledUntilQueryTyped() {
        compose.setContent { PodcastPanel(onEpisodeReady = {}) }
        compose.onNodeWithText(str(R.string.podcast_search_hint)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.podcast_search)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("news")
        compose.onNodeWithText(str(R.string.podcast_search)).assertIsEnabled()
    }

    @Test fun podcastSearchCompletesOverNetwork() {
        compose.setContent { PodcastPanel(onEpisodeReady = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("BBC")
        compose.onNodeWithText(str(R.string.podcast_search)).performClick()
        searchCleared()   // the busy "searching" bar must clear within 30 s (not hang)
        compose.onNodeWithText(str(R.string.podcast_search)).assertIsEnabled()
    }

    // --- YouTubeSheet ------------------------------------------------------------------------

    @Test fun youtubeRendersTitleAndField() {
        compose.setContent { YouTubeSheet(onAudioReady = {}, onDismiss = {}) }
        compose.onNodeWithText(str(R.string.source_youtube)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.youtube_search_hint)).assertIsDisplayed()
    }

    @Test fun youtubeButtonLabelTogglesBetweenSearchAndGo() {
        compose.setContent { YouTubeSheet(onAudioReady = {}, onDismiss = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("lofi beats")
        compose.onNodeWithText(str(R.string.youtube_search)).assertIsDisplayed()   // keyword → Search
    }

    @Test fun youtubeButtonSaysGoForAUrl() {
        compose.setContent { YouTubeSheet(onAudioReady = {}, onDismiss = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("https://youtu.be/dQw4w9WgXcQ")
        compose.onNodeWithText(str(R.string.youtube_go)).assertIsDisplayed()       // URL → Go
    }

    @Test fun youtubeSearchCompletesOverNetwork() {
        compose.setContent { YouTubeSheet(onAudioReady = {}, onDismiss = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("lofi")
        compose.onNodeWithText(str(R.string.youtube_search)).performClick()
        searchCleared()   // resolves to results or a clean error within 30 s — must not hang
    }
}
