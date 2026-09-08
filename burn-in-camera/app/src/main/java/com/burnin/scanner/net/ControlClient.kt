package com.burnin.scanner.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 대상 기기 앱의 TCP 서버로 명령을 보내는 클라이언트.
 * 모든 호출은 블로킹이므로 Dispatchers.IO 에서 사용해야 한다.
 */
class ControlClient(private val host: String, private val port: Int = Protocol.PORT) {

    private val requestLock = Any()
    @Volatile private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    fun connect(timeoutMs: Int = 4000) = synchronized(requestLock) {
        require(timeoutMs > 0)
        close()
        val s = Socket()
        socket = s // close() can interrupt a connect in progress, too.
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 20_000
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 1 shl 16)
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), 1 shl 16)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    /** A failed exchange invalidates the stream so a late ACK cannot satisfy the next request. */
    fun request(msg: JSONObject, timeoutMs: Int = 20_000): JSONObject = synchronized(requestLock) {
        require(timeoutMs > 0)
        val cmd = msg.getString("cmd")
        val s = socket ?: throw IllegalStateException("연결되지 않음")
        try {
            s.soTimeout = timeoutMs
            val w = writer ?: throw IllegalStateException("연결되지 않음")
            w.write(msg.toString())
            w.write("\n")
            w.flush()
            val line = reader!!.readLine() ?: throw IllegalStateException("연결이 끊어짐")
            val reply = JSONObject(line)
            check(reply.optString("cmd") == cmd) { "응답 명령 불일치" }
            check(reply.optBoolean("ok", false)) { reply.optString("error", "알 수 없는 오류") }
            reply
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun command(cmd: String, vararg pairs: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("cmd", cmd)
        pairs.forEach { (k, v) -> o.put(k, v) }
        return request(o)
    }

    fun showPattern(name: String): JSONObject =
        request(JSONObject().put("cmd", Protocol.CMD_SHOW_PATTERN).put("pattern", name))

    fun close() {
        // Do not take requestLock here: closing must unblock an in-flight readLine().
        // Keep reader/writer references until the next serialized connect replaces them.
        runCatching { socket?.close() }
    }

}

/** 화면 간 공유 연결 상태 (MainActivity → MeasurementActivity). */
object Session {
    var client: ControlClient? = null
    var referenceClient: ControlClient? = null
    var screenWidth: Int = 0
    var screenHeight: Int = 0
    var screenRefreshRate: Float = 60f
    var targetName: String = ""
    var targetRole: String = Protocol.ROLE_ADJUSTMENT
    var referenceScreenWidth: Int = 0
    var referenceScreenHeight: Int = 0
    var referenceName: String = ""
    var referenceRole: String = Protocol.ROLE_REFERENCE
}
