package com.burnin.scanner.analysis

import org.junit.Assert.*
import org.junit.Test

class DotGridDetectorTest {
    private val corners=arrayOf(Vec2(0f,0f),Vec2(240f,0f),Vec2(240f,240f),Vec2(0f,240f))
    private val identity=Homography.from4Points(corners,corners)!!
    private fun image(count:Int=121,dx:Int=0):GrayImage {
        val data=FloatArray(240*240);var n=0
        for(row in 1..11) for(col in 1..11) {
            if(n++>=count) continue
            for(y in row*20-1..row*20+1) for(x in col*20+dx-1..col*20+dx+1) data[y*240+x]=1f
        }
        return GrayImage(240,240,data)
    }
    @Test fun detectsEveryInteriorDotAndRecoversTranslation() {
        val result=DotGridDetector.refineHomography(image(dx=3),identity,240,240)!!
        assertEquals(121,result.matchedPoints);assertTrue(result.rmsResidualPx<.001f);assertTrue(result.maxResidualPx<.001f)
        val out=DoubleArray(2);result.homography.map(83.0,147.0,out)
        assertEquals(86.0,out[0],.001);assertEquals(147.0,out[1],.001)
    }
    @Test fun requiresAtLeastSixteenDistinctNonCollinearMatches() {
        assertNull(DotGridDetector.refineHomography(image(15),identity,240,240))
        assertNotNull(DotGridDetector.refineHomography(image(22),identity,240,240))
    }
    @Test fun rejectsBlankFloodedAndDistantDots() {
        assertNull(DotGridDetector.refineHomography(GrayImage(240,240,FloatArray(57600)),identity,240,240))
        assertNull(DotGridDetector.refineHomography(GrayImage(240,240,FloatArray(57600){1f}),identity,240,240))
        assertNull(DotGridDetector.refineHomography(image(dx=10),identity,240,240))
    }
}
