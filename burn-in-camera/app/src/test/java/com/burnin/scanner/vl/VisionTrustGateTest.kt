package com.burnin.scanner.vl

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VisionTrustGateTest {
    @Test fun parsesFencedJsonAndPreservesReasonAndRawEvidence() {
        val raw="```json\n{\"status\":\"trust_screen\",\"confidence\":0.9,\"reason\":\"same location\"}\n```"
        val d=VisionTrustGate.parseDecision(raw)
        assertEquals(VisionTrustGate.Status.TRUST_SCREEN,d.status);assertEquals(.9f,d.confidence,0f)
        assertEquals("same location",d.reason);assertEquals(raw,d.rawText)
    }
    @Test fun negatedAmbiguousAndProseStatusesNeverGrantTrust() {
        for(status in listOf("not_trust_screen","untrust_screen","no burn_in","trust_screen or reflection","there is screen_defect")) {
            assertEquals(status,VisionTrustGate.Status.UNCERTAIN,VisionTrustGate.parseDecision("{\"status\":\"$status\",\"confidence\":1}").status)
        }
        assertEquals(VisionTrustGate.Status.UNCERTAIN,VisionTrustGate.parseDecision("Do not trust_screen").status)
    }
    @Test fun aliasesRequireExactTokensAndIgnoreLocale() {
        val old=Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(VisionTrustGate.Status.TRUST_SCREEN,VisionTrustGate.parseDecision("TRUST_SCREEN").status)
            for(s in listOf("untrust_capture","camera_artifact","moire","flicker","reflection","blur"))
                assertEquals(VisionTrustGate.Status.UNTRUST_CAPTURE,VisionTrustGate.parseDecision(s).status)
        } finally { Locale.setDefault(old) }
    }
    @Test fun missingMalformedAndNonFiniteConfidenceCannotRaiseTrust() {
        for(value in listOf("\"NaN\"","\"Infinity\"","null","\"oops\"")) {
            val d=VisionTrustGate.parseDecision("{\"status\":\"trust_screen\",\"confidence\":$value}")
            assertEquals(0f,d.confidence,0f)
        }
        assertEquals(0f,VisionTrustGate.parseDecision("{\"status\":\"trust_screen\"}").confidence,0f)
        assertEquals(0f,VisionTrustGate.parseDecision("").confidence,0f)
        assertEquals(1f,VisionTrustGate.parseDecision("{\"status\":\"trust_screen\",\"confidence\":2}").confidence,0f)
    }
}
