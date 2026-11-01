package com.burnin.scanner.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 글씨·버튼 번인(1~3px 획)을 희소 점등 측광으로 화면 픽셀 단위까지 복원하는지 확인한다.
 * 카메라 모델: 원근 호모그래피 + 배럴 왜곡, OLED처럼 픽셀 일부만 발광하는 서브픽셀 구조,
 * 가우시안 PSF, 샷 노이즈. 앱에는 왜곡이 없고 1.2px 어긋난 호모그래피만 준다.
 */
class SparseSpotPhotometryTest {

    private val screenW = 96
    private val screenH = 128
    private val depth = 0.03f

    /** 2px 획 글자 + 3px 버튼 링 */
    private fun strokeMask(): BooleanArray {
        val m = BooleanArray(screenW * screenH)
        fun set(x: Int, y: Int) {
            if (x in 0 until screenW && y in 0 until screenH) m[y * screenW + x] = true
        }
        for (row in 0 until 3) {
            val y0 = 14 + row * 24
            for (ch in 0 until 5) {
                val x0 = 10 + ch * 16
                for (y in y0 until y0 + 2) for (x in x0 until x0 + 10) set(x, y)
                for (y in y0 until y0 + 14) for (x in x0 until x0 + 2) set(x, y)
                for (y in y0 + 6 until y0 + 8) for (x in x0 until x0 + 7) set(x, y)
            }
        }
        for (y in 0 until screenH) for (x in 0 until screenW) {
            val d = Math.hypot(x + 0.5 - 60.0, y + 0.5 - 104.0)
            if (Math.abs(d - 11.0) < 1.5) set(x, y)
        }
        return m
    }

    private fun truePanel(mask: BooleanArray): FloatArray {
        val rnd = Random(7)
        return FloatArray(screenW * screenH) { i ->
            val base = 1f + (rnd.nextGaussian() * 0.002).toFloat()
            if (mask[i]) base * (1f - depth) else base
        }
    }

    private val scale = 1.4
    private fun trueHomography(): Homography = Homography.from4Points(
        arrayOf(Vec2(0f, 0f), Vec2(screenW.toFloat(), 0f), Vec2(screenW.toFloat(), screenH.toFloat()), Vec2(0f, screenH.toFloat())),
        arrayOf(
            Vec2(20f, 18f),
            Vec2((20 + screenW * scale + 3).toFloat(), 16f),
            Vec2((20 + screenW * scale).toFloat(), (18 + screenH * scale + 2).toFloat()),
            Vec2(18f, (18 + screenH * scale).toFloat()),
        ),
    )!!

    /** 앱이 아는 호모그래피: 실제보다 (1.2, -0.8) 카메라 px 어긋남, 왜곡 모름 */
    private fun appHomography(errX: Double = 1.2, errY: Double = -0.8): Homography = trueHomography().let { h ->
        val c = DoubleArray(2)
        val src = arrayOf(Vec2(0f, 0f), Vec2(screenW.toFloat(), 0f), Vec2(screenW.toFloat(), screenH.toFloat()), Vec2(0f, screenH.toFloat()))
        val dst = src.map { p -> h.map(p.x.toDouble(), p.y.toDouble(), c); Vec2((c[0] + errX).toFloat(), (c[1] + errY).toFloat()) }
        Homography.from4Points(src, dst.toTypedArray())!!
    }

    private val camW = 180
    private val camH = 220

    /** 화면 휘도 × 점등 마스크 → 카메라 프레임 */
    private fun render(lum: FloatArray, lit: (Int, Int) -> Boolean, rnd: Random, noise: Double = 0.01): SparseSpotPhotometry.Frame {
        val img = FloatArray(camW * camH)
        val h = trueHomography()
        val p = DoubleArray(2)
        val sigma = 0.7
        val ccx = camW / 2.0
        val ccy = camH / 2.0
        // 서브픽셀 발광점 (픽셀 내부 좌표, 가중치): 픽셀 면적 일부만 발광
        val emitters = listOf(Triple(0.3, 0.3, 0.6), Triple(0.7, 0.3, 0.6), Triple(0.5, 0.75, 0.8))
        val wsum = emitters.sumOf { it.third }
        for (y in 0 until screenH) for (x in 0 until screenW) {
            if (!lit(x, y)) continue
            val v = lum[y * screenW + x]
            for ((ex, ey, ew) in emitters) {
                h.map(x + ex, y + ey, p)
                // 배럴 왜곡 (가장자리에서 ~1.5px)
                val dx = p[0] - ccx
                val dy = p[1] - ccy
                val r2 = (dx * dx + dy * dy) / (ccx * ccx + ccy * ccy)
                val px = p[0] + dx * 0.012 * r2
                val py = p[1] + dy * 0.012 * r2
                val amp = v * ew / wsum
                val ix = Math.round(px).toInt()
                val iy = Math.round(py).toInt()
                for (yy in iy - 3..iy + 3) for (xx in ix - 3..ix + 3) {
                    if (xx !in 0 until camW || yy !in 0 until camH) continue
                    val g = Math.exp(-((xx - px) * (xx - px) + (yy - py) * (yy - py)) / (2 * sigma * sigma))
                    img[yy * camW + xx] += (amp * g / (2 * Math.PI * sigma * sigma)).toFloat()
                }
            }
        }
        for (i in img.indices) {
            // black 차감 후 프레임: 배경은 0 근처, 노이즈만 남는다
            img[i] += (rnd.nextGaussian() * noise * Math.sqrt(maxOf(img[i], 0f).toDouble() + 0.002)).toFloat()
        }
        return SparseSpotPhotometry.Frame(camW, camH, img)
    }

    fun measure(lum: FloatArray, plan: SparseSpotPhotometry.Plan, frames: Int, seed: Long = 1, noise: Double = 0.01): SparseSpotPhotometry.SparseMap =
        SparseSpotPhotometry.assemble(layouts(lum, plan, frames, seed, noise, appHomography()), screenW, screenH)

    private fun layouts(
        lum: FloatArray,
        plan: SparseSpotPhotometry.Plan,
        frames: Int,
        seed: Long,
        noise: Double,
        h: Homography,
    ): List<SparseSpotPhotometry.PhaseLayout> {
        val rnd = Random(seed)
        return plan.phases().map { (px, py) ->
            val lit = { x: Int, y: Int ->
                val bx = x - plan.block * px
                val by = y - plan.block * py
                bx >= 0 && by >= 0 && bx % plan.pitch < plan.block && by % plan.pitch < plan.block
            }
            val first = render(lum, lit, rnd, noise)
            val layout = SparseSpotPhotometry.locate(first, h, screenW, screenH, plan, px, py)
            SparseSpotPhotometry.accumulate(first, layout)
            repeat(frames - 1) { SparseSpotPhotometry.accumulate(render(lum, lit, rnd, noise), layout) }
            layout
        }
    }

    private fun strokeContrast(values: FloatArray, mask: BooleanArray, valid: BooleanArray? = null): Float {
        val near = SparseSpotPhotometry.weightedBox(FloatArray(mask.size) { if (mask[it]) 1f else 0f }, BooleanArray(mask.size) { true }, screenW, screenH, 3)
        var s = 0.0; var n = 0; var r = 0.0; var m = 0
        for (i in values.indices) {
            if (valid != null && !valid[i]) continue
            if (mask[i]) { s += values[i]; n++ } else if (near[i] > 0f) { r += values[i]; m++ }
        }
        return ((s / n) / (r / m) - 1.0).toFloat()
    }

    @Test
    fun planPrefersSinglePixelBlocksAndRejectsTooCoarseCameras() {
        val fine = SparseSpotPhotometry.plan(1.4f)!!
        assertEquals(1, fine.block)
        assertEquals(4, fine.pitch)
        assertEquals(16, fine.patternCount)
        assertEquals("sparse70_b1_p4_x3_y1", fine.patternName(3, 1))
        // 해상도가 모자라면 2px로 바꾸기 전에 1px 점 간격을 넓혀(패턴 수를 늘려) 정밀도를 유지한다
        val coarse = SparseSpotPhotometry.plan(0.6f)!!
        assertEquals(1, coarse.block)
        assertEquals(9, coarse.pitch)
        assertTrue(coarse.phasesPerAxis <= SparseSpotPhotometry.MAX_PHASES_PER_AXIS_FINE)
        val veryCoarse = SparseSpotPhotometry.plan(0.45f)!!
        assertEquals(2, veryCoarse.block)
        assertTrue(veryCoarse.phasesPerAxis <= SparseSpotPhotometry.MAX_PHASES_PER_AXIS)
        assertNull(SparseSpotPhotometry.plan(0.2f))
        assertNull(SparseSpotPhotometry.plan(Float.NaN))
    }

    @Test
    fun recoversStrokeBurnInAtPixelResolutionDespiteMisregistrationAndDistortion() {
        val mask = strokeMask()
        val lum = truePanel(mask)
        val plan = SparseSpotPhotometry.plan(SparseSpotPhotometry.cameraPxPerScreenPx(appHomography(), screenW, screenH))!!
        val map = measure(lum, plan, frames = 4)
        assertTrue("valid ${map.validFraction}", map.validFraction > 0.85f)
        val measured = strokeContrast(map.value, mask, map.valid)
        assertEquals("획 대비 측정", -depth, measured, 0.004f)
    }

    @Test
    fun detailLayerFlattensStrokesWithoutTouchingFlatAreas() {
        val mask = strokeMask()
        val lum = truePanel(mask)
        val plan = SparseSpotPhotometry.plan(1.4f)!!
        val map = measure(lum, plan, frames = 8)
        val ratio = SparseSpotPhotometry.detailRatio(map, lowPassRadius = 2)
        val gain = SparseSpotPhotometry.detailGain(ratio, map.valid, SparseSpotPhotometry.detailReference(map, lowPassRadius = 2))
        // 저주파는 기존 전면 경로가 같은 커널(반경 2 박스 2회)로 보정한다고 보고 함께 적용
        val all = BooleanArray(lum.size) { true }
        val low = SparseSpotPhotometry.weightedBox(SparseSpotPhotometry.weightedBox(lum, all, screenW, screenH, 2), all, screenW, screenH, 2)
        val lowTarget = low.minOrNull()!!
        val corrected = FloatArray(lum.size) { lum[it] * gain[it] * (lowTarget / low[it]) }
        val before = strokeContrast(lum, mask, map.valid)
        val after = strokeContrast(corrected, mask, map.valid)
        assertTrue("before $before after $after", Math.abs(after) < Math.abs(before) * 0.2f)
        // 평탄한 영역의 픽셀 단위 불균일을 키우지 않는다
        val flat = BooleanArray(lum.size) { map.valid[it] && !mask[it] }
        fun spread(v: FloatArray): Double {
            val xs = v.indices.filter { flat[it] }.map { v[it].toDouble() }
            val mean = xs.average()
            return Math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
        }
        assertTrue("flat spread ${spread(lum)} -> ${spread(corrected)}", spread(corrected) <= spread(lum) * 1.5 + 1e-3)
    }

    @Test
    fun noiseOnlyMeasurementLeavesDetailLayerNearlyNeutral() {
        val lum = FloatArray(screenW * screenH) { 1f }
        val map = measure(lum, SparseSpotPhotometry.plan(1.4f)!!, frames = 3, seed = 3)
        val ratio = SparseSpotPhotometry.detailRatio(map, lowPassRadius = 2)
        val gain = SparseSpotPhotometry.detailGain(ratio, map.valid, SparseSpotPhotometry.detailReference(map, lowPassRadius = 2))
        val meanAtt = gain.indices.filter { map.valid[it] }.map { 1.0 - gain[it] }.average()
        assertTrue("mean attenuation $meanAtt", meanAtt < 0.005)
    }

    @Test
    fun alignmentLagDetectsWholePitchMisassignment() {
        val mask = strokeMask()
        val lum = truePanel(mask)
        val map = measure(lum, SparseSpotPhotometry.plan(1.4f)!!, frames = 2)
        val shifted = SparseSpotPhotometry.shift(map, 4, 0)
        // 기준은 저해상도 전면 측정처럼 흐린 맵
        val reference = SparseSpotPhotometry.weightedBox(lum, BooleanArray(lum.size) { true }, screenW, screenH, 1)
        val lag = SparseSpotPhotometry.alignmentLag(shifted, reference, maxLag = 5, highPassRadius = 8)
        assertEquals(4, lag[0])
        assertEquals(0, lag[1])
        val none = SparseSpotPhotometry.alignmentLag(map, reference, maxLag = 5, highPassRadius = 8)
        assertEquals(0, none[0]); assertEquals(0, none[1])
    }

    @Test
    fun perPhaseRealignmentRecoversHomographyErrorNearHalfPitch() {
        val mask = strokeMask()
        val lum = truePanel(mask)
        val plan = SparseSpotPhotometry.plan(1.4f)!!
        // 피치(카메라 5.6px)의 절반 가까운 오차 → 위상마다 이웃 블록으로 잘못 붙을 수 있다
        val bad = layouts(lum, plan, 3, 5, 0.01, appHomography(errX = 3.3, errY = 2.9))
        val all = BooleanArray(lum.size) { true }
        // 기준은 지금의 저해상도 전면 측정처럼 흐린 맵 (반경 2 박스 2회)
        val reference = SparseSpotPhotometry.weightedBox(SparseSpotPhotometry.weightedBox(lum, all, screenW, screenH, 2), all, screenW, screenH, 2)
        val (map, _) = SparseSpotPhotometry.assembleAligned(bad, screenW, screenH, reference, 3 * plan.pitch)
        assertTrue(map.validFraction > 0.8f)
        assertEquals("획 대비", -depth, strokeContrast(map.value, mask, map.valid), 0.005f)
    }

    @Test
    fun imageAffineMatchesBlockDownsampleConvention() {
        val h = trueHomography()
        val full = h.withImageAffine(4.0, 1.5)
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        h.map(30.0, 40.0, a)
        full.map(30.0, 40.0, b)
        assertEquals(a[0] * 4 + 1.5, b[0], 1e-9)
        assertEquals(a[1] * 4 + 1.5, b[1], 1e-9)
        assertNotNull(full)
    }

    @Test fun pixelRefineDimsRemainingBrightPixelsAndRestoresOvercorrectedOnes() {
        val w = 40
        val h = 40
        val n = w * h
        val value = FloatArray(n) { 1f }
        value[20 * w + 10] = 1.08f   // 보정 후에도 밝게 남은 픽셀
        value[20 * w + 30] = 0.95f   // 과하게 낮춘 픽셀
        val residual = SparseSpotPhotometry.SparseMap(w, h, value, FloatArray(n) { 0.002f }, BooleanArray(n) { true })
        val old = FloatArray(n) { 1f }.also { it[20 * w + 30] = 0.93f }
        val rmsBefore = SparseSpotPhotometry.detailRms(residual, 3)
        assertTrue(rmsBefore > 0.001f)
        val g = SparseSpotPhotometry.refineDetailGain(old, residual, 3, 0.25f)
        assertTrue("bright pixel dimmed", g[20 * w + 10] < 0.95f)
        assertTrue("overcorrected pixel raised", g[20 * w + 30] > 0.96f)
        assertEquals(1f, g[5 * w + 5], 0.01f)
    }
}
