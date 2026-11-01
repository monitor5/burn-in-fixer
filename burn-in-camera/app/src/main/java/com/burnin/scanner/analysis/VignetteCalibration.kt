package com.burnin.scanner.analysis

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/**
 * 여러 위치 촬영으로 카메라 비네팅(잔여 셰이딩)을 화면 번인과 분리한다.
 *
 * 같은 화면을 카메라 프레임의 서로 다른 위치에 놓고 찍으면, 화면 번인 S는 화면 좌표에 붙어
 * 따라 움직이고 비네팅 F는 카메라 좌표에 고정돼 있다. 화면 블록 b를 k번째 위치에서 잰 값은
 *   log v[k,b] = log S[b] + g(x[k,b], y[k,b]),   g(x,y) = c1·r² + c2·r⁴ (+ c3·r⁶)
 * 이다(r = 이미지 중심 기준 정규화 거리, g(0) = 0으로 밝기 배율 모호성을 없앤다).
 * 블록마다 위치 평균을 빼서 S를 소거하고 c를 한 번의 최소제곱으로 푼다. 비네팅은 광축 대칭이라 위·아래·가운데처럼 한 방향 이동만으로도
 * 반지름 방향이 충분히 바뀌어 풀린다.
 */
object VignetteCalibration {

    /** 비네팅 모델: 카메라 이미지 정규화 좌표(중심 0, 긴 변 반폭 1)에서 상대 감도. */
    data class Model(
        val c1: Double,
        val c2: Double,
        val c3: Double,
        /** 보정 촬영 데이터가 닿은 최대 반지름. 그 밖은 다항식 외삽이 폭주하므로 이 값에서 멈춘다. */
        val rMax: Double = Double.POSITIVE_INFINITY,
    ) {
        fun logGain(x: Double, y: Double): Double {
            val r2 = minOf(x * x + y * y, rMax * rMax)
            return c1 * r2 + c2 * r2 * r2 + c3 * r2 * r2 * r2
        }

        fun gain(x: Double, y: Double): Double = exp(logGain(x, y))

        fun toJson(): JSONObject = JSONObject().put("c1", c1).put("c2", c2).put("c3", c3)
            .apply { if (rMax.isFinite()) put("rMax", rMax) }

        companion object {
            fun fromJson(o: JSONObject) = Model(
                o.getDouble("c1"), o.getDouble("c2"), o.optDouble("c3", 0.0),
                o.optDouble("rMax", Double.POSITIVE_INFINITY).takeIf { !it.isNaN() } ?: Double.POSITIVE_INFINITY,
            )
        }
    }

    /** 한 위치의 화면 블록 측정값과 그 블록 중심의 카메라 정규화 좌표. 값이 0 이하면 무시한다. */
    class Position(val values: FloatArray, val xs: FloatArray, val ys: FloatArray) {
        init { require(values.size == xs.size && values.size == ys.size) }
    }

    class Result(val model: Model, val rmsResidual: Double, val positions: Int, val blocks: Int) {
        /** 모서리(r=1) 감도. 1보다 작을수록 모서리가 어둡다. */
        val cornerGain: Double get() = model.gain(1.0, 0.0)
    }

    /** 이미지 픽셀 좌표 → 정규화 좌표(중심 0, 긴 변 반폭 1). */
    fun normalize(px: Double, py: Double, imageW: Int, imageH: Int): Pair<Double, Double> {
        val half = maxOf(imageW, imageH) / 2.0
        return (px - imageW / 2.0) / half to (py - imageH / 2.0) / half
    }

    fun fit(positions: List<Position>, useR6: Boolean = true): Result {
        require(positions.size >= 2) { "위치가 2개 이상 필요합니다" }
        val nBlocks = positions[0].values.size
        require(positions.all { it.values.size == nBlocks })
        val k = if (useR6) 3 else 2
        fun basis(x: Double, y: Double): DoubleArray {
            val r2 = x * x + y * y
            return doubleArrayOf(r2, r2 * r2, r2 * r2 * r2)
        }
        // 화면값 log S[b]를 소거한다(변수 투영): 블록마다 위치 평균을 빼면
        //   (log v − 평균 log v) = c·(basis − 평균 basis) 만 남아 계수를 한 번에 푼다.
        val ata = Array(k) { DoubleArray(k) }
        val atb = DoubleArray(k)
        for (b in 0 until nBlocks) {
            val valid = positions.filter { val v = it.values[b]; v.isFinite() && v > 0f }
            if (valid.size < 2) continue
            val logs = valid.map { ln(it.values[b].toDouble()) }
            val bases = valid.map { basis(it.xs[b].toDouble(), it.ys[b].toDouble()) }
            val meanLog = logs.average()
            val meanBasis = DoubleArray(3) { i -> bases.sumOf { it[i] } / bases.size }
            for (j in valid.indices) {
                val t = logs[j] - meanLog
                val a = DoubleArray(k) { bases[j][it] - meanBasis[it] }
                for (i in 0 until k) {
                    atb[i] += a[i] * t
                    for (m in 0 until k) ata[i][m] += a[i] * a[m]
                }
            }
        }
        val c = solve(ata, atb) ?: throw IllegalStateException("비네팅 계수를 풀 수 없습니다(위치 차이가 너무 작음)")
        var rMax = 0.0
        for (pos in positions) for (b in 0 until nBlocks) {
            val v = pos.values[b]
            if (v.isFinite() && v > 0f) rMax = maxOf(rMax, Math.hypot(pos.xs[b].toDouble(), pos.ys[b].toDouble()))
        }
        val model = Model(c[0], c[1], if (useR6) c[2] else 0.0, if (rMax > 0.0) rMax else Double.POSITIVE_INFINITY)
        var ss = 0.0
        var n = 0
        for (b in 0 until nBlocks) {
            val valid = positions.filter { val v = it.values[b]; v.isFinite() && v > 0f }
            if (valid.size < 2) continue
            val corrected = valid.map { ln(it.values[b].toDouble()) - model.logGain(it.xs[b].toDouble(), it.ys[b].toDouble()) }
            val mean = corrected.average()
            for (e in corrected) { ss += (e - mean) * (e - mean); n++ }
        }
        return Result(model, if (n > 0) Math.sqrt(ss / n) else Double.NaN, positions.size, nBlocks)
    }

    /** 작은 대칭 행렬 가우스 소거. 특이하면 null. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            val piv = (col until n).maxBy { Math.abs(m[it][col]) }
            if (Math.abs(m[piv][col]) < 1e-12) return null
            val t = m[piv]; m[piv] = m[col]; m[col] = t
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (j in col..n) m[r][j] -= f * m[col][j]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
