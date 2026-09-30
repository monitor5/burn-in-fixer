package com.burnin.scanner.analysis

/**
 * 여러 화각(표준/초광각/망원)의 screen-space 휘도 그리드를 위치별 품질에 따라 합친다.
 *
 * 기존 [Analyzer.crossAgreementConfidence]는 카메라별 자체 중앙값으로 정규화하고
 * 최대-최소 차이로 신뢰도를 매기므로, 카메라 하나가 나쁘면 전체 신뢰도가 떨어지고
 * 화면 일부만 보는 카메라는 자기 시야의 밝기 분포에 맞춰 잘못 정규화된다.
 * 여기서는 다음 순서로 이를 보완한다.
 *
 *  1. 유효 영역: 카메라 이미지 안쪽에 실제로 투영되는 셀만 사용한다 ([coverageMask]).
 *  2. 겹침 정규화: 기준 카메라와 함께 신뢰 가능하게 보는 셀에서만 상대 배율을 구한다
 *     ([normalizeToReference]). 공간적으로 일정한 배율 하나만 맞춰 실제 불균일을 지우지 않는다.
 *  3. 공통 해상도: 가장 거친 화각이 구분할 수 있는 수준으로 저역통과한 뒤 비교한다.
 *  4. 강건한 합의: 셀마다 품질 가중 중앙값에서 출발해 Huber IRLS로 합의값을 구하고,
 *     합의에서 벗어난 가중치 비율로 신뢰도를 만든다 ([fuse]).
 *
 * 품질이 좋은 두 화각이 일치하고 품질이 낮은 하나만 벗어나면 신뢰도를 유지하고,
 * 품질이 비슷한 화각끼리 불일치하면 어느 쪽이 맞는지 알 수 없으므로 신뢰도를 낮춘다
 * ([FusionResult.ambiguous]는 재촬영 후보). 여러 화각에서 재현되는 어두운 영역도
 * 공통 반사나 시야각 특성일 수 있으므로 이 결과만으로 번인이라고 단정하지 않는다.
 */
object MultiViewFusion {

    /** 화면 좌표로 정합된 한 화각의 관측. signal은 black 차감·flat-field 적용 휘도. */
    class View(
        val role: String,
        val signal: FloatArray,
        /** 0..1 위치별 품질 가중치 (SNR·클리핑·프레임 안정도·해상도·정합 등). */
        val quality: FloatArray,
        /** 카메라 이미지 안에 들어오는 셀. null이면 전체 유효. */
        val valid: BooleanArray? = null,
        /** 화면 셀당 카메라 픽셀 수의 대표값. 공통 해상도 저역통과 반경 산정에 쓴다. */
        val cameraPxPerCell: Float = Float.NaN,
    )

    class NormalizedView(
        val role: String,
        /** 기준 카메라 스케일로 맞춘 휘도 (유효하지 않은 셀은 0). */
        val relative: FloatArray,
        val quality: FloatArray,
        val valid: BooleanArray,
        val scale: Float,
        /** 전체 셀 대비 기준 카메라와의 신뢰 가능한 겹침 비율. */
        val overlapFraction: Float,
    )

    class FusionResult(
        /** 품질 가중 강건 합의 휘도 (기준 카메라 스케일). 관측이 없는 셀은 0. */
        val fused: FloatArray,
        /** 0..1 화각 간 합의 신뢰도. 비교할 관측이 하나뿐인 셀은 1(중립). */
        val agreement: FloatArray,
        /** 품질이 충분한 관측이 둘 이상인데 합의가 깨진 셀 — 재촬영 대상. */
        val ambiguous: BooleanArray,
        /** 품질이 충분한 관측이 둘 이상인 셀 비율 — 실제로 교차 검증된 범위. */
        val crossCheckedFraction: Float,
        val ambiguousFraction: Float,
        val meanAgreement: Float,
        /** 저역통과 반경(셀). 0이면 원본 해상도에서 비교. */
        val commonRadius: Int,
        val views: List<NormalizedView>,
        val excluded: Map<String, String>,
    )

    /**
     * 셀의 3x3 샘플 지점(lumaGrid와 동일)이 모두 이미지 경계 안쪽 [borderPx]에 들어오면 유효.
     * 망원처럼 화면 일부만 보는 화각에서 프레임 밖을 clamp 샘플한 값이 섞이는 것을 막는다.
     */
    fun coverageMask(
        h: Homography,
        imageW: Int,
        imageH: Int,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        borderPx: Float = 2f,
    ): BooleanArray {
        require(imageW > 0 && imageH > 0 && screenW > 0 && screenH > 0 && gw > 0 && gh > 0)
        require(borderPx.isFinite() && borderPx >= 0f)
        val out = BooleanArray(gw * gh)
        val p = DoubleArray(2)
        val margin = Analyzer.SCREEN_SAMPLE_MARGIN
        val maxX = imageW - 1 - borderPx
        val maxY = imageH - 1 - borderPx
        for (gy in 0 until gh) {
            val v = (margin + (1 - 2 * margin) * (gy + 0.5f) / gh) * screenH
            for (gx in 0 until gw) {
                val u = (margin + (1 - 2 * margin) * (gx + 0.5f) / gw) * screenW
                var inside = true
                loop@ for (sy in -1..1) {
                    for (sx in -1..1) {
                        val ok = runCatching {
                            h.map(
                                (u + sx * screenW * 0.15 / gw).toDouble(),
                                (v + sy * screenH * 0.15 / gh).toDouble(),
                                p,
                            )
                        }.isSuccess
                        if (!ok || !(p[0] >= borderPx && p[0] <= maxX && p[1] >= borderPx && p[1] <= maxY)) {
                            inside = false
                            break@loop
                        }
                    }
                }
                out[gy * gw + gx] = inside
            }
        }
        return out
    }

    /**
     * 화면 셀당 카메라 픽셀 수(선형). 셀 중심 주변의 호모그래피 야코비안 행렬식으로
     * 국소 면적 배율을 구해 제곱근을 취한다. 초광각 주변부처럼 해상도가 낮은 곳을 찾는 데 쓴다.
     * 계산량을 줄이기 위해 [step] 셀 간격으로 구하고 최근접으로 채운다.
     */
    fun cameraPixelsPerCell(
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        step: Int = 16,
    ): FloatArray {
        require(screenW > 0 && screenH > 0 && gw > 0 && gh > 0 && step > 0)
        val cellW = screenW.toDouble() / gw
        val cellH = screenH.toDouble() / gh
        val p0 = DoubleArray(2)
        val px = DoubleArray(2)
        val py = DoubleArray(2)
        val out = FloatArray(gw * gh)
        var y0 = 0
        while (y0 < gh) {
            var x0 = 0
            while (x0 < gw) {
                val cx = minOf(x0 + step / 2, gw - 1)
                val cy = minOf(y0 + step / 2, gh - 1)
                val u = (cx + 0.5) * cellW
                val v = (cy + 0.5) * cellH
                val d = runCatching {
                    h.map(u, v, p0)
                    h.map(u + cellW, v, px)
                    h.map(u, v + cellH, py)
                    val det = Math.abs((px[0] - p0[0]) * (py[1] - p0[1]) - (px[1] - p0[1]) * (py[0] - p0[0]))
                    Math.sqrt(det).toFloat()
                }.getOrDefault(0f).takeIf { it.isFinite() } ?: 0f
                for (y in y0 until minOf(y0 + step, gh)) {
                    for (x in x0 until minOf(x0 + step, gw)) out[y * gw + x] = d
                }
                x0 += step
            }
            y0 += step
        }
        return out
    }

    /**
     * 해상도 품질: 셀당 카메라 픽셀 수가 [okAt] 이상이면 1, [zeroAt] 이하이면 0.
     * 셀당 1픽셀 미만이면 그 셀 값은 이웃 셀과 섞인 값이므로 비교 가중치를 줄인다.
     */
    fun resolutionQuality(pxPerCell: FloatArray, okAt: Float = 1.0f, zeroAt: Float = 0.25f): FloatArray {
        require(okAt.isFinite() && zeroAt.isFinite() && okAt > zeroAt && zeroAt >= 0f)
        return FloatArray(pxPerCell.size) { ((pxPerCell[it] - zeroAt) / (okAt - zeroAt)).coerceIn(0f, 1f) }
    }

    /** 정합 품질: dot-grid RMS 잔차(px)가 작을수록 1. dot-grid가 없으면(4모서리/마커만) 보수적 값. */
    fun registrationQuality(rmsResidualPx: Float?, cornerOnly: Float = 0.7f): Float {
        if (rmsResidualPx == null || !rmsResidualPx.isFinite()) return cornerOnly
        return (1f - (rmsResidualPx - 1.0f) / (4.0f - 1.0f)).coerceIn(0.2f, 1f)
    }

    /** 여러 품질 지도와 스칼라를 곱해 하나로 만든다. valid가 아닌 셀은 0. */
    fun combineQuality(size: Int, maps: List<FloatArray>, scalar: Float = 1f, valid: BooleanArray? = null): FloatArray {
        require(size > 0 && scalar.isFinite() && scalar >= 0f)
        maps.forEach { require(it.size == size) { "quality grid size mismatch" } }
        if (valid != null) require(valid.size == size) { "valid mask size mismatch" }
        val out = FloatArray(size) { scalar.coerceIn(0f, 1f) }
        for (m in maps) for (i in 0 until size) out[i] *= m[i].coerceIn(0f, 1f)
        if (valid != null) for (i in 0 until size) if (!valid[i]) out[i] = 0f
        return out
    }

    /**
     * 각 화각을 기준 화각(views[referenceIndex]) 스케일로 맞춘다.
     * 배율은 두 화각이 모두 유효하고 품질이 [minQuality] 이상인 셀에서 (view/ref) 비율의
     * 가중 중앙값으로 구한다. 망원이 어두운 번인 영역만 봐도 자기 중앙값이 아니라 같은
     * 화면 위치의 기준값과 비교하므로 번인이 정상 밝기로 정규화되지 않는다.
     * 겹침이 [minOverlapFraction] 미만이면 신뢰할 배율을 구할 수 없어 제외한다.
     */
    fun normalizeToReference(
        views: List<View>,
        gw: Int,
        gh: Int,
        referenceIndex: Int = 0,
        minQuality: Float = 0.3f,
        minOverlapFraction: Float = 0.05f,
        excluded: MutableMap<String, String>? = null,
    ): List<NormalizedView> {
        require(gw > 0 && gh > 0 && gw.toLong() * gh <= Int.MAX_VALUE)
        require(referenceIndex in views.indices)
        require(minQuality.isFinite() && minQuality in 0f..1f)
        require(minOverlapFraction.isFinite() && minOverlapFraction in 0f..1f)
        val size = gw * gh
        views.forEach { requireView(it, size) }
        val ref = views[referenceIndex]
        val refValid = validOf(ref, size)
        val out = ArrayList<NormalizedView>(views.size)
        for ((index, view) in views.withIndex()) {
            val valid = validOf(view, size)
            if (index == referenceIndex) {
                val n = valid.count { it }
                out += NormalizedView(view.role, maskSignal(view.signal, valid), view.quality, valid, 1f, n / size.toFloat())
                continue
            }
            val ratios = ArrayList<Float>()
            val weights = ArrayList<Float>()
            for (i in 0 until size) {
                if (!valid[i] || !refValid[i]) continue
                val w = minOf(view.quality[i], ref.quality[i])
                if (w < minQuality) continue
                val r = ref.signal[i]
                if (r <= 1e-5f) continue
                ratios += view.signal[i] / r
                weights += w
            }
            val overlap = ratios.size / size.toFloat()
            if (ratios.isEmpty() || overlap < minOverlapFraction) {
                excluded?.put(view.role, "기준 화각과 겹침 부족 (${"%.1f".format(overlap * 100)}%)")
                continue
            }
            val scale = weightedMedian(ratios.toFloatArray(), weights.toFloatArray(), ratios.size)
            if (!scale.isFinite() || scale <= 1e-6f) {
                excluded?.put(view.role, "상대 배율 계산 실패")
                continue
            }
            val rel = FloatArray(size)
            for (i in 0 until size) if (valid[i]) rel[i] = view.signal[i] / scale
            out += NormalizedView(view.role, rel, view.quality, valid, scale, overlap)
        }
        return out
    }

    /**
     * 공통 해상도 반경: 가장 거친 화각에서 카메라 1픽셀이 덮는 셀 수의 절반.
     * 선명한 망원 지도를 흐린 초광각 지도와 그대로 비교하면 작은 결함이 불일치로 잡히므로
     * 교차 검증은 모두가 구분할 수 있는 해상도에서 한다.
     */
    fun commonRadius(views: List<View>, maxRadius: Int = 16): Int {
        require(maxRadius >= 0)
        val densities = views.map { it.cameraPxPerCell }.filter { it.isFinite() && it > 0f }
        if (densities.isEmpty()) return 0
        val coarsest = densities.min()
        return Math.ceil(0.5 / coarsest - 0.5).toInt().coerceIn(0, maxRadius)
    }

    /**
     * 품질 가중 강건 합의.
     *
     * 셀마다 유효하고 품질이 [minQuality] 이상인 관측만 사용한다. 가중 중앙값에서 출발해
     * Huber 가중(δ = [okRelativeRange]·합의값)으로 [huberIterations]회 재가중한다.
     * 각 관측의 상대 잔차를 [okRelativeRange]~[zeroRelativeRange] 램프로 일치도로 바꾼 뒤,
     * 불일치 관측의 가중치 비율(conflict)을 [conflictOk]~[conflictZero] 램프로 신뢰도로 만든다.
     * 기본값에서 같은 품질 세 화각 중 하나만 벗어나도(1/3) 신뢰도는 0이 되고,
     * 품질 1인 두 화각과 품질 0.3인 한 화각에서 후자만 벗어나면(≈0.13) 신뢰도는 유지된다.
     */
    fun fuse(
        views: List<View>,
        gw: Int,
        gh: Int,
        referenceIndex: Int = 0,
        minQuality: Float = 0.1f,
        okRelativeRange: Float = 0.02f,
        zeroRelativeRange: Float = 0.06f,
        conflictOk: Float = 0.15f,
        conflictZero: Float = 0.30f,
        ambiguousQuality: Float = 0.5f,
        huberIterations: Int = 3,
        smoothOutput: Boolean = true,
        radiusOverride: Int? = null,
    ): FusionResult {
        require(gw > 0 && gh > 0 && gw.toLong() * gh <= Int.MAX_VALUE)
        require(okRelativeRange.isFinite() && zeroRelativeRange.isFinite() && okRelativeRange >= 0f && zeroRelativeRange > okRelativeRange)
        require(conflictOk.isFinite() && conflictZero.isFinite() && conflictOk >= 0f && conflictZero > conflictOk)
        require(minQuality.isFinite() && minQuality in 0f..1f)
        require(ambiguousQuality.isFinite() && ambiguousQuality in 0f..1f)
        require(huberIterations >= 0)
        val size = gw * gh
        val excluded = LinkedHashMap<String, String>()
        val normalized = if (views.isEmpty()) emptyList() else normalizeToReference(views, gw, gh, referenceIndex, excluded = excluded)
        val radius = radiusOverride ?: commonRadius(views.filter { v -> normalized.any { it.role == v.role } })

        // 공통 해상도로 저역통과. 유효 셀만 평균해 경계 밖 값이 새어 들지 않게 한다.
        val lowpassed = normalized.map { maskedBoxBlur(it.relative, it.valid, gw, gh, radius) }

        val fused = FloatArray(size)
        val agreement = FloatArray(size) { 1f }
        val ambiguous = BooleanArray(size)
        val n = normalized.size
        val values = FloatArray(n)
        val weights = FloatArray(n)
        var crossChecked = 0
        var ambiguousCount = 0
        for (i in 0 until size) {
            var m = 0
            var strong = 0
            for (k in 0 until n) {
                val nv = normalized[k]
                if (!nv.valid[i]) continue
                val q = nv.quality[i]
                if (q < minQuality) continue
                val v = lowpassed[k][i]
                if (!(v > 0f)) continue
                values[m] = v
                weights[m] = q
                if (q >= ambiguousQuality) strong++
                m++
            }
            if (m == 0) continue
            var est = weightedMedian(values, weights, m)
            repeat(huberIterations) {
                val delta = (okRelativeRange * est).coerceAtLeast(1e-6f)
                var num = 0.0
                var den = 0.0
                for (k in 0 until m) {
                    val r = Math.abs(values[k] - est)
                    val hw = weights[k] * if (r <= delta) 1f else delta / r
                    num += hw * values[k]
                    den += hw
                }
                if (den > 0.0) est = (num / den).toFloat()
            }
            fused[i] = est
            if (m < 2) continue
            if (strong >= 2) crossChecked++
            var total = 0.0
            var conflict = 0.0
            for (k in 0 until m) {
                val rel = Math.abs(values[k] - est) / est.coerceAtLeast(1e-5f)
                val agree = Analyzer.confidenceRamp(rel, okRelativeRange, zeroRelativeRange)
                total += weights[k]
                conflict += weights[k] * (1f - agree)
            }
            val c = Analyzer.confidenceRamp((conflict / total).toFloat(), conflictOk, conflictZero)
            agreement[i] = c
            if (strong >= 2 && c < 0.5f) {
                ambiguous[i] = true
                ambiguousCount++
            }
        }
        val smoothed = if (smoothOutput) {
            Analyzer.boxBlur(Analyzer.boxBlur(agreement, gw, gh), gw, gh).also { a ->
                for (i in a.indices) a[i] = a[i].coerceIn(0f, 1f)
            }
        } else {
            agreement
        }
        return FusionResult(
            fused = fused,
            agreement = smoothed,
            ambiguous = ambiguous,
            crossCheckedFraction = crossChecked / size.toFloat(),
            ambiguousFraction = ambiguousCount / size.toFloat(),
            meanAgreement = Analyzer.meanConfidence(smoothed),
            commonRadius = radius,
            views = normalized,
            excluded = excluded,
        )
    }

    /** 두 신뢰도 지도의 평균 절대 차이 — 기존 교차 신뢰도와 새 방식 비교용. */
    fun meanAbsDifference(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty())
        var s = 0.0
        for (i in a.indices) s += Math.abs(a[i] - b[i]).toDouble()
        return (s / a.size).toFloat()
    }

    internal fun weightedMedian(values: FloatArray, weights: FloatArray, count: Int): Float {
        require(count in 1..values.size && count <= weights.size)
        if (count == 1) return values[0]
        val idx = (0 until count).sortedBy { values[it] }
        var total = 0.0
        for (k in 0 until count) total += weights[k].coerceAtLeast(0f)
        if (total <= 0.0) return values[idx[count / 2]]
        val half = total / 2.0
        var acc = 0.0
        for ((pos, k) in idx.withIndex()) {
            acc += weights[k].coerceAtLeast(0f)
            if (acc > half + 1e-9) return values[k]
            // 정확히 절반에서 끊기면 인접 두 값의 평균 (두 화각 동률일 때 한쪽 편향 방지)
            if (Math.abs(acc - half) <= 1e-9 && pos + 1 < count) return (values[k] + values[idx[pos + 1]]) / 2f
        }
        return values[idx.last()]
    }

    private fun maskedBoxBlur(src: FloatArray, valid: BooleanArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius < 1) return src
        val num = FloatArray(src.size)
        val den = FloatArray(src.size)
        for (i in src.indices) if (valid[i]) {
            num[i] = src[i]
            den[i] = 1f
        }
        val bn = Analyzer.boxBlur(num, w, h, radius)
        val bd = Analyzer.boxBlur(den, w, h, radius)
        return FloatArray(src.size) { i -> if (valid[i] && bd[i] > 1e-6f) bn[i] / bd[i] else 0f }
    }

    private fun maskSignal(signal: FloatArray, valid: BooleanArray): FloatArray =
        FloatArray(signal.size) { if (valid[it]) signal[it] else 0f }

    private fun validOf(view: View, size: Int): BooleanArray = view.valid ?: BooleanArray(size) { true }

    private fun requireView(view: View, size: Int) {
        require(view.signal.size == size) { "signal grid size mismatch: ${view.role}" }
        require(view.quality.size == size) { "quality grid size mismatch: ${view.role}" }
        require(view.valid == null || view.valid.size == size) { "valid mask size mismatch: ${view.role}" }
        require(view.signal.all { it.isFinite() && it >= 0f }) { "signal must be finite and nonnegative: ${view.role}" }
        require(view.quality.all { it.isFinite() && it in 0f..1f }) { "quality must be in 0..1: ${view.role}" }
    }
}
