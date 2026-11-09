package com.robd.robvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private val SR = 16000
    /** "Speech" everywhere except 0.6 s pauses at the given seconds. */
    private fun audio(sec: Int, pauses: List<Double>): FloatArray {
        val x = FloatArray(sec * SR) { i -> 0.3f * kotlin.math.sin(i * 0.05f) }
        for (p in pauses) { val a = (p * SR).toInt(); for (i in a until minOf(x.size, a + (0.6 * SR).toInt())) x[i] = 0.0005f }
        return x
    }

    @Test fun shortAudioIsOneChunk() = assertEquals(listOf(0 until 20 * SR), Chunker.split(audio(20, listOf(8.0))))

    @Test fun cutsInsideThePause() {
        val c = Chunker.cut(audio(30, listOf(5.0, 14.0, 23.0)), 0)
        assertTrue("cut at ${c / SR.toFloat()} s", c in (14.0 * SR).toInt()..(14.6 * SR).toInt())
    }

    @Test fun splitCoversEverySampleOnceInOrder() {
        val x = audio(130, listOf(12.0, 27.0, 41.0, 55.5, 69.0, 83.0, 98.0, 112.0, 125.0))
        val r = Chunker.split(x)
        assertEquals(0, r.first().first); assertEquals(x.size - 1, r.last().last)
        for (i in 1 until r.size) assertEquals(r[i - 1].last + 1, r[i].first)
        assertTrue(r.all { it.last - it.first + 1 <= Chunker.MAX })
        for (k in 0 until r.size - 1) assertTrue("boundary ${r[k].last} not in a pause", x[r[k].last] < 0.01f)
    }

    @Test fun noPauseStillTerminates() {
        val x = FloatArray(70 * SR) { 0.3f }
        val r = Chunker.split(x); assertTrue(r.size >= 3); assertEquals(x.size - 1, r.last().last)
    }

    @Test fun joinSkipsEmpty() = assertEquals("a b", Chunker.join(listOf(" a ", "", "b")))
}
