package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 5-segment mic input level shown while recording — quantized service-side, so the e-ink
 *  screen only repaints when the level crosses a bucket boundary. (The old VoxSumTopBar this
 *  file was named for died in the 2.0 redesign — SessionTopBar/StudioScreen replaced it.) */
@Composable
fun MicLevelBars(level: Float, color: androidx.compose.ui.graphics.Color, scale: Float = 1f) {
    val active = (level * 5 + 0.5f).toInt().coerceIn(0, 5)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy((2 * scale).dp)) {
        repeat(5) { i ->
            Box(
                Modifier
                    .width((3 * scale).dp)
                    .height(((6 + i * 2) * scale).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < active) color else color.copy(alpha = 0.3f)),
            )
        }
    }
}
