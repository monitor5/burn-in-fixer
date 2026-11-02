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

    /** 켜진 화면 영역 마스크(분석 이미지 크기). [count]는 참인 화소 수. */
    class Mask(val w: Int, val h: Int, val bits: BooleanArray, val count: Int)

    /**
     * 켜진 화면 영역을 사각형 없이 화소 마스크로 잡는다. 대조설비처럼 프레임 가장자리에 걸려 일부만 보이는
     * 화면에 쓴다(색감만 재므로 화면 전체가 들어올 필요가 없다). 경계 번짐·잘린 가장자리를 피하려고 짧은 변의
     * [erodeFrac]만큼 안쪽으로 깎는다. 남는 화소가 프레임의 [minFraction]보다 적으면 null.
     */
    fun brightMask(img: RgbImage, erodeFrac: Float = 0.02f, minFraction: Float = 0.005f): Mask? {
        val w = img.w
        val h = img.h
        var maxV = 1e-6f
        for (i in img.r.indices) maxV = max(maxV, luma(img, i))
        // detectBrightQuad와 같은 문턱: 상위 1% 밝기의 30%
        val bins = IntArray(1024)
        val scale = 1023f / maxV
        for (i in img.r.indices) bins[(luma(img, i) * scale).toInt().coerceIn(0, 1023)]++
        var cum = 0
        var p99 = maxV
        for (b in 0 until 1024) {
            cum += bins[b]
            if (cum >= img.r.size * 0.99f) { p99 = b / scale; break }
        }
        if (p99 < 0.005f) return null
        val threshold = p99 * 0.30f
        // 어두운 화소의 적분 영상으로, 반경 r 안에 어두운 화소가 하나라도 있으면(또는 프레임 밖이면) 뺀다
        val r = max(1, (min(w, h) * erodeFrac).toInt())
        val stride = w + 1
        val dark = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (luma(img, y * w + x) <= threshold) row++
                dark[(y + 1) * stride + x + 1] = dark[y * stride + x + 1] + row
            }
        }
        val bits = BooleanArray(w * h)
        var count = 0
        for (y in r until h - r) for (x in r until w - r) {
            val x0 = x - r; val x1 = x + r + 1; val y0 = y - r; val y1 = y + r + 1
            val n = dark[y1 * stride + x1] - dark[y0 * stride + x1] - dark[y1 * stride + x0] + dark[y0 * stride + x0]
            if (n == 0) { bits[y * w + x] = true; count++ }
        }
        if (count < w * h * minFraction) return null
        return Mask(w, h, bits, count)
    }

    fun meanMaskRgb(img: RgbImage, mask: Mask): Rgb {
        require(img.w == mask.w && img.h == mask.h) { "마스크 크기 불일치" }
        var r = 0.0; var g = 0.0; var b = 0.0
        for (i in mask.bits.indices) {
            if (!mask.bits[i]) continue
            r += img.r[i]; g += img.g[i]; b += img.b[i]
        }
        return Rgb(r / mask.count, g / mask.count, b / mask.count)
    }

    /** 사각형 꼭짓점이 프레임 테두리 [margin] 안에 닿으면 true(화면이 잘려 사각형이 실제 화면이 아님). */
    fun touchesBorder(q: ScreenDetector.Quad, w: Int, h: Int, margin: Float = 3f): Boolean =
        q.corners.any { it.x <= margin || it.y <= margin || it.x >= w - 1 - margin || it.y >= h - 1 - margin }

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
