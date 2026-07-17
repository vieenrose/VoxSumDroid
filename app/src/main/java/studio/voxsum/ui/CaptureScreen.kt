package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.voxsum.R
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Full-screen capture — the studio's recording booth. Fixed layout with two GIANT buttons
 * (⏭ Next talk / ⏹ Stop & save) sized for a conference table and an e-ink screen: nothing
 * shifts position with state, unlike the old top-bar icon strip. A collapsible live-transcript
 * panel fills the space between the name field and the buttons, auto-following the newest line;
 * back leaves the recording running (Studio shows a live banner to return here).
 */
@Composable
fun CaptureScreen(
    isRecording: Boolean,
    recSeconds: Int,
    micLevel: Float,
    sessionName: String,
    onSessionName: (String) -> Unit,
    utterances: List<TranscriptEvent.Utterance>,
    onNextTalk: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    var showLive by remember { mutableStateOf(true) }
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.Slate900Grad)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = pal.Slate200)
            }
            Text(
                stringResource(if (isRecording) R.string.status_recording else R.string.capture_title),
                style = MaterialTheme.typography.titleMedium,
                color = if (isRecording) VoxSumPalette.Red else pal.Slate200,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Compact header: timer + mic bars on one line — still readable from across a table, but
        // the vertical space goes to the live transcript below instead of empty padding.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                "%d:%02d".format(recSeconds / 60, recSeconds % 60),
                fontSize = 64.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,   // tabular: no layout shift per tick
                color = pal.Slate200,
            )
            Spacer(Modifier.width(20.dp))
            // 2x: at 1x the bars are a speck beside 64sp digits — scale to visually balance them.
            MicLevelBars(micLevel, pal.Sky, scale = 2f)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = sessionName,
            onValueChange = onSessionName,
            singleLine = true,
            label = { Text(stringResource(R.string.capture_session_name), color = pal.Slate400) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = pal.Slate200, unfocusedTextColor = pal.Slate200,
                focusedBorderColor = pal.Sky, unfocusedBorderColor = pal.Slate700,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        // Live transcript: a first-class panel filling everything between the name field and the
        // buttons — the full running transcript, auto-following the newest line. Collapsible for
        // anyone who finds the moving text distracting mid-meeting.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.Slate800).padding(horizontal = 12.dp),
        ) {
            Text(
                stringResource(R.string.capture_live_transcript),
                style = MaterialTheme.typography.labelMedium,
                color = pal.Slate400,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showLive = !showLive }) {
                Icon(
                    if (showLive) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = null,
                    tint = pal.Slate400,
                )
            }
        }
        if (showLive) {
            if (utterances.isEmpty()) {
                // Centered waiting state: a corner-anchored one-liner made the big empty panel
                // look unfinished — center it with a quiet mic glyph so the space reads intentional.
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Mic, contentDescription = null,
                            tint = pal.Slate700, modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.capture_live_waiting), color = pal.Slate400, style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                val listState = rememberLazyListState()
                // Follow the newest line. Instant jump, not animate: e-ink hates animated scrolls.
                LaunchedEffect(utterances.size) {
                    listState.scrollToItem(utterances.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    items(utterances.size) { i ->
                        Text(
                            utterances[i].text,
                            color = pal.Slate200,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // ⏭ Next talk — the batch-recording workhorse: auto-save this capture, defer its
            // processing, and roll straight into the next session.
            Button(
                onClick = onNextTalk,
                enabled = isRecording,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pal.Sky),
                // On a batch day ⏭ is tapped ten times for every ⏹ — it gets the primary width.
                modifier = Modifier.weight(1.5f).height(96.dp),
                // Minimal padding: on narrow phones the default 24dp sides forced CJK labels to wrap.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text(stringResource(R.string.capture_next_talk), fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            // ⏹ Stop & save — always safe: the capture is in the library before processing starts.
            Button(
                onClick = onStop,
                enabled = isRecording,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VoxSumPalette.Red),
                modifier = Modifier.weight(1f).height(96.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text(stringResource(R.string.capture_stop), fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
