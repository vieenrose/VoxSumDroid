package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.config.ThemeMode
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.data.SpeakerNames
import studio.voxsum.data.speakerColor
import studio.voxsum.data.speakerLabel
import studio.voxsum.desktop.audio.AudioDecoder
import studio.voxsum.desktop.files.FilePicker
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumTheme
import java.io.File

/** Per-user app-private storage, XDG Base Directory-compliant: $XDG_DATA_HOME/VoxSum, falling
 *  back to ~/.local/share/VoxSum — the same role app/'s Context.filesDir plays on Android.
 *  ModelManager creates its own "models" subdirectory under whatever it's given. */
private val appDataDir: File by lazy {
    val xdgDataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}/.local/share"
    File(xdgDataHome, "VoxSum").apply { mkdirs() }
}

private data class PipelineState(
    val fileName: String = "",
    val status: String = "",
    val progress: Float? = null,
    val running: Boolean = false,
    val utterances: List<TranscriptEvent.Utterance> = emptyList(),
    val speakerCount: Int = 0,
    val title: String = "",
    val summary: String = "",
    val error: String? = null,
)

fun main() {
    NativeLibs.ensureLoaded()
    mainApplication()
}

private fun mainApplication() = application {
    Window(onCloseRequest = ::exitApplication, title = "VoxSum for Linux") {
        var themeMode by remember { mutableStateOf(ThemeMode.AUTO) }
        var state by remember { mutableStateOf(PipelineState()) }
        val scope = rememberVoxSumScope()

        VoxSumTheme(themeMode = themeMode) {
            val pal = LocalVoxSumPalette.current
            Column(Modifier.fillMaxSize().background(pal.Slate900).padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Button(
                        enabled = !state.running,
                        onClick = {
                            val picked = FilePicker.openFile(
                                "Pick an audio file",
                                extensions = listOf("wav", "mp3", "m4a", "flac", "ogg"),
                            ) ?: return@Button
                            scope.launch {
                                runPipeline(picked) { state = it(state) }
                            }
                        },
                    ) { Text("Add audio") }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThemeMode.entries.forEach { m ->
                            Button(onClick = { themeMode = m }) { Text(m.name) }
                        }
                    }
                }

                Column(Modifier.padding(top = 12.dp)) {
                    if (state.fileName.isNotEmpty()) Text(state.fileName, color = pal.Slate400)
                    if (state.status.isNotEmpty()) Text(state.status, color = pal.Slate200)
                    state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                }

                if (state.title.isNotEmpty() || state.summary.isNotEmpty()) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        if (state.title.isNotEmpty()) Text(state.title, color = pal.Slate200, style = MaterialTheme.typography.titleMedium)
                        if (state.summary.isNotEmpty()) Text(state.summary, color = pal.Slate400)
                    }
                }

                val names: SpeakerNames = emptyMap()
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(state.utterances) { u ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Top,
                        ) {
                            Box(
                                Modifier.size(10.dp).padding(top = 4.dp)
                                    .clip(CircleShape)
                                    .background(Color(speakerColor(u.speaker))),
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(
                                    speakerLabel(u.speaker, names) ?: "",
                                    color = pal.Slate400,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(u.text, color = pal.Slate200)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Decode -> ASR -> diarize -> summarize, reporting progress through [update]. Heavy work runs
 *  on Dispatchers.Default/IO; [update] mutations are posted back via the caller's (UI) dispatcher. */
private suspend fun runPipeline(file: File, update: ((PipelineState) -> PipelineState) -> Unit) {
    update { it.copy(fileName = file.name, running = true, error = null, utterances = emptyList(), title = "", summary = "") }
    try {
        val models = ModelManager(appDataDir)
        val llmSpec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)

        if (!models.asrReady(AsrBackend.SENSEVOICE)) {
            update { it.copy(status = "Downloading speech model…", progress = 0f) }
            models.ensureAsrModels(AsrBackend.SENSEVOICE) { p -> update { it.copy(progress = p) } }
        }
        if (!models.diarizationReady()) {
            update { it.copy(status = "Downloading speaker model…", progress = 0f) }
            models.ensureDiarizationModels { p -> update { it.copy(progress = p) } }
        }

        update { it.copy(status = "Decoding…", progress = null) }
        val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(file) }

        update { it.copy(status = "Transcribing…", progress = 0f) }
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        withContext(Dispatchers.Default) {
            AsrEngine(
                backend = AsrBackend.SENSEVOICE,
                files = models.asrFiles(AsrBackend.SENSEVOICE),
                vadModel = models.vadModel.absolutePath,
                numThreads = 2,
            ).use { asr ->
                asr.transcribe(pcm).collect { e ->
                    when (e) {
                        is TranscriptEvent.Utterance -> { utterances += e; update { it.copy(utterances = utterances.toList()) } }
                        is TranscriptEvent.Progress -> update { it.copy(progress = e.fraction) }
                        is TranscriptEvent.Status -> update { it.copy(status = e.message) }
                        else -> {}
                    }
                }
            }
        }

        update { it.copy(status = "Identifying speakers…", progress = null) }
        val diarResult: Pair<List<TranscriptEvent.Utterance>, Int> = withContext(Dispatchers.Default) {
            val diar = DiarizationEngine(embeddingModel = models.embeddingModel.absolutePath, numThreads = 2)
            try {
                diar.assignSpeakers(
                    pcm16k = pcm,
                    utterances = utterances,
                )
            } finally {
                diar.close()
            }
        }
        val tagged = diarResult.first
        val speakerCount = diarResult.second
        update { it.copy(utterances = tagged, speakerCount = speakerCount, progress = null) }

        if (!models.llmReady(llmSpec)) {
            update { it.copy(status = "Downloading summarization model…", progress = 0f) }
            models.ensureLlmModel(llmSpec) { p -> update { it.copy(progress = p) } }
        }

        update { it.copy(status = "Summarizing…", progress = null) }
        val transcriptText = tagged.joinToString("\n") { u -> "${speakerLabel(u.speaker, emptyMap()) ?: ""}: ${u.text}" }
        withContext(Dispatchers.Default) {
            LlmEngine.load(models.llmFile(llmSpec).absolutePath, nThreads = 4, sampler = llmSpec.sampler).use { llm ->
                Summarizer(llm, llmSpec.chatTemplate).summarize(
                    transcript = transcriptText,
                    userPrompt = "Summarize the key points of this transcript.",
                ).collect { e ->
                    when (e) {
                        is TranscriptEvent.Title -> update { it.copy(title = e.title) }
                        is TranscriptEvent.Partial -> update { it.copy(summary = it.summary + e.chunk) }
                        is TranscriptEvent.SummaryComplete -> update { it.copy(summary = e.summary) }
                        else -> {}
                    }
                }
            }
        }
        update { it.copy(status = "Done", running = false) }
    } catch (t: Throwable) {
        update { it.copy(error = t.message ?: t.javaClass.simpleName, running = false, status = "Failed") }
    }
}

@androidx.compose.runtime.Composable
private fun rememberVoxSumScope() = androidx.compose.runtime.rememberCoroutineScope()
