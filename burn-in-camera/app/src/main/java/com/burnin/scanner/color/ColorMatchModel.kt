package com.burnin.scanner.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 기준(A급) 기기와 조정 기기의 색감 일치용 순수 계산 모듈 (Android 의존성 없음).
 *
 * 측정값은 같은 카메라·같은 고정 노출/WB로 찍은 "근사 선형 카메라 RGB"다. 두 화면을 같은
 * 카메라로 비교하므로 카메라 자체의 색 응답은 양쪽에 공통으로 들어가고, 일치 판정은
 * "같은 카메라가 같은 값을 보느냐"로 정의한다. 같은 패널 계열이면 이는 육안 일치와 가깝지만,
 * 발광 스펙트럼이 다른 패널끼리는 metamerism 때문에 카메라 일치가 육안 일치를 보장하지 않는다.
 *
 * 화면 모델 (가법 모델): M(c) = K + Σ_k P_k · f_k(c_k)
 *  - K: 검정 측정값 (카메라 offset + 주변광 + 패널 검정)
 *  - P_k: 채널 k 최대 출력의 카메라 RGB 벡터 (primary)
 *  - f_k: 채널 k의 코드값 → 상대 출력 곡선 (0→0, 1→1, 단조 증가)
 */
internal data class Rgb(val r: Double, val g: Double, val b: Double) {
    operator fun plus(o: Rgb) = Rgb(r + o.r, g + o.g, b + o.b)
    operator fun minus(o: Rgb) = Rgb(r - o.r, g - o.g, b - o.b)
    operator fun times(s: Double) = Rgb(r * s, g * s, b * s)
    operator fun get(i: Int): Double = when (i) { 0 -> r; 1 -> g; 2 -> b; else -> throw IndexOutOfBoundsException(i) }
    fun dot(o: Rgb) = r * o.r + g * o.g + b * o.b
    fun max() = maxOf(r, g, b)
    fun isFinite() = r.isFinite() && g.isFinite() && b.isFinite()
    /** 카메라 RGB를 sRGB primaries로 가정한 상대 휘도. 정규화/gamut 처리용 근사. */
    fun luma() = 0.2126 * r + 0.7152 * g + 0.0722 * b
    fun toArray() = doubleArrayOf(r, g, b)

    companion object {
        val ZERO = Rgb(0.0, 0.0, 0.0)
        fun of(a: DoubleArray) = Rgb(a[0], a[1], a[2])
    }
}

/** 측정 패치 목록. 패턴 이름은 Target의 `rgb-RRGGBB` 단색 패턴과 일치해야 한다. */
internal object ColorPatchPlan {
    /** 채널 램프/회색 램프 코드값. 0(검정)은 별도 패치로 한 번만 측정한다. */
    val LEVELS = intArrayOf(32, 64, 96, 128, 160, 192, 224, 255)

    enum class Role { BLACK, RAMP_R, RAMP_G, RAMP_B, GRAY, VALIDATION }

    data class Patch(val id: String, val r: Int, val g: Int, val b: Int, val role: Role) {
        init { require(listOf(r, g, b).all { it in 0..255 }) { "patch code out of range" } }
        val pattern: String get() = patternName(r, g, b)
        val codes: DoubleArray get() = doubleArrayOf(r / 255.0, g / 255.0, b / 255.0)
    }

    fun patternName(r: Int, g: Int, b: Int): String =
        String.format(java.util.Locale.US, "rgb-%02x%02x%02x", r, g, b)

    val BLACK = Patch("black", 0, 0, 0, Role.BLACK)

    fun fitPatches(): List<Patch> = buildList {
        add(BLACK)
        for (v in LEVELS) add(Patch("r$v", v, 0, 0, Role.RAMP_R))
        for (v in LEVELS) add(Patch("g$v", 0, v, 0, Role.RAMP_G))
        for (v in LEVELS) add(Patch("b$v", 0, 0, v, Role.RAMP_B))
        for (v in LEVELS) add(Patch("gray$v", v, v, v, Role.GRAY))
    }

    fun grayPatches(): List<Patch> = LEVELS.map { Patch("gray$it", it, it, it, Role.GRAY) }

    /**
     * 모델 적합에 쓰지 않는 held-out 검증색. 피부·하늘·식물·중간 채도·2차색·어두운 색을 섞었다.
     * (특정 상용 차트의 값을 복제한 것이 아니라 대표 색역을 고르게 덮도록 고른 코드값)
     */
    val VALIDATION = listOf(
        Patch("skin_light", 230, 190, 160, Role.VALIDATION),
        Patch("skin_dark", 140, 95, 70, Role.VALIDATION),
        Patch("sky", 100, 150, 210, Role.VALIDATION),
        Patch("foliage", 90, 120, 60, Role.VALIDATION),
        Patch("orange", 240, 140, 40, Role.VALIDATION),
        Patch("purple", 120, 70, 160, Role.VALIDATION),
        Patch("cyan", 60, 200, 210, Role.VALIDATION),
        Patch("magenta", 210, 60, 170, Role.VALIDATION),
        Patch("yellow", 230, 220, 70, Role.VALIDATION),
        Patch("slate", 70, 80, 100, Role.VALIDATION),
        Patch("gray45", 115, 115, 115, Role.VALIDATION),
        Patch("near_white", 245, 245, 240, Role.VALIDATION),
    )
}

/**
 * 단조 증가 톤 곡선 f: [0,1] → [0,1], f(0)=0.
 * 노드 사이는 Fritsch–Carlson monotone cubic, 첫 노드 아래는 power law로 잇는다.
 */
internal class ToneCurve(xs: DoubleArray, ys: DoubleArray) {
    val x: DoubleArray
    val y: DoubleArray
    private val m: DoubleArray
    private val lowGamma: Double

    init {
        require(xs.size == ys.size && xs.size >= 2) { "tone curve needs >= 2 nodes" }
        require(xs.all { it.isFinite() && it > 0.0 && it <= 1.0 }) { "tone curve x out of range" }
        require(ys.all { it.isFinite() }) { "tone curve y not finite" }
        for (i in 1 until xs.size) require(xs[i] > xs[i - 1]) { "tone curve x must increase" }
        x = xs.copyOf()
        y = isotonic(ys).let { iso ->
            // 엄격한 단조 증가 + 양수 보장 (역함수/거듭제곱 보간 안정성).
            val out = DoubleArray(iso.size)
            var prev = 0.0
            for (i in iso.indices) {
                val floor = prev + 1e-6
                out[i] = max(iso[i], floor)
                prev = out[i]
            }
            out
        }
        lowGamma = if (x.size >= 2) {
            val g = ln(y[1] / y[0]) / ln(x[1] / x[0])
            if (g.isFinite()) g.coerceIn(1.0, 4.0) else 2.2
        } else 2.2
        m = monotoneSlopes(x, y)
    }

    fun eval(v: Double): Double {
        if (!v.isFinite() || v <= 0.0) return 0.0
        if (v <= x[0]) return y[0] * (v / x[0]).pow(lowGamma)
        val n = x.size
        if (v >= x[n - 1]) {
            // 마지막 노드 위는 마지막 기울기로 선형 외삽 (보통 마지막 노드가 1.0이라 사용되지 않음).
            return y[n - 1] + m[n - 1] * (v - x[n - 1])
        }
        var i = 0
        while (i < n - 2 && v > x[i + 1]) i++
        val h = x[i + 1] - x[i]
        val t = (v - x[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * y[i] + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * y[i + 1] + (t3 - t2) * h * m[i + 1]
    }

    /** f^{-1}(target), 결과는 [0, 1]로 제한. eval이 단조이므로 이분법이 항상 수렴한다. */
    fun inverse(target: Double): Double {
        if (!target.isFinite() || target <= 0.0) return 0.0
        if (target >= eval(1.0)) return 1.0
        var lo = 0.0
        var hi = 1.0
        repeat(50) {
            val mid = (lo + hi) * 0.5
            if (eval(mid) < target) lo = mid else hi = mid
        }
        return (lo + hi) * 0.5
    }

    companion object {
        /** Pool-adjacent-violators: 노이즈로 생긴 역전을 최소제곱 의미에서 단조로 만든다. */
        fun isotonic(v: DoubleArray): DoubleArray {
            val values = ArrayList<Double>()
            val weights = ArrayList<Int>()
            for (e in v) {
                values += e
                weights += 1
                while (values.size >= 2 && values[values.size - 2] > values[values.size - 1]) {
                    val w = weights[weights.size - 2] + weights[weights.size - 1]
                    val merged = (values[values.size - 2] * weights[weights.size - 2] +
                        values[values.size - 1] * weights[weights.size - 1]) / w
                    values.removeAt(values.size - 1); weights.removeAt(weights.size - 1)
                    values[values.size - 1] = merged; weights[weights.size - 1] = w
                }
            }
            val out = DoubleArray(v.size)
            var k = 0
            for (i in values.indices) repeat(weights[i]) { out[k++] = values[i] }
            return out
        }

        private fun monotoneSlopes(x: DoubleArray, y: DoubleArray): DoubleArray {
            val n = x.size
            val d = DoubleArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]) }
            val m = DoubleArray(n)
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
            for (i in 0 until n - 1) {
                if (d[i] == 0.0) { m[i] = 0.0; m[i + 1] = 0.0; continue }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9) {
                    val t = 3 / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
            return m
        }
    }
}

/** 3×3 행렬 (행 우선). */
internal class Mat3(val a: DoubleArray) {
    init { require(a.size == 9) }
    operator fun times(v: Rgb) = Rgb(
        a[0] * v.r + a[1] * v.g + a[2] * v.b,
        a[3] * v.r + a[4] * v.g + a[5] * v.b,
        a[6] * v.r + a[7] * v.g + a[8] * v.b,
    )
    fun det(): Double = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) +
        a[2] * (a[3] * a[7] - a[4] * a[6])
    fun inverse(): Mat3 {
        val d = det()
        require(abs(d) > 1e-15 && d.isFinite()) { "singular matrix" }
        val i = 1.0 / d
        return Mat3(doubleArrayOf(
            (a[4] * a[8] - a[5] * a[7]) * i, (a[2] * a[7] - a[1] * a[8]) * i, (a[1] * a[5] - a[2] * a[4]) * i,
            (a[5] * a[6] - a[3] * a[8]) * i, (a[0] * a[8] - a[2] * a[6]) * i, (a[2] * a[3] - a[0] * a[5]) * i,
            (a[3] * a[7] - a[4] * a[6]) * i, (a[1] * a[6] - a[0] * a[7]) * i, (a[0] * a[4] - a[1] * a[3]) * i,
        ))
    }
    companion object {
        /** 열 벡터 c0, c1, c2로 행렬 구성. */
        fun columns(c0: Rgb, c1: Rgb, c2: Rgb) = Mat3(doubleArrayOf(
            c0.r, c1.r, c2.r,
            c0.g, c1.g, c2.g,
            c0.b, c1.b, c2.b,
        ))
    }
}

/** 화면 한 대의 가법 모델. */
internal class DisplayModel(val black: Rgb, val primaries: Array<Rgb>, val curves: Array<ToneCurve>) {
    val primaryMatrix: Mat3 = Mat3.columns(primaries[0], primaries[1], primaries[2])
    private val primaryInverse: Mat3 = primaryMatrix.inverse()

    /** 코드값(0..1)에서 black 제외 카메라 RGB 예측. */
    fun predictSignal(codes: DoubleArray): Rgb {
        var s = Rgb.ZERO
        for (k in 0..2) s += primaries[k] * curves[k].eval(codes[k])
        return s
    }

    fun predict(codes: DoubleArray): Rgb = black + predictSignal(codes)

    /** black 제외 카메라 RGB → 채널 구동량(f 값). 범위 제한 없음. */
    fun drives(signal: Rgb): DoubleArray = (primaryInverse * signal).toArray()

    fun codesForDrives(drives: DoubleArray): DoubleArray =
        DoubleArray(3) { curves[it].inverse(drives[it].coerceIn(0.0, 1.0)) }

    fun drivesForCodes(codes: DoubleArray): DoubleArray = DoubleArray(3) { curves[it].eval(codes[it]) }

    companion object {
        /**
         * 측정값으로 모델을 적합한다.
         * @param ramps 채널별 (코드값 0..255, 측정 RGB) 목록. 각 채널에 255가 포함되어야 한다.
         */
        fun fit(black: Rgb, ramps: List<List<Pair<Int, Rgb>>>, minSignal: Double = 1e-3): DisplayModel {
            require(ramps.size == 3) { "R/G/B 램프 3개가 필요합니다" }
            require(black.isFinite()) { "검정 측정값이 유효하지 않습니다" }
            val primaries = Array(3) { k ->
                val full = ramps[k].firstOrNull { it.first == 255 }
                    ?: throw IllegalArgumentException("${"RGB"[k]} 채널 255 측정이 없습니다")
                (full.second - black)
            }
            for (k in 0..2) {
                val p = primaries[k]
                require(p.isFinite() && p[k] > minSignal) {
                    "${"RGB"[k]} 채널 신호가 너무 약합니다 (노출 부족 또는 화면 검출 실패)"
                }
                require(p[k] >= p.max() * 0.999) {
                    "${"RGB"[k]} primary의 최대 성분이 해당 채널이 아닙니다 (카메라 WB/화면 검출 확인)"
                }
            }
            val pm = Mat3.columns(primaries[0], primaries[1], primaries[2])
            val scale = primaries.maxOf { it.max() }
            require(abs(pm.det()) > 1e-6 * scale * scale * scale) { "primary 행렬이 특이합니다" }
            val curves = Array(3) { k ->
                val p = primaries[k]
                val pp = p.dot(p)
                val pts = ramps[k].filter { it.first in 1..255 }.sortedBy { it.first }
                require(pts.size >= 3) { "${"RGB"[k]} 램프 노드가 부족합니다" }
                val xs = DoubleArray(pts.size) { pts[it].first / 255.0 }
                val ys = DoubleArray(pts.size) {
                    val proj = (pts[it].second - black).dot(p) / pp
                    max(proj, 1e-5)
                }
                ToneCurve(xs, ys)
            }
            return DisplayModel(black, primaries, curves)
        }
    }
}

/**
 * 조정 기기의 코드값 c' 를 골라 M_adj(c') - K_adj ≈ scale · (M_ref(c) - K_ref) 가 되게 한다.
 *
 * 화면은 밝게 올릴 수 없으므로 scale ≤ 1 범위에서 "기준 기기 흰색이 조정 기기 gamut에 들어가는
 * 최대 배율"을 고른다(상대 일치). 두 기기는 프레임의 다른 위치에 있어 렌즈 비네팅이 다르므로
 * 절대 밝기 비교는 신뢰할 수 없고, 상대 일치는 그 오차를 배율 하나로 흡수한다.
 */
internal class ColorMatcher(
    val ref: DisplayModel,
    val adj: DisplayModel,
    refWhiteMeasured: Rgb? = null,
    adjWhiteMeasured: Rgb? = null,
) {
    /** scale=1일 때 기준 흰색에 필요한 조정 기기 구동량. */
    val unscaledWhiteDrives: DoubleArray = adj.drives(ref.predictSignal(doubleArrayOf(1.0, 1.0, 1.0)))
    val scale: Double

    init {
        require(unscaledWhiteDrives.all { it.isFinite() && it > 1e-6 }) {
            "기준 흰색을 조정 기기 primary로 만들 수 없습니다 (화면 검출/노출 확인)"
        }
        var s = 1.0 / unscaledWhiteDrives.max()
        if (refWhiteMeasured != null && adjWhiteMeasured != null) {
            // 전체 화면 흰색은 ABL(전력 제한) 때문에 R+G+B 단색 합보다 어둡다. 실측 흰색끼리의
            // 구동량 비로 배율을 한 번 더 제한해 조정 기기가 실제로 낼 수 있는 흰색을 넘지 않게 한다.
            val need = adj.drives(refWhiteMeasured - ref.black)
            val have = adj.drives(adjWhiteMeasured - adj.black)
            require(need.all { it.isFinite() && it > 1e-6 } && have.all { it.isFinite() && it > 1e-6 }) {
                "흰색 실측값이 유효하지 않습니다"
            }
            s = min(s, 1.0 / DoubleArray(3) { need[it] / have[it] }.max())
        }
        scale = s
    }

    private val whiteLuma = ref.predictSignal(doubleArrayOf(1.0, 1.0, 1.0)).luma() * scale

    fun targetSignal(codes: DoubleArray): Rgb = ref.predictSignal(codes) * scale

    /** 측정된 기준값(black 포함)을 목표 신호로 변환. */
    fun targetFromMeasured(measuredRef: Rgb): Rgb = (measuredRef - ref.black) * scale

    /**
     * 목표 신호를 조정 기기 구동량으로. gamut 밖이면 같은 휘도의 무채색 쪽으로 채도를 줄여
     * 색상(hue)을 최대한 유지한다. 반환: (구동량, gamut 밖이었는지)
     */
    fun drivesFor(target: Rgb): Pair<DoubleArray, Boolean> {
        val a = adj.drives(target)
        if (a.all { it in 0.0..1.0 }) return a to false
        val y = if (whiteLuma > 0) (target.luma() / whiteLuma).coerceIn(0.0, 1.0) else 0.0
        val neutral = DoubleArray(3) { (unscaledWhiteDrives[it] * scale * y).coerceIn(0.0, 1.0) }
        var s = 1.0
        for (k in 0..2) {
            val d = a[k] - neutral[k]
            if (a[k] > 1.0 && d > 0) s = min(s, (1.0 - neutral[k]) / d)
            if (a[k] < 0.0 && d < 0) s = min(s, (0.0 - neutral[k]) / d)
        }
        s = s.coerceIn(0.0, 1.0)
        return DoubleArray(3) { (neutral[it] + s * (a[it] - neutral[it])).coerceIn(0.0, 1.0) } to true
    }

    fun solve(codes: DoubleArray): DoubleArray = adj.codesForDrives(drivesFor(targetSignal(codes)).first)

    fun bakeLut(size: Int = ColorLut.DEFAULT_SIZE): ColorLut {
        require(size in 2..65)
        val data = FloatArray(size * size * size * 3)
        var o = 0
        for (ri in 0 until size) for (gi in 0 until size) for (bi in 0 until size) {
            val c = solve(doubleArrayOf(ri / (size - 1.0), gi / (size - 1.0), bi / (size - 1.0)))
            data[o++] = c[0].toFloat(); data[o++] = c[1].toFloat(); data[o++] = c[2].toFloat()
        }
        return ColorLut(size, data, ColorLut.identityPost())
    }

    /**
     * 회색 계조 closed-loop 보정: 프로파일 적용 후 실측한 조정 기기 회색과 기준 기기 회색의 차이를
     * 조정 모델의 Jacobian(primary 행렬)으로 구동량 변화로 바꾸고, LUT 뒤의 채널별 1D post 곡선을
     * 갱신한다. 가법 모델이 놓친 ABL·채널 간 간섭·카메라 톤 곡선 오차를 무채색 축에서 직접 맞춘다.
     *
     * @param grayCodes 회색 패치 코드값(0..255)
     * @param refMeasured 기준 기기 회색 실측 (black 포함)
     * @param adjMeasured 현재 프로파일 적용 상태의 조정 기기 회색 실측 (black 포함)
     */
    fun refinePost(
        lut: ColorLut,
        grayCodes: IntArray,
        refMeasured: List<Rgb>,
        adjMeasured: List<Rgb>,
        damping: Double = 0.8,
    ): ColorLut {
        require(grayCodes.size == refMeasured.size && grayCodes.size == adjMeasured.size && grayCodes.isNotEmpty())
        require(damping in 0.0..1.0)
        val nodes = Array(3) { ArrayList<Pair<Double, Double>>() }
        for (i in grayCodes.indices) {
            val v = grayCodes[i] / 255.0
            val pre = lut.lookupPre(v, v, v)
            val cur = DoubleArray(3) { lut.post(it, pre[it]) }
            val target = targetFromMeasured(refMeasured[i])
            val measured = adjMeasured[i] - adj.black
            val delta = adj.drives(target - measured)
            val curDrives = adj.drivesForCodes(cur)
            val next = adj.codesForDrives(DoubleArray(3) { curDrives[it] + damping * delta[it] })
            for (k in 0..2) nodes[k] += pre[k] to next[k]
        }
        val post = Array(3) { k -> ColorLut.curveFromNodes(nodes[k], lut.postCurves[k]) }
        return ColorLut(lut.size, lut.data, post)
    }
}

/**
 * 3D LUT + 채널별 1D post 곡선. 입력/출력은 코드값 0..1.
 * 와이어 형식(Target ColorLut과 동일해야 함): big-endian uint16 (0..65535 → 0..1)
 *   [size³ × 3 LUT, 인덱스 ((r·N)+g)·N+b, 채널 순서 R,G,B] 다음 [3 × 256 post 곡선, R,G,B 순서]
 */
internal class ColorLut(val size: Int, val data: FloatArray, val postCurves: Array<FloatArray>) {
    init {
        require(size in 2..65 && data.size == size * size * size * 3)
        require(postCurves.size == 3 && postCurves.all { it.size == POST_SIZE })
    }

    fun lookupPre(r: Double, g: Double, b: Double): DoubleArray {
        val n = size - 1
        val fr = r.coerceIn(0.0, 1.0) * n; val fg = g.coerceIn(0.0, 1.0) * n; val fb = b.coerceIn(0.0, 1.0) * n
        val r0 = min(fr.toInt(), n - 1); val g0 = min(fg.toInt(), n - 1); val b0 = min(fb.toInt(), n - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        val out = DoubleArray(3)
        for (corner in 0 until 8) {
            val ir = r0 + (corner shr 2 and 1); val ig = g0 + (corner shr 1 and 1); val ib = b0 + (corner and 1)
            val w = (if (corner and 4 != 0) dr else 1 - dr) * (if (corner and 2 != 0) dg else 1 - dg) *
                (if (corner and 1 != 0) db else 1 - db)
            if (w == 0.0) continue
            val o = ((ir * size + ig) * size + ib) * 3
            out[0] += w * data[o]; out[1] += w * data[o + 1]; out[2] += w * data[o + 2]
        }
        return out
    }

    fun post(channel: Int, v: Double): Double {
        val c = postCurves[channel]
        val f = v.coerceIn(0.0, 1.0) * (POST_SIZE - 1)
        val i = min(f.toInt(), POST_SIZE - 2)
        val t = f - i
        return c[i] * (1 - t) + c[i + 1] * t
    }

    fun apply(r: Double, g: Double, b: Double): DoubleArray {
        val pre = lookupPre(r, g, b)
        return DoubleArray(3) { post(it, pre[it]) }
    }

    /** 8-bit 코드값 변환 (Target 렌더링과 같은 반올림). */
    fun apply8(r: Int, g: Int, b: Int): IntArray {
        val o = apply(r / 255.0, g / 255.0, b / 255.0)
        return IntArray(3) { Math.round(o[it] * 255.0).toInt().coerceIn(0, 255) }
    }

    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream((data.size + 3 * POST_SIZE) * 2)
        fun put(v: Float) {
            val q = Math.round(v.coerceIn(0f, 1f) * 65535f)
            out.write(q shr 8 and 0xFF); out.write(q and 0xFF)
        }
        data.forEach(::put)
        postCurves.forEach { it.forEach(::put) }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_SIZE = 17
        const val POST_SIZE = 256
        const val FORMAT = "rgb3d-u16be-v1"

        fun identityPost() = Array(3) { FloatArray(POST_SIZE) { i -> i / (POST_SIZE - 1f) } }

        fun identity(size: Int = DEFAULT_SIZE): ColorLut {
            val data = FloatArray(size * size * size * 3)
            var o = 0
            for (r in 0 until size) for (g in 0 until size) for (b in 0 until size) {
                data[o++] = r / (size - 1f); data[o++] = g / (size - 1f); data[o++] = b / (size - 1f)
            }
            return ColorLut(size, data, identityPost())
        }

        fun decode(size: Int, bytes: ByteArray): ColorLut {
            require(size in 2..65)
            val lutCount = size * size * size * 3
            require(bytes.size == (lutCount + 3 * POST_SIZE) * 2) { "LUT 길이 불일치" }
            fun at(i: Int) = ((bytes[2 * i].toInt() and 0xFF shl 8) or (bytes[2 * i + 1].toInt() and 0xFF)) / 65535f
            val data = FloatArray(lutCount) { at(it) }
            val post = Array(3) { c -> FloatArray(POST_SIZE) { at(lutCount + c * POST_SIZE + it) } }
            return ColorLut(size, data, post)
        }

        /**
         * (입력, 출력) 노드로 단조 증가 post 곡선을 만든다. 노드 밖 구간은 기존 곡선의 변화량을
         * 유지(노드가 없는 밝은 끝·어두운 끝을 함부로 바꾸지 않음)하고, 0은 항상 0에 고정한다.
         */
        fun curveFromNodes(nodes: List<Pair<Double, Double>>, previous: FloatArray): FloatArray {
            val sorted = nodes.filter { it.first.isFinite() && it.second.isFinite() && it.first > 1e-4 }
                .sortedBy { it.first }
            if (sorted.isEmpty()) return previous.copyOf()
            fun prev(v: Double): Double {
                val f = v.coerceIn(0.0, 1.0) * (POST_SIZE - 1)
                val i = min(f.toInt(), POST_SIZE - 2)
                val t = f - i
                return previous[i] * (1 - t) + previous[i + 1] * t
            }
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            xs += 0.0; ys += 0.0
            for ((x, y) in sorted) {
                if (x - xs.last() < 1e-4) { ys[ys.size - 1] = y; continue }
                xs += x; ys += y.coerceIn(0.0, 1.0)
            }
            val lastX = xs.last()
            val lastDelta = ys.last() - prev(lastX)
            val out = FloatArray(POST_SIZE)
            for (i in 0 until POST_SIZE) {
                val v = i / (POST_SIZE - 1.0)
                val value = if (v >= lastX) {
                    prev(v) + lastDelta
                } else {
                    var j = 0
                    while (j < xs.size - 2 && v > xs[j + 1]) j++
                    val t = (v - xs[j]) / (xs[j + 1] - xs[j])
                    ys[j] * (1 - t) + ys[j + 1] * t
                }
                out[i] = value.coerceIn(0.0, 1.0).toFloat()
            }
            out[0] = 0f
            for (i in 1 until POST_SIZE) if (out[i] < out[i - 1]) out[i] = out[i - 1]
            return out
        }
    }
}

/**
 * 근사 색차. 카메라 RGB를 선형 sRGB로 가정해 XYZ → CIELAB(기준 흰색 = 목표 흰색)으로 바꾼 뒤
 * CIEDE2000을 계산한다. 분광 측색기 값이 아니므로 "카메라 기준 근사 ΔE00"으로만 보고한다.
 */
internal object ColorDifference {
    private fun xyz(c: Rgb) = doubleArrayOf(
        0.4124 * c.r + 0.3576 * c.g + 0.1805 * c.b,
        0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b,
        0.0193 * c.r + 0.1192 * c.g + 0.9505 * c.b,
    )

    fun lab(c: Rgb, white: Rgb): DoubleArray {
        val x = xyz(c); val w = xyz(white)
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f(x[0] / w[0]); val fy = f(x[1] / w[1]); val fz = f(x[2] / w[2])
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun deltaE2000(a: Rgb, b: Rgb, white: Rgb): Double = ciede2000(lab(a, white), lab(b, white))

    fun ciede2000(l1: DoubleArray, l2: DoubleArray): Double {
        val (L1, a1, b1) = Triple(l1[0], l1[1], l1[2])
        val (L2, a2, b2) = Triple(l2[0], l2[1], l2[2])
        val c1 = sqrt(a1 * a1 + b1 * b1); val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cBar.pow(7) / (cBar.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * a1; val a2p = (1 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1); val c2p = sqrt(a2p * a2p + b2 * b2)
        fun hue(bb: Double, aa: Double): Double {
            if (bb == 0.0 && aa == 0.0) return 0.0
            val h = Math.toDegrees(atan2(bb, aa))
            return if (h < 0) h + 360 else h
        }
        val h1p = hue(b1, a1p); val h2p = hue(b2, a2p)
        val dLp = L2 - L1
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180 -> h2p - h1p
            h2p - h1p > 180 -> h2p - h1p - 360
            else -> h2p - h1p + 360
        }
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
        val lBarP = (L1 + L2) / 2
        val cBarP = (c1p + c2p) / 2
        val hBarP = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hBarP - 30)) + 0.24 * cos(Math.toRadians(2 * hBarP)) +
            0.32 * cos(Math.toRadians(3 * hBarP + 6)) - 0.20 * cos(Math.toRadians(4 * hBarP - 63))
        val dTheta = 30 * exp(-((hBarP - 275) / 25).pow(2))
        val rc = 2 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lBarP - 50).pow(2) / sqrt(20 + (lBarP - 50).pow(2))
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        val x = dLp / sl; val y = dCp / sc; val z = dHp / sh
        return sqrt(x * x + y * y + z * z + rt * y * z)
    }
}

/**
 * 시스템 전역 근사용 3×3 행렬. 앱 밖에는 임의 3D LUT를 걸 수 없으므로, LUT를 선형광(sRGB EOTF 가정)
 * 영역의 3×3 행렬로 최소제곱 근사한다. 실험용 출력이며 적용 공간(선형/감마)과 행렬 순서는 기기·
 * Android 버전별 검증이 필요하다.
 */
internal object GlobalMatrixFit {
    fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    /** 반환: 행 우선 3×3, out_lin = M · in_lin */
    fun fit(lut: ColorLut, samples: Int = 9): DoubleArray {
        val xx = DoubleArray(9); val yx = DoubleArray(9)
        for (ri in 0 until samples) for (gi in 0 until samples) for (bi in 0 until samples) {
            val c = doubleArrayOf(ri / (samples - 1.0), gi / (samples - 1.0), bi / (samples - 1.0))
            val o = lut.apply(c[0], c[1], c[2])
            val x = DoubleArray(3) { srgbToLinear(c[it]) }
            val y = DoubleArray(3) { srgbToLinear(o[it]) }
            for (i in 0..2) for (j in 0..2) { xx[i * 3 + j] += x[i] * x[j]; yx[i * 3 + j] += y[i] * x[j] }
        }
        val inv = Mat3(xx).inverse().a
        val m = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += yx[i * 3 + k] * inv[k * 3 + j]
            m[i * 3 + j] = s
        }
        return m
    }

    /**
     * `service call SurfaceFlinger 1015` 형식 문자열(4×4 column-major, 마지막 행/열은 단위).
     * 자동 실행하지 않으며, 사용자가 adb shell에서 직접 검증하도록 리포트에만 남긴다.
     */
    fun surfaceFlingerCommand(m: DoubleArray): String {
        val col = doubleArrayOf(
            m[0], m[3], m[6], 0.0,
            m[1], m[4], m[7], 0.0,
            m[2], m[5], m[8], 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        return "adb shell service call SurfaceFlinger 1015 i32 1 " +
            col.joinToString(" ") { "f " + String.format(java.util.Locale.US, "%.6f", it) }
    }
}
