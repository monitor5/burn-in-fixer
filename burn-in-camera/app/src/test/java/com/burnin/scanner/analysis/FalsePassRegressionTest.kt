package com.burnin.scanner.analysis

import com.burnin.scanner.measure.MeasurementPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 거짓 합격 회귀: 세로 폰 화면의 상태바·내비바 번인(면적 < 10%)이
 * (A) 방사형 flat-field에 흡수되거나, (B) p10 목표 때문에 보정되지 않거나,
 * (C) 셀 단위 RMS/P95에 가려져 "합격"으로 보고되던 경로를 고정한다.
 * 패널 휘도(ground truth)와 앱 측정을 분리해 실제 화면이 균일해졌는지 검사한다.
 */
class FalsePassRegressionTest {
    private val gw = 54
    private val gh = 120
    private val maxAtt = 0.10f

    /** 상단 4%(상태바)·하단 5%(내비바) 띠가 depth만큼 어두운 패널 */
    private fun bandPanel(depth: Float) = FloatArray(gw * gh) { i ->
        val y = i / gw
        if (y < gh * 0.04f || y >= gh * 0.95f) 1f - depth else 1f
    }

    private fun isBand(i: Int): Boolean {
        val y = i / gw
        return y < gh * 0.04f || y >= gh * 0.95f
    }

    /** 카메라 비네팅 1 - k·ρ² (화면 중심 기준) */
    private fun vignette(k: Float) = FloatArray(gw * gh) { i ->
        val cx = (gw - 1) * 0.5f
        val cy = (gh - 1) * 0.5f
        val dx = i % gw - cx
        val dy = i / gw - cy
        1f - k * (dx * dx + dy * dy) / (cx * cx + cy * cy)
    }

    private fun bandContrast(grid: FloatArray): Float {
        var band = 0.0
        var nb = 0
        val rest = ArrayList<Float>()
        for (i in grid.indices) if (isBand(i)) { band += grid[i]; nb++ } else rest += grid[i]
        rest.sort()
        return (band / nb).toFloat() / rest[rest.size / 2] - 1f
    }

    private fun times(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] * b[it] }

    @Test fun flatFieldKeepsNarrowBandBurnInOnTallScreen() {
        val measured = times(bandPanel(0.04f), vignette(0.15f))
        val corrected = Analyzer.applyFlatField(measured, Analyzer.radialFlatField(measured, gw, gh))
        val contrast = bandContrast(corrected)
        assertTrue("띠 번인이 flat-field에 흡수됨: $contrast", contrast < -0.034f)
    }

    @Test fun flatFieldStillRemovesVignettingFromCleanScreen() {
        val measured = vignette(0.15f)
        val before = Analyzer.blockMaxDev(measured, gw, gh)
        val corrected = Analyzer.applyFlatField(measured, Analyzer.radialFlatField(measured, gw, gh))
        val after = Analyzer.blockMaxDev(corrected, gw, gh)
        assertTrue("비네팅 잔여: before=$before after=$after", after < 0.003f && before > 0.05f)
    }

    @Test fun correctionTargetFollowsNarrowDarkBandAndIsBoundedByMaxAttenuation() {
        assertEquals(0.96f, Analyzer.correctionTarget(bandPanel(0.04f), gw, gh, maxAtt), 1e-4f)
        // 정상 영역을 maxAtt보다 더 낮추는 목표는 만들지 않는다.
        assertEquals(0.90f, Analyzer.correctionTarget(bandPanel(0.30f), gw, gh, maxAtt), 1e-4f)
        assertEquals(1f, Analyzer.correctionTarget(bandPanel(0.04f), gw, gh, 0f), 0f)
    }

    @Test fun cellMetricsMissNarrowBandButBlockMetricDoesNot() {
        val panel = bandPanel(0.03f)
        val stats = Analyzer.stats(panel)
        val block = Analyzer.blockMaxDev(panel, gw, gh)
        assertTrue("기존 지표로는 합격: $stats", MeasurementPolicy.score(stats, 0.015f, 0.035f) <= 1f)
        assertEquals(0.03f, block, 1e-4f)
        assertTrue(MeasurementPolicy.score(stats, 0.015f, 0.035f, block, 0.02f) > 1f)
    }

    @Test fun narrowBandBurnInIsActuallyCorrectedOnThePanel() {
        val panel = bandPanel(0.04f)
        val measured = times(panel, vignette(0.15f))
        val luma = Analyzer.applyFlatField(measured, Analyzer.radialFlatField(measured, gw, gh))
        var gain = Analyzer.gainGrid(luma, gw, gh, maxAtt, smoothRadius = 0)
        val target = Analyzer.correctionTarget(luma, gw, gh, maxAtt)
        repeat(4) {
            val shown = times(times(panel, gain), vignette(0.15f))
            val after = Analyzer.applyFlatField(shown, Analyzer.radialFlatField(measured, gw, gh))
            gain = Analyzer.refineGain(gain, after, target, 0.3f, gw, gh, maxAtt, smoothRadius = 0)
        }
        val truthBefore = Analyzer.blockMaxDev(panel, gw, gh)
        val truthAfter = Analyzer.blockMaxDev(times(panel, gain), gw, gh)
        assertTrue("실제 패널 개선 부족: $truthBefore -> $truthAfter", truthAfter < 0.01f)
    }
}
