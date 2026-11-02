package com.burnin.scanner.analysis

import java.io.File

/**
 * 네이티브(C++, pixelcore) 픽셀 처리 다리. 프레임 디코드·평균의 중간 배열과 격자 샘플링을
 * Java 힙(512MB 한도) 밖에서 멀티스레드로 처리한다. 라이브러리가 없거나(JVM 단위 테스트 등)
 * 지원하지 않는 형식이면 null을 돌려주고, 호출부는 Kotlin 구현으로 폴백한다.
 */
object NativePixel {
    /** 회색(사이트 WB 정규화) 디코드 모드. 0/1/2는 해당 채널. */
    const val MODE_GRAY = -1

    val available: Boolean = try {
        System.loadLibrary("pixelcore")
        true
    } catch (_: Throwable) {
        false
    }

    /** 끄면 항상 Kotlin 구현을 쓴다(비교 검증용). */
    @Volatile
    var enabled: Boolean = true

    /** 프레임 파일들을 디코드해 양끝값 제외 평균한 이미지. RAW(회색·채널)와 YUV(회색)만 지원. */
    fun average(files: List<File>, mode: Int, maxLongEdge: Int = ImageOps.analysisMaxLongEdge): GrayImage? {
        if (!available || !enabled || files.isEmpty()) return null
        return averageNative(files.map { it.absolutePath }.toTypedArray(), mode, maxLongEdge, ImageOps.yLinearLut())
    }

    /** [Analyzer.lumaGrid]와 같은 9탭 샘플링. */
    fun lumaGrid(
        gray: GrayImage,
        black: GrayImage?,
        h: Homography,
        screenW: Int,
        screenH: Int,
        gw: Int,
        gh: Int,
    ): FloatArray? {
        if (!available || !enabled) return null
        if (black != null && (black.w != gray.w || black.h != gray.h)) return null
        return lumaGridNative(gray.data, gray.w, gray.h, black?.data, h.coefficients(), screenW, screenH, gw, gh,
            Analyzer.SCREEN_SAMPLE_MARGIN)
    }

    /**
     * 프레임마다 디코드해 바로 [gw]×[gh] 격자로 샘플링한 목록(프레임 안정도용).
     * 원본 크기 디코드 버퍼는 네이티브에만 두고 Java에는 작은 격자만 돌려준다.
     */
    fun frameGrids(
        files: List<File>, mode: Int, black: GrayImage?, h: Homography,
        screenW: Int, screenH: Int, gw: Int, gh: Int,
    ): List<FloatArray>? {
        if (!available || !enabled || files.isEmpty()) return null
        val all = frameGridsNative(files.map { it.absolutePath }.toTypedArray(), mode, ImageOps.analysisMaxLongEdge,
            ImageOps.yLinearLut(), black?.data, black?.w ?: 0, black?.h ?: 0, h.coefficients(),
            screenW, screenH, gw, gh, Analyzer.SCREEN_SAMPLE_MARGIN) ?: return null
        val cell = gw * gh
        return List(files.size) { all.copyOfRange(it * cell, (it + 1) * cell) }
    }

    /**
     * 프레임 파일 하나를 디코드해 [out](재사용 버퍼, 디코드 크기와 같아야 함)에 쓰고 [black]을 뺀다.
     * 실패하거나 네이티브가 없으면 false — 호출부가 Kotlin 디코드로 폴백한다.
     */
    fun decodeInto(file: File, mode: Int, maxLongEdge: Int, black: FloatArray?, out: FloatArray): Boolean {
        if (!available || !enabled) return false
        return decodeIntoNative(file.absolutePath, mode, maxLongEdge, ImageOps.yLinearLut(), black, out)
    }

    /**
     * 프레임 묶음을 원본 해상도([maxLongEdge])로 디코드해 바로 격자로 샘플링한 평균(양끝값 제외).
     * [h]는 그 해상도 좌표계의 호모그래피여야 한다. black은 빼지 않는다.
     */
    fun averageGrid(
        files: List<File>, mode: Int, maxLongEdge: Int, h: Homography,
        screenW: Int, screenH: Int, gw: Int, gh: Int,
    ): FloatArray? {
        if (!available || !enabled || files.isEmpty()) return null
        return averageGridNative(files.map { it.absolutePath }.toTypedArray(), mode, maxLongEdge, ImageOps.yLinearLut(),
            h.coefficients(), screenW, screenH, gw, gh, Analyzer.SCREEN_SAMPLE_MARGIN)
    }

    /** RAW 포화 점검: 채널(R,G,B)별 센서 범위 대비 p99.9와 포화(≥0.98) 사이트 비율. RAW가 아니면 null. */
    class RawStats(val p999: FloatArray, val saturated: FloatArray) {
        val peak: Float get() = p999.max()
        val maxSaturated: Float get() = saturated.max()
    }

    fun rawStats(file: File): RawStats? {
        if (!available || !enabled) return null
        val a = rawStatsNative(file.absolutePath) ?: return null
        return RawStats(a.copyOfRange(0, 3), a.copyOfRange(3, 6))
    }

    private external fun rawStatsNative(path: String): FloatArray?

    private external fun averageGridNative(
        paths: Array<String>, mode: Int, maxLongEdge: Int, yLut: FloatArray, homography: DoubleArray,
        screenW: Int, screenH: Int, gw: Int, gh: Int, margin: Float,
    ): FloatArray?

    /** 풀해상도 black을 네이티브에 보관한다(null이면 해제). 이후 [decodeIntoStored]가 이 black을 뺀다. */
    fun storeBlack(black: FloatArray?): Boolean {
        if (!available || !enabled) return false
        storeBlackNative(black)
        return true
    }

    /** [decodeInto]와 같되 [storeBlack]으로 보관한 black을 뺀다(크기가 다르면 false). */
    fun decodeIntoStored(file: File, mode: Int, maxLongEdge: Int, out: FloatArray): Boolean {
        if (!available || !enabled) return false
        return decodeIntoStoredNative(file.absolutePath, mode, maxLongEdge, ImageOps.yLinearLut(), out)
    }

    private external fun storeBlackNative(black: FloatArray?)

    private external fun decodeIntoStoredNative(path: String, mode: Int, maxLongEdge: Int, yLut: FloatArray, out: FloatArray): Boolean

    private external fun decodeIntoNative(
        path: String, mode: Int, maxLongEdge: Int, yLut: FloatArray, black: FloatArray?, out: FloatArray,
    ): Boolean

    private external fun frameGridsNative(
        paths: Array<String>, mode: Int, maxLongEdge: Int, yLut: FloatArray,
        black: FloatArray?, bw: Int, bh: Int, homography: DoubleArray,
        screenW: Int, screenH: Int, gw: Int, gh: Int, margin: Float,
    ): FloatArray?

    private external fun averageNative(paths: Array<String>, mode: Int, maxLongEdge: Int, yLut: FloatArray): GrayImage?

    private external fun lumaGridNative(
        gray: FloatArray, w: Int, h: Int, black: FloatArray?, homography: DoubleArray,
        screenW: Int, screenH: Int, gw: Int, gh: Int, margin: Float,
    ): FloatArray?
}
