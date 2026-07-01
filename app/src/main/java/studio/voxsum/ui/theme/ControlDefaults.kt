package studio.voxsum.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One place for the brand-consistent colors of Material controls, so sliders, switches, text
 * fields, radios and buttons all read sky-on-slate instead of the default Material indigo. Colors
 * are pulled from the theme-aware [LocalVoxSumPalette] so they flip with light / dark / e-ink.
 */

@Composable
fun voxSumSliderColors(): SliderColors {
    val pal = LocalVoxSumPalette.current
    return SliderDefaults.colors(
        thumbColor = pal.Sky,
        activeTrackColor = pal.Sky,
        inactiveTrackColor = pal.Slate700,
        activeTickColor = pal.Slate900,
        inactiveTickColor = pal.Slate600,
    )
}

@Composable
fun voxSumSwitchColors(): SwitchColors {
    val pal = LocalVoxSumPalette.current
    return SwitchDefaults.colors(
        checkedThumbColor = pal.Slate900,
        checkedTrackColor = pal.Sky,
        checkedBorderColor = pal.Sky,
        uncheckedThumbColor = pal.Slate400,
        uncheckedTrackColor = pal.Slate800,
        uncheckedBorderColor = pal.Slate600,
    )
}

@Composable
fun voxSumTextFieldColors(): TextFieldColors {
    val pal = LocalVoxSumPalette.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = pal.Slate200,
        unfocusedTextColor = pal.Slate200,
        focusedContainerColor = pal.InsetSurface,
        unfocusedContainerColor = pal.InsetSurface,
        cursorColor = pal.Sky,
        focusedBorderColor = pal.Sky,
        unfocusedBorderColor = pal.Hairline,
        focusedLabelColor = pal.Sky,
        unfocusedLabelColor = pal.Slate400,
    )
}

@Composable
fun voxSumRadioColors(): RadioButtonColors {
    val pal = LocalVoxSumPalette.current
    return RadioButtonDefaults.colors(
        selectedColor = pal.Sky,
        unselectedColor = pal.Slate400,
    )
}

/** Outlined-button content tint; pair with a Hairline border at the call site. */
@Composable
fun voxSumOutlinedColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    contentColor = LocalVoxSumPalette.current.Slate200,
)

@Composable
fun voxSumFilledTonalColors(
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = container,
    contentColor = content,
)
