package com.burnin.scanner.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Synthetic 두 패널 + 카메라로 색감 일치 파이프라인을 검증한다.
 * 실제 기기 검증을 대신하지 않으며, 알고리즘이 의도한 오차 성분을 줄이는지만 확인한다.
 */
class ColorMatchModelTest {

    /** 가상 패널: 채널별 감마, primary 카메라 RGB, 전체 부하에 따른 ABL 압축. */
    private class Panel(
        val gamma: DoubleArray,
        val primaries: Array<Rgb>,
        val abl: Double = 0.0,
        val black: Rgb = Rgb(0.002, 0.002, 0.002),
    ) {
        fun emit(codes: DoubleArray): Rgb {
            val drive = DoubleArray(3) { codes[it].coerceIn(0.0, 1.0).pow(gamma[it]) }
            // ABL: 세 채널 합 구동량이 클수록 전체 출력이 줄어든다 (전체 화면 흰색에서 최대).
            val load = drive.sum() / 3.0
            val gain = 1.0 - abl * load * load
            var s = Rgb.ZERO
            for (k in 0..2) s += primaries[k] * (drive[k] * gain)
            return black + s
        }
    }

    /** 가상 카메라: 디코더 선형화가 실제 ISP 곡선과 약간 어긋난 상황(지수 0.93)을 흉내 낸다. */
    private fun camera(x: Rgb, vignette: Double): Rgb {
        fun h(v: Double) = (v * vignette).coerceAtLeast(0.0).pow(0.93)
        return Rgb(h(x.r), h(x.g), h(x.b))
    }

    private val refPanel = Panel(
        gamma = doubleArrayOf(2.2, 2.2, 2.2),
        primaries = arrayOf(Rgb(0.60, 0.05, 0.01), Rgb(0.10, 0.70, 0.08), Rgb(0.03, 0.06, 0.65)),
        abl = 0.10,
    )

    /** 노후 패널: 파랑 열화, 초록 primary 이동, 감마 차이, 더 강한 ABL. */
    private val agedPanel = Panel(
        gamma = doubleArrayOf(2.35, 2.25, 2.1),
        primaries = arrayOf(Rgb(0.63, 0.06, 0.01), Rgb(0.13, 0.74, 0.06), Rgb(0.03, 0.05, 0.52)),
        abl = 0.40,
    )

    private fun measureRef(c: DoubleArray) = camera(refPanel.emit(c), 1.0)
    private fun measureAdj(c: DoubleArray) = camera(agedPanel.emit(c), 0.9) // 다른 위치 = 다른 비네팅

    private fun fitModel(measure: (DoubleArray) -> Rgb): DisplayModel {
        val patches = ColorPatchPlan.fitPatches()
        val values = patches.associate { it.id to measure(it.codes) }
        val ramps = listOf(ColorPatchPlan.Role.RAMP_R, ColorPatchPlan.Role.RAMP_G, ColorPatchPlan.Role.RAMP_B).mapIndexed { k, role ->
            patches.filter { it.role == role }.map { p -> intArrayOf(p.r, p.g, p.b)[k] to values.getValue(p.id) }
        }
        return DisplayModel.fit(values.getValue("black"), ramps)
    }

    private fun meanDeltaE(matcher: ColorMatcher, lut: ColorLut?, patches: List<ColorPatchPlan.Patch>): Double {
        val white = matcher.targetFromMeasured(measureRef(doubleArrayOf(1.0, 1.0, 1.0)))
        return patches.map { p ->
            val target = matcher.targetFromMeasured(measureRef(p.codes))
            val shown = lut?.apply8(p.r, p.g, p.b)?.let { o -> DoubleArray(3) { o[it] / 255.0 } } ?: p.codes
            val got = measureAdj(shown) - matcher.adj.black
            // 보정 전 비교는 같은 상대 배율이 되도록 조정 기기 흰색 기준으로 정규화한다.
            val norm = if (lut == null) {
                val adjWhite = measureAdj(doubleArrayOf(1.0, 1.0, 1.0)) - matcher.adj.black
                got * (white.luma() / adjWhite.luma())
            } else got
            ColorDifference.deltaE2000(target, norm, white)
        }.average()
    }

    @Test fun matchingAgedPanelReducesHeldOutErrorAndGrayRefinementHelpsFurther() {
        val ref = fitModel(::measureRef)
        val adj = fitModel(::measureAdj)
        val white = doubleArrayOf(1.0, 1.0, 1.0)
        val matcher = ColorMatcher(ref, adj, measureRef(white), measureAdj(white))
        assertTrue(matcher.scale in 0.3..1.0)

        val validation = ColorPatchPlan.VALIDATION
        val before = meanDeltaE(matcher, null, validation)
        var lut = matcher.bakeLut()
        val modelOnly = meanDeltaE(matcher, lut, validation)

        val grays = ColorPatchPlan.LEVELS
        val refGray = grays.map { measureRef(doubleArrayOf(it / 255.0, it / 255.0, it / 255.0)) }
        val grayErrBefore = meanDeltaE(matcher, lut, ColorPatchPlan.grayPatches())
        repeat(3) {
            val adjGray = grays.map { v ->
                val o = lut.apply8(v, v, v)
                measureAdj(DoubleArray(3) { o[it] / 255.0 })
            }
            lut = matcher.refinePost(lut, grays, refGray, adjGray)
        }
        val grayErrAfter = meanDeltaE(matcher, lut, ColorPatchPlan.grayPatches())
        val refined = meanDeltaE(matcher, lut, validation)
        println("synthetic ΔE00: before=$before model=$modelOnly refined=$refined gray $grayErrBefore -> $grayErrAfter scale=${matcher.scale}")

        assertTrue("before=$before model=$modelOnly", modelOnly < before * 0.5)
        assertTrue("gray $grayErrBefore -> $grayErrAfter", grayErrAfter < grayErrBefore)
        assertTrue("gray after refine $grayErrAfter", grayErrAfter < 1.0)
        assertTrue("held-out after refine $refined (model $modelOnly, before $before)", refined < 2.0)
    }

    @Test fun identicalPanelsGiveIdentityLut() {
        val ref = fitModel(::measureRef)
        val adj = fitModel { camera(refPanel.emit(it), 1.0) }
        val matcher = ColorMatcher(ref, adj)
        assertEquals(1.0, matcher.scale, 1e-9)
        val lut = matcher.bakeLut()
        for (code in listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(128, 64, 200), intArrayOf(10, 240, 30))) {
            val o = lut.apply8(code[0], code[1], code[2])
            for (k in 0..2) assertTrue("$k ${code.toList()} -> ${o.toList()}", abs(o[k] - code[k]) <= 2)
        }
    }

    @Test fun dimmerUniformPanelOnlyNeedsNoChangeInRelativeMode() {
        // 전체가 균일하게 어두운 패널(또는 비네팅 차이)은 상대 일치에서 보정이 필요 없다.
        val ref = fitModel(::measureRef)
        val adj = fitModel { camera(refPanel.emit(it), 0.7) }
        val lut = ColorMatcher(ref, adj).bakeLut()
        val o = lut.apply8(200, 120, 60)
        assertTrue(o.toList().toString(), abs(o[0] - 200) <= 2 && abs(o[1] - 120) <= 2 && abs(o[2] - 60) <= 2)
    }

    @Test fun outOfGamutTargetsStayInRangeAndAreFlagged() {
        val ref = fitModel(::measureRef)
        val adj = fitModel(::measureAdj)
        val m = ColorMatcher(ref, adj)
        val (drives, clipped) = m.drivesFor(Rgb(0.0, 0.0, 0.9))
        assertTrue(clipped)
        assertTrue(drives.all { it in 0.0..1.0 })
        val (inGamut, flag) = m.drivesFor(m.targetSignal(doubleArrayOf(0.5, 0.5, 0.5)))
        assertFalse(flag)
        assertTrue(inGamut.all { it in 0.0..1.0 })
    }

    @Test fun weakOrWrongChannelSignalIsRejected() {
        val black = Rgb(0.01, 0.01, 0.01)
        fun ramp(p: Rgb) = ColorPatchPlan.LEVELS.map { v -> v to black + p * (v / 255.0).pow(2.2) }
        assertThrows(IllegalArgumentException::class.java) {
            DisplayModel.fit(black, listOf(ramp(Rgb(0.5, 0.0, 0.0)), ramp(Rgb(0.0, 0.0, 0.0)), ramp(Rgb(0.0, 0.0, 0.5))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            // 초록 램프인데 빨강이 가장 큼 → 화면 검출/WB 오류로 간주
            DisplayModel.fit(black, listOf(ramp(Rgb(0.5, 0.0, 0.0)), ramp(Rgb(0.6, 0.5, 0.0)), ramp(Rgb(0.0, 0.0, 0.5))))
        }
    }

    @Test fun toneCurveIsMonotoneAndInvertibleEvenWithNoisyNodes() {
        val xs = ColorPatchPlan.LEVELS.map { it / 255.0 }.toDoubleArray()
        val ys = xs.map { it.pow(2.2) }.toDoubleArray()
        ys[3] = ys[4] + 0.01 // 노이즈 역전
        val c = ToneCurve(xs, ys)
        var prev = -1.0
        for (i in 0..1000) {
            val v = c.eval(i / 1000.0)
            assertTrue(v >= prev - 1e-12)
            prev = v
        }
        for (t in listOf(0.001, 0.05, 0.2, 0.5, 0.9)) assertEquals(t, c.eval(c.inverse(t)), 1e-6)
        assertEquals(0.0, c.eval(0.0), 0.0)
        assertEquals(1.0, c.inverse(2.0), 0.0)
    }

    @Test fun lutWireFormatRoundTripsAndMatchesGoldenBytes() {
        val id = ColorLut.identity(2)
        val bytes = id.encode()
        assertEquals((8 * 3 + 3 * 256) * 2, bytes.size)
        // 첫 노드 (0,0,0), 둘째 노드 (r=0,g=0,b=1) = (0,0,1) → 여섯 번째 uint16이 0xFFFF
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF), bytes.take(12).map { it.toInt() and 0xFF })
        val back = ColorLut.decode(2, bytes)
        assertArrayEquals(id.data, back.data, 1e-6f)
        for (v in listOf(0, 1, 77, 128, 254, 255)) assertArrayEquals(intArrayOf(v, v, v), back.apply8(v, v, v))
        assertThrows(IllegalArgumentException::class.java) { ColorLut.decode(3, bytes) }
    }

    @Test fun postCurveFromNodesIsMonotoneAndKeepsZero() {
        val prev = ColorLut.identityPost()[0]
        val c = ColorLut.curveFromNodes(listOf(0.5 to 0.45, 0.25 to 0.3, 0.8 to 0.7, 0.6 to 0.4), prev)
        assertEquals(0f, c[0], 0f)
        for (i in 1 until c.size) assertTrue(c[i] >= c[i - 1])
        // 마지막 노드 위는 기존 곡선 + 마지막 노드 변화량
        assertEquals(1.0 - 0.1, c[255].toDouble(), 1e-6)
    }

    @Test fun ciede2000MatchesSharmaReferencePairs() {
        assertEquals(2.0425, ColorDifference.ciede2000(doubleArrayOf(50.0, 2.6772, -79.7751), doubleArrayOf(50.0, 0.0, -82.7485)), 1e-4)
        assertEquals(27.1492, ColorDifference.ciede2000(doubleArrayOf(50.0, 2.5, 0.0), doubleArrayOf(73.0, 25.0, -18.0)), 1e-4)
        assertEquals(1.0, ColorDifference.ciede2000(doubleArrayOf(50.0, 2.5, 0.0), doubleArrayOf(50.0, 3.1736, 0.5854)), 1e-4)
        assertEquals(0.0, ColorDifference.deltaE2000(Rgb(0.3, 0.4, 0.5), Rgb(0.3, 0.4, 0.5), Rgb(1.0, 1.0, 1.0)), 1e-9)
    }

    @Test fun globalMatrixOfIdentityLutIsIdentity() {
        val m = GlobalMatrixFit.fit(ColorLut.identity(9))
        val expected = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (i in 0..8) assertEquals(expected[i], m[i], 1e-3)
        val cmd = GlobalMatrixFit.surfaceFlingerCommand(expected)
        assertTrue(cmd.startsWith("adb shell service call SurfaceFlinger 1015 i32 1 f 1.000000 f 0.000000"))
        assertEquals(16, Regex(" f ").findAll(cmd).count())
    }

    @Test fun patchPatternNamesMatchTargetGrammar() {
        assertEquals("rgb-ff8000", ColorPatchPlan.patternName(255, 128, 0))
        val fit = ColorPatchPlan.fitPatches()
        assertEquals(1 + 4 * ColorPatchPlan.LEVELS.size, fit.size)
        assertEquals(fit.size, fit.map { it.id }.toSet().size)
        assertTrue(ColorPatchPlan.VALIDATION.none { v -> fit.any { it.r == v.r && it.g == v.g && it.b == v.b } })
    }
}
