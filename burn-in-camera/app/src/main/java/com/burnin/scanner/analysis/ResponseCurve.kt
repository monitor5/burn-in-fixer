package com.burnin.scanner.analysis

import kotlin.math.exp
import kotlin.math.ln

/**
 * 밝기 레벨별 응답 곡선으로 곱하기 보정맵의 목표를 정한다.
 *
 * 번인은 발광 효율 저하(곱하기)와 구동 TFT 문턱전압 변화(더하기)가 섞여 있어, 위치마다 레벨에 따라
 * 상대 밝기 r_L(b)가 다르다(어두운 레벨일수록 더 처짐). 오버레이 보정은 아래 화면 레벨을 모르므로
 * 위치마다 곱하기 g(b) 하나만 쓸 수 있다. 그래서 레벨 가중 로그 오차 Σ w_L (log g + log r_L − c)²를
 * 최소로 하는 g, 즉 log g = −Σ w_L log r_L / Σ w_L (가중 기하평균의 역수)를 고른다.
 *
 * 반복 보정 루프는 gray70만 다시 재므로, gray70 측정값을 [compensation] q = r_70 / r_eff로 나눠
 * "곡선 전체로 본 밝기"로 바꿔 넣는다. 곱하기 보정은 모든 레벨에 똑같이 곱해지므로 q는 반복 중
 * 변하지 않는다.
 */
object ResponseCurve {

    /** 블록을 중앙값 1로 정규화한다. 유효하지 않은 블록은 NaN. */
    fun normalized(b: LowFrequency.Blocks): LowFrequency.Blocks {
        val m = LowFrequency.median(b.values)
        return LowFrequency.Blocks(b.bw, b.bh, FloatArray(b.values.size) { val v = b.values[it]; if (v.isFinite() && v > 0f) v / m else Float.NaN })
    }

    /** 레벨 가중 기하평균 상대 밝기 r_eff. 유효 레벨이 없는 블록은 NaN. */
    fun effective(levels: List<LowFrequency.Blocks>, weights: FloatArray): LowFrequency.Blocks {
        require(levels.isNotEmpty() && levels.size == weights.size)
        val n = levels[0].values.size
        require(levels.all { it.values.size == n })
        val out = FloatArray(n) { i ->
            var sw = 0.0
            var sl = 0.0
            for ((k, b) in levels.withIndex()) {
                val v = b.values[i]
                if (!v.isFinite() || v <= 0f) continue
                sw += weights[k]
                sl += weights[k] * ln(v.toDouble())
            }
            if (sw > 0.0) exp(sl / sw).toFloat() else Float.NaN
        }
        return LowFrequency.Blocks(levels[0].bw, levels[0].bh, out)
    }

    /** gray70 측정값을 나눌 보상 q = r_ref / r_eff ([minQ], [maxQ]로 자르고 빈 블록은 1). */
    fun compensation(
        reference: LowFrequency.Blocks,
        effective: LowFrequency.Blocks,
        minQ: Float = 0.85f,
        maxQ: Float = 1.15f,
    ): LowFrequency.Blocks {
        val q = FloatArray(reference.values.size) { i ->
            val r = reference.values[i]
            val e = effective.values[i]
            if (r.isFinite() && e.isFinite() && r > 0f && e > 0f) (r / e).coerceIn(minQ, maxQ) else 1f
        }
        return LowFrequency.Blocks(reference.bw, reference.bh, q)
    }

    /** 정규화 블록의 RMS 편차(레벨별 곡선 비교용). */
    fun blockRms(b: LowFrequency.Blocks): Float {
        var ss = 0.0
        var n = 0
        for (v in b.values) {
            if (!v.isFinite()) continue
            ss += (v - 1.0) * (v - 1.0)
            n++
        }
        return if (n > 0) Math.sqrt(ss / n).toFloat() else Float.NaN
    }

    /** 격자를 블록 q의 이중선형 보간 값으로 나눈다(q 격자를 따로 만들지 않음). */
    fun divide(grid: FloatArray, q: LowFrequency.Blocks, gw: Int, gh: Int): FloatArray {
        require(grid.size == gw * gh)
        val out = FloatArray(grid.size)
        Analyzer.parallelRows(gh) { y ->
            val fy = ((y + 0.5f) * q.bh / gh - 0.5f).coerceIn(0f, (q.bh - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(q.bh - 2).coerceAtLeast(0)
            val y1 = (y0 + 1).coerceAtMost(q.bh - 1)
            val dy = fy - y0
            val v = q.values
            for (x in 0 until gw) {
                val fx = ((x + 0.5f) * q.bw / gw - 0.5f).coerceIn(0f, (q.bw - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(q.bw - 2).coerceAtLeast(0)
                val x1 = (x0 + 1).coerceAtMost(q.bw - 1)
                val dx = fx - x0
                val qq = (v[y0 * q.bw + x0] * (1 - dx) + v[y0 * q.bw + x1] * dx) * (1 - dy) +
                    (v[y1 * q.bw + x0] * (1 - dx) + v[y1 * q.bw + x1] * dx) * dy
                val g = grid[y * gw + x]
                out[y * gw + x] = if (g.isFinite() && qq > 0f) g / qq else g
            }
        }
        return out
    }
}
