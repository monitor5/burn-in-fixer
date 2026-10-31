package com.burnin.target.correction

import kotlin.math.min

/**
 * 기준(A급) 기기 색감에 맞추는 3D LUT + 채널별 1D post 곡선. 입력/출력은 코드값 0..1.
 *
 * 와이어 형식 "rgb3d-u16be-v1" (Scanner ColorLut과 동일해야 함):
 *   big-endian uint16 (0..65535 → 0..1)
 *   [size³ × 3 LUT, 인덱스 ((r·N)+g)·N+b, 채널 R,G,B] 다음 [3 × 256 post 곡선, R,G,B 순서]
 * 순수 Kotlin이라 JVM 단위 테스트에서 그대로 검증한다.
 */
class ColorLut private constructor(val size: Int, private val data: FloatArray, private val post: Array<FloatArray>) {

    fun apply(r: Double, g: Double, b: Double): DoubleArray {
        val n = size - 1
        val fr = r.coerceIn(0.0, 1.0) * n; val fg = g.coerceIn(0.0, 1.0) * n; val fb = b.coerceIn(0.0, 1.0) * n
        val r0 = min(fr.toInt(), n - 1); val g0 = min(fg.toInt(), n - 1); val b0 = min(fb.toInt(), n - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        val pre = DoubleArray(3)
        for (corner in 0 until 8) {
            val ir = r0 + (corner shr 2 and 1); val ig = g0 + (corner shr 1 and 1); val ib = b0 + (corner and 1)
            val w = (if (corner and 4 != 0) dr else 1 - dr) * (if (corner and 2 != 0) dg else 1 - dg) *
                (if (corner and 1 != 0) db else 1 - db)
            if (w == 0.0) continue
            val o = ((ir * size + ig) * size + ib) * 3
            pre[0] += w * data[o]; pre[1] += w * data[o + 1]; pre[2] += w * data[o + 2]
        }
        return DoubleArray(3) { postValue(it, pre[it]) }
    }

    private fun postValue(channel: Int, v: Double): Double {
        val c = post[channel]
        val f = v.coerceIn(0.0, 1.0) * (POST_SIZE - 1)
        val i = min(f.toInt(), POST_SIZE - 2)
        val t = f - i
        return c[i] * (1 - t) + c[i + 1] * t
    }

    /** ARGB 색 변환. 알파는 유지한다. */
    fun transformArgb(color: Int): Int {
        val o = apply(((color shr 16) and 0xFF) / 255.0, ((color shr 8) and 0xFF) / 255.0, (color and 0xFF) / 255.0)
        fun q(v: Double) = Math.round(v * 255.0).toInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (q(o[0]) shl 16) or (q(o[1]) shl 8) or q(o[2])
    }

    companion object {
        const val FORMAT = "rgb3d-u16be-v1"
        const val POST_SIZE = 256

        fun byteLength(size: Int): Int = (size * size * size * 3 + 3 * POST_SIZE) * 2

        /** 길이·범위·단조성 검증 후 디코드. 실패 시 IllegalArgumentException. */
        fun decode(size: Int, bytes: ByteArray): ColorLut {
            require(size in 2..65) { "LUT 크기 범위 오류: $size" }
            require(bytes.size == byteLength(size)) { "LUT 길이 불일치: ${bytes.size} != ${byteLength(size)}" }
            val lutCount = size * size * size * 3
            fun at(i: Int) = ((bytes[2 * i].toInt() and 0xFF shl 8) or (bytes[2 * i + 1].toInt() and 0xFF)) / 65535f
            val data = FloatArray(lutCount) { at(it) }
            val post = Array(3) { c -> FloatArray(POST_SIZE) { at(lutCount + c * POST_SIZE + it) } }
            for (c in post) {
                require(c[0] == 0f) { "post 곡선이 0에서 시작하지 않습니다" }
                for (i in 1 until POST_SIZE) require(c[i] >= c[i - 1]) { "post 곡선이 단조 증가가 아닙니다" }
            }
            // 검정은 검정으로 유지되어야 한다 (OLED 검정을 띄우면 대비가 무너짐).
            require(data[0] <= 1f / 255f && data[1] <= 1f / 255f && data[2] <= 1f / 255f) { "LUT 검정 노드가 0이 아닙니다" }
            return ColorLut(size, data, post)
        }
    }
}
