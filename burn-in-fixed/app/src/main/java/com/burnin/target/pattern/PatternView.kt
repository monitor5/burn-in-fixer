package com.burnin.target.pattern

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
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
                if (correctionEnabled && (rgb != null || wb != null)) {
                    canvas.drawBitmap(correctedSolidBitmap(spec.color, w, h, rgb, wb), 0f, 0f, null)
                    usedCorrectionRgbSolid = rgb != null
                } else {
                    canvas.drawColor(spec.color)
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
                solidCacheWhiteBalanceMaxAttenuation == whiteBalanceMaxAttenuation
            ) {
                return cached
            }
        }
        clearSolidCache()
        val correctionMap = scaledMap(rgb, w, h)
        val whiteBalanceMap = scaledMap(whiteBalanceRgb, w, h)
        val correctionAtt = correctionMap?.let {
            IntArray(w * h).also { out -> it.getPixels(out, 0, w, 0, 0, w, h) }
        }
        val whiteBalanceAtt = whiteBalanceMap?.let {
            IntArray(w * h).also { out -> it.getPixels(out, 0, w, 0, 0, w, h) }
        }
        if (correctionMap != null && correctionMap !== rgb) correctionMap.recycle()
        if (whiteBalanceMap != null && whiteBalanceMap !== whiteBalanceRgb) whiteBalanceMap.recycle()
        val baseR = Color.red(color)
        val baseG = Color.green(color)
        val baseB = Color.blue(color)
        val strength = strengthPct / 100.0
        val correctionMaxAtt = correctionMaxAttenuation
        val whiteBalanceMaxAtt = whiteBalanceMaxAttenuation
        val out = IntArray(w * h)
        for (i in out.indices) {
            var rGain = 1.0
            var gGain = 1.0
            var bGain = 1.0
            correctionAtt?.let { att ->
                val p = att[i]
                rGain *= 1.0 - (Color.red(p) / 255.0) * correctionMaxAtt * strength
                gGain *= 1.0 - (Color.green(p) / 255.0) * correctionMaxAtt * strength
                bGain *= 1.0 - (Color.blue(p) / 255.0) * correctionMaxAtt * strength
            }
            whiteBalanceAtt?.let { att ->
                val p = att[i]
                rGain *= 1.0 - (Color.red(p) / 255.0) * whiteBalanceMaxAtt * strength
                gGain *= 1.0 - (Color.green(p) / 255.0) * whiteBalanceMaxAtt * strength
                bGain *= 1.0 - (Color.blue(p) / 255.0) * whiteBalanceMaxAtt * strength
            }
            val r = Math.round(baseR * rGain).toInt().coerceIn(0, 255)
            val g = Math.round(baseG * gGain).toInt().coerceIn(0, 255)
            val b = Math.round(baseB * bGain).toInt().coerceIn(0, 255)
            out[i] = Color.rgb(r, g, b)
        }
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
