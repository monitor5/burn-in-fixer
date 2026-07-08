package com.burnin.target.net

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.burnin.target.DeviceRole
import com.burnin.target.correction.CorrectionStore
import com.burnin.target.correction.WhiteBalanceStore
import com.burnin.target.overlay.OverlayService
import com.burnin.target.pattern.PatternBus
import com.burnin.target.pattern.PatternSpec
import com.burnin.target.util.AppLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 측정 기기(Scanner)의 명령을 받는 TCP 서버 (12장 통신 요구사항).
 * newline 구분 JSON 프로토콜. 클라이언트는 한 번에 하나만 처리한다.
 */
object ControlServer {

    private val started = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile var clientAddress: String? = null
        private set

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        Thread({ serverLoop() }, "control-server").apply { isDaemon = true }.start()
    }

    private fun serverLoop() {
        try {
            val server = ServerSocket(Protocol.PORT)
            AppLog.i("서버 대기 중: 포트 ${Protocol.PORT}")
            while (true) {
                val socket = server.accept()
                clientAddress = socket.inetAddress.hostAddress
                AppLog.i("측정 기기 연결됨: $clientAddress")
                try {
                    handleClient(socket)
                } catch (e: Exception) {
                    AppLog.i("연결 종료: ${e.message}")
                } finally {
                    clientAddress = null
                    runCatching { socket.close() }
                    AppLog.i("측정 기기 연결 해제")
                }
            }
        } catch (e: Exception) {
            AppLog.i("서버 오류: ${e.message}")
            started.set(false)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 1 shl 16)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), 1 shl 16)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val reply = try {
                handleMessage(JSONObject(line))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "요청 처리 오류: ${e.message}")
            }
            writer.write(reply.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    private fun handleMessage(msg: JSONObject): JSONObject {
        val context = appContext ?: return err("HELLO", "컨텍스트 없음")
        val cmd = msg.optString("cmd")
        val reply = JSONObject().put("ok", true).put("cmd", cmd)
        val role = DeviceRole.get(context)

        when (cmd) {
            Protocol.CMD_HELLO, Protocol.CMD_SCREEN_INFO -> {
                val p: Point = CorrectionStore.realScreenSize(context)
                reply.put("role", role)
                reply.put("roleLabel", DeviceRole.label(role))
                reply.put("device", JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE))
                reply.put("screen", JSONObject()
                    .put("width", p.x)
                    .put("height", p.y)
                    .put("refreshRate", displayRefreshRate(context).toDouble()))
                val m = CorrectionStore.meta
                reply.put("correction", JSONObject()
                    .put("loaded", m != null)
                    .put("width", m?.width ?: 0)
                    .put("height", m?.height ?: 0))
                val wb = WhiteBalanceStore.meta
                reply.put("whiteBalance", JSONObject()
                    .put("loaded", wb != null)
                    .put("width", wb?.width ?: 0)
                    .put("height", wb?.height ?: 0)
                    .put("redGain", wb?.redGain ?: 1.0)
                    .put("greenGain", wb?.greenGain ?: 1.0)
                    .put("blueGain", wb?.blueGain ?: 1.0))
                if (cmd == Protocol.CMD_HELLO) {
                    AppLog.i("HELLO 수신 (${DeviceRole.label(role)}, 화면 ${p.x}x${p.y})")
                }
            }

            Protocol.CMD_SHOW_PATTERN -> {
                val name = msg.optString("pattern")
                val spec = PatternSpec.parse(name)
                    ?: return err(cmd, "알 수 없는 패턴: $name")
                if (role == Protocol.ROLE_REFERENCE) {
                    PatternBus.setCorrection(false, null)
                }
                val latch = CountDownLatch(1)
                PatternBus.showPattern(context, spec) { latch.countDown() }
                val applied = latch.await(7, TimeUnit.SECONDS)
                if (!applied) return err(cmd, "패턴 표시 시간 초과")
                AppLog.i("패턴 표시: $name")
                reply.put("pattern", name)
            }

            Protocol.CMD_APPLY_MAP -> {
                if (role == Protocol.ROLE_REFERENCE) {
                    return err(cmd, "대조설비는 보정맵을 적용하지 않습니다")
                }
                val error = CorrectionStore.applyFromBase64(
                    context = context,
                    width = msg.getInt("width"),
                    height = msg.getInt("height"),
                    maxAttenuation = msg.optDouble("maxAttenuation", 0.05),
                    defaultStrengthPct = msg.optInt("defaultStrength", 100),
                    checksumMd5 = msg.optString("checksumMd5", ""),
                    dataBase64 = msg.getString("data"),
                    sourceDevice = msg.optString("sourceDevice", "scanner"),
                    rgbChecksumMd5 = msg.optString("rgbChecksumMd5", ""),
                    rgbDataBase64 = if (msg.has("rgbData")) msg.getString("rgbData") else null,
                )
                if (error != null) {
                    AppLog.i("보정맵 거부: $error")
                    return err(cmd, error)
                }
            }

            Protocol.CMD_APPLY_WHITE_BALANCE -> {
                if (role == Protocol.ROLE_REFERENCE) {
                    return err(cmd, "대조설비는 화이트밸런스를 적용하지 않습니다")
                }
                val error = WhiteBalanceStore.applyFromBase64(
                    context = context,
                    width = msg.getInt("width"),
                    height = msg.getInt("height"),
                    maxAttenuation = msg.optDouble("maxAttenuation", 0.10),
                    checksumMd5 = msg.optString("checksumMd5", ""),
                    dataBase64 = msg.getString("data"),
                    sourceDevice = msg.optString("sourceDevice", "scanner"),
                    redGain = msg.optDouble("redGain", 1.0),
                    greenGain = msg.optDouble("greenGain", 1.0),
                    blueGain = msg.optDouble("blueGain", 1.0),
                )
                if (error != null) {
                    AppLog.i("화이트밸런스 거부: $error")
                    return err(cmd, error)
                }
                PatternBus.setCorrection(true, msg.optInt("strength", 100))
                AppLog.i("화이트밸런스 적용")
            }

            Protocol.CMD_CLEAR_WHITE_BALANCE -> {
                if (role == Protocol.ROLE_REFERENCE) {
                    return err(cmd, "대조설비는 화이트밸런스를 사용하지 않습니다")
                }
                WhiteBalanceStore.clear(context)
                PatternBus.setCorrection(PatternBus.correctionEnabled, null)
            }

            Protocol.CMD_ENABLE_CORRECTION -> {
                if (role == Protocol.ROLE_REFERENCE) {
                    return err(cmd, "대조설비는 보정을 켤 수 없습니다")
                }
                if (
                    CorrectionStore.bakedBitmap == null &&
                    WhiteBalanceStore.rgbAttenuationBitmap == null
                ) {
                    return err(cmd, "적재된 보정맵/화이트밸런스 없음")
                }
                PatternBus.setCorrection(true, msg.optInt("strength", 100))
                AppLog.i("앱 내부 보정 ON (강도 ${PatternBus.strengthPct}%)")
            }

            Protocol.CMD_DISABLE_CORRECTION -> {
                PatternBus.setCorrection(false, null)
                AppLog.i("앱 내부 보정 OFF")
            }

            Protocol.CMD_ENABLE_OVERLAY -> {
                if (role == Protocol.ROLE_REFERENCE) {
                    return err(cmd, "대조설비는 오버레이를 켤 수 없습니다")
                }
                val error = OverlayService.requestStart(context, msg.optInt("strength", 100))
                if (error != null) return err(cmd, error)
            }

            Protocol.CMD_DISABLE_OVERLAY -> OverlayService.requestStop(context)

            Protocol.CMD_END_SESSION -> {
                PatternBus.endSession()
                AppLog.i("세션 종료")
            }

            else -> return err(cmd, "알 수 없는 명령")
        }
        return reply
    }

    /** 측정 기기가 노출을 주사 주기의 정수배로 맞출 수 있도록 실제 주사율을 알려준다. */
    private fun displayRefreshRate(context: Context): Float {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 60f
        @Suppress("DEPRECATION")
        val rate = wm.defaultDisplay?.refreshRate ?: 60f
        return if (rate > 1f) rate else 60f
    }

    private fun err(cmd: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("cmd", cmd).put("error", message)
}
