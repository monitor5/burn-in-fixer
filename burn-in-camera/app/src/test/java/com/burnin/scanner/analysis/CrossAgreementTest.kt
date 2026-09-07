package com.burnin.scanner.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 동시 3각 교차 재현성(M-FR 사용자 요구: "불일치 구간 무시") 수학 검증.
 * 두 화각이 같은 화면을 봤다면 정규화 그리드는 일치해야 하고,
 * 한쪽 광학 경로에만 있는 성분은 낮은 confidence로 보정 반영을 줄인다.
 * 실제 화면 결함 여부는 이 계산만으로 단정하지 않고, 필요하면 VL crop 판정으로 보조한다.
 */
class CrossAgreementTest {

    @Test
    fun normalizeByMedianRemovesExposureScale() {
        val grid = FloatArray(100) { 2f }
        grid[0] = 4f
        val norm = Analyzer.normalizeByMedian(grid)
        assertEquals(1f, norm[50], 1e-4f)
        assertEquals(2f, norm[0], 1e-4f)
    }

    @Test
    fun identicalGridsGiveFullConfidence() {
        val gw = 40
        val gh = 30
        val a = FloatArray(gw * gh) { 1f }
        val b = FloatArray(gw * gh) { 1f }
        val conf = Analyzer.crossAgreementConfidence(listOf(a, b), gw, gh)
        assertTrue("동일 그리드 confidence 낮음: ${conf.min()}", conf.min()!! > 0.99f)
    }

    @Test
    fun disagreementRegionIsMasked() {
        val gw = 40
        val gh = 30
        val a = FloatArray(gw * gh) { 1f }
        val b = FloatArray(gw * gh) { 1f }
        // 한 화각에만 있는 +10% 얼룩 — screen-space 재현성이 없으므로 낮은 confidence
        for (y in 10..14) for (x in 10..19) b[y * gw + x] = 1.10f
        val conf = Analyzer.crossAgreementConfidence(listOf(a, b), gw, gh, 0.02f, 0.06f)

        val center = conf[12 * gw + 15]
        val far = conf[25 * gw + 35]
        assertTrue("불일치 중심이 마스킹되지 않음: $center", center < 0.15f)
        assertTrue("일치 영역까지 잘못 마스킹: $far", far > 0.9f)
    }

    @Test
    fun sharedScreenDefectStaysTrustedAcrossAngles() {
        val gw = 40
        val gh = 30
        val a = FloatArray(gw * gh) { 1f }
        val b = FloatArray(gw * gh) { 1f }
        val c = FloatArray(gw * gh) { 1f }
        for (y in 10..14) {
            for (x in 10..19) {
                a[y * gw + x] = 0.92f
                b[y * gw + x] = 0.92f
                c[y * gw + x] = 0.92f
            }
        }

        val conf = Analyzer.crossAgreementConfidence(listOf(a, b, c), gw, gh, 0.02f, 0.06f)

        assertTrue(
            "모든 화각에 재현된 화면 결함은 신뢰되어야 함: ${conf[12 * gw + 15]}",
            conf[12 * gw + 15] > 0.99f,
        )
    }

    @Test
    fun singleGridIsNeutral() {
        val conf = Analyzer.crossAgreementConfidence(listOf(FloatArray(12) { 1f }), 4, 3)
        assertTrue(conf.all { it == 1f })
    }

    @Test
    fun threeWayAgreementUsesWorstPair() {
        val gw = 20
        val gh = 20
        val a = FloatArray(gw * gh) { 1f }
        val b = FloatArray(gw * gh) { 1f }
        val c = FloatArray(gw * gh) { 1f }
        // 셋 중 하나만 튀어도 해당 블록은 불신 (이중 3x3 블러를 견디도록 5x5 블록)
        for (y in 8..12) for (x in 8..12) c[y * gw + x] = 1.2f
        val conf = Analyzer.crossAgreementConfidence(listOf(a, b, c), gw, gh, 0.02f, 0.06f)
        assertTrue("3자 불일치 마스킹 실패: ${conf[10 * gw + 10]}", conf[10 * gw + 10] < 0.15f)
    }

    @Test
    fun findsLowConfidenceRegionsAndCanRestoreFloor() {
        val gw = 20
        val gh = 12
        val conf = FloatArray(gw * gh) { 1f }
        for (y in 3..7) for (x in 4..10) conf[y * gw + x] = 0.1f

        val regions = Analyzer.lowConfidenceRegions(conf, gw, gh, threshold = 0.5f, minArea = 8)
        val restored = Analyzer.raiseConfidenceFloor(conf, gw, gh, regions.single(), 0.85f)

        assertEquals(4, regions.single().minX)
        assertEquals(10, regions.single().maxX)
        assertTrue("VL trust 판정 시 해당 후보 confidence floor가 올라가야 함", restored[5 * gw + 7] >= 0.85f)
        assertEquals(1f, restored[0], 1e-6f)
    }
}
