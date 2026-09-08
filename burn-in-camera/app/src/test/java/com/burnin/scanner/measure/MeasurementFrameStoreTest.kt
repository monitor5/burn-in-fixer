package com.burnin.scanner.measure

import com.burnin.scanner.analysis.GrayImage
import com.burnin.scanner.analysis.RgbImage
import com.burnin.scanner.camera.CaptureFrame
import com.burnin.scanner.camera.CaptureFrameCodec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MeasurementFrameStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun average(values:List<Int>,method:String):Any {
        val files=values.map { y ->
            temp.newFile().also { file ->
                val frame=CaptureFrame(35,2,2,listOf(CaptureFrame.Plane(ByteArray(4){y.toByte()},2,1),
                    CaptureFrame.Plane(byteArrayOf(128.toByte()),1,1),CaptureFrame.Plane(byteArrayOf(128.toByte()),1,1)),0,null,null)
                CaptureFrameCodec.write(file,frame)
            }
        }
        val storeClass=Class.forName("com.burnin.scanner.measure.MeasurementActivity\$CaptureFrameStore")
        val store=storeClass.getDeclaredConstructor(List::class.java,Long::class.javaPrimitiveType).apply{isAccessible=true}.newInstance(files,0L)
        val activity=Robolectric.buildActivity(MeasurementActivity::class.java).get()
        val fn=MeasurementActivity::class.java.getDeclaredMethod(method,storeClass,Continuation::class.java).apply{isAccessible=true}
        return suspendCoroutine { cont ->
            try { val result=fn.invoke(activity,store,cont);if(result!==COROUTINE_SUSPENDED) cont.resumeWith(Result.success(requireNotNull(result))) }
            catch(e:InvocationTargetException){cont.resumeWith(Result.failure(e.cause!!))}
        }
    }
    private fun linear(y:Int)=Math.pow(((y-16)/219.0).coerceIn(0.0,1.0),2.2).toFloat()
    @Test fun actualGrayStoreTrimsBothExtremesStartingAtFourFrames() = runBlocking {
        val four=average(listOf(16,100,150,235),"averageGrayFromStore") as GrayImage
        assertArrayEquals(FloatArray(4){(linear(100)+linear(150))/2},four.data,1e-6f)
        val three=average(listOf(16,100,235),"averageGrayFromStore") as GrayImage
        assertEquals((linear(16)+linear(100)+linear(235))/3,three.data[0],1e-6f)
    }
    @Test fun actualRgbStoreTrimsEachChannelAndRetainsDimensions() = runBlocking {
        val rgb=average(listOf(16,100,150,235),"averageRgbFromStore") as RgbImage
        assertEquals(2,rgb.w);assertEquals(2,rgb.h)
        assertEquals((linear(100)+linear(150))/2,rgb.r[0],1e-6f)
        assertArrayEquals(rgb.r,rgb.g,1e-6f);assertArrayEquals(rgb.r,rgb.b,1e-6f)
    }
}
