package com.burnin.scanner.report

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 세션 결과 저장 (M-FR-014, 18.2 저장 구조).
 * 위치: Android/data/com.burnin.scanner/files/sessions/<타임스탬프>/
 */
object ReportStore {

    fun newSessionDir(context: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "sessions/${ts}_${UUID.randomUUID()}").apply { check(mkdirs()) { "세션 디렉터리 생성 실패" } }
    }

    fun save(dir: File, report: JSONObject, files: Map<String, ByteArray>) {
        require(dir.isDirectory) { "session directory missing" }
        require(!File(dir, "report.json").exists()) { "session already committed" }
        require(files.keys.all { File(it).name == it && it !in setOf(".", "..", "report.json", ".pending") }) { "invalid report file name" }
        val staging = File(dir, ".pending")
        check(staging.mkdir()) { "session save already in progress" }
        val published = ArrayList<File>()
        try {
            files.forEach { (name, bytes) -> File(staging, name).writeBytes(bytes) }
            File(staging, "report.json").writeText(report.toString(2))
            // report.json is the commit marker and is always published last.
            for (name in files.keys + "report.json") {
                val destination = File(dir, name)
                check(!destination.exists() && File(staging, name).renameTo(destination)) { "session publish failed: $name" }
                published += destination
            }
        } catch (e: Exception) {
            published.forEach { it.delete() }
            throw e
        } finally { staging.deleteRecursively() }
    }
}
