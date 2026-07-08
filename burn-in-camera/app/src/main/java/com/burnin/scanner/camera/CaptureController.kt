package com.burnin.scanner.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CaptureFrame(
    val format: Int,
    val width: Int,
    val height: Int,
    val planes: List<Plane>,
    val timestampNs: Long,
    val sensitivityIso: Int?,
    val exposureTimeNs: Long?,
) {
    data class Plane(
        val bytes: ByteArray,
        val rowStride: Int,
        val pixelStride: Int,
    )
}

/**
 * Camera2 기반 측정 촬영 (M-FR-002/003/005의 MVP 구현).
 * - 후면 카메라, YUV_420_888 우선 촬영(JPEG는 fallback)
 * - 프리뷰 AE/AWB/AF 수렴 값을 읽은 뒤 가능하면 ISO/셔터/WB/포커스를 수동 고정
 *   (MANUAL_SENSOR 미지원 기기는 AE/AWB lock fallback)
 * - 프레임 평균화를 위해 같은 패턴을 여러 장 연속 촬영
 */
class CaptureController(context: Context, private val textureView: TextureView) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private lateinit var previewBuilder: CaptureRequest.Builder

    lateinit var cameraId: String
        private set
    lateinit var characteristics: CameraCharacteristics
        private set
    lateinit var captureSize: Size
        private set
    lateinit var jpegSize: Size
        private set

    private var captureFormat: Int = ImageFormat.JPEG
    private var manualLocked = false
    private var latestExposureTimeNs: Long? = null
    private var latestSensitivityIso: Int? = null
    private var latestAwbGains: RggbChannelVector? = null
    private var latestFocusDistance: Float? = null
    private var manualExposureTimeNs: Long? = null
    private var manualSensitivityIso: Int? = null
    private var manualAwbGains: RggbChannelVector? = null
    private var manualFocusDistance: Float? = null

    var aeLocked = false
        private set

    val captureFormatText: String
        get() = when (captureFormat) {
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.JPEG -> "JPEG"
            else -> "format=$captureFormat"
        }

    fun hardwareLevelText(): String {
        val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val name = when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY(자동 위주)"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL(수동 지원)"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3(수동+RAW)"
            else -> "UNKNOWN"
        }
        val aeLockAvailable =
            characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
        return "$name, $captureFormatText, manual ${if (manual) "가능" else "불가"}, " +
            "RAW ${if (raw) "가능" else "불가"}, AE잠금 ${if (aeLockAvailable) "가능" else "불가"}"
    }

    @SuppressLint("MissingPermission")
    suspend fun start() {
        val texture = awaitSurfaceTexture()

        cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: throw IllegalStateException("후면 카메라 없음")
        characteristics = manager.getCameraCharacteristics(cameraId)

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("스트림 설정 없음")
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val yuvSize = yuvSizes.maxByOrNull { it.width.toLong() * it.height }
        val fallbackJpegSize = jpegSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("JPEG/YUV 촬영 출력 미지원")
        captureFormat = if (yuvSize != null) ImageFormat.YUV_420_888 else ImageFormat.JPEG
        captureSize = yuvSize ?: fallbackJpegSize
        // 기존 호출부 호환용 이름. 실제 포맷은 captureFormatText를 확인한다.
        jpegSize = captureSize

        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1280 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: map.getOutputSizes(SurfaceTexture::class.java).first()
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val pSurface = Surface(texture)
        previewSurface = pSurface

        reader = ImageReader.newInstance(captureSize.width, captureSize.height, captureFormat, 3)

        device = openCamera()
        session = createSession(listOf(pSurface, reader!!.surface))

        previewBuilder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(pSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO,
            )
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
        session!!.setRepeatingRequest(previewBuilder.build(), previewCallback, handler)
    }

    /** AE/AWB 잠금. 이후 모든 촬영이 같은 노출·화이트밸런스로 이루어진다. */
    fun lockAeAwb() {
        manualLocked = false
        previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        previewBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        session?.setRepeatingRequest(previewBuilder.build(), previewCallback, handler)
        aeLocked = true
    }

    /**
     * 현재 자동 수렴 값을 수동 값으로 고정한다. FULL/LEVEL_3 기기에서는 ISO/셔터/WB/포커스를
     * Camera2 manual request로 고정하고, 그 외 기기는 AE/AWB lock으로 fallback한다.
     *
     * displayRefreshHz가 주어지면 노출 시간을 대상 화면 주사 주기의 정수배로 양자화한다.
     * OLED는 60Hz 리프레시와 그 정수배 PWM 디밍으로 밝기가 진동하므로, 임의 노출로 찍으면
     * 롤링셔터 행마다 위상이 달라 가로 줄무늬(밴딩)와 프레임 간 플리커가 생긴다. 노출이
     * 주기의 정수배면 모든 행이 완전한 사이클을 적분해 밴딩이 원천 상쇄된다.
     */
    fun lockMeasurementControls(displayRefreshHz: Float = 60f): String {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            .orEmpty()
        val manualCapable = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val exposure = latestExposureTimeNs
        val iso = latestSensitivityIso
        if (manualCapable && exposure != null && iso != null) {
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val (flickerFreeExposure, compensatedIso) = quantizeExposureToRefresh(
                exposure.coerceInRange(exposureRange),
                iso.coerceInRange(isoRange),
                displayRefreshHz,
                exposureRange,
                isoRange,
            )
            manualExposureTimeNs = flickerFreeExposure
            manualSensitivityIso = compensatedIso
            manualAwbGains = latestAwbGains
            manualFocusDistance = latestFocusDistance?.let { focus ->
                val maxFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (maxFocus > 0f) focus.coerceIn(0f, maxFocus) else null
            }
            manualLocked = true
            aeLocked = true
            applyMeasurementControls(previewBuilder)
            session?.setRepeatingRequest(previewBuilder.build(), previewCallback, handler)
            val focusText = manualFocusDistance?.let { ", focus ${"%.2f".format(it)}D" } ?: ""
            val flickerText = if (flickerFreeExposure != exposure) {
                val cycles = Math.round(flickerFreeExposure * displayRefreshHz / 1e9)
                " (flicker-sync ${displayRefreshHz.toInt()}Hz×$cycles, AE ${exposure}ns→)"
            } else {
                ""
            }
            return "수동 고정: ISO $manualSensitivityIso, ${manualExposureTimeNs}ns$focusText$flickerText"
        }

        lockAeAwb()
        return "AE/AWB lock fallback"
    }

    /**
     * 노출을 주사 주기(1/refreshHz)의 정수배로 양자화하고, 총 노출량이 유지되도록 ISO를
     * 반비례 보상한다. 우선 올림(노출↑·ISO↓, 노이즈도 감소)을 시도하고, ISO 하한이나
     * 노출 상한에 걸려 밝기가 15% 이상 달라지면 내림을 시도하며, 둘 다 불가하면 원값 유지.
     */
    private fun quantizeExposureToRefresh(
        exposureNs: Long,
        iso: Int,
        refreshHz: Float,
        exposureRange: Range<Long>?,
        isoRange: Range<Int>?,
    ): Pair<Long, Int> {
        if (refreshHz < 1f || exposureNs <= 0L) return exposureNs to iso
        val periodNs = Math.round(1e9 / refreshHz)
        if (periodNs <= 0L) return exposureNs to iso

        fun candidate(cycles: Long): Pair<Long, Int>? {
            if (cycles < 1) return null
            val quantized = cycles * periodNs
            if (exposureRange != null && quantized !in exposureRange.lower..exposureRange.upper) return null
            val compensated = Math.round(iso.toDouble() * exposureNs / quantized)
                .toInt()
                .coerceInRange(isoRange)
            val brightnessRatio = compensated.toDouble() * quantized / (iso.toDouble() * exposureNs)
            if (brightnessRatio > 1.15 || brightnessRatio < 0.85) return null
            return quantized to compensated
        }

        val up = (exposureNs + periodNs - 1) / periodNs
        return candidate(up) ?: candidate(exposureNs / periodNs) ?: (exposureNs to iso)
    }

    suspend fun captureFrames(count: Int, interFrameDelayMs: Long = 150): List<CaptureFrame> {
        val list = ArrayList<CaptureFrame>(count)
        repeat(count) {
            list += captureSingleFrame()
            delay(interFrameDelayMs)
        }
        return list
    }

    suspend fun captureSingleFrame(): CaptureFrame =
        withTimeout(12_000) { captureFrame() }

    private suspend fun captureFrame(): CaptureFrame = suspendCancellableCoroutine { cont ->
        val r = reader ?: return@suspendCancellableCoroutine cont.resumeWithException(
            IllegalStateException("카메라 미시작")
        )
        r.setOnImageAvailableListener({ rd ->
            val image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            val frame = try {
                image.toCaptureFrame()
            } finally {
                image.close()
            }
            rd.setOnImageAvailableListener(null, null)
            if (cont.isActive) cont.resume(frame)
        }, handler)

        val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            applyMeasurementControls(this)
            if (captureFormat == ImageFormat.JPEG) set(CaptureRequest.JPEG_QUALITY, 98.toByte())
        }
        session!!.capture(req.build(), previewCallback, handler)
    }

    private fun applyMeasurementControls(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        if (manualLocked) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            manualExposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            manualSensitivityIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualAwbGains?.let {
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, it)
            }
            manualFocusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, aeLocked)
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            latestExposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: latestExposureTimeNs
            latestSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: latestSensitivityIso
            latestAwbGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: latestAwbGains
            latestFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: latestFocusDistance
        }
    }

    private fun Image.toCaptureFrame(): CaptureFrame {
        val copiedPlanes = planes.map { plane ->
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            CaptureFrame.Plane(bytes, plane.rowStride, plane.pixelStride)
        }
        return CaptureFrame(
            format = format,
            width = width,
            height = height,
            planes = copiedPlanes,
            timestampNs = timestamp,
            sensitivityIso = manualSensitivityIso ?: latestSensitivityIso,
            exposureTimeNs = manualExposureTimeNs ?: latestExposureTimeNs,
        )
    }

    private suspend fun awaitSurfaceTexture(): SurfaceTexture =
        suspendCancellableCoroutine { cont ->
            val existing = textureView.surfaceTexture
            if (textureView.isAvailable && existing != null) {
                cont.resume(existing)
                return@suspendCancellableCoroutine
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    textureView.surfaceTextureListener = null
                    if (cont.isActive) cont.resume(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카메라 연결 끊김"))
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카메라 오류 $error"))
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            device!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("세션 구성 실패"))
                }
            }, handler)
        }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        device = null
        reader = null
        thread.quitSafely()
    }

    private fun Long.coerceInRange(range: Range<Long>?): Long =
        if (range == null) this else coerceIn(range.lower, range.upper)

    private fun Int.coerceInRange(range: Range<Int>?): Int =
        if (range == null) this else coerceIn(range.lower, range.upper)
}
