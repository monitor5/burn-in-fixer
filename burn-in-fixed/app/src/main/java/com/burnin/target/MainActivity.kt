package com.burnin.target

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.burnin.target.correction.ColorProfileStore
import com.burnin.target.correction.CorrectionStore
import com.burnin.target.correction.WhiteBalanceStore
import com.burnin.target.net.ControlServer
import com.burnin.target.net.Protocol
import com.burnin.target.overlay.OverlayService
import com.burnin.target.pattern.PatternActivity
import com.burnin.target.pattern.PatternBus
import com.burnin.target.pattern.PatternSpec
import com.burnin.target.util.AppLog
import com.burnin.target.util.CrashLog
import com.burnin.target.util.NetUtils

/**
 * 대상 기기 앱 홈 화면.
 * - TCP 서버를 시작하고 측정 기기가 입력할 IP:포트를 표시한다.
 * - 보정 프로파일 상태 확인, 보정 미리보기, 시스템 오버레이 on/off, 강도 조절.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var txtIp: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtProfile: TextView
    private lateinit var txtRole: TextView
    private lateinit var txtLog: TextView
    private lateinit var scrollLog: ScrollView

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtIp = findViewById(R.id.txtIp)
        txtStatus = findViewById(R.id.txtStatus)
        txtProfile = findViewById(R.id.txtProfile)
        txtRole = findViewById(R.id.txtRole)
        txtLog = findViewById(R.id.txtLog)
        scrollLog = findViewById(R.id.scrollLog)

        findViewById<Button>(R.id.btnRoleAdjustment).setOnClickListener {
            setRole(Protocol.ROLE_ADJUSTMENT)
        }
        findViewById<Button>(R.id.btnRoleReference).setOnClickListener {
            setRole(Protocol.ROLE_REFERENCE)
        }
        findViewById<Button>(R.id.btnPattern).setOnClickListener {
            startActivity(Intent(this, PatternActivity::class.java))
        }
        findViewById<Button>(R.id.btnPreview).setOnClickListener { openPreview() }
        findViewById<Button>(R.id.btnColorProfile).setOnClickListener { toggleColorProfile() }
        findViewById<Button>(R.id.btnOverlayOn).setOnClickListener { enableOverlay() }
        findViewById<Button>(R.id.btnOverlayOff).setOnClickListener {
            OverlayService.requestStop(this)
        }

        val txtStrength = findViewById<TextView>(R.id.txtStrength)
        findViewById<SeekBar>(R.id.seekStrength).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    txtStrength.text = "$progress%"
                    if (fromUser) PatternBus.setCorrection(PatternBus.correctionEnabled, progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    if (OverlayService.running) OverlayService.requestStart(this@MainActivity, sb.progress)
                }
            })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        CrashLog.install(this)
        // 액티비티만 다시 만들어진 경우(같은 프로세스)에는 이미 적재된 프로파일을 다시 굽지 않는다.
        if (CorrectionStore.meta == null) CorrectionStore.loadFromDisk(this)
        if (WhiteBalanceStore.meta == null) WhiteBalanceStore.loadFromDisk(this)
        if (ColorProfileStore.meta == null) ColorProfileStore.loadFromDisk(this)
        ControlServer.start(this)
        showDisclaimerOnce()
        val rt = Runtime.getRuntime()
        AppLog.i("대상 기기 앱 시작 (pid ${android.os.Process.myPid()}, heap max ${rt.maxMemory() shr 20}MB)")
    }

    override fun onResume() {
        super.onResume()
        AppLog.listener = { text ->
            txtLog.text = text
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        txtLog.text = AppLog.dump()
        handler.post(ticker)
    }

    override fun onPause() {
        AppLog.listener = null
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refreshStatus() {
        val role = DeviceRole.get(this)
        val ip = NetUtils.localIpv4()
        txtIp.text = if (ip != null) "이 기기 주소:  $ip : ${Protocol.PORT}"
        else "Wi-Fi 미연결 — 측정 기기와 같은 Wi-Fi에 연결하세요"
        txtRole.text = "역할: ${DeviceRole.label(role)}"

        val client = ControlServer.clientAddress
        val screen = CorrectionStore.realScreenSize(this)
        txtStatus.text = buildString {
            append("화면 ${screen.x}x${screen.y}")
            append("  |  측정 기기: ${client ?: "대기 중"}")
            append("  |  오버레이: ${if (OverlayService.running) "ON" else "OFF"}")
            if (role == Protocol.ROLE_REFERENCE) append("  |  보정 잠금")
        }

        val m = CorrectionStore.meta
        val wb = WhiteBalanceStore.meta
        val correctionText = if (m == null) "보정 프로파일: 없음"
        else "보정 프로파일: ${m.width}x${m.height}, 최대 감쇠 ${(m.maxAttenuation * 100).toInt()}%, ${m.createdAt}"
        val wbText = if (wb == null) "화이트밸런스: 없음"
        else "화이트밸런스: R ${fmtGain(wb.redGain)}, G ${fmtGain(wb.greenGain)}, B ${fmtGain(wb.blueGain)}"
        val cp = ColorProfileStore.meta
        val cpText = if (cp == null) "색감 프로파일: 없음"
        else "색감 프로파일: 기준 ${cp.referenceDevice.ifEmpty { "?" }}, LUT ${cp.lutSize}³, ${if (cp.enabled) "ON" else "OFF"}"
        txtProfile.text = "$correctionText\n$wbText\n$cpText"
        findViewById<Button>(R.id.btnColorProfile).text = when {
            cp == null -> "색감 보정: 프로파일 없음"
            cp.enabled -> "색감 보정 끄기"
            else -> "색감 보정 켜기"
        }
    }

    private fun setRole(role: String) {
        DeviceRole.set(this, role)
        if (role == Protocol.ROLE_REFERENCE) {
            PatternBus.setCorrection(false, null)
            OverlayService.requestStop(this)
        }
        AppLog.i("역할 설정: ${DeviceRole.label(role)}")
        refreshStatus()
    }

    /** 회색 70% 패턴 + 보정 ON 상태로 패턴 화면을 열어 육안 비교(탭으로 전/후 토글). */
    private fun openPreview() {
        if (DeviceRole.isReference(this)) {
            AppLog.i("대조설비는 보정 미리보기를 사용하지 않습니다")
            return
        }
        if (CorrectionStore.bakedBitmap == null && WhiteBalanceStore.rgbAttenuationBitmap == null) {
            AppLog.i("보정맵/화이트밸런스가 없어 미리보기 불가")
            return
        }
        PatternBus.setCorrection(true, null)
        PatternBus.showPattern(this, PatternSpec.parse("gray70")!!) { }
    }

    /** 색감 프로파일은 번인 보정과 별개로 켜고 끈다. 앱 자체 렌더링(패턴 화면)에만 적용된다. */
    private fun toggleColorProfile() {
        if (DeviceRole.isReference(this)) {
            AppLog.i("대조설비는 색감 보정을 사용하지 않습니다")
            return
        }
        val cp = ColorProfileStore.meta ?: run {
            AppLog.i("색감 프로파일이 없습니다 — Scanner의 [색감 맞추기]로 먼저 측정하세요")
            return
        }
        val error = ColorProfileStore.setEnabled(this, !cp.enabled)
        if (error != null) AppLog.i(error)
        PatternBus.refresh()
        refreshStatus()
    }

    private fun enableOverlay() {
        if (DeviceRole.isReference(this)) {
            AppLog.i("대조설비는 오버레이를 켤 수 없습니다")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("오버레이 권한 필요")
                .setMessage("시스템 전체 화면 보정을 위해 '다른 앱 위에 표시' 권한이 필요합니다.")
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val error = OverlayService.requestStart(this, findViewById<SeekBar>(R.id.seekStrength).progress)
        if (error != null) AppLog.i("오버레이 시작 실패: $error")
    }

    private fun fmtGain(v: Double): String =
        String.format(java.util.Locale.US, "%.3f", v)

    private fun showDisclaimerOnce() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("disclaimer_shown", false)) return
        AlertDialog.Builder(this)
            .setTitle("사용 전 안내")
            .setMessage(
                "· 이 앱은 OLED 픽셀을 물리적으로 복구하지 않습니다.\n" +
                    "· 보정은 정상 영역의 밝기를 낮춰 화면을 균일하게 보이게 하는 방식입니다.\n" +
                    "· 보정 강도가 높을수록 화면 최대 밝기가 낮아질 수 있습니다.\n" +
                    "· 심한 번인은 완전히 숨길 수 없습니다.\n" +
                    "· 측정 정확도는 카메라, 조명, 각도, 화면 밝기의 영향을 받습니다."
            )
            .setPositiveButton("확인") { _, _ ->
                prefs.edit().putBoolean("disclaimer_shown", true).apply()
            }
            .show()
    }
}
