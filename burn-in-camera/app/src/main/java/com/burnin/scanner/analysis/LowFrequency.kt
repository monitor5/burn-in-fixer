package com.burnin.scanner.analysis

/**
 * 화면 격자의 저주파(블록 평균) 연산. 카메라 잔여 비네팅과 색 얼룩처럼 화면 전체에 걸친 완만한
 * 성분만 다룰 때 쓴다. 블록 단위라 2048×1536 격자를 여러 장 만들지 않고 계산할 수 있다.
 */
object LowFrequency {

    class Blocks(val bw: Int, val bh: Int, val values: FloatArray)

    /** 긴 변을 [blocksLong]칸으로 나눈 블록 평균. 0 이하·비유한 값은 빼고 평균한다(전부 빠지면 NaN). */
    fun blocks(grid: FloatArray, gw: Int, gh: Int, blocksLong: Int): Blocks {
        require(grid.size == gw * gh && blocksLong >= 2)
        val bw = if (gw >= gh) blocksLong else maxOf(2, Math.round(blocksLong * gw.toFloat() / gh))
        val bh = if (gh > gw) blocksLong else maxOf(2, Math.round(blocksLong * gh.toFloat() / gw))
        val sum = DoubleArray(bw * bh)
        val cnt = IntArray(bw * bh)
        for (y in 0 until gh) {
            val by = (y * bh / gh).coerceAtMost(bh - 1)
            for (x in 0 until gw) {
                val v = grid[y * gw + x]
                if (!v.isFinite() || v <= 0f) continue
                val b = by * bw + (x * bw / gw).coerceAtMost(bw - 1)
                sum[b] += v.toDouble()
                cnt[b]++
            }
        }
        return Blocks(bw, bh, FloatArray(bw * bh) { if (cnt[it] > 0) (sum[it] / cnt[it]).toFloat() else Float.NaN })
    }

    fun median(values: FloatArray): Float {
        val v = values.filter { it.isFinite() }.sorted()
        require(v.isNotEmpty()) { "유효한 블록이 없습니다" }
        return v[v.size / 2]
    }

    /** NaN 블록은 유효한 이웃 평균으로 채운다(가장자리 블록이 비는 경우). 전부 NaN이면 [fallback]. */
    fun fillMissing(b: Blocks, fallback: Float): Blocks {
        val out = b.values.copyOf()
        repeat(b.bw + b.bh) {
            var changed = false
            for (y in 0 until b.bh) for (x in 0 until b.bw) {
                val i = y * b.bw + x
                if (out[i].isFinite()) continue
                var s = 0f
                var n = 0
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until b.bw && ny in 0 until b.bh && out[ny * b.bw + nx].isFinite()) {
                        s += out[ny * b.bw + nx]; n++
                    }
                }
                if (n > 0) { out[i] = s / n; changed = true }
            }
            if (!changed) return@repeat
        }
        for (i in out.indices) if (!out[i].isFinite()) out[i] = fallback
        return Blocks(b.bw, b.bh, out)
    }

    /** 블록 값을 격자 크기로 이중선형 보간(블록 중심 기준, 가장자리는 클램프). */
    fun upsample(b: Blocks, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gw * gh)
        Analyzer.parallelRows(gh) { y ->
            val fy = ((y + 0.5f) * b.bh / gh - 0.5f).coerceIn(0f, (b.bh - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(b.bh - 2).coerceAtLeast(0)
            val y1 = (y0 + 1).coerceAtMost(b.bh - 1)
            val dy = fy - y0
            for (x in 0 until gw) {
                val fx = ((x + 0.5f) * b.bw / gw - 0.5f).coerceIn(0f, (b.bw - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(b.bw - 2).coerceAtLeast(0)
                val x1 = (x0 + 1).coerceAtMost(b.bw - 1)
                val dx = fx - x0
                val v = b.values
                out[y * gw + x] = (v[y0 * b.bw + x0] * (1 - dx) + v[y0 * b.bw + x1] * dx) * (1 - dy) +
                    (v[y1 * b.bw + x0] * (1 - dx) + v[y1 * b.bw + x1] * dx) * dy
            }
        }
        return out
    }

    /**
     * 메인 카메라의 잔여 비네팅(원형) 추정: 같은 화면을 본 기준 카메라(초광각, 화면이 프레임 가운데
     * 작은 영역이라 렌즈 비네팅이 거의 평평)와의 저주파 비율. 화면 내용(번인)은 둘 다 보므로 상쇄되고
     * 메인 카메라 쪽 성분만 남는다. 중앙값 1로 정규화하고 [minRatio, maxRatio]로 자른 블록을 돌려준다.
     */
    fun cameraResidual(
        main: FloatArray,
        reference: Blocks,
        gw: Int,
        gh: Int,
        minRatio: Float = 0.75f,
        maxRatio: Float = 1.25f,
    ): Blocks {
        val r = reference
        val m = blocks(main, gw, gh, maxOf(r.bw, r.bh))
        require(m.bw == r.bw && m.bh == r.bh) { "블록 크기 불일치" }
        val ratio = FloatArray(m.values.size) {
            val a = m.values[it]
            val b = r.values[it]
            if (a.isFinite() && b.isFinite() && b > 0f) a / b else Float.NaN
        }
        val med = median(ratio)
        for (i in ratio.indices) if (ratio[i].isFinite()) ratio[i] = (ratio[i] / med).coerceIn(minRatio, maxRatio)
        return fillMissing(Blocks(m.bw, m.bh, ratio), 1f)
    }

    /** 채널별 블록(중앙값 정규화)과 색 편차 지표. */
    class Chroma(val r: Blocks, val g: Blocks, val b: Blocks) {
        /** 블록마다 (가장 강한 채널 / 가장 약한 채널 − 1). 0이면 기준 색과 같다. */
        fun spread(): FloatArray = FloatArray(r.values.size) {
            val v = floatArrayOf(r.values[it], g.values[it], b.values[it])
            if (v.all { x -> x.isFinite() && x > 0f }) v.max() / v.min() - 1f else Float.NaN
        }

        fun meanSpread(): Float = spread().filter { it.isFinite() }.average().toFloat()
        fun p95Spread(): Float = spread().filter { it.isFinite() }.sorted().let { it[((it.size - 1) * 0.95).toInt()] }
    }

    fun chroma(red: FloatArray, green: FloatArray, blue: FloatArray, gw: Int, gh: Int, blocksLong: Int = 24): Chroma {
        fun norm(grid: FloatArray): Blocks {
            val b = blocks(grid, gw, gh, blocksLong)
            val med = median(b.values)
            return Blocks(b.bw, b.bh, FloatArray(b.values.size) { b.values[it] / med })
        }
        return Chroma(norm(red), norm(green), norm(blue))
    }

    /**
     * 색 균일화 감쇠: 블록마다 상대적으로 강한 채널을 가장 약한 채널 수준으로 낮추는 채널 gain(≤ 1).
     * 화면은 밝게 올릴 수 없으므로 낮추는 쪽만 쓰고, gain은 [1 − maxAtt]에서 자른다. 기준 색은 화면 중앙값.
     */
    fun chromaGains(c: Chroma, maxAtt: Float): Triple<Blocks, Blocks, Blocks> {
        val n = c.r.values.size
        val gr = FloatArray(n)
        val gg = FloatArray(n)
        val gb = FloatArray(n)
        for (i in 0 until n) {
            val v = floatArrayOf(c.r.values[i], c.g.values[i], c.b.values[i])
            if (!v.all { it.isFinite() && it > 0f }) { gr[i] = 1f; gg[i] = 1f; gb[i] = 1f; continue }
            val m = v.min()
            gr[i] = (m / v[0]).coerceIn(1f - maxAtt, 1f)
            gg[i] = (m / v[1]).coerceIn(1f - maxAtt, 1f)
            gb[i] = (m / v[2]).coerceIn(1f - maxAtt, 1f)
        }
        val bw = c.r.bw
        val bh = c.r.bh
        return Triple(softenEdges(Blocks(bw, bh, gr)), softenEdges(Blocks(bw, bh, gg)), softenEdges(Blocks(bw, bh, gb)))
    }

    /**
     * 가장자리 한 줄 블록은 비네팅·색 셰이딩이 커 색 비율이 덜 정확해 과보정(붉은 테)이 났다.
     * 가장자리 블록의 감쇠를 바로 안쪽 블록 감쇠의 절반으로 둔다.
     */
    fun softenEdges(b: Blocks): Blocks {
        if (b.bw < 3 || b.bh < 3) return b
        val v = b.values.copyOf()
        for (y in 0 until b.bh) for (x in 0 until b.bw) {
            if (x != 0 && y != 0 && x != b.bw - 1 && y != b.bh - 1) continue
            val ix = x.coerceIn(1, b.bw - 2)
            val iy = y.coerceIn(1, b.bh - 2)
            val inner = b.values[iy * b.bw + ix]
            v[y * b.bw + x] = 1f - 0.5f * (1f - inner)
        }
        return Blocks(b.bw, b.bh, v)
    }
}
