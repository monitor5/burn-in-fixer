package com.burnin.target.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import android.widget.ImageView
import com.burnin.target.DeviceRole
import com.burnin.target.correction.CorrectionStore
import com.burnin.target.util.AppLog

/**
 * 시스템 오버레이 보정 (T-FR-009/010/011, MVP 3).
 * - 전체 화면 위에 터치를 통과시키는 알파 감쇠 레이어를 띄운다.
 * - 알림의 "보정 끄기" 액션으로 즉시 비활성화할 수 있다 (긴급 비활성화).
 * - 화면 회전이 감지되면 좌표가 틀어지므로 안전하게 스스로 중지한다.
 * - Android 8.0+ 는 TYPE_APPLICATION_OVERLAY, 7.x 는 TYPE_SYSTEM_ALERT 를 사용한다.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.burnin.target.overlay.STOP"
        const val EXTRA_STRENGTH = "strength"
        private const val CHANNEL_ID = "overlay"
        private const val NOTI_ID = 11

        @Volatile var running = false
            private set

        /** 시작 요청. 반환값: null = 요청됨, 문자열 = 불가 사유 */
        fun requestStart(context: Context, strengthPct: Int): String? {
            if (DeviceRole.isReference(context)) return "대조설비에는 보정을 적용할 수 없음"
            if (CorrectionStore.bakedBitmap == null && !CorrectionStore.loadFromDisk(context)) {
                return "적재된 보정맵 없음"
            }
            if (!Settings.canDrawOverlays(context)) {
                return "오버레이 권한 없음 (앱에서 '다른 앱 위에 표시' 권한을 허용하세요)"
            }
            val i = Intent(context, OverlayService::class.java).putExtra(EXTRA_STRENGTH, strengthPct)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            return null
        }

        fun requestStop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private var overlayView: ImageView? = null
    private var initialOrientation = Configuration.ORIENTATION_UNDEFINED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val strength = (intent?.getIntExtra(EXTRA_STRENGTH, 100) ?: 100).coerceIn(0, 100)
        startForeground(NOTI_ID, buildNotification())

        val bmp = CorrectionStore.bakedBitmap
        if (bmp == null || DeviceRole.isReference(this) || !Settings.canDrawOverlays(this)) {
            AppLog.i("오버레이 시작 불가 (맵 또는 권한 없음)")
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayView == null) {
            val view = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(bmp)
                imageAlpha = strength * 255 / 100
            }
            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try {
                getSystemService(WindowManager::class.java).addView(view, lp)
                overlayView = view
                initialOrientation = resources.configuration.orientation
                running = true
                AppLog.i("시스템 오버레이 보정 ON (강도 $strength%)")
            } catch (e: Exception) {
                AppLog.i("오버레이 추가 실패: ${e.message}")
                stopSelf()
            }
        } else {
            overlayView?.setImageBitmap(bmp)
            overlayView?.imageAlpha = strength * 255 / 100
            AppLog.i("오버레이 강도 변경: $strength%")
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 회전하면 네이티브 1:1 정렬이 깨지므로 안전 중지 (T-FR-009)
        if (newConfig.orientation != initialOrientation) {
            AppLog.i("화면 회전 감지 → 오버레이 안전 중지")
            stopSelf()
        }
    }

    override fun onDestroy() {
        overlayView?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
        }
        overlayView = null
        running = false
        AppLog.i("시스템 오버레이 보정 OFF")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "오버레이 보정", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("번인 보정 오버레이 동작 중")
            .setContentText("탭 한 번으로 끌 수 있습니다")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "보정 끄기", stopIntent
                ).build()
            )
            .build()
    }
}
