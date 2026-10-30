package io.github.jvspier.dabplay.core.fic

import kotlin.test.Test
import kotlin.test.assertEquals

class FollowingTest {
    // shaped like the 5B FIC (captures/golden/2026-09-24-5B.fic.txt): oe_service 8421 → 6 EIds, fi rm 0 per EId
    private val oe = mapOf(0x8421L to setOf(0x805A, 0x806A, 0x807C, 0x805B), 0x841BL to setOf(0x812B))
    private val fi = mapOf(
        FiKey(true, 0, 0x805A) to setOf<FiFrequency>(FiFrequency.Khz(174928)),
        FiKey(true, 0, 0x806A) to setOf<FiFrequency>(FiFrequency.Khz(181936)),
        FiKey(true, 0, 0x807C) to setOf<FiFrequency>(FiFrequency.Khz(192352)),
        FiKey(false, 8, 0x8421) to setOf<FiFrequency>(FiFrequency.Khz(98400)),       // FM: ignored
    )

    @Test fun dabFrequenciesOfTheOtherEnsembles() {
        assertEquals(listOf(Alternative(0x805A, 174928), Alternative(0x806A, 181936), Alternative(0x807C, 192352)),
            dabAlternatives(oe, fi, 0x8421, ownEid = 0x805B))
    }

    @Test fun anEnsembleWithoutKnownFrequencyIsNoAlternative() {
        assertEquals(emptyList(), dabAlternatives(oe, fi, 0x841B, ownEid = 0x805B))
        assertEquals(emptyList(), dabAlternatives(oe, fi, 0x83C7, ownEid = 0x8008))
    }

    @Test fun fmFrequenciesByPiEqualToSid() {
        assertEquals(listOf(98400), fmFrequencies(emptyList(), fi, 0x8421))
        assertEquals(emptyList(), fmFrequencies(emptyList(), fi, 0x841B))
    }

    @Test fun fmFrequenciesThroughALinkageSet() {
        val ls = LinkageSet(false, 1, hard = true, active = true, otherEnsemble = false).apply {
            ids += LinkId("DAB", 0x83C7); ids += LinkId("RDS-PI", 0x8C07)
        }
        val fm = mapOf(
            FiKey(false, 9, 0x8C07) to setOf<FiFrequency>(FiFrequency.Khz(101700), FiFrequency.Khz(88300)),
            FiKey(false, 0, 0x8C07) to setOf<FiFrequency>(FiFrequency.Khz(174928)),     // a DAB entry: not FM
        )
        assertEquals(listOf(88300, 101700), fmFrequencies(listOf(ls), fm, 0x83C7))
        assertEquals(emptyList(), fmFrequencies(listOf(ls), fm, 0x83C8))
    }
}
