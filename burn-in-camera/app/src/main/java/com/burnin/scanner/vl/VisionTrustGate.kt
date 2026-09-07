package com.burnin.scanner.vl

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class VisionTrustGate(
    private val downloadTimeoutMs: Long = 90_000L,
    private val inferenceTimeoutMs: Long = 45_000L,
) {
    data class Decision(
        val status: Status,
        val confidence: Float,
        val reason: String,
        val rawText: String,
    )

    enum class Status {
        TRUST_SCREEN,
        UNTRUST_CAPTURE,
        UNCERTAIN,
        UNAVAILABLE,
    }

    private val model by lazy { Generation.getClient() }

    suspend fun assessCrop(
        image: Bitmap,
        roleSummary: String,
        regionSummary: String,
    ): Decision {
        return try {
            when (val status = model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADABLE -> {
                    val ready = downloadModel()
                    if (!ready) {
                        return Decision(
                            Status.UNAVAILABLE,
                            0f,
                            "Gemini Nano download did not finish in time",
                            "status=$status",
                        )
                    }
                }
                FeatureStatus.DOWNLOADING -> {
                    return Decision(Status.UNAVAILABLE, 0f, "Gemini Nano download is already in progress", "status=$status")
                }
                else -> {
                    return Decision(Status.UNAVAILABLE, 0f, "Gemini Nano is unavailable on this device", "status=$status")
                }
            }

            val request = GenerateContentRequest.Builder(
                ImagePart(image),
                TextPart(buildPrompt(roleSummary, regionSummary)),
            ).apply {
                temperature = 0.0f
                topK = 1
                seed = 7
                maxOutputTokens = 96
                candidateCount = 1
            }.build()
            val response = withTimeout(inferenceTimeoutMs) { model.generateContent(request) }
            parseDecision(response.candidates.firstOrNull()?.text.orEmpty())
        } catch (e: TimeoutCancellationException) {
            Decision(Status.UNAVAILABLE, 0f, "VL timeout", e.message.orEmpty())
        } catch (e: Throwable) {
            Decision(Status.UNAVAILABLE, 0f, unavailableReason(e), e.toString())
        }
    }

    fun close() {
        runCatching { model.close() }
    }

    private suspend fun downloadModel(): Boolean {
        return try {
            withTimeout(downloadTimeoutMs) {
                var completed = false
                model.download().collect { status ->
                    when (status) {
                        DownloadStatus.DownloadCompleted -> completed = true
                        is DownloadStatus.DownloadFailed -> throw status.e
                        else -> Unit
                    }
                }
                completed || model.checkStatus() == FeatureStatus.AVAILABLE
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun unavailableReason(e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            "606" in message || "FEATURE_NOT_FOUND" in message ->
                "AICore Gemini Nano image feature is not provisioned yet. Keep network/Play Store updates on, " +
                    "open Google/Gemini once if needed, reboot, then retry; measurement continues without VL."
            else -> "VL failed: ${e.message}"
        }
    }

    companion object {
        fun parseDecision(text: String): Decision {
            val cleaned = text
                .replace("```json", "", ignoreCase = true)
                .replace("```", "")
                .trim()
            val jsonStart = cleaned.indexOf('{')
            val jsonEnd = cleaned.lastIndexOf('}')
            val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                cleaned.substring(jsonStart, jsonEnd + 1)
            } else {
                cleaned
            }
            val obj = runCatching { JSONObject(jsonText) }.getOrNull()
            val statusText = obj?.optString("status")?.lowercase() ?: cleaned.lowercase()
            val status = when {
                "trust_screen" in statusText || "screen_defect" in statusText || "burn_in" in statusText ->
                    Status.TRUST_SCREEN
                "untrust_capture" in statusText || "camera_artifact" in statusText || "moire" in statusText ||
                    "flicker" in statusText || "reflection" in statusText || "blur" in statusText ->
                    Status.UNTRUST_CAPTURE
                else -> Status.UNCERTAIN
            }
            val confidence = obj?.optDouble("confidence", 0.5)?.toFloat()
                ?: if (status == Status.UNCERTAIN) 0.3f else 0.5f
            val reason = obj?.optString("reason")?.takeIf { it.isNotBlank() } ?: cleaned.take(180)
            return Decision(status, confidence.coerceIn(0f, 1f), reason, text)
        }

        private fun buildPrompt(roleSummary: String, regionSummary: String): String =
            """
            You are checking a cropped OLED screen-measurement contact sheet.
            The columns are simultaneous camera views of the same screen-space region: $roleSummary.
            Region metadata: $regionSummary.

            Decide whether this crop should be trusted as a real screen/panel non-uniformity
            such as burn-in, or untrusted as a capture artifact such as moire, rolling-band
            flicker, blur, focus failure, lens distortion residue, reflection, glare, or framing error.

            Return exactly one compact JSON object:
            {"status":"trust_screen|untrust_capture|uncertain","confidence":0.0-1.0,"reason":"short"}
            """.trimIndent()
    }
}
