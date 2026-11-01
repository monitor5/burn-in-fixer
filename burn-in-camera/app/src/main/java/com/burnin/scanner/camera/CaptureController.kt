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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.burnin.scanner.analysis.CameraToneCurve
import com.burnin.scanner.analysis.ImageOps
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    /** RAW_SENSOR 프레임 디코드 정보. 다른 형식이면 null. */
    val raw: RawInfo? = null,
) {
    /**
     * RAW 디코드에 필요한 센서·촬영 정보. [blackLevel]은 2×2 위치(행 우선) 순서,
     * gain은 촬영에 고정한 AWB gain, [ccm]은 HAL 색 보정 행렬(행 우선 3×3, 없으면 null).
     */
    data class RawInfo(
        val cfa: Int,
        val blackLevel: List<Int>,
        val whiteLevel: Int,
        val gainR: Float,
        val gainG: Float,
        val gainB: Float,
        val ccm: List<Float>?,
        /** 렌즈 셰이딩(비네팅) 보정 gain 맵. RAW에는 ISP 셰이딩 보정이 빠져 있어 디코드에서 곱한다. */
        val shading: Shading? = null,
    ) {
        /**
         * Camera2 LensShadingMap: [rows]×[cols] 격자, 칸마다 [R, G_even, G_odd, B] gain(행 우선).
         * 격자는 센서 전체를 덮고, 픽셀 (x, y)의 gain은 격자 좌표 x·(cols−1)/(w−1)에서 이중선형 보간한다.
         */
        data class Shading(val cols: Int, val rows: Int, val gains: List<Float>) {
            init { require(cols >= 2 && rows >= 2 && gains.size == cols * rows * 4) { "셰이딩 맵 크기 오류" } }
        }

        companion object {
            const val CFA_RGGB = 0
            const val CFA_GRBG = 1
            const val CFA_GBRG = 2
            const val CFA_BGGR = 3
        }
    }

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
class CaptureController(
    context: Context,
    private val textureView: TextureView,
    /** 보조 화각(초광각/망원) 프리뷰. null이거나 보조 카메라가 없으면 쓰지 않는다. */
    private val auxTextureView: TextureView? = null,
) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var auxPreviewSurface: Surface? = null
    /** 보조 프리뷰에 연결된 역할 (ultrawide/tele). */
    var auxPreviewRole: String? = null
        private set
    /** 메인 카메라가 열린 뒤 끊겼는지(다른 카메라를 열다 빼앗긴 경우 등). */
    @Volatile private var mainLost = false
    private lateinit var previewBuilder: CaptureRequest.Builder

    /** 동시 3각 촬영용 보조 화각 리더 (role → reader). 비어 있으면 메인 단독. */
    private val extraReaders = LinkedHashMap<String, ImageReader>()
    private val standaloneCameras = LinkedHashMap<String, StandaloneCamera>()
    /** 동시 오픈이 막힌 보조 화각: 메인을 잠시 닫고 번갈아 연다 (role → choice). */
    private val sequentialRoles = LinkedHashMap<String, CameraEnumerator.CameraChoice>()
    private var triSelection: CameraEnumerator.TriSelection? = null
    private val setupNotes = ArrayList<String>()

    /** 마지막 동시 촬영에서 물리 카메라별 실제 노출(ns). 플리커 싱크 검증용. */
    private val lastPhysicalExposureNs = LinkedHashMap<String, Long>()

    val isTriActive: Boolean
        get() = extraReaders.isNotEmpty() || standaloneCameras.isNotEmpty() || sequentialRoles.isNotEmpty()

    /** 번갈아 촬영하는 보조 화각이 있는지. 있으면 [captureSequential]로 따로 찍어야 한다. */
    val hasSequentialRoles: Boolean get() = sequentialRoles.isNotEmpty()

    /** 보조 화각 프리뷰가 실제로 흘러가고 있는지. */
    val isAuxPreviewActive: Boolean get() = isTriActive && auxPreviewSurface != null

    private companion object {
        /** 번갈아 촬영 시 보조 카메라를 연 뒤 AF·센서가 안정될 때까지 기다리는 시간. */
        const val SEQUENTIAL_SETTLE_MS = 500L
    }

    val activeRoles: List<String>
        get() = listOf(CameraEnumerator.ROLE_MAIN) + extraReaders.keys + standaloneCameras.keys + sequentialRoles.keys

    fun triSummary(): String {
        val tri = triSelection ?: return "메인 단독 [$cameraId]"
        return tri.roles()
            .filter { (role, _) ->
                role == CameraEnumerator.ROLE_MAIN ||
                    extraReaders.containsKey(role) ||
                    standaloneCameras.containsKey(role) ||
                    sequentialRoles.containsKey(role)
            }
            .joinToString(" + ") { (role, c) ->
                val mode = when {
                    standaloneCameras.containsKey(role) -> ", 독립동시오픈"
                    sequentialRoles.containsKey(role) -> ", 번갈아촬영"
                    else -> ""
                }
                "$role[${c.key}] FOV ${c.fovDeg.toInt()}°$mode"
            }
    }

    fun diagnosticNotes(): List<String> = setupNotes.toList()

    private data class StandaloneCamera(
        val role: String,
        val choice: CameraEnumerator.CameraChoice,
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val reader: ImageReader,
        /** RAW로 찍는 보조 화각이면 그 센서의 CFA·레벨 정보. */
        val raw: RawStatic? = null,
    )

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
    private var latestCcm: android.hardware.camera2.params.ColorSpaceTransform? = null
    /** 카메라(키)별 최근 렌즈 셰이딩 맵. RAW 프레임에 실어 디코드에서 비네팅을 보정한다. */
    private val shadingByCamera = java.util.concurrent.ConcurrentHashMap<String, CaptureFrame.RawInfo.Shading>()
    private var rawStatic: RawStatic? = null

    /** RAW_SENSOR를 쓸 수 있으면 RAW로 촬영한다(센서 선형값). */
    var preferRaw: Boolean = true
    private var latestFocusDistance: Float? = null
    private var manualExposureTimeNs: Long? = null
    private var manualSensitivityIso: Int? = null
    private var manualAwbGains: RggbChannelVector? = null
    private var manualFocusDistance: Float? = null

    /** 측정 촬영에 지정하는 톤커브. 세션 시작 시 기기 지원에 따라 고르고, 결과가 다르면 ASSUMED로 내린다. */
    @Volatile
    var toneMode: CameraToneCurve.Mode = CameraToneCurve.Mode.ASSUMED
        private set
    private val toneCurve by lazy {
        val pts = CameraToneCurve.curvePoints()
        TonemapCurve(pts, pts, pts)
    }

    var aeLocked = false
        private set

    /** 메인 측정 프레임이 RAW_SENSOR인지 (선형 센서값, 톤커브 무관). */
    val isRaw: Boolean get() = captureFormat == ImageFormat.RAW_SENSOR

    val captureFormatText: String
        get() = when (captureFormat) {
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.JPEG -> "JPEG"
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR(16bit Bayer)"
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

    /**
     * 카메라 시작. selection이 주어지고 기기가 지원하면(API 28+, 논리 멀티카메라)
     * 초광각/표준/망원 물리 스트림을 한 세션에 함께 구성해 동시 촬영을 준비한다.
     * 동시 구성 실패 시 메인 단독으로 자동 폴백한다.
     */
    @SuppressLint("MissingPermission")
    suspend fun start(selection: CameraEnumerator.TriSelection? = null) {
        val texture = awaitSurfaceTexture()

        val tri = if (selection != null && Build.VERSION.SDK_INT >= 28) selection else null
        triSelection = tri
        setupNotes.clear()
        if (tri == null) {
            cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            } ?: throw IllegalStateException("후면 카메라 없음")
            characteristics = manager.getCameraCharacteristics(cameraId)
        } else {
            cameraId = tri.openId
            characteristics = manager.getCameraCharacteristics(tri.main.physicalId ?: tri.openId)
        }

        setToneMode(selectToneMode(manager.getCameraCharacteristics(cameraId)))
        setupNotes += "측정 톤커브: ${toneMode.label}"

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("스트림 설정 없음")
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val yuvSize = yuvSizes.maxByOrNull { it.width.toLong() * it.height }
        val fallbackJpegSize = jpegSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("JPEG/YUV 촬영 출력 미지원")
        // RAW가 되면 RAW 우선: 센서 선형값이라 톤커브 추정·역변환 오차가 없다.
        val rawSize = rawCaptureSize(characteristics)
        rawStatic = rawSize?.let { rawStaticInfo(characteristics) }
        captureFormat = when {
            preferRaw && rawSize != null && rawStatic != null -> ImageFormat.RAW_SENSOR
            yuvSize != null -> ImageFormat.YUV_420_888
            else -> ImageFormat.JPEG
        }
        captureSize = if (captureFormat == ImageFormat.RAW_SENSOR) rawSize!! else yuvSize ?: fallbackJpegSize
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

        auxPreviewRole = tri?.roles()?.firstOrNull { it.first != CameraEnumerator.ROLE_MAIN }?.first
        val auxView = auxTextureView
        auxPreviewSurface = if (auxPreviewRole != null && auxView != null) {
            withTimeoutOrNull(3_000) { awaitSurfaceTexture(auxView) }?.let { st ->
                st.setDefaultBufferSize(previewSize.width, previewSize.height)
                Surface(st)
            }
        } else null
        if (auxPreviewRole != null && auxView != null && auxPreviewSurface == null) {
            setupNotes += "보조 프리뷰 없음 (화면 준비 안 됨)"
        }

        extraReaders.clear()
        standaloneCameras.values.forEach { runCatching { it.close() } }
        standaloneCameras.clear()
        sequentialRoles.clear()
        if (tri != null) {
            for ((role, choice) in tri.roles()) {
                if (role == CameraEnumerator.ROLE_MAIN) continue
                if (tri.isStandalone(role)) continue
                val rc = runCatching {
                    manager.getCameraCharacteristics(choice.physicalId ?: choice.openId)
                }.getOrNull() ?: continue
                val rMap = rc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val rSizes = rMap.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                // 보조 화각은 4096px 이하로 캡: 3스트림 동시 대역폭·메모리 한도 보호
                val rSize = rSizes.filter { maxOf(it.width, it.height) <= 4096 }
                    .maxByOrNull { it.width.toLong() * it.height }
                    ?: rSizes.minByOrNull { it.width.toLong() * it.height }
                    ?: continue
                extraReaders[role] =
                    ImageReader.newInstance(rSize.width, rSize.height, ImageFormat.YUV_420_888, 2)
            }
        }

        mainLost = false
        device = openCamera()
        session = if (tri != null && extraReaders.isNotEmpty() && Build.VERSION.SDK_INT >= 28) {
            try {
                try {
                    createTriSession(tri, pSurface, auxPreviewSurface)
                } catch (e: Exception) {
                    if (auxPreviewSurface == null) throw e
                    // 보조 프리뷰 스트림 하나 때문에 동시 촬영 전체를 잃지 않게 프리뷰 없이 재시도
                    setupNotes += "보조 프리뷰 포함 세션 실패, 프리뷰 없이 재시도: ${e.message}"
                    releaseAuxPreview()
                    createTriSession(tri, pSurface, null)
                }
            } catch (e: Exception) {
                // 동시 3각 구성 실패 → 보조 리더 정리 후 메인 단독 폴백
                setupNotes += "논리 멀티카메라 세션 실패: ${e.message}"
                extraReaders.values.forEach { runCatching { it.close() } }
                extraReaders.clear()
                createSession(listOf(pSurface, reader!!.surface))
            }
        } else {
            extraReaders.values.forEach { runCatching { it.close() } }
            extraReaders.clear()
            try {
                createSession(listOf(pSurface, reader!!.surface))
            } catch (e: Exception) {
                if (captureFormat != ImageFormat.RAW_SENSOR) throw e
                // RAW + 프리뷰 조합을 HAL이 거부하면 YUV로 다시 구성한다.
                setupNotes += "RAW 세션 구성 실패, YUV로 폴백: ${e.message}"
                runCatching { reader?.close() }
                val yuv = yuvSize ?: throw e
                captureFormat = ImageFormat.YUV_420_888
                captureSize = yuv
                jpegSize = yuv
                reader = ImageReader.newInstance(yuv.width, yuv.height, captureFormat, 3)
                createSession(listOf(pSurface, reader!!.surface))
            }
        }

        if (tri != null) {
            for ((role, choice) in tri.roles()) {
                if (!tri.isStandalone(role)) continue
                val standalone = runCatching { openStandaloneCamera(role, choice) }
                    .onFailure {
                        setupNotes += "독립 $role[${choice.key}] 동시 오픈 실패: ${it.message} — 메인과 번갈아 촬영으로 전환"
                        sequentialRoles[role] = choice
                    }
                    .getOrNull()
                if (standalone != null) {
                    standaloneCameras[role] = standalone
                    setupNotes += "독립 $role[${choice.key}] 동시 오픈 활성"
                }
            }
            if (standaloneCameras.isNotEmpty()) {
                // 동시 오픈이 보장되지 않는 기기는 두 번째 카메라를 열 때 메인을 끊어 버린다.
                delay(500)
                if (mainLost) {
                    // 동시에 둘 다 쥘 수 없다: 보조를 닫고 메인을 다시 연 뒤 번갈아 촬영으로 쓴다.
                    standaloneCameras.forEach { (role, cam) ->
                        sequentialRoles[role] = cam.choice
                        runCatching { cam.close() }
                    }
                    standaloneCameras.clear()
                    setupNotes += "보조 카메라를 열자 메인 카메라가 끊김 — 메인과 번갈아 촬영으로 전환"
                    reopenMain()
                }
            }
        }

        startMainPreview()
        if (sequentialRoles.isNotEmpty() && auxPreviewSurface != null) {
            // 정렬 확인용: 보조 화각을 한 번 열어 보조 프리뷰에 장면을 띄워 둔다.
            runCatching { captureSequential(0, 0L) { _, _ -> } }
                .onFailure { setupNotes += "보조 프리뷰 미리보기 실패: ${it.message}" }
        }
    }

    private fun startMainPreview() {
        previewBuilder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface!!)
            // 논리 멀티카메라 세션이면 보조 프리뷰도 같은 반복 요청으로 흘린다.
            if (auxPreviewRole?.let { it in extraReaders } == true) auxPreviewSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO,
            )
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            applyShadingMapMode(this)
            // 번갈아 촬영 뒤 다시 열 때 잠금 상태를 이어 간다.
            if (manualLocked) applyMeasurementControls(this)
            else if (aeLocked) {
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
        }
        session!!.setRepeatingRequest(previewBuilder.build(), previewCallback, handler)
    }

    /** 메인 카메라(와 같은 논리 카메라의 보조 스트림)를 다시 열고 프리뷰를 이어 간다. */
    private suspend fun reopenMain() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        mainLost = false
        device = openCamera()
        val tri = triSelection
        session = if (tri != null && extraReaders.isNotEmpty() && Build.VERSION.SDK_INT >= 28) {
            createTriSession(tri, previewSurface!!, auxPreviewSurface?.takeIf { auxPreviewRole in extraReaders })
        } else {
            createSession(listOf(previewSurface!!, reader!!.surface))
        }
        startMainPreview()
    }

    /**
     * 동시 오픈이 막힌 보조 화각을 메인과 번갈아 촬영한다. 메인을 닫고 보조를 같은 수동 노출·
     * 화이트밸런스·톤커브로 열어 [count]장을 찍은 뒤 메인을 다시 연다. 화면 패턴은 그동안 그대로라
     * 동시 촬영과 같은 장면을 다른 광학 경로로 기록한다(시점만 수 초 다름).
     * 보조 프리뷰가 있으면 보조가 열려 있는 동안 그쪽이 살아 있고, 메인 프리뷰는 마지막 장면에 멈춘다.
     */
    suspend fun captureSequential(
        count: Int,
        interFrameDelayMs: Long,
        onFrame: suspend (role: String, frame: CaptureFrame) -> Unit,
    ) {
        if (sequentialRoles.isEmpty()) return
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        try {
            for ((role, choice) in sequentialRoles) {
                val cam = openSequentialCamera(role, choice)
                try {
                    // 보조 렌즈는 메인 초점거리를 쓸 수 없어 AF로 맞추고, 노출·WB·톤커브는 메인 값으로 고정한다.
                    delay(SEQUENTIAL_SETTLE_MS)
                    repeat(count) { i ->
                        onFrame(role, captureSequentialFrame(cam))
                        if (i != count - 1) delay(interFrameDelayMs)
                    }
                } finally {
                    cam.close()
                }
            }
        } finally {
            reopenMain()
        }
    }

    private suspend fun openSequentialCamera(role: String, choice: CameraEnumerator.CameraChoice): StandaloneCamera {
        val rc = manager.getCameraCharacteristics(choice.openId)
        val rMap = rc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("스트림 설정 없음")
        // 번갈아 촬영은 한 번에 한 대만 쓰므로 보조도 최대 해상도로, 가능하면 RAW로 받는다.
        val rawSize = if (preferRaw) rawCaptureSize(rc) else null
        val rawSt = rawSize?.let { rawStaticInfo(rc) }
        val (rSize, rFormat) = if (rawSize != null && rawSt != null) {
            rawSize to ImageFormat.RAW_SENSOR
        } else {
            (rMap.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width.toLong() * it.height }
                ?: throw IllegalStateException("YUV 출력 없음")) to ImageFormat.YUV_420_888
        }
        val sReader = ImageReader.newInstance(rSize.width, rSize.height, rFormat, 2)
        val sDevice = try {
            suspendCancellableCoroutine<CameraDevice> { cont -> openCameraById(choice.openId, cont) }
        } catch (e: Exception) {
            sReader.close()
            throw e
        }
        val aux = auxPreviewSurface?.takeIf { role == auxPreviewRole }
        val sSession = try {
            createSession(sDevice, listOfNotNull(sReader.surface, aux))
        } catch (e: Exception) {
            runCatching { sDevice.close() }
            sReader.close()
            throw e
        }
        val repeating = sDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(aux ?: sReader.surface)
            applySequentialControls(this)
        }
        // 반복 요청 결과로 이 카메라의 셰이딩 맵을 미리 받아 둔다(첫 RAW 프레임부터 보정되게).
        if (aux != null) sSession.setRepeatingRequest(repeating.build(), standaloneCallback(choice.key), handler)
        else sSession.capture(repeating.build(), standaloneCallback(choice.key), handler) // AF 시작용 한 장
        return StandaloneCamera(role, choice, sDevice, sSession, sReader, rawSt.takeIf { rFormat == ImageFormat.RAW_SENSOR })
    }

    private suspend fun captureSequentialFrame(cam: StandaloneCamera): CaptureFrame = withTimeout(12_000) {
        suspendCancellableCoroutine { cont ->
            val r = cam.reader
            r.setOnImageAvailableListener({ rd ->
                val image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val frame = image.toCaptureFrame(cam.raw, cam.choice.key)
                    if (cont.isActive) cont.resume(frame)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    image.close()
                    rd.setOnImageAvailableListener(null, null)
                }
            }, handler)
            cont.invokeOnCancellation { handler.post { runCatching { r.setOnImageAvailableListener(null, null) } } }
            val req = cam.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                applySequentialControls(this)
            }
            cam.session.capture(req.build(), standaloneCallback(cam.choice.key), handler)
        }
    }

    /** 번갈아 촬영 보조 화각: 메인과 같은 노출·WB·톤커브, 초점만 AF. */
    private fun applySequentialControls(builder: CaptureRequest.Builder) {
        applyStandaloneMeasurementControls(builder)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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
     * 수동 고정 상태에서 총 노출량(노출 시간 × ISO)을 factor배로 바꾼다. 노출 시간은 계속
     * 주사 주기의 정수배로 유지한다. 수동 고정이 아니거나 범위 한계로 거의 바뀌지 않으면 false.
     * (색감 측정에서 흰색 포화/신호 부족을 피하려고 사용)
     */
    fun scaleManualExposure(factor: Double, displayRefreshHz: Float = 60f): Boolean {
        val exposure = manualExposureTimeNs
        val iso = manualSensitivityIso
        if (!manualLocked || exposure == null || iso == null || !factor.isFinite() || factor <= 0.0) return false
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        // ISO를 먼저 바꾸고, ISO 한계에 걸리면 남은 비율만큼 노출 시간을 바꾼다.
        val desiredIso = (iso * factor).coerceIn(1.0, Int.MAX_VALUE.toDouble())
        val newIso = Math.round(desiredIso).toInt().coerceInRange(isoRange)
        val remaining = factor * iso / newIso
        val (newExposure, quantIso) = quantizeExposureToRefresh(
            Math.round(exposure * remaining).coerceAtLeast(1L).coerceInRange(exposureRange),
            newIso, displayRefreshHz, exposureRange, isoRange,
        )
        val achieved = newExposure.toDouble() * quantIso / (exposure.toDouble() * iso)
        if (Math.abs(achieved - 1.0) < 0.02) return false
        manualExposureTimeNs = newExposure
        manualSensitivityIso = quantIso
        applyMeasurementControls(previewBuilder)
        session?.setRepeatingRequest(previewBuilder.build(), previewCallback, handler)
        return true
    }

    /** 현재 수동 노출 설명 (리포트용). */
    fun manualExposureText(): String =
        if (manualLocked) "ISO $manualSensitivityIso, ${manualExposureTimeNs}ns" else "AE/AWB lock"

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
    ): Pair<Long, Int> = ExposurePolicy.quantize(exposureNs, iso, refreshHz,
        exposureRange?.let { it.lower..it.upper }, isoRange?.let { it.lower..it.upper })

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
            try {
                val frame = image.toCaptureFrame()
                if (cont.isActive) cont.resume(frame)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                image.close()
                rd.setOnImageAvailableListener(null, null)
            }
        }, handler)

        cont.invokeOnCancellation { handler.post { runCatching { r.setOnImageAvailableListener(null, null) } } }
        val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            applyMeasurementControls(this)
            if (captureFormat == ImageFormat.JPEG) set(CaptureRequest.JPEG_QUALITY, 98.toByte())
        }
        session!!.capture(req.build(), previewCallback, handler)
    }

    /**
     * 알려진 톤커브 우선순위: CONTRAST_CURVE(제어점 수 충분) → GAMMA_VALUE → PRESET_CURVE sRGB → 기본(ASSUMED).
     * 모두 [ImageOps] 디코더가 정확한 역함수를 가진다 ([CameraToneCurve]).
     */
    private fun selectToneMode(ch: CameraCharacteristics): CameraToneCurve.Mode {
        val modes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
        val maxPoints = ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        return when {
            CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in modes && maxPoints >= CameraToneCurve.POINTS ->
                CameraToneCurve.Mode.CONTRAST_CURVE
            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in modes -> CameraToneCurve.Mode.GAMMA_VALUE
            CameraMetadata.TONEMAP_MODE_PRESET_CURVE in modes -> CameraToneCurve.Mode.PRESET_SRGB
            else -> CameraToneCurve.Mode.ASSUMED
        }
    }

    private fun setToneMode(mode: CameraToneCurve.Mode) {
        toneMode = mode
        ImageOps.toneMode = mode
    }

    private fun requestedTonemapMode(mode: CameraToneCurve.Mode): Int? = when (mode) {
        CameraToneCurve.Mode.CONTRAST_CURVE -> CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
        CameraToneCurve.Mode.GAMMA_VALUE -> CameraMetadata.TONEMAP_MODE_GAMMA_VALUE
        CameraToneCurve.Mode.PRESET_SRGB -> CameraMetadata.TONEMAP_MODE_PRESET_CURVE
        CameraToneCurve.Mode.ASSUMED -> null
    }

    private fun applyToneControls(builder: CaptureRequest.Builder) {
        val mode = toneMode
        val cameraMode = requestedTonemapMode(mode) ?: return
        builder.set(CaptureRequest.TONEMAP_MODE, cameraMode)
        when (mode) {
            CameraToneCurve.Mode.CONTRAST_CURVE -> builder.set(CaptureRequest.TONEMAP_CURVE, toneCurve)
            CameraToneCurve.Mode.GAMMA_VALUE -> builder.set(CaptureRequest.TONEMAP_GAMMA, CameraToneCurve.GAMMA.toFloat())
            CameraToneCurve.Mode.PRESET_SRGB ->
                builder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
            CameraToneCurve.Mode.ASSUMED -> Unit
        }
    }

    /**
     * 측정 요청에 지정한 톤맵 모드를 HAL이 실제로 적용했는지 CaptureResult로 확인한다.
     * 다르게 보고하면 디코더를 기본 곡선 근사(ASSUMED)로 내리고 진단 노트에 남긴다.
     */
    private fun verifyToneResult(request: CaptureRequest, result: TotalCaptureResult) {
        val requested = request.get(CaptureRequest.TONEMAP_MODE) ?: return
        if (requested != requestedTonemapMode(toneMode)) return
        val applied = result.get(CaptureResult.TONEMAP_MODE) ?: return
        if (applied != requested) {
            setupNotes += "경고: 톤맵 모드 요청 $requested, 결과 $applied — 기본 곡선 근사(x^2.2)로 되돌림"
            setToneMode(CameraToneCurve.Mode.ASSUMED)
        }
    }

    private fun applyMeasurementControls(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        applyShadingMapMode(builder)
        applyToneControls(builder)
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

    /**
     * 활성 화각 전체(메인 + 보조)를 "한 번의 캡처 요청"으로 동시 촬영한다.
     * 같은 순간·같은 화면 상태를 서로 다른 광학 경로로 기록하므로,
     * 결과 간 불일치는 화면이 아니라 카메라 기인 성분(무아레·왜곡·플리커 위상)이다.
     * 메인 단독 세션에서는 main 한 장만 담긴 맵을 반환한다 (호출부 코드 경로 동일).
     */
    suspend fun captureTriFrames(): Map<String, CaptureFrame> = withTimeout(15_000) {
        val mainReader = reader ?: throw IllegalStateException("카메라 미시작")
        val readers = LinkedHashMap<String, ImageReader>()
        readers[CameraEnumerator.ROLE_MAIN] = mainReader
        readers.putAll(extraReaders)
        standaloneCameras.forEach { (role, cam) -> readers[role] = cam.reader }

        val waits = readers.mapValues { CompletableDeferred<CaptureFrame>() }
        try {
            for ((role, r) in readers) {
                r.setOnImageAvailableListener({ rd ->
                    val image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val frame = try {
                        image.toCaptureFrame()
                    } finally {
                        image.close()
                    }
                    rd.setOnImageAvailableListener(null, null)
                    waits.getValue(role).complete(frame)
                }, handler)
            }
            val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(mainReader.surface)
                extraReaders.values.forEach { addTarget(it.surface) }
                applyMeasurementControls(this)
                if (captureFormat == ImageFormat.JPEG) set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            }
            session!!.capture(req.build(), triCallback, handler)
            standaloneCameras.values.forEach { cam ->
                val sReq = cam.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(cam.reader.surface)
                    applyStandaloneMeasurementControls(this)
                }
                cam.session.capture(sReq.build(), standaloneCallback(cam.choice.key), handler)
            }
            waits.mapValues { it.value.await() }
        } finally {
            readers.values.forEach { it.setOnImageAvailableListener(null, null) }
        }
    }

    /**
     * 물리 카메라별 실제 노출이 화면 주사 주기의 정수배(플리커 싱크)인지 요약.
     * 보조 화각이 싱크에서 벗어나면 해당 카메라 프레임은 밴딩이 남을 수 있으므로
     * 교차 일치도 마스킹의 근거 로그로 남긴다.
     */
    fun physicalExposureSummary(refreshHz: Float): String? {
        if (lastPhysicalExposureNs.isEmpty() || refreshHz < 1f) return null
        val periodNs = 1e9 / refreshHz
        return lastPhysicalExposureNs.entries.joinToString(", ") { (pid, ns) ->
            val cycles = ns / periodNs
            val synced = Math.abs(cycles - Math.round(cycles)) < 0.05
            "[$pid] ${ns}ns ${if (synced) "sync✓" else "sync✗"}"
        }
    }

    private val triCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            previewCallback.onCaptureCompleted(session, request, result)
            if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                val physical = runCatching { result.physicalCameraResults }.getOrNull() ?: return
                for ((pid, r) in physical) {
                    r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        ?.let { lastPhysicalExposureNs[pid] = it }
                }
            }
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            verifyToneResult(request, result)
            latestExposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: latestExposureTimeNs
            latestSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: latestSensitivityIso
            latestAwbGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: latestAwbGains
            latestCcm = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: latestCcm
            recordShading(cameraId, result)
            latestFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: latestFocusDistance
        }
    }

    private fun recordShading(key: String, result: CaptureResult) {
        val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return
        val cols = map.columnCount
        val rows = map.rowCount
        if (cols < 2 || rows < 2) return
        val gains = FloatArray(map.gainFactorCount)
        map.copyGainFactors(gains, 0)
        shadingByCamera[key] = CaptureFrame.RawInfo.Shading(cols, rows, gains.toList())
    }

    /** 측정 로그용: 카메라별 셰이딩 맵 크기와 모서리 G gain(중앙 대비). */
    fun shadingSummary(): String =
        if (shadingByCamera.isEmpty()) "렌즈 셰이딩 맵 없음 — RAW 비네팅 보정 불가(flat-field만 사용)"
        else shadingByCamera.entries.joinToString(", ") { (key, s) ->
            fun g(c: Int, r: Int) = s.gains[(r * s.cols + c) * 4 + 1]
            val center = g(s.cols / 2, s.rows / 2)
            "렌즈 셰이딩 맵 [$key] ${s.cols}x${s.rows}, 모서리/중앙 G gain ${"%.2f".format(g(0, 0) / center)}"
        }

    private fun applyShadingMapMode(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
    }

    private fun Image.toCaptureFrame(rawSt: RawStatic? = rawStatic, key: String = cameraId): CaptureFrame {
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
            raw = if (format == ImageFormat.RAW_SENSOR) rawInfo(rawSt)?.copy(shading = shadingByCamera[key]) else null,
        )
    }

    private fun rawCaptureSize(ch: CameraCharacteristics): Size? {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return null
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        return map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
    }

    private class RawStatic(val cfa: Int, val black: List<Int>, val white: Int)

    private fun rawStaticInfo(ch: CameraCharacteristics): RawStatic? {
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return null
        if (cfa !in 0..3) return null // RGGB 계열이 아니면(모노·RGBW 등) YUV를 쓴다
        val pattern = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) ?: return null
        val white = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
        // BlackLevelPattern.getOffsetForIndex(column, row) → 2×2 위치 행 우선 순서
        val black = listOf(
            pattern.getOffsetForIndex(0, 0), pattern.getOffsetForIndex(1, 0),
            pattern.getOffsetForIndex(0, 1), pattern.getOffsetForIndex(1, 1),
        )
        return RawStatic(cfa, black, white)
    }

    private fun rawInfo(st: RawStatic?): CaptureFrame.RawInfo? {
        st ?: return null
        val g = manualAwbGains ?: latestAwbGains
        val ccm = latestCcm?.let { t -> List(9) { t.getElement(it % 3, it / 3).toFloat() } }
        return CaptureFrame.RawInfo(
            cfa = st.cfa,
            blackLevel = st.black,
            whiteLevel = st.white,
            gainR = g?.red ?: 1f,
            gainG = g?.let { (it.greenEven + it.greenOdd) / 2f } ?: 1f,
            gainB = g?.blue ?: 1f,
            ccm = ccm,
        )
    }

    private suspend fun awaitSurfaceTexture(view: TextureView = textureView): SurfaceTexture =
        suspendCancellableCoroutine { cont ->
            val existing = view.surfaceTexture
            if (view.isAvailable && existing != null) {
                cont.resume(existing)
                return@suspendCancellableCoroutine
            }
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    view.surfaceTextureListener = null
                    if (cont.isActive) cont.resume(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(): CameraDevice = suspendCancellableCoroutine { cont ->
        openCameraById(cameraId, cont) { mainLost = true }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraById(
        id: String,
        cont: kotlinx.coroutines.CancellableContinuation<CameraDevice>,
        onLostAfterOpen: (() -> Unit)? = null,
    ) {
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                CameraResourceDelivery.deliver(cont, camera) { it.close() }
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카메라 연결 끊김"))
                else onLostAfterOpen?.invoke()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카메라 오류 $error"))
                else onLostAfterOpen?.invoke()
            }
        }, handler)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openStandaloneCamera(
        role: String,
        choice: CameraEnumerator.CameraChoice,
    ): StandaloneCamera {
        val rc = manager.getCameraCharacteristics(choice.openId)
        val rMap = rc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("스트림 설정 없음")
        val rSizes = rMap.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val rSize = rSizes.filter { maxOf(it.width, it.height) <= 4096 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: rSizes.minByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("YUV 출력 없음")
        val sReader = ImageReader.newInstance(rSize.width, rSize.height, ImageFormat.YUV_420_888, 2)
        val sDevice = suspendCancellableCoroutine<CameraDevice> { cont ->
            openCameraById(choice.openId, cont)
        }
        val aux = auxPreviewSurface?.takeIf { role == auxPreviewRole }
        val sSession = try {
            createSession(sDevice, listOfNotNull(sReader.surface, aux))
        } catch (e: Exception) {
            if (aux == null) throw e
            setupNotes += "독립 $role 보조 프리뷰 포함 세션 실패, 프리뷰 없이 재시도: ${e.message}"
            releaseAuxPreview()
            createSession(sDevice, listOf(sReader.surface))
        }
        if (aux != null && auxPreviewSurface != null) {
            // 보조 프리뷰: 자동 노출/초점으로 계속 흘린다. 측정 촬영은 별도 요청에 수동값을 싣는다.
            val pReq = sDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(aux)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            sSession.setRepeatingRequest(pReq.build(), null, handler)
        }
        return StandaloneCamera(role, choice, sDevice, sSession, sReader)
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(surfaces: List<Surface>): CameraCaptureSession =
        createSession(device!!, surfaces)

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    CameraResourceDelivery.deliver(cont, s) { it.close() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("세션 구성 실패"))
                }
            }, handler)
        }

    private fun applyStandaloneMeasurementControls(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        // 디코더는 모든 프레임에 같은 톤커브 역변환을 쓰므로 보조 화각도 같은 곡선으로 찍는다.
        applyToneControls(builder)
        applyShadingMapMode(builder)
        if (manualLocked) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            manualExposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            manualSensitivityIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualAwbGains?.let {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, it)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    private fun standaloneCallback(key: String) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            recordShading(key, result)
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                ?.let { lastPhysicalExposureNs[key] = it }
        }
    }

    /**
     * 논리 멀티카메라 동시 세션: 각 출력 스트림을 setPhysicalCameraId로
     * 초광각/표준/망원 물리 카메라에 라우팅한다 (API 28+).
     */
    @androidx.annotation.RequiresApi(28)
    private suspend fun createTriSession(
        tri: CameraEnumerator.TriSelection,
        previewSurface: Surface,
        auxPreview: Surface?,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        require(Build.VERSION.SDK_INT >= 28)
        val mainPid = tri.main.physicalId
        val outputs = ArrayList<OutputConfiguration>()
        outputs += OutputConfiguration(previewSurface).also {
            if (mainPid != null) it.setPhysicalCameraId(mainPid)
        }
        outputs += OutputConfiguration(reader!!.surface).also {
            if (mainPid != null) it.setPhysicalCameraId(mainPid)
        }
        for ((role, choice) in tri.roles()) {
            if (role == CameraEnumerator.ROLE_MAIN) continue
            val r = extraReaders[role] ?: continue
            outputs += OutputConfiguration(r.surface).also { out ->
                choice.physicalId?.let { out.setPhysicalCameraId(it) }
            }
            if (auxPreview != null && role == auxPreviewRole) {
                outputs += OutputConfiguration(auxPreview).also { out ->
                    choice.physicalId?.let { out.setPhysicalCameraId(it) }
                }
            }
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            Executor { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    CameraResourceDelivery.deliver(cont, s) { it.close() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("동시 3각 세션 구성 실패"))
                    }
                }
            },
        )
        try {
            device!!.createCaptureSession(config)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    fun close() {
        closeCameras()
        thread.quitSafely()
    }

    /** 카메라·세션·리더만 닫는다(스레드는 유지해 다시 start할 수 있게). */
    private fun closeCameras() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        standaloneCameras.values.forEach { runCatching { it.close() } }
        standaloneCameras.clear()
        sequentialRoles.clear()
        extraReaders.values.forEach { runCatching { it.close() } }
        extraReaders.clear()
        releaseAuxPreview()
        runCatching { previewSurface?.release() }
        previewSurface = null
        session = null
        device = null
        reader = null
    }

    private fun releaseAuxPreview() {
        runCatching { auxPreviewSurface?.release() }
        auxPreviewSurface = null
    }

    private fun StandaloneCamera.close() {
        runCatching { session.close() }
        runCatching { device.close() }
        runCatching { reader.close() }
    }

    private fun Long.coerceInRange(range: Range<Long>?): Long =
        if (range == null) this else coerceIn(range.lower, range.upper)

    private fun Int.coerceInRange(range: Range<Int>?): Int =
        if (range == null) this else coerceIn(range.lower, range.upper)
}
