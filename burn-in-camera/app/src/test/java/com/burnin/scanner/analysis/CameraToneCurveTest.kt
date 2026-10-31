package com.burnin.scanner.analysis

import org.junit.Assert.*
import org.junit.Test

class CameraToneCurveTest {
    private val modes = CameraToneCurve.Mode.values()

    @Test fun decoderExactlyInvertsTheRequestedCurve() {
        for (mode in modes) {
            for (i in 0..1000) {
                val x = i / 1000.0
                val y = CameraToneCurve.fromLinear(x, mode)
                assertEquals("$mode x=$x", x, CameraToneCurve.toLinear(y, mode), 1e-9)
            }
        }
    }

    @Test fun contrastCurvePointsAreMonotoneAndSpanUnitRange() {
        val pts = CameraToneCurve.curvePoints()
        assertEquals(2 * CameraToneCurve.POINTS, pts.size)
        assertEquals(0f, pts[0], 0f); assertEquals(0f, pts[1], 0f)
        assertEquals(1f, pts[pts.size - 2], 0f); assertEquals(1f, pts[pts.size - 1], 0f)
        for (k in 1 until CameraToneCurve.POINTS) {
            assertTrue(pts[2 * k] > pts[2 * k - 2])
            assertTrue(pts[2 * k + 1] > pts[2 * k - 1])
        }
    }

    @Test fun piecewiseCurveDiffersFromPurePowerSoTheExactInverseMatters() {
        // HAL은 제어점 사이를 선형 보간한다. 그 곡선을 x^2.2로 되돌리면 저휘도에서 수 % 오차가 난다.
        val y = 0.1 + 0.5 / (CameraToneCurve.POINTS - 1)
        val exact = CameraToneCurve.toLinear(y, CameraToneCurve.Mode.CONTRAST_CURVE)
        val naive = Math.pow(y, CameraToneCurve.GAMMA)
        assertTrue(Math.abs(exact / naive - 1) > 0.005)
        assertTrue(Math.abs(exact / naive - 1) < 0.05)
    }

    @Test fun ratiosOfDimSignalsSurviveTheKnownCurve() {
        // 희소 점 측광: 검은 바탕 낮은 신호에서 −3% 비율이 그대로 복원돼야 한다 (literature-eval B2).
        for (mode in modes) for (peak in listOf(0.02, 0.15, 0.6)) {
            val a = CameraToneCurve.toLinear(CameraToneCurve.fromLinear(peak * 0.97, mode), mode)
            val b = CameraToneCurve.toLinear(CameraToneCurve.fromLinear(peak, mode), mode)
            assertEquals("$mode $peak", -0.03, a / b - 1, 1e-9)
        }
    }
}
