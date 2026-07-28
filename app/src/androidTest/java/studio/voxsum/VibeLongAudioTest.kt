package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.VibeLiteEngine
import studio.voxsum.core.asr.moss.MossWindower
import java.io.File

/**
 * VibeVoice over a long clip, with and without the MOSS-style windowing, so the
 * saving is measured rather than assumed.
 *
 * Fixed slicing transcribes every 10 s window including silence, and cuts mid-word
 * at each boundary — a model with no cross-window state cannot recover from that.
 * Pause-snapping plus a silence gate should cost less AND read better.
 *
 *   scripts/test-on-device.sh <serial> -- -e class studio.voxsum.VibeLongAudioTest -e bench 1
 */
@RunWith(AndroidJUnit4::class)
class VibeLongAudioTest {

    @Test fun windowingSavesTimeOnLongAudio() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("opt-in — pass -e bench 1", args.getString("bench") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val benchDir = File(args.getString("benchDir") ?: "/data/local/tmp/bench")
        val lang = args.getString("lang") ?: "en"
        val wav = File(benchDir, "${lang}_5min.wav")
        Assume.assumeTrue("push ${wav.name} to $benchDir", wav.exists())

        // The encoder window is baked into the export, so the graph and the slicing
        // have to agree. A longer window amortizes the chat template, which is a
        // FIXED 50 tokens per window against 7.5 audio frames per second.
        val winSecs = args.getString("win")?.toInt() ?: 10
        val vd = File(args.getString("vibeDir") ?: "/data/local/tmp/vibe_engine")
        // The decode and prefill graphs are context-specific and must be a matched
        // pair. A smaller ctx is worth testing because per-token cost tracks
        // position, so some of the work may scale with the graph's ctx too.
        val decName = args.getString("dec") ?: "decoder_28L_512_c.tflite"
        val preName = args.getString("pre") ?: "prefill_512_t16_c.tflite"

        // -e warm 1 builds and drops a throwaway engine first, so the XNNPACK weight
        // cache is populated before the engine under measurement is built. The
        // harness uninstalls the test build after every run, wiping app.cacheDir, so
        // without this every measurement pays a COLD cache — 231 MB of anonymous
        // memory to pack the head alone. Production keeps the cache across launches,
        // so the warm number is the one users actually live with.
        if (args.getString("warm") == "1") {
            VibeLiteEngine.create(
                encoder = File(vd, "vibe_front_${winSecs}s_q8.tflite"),
                decoder = File(vd, decName),
                head = File(vd, "head_q8.tflite"),
                weightsDir = File(vd, "weights"),
                manifest = File(vd, "dec_28L_manifest.txt"),
                embeddingTable = File(vd, "embd_table.bin"),
                vocabJson = File(vd, "vocab.json"),
                prefill = File(vd, preName).takeIf { it.exists() },
                xnnCacheDir = File(app.cacheDir, "xnnpack"),
                threads = 4,
            )?.close()
        }

        val engine = VibeLiteEngine.create(
            encoder = File(vd, "vibe_front_${winSecs}s_q8.tflite"),
            decoder = File(vd, decName),
            head = File(vd, "head_q8.tflite"),
            weightsDir = File(vd, "weights"),
            manifest = File(vd, "dec_28L_manifest.txt"),
            embeddingTable = File(vd, "embd_table.bin"),
            vocabJson = File(vd, "vocab.json"),
            prefill = File(vd, preName).takeIf { it.exists() },
            // app.cacheDir, because the app must be able to WRITE it. Pointing this
            // at /data/local/tmp looks appealing (it survives the harness uninstall)
            // but the app has no write permission there, the cache silently does not
            // happen, and peak RssAnon goes 906 MB -> 1552 MB.
            xnnCacheDir = File(app.cacheDir, "xnnpack"),
            threads = 4,
        )
        assertTrue("engine failed to load", engine != null)

        // Only the first 60 s: a full 5 minutes is ~30 windows at tens of seconds
        // each, and the ratio between the two strategies is what matters here.
        val secs = args.getString("seconds")?.toInt() ?: 60
        val pcm = readWav16k(wav).let { it.copyOf(minOf(it.size, secs * 16_000)) }
        Log.i(TAG, "$lang: ${pcm.size / 16_000.0}s")

        engine!!.use { e ->
            val fixed = run(e, pcm, snap = false, winSecs = winSecs)
            Log.i(TAG, "FIXED    windows=${fixed.windows} skipped=${fixed.skipped} " +
                "wall=${"%.1f".format(fixed.wall)}s")
            Log.i(TAG, "  ${fixed.text.take(300)}")

            if (args.getString("arms") == "fixed") return@use
            val snapped = run(e, pcm, snap = true, winSecs = winSecs)
            Log.i(TAG, "SNAPPED  windows=${snapped.windows} skipped=${snapped.skipped} " +
                "wall=${"%.1f".format(snapped.wall)}s  " +
                "(${"%.2f".format(fixed.wall / snapped.wall)}x)")
            Log.i(TAG, "  ${snapped.text.take(300)}")
        }
    }

    private data class Res(val text: String, val wall: Double, val windows: Int, val skipped: Int)

    private fun run(e: VibeLiteEngine, pcm: FloatArray, snap: Boolean, winSecs: Int): Res {
        val win = winSecs * 16_000
        val sb = StringBuilder()
        var s = 0
        var windows = 0
        var skipped = 0
        val t0 = System.nanoTime()
        while (s < pcm.size) {
            val piece = pcm.copyOfRange(s, minOf(s + win, pcm.size))
            val used = if (!snap) piece else {
                val cut = (MossWindower.pauseCut(piece, winSecs, 16_000, snapSeconds = 2.0) * 16_000)
                    .toInt().coerceIn(1, piece.size)
                if (cut < piece.size) piece.copyOfRange(0, cut) else piece
            }
            if (snap && MossWindower.isSilentStrict(used)) {
                skipped++
            } else {
                sb.append(e.transcribeWindow(used).trim()).append(' ')
                windows++
                // Total wall clock cannot separate a slow front end from slow
                // generation, and the two want completely different fixes.
                val st = e.lastStats()
                Log.i(TAG, "  w$windows ${"%.1f".format(used.size / 16_000.0)}s  " +
                    "enc=${"%.1f".format(st.encodeSec)}s " +
                    "pre=${"%.1f".format(st.prefillSec)}s/${st.promptTokens}tok " +
                    "dec=${"%.1f".format(st.decodeSec)}s/${st.generatedTokens}tok")
            }
            s += used.size
        }
        return Res(sb.toString().trim(), (System.nanoTime() - t0) / 1e9, windows, skipped)
    }

    private fun readWav16k(f: File): FloatArray {
        val bytes = f.readBytes()
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val sz = (bytes[p+4].toInt() and 0xff) or ((bytes[p+5].toInt() and 0xff) shl 8) or
                ((bytes[p+6].toInt() and 0xff) shl 16) or ((bytes[p+7].toInt() and 0xff) shl 24)
            if (id == "data") {
                val n = sz / 2
                return FloatArray(n) { i ->
                    val lo = bytes[p+8+i*2].toInt() and 0xff
                    val hi = bytes[p+9+i*2].toInt()
                    ((hi shl 8) or lo).toShort() / 32768f
                }
            }
            p += 8 + sz + (sz and 1)
        }
        error("no data chunk")
    }

    private companion object { const val TAG = "VibeLongAudio" }
}
