package com.burnin.target.correction

import kotlin.math.pow

/**
 * 보정맵의 "감쇠"는 Scanner가 선형 휘도 비율로 계산한 값이다(gain = 목표 휘도 / 측정 휘도).
 * 화면에는 sRGB 코드값(또는 코드 공간 알파 블렌딩)으로 나가므로, 코드에 gain을 그대로 곱하면
 * 실제 휘도 감쇠는 gain^γ(γ≈2.2)가 되어 약 2.2배 과보정된다.
 * 이 객체는 휘도 gain → 코드 gain 변환(gain^(1/γ))과, 8비트 출력의 1코드 계단(gray70에서 휘도
 * 약 1.2%, gray25에서 약 3.4%)을 공간적으로 나눠 주는 Floyd–Steinberg dither를 제공한다.
 * Android 의존성이 없는 순수 계산 모듈이다. (research/literature-eval 실험 A)
 */
object OutputTransfer {
    const val DEFAULT_GAMMA = 2.2
    const val MIN_GAMMA = 1.6
    const val MAX_GAMMA = 3.0

    /** 전달받은 γ가 없거나 비정상이면 [DEFAULT_GAMMA]. */
    fun sanitizeGamma(gamma: Double?): Double =
        if (gamma != null && gamma.isFinite() && gamma in MIN_GAMMA..MAX_GAMMA) gamma else DEFAULT_GAMMA

    /** 선형 휘도 gain(0~1) → 코드값에 곱할 gain. */
    fun codeGain(luminanceGain: Double, gamma: Double): Double {
        val g = luminanceGain.coerceIn(0.0, 1.0)
        return if (g == 0.0) 0.0 else g.pow(1.0 / sanitizeGamma(gamma))
    }

    /** 검정 오버레이 알파(0~1). 코드 공간 블렌딩 out = c·(1−a)가 휘도 gain을 내도록 a = 1 − gain^(1/γ). */
    fun overlayAlpha(luminanceGain: Double, gamma: Double): Double = 1.0 - codeGain(luminanceGain, gamma)

    /**
     * 연속 값 → 0..[max] 정수, serpentine Floyd–Steinberg 오차 확산.
     * 행 두 개 분량의 오차 버퍼만 써서 큰 화면(Tab S2 2048×1536)에서도 메모리를 적게 쓴다.
     * [value]는 픽셀 인덱스(y·w+x)의 연속 값, [sink]는 (인덱스, 정수 값)을 받는다.
     * 같은 입력이면 항상 같은 결과를 낸다(측정 반복 간 재현성).
     */
    fun dither(w: Int, h: Int, max: Int, value: (Int) -> Double, sink: (Int, Int) -> Unit) {
        require(w > 0 && h > 0 && max > 0)
        var cur = DoubleArray(w + 2)
        var next = DoubleArray(w + 2)
        for (y in 0 until h) {
            val leftToRight = y % 2 == 0
            for (k in 0 until w) {
                val x = if (leftToRight) k else w - 1 - k
                val i = y * w + x
                val v = value(i) + cur[x + 1]
                val q = Math.round(v).toInt().coerceIn(0, max)
                sink(i, q)
                val e = v - q
                val fwd = if (leftToRight) 1 else -1
                cur[x + 1 + fwd] += e * 7.0 / 16.0
                next[x + 1 - fwd] += e * 3.0 / 16.0
                next[x + 1] += e * 5.0 / 16.0
                next[x + 1 + fwd] += e * 1.0 / 16.0
            }
            val t = cur
            cur = next
            next = t
            next.fill(0.0)
        }
    }
}
