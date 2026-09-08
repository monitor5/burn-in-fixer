package com.burnin.scanner.analysis

import android.graphics.ImageFormat
import com.burnin.scanner.camera.CaptureFrame
import org.junit.Assert.*
import org.junit.Test

class YuvDecodeTest {
    private fun plane(values:IntArray,row:Int,pixel:Int=1)=CaptureFrame.Plane(values.map{it.toByte()}.toByteArray(),row,pixel)
    private fun frame(w:Int,h:Int,planes:List<CaptureFrame.Plane>)=CaptureFrame(ImageFormat.YUV_420_888,w,h,planes,0,null,null)
    @Test fun grayReadsPaddedRowsAndVideoRangeEndpoints() {
        val f=frame(2,2,listOf(plane(intArrayOf(16,235,99,126,16),3)))
        val g=ImageOps.decodeLinearGray(f)
        assertArrayEquals(floatArrayOf(0f,1f,Math.pow(110.0/219,2.2).toFloat(),0f),g.data,1e-6f)
    }
    @Test fun blockAverageIsComputedAfterLinearization() {
        val f=frame(2,2,listOf(plane(intArrayOf(16,235,235,16),2)))
        val g=ImageOps.decodeLinearGray(f,1)
        assertEquals(1,g.w);assertEquals(1,g.h);assertEquals(.5f,g.data[0],1e-6f)
    }
    @Test fun rgbChromaPixelStrideAndChannelOrderArePreserved() {
        val y=plane(IntArray(8){126},4)
        val u=plane(intArrayOf(128,0,240),4,2);val v=plane(intArrayOf(240,0,128),4,2)
        val rgb=ImageOps.decodeLinearRgb(frame(4,2,listOf(y,u,v)))
        assertTrue(rgb.r[0]>.99f);assertTrue(rgb.b[0]<.3f)
        assertTrue(rgb.b[3]>.99f);assertTrue(rgb.r[3]<.3f)
        assertEquals(rgb.r[0],rgb.r[4],0f)
    }
    @Test fun oddDimensionsKeepLastRowAndColumn() {
        val y=plane(IntArray(9){235},3);val uv=plane(IntArray(4){128},2)
        val rgb=ImageOps.decodeLinearRgb(frame(3,3,listOf(y,uv,uv)),1)
        assertEquals(2,rgb.w);assertEquals(2,rgb.h);assertArrayEquals(FloatArray(4){1f},rgb.r,1e-6f)
    }
    @Test fun invalidPlanesLimitsAndFormatsFailBeforeSampling() {
        fun rejects(f:CaptureFrame) { assertThrows(IllegalArgumentException::class.java) { ImageOps.decodeLinearRgb(f) } }
        val y=plane(IntArray(4){128},2);val uv=plane(intArrayOf(128),1)
        rejects(frame(2,2,listOf(y,uv)))
        rejects(frame(2,2,listOf(y,plane(intArrayOf(),1),uv)))
        rejects(frame(2,2,listOf(y.copy(rowStride=0),uv,uv)))
        rejects(frame(2,2,listOf(y,uv,uv)).copy(format=ImageFormat.RAW_SENSOR))
        assertThrows(IllegalArgumentException::class.java) { ImageOps.decodeLinearGray(frame(2,2,listOf(y)),0) }
        assertThrows(IllegalArgumentException::class.java) { ImageOps.decodeLinearRgb(byteArrayOf(),0) }
    }
}
