package com.robd.robvoice

import android.content.Context
import java.util.concurrent.ExecutorService

/**
 * Splits long audio at pauses so each decode pass stays short (about 10-20 s).
 * Why: one pass over the whole clip gets slower faster than the clip gets longer (attention cost grows with length),
 * and Moonshine produces repeats/garbage on clips over ~30-60 s. Cutting in the middle of a pause keeps words whole.
 */
object Chunker {
    const val SR = 16000
    private const val FR = 320            // 20 ms frames
    private const val W = 10              // 200 ms window
    const val MAX = 25 * SR               // anything up to 25 s is decoded in one pass
    const val LO = 10 * SR                // a cut is searched 10..20 s after the chunk start
    const val HI = 20 * SR

    /** Absolute sample index of the middle of the quietest (longest-quiet) 200 ms stretch in [from+lo, from+hi). */
    fun cut(x: FloatArray, from: Int, lo: Int = LO, hi: Int = HI, end: Int = x.size): Int {
        val nf = (end - from) / FR
        val a = lo / FR; val b = minOf(hi / FR, nf - W)
        if (b <= a) return minOf(end, from + hi)
        val e = FloatArray(nf)
        for (f in 0 until nf) { var pk = 0f; val s = from + f * FR; for (k in s until s + FR) { val v = kotlin.math.abs(x[k]); if (v > pk) pk = v }; e[f] = pk }
        val sums = FloatArray(b - a)
        var run = 0f; for (k in 0 until W) run += e[a + k]
        for (i in sums.indices) { sums[i] = run; if (i + 1 < sums.size) run += e[a + i + W] - e[a + i] }
        var m = Float.MAX_VALUE; for (s in sums) if (s < m) m = s
        val thr = m + 0.002f * W
        var bestLen = 0; var bestStart = 0; var i = 0
        while (i < sums.size) {
            if (sums[i] <= thr) { var j = i; while (j < sums.size && sums[j] <= thr) j++; if (j - i > bestLen) { bestLen = j - i; bestStart = i }; i = j } else i++
        }
        return from + (a + bestStart + bestLen / 2 + W / 2) * FR
    }

    /** [start, end) ranges covering x, each <= MAX except where no audio is left to cut. */
    fun split(x: FloatArray, maxLen: Int = MAX): List<IntRange> {
        val out = ArrayList<IntRange>(); var st = 0
        while (x.size - st > maxLen) { val c = cut(x, st); out += st until c; st = c }
        out += st until x.size
        return out
    }

    /** Joins chunk texts with single spaces, skipping empty ones. */
    fun join(parts: List<String>) = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * One dictation. While recording, every finished ~10-20 s stretch (cut at a pause) is decoded in the background,
 * so when you stop only the last stretch is left to decode. All jobs run in order on the single shared worker.
 */
class Pipeline(private val ctx: Context, private val rec: Recorder, private val worker: ExecutorService) {
    @Volatile var dead = false
    private var queued = 0                                   // samples already handed to the decoder (main thread only)
    private val parts = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile private var error: Throwable? = null

    /** Call on the main thread while recording (cheap when there's nothing to do). */
    fun poll() {
        if (dead || !rec.isRunning) return
        val total = rec.samples()
        if (total - queued < Chunker.MAX) return
        val a = rec.range(queued, total)
        val c = Chunker.cut(a, 0)
        val seg = a.copyOf(c + 4800)                            // 0.3 s silence tail helps the last word
        queued += c
        worker.execute { if (!dead && error == null) try { parts += Asr.transcribe(ctx, seg) } catch (t: Throwable) { error = t } }
    }

    /** Seconds recorded so far. */
    val seconds get() = rec.samples() / Chunker.SR.toFloat()

    /** Main thread: stop the mic and return the audio not yet queued (silence-trimmed), or null if nothing was said at all. */
    fun stopAndRemainder(): FloatArray? {
        val all = rec.stop()                                     // includes a 0.3 s silent tail
        if (queued == 0 && all.size - 4800 < Chunker.SR / 2) return null
        val rest = if (queued == 0) all else all.copyOfRange(minOf(queued, all.size), all.size)
        return Recorder.trim(rest)
    }

    /** Worker thread. Runs after every queued chunk (the worker is single-threaded). Returns the raw joined text. */
    fun finishOnWorker(rest: FloatArray): String {
        error?.let { throw it }
        val tail = if (!Recorder.hasSound(rest)) emptyList() else Chunker.split(rest).map { r ->
            val seg = rest.copyOfRange(r.first, r.last + 1)
            Asr.transcribe(ctx, if (r.last + 1 < rest.size) seg + FloatArray(4800) else seg) }
        return Chunker.join(synchronized(parts) { parts.toList() } + tail)
    }
}
