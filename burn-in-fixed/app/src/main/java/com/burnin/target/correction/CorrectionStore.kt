package com.burnin.target.correction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.util.Base64
import android.view.WindowManager
import com.burnin.target.util.AppLog
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 보정 프로파일 저장소 + 검증기 (T-FR-006/007, 13장 데이터 포맷).
 *
 * 보정맵 PNG: 그레이스케일. 픽셀값 v(0~255)의 의미는 v/255 * maxAttenuation 만큼
 * 해당 위치의 밝기를 낮춘다는 뜻 (0 = 보정 없음, 255 = 최대 감쇠).
 * 적용을 위해 "검정 RGB + 알파 = v/255 * maxAttenuation" 인 ARGB 비트맵으로 굽는다(bake).
 */
object CorrectionStore {

    data class Meta(
        val width: Int,
        val height: Int,
        val maxAttenuation: Double,
        val defaultStrengthPct: Int,
        val checksumMd5: String,
        val rgbChecksumMd5: String,
        val createdAt: String,
        val sourceDevice: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("profileVersion", 2)
            .put("width", width)
            .put("height", height)
            .put("maxAttenuation", maxAttenuation)
            .put("defaultStrengthPct", defaultStrengthPct)
            .put("checksumMd5", checksumMd5)
            .put("rgbChecksumMd5", rgbChecksumMd5)
            .put("hasRgbMap", rgbChecksumMd5.isNotEmpty())
            .put("createdAt", createdAt)
            .put("sourceDevice", sourceDevice)

        companion object {
            fun fromJson(o: JSONObject) = Meta(
                width = o.getInt("width"),
                height = o.getInt("height"),
                maxAttenuation = o.getDouble("maxAttenuation"),
                defaultStrengthPct = o.optInt("defaultStrengthPct", 100),
                checksumMd5 = o.optString("checksumMd5", ""),
                rgbChecksumMd5 = o.optString("rgbChecksumMd5", ""),
                createdAt = o.optString("createdAt", ""),
                sourceDevice = o.optString("sourceDevice", ""),
            )
        }
    }

    @Volatile var meta: Meta? = null
        private set
    @Volatile var bakedBitmap: Bitmap? = null
        private set
    @Volatile var rgbAttenuationBitmap: Bitmap? = null
        private set

    private fun dir(context: Context): File =
        ProfileFiles.recover(File(context.filesDir, "profiles/current"))

    fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    fun realScreenSize(context: Context): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }

    /**
     * base64 PNG 보정맵을 검증 후 저장·적재한다.
     * 반환값: null = 성공, 문자열 = 거부 사유 (검증 실패 시 적용하지 않는다. T-FR-007)
     */
    fun applyFromBase64(
        context: Context,
        width: Int,
        height: Int,
        maxAttenuation: Double,
        defaultStrengthPct: Int,
        checksumMd5: String,
        dataBase64: String,
        sourceDevice: String,
        rgbChecksumMd5: String = "",
        rgbDataBase64: String? = null,
    ): String? = applyValidated(context, width, height, maxAttenuation, defaultStrengthPct,
        checksumMd5, dataBase64, sourceDevice, rgbChecksumMd5, rgbDataBase64)


    @Synchronized
    private fun applyValidated(
        context: Context,
        width: Int,
        height: Int,
        maxAttenuation: Double,
        defaultStrengthPct: Int,
        checksumMd5: String,
        dataBase64: String,
        sourceDevice: String,
        rgbChecksumMd5: String = "",
        rgbDataBase64: String? = null,
        persist: Boolean = true,
        restoredCreatedAt: String? = null,
    ): String? {
        if (width <= 0 || height <= 0 || defaultStrengthPct !in 0..100) return "프로파일 메타데이터 범위 오류"
        if (rgbDataBase64.isNullOrBlank() && rgbChecksumMd5.isNotEmpty()) return "RGB 데이터 누락"
        val png: ByteArray = try {
            Base64.decode(dataBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return "base64 디코드 실패"
        }

        if (checksumMd5.isNotEmpty() && !md5(png).equals(checksumMd5, ignoreCase = true)) {
            return "체크섬 불일치 (파일 손상)"
        }
        val rgbPng: ByteArray? = if (!rgbDataBase64.isNullOrBlank()) {
            try {
                Base64.decode(rgbDataBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                return "RGB base64 디코드 실패"
            }
        } else {
            null
        }
        if (rgbPng != null && rgbChecksumMd5.isNotEmpty() &&
            !md5(rgbPng).equals(rgbChecksumMd5, ignoreCase = true)
        ) {
            return "RGB 체크섬 불일치 (파일 손상)"
        }
        if (maxAttenuation !in 0.0..0.30) {
            return "maxAttenuation 범위 오류: $maxAttenuation (허용 0~0.30)"
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, opts)
        if (opts.outWidth != width || opts.outHeight != height) {
            return "PNG 크기(${opts.outWidth}x${opts.outHeight})가 메타데이터(${width}x${height})와 다름"
        }

        val screen = realScreenSize(context)
        if (!(screen.x == width && screen.y == height)) {
            return if (screen.x == height && screen.y == width) {
                "화면 방향 불일치: 화면 ${screen.x}x${screen.y}, 보정맵 ${width}x${height} (기기 방향을 측정 시와 동일하게 하세요)"
            } else {
                "해상도 불일치: 화면 ${screen.x}x${screen.y}, 보정맵 ${width}x${height}"
            }
        }
        if (rgbPng != null) {
            val rgbOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rgbPng, 0, rgbPng.size, rgbOpts)
            if (rgbOpts.outWidth != width || rgbOpts.outHeight != height) {
                return "RGB PNG 크기(${rgbOpts.outWidth}x${rgbOpts.outHeight})가 메타데이터(${width}x${height})와 다름"
            }
        }

        val src = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: return "PNG 디코드 실패"

        val baked = bake(src, maxAttenuation)
        src.recycle()
        val rgbBitmap = rgbPng?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)?.copy(Bitmap.Config.ARGB_8888, false)
                ?: return "RGB PNG 디코드 실패"
        }

        val m = Meta(
            width, height, maxAttenuation, defaultStrengthPct, md5(png), rgbPng?.let { md5(it) }.orEmpty(),
            createdAt = restoredCreatedAt ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                .format(java.util.Date()),
            sourceDevice = sourceDevice,
        )
        try {
            val files = linkedMapOf("correction_alpha.png" to png, "metadata.json" to m.toJson().toString(2).toByteArray(Charsets.UTF_8))
            if (rgbPng != null) files["correction_rgb.png"] = rgbPng
            if (persist) ProfileFiles.replace(dir(context), files)
        } catch (e: Exception) {
            baked.recycle()
            rgbBitmap?.recycle()
            return "프로파일 저장 실패: ${e.message}"
        }
        // Views may still be drawing the previous bitmap; let its last owner release it.
        meta = m
        bakedBitmap = baked
        rgbAttenuationBitmap = rgbBitmap
        AppLog.i(
            "보정맵 적용 준비 완료: ${width}x${height}, maxAtt=$maxAttenuation, " +
                "RGB ${if (rgbBitmap != null) "있음" else "없음"}"
        )
        return null
    }

    /** 그레이 PNG(감쇠 비율) → 검정+알파 ARGB 비트맵 */
    private fun bake(src: Bitmap, maxAttenuation: Double): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val lut = IntArray(256) { v -> Math.round(v * maxAttenuation).toInt().coerceIn(0, 255) shl 24 }
        for (i in pixels.indices) {
            pixels[i] = lut[Color.red(pixels[i])]
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 앱 재시작 시 저장된 프로파일을 다시 적재한다. */
    @Synchronized
    fun loadFromDisk(context: Context): Boolean {
        return try {
            val d = dir(context)
            val m = Meta.fromJson(JSONObject(File(d, "metadata.json").readText()))
            val png = File(d, "correction_alpha.png").readBytes()
            val rgb = File(d, "correction_rgb.png").takeIf { it.exists() }?.readBytes()
            applyValidated(context, m.width, m.height, m.maxAttenuation, m.defaultStrengthPct,
                m.checksumMd5, Base64.encodeToString(png, Base64.NO_WRAP), m.sourceDevice,
                m.rgbChecksumMd5, rgb?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                persist = false, restoredCreatedAt = m.createdAt) == null
        } catch (e: Exception) {
            AppLog.i("프로파일 적재 실패: ${e.message}")
            false
        }
    }
}
