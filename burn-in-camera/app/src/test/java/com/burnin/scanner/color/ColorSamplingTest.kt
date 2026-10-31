package com.burnin.scanner.color

import com.burnin.scanner.analysis.RgbImage
import com.burnin.scanner.analysis.ScreenDetector
import com.burnin.scanner.analysis.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSamplingTest {
    private fun frame(x0: Int, y0: Int, x1: Int, y1: Int, color: Rgb, w: Int = 200, h: Int = 100): RgbImage {
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            r[i] = color.r.toFloat(); g[i] = color.g.toFloat(); b[i] = color.b.toFloat()
        }
        return RgbImage(w, h, r, g, b)
    }

    @Test fun detectsLitScreenAndAveragesItsCenter() {
        val img = frame(120, 20, 180, 80, Rgb(0.4, 0.3, 0.2))
        val q = ColorSampling.detectBrightQuad(img)
        assertNotNull(q)
        assertEquals(120f, q!!.tl.x, 1f); assertEquals(79f, q.br.y, 1f)
        val mean = ColorSampling.meanCenterRgb(img, q)
        assertEquals(0.4, mean.r, 1e-4); assertEquals(0.3, mean.g, 1e-4); assertEquals(0.2, mean.b, 1e-4)
    }

    @Test fun darkFrameHasNoScreen() {
        assertNull(ColorSampling.detectBrightQuad(frame(0, 0, 0, 0, Rgb.ZERO)))
    }

    @Test fun overlapDetectsSameScreenTwice() {
        fun quad(x0: Float, y0: Float, x1: Float, y1: Float) =
            ScreenDetector.Quad(arrayOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1)))
        assertTrue(ColorSampling.overlaps(quad(0f, 0f, 10f, 10f), quad(1f, 1f, 11f, 11f)))
        assertFalse(ColorSampling.overlaps(quad(0f, 0f, 10f, 10f), quad(12f, 0f, 22f, 10f)))
    }
}
