package com.burnin.scanner.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/**
 * 후면 카메라 열거 + 3각(초광각/표준/망원) 동시 촬영 세트 선택.
 *
 * 노출 경로 3종:
 *  - LISTED: cameraIdList에 공개된 ID
 *  - LOGICAL_PHYSICAL: 논리 멀티카메라(API 28+)가 보고하는 물리 서브카메라 ID
 *    → 같은 논리 카메라를 열고 OutputConfiguration.setPhysicalCameraId로 라우팅하면
 *      여러 화각을 "한 세션에서 동시에" 촬영할 수 있다.
 *  - PROBED: 일부 OEM이 목록에 숨긴 ID를 직접 조회해 발견한 것 (진단용)
 *
 * OEM(특히 삼성)이 서드파티에 물리 ID를 안 열어주면 LISTED 단일 카메라만 남는다.
 * 그 경우 동시 3각 촬영은 불가능하며 메인 카메라 단독 측정으로 폴백한다.
 */
object CameraEnumerator {

    const val ROLE_MAIN = "main"
    const val ROLE_ULTRA_WIDE = "ultrawide"
    const val ROLE_TELE = "tele"

    enum class Source { LISTED, LOGICAL_PHYSICAL, PROBED }

    data class CameraChoice(
        val openId: String,        // openCamera에 넘길 ID (물리 카메라면 논리 부모 ID)
        val physicalId: String?,   // null이 아니면 setPhysicalCameraId 라우팅 필요
        val focalMm: Float,
        val fovDeg: Float,         // 수평 화각 (0 = 미상)
        val minFocusCm: Float?,    // null = 고정초점 또는 미상
        val maxW: Int,
        val maxH: Int,
        val manual: Boolean,
        val raw: Boolean,
        val source: Source,
    ) {
        val key: String get() = physicalId ?: openId

        val label: String
            get() {
                val idText = physicalId?.let { "$it@$openId" } ?: openId
                val focus = minFocusCm?.let { "AF ${it.toInt()}cm~" } ?: "고정초점"
                return "[$idText] ${roleName(fovDeg)} ${"%.1f".format(focalMm)}mm " +
                    "FOV ${fovDeg.toInt()}° ${maxW}x${maxH} $focus"
            }
    }

    /** 한 논리 카메라에서 동시에 열 수 있는 화각 세트 */
    data class TriSelection(
        val openId: String,
        val main: CameraChoice,
        val ultraWide: CameraChoice?,
        val tele: CameraChoice?,
        val standaloneRoles: Set<String> = emptySet(),
    ) {
        /** role → choice, main 우선 순서 고정 */
        fun roles(): List<Pair<String, CameraChoice>> = buildList {
            add(ROLE_MAIN to main)
            ultraWide?.let { add(ROLE_ULTRA_WIDE to it) }
            tele?.let { add(ROLE_TELE to it) }
        }

        val roleCount: Int get() = roles().size
        fun isStandalone(role: String): Boolean = role in standaloneRoles
    }

    // ── 순수 계산 (JVM 단위 테스트 대상) ──────────────────────────

    /** 센서 가로 크기(mm)와 초점거리(mm)로 수평 화각 계산 */
    fun fovDeg(sensorWidthMm: Float, focalMm: Float): Float =
        if (!sensorWidthMm.isFinite() || !focalMm.isFinite() || sensorWidthMm <= 0f || focalMm <= 0f) 0f
        else Math.toDegrees(2.0 * Math.atan((sensorWidthMm / (2.0 * focalMm)))).toFloat()

    fun roleName(fovDeg: Float): String = when {
        !fovDeg.isFinite() || fovDeg <= 0f -> "화각미상"
        fovDeg >= 95f -> "초광각"
        fovDeg >= 55f -> "광각"
        else -> "망원"
    }

    /** 암실 박스 측정 관점의 주의사항 */
    fun suitabilityNotes(fovDeg: Float, minFocusCm: Float?): List<String> = buildList {
        if (fovDeg in 0.1f..54.9f) {
            add("망원: 박스 거리에서 화면이 프레임을 넘치거나 최단 초점 밖일 수 있음 — 검출 실패 시 자동 제외")
        }
        if (fovDeg >= 95f) {
            add("초광각: 렌즈 왜곡 큼 — dot-grid 잔차와 교차 일치도로 신뢰도 자동 반영")
        }
        if (minFocusCm != null && minFocusCm > 15f) {
            add("최단 초점 ${minFocusCm.toInt()}cm — 박스 높이를 그 이상으로")
        }
        if (minFocusCm == null) add("고정초점 — 근접 선명도 확인 필요")
    }

    /**
     * 동시 3각 세트 선택: 물리 서브카메라가 가장 많은 논리 카메라를 고르고,
     * 그 안에서 초광각(FOV 최대·95°↑) / 표준(55~95° 중 최고 해상도 = "best wide") /
     * 망원(FOV 최소·55°↓)을 배정한다. 표준이 없으면 남은 것 중 최고 해상도.
     */
    fun selectTriSet(
        choices: List<CameraChoice>,
        concurrentSets: List<Set<String>> = emptyList(),
    ): TriSelection? {
        val grouped = choices
            .filter { it.source == Source.LOGICAL_PHYSICAL && it.physicalId != null }
            .groupBy { it.openId }
        val listedBack = choices.filter { it.source == Source.LISTED && it.physicalId == null }
        return grouped.values.sortedByDescending { it.size }
            .firstNotNullOfOrNull { selectFromGroup(it, listedBack, concurrentSets) }
            ?: selectListedPair(listedBack, concurrentSets)
    }

    /**
     * 물리 서브카메라를 노출하지 않는 기기(삼성 등)용 2대 세트: 공개 ID로 따로 보이는
     * 표준(메인)과 초광각을 각각 열어 동시에 찍는다. 초광각은 독립 오픈(standalone) 역할이다.
     * OS가 보고한 동시 오픈 세트에 있는 쌍을 우선하되, 없어도 시도한다. 삼성은 그 세트에
     * 전·후면 쌍만 올리는 경우가 많고, 실제로 안 되면 CaptureController가 메인을 잃은 것을
     * 감지해 메인 단독으로 돌아간다.
     */
    private fun selectListedPair(listedBack: List<CameraChoice>, concurrentSets: List<Set<String>>): TriSelection? {
        val main = listedBack.filter { it.fovDeg in 55f..95f }.maxByOrNull { it.maxW.toLong() * it.maxH }
            ?: return null
        val ultra = listedBack
            .filter { it !== main && it.openId != main.openId && it.fovDeg >= 95f }
            .sortedWith(compareByDescending<CameraChoice> { canOpenConcurrently(main.openId, it.openId, concurrentSets) }
                .thenByDescending { it.fovDeg })
            .firstOrNull()
            ?: return null
        return TriSelection(main.openId, main, ultra, null, setOf(ROLE_ULTRA_WIDE))
    }

    private fun selectFromGroup(group: List<CameraChoice>, listedBack: List<CameraChoice>, concurrentSets: List<Set<String>>): TriSelection? {
        val ultraFromGroup = group.filter { it.fovDeg >= 95f }.maxByOrNull { it.fovDeg }
        val ultraFromListed = listedBack
            .filter { listed ->
                listed.fovDeg >= 95f &&
                    group.none { it.key == listed.key } &&
                    canOpenConcurrently(group.first().openId, listed.openId, concurrentSets)
            }
            .maxByOrNull { it.fovDeg }
        val ultra = ultraFromGroup ?: ultraFromListed
        val tele = group.filter { it.fovDeg > 0f && it.fovDeg < 55f }.minByOrNull { it.fovDeg }
        val mids = group.filter { it !== ultra && it !== tele && (it.fovDeg <= 0f || it.fovDeg in 55f..95f) }
        val main = mids.maxByOrNull { it.maxW.toLong() * it.maxH }
            ?: group.filter { it !== ultra && it !== tele }.maxByOrNull { it.maxW.toLong() * it.maxH }
            ?: return null
        if (ultra == null && tele == null) return null // 화각이 하나뿐이면 동시 교차 의미 없음
        val standalone = buildSet {
            if (ultra != null && ultra.source != Source.LOGICAL_PHYSICAL) add(ROLE_ULTRA_WIDE)
        }
        return TriSelection(main.openId, main, ultra, tele, standalone)
    }

    private fun canOpenConcurrently(
        logicalId: String,
        listedId: String,
        concurrentSets: List<Set<String>>,
    ): Boolean {
        if (concurrentSets.isEmpty()) return true
        return concurrentSets.any { logicalId in it && listedId in it }
    }

    fun concurrentCameraSets(manager: CameraManager): List<Set<String>> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            manager.concurrentCameraIds.map { it.toSet() }
        }.getOrDefault(emptyList())
    }

    // ── Camera2 조회 ─────────────────────────────────────────────

    fun enumerate(manager: CameraManager): List<CameraChoice> {
        val out = LinkedHashMap<String, CameraChoice>()
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())

        for (id in listed) {
            toChoice(manager, id, parentId = null, Source.LISTED)?.let { out[it.key] = it }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            for (id in listed) {
                val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
                val physicals = runCatching { ch.physicalCameraIds }.getOrDefault(emptySet())
                for (pid in physicals) {
                    if (out.containsKey(pid)) continue
                    toChoice(manager, pid, parentId = id, Source.LOGICAL_PHYSICAL)
                        ?.let { out[it.key] = it }
                }
            }
        }

        // OEM 숨김 ID 프로브 (진단·직접 열기 시도용)
        val probeCandidates = (0..9) + (20..39) + (50..56)
        for (n in probeCandidates) {
            val id = n.toString()
            if (out.containsKey(id) || id in listed) continue
            toChoice(manager, id, parentId = null, Source.PROBED)?.let { out[it.key] = it }
        }
        return out.values.toList()
    }

    private fun toChoice(
        manager: CameraManager,
        id: String,
        parentId: String?,
        source: Source,
    ): CameraChoice? {
        val ch = try {
            manager.getCameraCharacteristics(id)
        } catch (e: Throwable) {
            return null
        }
        if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return null
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val size = (map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty() +
            map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() +
            map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty())
            .maxByOrNull { it.width.toLong() * it.height } ?: return null

        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f
        val sensorW = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
        val minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()

        return CameraChoice(
            openId = parentId ?: id,
            physicalId = if (parentId != null) id else null,
            focalMm = focal,
            fovDeg = fovDeg(sensorW, focal),
            minFocusCm = minFocusDiopters?.takeIf { it > 0f }?.let { 100f / it },
            maxW = size.width,
            maxH = size.height,
            manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            source = source,
        )
    }

    /**
     * 초고해상도(픽셀 비닝 해제, 예: 200MP) 출력이 서드파티에 열려 있는지 진단한다 (API 31+).
     * 기본 스트림 맵은 비닝된 해상도(예: 4080x3060)만 주고, 원본 해상도는
     * SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION 전용 맵에만 나온다.
     */
    fun maxResolutionReport(manager: CameraManager, choices: List<CameraChoice>): List<String> {
        if (Build.VERSION.SDK_INT < 31) return listOf("초고해상도 모드: API 31 미만이라 확인 불가")
        val out = ArrayList<String>()
        for (c in choices.filter { it.source == Source.LISTED }) {
            val ch = runCatching { manager.getCameraCharacteristics(c.openId) }.getOrNull() ?: continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            val ultra = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            fun biggest(format: Int) = runCatching { map?.getOutputSizes(format) }.getOrNull()
                ?.maxByOrNull { it.width.toLong() * it.height }?.let { "${it.width}x${it.height}" } ?: "-"
            val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            out += "초고해상도 [${c.openId}]: capability ${if (ultra) "있음" else "없음"}, " +
                "센서 ${pixelArray?.let { "${it.width}x${it.height}" } ?: "?"}, " +
                "최대해상도 맵 YUV ${biggest(ImageFormat.YUV_420_888)} / JPEG ${biggest(ImageFormat.JPEG)} / " +
                "RAW ${biggest(ImageFormat.RAW_SENSOR)}"
        }
        return out
    }

    fun report(choices: List<CameraChoice>, tri: TriSelection?): String = buildString {
        appendLine("카메라 진단: 후면 ${choices.size}개 (공개 ${choices.count { it.source == Source.LISTED }}, " +
            "물리 ${choices.count { it.source == Source.LOGICAL_PHYSICAL }}, " +
            "숨김 ${choices.count { it.source == Source.PROBED }})")
        for (c in choices) {
            appendLine("· ${c.label} ${if (c.manual) "manual" else "auto"}${if (c.raw) "+RAW" else ""}")
            for (note in suitabilityNotes(c.fovDeg, c.minFocusCm)) appendLine("   ⚠ $note")
        }
        if (tri != null) {
            appendLine(
                "동시 3각 세트: 표준=[${tri.main.key}]" +
                    (tri.ultraWide?.let { ", 초광각=[${it.key}]" } ?: "") +
                    (tri.tele?.let { ", 망원=[${it.key}]" } ?: "") +
                    if (tri.standaloneRoles.isEmpty()) {
                        " (논리 ${tri.openId} 한 세션에서 동시 촬영)"
                    } else {
                        " (논리 ${tri.openId} + 독립 ${tri.standaloneRoles.joinToString()} 동시 오픈 시도)"
                    }
            )
        } else {
            appendLine("동시 3각 세트: 불가 — 이 기기는 물리 서브카메라를 서드파티에 노출하지 않음 (메인 단독 측정)")
        }
    }
}
