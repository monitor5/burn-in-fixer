package com.burnin.target.util

import android.content.Context
import java.io.File

/**
 * 비정상 종료 기록. adb 없이(Tab S2 무선 측정) 원인을 보려고, 잡히지 않은 예외의 스택을
 * 파일에 남기고 다음 실행 때 화면 로그로 보여 준다.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        val app = context.applicationContext
        if (installed) return
        installed = true
        report(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val rt = Runtime.getRuntime()
                val heap = "heap used ${(rt.totalMemory() - rt.freeMemory()) shr 20}MB / max ${rt.maxMemory() shr 20}MB"
                File(app.filesDir, FILE).writeText(
                    "${java.util.Date()} thread=${thread.name} $heap\n${e.stackTraceToString()}"
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun report(context: Context) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        AppLog.i("!! 이전 비정상 종료 기록:")
        lines.take(12).forEach { AppLog.i("   $it") }
        runCatching { f.renameTo(File(context.filesDir, "$FILE.prev")) }
    }
}
