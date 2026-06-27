package studio.voxsum.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Find-in-page over the current transcript: a slim bar (shown above the list when search is active)
 * with a query field, a match counter, and prev/next steppers. Pure local string matching — no
 * model, no network. The host owns the query/match state and drives the list scroll; this is just
 * the control surface.
 */
@Composable
fun TranscriptSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    matchPos: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = VoxSumPalette.Slate400, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = VoxSumPalette.Slate200),
            cursorBrush = SolidColor(VoxSumPalette.Sky),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.search_transcript_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoxSumPalette.Slate400,
                    )
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            Text(
                if (matchCount == 0) stringResource(R.string.search_no_matches)
                else stringResource(R.string.search_match_count, matchPos + 1, matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = VoxSumPalette.Slate400,
            )
            IconButton(onClick = onPrev, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.search_prev), tint = VoxSumPalette.Slate200, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onNext, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.search_next), tint = VoxSumPalette.Slate200, modifier = Modifier.size(20.dp))
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, stringResource(R.string.search_close), tint = VoxSumPalette.Slate400, modifier = Modifier.size(18.dp))
        }
    }
}

/** Render [text] with every (case-insensitive) occurrence of [query] highlighted. Returns plain
 *  text when [query] is blank, so callers can use it unconditionally. */
fun highlightedTranscript(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    // Match on the original text with ignoreCase (NOT a pre-lowercased copy, whose length can differ
    // from the original for some Unicode and misalign the indices). query.length advances each step,
    // so the loop always terminates.
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(query, i, ignoreCase = true)
            if (hit < 0) { append(text.substring(i)); break }
            append(text.substring(i, hit))
            withStyle(SpanStyle(background = VoxSumPalette.Sky.copy(alpha = 0.35f), color = VoxSumPalette.Slate200)) {
                append(text.substring(hit, hit + query.length))
            }
            i = hit + query.length
        }
    }
}
