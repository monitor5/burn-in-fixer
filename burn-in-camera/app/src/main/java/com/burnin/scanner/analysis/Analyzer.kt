package com.burnin.scanner.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * 측정 파이프라인의 수치 부분 (14장 알고리즘 요구사항의 MVP 구현).
 *
 *  촬영(선형화·평균) → black offset 제거 → 호모그래피 정합 →
 *  분석 그리드 휘도맵 → 통계 → gain 맵(정상 영역 낮춤) → 스무딩 →
 *  네이티브 해상도 알파 감쇠 PNG
 *
 * 렌즈/카메라 기인 성분은 dot-grid 다점 정합과 radial flat-field로 줄이고,
 * SNR/미광/클리핑 confidence를 gain 계산에 곱해 과보정을 억제한다.
 */
object Analyzer {
    const val SCREEN_SAMPLE_MARGIN = 0f
    const val EDGE_RAMP_FRACTION = 0f
    private const val FLAT_FIELD_OUTLIER_OK = 0.04f
    private const val FLAT_FIELD_OUTLIER_REJECT = 0.12f
    private const val FLAT_FIELD_MAX_DEVIATION = 0.18f


    data class Stats(
        val median: Float,
        val p10: Float,
        val rmsDev: Float,   // 중앙값 대비 상대 편차 RMS
        val p95Dev: Float,   // 상대 편차 절대값의 P95
        val maxDev: Float,   // 상대 편차 절대값의 최대
        val mean: Float,
    )

    data class GeometryQuality(
        val score: Float,
        val aspectError: Float,
        val edgeBalance: Float,
        val diagonalError: Float,
        val parallelErrorDeg: Float,
    )

    data class CornerMapping(
        val targetTlImageCorner: Int,
        val clockwise: Boolean,
        val label: String,
    )

    data class OrientedHomography(
        val homography: Homography,
        val mapping: CornerMapping,
        val markerScore: Float,
        val confidence: Float,
    )

    data class GridRegion(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val area: Int,
        val minConfidence: Float,
        val meanConfidence: Float,
    )

    /** 화면 4모서리(스크린 좌표)와 검출 사각형(이미지 좌표)로 호모그래피 생성.
     *  카메라가 90° 돌아가 있으면 가로/세로 비율로 감지해 모서리 대응을 회전시킨다. */
    fun buildHomography(
        quad: ScreenDetector.Quad,
        screenW: Int,
        screenH: Int,
        mapping: CornerMapping? = null,
    ): Homography? {
        val src = arrayOf(
            Vec2(0f, 0f),
            Vec2(screenW.toFloat(), 0f),
            Vec2(screenW.toFloat(), screenH.toFloat()),
            Vec2(0f, screenH.toFloat()),
        )
        if (mapping != null) {
            return Homography.from4Points(src, mappedCorners(quad, mapping))
        }
        val screenLandscape = screenW >= screenH
        val quadLandscape = quad.topLen() >= quad.sideLen()
        val dst = if (screenLandscape == quadLandscape) {
            arrayOf(quad.tl, quad.tr, quad.br, quad.bl)
        } else {
            // 카메라가 화면 대비 90° 회전: 화면 TL → 이미지 TR 방향으로 한 칸 회전
            arrayOf(quad.tr, quad.br, quad.bl, quad.tl)
        }
        return Homography.from4Points(src, dst)
    }

    /**
     * 비대칭 marker 패턴의 좌상단 검정 inset을 이용해 타겟 화면 좌표와 카메라 이미지
     * 좌표의 코너 대응을 결정한다. gray/dotgrid는 180°/상하 반전 배치를 구분할 수 없기 때문에
     * 이 결과를 이후 재검출 호모그래피에도 같은 mapping으로 재사용해야 한다.
     */
    fun buildHomographyWithMarker(
        quad: ScreenDetector.Quad,
        marker: GrayImage,
        screenW: Int,
        screenH: Int,
    ): OrientedHomography? {
        val candidates = cornerMappings().mapNotNull { mapping ->
            val h = buildHomography(quad, screenW, screenH, mapping) ?: return@mapNotNull null
            val score = markerOrientationScore(marker, h, screenW, screenH)
            Triple(mapping, h, score)
        }
        if (candidates.isEmpty()) return null

        val sorted = candidates.sortedByDescending { it.third }
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.third ?: 0f
        if (best.third < 0.03f) return null
        val confidence = ((best.third - second) / best.third.coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
        return OrientedHomography(
            homography = best.second,
            mapping = best.first,
            markerScore = best.third,
            confidence = confidence,
        )
    }

    /**
     * 시작 시점의 기하 왜곡/정렬 품질 진단.
     * 현재는 화면 경계 사각형만으로 perspective/skew/aspect 왜곡을 추정한다.
     * 렌즈의 국소 왜곡은 dotgrid 검출이 들어오면 이 값과 별도 항목으로 더해야 한다.
     */
    fun geometryQuality(quad: ScreenDetector.Quad, screenW: Int, screenH: Int): GeometryQuality {
        val top = quad.topLen().coerceAtLeast(1e-5f)
        val bottom = dist(quad.bl, quad.br).coerceAtLeast(1e-5f)
        val left = quad.sideLen().coerceAtLeast(1e-5f)
        val right = dist(quad.tr, quad.br).coerceAtLeast(1e-5f)
        val avgW = (top + bottom) * 0.5f
        val avgH = (left + right) * 0.5f

        val observedAspect = avgW / avgH
        val screenLandscape = screenW >= screenH
        val quadLandscape = avgW >= avgH
        val expectedAspectRaw = if (screenLandscape == quadLandscape) {
            screenW.toFloat() / screenH
        } else {
            screenH.toFloat() / screenW
        }
        val expectedAspect = expectedAspectRaw.coerceAtLeast(1e-5f)
        val aspectError = Math.abs(Math.log((observedAspect / expectedAspect).toDouble())).toFloat()

        val edgeBalance = maxOf(
            Math.abs(top - bottom) / avgW,
            Math.abs(left - right) / avgH,
        )
        val diagonalA = dist(quad.tl, quad.br).coerceAtLeast(1e-5f)
        val diagonalB = dist(quad.tr, quad.bl).coerceAtLeast(1e-5f)
        val diagonalError = Math.abs(diagonalA - diagonalB) / ((diagonalA + diagonalB) * 0.5f)
        val parallelError = maxOf(
            parallelAngleErrorDeg(quad.tl, quad.tr, quad.bl, quad.br),
            parallelAngleErrorDeg(quad.tl, quad.bl, quad.tr, quad.br),
        )

        val score = maxOf(
            aspectError,
            edgeBalance,
            diagonalError,
            parallelError / 20f,
        )
        return GeometryQuality(score, aspectError, edgeBalance, diagonalError, parallelError)
    }

    /**
     * 분석 그리드 휘도맵. 각 셀 중심 주변 3x3 지점을 호모그래피로 이미지에 투영해
     * 평균 샘플하고, black offset 맵(있으면)을 같은 위치에서 빼준다.
     * 화면 전체를 마진 없이 샘플한다. 출력 보정맵도 같은 좌표계를 사용한다.
     */
    fun lumaGrid(
        gray: GrayImage,
        black: GrayImage?,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray {
        val grid = FloatArray(gw * gh)
        val out = DoubleArray(2)
        val margin = SCREEN_SAMPLE_MARGIN
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val u = (margin + (1 - 2 * margin) * (gx + 0.5f) / gw) * screenW
                val v = (margin + (1 - 2 * margin) * (gy + 0.5f) / gh) * screenH
                var acc = 0f
                var accB = 0f
                for (sy in -1..1) {
                    for (sx in -1..1) {
                        h.map(
                            (u + sx * screenW * 0.15 / gw).toDouble(),
                            (v + sy * screenH * 0.15 / gh).toDouble(),
                            out,
                        )
                        acc += gray.bilinear(out[0].toFloat(), out[1].toFloat())
                        if (black != null) accB += black.bilinear(out[0].toFloat(), out[1].toFloat())
                    }
                }
                val value = (acc - accB) / 9f
                grid[gy * gw + gx] = value.coerceAtLeast(1e-5f)
            }
        }
        return grid
    }

    fun channelGrid(
        rgb: RgbImage,
        black: RgbImage?,
        channel: Int,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray {
        val grid = FloatArray(gw * gh)
        val out = DoubleArray(2)
        val margin = SCREEN_SAMPLE_MARGIN
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val u = (margin + (1 - 2 * margin) * (gx + 0.5f) / gw) * screenW
                val v = (margin + (1 - 2 * margin) * (gy + 0.5f) / gh) * screenH
                var acc = 0f
                var accB = 0f
                for (sy in -1..1) {
                    for (sx in -1..1) {
                        h.map(
                            (u + sx * screenW * 0.15 / gw).toDouble(),
                            (v + sy * screenH * 0.15 / gh).toDouble(),
                            out,
                        )
                        acc += rgb.bilinearChannel(out[0].toFloat(), out[1].toFloat(), channel)
                        if (black != null) {
                            accB += black.bilinearChannel(out[0].toFloat(), out[1].toFloat(), channel)
                        }
                    }
                }
                val value = (acc - accB) / 9f
                grid[gy * gw + gx] = value.coerceAtLeast(1e-5f)
            }
        }
        return grid
    }

    fun stats(grid: FloatArray): Stats {
        require(grid.isNotEmpty()) { "signal grid must not be empty" }
        require(grid.all { it.isFinite() && it >= 0f }) { "signal must be finite and nonnegative" }
        val sorted = grid.clone().apply { sort() }
        val median = sorted[sorted.size / 2]
        require(median > 0f) { "signal median must be positive" }
        val p10 = sorted[(sorted.size * 0.10f).toInt().coerceIn(0, sorted.size - 1)]
        var sumSq = 0.0
        var sum = 0.0
        val absDev = FloatArray(grid.size)
        for (i in grid.indices) {
            val d = grid[i] / median - 1f
            absDev[i] = Math.abs(d)
            sumSq += (d * d).toDouble()
            sum += grid[i].toDouble()
        }
        absDev.sort()
        return Stats(
            median = median,
            p10 = p10,
            rmsDev = Math.sqrt(sumSq / grid.size).toFloat(),
            p95Dev = absDev[(absDev.size * 0.95f).toInt().coerceIn(0, absDev.size - 1)],
            maxDev = absDev.last(),
            mean = (sum / grid.size).toFloat(),
        )
    }

    /**
     * 카메라 비네팅/플랫필드 근사. 별도 적분구/무한균일 광원이 없는 현장 측정이므로,
     * gray 기준 프레임에서 반지름별 저주파 성분만 추정한다. 2-pass outlier 억제로
     * 번인/얼룩이 렌즈 비네팅으로 흡수되는 것을 줄이고, 과한 현장 보정은 제한한다.
     */
    fun radialFlatField(luma: FloatArray, gw: Int, gh: Int, bins: Int = 64): FloatArray {
        require(luma.size == gw * gh) { "grid size mismatch" }
        val median = stats(luma).median.coerceAtLeast(1e-5f)
        val cx = (gw - 1) * 0.5f
        val cy = (gh - 1) * 0.5f
        val maxR = Math.sqrt((cx * cx + cy * cy).toDouble()).coerceAtLeast(1e-5)

        fun radialBin(x: Int, y: Int): Int {
            val dx = x - cx
            val dy = y - cy
            return ((Math.sqrt((dx * dx + dy * dy).toDouble()) / maxR) * (bins - 1))
                .toInt()
                .coerceIn(0, bins - 1)
        }

        fun smoothProfile(profile: FloatArray, passes: Int = 3) {
            repeat(passes) {
                val copy = profile.clone()
                for (i in profile.indices) {
                    var acc = 0f
                    var n = 0
                    for (d in -2..2) {
                        val j = i + d
                        if (j in profile.indices) {
                            acc += copy[j]
                            n++
                        }
                    }
                    profile[i] = acc / n
                }
            }
        }

        val sum = DoubleArray(bins)
        val count = IntArray(bins)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val b = radialBin(x, y)
                sum[b] += (luma[y * gw + x] / median).toDouble()
                count[b]++
            }
        }
        val profile = FloatArray(bins) { i ->
            if (count[i] > 0) (sum[i] / count[i]).toFloat() else 1f
        }
        smoothProfile(profile)

        val robustSum = DoubleArray(bins)
        val robustWeight = DoubleArray(bins)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val b = radialBin(x, y)
                val normalized = luma[y * gw + x] / median
                val expected = profile[b].coerceAtLeast(1e-5f)
                val residual = normalized / expected - 1f
                val absResidual = Math.abs(residual)
                val weight = when {
                    absResidual <= FLAT_FIELD_OUTLIER_OK -> 1.0
                    absResidual >= FLAT_FIELD_OUTLIER_REJECT -> 0.0
                    else -> 1.0 -
                        (absResidual - FLAT_FIELD_OUTLIER_OK) /
                        (FLAT_FIELD_OUTLIER_REJECT - FLAT_FIELD_OUTLIER_OK)
                }
                if (weight > 0.0) {
                    robustSum[b] += normalized.toDouble() * weight
                    robustWeight[b] += weight
                }
            }
        }
        for (i in profile.indices) {
            if (robustWeight[i] > 0.0) profile[i] = (robustSum[i] / robustWeight[i]).toFloat()
        }
        smoothProfile(profile, passes = 4)

        val out = FloatArray(luma.size)
        for (y in 0 until gh) {
            val dy = y - cy
            for (x in 0 until gw) {
                val dx = x - cx
                val f = (Math.sqrt((dx * dx + dy * dy).toDouble()) / maxR * (bins - 1))
                    .toFloat()
                    .coerceIn(0f, bins - 1.001f)
                val i0 = f.toInt()
                val t = f - i0
                val a = profile[i0]
                val b = profile[(i0 + 1).coerceAtMost(bins - 1)]
                out[y * gw + x] = (a * (1 - t) + b * t)
                    .coerceIn(1f - FLAT_FIELD_MAX_DEVIATION, 1f + FLAT_FIELD_MAX_DEVIATION)
            }
        }
        return out
    }

    fun edgeFalloff(luma: FloatArray, gw: Int, gh: Int, bandFraction: Float = 0.06f): Float {
        require(luma.size == gw * gh) { "grid size mismatch" }
        val bx = (gw * bandFraction).toInt().coerceIn(1, (gw / 2).coerceAtLeast(1))
        val by = (gh * bandFraction).toInt().coerceIn(1, (gh / 2).coerceAtLeast(1))
        val centerX0 = gw / 4
        val centerX1 = gw - centerX0
        val centerY0 = gh / 4
        val centerY1 = gh - centerY0
        var edgeSum = 0.0
        var edgeCount = 0
        var centerSum = 0.0
        var centerCount = 0
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val v = luma[y * gw + x].toDouble()
                if (x < bx || x >= gw - bx || y < by || y >= gh - by) {
                    edgeSum += v
                    edgeCount++
                }
                if (x in centerX0 until centerX1 && y in centerY0 until centerY1) {
                    centerSum += v
                    centerCount++
                }
            }
        }
        val edgeMean = edgeSum / edgeCount.coerceAtLeast(1)
        val centerMean = centerSum / centerCount.coerceAtLeast(1)
        return (1f - (edgeMean / centerMean.coerceAtLeast(1e-5)).toFloat()).coerceAtLeast(0f)
    }

    fun applyFlatField(grid: FloatArray, flatField: FloatArray?): FloatArray {
        if (flatField == null) return grid
        require(grid.size == flatField.size) { "flat-field grid size mismatch" }
        val out = FloatArray(grid.size)
        for (i in grid.indices) out[i] = (grid[i] / flatField[i].coerceAtLeast(0.50f)).coerceAtLeast(1e-5f)
        return out
    }

    fun flatFieldStrength(flatField: FloatArray): Float {
        var maxDev = 0f
        for (v in flatField) maxDev = maxOf(maxDev, Math.abs(v - 1f))
        return maxDev
    }

    /**
     * SNR/미광/클리핑 confidence. 1.0은 그대로 반영, 0.0은 gain 갱신을 막는다.
     * signal은 black-subtracted grid, black은 같은 좌표계의 black raw grid다.
     */
    fun confidenceGrid(
        signal: FloatArray,
        black: FloatArray?,
        strayWarn: Float,
        clipHigh: Float = 0.92f,
    ): FloatArray {
        require(signal.all { it.isFinite() && it >= 0f })
        require(black == null || black.all { it.isFinite() && it >= 0f })
        require(strayWarn.isFinite() && strayWarn > 0.03f)
        require(clipHigh.isFinite() && clipHigh in 0f..0.994f)
        if (black != null) require(signal.size == black.size) { "black grid size mismatch" }
        val out = FloatArray(signal.size)
        for (i in signal.indices) {
            val s = signal[i].coerceAtLeast(1e-5f)
            val b = black?.get(i)?.coerceAtLeast(0f) ?: 0f
            val strayRatio = if (black == null) 0f else b / s
            val strayConf = confidenceRamp(strayRatio, 0.03f, strayWarn.coerceAtLeast(0.031f))
            val snr = s / (b + 1e-5f)
            val snrConf = ((snr - 4f) / 16f).coerceIn(0f, 1f)
            val clipConf = confidenceRamp(s, clipHigh, 0.995f)
            out[i] = (strayConf * snrConf * clipConf).coerceIn(0f, 1f)
        }
        return out
    }

    fun meanConfidence(confidence: FloatArray?): Float {
        if (confidence == null || confidence.isEmpty()) return 1f
        var sum = 0.0
        for (v in confidence) sum += v.toDouble()
        return (sum / confidence.size).toFloat()
    }

    fun combineConfidence(primary: FloatArray, secondary: FloatArray): FloatArray {
        requireConfidence(primary)
        requireConfidence(secondary)
        require(primary.size == secondary.size) { "confidence grid size mismatch" }
        val out = FloatArray(primary.size)
        for (i in primary.indices) out[i] = (primary[i] * secondary[i]).coerceIn(0f, 1f)
        return out
    }

    /**
     * 같은 패턴을 연속 촬영한 그리드들의 프레임 간 변화량을 confidence로 변환한다.
     * 플리커/롤링밴드처럼 매 프레임 위치나 밝기가 달라지는 영역은 gain 갱신에서 제외된다.
     */
    fun temporalStabilityConfidence(
        frameGrids: List<FloatArray>,
        gridW: Int,
        gridH: Int,
        outW: Int,
        outH: Int,
        okRelativeRange: Float,
        zeroRelativeRange: Float,
    ): FloatArray {
        require(gridW > 0 && gridH > 0 && outW > 0 && outH > 0)
        require(outW.toLong() * outH <= Int.MAX_VALUE)
        require(okRelativeRange.isFinite() && zeroRelativeRange.isFinite() && okRelativeRange >= 0f && zeroRelativeRange > okRelativeRange)
        frameGrids.forEach { requireGrid(it, gridW, gridH) }
        if (frameGrids.size < 2) return FloatArray(outW * outH) { 1f }
        val size = gridW * gridH
        for (grid in frameGrids) require(grid.size == size) { "temporal grid size mismatch" }

        val low = FloatArray(size)
        for (i in 0 until size) {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var sum = 0.0
            for (grid in frameGrids) {
                val v = grid[i]
                min = minOf(min, v)
                max = maxOf(max, v)
                sum += v.toDouble()
            }
            val mean = (sum / frameGrids.size).toFloat().coerceAtLeast(1e-5f)
            val relativeRange = (max - min) / mean
            low[i] = confidenceRamp(relativeRange, okRelativeRange, zeroRelativeRange)
        }

        val smoothed = boxBlur(boxBlur(low, gridW, gridH), gridW, gridH)
        for (i in smoothed.indices) smoothed[i] = smoothed[i].coerceIn(0f, 1f)
        return if (gridW == outW && gridH == outH) {
            smoothed
        } else {
            resampleGrid(smoothed, gridW, gridH, outW, outH)
        }
    }

    /** 그리드를 자체 중앙값으로 정규화 (카메라 간 노출·감도 차이 제거) */
    fun normalizeByMedian(grid: FloatArray): FloatArray {
        val med = stats(grid).median.coerceAtLeast(1e-6f)
        return FloatArray(grid.size) { grid[it] / med }
    }

    /**
     * 동시 촬영한 서로 다른 화각(초광각/표준/망원)의 정규화 휘도 그리드를 비교해
     * 카메라 간 screen-space 재현성 confidence를 만든다. 같은 순간의 같은 화면을
     * 여러 광학 경로로 봤을 때 재현되지 않는 구간은 촬영계 잔차일 가능성이 높으므로
     * 보정 반영을 낮춘다. 실제 화면 결함 여부는 이 값만으로 의미론적 판정하지 않는다.
     */
    fun crossAgreementConfidence(
        normalizedGrids: List<FloatArray>,
        gw: Int,
        gh: Int,
        okRelativeRange: Float = 0.02f,
        zeroRelativeRange: Float = 0.06f,
    ): FloatArray {
        require(gw > 0 && gh > 0 && gw.toLong() * gh <= Int.MAX_VALUE)
        require(okRelativeRange.isFinite() && zeroRelativeRange.isFinite() && okRelativeRange >= 0f && zeroRelativeRange > okRelativeRange)
        normalizedGrids.forEach { requireGrid(it, gw, gh) }
        if (normalizedGrids.size < 2) return FloatArray(gw * gh) { 1f }
        val size = gw * gh
        for (grid in normalizedGrids) require(grid.size == size) { "cross grid size mismatch" }
        val out = FloatArray(size)
        for (i in 0 until size) {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var sum = 0.0
            for (grid in normalizedGrids) {
                val v = grid[i]
                min = minOf(min, v)
                max = maxOf(max, v)
                sum += v.toDouble()
            }
            val mean = (sum / normalizedGrids.size).toFloat().coerceAtLeast(1e-5f)
            out[i] = confidenceRamp((max - min) / mean, okRelativeRange, zeroRelativeRange)
        }
        return boxBlur(boxBlur(out, gw, gh), gw, gh).also { values ->
            for (i in values.indices) values[i] = values[i].coerceIn(0f, 1f)
        }
    }

    fun lowConfidenceRegions(
        confidence: FloatArray,
        gw: Int,
        gh: Int,
        threshold: Float = 0.55f,
        minArea: Int = 48,
        maxRegions: Int = 4,
    ): List<GridRegion> {
        require(confidence.size == gw * gh) { "confidence grid size mismatch" }
        val seen = BooleanArray(confidence.size)
        val regions = ArrayList<GridRegion>()
        val qx = IntArray(confidence.size)
        val qy = IntArray(confidence.size)
        for (sy in 0 until gh) {
            for (sx in 0 until gw) {
                val start = sy * gw + sx
                if (seen[start] || confidence[start] >= threshold) continue
                var head = 0
                var tail = 0
                qx[tail] = sx
                qy[tail] = sy
                tail++
                seen[start] = true
                var minX = sx
                var minY = sy
                var maxX = sx
                var maxY = sy
                var area = 0
                var sum = 0.0
                var minConf = Float.MAX_VALUE
                while (head < tail) {
                    val x = qx[head]
                    val y = qy[head]
                    head++
                    val idx = y * gw + x
                    val c = confidence[idx]
                    area++
                    sum += c.toDouble()
                    if (c < minConf) minConf = c
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y

                    fun add(nx: Int, ny: Int) {
                        if (nx !in 0 until gw || ny !in 0 until gh) return
                        val ni = ny * gw + nx
                        if (!seen[ni] && confidence[ni] < threshold) {
                            seen[ni] = true
                            qx[tail] = nx
                            qy[tail] = ny
                            tail++
                        }
                    }

                    add(x - 1, y)
                    add(x + 1, y)
                    add(x, y - 1)
                    add(x, y + 1)
                }
                if (area >= minArea) {
                    regions += GridRegion(minX, minY, maxX, maxY, area, minConf, (sum / area).toFloat())
                }
            }
        }
        return regions
            .sortedWith(compareBy<GridRegion> { it.meanConfidence }.thenByDescending { it.area })
            .take(maxRegions)
    }

    fun raiseConfidenceFloor(
        confidence: FloatArray,
        gw: Int,
        gh: Int,
        region: GridRegion,
        floor: Float,
    ): FloatArray {
        require(confidence.size == gw * gh) { "confidence grid size mismatch" }
        val out = confidence.copyOf()
        val f = floor.coerceIn(0f, 1f)
        for (y in region.minY..region.maxY) {
            for (x in region.minX..region.maxX) {
                val i = y * gw + x
                out[i] = maxOf(out[i], f)
            }
        }
        return out
    }

    /** 역해석용 8-bit 그레이 PNG (다운스케일). 카메라별 평균 프레임 저장에 사용. */
    fun grayImagePng(img: GrayImage, maxLongEdge: Int = 1600): ByteArray {
        val stride = Math.max(1, Math.ceil(maxOf(img.w, img.h) / maxLongEdge.toDouble()).toInt())
        val outW = Math.max(1, img.w / stride)
        val outH = Math.max(1, img.h / stride)
        var peak = 1e-6f
        for (v in img.data) if (v > peak) peak = v
        val pixels = IntArray(outW * outH)
        var i = 0
        for (y in 0 until outH) {
            val sy = y * stride
            for (x in 0 until outW) {
                // 감마 재적용(1/2.2)해 사람이 보기 좋은 밝기로 저장
                val lin = (img[x * stride, sy] / peak).coerceIn(0f, 1f)
                val v = Math.round(Math.pow(lin.toDouble(), 1.0 / 2.2) * 255).toInt().coerceIn(0, 255)
                pixels[i++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    fun screenCropBitmap(
        img: GrayImage,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        region: GridRegion,
        maxLongEdge: Int = 640,
    ): Bitmap {
        val minScreenSide = minOf(screenW, screenH).coerceAtLeast(1)
        val rawX0 = region.minX.toFloat() / gw * screenW
        val rawY0 = region.minY.toFloat() / gh * screenH
        val rawX1 = (region.maxX + 1).toFloat() / gw * screenW
        val rawY1 = (region.maxY + 1).toFloat() / gh * screenH
        val minSpan = minScreenSide * 0.14f
        val spanX = maxOf(rawX1 - rawX0, minSpan)
        val spanY = maxOf(rawY1 - rawY0, minSpan)
        val cx = (rawX0 + rawX1) * 0.5f
        val cy = (rawY0 + rawY1) * 0.5f
        val padX = spanX * 0.30f
        val padY = spanY * 0.30f
        val x0 = (cx - spanX * 0.5f - padX).coerceIn(0f, screenW.toFloat())
        val y0 = (cy - spanY * 0.5f - padY).coerceIn(0f, screenH.toFloat())
        val x1 = (cx + spanX * 0.5f + padX).coerceIn(0f, screenW.toFloat())
        val y1 = (cy + spanY * 0.5f + padY).coerceIn(0f, screenH.toFloat())
        val cropW = maxOf(1f, x1 - x0)
        val cropH = maxOf(1f, y1 - y0)
        val scale = minOf(maxLongEdge / cropW, maxLongEdge / cropH, 1f)
        val outW = maxOf(16, Math.round(cropW * scale))
        val outH = maxOf(16, Math.round(cropH * scale))
        val values = FloatArray(outW * outH)
        val mapped = DoubleArray(2)
        var i = 0
        for (y in 0 until outH) {
            val sv = y0 + (y + 0.5f) / outH * cropH
            for (x in 0 until outW) {
                val su = x0 + (x + 0.5f) / outW * cropW
                h.map(su.toDouble(), sv.toDouble(), mapped)
                values[i++] = img.bilinear(mapped[0].toFloat(), mapped[1].toFloat())
            }
        }
        val sorted = values.copyOf()
        sorted.sort()
        val p1 = sorted[(sorted.size * 0.01f).toInt().coerceIn(0, sorted.lastIndex)]
        val p99 = sorted[(sorted.size * 0.99f).toInt().coerceIn(0, sorted.lastIndex)]
        val span = (p99 - p1).coerceAtLeast(1e-5f)
        val pixels = IntArray(outW * outH)
        for (idx in values.indices) {
            val lin = ((values[idx] - p1) / span).coerceIn(0f, 1f)
            val s = Math.round(Math.pow(lin.toDouble(), 1.0 / 2.2) * 255).toInt().coerceIn(0, 255)
            pixels[idx] = Color.rgb(s, s, s)
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    fun contactSheetBitmap(items: List<Pair<String, Bitmap>>): Bitmap {
        require(items.isNotEmpty()) { "contact sheet requires at least one bitmap" }
        val labelH = 30
        val cellW = items.maxOf { it.second.width }
        val cellH = items.maxOf { it.second.height } + labelH
        val out = Bitmap.createBitmap(cellW * items.size, cellH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
        }
        val linePaint = Paint().apply { color = Color.rgb(80, 80, 80) }
        for ((idx, item) in items.withIndex()) {
            val x = idx * cellW
            canvas.drawText(item.first, x + 8f, 22f, paint)
            canvas.drawBitmap(item.second, x.toFloat(), labelH.toFloat(), null)
            if (idx > 0) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), cellH.toFloat(), linePaint)
        }
        return out
    }

    fun bitmapPng(bitmap: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    /**
     * gain 맵 생성 (14.5/14.6):
     *  target = 하위 10퍼센타일, gain = clamp(target/측정값, 1-maxAtt, 1)
     *  → 밝은(정상) 영역일수록 gain < 1 (낮춤), 번인(어두운) 영역은 1 (유지)
     * 박스 블러 2회로 측정 노이즈를 죽인다. smoothRadius는 그리드 해상도가 카메라의
     * 실측 해상도보다 높을 때(네이티브 1:1 맵) 카메라 1픽셀 이하 스케일의 노이즈·무아레가
     * 보정맵에 그대로 새겨지지 않도록 카메라 픽셀 스케일에 맞춰 키운다.
     */
    fun gainGrid(
        luma: FloatArray,
        gw: Int,
        gh: Int,
        maxAtt: Float,
        confidence: FloatArray? = null,
        smoothRadius: Int = 1,
    ): FloatArray {
        requireGrid(luma, gw, gh)
        requireAttenuation(maxAtt)
        require(smoothRadius >= 0)
        requireConfidence(confidence)
        if (confidence != null) require(confidence.size == luma.size) { "confidence grid size mismatch" }
        val st = stats(luma)
        val target = st.p10
        val gain = FloatArray(luma.size)
        for (i in luma.indices) {
            val raw = (target / luma[i].coerceAtLeast(1e-5f)).coerceIn(1f - maxAtt, 1f)
            val conf = confidence?.get(i) ?: 1f
            gain[i] = (1f - (1f - raw) * conf).coerceIn(1f - maxAtt, 1f)
        }
        var g = boxBlur(gain, gw, gh, smoothRadius)
        g = boxBlur(g, gw, gh, smoothRadius)
        applyEdgeRamp(g, gw, gh)
        for (i in g.indices) g[i] = g[i].coerceIn(1f - maxAtt, 1f)
        // Smoothing must never turn an excluded cell into an accepted correction.
        if (confidence != null) for (i in g.indices) if (confidence[i] == 0f) g[i] = 1f
        return g
    }

    /**
     * 밝기별 gain 맵 혼합. gain 자체가 아니라 attenuation(1-gain)을 섞어
     * gray25 같은 저휘도에서만 보이는 자국도 실제 보정량에 반영한다.
     */
    /** Reapply the persistent mask after mixing so other brightness/channel maps cannot bypass it. */
    fun limitGainByConfidence(gain: FloatArray, confidence: FloatArray, maxAtt: Float): FloatArray {
        require(gain.size == confidence.size && gain.all { it.isFinite() && it in 0f..1f })
        requireConfidence(confidence)
        requireAttenuation(maxAtt)
        return FloatArray(gain.size) { maxOf(gain[it], 1f - maxAtt * confidence[it]) }
    }

    fun mixGainGrids(
        primary: FloatArray,
        secondary: FloatArray,
        secondaryWeight: Float,
        maxAtt: Float,
    ): FloatArray {
        requireAttenuation(maxAtt)
        require(secondaryWeight.isFinite())
        require(primary.all { it.isFinite() } && secondary.all { it.isFinite() })
        require(primary.size == secondary.size) { "gain grid size mismatch" }
        val w = secondaryWeight.coerceIn(0f, 1f)
        val out = FloatArray(primary.size)
        for (i in primary.indices) {
            val primaryAtt = 1f - primary[i]
            val secondaryAtt = 1f - secondary[i]
            val att = primaryAtt * (1f - w) + secondaryAtt * w
            out[i] = (1f - att).coerceIn(1f - maxAtt, 1f)
        }
        return out
    }

    fun averageGainGrids(gains: List<FloatArray>, maxAtt: Float): FloatArray? {
        requireAttenuation(maxAtt)
        require(gains.all { g -> g.all { it.isFinite() } })
        if (gains.isEmpty()) return null
        val size = gains[0].size
        val out = FloatArray(size)
        for (g in gains) {
            require(g.size == size) { "gain grid size mismatch" }
            for (i in out.indices) out[i] += 1f - g[i]
        }
        val n = gains.size.toFloat()
        for (i in out.indices) {
            val att = out[i] / n
            out[i] = (1f - att).coerceIn(1f - maxAtt, 1f)
        }
        return out
    }

    /**
     * 반복 보정 갱신 (M-FR-012, 14.9):
     *   newGain = clamp(oldGain * (target / measuredAfter)^alpha, 1-maxAtt, 1)
     * alpha(기본 0.3)는 damping 계수. 갱신 후 노이즈 억제 블러와 가장자리 ramp를 재적용한다.
     */
    fun refineGain(
        gain: FloatArray,
        measuredAfter: FloatArray,
        target: Float,
        alpha: Float,
        gw: Int,
        gh: Int,
        maxAtt: Float,
        confidence: FloatArray? = null,
        smoothRadius: Int = 1,
    ): FloatArray {
        requireGrid(gain, gw, gh)
        requireGrid(measuredAfter, gw, gh)
        requireAttenuation(maxAtt)
        require(target.isFinite() && target > 0f)
        require(alpha.isFinite() && alpha in 0f..1f)
        require(smoothRadius >= 0)
        requireConfidence(confidence)
        if (confidence != null) require(confidence.size == gain.size) { "confidence grid size mismatch" }
        val out = FloatArray(gain.size)
        for (i in gain.indices) {
            val ratio = target.toDouble() / measuredAfter[i].coerceAtLeast(1e-5f)
            val localAlpha = alpha * (confidence?.get(i) ?: 1f)
            out[i] = (gain[i] * Math.pow(ratio, localAlpha.toDouble()).toFloat())
                .coerceIn(1f - maxAtt, 1f)
        }
        val g = boxBlur(out, gw, gh, smoothRadius)
        applyEdgeRamp(g, gw, gh)
        for (i in g.indices) g[i] = g[i].coerceIn(1f - maxAtt, 1f)
        if (confidence != null) for (i in g.indices) if (confidence[i] == 0f) g[i] = gain[i]
        return g
    }

    private fun requireGrid(grid: FloatArray, w: Int, h: Int) {
        require(w > 0 && h > 0 && w.toLong() * h == grid.size.toLong()) { "grid shape mismatch" }
        require(grid.all { it.isFinite() && it >= 0f }) { "invalid grid value" }
    }

    private fun requireAttenuation(value: Float) {
        require(value.isFinite() && value in 0f..1f) { "invalid attenuation" }
    }

    private fun requireConfidence(values: FloatArray?) {
        require(values == null || values.all { it.isFinite() && it in 0f..1f }) { "invalid confidence" }
    }

    /** 가장자리 confidence 감쇠: 렌즈 왜곡/비네팅 잔차가 큰 맨 바깥 영역만 보정 강도를 줄인다 */
    private fun applyEdgeRamp(g: FloatArray, gw: Int, gh: Int) {
        if (EDGE_RAMP_FRACTION <= 0f) return
        val rampX = (gw * EDGE_RAMP_FRACTION).coerceAtLeast(1f)
        val rampY = (gh * EDGE_RAMP_FRACTION).coerceAtLeast(1f)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val ex = Math.min(x, gw - 1 - x) / rampX
                val ey = Math.min(y, gh - 1 - y) / rampY
                val conf = Math.min(1f, Math.min(ex, ey))
                val i = y * gw + x
                g[i] = 1f + (g[i] - 1f) * conf
            }
        }
    }

    /** 가로/세로 분리 슬라이딩 윈도 박스 블러. 반경이 커져도 O(w·h)로 동작하며,
     *  가장자리는 실제 포함된 픽셀 수로 나눠 기존 3x3 구현과 같은 경계 동작을 유지한다. */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int = 1): FloatArray {
        if (radius < 1) return src.clone()
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            var n = 0
            for (x in 0 until minOf(radius, w)) {
                acc += src[row + x]
                n++
            }
            for (x in 0 until w) {
                val add = x + radius
                if (add < w) {
                    acc += src[row + add]
                    n++
                }
                tmp[row + x] = acc / n
                val drop = x - radius
                if (drop >= 0) {
                    acc -= src[row + drop]
                    n--
                }
            }
        }
        val dst = FloatArray(src.size)
        for (x in 0 until w) {
            var acc = 0f
            var n = 0
            for (y in 0 until minOf(radius, h)) {
                acc += tmp[y * w + x]
                n++
            }
            for (y in 0 until h) {
                val add = y + radius
                if (add < h) {
                    acc += tmp[add * w + x]
                    n++
                }
                dst[y * w + x] = acc / n
                val drop = y - radius
                if (drop >= 0) {
                    acc -= tmp[drop * w + x]
                    n--
                }
            }
        }
        return dst
    }

    private fun resampleGrid(src: FloatArray, srcW: Int, srcH: Int, outW: Int, outH: Int): FloatArray {
        val image = GrayImage(srcW, srcH, src)
        val out = FloatArray(outW * outH)
        val sx = srcW / outW.toFloat()
        val sy = srcH / outH.toFloat()
        for (y in 0 until outH) {
            val gy = (y + 0.5f) * sy - 0.5f
            for (x in 0 until outW) {
                out[y * outW + x] = image.bilinear((x + 0.5f) * sx - 0.5f, gy)
            }
        }
        return out
    }

    private fun confidenceRamp(value: Float, okAt: Float, zeroAt: Float): Float {
        if (value <= okAt) return 1f
        if (value >= zeroAt) return 0f
        return (1f - (value - okAt) / (zeroAt - okAt)).coerceIn(0f, 1f)
    }

    private fun cornerMappings(): List<CornerMapping> {
        val names = arrayOf("image TL", "image TR", "image BR", "image BL")
        val out = ArrayList<CornerMapping>(8)
        for (start in 0..3) {
            out += CornerMapping(start, clockwise = true, label = "target TL -> ${names[start]}, normal")
            out += CornerMapping(start, clockwise = false, label = "target TL -> ${names[start]}, mirrored")
        }
        return out
    }

    private fun mappedCorners(quad: ScreenDetector.Quad, mapping: CornerMapping): Array<Vec2> {
        val corners = arrayOf(quad.tl, quad.tr, quad.br, quad.bl)
        val direction = if (mapping.clockwise) 1 else -1
        return Array(4) { i ->
            val idx = Math.floorMod(mapping.targetTlImageCorner + direction * i, 4)
            corners[idx]
        }
    }

    private fun markerOrientationScore(marker: GrayImage, h: Homography, screenW: Int, screenH: Int): Float {
        val m = minOf(screenW, screenH).toFloat()
        val size = m * 0.10f
        val inset = m * 0.04f
        val tlBlack = sampleScreenRect(
            marker,
            h,
            inset + size * 0.48f,
            inset + size * 0.48f,
            inset + size * 0.72f,
            inset + size * 0.72f,
        )
        val whites = floatArrayOf(
            sampleScreenRect(
                marker,
                h,
                inset + size * 0.10f,
                inset + size * 0.10f,
                inset + size * 0.30f,
                inset + size * 0.30f,
            ),
            sampleScreenRect(
                marker,
                h,
                screenW - inset - size * 0.70f,
                inset + size * 0.30f,
                screenW - inset - size * 0.30f,
                inset + size * 0.70f,
            ),
            sampleScreenRect(
                marker,
                h,
                screenW - inset - size * 0.70f,
                screenH - inset - size * 0.70f,
                screenW - inset - size * 0.30f,
                screenH - inset - size * 0.30f,
            ),
            sampleScreenRect(
                marker,
                h,
                inset + size * 0.30f,
                screenH - inset - size * 0.70f,
                inset + size * 0.70f,
                screenH - inset - size * 0.30f,
            ),
        )
        var whiteMean = 0f
        for (w in whites) whiteMean += w
        whiteMean /= whites.size
        return (whiteMean - tlBlack).coerceAtLeast(0f)
    }

    private fun sampleScreenRect(
        img: GrayImage,
        h: Homography,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        samples: Int = 5,
    ): Float {
        val out = DoubleArray(2)
        var acc = 0f
        var n = 0
        for (yy in 0 until samples) {
            val v = y0 + (yy + 0.5f) / samples * (y1 - y0)
            for (xx in 0 until samples) {
                val u = x0 + (xx + 0.5f) / samples * (x1 - x0)
                h.map(u.toDouble(), v.toDouble(), out)
                acc += img.bilinear(out[0].toFloat(), out[1].toFloat())
                n++
            }
        }
        return if (n == 0) 0f else acc / n
    }

    private fun dist(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun parallelAngleErrorDeg(a0: Vec2, a1: Vec2, b0: Vec2, b1: Vec2): Float {
        val aa = Math.atan2((a1.y - a0.y).toDouble(), (a1.x - a0.x).toDouble())
        val bb = Math.atan2((b1.y - b0.y).toDouble(), (b1.x - b0.x).toDouble())
        var d = Math.abs(aa - bb)
        while (d > Math.PI) d -= Math.PI
        if (d > Math.PI * 0.5) d = Math.PI - d
        return Math.toDegrees(d).toFloat()
    }

    /**
     * gain 그리드 → 네이티브 해상도 알파 감쇠 PNG (M-FR-010).
     * 픽셀값 v = (1-gain)/maxAtt * 255  (0=보정 없음, 255=최대 감쇠), 그레이 PNG.
     */
    fun toAlphaPng(
        gain: FloatArray,
        gw: Int,
        gh: Int,
        outW: Int,
        outH: Int,
        maxAtt: Float,
        screenMargin: Float = SCREEN_SAMPLE_MARGIN,
    ): ByteArray {
        val pixels = IntArray(outW * outH)
        val gridImg = GrayImage(gw, gh, gain)
        for (y in 0 until outH) {
            val gy = gridCoordForScreenPixel(y + 0.5f, outH, gh, screenMargin)
            for (x in 0 until outW) {
                val gx = gridCoordForScreenPixel(x + 0.5f, outW, gw, screenMargin)
                val g = gridImg.bilinear(gx, gy)
                val v = Math.round((1f - g) / maxAtt * 255f).coerceIn(0, 255)
                pixels[y * outW + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    /**
     * 채널별 gain 그리드 → RGB attenuation PNG.
     * R/G/B 픽셀값은 각 채널의 감쇠량이며, 대상 앱은 solid pattern 렌더링 때 채널별로 곱한다.
     */
    fun toRgbAttenuationPng(
        redGain: FloatArray,
        greenGain: FloatArray,
        blueGain: FloatArray,
        gw: Int,
        gh: Int,
        outW: Int,
        outH: Int,
        maxAtt: Float,
        screenMargin: Float = SCREEN_SAMPLE_MARGIN,
    ): ByteArray {
        require(redGain.size == greenGain.size && redGain.size == blueGain.size) {
            "RGB gain grid size mismatch"
        }
        val pixels = IntArray(outW * outH)
        val rImg = GrayImage(gw, gh, redGain)
        val gImg = GrayImage(gw, gh, greenGain)
        val bImg = GrayImage(gw, gh, blueGain)
        for (y in 0 until outH) {
            val gy = gridCoordForScreenPixel(y + 0.5f, outH, gh, screenMargin)
            for (x in 0 until outW) {
                val gx = gridCoordForScreenPixel(x + 0.5f, outW, gw, screenMargin)
                val i = y * outW + x
                pixels[i] = (0xFF shl 24) or
                    (attenuationByte(rImg.bilinear(gx, gy), maxAtt) shl 16) or
                    (attenuationByte(gImg.bilinear(gx, gy), maxAtt) shl 8) or
                    attenuationByte(bImg.bilinear(gx, gy), maxAtt)
            }
        }
        val bmp = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private fun attenuationByte(gain: Float, maxAtt: Float): Int =
        Math.round((1f - gain) / maxAtt * 255f).coerceIn(0, 255)

    fun gridCoordForScreenPixel(
        pixelCenter: Float,
        screenPixels: Int,
        gridCells: Int,
        screenMargin: Float = SCREEN_SAMPLE_MARGIN,
    ): Float {
        val usable = (1f - 2f * screenMargin).coerceAtLeast(1e-5f)
        return ((pixelCenter / screenPixels - screenMargin) / usable) * gridCells - 0.5f
    }

    /**
     * 상대 편차 히트맵 PNG (평가 시각화, M-FR-017).
     * 파랑(-range) ~ 검정(0) ~ 빨강(+range), range 기본 ±5%.
     */
    fun deviationHeatmapPng(grid: FloatArray, gw: Int, gh: Int, range: Float = 0.05f): ByteArray {
        val st = stats(grid)
        val pixels = IntArray(gw * gh)
        for (i in grid.indices) {
            val d = (grid[i] / st.median - 1f) / range
            val r = (d.coerceIn(0f, 1f) * 255).toInt()
            val b = ((-d).coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or b
        }
        val bmp = Bitmap.createBitmap(pixels, gw, gh, Bitmap.Config.ARGB_8888)
        val scale = if (maxOf(gw, gh) <= 1024) 4 else 1
        val scaled = if (scale > 1) {
            Bitmap.createScaledBitmap(bmp, gw * scale, gh * scale, false).also { bmp.recycle() }
        } else {
            bmp
        }
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
        scaled.recycle()
        return bos.toByteArray()
    }
}
