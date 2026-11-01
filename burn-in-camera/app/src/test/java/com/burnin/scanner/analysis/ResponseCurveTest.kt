package com.burnin.scanner.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseCurveTest {
    private fun blocks(vararg v: Float) = LowFrequency.Blocks(v.size, 1, v)

    @Test fun burnInThatDeepensAtLowLevelsIsWeightedIntoTheTarget() {
        // 블록 1은 gray70에서 5%, 어두운 레벨에서 15% 처지는 번인
        val l70 = blocks(1f, 0.95f, 1f)
        val l25 = blocks(1f, 0.85f, 1f)
        val eff = ResponseCurve.effective(listOf(l25, l70), floatArrayOf(1f, 1f))
        assertEquals(Math.sqrt(0.95 * 0.85).toFloat(), eff.values[1], 1e-5f)
        val q = ResponseCurve.compensation(l70, eff)
        // gray70을 q로 나누면 곡선 전체로 본 밝기가 된다 → 반복 루프가 gray70 기준보다 더 깊게 보정한다
        assertEquals(eff.values[1], l70.values[1] / q.values[1], 1e-5f)
        assertEquals(1f, q.values[0], 1e-6f)
    }

    @Test fun compensationIsClampedAndMissingBlocksStayNeutral() {
        val ref = blocks(1f, 1.5f, Float.NaN)
        val eff = blocks(1f, 1f, 1f)
        val q = ResponseCurve.compensation(ref, eff)
        assertEquals(1.15f, q.values[1], 1e-6f)
        assertEquals(1f, q.values[2], 0f)
    }

    @Test fun divideInterpolatesBlockCompensation() {
        val q = LowFrequency.Blocks(2, 2, floatArrayOf(1f, 1f, 1f, 1f))
        val grid = FloatArray(16) { 0.5f }
        val out = ResponseCurve.divide(grid, q, 4, 4)
        assertEquals(0.5f, out[5], 1e-6f)
    }
}
