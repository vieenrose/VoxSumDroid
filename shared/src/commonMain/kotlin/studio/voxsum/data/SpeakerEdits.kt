package studio.voxsum.data

import studio.voxsum.core.events.TranscriptEvent

/**
 * Pure speaker-correction ops for fixing diarization mistakes: move one line to another speaker, or
 * merge a whole speaker into another. Both are plain relabels of [TranscriptEvent.Utterance.speaker]
 * (no embeddings needed — nothing downstream consumes centroids after diarization), and the result
 * is renumbered to contiguous ids 0..k-1 so labels/colours stay tidy. The `.ogg` round-trips the
 * corrected ints losslessly. Returns new (utterances, speakerNames); callers apply to their state.
 */
object SpeakerEdits {

    fun reassign(
        utts: List<TranscriptEvent.Utterance>,
        names: Map<Int, SpeakerName>,
        index: Int,
        target: Int,
    ): Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>> {
        if (index !in utts.indices) return utts to names
        val old = utts[index].speaker
        if (old == target) return utts to names
        val moved = utts.toMutableList().also { it[index] = it[index].copy(speaker = target) }
        // If the source speaker has no lines left, drop its name too.
        val names2 = if (old != null && moved.none { it.speaker == old }) names - old else names
        return renumber(moved, names2)
    }

    fun merge(
        utts: List<TranscriptEvent.Utterance>,
        names: Map<Int, SpeakerName>,
        from: Int,
        into: Int,
    ): Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>> {
        if (from == into) return utts to names
        val merged = utts.map { if (it.speaker == from) it.copy(speaker = into) else it }
        return renumber(merged, names - from)
    }

    /** Compact speaker ids to a contiguous 0..k-1 (sorted), remapping names; no-op if already so. */
    fun renumber(
        utts: List<TranscriptEvent.Utterance>,
        names: Map<Int, SpeakerName>,
    ): Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>> {
        val present = utts.mapNotNull { it.speaker }.distinct().sorted()
        val remap = present.withIndex().associate { (newId, oldId) -> oldId to newId }
        if (remap.all { it.key == it.value }) return utts to names
        val utts2 = utts.map { u -> u.speaker?.let { s -> remap[s]?.let { u.copy(speaker = it) } } ?: u }
        val names2 = names.mapNotNull { (k, v) -> remap[k]?.let { it to v } }.toMap()
        return utts2 to names2
    }
}
