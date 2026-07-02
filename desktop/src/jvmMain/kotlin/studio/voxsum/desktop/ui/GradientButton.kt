package studio.voxsum.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.theme.LocalVoxSumPalette

/** Desktop copy of app/ui/components/GradientButton.kt — identical, pure-Compose, no Android
 *  dependency in the original, so this is a verbatim port (only the package differs). */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.background(
            brush = if (enabled) pal.BrandGradient else SolidColor(pal.Slate700),
            shape = RoundedCornerShape(12.dp),
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = pal.Slate900, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = pal.Slate900, fontWeight = FontWeight.SemiBold)
            if (trailingIcon != null) {
                Spacer(Modifier.width(4.dp))
                Icon(trailingIcon, contentDescription = null, tint = pal.Slate900, modifier = Modifier.size(18.dp))
            }
        }
    }
}
