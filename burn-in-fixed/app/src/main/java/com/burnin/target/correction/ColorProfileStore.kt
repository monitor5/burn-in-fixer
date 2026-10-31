package com.burnin.target.correction

import android.content.Context
import android.util.Base64
import com.burnin.target.util.AppLog
import org.json.JSONObject
import java.io.File

/**
 * 기준 기기 색감 일치 프로파일(3D LUT). 번인 보정맵과 독립적으로 저장하고 켜고 끈다.
 * 적용 순서: 원래 색 → 색 프로파일 LUT → 번인 RGB/알파 감쇠 → (구) 화이트밸런스 레이어.
 * 앱 자체 렌더링(패턴 화면)에만 적용된다. 시스템 오버레이는 검정 알파 감쇠만 가능해 임의 색 변환을 할 수 없다.
 */
object ColorProfileStore {

    data class Meta(
        val lutSize: Int,
        val format: String,
        val checksumMd5: String,
        val createdAt: String,
        val sourceDevice: String,
        val referenceDevice: String,
        val scale: Double,
        val enabled: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("profileVersion", 1)
            .put("lutSize", lutSize)
            .put("format", format)
            .put("checksumMd5", checksumMd5)
            .put("createdAt", createdAt)
            .put("sourceDevice", sourceDevice)
            .put("referenceDevice", referenceDevice)
            .put("scale", scale)
            .put("enabled", enabled)

        companion object {
            fun fromJson(o: JSONObject) = Meta(
                lutSize = o.getInt("lutSize"),
                format = o.optString("format", ColorLut.FORMAT),
                checksumMd5 = o.optString("checksumMd5", ""),
                createdAt = o.optString("createdAt", ""),
                sourceDevice = o.optString("sourceDevice", ""),
                referenceDevice = o.optString("referenceDevice", ""),
                scale = o.optDouble("scale", 1.0),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }

    @Volatile var meta: Meta? = null
        private set
    @Volatile var lut: ColorLut? = null
        private set

    /** 프로파일이 있고 켜져 있을 때만 렌더링에 쓰는 LUT. */
    val activeLut: ColorLut? get() = if (meta?.enabled == true) lut else null

    private fun dir(context: Context): File =
        ProfileFiles.recover(File(context.filesDir, "profiles/color_profile"))

    fun applyFromBase64(
        context: Context,
        lutSize: Int,
        format: String,
        checksumMd5: String,
        dataBase64: String,
        sourceDevice: String,
        referenceDevice: String,
        scale: Double,
    ): String? = applyValidated(context, lutSize, format, checksumMd5, dataBase64, sourceDevice,
        referenceDevice, scale, enabled = true, persist = true, restoredCreatedAt = null)

    @Synchronized
    private fun applyValidated(
        context: Context,
        lutSize: Int,
        format: String,
        checksumMd5: String,
        dataBase64: String,
        sourceDevice: String,
        referenceDevice: String,
        scale: Double,
        enabled: Boolean,
        persist: Boolean,
        restoredCreatedAt: String?,
    ): String? {
        if (format != ColorLut.FORMAT) return "색 프로파일 형식 미지원: $format"
        if (!scale.isFinite() || scale !in 0.0..1.0) return "색 프로파일 scale 범위 오류: $scale"
        val bytes = try {
            Base64.decode(dataBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return "색 프로파일 base64 디코드 실패"
        }
        if (checksumMd5.isNotEmpty() && !CorrectionStore.md5(bytes).equals(checksumMd5, ignoreCase = true)) {
            return "색 프로파일 체크섬 불일치"
        }
        val decoded = try {
            ColorLut.decode(lutSize, bytes)
        } catch (e: IllegalArgumentException) {
            return "색 프로파일 거부: ${e.message}"
        }
        val m = Meta(
            lutSize = lutSize,
            format = format,
            checksumMd5 = CorrectionStore.md5(bytes),
            createdAt = restoredCreatedAt ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                .format(java.util.Date()),
            sourceDevice = sourceDevice,
            referenceDevice = referenceDevice,
            scale = scale,
            enabled = enabled,
        )
        try {
            if (persist) write(context, m, bytes)
        } catch (e: Exception) {
            return "색 프로파일 저장 실패: ${e.message}"
        }
        lut = decoded
        meta = m
        AppLog.i("색 프로파일 준비 완료: LUT ${lutSize}³, 기준 ${referenceDevice.ifEmpty { "?" }}, scale ${String.format(java.util.Locale.US, "%.3f", scale)}")
        return null
    }

    private fun write(context: Context, m: Meta, bytes: ByteArray) {
        ProfileFiles.replace(dir(context), mapOf(
            "color_lut.bin" to bytes,
            "metadata.json" to m.toJson().toString(2).toByteArray(Charsets.UTF_8),
        ))
    }

    /** 색 프로파일만 켜고 끈다 (번인 보정과 독립). 상태는 디스크에도 남긴다. */
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean): String? {
        val m = meta ?: return "적재된 색 프로파일 없음"
        if (m.enabled == enabled) return null
        val next = m.copy(enabled = enabled)
        try {
            val bytes = File(dir(context), "color_lut.bin").readBytes()
            write(context, next, bytes)
        } catch (e: Exception) {
            return "색 프로파일 상태 저장 실패: ${e.message}"
        }
        meta = next
        AppLog.i("색 프로파일 ${if (enabled) "ON" else "OFF"}")
        return null
    }

    @Synchronized
    fun loadFromDisk(context: Context): Boolean {
        return try {
            val d = dir(context)
            val m = Meta.fromJson(JSONObject(File(d, "metadata.json").readText()))
            val bytes = File(d, "color_lut.bin").readBytes()
            applyValidated(context, m.lutSize, m.format, m.checksumMd5,
                Base64.encodeToString(bytes, Base64.NO_WRAP), m.sourceDevice, m.referenceDevice, m.scale,
                enabled = m.enabled, persist = false, restoredCreatedAt = m.createdAt) == null
        } catch (e: Exception) {
            AppLog.i("색 프로파일 적재 실패: ${e.message}")
            false
        }
    }

    @Synchronized
    fun clear(context: Context) {
        ProfileFiles.replace(dir(context), emptyMap())
        lut = null
        meta = null
        AppLog.i("색 프로파일 삭제")
    }
}
