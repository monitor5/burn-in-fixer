package com.burnin.target.pattern

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.burnin.target.util.AppLog

/**
 * 통신 스레드(ControlServer)와 PatternActivity(UI) 사이의 브리지.
 * 서버가 패턴 표시를 요청하면, 액티비티가 떠 있으면 즉시 적용하고
 * 없으면 액티비티를 띄운 뒤 등록될 때까지 대기했다가 적용한다.
 * 적용 완료(프레임 커밋 후) 시 done 콜백이 호출되어 서버가 ACK를 보낸다.
 */
object PatternBus {
    interface Host {
        fun applyPattern(spec: PatternSpec, done: Runnable)
        fun applyCorrectionState()
        fun closeSelf()
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile var currentSpec: PatternSpec = PatternSpec.BLACK
        private set
    @Volatile var correctionEnabled: Boolean = false
    @Volatile var strengthPct: Int = 100

    @Volatile private var host: Host? = null

    fun register(h: Host) {
        host = h
    }

    fun unregister(h: Host) {
        if (host === h) host = null
    }

    /** 패턴 표시 요청. done은 프레임이 실제로 그려진 뒤 메인 스레드에서 1회 호출된다. */
    fun showPattern(context: Context, spec: PatternSpec, done: Runnable) {
        main.post {
            currentSpec = spec
            val h = host
            if (h != null) {
                h.applyPattern(spec, done)
            } else {
                AppLog.i("패턴 화면 실행: ${spec.name}")
                context.startActivity(
                    Intent(context, PatternActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                waitForHost(spec, done, SystemClock.elapsedRealtime() + 6000)
            }
        }
    }

    private fun waitForHost(spec: PatternSpec, done: Runnable, deadline: Long) {
        val h = host
        when {
            h != null -> h.applyPattern(spec, done)
            SystemClock.elapsedRealtime() > deadline -> {
                AppLog.i("경고: 패턴 화면 실행 대기 시간 초과")
                // No frame was committed. Let ControlServer report its bounded timeout.
            }
            else -> main.postDelayed({ waitForHost(spec, done, deadline) }, 50)
        }
    }

    /** 보정 켜기/끄기. 패턴 화면이 떠 있으면 즉시 반영된다. */
    fun setCorrection(enabled: Boolean, strength: Int?) {
        main.post {
            correctionEnabled = enabled
            if (strength != null) strengthPct = strength.coerceIn(0, 100)
            host?.applyCorrectionState()
        }
    }

    fun endSession() {
        main.post { host?.closeSelf() }
    }
}
