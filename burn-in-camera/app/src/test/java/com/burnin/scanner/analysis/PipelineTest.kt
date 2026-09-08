package com.burnin.scanner.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 측정 파이프라인 종단 검증 (MVP 1 성공 기준의 시뮬레이션 버전):
 * 번인(밴드 −5%, 아이콘 −6%)이 있는 합성 화면을 가상 카메라로 촬영 →
 * 검출 → 호모그래피 정합 → 휘도맵 → 보정맵 생성 → 보정 적용 시뮬레이션 → 재측정.
 *
 * 요구서 19.1: "보정 후 회색 화면 잔여 RMS 편차가 보정 전 대비 50% 이상 감소"
 * 를 내부 영역 기준으로 검증한다.
 */
class PipelineTest {

    private val screenW = 640
    private val screenH = 400
    private val camW = 500
    private val camH = 380
    private val left = 50
    private val top = 40
    private val right = 450
    private val bottom = 340

    /** 번인이 있는 화면의 위치별 발광 함수 */
    private fun screenLum(u: Float, v: Float, gainAt: ((Float, Float) -> Float)?): Float {
        var lum = 0.5f
        if (v >= 160f && v < 220f) lum *= 0.95f                       // 상태바형 밴드 번인
        if (u >= 480f && u < 520f && v >= 60f && v < 100f) lum *= 0.94f // 아이콘 번인
        if (gainAt != null) lum *= gainAt(u, v).coerceIn(0f, 1f)
        return lum
    }

    /** 암실 박스 안 카메라 시뮬레이션 (화면 사각형만 밝음) */
    private fun renderCamera(gainAt: ((Float, Float) -> Float)? = null): GrayImage {
        val data = FloatArray(camW * camH) { 0.002f }
        for (y in top until bottom) {
            for (x in left until right) {
                val u = (x - left + 0.5f) / (right - left) * screenW
                val v = (y - top + 0.5f) / (bottom - top) * screenH
                data[y * camW + x] = screenLum(u, v, gainAt)
            }
        }
        return GrayImage(camW, camH, data)
    }

    /** RGB30 단색 패턴처럼 낮은 신호에서 특정 채널만 공간 편차가 있는 합성 프레임 */
    private fun renderLowLightRgb(): RgbImage {
        val r = FloatArray(camW * camH) { 0.002f }
        val g = FloatArray(camW * camH) { 0.002f }
        val b = FloatArray(camW * camH) { 0.002f }
        for (y in top until bottom) {
            for (x in left until right) {
                val u = (x - left + 0.5f) / (right - left) * screenW
                val v = (y - top + 0.5f) / (bottom - top) * screenH
                var red = 0.30f
                if (u >= 480f && u < 520f && v >= 60f && v < 100f) red *= 0.90f
                r[y * camW + x] = red
                g[y * camW + x] = 0.15f
                b[y * camW + x] = 0.09f
            }
        }
        return RgbImage(camW, camH, r, g, b)
    }

    /** 안정적인 비교를 위해 내부 80%만 잘라낸 그리드 */
    private fun interior(grid: FloatArray, gw: Int, gh: Int): FloatArray {
        val x0 = (gw * 0.10f).toInt()
        val x1 = (gw * 0.90f).toInt()
        val y0 = (gh * 0.10f).toInt()
        val y1 = (gh * 0.90f).toInt()
        val out = FloatArray((x1 - x0) * (y1 - y0))
        var i = 0
        for (y in y0 until y1) for (x in x0 until x1) out[i++] = grid[y * gw + x]
        return out
    }

    @Test
    fun endToEndCorrectionImprovesUniformity() {
        val gw = 96
        val gh = 60
        val maxAtt = 0.05f

        // 1. 보정 전 측정
        val before = renderCamera()
        val det = ScreenDetector.detect(before)
        assertNotNull("합성 화면 검출 실패", det)
        assertTrue("프레임 점유율 이상: ${det!!.areaRatio}", det.areaRatio in 0.4f..0.9f)

        val h = Analyzer.buildHomography(det.quad, screenW, screenH)
        assertNotNull(h)

        val lumaBefore = Analyzer.lumaGrid(before, null, h!!, screenW, screenH, gw, gh)
        val statsBefore = Analyzer.stats(lumaBefore)
        val intBefore = Analyzer.stats(interior(lumaBefore, gw, gh))
        assertTrue("5% 번인이 감지돼야 함 (rms=${intBefore.rmsDev})", intBefore.rmsDev > 0.008f)

        // 2. 보정맵 생성
        val gain = Analyzer.gainGrid(lumaBefore, gw, gh, maxAtt)
        for (g in gain) assertTrue("gain 범위 위반: $g", g in (1f - maxAtt - 1e-4f)..(1f + 1e-4f))

        // 3. 보정 적용 시뮬레이션: 분석 그리드 좌표계의 역사상으로 화면에 gain 적용
        val gridImg = GrayImage(gw, gh, gain)
        val margin = Analyzer.SCREEN_SAMPLE_MARGIN
        val gainAt = { u: Float, v: Float ->
            val gx = ((u / screenW - margin) / (1 - 2 * margin)) * gw - 0.5f
            val gy = ((v / screenH - margin) / (1 - 2 * margin)) * gh - 0.5f
            gridImg.bilinear(gx, gy)
        }

        // 4. 보정 후 재측정 (동일 호모그래피)
        val after = renderCamera(gainAt)
        val lumaAfter = Analyzer.lumaGrid(after, null, h, screenW, screenH, gw, gh)
        val statsAfter = Analyzer.stats(lumaAfter)
        val intAfter = Analyzer.stats(interior(lumaAfter, gw, gh))

        // 내부 영역: 잔여 RMS 60% 이상 감소 (요구서 기준 50% 이상)
        assertTrue(
            "내부 개선 부족: before=${intBefore.rmsDev} after=${intAfter.rmsDev}",
            intAfter.rmsDev < intBefore.rmsDev * 0.4f,
        )
        // 전체 화면 기준으로도 악화되지 않아야 함 (가장자리 ramp 비용 포함)
        assertTrue(
            "전체 화면 악화: before=${statsBefore.rmsDev} after=${statsAfter.rmsDev}",
            statsAfter.rmsDev <= statsBefore.rmsDev,
        )
        // 예상 밝기 손실이 maxAttenuation 이내
        var mean = 0.0
        for (g in gain) mean += g.toDouble()
        val loss = 1.0 - mean / gain.size
        assertTrue("밝기 손실 $loss > $maxAtt", loss <= maxAtt + 1e-4)
    }

    /** 반복 보정(M-FR-012): 언더슈트 상태에서 시작해도 damping 반복으로 잔여 오차가 수렴해야 한다 */
    @Test
    fun iterativeRefinementConverges() {
        val gw = 96
        val gh = 60
        val maxAtt = 0.05f
        val margin = Analyzer.SCREEN_SAMPLE_MARGIN

        val before = renderCamera()
        val det = ScreenDetector.detect(before)!!
        val h = Analyzer.buildHomography(det.quad, screenW, screenH)!!
        val lumaBefore = Analyzer.lumaGrid(before, null, h, screenW, screenH, gw, gh)
        val target = Analyzer.stats(lumaBefore).p10

        // 초기 맵을 의도적으로 절반만 보정된 상태로 약화 (첫 보정이 부족했던 상황)
        val full = Analyzer.gainGrid(lumaBefore, gw, gh, maxAtt)
        var gain = FloatArray(full.size) { 1f + (full[it] - 1f) * 0.5f }

        fun measure(g: FloatArray): FloatArray {
            val gi = GrayImage(gw, gh, g)
            val cam = renderCamera { u, v ->
                gi.bilinear(
                    ((u / screenW - margin) / (1 - 2 * margin)) * gw - 0.5f,
                    ((v / screenH - margin) / (1 - 2 * margin)) * gh - 0.5f,
                )
            }
            return Analyzer.lumaGrid(cam, null, h, screenW, screenH, gw, gh)
        }

        val rmsHistory = ArrayList<Float>()
        rmsHistory += Analyzer.stats(interior(measure(gain), gw, gh)).rmsDev
        repeat(6) {
            val measured = measure(gain)
            gain = Analyzer.refineGain(gain, measured, target, 0.3f, gw, gh, maxAtt)
            rmsHistory += Analyzer.stats(interior(measure(gain), gw, gh)).rmsDev
        }

        // 단조 감소하며, 초기 언더슈트 대비 45% 이상 개선 (블러/샘플링에 의한 수렴 하한 감안)
        assertTrue("수렴 실패: $rmsHistory", rmsHistory.last() < rmsHistory.first() * 0.55f)
        assertTrue("잔여 RMS 절대값 과대: $rmsHistory", rmsHistory.last() < 0.008f)
        assertTrue(
            "중간에 발산: $rmsHistory",
            rmsHistory.zipWithNext().all { (a, b) -> b < a * 1.05f },
        )
        // gain 값 범위 유지
        for (g in gain) assertTrue("gain 범위 위반: $g", g in (1f - maxAtt - 1e-4f)..(1f + 1e-4f))
    }

    @Test
    fun lowLightGainMixContributesToCorrection() {
        val primary = floatArrayOf(1f, 0.98f, 0.97f, 1f)
        val lowLight = floatArrayOf(1f, 0.95f, 0.96f, 1f)

        val mixed = Analyzer.mixGainGrids(primary, lowLight, 0.35f, 0.05f)

        assertTrue("gray30 보정량이 섞여야 함: ${mixed[1]}", mixed[1] < primary[1])
        assertTrue("gray30만큼 과격하게 따라가면 안 됨: ${mixed[1]}", mixed[1] > lowLight[1])
        assertTrue("gain 범위 위반: ${mixed[1]}", mixed[1] in 0.95f..1f)
    }

    @Test
    fun correctionPngUsesSameScreenMarginAsMeasurementGrid() {
        val screenPixels = 1000
        val gridCells = 100
        val margin = Analyzer.SCREEN_SAMPLE_MARGIN

        for (cell in listOf(0, 12, 50, 99)) {
            val measuredScreenPixel =
                (margin + (1f - 2f * margin) * (cell + 0.5f) / gridCells) * screenPixels
            val pngGridCoord = Analyzer.gridCoordForScreenPixel(
                measuredScreenPixel,
                screenPixels,
                gridCells,
                margin,
            )

            assertTrue(
                "PNG 좌표 역변환 불일치: cell=$cell coord=$pngGridCoord",
                Math.abs(pngGridCoord - cell) < 1e-4f,
            )
        }
    }

    @Test
    fun averageGainGridsMergesRgbLowLightAttenuation() {
        val red = floatArrayOf(1f, 0.99f, 0.98f)
        val green = floatArrayOf(1f, 0.97f, 0.98f)
        val blue = floatArrayOf(1f, 0.95f, 1f)

        val avg = Analyzer.averageGainGrids(listOf(red, green, blue), 0.05f)!!

        assertTrue("RGB attenuation 평균 오류: ${avg[1]}", Math.abs(avg[1] - 0.97f) < 1e-5f)
        assertTrue("최대 감쇠 범위 위반: ${avg[1]}", avg[1] in 0.95f..1f)
        assertTrue("채널별 attenuation이 반영돼야 함: ${avg[2]}", avg[2] < 0.99f)
    }

    @Test
    fun rgbChannelGridExtractsSelectedLowLightChannel() {
        val gw = 64
        val gh = 40
        val before = renderCamera()
        val det = ScreenDetector.detect(before)!!
        val h = Analyzer.buildHomography(det.quad, screenW, screenH)!!
        val black = RgbImage(
            camW,
            camH,
            FloatArray(camW * camH) { 0.002f },
            FloatArray(camW * camH) { 0.002f },
            FloatArray(camW * camH) { 0.002f },
        )

        val rgb = renderLowLightRgb()
        val redGrid = Analyzer.channelGrid(rgb, black, 0, h, screenW, screenH, gw, gh)
        val greenGrid = Analyzer.channelGrid(rgb, black, 1, h, screenW, screenH, gw, gh)
        val redStats = Analyzer.stats(interior(redGrid, gw, gh))
        val greenStats = Analyzer.stats(interior(greenGrid, gw, gh))

        assertTrue("red30 median 범위 이상: ${redStats.median}", redStats.median in 0.29f..0.31f)
        assertTrue("red30 채널 편차를 감지해야 함: ${redStats.rmsDev}", redStats.rmsDev > 0.006f)
        assertTrue("green30 균일 채널은 낮은 RMS여야 함: ${greenStats.rmsDev}", greenStats.rmsDev < 0.003f)
    }

    @Test
    fun confidenceWeightedGainSuppressesLowTrustCells() {
        val gw = 20
        val gh = 20
        val luma = FloatArray(gw * gh)
        val confidence = FloatArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                luma[y * gw + x] = 1f + x * 0.01f
                confidence[y * gw + x] = if (x < gw / 2) 1f else 0f
            }
        }

        val gain = Analyzer.gainGrid(luma, gw, gh, 0.10f, confidence)

        assertTrue("신뢰 높은 셀은 보정돼야 함: ${gain[gh / 2 * gw + 6]}", gain[gh / 2 * gw + 6] < 0.99f)
        assertTrue("신뢰 낮은 셀은 보정이 억제돼야 함: ${gain[gh / 2 * gw + 15]}", gain[gh / 2 * gw + 15] > 0.995f)
    }

    /** 네이티브 1:1 그리드에서 카메라 픽셀 스케일 노이즈/무아레가 맵에 새겨지지 않아야 한다 */
    @Test
    fun adaptiveSmoothRadiusSuppresssSubCameraPixelNoise() {
        val gw = 96
        val gh = 96
        val rnd = java.util.Random(7)
        // 셀 단위 고주파 노이즈(카메라가 분해 못 하는 스케일의 무아레/노이즈 모사) ±3%
        val luma = FloatArray(gw * gh) { 1f + (rnd.nextFloat() - 0.5f) * 0.06f }

        val noisy = Analyzer.gainGrid(luma, gw, gh, 0.10f, smoothRadius = 1)
        val smoothed = Analyzer.gainGrid(luma, gw, gh, 0.10f, smoothRadius = 4)

        fun attenuationRms(gain: FloatArray): Float {
            val interior = interior(gain, gw, gh)
            var mean = 0.0
            for (v in interior) mean += v.toDouble()
            mean /= interior.size
            var sq = 0.0
            for (v in interior) sq += (v - mean) * (v - mean)
            return Math.sqrt(sq / interior.size).toFloat()
        }

        val noisyRms = attenuationRms(noisy)
        val smoothRms = attenuationRms(smoothed)
        assertTrue(
            "스무딩 반경 확대가 맵의 고주파 성분을 줄여야 함: r1=$noisyRms r4=$smoothRms",
            smoothRms < noisyRms * 0.5f,
        )
    }

    /** 스무딩 반경을 키워도 넓은 번인 얼룩(저주파)은 계속 보정되어야 한다 */
    @Test
    fun adaptiveSmoothRadiusKeepsWideBurnInCorrection() {
        val gw = 96
        val gh = 96
        val luma = FloatArray(gw * gh) { 1f }
        // 화면 1/3 크기의 어두운 번인 영역 (-6%)
        for (y in gh / 3 until gh * 2 / 3) {
            for (x in gw / 3 until gw * 2 / 3) {
                luma[y * gw + x] = 0.94f
            }
        }

        val gain = Analyzer.gainGrid(luma, gw, gh, 0.10f, smoothRadius = 4)
        val burnCenter = gain[(gh / 2) * gw + gw / 2]
        val normalRegion = gain[(gh / 6) * gw + gw / 2]

        assertTrue("번인 중앙은 유지(≈1.0)돼야 함: $burnCenter", burnCenter > 0.99f)
        assertTrue("정상 영역은 낮춰져야 함: $normalRegion", normalRegion < 0.96f)
    }

    @Test
    fun gainGridKeepsEdgeCellsActive() {
        val gw = 200
        val gh = 200
        val luma = FloatArray(gw * gh) { 1f }
        for (y in 0 until 16) {
            for (x in 0 until gw) {
                luma[y * gw + x] = 1.20f
            }
        }

        val gain = Analyzer.gainGrid(luma, gw, gh, 0.10f)
        val centerX = gw / 2
        val topEdge = gain[0 * gw + centerX]
        val statusBarRegion = gain[4 * gw + centerX] // 2% from top

        assertTrue("맨 가장자리도 보정돼야 함: $topEdge", topEdge < 0.93f)
        assertTrue(
            "상단바 영역 보정이 유지돼야 함: $statusBarRegion",
            statusBarRegion < 0.93f,
        )
    }

    @Test
    fun radialFlatFieldReducesCameraVignetting() {
        val gw = 64
        val gh = 40
        val data = FloatArray(gw * gh)
        val cx = (gw - 1) * 0.5f
        val cy = (gh - 1) * 0.5f
        val maxR = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val dx = x - cx
                val dy = y - cy
                val r = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / maxR
                data[y * gw + x] = 1f - 0.12f * r
            }
        }

        val before = Analyzer.stats(data).rmsDev
        val flat = Analyzer.radialFlatField(data, gw, gh)
        val corrected = Analyzer.applyFlatField(data, flat)
        val after = Analyzer.stats(corrected).rmsDev

        assertTrue("flat-field 보정 후 RMS가 줄어야 함: before=$before after=$after", after < before * 0.35f)
    }

    @Test
    fun edgeFalloffReportsDarkEdges() {
        val gw = 100
        val gh = 80
        val luma = FloatArray(gw * gh) { 1f }
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                if (x < 6 || x >= gw - 6 || y < 5 || y >= gh - 5) {
                    luma[y * gw + x] = 0.85f
                }
            }
        }

        val falloff = Analyzer.edgeFalloff(luma, gw, gh)

        assertTrue("가장자리 감광률이 감지돼야 함: $falloff", falloff in 0.10f..0.20f)
    }

    @Test
    fun geometryQualityIsLowForUndistortedQuad() {
        val quad = ScreenDetector.Quad(
            arrayOf(
                Vec2(0f, 0f),
                Vec2(screenW.toFloat(), 0f),
                Vec2(screenW.toFloat(), screenH.toFloat()),
                Vec2(0f, screenH.toFloat()),
            ),
        )

        val quality = Analyzer.geometryQuality(quad, screenW, screenH)

        assertTrue("정상 사각형 기하 점수가 높음: $quality", quality.score < 0.001f)
        assertTrue("평행 오차가 없어야 함: $quality", Math.abs(quality.parallelErrorDeg) < 0.001f)
    }

    @Test
    fun homographyAlignsScreenCoordinates() {
        val before = renderCamera()
        val det = ScreenDetector.detect(before)!!
        val h = Analyzer.buildHomography(det.quad, screenW, screenH)!!
        val out = DoubleArray(2)

        h.map(0.0, 0.0, out)
        assertTrue("TL 오차: (${out[0]}, ${out[1]})", Math.abs(out[0] - left) < 3 && Math.abs(out[1] - top) < 3)

        h.map(screenW.toDouble(), screenH.toDouble(), out)
        assertTrue("BR 오차: (${out[0]}, ${out[1]})", Math.abs(out[0] - right) < 3 && Math.abs(out[1] - bottom) < 3)

        h.map(screenW / 2.0, screenH / 2.0, out)
        assertTrue(
            "중앙 오차: (${out[0]}, ${out[1]})",
            Math.abs(out[0] - (left + right) / 2.0) < 3 && Math.abs(out[1] - (top + bottom) / 2.0) < 3,
        )
    }

    /** 세로 화면(가로<세로) 대상 기기 + 가로로 놓인 카메라: 90° 회전 대응 확인 */
    @Test
    fun rotatedCameraStillBuildsHomography() {
        // 화면은 400x640 (세로), 카메라에는 가로로 긴 사각형으로 찍힘
        val det = ScreenDetector.detect(renderCamera())!!
        val h = Analyzer.buildHomography(det.quad, 400, 640)
        assertNotNull(h)
        val out = DoubleArray(2)
        // 화면 (0,0)은 90도 회전 규칙에 따라 이미지 TR로 사상되어야 한다.
        h!!.map(0.0, 0.0, out)
        assertTrue(
            "회전 대응 실패: (${out[0]}, ${out[1]})",
            Math.abs(right - out[0]) < 3 && Math.abs(top - out[1]) < 3,
        )
    }
}
