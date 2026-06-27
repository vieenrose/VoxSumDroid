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
import studio.voxsum.data.DiarizationStats
import studio.voxsum.data.SpeakerStats
import studio.voxsum.ui.EmptyState
import studio.voxsum.ui.ModelOptionCard
import studio.voxsum.ui.SpeakerStatsPanel
import studio.voxsum.ui.UpdateBanner
import studio.voxsum.ui.components.DownloadStatusBar
import studio.voxsum.ui.components.GradientButton

/**
 * Isolated Compose tests for the leaf UI components — each hosted with controlled state and spy
 * callbacks, asserting it renders the right (locale-resolved) text/contentDescription, reflects its
 * state (selected/downloaded/downloading/disabled), and invokes the correct callback on interaction.
 * Resource strings are resolved via the app context so the test is locale-independent (device: zh-Hant-TW).
 */
@RunWith(AndroidJUnit4::class)
class UiComponentsTest {

    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    // --- GradientButton ----------------------------------------------------------------------

    @Test fun gradientButtonRendersAndClicks() {
        var clicked = false
        compose.setContent { GradientButton("Tap me", onClick = { clicked = true }) }
        compose.onNodeWithText("Tap me").assertIsDisplayed().performClick()
        assertTrue("onClick should fire", clicked)
    }

    @Test fun gradientButtonDisabledIsNotEnabled() {
        var clicked = false
        compose.setContent { GradientButton("Nope", enabled = false, onClick = { clicked = true }) }
        compose.onNodeWithText("Nope").assertIsNotEnabled()
        assertTrue("a disabled button must not have fired", !clicked)
    }

    // --- DownloadStatusBar -------------------------------------------------------------------

    @Test fun downloadStatusBarAppendsPercentWhenDeterminate() {
        compose.setContent { DownloadStatusBar(R.string.update_downloading, 0.42f) }
        compose.onNodeWithText(ctx.getString(R.string.update_downloading) + " 42%").assertIsDisplayed()
    }

    @Test fun downloadStatusBarHasNoPercentWhenIndeterminate() {
        compose.setContent { DownloadStatusBar(R.string.update_downloading, null) }
        compose.onNodeWithText(ctx.getString(R.string.update_downloading)).assertIsDisplayed()
    }

    // --- ModelOptionCard ---------------------------------------------------------------------

    @Test fun modelOptionCardShowsMetadataAndClicks() {
        var clicked = false
        compose.setContent {
            ModelOptionCard("Gemma 4 E2B", "~2.2 GB", selected = true, downloaded = true, onClick = { clicked = true })
        }
        compose.onNodeWithText("Gemma 4 E2B").assertIsDisplayed()
        compose.onNodeWithText("~2.2 GB").assertIsDisplayed()
        compose.onNodeWithContentDescription(ctx.getString(R.string.model_downloaded)).assertIsDisplayed()
        compose.onNodeWithText("Gemma 4 E2B").performClick()
        assertTrue(clicked)
    }

    @Test fun modelOptionCardShowsWillDownloadBadge() {
        compose.setContent { ModelOptionCard("X", "Y", selected = false, downloaded = false, onClick = {}) }
        compose.onNodeWithContentDescription(ctx.getString(R.string.model_will_download)).assertIsDisplayed()
    }

    // --- EmptyState --------------------------------------------------------------------------

    @Test fun emptyStateShowsHeadlinePillarsAndCtaFires() {
        var added = false
        compose.setContent { EmptyState(onAddSource = { added = true }) }
        compose.onNodeWithText(ctx.getString(R.string.empty_headline)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.pillar_private_title)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.pillar_offline_title)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.pillar_cost_title)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.add_audio)).assertIsDisplayed().performClick()
        assertTrue("Add audio CTA should fire onAddSource", added)
    }

    // --- UpdateBanner ------------------------------------------------------------------------

    @Test fun updateBannerIdleShowsActionsAndFiresCallbacks() {
        var updated = false
        var dismissed = false
        compose.setContent {
            UpdateBanner("v9.9.9", "Shiny new things\nand more", progress = null,
                onUpdate = { updated = true }, onDismiss = { dismissed = true })
        }
        compose.onNodeWithText(ctx.getString(R.string.update_available, "v9.9.9")).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.update_now)).performClick()
        assertTrue("update_now fires onUpdate", updated)
        compose.onNodeWithContentDescription(ctx.getString(R.string.update_later)).performClick()
        assertTrue("the dismiss X fires onDismiss", dismissed)
    }

    @Test fun updateBannerDownloadingHidesActions() {
        compose.setContent {
            UpdateBanner("v9.9.9", "", progress = 0.4f, onUpdate = {}, onDismiss = {})
        }
        compose.onNodeWithText(ctx.getString(R.string.update_downloading) + " 40%").assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.update_now)).assertDoesNotExist()
    }

    // --- SpeakerStatsPanel -------------------------------------------------------------------

    @Test fun speakerStatsPanelEmptyRendersNothing() {
        compose.setContent { SpeakerStatsPanel(DiarizationStats(0, 0.0, emptyList())) }
        compose.onNodeWithText(ctx.resources.getQuantityString(R.plurals.speaker_count, 2, 2)).assertDoesNotExist()
    }

    @Test fun speakerStatsPanelShowsSpeakerCount() {
        val stats = DiarizationStats(
            totalSpeakers = 2, totalDurationSec = 10.0,
            perSpeaker = listOf(
                SpeakerStats(0, 5.0, 50.0, 3, 1.6),
                SpeakerStats(1, 5.0, 50.0, 2, 2.5),
            ),
        )
        compose.setContent { SpeakerStatsPanel(stats) }
        compose.onNodeWithText(ctx.resources.getQuantityString(R.plurals.speaker_count, 2, 2)).assertIsDisplayed()
    }
}
