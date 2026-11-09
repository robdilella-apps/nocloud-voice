package com.robd.robvoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/** 16 kHz mono PCM recorder. Audio stays in memory; nothing is written to disk or network. */
class Recorder {
    @Volatile private var running = false
    private var t: Thread? = null
    // Growable 16-bit buffer. Reads copy only the range asked for (the old version copied the whole recording on every preview tick).
    private val lock = Object()
    private var pcm = ShortArray(16000 * 30)
    private var len = 0
    @Volatile var level = 0f; private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        synchronized(lock) { len = 0 }; running = true
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
        ar.startRecording()
        t = thread(name = "rv-rec") {
            val buf = ByteArray(3200)
            while (running) { val n = ar.read(buf, 0, buf.size); if (n > 0) { append(buf, n)
                var peak = 0; var i = 0; while (i + 1 < n) { val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8); peak = maxOf(peak, kotlin.math.abs(v.toShort().toInt())); i += 2 }
                level = peak / 32768f } }
            ar.stop(); ar.release()
        }
    }

    private fun append(b: ByteArray, n: Int) = synchronized(lock) {
        val k = n / 2
        if (len + k > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, len + k))
        for (i in 0 until k) pcm[len + i] = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort()
        len += k
    }
    /** Samples recorded so far. */
    fun samples(): Int = synchronized(lock) { len }
    /** Float copy of samples [from, to). */
    fun range(from: Int, to: Int, pad: Int = 0): FloatArray = synchronized(lock) {
        val a = from.coerceIn(0, len); val e = to.coerceIn(a, len); val out = FloatArray(e - a + pad)
        for (i in a until e) out[i - a] = pcm[i] / 32768f
        out
    }

    /** Stops and returns float samples in [-1,1]. Adds 0.3 s silence tail (helps final words). */
    fun stop(): FloatArray {
        running = false; t?.join(1000); t = null
        return range(0, Int.MAX_VALUE, 4800)
    }
    /** Copy of the last [maxSec] seconds recorded so far (for the live preview). Second value = true if older audio was cut off. */
    fun snapshot(maxSec: Float): Pair<FloatArray, Boolean> {
        val total = samples(); val off = maxOf(0, total - (maxSec * 16000).toInt())
        return range(off, total, 3200) to (off > 0)
    }

    companion object {
        /** Drop long silence at the start/end (keeps 0.25 s lead and 0.4 s tail). Shorter audio = faster final pass. */
        fun trim(x: FloatArray, thr: Float = 0.015f): FloatArray {
            val w = 320; var first = -1; var last = -1; var i = 0
            while (i + w <= x.size) { var pk = 0f; for (k in i until i + w) pk = maxOf(pk, kotlin.math.abs(x[k])); if (pk > thr) { if (first < 0) first = i; last = i + w }; i += w }
            if (first < 0) return x
            val a = maxOf(0, first - 4000); val e = minOf(x.size, last + 6400)
            return if (a == 0 && e == x.size) x else x.copyOfRange(a, e).let { it + FloatArray(1600) }
        }
        /** True if any 20 ms frame is louder than the trim threshold. */
        fun hasSound(x: FloatArray, thr: Float = 0.005f): Boolean { for (v in x) if (kotlin.math.abs(v) > thr) return true; return false }
    }
    val isRunning get() = running
}
