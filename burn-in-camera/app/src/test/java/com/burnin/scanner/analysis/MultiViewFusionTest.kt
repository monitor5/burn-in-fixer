package com.burnin.scanner.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 멀티 화각 융합 검증: 겹침 영역 정규화 → 위치별 품질 → 강건한 합의.
 * 기존 crossAgreementConfidence와 달리 품질이 낮은 화각 하나가 전체 신뢰도를 끌어내리지 않고,
 * 화면 일부만 보는 화각이 자기 시야의 분포로 잘못 정규화되지 않는지 확인한다.
 */
class MultiViewFusionTest {

    private val gw = 40
    private val gh = 30
    private val size = gw * gh

    private fun flat(value: Float) = FloatArray(size) { value }
    private fun quality(value: Float) = FloatArray(size) { value }
    private fun block(grid: FloatArray, x0: Int, x1: Int, y0: Int, y1: Int, value: Float) {
        for (y in y0..y1) for (x in x0..x1) grid[y * gw + x] = value
    }

    @Test
    fun partialViewOfBurnInIsNotNormalizedAwayByOwnMedian() {
        // 기준(표준)은 전체를 보고 오른쪽 절반에 -10% 번인이 있다.
        val main = flat(1f)
        block(main, 20, 39, 0, 29, 0.9f)
        // 망원은 노출이 2배이고, 번인 영역 + 약간의 정상 영역(x=16..39)만 본다.
        val valid = BooleanArray(size) { (it % gw) >= 16 }
        val tele = FloatArray(size) { if (valid[it]) main[it] * 2f else 0f }

        // 자체 중앙값 정규화는 번인을 정상 밝기(1.0)처럼 만든다 — 기존 방식의 문제.
        val selfNorm = Analyzer.normalizeByMedian(FloatArray(size) { if (valid[it]) tele[it] else tele[30] })
        assertEquals(1f, selfNorm[10 * gw + 30], 1e-4f)

        val views = listOf(
            MultiViewFusion.View("main", main, quality(1f)),
            MultiViewFusion.View("tele", tele, quality(1f), valid),
        )
        val norm = MultiViewFusion.normalizeToReference(views, gw, gh)
        val t = norm.single { it.role == "tele" }
        assertEquals(2f, t.scale, 1e-4f)
        assertEquals("겹침 정규화 후 번인 밝기가 유지되어야 함", 0.9f, t.relative[10 * gw + 30], 1e-4f)
        assertEquals(1f, t.relative[10 * gw + 17], 1e-4f)

        val fused = MultiViewFusion.fuse(views, gw, gh, smoothOutput = false)
        assertTrue("일치하는 번인 영역은 신뢰되어야 함", fused.agreement[10 * gw + 30] > 0.99f)
        assertEquals(0.9f, fused.fused[10 * gw + 30], 1e-3f)
        assertEquals(0f, fused.ambiguousFraction, 0f)
    }

    @Test
    fun lowQualityOutlierIsDownweighted() {
        val a = flat(1f)
        val b = flat(1.3f) // 노출 차이
        val c = flat(0.7f)
        // 저품질 화각(c)에만 있는 +10% 반사
        block(c, 10, 19, 10, 14, 0.77f)
        val views = listOf(
            MultiViewFusion.View("main", a, quality(1f)),
            MultiViewFusion.View("wide", b, quality(1f)),
            MultiViewFusion.View("tele", c, quality(0.3f)),
        )
        val robust = MultiViewFusion.fuse(views, gw, gh)
        assertTrue("저품질 단독 이탈은 신뢰도를 깎지 않아야 함: ${robust.agreement[12 * gw + 15]}", robust.agreement[12 * gw + 15] > 0.9f)
        // Huber는 이탈값의 영향을 δ로 제한한다: 10% 반사가 합의값을 0.5% 미만만 움직여야 한다.
        assertEquals(1f, robust.fused[12 * gw + 15], 5e-3f)

        val legacy = Analyzer.crossAgreementConfidence(
            views.map { Analyzer.normalizeByMedian(it.signal) }, gw, gh,
        )
        assertTrue("기존 방식은 같은 구간을 마스킹함", legacy[12 * gw + 15] < 0.15f)
    }

    @Test
    fun equalQualitySingleDissentStillMasked() {
        val a = flat(1f)
        val b = flat(1f)
        val c = flat(1f)
        block(c, 8, 16, 8, 16, 1.2f)
        val robust = MultiViewFusion.fuse(
            listOf(
                MultiViewFusion.View("a", a, quality(1f)),
                MultiViewFusion.View("b", b, quality(1f)),
                MultiViewFusion.View("c", c, quality(1f)),
            ),
            gw,
            gh,
        )
        assertTrue("같은 품질 세 화각 중 하나의 이탈은 불신: ${robust.agreement[12 * gw + 12]}", robust.agreement[12 * gw + 12] < 0.15f)
        assertTrue(robust.agreement[25 * gw + 35] > 0.99f)
    }

    @Test
    fun twoGoodViewsDisagreeingAreAmbiguous() {
        val a = flat(1f)
        val b = flat(1f)
        block(b, 10, 19, 10, 14, 1.10f)
        val r = MultiViewFusion.fuse(
            listOf(
                MultiViewFusion.View("main", a, quality(1f)),
                MultiViewFusion.View("wide", b, quality(0.9f)),
            ),
            gw,
            gh,
            smoothOutput = false,
        )
        val i = 12 * gw + 15
        assertTrue("둘 다 좋은 품질의 불일치는 낮은 신뢰도: ${r.agreement[i]}", r.agreement[i] < 0.15f)
        assertTrue("재촬영 대상으로 표시", r.ambiguous[i])
        assertFalse(r.ambiguous[25 * gw + 35])
        assertEquals(50f / size, r.ambiguousFraction, 1e-6f)
        assertEquals(1f, r.crossCheckedFraction, 1e-6f)
    }

    @Test
    fun cellsOutsideSecondaryCoverageAreNeutral() {
        val a = flat(1f)
        val valid = BooleanArray(size) { (it / gw) < 15 }
        val b = FloatArray(size) { if (valid[it]) 1f else 0.2f } // 경계 밖 clamp 값 흉내
        val r = MultiViewFusion.fuse(
            listOf(
                MultiViewFusion.View("main", a, quality(1f)),
                MultiViewFusion.View("tele", b, quality(1f), valid),
            ),
            gw,
            gh,
            smoothOutput = false,
        )
        assertTrue("관측이 하나뿐인 셀은 중립", r.agreement.all { it > 0.99f })
        assertEquals(0.5f, r.crossCheckedFraction, 1e-6f)
    }

    @Test
    fun insufficientOverlapExcludesView() {
        val valid = BooleanArray(size).also { it[0] = true }
        val r = MultiViewFusion.fuse(
            listOf(
                MultiViewFusion.View("main", flat(1f), quality(1f)),
                MultiViewFusion.View("tele", flat(1f), quality(1f), valid),
            ),
            gw,
            gh,
        )
        assertEquals(listOf("main"), r.views.map { it.role })
        assertNotNull(r.excluded["tele"])
    }

    @Test
    fun lowQualityCellsDoNotDriveScale() {
        val main = flat(1f)
        val wide = flat(2f)
        val q = quality(1f)
        // 반사로 밝게 찍힌 절반은 품질이 낮다 — 배율 추정에서 빠져야 한다.
        for (i in 0 until size) if ((i % gw) < 25) {
            wide[i] = 3f
            q[i] = 0.1f
        }
        val norm = MultiViewFusion.normalizeToReference(
            listOf(MultiViewFusion.View("main", main, quality(1f)), MultiViewFusion.View("wide", wide, q)),
            gw,
            gh,
        )
        assertEquals(2f, norm[1].scale, 1e-4f)
    }

    @Test
    fun commonResolutionHidesDetailOnlySharpViewResolves() {
        // 망원만 해상하는 1셀 점결함: 초광각이 셀당 0.2픽셀이면 공통 해상도에서 비교해야 한다.
        val main = flat(1f)
        val tele = flat(1f).also { it[15 * gw + 20] = 0.8f }
        val views = listOf(
            MultiViewFusion.View("wide", main, quality(1f), cameraPxPerCell = 0.2f),
            MultiViewFusion.View("tele", tele, quality(1f), cameraPxPerCell = 4f),
        )
        assertEquals(2, MultiViewFusion.commonRadius(views))
        val r = MultiViewFusion.fuse(views, gw, gh, smoothOutput = false)
        assertTrue("공통 해상도에서 점결함은 불일치로 잡히지 않아야 함: ${r.agreement[15 * gw + 20]}", r.agreement[15 * gw + 20] > 0.99f)
        val raw = MultiViewFusion.fuse(views, gw, gh, smoothOutput = false, radiusOverride = 0)
        assertTrue(raw.agreement[15 * gw + 20] < 0.15f)
    }

    @Test
    fun weightedMedianHandlesTiesAndWeights() {
        assertEquals(1.5f, MultiViewFusion.weightedMedian(floatArrayOf(1f, 2f), floatArrayOf(1f, 1f), 2), 1e-6f)
        assertEquals(1f, MultiViewFusion.weightedMedian(floatArrayOf(2f, 1f, 5f), floatArrayOf(0.2f, 1f, 0.5f), 3), 1e-6f)
        assertEquals(3f, MultiViewFusion.weightedMedian(floatArrayOf(3f, 9f), floatArrayOf(1f, 0f), 2), 1e-6f)
    }

    @Test
    fun coverageMaskAndDensityFollowHomography() {
        val screenW = 100
        val screenH = 50
        // 화면을 이미지에 2배 확대 + 왼쪽으로 50px 이동 → 화면 x<25 부분은 이미지 밖.
        val h = Homography.from4Points(
            arrayOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 50f), Vec2(0f, 50f)),
            arrayOf(Vec2(-50f, 0f), Vec2(150f, 0f), Vec2(150f, 100f), Vec2(-50f, 100f)),
        )!!
        val mask = MultiViewFusion.coverageMask(h, 200, 101, screenW, screenH, 10, 5, borderPx = 0f)
        assertFalse(mask[0 * 10 + 0])
        assertFalse(mask[0 * 10 + 2])
        assertTrue(mask[2 * 10 + 5])
        val d = MultiViewFusion.cameraPixelsPerCell(h, screenW, screenH, 10, 5, step = 1)
        // 셀 10x10 화면 px → 이미지 20x20 px
        assertEquals(20f, d[2 * 10 + 5], 1e-3f)
        val q = MultiViewFusion.resolutionQuality(floatArrayOf(0.1f, 0.625f, 2f))
        assertEquals(0f, q[0], 1e-6f)
        assertEquals(0.5f, q[1], 1e-6f)
        assertEquals(1f, q[2], 1e-6f)
    }

    @Test
    fun registrationQualityPrefersDotGrid() {
        assertEquals(1f, MultiViewFusion.registrationQuality(0.8f), 1e-6f)
        assertEquals(0.2f, MultiViewFusion.registrationQuality(10f), 1e-6f)
        assertEquals(0.7f, MultiViewFusion.registrationQuality(null), 1e-6f)
    }
}
