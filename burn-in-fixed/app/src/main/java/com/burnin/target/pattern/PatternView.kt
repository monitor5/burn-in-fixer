package com.burnin.target.pattern

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.burnin.target.correction.ColorLut
import com.burnin.target.correction.OutputTransfer
import com.burnin.target.util.AppLog
import kotlin.math.min

/**
 * 전체 화면 테스트 패턴 렌더러.
 * 보정이 켜져 있으면 패턴 위에 알파 감쇠 비트맵(검정 + 픽셀별 알파)을 1:1로 덮어 그린다.
 * (앱 내부 보정. 요구서 T-FR-008의 OpenGL 셰이더 대신 MVP에서는 Canvas 합성으로 동일한
 *  "정상 영역을 낮추는" 알파 감쇠를 수행한다.)
 */
class PatternView(context: Context) : View(context) {

    var spec: PatternSpec = PatternSpec.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var correctionBitmap: Bitmap? = null
        set(value) {
            field = value
            clearSolidCache()
            invalidate()
        }

    var correctionRgbBitmap: Bitmap? = null
        set(value) {
            field = value
            clearSolidCache()
            invalidate()
        }

    var correctionMaxAttenuation: Double = 0.05
        set(value) {
            field = value
            clearSolidCache()
            invalidate()
        }

    var whiteBalanceRgbBitmap: Bitmap? = null
        set(value) {
            field = value
            clearSolidCache()
            invalidate()
        }

    var whiteBalanceMaxAttenuation: Double = 0.10
        set(value) {
            field = value
            clearSolidCache()
            invalidate()
        }

    /**
     * 패널 코드→휘도 지수. 감쇠맵은 선형 휘도 gain이므로 단색 경로에서 코드에는 gain^(1/γ)를 곱한다.
     * (알파 비트맵 경로는 [com.burnin.target.correction.CorrectionStore]가 구울 때 이미 변환한다.)
     */
    var outputGamma: Double = OutputTransfer.DEFAULT_GAMMA
        set(value) {
            val v = OutputTransfer.sanitizeGamma(value)
            if (v == field) return
            field = v
            clearSolidCache()
            invalidate()
        }

    /** 기준 기기 색감 일치 LUT. 번인 보정 ON/OFF와 독립적으로 단색 패턴에만 적용된다. */
    var colorLut: ColorLut? = null
        set(value) {
            field = value
            clearSolidCache()
            invalidate()
        }

    var correctionEnabled: Boolean = false
        set(value) {
            field = value
            clearSolidCache()
            invalidate()
        }

    /** 0~100 (%) 보정 강도. 알파 감쇠맵 전체에 곱해진다. */
    var strengthPct: Int = 100
        set(value) {
            field = value.coerceIn(0, 100)
            clearSolidCache()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var solidCache: Bitmap? = null
    private var solidCacheColor: Int = 0
    private var solidCacheW: Int = 0
    private var solidCacheH: Int = 0
    private var solidCacheStrength: Int = -1
    private var solidCacheRgb: Bitmap? = null
    private var solidCacheWhiteBalanceRgb: Bitmap? = null
    private var solidCacheCorrectionMaxAttenuation: Double = -1.0
    private var solidCacheWhiteBalanceMaxAttenuation: Double = -1.0
    private var solidCacheGamma: Double = -1.0
    private var sparseCache: Bitmap? = null
    private var sparseCacheSpec: PatternSpec? = null

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        var usedCorrectionRgbSolid = false
        when (spec.kind) {
            PatternSpec.Kind.SOLID -> {
                val rgb = correctionRgbBitmap
                val wb = whiteBalanceRgbBitmap
                val base = colorLut?.transformArgb(spec.color) ?: spec.color
                val corrected = if (correctionEnabled && (rgb != null || wb != null)) {
                    try {
                        correctedSolidBitmap(base, w, h, rgb, wb)
                    } catch (e: OutOfMemoryError) {
                        // 죽지 않고 측정 쪽에 패턴 표시 실패로 알린다(보정 없는 화면을 보정된 것으로 재지 않게).
                        clearSolidCache()
                        PatternBus.renderError = "보정 화면 생성 메모리 부족 (${w}x${h})"
                        AppLog.i("!! ${PatternBus.renderError}")
                        null
                    }
                } else null
                if (corrected != null) {
                    canvas.drawBitmap(corrected, 0f, 0f, null)
                    usedCorrectionRgbSolid = rgb != null
                } else {
                    canvas.drawColor(base)
                }
            }

            PatternSpec.Kind.MARKER -> drawMarker(canvas, w, h)

            PatternSpec.Kind.GRID -> {
                canvas.drawColor(Color.BLACK)
                paint.color = Color.WHITE
                paint.strokeWidth = 2f
                val n = spec.cells
                for (i in 0..n) {
                    val x = w * i / n.toFloat()
                    val y = h * i / n.toFloat()
                    canvas.drawLine(x, 0f, x, h.toFloat(), paint)
                    if (i <= n) canvas.drawLine(0f, y, w.toFloat(), y, paint)
                }
            }

            PatternSpec.Kind.CHECKER -> {
                canvas.drawColor(Color.BLACK)
                paint.color = Color.WHITE
                val cell = w / spec.cells.toFloat() // 정사각 셀
                val rows = Math.ceil(h / cell.toDouble()).toInt()
                for (r in 0 until rows) {
                    for (c in 0 until spec.cells) {
                        if ((r + c) % 2 == 0) {
                            canvas.drawRect(c * cell, r * cell, (c + 1) * cell, (r + 1) * cell, paint)
                        }
                    }
                }
            }

            PatternSpec.Kind.SPARSE -> {
                canvas.drawColor(Color.BLACK)
                if (w > 0 && h > 0) canvas.drawBitmap(sparseBitmap(spec, w, h), 0f, 0f, null)
            }

            PatternSpec.Kind.DOTGRID -> {
                canvas.drawColor(Color.BLACK)
                paint.color = Color.WHITE
                val n = spec.cells
                val radius = min(w, h) / (n * 6f)
                for (r in 1 until n) {
                    for (c in 1 until n) {
                        canvas.drawCircle(w * c / n.toFloat(), h * r / n.toFloat(), radius, paint)
                    }
                }
            }
        }

        val bmp = correctionBitmap
        if (correctionEnabled && bmp != null && spec.name != "marker" && !usedCorrectionRgbSolid) {
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0, 0, w, h)
            mapPaint.alpha = strengthPct * 255 / 100
            canvas.drawBitmap(bmp, srcRect, dstRect, mapPaint)
        }
    }

    /** 네 모서리 기준 사각형 + 방향 식별용 비대칭 마커(좌상단 이중 사각형) + 중앙 십자 + 격자점 */
    private fun drawMarker(canvas: Canvas, w: Int, h: Int) {
        canvas.drawColor(Color.BLACK)
        paint.color = Color.WHITE
        val m = min(w, h)
        val size = m * 0.10f
        val inset = m * 0.04f

        // 네 모서리 사각형
        canvas.drawRect(inset, inset, inset + size, inset + size, paint)                       // TL
        canvas.drawRect(w - inset - size, inset, w - inset, inset + size, paint)               // TR
        canvas.drawRect(w - inset - size, h - inset - size, w - inset, h - inset, paint)       // BR
        canvas.drawRect(inset, h - inset - size, inset + size, h - inset, paint)               // BL

        // 좌상단 비대칭(방향) 마커: 흰 사각형 내부에 검정 사각형
        paint.color = Color.BLACK
        val q = size * 0.4f
        canvas.drawRect(inset + q, inset + q, inset + size - q * 0.5f, inset + size - q * 0.5f, paint)

        // 중앙 십자
        paint.color = Color.WHITE
        paint.strokeWidth = m * 0.006f
        canvas.drawLine(w / 2f - size, h / 2f, w / 2f + size, h / 2f, paint)
        canvas.drawLine(w / 2f, h / 2f - size, w / 2f, h / 2f + size, paint)

        // 격자점
        val n = 8
        for (r in 1 until n) {
            for (c in 1 until n) {
                canvas.drawCircle(w * c / n.toFloat(), h * r / n.toFloat(), m * 0.004f, paint)
            }
        }
    }

    private fun correctedSolidBitmap(
        color: Int,
        w: Int,
        h: Int,
        rgb: Bitmap?,
        whiteBalanceRgb: Bitmap?,
    ): Bitmap {
        solidCache?.let { cached ->
            if (
                solidCacheColor == color &&
                solidCacheW == w &&
                solidCacheH == h &&
                solidCacheStrength == strengthPct &&
                solidCacheRgb === rgb &&
                solidCacheWhiteBalanceRgb === whiteBalanceRgb &&
                solidCacheCorrectionMaxAttenuation == correctionMaxAttenuation &&
                solidCacheWhiteBalanceMaxAttenuation == whiteBalanceMaxAttenuation &&
                solidCacheGamma == outputGamma
            ) {
                return cached
            }
        }
        clearSolidCache()
        val correctionMap = scaledMap(rgb, w, h)
        val whiteBalanceMap = scaledMap(whiteBalanceRgb, w, h)
        // 감쇠 맵은 한 행씩만 읽는다(전체 화면 IntArray 두 장을 피해 구형 기기 힙 사용을 줄인다).
        val correctionRow = correctionMap?.let { IntArray(w) }
        val whiteBalanceRow = whiteBalanceMap?.let { IntArray(w) }
        var loadedRow = -1
        val strength = strengthPct / 100.0
        val correctionMaxAtt = correctionMaxAttenuation
        val whiteBalanceMaxAtt = whiteBalanceMaxAttenuation
        // 두 감쇠 층의 8비트 값 쌍 → 코드 gain. 감쇠는 선형 휘도 기준이라 곱한 뒤 1/γ 제곱한다.
        val gamma = outputGamma
        val codeGainLut = DoubleArray(256 * 256) { k ->
            val lum = (1.0 - (k shr 8) / 255.0 * correctionMaxAtt * strength) *
                (1.0 - (k and 0xFF) / 255.0 * whiteBalanceMaxAtt * strength)
            OutputTransfer.codeGain(lum, gamma)
        }
        val out = IntArray(w * h) { 0xFF000000.toInt() }
        for (channel in 0 until 3) {
            val shift = 16 - 8 * channel
            val base = (color shr shift) and 0xFF
            loadedRow = -1
            OutputTransfer.dither(w, h, 255, { i ->
                val y = i / w
                if (y != loadedRow) {
                    if (correctionRow != null) correctionMap?.getPixels(correctionRow, 0, w, 0, y, w, 1)
                    if (whiteBalanceRow != null) whiteBalanceMap?.getPixels(whiteBalanceRow, 0, w, 0, y, w, 1)
                    loadedRow = y
                }
                val x = i - y * w
                val c = if (correctionRow != null) (correctionRow[x] shr shift) and 0xFF else 0
                val wbv = if (whiteBalanceRow != null) (whiteBalanceRow[x] shr shift) and 0xFF else 0
                base * codeGainLut[(c shl 8) or wbv]
            }) { i, v -> out[i] = out[i] or (v shl shift) }
        }
        if (correctionMap != null && correctionMap !== rgb) correctionMap.recycle()
        if (whiteBalanceMap != null && whiteBalanceMap !== whiteBalanceRgb) whiteBalanceMap.recycle()
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        solidCache = bmp
        solidCacheColor = color
        solidCacheW = w
        solidCacheH = h
        solidCacheStrength = strengthPct
        solidCacheRgb = rgb
        solidCacheWhiteBalanceRgb = whiteBalanceRgb
        solidCacheCorrectionMaxAttenuation = correctionMaxAttenuation
        solidCacheWhiteBalanceMaxAttenuation = whiteBalanceMaxAttenuation
        solidCacheGamma = outputGamma
        return bmp
    }

    /**
     * 희소 점등 패턴은 픽셀 단위로 정확해야 하므로 안티에일리어싱 없는 비트맵을 직접 채운다.
     * 뷰가 실제 화면 해상도와 1:1이어야 의미가 있다 (디스플레이 해상도 축소 모드에서는 블록이 번진다).
     */
    private fun sparseBitmap(spec: PatternSpec, w: Int, h: Int): Bitmap {
        sparseCache?.let { cached ->
            if (sparseCacheSpec == spec && cached.width == w && cached.height == h) return cached
        }
        sparseCache?.recycle()
        val pixels = IntArray(w * h) { Color.BLACK }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (spec.sparseLit(x, y)) pixels[y * w + x] = spec.color
            }
        }
        val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        sparseCache = bmp
        sparseCacheSpec = spec
        return bmp
    }

    private fun scaledMap(src: Bitmap?, w: Int, h: Int): Bitmap? {
        if (src == null) return null
        return if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun clearSolidCache() {
        solidCache?.recycle()
        solidCache = null
        solidCacheRgb = null
        solidCacheWhiteBalanceRgb = null
    }

    override fun onDetachedFromWindow() {
        clearSolidCache()
        sparseCache?.recycle()
        sparseCache = null
        sparseCacheSpec = null
        super.onDetachedFromWindow()
    }
}
