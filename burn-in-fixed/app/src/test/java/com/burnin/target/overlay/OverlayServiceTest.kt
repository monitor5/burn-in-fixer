package com.burnin.target.overlay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.widget.ImageView
import com.burnin.target.DeviceRole
import com.burnin.target.correction.CorrectionStore
import com.burnin.target.net.Protocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w4dp-h3dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun map(color:Int) {
        val size=CorrectionStore.realScreenSize(context);val b=Bitmap.createBitmap(size.x,size.y,Bitmap.Config.ARGB_8888);b.eraseColor(color)
        val out=ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle()
        assertNull(CorrectionStore.applyFromBase64(context,size.x,size.y,.1,100,"",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP),"test"))
    }
    @Test fun mapReplacementUpdatesExistingOverlayAndDestroyRemovesIt() {
        map(Color.WHITE);ShadowSettings.setCanDrawOverlays(true)
        val controller=Robolectric.buildService(OverlayService::class.java).create();val service=controller.get()
        try {
            service.onStartCommand(Intent().putExtra(OverlayService.EXTRA_STRENGTH,100),0,1)
            val field=OverlayService::class.java.getDeclaredField("overlayView").apply{isAccessible=true}
            val view=field.get(service) as ImageView;val before=(view.drawable as BitmapDrawable).bitmap
            map(Color.BLACK);service.onStartCommand(Intent().putExtra(OverlayService.EXTRA_STRENGTH,50),0,2)
            assertSame(view,field.get(service));assertNotSame(before,(view.drawable as BitmapDrawable).bitmap)
            assertSame(CorrectionStore.bakedBitmap,(view.drawable as BitmapDrawable).bitmap);assertEquals(127,view.imageAlpha)
        } finally {controller.destroy()}
        assertFalse(OverlayService.running)
    }
    @Test fun referenceRoleAndMissingPermissionCannotStartCorrection() {
        map(Color.WHITE);DeviceRole.set(context,Protocol.ROLE_REFERENCE);ShadowSettings.setCanDrawOverlays(true)
        assertNotNull(OverlayService.requestStart(context,100))
        DeviceRole.set(context,Protocol.ROLE_ADJUSTMENT);ShadowSettings.setCanDrawOverlays(false)
        assertNotNull(OverlayService.requestStart(context,100))
    }
    @Test fun stopActionStopsWithoutStickyRestart() {
        val controller=Robolectric.buildService(OverlayService::class.java).create();val service=controller.get()
        try {assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(Intent(OverlayService.ACTION_STOP),0,1));assertTrue(shadowOf(service).isStoppedBySelf)}
        finally {controller.destroy()}
    }
}
