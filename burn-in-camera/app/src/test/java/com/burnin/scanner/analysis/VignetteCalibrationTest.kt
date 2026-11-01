package com.burnin.scanner.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class VignetteCalibrationTest {
    private val truth = VignetteCalibration.Model(-0.45, 0.08, 0.0)

    /** 화면 번인: 왼쪽 1/3이 12% 어둡고 아래 절반이 6% 밝다(UI 모양 번인). */
    private fun screen(bx: Int, by: Int, bw: Int, bh: Int): Double =
        (if (bx < bw / 3) 0.88 else 1.0) * (if (by >= bh / 2) 1.06 else 1.0)

    /** 화면을 카메라 프레임 세로 [offsetY] 위치에 놓았을 때 블록 측정값과 카메라 좌표. */
    private fun position(offsetY: Double, bw: Int, bh: Int, noise: Double = 0.0, seed: Int = 0): VignetteCalibration.Position {
        val rnd = java.util.Random(seed.toLong())
        val n = bw * bh
        val v = FloatArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val i = by * bw + bx
            // 화면이 프레임 가로 70%, 세로 50%를 차지한다고 보고 정규화 좌표로 배치
            val x = -0.7 + 1.4 * (bx + 0.5) / bw
            val y = offsetY - 0.35 + 0.7 * (by + 0.5) / bh
            xs[i] = x.toFloat(); ys[i] = y.toFloat()
            v[i] = (0.6 * screen(bx, by, bw, bh) * truth.gain(x, y) * (1 + noise * rnd.nextGaussian())).toFloat()
        }
        return VignetteCalibration.Position(v, xs, ys)
    }

    @Test fun recoversCameraFalloffFromThreeVerticalPositionsDespiteBurnIn() {
        val bw = 24
        val bh = 18
        val r = VignetteCalibration.fit(listOf(position(-0.3, bw, bh), position(0.3, bw, bh), position(0.0, bw, bh)))
        for ((x, y) in listOf(0.0 to 0.0, 0.5 to 0.2, 0.8 to 0.5, -0.6 to -0.5)) {
            assertEquals("gain at $x,$y", truth.gain(x, y), r.model.gain(x, y), 0.01)
        }
        assertTrue(r.rmsResidual < 1e-3)
    }

    @Test fun toleratesMeasurementNoise() {
        val bw = 24
        val bh = 18
        val r = VignetteCalibration.fit(listOf(
            position(-0.3, bw, bh, 0.004, 1), position(0.3, bw, bh, 0.004, 2), position(0.0, bw, bh, 0.004, 3),
        ))
        assertEquals(truth.gain(0.8, 0.5), r.model.gain(0.8, 0.5), 0.02)
        assertEquals(exp(truth.logGain(0.6, 0.0)), r.model.gain(0.6, 0.0), 0.015)
    }
}
