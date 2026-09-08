package com.burnin.scanner.measure

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.hardware.camera2.CameraManager
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.burnin.scanner.R
import com.burnin.scanner.analysis.Analyzer
import com.burnin.scanner.analysis.DotGridDetector
import com.burnin.scanner.analysis.GrayImage
import com.burnin.scanner.analysis.Homography
import com.burnin.scanner.analysis.ImageOps
import com.burnin.scanner.analysis.RgbImage
import com.burnin.scanner.analysis.ScreenDetector
import com.burnin.scanner.camera.CameraEnumerator
import com.burnin.scanner.camera.CaptureFrameCodec
import com.burnin.scanner.camera.CaptureController
import com.burnin.scanner.camera.CaptureFrame
import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import com.burnin.scanner.net.Session
import com.burnin.scanner.report.ReportStore
import com.burnin.scanner.util.AppLog
import com.burnin.scanner.vl.VisionTrustGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 측정 → 보정맵 생성 → 반복 보정(수렴 기반) → 평가 → 리포트의 전체 워크플로우
 * (7.2/7.3, MVP 2 + MVP 4).
 *
 * 순서:
 *  1. gray70 표시 → AE 수렴 → AE/AWB 잠금 (세션 내 모든 촬영 동일 노출)
 *  2. gray70 촬영(평균) → 화면 사각형 검출 → 호모그래피 + 시작 기하 왜곡 진단
 *  3. black 촬영 → black offset + 잔여 미광 검증
 *  4. gray70 + gray25 휘도 그리드 + 목표값(하위 10퍼센타일)
 *  5. red70/green70/blue70 진단 + red30/green30/blue30 저휘도 채널 측정
 *  6. gray70/gray25/RGB30 혼합 초기 보정맵 생성·적용
 *  7. 반복 보정 루프 (M-FR-012): gray70/gray25/RGB30 재측정 → damping 갱신
 *  8. 최종 gray70/gray25/RGB30 평가·판정 (M-FR-017)
 *  9. 리포트 저장 (M-FR-014)
 */
class MeasurementActivity : Activity() {

    companion object {
        private const val FRAMES_PER_PATTERN = 5
        private const val LOW_LIGHT_PATTERN = "gray25"
        private const val LOW_LIGHT_FRAMES = 5
        private const val LOW_LIGHT_STRAY_WARN = 0.10f
        private const val LOW_LIGHT_GAIN_WEIGHT = 0.35f
        private const val LOW_LIGHT_WEAK_GAIN_WEIGHT = 0.12f
        private const val LOW_RGB_FRAMES = 5
        private const val LOW_RGB_GAIN_WEIGHT = 0.18f
        private const val LOW_RGB_WEAK_GAIN_WEIGHT = 0.06f
        private const val MAX_ATTENUATION = 0.10f
        private const val PASS_RMS = 0.015f      // 합격: 잔여 RMS ≤ 1.5%
        private const val PASS_P95 = 0.035f      // 합격: P95 편차 ≤ 3.5%
        private const val MAX_ITERATIONS = 24    // 품질 우선 반복 상한
        private const val STAGNATION_LIMIT = 4
        private const val DIVERGENCE_LIMIT = 3
        private const val DAMPING_ALPHA = 0.3f   // 반복 damping
        private const val DRIFT_LIMIT = 0.10f    // 중앙 휘도 드리프트 > 10%면 조건 변화로 무효
        private const val DISTORTION_WARN_SCORE = 0.18f
        private const val FLICKER_STABILITY_GRID_MAX_EDGE = 512
        private const val FLICKER_OK_RELATIVE_RANGE = 0.015f
        private const val FLICKER_ZERO_RELATIVE_RANGE = 0.045f
        private const val FLICKER_INTER_FRAME_DELAY_MS = 1_200L
        private const val VL_REGION_CONFIDENCE_THRESHOLD = 0.55f
        private const val VL_REGION_MIN_AREA = 96
        private const val VL_RESTORED_CONFIDENCE_FLOOR = 0.85f

        // 1200ms는 60Hz 주사 주기(16.67ms)의 정확히 72배라, 잔여 롤링 밴드가 매 프레임
        // 같은 위상(같은 위치)에 반복되어 평균화로 상쇄되지 않았다. 주기의 정수배가 아닌
        // 오프셋을 프레임마다 더해 밴드 위상을 흩뜨린다.
        private val FLICKER_PHASE_JITTER_MS = longArrayOf(0, 7, 23, 41, 11)
        private val RGB70_PATTERNS = listOf("red70", "green70", "blue70")
        private val RGB30_PATTERNS = listOf("red30", "green30", "blue30")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var capture: CaptureController? = null
    private lateinit var txtStatus: TextView
    private lateinit var txtResult: TextView
    private lateinit var btnStart: Button
    private lateinit var btnToggle: Button
    private lateinit var editRefresh: EditText
    private lateinit var chkTri: CheckBox
    private var triSelection: CameraEnumerator.TriSelection? = null
    private var measuring = false
    private var correctionOn = true
    private val resultLog = StringBuilder()
    private val visionTrustGateHolder = lazy { VisionTrustGate() }
    private val visionTrustGate by visionTrustGateHolder

    private data class GrayCapture(
        val average: GrayImage,
        val frameStore: CaptureFrameStore,
    )

    private data class RgbCapture(
        val average: RgbImage,
        val frameStore: CaptureFrameStore,
    )

    private data class CaptureFrameStore(
        val files: List<File>,
        val spanMs: Long,
    ) {
        fun delete() {
            files.forEach { file -> runCatching { file.delete() } }
        }
    }

    private data class RgbMeasurement(
        val stats: LinkedHashMap<String, Analyzer.Stats>,
        val grids: LinkedHashMap<String, FloatArray>,
        val heatmaps: LinkedHashMap<String, ByteArray>,
        val aggregateGain: FloatArray?,
        val confidences: LinkedHashMap<String, FloatArray>,
        val channelGains: LinkedHashMap<Int, FloatArray>,
        val meanRms: Float,
        val maxStrayRatio: Float,
        val signalValid: Boolean,
    )

    private data class CorrectionIterations(
        val bestMap: CorrectionMap,
        val bestRms: Float,
        val bestLowRms: Float,
        val bestLowRgbRms: Float,
        val invalid: Boolean,
        val rgb30Targets: LinkedHashMap<String, Float>,
        val grayRms: List<Float>,
        val lowLightRms: List<Float>,
        val lowRgbRms: List<Float>,
    )

    private data class FinalEvaluation(
        val lumaAfter: FloatArray?,
        val statsAfter: Analyzer.Stats?,
        val lowLumaAfter: FloatArray?,
        val lowStatsAfter: Analyzer.Stats?,
        val rgb30: RgbMeasurement,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        txtStatus = findViewById(R.id.txtStatus)
        txtResult = findViewById(R.id.txtResult)
        btnStart = findViewById(R.id.btnStart)
        btnToggle = findViewById(R.id.btnToggle)

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            scope.launch { runMeasurementSafely() }
        }
        btnToggle.setOnClickListener { toggleCorrection() }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("카메라 권한 없음 — 홈 화면에서 권한을 허용하세요")
            btnStart.isEnabled = false
            return
        }

        editRefresh = findViewById(R.id.editRefresh)
        chkTri = findViewById(R.id.chkTri)
        if (Session.screenRefreshRate >= 1f) editRefresh.setText(fmtRefresh(Session.screenRefreshRate))

        val cameraManager = getSystemService(CameraManager::class.java)
        val choices = CameraEnumerator.enumerate(cameraManager)
        val concurrentSets = CameraEnumerator.concurrentCameraSets(cameraManager)
        triSelection = if (Build.VERSION.SDK_INT >= 28) {
            CameraEnumerator.selectTriSet(choices, concurrentSets)
        } else {
            null
        }
        log(CameraEnumerator.report(choices, triSelection).trimEnd())
        if (concurrentSets.isNotEmpty()) {
            log("OS concurrent camera sets: ${concurrentSets.joinToString { set -> set.joinToString(prefix = "[", postfix = "]") }}")
        } else if (Build.VERSION.SDK_INT >= 30) {
            log("OS concurrent camera sets: 없음 — 공개 카메라 동시 오픈 보장 없음")
        }
        chkTri.isEnabled = triSelection != null
        chkTri.isChecked = triSelection != null
        chkTri.setOnCheckedChangeListener { _, _ -> if (!measuring) startCamera() }

        startCamera()
    }

    /** 선택 상태(3각 동시 on/off)에 맞춰 카메라 세션을 (재)시작한다. */
    private fun startCamera() {
        scope.launch {
            try {
                capture?.close()
                capture = null
                val c = CaptureController(this@MeasurementActivity, findViewById<TextureView>(R.id.preview))
                capture = c
                c.start(if (chkTri.isChecked) triSelection else null)
                status("카메라 준비 완료 — 정렬 확인 후 [측정 시작]")
                log(
                    "카메라: ${c.captureSize.width}x${c.captureSize.height} " +
                        "${c.captureFormatText}, ${c.hardwareLevelText()}"
                )
                if (chkTri.isChecked) {
                    log(
                        if (c.isTriActive) "동시 3각 세션 구성: ${c.triSummary()}"
                        else "동시 3각 구성 실패 → 메인 단독 폴백 (기기 HAL 제한)"
                    )
                }
                c.diagnosticNotes().forEach { log(it) }
            } catch (e: Exception) {
                capture?.close()
                capture = null
                if (e is CancellationException) throw e
                status("카메라 초기화 실패: ${e.message}")
                btnStart.isEnabled = false
            }
        }
    }

    private fun fmtRefresh(hz: Float): String =
        if (hz == Math.floor(hz.toDouble()).toFloat()) hz.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", hz)

    /** 사용자가 입력한 주사율(기본 60, HELLO 값 프리필). 셔터 양자화의 기준. */
    private fun inputRefreshHz(): Float {
        val v = editRefresh.text.toString().trim().toFloatOrNull()
        return when {
            v == null || v < 1f -> if (Session.screenRefreshRate >= 1f) Session.screenRefreshRate else 60f
            else -> v.coerceIn(24f, 480f)
        }
    }

    private fun status(msg: String) {
        txtStatus.text = msg
        AppLog.i(msg)
    }

    private fun log(msg: String) {
        resultLog.appendLine(msg)
        txtResult.text = resultLog.toString()
        AppLog.i(msg)
    }

    private suspend fun runMeasurementSafely() {
        measuring = true
        chkTri.isEnabled = false
        editRefresh.isEnabled = false
        val measurementClient = Session.client
        try {
            MeasurementSessionGuard.run(onFailure = {
                try {
                    withContext(Dispatchers.IO) { MeasurementCleanup.disable(measurementClient) }
                } catch (cleanup: MeasurementCleanup.Incomplete) {
                    log(MeasurementCleanup.WARNING)
                    status(MeasurementCleanup.WARNING)
                    android.widget.Toast.makeText(applicationContext,
                        MeasurementCleanup.WARNING, android.widget.Toast.LENGTH_LONG).show()
                    throw cleanup
                }
            }) { runMeasurement() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status(if (e.suppressed.any { it is MeasurementCleanup.Incomplete })
                MeasurementCleanup.WARNING else "측정 실패: ${e.message}")
            log("!! 중단: ${e.message}")
        } finally {
            btnStart.isEnabled = true
            measuring = false
            chkTri.isEnabled = triSelection != null
            editRefresh.isEnabled = true
        }
    }

    private suspend fun runMeasurement() {
        val client = Session.client ?: throw IllegalStateException("대상 기기 미연결")
        val cap = capture ?: throw IllegalStateException("카메라 미준비")
        val screenW = Session.screenWidth
        val screenH = Session.screenHeight
        require(screenW > 0 && screenH > 0) { "대상 화면 정보 없음" }
        resultLog.clear()
        log("대상: ${Session.targetName} (${screenW}x${screenH})")

        // ── 1. 기준 패턴 + 노출 잠금 ─────────────────────────────
        status("1/10 gray70 패턴 표시, 노출 수렴 중...")
        withContext(Dispatchers.IO) {
            client.command(Protocol.CMD_DISABLE_CORRECTION)
            client.command(Protocol.CMD_DISABLE_OVERLAY)
            client.showPattern("gray70")
        }
        delay(2500)
        val refreshHz = inputRefreshHz()
        val lockText = cap.lockMeasurementControls(refreshHz)
        log("캡처 고정: $lockText (입력 주사율 ${fmtRefresh(refreshHz)}Hz)")
        if (cap.isTriActive) log("동시 3각 촬영 활성: ${cap.triSummary()}")
        cap.diagnosticNotes().forEach { log(it) }
        delay(400)

        // ── 2. 기준 촬영 + 화면 검출 (활성 화각 전체 동시 캡처) ────
        status("2/10 기준 패턴 촬영 (${FRAMES_PER_PATTERN}장)...")
        val triGrayStores = captureTriStores(cap, FRAMES_PER_PATTERN)
        val mainGrayStore = triGrayStores.getValue(CameraEnumerator.ROLE_MAIN)
        val grayCapture = GrayCapture(averageGrayFromStore(mainGrayStore), mainGrayStore)
        val grayAvg = grayCapture.average
        val det = withContext(Dispatchers.Default) { ScreenDetector.detect(grayAvg) }
            ?: throw IllegalStateException("화면 검출 실패 — 차광 상태와 카메라 정렬을 확인하세요")
        log("화면 검출: ${det.quad}  (프레임 점유율 ${(det.areaRatio * 100).toInt()}%)")
        when {
            det.areaRatio < 0.25f ->
                log("경고: 화면이 작게 잡힘 → 측정 신뢰도 낮음. 카메라를 더 가까이 (권장 40~90%)")
            det.areaRatio > 0.95f ->
                log("경고: 화면이 프레임을 넘칠 수 있음. 카메라를 조금 멀리")
        }
        val geometry = Analyzer.geometryQuality(det.quad, screenW, screenH)
        log(
            "시작 왜곡/정렬 점수 ${fmt(geometry.score)} " +
                "(aspect ${fmt(geometry.aspectError)}, edge ${fmt(geometry.edgeBalance)}, " +
                "diag ${fmt(geometry.diagonalError)}, parallel ${fmt(geometry.parallelErrorDeg)}°)"
        )
        if (geometry.score > DISTORTION_WARN_SCORE) {
            log("경고: 시작 기하 왜곡이 큼 — 카메라를 화면과 더 평행하게 맞추면 고해상도 맵 품질이 좋아집니다")
        }
        val fallbackHomography = Analyzer.buildHomography(det.quad, screenW, screenH)
            ?: throw IllegalStateException("호모그래피 계산 실패")
        status("2/10 방향 마커 촬영, 화면 좌표 방향 판별...")
        var markerTriFrames: Map<String, CaptureFrame> = emptyMap()
        val markerOrientation = try {
            withContext(Dispatchers.IO) { client.showPattern("marker") }
            delay(800)
            val markerAvg = captureAveraged(cap)
            if (cap.isTriActive) {
                // 보조 화각도 방향 판별이 필요하다 (교차 그리드 180° 뒤집힘 방지)
                markerTriFrames = runCatching { cap.captureTriFrames() }.getOrDefault(emptyMap())
            }
            withContext(Dispatchers.Default) {
                Analyzer.buildHomographyWithMarker(det.quad, markerAvg, screenW, screenH)
            }
        } catch (e: Exception) {
            log("방향 마커 판별 건너뜀: ${e.message}")
            null
        }
        val cornerMapping = markerOrientation?.mapping
        val initialHomography = markerOrientation?.homography ?: fallbackHomography
        if (markerOrientation != null) {
            log(
                "방향 마커: ${markerOrientation.mapping.label}, " +
                    "score ${fmt(markerOrientation.markerScore)}, " +
                    "confidence ${pct(markerOrientation.confidence)}"
            )
            if (markerOrientation.confidence < 0.25f) {
                log("경고: 방향 마커 confidence 낮음 — 마커 패턴이 가려지거나 초점이 흐릴 수 있습니다")
            }
        } else {
            log("방향 마커 판별 실패 — 기본 코너 매핑으로 계속 진행")
        }
        status("2/10 dot-grid 기준점 촬영, 호모그래피 보강...")
        val dotGrid = try {
            withContext(Dispatchers.IO) { client.showPattern("dotgrid") }
            delay(800)
            val dotAvg = captureAveraged(cap)
            withContext(Dispatchers.Default) {
                DotGridDetector.refineHomography(dotAvg, initialHomography, screenW, screenH)
            }
        } catch (e: Exception) {
            log("dot-grid 보강 건너뜀: ${e.message}")
            null
        }
        val homography = dotGrid?.homography ?: initialHomography
        if (dotGrid != null) {
            log(
                "dot-grid 정합: ${dotGrid.matchedPoints}점, " +
                    "RMS ${fmt(dotGrid.rmsResidualPx)}px, max ${fmt(dotGrid.maxResidualPx)}px"
            )
            if (dotGrid.rmsResidualPx > 2.5f) {
                log("경고: dot-grid 잔차가 큼 — 렌즈 왜곡/초점/정렬 영향이 남아 있을 수 있습니다")
            }
        } else {
            log("dot-grid 정합 실패 — 4모서리 호모그래피로 계속 진행")
        }

        // ── 3. 블랙 오프셋 (활성 화각 전체 동시 캡처) ─────────────
        status("3/10 black 패턴 촬영 (오프셋/미광 검증)...")
        withContext(Dispatchers.IO) { client.showPattern("black") }
        delay(700)
        val triBlackStores = captureTriStores(cap, FRAMES_PER_PATTERN)
        val blackStore = triBlackStores.getValue(CameraEnumerator.ROLE_MAIN)
        val blackAvg = averageGrayFromStore(blackStore)
        val blackRgbAvg = averageRgbFromStore(blackStore)
        blackStore.delete()

        // ── 4. 휘도맵 + 목표값 ──────────────────────────────────
        status("4/10 gray70 휘도맵 계산 (기기 해상도 1:1)...")
        val gw = screenW
        val gh = screenH
        log("보정맵 해상도: ${gw}x${gh} (대상 기기 해상도 1:1)")
        val smoothRadius = mapSmoothRadius(det.quad, gw, gh)
        log("보정맵 스무딩 반경 ${smoothRadius}셀 — 카메라 해상 한계 이하 노이즈/무아레가 맵에 새겨지지 않도록 적응 조정")
        val lumaBeforeRaw = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(grayAvg, blackAvg, homography, screenW, screenH, gw, gh)
        }
        val blackFullGrid = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(blackAvg, null, homography, screenW, screenH, gw, gh)
        }
        val flatField = withContext(Dispatchers.Default) {
            Analyzer.radialFlatField(lumaBeforeRaw, gw, gh)
        }
        val edgeFalloff = withContext(Dispatchers.Default) {
            Analyzer.edgeFalloff(lumaBeforeRaw, gw, gh)
        }
        val lumaBefore = withContext(Dispatchers.Default) {
            Analyzer.applyFlatField(lumaBeforeRaw, flatField)
        }
        val baseConfidence70 = withContext(Dispatchers.Default) {
            Analyzer.confidenceGrid(lumaBeforeRaw, blackFullGrid, strayWarn = 0.05f)
        }
        val flickerConfidence70 = temporalLumaConfidence(
            grayCapture.frameStore, blackAvg, homography, screenW, screenH, gw, gh
        )
        val confidence70Single = withContext(Dispatchers.Default) {
            Analyzer.combineConfidence(baseConfidence70, flickerConfidence70)
        }

        // ── 3.5 동시 3각 교차 분석: 화각 간 불일치 구간을 보정에서 무시 ──
        val cross = analyzeCrossRoles(
            triGrayStores,
            triBlackStores,
            markerTriFrames,
            grayAvg,
            homography,
            lumaBefore,
            screenW,
            screenH,
            gw,
            gh,
        )
        val confidence70 = if (cross != null) {
            withContext(Dispatchers.Default) {
                Analyzer.combineConfidence(confidence70Single, cross.agreement)
            }
        } else {
            confidence70Single
        }
        if (cross != null) {
            cross.notes.forEach { log(it) }
            log(
                "3각 교차 일치도 평균 ${pct(cross.meanAgreement)} " +
                    "(${cross.roles.joinToString("+")}) — 불일치 구간은 gain 반영 제외"
            )
            cap.physicalExposureSummary(refreshHz)?.let { log("물리 카메라 노출: $it") }
        }
        val blackGrid = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(blackAvg, null, homography, screenW, screenH, 16, 16)
        }
        val blackStats = Analyzer.stats(blackGrid)
        val statsBefore = Analyzer.stats(lumaBefore)
        val target = statsBefore.p10
        val strayRatio = blackStats.median / statsBefore.median
        log(
            "카메라 flat-field 보정: radial max ${pct(Analyzer.flatFieldStrength(flatField))}, " +
                "raw edge falloff ${pct(edgeFalloff)}, " +
                "gray70 confidence 평균 ${pct(Analyzer.meanConfidence(confidence70))}, " +
                "프레임 안정도 ${pct(Analyzer.meanConfidence(flickerConfidence70))}, " +
                "flicker span ${fmtSeconds(grayCapture.frameStore.spanMs)}"
        )
        if (edgeFalloff > 0.08f) {
            log(
                "경고: 화면 가장자리가 중앙보다 ${pct(edgeFalloff)} 어둡게 촬영됨 — " +
                    "카메라를 화면과 평행하게 두고 조금 더 멀리서 1.5~2x 망원/기본 렌즈로 재측정 권장"
            )
        }
        if (strayRatio > 0.05f) {
            log("경고: 잔여 미광 ${(strayRatio * 100).toInt()}% — 박스 차광을 보완하세요")
        }
        log(
            "보정 전: RMS편차 ${pct(statsBefore.rmsDev)}, P95 ${pct(statsBefore.p95Dev)}, " +
                "최대 ${pct(statsBefore.maxDev)}"
        )

        // ── 5. 저휘도 맵 측정: gray25도 보정맵에 혼합 ─────────────
        status("5/10 $LOW_LIGHT_PATTERN 저휘도 촬영 (${LOW_LIGHT_FRAMES}장)...")
        val lowBeforeCapture = capturePatternGray(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
        val lowBeforeAvg = lowBeforeCapture.average
        val lumaLowBeforeRaw = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(lowBeforeAvg, blackAvg, homography, screenW, screenH, gw, gh)
        }
        val lumaLowBefore = withContext(Dispatchers.Default) {
            Analyzer.applyFlatField(lumaLowBeforeRaw, flatField)
        }
        val baseConfidenceLow = withContext(Dispatchers.Default) {
            Analyzer.confidenceGrid(lumaLowBeforeRaw, blackFullGrid, strayWarn = LOW_LIGHT_STRAY_WARN)
        }
        val flickerConfidenceLow = temporalLumaConfidence(
            lowBeforeCapture.frameStore, blackAvg, homography, screenW, screenH, gw, gh
        )
        val confidenceLow = withContext(Dispatchers.Default) {
            val single = Analyzer.combineConfidence(baseConfidenceLow, flickerConfidenceLow)
            if (cross == null) single else Analyzer.combineConfidence(single, cross.agreement)
        }
        val lowStatsBefore = Analyzer.stats(lumaLowBefore)
        val lowTarget = lowStatsBefore.p10
        val lowStrayRatio = blackStats.median / lowStatsBefore.median
        val lowSignalValid = lowStrayRatio <= LOW_LIGHT_STRAY_WARN
        val lowLightWeight = if (lowSignalValid) LOW_LIGHT_GAIN_WEIGHT else LOW_LIGHT_WEAK_GAIN_WEIGHT
        log(
            "$LOW_LIGHT_PATTERN 보정 전: RMS ${pct(lowStatsBefore.rmsDev)}, " +
                "P95 ${pct(lowStatsBefore.p95Dev)}, median ${fmt(lowStatsBefore.median)}, " +
                "black/$LOW_LIGHT_PATTERN ${pct(lowStrayRatio)}"
        )
        if (!lowSignalValid) {
            log(
                "경고: 저휘도 신호 부족(low_light_signal_weak) — " +
                    "$LOW_LIGHT_PATTERN 보정 반영 가중치를 ${pct(lowLightWeight)}로 낮춤"
            )
        } else {
            log("$LOW_LIGHT_PATTERN 보정 반영 가중치 ${pct(lowLightWeight)}")
        }
        log(
            "$LOW_LIGHT_PATTERN confidence 평균 ${pct(Analyzer.meanConfidence(confidenceLow))}, " +
                "프레임 안정도 ${pct(Analyzer.meanConfidence(flickerConfidenceLow))}, " +
                "flicker span ${fmtSeconds(lowBeforeCapture.frameStore.spanMs)}"
        )

        // ── 6. RGB 채널별 측정: 70% 진단 + 30% 보정 혼합 ─────────
        status("6/10 RGB 70%/30% 채널별 측정...")
        val rgb70 = try {
            measureRgbPatterns(
                client, cap, blackRgbAvg, homography, screenW, screenH, gw, gh,
                RGB70_PATTERNS, FRAMES_PER_PATTERN, "deviation_rgb70",
                flatField = flatField,
                makeHeatmaps = true,
                includeGain = false,
            )
        } catch (e: Exception) {
            log("RGB70 채널 측정 건너뜀: ${e.message}")
            emptyRgbMeasurement()
        }
        val rgb30Measured = try {
            measureRgbPatterns(
                client, cap, blackRgbAvg, homography, screenW, screenH, gw, gh,
                RGB30_PATTERNS, LOW_RGB_FRAMES, "deviation_rgb30_before",
                flatField = flatField,
                makeHeatmaps = true,
                includeGain = true,
                smoothRadius = smoothRadius,
            )
        } catch (e: Exception) {
            log("RGB30 채널 측정 건너뜀: ${e.message}")
            emptyRgbMeasurement()
        }
        val rgb30 = rgb30Measured.copy(
            channelGains = rgb30Measured.channelGains.mapValuesTo(LinkedHashMap()) { (_, gain) ->
                Analyzer.limitGainByConfidence(gain, confidence70, MAX_ATTENUATION)
            },
            aggregateGain = rgb30Measured.aggregateGain?.let { Analyzer.limitGainByConfidence(it, confidence70, MAX_ATTENUATION) },
        )
        val lowRgbWeight = when {
            rgb30.stats.isEmpty() -> 0f
            rgb30.signalValid -> LOW_RGB_GAIN_WEIGHT
            else -> LOW_RGB_WEAK_GAIN_WEIGHT
        }
        if (rgb70.stats.isNotEmpty()) {
            log(
                "RGB70 RMS — R ${pct(rgb70.stats["red70"]!!.rmsDev)}, " +
                    "G ${pct(rgb70.stats["green70"]!!.rmsDev)}, B ${pct(rgb70.stats["blue70"]!!.rmsDev)}"
            )
        }
        if (rgb30.stats.isNotEmpty()) {
            log(
                "RGB30 RMS — R ${pct(rgb30.stats["red30"]!!.rmsDev)}, " +
                    "G ${pct(rgb30.stats["green30"]!!.rmsDev)}, B ${pct(rgb30.stats["blue30"]!!.rmsDev)}, " +
                    "black/RGB30 max ${pct(rgb30.maxStrayRatio)}"
            )
            if (!rgb30.signalValid) {
                log("경고: RGB30 신호 부족 — RGB30 보정 반영 가중치를 ${pct(lowRgbWeight)}로 낮춤")
            } else {
                log("RGB30 보정 반영 가중치 ${pct(lowRgbWeight)}")
            }
        }

        // ── 7~8. 초기 보정맵 + 반복 보정 루프 ────────────────────
        val correction = runCorrectionIterations(
            client = client,
            cap = cap,
            screenW = screenW,
            screenH = screenH,
            gw = gw,
            gh = gh,
            lumaBefore = lumaBefore,
            lumaLowBefore = lumaLowBefore,
            confidence70 = confidence70,
            confidenceLow = confidenceLow,
            lowLightWeight = lowLightWeight,
            rgb30 = rgb30,
            lowRgbWeight = lowRgbWeight,
            blackAvg = blackAvg,
            blackRgbAvg = blackRgbAvg,
            blackFullGrid = blackFullGrid,
            homography = homography,
            det = det,
            cornerMapping = cornerMapping,
            statsBefore = statsBefore,
            target = target,
            lowTarget = lowTarget,
            flatField = flatField,
            smoothRadius = smoothRadius,
        )
        val bestMap = correction.bestMap
        val bestGain = bestMap.gain
        val bestRms = correction.bestRms
        val bestLowRms = correction.bestLowRms
        val bestLowRgbRms = correction.bestLowRgbRms
        val invalid = correction.invalid
        val rgb30Targets = correction.rgb30Targets
        val iterRmsList = correction.grayRms
        val iterLowRmsList = correction.lowLightRms
        val iterLowRgbRmsList = correction.lowRgbRms

        // ── 8. 최종 평가·판정 ───────────────────────────────────
        val finalEval = runFinalEvaluation(
            invalid = invalid,
            client = client,
            cap = cap,
            blackAvg = blackAvg,
            blackRgbAvg = blackRgbAvg,
            referenceQuad = det.quad,
            cornerMapping = cornerMapping,
            screenW = screenW,
            screenH = screenH,
            gw = gw,
            gh = gh,
            flatField = flatField,
            rgb30Targets = rgb30Targets,
        )
        val finalLumaAfter = finalEval.lumaAfter
        val finalStatsAfter = finalEval.statsAfter
        val finalLowLumaAfter = finalEval.lowLumaAfter
        val finalLowStatsAfter = finalEval.lowStatsAfter
        val finalRgb30 = finalEval.rgb30

        val finalRms = finalStatsAfter?.rmsDev
            ?: if (bestRms == Float.MAX_VALUE) statsBefore.rmsDev else bestRms
        val finalLowRms = finalLowStatsAfter?.rmsDev
            ?: if (bestLowRms == Float.MAX_VALUE) lowStatsBefore.rmsDev else bestLowRms
        val finalP95 = finalStatsAfter?.p95Dev ?: statsBefore.p95Dev
        val finalLowP95 = finalLowStatsAfter?.p95Dev ?: lowStatsBefore.p95Dev
        val finalLowRgbRms = when {
            finalRgb30.stats.isNotEmpty() -> finalRgb30.meanRms
            bestLowRgbRms != Float.MAX_VALUE -> bestLowRgbRms
            else -> rgb30.meanRms
        }
        val finalGray70Passed = finalStatsAfter?.let { uniformityPassed(it) } == true
        val finalLowPassed = finalLowStatsAfter?.let { uniformityPassed(it) } == true
        val finalUniformityPassed = finalGray70Passed && finalLowPassed
        val improvement = MeasurementPolicy.improvement(statsBefore.rmsDev, finalRms)
        val lowImprovement = MeasurementPolicy.improvement(lowStatsBefore.rmsDev, finalLowRms)
        val rgb30Improvement = if (rgb30.meanRms > 0f) {
            1.0 - finalLowRgbRms.toDouble() / rgb30.meanRms.toDouble()
        } else {
            0.0
        }
        val brightnessLoss = (1f - mean(bestGain)).toDouble()
        val verdict = when {
            invalid -> "평가 무효(측정 조건 변화)"
            finalUniformityPassed -> "합격"
            else -> "미달(측정 환경 개선 또는 심한 번인)"
        }
        log("──────────────")
        log(
            "반복 ${iterRmsList.size}회, 최종 gray70 RMS ${pct(finalRms)} / P95 ${pct(finalP95)} " +
                "(보정 전 RMS ${pct(statsBefore.rmsDev)})"
        )
        log(
            "$LOW_LIGHT_PATTERN 보정 후 RMS ${pct(finalLowRms)} / P95 ${pct(finalLowP95)} " +
                "(보정 전 RMS ${pct(lowStatsBefore.rmsDev)}, 개선율 ${pct(lowImprovement.toFloat())})"
        )
        if (rgb30.stats.isNotEmpty()) {
            log(
                "RGB30 보정 후 평균 RMS ${pct(finalLowRgbRms)} " +
                    "(보정 전 ${pct(rgb30.meanRms)}, 개선율 ${pct(rgb30Improvement.toFloat())})"
            )
        }
        log("개선율 ${pct(improvement.toFloat())}, 평균 밝기 손실 ${pct(brightnessLoss.toFloat())}")
        log("판정: $verdict (gray70/$LOW_LIGHT_PATTERN 모두 RMS ≤ ${pct(PASS_RMS)}, P95 ≤ ${pct(PASS_P95)})")

        // ── 9. 리포트 저장 ──────────────────────────────────────
        status("10/10 리포트 저장...")
        val dir = saveMeasurementReport(
            screenW = screenW,
            screenH = screenH,
            gw = gw,
            gh = gh,
            det = det,
            geometry = geometry,
            markerOrientation = markerOrientation,
            dotGrid = dotGrid,
            flatField = flatField,
            edgeFalloff = edgeFalloff,
            confidence70 = confidence70,
            flickerConfidence70 = flickerConfidence70,
            grayCapture = grayCapture,
            confidenceLow = confidenceLow,
            flickerConfidenceLow = flickerConfidenceLow,
            lowBeforeCapture = lowBeforeCapture,
            strayRatio = strayRatio,
            cap = cap,
            refreshHz = refreshHz,
            cross = cross,
            statsBefore = statsBefore,
            finalRms = finalRms,
            finalP95 = finalP95,
            iterRmsList = iterRmsList,
            iterLowRmsList = iterLowRmsList,
            iterLowRgbRmsList = iterLowRgbRmsList,
            improvement = improvement,
            finalGray70Passed = finalGray70Passed,
            lowStatsBefore = lowStatsBefore,
            finalLowRms = finalLowRms,
            finalLowP95 = finalLowP95,
            lowImprovement = lowImprovement,
            lowStrayRatio = lowStrayRatio,
            lowSignalValid = lowSignalValid,
            lowLightWeight = lowLightWeight,
            finalLowPassed = finalLowPassed,
            rgb30 = rgb30,
            finalLowRgbRms = finalLowRgbRms,
            rgb30Improvement = rgb30Improvement,
            finalRgb30 = finalRgb30,
            rgb70 = rgb70,
            brightnessLoss = brightnessLoss,
            bestMap = bestMap,
            finalUniformityPassed = finalUniformityPassed,
            invalid = invalid,
            lumaBefore = lumaBefore,
            finalLumaAfter = finalLumaAfter,
            lumaLowBefore = lumaLowBefore,
            finalLowLumaAfter = finalLowLumaAfter,
            grayAvg = grayAvg,
        )
        log("리포트 저장: ${dir.absolutePath}")
        status("측정 완료 — $verdict, 개선율 ${pct(improvement.toFloat())}")
        btnToggle.isEnabled = true
        correctionOn = true
    }

    private suspend fun runCorrectionIterations(
        client: ControlClient,
        cap: CaptureController,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        lumaBefore: FloatArray,
        lumaLowBefore: FloatArray,
        confidence70: FloatArray,
        confidenceLow: FloatArray,
        lowLightWeight: Float,
        rgb30: RgbMeasurement,
        lowRgbWeight: Float,
        blackAvg: GrayImage,
        blackRgbAvg: RgbImage,
        blackFullGrid: FloatArray,
        homography: Homography,
        det: ScreenDetector.Result,
        cornerMapping: Analyzer.CornerMapping?,
        statsBefore: Analyzer.Stats,
        target: Float,
        lowTarget: Float,
        flatField: FloatArray,
        smoothRadius: Int,
    ): CorrectionIterations {
        val gray70Gain = withContext(Dispatchers.Default) {
            Analyzer.gainGrid(lumaBefore, gw, gh, MAX_ATTENUATION, confidence70, smoothRadius)
        }
        val lowLightGain = withContext(Dispatchers.Default) {
            Analyzer.gainGrid(lumaLowBefore, gw, gh, MAX_ATTENUATION, confidenceLow, smoothRadius)
        }
        val grayMixedGain = withContext(Dispatchers.Default) {
            Analyzer.mixGainGrids(gray70Gain, lowLightGain, lowLightWeight, MAX_ATTENUATION)
        }
        var gain = rgb30.aggregateGain?.let { rgb30Gain ->
            withContext(Dispatchers.Default) {
                Analyzer.mixGainGrids(grayMixedGain, rgb30Gain, lowRgbWeight, MAX_ATTENUATION)
            }
        } ?: grayMixedGain
        gain = Analyzer.limitGainByConfidence(gain, confidence70, MAX_ATTENUATION)
        val baselineLowStats = Analyzer.stats(lumaLowBefore)
        val baselineScore = maxOf(uniformityScore(statsBefore), uniformityScore(baselineLowStats)) * (1f - lowRgbWeight) +
            (if (rgb30.stats.isNotEmpty()) rgb30.meanRms / PASS_RMS else 0f) * lowRgbWeight
        val selection = MeasurementPolicy.Selection(FloatArray(gain.size) { 1f }, baselineScore)
        var bestRms = statsBefore.rmsDev
        var bestLowRms = baselineLowStats.rmsDev
        var bestLowRgbRms = rgb30.meanRms
        var prevScore: Float? = null
        var divergeCount = 0
        var stalledCount = 0
        var invalid = false
        var lastAppliedIsBest = true
        val iterRmsList = ArrayList<Float>()
        val iterLowRmsList = ArrayList<Float>()
        val iterLowRgbRmsList = ArrayList<Float>()
        val rgb30Targets = LinkedHashMap<String, Float>().apply {
            rgb30.stats.forEach { (pattern, st) -> put(pattern, st.p10) }
        }

        withContext(Dispatchers.IO) { client.showPattern("gray70") }
        var iter = 0
        while (iter < MAX_ITERATIONS) {
            iter++
            status("7/10 반복 $iter/$MAX_ITERATIONS — 보정맵 전송·적용...")
            applyGainMap(client, CorrectionMap(gain, rgb30.channelGains, lowRgbWeight), gw, gh, screenW, screenH)
            lastAppliedIsBest = false

            status("8/10 반복 $iter/$MAX_ITERATIONS — gray70/$LOW_LIGHT_PATTERN/RGB30 재촬영·평가...")
            delay(900)
            val afterCapture = captureGray(cap)
            val afterAvg = afterCapture.average
            val detAfter = withContext(Dispatchers.Default) { ScreenDetector.detect(afterAvg) }
            checkNotNull(detAfter) { "반복 화면 검출 실패 — 재측정 필요" }
            check(MeasurementPolicy.geometryStable(det.quad, detAfter.quad)) { "화면 이동 — black/flat-field 재측정 필요" }
            val hAfter = checkNotNull(Analyzer.buildHomography(detAfter.quad, screenW, screenH, cornerMapping))
            val lumaAfterRaw = withContext(Dispatchers.Default) {
                Analyzer.lumaGrid(afterAvg, blackAvg, hAfter, screenW, screenH, gw, gh)
            }
            val lumaAfter = withContext(Dispatchers.Default) {
                Analyzer.applyFlatField(lumaAfterRaw, flatField)
            }
            val baseConfidenceAfter = withContext(Dispatchers.Default) {
                Analyzer.confidenceGrid(lumaAfterRaw, blackFullGrid, strayWarn = 0.05f)
            }
            val flickerConfidenceAfter = temporalLumaConfidence(
                afterCapture.frameStore, blackAvg, hAfter, screenW, screenH, gw, gh
            )
            val confidenceAfter = withContext(Dispatchers.Default) {
                Analyzer.combineConfidence(baseConfidenceAfter, flickerConfidenceAfter)
            }
            val st = Analyzer.stats(lumaAfter)
            val lowAfterCapture = capturePatternGray(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
            val lowAfterAvg = lowAfterCapture.average
            val lumaLowAfterRaw = withContext(Dispatchers.Default) {
                Analyzer.lumaGrid(lowAfterAvg, blackAvg, hAfter, screenW, screenH, gw, gh)
            }
            val lumaLowAfter = withContext(Dispatchers.Default) {
                Analyzer.applyFlatField(lumaLowAfterRaw, flatField)
            }
            val baseConfidenceLowAfter = withContext(Dispatchers.Default) {
                Analyzer.confidenceGrid(lumaLowAfterRaw, blackFullGrid, strayWarn = LOW_LIGHT_STRAY_WARN)
            }
            val flickerConfidenceLowAfter = temporalLumaConfidence(
                lowAfterCapture.frameStore, blackAvg, hAfter, screenW, screenH, gw, gh
            )
            val confidenceLowAfter = withContext(Dispatchers.Default) {
                Analyzer.combineConfidence(baseConfidenceLowAfter, flickerConfidenceLowAfter)
            }
            val lowSt = Analyzer.stats(lumaLowAfter)
            val rgb30After = if (rgb30Targets.isNotEmpty()) {
                measureRgbPatterns(
                    client, cap, blackRgbAvg, hAfter, screenW, screenH, gw, gh,
                    RGB30_PATTERNS, LOW_RGB_FRAMES, "iteration",
                    flatField = flatField,
                    makeHeatmaps = false,
                    includeGain = false,
                    keepGrids = true,
                )
            } else {
                emptyRgbMeasurement()
            }
            val rgb30AfterMeanRms = rgb30After.meanRms
            val gray70Passed = uniformityPassed(st)
            val lowLightPassed = uniformityPassed(lowSt)
            val bothGrayPassed = gray70Passed && lowLightPassed
            val grayScore = maxOf(uniformityScore(st), uniformityScore(lowSt))
            val rgbScore = if (rgb30After.stats.isNotEmpty()) {
                rgb30AfterMeanRms / PASS_RMS
            } else {
                0f
            }
            val compositeScore = grayScore * (1f - lowRgbWeight) + rgbScore * lowRgbWeight

            val loss = 1f - mean(gain)
            val drift = st.median / (statsBefore.median * (1f - loss)) - 1f
            if (Math.abs(drift) > DRIFT_LIMIT) {
                invalid = true
                log(
                    "반복 $iter: 중앙 휘도 드리프트 ${pct(drift)} — 반사/이동/노출 변화 의심. " +
                        "반복 중단 (암실 박스 고정 후 재측정 권장)"
                )
                break
            }

            iterRmsList += st.rmsDev
            iterLowRmsList += lowSt.rmsDev
            if (rgb30After.stats.isNotEmpty()) iterLowRgbRmsList += rgb30AfterMeanRms
            log(
                "반복 $iter: gray70 RMS ${pct(st.rmsDev)} / P95 ${pct(st.p95Dev)} " +
                    "${if (gray70Passed) "통과" else "개선 필요"}, " +
                    "$LOW_LIGHT_PATTERN RMS ${pct(lowSt.rmsDev)} / P95 ${pct(lowSt.p95Dev)} " +
                    "${if (lowLightPassed) "통과" else "개선 필요"}, " +
                    "RGB30 평균 RMS ${pct(rgb30AfterMeanRms)}, 균일도 점수 ${fmt(compositeScore)}x"
            )

            if (selection.consider(gain, compositeScore)) {
                bestRms = st.rmsDev
                bestLowRms = lowSt.rmsDev
                bestLowRgbRms = rgb30AfterMeanRms
                lastAppliedIsBest = true
            }

            if (bothGrayPassed) {
                log(
                    "gray70/$LOW_LIGHT_PATTERN 균일도 기준 도달 — " +
                        "RMS ≤ ${pct(PASS_RMS)}, P95 ≤ ${pct(PASS_P95)}"
                )
                break
            }

            var stopAfterThisEvaluation = false
            val lastScore = prevScore
            if (lastScore != null) {
                val stopDelta = 0.01f + compositeScore * 0.01f
                divergeCount = if (compositeScore > lastScore + stopDelta) divergeCount + 1 else 0
                stalledCount = if (lastScore - compositeScore < stopDelta) stalledCount + 1 else 0
                if (divergeCount >= DIVERGENCE_LIMIT) {
                    log("균일도 점수 증가 ${DIVERGENCE_LIMIT}회 감지 — 최적 맵으로 롤백")
                    stopAfterThisEvaluation = true
                }
                if (stalledCount >= STAGNATION_LIMIT) {
                    log("추가 개선 정체 ${STAGNATION_LIMIT}회 — 현재 측정 조건에서 최적 맵으로 평가 진행")
                    stopAfterThisEvaluation = true
                }
            }
            prevScore = compositeScore
            if (stopAfterThisEvaluation) break

            val refined70 = withContext(Dispatchers.Default) {
                Analyzer.refineGain(
                    gain, lumaAfter, target, DAMPING_ALPHA, gw, gh, MAX_ATTENUATION,
                    confidenceAfter, smoothRadius
                )
            }
            val refined30 = withContext(Dispatchers.Default) {
                Analyzer.refineGain(
                    gain, lumaLowAfter, lowTarget, DAMPING_ALPHA, gw, gh, MAX_ATTENUATION,
                    confidenceLowAfter, smoothRadius
                )
            }
            val refinedRgb30 = refineRgbGain(gain, rgb30After, rgb30Targets, gw, gh, smoothRadius)
            val refinedGray = withContext(Dispatchers.Default) {
                Analyzer.mixGainGrids(refined70, refined30, lowLightWeight, MAX_ATTENUATION)
            }
            gain = refinedRgb30?.let { rgbGain ->
                withContext(Dispatchers.Default) {
                    Analyzer.mixGainGrids(refinedGray, rgbGain, lowRgbWeight, MAX_ATTENUATION)
                }
            } ?: refinedGray
            gain = Analyzer.limitGainByConfidence(gain, confidence70, MAX_ATTENUATION)
        }

        val bestMap = selection.correctionMap(rgb30.channelGains, lowRgbWeight)
        if (!lastAppliedIsBest) {
            status("9/10 최적 반복 맵으로 롤백 적용...")
            applyGainMap(client, bestMap, gw, gh, screenW, screenH)
        }

        return CorrectionIterations(
            bestMap = bestMap,
            bestRms = bestRms,
            bestLowRms = bestLowRms,
            bestLowRgbRms = bestLowRgbRms,
            invalid = invalid,
            rgb30Targets = rgb30Targets,
            grayRms = iterRmsList,
            lowLightRms = iterLowRmsList,
            lowRgbRms = iterLowRgbRmsList,
        )
    }

    private suspend fun runFinalEvaluation(
        invalid: Boolean,
        client: ControlClient,
        cap: CaptureController,
        blackAvg: GrayImage,
        blackRgbAvg: RgbImage,
        referenceQuad: ScreenDetector.Quad,
        cornerMapping: Analyzer.CornerMapping?,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        flatField: FloatArray,
        rgb30Targets: Map<String, Float>,
    ): FinalEvaluation {
        if (invalid) {
            return FinalEvaluation(
                lumaAfter = null,
                statsAfter = null,
                lowLumaAfter = null,
                lowStatsAfter = null,
                rgb30 = emptyRgbMeasurement(),
            )
        }

        status("9/10 최종 gray70/$LOW_LIGHT_PATTERN/RGB30 촬영·평가...")
        withContext(Dispatchers.IO) { client.showPattern("gray70") }
        delay(900)
        val finalAvg = captureAveraged(cap)
        val detFinal = withContext(Dispatchers.Default) { ScreenDetector.detect(finalAvg) }
        checkNotNull(detFinal) { "최종 화면 검출 실패 — 평가 무효" }
        check(MeasurementPolicy.geometryStable(referenceQuad, detFinal.quad)) { "최종 화면 이동 — black/flat-field 재측정 필요" }
        val hFinal = checkNotNull(Analyzer.buildHomography(detFinal.quad, screenW, screenH, cornerMapping)) { "최종 좌표 정합 실패" }
        val finalGridRaw = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(finalAvg, blackAvg, hFinal, screenW, screenH, gw, gh)
        }
        val finalGrid = withContext(Dispatchers.Default) {
            Analyzer.applyFlatField(finalGridRaw, flatField)
        }

        val lowFinalAvg = capturePatternAveraged(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
        val finalLowGridRaw = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(lowFinalAvg, blackAvg, hFinal, screenW, screenH, gw, gh)
        }
        val finalLowGrid = withContext(Dispatchers.Default) {
            Analyzer.applyFlatField(finalLowGridRaw, flatField)
        }

        val finalRgb30 = if (rgb30Targets.isNotEmpty()) {
            measureRgbPatterns(
                client, cap, blackRgbAvg, hFinal, screenW, screenH, gw, gh,
                RGB30_PATTERNS, LOW_RGB_FRAMES, "deviation_rgb30_after",
                flatField = flatField,
                makeHeatmaps = true,
                includeGain = false,
            )
        } else {
            emptyRgbMeasurement()
        }
        withContext(Dispatchers.IO) { client.showPattern("gray70") }

        return FinalEvaluation(
            lumaAfter = finalGrid,
            statsAfter = Analyzer.stats(finalGrid),
            lowLumaAfter = finalLowGrid,
            lowStatsAfter = Analyzer.stats(finalLowGrid),
            rgb30 = finalRgb30,
        )
    }

    private suspend fun saveMeasurementReport(
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        det: ScreenDetector.Result,
        geometry: Analyzer.GeometryQuality,
        markerOrientation: Analyzer.OrientedHomography?,
        dotGrid: DotGridDetector.Result?,
        flatField: FloatArray,
        edgeFalloff: Float,
        confidence70: FloatArray,
        flickerConfidence70: FloatArray,
        grayCapture: GrayCapture,
        confidenceLow: FloatArray,
        flickerConfidenceLow: FloatArray,
        lowBeforeCapture: GrayCapture,
        strayRatio: Float,
        cap: CaptureController,
        refreshHz: Float,
        cross: CrossResult?,
        statsBefore: Analyzer.Stats,
        finalRms: Float,
        finalP95: Float,
        iterRmsList: List<Float>,
        iterLowRmsList: List<Float>,
        iterLowRgbRmsList: List<Float>,
        improvement: Double,
        finalGray70Passed: Boolean,
        lowStatsBefore: Analyzer.Stats,
        finalLowRms: Float,
        finalLowP95: Float,
        lowImprovement: Double,
        lowStrayRatio: Float,
        lowSignalValid: Boolean,
        lowLightWeight: Float,
        finalLowPassed: Boolean,
        rgb30: RgbMeasurement,
        finalLowRgbRms: Float,
        rgb30Improvement: Double,
        finalRgb30: RgbMeasurement,
        rgb70: RgbMeasurement,
        brightnessLoss: Double,
        bestMap: CorrectionMap,
        finalUniformityPassed: Boolean,
        invalid: Boolean,
        lumaBefore: FloatArray,
        finalLumaAfter: FloatArray?,
        lumaLowBefore: FloatArray,
        finalLowLumaAfter: FloatArray?,
        grayAvg: GrayImage,
    ): File {
        val bestPng = withContext(Dispatchers.Default) {
            bestMap.alphaPng(gw, gh, screenW, screenH, MAX_ATTENUATION)
        }
        val bestRgbPng = withContext(Dispatchers.Default) {
            bestMap.rgbPng(gw, gh, screenW, screenH, MAX_ATTENUATION)
        }
        val lowIterations = floatListJson(iterLowRmsList)
        val rgb70Json = statsMapJson(rgb70.stats)
        val report = JSONObject()
            .put("reportVersion", 2)
            .put(
                "createdAt",
                java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssZ",
                    java.util.Locale.US,
                ).format(java.util.Date()),
            )
            .put("targetDevice", Session.targetName)
            .put("scannerDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("screenResolution", "${screenW}x${screenH}")
            .put("analysisGrid", "${gw}x${gh}")
            .put("nativeResolutionCorrectionMap", gw == screenW && gh == screenH)
            .put("frameAreaRatio", det.areaRatio.toDouble())
            .put("geometryScore", geometry.score.toDouble())
            .put("geometryAspectError", geometry.aspectError.toDouble())
            .put("geometryEdgeBalance", geometry.edgeBalance.toDouble())
            .put("geometryDiagonalError", geometry.diagonalError.toDouble())
            .put("geometryParallelErrorDeg", geometry.parallelErrorDeg.toDouble())
            .put("markerOrientation", markerOrientation?.mapping?.label ?: "fallback")
            .put("markerOrientationScore", (markerOrientation?.markerScore ?: 0f).toDouble())
            .put("markerOrientationConfidence", (markerOrientation?.confidence ?: 0f).toDouble())
            .put("dotGridMatchedPoints", dotGrid?.matchedPoints ?: 0)
            .put("dotGridRmsResidualPx", (dotGrid?.rmsResidualPx ?: 0f).toDouble())
            .put("dotGridMaxResidualPx", (dotGrid?.maxResidualPx ?: 0f).toDouble())
            .put("flatFieldRadialMaxDeviation", Analyzer.flatFieldStrength(flatField).toDouble())
            .put("rawEdgeFalloff", edgeFalloff.toDouble())
            .put("gray70ConfidenceMean", Analyzer.meanConfidence(confidence70).toDouble())
            .put("gray70FrameStabilityMean", Analyzer.meanConfidence(flickerConfidence70).toDouble())
            .put("gray70FlickerFrameSpanSeconds", grayCapture.frameStore.spanMs / 1000.0)
            .put("lowLightConfidenceMean", Analyzer.meanConfidence(confidenceLow).toDouble())
            .put("lowLightFrameStabilityMean", Analyzer.meanConfidence(flickerConfidenceLow).toDouble())
            .put("lowLightFlickerFrameSpanSeconds", lowBeforeCapture.frameStore.spanMs / 1000.0)
            .put("strayLightRatio", strayRatio.toDouble())
            .put("cameraSetup", cap.triSummary())
            .put("simultaneousTriCapture", cap.isTriActive)
            .put("refreshRateUsedHz", refreshHz.toDouble())
            .put("crossAgreementMean", (cross?.meanAgreement ?: 1f).toDouble())
            .put("crossRoles", JSONArray(cross?.roles ?: emptyList<String>()))
            .put("crossRoleStats", statsMapJson(cross?.roleStats ?: emptyMap()))
            .put("vlTrustGate", cross?.vlDecisions?.isNotEmpty() == true)
            .put("vlTrustDecisions", vlDecisionJson(cross?.vlDecisions ?: emptyList()))
            .put("physicalExposures", cap.physicalExposureSummary(refreshHz) ?: "")
            .put("rmsBefore", statsBefore.rmsDev.toDouble())
            .put("p95Before", statsBefore.p95Dev.toDouble())
            .put("maxBefore", statsBefore.maxDev.toDouble())
            .put("rmsAfterBest", finalRms.toDouble())
            .put("p95AfterBest", finalP95.toDouble())
            .put("iterationRms", floatListJson(iterRmsList))
            .put("iterationLowLightRms", lowIterations)
            .put("iterationGray30Rms", lowIterations)
            .put("iterationRgb30Rms", floatListJson(iterLowRgbRmsList))
            .put("iterationCount", iterRmsList.size)
            .put("improvementRatio", improvement)
            .put("passRmsThreshold", PASS_RMS.toDouble())
            .put("passP95Threshold", PASS_P95.toDouble())
            .put("gray70UniformityPassed", finalGray70Passed)
            .put("lowLightPattern", LOW_LIGHT_PATTERN)
            .put("lowLightRmsBefore", lowStatsBefore.rmsDev.toDouble())
            .put("lowLightRmsAfter", finalLowRms.toDouble())
            .put("lowLightP95Before", lowStatsBefore.p95Dev.toDouble())
            .put("lowLightP95After", finalLowP95.toDouble())
            .put("lowLightImprovementRatio", lowImprovement)
            .put("lowLightStrayRatio", lowStrayRatio.toDouble())
            .put("lowLightSignalValid", lowSignalValid)
            .put("lowLightGainWeight", lowLightWeight.toDouble())
            .put("lowLightUniformityPassed", finalLowPassed)
            .put("gray25RmsBefore", lowStatsBefore.rmsDev.toDouble())
            .put("gray25RmsAfter", finalLowRms.toDouble())
            .put("gray25P95Before", lowStatsBefore.p95Dev.toDouble())
            .put("gray25P95After", finalLowP95.toDouble())
            .put("gray25ImprovementRatio", lowImprovement)
            .put("gray25StrayRatio", lowStrayRatio.toDouble())
            .put("gray25SignalValid", lowSignalValid)
            .put("gray25GainWeight", lowLightWeight.toDouble())
            .put("gray30RmsBefore", lowStatsBefore.rmsDev.toDouble())
            .put("gray30RmsAfter", finalLowRms.toDouble())
            .put("gray30P95Before", lowStatsBefore.p95Dev.toDouble())
            .put("gray30P95After", finalLowP95.toDouble())
            .put("gray30ImprovementRatio", lowImprovement)
            .put("gray30StrayRatio", lowStrayRatio.toDouble())
            .put("gray30SignalValid", lowSignalValid)
            .put("gray30GainWeight", lowLightWeight.toDouble())
            .put("rgb30MeanRmsBefore", rgb30.meanRms.toDouble())
            .put("rgb30MeanRmsAfter", finalLowRgbRms.toDouble())
            .put("rgb30ImprovementRatio", rgb30Improvement)
            .put("rgb30StrayRatio", rgb30.maxStrayRatio.toDouble())
            .put("rgb30SignalValid", rgb30.signalValid)
            .put("rgb30GainWeight", bestMap.channelWeight.toDouble())
            .put("baselineSelected", bestMap.isBaseline)
            .put("rgbChannelCorrectionMap", bestRgbPng != null)
            .put("estimatedBrightnessLoss", brightnessLoss)
            .put("maxAttenuation", MAX_ATTENUATION.toDouble())
            .put("rgbChannels", rgb70Json)
            .put("rgb70Channels", rgb70Json)
            .put("rgb30ChannelsBefore", statsMapJson(rgb30.stats))
            .put("rgb30ChannelsAfter", statsMapJson(finalRgb30.stats))
            .put(
                "evaluationVerdict",
                when {
                    invalid -> "invalid_condition_changed"
                    finalUniformityPassed -> "pass"
                    else -> "retry"
                },
            )

        val dir = ReportStore.newSessionDir(this)
        withContext(Dispatchers.IO) {
            val files = LinkedHashMap<String, ByteArray>()
            files["correction_alpha_${screenW}x${screenH}.png"] = bestPng
            if (bestRgbPng != null) files["correction_rgb_${screenW}x${screenH}.png"] = bestRgbPng
            files["deviation_before.png"] = Analyzer.deviationHeatmapPng(lumaBefore, gw, gh)
            files["deviation_after.png"] = Analyzer.deviationHeatmapPng(finalLumaAfter ?: lumaBefore, gw, gh)
            val lowBeforeHeatmap = Analyzer.deviationHeatmapPng(lumaLowBefore, gw, gh)
            val lowAfterHeatmap = Analyzer.deviationHeatmapPng(finalLowLumaAfter ?: lumaLowBefore, gw, gh)
            files["deviation_${LOW_LIGHT_PATTERN}_before.png"] = lowBeforeHeatmap
            files["deviation_${LOW_LIGHT_PATTERN}_after.png"] = lowAfterHeatmap
            files["deviation_gray30_before.png"] = lowBeforeHeatmap
            files["deviation_gray30_after.png"] = lowAfterHeatmap
            files.putAll(rgb70.heatmaps)
            files.putAll(rgb30.heatmaps)
            files.putAll(finalRgb30.heatmaps)
            if (cross != null) {
                files.putAll(cross.files)
                files["camera_main_gray70.png"] = Analyzer.grayImagePng(grayAvg)
            }
            ReportStore.save(dir, report, files)
        }
        return dir
    }

    /** 선택한 맵 전송 후 후보 상태 적용. 무보정 후보는 기존 WB도 활성화하지 않는다. */
    private suspend fun applyGainMap(
        client: ControlClient,
        map: CorrectionMap,
        gw: Int,
        gh: Int,
        screenW: Int,
        screenH: Int,
    ) {
        val png = withContext(Dispatchers.Default) {
            map.alphaPng(gw, gh, screenW, screenH, MAX_ATTENUATION)
        }
        val rgbPng = withContext(Dispatchers.Default) {
            map.rgbPng(gw, gh, screenW, screenH, MAX_ATTENUATION)
        }
        withContext(Dispatchers.IO) {
            val request = JSONObject()
                .put("cmd", Protocol.CMD_APPLY_MAP)
                .put("width", screenW)
                .put("height", screenH)
                .put("maxAttenuation", MAX_ATTENUATION.toDouble())
                .put("defaultStrength", 100)
                .put("checksumMd5", md5(png))
                .put("sourceDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("data", Base64.encodeToString(png, Base64.NO_WRAP))
            if (rgbPng != null) {
                request
                    .put("rgbChecksumMd5", md5(rgbPng))
                    .put("rgbData", Base64.encodeToString(rgbPng, Base64.NO_WRAP))
            }
            client.request(request, timeoutMs = 120_000)
            client.command(if (map.isBaseline) Protocol.CMD_DISABLE_CORRECTION
                else Protocol.CMD_ENABLE_CORRECTION, "strength" to 100)
            client.showPattern("gray70")
        }
    }

    private fun mean(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += v.toDouble()
        return (s / a.size).toFloat()
    }

    private fun uniformityPassed(stats: Analyzer.Stats): Boolean =
        uniformityScore(stats) <= 1f

    private fun uniformityScore(stats: Analyzer.Stats): Float =
        MeasurementPolicy.score(stats, PASS_RMS, PASS_P95)

    private suspend fun measureRgbPatterns(
        client: ControlClient,
        cap: CaptureController,
        blackRgb: RgbImage,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        patterns: List<String>,
        frameCount: Int,
        heatmapPrefix: String,
        flatField: FloatArray?,
        makeHeatmaps: Boolean,
        includeGain: Boolean,
        keepGrids: Boolean = false,
        smoothRadius: Int = 1,
    ): RgbMeasurement {
        val stats = LinkedHashMap<String, Analyzer.Stats>()
        val grids = LinkedHashMap<String, FloatArray>()
        val heatmaps = LinkedHashMap<String, ByteArray>()
        val confidences = LinkedHashMap<String, FloatArray>()
        val channelGains = LinkedHashMap<Int, FloatArray>()
        val gains = ArrayList<FloatArray>()
        var maxStrayRatio = 0f

        for (pattern in patterns) {
            val channel = rgbChannel(pattern)
            val capture = capturePatternRgb(client, cap, pattern, frameCount)
            try {
                val avg = capture.average
                val rawGrid = withContext(Dispatchers.Default) {
                    Analyzer.channelGrid(avg, blackRgb, channel, h, screenW, screenH, gw, gh)
                }
                val grid = withContext(Dispatchers.Default) { Analyzer.applyFlatField(rawGrid, flatField) }
                val st = Analyzer.stats(grid)
                val blackGrid = withContext(Dispatchers.Default) {
                    Analyzer.channelGrid(blackRgb, null, channel, h, screenW, screenH, 16, 16)
                }
                val blackStats = Analyzer.stats(blackGrid)
                val strayRatio = blackStats.median / st.median
                maxStrayRatio = maxOf(maxStrayRatio, strayRatio)

                stats[pattern] = st
                if (keepGrids) grids[pattern] = grid
                if (makeHeatmaps) {
                    heatmaps["${heatmapPrefix}_${pattern}.png"] = Analyzer.deviationHeatmapPng(grid, gw, gh)
                }
                if (includeGain || keepGrids) {
                    val blackFull = withContext(Dispatchers.Default) {
                        Analyzer.channelGrid(blackRgb, null, channel, h, screenW, screenH, gw, gh)
                    }
                    val baseConfidence = withContext(Dispatchers.Default) {
                        Analyzer.confidenceGrid(rawGrid, blackFull, strayWarn = LOW_LIGHT_STRAY_WARN)
                    }
                    val flickerConfidence = temporalChannelConfidence(
                        capture.frameStore, blackRgb, channel, h, screenW, screenH, gw, gh
                    )
                    val confidence = withContext(Dispatchers.Default) {
                        Analyzer.combineConfidence(baseConfidence, flickerConfidence)
                    }
                    confidences[pattern] = confidence
                    if (includeGain || makeHeatmaps) {
                        log(
                            "$pattern 프레임 안정도 ${pct(Analyzer.meanConfidence(flickerConfidence))}, " +
                                "flicker span ${fmtSeconds(capture.frameStore.spanMs)}"
                        )
                    }
                }
                if (includeGain) {
                    val confidence = confidences[pattern]
                    val gain = withContext(Dispatchers.Default) {
                        Analyzer.gainGrid(grid, gw, gh, MAX_ATTENUATION, confidence, smoothRadius)
                    }
                    gains += gain
                    channelGains[channel] = gain
                }
            } finally {
                if (!includeGain && !keepGrids) {
                    capture.frameStore.delete()
                }
            }
        }

        var rmsSum = 0f
        stats.values.forEach { rmsSum += it.rmsDev }
        val meanRms = if (stats.isEmpty()) 0f else rmsSum / stats.size
        val aggregateGain = if (includeGain) {
            withContext(Dispatchers.Default) { Analyzer.averageGainGrids(gains, MAX_ATTENUATION) }
        } else {
            null
        }
        return RgbMeasurement(
            stats = stats,
            grids = grids,
            heatmaps = heatmaps,
            aggregateGain = aggregateGain,
            confidences = confidences,
            channelGains = channelGains,
            meanRms = meanRms,
            maxStrayRatio = maxStrayRatio,
            signalValid = stats.isNotEmpty() && maxStrayRatio <= LOW_LIGHT_STRAY_WARN,
        )
    }

    private suspend fun refineRgbGain(
        currentGain: FloatArray,
        measurement: RgbMeasurement,
        targets: Map<String, Float>,
        gw: Int,
        gh: Int,
        smoothRadius: Int = 1,
    ): FloatArray? {
        if (measurement.grids.isEmpty()) return null
        val refined = ArrayList<FloatArray>()
        for ((pattern, grid) in measurement.grids) {
            val target = targets[pattern] ?: continue
            val confidence = measurement.confidences[pattern]
            refined += withContext(Dispatchers.Default) {
                Analyzer.refineGain(
                    currentGain, grid, target, DAMPING_ALPHA, gw, gh, MAX_ATTENUATION,
                    confidence, smoothRadius
                )
            }
        }
        return withContext(Dispatchers.Default) { Analyzer.averageGainGrids(refined, MAX_ATTENUATION) }
    }

    private fun emptyRgbMeasurement(): RgbMeasurement =
        RgbMeasurement(
            stats = LinkedHashMap(),
            grids = LinkedHashMap(),
            heatmaps = LinkedHashMap(),
            aggregateGain = null,
            confidences = LinkedHashMap(),
            channelGains = LinkedHashMap(),
            meanRms = 0f,
            maxStrayRatio = 0f,
            signalValid = false,
        )

    private fun rgbChannel(pattern: String): Int = when {
        pattern.startsWith("red") -> 0
        pattern.startsWith("green") -> 1
        pattern.startsWith("blue") -> 2
        else -> throw IllegalArgumentException("RGB 패턴 아님: $pattern")
    }

    private fun floatListJson(values: List<Float>): JSONArray =
        JSONArray().apply { values.forEach { put(it.toDouble()) } }

    private fun statsMapJson(stats: Map<String, Analyzer.Stats>): JSONObject {
        val json = JSONObject()
        stats.forEach { (name, st) ->
            json.put(
                name,
                JSONObject()
                    .put("median", st.median.toDouble())
                    .put("p10", st.p10.toDouble())
                    .put("mean", st.mean.toDouble())
                    .put("rms", st.rmsDev.toDouble())
                    .put("p95", st.p95Dev.toDouble())
                    .put("max", st.maxDev.toDouble()),
            )
        }
        return json
    }

    private fun vlDecisionJson(decisions: List<VisionTrustGate.Decision>): JSONArray {
        val arr = JSONArray()
        decisions.forEach { d ->
            arr.put(
                JSONObject()
                    .put("status", d.status.name)
                    .put("confidence", d.confidence.toDouble())
                    .put("reason", d.reason)
                    .put("rawText", d.rawText),
            )
        }
        return arr
    }

    private suspend fun capturePatternAveraged(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = 900,
    ): GrayImage {
        val capture = capturePatternGray(client, cap, pattern, frameCount, settleMs)
        capture.frameStore.delete()
        return capture.average
    }

    private suspend fun capturePatternGray(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = 900,
    ): GrayCapture {
        withContext(Dispatchers.IO) { client.showPattern(pattern) }
        delay(settleMs)
        return captureGray(cap, frameCount)
    }

    private suspend fun capturePatternAveragedRgb(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = 900,
    ): RgbImage {
        val capture = capturePatternRgb(client, cap, pattern, frameCount, settleMs)
        capture.frameStore.delete()
        return capture.average
    }

    private suspend fun capturePatternRgb(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = 900,
    ): RgbCapture {
        withContext(Dispatchers.IO) { client.showPattern(pattern) }
        delay(settleMs)
        return captureRgb(cap, frameCount)
    }

    private suspend fun captureAveraged(
        cap: CaptureController,
        frameCount: Int = FRAMES_PER_PATTERN,
    ): GrayImage {
        val capture = captureGray(cap, frameCount)
        capture.frameStore.delete()
        return capture.average
    }

    private suspend fun captureGray(
        cap: CaptureController,
        frameCount: Int = FRAMES_PER_PATTERN,
    ): GrayCapture {
        val store = captureFrameStore(cap, frameCount)
        return GrayCapture(averageGrayFromStore(store), store)
    }

    private suspend fun captureRgb(
        cap: CaptureController,
        frameCount: Int,
    ): RgbCapture {
        val store = captureFrameStore(cap, frameCount)
        return RgbCapture(averageRgbFromStore(store), store)
    }

    private suspend fun captureFrameStore(
        cap: CaptureController,
        frameCount: Int,
        interFrameDelayMs: Long = FLICKER_INTER_FRAME_DELAY_MS,
    ): CaptureFrameStore {
        val files = ArrayList<File>(frameCount)
        var firstTimestampNs: Long? = null
        var lastTimestampNs: Long? = null
        val wallStartMs = System.currentTimeMillis()
        try {
            repeat(frameCount) { index ->
                val frame = cap.captureSingleFrame()
                if (frame.timestampNs > 0L) {
                    if (firstTimestampNs == null) firstTimestampNs = frame.timestampNs
                    lastTimestampNs = frame.timestampNs
                }
                val file = File.createTempFile("capture_${System.nanoTime()}_", ".bin", cacheDir)
                files += file
                writeCaptureFrame(file, frame)
                if (index != frameCount - 1) {
                    delay(interFrameDelayMs + FLICKER_PHASE_JITTER_MS[index % FLICKER_PHASE_JITTER_MS.size])
                }
            }
            val sensorSpanMs = firstTimestampNs?.let { first ->
                lastTimestampNs?.let { last -> ((last - first) / 1_000_000L).coerceAtLeast(0L) }
            } ?: 0L
            val wallSpanMs = (System.currentTimeMillis() - wallStartMs).coerceAtLeast(0L)
            return CaptureFrameStore(files, if (sensorSpanMs > 0L) sensorSpanMs else wallSpanMs)
        } catch (e: Exception) {
            files.forEach { file -> runCatching { file.delete() } }
            throw e
        }
    }

    /**
     * 활성 화각 전체(메인+초광각+망원)를 동시 캡처로 N회 촬영해 role별 스토어로 만든다.
     * 각 회차는 하나의 캡처 요청이므로 role 간 프레임은 "같은 순간"의 화면이다.
     * 메인 단독 세션이면 main 스토어 하나만 반환된다 (기존 코드 경로와 동일).
     */
    private suspend fun captureTriStores(
        cap: CaptureController,
        frameCount: Int,
        interFrameDelayMs: Long = FLICKER_INTER_FRAME_DELAY_MS,
    ): Map<String, CaptureFrameStore> {
        val filesByRole = LinkedHashMap<String, ArrayList<File>>()
        val firstTs = HashMap<String, Long>()
        val lastTs = HashMap<String, Long>()
        val wallStartMs = System.currentTimeMillis()
        try {
            repeat(frameCount) { index ->
                val frames = cap.captureTriFrames()
                for ((role, frame) in frames) {
                    if (frame.timestampNs > 0L) {
                        if (!firstTs.containsKey(role)) firstTs[role] = frame.timestampNs
                        lastTs[role] = frame.timestampNs
                    }
                    val file = File.createTempFile(
                        "capture_${role}_${System.nanoTime()}_", ".bin", cacheDir,
                    )
                    filesByRole.getOrPut(role) { ArrayList() } += file
                    writeCaptureFrame(file, frame)
                }
                if (index != frameCount - 1) {
                    delay(
                        interFrameDelayMs +
                            FLICKER_PHASE_JITTER_MS[index % FLICKER_PHASE_JITTER_MS.size],
                    )
                }
            }
            val wallSpanMs = (System.currentTimeMillis() - wallStartMs).coerceAtLeast(0L)
            return filesByRole.mapValues { (role, files) ->
                val span = firstTs[role]?.let { f ->
                    lastTs[role]?.let { l -> ((l - f) / 1_000_000L).coerceAtLeast(0L) }
                } ?: 0L
                CaptureFrameStore(files, if (span > 0L) span else wallSpanMs)
            }
        } catch (e: Exception) {
            filesByRole.values.flatten().forEach { runCatching { it.delete() } }
            throw e
        }
    }

    private data class CrossResult(
        val agreement: FloatArray,
        val roles: List<String>,
        val meanAgreement: Float,
        val roleStats: LinkedHashMap<String, Analyzer.Stats>,
        val files: LinkedHashMap<String, ByteArray>,
        val notes: List<String>,
        val vlDecisions: List<VisionTrustGate.Decision>,
    )

    /**
     * 동시 촬영된 보조 화각(초광각/망원)을 각자 검출·방향판별·정합·flat-field 후
     * 정규화 그리드로 만들고, 메인과의 screen-space 재현성 confidence를 계산한다.
     * 낮은 재현성 구간은 촬영계 성분으로 의심하되, Gemini Nano VL이 사용 가능하면
     * 해당 crop이 실제 화면 결함인지 보조 판정해 과도한 마스킹을 완화한다.
     * 역해석용으로 화각별 평균 프레임 PNG와 편차 히트맵을 세션 폴더에 남긴다.
     */
    private suspend fun analyzeCrossRoles(
        grayStores: Map<String, CaptureFrameStore>,
        blackStores: Map<String, CaptureFrameStore>,
        markerFrames: Map<String, CaptureFrame>,
        mainGray: GrayImage,
        mainHomography: Homography,
        mainLuma: FloatArray,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): CrossResult? {
        val extraRoles = grayStores.keys.filter { it != CameraEnumerator.ROLE_MAIN }
        if (extraRoles.isEmpty()) return null

        val norms = ArrayList<FloatArray>()
        norms += withContext(Dispatchers.Default) { Analyzer.normalizeByMedian(mainLuma) }
        val okRoles = ArrayList<String>()
        val roleStats = LinkedHashMap<String, Analyzer.Stats>()
        val files = LinkedHashMap<String, ByteArray>()
        val notes = ArrayList<String>()
        val roleImages = LinkedHashMap<String, GrayImage>()
        val roleHomographies = LinkedHashMap<String, Homography>()
        roleImages[CameraEnumerator.ROLE_MAIN] = mainGray
        roleHomographies[CameraEnumerator.ROLE_MAIN] = mainHomography

        for (role in extraRoles) {
            val grayStore = grayStores.getValue(role)
            val blackStore = blackStores[role]
            try {
                status("3/10 3각 교차 분석: $role...")
                val roleGray = averageGrayFromStore(grayStore)
                val roleBlack = blackStore?.let { averageGrayFromStore(it) }

                val det = withContext(Dispatchers.Default) { ScreenDetector.detect(roleGray) }
                    ?: throw IllegalStateException("화면 검출 실패 (프레이밍/초점)")
                if (det.areaRatio > 0.97f) throw IllegalStateException("화면이 프레임 초과 (망원 한계)")
                if (det.areaRatio < 0.04f) throw IllegalStateException("화면 점유율 과소")

                val markerImg = markerFrames[role]?.let { frame ->
                    withContext(Dispatchers.Default) { ImageOps.decodeLinearGray(frame) }
                }
                val oriented = markerImg?.let { img ->
                    withContext(Dispatchers.Default) {
                        Analyzer.buildHomographyWithMarker(det.quad, img, screenW, screenH)
                    }
                }
                val h = oriented?.homography
                    ?: withContext(Dispatchers.Default) {
                        Analyzer.buildHomography(det.quad, screenW, screenH)
                    }
                    ?: throw IllegalStateException("호모그래피 실패")

                val raw = withContext(Dispatchers.Default) {
                    Analyzer.lumaGrid(roleGray, roleBlack, h, screenW, screenH, gw, gh)
                }
                val ff = withContext(Dispatchers.Default) { Analyzer.radialFlatField(raw, gw, gh) }
                val grid = withContext(Dispatchers.Default) { Analyzer.applyFlatField(raw, ff) }
                val st = Analyzer.stats(grid)
                roleStats[role] = st
                norms += withContext(Dispatchers.Default) { Analyzer.normalizeByMedian(grid) }
                okRoles += role
                roleImages[role] = roleGray
                roleHomographies[role] = h
                files["camera_${role}_gray70.png"] =
                    withContext(Dispatchers.Default) { Analyzer.grayImagePng(roleGray) }
                files["deviation_${role}_before.png"] =
                    withContext(Dispatchers.Default) { Analyzer.deviationHeatmapPng(grid, gw, gh) }
                notes += "교차 포함: $role — 점유율 ${(det.areaRatio * 100).toInt()}%, " +
                    "RMS ${pct(st.rmsDev)}" +
                    (oriented?.let { ", 방향 ${it.mapping.label}" } ?: ", 방향 기본매핑")
            } catch (e: Exception) {
                notes += "교차 제외: $role — ${e.message}"
            } finally {
                grayStore.delete()
                blackStore?.delete()
            }
        }

        var agreement = if (okRoles.isEmpty()) {
            FloatArray(gw * gh) { 1f } // 전부 제외 → 중립 (메인 단독과 동일)
        } else {
            withContext(Dispatchers.Default) { Analyzer.crossAgreementConfidence(norms, gw, gh) }
        }
        val vlDecisions = ArrayList<VisionTrustGate.Decision>()
        if (okRoles.isNotEmpty()) {
            val regions = withContext(Dispatchers.Default) {
                Analyzer.lowConfidenceRegions(
                    agreement,
                    gw,
                    gh,
                    VL_REGION_CONFIDENCE_THRESHOLD,
                    VL_REGION_MIN_AREA,
                )
            }
            if (regions.isNotEmpty()) {
                notes += "VL 교차 후보: ${regions.size}개 crop 검사 시도"
            }
            for ((idx, region) in regions.withIndex()) {
                val crops = ArrayList<Pair<String, android.graphics.Bitmap>>()
                try {
                    for (role in listOf(CameraEnumerator.ROLE_MAIN) + okRoles) {
                        val img = roleImages[role] ?: continue
                        val h = roleHomographies[role] ?: continue
                        val bmp = withContext(Dispatchers.Default) {
                            Analyzer.screenCropBitmap(img, h, screenW, screenH, gw, gh, region)
                        }
                        crops += role to bmp
                    }
                    if (crops.isEmpty()) continue
                    val sheet = withContext(Dispatchers.Default) { Analyzer.contactSheetBitmap(crops) }
                    files["vl_cross_candidate_${idx + 1}.png"] =
                        withContext(Dispatchers.Default) { Analyzer.bitmapPng(sheet) }
                    val decision = visionTrustGate.assessCrop(
                        sheet,
                        (listOf(CameraEnumerator.ROLE_MAIN) + okRoles).joinToString("+"),
                        "grid=(${region.minX},${region.minY})-(${region.maxX},${region.maxY}), " +
                            "area=${region.area}, meanConfidence=${fmt(region.meanConfidence)}",
                    )
                    vlDecisions += decision
                    notes += "VL 후보 ${idx + 1}: ${decision.status} " +
                        "conf ${pct(decision.confidence)} — ${decision.reason}"
                    if (
                        decision.status == VisionTrustGate.Status.TRUST_SCREEN &&
                        decision.confidence >= 0.55f
                    ) {
                        agreement = withContext(Dispatchers.Default) {
                            Analyzer.raiseConfidenceFloor(
                                agreement,
                                gw,
                                gh,
                                region,
                                VL_RESTORED_CONFIDENCE_FLOOR,
                            )
                        }
                    }
                    sheet.recycle()
                } finally {
                    crops.forEach { (_, bmp) -> bmp.recycle() }
                }
            }
        }
        return CrossResult(
            agreement = agreement,
            roles = listOf(CameraEnumerator.ROLE_MAIN) + okRoles,
            meanAgreement = Analyzer.meanConfidence(agreement),
            roleStats = roleStats,
            files = files,
            notes = notes,
            vlDecisions = vlDecisions,
        )
    }

    /**
     * 프레임 스택 → 픽셀별 절사 평균. 4장 이상이면 픽셀별 최소/최대 1장씩을 제외해,
     * 잔여 롤링 밴드나 순간 플리커가 남은 프레임이 평균을 끌고 가는 것을 막는다.
     * (min/max 추적만 추가하면 되어 스트리밍 처리 그대로 유지)
     */
    private suspend fun averageGrayFromStore(store: CaptureFrameStore): GrayImage =
        withContext(Dispatchers.Default) {
            var width = 0
            var height = 0
            var acc: FloatArray? = null
            var minArr: FloatArray? = null
            var maxArr: FloatArray? = null
            for (file in store.files) {
                val img = ImageOps.decodeLinearGray(readCaptureFrame(file))
                if (acc == null) {
                    width = img.w
                    height = img.h
                    acc = FloatArray(img.data.size)
                    minArr = FloatArray(img.data.size) { Float.MAX_VALUE }
                    maxArr = FloatArray(img.data.size) { -Float.MAX_VALUE }
                } else {
                    require(img.w == width && img.h == height) { "프레임 크기 불일치" }
                }
                val dst = acc!!
                val mn = minArr!!
                val mx = maxArr!!
                for (i in dst.indices) {
                    val v = img.data[i]
                    dst[i] += v
                    if (v < mn[i]) mn[i] = v
                    if (v > mx[i]) mx[i] = v
                }
            }
            val dst = acc ?: throw IllegalStateException("프레임 없음")
            val n = store.files.size
            if (n >= 4) {
                val mn = minArr!!
                val mx = maxArr!!
                val div = (n - 2).toFloat()
                for (i in dst.indices) dst[i] = (dst[i] - mn[i] - mx[i]) / div
            } else {
                for (i in dst.indices) dst[i] /= n.toFloat()
            }
            GrayImage(width, height, dst)
        }

    private suspend fun averageRgbFromStore(store: CaptureFrameStore): RgbImage =
        withContext(Dispatchers.Default) {
            var width = 0
            var height = 0
            var accR: FloatArray? = null
            var accG: FloatArray? = null
            var accB: FloatArray? = null
            var minR: FloatArray? = null
            var minG: FloatArray? = null
            var minB: FloatArray? = null
            var maxR: FloatArray? = null
            var maxG: FloatArray? = null
            var maxB: FloatArray? = null
            for (file in store.files) {
                val img = ImageOps.decodeLinearRgb(readCaptureFrame(file))
                if (accR == null) {
                    width = img.w
                    height = img.h
                    accR = FloatArray(img.r.size)
                    accG = FloatArray(img.g.size)
                    accB = FloatArray(img.b.size)
                    minR = FloatArray(img.r.size) { Float.MAX_VALUE }
                    minG = FloatArray(img.g.size) { Float.MAX_VALUE }
                    minB = FloatArray(img.b.size) { Float.MAX_VALUE }
                    maxR = FloatArray(img.r.size) { -Float.MAX_VALUE }
                    maxG = FloatArray(img.g.size) { -Float.MAX_VALUE }
                    maxB = FloatArray(img.b.size) { -Float.MAX_VALUE }
                } else {
                    require(img.w == width && img.h == height) { "프레임 크기 불일치" }
                }
                val r = accR!!
                val g = accG!!
                val b = accB!!
                val rn = minR!!
                val gn = minG!!
                val bn = minB!!
                val rx = maxR!!
                val gx = maxG!!
                val bx = maxB!!
                for (i in r.indices) {
                    val rv = img.r[i]
                    val gv = img.g[i]
                    val bv = img.b[i]
                    r[i] += rv
                    g[i] += gv
                    b[i] += bv
                    if (rv < rn[i]) rn[i] = rv
                    if (gv < gn[i]) gn[i] = gv
                    if (bv < bn[i]) bn[i] = bv
                    if (rv > rx[i]) rx[i] = rv
                    if (gv > gx[i]) gx[i] = gv
                    if (bv > bx[i]) bx[i] = bv
                }
            }
            val r = accR ?: throw IllegalStateException("프레임 없음")
            val g = accG!!
            val b = accB!!
            val n = store.files.size
            if (n >= 4) {
                val div = (n - 2).toFloat()
                val rn = minR!!
                val gn = minG!!
                val bn = minB!!
                val rx = maxR!!
                val gx = maxG!!
                val bx = maxB!!
                for (i in r.indices) {
                    r[i] = (r[i] - rn[i] - rx[i]) / div
                    g[i] = (g[i] - gn[i] - gx[i]) / div
                    b[i] = (b[i] - bn[i] - bx[i]) / div
                }
            } else {
                val div = n.toFloat()
                for (i in r.indices) {
                    r[i] /= div
                    g[i] /= div
                    b[i] /= div
                }
            }
            RgbImage(width, height, r, g, b)
        }

    private suspend fun temporalLumaConfidence(
        frameStore: CaptureFrameStore,
        black: GrayImage?,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray =
        withContext(Dispatchers.Default) {
            try {
                if (frameStore.files.size < 2) return@withContext FloatArray(gw * gh) { 1f }
                val (tw, th) = stabilityGridSize(gw, gh)
                val grids = ArrayList<FloatArray>(frameStore.files.size)
                for (file in frameStore.files) {
                    val frame = ImageOps.decodeLinearGray(readCaptureFrame(file))
                    grids += Analyzer.lumaGrid(frame, black, h, screenW, screenH, tw, th)
                }
                Analyzer.temporalStabilityConfidence(
                    grids,
                    tw,
                    th,
                    gw,
                    gh,
                    FLICKER_OK_RELATIVE_RANGE,
                    FLICKER_ZERO_RELATIVE_RANGE,
                )
            } finally {
                frameStore.delete()
            }
        }

    private suspend fun temporalChannelConfidence(
        frameStore: CaptureFrameStore,
        black: RgbImage?,
        channel: Int,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray =
        withContext(Dispatchers.Default) {
            try {
                if (frameStore.files.size < 2) return@withContext FloatArray(gw * gh) { 1f }
                val (tw, th) = stabilityGridSize(gw, gh)
                val grids = ArrayList<FloatArray>(frameStore.files.size)
                for (file in frameStore.files) {
                    val frame = ImageOps.decodeLinearRgb(readCaptureFrame(file))
                    grids += Analyzer.channelGrid(frame, black, channel, h, screenW, screenH, tw, th)
                }
                Analyzer.temporalStabilityConfidence(
                    grids,
                    tw,
                    th,
                    gw,
                    gh,
                    FLICKER_OK_RELATIVE_RANGE,
                    FLICKER_ZERO_RELATIVE_RANGE,
                )
            } finally {
                frameStore.delete()
            }
        }

    /**
     * gain 맵 스무딩 반경(그리드 셀 단위)을 카메라의 실측 해상도에 맞춘다.
     * 네이티브 1:1 그리드는 촬영 이미지보다 훨씬 조밀해서(카메라 1픽셀 ≈ 여러 셀),
     * 고정 3x3 블러로는 카메라 픽셀 스케일의 노이즈·무아레가 거의 그대로 맵에 새겨져
     * 화면에 줄무늬/얼룩으로 표시됐다. 반경을 '카메라 1픽셀이 차지하는 셀 수' 이상으로
     * 잡으면 카메라가 실제로 분해하지 못하는 성분만 제거되고 실측 정보는 보존된다.
     */
    private fun mapSmoothRadius(quad: ScreenDetector.Quad, gw: Int, gh: Int): Int {
        val screenSpanPx = maxOf(quad.topLen(), quad.sideLen())
        if (screenSpanPx <= 1f) return 1
        val cellsPerCameraPx = maxOf(gw, gh) / screenSpanPx
        return Math.ceil(cellsPerCameraPx.toDouble()).toInt().coerceIn(1, 8)
    }

    private fun stabilityGridSize(gw: Int, gh: Int): Pair<Int, Int> {
        val maxEdge = maxOf(gw, gh)
        if (maxEdge <= FLICKER_STABILITY_GRID_MAX_EDGE) return Pair(gw, gh)
        val scale = FLICKER_STABILITY_GRID_MAX_EDGE / maxEdge.toFloat()
        val w = Math.max(8, Math.round(gw * scale))
        val h = Math.max(8, Math.round(gh * scale))
        return Pair(w, h)
    }

    private fun writeCaptureFrame(file: File, frame: CaptureFrame) = CaptureFrameCodec.write(file, frame)
    private fun readCaptureFrame(file: File): CaptureFrame = CaptureFrameCodec.read(file)

    /** 대상 화면의 보정을 켜고 끄며 육안 비교 (보정 전/후 비교 UI의 원격 버전). */
    private fun toggleCorrection() {
        val client = Session.client ?: return
        correctionOn = !correctionOn
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (correctionOn) client.command(Protocol.CMD_ENABLE_CORRECTION, "strength" to 100)
                    else client.command(Protocol.CMD_DISABLE_CORRECTION)
                }
                status("대상 화면 보정 ${if (correctionOn) "ON" else "OFF"}")
            } catch (e: Exception) {
                log("토글 실패: ${e.message}")
            }
        }
    }

    private fun pct(v: Float): String = String.format(java.util.Locale.US, "%.2f%%", v * 100)

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.4f", v)

    private fun fmtSeconds(ms: Long): String =
        String.format(java.util.Locale.US, "%.2fs", ms / 1000.0)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun onDestroy() {
        scope.cancel()
        if (visionTrustGateHolder.isInitialized()) {
            runCatching { visionTrustGate.close() }
        }
        capture?.close()
        capture = null
        super.onDestroy()
    }
}
