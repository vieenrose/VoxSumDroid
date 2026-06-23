package studio.voxsum.core.diarization

import studio.voxsum.core.events.TranscriptEvent

/**
 * Speaker diarization — Android counterpart of src/diarization.py +
 * src/improved_diarization.py. Uses sherpa-onnx's OfflineSpeakerDiarization, which
 * bundles the whole pipeline natively:
 *   segmentation (pyannote-segmentation-3.0) -> speaker embeddings (3D-Speaker) ->
 *   FastClustering (num_clusters or threshold).
 *
 * This is why we chose Path A: VoxSum's diarization maps onto a single sherpa-onnx call
 * instead of reimplementing FAISS clustering. The cost is the onnxruntime source build
 * for F-Droid (see SPIKE.md).
 */
class DiarizationEngine(
    private val segmentationModel: String,  // sherpa-onnx-pyannote-segmentation-3-0/model.onnx
    private val embeddingModel: String,     // 3dspeaker_*_sv_*.onnx
    private val numThreads: Int,
) {
    /**
     * Assigns a speaker id to each utterance. If [numSpeakers] is null, clustering uses a
     * distance threshold (auto count) — same idea as the Python small-n heuristic path.
     *
     * Returns the utterances tagged with `speaker`, plus the detected speaker count, so the
     * caller can emit the final Complete event and the UI can apply per-speaker colors
     * (getSpeakerColor mirrors src/diarization.py::get_speaker_color).
     */
    fun assignSpeakers(
        pcm16k: FloatArray,
        utterances: List<TranscriptEvent.Utterance>,
        numSpeakers: Int? = null,
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        // TODO(spike): build OfflineSpeakerDiarizationConfig(segmentation, embedding,
        //   FastClusteringConfig(numClusters = numSpeakers ?: -1, threshold = 0.5)),
        //   process(pcm) -> segments(speaker,start,end), then overlap-match each utterance
        //   to the segment it falls in and copy the speaker id.
        TODO("sherpa-onnx OfflineSpeakerDiarization + overlap-match to utterances")
    }
}
