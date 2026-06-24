package studio.voxsum.core.export

import studio.voxsum.core.events.TranscriptEvent

/**
 * Transcript/summary exporters — Kotlin port of src/export_utils.py. Pure string logic
 * (JVM-unit-testable, no Android deps). Operates on [TranscriptEvent.Utterance]; a null
 * speaker means "no diarization", matching the Python `utterances_with_speakers=None` path.
 */
object TranscriptExport {

    enum class Format(val ext: String, val mime: String) {
        SRT(".srt", "text/plain"),
        VTT(".vtt", "text/vtt"),
        ASS(".ass", "text/plain"),
        JSON(".json", "application/json"),
        EAF(".eaf", "application/xml"),
        TXT(".txt", "text/plain"),
    }

    private data class Row(val start: Double, val end: Double, val text: String, val speaker: Int)

    private fun rows(utts: List<TranscriptEvent.Utterance>) =
        utts.map { Row(it.startSec, it.endSec, it.text, it.speaker ?: 0) }

    private fun hasSpeakers(utts: List<TranscriptEvent.Utterance>) = utts.any { it.speaker != null }

    /**
     * Quantize to the format's display precision BEFORE splitting into h/m/s, so a value
     * that rounds up to a whole second carries correctly (avoids an invalid ":60" field,
     * e.g. 59.9996s -> 00:01:00,000). Faithful to export_utils.format_timestamp.
     */
    fun formatTimestamp(seconds: Double, type: String): String {
        val s = if (seconds < 0) 0.0 else seconds
        return when (type) {
            "srt", "vtt" -> {
                val totalMs = Math.round(s * 1000)
                val sep = if (type == "srt") "," else "."
                "%02d:%02d:%02d%s%03d".format(
                    totalMs / 3_600_000, (totalMs / 60_000) % 60, (totalMs / 1000) % 60, sep, totalMs % 1000
                )
            }
            "ass" -> {
                val totalCs = Math.round(s * 100)
                "%d:%02d:%02d.%02d".format(
                    totalCs / 360_000, (totalCs / 6_000) % 60, (totalCs / 100) % 60, totalCs % 100
                )
            }
            else -> { // tenths of a second
                val totalDs = Math.round(s * 10)
                "%02d:%02d:%02d.%01d".format(
                    totalDs / 36_000, (totalDs / 600) % 60, (totalDs / 10) % 60, totalDs % 10
                )
            }
        }
    }

    fun export(format: Format, utts: List<TranscriptEvent.Utterance>): String = when (format) {
        Format.SRT -> srt(utts)
        Format.VTT -> vtt(utts)
        Format.ASS -> ass(utts)
        Format.JSON -> json(utts)
        Format.EAF -> eaf(utts)
        Format.TXT -> plainText(utts, includeTimestamps = true)
    }

    fun srt(utts: List<TranscriptEvent.Utterance>): String {
        val spk = hasSpeakers(utts)
        val out = StringBuilder()
        rows(utts).forEachIndexed { i, r ->
            val prefix = if (spk) "Speaker ${r.speaker + 1}: " else ""
            out.append(i + 1).append('\n')
            out.append("${formatTimestamp(r.start, "srt")} --> ${formatTimestamp(r.end, "srt")}\n")
            out.append("$prefix${r.text}\n\n")
        }
        return out.toString().trimEnd('\n')
    }

    fun vtt(utts: List<TranscriptEvent.Utterance>): String {
        val spk = hasSpeakers(utts)
        val out = StringBuilder("WEBVTT\n\n")
        rows(utts).forEach { r ->
            val prefix = if (spk) "Speaker ${r.speaker + 1}: " else ""
            out.append("${formatTimestamp(r.start, "vtt")} --> ${formatTimestamp(r.end, "vtt")}\n")
            out.append("$prefix${r.text}\n\n")
        }
        return out.toString().trimEnd('\n')
    }

    fun ass(utts: List<TranscriptEvent.Utterance>): String {
        val spk = hasSpeakers(utts)
        val header = """
            [Script Info]
            Title: VoxSum Transcript
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text

        """.trimIndent() + "\n"
        val events = rows(utts).joinToString("\n") { r ->
            val prefix = if (spk) "Speaker ${r.speaker + 1}: " else ""
            "Dialogue: 0,${formatTimestamp(r.start, "ass")},${formatTimestamp(r.end, "ass")},Default,,0,0,0,,$prefix${r.text}"
        }
        return header + events
    }

    fun plainText(utts: List<TranscriptEvent.Utterance>, includeTimestamps: Boolean): String {
        val spk = hasSpeakers(utts)
        val lines = ArrayList<String>()
        var current: Int? = null
        rows(utts).forEach { r ->
            if (spk && r.speaker != current) {
                if (lines.isNotEmpty()) lines.add("")
                lines.add("Speaker ${r.speaker + 1}:")
                current = r.speaker
            }
            val ts = if (includeTimestamps) "[${formatTimestamp(r.start, "default")}] " else ""
            lines.add("$ts${r.text}")
        }
        return lines.joinToString("\n")
    }

    fun json(utts: List<TranscriptEvent.Utterance>): String {
        val spk = hasSpeakers(utts)
        val rows = rows(utts)
        val speakers = if (spk) rows.map { it.speaker }.toSet().size else 1
        val items = rows.joinToString(",\n") { r ->
            """    {
      "start": ${r.start},
      "end": ${r.end},
      "duration": ${r.end - r.start},
      "text": ${jsonStr(r.text)},
      "speaker_id": ${r.speaker},
      "speaker_label": "Speaker ${r.speaker + 1}"
    }"""
        }
        return """{
  "metadata": {
    "source": "VoxSum",
    "format_version": "1.0",
    "speakers_detected": $speakers
  },
  "utterances": [
$items
  ]
}"""
    }

    /** @param date document date (injected for testability; Python used datetime.now()). */
    fun eaf(utts: List<TranscriptEvent.Utterance>, date: String = ""): String {
        val rows = rows(utts)
        val speakers = rows.map { it.speaker }.toSortedSet()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<ANNOTATION_DOCUMENT AUTHOR=\"VoxSum\" DATE=\"$date\" FORMAT=\"3.0\" VERSION=\"3.0\">\n")
        sb.append("    <HEADER MEDIA_FILE=\"\" TIME_UNITS=\"milliseconds\">\n")
        sb.append("        <PROPERTY NAME=\"URN\">urn:nl-mpi-tools-elan-eaf:voxsum-transcript</PROPERTY>\n")
        sb.append("        <PROPERTY NAME=\"lastUsedAnnotationId\">${rows.size}</PROPERTY>\n")
        sb.append("    </HEADER>\n    <TIME_ORDER>\n")
        var timeId = 1
        for (r in rows) {
            sb.append("        <TIME_SLOT TIME_SLOT_ID=\"ts$timeId\" TIME_VALUE=\"${(r.start * 1000).toLong()}\"/>\n"); timeId++
            sb.append("        <TIME_SLOT TIME_SLOT_ID=\"ts$timeId\" TIME_VALUE=\"${(r.end * 1000).toLong()}\"/>\n"); timeId++
        }
        sb.append("    </TIME_ORDER>\n")
        // Document-wide unique annotation id; XML-escape text.
        var annId = 1
        for (sp in speakers) {
            sb.append("    <TIER LINGUISTIC_TYPE_REF=\"default-lt\" TIER_ID=\"Speaker_${sp + 1}\">\n")
            var tId = 1
            for (r in rows) {
                if (r.speaker == sp) {
                    sb.append("        <ANNOTATION>\n")
                    sb.append("            <ALIGNABLE_ANNOTATION ANNOTATION_ID=\"a$annId\" TIME_SLOT_REF1=\"ts$tId\" TIME_SLOT_REF2=\"ts${tId + 1}\">\n")
                    sb.append("                <ANNOTATION_VALUE>${xmlEscape(r.text)}</ANNOTATION_VALUE>\n")
                    sb.append("            </ALIGNABLE_ANNOTATION>\n        </ANNOTATION>\n")
                    annId++
                }
                tId += 2
            }
            sb.append("    </TIER>\n")
        }
        sb.append("""    <LINGUISTIC_TYPE GRAPHIC_REFERENCES="false" LINGUISTIC_TYPE_ID="default-lt" TIME_ALIGNABLE="true"/>
    <CONSTRAINT DESCRIPTION="Time subdivision of parent annotation's time interval, no time gaps allowed within this interval" STEREOTYPE="Time_Subdivision"/>
    <CONSTRAINT DESCRIPTION="Symbolic subdivision of a parent annotation. Annotations refering to the same parent are ordered" STEREOTYPE="Symbolic_Subdivision"/>
    <CONSTRAINT DESCRIPTION="1-1 association with a parent annotation" STEREOTYPE="Symbolic_Association"/>
    <CONSTRAINT DESCRIPTION="Time alignable annotations within the parent annotation's time interval, gaps are allowed" STEREOTYPE="Included_In"/>
</ANNOTATION_DOCUMENT>""")
        return sb.toString()
    }

    fun summaryPlain(summary: String, title: String? = null): String =
        if (title != null) "$title\n\n$summary" else summary

    fun summaryMarkdown(summary: String, title: String? = null): String {
        val sb = StringBuilder()
        if (title != null) {
            sb.append("# Summary\n\n**Title:** $title\n\n## Content\n\n")
        }
        sb.append(summary)
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> b.append("\\\\"); '"' -> b.append("\\\"")
            '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
