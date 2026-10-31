package com.robd.robvoice

import org.junit.Assert.assertTrue
import org.junit.Test

class TrimTest {
    @Test fun trimsLongSilence() {
        val x = FloatArray(16000 * 6); for (i in 16000 * 2 until 16000 * 3) x[i] = 0.3f
        val y = Recorder.trim(x)
        assertTrue("len ${y.size}", y.size in 16000..16000 * 2)
    }
    @Test fun keepsAllSilent() { val x = FloatArray(16000); assertTrue(Recorder.trim(x).size == x.size) }
}
