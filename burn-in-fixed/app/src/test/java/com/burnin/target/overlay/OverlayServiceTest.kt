package com.burnin.target.overlay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.graphics.Matrix
import android.graphics.RectF
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
            val view=field.get(service) as OverlayMapView;val before=view.bitmap
            map(Color.BLACK);service.onStartCommand(Intent().putExtra(OverlayService.EXTRA_STRENGTH,50),0,2)
            assertSame(view,field.get(service));assertNotSame(before,view.bitmap)
            assertSame(CorrectionStore.bakedBitmap,view.bitmap);assertEquals(127,view.mapAlpha)
        } finally {controller.destroy()}
        assertFalse(OverlayService.running)
    }
    @Test fun rotatedMapCoversWindowAndRoundTrips() {
        val w=1536f;val h=2048f;val m=Matrix();val s=Matrix()
        for (from in 0..3) for (to in 0..3) {
            OverlayMapView.mapToWindow(from,to,w,h,m,s)
            val mapRect=if(from%2==1) RectF(0f,0f,h,w) else RectF(0f,0f,w,h)
            val out=RectF();m.mapRect(out,mapRect)
            val win=if(to%2==1) RectF(0f,0f,h,w) else RectF(0f,0f,w,h)
            assertEquals("$from→$to",win,out)
        }
        // 고유 방향 좌상단 픽셀은 90도 방향(기기를 반시계로 눕힘)에서 왼쪽 아래로 간다
        OverlayMapView.mapToWindow(0,1,w,h,m,s)
        val p=floatArrayOf(0.5f,0.5f);m.mapPoints(p)
        assertEquals(0.5f,p[0],1e-3f);assertEquals(w-0.5f,p[1],1e-3f)
        // 같은 방향이면 그대로
        OverlayMapView.mapToWindow(2,2,w,h,m,s);assertTrue(m.isIdentity)
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
