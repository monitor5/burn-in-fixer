package com.burnin.scanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraEnumeratorTest {

    private fun choice(
        pid: String,
        fov: Float,
        w: Int,
        h: Int,
        openId: String = "0",
        source: CameraEnumerator.Source = CameraEnumerator.Source.LOGICAL_PHYSICAL,
        minFocusCm: Float? = 10f,
    ) = CameraEnumerator.CameraChoice(
        openId = openId,
        physicalId = if (source == CameraEnumerator.Source.LOGICAL_PHYSICAL) pid else null,
        focalMm = 5f,
        fovDeg = fov,
        minFocusCm = minFocusCm,
        maxW = w,
        maxH = h,
        manual = true,
        raw = false,
        source = source,
    )

    @Test
    fun computesHorizontalFov() {
        // 센서 가로 6.4mm, 초점 5.6mm → 2·atan(0.571) ≈ 59.4°
        assertEquals(59.4f, CameraEnumerator.fovDeg(6.4f, 5.6f), 0.5f)
        assertEquals(0f, CameraEnumerator.fovDeg(0f, 5f), 1e-6f)
    }

    @Test
    fun classifiesRolesByFov() {
        assertEquals("초광각", CameraEnumerator.roleName(120f))
        assertEquals("광각", CameraEnumerator.roleName(82f))
        assertEquals("망원", CameraEnumerator.roleName(30f))
    }

    @Test
    fun selectsTriSetUltraMainTele() {
        val uw = choice("2", fov = 120f, w = 4000, h = 3000)
        val mainLow = choice("1", fov = 80f, w = 4000, h = 3000)
        val mainHigh = choice("3", fov = 84f, w = 8160, h = 6120) // 최고 해상도 표준 = best wide
        val tele = choice("4", fov = 30f, w = 4000, h = 3000)

        val tri = CameraEnumerator.selectTriSet(listOf(uw, mainLow, mainHigh, tele))
        assertNotNull(tri)
        assertEquals("3", tri!!.main.physicalId)
        assertEquals("2", tri.ultraWide?.physicalId)
        assertEquals("4", tri.tele?.physicalId)
        assertEquals(3, tri.roleCount)
        assertEquals("0", tri.openId)
    }

    @Test
    fun twoRoleSetStillWorks() {
        val uw = choice("2", fov = 118f, w = 3264, h = 2448)
        val main = choice("1", fov = 80f, w = 8000, h = 6000)
        val tri = CameraEnumerator.selectTriSet(listOf(uw, main))
        assertNotNull(tri)
        assertEquals("1", tri!!.main.physicalId)
        assertNull(tri.tele)
        assertEquals(2, tri.roleCount)
    }

    @Test
    fun listedUltraWideRequiresConcurrentSetWhenOsReportsSets() {
        val listedUltra = choice(
            "2",
            fov = 103f,
            w = 4000,
            h = 3000,
            openId = "2",
            source = CameraEnumerator.Source.LISTED,
            minFocusCm = null,
        )
        val main = choice("5", fov = 74f, w = 4080, h = 3060)
        val tele = choice("6", fov = 29f, w = 3648, h = 2736)

        val unsupported = CameraEnumerator.selectTriSet(
            listOf(listedUltra, main, tele),
            concurrentSets = listOf(setOf("0", "1"), setOf("0", "3")),
        )
        assertNotNull(unsupported)
        assertNull(unsupported!!.ultraWide)
        assertEquals("6", unsupported.tele?.physicalId)

        val supported = CameraEnumerator.selectTriSet(
            listOf(listedUltra, main, tele),
            concurrentSets = listOf(setOf("0", "2")),
        )
        assertNotNull(supported)
        assertEquals("2", supported!!.ultraWide?.openId)
        assertTrue(supported.isStandalone(CameraEnumerator.ROLE_ULTRA_WIDE))
    }

    @Test
    fun rejectsWhenNoPhysicalSubCameras() {
        // LISTED 단독(물리 미노출 기기) → 동시 3각 불가
        val only = choice("0", fov = 80f, w = 8000, h = 6000, source = CameraEnumerator.Source.LISTED)
        assertNull(CameraEnumerator.selectTriSet(listOf(only)))
    }

    @Test
    fun rejectsSingleFovGroup() {
        // 화각이 하나뿐이면 교차 의미 없음
        val main = choice("1", fov = 80f, w = 8000, h = 6000)
        assertNull(CameraEnumerator.selectTriSet(listOf(main)))
    }

    @Test
    fun suitabilityNotesWarnTeleAndUltraWide() {
        assertTrue(CameraEnumerator.suitabilityNotes(30f, 20f).any { it.contains("망원") })
        assertTrue(CameraEnumerator.suitabilityNotes(120f, 5f).any { it.contains("초광각") })
        assertTrue(CameraEnumerator.suitabilityNotes(80f, null).any { it.contains("고정초점") })
        assertTrue(CameraEnumerator.suitabilityNotes(80f, 30f).any { it.contains("최단 초점") })
    }
    @Test fun exactFovBoundariesAndInvalidValuesHaveStableRoles() {
        assertEquals("망원",CameraEnumerator.roleName(54.999f))
        assertEquals("광각",CameraEnumerator.roleName(55f))
        assertEquals("광각",CameraEnumerator.roleName(94.999f))
        assertEquals("초광각",CameraEnumerator.roleName(95f))
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-1f)) {
            assertEquals("화각미상",CameraEnumerator.roleName(value))
            assertEquals(0f,CameraEnumerator.fovDeg(value,5f),0f)
        }
    }

    @Test fun unusableLargestLogicalGroupDoesNotHideAValidSmallerSet() {
        val redundant=(1..3).map{choice("wide$it",80f,4000,3000,openId="bad")}
        val valid=listOf(choice("main",80f,4000,3000,openId="good"),choice("ultra",120f,4000,3000,openId="good"))
        for(choices in listOf(redundant+valid,(redundant+valid).reversed())) {
            val result=CameraEnumerator.selectTriSet(choices)!!
            assertEquals("good",result.openId);assertEquals(2,result.roleCount)
            assertEquals(listOf(CameraEnumerator.ROLE_MAIN,CameraEnumerator.ROLE_ULTRA_WIDE),result.roles().map{it.first})
        }
    }

}
