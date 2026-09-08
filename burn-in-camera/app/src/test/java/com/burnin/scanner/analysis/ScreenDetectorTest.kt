package com.burnin.scanner.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenDetectorTest {

    private fun brightRect(
        w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int,
        fg: Float = 0.5f, bg: Float = 0.002f,
    ): GrayImage {
        val data = FloatArray(w * h) { bg }
        for (y in top until bottom) {
            for (x in left until right) data[y * w + x] = fg
        }
        return GrayImage(w, h, data)
    }

    @Test
    fun detectsBrightScreenCorners() {
        val img = brightRect(400, 300, 60, 40, 340, 260)
        val r = ScreenDetector.detect(img)
        assertNotNull("화면을 검출하지 못함", r)
        val q = r!!.quad
        assertEquals(60f, q.tl.x, 2f)
        assertEquals(40f, q.tl.y, 2f)
        assertEquals(339f, q.tr.x, 2f)
        assertEquals(40f, q.tr.y, 2f)
        assertEquals(339f, q.br.x, 2f)
        assertEquals(259f, q.br.y, 2f)
        assertEquals(60f, q.bl.x, 2f)
        assertEquals(259f, q.bl.y, 2f)
        assertEquals(0.51f, r.areaRatio, 0.03f)
    }

    @Test
    fun rejectsAllDarkFrame() {
        val img = GrayImage(200, 150, FloatArray(200 * 150) { 0.001f })
        assertNull(ScreenDetector.detect(img))
    }

    @Test
    fun rejectsTinyBrightSpot() {
        // 반사광 같은 작은 밝은 점만 있으면 화면으로 인정하지 않아야 한다
        val img = brightRect(400, 300, 190, 140, 210, 160)
        assertNull(ScreenDetector.detect(img))
    }
    @Test fun isolatedReflectionCannotExpandDetectedScreen() {
        val img=brightRect(400,300,60,40,340,260)
        img.data[0]=1f;img.data[img.data.lastIndex]=1f
        val quad=ScreenDetector.detect(img)!!.quad
        assertEquals(60f,quad.tl.x,1f);assertEquals(40f,quad.tl.y,1f)
        assertEquals(339f,quad.br.x,1f);assertEquals(259f,quad.br.y,1f)
    }

    @Test fun disconnectedSmallHighlightsCannotCombineIntoAScreen() {
        val img=GrayImage(200,200,FloatArray(40000))
        for(y in 0 until 200 step 4) for(x in 0 until 200 step 4) img.data[y*200+x]=1f
        assertNull(ScreenDetector.detect(img))
    }

    @Test fun nonFiniteImageCannotProduceValidGeometry() {
        val img=brightRect(200,150,20,20,180,130);img.data[0]=Float.NaN
        assertNull(ScreenDetector.detect(img))
    }

}
