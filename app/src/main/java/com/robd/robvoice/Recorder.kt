package com.robd.robvoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** 16 kHz mono PCM recorder. Audio stays in memory; nothing is written to disk or network. */
class Recorder {
    @Volatile private var running = false
    private var t: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile var level = 0f; private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        pcm.reset(); running = true
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
        ar.startRecording()
        t = thread(name = "rv-rec") {
            val buf = ByteArray(3200)
            while (running) { val n = ar.read(buf, 0, buf.size); if (n > 0) { synchronized(pcm) { pcm.write(buf, 0, n) }
                var peak = 0; var i = 0; while (i + 1 < n) { val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8); peak = maxOf(peak, kotlin.math.abs(v.toShort().toInt())); i += 2 }
                level = peak / 32768f } }
            ar.stop(); ar.release()
        }
    }

    /** Stops and returns float samples in [-1,1]. Adds 0.3 s silence tail (helps final words). */
    fun stop(): FloatArray {
        running = false; t?.join(1000); t = null
        val b = synchronized(pcm) { pcm.toByteArray() }
        val n = b.size / 2; val out = FloatArray(n + 4800)
        for (i in 0 until n) out[i] = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        return out
    }
    /** Copy of the last [maxSec] seconds recorded so far (for the live preview). Second value = true if older audio was cut off. */
    fun snapshot(maxSec: Float): Pair<FloatArray, Boolean> {
        val b = synchronized(pcm) { pcm.toByteArray() }
        val total = b.size / 2; val n = minOf(total, (maxSec * 16000).toInt()); val off = total - n
        val out = FloatArray(n + 3200)
        for (i in 0 until n) { val j = 2 * (off + i); out[i] = ((b[j].toInt() and 0xff) or (b[j + 1].toInt() shl 8)).toShort() / 32768f }
        return out to (off > 0)
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
    }
    val isRunning get() = running
}
