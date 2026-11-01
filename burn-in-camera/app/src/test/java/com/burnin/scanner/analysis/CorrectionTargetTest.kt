package com.burnin.scanner.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionTargetTest {
    @Test fun darkOutliersDoNotDragTheTargetDownToTheCap() {
        val gw = 120
        val gh = 160
        // 위 1/4은 20% 밝은 띠(덜 탄 영역), 왼쪽 가장자리 띠는 셰이딩 오차로 40% 어둡다(블록 1% 분위수를 끌어내림)
        val luma = FloatArray(gw * gh) { i ->
            val x = i % gw
            val y = i / gw
            when {
                x < 8 -> 0.6f
                y < gh / 4 -> 1.2f
                else -> 1f
            }
        }
        val t = Analyzer.correctionTarget(luma, gw, gh, 0.3f)
        // 예전 목표(중앙값×0.7 = 0.70)는 화면 전체를 30% 가까이 어둡게 하고도 띠를 0.84로 남겼다.
        // 띠를 상한 안에서 맞출 수 있는 0.84 근처(가장자리 블록이 섞여 조금 아래)가 가장 고르다.
        assertTrue("target $t", t in 0.78f..0.86f)
    }
}
