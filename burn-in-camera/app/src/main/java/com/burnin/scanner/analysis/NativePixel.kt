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

    private external fun averageNative(paths: Array<String>, mode: Int, maxLongEdge: Int, yLut: FloatArray): GrayImage?

    private external fun lumaGridNative(
        gray: FloatArray, w: Int, h: Int, black: FloatArray?, homography: DoubleArray,
        screenW: Int, screenH: Int, gw: Int, gh: Int, margin: Float,
    ): FloatArray?
}
