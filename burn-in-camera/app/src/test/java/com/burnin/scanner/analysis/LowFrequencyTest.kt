package com.burnin.scanner.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowFrequencyTest {
    private val gw = 96
    private val gh = 72

    /** 중앙에서 멀수록 어두워지는 원형(카메라 비네팅) 성분. */
    private fun ring(x: Int, y: Int): Float {
        val dx = (x + 0.5f) / gw - 0.5f
        val dy = (y + 0.5f) / gh - 0.5f
        return 1f - 0.6f * (dx * dx + dy * dy)
    }

    /** 화면 쪽 번인: 왼쪽 위 사각형이 8% 어둡다. */
    private fun burn(x: Int, y: Int): Float = if (x < gw / 3 && y < gh / 3) 0.92f else 1f

    @Test fun cameraResidualCancelsScreenContentAndKeepsOnlyTheRing() {
        val main = FloatArray(gw * gh) { burn(it % gw, it / gw) * ring(it % gw, it / gw) }
        val reference = FloatArray(gw * gh) { burn(it % gw, it / gw) * 0.7f } // 평평한 기준 카메라(밝기 배율만 다름)
        val residual = LowFrequency.cameraResidual(main, LowFrequency.blocks(reference, gw, gh, 12), gw, gh)
        val up = LowFrequency.upsample(residual, gw, gh)
        val corrected = FloatArray(main.size) { main[it] / up[it] }
        // 원형을 지우면 번인 비율(0.92)만 남아야 한다
        val inside = corrected[(gh / 6) * gw + gw / 6]
        val outside = corrected[(gh * 5 / 6) * gw + gw * 5 / 6]
        val center = corrected[(gh / 2) * gw + gw / 2]
        assertEquals(0.92f, inside / center, 0.02f)
        assertEquals(1f, outside / center, 0.02f)
    }

    @Test fun chromaGainsEqualizeChannelRatiosWithinCap() {
        // 오른쪽 절반은 빨강이 6% 강한 분홍빛
        val red = FloatArray(gw * gh) { if (it % gw >= gw / 2) 1.06f else 1f }
        val green = FloatArray(gw * gh) { 1f }
        val blue = FloatArray(gw * gh) { 1f }
        val chroma = LowFrequency.chroma(red, green, blue, gw, gh, 12)
        assertTrue(chroma.p95Spread() > 0.04f)
        val (gr, gg, gb) = LowFrequency.chromaGains(chroma, 0.10f)
        val correctedRed = FloatArray(red.size) { red[it] * LowFrequency.upsample(gr, gw, gh)[it] }
        val ug = LowFrequency.upsample(gg, gw, gh)
        val ub = LowFrequency.upsample(gb, gw, gh)
        val after = LowFrequency.chroma(correctedRed, FloatArray(gw * gh) { ug[it] }, FloatArray(gw * gh) { ub[it] }, gw, gh, 12)
        assertTrue("before ${chroma.meanSpread()} after ${after.meanSpread()}", after.meanSpread() < chroma.meanSpread() * 0.5f)
        for (g in gr.values + gg.values + gb.values) assertTrue(g in 0.9f..1f)
    }
}
