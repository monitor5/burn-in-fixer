package com.burnin.scanner.color

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.burnin.scanner.R
import com.burnin.scanner.analysis.RgbImage
import com.burnin.scanner.analysis.ImageOps
import com.burnin.scanner.analysis.NativePixel
import com.burnin.scanner.analysis.ScreenDetector
import com.burnin.scanner.camera.CaptureController
import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import com.burnin.scanner.net.Session
import com.burnin.scanner.report.ReportStore
import com.burnin.scanner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 색감 맞추기: 대조설비(A급 기준 기기)의 색감에 조정설비(번인 보정 대상)를 맞춘다.
 *
 * 1. 두 화면을 같은 고정 노출·WB 카메라로 번갈아 촬영 (한 화면 점등, 다른 화면 검정)
 * 2. 검정 + R/G/B 램프 + 회색 램프로 두 기기 화면 모델 적합 → 17³ 3D LUT
 * 3. 프로파일 적용 후 회색 계조를 다시 재서 post 곡선을 closed-loop 보정
 * 4. 적합에 쓰지 않은 검증색(held-out)으로 보정 전/후 근사 ΔE00 보고, 리포트 저장
 *
 * 색 프로파일은 번인 보정맵과 별도로 저장되고 Target에서 따로 켜고 끈다. 앱 자체 렌더링에만 적용된다.
 */
class ColorCalibrationActivity : Activity() {

    companion object {
        private const val WHITE_PATTERN = "white70"
        private const val SETTLE_MS = 2_500L
        private const val SWITCH_SETTLE_MS = 600L
        private const val FRAMES_PER_PATCH = 2
        private const val DECODE_LONG_EDGE = 1600
        private const val TARGET_PEAK = 0.55
        private const val MIN_PEAK = 0.20
        private const val MAX_PEAK = 0.80
        private const val REFINE_ITERATIONS = 2
        private const val DRIFT_WARN = 0.03
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var capture: CaptureController? = null
    private lateinit var txtStatus: TextView
    private lateinit var txtResult: TextView
    private lateinit var btnStart: Button
    private lateinit var btnToggle: Button
    private val resultLog = StringBuilder()
    private var profileEnabled = true
    /** 근사 Lab의 기준 흰색 = 목표 흰색(기준 기기 실측 흰색 × scale). */
    private var labWhite = Rgb(1.0, 1.0, 1.0)

    /** 대조설비는 색감만 재므로 프레임에 일부만 걸쳐도 된다: 사각형이 온전하면 중앙 영역, 아니면 켜진 화소 마스크. */
    private class Quads(val ref: ScreenDetector.Quad?, val adj: ScreenDetector.Quad, val refMask: ColorSampling.Mask? = null) {
        fun refSampler(): (RgbImage) -> Rgb =
            refMask?.let { m -> { img: RgbImage -> ColorSampling.meanMaskRgb(img, m) } }
                ?: ref!!.let { q -> { img: RgbImage -> ColorSampling.meanCenterRgb(img, q) } }
        fun adjSampler(): (RgbImage) -> Rgb = { img -> ColorSampling.meanCenterRgb(img, adj) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        txtStatus = findViewById(R.id.txtStatus)
        txtResult = findViewById(R.id.txtResult)
        btnStart = findViewById(R.id.btnStart)
        btnToggle = findViewById(R.id.btnToggle)
        btnStart.text = "색감 맞추기 시작"
        btnToggle.text = "색감 보정 ON/OFF"
        btnToggle.isEnabled = false

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            btnToggle.isEnabled = false
            scope.launch { runSafely() }
        }
        btnToggle.setOnClickListener { scope.launch { toggleProfile() } }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("카메라 권한 없음 — 홈 화면에서 권한을 허용하세요")
            btnStart.isEnabled = false
            return
        }

        scope.launch {
            try {
                val c = CaptureController(this@ColorCalibrationActivity, findViewById<TextureView>(R.id.preview))
                c.start()
                capture = c
                status("카메라 준비 완료 — 두 기기 화면이 모두 보이면 [색감 맞추기 시작]")
                log("카메라: ${c.captureSize.width}x${c.captureSize.height} ${c.captureFormatText}, ${c.hardwareLevelText()}")
                log("두 기기의 밝기 슬라이더·색상 모드·화면 보호 필터를 같게 맞추고 주변광을 끄세요.")
            } catch (e: Exception) {
                status("카메라 초기화 실패: ${e.message}")
                btnStart.isEnabled = false
            }
        }
    }

    private suspend fun runSafely() {
        try {
            runMatch()
        } catch (e: Exception) {
            status("색감 맞추기 실패: ${e.message}")
            log("실패: ${e.stackTraceToString().lineSequence().firstOrNull() ?: e.message}")
        } finally {
            btnStart.isEnabled = true
        }
    }

    private suspend fun runMatch() {
        val adjust = Session.client ?: error("조정설비가 연결되지 않았습니다")
        // 대조설비가 없으면 이상적인 sRGB 화면을 가상 기준으로 삼는다.
        val reference = Session.referenceClient?.takeIf { it.isConnected }
        val referenceName = if (reference != null) Session.referenceName else VirtualSrgbReference.NAME
        val cap = capture ?: error("카메라가 준비되지 않았습니다")
        val startedAt = System.currentTimeMillis()
        resultLog.setLength(0)
        log("기준: $referenceName / 조정: ${Session.targetName}")
        if (reference == null) log("대조설비 없음: sRGB 목표로 맞춥니다. 흰점은 카메라 AWB 기준이라 절대 D65는 아닙니다.")

        status("이전 색 프로파일·화이트밸런스 제거, 번인 보정 ON...")
        withContext(Dispatchers.IO) {
            runCatching { adjust.command(Protocol.CMD_CLEAR_COLOR_PROFILE) }
                .onFailure { AppLog.i("색 프로파일 초기화 생략: ${it.message}") }
            // 색 프로파일이 흰점까지 포함하므로 구 화이트밸런스 레이어와 겹치지 않게 지운다.
            runCatching { adjust.command(Protocol.CMD_CLEAR_WHITE_BALANCE) }
                .onFailure { AppLog.i("화이트밸런스 초기화 생략: ${it.message}") }
            runCatching { adjust.command(Protocol.CMD_ENABLE_CORRECTION, "strength" to 100) }
                .onFailure { log("번인 보정맵 없음/OFF 상태로 측정: ${it.message}") }
            reference?.showPattern(WHITE_PATTERN)
            adjust.showPattern(WHITE_PATTERN)
        }
        delay(SETTLE_MS)
        log("카메라 고정: ${cap.lockMeasurementControls(Session.screenRefreshRate)}")

        status("화면 위치 검출...")
        val quads = detectQuads(adjust, reference)

        status("노출 조정...")
        adjustExposure(cap, adjust, reference, quads)

        val white = ColorPatchPlan.Patch("gray255", 255, 255, 255, ColorPatchPlan.Role.GRAY)
        val nativePatches = ColorPatchPlan.fitPatches() + ColorPatchPlan.VALIDATION
        var refNative = LinkedHashMap<String, Rgb>()
        val adjNative = LinkedHashMap<String, Rgb>()
        nativePatches.forEachIndexed { i, p ->
            status("원본 측정 ${i + 1}/${nativePatches.size}: ${p.id}")
            if (reference != null) refNative[p.id] = measure(reference, adjust, p.pattern, quads.refSampler())
            adjNative[p.id] = measure(adjust, reference, p.pattern, quads.adjSampler())
        }

        status("화면 모델 적합...")
        val adjModel = fitModel(adjNative)
        val refModel = if (reference != null) fitModel(refNative) else {
            val whiteLevel = (adjNative.getValue(white.id) - adjModel.black).luma()
            VirtualSrgbReference.model(whiteLevel, adjModel.black).also {
                refNative = VirtualSrgbReference.measurements(it, nativePatches)
            }
        }
        val matcher = ColorMatcher(refModel, adjModel, refNative.getValue(white.id), adjNative.getValue(white.id))
        labWhite = matcher.targetFromMeasured(refNative.getValue(white.id))
        log("상대 밝기 배율 scale ${fmt3(matcher.scale)} (조정 기기가 기준 흰색을 낼 수 있는 최대 배율)")
        var lut = withContext(Dispatchers.Default) { matcher.bakeLut() }
        sendProfile(adjust, lut, matcher.scale, referenceName)

        val grays = ColorPatchPlan.LEVELS
        val refGray = grays.map { refNative.getValue("gray$it") }
        val refineLog = JSONArray()
        repeat(REFINE_ITERATIONS) { iter ->
            val adjGray = grays.mapIndexed { i, v ->
                status("회색 계조 보정 ${iter + 1}/$REFINE_ITERATIONS: ${i + 1}/${grays.size}")
                measure(adjust, reference, ColorPatchPlan.patternName(v, v, v), quads.adjSampler())
            }
            val err = grays.indices.map { deltaE(matcher, refGray[it], adjGray[it] - adjModel.black) }
            refineLog.put(JSONObject().put("iteration", iter).put("grayMeanDeltaE00", err.average()))
            log("회색 보정 ${iter + 1}: 적용 중 회색 평균 ΔE00 ${fmt2(err.average())}")
            lut = withContext(Dispatchers.Default) { matcher.refinePost(lut, grays, refGray, adjGray) }
            sendProfile(adjust, lut, matcher.scale, referenceName)
        }

        val evalPatches = ColorPatchPlan.grayPatches() + ColorPatchPlan.VALIDATION
        val adjAfter = LinkedHashMap<String, Rgb>()
        evalPatches.forEachIndexed { i, p ->
            status("최종 검증 ${i + 1}/${evalPatches.size}: ${p.id}")
            adjAfter[p.id] = measure(adjust, reference, p.pattern, quads.adjSampler())
        }

        val refWhiteStart = refNative.getValue(white.id)
        val drift: Double? = if (reference != null) {
            status("drift 확인...")
            val refWhiteEnd = measure(reference, adjust, white.pattern, quads.refSampler())
            ((refWhiteEnd.luma() - refWhiteStart.luma()) / refWhiteStart.luma()).also { d ->
                log("기준 흰색 drift ${pct(d)}" + if (kotlin.math.abs(d) > DRIFT_WARN) " — 측정 중 화면/카메라가 변했습니다. 결과를 신뢰하기 어렵습니다." else "")
            }
        } else null

        // 보정 전: 조정 기기 원본을 흰색 휘도 비로만 맞춰 비교(상대 일치 기준), 보정 후: 프로파일 적용 실측
        val adjWhiteSignal = adjNative.getValue(white.id) - adjModel.black
        val targetWhite = matcher.targetFromMeasured(refWhiteStart)
        val beforeNorm = targetWhite.luma() / adjWhiteSignal.luma()
        val rows = JSONArray()
        val before = HashMap<String, Double>()
        val after = HashMap<String, Double>()
        for (p in evalPatches) {
            val refV = refNative.getValue(p.id)
            val b = deltaE(matcher, refV, (adjNative.getValue(p.id) - adjModel.black) * beforeNorm)
            val a = deltaE(matcher, refV, adjAfter.getValue(p.id) - adjModel.black)
            before[p.id] = b
            after[p.id] = a
            rows.put(JSONObject().put("id", p.id).put("role", p.role.name).put("code", JSONArray(listOf(p.r, p.g, p.b)))
                .put("ref", rgbJson(refV)).put("adjBefore", rgbJson(adjNative.getValue(p.id)))
                .put("adjAfter", rgbJson(adjAfter.getValue(p.id)))
                .put("deltaE00Before", b).put("deltaE00After", a))
        }
        fun summary(ids: List<String>, m: Map<String, Double>) = ids.map { m.getValue(it) }.let { v ->
            JSONObject().put("mean", v.average()).put("max", v.max()).put("p90", v.sorted()[((v.size - 1) * 0.9).toInt()])
        }
        val validationIds = ColorPatchPlan.VALIDATION.map { it.id }
        val grayIds = ColorPatchPlan.grayPatches().map { it.id }
        val vb = summary(validationIds, before); val va = summary(validationIds, after)
        val gb = summary(grayIds, before); val ga = summary(grayIds, after)
        log("검증색(held-out) 근사 ΔE00: 평균 ${fmt2(vb.getDouble("mean"))} → ${fmt2(va.getDouble("mean"))}, 최대 ${fmt2(vb.getDouble("max"))} → ${fmt2(va.getDouble("max"))}")
        log("회색 계조 근사 ΔE00: 평균 ${fmt2(gb.getDouble("mean"))} → ${fmt2(ga.getDouble("mean"))}")
        val worst = validationIds.maxBy { after.getValue(it) }
        log("가장 큰 잔차: $worst (${fmt2(after.getValue(worst))}). 채도 높은 색은 조정 기기 gamut 밖이면 덜 맞습니다.")

        val matrix = GlobalMatrixFit.fit(lut)
        val adbCommand = GlobalMatrixFit.surfaceFlingerCommand(matrix)

        val lutBytes = lut.encode()
        val report = JSONObject()
            .put("type", "color_match")
            .put("referenceDevice", referenceName)
            .put("referenceMode", if (reference != null) "device" else "virtual_srgb")
            .put("adjustmentDevice", Session.targetName)
            .put("scannerDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("camera", cap.manualExposureText())
            .put("durationSec", (System.currentTimeMillis() - startedAt) / 1000.0)
            .put("scale", matcher.scale)
            .put("lut", JSONObject().put("size", lut.size).put("format", ColorLut.FORMAT).put("checksumMd5", md5(lutBytes)))
            .put("referencePrimaries", JSONArray(refModel.primaries.map { rgbJson(it) }))
            .put("adjustmentPrimaries", JSONArray(adjModel.primaries.map { rgbJson(it) }))
            .put("referenceWhiteDrift", drift ?: JSONObject.NULL)
            .put("refinement", refineLog)
            .put("summary", JSONObject()
                .put("validationBefore", vb).put("validationAfter", va)
                .put("grayBefore", gb).put("grayAfter", ga))
            .put("patches", rows)
            .put("fitMeasurements", JSONArray(ColorPatchPlan.fitPatches().map { p ->
                JSONObject().put("id", p.id).put("ref", rgbJson(refNative.getValue(p.id))).put("adj", rgbJson(adjNative.getValue(p.id)))
            }))
            .put("experimentalGlobalMatrix", JSONObject()
                .put("rowMajorLinear", JSONArray(matrix.toList()))
                .put("adbCommand", adbCommand)
                .put("note", "앱 밖 전역 적용용 3x3 근사. 자동 실행하지 않음. 적용 공간·행렬 순서는 기기별 검증 필요. 해제: service call SurfaceFlinger 1015 i32 0"))
            .put("limitations", JSONArray(listOf(
                "카메라 RGB 기준 일치: 발광 스펙트럼이 다른 패널은 육안 일치가 보장되지 않음(metamerism)",
                "ΔE00은 카메라 RGB를 선형 sRGB로 가정한 근사값",
                "두 기기 위치의 비네팅 차이 때문에 절대 밝기는 맞추지 않고 상대 밝기만 맞춤",
                "프로파일은 Target 앱 자체 렌더링에만 적용됨",
            ) + if (reference == null) listOf(
                "대조설비 없음: 이상적인 sRGB 화면을 가상 기준으로 사용. 흰점은 카메라 AWB 고정값 기준이라 절대 D65가 아님",
                "대조설비 없음: 기준 흰색 drift를 잴 수 없음",
            ) else emptyList()))
        withContext(Dispatchers.IO) {
            val dir = ReportStore.newSessionDir(this@ColorCalibrationActivity)
            ReportStore.save(dir, report, mapOf("color_lut.bin" to lutBytes))
            log("리포트 저장: ${dir.absolutePath}")
            reference?.showPattern(WHITE_PATTERN)
            adjust.showPattern(WHITE_PATTERN)
        }
        profileEnabled = true
        btnToggle.isEnabled = true
        status("색감 맞추기 완료 — [색감 보정 ON/OFF]로 두 기기를 비교하세요")
    }

    private suspend fun detectQuads(adjust: ControlClient, reference: ControlClient?): Quads {
        val adj = detectQuad(adjust, reference, "조정설비")
        if (reference == null) {
            log("화면 검출: 조정설비 $adj")
            return Quads(null, adj)
        }
        // 대조설비는 프레임 가장자리에 일부만 걸쳐도 된다(중앙은 조정설비 몫). 사각형이 잘렸거나 못 찾으면 마스크로 잰다.
        withContext(Dispatchers.IO) {
            adjust.showPattern("black")
            reference.showPattern(WHITE_PATTERN)
        }
        delay(SWITCH_SETTLE_MS)
        val img = withContext(Dispatchers.Default) { ImageOps.decodeLinearRgb(capture!!.captureSingleFrame(), DECODE_LONG_EDGE) }
        val ref = withContext(Dispatchers.Default) { ColorSampling.detectBrightQuad(img) }
        if (ref != null && !ColorSampling.touchesBorder(ref, img.w, img.h) && !ColorSampling.overlaps(ref, adj)) {
            log("화면 검출: 대조설비 $ref, 조정설비 $adj")
            return Quads(ref, adj)
        }
        val mask = withContext(Dispatchers.Default) { ColorSampling.brightMask(img) }
            ?: error("대조설비 화면이 프레임에 거의 보이지 않습니다. 가장자리에라도 조금 더 걸리게 놓아 주세요")
        // 마스크가 조정설비 중앙 영역과 겹치면 다른 화면을 잡은 것
        val c = ColorSampling.quadPoint(adj, 0.5f, 0.5f)
        val ci = c.y.toInt().coerceIn(0, img.h - 1) * img.w + c.x.toInt().coerceIn(0, img.w - 1)
        require(!mask.bits[ci]) { "대조설비와 조정설비가 같은 위치로 검출되었습니다" }
        log("화면 검출: 대조설비는 프레임에 일부만 보임 — 켜진 영역 ${String.format(java.util.Locale.US, "%.1f", mask.count * 100.0 / (img.w * img.h))}% 화소로 색 측정, 조정설비 $adj")
        return Quads(null, adj, mask)
    }

    private suspend fun detectQuad(lit: ControlClient, dark: ControlClient?, name: String): ScreenDetector.Quad {
        withContext(Dispatchers.IO) {
            dark?.showPattern("black")
            lit.showPattern(WHITE_PATTERN)
        }
        delay(SWITCH_SETTLE_MS)
        val frame = capture!!.captureSingleFrame()
        return withContext(Dispatchers.Default) {
            ColorSampling.detectBrightQuad(ImageOps.decodeLinearRgb(frame, DECODE_LONG_EDGE))
        } ?: error("$name 화면을 찾지 못했습니다")
    }

    /**
     * 흰색(255)에서 두 화면 중 밝은 쪽 최대 채널이 TARGET_PEAK 근처가 되게 노출을 맞춘다 (포화·신호 부족 방지).
     * 디코드 값은 채널 WB gain으로 나뉘어 있어 G가 먼저 센서 포화에 닿는다(V60: 디코드 0.47에서 G 포화).
     * 그래서 RAW 센서 범위도 함께 본다: 클립되면 색 비가 틀어져 프로파일 전체가 망가진다.
     */
    private suspend fun adjustExposure(cap: CaptureController, adjust: ControlClient, reference: ControlClient?, quads: Quads) {
        val white = ColorPatchPlan.patternName(255, 255, 255)
        repeat(6) {
            val refPeak = if (reference != null) measure(reference, adjust, white, quads.refSampler()).max() else 0.0
            val peak = maxOf(refPeak, measure(adjust, reference, white, quads.adjSampler()).max())
            val raws = listOfNotNull(
                reference?.let { rawStats(cap, it, adjust, white) },
                rawStats(cap, adjust, reference, white),
            )
            raws.forEach { log("흰색 RAW: ${it.text()}") }
            val rawFactor = raws.mapNotNull { it.exposureFactor() }.minOrNull()
            if (peak in MIN_PEAK..MAX_PEAK && rawFactor == null) {
                log("노출 확인: 흰색 최대 채널 ${fmt3(peak)} (${cap.manualExposureText()})")
                return
            }
            var factor = TARGET_PEAK / peak
            if (rawFactor != null) factor = minOf(factor, rawFactor.toDouble())
            // 올릴 때도 RAW 센서 범위 0.75를 넘지 않게(클립 중이면 p99.9가 실제보다 낮게 보여 위의 절반이 우선)
            if (rawFactor == null) raws.maxOfOrNull { it.peak }?.takeIf { it > 0.01f }?.let { factor = minOf(factor, 0.75 / it) }
            if (!cap.scaleManualExposure(factor, Session.screenRefreshRate)) {
                log("노출을 더 조정할 수 없습니다 (흰색 최대 채널 ${fmt3(peak)}). 밝기 슬라이더나 거리를 바꿔 다시 시도하세요")
                require(peak > 0.02) { "카메라 신호가 너무 약합니다" }
                if (rawFactor != null) log("경고: RAW 포화가 남은 채 측정합니다 — 색 비가 틀어질 수 있습니다")
                return
            }
            delay(500)
        }
        log("경고: 노출을 6번 조정해도 범위에 들지 않았습니다 (${cap.manualExposureText()})")
    }

    /** [lit]만 [pattern]으로 켜고 RAW 한 장의 센서 범위 통계. RAW가 아니면 null. */
    private suspend fun rawStats(cap: CaptureController, lit: ControlClient, dark: ControlClient?, pattern: String): NativePixel.RawStats? {
        if (!cap.isRaw) return null
        withContext(Dispatchers.IO) {
            dark?.showPattern("black")
            lit.showPattern(pattern)
        }
        delay(SWITCH_SETTLE_MS)
        val file = java.io.File.createTempFile("color_raw_", ".bin", cacheDir)
        return try {
            cap.captureSingleFrameToFile(file)
            withContext(Dispatchers.Default) { NativePixel.rawStats(file) }
        } finally {
            file.delete()
        }
    }

    private suspend fun measure(lit: ControlClient, dark: ControlClient?, pattern: String, sample: (RgbImage) -> Rgb): Rgb {
        withContext(Dispatchers.IO) {
            dark?.showPattern("black")
            lit.showPattern(pattern)
        }
        delay(SWITCH_SETTLE_MS)
        val frames = capture!!.captureFrames(FRAMES_PER_PATCH, interFrameDelayMs = 60)
        return withContext(Dispatchers.Default) {
            frames.map { sample(ImageOps.decodeLinearRgb(it, DECODE_LONG_EDGE)) }
                .reduce { acc, v -> acc + v } * (1.0 / frames.size)
        }
    }

    private fun fitModel(values: Map<String, Rgb>): DisplayModel {
        val patches = ColorPatchPlan.fitPatches()
        val roles = listOf(ColorPatchPlan.Role.RAMP_R, ColorPatchPlan.Role.RAMP_G, ColorPatchPlan.Role.RAMP_B)
        val ramps = roles.mapIndexed { k, role ->
            patches.filter { it.role == role }.map { p -> intArrayOf(p.r, p.g, p.b)[k] to values.getValue(p.id) }
        }
        return DisplayModel.fit(values.getValue(ColorPatchPlan.BLACK.id), ramps)
    }

    /** 목표(기준 실측 × scale)와 조정 기기 신호(black 제외)의 근사 ΔE00. Lab 기준 흰색 = 목표 흰색. */
    private fun deltaE(matcher: ColorMatcher, refMeasured: Rgb, adjSignal: Rgb): Double =
        ColorDifference.deltaE2000(matcher.targetFromMeasured(refMeasured), adjSignal, labWhite)

    private suspend fun sendProfile(adjust: ControlClient, lut: ColorLut, scale: Double, referenceName: String) {
        val bytes = withContext(Dispatchers.Default) { lut.encode() }
        withContext(Dispatchers.IO) {
            adjust.request(
                JSONObject()
                    .put("cmd", Protocol.CMD_APPLY_COLOR_PROFILE)
                    .put("lutSize", lut.size)
                    .put("format", ColorLut.FORMAT)
                    .put("checksumMd5", md5(bytes))
                    .put("sourceDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("referenceDevice", referenceName)
                    .put("scale", scale)
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                timeoutMs = 60_000,
            )
        }
    }

    private suspend fun toggleProfile() {
        val adjust = Session.client ?: return
        profileEnabled = !profileEnabled
        runCatching {
            withContext(Dispatchers.IO) {
                adjust.command(Protocol.CMD_SET_COLOR_PROFILE, "enabled" to profileEnabled)
                Session.referenceClient?.showPattern(WHITE_PATTERN)
                adjust.showPattern(WHITE_PATTERN)
            }
        }.onSuccess { status("조정설비 색감 보정 ${if (profileEnabled) "ON" else "OFF"}") }
            .onFailure { status("전환 실패: ${it.message}") }
    }

    private fun rgbJson(c: Rgb) = JSONArray(listOf(c.r, c.g, c.b))

    private fun status(msg: String) {
        txtStatus.text = msg
        AppLog.i(msg)
    }

    private fun log(msg: String) {
        resultLog.appendLine(msg)
        txtResult.text = resultLog.toString()
        AppLog.i(msg)
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmt3(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
    private fun pct(v: Double): String = String.format(java.util.Locale.US, "%+.1f%%", v * 100)

    override fun onDestroy() {
        capture?.close()
        scope.cancel()
        super.onDestroy()
    }
}
