package studio.voxsum.core.diarization

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import studio.voxsum.core.events.TranscriptEvent

/**
 * Speaker diarization — Android counterpart of src/diarization.py. Wraps sherpa-onnx's
 * OfflineSpeakerDiarization, which runs the whole pipeline natively: pyannote segmentation
 * → 3D-Speaker embeddings → FastClustering. We then attach a speaker id to each ASR
 * utterance by maximum temporal overlap with the diarization segments.
 *
 * numClusters = -1 → auto speaker count via the clustering threshold (matches VoxSum's
 * "let it decide" default). One instance owns native resources; call [close].
 */
class DiarizationEngine(
    segmentationModel: String,
    embeddingModel: String,
    numThreads: Int,
    numClusters: Int = -1,
    clusterThreshold: Float = 0.5f,
) : AutoCloseable {

    private val sd = OfflineSpeakerDiarization(
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModel),
                numThreads = numThreads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModel,
                numThreads = numThreads,
            ),
            clustering = FastClusteringConfig(numClusters = numClusters, threshold = clusterThreshold),
        ),
    )

    /**
     * Tag each utterance with a speaker id. Returns the tagged utterances and the detected
     * speaker count (0 if diarization found nothing, leaving utterances unchanged).
     */
    fun assignSpeakers(
        pcm16k: FloatArray,
        utterances: List<TranscriptEvent.Utterance>,
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        val segments = sd.process(pcm16k)
        if (segments.isEmpty()) return utterances to 0

        val tagged = utterances.map { u ->
            var bestSpeaker = -1
            var bestOverlap = 0.0
            for (s in segments) {
                val overlap = minOf(u.endSec, s.end.toDouble()) - maxOf(u.startSec, s.start.toDouble())
                if (overlap > bestOverlap) { bestOverlap = overlap; bestSpeaker = s.speaker }
            }
            if (bestSpeaker >= 0) u.copy(speaker = bestSpeaker) else u
        }
        val speakerCount = segments.maxOf { it.speaker } + 1
        return tagged to speakerCount
    }

    override fun close() = sd.release()
}
