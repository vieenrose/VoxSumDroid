package studio.voxsum.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.theme.LocalVoxSumPalette

/** Podcast search/browse/download in a bottom sheet (hosts the existing [PodcastPanel]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSheet(onEpisodeReady: (Uri) -> Unit, onDismiss: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pal.Slate800,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            PodcastPanel(onEpisodeReady = onEpisodeReady)
        }
    }
}
