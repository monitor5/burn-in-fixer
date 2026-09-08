package com.burnin.scanner.measure

import com.burnin.scanner.analysis.Analyzer
import com.burnin.scanner.analysis.ScreenDetector

internal object MeasurementPolicy {
    fun score(stats: Analyzer.Stats, rmsLimit: Float, p95Limit: Float): Float {
        require(rmsLimit.isFinite() && rmsLimit > 0f && p95Limit.isFinite() && p95Limit > 0f)
        if (!stats.median.isFinite() || stats.median <= 1e-4f ||
            !stats.rmsDev.isFinite() || stats.rmsDev < 0f || !stats.p95Dev.isFinite() || stats.p95Dev < 0f) return Float.POSITIVE_INFINITY
        return maxOf(stats.rmsDev / rmsLimit, stats.p95Dev / p95Limit)
    }

    fun geometryStable(reference: ScreenDetector.Quad, current: ScreenDetector.Quad, tolerancePx: Float = 4f): Boolean =
        reference.corners.zip(current.corners).all { (a, b) ->
            Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) <= tolerancePx
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
        fun correctionMap(channelGains: Map<Int, FloatArray>, channelWeight: Float): CorrectionMap =
            CorrectionMap(gain, if (isBaseline) emptyMap() else channelGains,
                if (isBaseline) 0f else channelWeight, isBaseline)

        fun consider(candidate: FloatArray, candidateScore: Float): Boolean {
            require(candidate.size == gain.size && candidate.all { it.isFinite() && it in 0f..1f })
            if (!candidateScore.isFinite() || candidateScore >= score - 1e-4f) return false
            gain = candidate.copyOf(); score = candidateScore; isBaseline = false
            return true
        }
    }
}
