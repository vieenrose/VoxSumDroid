package studio.voxsum

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The export redesign: the overflow menu carries ONE export entry instead of eight, and the sheet
 * behind it groups formats by what you get, with Save and Share on each group.
 */
@RunWith(AndroidJUnit4::class)
class ExportSheetTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    private val seed = object : ExternalResource() {
        override fun before() { SessionFixture.clear(); SessionFixture.seed() }
    }

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(SessionFixture.notificationPermission)
        .around(seed)
        .around(compose)

    private fun str(id: Int) = compose.activity.getString(id)

    @Before fun openSession() = SessionFixture.open(compose)

    @After fun tidy() = SessionFixture.cleanUp()

    private fun openSheet() {
        compose.onNodeWithContentDescription(str(R.string.cd_export)).performClick()
        compose.onNodeWithText(str(R.string.export_menu_entry)).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(str(R.string.export_group_document)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun overflowCarriesOneExportEntryNotEight() {
        compose.onNodeWithContentDescription(str(R.string.cd_export)).performClick()
        compose.onNodeWithText(str(R.string.export_menu_entry)).assertIsDisplayed()
        // The per-format items moved into the sheet; none of them remain in the menu.
        listOf(R.string.export_txt, R.string.export_srt, R.string.export_vtt,
               R.string.export_lrc, R.string.export_md, R.string.export_pdf,
               R.string.session_save_m4a, R.string.session_share_m4a).forEach { id ->
            compose.onAllNodesWithText(str(id), substring = true).assertCountEquals(0)
        }
    }

    @Test fun sheetGroupsFormatsAndOffersSaveAndShareOnEach() {
        openSheet()
        compose.onNodeWithText(str(R.string.export_group_session)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.export_group_document)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.export_group_subtitles)).assertIsDisplayed()
        // One Save + one Share per group — Share on documents and subtitles is new.
        compose.onAllNodesWithText(str(R.string.export_action_save)).assertCountEquals(3)
        compose.onAllNodesWithText(str(R.string.export_action_share)).assertCountEquals(3)
        // Every format is reachable as a chip.
        listOf("PDF", "MD", "TXT", "SRT", "VTT", "LRC").forEach {
            compose.onAllNodesWithText(it).onFirst().assertIsDisplayed()
        }
        compose.onNodeWithText(str(R.string.export_copy_transcript)).assertIsDisplayed()
    }

    @Test fun pickingAFormatChipKeepsTheSheetOpen() {
        // The chip selects a format; only Save/Share act. A chip that dismissed the sheet would make
        // the second and third formats in a group unreachable.
        openSheet()
        compose.onAllNodesWithText("VTT").onFirst().performClick()
        compose.onNodeWithText(str(R.string.export_group_subtitles)).assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.export_action_save)).assertCountEquals(3)
    }
}
