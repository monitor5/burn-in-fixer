package com.burnin.scanner.color

import com.burnin.scanner.analysis.RgbImage
import com.burnin.scanner.analysis.ScreenDetector
import com.burnin.scanner.analysis.Vec2
import kotlin.math.max
import kotlin.math.min

/** 카메라 프레임에서 한 화면의 위치를 찾고 중앙 영역 평균 RGB를 뽑는 도우미 (Android 의존성 없음). */
internal object ColorSampling {
    const val CENTER_START = 0.30f
    const val CENTER_END = 0.70f
    const val CENTER_STEPS = 40

    /** 밝게 켜진 화면 하나를 사각형으로 검출한다. 다른 화면은 검정이어야 한다. */
    fun detectBrightQuad(img: RgbImage): ScreenDetector.Quad? {
        val bins = IntArray(1024)
        var maxV = 1e-6f
        for (i in img.r.indices) {
            val y = luma(img, i)
            if (y > maxV) maxV = y
        }
        val scale = 1023f / maxV
        for (i in img.r.indices) bins[(luma(img, i) * scale).toInt().coerceIn(0, 1023)]++
        val total = img.r.size
        var cum = 0
        var p99 = maxV
        for (b in 0 until 1024) {
            cum += bins[b]
            if (cum >= total * 0.99f) {
                p99 = b / scale
                break
            }
        }
        if (p99 < 0.005f) return null
        val threshold = p99 * 0.30f

        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE
        val tl = Vec2(); val tr = Vec2(); val br = Vec2(); val bl = Vec2()
        var count = 0
        var i = 0
        for (y in 0 until img.h) {
            for (x in 0 until img.w) {
                if (luma(img, i) > threshold) {
                    count++
                    val s = (x + y).toFloat()
                    val d = (x - y).toFloat()
                    if (s < minSum) { minSum = s; tl.set(x.toFloat(), y.toFloat()) }
                    if (s > maxSum) { maxSum = s; br.set(x.toFloat(), y.toFloat()) }
                    if (d > maxDiff) { maxDiff = d; tr.set(x.toFloat(), y.toFloat()) }
                    if (d < minDiff) { minDiff = d; bl.set(x.toFloat(), y.toFloat()) }
                }
                i++
            }
        }
        if (count < total * 0.03f) return null
        val quad = ScreenDetector.Quad(arrayOf(tl, tr, br, bl))
        val areaRatio = quad.area() / total.toFloat()
        if (areaRatio < 0.03f || areaRatio > 0.95f) return null
        return quad
    }

    /** 두 사각형의 bounding box가 작은 쪽 면적의 10% 이상 겹치면 true (같은 화면을 두 번 잡은 경우). */
    fun overlaps(a: ScreenDetector.Quad, b: ScreenDetector.Quad): Boolean {
        fun box(q: ScreenDetector.Quad) = floatArrayOf(
            q.corners.minOf { it.x }, q.corners.minOf { it.y }, q.corners.maxOf { it.x }, q.corners.maxOf { it.y },
        )
        val p = box(a); val q = box(b)
        val iw = min(p[2], q[2]) - max(p[0], q[0])
        val ih = min(p[3], q[3]) - max(p[1], q[1])
        if (iw <= 0f || ih <= 0f) return false
        val smaller = min((p[2] - p[0]) * (p[3] - p[1]), (q[2] - q[0]) * (q[3] - q[1]))
        return iw * ih > smaller * 0.10f
    }

    /** 사각형 내부 중앙 영역(정규화 좌표 start..end)의 평균 RGB. */
    fun meanCenterRgb(
        img: RgbImage,
        quad: ScreenDetector.Quad,
        start: Float = CENTER_START,
        end: Float = CENTER_END,
        steps: Int = CENTER_STEPS,
    ): Rgb {
        var r = 0.0; var g = 0.0; var b = 0.0
        var count = 0
        val span = end - start
        for (yi in 0 until steps) {
            val v = start + span * (yi + 0.5f) / steps
            for (xi in 0 until steps) {
                val u = start + span * (xi + 0.5f) / steps
                val p = quadPoint(quad, u, v)
                r += img.bilinearChannel(p.x, p.y, 0).toDouble()
                g += img.bilinearChannel(p.x, p.y, 1).toDouble()
                b += img.bilinearChannel(p.x, p.y, 2).toDouble()
                count++
            }
        }
        return Rgb(r / count, g / count, b / count)
    }

    fun quadPoint(quad: ScreenDetector.Quad, u: Float, v: Float): Vec2 {
        val topX = quad.tl.x * (1f - u) + quad.tr.x * u
        val topY = quad.tl.y * (1f - u) + quad.tr.y * u
        val bottomX = quad.bl.x * (1f - u) + quad.br.x * u
        val bottomY = quad.bl.y * (1f - u) + quad.br.y * u
        return Vec2(topX * (1f - v) + bottomX * v, topY * (1f - v) + bottomY * v)
    }

    private fun luma(img: RgbImage, i: Int): Float =
        0.2126f * img.r[i] + 0.7152f * img.g[i] + 0.0722f * img.b[i]
}
