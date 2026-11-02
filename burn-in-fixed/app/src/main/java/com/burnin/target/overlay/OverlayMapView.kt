package com.burnin.target.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.Surface
import android.view.View
import com.burnin.target.correction.CorrectionStore

/**
 * 오버레이 보정맵을 그리는 뷰. 맵은 측정할 때의 화면 방향([mapRotation]) 좌표라서, 화면이 돌아가면
 * 같은 물리 픽셀에 같은 맵 픽셀이 오도록 90도 단위로 돌려 그린다(번인은 패널에 붙어 있다).
 * 90도 단위 회전 + 정수 이동이라 픽셀이 1:1로 옮겨지고 보간이 없다. 그리기는 하드웨어 가속 캔버스(GPU).
 */
class OverlayMapView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 맵이 측정된 화면 방향(Surface.ROTATION_*). */
    var mapRotation: Int = Surface.ROTATION_0
        set(value) {
            field = value
            invalidate()
        }

    /** 0~255 */
    var mapAlpha: Int = 255
        set(value) {
            field = value
            paint.alpha = value
            invalidate()
        }

    private val paint = Paint()
    private val matrix = Matrix()
    private val inverse = Matrix()

    /** 마지막으로 그린 화면 방향. 로그·검증용. */
    var drawnRotation: Int = -1
        private set

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return
        val natural = CorrectionStore.naturalScreenSize(context)
        val current = CorrectionStore.displayRotation(context)
        mapToWindow(mapRotation, current, natural.x.toFloat(), natural.y.toFloat(), matrix, inverse)
        drawnRotation = current
        canvas.drawBitmap(bmp, matrix, paint)
    }

    companion object {
        /**
         * 고유 방향(물리) 좌표 → 방향 [rotation]의 화면 좌표. AOSP의 physical→logical 변환과 같다.
         * [w]·[h]는 고유 방향의 화면 크기.
         */
        fun physicalToLogical(rotation: Int, w: Float, h: Float, out: Matrix) {
            out.reset()
            when (rotation) {
                Surface.ROTATION_90 -> { out.setRotate(270f); out.postTranslate(0f, w) }
                Surface.ROTATION_180 -> { out.setRotate(180f); out.postTranslate(w, h) }
                Surface.ROTATION_270 -> { out.setRotate(90f); out.postTranslate(h, 0f) }
            }
        }

        /** 방향 [from]에서 잰 맵 → 지금 방향 [to]의 화면 좌표. */
        fun mapToWindow(from: Int, to: Int, w: Float, h: Float, out: Matrix, scratch: Matrix) {
            physicalToLogical(from, w, h, scratch)
            scratch.invert(scratch)
            physicalToLogical(to, w, h, out)
            out.preConcat(scratch)
        }
    }
}
