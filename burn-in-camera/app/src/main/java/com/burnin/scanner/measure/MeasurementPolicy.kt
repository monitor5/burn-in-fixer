package com.burnin.scanner.measure

import com.burnin.scanner.analysis.Analyzer
import com.burnin.scanner.analysis.ScreenDetector

internal object MeasurementPolicy {
    /**
     * 1 이하가 합격. 셀 단위 RMS/P95는 면적이 작은 띠 결함을 희석하므로 블록 평균 최대 편차
     * ([Analyzer.blockMaxDev])도 함께 본다. blockDev를 생략하면 기존 두 지표만 쓴다.
     */
    fun score(
        stats: Analyzer.Stats,
        rmsLimit: Float,
        p95Limit: Float,
        blockDev: Float? = null,
        blockLimit: Float = Float.POSITIVE_INFINITY,
    ): Float {
        require(rmsLimit.isFinite() && rmsLimit > 0f && p95Limit.isFinite() && p95Limit > 0f)
        require(blockDev == null || (blockLimit.isFinite() && blockLimit > 0f))
        if (!stats.median.isFinite() || stats.median <= 1e-4f ||
            !stats.rmsDev.isFinite() || stats.rmsDev < 0f || !stats.p95Dev.isFinite() || stats.p95Dev < 0f) return Float.POSITIVE_INFINITY
        if (blockDev != null && (!blockDev.isFinite() || blockDev < 0f)) return Float.POSITIVE_INFINITY
        val cellScore = maxOf(stats.rmsDev / rmsLimit, stats.p95Dev / p95Limit)
        return if (blockDev == null) cellScore else maxOf(cellScore, blockDev / blockLimit)
    }

    fun geometryStable(
        reference: ScreenDetector.Quad,
        current: ScreenDetector.Quad,
        tolerancePx: Float = geometryTolerance(reference),
    ): Boolean = maxCornerShift(reference, current) <= tolerancePx

    fun maxCornerShift(reference: ScreenDetector.Quad, current: ScreenDetector.Quad): Float =
        reference.corners.zip(current.corners).maxOf { (a, b) ->
            Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        }.toFloat()

    /**
     * 화면 이동 허용치: 4px 또는 화면 대각선의 0.5% 중 큰 값. 보정 패턴은 가장자리 밝기를 바꿔
     * 검출 경계가 1~2px 움직일 수 있어, 크게 잡힌 화면에서 고정 4px는 실제 이동이 아닌데도 중단시켰다.
     */
    fun geometryTolerance(reference: ScreenDetector.Quad): Float {
        val c = reference.corners
        val diag = maxOf(
            Math.hypot((c[0].x - c[2].x).toDouble(), (c[0].y - c[2].y).toDouble()),
            Math.hypot((c[1].x - c[3].x).toDouble(), (c[1].y - c[3].y).toDouble()),
        ).toFloat()
        return maxOf(4f, diag * 0.005f)
    }

    /** A perfect baseline has no relative improvement; keep the report finite and deterministic. */
    fun improvement(before: Float, after: Float): Double {
        require(before.isFinite() && before >= 0f && after.isFinite() && after >= 0f)
        return if (before == 0f) 0.0 else 1.0 - after.toDouble() / before
    }

    class Selection(baselineGain: FloatArray, baselineScore: Float) {
        var gain = baselineGain.copyOf(); private set
        var score = baselineScore; private set
        var isBaseline = true; private set
        init { require(baselineScore.isFinite() && baselineScore >= 0f) }
        fun correctionMap(
            channelGains: Map<Int, FloatArray>,
            channelWeight: Float,
            detail: FloatArray? = null,
            maxAttenuation: Float = 1f,
        ): CorrectionMap =
            CorrectionMap(gain, if (isBaseline) emptyMap() else channelGains,
                if (isBaseline) 0f else channelWeight, isBaseline, detail, maxAttenuation)

        fun consider(candidate: FloatArray, candidateScore: Float): Boolean {
            require(candidate.size == gain.size && candidate.all { it.isFinite() && it in 0f..1f })
            if (!candidateScore.isFinite() || candidateScore >= score - 1e-4f) return false
            gain = candidate.copyOf(); score = candidateScore; isBaseline = false
            return true
        }
    }
}
