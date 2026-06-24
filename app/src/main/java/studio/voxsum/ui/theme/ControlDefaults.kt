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
 * fields, radios and buttons all read sky-on-slate instead of the default Material indigo.
 */

@Composable
fun voxSumSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = VoxSumPalette.Sky,
    activeTrackColor = VoxSumPalette.Sky,
    inactiveTrackColor = VoxSumPalette.Slate700,
    activeTickColor = VoxSumPalette.Slate900,
    inactiveTickColor = VoxSumPalette.Slate600,
)

@Composable
fun voxSumSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = VoxSumPalette.Slate900,
    checkedTrackColor = VoxSumPalette.Sky,
    checkedBorderColor = VoxSumPalette.Sky,
    uncheckedThumbColor = VoxSumPalette.Slate400,
    uncheckedTrackColor = VoxSumPalette.Slate800,
    uncheckedBorderColor = VoxSumPalette.Slate600,
)

@Composable
fun voxSumTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = VoxSumPalette.Slate200,
    unfocusedTextColor = VoxSumPalette.Slate200,
    focusedContainerColor = VoxSumPalette.InsetSurface,
    unfocusedContainerColor = VoxSumPalette.InsetSurface,
    cursorColor = VoxSumPalette.Sky,
    focusedBorderColor = VoxSumPalette.Sky,
    unfocusedBorderColor = VoxSumPalette.Hairline,
    focusedLabelColor = VoxSumPalette.Sky,
    unfocusedLabelColor = VoxSumPalette.Slate400,
)

@Composable
fun voxSumRadioColors(): RadioButtonColors = RadioButtonDefaults.colors(
    selectedColor = VoxSumPalette.Sky,
    unselectedColor = VoxSumPalette.Slate400,
)

/** Outlined-button content tint; pair with a Hairline border at the call site. */
@Composable
fun voxSumOutlinedColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    contentColor = VoxSumPalette.Slate200,
)

@Composable
fun voxSumFilledTonalColors(
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = container,
    contentColor = content,
)
