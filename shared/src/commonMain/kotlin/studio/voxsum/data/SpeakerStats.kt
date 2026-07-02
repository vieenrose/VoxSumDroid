package studio.voxsum.data

import studio.voxsum.core.events.TranscriptEvent

/** Per-speaker stats — port of src/diarization.py::get_diarization_stats["speakers"][id]. */
data class SpeakerStats(
    val speaker: Int,
    val speakingTimeSec: Double,
    val percentage: Double,            // share of total speaking time
    val utteranceCount: Int,
    val avgUtteranceLengthSec: Double,
)

/** Aggregate — port of the top-level get_diarization_stats dict. */
data class DiarizationStats(
    val totalSpeakers: Int,
    val totalDurationSec: Double,      // total speaking time (sum of durations)
    val perSpeaker: List<SpeakerStats>,
)

/** Pure port of get_diarization_stats. Utterances with speaker == null are ignored. */
fun computeDiarizationStats(utterances: List<TranscriptEvent.Utterance>): DiarizationStats {
    val time = LinkedHashMap<Int, Double>()
    val count = LinkedHashMap<Int, Int>()
    var total = 0.0
    for (u in utterances) {
        val s = u.speaker ?: continue
        val dur = (u.endSec - u.startSec).coerceAtLeast(0.0)
        total += dur
        time[s] = (time[s] ?: 0.0) + dur
        count[s] = (count[s] ?: 0) + 1
    }
    if (time.isEmpty()) return DiarizationStats(0, 0.0, emptyList())
    val perSpeaker = time.keys.sorted().map { id ->
        val t = time.getValue(id)
        val c = count.getValue(id)
        SpeakerStats(
            speaker = id,
            speakingTimeSec = t,
            percentage = if (total > 0.0) t / total * 100.0 else 0.0,
            utteranceCount = c,
            avgUtteranceLengthSec = if (c > 0) t / c else 0.0,
        )
    }
    return DiarizationStats(time.size, total, perSpeaker)
}

/** "3m 04s" / "12s" helper for the stats panel. */
fun formatDuration(seconds: Double): String {
    val tot = seconds.toInt()
    val m = tot / 60
    val s = tot % 60
    return if (m > 0) "${m}m ${s.toString().padStart(2, '0')}s" else "${s}s"
}
