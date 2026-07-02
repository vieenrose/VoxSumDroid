package studio.voxsum.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.theme.LocalVoxSumPalette

/** Desktop copy of MainActivity.kt's private SectionCard — a rounded, bordered surface used to
 *  group a section (title/summary/action items, a list row) instead of plain unstyled Text/Row,
 *  matching Android's card-based visual hierarchy. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalVoxSumPalette.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pal.PanelSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, pal.Hairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
