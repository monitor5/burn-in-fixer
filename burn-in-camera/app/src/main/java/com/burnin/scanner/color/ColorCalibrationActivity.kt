package com.burnin.scanner.color

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.burnin.scanner.R
import com.burnin.scanner.analysis.Analyzer
import com.burnin.scanner.analysis.ImageOps
import com.burnin.scanner.analysis.RgbImage
import com.burnin.scanner.analysis.ScreenDetector
import com.burnin.scanner.analysis.Vec2
import com.burnin.scanner.camera.CaptureController
import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import com.burnin.scanner.net.Session
import com.burnin.scanner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 대조설비의 원본 흰색과 조정설비의 기존 번인 보정 ON 상태 흰색을 비교해
 * 조정설비에 추가 RGB 화이트밸런스 레이어를 전송한다.
 */
class ColorCalibrationActivity : Activity() {

    companion object {
        private const val WHITE_PATTERN = "white70"
        private const val SETTLE_MS = 2_500L
        private const val SWITCH_SETTLE_MS = 700L
        private const val MAX_WB_ATTENUATION = 0.20f
        private const val CENTER_SAMPLE_START = 0.35f
        private const val CENTER_SAMPLE_END = 0.65f
        private const val CENTER_SAMPLE_STEPS = 40
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var capture: CaptureController? = null
    private lateinit var txtStatus: TextView
    private lateinit var txtResult: TextView
    private lateinit var btnStart: Button
    private lateinit var btnToggle: Button
    private val resultLog = StringBuilder()

    private data class RgbMean(val r: Double, val g: Double, val b: Double) {
        fun chroma(): RgbMean {
            val sum = (r + g + b).coerceAtLeast(1e-9)
            return RgbMean(r / sum, g / sum, b / sum)
        }
    }

    private data class ChannelGains(val r: Double, val g: Double, val b: Double, val clipped: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        txtStatus = findViewById(R.id.txtStatus)
        txtResult = findViewById(R.id.txtResult)
        btnStart = findViewById(R.id.btnStart)
        btnToggle = findViewById(R.id.btnToggle)
        btnStart.text = "화이트밸런스 시작"
        btnToggle.isEnabled = false

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            scope.launch { runCalibrationSafely() }
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("카메라 권한 없음 — 홈 화면에서 권한을 허용하세요")
            btnStart.isEnabled = false
            return
        }

        scope.launch {
            try {
                val c = CaptureController(this@ColorCalibrationActivity, findViewById<TextureView>(R.id.preview))
                c.start()
                capture = c
                status("카메라 준비 완료 — 두 설비가 보이면 [화이트밸런스 시작]")
                log("카메라: ${c.captureSize.width}x${c.captureSize.height} ${c.captureFormatText}, ${c.hardwareLevelText()}")
            } catch (e: Exception) {
                status("카메라 초기화 실패: ${e.message}")
                btnStart.isEnabled = false
            }
        }
    }

    private suspend fun runCalibrationSafely() {
        try {
            runCalibration()
        } catch (e: Exception) {
            status("화이트밸런스 실패: ${e.message}")
            log("실패: ${e.stackTraceToString().lineSequence().firstOrNull() ?: e.message}")
        } finally {
            btnStart.isEnabled = true
        }
    }

    private suspend fun runCalibration() {
        val adjust = Session.client ?: error("조정설비가 연결되지 않았습니다")
        val reference = Session.referenceClient ?: error("대조설비가 연결되지 않았습니다")
        val cap = capture ?: error("카메라가 준비되지 않았습니다")
        val screenW = Session.screenWidth
        val screenH = Session.screenHeight
        if (screenW <= 0 || screenH <= 0) error("조정설비 해상도를 알 수 없습니다")

        status("기존 화이트밸런스 제거, 조정설비 번인 보정 ON...")
        withContext(Dispatchers.IO) {
            runCatching { adjust.command(Protocol.CMD_CLEAR_WHITE_BALANCE) }
                .onFailure { AppLog.i("화이트밸런스 초기화 생략: ${it.message}") }
            runCatching { adjust.command(Protocol.CMD_ENABLE_CORRECTION, "strength" to 100) }
                .onFailure { AppLog.i("기존 번인 보정 ON 실패/없음: ${it.message}") }
            reference.showPattern(WHITE_PATTERN)
            adjust.showPattern(WHITE_PATTERN)
        }
        delay(SETTLE_MS)
        val lockText = cap.lockMeasurementControls(Session.screenRefreshRate)
        log("카메라 고정: $lockText")

        status("대조설비 원본 흰색 측정...")
        val refMean = captureSingleLitMean(
            whiteClient = reference,
            darkClient = adjust,
            whiteName = "대조설비",
            keepAdjustmentCorrectionOn = false,
        )
        log("대조설비 중앙 RGB: ${fmt(refMean.r)}, ${fmt(refMean.g)}, ${fmt(refMean.b)}")

        status("조정설비 보정 ON 흰색 측정...")
        val adjustMean = captureSingleLitMean(
            whiteClient = adjust,
            darkClient = reference,
            whiteName = "조정설비",
            keepAdjustmentCorrectionOn = true,
        )
        log("조정설비 중앙 RGB(기존 보정 ON): ${fmt(adjustMean.r)}, ${fmt(adjustMean.g)}, ${fmt(adjustMean.b)}")

        val gains = computeGains(refMean, adjustMean)
        if (gains.clipped) {
            log("필요 감쇠가 ${pct(MAX_WB_ATTENUATION)} 한도를 넘은 채널이 있어 한도 내로 제한했습니다.")
        }
        log("화이트밸런스 gain: R ${fmt(gains.r)}, G ${fmt(gains.g)}, B ${fmt(gains.b)}")

        status("화이트밸런스 레이어 전송...")
        val png = withContext(Dispatchers.Default) {
            val r = FloatArray(4) { gains.r.toFloat() }
            val g = FloatArray(4) { gains.g.toFloat() }
            val b = FloatArray(4) { gains.b.toFloat() }
            Analyzer.toRgbAttenuationPng(r, g, b, 2, 2, screenW, screenH, MAX_WB_ATTENUATION)
        }
        withContext(Dispatchers.IO) {
            adjust.request(
                JSONObject()
                    .put("cmd", Protocol.CMD_APPLY_WHITE_BALANCE)
                    .put("width", screenW)
                    .put("height", screenH)
                    .put("maxAttenuation", MAX_WB_ATTENUATION.toDouble())
                    .put("checksumMd5", md5(png))
                    .put("sourceDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("redGain", gains.r)
                    .put("greenGain", gains.g)
                    .put("blueGain", gains.b)
                    .put("data", Base64.encodeToString(png, Base64.NO_WRAP)),
                timeoutMs = 120_000,
            )
            adjust.command(Protocol.CMD_ENABLE_CORRECTION, "strength" to 100)
        }

        status("적용 후 색 차이 확인...")
        val adjustedAfter = captureSingleLitMean(
            whiteClient = adjust,
            darkClient = reference,
            whiteName = "조정설비 적용 후",
            keepAdjustmentCorrectionOn = true,
        )
        val error = chromaError(refMean, adjustedAfter)
        log("적용 후 중앙 RGB: ${fmt(adjustedAfter.r)}, ${fmt(adjustedAfter.g)}, ${fmt(adjustedAfter.b)}")
        log("적용 후 색좌표 오차: ${fmt(error)}")

        withContext(Dispatchers.IO) {
            reference.showPattern(WHITE_PATTERN)
            adjust.showPattern(WHITE_PATTERN)
        }
        status("화이트밸런스 완료 — 기존 번인 보정 위에 RGB 레이어 적용됨")
    }

    private suspend fun captureSingleLitMean(
        whiteClient: ControlClient,
        darkClient: ControlClient,
        whiteName: String,
        keepAdjustmentCorrectionOn: Boolean,
    ): RgbMean {
        withContext(Dispatchers.IO) {
            darkClient.showPattern("black")
            if (keepAdjustmentCorrectionOn) {
                runCatching { whiteClient.command(Protocol.CMD_ENABLE_CORRECTION, "strength" to 100) }
            }
            whiteClient.showPattern(WHITE_PATTERN)
        }
        delay(SWITCH_SETTLE_MS)

        val frame = capture!!.captureSingleFrame()
        val rgb = withContext(Dispatchers.Default) {
            ImageOps.decodeLinearRgb(frame, maxLongEdge = Int.MAX_VALUE)
        }
        val quad = detectBrightQuad(rgb)
            ?: error("$whiteName 화면을 찾지 못했습니다")
        log("$whiteName 검출: ${quad}")
        return meanCenterRgb(rgb, quad)
    }

    private fun computeGains(reference: RgbMean, adjustment: RgbMean): ChannelGains {
        val gains = WhiteBalanceMath.compute(doubleArrayOf(reference.r, reference.g, reference.b),
            doubleArrayOf(adjustment.r, adjustment.g, adjustment.b), MAX_WB_ATTENUATION.toDouble())
        return ChannelGains(gains.r, gains.g, gains.b, gains.clipped)
    }

    private fun detectBrightQuad(img: RgbImage): ScreenDetector.Quad? {
        val bins = IntArray(1024)
        var maxV = 1e-6f
        for (i in img.r.indices) {
            val y = luma(img, i)
            if (y > maxV) maxV = y
        }
        val scale = 1023f / maxV
        for (i in img.r.indices) {
            bins[(luma(img, i) * scale).toInt().coerceIn(0, 1023)]++
        }
        val total = img.r.size
        var cum = 0
        var p99 = maxV
        for (b in 0 until 1024) {
            cum += bins[b]
            if (cum >= total * 0.99f) {
                p99 = b / scale
                break
            }
        }
        if (p99 < 0.005f) return null
        val threshold = p99 * 0.30f

        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE
        val tl = Vec2()
        val tr = Vec2()
        val br = Vec2()
        val bl = Vec2()
        var count = 0
        var i = 0
        for (y in 0 until img.h) {
            for (x in 0 until img.w) {
                if (luma(img, i) > threshold) {
                    count++
                    val s = (x + y).toFloat()
                    val d = (x - y).toFloat()
                    if (s < minSum) { minSum = s; tl.set(x.toFloat(), y.toFloat()) }
                    if (s > maxSum) { maxSum = s; br.set(x.toFloat(), y.toFloat()) }
                    if (d > maxDiff) { maxDiff = d; tr.set(x.toFloat(), y.toFloat()) }
                    if (d < minDiff) { minDiff = d; bl.set(x.toFloat(), y.toFloat()) }
                }
                i++
            }
        }
        if (count < total * 0.03f) return null
        val quad = ScreenDetector.Quad(arrayOf(tl, tr, br, bl))
        val areaRatio = quad.area() / total.toFloat()
        if (areaRatio < 0.03f || areaRatio > 0.95f) return null
        return quad
    }

    private fun meanCenterRgb(img: RgbImage, quad: ScreenDetector.Quad): RgbMean {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var count = 0
        val start = CENTER_SAMPLE_START
        val span = CENTER_SAMPLE_END - CENTER_SAMPLE_START
        for (yi in 0 until CENTER_SAMPLE_STEPS) {
            val v = start + span * (yi + 0.5f) / CENTER_SAMPLE_STEPS
            for (xi in 0 until CENTER_SAMPLE_STEPS) {
                val u = start + span * (xi + 0.5f) / CENTER_SAMPLE_STEPS
                val p = quadPoint(quad, u, v)
                r += img.bilinearChannel(p.x, p.y, 0).toDouble()
                g += img.bilinearChannel(p.x, p.y, 1).toDouble()
                b += img.bilinearChannel(p.x, p.y, 2).toDouble()
                count++
            }
        }
        return RgbMean(r / count, g / count, b / count)
    }

    private fun quadPoint(quad: ScreenDetector.Quad, u: Float, v: Float): Vec2 {
        val topX = quad.tl.x * (1f - u) + quad.tr.x * u
        val topY = quad.tl.y * (1f - u) + quad.tr.y * u
        val bottomX = quad.bl.x * (1f - u) + quad.br.x * u
        val bottomY = quad.bl.y * (1f - u) + quad.br.y * u
        return Vec2(
            topX * (1f - v) + bottomX * v,
            topY * (1f - v) + bottomY * v,
        )
    }

    private fun luma(img: RgbImage, i: Int): Float =
        0.2126f * img.r[i] + 0.7152f * img.g[i] + 0.0722f * img.b[i]

    private fun chromaError(a: RgbMean, b: RgbMean): Double {
        val ca = a.chroma()
        val cb = b.chroma()
        val dr = ca.r - cb.r
        val dg = ca.g - cb.g
        val db = ca.b - cb.b
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun status(msg: String) {
        txtStatus.text = msg
        AppLog.i(msg)
    }

    private fun log(msg: String) {
        resultLog.appendLine(msg)
        txtResult.text = resultLog.toString()
        AppLog.i(msg)
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.5f", v)

    private fun pct(v: Float): String = String.format(java.util.Locale.US, "%.1f%%", v * 100f)

    override fun onDestroy() {
        capture?.close()
        scope.cancel()
        super.onDestroy()
    }
}
