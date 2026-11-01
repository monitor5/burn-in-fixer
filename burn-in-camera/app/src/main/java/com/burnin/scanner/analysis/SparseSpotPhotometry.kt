package com.burnin.scanner.analysis

/**
 * 희소 점등 측광 (sparse spot photometry): 글씨 획·버튼 테두리 같은 1~3px 번인을 화면 픽셀 단위로 잰다.
 *
 * 전면 gray 촬영은 카메라 1픽셀이 화면 여러 픽셀을 덮고, 서브픽셀 구조와 센서 격자가 간섭(모아레)하며,
 * 정합 오차가 1px만 나도 보정이 획 옆으로 밀린다. 그래서 지금까지는 맵을 스무딩할 수밖에 없었다.
 *
 * 여기서는 대상 화면에서 [Plan.block]×[Plan.block] 픽셀 블록만 [Plan.pitch] 간격으로 켠 패턴을
 * 위상을 바꿔 (pitch/block)² 장 찍는다. 켜진 블록끼리는 카메라 픽셀 여러 개만큼 떨어져 있으므로
 *  1. 블록마다 무게중심을 찾아 국소 정합한다 (렌즈 왜곡·호모그래피 오차가 블록 단위로 흡수된다),
 *  2. 무게중심에 맞춘 소수 경계 사각 창(이웃과 겹치지 않는 반 피치 크기)으로 광량을 통째로 적분한다
 *     (서브픽셀 배치·센서 격자와 무관해져 모아레가 사라진다),
 *  3. 남는 소수부 위상별 계통 오차는 모든 블록의 위상별 중앙값으로 정규화한다.
 * 입력 프레임은 black 프레임을 뺀 선형 휘도여야 한다 (배경을 창 테두리에서 추정하면 이웃 블록의
 * PSF 꼬리가 섞인다).
 * 결과는 화면 픽셀(블록) 해상도의 휘도맵이다.
 *
 * 점만 켠 화면은 전면 점등과 OLED 구동 조건(APL·IR drop)이 다르므로, 넓은 성분은 기존 전면 측정을 쓰고
 * 이 측정에서는 고주파 비율 성분([detailRatio])만 가져와 별도 감쇠 층([detailGain])으로 곱한다.
 *
 * Android 클래스에 의존하지 않는 순수 Kotlin이다 (JVM 단위 테스트 대상).
 */
object SparseSpotPhotometry {

    const val PATTERN_PREFIX = "sparse"

    /** 이웃 블록 중심 간 최소 카메라 픽셀 간격. 이보다 가까우면 PSF 꼬리가 이웃 창으로 샌다. */
    const val MIN_SPACING_CAMERA_PX = 5f

    /** 한 축 위상 수 상한. 패턴 수는 그 제곱이다 (6 → 36장). 2px 블록에 쓴다. */
    const val MAX_PHASES_PER_AXIS = 6

    /**
     * 1px 블록의 한 축 위상 수 상한(10 → 100장). 카메라 해상도가 모자랄 때 2px 블록으로 바꾸면
     * 획 단위(1px) 정밀도를 잃으므로, 시간이 더 들더라도 점 간격을 넓혀 1px 점을 유지한다.
     */
    const val MAX_PHASES_PER_AXIS_FINE = 10

    /** 고주파 층이 단독으로 줄 수 있는 최대 감쇠. 이보다 어두운 결함은 이 층에서 일부만 보정한다. */
    const val MAX_DETAIL_ATTENUATION = 0.06f

    private const val PHASE_BINS = 4
    private const val MIN_PHASE_BIN_SAMPLES = 16
    private const val MIN_VALID_FLUX_FRACTION = 0.3f

    class Plan(val block: Int, val pitch: Int, val level: Int = 70) {
        init {
            require(block in 1..4 && pitch >= 2 * block && pitch % block == 0) { "invalid sparse plan" }
            require(level in 1..100)
        }

        val phasesPerAxis: Int get() = pitch / block
        val patternCount: Int get() = phasesPerAxis * phasesPerAxis

        /** [channel]이 0/1/2면 그 채널(R/G/B)만 켜는 패턴, null이면 흰색. */
        fun patternName(phaseX: Int, phaseY: Int, channel: Int? = null): String {
            require(phaseX in 0 until phasesPerAxis && phaseY in 0 until phasesPerAxis)
            require(channel == null || channel in 0..2)
            val suffix = channel?.let { "_c" + "rgb"[it] } ?: ""
            return "$PATTERN_PREFIX${level}_b${block}_p${pitch}_x${phaseX}_y$phaseY$suffix"
        }

        fun phases(): List<Pair<Int, Int>> =
            (0 until phasesPerAxis).flatMap { py -> (0 until phasesPerAxis).map { px -> px to py } }

        override fun toString() = "block ${block}px, pitch ${pitch}px, ${patternCount}패턴"
    }

    /**
     * 카메라 해상도에 맞는 패턴 계획. 1px 블록을 우선하고, 위상이 너무 많아지면 2px 블록으로 바꾼다.
     * 둘 다 안 되면 null (이 해상도로는 획 단위 측정 불가 → 망원/근접 촬영 필요).
     */
    fun plan(
        cameraPxPerScreenPx: Float,
        level: Int = 70,
        minSpacingCameraPx: Float = MIN_SPACING_CAMERA_PX,
        maxPhasesPerAxis: Int = MAX_PHASES_PER_AXIS,
        maxPhasesPerAxisFine: Int = MAX_PHASES_PER_AXIS_FINE,
    ): Plan? {
        if (!cameraPxPerScreenPx.isFinite() || cameraPxPerScreenPx <= 0f) return null
        val minPitch = Math.ceil((minSpacingCameraPx / cameraPxPerScreenPx).toDouble()).toInt()
        for (block in 1..2) {
            var pitch = maxOf(minPitch, 2 * block, 3)
            if (pitch % block != 0) pitch += block - pitch % block
            val limit = if (block == 1) maxOf(maxPhasesPerAxis, maxPhasesPerAxisFine) else maxPhasesPerAxis
            if (pitch / block <= limit) return Plan(block, pitch, level)
        }
        return null
    }

    /** 화면 중앙에서 화면 1px가 카메라 몇 px인지 (가로·세로 평균). */
    fun cameraPxPerScreenPx(h: Homography, screenW: Int, screenH: Int): Float {
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        val c = DoubleArray(2)
        val u = screenW * 0.5
        val v = screenH * 0.5
        h.map(u, v, a)
        h.map(u + 1.0, v, b)
        h.map(u, v + 1.0, c)
        val sx = Math.hypot(b[0] - a[0], b[1] - a[1])
        val sy = Math.hypot(c[0] - a[0], c[1] - a[1])
        return ((sx + sy) * 0.5).toFloat()
    }

    /** 선형 휘도 프레임 (풀해상도). */
    class Frame(val w: Int, val h: Int, val data: FloatArray) {
        init { require(w > 0 && h > 0 && data.size == w * h) }
        operator fun get(x: Int, y: Int): Float = data[y * w + x]
    }

    /**
     * 한 위상 패턴의 블록 배치와 누적 측정값. 블록 (i,j)의 화면 좌상단은
     * (phaseX·block + i·pitch, phaseY·block + j·pitch).
     */
    class PhaseLayout internal constructor(
        val plan: Plan,
        val phaseX: Int,
        val phaseY: Int,
        val nx: Int,
        val ny: Int,
        val window: Int,
        /** 적분 창 반폭 (카메라 px, 소수). 이웃 블록 중심까지 거리의 절반. */
        val aperture: Float,
    ) {
        val size = nx * ny
        /** 블록 무게중심 (카메라 px) */
        val centerX = FloatArray(size)
        val centerY = FloatArray(size)
        /** 무게중심의 소수부 위상 (창 중심 기준, -0.5..0.5) */
        val fracX = FloatArray(size)
        val fracY = FloatArray(size)
        val located = BooleanArray(size)
        val fluxSum = DoubleArray(size)
        val fluxSumSq = DoubleArray(size)
        var frames = 0
            internal set

        fun screenX(i: Int) = plan.block * phaseX + i * plan.pitch
        fun screenY(j: Int) = plan.block * phaseY + j * plan.pitch
    }

    /**
     * 첫 프레임에서 블록 위치를 찾는다. 화면 중앙 블록부터 바깥으로 넓혀 가며, 이미 찾은 이웃의
     * 정합 오프셋을 예측에 더한다. 그래서 렌즈 왜곡처럼 매끄럽게 변하는 오차는 중앙에서
     * 반 피치 이내이기만 하면 전부 따라간다.
     */
    fun locate(frame: Frame, h: Homography, screenW: Int, screenH: Int, plan: Plan, phaseX: Int, phaseY: Int): PhaseLayout {
        require(screenW > 0 && screenH > 0)
        val ox = plan.block * phaseX
        val oy = plan.block * phaseY
        val nx = if (ox + plan.block > screenW) 0 else (screenW - plan.block - ox) / plan.pitch + 1
        val ny = if (oy + plan.block > screenH) 0 else (screenH - plan.block - oy) / plan.pitch + 1
        val spacing = cameraPxPerScreenPx(h, screenW, screenH) * plan.pitch
        val window = maxOf(1, Math.floor(spacing / 2.0 - 0.5).toInt())
        val aperture = maxOf(1f, spacing / 2f)
        val layout = PhaseLayout(plan, phaseX, phaseY, nx, ny, window, aperture)
        if (layout.size == 0) return layout

        val offX = FloatArray(layout.size)
        val offY = FloatArray(layout.size)
        val done = BooleanArray(layout.size)
        val ci = nx / 2
        val cj = ny / 2
        val maxRing = maxOf(ci, nx - 1 - ci, cj, ny - 1 - cj)
        val p = DoubleArray(2)
        val maxJump = spacing * 0.4f
        val ringCells = ArrayList<Int>()
        for (ring in 0..maxRing) {
            ringCells.clear()
            if (ring == 0) {
                ringCells += cj * nx + ci
            } else {
                // 링 둘레만 순회 (위·아래 변, 좌·우 변)
                for (i in ci - ring..ci + ring) {
                    if (i !in 0 until nx) continue
                    if (cj - ring >= 0) ringCells += (cj - ring) * nx + i
                    if (cj + ring < ny) ringCells += (cj + ring) * nx + i
                }
                for (j in cj - ring + 1..cj + ring - 1) {
                    if (j !in 0 until ny) continue
                    if (ci - ring >= 0) ringCells += j * nx + (ci - ring)
                    if (ci + ring < nx) ringCells += j * nx + (ci + ring)
                }
            }
            for (k in ringCells) {
                val i = k % nx
                val j = k / nx
                // 이전 링의 이웃 오프셋 평균을 예측에 사용
                var sx = 0f
                var sy = 0f
                var n = 0
                for (dj in -1..1) for (di in -1..1) {
                    val ii = i + di
                    val jj = j + dj
                    if (ii !in 0 until nx || jj !in 0 until ny) continue
                    val kk = jj * nx + ii
                    if (done[kk] && layout.located[kk]) {
                        sx += offX[kk]; sy += offY[kk]; n++
                    }
                }
                val predOffX = if (n > 0) sx / n else 0f
                val predOffY = if (n > 0) sy / n else 0f
                h.map(layout.screenX(i) + plan.block * 0.5, layout.screenY(j) + plan.block * 0.5, p)
                val px = p[0].toFloat() + predOffX
                val py = p[1].toFloat() + predOffY
                done[k] = true
                val found = centroid(frame, px, py, window) ?: continue
                val dx = found[0] - px
                val dy = found[1] - py
                // 이웃 정보로 예측한 블록은 크게 튀면 버린다. 이웃이 없으면(탐색 시작점) 가까운 블록을
                // 그대로 받아들이고, 피치 단위로 잘못 붙은 경우는 [assembleAligned]가 바로잡는다.
                if (n > 0 && dx * dx + dy * dy > maxJump * maxJump) continue
                offX[k] = found[0] - p[0].toFloat()
                offY[k] = found[1] - p[1].toFloat()
                if (!apertureInside(frame, found[0], found[1], aperture)) continue
                layout.centerX[k] = found[0]
                layout.centerY[k] = found[1]
                layout.fracX[k] = found[0] - Math.round(found[0])
                layout.fracY[k] = found[1] - Math.round(found[1])
                layout.located[k] = true
            }
        }
        return layout
    }

    /**
     * 양의 신호(배경 차감된 입력)로 무게중심을 수렴할 때까지(최대 6회) 반복한다. 시작점이 두 블록 사이면
     * 첫 결과도 사이에 놓이므로, 한두 번으로 끝내면 두 블록을 반씩 적분하게 된다. 신호가 없으면 null.
     */
    private fun centroid(frame: Frame, startX: Float, startY: Float, window: Int): FloatArray? {
        var cx = startX
        var cy = startY
        for (iter in 0 until 6) {
            val ix = Math.round(cx)
            val iy = Math.round(cy)
            if (ix - window < 0 || iy - window < 0 || ix + window >= frame.w || iy + window >= frame.h) return null
            var sw = 0.0
            var sx = 0.0
            var sy = 0.0
            for (y in iy - window..iy + window) {
                for (x in ix - window..ix + window) {
                    val v = frame[x, y]
                    if (v > 0f) {
                        sw += v; sx += v * x; sy += v * y
                    }
                }
            }
            if (sw <= 0.0) return null
            val nx = (sx / sw).toFloat()
            val ny = (sy / sw).toFloat()
            val moved = Math.abs(nx - cx) + Math.abs(ny - cy)
            cx = nx
            cy = ny
            if (moved < 0.05f && iter >= 1) break
        }
        return floatArrayOf(cx, cy)
    }

    private fun apertureInside(frame: Frame, cx: Float, cy: Float, r: Float): Boolean =
        cx - r >= -0.5f && cy - r >= -0.5f && cx + r <= frame.w - 0.5f && cy + r <= frame.h - 0.5f

    /** 픽셀 [x-0.5, x+0.5]와 구간 [c-r, c+r]의 겹침 길이 */
    private fun overlap(x: Int, c: Float, r: Float): Float =
        (minOf(x + 0.5f, c + r) - maxOf(x - 0.5f, c - r)).coerceIn(0f, 1f)

    /** 중심 (cx,cy), 반폭 r의 소수 경계 사각 창 적분 */
    private fun apertureSum(frame: Frame, cx: Float, cy: Float, r: Float): Double {
        val x0 = Math.floor((cx - r + 0.5f).toDouble()).toInt().coerceAtLeast(0)
        val x1 = Math.ceil((cx + r - 0.5f).toDouble()).toInt().coerceAtMost(frame.w - 1)
        val y0 = Math.floor((cy - r + 0.5f).toDouble()).toInt().coerceAtLeast(0)
        val y1 = Math.ceil((cy + r - 0.5f).toDouble()).toInt().coerceAtMost(frame.h - 1)
        var acc = 0.0
        for (y in y0..y1) {
            val wy = overlap(y, cy, r)
            if (wy <= 0f) continue
            val row = y * frame.w
            var rowAcc = 0.0
            for (x in x0..x1) {
                val wx = overlap(x, cx, r)
                if (wx > 0f) rowAcc += frame.data[row + x] * wx
            }
            acc += rowAcc * wy
        }
        return acc
    }

    /** 한 프레임(black 차감)의 블록별 적분 광량을 누적한다. 같은 위상 패턴의 프레임마다 호출. */
    fun accumulate(frame: Frame, layout: PhaseLayout) {
        val r = layout.aperture
        for (k in 0 until layout.size) {
            if (!layout.located[k]) continue
            if (!apertureInside(frame, layout.centerX[k], layout.centerY[k], r)) {
                layout.located[k] = false
                continue
            }
            val acc = apertureSum(frame, layout.centerX[k], layout.centerY[k], r)
            layout.fluxSum[k] += acc
            layout.fluxSumSq[k] += acc * acc
        }
        layout.frames++
    }

    /** 화면 픽셀 해상도의 희소 측정 결과. 측정 안 된 픽셀은 valid=false. */
    class SparseMap(
        val width: Int,
        val height: Int,
        /** 위상별 중앙값 1로 정규화한 상대 휘도 */
        val value: FloatArray,
        /** 평균의 상대 표준오차 (프레임 간 분산 기반) */
        val relNoise: FloatArray,
        val valid: BooleanArray,
    ) {
        val validFraction: Float get() = valid.count { it } / valid.size.toFloat()
    }

    /**
     * 위상 패턴들을 하나의 화면 해상도 맵으로 합친다. 패턴별 중앙값으로 정규화해 패턴 사이의
     * 노출·밝기 드리프트를 지우고, 패턴 안에서는 소수부 위상별 계통 오차를 지운다.
     */
    fun assemble(layouts: List<PhaseLayout>, screenW: Int, screenH: Int): SparseMap {
        val n = screenW * screenH
        val value = FloatArray(n) { 1f }
        val noise = FloatArray(n)
        val valid = BooleanArray(n)
        for (layout in layouts) {
            if (layout.frames == 0) continue
            val mean = DoubleArray(layout.size) { k -> if (layout.located[k]) layout.fluxSum[k] / layout.frames else Double.NaN }
            val positive = mean.filter { it.isFinite() && it > 0.0 }.sorted()
            if (positive.isEmpty()) continue
            val typical = positive[positive.size / 2]
            for (k in mean.indices) {
                if (!(mean[k] > typical * MIN_VALID_FLUX_FRACTION)) mean[k] = Double.NaN
            }
            val phaseCorr = phaseCorrection(layout, mean)
            val accepted = ArrayList<Double>()
            for (k in mean.indices) {
                if (!mean[k].isFinite()) continue
                val b = phaseBin(layout.fracX[k]) * PHASE_BINS + phaseBin(layout.fracY[k])
                mean[k] = mean[k] / phaseCorr[b]
                accepted += mean[k]
            }
            if (accepted.isEmpty()) continue
            accepted.sort()
            val median = accepted[accepted.size / 2]
            val block = layout.plan.block
            for (j in 0 until layout.ny) {
                for (i in 0 until layout.nx) {
                    val k = j * layout.nx + i
                    val m = mean[k]
                    if (!m.isFinite()) continue
                    val rel = (m / median).toFloat()
                    val variance = if (layout.frames > 1) {
                        val raw = layout.fluxSum[k] / layout.frames
                        (layout.fluxSumSq[k] / layout.frames - raw * raw).coerceAtLeast(0.0) / (layout.frames - 1)
                    } else {
                        0.0
                    }
                    val rawMean = layout.fluxSum[k] / layout.frames
                    val relErr = if (rawMean > 0.0) (Math.sqrt(variance) / rawMean).toFloat() else 1f
                    for (y in layout.screenY(j) until minOf(screenH, layout.screenY(j) + block)) {
                        for (x in layout.screenX(i) until minOf(screenW, layout.screenX(i) + block)) {
                            val idx = y * screenW + x
                            value[idx] = rel
                            noise[idx] = relErr
                            valid[idx] = true
                        }
                    }
                }
            }
        }
        return SparseMap(screenW, screenH, value, noise, valid)
    }

    private fun phaseBin(frac: Float): Int = ((frac + 0.5f) * PHASE_BINS).toInt().coerceIn(0, PHASE_BINS - 1)

    private fun phaseCorrection(layout: PhaseLayout, mean: DoubleArray): DoubleArray {
        val bins = Array(PHASE_BINS * PHASE_BINS) { ArrayList<Double>() }
        val all = ArrayList<Double>()
        for (k in mean.indices) {
            if (!mean[k].isFinite()) continue
            bins[phaseBin(layout.fracX[k]) * PHASE_BINS + phaseBin(layout.fracY[k])] += mean[k]
            all += mean[k]
        }
        val corr = DoubleArray(bins.size) { 1.0 }
        if (all.isEmpty()) return corr
        val overall = median(all)
        for (b in bins.indices) {
            if (bins[b].size >= MIN_PHASE_BIN_SAMPLES) corr[b] = median(bins[b]) / overall
        }
        return corr
    }

    private fun median(values: MutableList<Double>): Double {
        values.sort()
        return values[values.size / 2]
    }

    /**
     * 희소 맵을 전면 측정 맵(같은 화면 해상도)과 맞춰 볼 때, 블록 배정이 피치 단위로 통째로 밀렸는지
     * 확인한다. sparse(x)와 reference(x+lag)의 고주파 상관이 최대가 되는 정수 lag를 돌려주며,
     * 바로잡을 때는 shift(map, -lag[0], -lag[1])을 적용한다. 특징이 없으면 (0,0).
     */
    fun alignmentLag(
        sparse: SparseMap,
        reference: FloatArray,
        maxLag: Int,
        highPassRadius: Int,
        minGain: Float = 1.5f,
        step: Int = 1,
    ): IntArray {
        val w = sparse.width
        val h = sparse.height
        require(reference.size == w * h && maxLag >= 0 && highPassRadius >= 1 && step >= 1)
        val s = highPass(sparse.value, sparse.valid, w, h, highPassRadius)
        val refValid = BooleanArray(w * h) { reference[it].isFinite() && reference[it] > 0f }
        val r = highPass(reference, refValid, w, h, highPassRadius)
        // 유효 픽셀만 모아 둔다 (단일 위상 맵은 1/(pitch/block)²만 유효하므로 격자 건너뛰기 샘플링은 위상을 놓친다)
        val idx = ArrayList<Int>()
        for (y in maxLag until h - maxLag) for (x in maxLag until w - maxLag) {
            val i = y * w + x
            if (sparse.valid[i]) idx += i
        }
        fun corr(dx: Int, dy: Int): Double {
            val off = dy * w + dx
            var acc = 0.0
            for (i in idx) acc += s[i] * r[i + off]
            return acc
        }
        val zero = corr(0, 0)
        var best = zero
        var bestDx = 0
        var bestDy = 0
        for (dy in -maxLag..maxLag) for (dx in -maxLag..maxLag) {
            if (dx == 0 && dy == 0) continue
            if (dx % step != 0 || dy % step != 0) continue
            val c = corr(dx, dy)
            if (c > best) {
                best = c; bestDx = dx; bestDy = dy
            }
        }
        // 피크가 0이 아닌 곳에 있고 0 지연보다 확실히 클 때만 이동 (특징 없는 화면에서의 우연 방지)
        return if ((bestDx != 0 || bestDy != 0) && best > 0.0 && best > minGain * maxOf(zero, 0.0)) {
            intArrayOf(bestDx, bestDy)
        } else {
            intArrayOf(0, 0)
        }
    }

    /**
     * 위상 패턴마다 따로 조립하고, 전면 측정 맵과 비교해 피치 단위 배정 오류를 위상별로 바로잡은 뒤 합친다.
     * 위상마다 탐색 시작점이 달라 서로 다른 피치만큼 밀릴 수 있기 때문이다. 반환: 합친 맵과 위상별 이동량.
     */
    fun assembleAligned(
        layouts: List<PhaseLayout>,
        screenW: Int,
        screenH: Int,
        reference: FloatArray,
        highPassRadius: Int,
    ): Pair<SparseMap, List<IntArray>> {
        val n = screenW * screenH
        val value = FloatArray(n) { 1f }
        val noise = FloatArray(n)
        val valid = BooleanArray(n)
        val lags = ArrayList<IntArray>()
        for (layout in layouts) {
            val single = assemble(listOf(layout), screenW, screenH)
            val pitch = layout.plan.pitch
            val lag = alignmentLag(single, reference, maxLag = pitch, highPassRadius = highPassRadius, step = pitch)
            lags += lag
            val fixed = shift(single, -lag[0], -lag[1])
            for (i in 0 until n) {
                if (fixed.valid[i]) {
                    value[i] = fixed.value[i]; noise[i] = fixed.relNoise[i]; valid[i] = true
                }
            }
        }
        return SparseMap(screenW, screenH, value, noise, valid) to lags
    }

    /** 맵을 (dx,dy)만큼 이동한다: out(x,y) = in(x+dx, y+dy). */
    fun shift(map: SparseMap, dx: Int, dy: Int): SparseMap {
        if (dx == 0 && dy == 0) return map
        val w = map.width
        val h = map.height
        val value = FloatArray(w * h) { 1f }
        val noise = FloatArray(w * h)
        val valid = BooleanArray(w * h)
        for (y in 0 until h) {
            val sy = y + dy
            if (sy !in 0 until h) continue
            for (x in 0 until w) {
                val sx = x + dx
                if (sx !in 0 until w) continue
                val s = sy * w + sx
                val d = y * w + x
                value[d] = map.value[s]; noise[d] = map.relNoise[s]; valid[d] = map.valid[s]
            }
        }
        return SparseMap(w, h, value, noise, valid)
    }

    /**
     * 고주파 비율 R = sparse / lowPass(sparse). lowPass는 기존 전면 측정 경로(반경 [lowPassRadius]의
     * 박스 블러 2회)와 같은 커널이라, 전면 경로의 gain과 곱하면 서로 빈 대역이나 겹치는 대역이 없다.
     * 측정 노이즈 수준의 편차는 Wiener 수축으로 1에 가깝게 당긴다 (노이즈가 맵에 새겨지지 않게).
     */
    fun detailRatio(map: SparseMap, lowPassRadius: Int, noiseScale: Float = 1f): FloatArray {
        val w = map.width
        val h = map.height
        require(lowPassRadius >= 1)
        val low = weightedBox(weightedBox(map.value, map.valid, w, h, lowPassRadius), map.valid, w, h, lowPassRadius)
        // 프레임 몇 장으로 구한 블록별 분산은 그 자체가 흔들리므로, 전체 중앙값을 노이즈 하한으로 쓴다.
        val noiseFloor = map.relNoise.indices.filter { map.valid[it] }.map { map.relNoise[it] }.sorted()
            .let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val out = FloatArray(w * h) { 1f }
        for (i in out.indices) {
            if (!map.valid[i] || low[i] <= 0f) continue
            val d = map.value[i] / low[i] - 1f
            val s = maxOf(map.relNoise[i], noiseFloor) * noiseScale
            val shrink = if (d == 0f) 0f else d * d / (d * d + s * s)
            out[i] = 1f + d * shrink
        }
        return out
    }

    /**
     * 고주파 감쇠 층의 기준 q. 수축 전 비율 R의 하위 0.5% 값에, 노이즈만으로 생기는 같은 분위수 깊이
     * (2.58σ, σ는 블록별 표준오차의 중앙값)를 되돌린다. 그래서 노이즈뿐인 화면은 q≈1(감쇠 없음)이고,
     * 실제 획은 노이즈 폭과 무관하게 획 깊이로 잡힌다. [MAX_DETAIL_ATTENUATION]보다 깊게 내려가지 않는다.
     */
    fun detailReference(map: SparseMap, lowPassRadius: Int, maxAtt: Float = MAX_DETAIL_ATTENUATION): Float {
        val w = map.width
        val h = map.height
        val low = weightedBox(weightedBox(map.value, map.valid, w, h, lowPassRadius), map.valid, w, h, lowPassRadius)
        val raw = ArrayList<Float>()
        val noise = ArrayList<Float>()
        for (i in map.value.indices) {
            if (!map.valid[i] || low[i] <= 0f) continue
            raw += map.value[i] / low[i]
            noise += map.relNoise[i]
        }
        if (raw.isEmpty()) return 1f
        raw.sort()
        noise.sort()
        val sigma = noise[noise.size / 2]
        return (raw[(raw.size * 0.005f).toInt().coerceAtMost(raw.size - 1)] + 2.58f * sigma).coerceIn(1f - maxAtt, 1f)
    }

    /**
     * 고주파 감쇠 층 L = q / R ([detailReference]의 q 기준). 어두운 획보다 밝은 주변만 낮춘다.
     * confidence가 있으면 신뢰도만큼만 적용한다.
     */
    fun detailGain(
        ratio: FloatArray,
        valid: BooleanArray,
        reference: Float,
        confidence: FloatArray? = null,
        maxAtt: Float = MAX_DETAIL_ATTENUATION,
    ): FloatArray {
        require(ratio.size == valid.size && (confidence == null || confidence.size == ratio.size))
        require(maxAtt in 0f..0.5f && reference.isFinite() && reference > 0f)
        return FloatArray(ratio.size) { i ->
            if (!valid[i]) {
                1f
            } else {
                val raw = (reference / ratio[i].coerceAtLeast(1e-3f)).coerceIn(1f - maxAtt, 1f)
                val conf = confidence?.get(i)?.coerceIn(0f, 1f) ?: 1f
                1f - (1f - raw) * conf
            }
        }
    }

    /**
     * 고주파 층을 적용한 뒤 저해상도(전면) 재측정에 보일 평균 감쇠. 반복 보정 루프에서 재측정값을
     * 이 값으로 나눠, 고주파 층의 평균 감쇠를 저주파 루프가 '되돌리려' 하지 않게 한다.
     */
    fun lowFrequencyCompensation(detailGain: FloatArray, ratio: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        require(detailGain.size == w * h && ratio.size == w * h && radius >= 1)
        val prod = FloatArray(w * h) { detailGain[it] * ratio[it] }
        val all = BooleanArray(w * h) { true }
        return weightedBox(weightedBox(prod, all, w, h, radius), all, w, h, radius)
    }

    private fun highPass(src: FloatArray, valid: BooleanArray, w: Int, h: Int, radius: Int): FloatArray {
        val low = weightedBox(src, valid, w, h, radius)
        return FloatArray(src.size) { i -> if (valid[i] && low[i] > 0f) src[i] / low[i] - 1f else 0f }
    }

    /** 유효 픽셀만 평균하는 박스 블러 (적분 영상, O(w·h)). 유효 이웃이 없으면 1. */
    fun weightedBox(src: FloatArray, valid: BooleanArray, w: Int, h: Int, radius: Int): FloatArray {
        val stride = w + 1
        val sum = DoubleArray(stride * (h + 1))
        val cnt = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            var rowCnt = 0
            for (x in 0 until w) {
                val i = y * w + x
                if (valid[i]) {
                    rowSum += src[i]; rowCnt++
                }
                val o = (y + 1) * stride + (x + 1)
                sum[o] = sum[o - stride] + rowSum
                cnt[o] = cnt[o - stride] + rowCnt
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(w, x + radius + 1)
                val a = y1 * stride + x1
                val b = y0 * stride + x1
                val c = y1 * stride + x0
                val d = y0 * stride + x0
                val n = cnt[a] - cnt[b] - cnt[c] + cnt[d]
                out[y * w + x] = if (n > 0) ((sum[a] - sum[b] - sum[c] + sum[d]) / n).toFloat() else 1f
            }
        }
        return out
    }
}
