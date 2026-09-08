package com.burnin.scanner.measure

import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import org.json.JSONObject

/** Independent, idempotent cleanup commands with at most one reconnect per cleanup. */
internal object MeasurementCleanup {
    const val WARNING = "대상 보정 상태 확인 필요 — 대상 앱에서 보정과 오버레이를 직접 꺼 주세요"

    class Incomplete(causes: List<Exception>) : IllegalStateException(WARNING) {
        init { causes.forEach { addSuppressed(it) } }
    }

    fun disable(client: ControlClient?, timeoutMs: Int = 2000) {
        if (client == null) throw Incomplete(listOf(IllegalStateException("대상 연결 없음")))
        disable({ client.isConnected }, { client.connect(timeoutMs) }) { command ->
            client.request(JSONObject().put("cmd", command), timeoutMs)
        }
    }

    internal fun disable(isConnected: () -> Boolean, reconnect: () -> Unit, command: (String) -> Unit) {
        var reconnectAttempted = false
        val failures = ArrayList<Exception>()
        fun reconnectOnce() {
            check(!reconnectAttempted) { "보정 정리 재연결 한도 초과" }
            reconnectAttempted = true
            reconnect()
        }
        for (name in listOf(Protocol.CMD_DISABLE_CORRECTION, Protocol.CMD_DISABLE_OVERLAY)) {
            try {
                if (!isConnected()) reconnectOnce()
                try {
                    command(name)
                } catch (first: Exception) {
                    if (reconnectAttempted) throw first
                    try {
                        reconnectOnce()
                        command(name)
                    } catch (retry: Exception) {
                        retry.addSuppressed(first)
                        throw retry
                    }
                }
            } catch (failure: Exception) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) throw Incomplete(failures)
    }
}
