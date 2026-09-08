package com.burnin.scanner.analysis

import kotlin.math.abs

/**
 * 암실 조건에서 밝은 패턴(회색 70% 등)이 표시된 화면 사각형을 검출한다.
 * 배경은 검고 화면만 밝다는 가정(암실 박스 지그) 하에:
 *  1) p99 휘도의 일정 비율로 이진화
 *  2) 밝은 픽셀들의 극점 4개(x+y 최소/최대, x−y 최소/최대)를 모서리로 추정
 * 반환 순서: TL, TR, BR, BL (이미지 좌표 기준)
 */
object ScreenDetector {

    class Quad(val corners: Array<Vec2>) {
        val tl get() = corners[0]
        val tr get() = corners[1]
        val br get() = corners[2]
        val bl get() = corners[3]

        fun area(): Float {
            var s = 0f
            for (i in corners.indices) {
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                s += a.x * b.y - b.x * a.y
            }
            return abs(s) / 2f
        }

        fun topLen(): Float = dist(tl, tr)
        fun sideLen(): Float = dist(tl, bl)

        private fun dist(a: Vec2, b: Vec2): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        override fun toString() =
            corners.joinToString(" ") { "(${it.x.toInt()},${it.y.toInt()})" }
    }

    data class Result(val quad: Quad, val areaRatio: Float, val threshold: Float)

    fun detect(img: GrayImage): Result? {
        if (img.data.any { !it.isFinite() || it < 0f }) return null
        // p99 추정 (히스토그램 1024 bin)
        val bins = IntArray(1024)
        var maxV = 1e-6f
        for (v in img.data) if (v > maxV) maxV = v
        val scale = 1023f / maxV
        for (v in img.data) bins[(v * scale).toInt().coerceIn(0, 1023)]++
        val total = img.data.size
        var cum = 0
        var p99 = maxV
        for (b in 0 until 1024) {
            cum += bins[b]
            if (cum >= total * 0.99f) {
                p99 = b / scale
                break
            }
        }

        val threshold = p99 * 0.30f
        if (p99 < 0.005f) return null // 화면이 너무 어두움 (패턴 미표시?)

        // Use one connected screen component; isolated glare must not move its corners.
        val seen = BooleanArray(total)
        val queue = IntArray(total)
        var count = 0
        var bestCorners: Array<Vec2>? = null
        for (start in img.data.indices) {
            if (seen[start] || img.data[start] <= threshold) continue
            var head = 0
            var tail = 1
            queue[0] = start
            seen[start] = true
            var minSum = Float.MAX_VALUE
            var maxSum = -Float.MAX_VALUE
            var minDiff = Float.MAX_VALUE
            var maxDiff = -Float.MAX_VALUE
            val tl = Vec2(); val tr = Vec2(); val br = Vec2(); val bl = Vec2()
            while (head < tail) {
                val index = queue[head++]
                val x = index % img.w
                val y = index / img.w
                val sum = (x + y).toFloat()
                val diff = (x - y).toFloat()
                if (sum < minSum) { minSum = sum; tl.set(x.toFloat(), y.toFloat()) }
                if (sum > maxSum) { maxSum = sum; br.set(x.toFloat(), y.toFloat()) }
                if (diff > maxDiff) { maxDiff = diff; tr.set(x.toFloat(), y.toFloat()) }
                if (diff < minDiff) { minDiff = diff; bl.set(x.toFloat(), y.toFloat()) }
                fun add(next: Int) {
                    if (!seen[next] && img.data[next] > threshold) {
                        seen[next] = true
                        queue[tail++] = next
                    }
                }
                if (x > 0) add(index - 1)
                if (x + 1 < img.w) add(index + 1)
                if (y > 0) add(index - img.w)
                if (y + 1 < img.h) add(index + img.w)
            }
            if (tail > count) { count = tail; bestCorners = arrayOf(tl, tr, br, bl) }
        }

        if (count < total * 0.05f) return null // 밝은 영역이 프레임의 5% 미만

        val quad = Quad(bestCorners ?: return null)
        val areaRatio = quad.area() / (img.w * img.h).toFloat()
        if (areaRatio < 0.05f || areaRatio > 0.99f) return null
        return Result(quad, areaRatio, threshold)
    }
}
