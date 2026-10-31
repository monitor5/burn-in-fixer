package com.burnin.scanner.analysis

/**
 * 측정 촬영에 지정하는 "알려진" 카메라 톤커브와 그 정확한 역함수 (Android 의존성 없음).
 *
 * Camera2 기본 톤매핑은 기기·장면마다 다른 S-curve라서, YUV를 x^2.2로 되돌리는 근사는
 * 특히 검은 바탕의 희소 점(톤커브 toe)에서 측정 깊이를 최대 50% 키운다
 * (research/literature-eval 실험 B2). 선형 곡선(y=x)은 8비트 출력에서 gray25 같은 저휘도의
 * 양자화가 거칠어지므로(코드 ~16단계), 대신 y = x^(1/2.2)를 **명시적으로** 지정하고
 * 디코더가 같은 곡선을 정확히 되돌린다. 핵심은 선형이 아니라 "알고 있는 곡선"이다.
 *
 * - [Mode.CONTRAST_CURVE]: TONEMAP_MODE_CONTRAST_CURVE에 [points]개 제어점. HAL은 제어점 사이를
 *   선형 보간하므로 역함수도 같은 조각 선형 곡선의 역으로 계산한다.
 * - [Mode.GAMMA_VALUE]: API 28+ TONEMAP_MODE_GAMMA_VALUE, γ=2.2 → 역함수는 정확히 x^2.2.
 * - [Mode.PRESET_SRGB]: API 28+ TONEMAP_MODE_PRESET_CURVE sRGB → sRGB 역변환.
 * - [Mode.ASSUMED]: 기기 기본 곡선(미지). 기존처럼 x^2.2로 근사한다.
 */
object CameraToneCurve {
    const val GAMMA = 2.2
    const val POINTS = 33

    enum class Mode(val label: String) {
        CONTRAST_CURVE("CONTRAST_CURVE γ2.2 (${POINTS}점)"),
        GAMMA_VALUE("GAMMA_VALUE 2.2"),
        PRESET_SRGB("PRESET_CURVE sRGB"),
        ASSUMED("기기 기본 톤맵(미지, x^2.2 근사)"),
    }

    /** 제어점 입력값 x_k = (k/(n−1))^γ, 출력 y_k = k/(n−1). 출력이 균등이라 역함수 구간 찾기가 O(1). */
    fun nodeInput(k: Int, n: Int = POINTS): Double = Math.pow(k.toDouble() / (n - 1), GAMMA)

    /** TonemapCurve 채널 배열 [x0, y0, x1, y1, ...]. */
    fun curvePoints(n: Int = POINTS): FloatArray {
        require(n >= 2)
        val out = FloatArray(2 * n)
        for (k in 0 until n) {
            out[2 * k] = nodeInput(k, n).toFloat()
            out[2 * k + 1] = (k.toDouble() / (n - 1)).toFloat()
        }
        out[0] = 0f
        out[2 * n - 2] = 1f
        return out
    }

    /** 지정한 곡선을 통과한 카메라 출력 y(0~1) → 선형 신호 x(0~1). */
    fun toLinear(y: Double, mode: Mode, n: Int = POINTS): Double {
        val v = y.coerceIn(0.0, 1.0)
        return when (mode) {
            Mode.CONTRAST_CURVE -> {
                val t = v * (n - 1)
                val k = Math.min(t.toInt(), n - 2)
                val x0 = nodeInput(k, n)
                val x1 = nodeInput(k + 1, n)
                x0 + (t - k) * (x1 - x0)
            }
            Mode.GAMMA_VALUE, Mode.ASSUMED -> Math.pow(v, GAMMA)
            Mode.PRESET_SRGB -> if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
    }

    /** 순방향(검증·테스트용): 선형 x → 곡선 출력 y. */
    fun fromLinear(x: Double, mode: Mode, n: Int = POINTS): Double {
        val v = x.coerceIn(0.0, 1.0)
        return when (mode) {
            Mode.CONTRAST_CURVE -> {
                var k = 0
                while (k < n - 2 && nodeInput(k + 1, n) < v) k++
                val x0 = nodeInput(k, n)
                val x1 = nodeInput(k + 1, n)
                (k + (v - x0) / (x1 - x0)) / (n - 1)
            }
            Mode.GAMMA_VALUE, Mode.ASSUMED -> Math.pow(v, 1.0 / GAMMA)
            Mode.PRESET_SRGB -> if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
        }
    }
}
