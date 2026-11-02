package com.burnin.scanner.measure

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.hardware.camera2.CameraManager
import android.view.TextureView
import android.view.View
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
import com.burnin.scanner.analysis.LowFrequency
import com.burnin.scanner.analysis.VignetteCalibration
import com.burnin.scanner.analysis.NativePixel
import com.burnin.scanner.analysis.ResponseCurve
import com.burnin.scanner.analysis.RgbImage
import com.burnin.scanner.analysis.ScreenDetector
import com.burnin.scanner.analysis.SparseSpotPhotometry
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
import kotlin.math.roundToInt
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
        /** 색 균일화(화이트밸런스 레이어) 최대 감쇠. 탭 앱 WhiteBalanceStore 기본값과 같다. */
        private const val CHROMA_MAX_ATTENUATION = 0.15f
        /**
         * 색 보정 블록(긴 변 칸 수). 24칸(약 85px)으로는 UI 모양 번인의 날카로운 색 경계가 뭉개져 띠로 남았다.
         * 384칸(약 5px)이면 경계를 따라가면서 블록당 화소가 충분해 잡음이 작다.
         */
        private const val CHROMA_BLOCKS = 384
        /** 색 레이어를 켠 채 RGB70을 다시 재는 횟수. 마지막 회는 확인만 하고, 나빠졌으면 이전 레이어로 되돌린다. */
        private const val CHROMA_REFINE_ROUNDS = 3
        /** 저주파(잔여 비네팅·색 얼룩) 블록 수: 긴 변 기준. */
        private const val LOW_FREQ_BLOCKS = 24
        /** 패턴 표시 ACK(프레임 커밋 후) 뒤 카메라 파이프라인에 남은 이전 장면이 빠질 시간. */
        private const val PATTERN_SETTLE_MS = 300L
        /** 노출 잠금 전 자동 노출·화이트밸런스 수렴 대기. */
        private const val AE_CONVERGE_MS = 1_200L
        /** 원본 해상도 분석에 필요한 힙: RGB 프레임 한 장 크기의 이 배수 이상. */
        private const val FULL_RES_HEAP_FACTOR = 4
        private const val LOW_LIGHT_PATTERN = "gray25"
        private const val LOW_LIGHT_CODE = 64      // gray25 = round(255·0.25)
        private const val GRAY70_CODE = 179        // gray70 = round(255·0.70)
        private const val LOW_LIGHT_FRAMES = 5
        private const val LOW_LIGHT_STRAY_WARN = 0.10f
        private const val LOW_LIGHT_GAIN_WEIGHT = 0.35f
        private const val LOW_LIGHT_WEAK_GAIN_WEIGHT = 0.12f
        private const val LOW_RGB_FRAMES = 5
        private const val LOW_RGB_GAIN_WEIGHT = 0.18f
        private const val LOW_RGB_WEAK_GAIN_WEIGHT = 0.06f
        private const val DEFAULT_MAX_ATTENUATION = 0.10f
        /** 탭 앱 CorrectionStore가 받아들이는 최대 감쇠 상한. */
        private const val MAX_ALLOWED_ATTENUATION = 0.30f
        /** 희소 점등 측광(글씨 획 단위 고주파 층) 사용 여부 */
        private const val STROKE_DETAIL_ENABLED = true
        /** 반복 보정 뒤 보정을 켠 채 흰 점으로 픽셀 잔차를 재고 고주파 층을 다시 맞추는 최대 횟수. */
        private const val PIXEL_REFINE_ROUNDS = 2
        /** RGBW는 한 회에 희소 측정이 4번(약 7분)이라 고치기 1회 + 확인으로 줄인다. */
        private const val PIXEL_REFINE_ROUNDS_RGBW = 1
        private const val SPARSE_FRAMES_PER_PATTERN = 8
        private const val SPARSE_BLACK_FRAMES = 8
        private const val SPARSE_INTER_FRAME_DELAY_MS = 0L
        private const val SPARSE_SETTLE_MS = 300L
        /** 희소 촬영 노출 상향 목표: gray70 상위 1% 밝기가 이 값(선형 0~1)이 되도록. */
        private const val SPARSE_TARGET_PEAK = 0.6f
        private const val SPARSE_MAX_EXPOSURE_BOOST = 4f
        /** 점 탐침 뒤 노출 보정 상한(측정 전 노출 대비 누적). 단색 점은 흰 점보다 훨씬 어둡다. */
        private const val SPARSE_MAX_TOTAL_BOOST = 16f
        /** 탐침 프레임에서 점 최고 밝기로 볼 분위수(점 화소는 전체의 수 % 이하). */
        private const val SPARSE_PROBE_QUANTILE = 0.998f
        /** 단색(R/G/B) 점 구동 레벨(%). 서브픽셀 하나라 광량이 적어 흰 점(70)보다 올린다. */
        private const val SPARSE_CHANNEL_LEVEL = 100
        /** 응답 곡선 레벨(%)과 가중치: 번인이 잘 보이는 20~50% 구간을 무겁게 본다. */
        private val CURVE_LEVELS = intArrayOf(15, 25, 40, 55, 70, 85)
        private val CURVE_WEIGHTS = floatArrayOf(0.6f, 1f, 1f, 0.8f, 0.6f, 0.4f)
        private const val CURVE_FRAMES = 4
        private const val CURVE_BLOCKS = 48
        private const val PASS_RMS = 0.015f      // 합격: 잔여 RMS ≤ 1.5%
        private const val PASS_P95 = 0.035f      // 합격: P95 편차 ≤ 3.5%
        private const val PASS_BLOCK = 0.02f     // 합격: 블록 평균 최대 편차 ≤ 2% (좁은 띠 번인)
        private const val MAX_ITERATIONS = 24    // 품질 우선 반복 상한
        private const val STAGNATION_LIMIT = 4
        private const val DEFAULT_EXTRA_ITERATIONS = 4
        private const val DIVERGENCE_LIMIT = 3
        private const val DAMPING_ALPHA = 0.3f   // 반복 damping
        private const val DRIFT_LIMIT = 0.10f    // 중앙 휘도 드리프트 > 10%면 조건 변화로 무효
        private const val DISTORTION_WARN_SCORE = 0.18f
        private const val FLICKER_STABILITY_GRID_MAX_EDGE = 512
        private const val FLICKER_OK_RELATIVE_RANGE = 0.015f
        private const val FLICKER_ZERO_RELATIVE_RANGE = 0.045f
        private const val FLICKER_INTER_FRAME_DELAY_MS = 0L // 촬영되는 대로 연속 촬영(사용자 요청). 위상 지터만 남김
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
    /** 묶음 촬영으로 미리 저장한 패턴별 프레임 (pattern → store). */
    private val prefetched = LinkedHashMap<String, CaptureFrameStore>()
    /** 직전 측정의 최적 맵에서 반복 보정을 n회 더 돌리는 작업. 측정이 끝나야 생긴다. */
    private var extraImprovement: (suspend (Int) -> Unit)? = null
    /** 보정 감쇠 상한(0~1). 측정 시작 때 '최대 감쇠(%)' 입력에서 읽는다. */
    private var maxAttenuation = DEFAULT_MAX_ATTENUATION
    private lateinit var editMaxAtt: EditText
    /** 초기 측정의 희소 점등을 R·G·B 점으로 따로 잴지(끄면 흰 점). 원인 분리 비교용으로 화면에서 바꾼다. */
    private lateinit var chkRgbDots: CheckBox
    private lateinit var btnVignette: Button
    private lateinit var txtVignette: TextView
    /** 3위치 비네팅 보정 결과(카메라별 저장). 있으면 측정의 카메라 flat-field로 쓴다. */
    private var vignetteModel: VignetteCalibration.Model? = null
    /** 0 = 대기, 1..3 = 다음에 확정할 위치 번호. */
    private var vignetteStep = 0
    private val vignettePositions = ArrayList<VignetteCalibration.Position>()
    private lateinit var editExtra: EditText
    private lateinit var btnExtra: Button
    private var triSelection: CameraEnumerator.TriSelection? = null
    private var measuring = false
    private var correctionOn = true
    private var strokeDetailReport: JSONObject? = null
    /** 대상 패널 γ 추정치(gray70/gray25). Target이 감쇠(선형 휘도)를 코드 gain^(1/γ)로 바꾸는 데 쓴다. */
    private var outputGamma: Float? = null
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
        /** 패턴별 보정 목표 휘도 (includeGain 측정에서만 채운다) */
        val targets: LinkedHashMap<String, Float>,
        val meanRms: Float,
        val maxStrayRatio: Float,
        val signalValid: Boolean,
    )

    /** 희소 점등 측광 결과: 화면 픽셀 단위 고주파 감쇠 층과, 저해상도 재측정 보정용 평균 감쇠 */
    /** 레벨 곡선으로 정한 gray70 보상(q 블록)과 그 기준의 목표. 없으면 gray70·gray25 혼합만 쓴다. */
    private var levelComp: LowFrequency.Blocks? = null
    private var curveTarget70: Float? = null
    private var curveReport: JSONObject? = null

    /** 분석 이미지가 원본 대비 몇 분의 1인지(호모그래피를 원본 좌표로 옮길 때 씀). */
    private var analysisSample = 1

    /**
     * 메인 카메라 프레임 묶음을 원본 해상도로 디코드해 화면 격자로 바로 샘플링하고 black 격자를 뺀다.
     * 분석 이미지(절반 해상도)로 샘플링하면 화면 1px당 카메라 0.65px까지 떨어져 획·경계가 뭉개졌다.
     * [h]와 [black]은 분석 이미지 좌표계다(black은 매끈해 분석 해상도로 충분). 네이티브가 없으면 null.
     */
    private suspend fun fullResGrid(
        store: CaptureFrameStore, mode: Int, h: Homography, black: GrayImage?,
        screenW: Int, screenH: Int, gw: Int, gh: Int,
    ): FloatArray? = withContext(Dispatchers.Default) {
        val s = analysisSample
        val hFull = h.withImageAffine(s.toDouble(), (s - 1) / 2.0)
        val grid = NativePixel.averageGrid(store.files, mode, ImageOps.FULL_RESOLUTION, hFull, screenW, screenH, gw, gh)
            ?: return@withContext null
        if (black != null) {
            val b = Analyzer.lumaGrid(black, null, h, screenW, screenH, gw, gh)
            for (i in grid.indices) grid[i] = (grid[i] - b[i]).coerceAtLeast(1e-5f)
        } else {
            for (i in grid.indices) grid[i] = grid[i].coerceAtLeast(1e-5f)
        }
        grid
    }

    private fun levelAdjusted(luma: FloatArray, gw: Int, gh: Int): FloatArray =
        levelComp?.let { ResponseCurve.divide(luma, it, gw, gh) } ?: luma

    private class StrokeDetail(
        val gain: FloatArray,
        val compensation: FloatArray,
        val report: JSONObject,
        /** R/G/B 점으로 따로 잰 채널별 고주파 층(있으면). [gain]은 이들의 휘도 가중 합. */
        val channelGains: List<FloatArray>? = null,
    )

    private data class CorrectionIterations(
        val bestMap: CorrectionMap,
        /** 최적 맵의 균일도 점수(낮을수록 좋음). 추가 개선의 출발점 비교에 쓴다. */
        val bestScore: Float,
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
        editExtra = findViewById(R.id.editExtra)
        btnExtra = findViewById(R.id.btnExtra)
        findViewById<View>(R.id.rowExtra).visibility = View.VISIBLE
        findViewById<View>(R.id.rowAttenuation).visibility = View.VISIBLE
        findViewById<View>(R.id.rowVignette).visibility = View.VISIBLE
        btnVignette = findViewById(R.id.btnVignette)
        txtVignette = findViewById(R.id.txtVignette)
        btnVignette.setOnClickListener { onVignetteButton() }
        btnVignette.setOnLongClickListener {
            vignetteModel = null
            vignetteStep = 0
            vignettePositions.clear()
            getSharedPreferences("app", MODE_PRIVATE).edit().remove(vignettePrefKey()).apply()
            btnVignette.text = "비네팅 보정 (3위치)"
            txtVignette.text = "비네팅 보정값 없음"
            log("비네팅 보정값을 지웠어요")
            true
        }
        loadVignetteModel()
        editMaxAtt = findViewById(R.id.editMaxAtt)
        chkRgbDots = findViewById(R.id.chkRgbDots)
        // 감쇠 상한·RGBW 선택은 앱을 다시 띄워도(업데이트 설치 포함) 유지한다
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        prefs.getString("max_attenuation_pct", null)?.let { editMaxAtt.setText(it) }
        chkRgbDots.isChecked = prefs.getBoolean("rgbw_dots", chkRgbDots.isChecked)
        editMaxAtt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString("max_attenuation_pct", s.toString().trim()).apply()
            }
        })
        chkRgbDots.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("rgbw_dots", checked).apply() }
        findViewById<Button>(R.id.btnCheckAtt).setOnClickListener {
            btnStart.isEnabled = false
            btnExtra.isEnabled = false
            scope.launch { runMeasurementSafely { checkAttenuationNeed() } }
        }
        editExtra.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { btnExtra.text = "추가 개선 ${extraIterations()}회" }
        })
        btnExtra.setOnClickListener {
            val job = extraImprovement ?: return@setOnClickListener
            val n = extraIterations()
            btnStart.isEnabled = false
            btnExtra.isEnabled = false
            scope.launch { runMeasurementSafely { job(n) } }
        }

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
        CameraEnumerator.maxResolutionReport(cameraManager, choices).forEach { log(it) }
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
                val auxView = findViewById<TextureView>(R.id.previewAux)
                val wantAux = chkTri.isChecked && triSelection?.let { it.roleCount > 1 } == true
                auxView.visibility = if (wantAux) View.VISIBLE else View.GONE
                val c = CaptureController(
                    this@MeasurementActivity,
                    findViewById<TextureView>(R.id.preview),
                    auxTextureView = if (wantAux) auxView else null,
                )
                capture = c
                c.start(if (chkTri.isChecked) triSelection else null)
                if (!c.isAuxPreviewActive) auxView.visibility = View.GONE
                status("카메라 준비 완료 — 정렬 확인 후 [측정 시작]")
                log(
                    "카메라: ${c.captureSize.width}x${c.captureSize.height} " +
                        "${c.captureFormatText}, ${c.hardwareLevelText()}"
                )
                if (chkTri.isChecked) {
                    log(
                        if (c.isTriActive) "동시 촬영 구성(${c.activeRoles.size}대): ${c.triSummary()}"
                        else "동시 촬영 구성 실패 → 메인 단독 폴백 (기기 HAL 제한)"
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

    /**
     * 분석 해상도: 힙이 넉넉하면 촬영 원본 해상도로, 아니면 긴 변 절반(2의 거듭제곱 다운샘플)으로.
     * 원본 RGB 한 장이 수백 MB가 될 수 있어 largeMemoryClass로 판단한다.
     */
    private fun selectAnalysisResolution(cap: CaptureController) {
        val heapMb = getSystemService(android.app.ActivityManager::class.java).largeMemoryClass
        val w = cap.captureSize.width
        val h = cap.captureSize.height
        val rgbFrameMb = w.toLong() * h * 3 * 4 / (1 shl 20)
        val full = heapMb >= rgbFrameMb * FULL_RES_HEAP_FACTOR
        ImageOps.analysisMaxLongEdge = if (full) ImageOps.FULL_RESOLUTION else 1600
        val s = ImageOps.downsampleFactor(w, h)
        analysisSample = s
        log(
            "분석 이미지: ${w / s}x${h / s} (촬영 ${w}x$h, 1/$s, 힙 ${heapMb}MB) — 화면 검출용. " +
                "휘도·채널 격자는 ${if (NativePixel.available && NativePixel.enabled) "네이티브로 원본 해상도에서" else "이 해상도에서"} 샘플링"
        )
    }

    private fun heapText(): String {
        val rt = Runtime.getRuntime()
        return "${(rt.totalMemory() - rt.freeMemory()) shr 20}MB / 최대 ${rt.maxMemory() shr 20}MB"
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
        // 분석 코드가 Dispatchers.Default 안에서 로그를 남기기도 하므로 뷰 갱신은 항상 메인 스레드로 보낸다.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { status(msg) }
            return
        }
        txtStatus.text = msg
        AppLog.i(msg)
        // 단계("N/10")가 바뀔 때 직전 단계 소요 시간을 남겨 병목을 볼 수 있게 한다.
        val step = Regex("""^(\d+/10)""").find(msg)?.groupValues?.get(1)
        if (step != null && step != lastStep) {
            val now = android.os.SystemClock.elapsedRealtime()
            lastStep?.let { log("⏱ $it 단계 ${fmtSeconds(now - lastStepAt)}, 힙 ${heapText()}") }
            lastStep = step
            lastStepAt = now
        }
    }

    private var lastStep: String? = null
    private var nativeVerified = false
    /** 이번 측정에서 탭에 색 균일화(화이트밸런스) 레이어를 올렸는지. */
    private var whiteBalanceActive = false
    /** 측정 전 색 편차(블록 평균·P95). 최종 평가에서 전/후 비교에 쓴다. */
    private var chromaBefore: Pair<Float, Float>? = null
    private var lastStepAt = 0L

    private fun log(msg: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { log(msg) }
            return
        }
        resultLog.appendLine(msg)
        txtResult.text = resultLog.toString()
        AppLog.i(msg)
    }

    private fun vignettePrefKey(): String = "vignette_model_main"

    private fun loadVignetteModel() {
        val text = getSharedPreferences("app", MODE_PRIVATE).getString(vignettePrefKey(), null) ?: return
        runCatching {
            val o = JSONObject(text)
            vignetteModel = VignetteCalibration.Model.fromJson(o)
            txtVignette.text = "비네팅 보정값 사용 중 (${o.optString("created")})"
        }
    }

    private val vignetteSteps = listOf("위쪽", "아래쪽", "가운데(정방향)")

    /**
     * 3위치 비네팅 보정 단계 진행. 첫 누름은 안내만 하고, 이후 누를 때마다 지금 위치를 촬영해 확정한다.
     * 세 위치가 모이면 비네팅 모델을 풀어 저장한다.
     */
    private fun onVignetteButton() {
        if (measuring) return
        if (vignetteStep == 0) {
            vignettePositions.clear()
            vignetteStep = 1
            guideVignetteStep()
            return
        }
        btnStart.isEnabled = false
        btnVignette.isEnabled = false
        scope.launch {
            try {
                runMeasurementSafely { captureVignettePosition(vignetteStep) }
                if (vignettePositions.size == vignetteStep) {
                    if (vignetteStep < 3) {
                        vignetteStep++
                        guideVignetteStep()
                    } else {
                        finishVignetteCalibration()
                    }
                }
            } finally {
                btnVignette.isEnabled = true
            }
        }
    }

    private fun guideVignetteStep() {
        val where = vignetteSteps[vignetteStep - 1]
        btnVignette.text = "위치 확정 ($vignetteStep/3 $where)"
        status("비네팅 보정 $vignetteStep/3: 탭을 카메라 프레임 $where 에 두고(화면 전체가 보이게) [위치 확정]을 누르세요")
    }

    private suspend fun captureVignettePosition(step: Int) {
        val client = Session.client ?: throw IllegalStateException("대상 기기 미연결")
        val cap = capture ?: throw IllegalStateException("카메라 미준비")
        val screenW = Session.screenWidth
        val screenH = Session.screenHeight
        status("비네팅 보정 $step/3 촬영 중...")
        withContext(Dispatchers.IO) {
            client.command(Protocol.CMD_DISABLE_CORRECTION)
            client.command(Protocol.CMD_DISABLE_OVERLAY)
            client.showPattern("gray70")
        }
        if (step == 1) {
            // 세 위치를 같은 노출로 찍어야 비율이 의미가 있다: 첫 위치에서만 노출을 잠근다.
            delay(AE_CONVERGE_MS)
            log("비네팅 보정 노출 고정: ${cap.lockMeasurementControls(inputRefreshHz())}")
        } else {
            delay(PATTERN_SETTLE_MS + 500)
        }
        val grayStore = captureFrameStore(cap, FRAMES_PER_PATTERN)
        val gray = try { averageGrayFromStore(grayStore) } finally { grayStore.delete() }
        val det = withContext(Dispatchers.Default) { ScreenDetector.detect(gray) }
            ?: throw IllegalStateException("화면 검출 실패 — 탭 화면 전체가 프레임 안에 들어오게 해 주세요")
        val h = Analyzer.buildHomography(det.quad, screenW, screenH) ?: throw IllegalStateException("호모그래피 실패")
        withContext(Dispatchers.IO) { client.showPattern("black") }
        delay(PATTERN_SETTLE_MS)
        val blackStore = captureFrameStore(cap, 3)
        val black = try { averageGrayFromStore(blackStore) } finally { blackStore.delete() }
        withContext(Dispatchers.IO) { client.showPattern("gray70") }
        val bw = if (screenW >= screenH) 24 else 18
        val bh = if (screenW >= screenH) 18 else 24
        val fine = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(gray, black, h, screenW, screenH, bw * 4, bh * 4)
        }
        val values = FloatArray(bw * bh)
        val xs = FloatArray(bw * bh)
        val ys = FloatArray(bw * bh)
        val out = DoubleArray(2)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var sum = 0f
            for (dy in 0 until 4) for (dx in 0 until 4) sum += fine[(by * 4 + dy) * bw * 4 + bx * 4 + dx]
            val i = by * bw + bx
            values[i] = sum / 16f
            h.map((bx + 0.5) * screenW / bw, (by + 0.5) * screenH / bh, out)
            val (nx, ny) = VignetteCalibration.normalize(out[0], out[1], gray.w, gray.h)
            xs[i] = nx.toFloat()
            ys[i] = ny.toFloat()
        }
        vignettePositions += VignetteCalibration.Position(values, xs, ys)
        val cx = det.quad.corners.map { it.x }.average()
        val cy = det.quad.corners.map { it.y }.average()
        log(
            "비네팅 보정 $step/3 (${vignetteSteps[step - 1]}) 확정: 화면 중심 (${cx.toInt()}, ${cy.toInt()}) / " +
                "프레임 ${gray.w}x${gray.h}, 점유율 ${(det.areaRatio * 100).toInt()}%"
        )
    }

    private suspend fun finishVignetteCalibration() {
        vignetteStep = 0
        btnVignette.text = "비네팅 보정 (3위치)"
        val result = try {
            withContext(Dispatchers.Default) { VignetteCalibration.fit(vignettePositions) }
        } catch (e: Exception) {
            status("비네팅 보정 실패: ${e.message}")
            log("비네팅 보정 실패: ${e.message} — 세 위치를 서로 더 떨어뜨려 다시 해 주세요")
            return
        } finally {
            vignettePositions.clear()
        }
        val m = result.model
        vignetteModel = m
        val created = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        getSharedPreferences("app", MODE_PRIVATE).edit()
            .putString(vignettePrefKey(), m.toJson().put("created", created).put("rms", result.rmsResidual).toString())
            .apply()
        txtVignette.text = "비네팅 보정값 사용 중 ($created)"
        log(
            "비네팅 보정 완료: 중앙 대비 감도 r=0.5 ${pct(m.gain(0.5, 0.0).toFloat())}, r=0.8 ${pct(m.gain(0.8, 0.0).toFloat())}, " +
                "r=1 ${pct(result.cornerGain.toFloat())} (모델 잔차 ${pct(result.rmsResidual.toFloat())}) — 이후 측정에 적용, 길게 누르면 삭제"
        )
        status("비네팅 보정 완료 — 탭을 측정 위치(가운데)에 둔 채로 측정하세요")
    }

    /** 비네팅 모델로 만든 카메라 flat-field 격자(화면 좌표). applyFlatField는 이 값으로 나눈다. */
    private fun modelFlatField(
        model: VignetteCalibration.Model,
        h: Homography,
        imageW: Int,
        imageH: Int,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray {
        val out = FloatArray(gw * gh)
        val p = DoubleArray(2)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            h.map((gx + 0.5) * screenW / gw, (gy + 0.5) * screenH / gh, p)
            val (nx, ny) = VignetteCalibration.normalize(p[0], p[1], imageW, imageH)
            out[gy * gw + gx] = model.gain(nx, ny).toFloat().coerceIn(0.5f, 2f)
        }
        // applyFlatField는 상대 보정이므로 중앙값 1로 맞춘다
        val med = out.copyOf().apply { sort() }[out.size / 2]
        for (i in out.indices) out[i] /= med
        return out
    }

    private fun inputMaxAttenuation(): Float =
        ((editMaxAtt.text.toString().trim().toFloatOrNull() ?: DEFAULT_MAX_ATTENUATION * 100f) / 100f)
            .coerceIn(0.01f, MAX_ALLOWED_ATTENUATION)

    /**
     * 감쇠 필요도 검증: gray70·black만 빠르게 찍어(마커·dot-grid·희소 측광 없이) 블록 휘도 분포에서
     * 가장 어두운 쪽(하위 1% 블록)에 나머지를 맞추려면 얼마나 감쇠해야 하는지 보여 준다.
     * 화면은 밝게 올릴 수 없으므로 밝은 쪽을 낮추는 감쇠량이 곧 필요한 상한이다.
     */
    private suspend fun checkAttenuationNeed() {
        val client = Session.client ?: throw IllegalStateException("대상 기기 미연결")
        val cap = capture ?: throw IllegalStateException("카메라 미준비")
        val screenW = Session.screenWidth
        val screenH = Session.screenHeight
        status("감쇠 필요도 검증: gray70 촬영...")
        log("──────────────")
        withContext(Dispatchers.IO) {
            client.command(Protocol.CMD_DISABLE_CORRECTION)
            client.command(Protocol.CMD_DISABLE_OVERLAY)
            client.showPattern("gray70")
        }
        delay(AE_CONVERGE_MS)
        cap.lockMeasurementControls(inputRefreshHz())
        val grayStore = captureFrameStore(cap, FRAMES_PER_PATTERN)
        val gray = try { averageGrayFromStore(grayStore) } finally { grayStore.delete() }
        val det = withContext(Dispatchers.Default) { ScreenDetector.detect(gray) }
            ?: throw IllegalStateException("화면 검출 실패 — 차광 상태와 카메라 정렬을 확인하세요")
        val h = Analyzer.buildHomography(det.quad, screenW, screenH)
            ?: throw IllegalStateException("호모그래피 계산 실패")
        status("감쇠 필요도 검증: black 촬영...")
        withContext(Dispatchers.IO) { client.showPattern("black") }
        delay(PATTERN_SETTLE_MS)
        val blackStore = captureFrameStore(cap, FRAMES_PER_PATTERN)
        val black = try { averageGrayFromStore(blackStore) } finally { blackStore.delete() }
        withContext(Dispatchers.IO) { client.showPattern("gray70") }
        // 블록 통계만 보면 되므로 작은 격자로 계산한다(긴 변 256칸).
        val gw = if (screenW >= screenH) 256 else (256L * screenW / screenH).toInt()
        val gh = if (screenW >= screenH) (256L * screenH / screenW).toInt() else 256
        val lumaRaw = withContext(Dispatchers.Default) { Analyzer.lumaGrid(gray, black, h, screenW, screenH, gw, gh) }
        val ff = withContext(Dispatchers.Default) { Analyzer.radialFlatField(lumaRaw, gw, gh) }
        val luma = Analyzer.applyFlatField(lumaRaw, ff)
        val blocks = LowFrequency.blocks(luma, gw, gh, 48).values.filter { it.isFinite() && it > 0f }.sorted()
        require(blocks.size >= 16) { "유효 블록 부족" }
        val median = blocks[blocks.size / 2]
        val target = blocks[(blocks.size * 0.01f).toInt()]
        val att = blocks.map { (1f - target / it).coerceAtLeast(0f) }.sorted()
        val att95 = att[((att.size - 1) * 0.95f).toInt()]
        val att99 = att[((att.size - 1) * 0.99f).toInt()]
        val attMax = att.last()
        val meanLoss = att.average().toFloat()
        val current = inputMaxAttenuation()
        val verdict = when {
            current >= att99 -> "보정 가능(블록 99% 이상)"
            current >= att95 -> "대부분 보정 가능(블록 95%), 가장 밝은 일부는 덜 맞춰짐"
            else -> "상한 부족 — 블록 95%를 맞추려면 ${pct(att95)} 이상 필요"
        }
        log("감쇠 필요도 검증 (gray70, 블록 48칸, 하위 1% 블록 기준):")
        log("   블록 95% 보정에 최대 ${pct(att95)}, 99%에 ${pct(att99)}, 전체에 ${pct(attMax)} 감쇠 필요")
        log("   그만큼 감쇠하면 평균 밝기 손실 약 ${pct(meanLoss)} (중앙 블록 대비 가장 어두운 쪽 ${pct(1f - target / median)})")
        log("   현재 상한 ${pct(current)} → $verdict")
        log("   참고: 빠른 검증이라 초광각 기준 잔여 비네팅 보정이 빠져 있어 필요량이 약간 크게 나올 수 있어요")
        status("감쇠 필요도: 95% ${pct(att95)} / 99% ${pct(att99)} — 현재 ${pct(current)} → $verdict")
    }

    private fun extraIterations(): Int =
        (editExtra.text.toString().trim().toIntOrNull() ?: DEFAULT_EXTRA_ITERATIONS).coerceIn(1, MAX_ITERATIONS)

    private suspend fun runMeasurementSafely(block: suspend () -> Unit = { runMeasurement() }) {
        measuring = true
        lastStep = null
        btnExtra.isEnabled = false
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
            }) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // 앱이 죽지 않고 측정만 중단되게 한다. 다음 조정을 위해 어디서 모자랐는지 남긴다.
            status("측정 실패: 메모리 부족")
            log("!! 중단: 메모리 부족 (${heapText()}) — ${e.message}")
            e.stackTrace.firstOrNull { it.className.startsWith("com.burnin") }?.let {
                log("   위치: ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})")
            }
        } catch (e: Exception) {
            status(if (e.suppressed.any { it is MeasurementCleanup.Incomplete })
                MeasurementCleanup.WARNING else "측정 실패: ${e.message}")
            log("!! 중단: ${e.message}")
            // 메시지 없는 require/check 실패도 위치를 알 수 있게 앱 코드의 첫 스택 프레임을 남긴다.
            e.stackTrace.firstOrNull { it.className.startsWith("com.burnin") }?.let {
                log("   위치: ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber}) [${e.javaClass.simpleName}]")
            }
        } finally {
            clearPrefetched()
            btnStart.isEnabled = true
            btnExtra.isEnabled = extraImprovement != null
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
        // 번인 측정에는 대조설비를 쓰지 않는다: 색감 맞추기 뒤 흰 화면으로 남아 있으면 박스 안 미광이 되므로 끈다
        Session.referenceClient?.takeIf { it.isConnected }?.let { ref ->
            runCatching { withContext(Dispatchers.IO) { ref.showPattern("black") } }
                .onSuccess { log("대조설비 화면을 검정으로 바꿈(미광 차단)") }
        }
        extraImprovement = null
        maxAttenuation = inputMaxAttenuation()
        whiteBalanceActive = false
        chromaBefore = null
        resultLog.clear()
        log("대상: ${Session.targetName} (${screenW}x${screenH})")
        log("보정 감쇠 상한: ${pct(maxAttenuation)}, 희소 점: ${if (chkRgbDots.isChecked) "W·R·G·B 따로" else "흰 점"}, 비네팅 보정값: ${if (vignetteModel != null) "사용" else "없음(방사형 추정)"}")
        selectAnalysisResolution(cap)

        // ── 1. 기준 패턴 + 노출 잠금 ─────────────────────────────
        status("1/10 gray70 패턴 표시, 노출 수렴 중...")
        withContext(Dispatchers.IO) {
            client.command(Protocol.CMD_DISABLE_CORRECTION)
            client.command(Protocol.CMD_DISABLE_OVERLAY)
            // 지난 측정의 색 균일화 레이어가 남아 있으면 새 색 측정이 틀어지므로 지운다.
            runCatching { client.command(Protocol.CMD_CLEAR_WHITE_BALANCE) }
            client.showPattern("gray70")
        }
        delay(AE_CONVERGE_MS)
        val refreshHz = inputRefreshHz()
        val lockText = cap.lockMeasurementControls(refreshHz)
        log("캡처 고정: $lockText (입력 주사율 ${fmtRefresh(refreshHz)}Hz)")
        if (cap.isTriActive) log("동시 3각 촬영 활성: ${cap.triSummary()}")
        cap.diagnosticNotes().forEach { log(it) }
        if (cap.isRaw) log(cap.shadingSummary())
        log(
            if (cap.isRaw) "측정 프레임: RAW_SENSOR 선형값 (톤커브 역변환 불필요, 보조 화각 YUV는 ${cap.toneMode.label})"
            else "측정 톤커브(디코더 역변환): ${cap.toneMode.label}"
        )
        delay(150)

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
            delay(PATTERN_SETTLE_MS)
            val markerAvg = captureAveraged(cap)
            if (cap.isTriActive) {
                // 보조 화각도 방향 판별이 필요하다 (교차 그리드 180° 뒤집힘 방지)
                val frames = LinkedHashMap(runCatching { cap.captureTriFrames() }.getOrDefault(emptyMap()))
                if (cap.hasSequentialRoles) {
                    runCatching { cap.captureSequential(1, 0L) { role, frame -> frames[role] = frame } }
                        .onFailure { log("보조 화각 마커 번갈아 촬영 실패: ${it.message}") }
                }
                // 교차 분석은 보조 화각 마커만 쓴다. 메인 RAW 프레임(25MB)은 들고 가지 않는다.
                frames.remove(CameraEnumerator.ROLE_MAIN)
                markerTriFrames = frames
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
            delay(PATTERN_SETTLE_MS)
            val dotAvg = captureAveraged(cap)
            withContext(Dispatchers.Default) {
                DotGridDetector.refineHomography(dotAvg, initialHomography, screenW, screenH)
            }
        } catch (e: Exception) {
            log("dot-grid 보강 건너뜀: ${e.message}")
            null
        }
        val homography = dotGrid?.homography ?: initialHomography
        runCatching { verifyNative(grayCapture.frameStore, homography, screenW, screenH) }
            .onFailure { log("네이티브 검증 실패: ${it.message}") }
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
        delay(PATTERN_SETTLE_MS)
        val triBlackStores = captureTriStores(cap, FRAMES_PER_PATTERN)
        val blackStore = triBlackStores.getValue(CameraEnumerator.ROLE_MAIN)
        val blackAvg = averageGrayFromStore(blackStore)
        // 채널마다 네이티브 평균(중간 배열은 네이티브 메모리). 네이티브가 없으면 Kotlin RGB 평균.
        val blackRgbAvg = withContext(Dispatchers.Default) {
            val r = NativePixel.average(blackStore.files, 0)
            val g = r?.let { NativePixel.average(blackStore.files, 1) }
            val b = g?.let { NativePixel.average(blackStore.files, 2) }
            if (r != null && g != null && b != null) RgbImage(r.w, r.h, r.data, g.data, b.data) else null
        } ?: averageRgbFromStore(blackStore)
        blackStore.delete()

        // ── 3.2 묶음 촬영: 분석 전에 전면 패턴을 전부 먼저 찍어 둔다 ─────────
        // 색 균일화 레이어는 휘도 기준보다 먼저 정한다. 레이어가 위치별로 채널을 낮추면 밝기 분포도
        // 바뀌므로, 휘도 기준(gray70/gray25/RGB30)을 레이어 없이 재면 반복 보정과 조건이 달라져
        // 보정 맵이 매번 기준선에 지게 된다(평균 밝기 손실 0%, 맵 미채택).
        captureBatch(client, cap, RGB70_PATTERNS.map { it to FRAMES_PER_PATTERN }, "3/10 색")
        val rgb70 = try {
            // 색 비율은 채널끼리 나누므로 카메라 flat-field는 상쇄된다(여기선 아직 없음).
            measureRgbPatterns(
                client, cap, blackRgbAvg, homography, screenW, screenH, screenW, screenH,
                RGB70_PATTERNS, FRAMES_PER_PATTERN, "deviation_rgb70",
                flatField = null,
                makeHeatmaps = true,
                includeGain = false,
                keepGrids = true,
            ).let { m ->
                applyChromaUniformity(client, cap, blackRgbAvg, homography, m, screenW, screenH, screenW, screenH)
                m.copy(grids = LinkedHashMap(), confidences = LinkedHashMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("RGB70 채널 측정 건너뜀: ${e.message}")
            emptyRgbMeasurement()
        }
        val chromaOn = chromaBefore != null
        captureBatch(
            client, cap,
            (if (chromaOn) listOf("gray70" to FRAMES_PER_PATTERN) else emptyList()) +
                listOf(LOW_LIGHT_PATTERN to LOW_LIGHT_FRAMES) +
                RGB30_PATTERNS.map { it to LOW_RGB_FRAMES },
            "3/10",
        )

        // ── 4. 휘도맵 + 목표값 ──────────────────────────────────
        status("4/10 gray70 휘도맵 계산 (기기 해상도 1:1)...")
        val gw = screenW
        val gh = screenH
        log("보정맵 해상도: ${gw}x${gh} (대상 기기 해상도 1:1)")
        val smoothRadius = mapSmoothRadius(det.quad, gw, gh)
        log("보정맵 스무딩 반경 ${smoothRadius}셀 — 카메라 해상 한계 이하 노이즈/무아레가 맵에 새겨지지 않도록 적응 조정")
        val lumaBeforeRaw = fullResGrid(grayCapture.frameStore, NativePixel.MODE_GRAY, homography, blackAvg, screenW, screenH, gw, gh)
            ?: withContext(Dispatchers.Default) { Analyzer.lumaGrid(grayAvg, blackAvg, homography, screenW, screenH, gw, gh) }
        run {
            // 원본 해상도 격자가 분석 이미지 격자와 같은 밝기 척도인지 한 번 확인(좌표 변환 오류면 비가 1에서 벗어난다)
            val half = withContext(Dispatchers.Default) { Analyzer.lumaGrid(grayAvg, blackAvg, homography, screenW, screenH, gw, gh) }
            if (half !== lumaBeforeRaw) {
                val ratio = Analyzer.stats(lumaBeforeRaw).median / Analyzer.stats(half).median
                var ss = 0.0
                for (i in half.indices) { val d = lumaBeforeRaw[i] / half[i] / ratio - 1.0; ss += d * d }
                log("원본 해상도 격자 확인: 분석 해상도 대비 중앙값 비 ${fmt(ratio)}, 차이 RMS ${pct(Math.sqrt(ss / half.size).toFloat())}(해상도 차이로 생기는 세부)")
            }
        }
        val blackFullGrid = withContext(Dispatchers.Default) {
            Analyzer.lumaGrid(blackAvg, null, homography, screenW, screenH, gw, gh)
        }
        // 3위치 비네팅 보정값이 있으면 그것으로 카메라 flat-field를 만든다(화면 번인과 분리된 값).
        // 없으면 기존처럼 화면 자체에서 방사형으로 추정한다(번인 일부가 섞일 수 있음).
        val flatField = vignetteModel?.let { m ->
            log("카메라 flat-field: 3위치 비네팅 보정값 사용")
            withContext(Dispatchers.Default) { modelFlatField(m, homography, grayAvg.w, grayAvg.h, screenW, screenH, gw, gh) }
        } ?: withContext(Dispatchers.Default) {
            Analyzer.radialFlatField(lumaBeforeRaw, gw, gh)
        }
        val edgeFalloff = withContext(Dispatchers.Default) {
            Analyzer.edgeFalloff(lumaBeforeRaw, gw, gh)
        }
        val flatStrength = Analyzer.flatFieldStrength(flatField)
        if (vignetteModel != null && flatStrength > 0.25f) {
            log(
                "경고: 비네팅 보정값이 가장자리를 ${pct(flatStrength)}나 보정함" +
                    (if (vignetteModel?.rMax?.isFinite() == true) "" else "(보정 범위 정보 없는 예전 값)") +
                    " — 탭과 카메라 거리·박스를 바꿨다면 '비네팅 보정 (3위치)'을 지금 거치로 다시 해 주세요. " +
                    "맞지 않는 값이면 가장자리를 과하게 어둡게 만듭니다."
            )
        }
        val lumaBeforeRadial = withContext(Dispatchers.Default) {
            Analyzer.applyFlatField(lumaBeforeRaw, flatField)
        }
        // 중간 신뢰도 격자(기본·프레임 안정도)는 합친 뒤 버리고, 리포트에는 평균값만 남긴다(상주 메모리 절감).
        var graySpanMs = grayCapture.frameStore.spanMs
        var flickerStability70 = 0f
        val confidence70Single = run {
            val baseConfidence70 = withContext(Dispatchers.Default) {
                Analyzer.confidenceGrid(lumaBeforeRaw, blackFullGrid, strayWarn = 0.05f)
            }
            val flickerConfidence70 = temporalLumaConfidence(
                grayCapture.frameStore, blackAvg, homography, screenW, screenH, gw, gh, label = "gray70",
            )
            flickerStability70 = Analyzer.meanConfidence(flickerConfidence70)
            withContext(Dispatchers.Default) { Analyzer.combineConfidence(baseConfidence70, flickerConfidence70) }
        }

        // ── 3.5 동시 3각 교차 분석: 화각 간 불일치 구간을 보정에서 무시 ──
        val cross = analyzeCrossRoles(
            triGrayStores,
            triBlackStores,
            markerTriFrames,
            grayAvg,
            homography,
            lumaBeforeRadial,
            screenW,
            screenH,
            gw,
            gh,
        )
        // 리포트용 카메라 원본 회색 이미지는 지금 PNG로 바꿔 두고 이미지 자체는 놓는다.
        val grayAvgPng = if (cross != null) withContext(Dispatchers.Default) { Analyzer.grayImagePng(grayAvg) } else null
        val confidence70PreWb = if (cross != null) {
            withContext(Dispatchers.Default) {
                Analyzer.combineConfidence(confidence70Single, crossAgreement(cross))
            }
        } else {
            confidence70Single
        }
        // 원형(잔여 비네팅) 보정: 반복 보정은 같은 메인 카메라로 다시 재는 폐루프라 카메라 쪽 원형은
        // 반복으로 사라지지 않고 오히려 화면에 새겨진다. 초광각 기준 저주파 비율로 메인 flat-field를 고친다.
        val lumaBeforePreWb = cross?.referenceBlocks?.takeIf { vignetteModel == null }?.let { ref ->
            withContext(Dispatchers.Default) {
                val residual = LowFrequency.cameraResidual(lumaBeforeRadial, ref, gw, gh)
                val up = LowFrequency.upsample(residual, gw, gh)
                for (i in flatField.indices) flatField[i] *= up[i]
                val maxDev = residual.values.maxOf { Math.abs(it - 1f) }
                log("초광각 기준 메인 카메라 잔여 비네팅 보정: 블록 ${residual.bw}x${residual.bh}, 최대 ${pct(maxDev)}")
                FloatArray(lumaBeforeRadial.size) { (lumaBeforeRadial[it] / up[it].coerceAtLeast(0.5f)).coerceAtLeast(1e-5f) }
            }
        } ?: lumaBeforeRadial.also {
            if (vignetteModel != null) log("초광각 기준 잔여 비네팅 보정 생략: 3위치 비네팅 보정값 사용 중")
            else if (cross != null) log("초광각 기준 잔여 비네팅 보정 생략: 초광각 화면 정합 실패")
        }
        // 색 균일화 레이어를 켠 상태에서 다시 찍은 gray70을 휘도 기준으로 쓴다(카메라 flat-field는 위에서 확정).
        val (lumaBefore, confidence70) = if (chromaOn) {
            val base = capturePatternGray(client, cap, "gray70", FRAMES_PER_PATTERN)
            graySpanMs = base.frameStore.spanMs
            val raw = withContext(Dispatchers.Default) {
                Analyzer.lumaGrid(base.average, blackAvg, homography, screenW, screenH, gw, gh)
            }
            val luma = withContext(Dispatchers.Default) { Analyzer.applyFlatField(raw, flatField) }
            val baseConf = withContext(Dispatchers.Default) { Analyzer.confidenceGrid(raw, blackFullGrid, strayWarn = 0.05f) }
            val flicker = temporalLumaConfidence(base.frameStore, blackAvg, homography, screenW, screenH, gw, gh, label = "gray70(색 레이어)")
            flickerStability70 = Analyzer.meanConfidence(flicker)
            val conf = withContext(Dispatchers.Default) {
                val single = Analyzer.combineConfidence(baseConf, flicker)
                if (cross == null) single else Analyzer.combineConfidence(single, crossAgreement(cross))
            }
            log("휘도 기준: 색 균일화 레이어를 켠 상태로 gray70 재측정(반복 보정과 같은 조건)")
            luma to conf
        } else {
            lumaBeforePreWb to confidence70PreWb
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
        val target = Analyzer.correctionTarget(lumaBefore, gw, gh, maxAttenuation)
        val strayRatio = blackStats.median / statsBefore.median
        log(
            "카메라 flat-field 보정: radial max ${pct(Analyzer.flatFieldStrength(flatField))}, " +
                "raw edge falloff ${pct(edgeFalloff)}, " +
                "gray70 confidence 평균 ${pct(Analyzer.meanConfidence(confidence70))}, " +
                "프레임 안정도 ${pct(flickerStability70)}, " +
                "flicker span ${fmtSeconds(graySpanMs)}"
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
                "최대 ${pct(statsBefore.maxDev)}, 블록 최대 ${pct(Analyzer.blockMaxDev(lumaBefore, gw, gh))}"
        )

        // ── 4.5 희소 점등 측광: 글씨 획·버튼 테두리 단위 고주파 층 ──────
        val strokeDetail = if (STROKE_DETAIL_ENABLED) {
            if (chkRgbDots.isChecked) {
                measureStrokeDetailRgb(client, cap, homography, screenW, screenH, gw, gh, smoothRadius, confidence70, lumaBefore)
            } else {
                measureStrokeDetail(client, cap, homography, screenW, screenH, gw, gh, smoothRadius, confidence70, lumaBefore)
            }
        } else {
            null
        }
        strokeDetailReport = strokeDetail?.report

        // ── 5. 저휘도 맵 측정: gray25도 보정맵에 혼합 ─────────────
        status("5/10 $LOW_LIGHT_PATTERN 저휘도 촬영 (${LOW_LIGHT_FRAMES}장)...")
        val lowBeforeCapture = capturePatternGray(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
        val lowBeforeAvg = lowBeforeCapture.average
        val lumaLowBeforeRaw = fullResGrid(lowBeforeCapture.frameStore, NativePixel.MODE_GRAY, homography, blackAvg, screenW, screenH, gw, gh)
            ?: withContext(Dispatchers.Default) { Analyzer.lumaGrid(lowBeforeAvg, blackAvg, homography, screenW, screenH, gw, gh) }
        val lumaLowBefore = withContext(Dispatchers.Default) {
            Analyzer.applyFlatField(lumaLowBeforeRaw, flatField)
        }
        val lowSpanMs = lowBeforeCapture.frameStore.spanMs
        var flickerStabilityLow = 0f
        val confidenceLow = run {
            val baseConfidenceLow = withContext(Dispatchers.Default) {
                Analyzer.confidenceGrid(lumaLowBeforeRaw, blackFullGrid, strayWarn = LOW_LIGHT_STRAY_WARN)
            }
            val flickerConfidenceLow = temporalLumaConfidence(
                lowBeforeCapture.frameStore, blackAvg, homography, screenW, screenH, gw, gh, label = LOW_LIGHT_PATTERN,
            )
            flickerStabilityLow = Analyzer.meanConfidence(flickerConfidenceLow)
            withContext(Dispatchers.Default) {
                val single = Analyzer.combineConfidence(baseConfidenceLow, flickerConfidenceLow)
                if (cross == null) single else Analyzer.combineConfidence(single, crossAgreement(cross))
            }
        }
        val lowStatsBefore = Analyzer.stats(lumaLowBefore)
        val lowTarget = Analyzer.correctionTarget(lumaLowBefore, gw, gh, maxAttenuation)
        val lowStrayRatio = blackStats.median / lowStatsBefore.median
        val lowSignalValid = lowStrayRatio <= LOW_LIGHT_STRAY_WARN
        val lowLightWeight = if (lowSignalValid) LOW_LIGHT_GAIN_WEIGHT else LOW_LIGHT_WEAK_GAIN_WEIGHT
        outputGamma = if (lowSignalValid) {
            Analyzer.estimateDisplayGamma(statsBefore.median, lowStatsBefore.median, GRAY70_CODE, LOW_LIGHT_CODE)
        } else {
            null
        }
        log(
            outputGamma?.let { "대상 패널 γ 추정 ${fmt(it)} (gray70/$LOW_LIGHT_PATTERN, 출력 gain^(1/γ) 변환에 사용)" }
                ?: "대상 패널 γ 추정 실패 — Target 기본값 2.2로 출력 변환"
        )
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
                "프레임 안정도 ${pct(flickerStabilityLow)}, " +
                "flicker span ${fmtSeconds(lowSpanMs)}"
        )

        // ── 5.5 레벨별 응답 곡선: 곱하기 맵을 gray70 하나가 아니라 곡선 전체에 맞춘다 ─────
        levelComp = null
        curveTarget70 = null
        curveReport = null
        val curveBefore = measureLevelCurve(client, cap, blackAvg, homography, flatField, screenW, screenH, gw, gh, "5/10")
        setupLevelCompensation(curveBefore, lumaBefore, gw, gh)

        // ── 6. RGB 채널별 측정: 70% 진단 + 30% 보정 혼합 ─────────
        status("6/10 RGB 70%/30% 채널별 측정...")
        // 측정 원본(gain 4장)은 신뢰도 제한을 건 사본만 남기고 바로 버린다(2048×1536 격자 한 장 12.6MB).
        val rgb30 = run {
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
            // 측정 원본 gain은 이후 쓰지 않으므로 제자리로 제한해 복사본 4장(약 50MB)을 만들지 않는다.
            channelGains = rgb30Measured.channelGains.mapValuesTo(LinkedHashMap()) { (_, gain) ->
                Analyzer.limitGainByConfidenceInPlace(gain, confidence70, maxAttenuation)
            },
            aggregateGain = rgb30Measured.aggregateGain?.let { Analyzer.limitGainByConfidenceInPlace(it, confidence70, maxAttenuation) },
            // 초기 RGB30 신뢰도 격자 3장은 gain 계산에만 쓰고 이후엔 안 쓴다(반복은 재측정 값을 씀).
            confidences = LinkedHashMap(),
        )
        rgb30
        }
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

        log("힙 사용: ${heapText()}")

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
            strokeDetail = strokeDetail,
        )
        var currentDetail = strokeDetail
        suspend fun finish(iterated: CorrectionIterations) {
        // ── 7.5 픽셀 단위 검증·재귀 개선: 전면 gray/RGB 격자로는 획 단위 잔차가 안 보인다 ──
        val refined = currentDetail?.let {
            // 반복 중 미리 찍어 둔 묶음을 지우고 GC를 권해 풀해상도 희소 측정 여유를 만든다
            clearPrefetched()
            Runtime.getRuntime().gc()
            log("픽셀 단위 검증 시작, ${heapText()}")
            refinePixelDetail(client, cap, homography, screenW, screenH, smoothRadius, confidence70, lumaBefore, iterated, it, gw, gh)
        }
        if (refined != null) currentDetail = refined.second
        val correction = refined?.first ?: iterated
        val bestMap = correction.bestMap
        val bestGain = bestMap.effectiveGain
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
        val finalGray70Passed = finalStatsAfter != null && finalLumaAfter != null &&
            uniformityPassed(finalStatsAfter, finalLumaAfter, gw, gh)
        val finalLowPassed = finalLowStatsAfter != null && finalLowLumaAfter != null &&
            uniformityPassed(finalLowStatsAfter, finalLowLumaAfter, gw, gh)
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
        finalLumaAfter?.let { log("gray70 블록 최대 편차: 보정 후 ${pct(Analyzer.blockMaxDev(it, gw, gh))}") }
        log(
            "판정: $verdict (gray70/$LOW_LIGHT_PATTERN 모두 RMS ≤ ${pct(PASS_RMS)}, P95 ≤ ${pct(PASS_P95)}, " +
                "블록 최대 ≤ ${pct(PASS_BLOCK)})"
        )

        // 레벨별 곡선 재측정: 보정 전후를 레벨마다 비교한다(곡선 그래프용)
        if (curveBefore.any { it != null } && !invalid) {
            val curveAfter = measureLevelCurve(client, cap, blackAvg, homography, flatField, screenW, screenH, gw, gh, "9/10")
            reportLevelCurve(curveBefore, curveAfter)
        }

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
            flickerStability70 = flickerStability70,
            graySpanMs = graySpanMs,
            confidenceLow = confidenceLow,
            flickerStabilityLow = flickerStabilityLow,
            lowSpanMs = lowSpanMs,
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
            grayAvgPng = grayAvgPng,
        )
        log("리포트 저장: ${dir.absolutePath}")
        status("측정 완료 — $verdict, 개선율 ${pct(improvement.toFloat())}")
        btnToggle.isEnabled = true
        correctionOn = true

        // 이 측정의 기준(화면 위치·black·flat-field·신뢰도·목표)과 최적 맵을 그대로 이어받아 더 돌린다.
        extraImprovement = { n ->
            log("──────────────")
            log("추가 개선 ${n}회 시작 — 직전 최적 맵(균일도 점수 ${fmt(correction.bestScore)}x)에서 이어서 반복")
            val more = runCorrectionIterations(
                client = client, cap = cap, screenW = screenW, screenH = screenH, gw = gw, gh = gh,
                lumaBefore = lumaBefore, lumaLowBefore = lumaLowBefore,
                confidence70 = confidence70, confidenceLow = confidenceLow,
                lowLightWeight = lowLightWeight, rgb30 = rgb30, lowRgbWeight = lowRgbWeight,
                blackAvg = blackAvg, blackRgbAvg = blackRgbAvg, blackFullGrid = blackFullGrid,
                homography = homography, det = det, cornerMapping = cornerMapping,
                statsBefore = statsBefore, target = target, lowTarget = lowTarget,
                flatField = flatField, smoothRadius = smoothRadius, strokeDetail = currentDetail,
                previous = correction, maxIterations = n,
            )
            finish(more)
        }
        }
        finish(correction)
    }

    /**
     * 색 균일화: RGB70 채널 격자의 블록 비율로 위치별 색 틀어짐을 구하고, 강한 채널만 낮추는
     * 위치별 RGB 감쇠 맵을 탭의 화이트밸런스 레이어로 보낸다. 밝기 보정맵과 따로 저장·적용되며,
     * 이후 반복 보정의 gray 재측정에는 이 레이어가 켜진 상태가 반영돼 밝기 손실을 다시 맞춘다.
     */
    private suspend fun applyChromaUniformity(
        client: ControlClient,
        cap: CaptureController,
        blackRgbAvg: RgbImage,
        homography: Homography,
        rgb70: RgbMeasurement,
        gw: Int,
        gh: Int,
        screenW: Int,
        screenH: Int,
    ) {
        val r = rgb70.grids["red70"]
        val g = rgb70.grids["green70"]
        val b = rgb70.grids["blue70"]
        if (r == null || g == null || b == null) {
            chromaBefore = null
            log("색 균일화 생략: RGB70 측정 없음")
            return
        }
        try {
            val chroma = withContext(Dispatchers.Default) { LowFrequency.chroma(r, g, b, gw, gh, LOW_FREQ_BLOCKS) }
            chromaBefore = chroma.meanSpread() to chroma.p95Spread()
            // 보정은 잘게(약 5px 블록) 잡고, 편차 지표는 이전과 비교되도록 24칸 블록으로 낸다
            var gains = withContext(Dispatchers.Default) {
                LowFrequency.chromaGains(LowFrequency.chroma(r, g, b, gw, gh, CHROMA_BLOCKS), CHROMA_MAX_ATTENUATION)
            }
            suspend fun send(gs: Triple<LowFrequency.Blocks, LowFrequency.Blocks, LowFrequency.Blocks>) {
                val png = withContext(Dispatchers.Default) {
                    Analyzer.toRgbAttenuationPng(
                        { c -> LowFrequency.upsample(when (c) { 0 -> gs.first; 1 -> gs.second; else -> gs.third }, gw, gh) },
                        gw, gh, screenW, screenH, CHROMA_MAX_ATTENUATION,
                    )
                }
                withContext(Dispatchers.IO) {
                    client.request(
                        JSONObject()
                            .put("cmd", Protocol.CMD_APPLY_WHITE_BALANCE)
                            .put("width", screenW)
                            .put("height", screenH)
                            .put("maxAttenuation", CHROMA_MAX_ATTENUATION.wire())
                            .put("checksumMd5", md5(png))
                            .put("sourceDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
                            .put("data", Base64.encodeToString(png, Base64.NO_WRAP)),
                        timeoutMs = 120_000,
                    )
                }
            }
            send(gains)
            // 지난 측정 맵 대신 무감쇠(중립) 맵을 올리고 보정을 켜서 색 레이어만 켜진 상태를 만든다.
            whiteBalanceActive = true
            applyGainMap(client, CorrectionMap(FloatArray(gw * gh) { 1f }, emptyMap(), 0f, isBaseline = true), gw, gh, screenW, screenH)
            fun maxAttOf(gs: Triple<LowFrequency.Blocks, LowFrequency.Blocks, LowFrequency.Blocks>) =
                listOf(gs.first, gs.second, gs.third).maxOf { blk -> blk.values.maxOf { 1f - it } }
            log(
                "색 균일화: 블록 색 편차 평균 ${pct(chroma.meanSpread())} / P95 ${pct(chroma.p95Spread())} " +
                    "(가장 강한/약한 채널 비 − 1), ${gains.first.bw}x${gains.first.bh} 블록, 채널 감쇠 최대 ${pct(maxAttOf(gains))} — 탭 화이트밸런스 레이어로 적용"
            )
            // 색 레이어를 켠 채 다시 재서 잔여 색 틀어짐을 고친다(한 번에 맞추면 서브픽셀 간섭·감마로 어긋난다)
            var lastSpread = chroma.meanSpread()
            var prevGains = gains
            for (round in 1..CHROMA_REFINE_ROUNDS) {
                captureBatch(client, cap, RGB70_PATTERNS.map { it to FRAMES_PER_PATTERN }, "3/10 색 재조정 $round")
                val m = measureRgbPatterns(
                    client, cap, blackRgbAvg, homography, screenW, screenH, gw, gh,
                    RGB70_PATTERNS, FRAMES_PER_PATTERN, "chroma_refine",
                    flatField = null, makeHeatmaps = false, includeGain = false, keepGrids = true,
                )
                val rr = m.grids["red70"] ?: break
                val rg = m.grids["green70"] ?: break
                val rb = m.grids["blue70"] ?: break
                val coarse = withContext(Dispatchers.Default) { LowFrequency.chroma(rr, rg, rb, gw, gh, LOW_FREQ_BLOCKS) }
                val spread = coarse.meanSpread()
                if (!(spread < lastSpread)) {
                    log("색 재조정 $round: 편차 평균 ${pct(spread)} (직전 ${pct(lastSpread)}) — 개선 없음, 이전 레이어로")
                    if (round > 1) {
                        gains = prevGains
                        send(gains)
                        applyGainMap(client, CorrectionMap(FloatArray(gw * gh) { 1f }, emptyMap(), 0f, isBaseline = true), gw, gh, screenW, screenH)
                    }
                    break
                }
                if (round == CHROMA_REFINE_ROUNDS) {
                    log("색 재조정 $round: 편차 평균 ${pct(lastSpread)} → ${pct(spread)} / P95 ${pct(coarse.p95Spread())} (확인)")
                    break
                }
                val next = withContext(Dispatchers.Default) {
                    LowFrequency.refineChromaGains(gains, LowFrequency.chroma(rr, rg, rb, gw, gh, CHROMA_BLOCKS), CHROMA_MAX_ATTENUATION)
                }
                log("색 재조정 $round: 편차 평균 ${pct(lastSpread)} → ${pct(spread)} / P95 ${pct(coarse.p95Spread())}, 잔여 보정 적용(채널 감쇠 최대 ${pct(maxAttOf(next))})")
                lastSpread = spread
                prevGains = gains
                gains = next
                send(gains)
                applyGainMap(client, CorrectionMap(FloatArray(gw * gh) { 1f }, emptyMap(), 0f, isBaseline = true), gw, gh, screenW, screenH)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            chromaBefore = null
            log("색 균일화 실패 — 밝기 보정만 진행: ${e.message}")
        }
    }

    /** 레벨마다 전면 회색을 찍어 중앙값 1로 정규화한 블록(48칸)을 돌려준다. 실패한 레벨은 null. */
    private suspend fun measureLevelCurve(
        client: ControlClient,
        cap: CaptureController,
        blackAvg: GrayImage,
        homography: Homography,
        flatField: FloatArray,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        step: String,
    ): List<LowFrequency.Blocks?> {
        captureBatch(client, cap, CURVE_LEVELS.map { "gray$it" to CURVE_FRAMES }, "$step 레벨 곡선")
        return CURVE_LEVELS.map { level ->
            try {
                val capture = capturePatternGray(client, cap, "gray$level", CURVE_FRAMES)
                try {
                    val raw = fullResGrid(capture.frameStore, NativePixel.MODE_GRAY, homography, blackAvg, screenW, screenH, gw, gh)
                    withContext(Dispatchers.Default) {
                        val luma = Analyzer.applyFlatField(
                            raw ?: Analyzer.lumaGrid(capture.average, blackAvg, homography, screenW, screenH, gw, gh), flatField,
                        )
                        ResponseCurve.normalized(LowFrequency.blocks(luma, gw, gh, CURVE_BLOCKS))
                    }
                } finally {
                    capture.frameStore.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("레벨 곡선 gray$level 측정 실패: ${e.message}")
                null
            }
        }
    }

    private fun setupLevelCompensation(curve: List<LowFrequency.Blocks?>, lumaBefore: FloatArray, gw: Int, gh: Int) {
        val ref = curve[CURVE_LEVELS.indexOf(70)]
        val usable = curve.withIndex().filter { it.value != null }
        if (ref == null || usable.size < 3) {
            log("레벨 곡선 부족(${usable.size}레벨) — gray70·gray25 혼합으로 진행")
            return
        }
        val eff = ResponseCurve.effective(usable.map { it.value!! }, FloatArray(usable.size) { CURVE_WEIGHTS[usable[it].index] })
        val q = ResponseCurve.compensation(ref, eff)
        levelComp = q
        curveTarget70 = Analyzer.correctionTarget(ResponseCurve.divide(lumaBefore, q, gw, gh), gw, gh, maxAttenuation)
        val qv = q.values.filter { it.isFinite() }.sorted()
        log(
            "레벨 곡선(보정 전) 블록 RMS: " + CURVE_LEVELS.indices.joinToString(", ") { i ->
                "${CURVE_LEVELS[i]}% ${curve[i]?.let { pct(ResponseCurve.blockRms(it)) } ?: "-"}"
            } + " → 가중 곡선 ${pct(ResponseCurve.blockRms(ResponseCurve.normalized(eff)))}"
        )
        log("레벨 보상 q(gray70 ÷ 곡선): ${fmt(qv.first())} ~ ${fmt(qv.last())}, 목표 ${fmt(curveTarget70!!)} — 반복은 곡선 기준으로 보정")
    }

    private fun reportLevelCurve(before: List<LowFrequency.Blocks?>, after: List<LowFrequency.Blocks?>) {
        val levels = JSONArray()
        val parts = ArrayList<String>()
        for (i in CURVE_LEVELS.indices) {
            val b = before[i]?.let { ResponseCurve.blockRms(it) } ?: Float.NaN
            val a = after[i]?.let { ResponseCurve.blockRms(it) } ?: Float.NaN
            levels.put(JSONObject().put("level", CURVE_LEVELS[i]).put("weight", CURVE_WEIGHTS[i].toDouble())
                .put("blockRmsBefore", b.toDouble()).put("blockRmsAfter", a.toDouble()))
            parts += "${CURVE_LEVELS[i]}% ${pct(b)}→${pct(a)}"
        }
        log("레벨 곡선 블록 RMS(보정 전→후): " + parts.joinToString(", "))
        curveReport = JSONObject().put("used", levelComp != null).put("blocksLong", CURVE_BLOCKS).put("levels", levels)
    }

    private suspend fun initialGain(
        lumaBefore: FloatArray,
        lumaLowBefore: FloatArray,
        confidence70: FloatArray,
        confidenceLow: FloatArray,
        lowLightWeight: Float,
        rgb30: RgbMeasurement,
        lowRgbWeight: Float,
        gw: Int,
        gh: Int,
        smoothRadius: Int,
    ): FloatArray = withContext(Dispatchers.Default) {
        val grayMixedGain = run {
            // 곡선이 있으면 gray25도 곡선에 들어 있으므로 따로 섞지 않는다
            val gray70Gain = Analyzer.gainGrid(levelAdjusted(lumaBefore, gw, gh), gw, gh, maxAttenuation, confidence70, smoothRadius)
            if (levelComp != null) {
                gray70Gain
            } else {
                val lowLightGain = Analyzer.gainGrid(lumaLowBefore, gw, gh, maxAttenuation, confidenceLow, smoothRadius)
                Analyzer.mixGainGrids(gray70Gain, lowLightGain, lowLightWeight, maxAttenuation)
            }
        }
        val mixed = rgb30.aggregateGain?.let { Analyzer.mixGainGrids(grayMixedGain, it, lowRgbWeight, maxAttenuation) }
            ?: grayMixedGain
        Analyzer.limitGainByConfidenceInPlace(mixed, confidence70, maxAttenuation)
    }

    /** 반복 1회의 RGB30 재측정과 채널별 refine(감쇠 누적 평균). 격자·신뢰도는 패턴마다 버린다. */
    private suspend fun measureRgb30WithRefine(
        client: ControlClient,
        cap: CaptureController,
        blackRgbAvg: RgbImage,
        hAfter: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        flatField: FloatArray,
        gain: FloatArray,
        rgb30Targets: Map<String, Float>,
        smoothRadius: Int,
    ): Pair<RgbMeasurement, FloatArray?> {
        if (rgb30Targets.isEmpty()) return emptyRgbMeasurement() to null
        val attSum = FloatArray(gain.size)
        var count = 0
        val m = measureRgbPatterns(
            client, cap, blackRgbAvg, hAfter, screenW, screenH, gw, gh,
            RGB30_PATTERNS, LOW_RGB_FRAMES, "iteration",
            flatField = flatField,
            makeHeatmaps = false,
            includeGain = false,
            onGrid = { pattern, grid, confidence ->
                val t = rgb30Targets[pattern]
                if (t != null) {
                    val r = withContext(Dispatchers.Default) {
                        Analyzer.refineGain(gain, grid, t, DAMPING_ALPHA, gw, gh, maxAttenuation, confidence, smoothRadius)
                    }
                    for (i in attSum.indices) attSum[i] += 1f - r[i]
                    count++
                }
            },
        )
        if (count == 0) return m to null
        // averageGainGrids와 같은 평균: 감쇠 평균을 gain으로 되돌리고 상한으로 자른다.
        val n = count.toFloat()
        val maxAtt = maxAttenuation
        for (i in attSum.indices) attSum[i] = (1f - attSum[i] / n).coerceIn(1f - maxAtt, 1f)
        return m to attSum
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
        strokeDetail: StrokeDetail?,
        /** 있으면 그 최적 맵에서 출발하고, 선택도 그 점수와 비교해 더 나은 맵만 바꾼다. */
        previous: CorrectionIterations? = null,
        maxIterations: Int = MAX_ITERATIONS,
    ): CorrectionIterations {
        // 직전 최적 맵에서 이어 갈 때는 초기 gain을 다시 계산하지 않는다(격자 6장가량의 과도 메모리).
        var gain = if (previous != null && !previous.bestMap.isBaseline) {
            previous.bestMap.gain.copyOf()
        } else {
            initialGain(lumaBefore, lumaLowBefore, confidence70, confidenceLow, lowLightWeight, rgb30, lowRgbWeight, gw, gh, smoothRadius)
        }
        val baselineLowStats = Analyzer.stats(lumaLowBefore)
        val baselineScore = maxOf(
            optimizationScore(statsBefore),
            optimizationScore(baselineLowStats),
        ) * (1f - lowRgbWeight) +
            (if (rgb30.stats.isNotEmpty()) rgb30.meanRms / PASS_RMS else 0f) * lowRgbWeight
        val selection = MeasurementPolicy.Selection(FloatArray(gain.size) { 1f }, baselineScore)
        var bestRms = statsBefore.rmsDev
        var bestLowRms = baselineLowStats.rmsDev
        var bestLowRgbRms = rgb30.meanRms
        if (previous != null && !previous.bestMap.isBaseline) {
            selection.consider(previous.bestMap.gain, previous.bestScore)
            bestRms = previous.bestRms
            bestLowRms = previous.bestLowRms
            bestLowRgbRms = previous.bestLowRgbRms
        }
        var prevScore: Float? = null
        // 점수가 최적보다 나빠지면 최적 맵 쪽으로 반 걸음 되돌리고 보폭을 줄인다(되먹임 과보정 억제)
        var stepAlpha = DAMPING_ALPHA
        var divergeCount = 0
        var stalledCount = 0
        var invalid = false
        var lastAppliedIsBest = true
        val iterRmsList = ArrayList<Float>()
        val iterLowRmsList = ArrayList<Float>()
        val iterLowRgbRmsList = ArrayList<Float>()
        val rgb30Targets = LinkedHashMap(rgb30.targets)

        withContext(Dispatchers.IO) { client.showPattern("gray70") }
        var iter = 0
        while (iter < maxIterations) {
            iter++
            status("7/10 반복 $iter/$maxIterations — 보정맵 전송·적용...")
            applyGainMap(
                client,
                CorrectionMap(gain, rgb30.channelGains, lowRgbWeight, detail = strokeDetail?.gain, maxAttenuation = maxAttenuation, shareChannels = true,
                    detailRgb = strokeDetail?.channelGains),
                gw, gh, screenW, screenH,
            )
            lastAppliedIsBest = false

            captureBatch(
                client, cap,
                listOf("gray70" to FRAMES_PER_PATTERN, LOW_LIGHT_PATTERN to LOW_LIGHT_FRAMES) +
                    if (rgb30Targets.isNotEmpty()) RGB30_PATTERNS.map { it to LOW_RGB_FRAMES } else emptyList(),
                "8/10 반복 $iter",
            )
            status("8/10 반복 $iter/$maxIterations — gray70/$LOW_LIGHT_PATTERN/RGB30 평가...")
            val afterCapture = capturePatternGray(client, cap, "gray70", FRAMES_PER_PATTERN)
            val afterAvg = afterCapture.average
            val detAfter = withContext(Dispatchers.Default) { ScreenDetector.detect(afterAvg) }
            checkNotNull(detAfter) { "반복 화면 검출 실패 — 재측정 필요" }
            check(MeasurementPolicy.geometryStable(det.quad, detAfter.quad)) {
                "화면 이동 — black/flat-field 재측정 필요 (모서리 최대 ${fmt(MeasurementPolicy.maxCornerShift(det.quad, detAfter.quad))}px, " +
                    "허용 ${fmt(MeasurementPolicy.geometryTolerance(det.quad))}px)"
            }
            val hAfter = checkNotNull(Analyzer.buildHomography(detAfter.quad, screenW, screenH, cornerMapping))
            val lumaAfterRaw = fullResGrid(afterCapture.frameStore, NativePixel.MODE_GRAY, hAfter, blackAvg, screenW, screenH, gw, gh)
                ?: withContext(Dispatchers.Default) { Analyzer.lumaGrid(afterAvg, blackAvg, hAfter, screenW, screenH, gw, gh) }
            val lumaAfter = withContext(Dispatchers.Default) {
                compensateDetail(Analyzer.applyFlatField(lumaAfterRaw, flatField), strokeDetail)
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
            val lumaLowAfterRaw = fullResGrid(lowAfterCapture.frameStore, NativePixel.MODE_GRAY, hAfter, blackAvg, screenW, screenH, gw, gh)
                ?: withContext(Dispatchers.Default) { Analyzer.lumaGrid(lowAfterAvg, blackAvg, hAfter, screenW, screenH, gw, gh) }
            val lumaLowAfter = withContext(Dispatchers.Default) {
                compensateDetail(Analyzer.applyFlatField(lumaLowAfterRaw, flatField), strokeDetail)
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
            // 점수와 다음 gain 후보를 RGB 측정 전에 미리 구해, gray70/gray25 격자·신뢰도 4장을 먼저 놓는다.
            // (멈추게 되면 refine 계산만 버려진다 — 메모리 대신 CPU를 쓴다)
            val grayScore70 = uniformityScore(st, lumaAfter, gw, gh)
            val grayScoreLow = uniformityScore(lowSt, lumaLowAfter, gw, gh)
            val refined70 = withContext(Dispatchers.Default) {
                Analyzer.refineGain(
                    gain, levelAdjusted(lumaAfter, gw, gh), curveTarget70 ?: target, stepAlpha, gw, gh, maxAttenuation,
                    confidenceAfter, smoothRadius
                )
            }
            val refined30 = withContext(Dispatchers.Default) {
                Analyzer.refineGain(
                    gain, lumaLowAfter, lowTarget, stepAlpha, gw, gh, maxAttenuation,
                    confidenceLowAfter, smoothRadius
                )
            }
            // RGB30은 패턴마다 바로 refine해 감쇠 누적 버퍼 한 장에 더하고 격자·신뢰도는 버린다.
            // (반복 함수가 너무 커지면 ART 검증기가 클래스를 거부해 별도 함수로 뺀다)
            val (rgb30After, refinedRgb30) = measureRgb30WithRefine(
                client, cap, blackRgbAvg, hAfter, screenW, screenH, gw, gh, flatField, gain, rgb30Targets, smoothRadius,
            )
            val rgb30AfterMeanRms = rgb30After.meanRms
            val gray70Passed = grayScore70 <= 1f
            val lowLightPassed = grayScoreLow <= 1f
            val bothGrayPassed = gray70Passed && lowLightPassed
            // 맵 선택·수렴 판정은 RMS·P95 연속 점수로 한다. 블록 최대 편차는 가장 나쁜 한 칸이
            // 점수를 지배해(상한으로 못 펴는 블록 31%/2% ≈ 15배) 전체가 좋아져도 기준선을 못 이겼다.
            // 블록 최대 편차는 합격 판정(gray70Passed 등)에만 쓴다.
            val grayScore = maxOf(optimizationScore(st), optimizationScore(lowSt))
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
                    "RGB30 평균 RMS ${pct(rgb30AfterMeanRms)}, 균일도 점수 ${fmt(compositeScore)}x, 힙 ${heapText()}"
            )

            val improvedBest = selection.consider(gain, compositeScore)
            if (improvedBest) {
                bestRms = st.rmsDev
                bestLowRms = lowSt.rmsDev
                bestLowRgbRms = rgb30AfterMeanRms
                lastAppliedIsBest = true
            }
            val backtrack = !improvedBest && !selection.isBaseline &&
                compositeScore > selection.score * 1.01f + 0.01f

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

            val refinedGray = withContext(Dispatchers.Default) {
                Analyzer.mixGainGrids(refined70, refined30, if (levelComp != null) 0f else lowLightWeight, maxAttenuation)
            }
            gain = refinedRgb30?.let { rgbGain ->
                withContext(Dispatchers.Default) {
                    Analyzer.mixGainGrids(refinedGray, rgbGain, lowRgbWeight, maxAttenuation)
                }
            } ?: refinedGray
            gain = Analyzer.limitGainByConfidenceInPlace(gain, confidence70, maxAttenuation)
            if (backtrack) {
                // 이번 맵이 최적보다 나빴다: 다음 맵은 최적 맵과 이번 갱신의 기하 중간, 이후 보폭은 절반
                val best = selection.gain
                for (i in gain.indices) gain[i] = Math.sqrt((gain[i] * best[i]).toDouble()).toFloat()
                stepAlpha = (stepAlpha * 0.5f).coerceAtLeast(0.05f)
                log("반복 $iter: 점수가 최적(${fmt(selection.score)}x)보다 나쁨 — 최적 맵 쪽으로 반 걸음 되돌리고 보폭 α=${fmt(stepAlpha)}")
            }
        }

        val bestMap = selection.correctionMap(rgb30.channelGains, lowRgbWeight, strokeDetail?.gain, maxAttenuation, strokeDetail?.channelGains)
        if (!lastAppliedIsBest) {
            status("9/10 최적 반복 맵으로 롤백 적용...")
            applyGainMap(client, bestMap, gw, gh, screenW, screenH)
        }

        return CorrectionIterations(
            bestMap = bestMap,
            bestScore = selection.score,
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

        captureBatch(
            client, cap,
            listOf("gray70" to FRAMES_PER_PATTERN, LOW_LIGHT_PATTERN to LOW_LIGHT_FRAMES) +
                (if (rgb30Targets.isNotEmpty()) RGB30_PATTERNS.map { it to LOW_RGB_FRAMES } else emptyList()) +
                (if (chromaBefore != null) RGB70_PATTERNS.map { it to FRAMES_PER_PATTERN } else emptyList()),
            "9/10 최종",
        )
        status("9/10 최종 gray70/$LOW_LIGHT_PATTERN/RGB30 평가...")
        val finalCapture = capturePatternGray(client, cap, "gray70", FRAMES_PER_PATTERN)
        val finalAvg = finalCapture.average
        val detFinal = withContext(Dispatchers.Default) { ScreenDetector.detect(finalAvg) }
        checkNotNull(detFinal) { "최종 화면 검출 실패 — 평가 무효" }
        check(MeasurementPolicy.geometryStable(referenceQuad, detFinal.quad)) {
            "최종 화면 이동 — black/flat-field 재측정 필요 (모서리 최대 ${fmt(MeasurementPolicy.maxCornerShift(referenceQuad, detFinal.quad))}px, " +
                "허용 ${fmt(MeasurementPolicy.geometryTolerance(referenceQuad))}px)"
        }
        val hFinal = checkNotNull(Analyzer.buildHomography(detFinal.quad, screenW, screenH, cornerMapping)) { "최종 좌표 정합 실패" }
        val finalGridRaw = try {
            fullResGrid(finalCapture.frameStore, NativePixel.MODE_GRAY, hFinal, blackAvg, screenW, screenH, gw, gh)
                ?: withContext(Dispatchers.Default) { Analyzer.lumaGrid(finalAvg, blackAvg, hFinal, screenW, screenH, gw, gh) }
        } finally {
            finalCapture.frameStore.delete()
        }
        val finalGrid = withContext(Dispatchers.Default) {
            Analyzer.applyFlatField(finalGridRaw, flatField)
        }

        val lowFinalCapture = capturePatternGray(client, cap, LOW_LIGHT_PATTERN, LOW_LIGHT_FRAMES)
        val finalLowGridRaw = try {
            fullResGrid(lowFinalCapture.frameStore, NativePixel.MODE_GRAY, hFinal, blackAvg, screenW, screenH, gw, gh)
                ?: withContext(Dispatchers.Default) { Analyzer.lumaGrid(lowFinalCapture.average, blackAvg, hFinal, screenW, screenH, gw, gh) }
        } finally {
            lowFinalCapture.frameStore.delete()
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
        chromaBefore?.let { (beforeMean, beforeP95) ->
            runCatching {
                val m = measureRgbPatterns(
                    client, cap, blackRgbAvg, hFinal, screenW, screenH, gw, gh,
                    RGB70_PATTERNS, FRAMES_PER_PATTERN, "deviation_rgb70_after",
                    flatField = flatField,
                    makeHeatmaps = false,
                    includeGain = false,
                    keepGrids = true,
                )
                val after = withContext(Dispatchers.Default) {
                    LowFrequency.chroma(m.grids.getValue("red70"), m.grids.getValue("green70"), m.grids.getValue("blue70"), gw, gh, LOW_FREQ_BLOCKS)
                }
                log(
                    "색 균일화 결과: 블록 색 편차 평균 ${pct(beforeMean)} → ${pct(after.meanSpread())}, " +
                        "P95 ${pct(beforeP95)} → ${pct(after.p95Spread())}"
                )
            }.onFailure { log("색 균일화 재측정 실패(${it::class.java.simpleName}): ${it.message}, ${heapText()}") }
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
        flickerStability70: Float,
        graySpanMs: Long,
        confidenceLow: FloatArray,
        flickerStabilityLow: Float,
        lowSpanMs: Long,
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
        grayAvgPng: ByteArray?,
    ): File {
        val bestPng = withContext(Dispatchers.Default) {
            bestMap.alphaPng(gw, gh, screenW, screenH, maxAttenuation)
        }
        val bestRgbPng = withContext(Dispatchers.Default) {
            bestMap.rgbPng(gw, gh, screenW, screenH, maxAttenuation)
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
            .put("gray70FrameStabilityMean", flickerStability70.toDouble())
            .put("gray70FlickerFrameSpanSeconds", graySpanMs / 1000.0)
            .put("lowLightConfidenceMean", Analyzer.meanConfidence(confidenceLow).toDouble())
            .put("lowLightFrameStabilityMean", flickerStabilityLow.toDouble())
            .put("lowLightFlickerFrameSpanSeconds", lowSpanMs / 1000.0)
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
            .put("passBlockMaxThreshold", PASS_BLOCK.toDouble())
            .put("blockSizePx", Analyzer.blockGrid(lumaBefore, gw, gh).blockSize)
            .put("blockMaxDevBefore", Analyzer.blockMaxDev(lumaBefore, gw, gh).toDouble())
            .put("blockMaxDevAfter", Analyzer.blockMaxDev(finalLumaAfter ?: lumaBefore, gw, gh).toDouble())
            .put("lowLightBlockMaxDevBefore", Analyzer.blockMaxDev(lumaLowBefore, gw, gh).toDouble())
            .put("lowLightBlockMaxDevAfter", Analyzer.blockMaxDev(finalLowLumaAfter ?: lumaLowBefore, gw, gh).toDouble())
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
            .put("strokeDetail", strokeDetailReport ?: JSONObject().put("used", false))
            .put("responseCurve", curveReport ?: JSONObject().put("used", false))
            .put("maxAttenuation", maxAttenuation.wire())
            .put("outputGamma", outputGamma?.toDouble() ?: JSONObject.NULL)
            .put("cameraToneCurve", capture?.let { if (it.isRaw) "RAW_LINEAR" else it.toneMode.name } ?: JSONObject.NULL)
            .put("captureFormat", capture?.captureFormatText ?: JSONObject.NULL)
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
                grayAvgPng?.let { files["camera_main_gray70.png"] = it }
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
            map.alphaPng(gw, gh, screenW, screenH, maxAttenuation)
        }
        val rgbPng = withContext(Dispatchers.Default) {
            map.rgbPng(gw, gh, screenW, screenH, maxAttenuation)
        }
        withContext(Dispatchers.IO) {
            val request = JSONObject()
                .put("cmd", Protocol.CMD_APPLY_MAP)
                .put("width", screenW)
                .put("height", screenH)
                .put("maxAttenuation", maxAttenuation.wire())
                .put("defaultStrength", 100)
            outputGamma?.let { request.put("outputGamma", it.toDouble()) }
            request
                .put("checksumMd5", md5(png))
                .put("sourceDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("data", Base64.encodeToString(png, Base64.NO_WRAP))
            if (rgbPng != null) {
                request
                    .put("rgbChecksumMd5", md5(rgbPng))
                    .put("rgbData", Base64.encodeToString(rgbPng, Base64.NO_WRAP))
            }
            client.request(request, timeoutMs = 120_000)
            // 색 균일화 레이어가 있으면 무보정(기준선) 맵이어도 보정을 켜 둔다 — 레이어가 같이 꺼지면
            // 반복·최종 평가가 기준 측정과 다른 조건이 된다.
            client.command(if (map.isBaseline && !whiteBalanceActive) Protocol.CMD_DISABLE_CORRECTION
                else Protocol.CMD_ENABLE_CORRECTION, "strength" to 100)
            client.showPattern("gray70")
        }
    }

    /**
     * 프로토콜로 보내는 float → double. 0.3f.toDouble()은 0.30000001192…라 탭의 상한 검사(0~0.30)에 걸려
     * 맵 적용이 거부되고 연결이 끊겼다. 십진 표기 그대로(0.3) 보낸다.
     */
    private fun Float.wire(): Double = toString().toDouble()

    private fun mean(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += v.toDouble()
        return (s / a.size).toFloat()
    }

    private fun uniformityPassed(stats: Analyzer.Stats, grid: FloatArray, gw: Int, gh: Int): Boolean =
        uniformityScore(stats, grid, gw, gh) <= 1f

    /**
     * 초광각 교차 일치도를 신뢰도로 쓸 때의 가중. 3위치 비네팅 보정값이 있으면 카메라 쪽 원형은 이미
     * 분리됐고, 남는 불일치는 주로 초광각의 낮은 해상도(경계 번짐) 때문이라 절반만 반영한다.
     */
    private fun crossAgreement(cross: CrossResult): FloatArray =
        if (vignetteModel == null) cross.agreement
        else FloatArray(cross.agreement.size) { 0.5f + 0.5f * cross.agreement[it] }

    private fun optimizationScore(stats: Analyzer.Stats): Float =
        MeasurementPolicy.optimizationScore(stats, PASS_RMS, PASS_P95)

    private fun uniformityScore(stats: Analyzer.Stats, grid: FloatArray, gw: Int, gh: Int): Float =
        MeasurementPolicy.score(stats, PASS_RMS, PASS_P95, Analyzer.blockMaxDev(grid, gw, gh), PASS_BLOCK)

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
        /** 있으면 패턴마다 (패턴, 격자, 신뢰도)를 넘기고 격자·신뢰도는 보관하지 않는다(반복 루프용). */
        onGrid: (suspend (String, FloatArray, FloatArray) -> Unit)? = null,
    ): RgbMeasurement {
        val stats = LinkedHashMap<String, Analyzer.Stats>()
        val grids = LinkedHashMap<String, FloatArray>()
        val heatmaps = LinkedHashMap<String, ByteArray>()
        val confidences = LinkedHashMap<String, FloatArray>()
        val channelGains = LinkedHashMap<Int, FloatArray>()
        val targets = LinkedHashMap<String, Float>()
        val gains = ArrayList<FloatArray>()
        var maxStrayRatio = 0f

        for (pattern in patterns) {
            val channel = rgbChannel(pattern)
            // 단색 패턴은 해당 채널만 쓰므로 한 채널만 디코드·평균한다(RGB 전체 대비 메모리 1/3).
            val capture = capturePatternChannel(client, cap, pattern, frameCount, channel)
            try {
                val avg = capture.average
                val rawGrid = fullResGrid(capture.frameStore, channel, h, channelImage(blackRgb, channel), screenW, screenH, gw, gh)
                    ?: withContext(Dispatchers.Default) {
                        Analyzer.lumaGrid(avg, channelImage(blackRgb, channel), h, screenW, screenH, gw, gh)
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
                // 색 균일화용(keepGrids만)은 격자만 쓰므로 채널마다 12.6MB짜리 신뢰도 격자를 만들지 않는다
                // (V60 16MP RAW에서 RGB70 도중 OOM). 프레임 안정도 로그만 필요하면 그것만 계산한다.
                if (!includeGain && onGrid == null && makeHeatmaps) {
                    val flicker = temporalChannelConfidence(
                        capture.frameStore, blackRgb, channel, h, screenW, screenH, gw, gh, label = pattern,
                    )
                    log("$pattern 프레임 안정도 ${pct(Analyzer.meanConfidence(flicker))}, flicker span ${fmtSeconds(capture.frameStore.spanMs)}")
                }
                if (includeGain || onGrid != null) {
                    val blackFull = withContext(Dispatchers.Default) {
                        Analyzer.channelGrid(blackRgb, null, channel, h, screenW, screenH, gw, gh)
                    }
                    val baseConfidence = withContext(Dispatchers.Default) {
                        Analyzer.confidenceGrid(rawGrid, blackFull, strayWarn = LOW_LIGHT_STRAY_WARN)
                    }
                    val flickerConfidence = temporalChannelConfidence(
                        capture.frameStore, blackRgb, channel, h, screenW, screenH, gw, gh,
                        label = if (makeHeatmaps || includeGain) pattern else "",
                    )
                    val confidence = withContext(Dispatchers.Default) {
                        Analyzer.combineConfidence(baseConfidence, flickerConfidence)
                    }
                    if (onGrid != null) onGrid(pattern, grid, confidence) else confidences[pattern] = confidence
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
                        Analyzer.gainGrid(grid, gw, gh, maxAttenuation, confidence, smoothRadius)
                    }
                    gains += gain
                    channelGains[channel] = gain
                    targets[pattern] = Analyzer.correctionTarget(grid, gw, gh, maxAttenuation)
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
            withContext(Dispatchers.Default) { Analyzer.averageGainGrids(gains, maxAttenuation) }
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
            targets = targets,
            meanRms = meanRms,
            maxStrayRatio = maxStrayRatio,
            signalValid = stats.isNotEmpty() && maxStrayRatio <= LOW_LIGHT_STRAY_WARN,
        )
    }

    private fun emptyRgbMeasurement(): RgbMeasurement =
        RgbMeasurement(
            stats = LinkedHashMap(),
            grids = LinkedHashMap(),
            heatmaps = LinkedHashMap(),
            aggregateGain = null,
            confidences = LinkedHashMap(),
            channelGains = LinkedHashMap(),
            targets = LinkedHashMap(),
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
        settleMs: Long = PATTERN_SETTLE_MS,
    ): GrayImage {
        val capture = capturePatternGray(client, cap, pattern, frameCount, settleMs)
        capture.frameStore.delete()
        return capture.average
    }

    /**
     * 희소 점등 측광 ([SparseSpotPhotometry]). 메인 카메라 풀해상도 프레임에서 켜진 블록마다 국소 정합·
     * 광량 적분을 해서 화면 픽셀 단위 휘도를 얻고, 전면 측정 경로의 스무딩 커널과 상보적인 고주파 층만
     * 돌려준다. 카메라 해상도가 모자라거나 실패하면 null (기존 저주파 보정만 수행).
     */
    private suspend fun measureStrokeDetail(
        client: ControlClient,
        cap: CaptureController,
        homography: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        smoothRadius: Int,
        confidence: FloatArray,
        reference: FloatArray,
        /** 0/1/2면 그 채널만 켠 점을 해당 채널로 잰다(서브픽셀별). null이면 흰 점·회색. */
        channel: Int? = null,
    ): StrokeDetail? {
        val label = channel?.let { "RGB"[it].toString() } ?: "W"
        if (gw != screenW || gh != screenH) {
            log("희소 점등 측광 건너뜀: 보정맵이 화면 해상도와 1:1이 아님 (${gw}x$gh)")
            return null
        }
        return captureSparse(client, cap, homography, screenW, screenH, reference, channel, "4/10") { layouts, plan, camPerScreen ->
            buildStrokeDetail(layouts, plan, camPerScreen, screenW, screenH, smoothRadius, confidence, reference, label)
        }
    }

    /**
     * 희소 점등 패턴을 위상별로 찍어 블록 측정값(layout)을 모은 뒤 [onLayouts]로 넘긴다. 보정이 켜져 있으면
     * 점에도 보정 알파가 덮이므로, 같은 함수로 보정 후 픽셀 단위 잔차도 잰다. 실패하면 null.
     */
    private suspend fun <T> captureSparse(
        client: ControlClient,
        cap: CaptureController,
        homography: Homography,
        screenW: Int,
        screenH: Int,
        reference: FloatArray,
        channel: Int?,
        step: String,
        onLayouts: (List<SparseSpotPhotometry.PhaseLayout>, SparseSpotPhotometry.Plan, Float) -> T?,
    ): T? {
        val label = channel?.let { "RGB"[it].toString() } ?: "W"
        val mode = channel ?: NativePixel.MODE_GRAY
        // 희소 패턴은 점 픽셀 하나하나가 gray70 전체 화면보다 밝지 않으므로, gray70에 맞춘 노출을 올려
        // 점당 광자를 늘린다(잡음 ∝ 1/√광자). gray70 상위 1% 밝기가 0.6을 넘지 않게 최대 4배까지.
        val exposureBefore = cap.manualExposureState()
        var boosted = false
        if (exposureBefore != null) {
            // 화면 해상도 배열이라 박싱 리스트(수십 MB) 대신 원시 배열로 정렬한다
            val sorted = FloatArray(reference.size)
            var np = 0
            for (v in reference) if (v.isFinite() && v > 0f) sorted[np++] = v
            sorted.sort(0, np)
            val p99 = if (np == 0) Float.NaN else sorted[((np - 1) * 0.99f).toInt()]
            val boost = if (p99.isFinite() && p99 > 0f) (SPARSE_TARGET_PEAK / p99).coerceIn(1f, SPARSE_MAX_EXPOSURE_BOOST) else 1f
            if (boost > 1.05f && cap.scaleManualExposure(boost.toDouble(), inputRefreshHz(), preferExposureTime = true)) {
                boosted = true
                log("희소 점등 측광$label 노출 ×${fmt(boost)} (${cap.manualExposureText()}, gray70 상위1% ${fmt(p99)})")
                delay(300)
            }
        }
        return try {
            suspend fun captureBlack(): GrayImage {
                status("$step 희소 점등 측광$label 준비: black 풀해상도 촬영...")
                withContext(Dispatchers.IO) { client.showPattern("black") }
                delay(SPARSE_SETTLE_MS)
                val blackStore = captureFrameStore(cap, SPARSE_BLACK_FRAMES, SPARSE_INTER_FRAME_DELAY_MS)
                return try {
                    if (channel == null) averageFullResFromStore(blackStore)
                    else withContext(Dispatchers.Default) { NativePixel.average(blackStore.files, channel, ImageOps.FULL_RESOLUTION) }
                        ?: averageDecodedFromStore(blackStore) { ImageOps.decodeLinearChannel(it, channel, Int.MAX_VALUE) }
                } finally {
                    blackStore.delete()
                }
            }
            var black: GrayImage? = captureBlack()
            val bw = black!!.w
            val bh = black.h
            // 네이티브에 보관할 수 있으면 Java 힙의 black(약 50MB)은 바로 놓는다
            var nativeBlack = NativePixel.storeBlack(black.data)
            if (nativeBlack) black = null
            val sample = ImageOps.downsampleFactor(bw, bh)
            val hFull = homography.withImageAffine(sample.toDouble(), (sample - 1) / 2.0)
            val camPerScreen = SparseSpotPhotometry.cameraPxPerScreenPx(hFull, screenW, screenH)
            val plan = SparseSpotPhotometry.plan(camPerScreen, level = if (channel != null) SPARSE_CHANNEL_LEVEL else 70)
            if (plan == null) {
                log(
                    "희소 점등 측광 건너뜀: 화면 1px당 카메라 ${fmt(camPerScreen)}px — 획 단위 측정에 부족 " +
                        "(카메라를 더 가까이 두거나 망원 화각 사용)"
                )
                return null
            }
            log("희소 점등 측광$label: 화면 1px당 카메라 ${fmt(camPerScreen)}px (풀해상도 ${bw}x$bh), $plan")
            // 호출 간에 재사용하지 않는다: 붙잡아 두면 조립(약 70MB 임시) 동안 50MB가 더 얹힌다.
            // 놓은 큰 배열은 ART가 OOM 전에 회수하므로 매번 새로 잡는 편이 피크가 낮다.
            var sparseBuffer: FloatArray? = FloatArray(bw * bh)
            // 점 탐침: 첫 위상 한 장으로 점의 실제 최고 밝기를 재서 노출을 맞춘다. gray70 기준 부스트는
            // 단색 점(서브픽셀 하나)에 턱없이 모자라고, 레벨을 올린 점은 포화될 수도 있다.
            if (exposureBefore != null) {
                val (px0, py0) = plan.phases().first()
                withContext(Dispatchers.IO) { client.showPattern(plan.patternName(px0, py0, channel)) }
                delay(SPARSE_SETTLE_MS)
                val probeStore = captureFrameStore(cap, 1, SPARSE_INTER_FRAME_DELAY_MS)
                val peak = try {
                    val file = probeStore.files.first()
                    val ok = if (nativeBlack) NativePixel.decodeIntoStored(file, mode, ImageOps.FULL_RESOLUTION, sparseBuffer!!)
                        else NativePixel.decodeInto(file, mode, ImageOps.FULL_RESOLUTION, black!!.data, sparseBuffer!!)
                    if (ok) withContext(Dispatchers.Default) { probePeak(sparseBuffer!!) } else Float.NaN
                } finally {
                    probeStore.delete()
                }
                val total = cap.manualExposureRatio(exposureBefore)
                val factor = if (peak.isFinite() && peak > 0f) {
                    (SPARSE_TARGET_PEAK / peak).coerceIn(0.25f, (SPARSE_MAX_TOTAL_BOOST / total.toFloat()).coerceAtLeast(0.25f))
                } else 1f
                if ((factor < 0.85f || factor > 1.15f) && cap.scaleManualExposure(factor.toDouble(), inputRefreshHz(), preferExposureTime = true)) {
                    boosted = true
                    log("희소 점등 측광$label 점 탐침: 최고 밝기 ${fmt(peak)} → 노출 ×${fmt(factor)} (${cap.manualExposureText()}, 측정 전 대비 ×${fmt(cap.manualExposureRatio(exposureBefore).toFloat())})")
                    delay(300)
                    // 노출이 바뀌었으니 black도 다시 잰다
                    black = null
                    NativePixel.storeBlack(null)
                    val again = captureBlack()
                    require(again.w == bw && again.h == bh) { "black 크기 변화" }
                    nativeBlack = NativePixel.storeBlack(again.data)
                    black = if (nativeBlack) null else again
                } else {
                    log("희소 점등 측광$label 점 탐침: 최고 밝기 ${fmt(peak)} (노출 유지)")
                }
            }
            val layouts = ArrayList<SparseSpotPhotometry.PhaseLayout>()
            for ((index, phase) in plan.phases().withIndex()) {
                status("$step 희소 점등 측광$label ${index + 1}/${plan.patternCount}...")
                withContext(Dispatchers.IO) { client.showPattern(plan.patternName(phase.first, phase.second, channel)) }
                delay(SPARSE_SETTLE_MS)
                val store = captureFrameStore(cap, SPARSE_FRAMES_PER_PATTERN, SPARSE_INTER_FRAME_DELAY_MS)
                try {
                    var layout: SparseSpotPhotometry.PhaseLayout? = null
                    for (file in store.files) {
                        val frame = withContext(Dispatchers.Default) {
                            // 네이티브로 재사용 버퍼에 디코드·black 차감(프레임마다 50MB 배열을 새로 만들지 않음)
                            val decoded = if (nativeBlack) NativePixel.decodeIntoStored(file, mode, ImageOps.FULL_RESOLUTION, sparseBuffer!!)
                                else NativePixel.decodeInto(file, mode, ImageOps.FULL_RESOLUTION, black!!.data, sparseBuffer!!)
                            if (decoded) {
                                SparseSpotPhotometry.Frame(bw, bh, sparseBuffer!!)
                            } else {
                                fullResMinusBlack(readCaptureFrame(file), black ?: throw IllegalStateException("네이티브 디코드 실패"), channel)
                            }
                        }
                        withContext(Dispatchers.Default) {
                            val current = layout ?: SparseSpotPhotometry.locate(
                                frame, hFull, screenW, screenH, plan, phase.first, phase.second,
                            ).also { layout = it }
                            SparseSpotPhotometry.accumulate(frame, current)
                        }
                    }
                    layout?.let { layouts += it }
                } finally {
                    store.delete()
                }
            }
            withContext(Dispatchers.IO) { client.showPattern("gray70") }
            // 조립 전에 풀해상도 black·디코드 버퍼(약 100MB)를 놓는다
            black = null
            sparseBuffer = null
            withContext(Dispatchers.Default) { onLayouts(layouts, plan, camPerScreen) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("희소 점등 측광$label 실패: ${e.message}")
            runCatching { withContext(Dispatchers.IO) { client.showPattern("gray70") } }
            null
        } catch (e: OutOfMemoryError) {
            // 희소 측정은 없어도 저주파 보정은 끝까지 갈 수 있으므로 측정 전체를 멈추지 않는다
            log("희소 점등 측광$label 메모리 부족으로 건너뜀 (${heapText()})")
            runCatching { withContext(Dispatchers.IO) { client.showPattern("gray70") } }
            null
        } finally {
            NativePixel.storeBlack(null)
            // 이후 패턴(gray25·RGB·반복)은 원래 노출로 찍어야 기준과 비교가 맞는다
            if (boosted && exposureBefore != null) cap.restoreManualExposure(exposureBefore)
        }
    }

    /**
     * W·R·G·B 점을 따로 켜서 픽셀 단위 고주파 층을 잰다(패턴 수 4배).
     * 번인은 채널마다 다르게 진행되므로(보통 파랑이 먼저) 흰 점 하나로는 채널 차이를 못 본다.
     * 알파(휘도) 경로용 층은 흰 점으로 직접 잰 층을 쓴다(채널 합성은 채널별 잡음이 겹친다).
     * 채널 측정이 하나라도 실패하면 흰 점 층만 쓴다.
     */
    private suspend fun measureStrokeDetailRgb(
        client: ControlClient,
        cap: CaptureController,
        homography: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        smoothRadius: Int,
        confidence: FloatArray,
        reference: FloatArray,
    ): StrokeDetail? {
        val white = measureStrokeDetail(client, cap, homography, screenW, screenH, gw, gh, smoothRadius, confidence, reference)
            ?: return null
        val gains = ArrayList<FloatArray>(3)
        val reports = ArrayList<JSONObject>(3)
        for (c in 0..2) {
            val d = measureStrokeDetail(client, cap, homography, screenW, screenH, gw, gh, smoothRadius, confidence, reference, channel = c)
            if (d == null) {
                log("채널별 희소 점등 실패(${"RGB"[c]}) — 흰 점 층만 사용")
                return white
            }
            // 채널 보상층은 쓰지 않으므로 gain만 남긴다(화면 해상도 배열 하나씩)
            gains += d.gain
            reports += d.report
        }
        val report = JSONObject().put("used", true).put("mode", "rgbw").put("white", white.report)
            .put("red", reports[0]).put("green", reports[1]).put("blue", reports[2])
        log("RGBW 희소 점등 완료: W/R/G/B 층 평균 감쇠 " + (listOf(white.gain) + gains).joinToString(" / ") { pct(1f - it.average().toFloat()) })
        return StrokeDetail(white.gain, white.compensation, report, gains)
    }

    /** 탐침 프레임의 점 최고 밝기: 7화소마다 표본을 뽑아 [SPARSE_PROBE_QUANTILE] 분위수(정렬 메모리 줄임). */
    private fun probePeak(frame: FloatArray): Float {
        val n = frame.size / 7
        val tmp = FloatArray(n)
        var m = 0
        for (i in 0 until n) {
            val v = frame[i * 7]
            if (v.isFinite()) tmp[m++] = v
        }
        if (m == 0) return Float.NaN
        tmp.sort(0, m)
        return tmp[((m - 1) * SPARSE_PROBE_QUANTILE).toInt()]
    }

    private fun buildStrokeDetail(
        layouts: List<SparseSpotPhotometry.PhaseLayout>,
        plan: SparseSpotPhotometry.Plan,
        camPerScreen: Float,
        screenW: Int,
        screenH: Int,
        smoothRadius: Int,
        confidence: FloatArray,
        reference: FloatArray,
        label: String = "",
    ): StrokeDetail? {
        // 위상별로 블록 배정이 피치 단위로 밀렸는지 전면 측정 맵과 비교해 바로잡고 합친다
        val (map, lags) = SparseSpotPhotometry.assembleAligned(layouts, screenW, screenH, reference, 3 * plan.pitch)
        if (map.validFraction < 0.5f) {
            log("희소 점등 측광$label 무효: 측정된 픽셀 ${pct(map.validFraction)} — 정합/초점/차광 확인")
            return null
        }
        val shifted = lags.count { it[0] != 0 || it[1] != 0 }
        if (shifted > 0) log("희소 점등 측광$label: ${shifted}개 위상에서 피치 단위 배정 오류를 전면 맵 기준으로 바로잡음")
        // 전면 경로: 카메라 샘플링(≈smoothRadius) + gainGrid 박스 블러(smoothRadius) — 그보다 한 칸 넓게 잘라
        // 저주파는 기존 반복 루프가, 그 위 고주파는 이 층이 맡는다.
        val lowPassRadius = maxOf(1, smoothRadius) + 1
        val ratio = SparseSpotPhotometry.detailRatio(map, lowPassRadius)
        // 고주파 층 상한도 사용자 감쇠 상한을 따른다(고정 6%로는 깊은 번인 획을 일부만 잡았다).
        // 신뢰도는 비례가 아닌 문턱 방식(저주파 경로와 같음)으로 적용한다.
        val detailMax = maxAttenuation.coerceAtMost(0.5f)
        val q = SparseSpotPhotometry.detailReference(map, lowPassRadius, detailMax)
        val allowance = FloatArray(confidence.size) { Analyzer.confidenceAllowance(confidence[it]) }
        val gain = SparseSpotPhotometry.detailGain(ratio, map.valid, q, allowance, detailMax)
        val compensation = SparseSpotPhotometry.lowFrequencyCompensation(gain, ratio, screenW, screenH, lowPassRadius)
        var attSum = 0.0
        var attMax = 0f
        for (g in gain) {
            attSum += 1.0 - g
            attMax = maxOf(attMax, 1f - g)
        }
        val meanAtt = (attSum / gain.size).toFloat()
        // 점 하나의 측정 잡음(프레임 간 상대 표준오차) 중앙값: 픽셀 단위 번인을 가려낼 수 있는지 판단용
        val noiseMedian = SparseSpotPhotometry.medianWhere(map.relNoise, map.valid)
        log("희소 점등 측광$label 점 잡음: 상대 표준오차 중앙값 ${pct(noiseMedian)} (이보다 작은 픽셀 단위 번인은 구분하기 어려움)")
        log(
            "희소 점등 측광$label 완료: 측정 픽셀 ${pct(map.validFraction)}, 고주파 기준 ${fmt(q)}, " +
                "고주파 층 평균 감쇠 ${pct(meanAtt)} / 최대 ${pct(attMax)} (스무딩 없이 픽셀 단위 적용)"
        )
        val report = JSONObject()
            .put("used", true)
            .put("block", plan.block)
            .put("pitch", plan.pitch)
            .put("patterns", plan.patternCount)
            .put("framesPerPattern", SPARSE_FRAMES_PER_PATTERN)
            .put("cameraPxPerScreenPx", camPerScreen.toDouble())
            .put("validFraction", map.validFraction.toDouble())
            .put("phasesRealigned", shifted)
            .put("lowPassRadius", lowPassRadius)
            .put("reference", q.toDouble())
            .put("meanAttenuation", meanAtt.toDouble())
            .put("detailRms", SparseSpotPhotometry.detailRms(map, lowPassRadius).toDouble())
            .put("medianRelNoise", noiseMedian.toDouble())
            .put("maxAttenuation", attMax.toDouble())
        return StrokeDetail(gain, compensation, report)
    }

    /**
     * 픽셀 단위 검증: 최적 맵을 켠 채 희소 점(W, RGBW면 R·G·B도)으로 화면 픽셀 해상도 잔차를 재고,
     * 잔차로 각 고주파 층을 다시 맞춰 적용한 뒤 또 잰다. 층마다 잔차 RMS(점 잡음 제외)가 줄어들 때만
     * 채택하고, 나빠진 층은 직전 값으로 되돌려 더 고치지 않는다(탭은 희소 점에도 RGB 보정을 곱해 그린다).
     * 힙(512MB)이 반복 보정 상태로 이미 차 있으므로 층은 제자리에서 고치고, 되돌리기용 직전 값은
     * 16비트로 줄여 둔다(층당 12.6MB → 6.3MB, 후보 float 사본을 따로 두지 않음).
     * 반환: (새 최적 맵이 든 결과, 새 고주파 층). 바뀐 것이 없으면 null.
     */
    private suspend fun refinePixelDetail(
        client: ControlClient,
        cap: CaptureController,
        homography: Homography,
        screenW: Int,
        screenH: Int,
        smoothRadius: Int,
        confidence: FloatArray,
        reference: FloatArray,
        correction: CorrectionIterations,
        detail: StrokeDetail,
        gw: Int,
        gh: Int,
    ): Pair<CorrectionIterations, StrokeDetail>? {
        if (correction.invalid || correction.bestMap.isBaseline || !correction.bestMap.hasDetail) {
            log("픽셀 단위 검증 건너뜀: 최적 맵이 기준선(무보정)이거나 측정 무효")
            return null
        }
        val lowPassRadius = maxOf(1, smoothRadius) + 1
        val detailMax = maxAttenuation.coerceAtMost(0.5f)
        // 층 0 = W(알파), 1..3 = R·G·B
        val channels: List<Int?> = if (detail.channelGains?.size == 3) listOf(null, 0, 1, 2) else listOf(null)
        val names = listOf("W", "R", "G", "B")
        val reportKeys = listOf("white", "red", "green", "blue")
        val initialRms = channels.indices.map { k ->
            val r = if (channels.size == 1) detail.report else detail.report.optJSONObject(reportKeys[k])
            r?.optDouble("detailRms", Double.NaN)?.toFloat() ?: Float.NaN
        }
        val layers: List<FloatArray> = if (channels.size == 4) listOf(detail.gain) + detail.channelGains!! else listOf(detail.gain)
        val all = BooleanArray(detail.gain.size) { true }
        fun lowPass(g: FloatArray) = SparseSpotPhotometry.weightedBox(SparseSpotPhotometry.weightedBox(g, all, screenW, screenH, lowPassRadius), all, screenW, screenH, lowPassRadius)
        // 보상층 갱신용: 고치기 전 W 층의 저주파
        val lpOld = withContext(Dispatchers.Default) { lowPass(detail.gain) }
        fun backup(g: FloatArray) = ShortArray(g.size) { ((1f - g[it]).coerceIn(0f, 0.5f) * 131070f).roundToInt().toShort() }
        fun restore(b: ShortArray, g: FloatArray) { for (i in g.indices) g[i] = 1f - (b[i].toInt() and 0xFFFF) / 131070f }
        val saved = arrayOfNulls<ShortArray>(channels.size)
        val bestRms = FloatArray(channels.size) { Float.NaN }
        val frozen = BooleanArray(channels.size)
        val maxRounds = if (channels.size == 1) PIXEL_REFINE_ROUNDS else PIXEL_REFINE_ROUNDS_RGBW
        val rounds = JSONArray()
        var changed = false
        var round = 0
        try {
            while (true) {
                val roundJson = JSONObject().put("round", round + 1)
                for ((k, channel) in channels.withIndex()) {
                    if (frozen[k]) continue
                    // 지금 적용 중인 층(첫 회는 반복 보정 결과, 이후는 방금 고친 후보)의 픽셀 잔차
                    val residual = captureSparse(client, cap, homography, screenW, screenH, reference, channel, "8/10 픽셀 검증 ${round + 1}") { layouts, plan, _ ->
                        SparseSpotPhotometry.assembleAligned(layouts, screenW, screenH, reference, 3 * plan.pitch).first
                            .takeIf { it.validFraction >= 0.5f }
                    }
                    if (residual == null) {
                        log("픽셀 검증 ${names[k]} 측정 실패 — 이 층은 직전 값 유지")
                        saved[k]?.let { restore(it, layers[k]) }
                        frozen[k] = true
                        continue
                    }
                    val rms = withContext(Dispatchers.Default) { SparseSpotPhotometry.detailRms(residual, lowPassRadius) }
                    val accepted = round == 0 || !(rms >= bestRms[k])
                    log(
                        "픽셀 검증 ${round + 1} ${names[k]}: 고주파 잔차 RMS ${pct(rms)} (보정 전 ${pct(initialRms[k])}" +
                            (if (round > 0) ", 직전 ${pct(bestRms[k])}) — ${if (accepted) "채택" else "악화, 직전 층으로 되돌림"}" else ")") +
                            ", ${heapText()}"
                    )
                    roundJson.put(names[k], JSONObject().put("detailRms", rms.toDouble()).put("accepted", accepted))
                    if (!accepted) {
                        restore(saved[k]!!, layers[k])
                        frozen[k] = true
                        continue
                    }
                    if (round > 0) changed = true
                    bestRms[k] = rms
                    if (round >= maxRounds) {
                        frozen[k] = true
                        saved[k] = null
                    } else {
                        saved[k] = withContext(Dispatchers.Default) { backup(layers[k]) }
                        val allowance = FloatArray(confidence.size) { Analyzer.confidenceAllowance(confidence[it]) }
                        val refined = withContext(Dispatchers.Default) {
                            SparseSpotPhotometry.refineDetailGain(layers[k], residual, lowPassRadius, detailMax, allowance)
                        }
                        System.arraycopy(refined, 0, layers[k], 0, refined.size)
                    }
                }
                rounds.put(roundJson)
                // 이번 회에 고친 후보(또는 되돌린 층)를 적용한다. 다 끝났으면 최종 채택 층 그대로다.
                applyGainMap(client, correction.bestMap.replaceDetail(layers[0], if (layers.size == 4) layers.subList(1, 4) else null), gw, gh, screenW, screenH)
                if (frozen.all { it }) break
                round++
            }
        } catch (e: CancellationException) {
            for (k in channels.indices) saved[k]?.let { restore(it, layers[k]) }
            throw e
        }
        detail.report.put("pixelRefine", rounds)
        strokeDetailReport = detail.report
        if (!changed) return null
        val newComp = withContext(Dispatchers.Default) {
            // 저주파 재측정 보상층도 W 층 변화만큼 옮긴다: comp' ≈ comp · lowPass(new) / lowPass(old)
            val lpNew = lowPass(layers[0])
            FloatArray(layers[0].size) { detail.compensation[it] * lpNew[it] / lpOld[it].coerceAtLeast(1e-3f) }
        }
        val rgb = if (layers.size == 4) layers.subList(1, 4).toList() else null
        val refined = StrokeDetail(layers[0], newComp, detail.report, rgb)
        return correction.copy(bestMap = correction.bestMap.replaceDetail(layers[0], rgb)) to refined
    }

    /** 고주파 층을 켠 상태의 저해상도 재측정에서 층의 평균 감쇠를 되돌린다 (저주파 루프가 이를 상쇄하지 않도록). */
    private fun compensateDetail(luma: FloatArray, detail: StrokeDetail?): FloatArray {
        val comp = detail?.compensation ?: return luma
        if (comp.size != luma.size) return luma
        return FloatArray(luma.size) { (luma[it] / comp[it].coerceAtLeast(0.5f)).coerceAtLeast(1e-5f) }
    }

    /** 풀해상도 선형 휘도 평균 (black 측정용). 프레임을 하나씩 디코드해 메모리를 아낀다. */
    private suspend fun averageFullResFromStore(store: CaptureFrameStore): GrayImage =
        withContext(Dispatchers.Default) {
            // 네이티브: 원본 해상도 디코드·평균을 네이티브 메모리에서(4장 이상이면 양끝값 제외 평균)
            NativePixel.average(store.files, NativePixel.MODE_GRAY, ImageOps.FULL_RESOLUTION)?.let { return@withContext it }
            var acc: FloatArray? = null
            var w = 0
            var h = 0
            for (file in store.files) {
                val img = ImageOps.decodeLinearGray(readCaptureFrame(file), maxLongEdge = Int.MAX_VALUE)
                val dst = acc ?: FloatArray(img.data.size).also { acc = it; w = img.w; h = img.h }
                require(img.w == w && img.h == h) { "프레임 크기 불일치" }
                for (i in dst.indices) dst[i] += img.data[i]
            }
            val dst = acc ?: throw IllegalStateException("프레임 없음")
            val n = store.files.size.toFloat()
            for (i in dst.indices) dst[i] /= n
            GrayImage(w, h, dst)
        }

    private fun fullResMinusBlack(frame: CaptureFrame, black: GrayImage, channel: Int? = null): SparseSpotPhotometry.Frame {
        val img = if (channel == null) ImageOps.decodeLinearGray(frame, maxLongEdge = Int.MAX_VALUE)
        else ImageOps.decodeLinearChannel(frame, channel, Int.MAX_VALUE)
        require(img.w == black.w && img.h == black.h) { "black/희소 프레임 크기 불일치" }
        val data = img.data
        for (i in data.indices) data[i] -= black.data[i]
        return SparseSpotPhotometry.Frame(img.w, img.h, data)
    }

    /**
     * 묶음 촬영: 패턴을 연달아 띄우며 프레임만 디스크에 저장하고 분석은 나중에 패턴 하나씩 한다.
     * 촬영 구간이 짧아져 거치가 흔들릴 시간이 줄고, 분석 때는 한 패턴의 프레임만 메모리에 올린다.
     * 저장한 것은 [capturePatternGray]/[capturePatternRgb]가 같은 패턴을 요청할 때 꺼내 쓴다.
     */
    private suspend fun captureBatch(
        client: ControlClient,
        cap: CaptureController,
        plan: List<Pair<String, Int>>,
        label: String,
    ) {
        val startedMs = System.currentTimeMillis()
        for ((index, entry) in plan.withIndex()) {
            val (pattern, count) = entry
            status("$label 묶음 촬영 ${index + 1}/${plan.size}: $pattern ${count}장")
            withContext(Dispatchers.IO) { client.showPattern(pattern) }
            delay(PATTERN_SETTLE_MS)
            prefetched.remove(pattern)?.delete()
            prefetched[pattern] = captureFrameStore(cap, count)
        }
        log("$label 묶음 촬영: ${plan.size}패턴 ${plan.sumOf { it.second }}장, ${fmtSeconds(System.currentTimeMillis() - startedMs)}")
    }

    private fun clearPrefetched() {
        prefetched.values.forEach { it.delete() }
        prefetched.clear()
    }

    private suspend fun capturePatternGray(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = PATTERN_SETTLE_MS,
    ): GrayCapture {
        prefetched.remove(pattern)?.let { return GrayCapture(averageGrayFromStore(it), it) }
        withContext(Dispatchers.IO) { client.showPattern(pattern) }
        delay(settleMs)
        return captureGray(cap, frameCount)
    }

    private suspend fun capturePatternAveragedRgb(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = PATTERN_SETTLE_MS,
    ): RgbImage {
        val capture = capturePatternRgb(client, cap, pattern, frameCount, settleMs)
        capture.frameStore.delete()
        return capture.average
    }

    private fun channelImage(rgb: RgbImage, channel: Int): GrayImage =
        GrayImage(rgb.w, rgb.h, when (channel) { 0 -> rgb.r; 1 -> rgb.g; else -> rgb.b })

    private suspend fun capturePatternChannel(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        channel: Int,
    ): GrayCapture {
        val store = prefetched.remove(pattern) ?: run {
            withContext(Dispatchers.IO) { client.showPattern(pattern) }
            delay(PATTERN_SETTLE_MS)
            captureFrameStore(cap, frameCount)
        }
        val avg = withContext(Dispatchers.Default) { NativePixel.average(store.files, channel) }
            ?: averageDecodedFromStore(store) { ImageOps.decodeLinearChannel(it, channel) }
        return GrayCapture(avg, store)
    }

    private suspend fun capturePatternRgb(
        client: ControlClient,
        cap: CaptureController,
        pattern: String,
        frameCount: Int,
        settleMs: Long = PATTERN_SETTLE_MS,
    ): RgbCapture {
        prefetched.remove(pattern)?.let { return RgbCapture(averageRgbFromStore(it), it) }
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
            if (cap.hasSequentialRoles) {
                // 동시 오픈이 막힌 보조 화각은 메인 촬영이 끝난 뒤 같은 패턴에서 번갈아 찍는다.
                status("보조 화각 번갈아 촬영 (${frameCount}장)...")
                cap.captureSequential(frameCount, interFrameDelayMs) { role, frame ->
                    if (frame.timestampNs > 0L) {
                        if (!firstTs.containsKey(role)) firstTs[role] = frame.timestampNs
                        lastTs[role] = frame.timestampNs
                    }
                    val file = File.createTempFile("capture_${role}_${System.nanoTime()}_", ".bin", cacheDir)
                    filesByRole.getOrPut(role) { ArrayList() } += file
                    writeCaptureFrame(file, frame)
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
        /** 초광각이 본 화면의 저주파 블록(평탄화 전). 메인 카메라 잔여 비네팅 추정 기준. */
        val referenceBlocks: LowFrequency.Blocks? = null,
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
        var referenceBlocks: LowFrequency.Blocks? = null
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
                // 초광각은 화면을 프레임 가운데 작게 보므로 그 영역의 렌즈 비네팅이 거의 평평하다.
                if (role == CameraEnumerator.ROLE_ULTRA_WIDE && referenceBlocks == null) {
                    referenceBlocks = withContext(Dispatchers.Default) { LowFrequency.blocks(raw, gw, gh, LOW_FREQ_BLOCKS) }
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
            referenceBlocks = referenceBlocks,
        )
    }

    /**
     * 프레임 스택 → 픽셀별 절사 평균. 4장 이상이면 픽셀별 최소/최대 1장씩을 제외해,
     * 잔여 롤링 밴드나 순간 플리커가 남은 프레임이 평균을 끌고 가는 것을 막는다.
     * (min/max 추적만 추가하면 되어 스트리밍 처리 그대로 유지)
     */
    private suspend fun averageGrayFromStore(store: CaptureFrameStore): GrayImage =
        withContext(Dispatchers.Default) { NativePixel.average(store.files, NativePixel.MODE_GRAY) }
            ?: averageDecodedFromStore(store) { ImageOps.decodeLinearGray(it) }

    /**
     * 측정마다 한 번, 같은 프레임으로 네이티브와 Kotlin 결과를 비교해 로그로 남긴다.
     * (네이티브는 단위 테스트로 못 돌리므로 실기기에서 직접 확인)
     */
    private suspend fun verifyNative(store: CaptureFrameStore, h: Homography, screenW: Int, screenH: Int) {
        // Kotlin 비교는 메모리를 더 쓰므로 측정 화면을 열 때마다 한 번만 한다.
        if (nativeVerified) return
        nativeVerified = true
        if (!NativePixel.available) {
            log("네이티브 픽셀 처리: 사용 불가 — Kotlin 구현으로 진행")
            return
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val native = withContext(Dispatchers.Default) { NativePixel.average(store.files, NativePixel.MODE_GRAY) }
        val t1 = android.os.SystemClock.elapsedRealtime()
        if (native == null) {
            log("네이티브 픽셀 처리: 이 프레임 형식은 미지원 — Kotlin 구현으로 진행")
            return
        }
        NativePixel.enabled = false
        val kotlin = try {
            averageDecodedFromStore(store) { ImageOps.decodeLinearGray(it) }
        } finally {
            NativePixel.enabled = true
        }
        val t2 = android.os.SystemClock.elapsedRealtime()
        var maxDiff = 0f
        for (i in native.data.indices) maxDiff = maxOf(maxDiff, Math.abs(native.data[i] - kotlin.data[i]))
        val gw = 256
        val gh = (256L * screenH / screenW).toInt().coerceAtLeast(1)
        val gN = withContext(Dispatchers.Default) { Analyzer.lumaGrid(native, null, h, screenW, screenH, gw, gh) }
        val gK = withContext(Dispatchers.Default) { Analyzer.lumaGridKotlin(native, null, h, screenW, screenH, gw, gh) }
        var maxRel = 0f
        for (i in gN.indices) maxRel = maxOf(maxRel, Math.abs(gN[i] - gK[i]) / gK[i].coerceAtLeast(1e-4f))
        log(
            "네이티브 픽셀 처리 검증: 평균 이미지 최대 차이 ${String.format(java.util.Locale.US, "%.2e", maxDiff)}, " +
                "격자 최대 상대 차이 ${String.format(java.util.Locale.US, "%.2e", maxRel)}, " +
                "평균 ${t1 - t0}ms (Kotlin ${t2 - t1}ms)"
        )
    }

    /** 프레임마다 [decode]한 단일 평면을 양끝값 제외 평균한다(4장 이상). */
    private suspend fun averageDecodedFromStore(
        store: CaptureFrameStore,
        decode: (CaptureFrame) -> GrayImage,
    ): GrayImage =
        withContext(Dispatchers.Default) {
            var width = 0
            var height = 0
            var acc: FloatArray? = null
            var minArr: FloatArray? = null
            var maxArr: FloatArray? = null
            for (file in store.files) {
                val img = decode(readCaptureFrame(file))
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

    /**
     * 패턴 전환 직후 이전 패턴이 남아 첫 프레임에 섞였는지 점검한다. 프레임별 중앙값을 비교해
     * 첫 프레임만 튀면(나머지 프레임 간 흔들림보다 확연히 크면) 잔상 의심으로 경고한다.
     */
    private fun checkCarryOver(label: String, grids: List<FloatArray>) {
        if (grids.size < 3) return
        val med = grids.map { g -> g.filter { it.isFinite() && it > 0f }.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] } }
        if (med.any { !it.isFinite() || it <= 0f }) return
        val rest = med.drop(1).sorted()
        val ref = rest[rest.size / 2]
        val first = med[0] / ref - 1f
        val restSpread = rest.maxOf { Math.abs(it / ref - 1f) }
        val suspicious = Math.abs(first) > maxOf(0.005f, restSpread * 3f)
        log(
            "프레임 잔상 점검 $label: 첫 프레임 ${String.format(java.util.Locale.US, "%+.2f%%", first * 100)}, " +
                "나머지 간 최대 ${pct(restSpread)}" + if (suspicious) " — 잔상 의심(패턴 전환 대기 부족)" else ""
        )
    }

    private suspend fun temporalLumaConfidence(
        frameStore: CaptureFrameStore,
        black: GrayImage?,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
        label: String = "",
    ): FloatArray =
        withContext(Dispatchers.Default) {
            try {
                if (frameStore.files.size < 2) return@withContext FloatArray(gw * gh) { 1f }
                val (tw, th) = stabilityGridSize(gw, gh)
                val grids = NativePixel.frameGrids(frameStore.files, NativePixel.MODE_GRAY, black, h, screenW, screenH, tw, th)
                    ?: frameStore.files.map { file ->
                        Analyzer.lumaGrid(ImageOps.decodeLinearGray(readCaptureFrame(file)), black, h, screenW, screenH, tw, th)
                    }
                if (label.isNotEmpty()) checkCarryOver(label, grids)
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
        label: String = "",
    ): FloatArray =
        withContext(Dispatchers.Default) {
            try {
                if (frameStore.files.size < 2) return@withContext FloatArray(gw * gh) { 1f }
                val (tw, th) = stabilityGridSize(gw, gh)
                val blackChannel = black?.let { channelImage(it, channel) }
                val grids = NativePixel.frameGrids(frameStore.files, channel, blackChannel, h, screenW, screenH, tw, th)
                    ?: frameStore.files.map { file ->
                        val frame = ImageOps.decodeLinearChannel(readCaptureFrame(file), channel)
                        Analyzer.lumaGrid(frame, blackChannel, h, screenW, screenH, tw, th)
                    }
                if (label.isNotEmpty()) checkCarryOver(label, grids)
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
