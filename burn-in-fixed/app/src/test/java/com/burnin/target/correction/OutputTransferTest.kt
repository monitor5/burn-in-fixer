package com.burnin.target.correction

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class OutputTransferTest {
    @Test fun codeGainInvertsPanelGammaSoLuminanceGainIsExact() {
        for (g in listOf(1.0, .96, .9, .5)) {
            val code = OutputTransfer.codeGain(g, 2.2)
            assertEquals(g, code.pow(2.2), 1e-12)
        }
        assertEquals(0.0, OutputTransfer.codeGain(0.0, 2.2), 0.0)
        assertEquals(1.0, OutputTransfer.codeGain(1.5, 2.2), 0.0)
    }

    @Test fun overlayAlphaMakesCodeSpaceBlendHitLuminanceGain() {
        val a = OutputTransfer.overlayAlpha(.9, 2.2)
        assertEquals(.9, (1 - a).pow(2.2), 1e-12)
        assertEquals(0.0, OutputTransfer.overlayAlpha(1.0, 2.2), 0.0)
    }

    @Test fun invalidGammaFallsBackToDefault() {
        assertEquals(2.2, OutputTransfer.sanitizeGamma(null), 0.0)
        assertEquals(2.2, OutputTransfer.sanitizeGamma(Double.NaN), 0.0)
        assertEquals(2.2, OutputTransfer.sanitizeGamma(1.0), 0.0)
        assertEquals(2.2, OutputTransfer.sanitizeGamma(5.0), 0.0)
        assertEquals(2.0, OutputTransfer.sanitizeGamma(2.0), 0.0)
        assertEquals(OutputTransfer.codeGain(.9, 2.2), OutputTransfer.codeGain(.9, 9.0), 0.0)
    }

    private fun dithered(w: Int, h: Int, value: (Int) -> Double): IntArray {
        val out = IntArray(w * h) { -1 }
        OutputTransfer.dither(w, h, 255, value) { i, v -> out[i] = v }
        return out
    }

    @Test fun ditherPreservesMeanBelowOneCodeStep() {
        // gray25(64)에 1.5% 휘도 감쇠: 코드 63.56 — 반올림만 하면 64(감쇠 0)로 사라진다
        val target = 64 * OutputTransfer.codeGain(.985, 2.2)
        val out = dithered(64, 64) { target }
        assertTrue(out.all { it == 63 || it == 64 })
        assertEquals(target, out.average(), .01)
        assertNotEquals(64.0, out.average(), .1)
    }

    @Test fun ditherIsDeterministicClampedAndExactForIntegers() {
        val a = dithered(17, 9) { i -> (i * 37 % 300).toDouble() - 20.3 }
        val b = dithered(17, 9) { i -> (i * 37 % 300).toDouble() - 20.3 }
        assertArrayEquals(a, b)
        assertTrue(a.all { it in 0..255 })
        assertTrue(dithered(5, 4) { 179.0 }.all { it == 179 })
    }
}
