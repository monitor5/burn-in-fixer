package com.burnin.target.pattern

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.burnin.target.DeviceRole
import com.burnin.target.correction.ColorProfileStore
import com.burnin.target.correction.CorrectionStore
import com.burnin.target.correction.OutputTransfer
import com.burnin.target.correction.WhiteBalanceStore
import com.burnin.target.util.AppLog

/**
 * 전체 화면 패턴 표시 액티비티 (T-FR-002/003/004).
 * - immersive sticky 전체 화면, 화면 꺼짐 방지, 밝기 0.75 고정, 회전 잠금
 * - 화면을 탭하면 (보정맵이 있을 때) 보정 전/후를 토글하여 육안 비교 가능
 */
class PatternActivity : Activity(), PatternBus.Host {

    private lateinit var view: PatternView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = PatternView(this)
        setContentView(view)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 측정 재현성을 위해 창 밝기를 75%로 고정한다 (자동 밝기 무시).
        window.attributes = window.attributes.apply {
            screenBrightness = 0.75f
            // 펀치홀/노치 기기에서도 창을 실제 화면 전체로 확장해 1:1 좌표를 보장한다
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        view.setOnClickListener {
            if (DeviceRole.isReference(this)) {
                PatternBus.correctionEnabled = false
                applyCorrectionState()
                AppLog.i("대조설비는 보정을 켜지 않습니다")
                return@setOnClickListener
            }
            if (
                view.correctionBitmap != null ||
                view.correctionRgbBitmap != null ||
                view.whiteBalanceRgbBitmap != null
            ) {
                PatternBus.correctionEnabled = !view.correctionEnabled
                applyCorrectionState()
                AppLog.i("탭 토글: 보정 ${if (view.correctionEnabled) "ON" else "OFF"}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        PatternBus.register(this)
        view.spec = PatternBus.currentSpec
        if (DeviceRole.isReference(this)) {
            PatternBus.correctionEnabled = false
        }
        view.correctionBitmap = CorrectionStore.bakedBitmap
        view.correctionRgbBitmap = CorrectionStore.rgbAttenuationBitmap
        view.correctionMaxAttenuation = CorrectionStore.meta?.maxAttenuation ?: 0.05
        view.outputGamma = CorrectionStore.meta?.outputGamma ?: OutputTransfer.DEFAULT_GAMMA
        view.whiteBalanceRgbBitmap = WhiteBalanceStore.rgbAttenuationBitmap
        view.whiteBalanceMaxAttenuation = WhiteBalanceStore.meta?.maxAttenuation ?: 0.10
        applyCorrectionState()
    }

    override fun onPause() {
        PatternBus.unregister(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        view.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    // ---- PatternBus.Host ----

    override fun applyPattern(spec: PatternSpec, done: Runnable) {
        view.spec = spec
        view.correctionBitmap = CorrectionStore.bakedBitmap
        applyCorrectionState()
        // 두 프레임 뒤 = 실제 화면에 커밋된 뒤 ACK
        view.post { view.post(done) }
    }

    override fun applyCorrectionState() {
        // 세션 중 새 보정맵이 수신됐을 수 있으므로 비트맵도 함께 갱신한다
        view.correctionBitmap = CorrectionStore.bakedBitmap
        view.correctionRgbBitmap = CorrectionStore.rgbAttenuationBitmap
        view.correctionMaxAttenuation = CorrectionStore.meta?.maxAttenuation ?: 0.05
        view.outputGamma = CorrectionStore.meta?.outputGamma ?: OutputTransfer.DEFAULT_GAMMA
        view.whiteBalanceRgbBitmap = WhiteBalanceStore.rgbAttenuationBitmap
        view.whiteBalanceMaxAttenuation = WhiteBalanceStore.meta?.maxAttenuation ?: 0.10
        view.correctionEnabled = !DeviceRole.isReference(this) && PatternBus.correctionEnabled
        view.colorLut = if (DeviceRole.isReference(this)) null else ColorProfileStore.activeLut
        view.strengthPct = PatternBus.strengthPct
        view.overlayEquivalent = PatternBus.overlayEquivalent
    }

    override fun closeSelf() {
        finish()
    }
}
