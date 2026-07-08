package com.burnin.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.burnin.scanner.color.ColorCalibrationActivity
import com.burnin.scanner.measure.MeasurementActivity
import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import com.burnin.scanner.net.Session
import com.burnin.scanner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 측정 기기 앱 홈 화면: 대상 기기 IP 입력 → 연결(HELLO) → 측정 화면으로 이동.
 * (12.1 페어링 중 "수동 IP 입력" 경로의 MVP 구현. QR/자동 탐색은 후속.)
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var editIp: EditText
    private lateinit var editReferenceIp: EditText
    private lateinit var txtTarget: TextView
    private lateinit var txtReference: TextView
    private lateinit var btnMeasure: Button
    private lateinit var btnColorCalibrate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editIp = findViewById(R.id.editIp)
        editReferenceIp = findViewById(R.id.editReferenceIp)
        txtTarget = findViewById(R.id.txtTarget)
        txtReference = findViewById(R.id.txtReference)
        btnMeasure = findViewById(R.id.btnMeasure)
        btnColorCalibrate = findViewById(R.id.btnColorCalibrate)
        val txtLog = findViewById<TextView>(R.id.txtLog)
        val scrollLog = findViewById<ScrollView>(R.id.scrollLog)

        AppLog.listener = { text ->
            txtLog.text = text
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        editIp.setText(prefs.getString("last_adjust_ip", prefs.getString("last_ip", "")))
        editReferenceIp.setText(prefs.getString("last_reference_ip", ""))

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectAdjustment() }
        findViewById<Button>(R.id.btnConnectReference).setOnClickListener { connectReference() }
        btnMeasure.setOnClickListener {
            startActivity(Intent(this, MeasurementActivity::class.java))
        }
        btnColorCalibrate.setOnClickListener {
            startActivity(Intent(this, ColorCalibrationActivity::class.java))
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        AppLog.i("측정 기기 앱 시작")
    }

    private fun connectAdjustment() {
        val ip = editIp.text.toString().trim()
        if (ip.isEmpty()) {
            AppLog.i("조정설비 IP를 입력하세요")
            return
        }
        getSharedPreferences("app", MODE_PRIVATE).edit()
            .putString("last_adjust_ip", ip)
            .putString("last_ip", ip)
            .apply()
        txtTarget.text = "조정설비: 연결 중..."
        btnMeasure.isEnabled = false
        btnColorCalibrate.isEnabled = false

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    Session.client?.close()
                    val c = ControlClient(ip)
                    c.connect()
                    val r = c.request(JSONObject().put("cmd", Protocol.CMD_HELLO))
                    val role = r.optString("role", Protocol.ROLE_ADJUSTMENT)
                    if (role == Protocol.ROLE_REFERENCE) {
                        c.close()
                        throw IllegalStateException("이 기기는 대조설비로 설정되어 있습니다")
                    }
                    Session.client = c
                    r
                }
                val dev = reply.getJSONObject("device")
                val screen = reply.getJSONObject("screen")
                Session.screenWidth = screen.getInt("width")
                Session.screenHeight = screen.getInt("height")
                Session.screenRefreshRate = screen.optDouble("refreshRate", 60.0).toFloat()
                Session.targetName = "${dev.optString("manufacturer")} ${dev.optString("model")}"
                Session.targetRole = reply.optString("role", Protocol.ROLE_ADJUSTMENT)
                txtTarget.text =
                    "조정설비: ${Session.targetName} (Android ${dev.optString("android")}, " +
                        "화면 ${Session.screenWidth}x${Session.screenHeight})"
                updateActionButtons()
                AppLog.i("조정설비 연결 성공: $ip — 화면 ${Session.screenWidth}x${Session.screenHeight}")
            } catch (e: Exception) {
                txtTarget.text = "조정설비: 연결 실패"
                updateActionButtons()
                AppLog.i("조정설비 연결 실패: ${e.message} (대상 앱 실행/같은 Wi-Fi 여부 확인)")
            }
        }
    }

    private fun connectReference() {
        val ip = editReferenceIp.text.toString().trim()
        if (ip.isEmpty()) {
            AppLog.i("대조설비 IP를 입력하세요")
            return
        }
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("last_reference_ip", ip).apply()
        txtReference.text = "대조설비: 연결 중..."
        btnColorCalibrate.isEnabled = false

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    Session.referenceClient?.close()
                    val c = ControlClient(ip)
                    c.connect()
                    val r = c.request(JSONObject().put("cmd", Protocol.CMD_HELLO))
                    val role = r.optString("role", Protocol.ROLE_ADJUSTMENT)
                    if (role != Protocol.ROLE_REFERENCE) {
                        c.close()
                        throw IllegalStateException("이 기기를 먼저 대조설비로 설정하세요")
                    }
                    Session.referenceClient = c
                    r
                }
                val dev = reply.getJSONObject("device")
                val screen = reply.getJSONObject("screen")
                Session.referenceScreenWidth = screen.getInt("width")
                Session.referenceScreenHeight = screen.getInt("height")
                Session.referenceName = "${dev.optString("manufacturer")} ${dev.optString("model")}"
                Session.referenceRole = reply.optString("role", Protocol.ROLE_REFERENCE)
                txtReference.text =
                    "대조설비: ${Session.referenceName} (Android ${dev.optString("android")}, " +
                        "화면 ${Session.referenceScreenWidth}x${Session.referenceScreenHeight})"
                updateActionButtons()
                AppLog.i("대조설비 연결 성공: $ip — 화면 ${Session.referenceScreenWidth}x${Session.referenceScreenHeight}")
            } catch (e: Exception) {
                txtReference.text = "대조설비: 연결 실패"
                updateActionButtons()
                AppLog.i("대조설비 연결 실패: ${e.message} (대상 앱 실행/같은 Wi-Fi/역할 설정 확인)")
            }
        }
    }

    private fun updateActionButtons() {
        btnMeasure.isEnabled = Session.client?.isConnected == true
        btnColorCalibrate.isEnabled =
            Session.client?.isConnected == true && Session.referenceClient?.isConnected == true
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            AppLog.i("카메라 권한이 거부되어 측정할 수 없습니다")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
