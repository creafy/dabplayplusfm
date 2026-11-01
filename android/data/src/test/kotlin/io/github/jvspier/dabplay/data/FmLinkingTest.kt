package io.github.jvspier.dabplay.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FmLinkingTest {
    private fun dab(sid: Long, label: String) = Station(StoredService(ServiceKey(0xE0, sid), label, 1, 96, 63),
        StoredEnsemble(0x1001, "11D", 222064, "WDR", 0, 0, emptyList()))
    private val wdr2 = dab(0xD392, "WDR 2 Rheinland")
    private val einslive = dab(0xD391, "1LIVE")
    private val wdr4 = dab(0xD394, "WDR 4")
    private val fm = listOf(StoredFm(9980, "WDR 2", 0), StoredFm(10630, "1LIVE", 0), StoredFm(8830, "ANTENNE", 0))

    @Test fun announcedFrequenciesComeFirstAndPreferOnesTheScanFound() {
        val l = FmLinking.link(listOf(wdr2, einslive), fm, mapOf(wdr2.key to listOf(100400, 99800)), emptyMap())
        assertEquals(9980, l[wdr2.key])
        assertEquals(10630, l[einslive.key])                         // by name
    }

    @Test fun anAnnouncedFrequencyCountsWithoutAnFmScan() {
        assertEquals(10040, FmLinking.link(listOf(wdr2), emptyList(), mapOf(wdr2.key to listOf(100400)), emptyMap())[wdr2.key])
    }

    @Test fun byHandWinsAndZeroMeansNever() {
        val l = FmLinking.link(listOf(wdr2, einslive), fm, emptyMap(), mapOf(wdr2.key to 8830, einslive.key to 0))
        assertEquals(8830, l[wdr2.key])
        assertNull(l[einslive.key])
    }

    @Test fun namesMatchLoosely() {
        assertTrue(FmLinking.namesMatch("WDR 2", "WDR 2 Rheinland"))
        assertTrue(FmLinking.namesMatch("1LIVE", "1Live"))
        assertTrue(!FmLinking.namesMatch("WDR", "WDR 4"))            // too short to be a start
        assertTrue(!FmLinking.namesMatch("WDR 2", "WDR 4"))
    }

    @Test fun snapshotListsEachStationOnce() {
        val e = StoredEnsemble(0x1001, "11D", 222064, "WDR", 1, 0, listOf(wdr2.service, einslive.service, wdr4.service))
        val s = Snapshot(ensembles = listOf(e), fmStations = fm)
        assertEquals(listOf("1LIVE", "ANTENNE", "WDR 2 Rheinland", "WDR 4"), s.stations.map { it.label })
        assertEquals(9980, s.station(wdr2.key)?.fm)
        assertEquals(ServiceKey.fm(8830), s.stations[1].key)
        assertEquals(wdr2.key, s.station(ServiceKey.fm(9980))?.key)  // an old FM favourite finds its DAB station
        assertEquals("101.7", Station.fmText(10170)); assertEquals("87.55", Station.fmText(8755))
    }

    @Test fun aNameInBracketsCountsOnItsOwn() {
        assertTrue(FmLinking.namesMatch("Deutschlandfunk (Dlf)", "DLF"))
        assertTrue(FmLinking.namesMatch("Deutschlandfunk (Dlf)", "Deutschlandfunk"))
        assertTrue(FmLinking.namesMatch("NDR 2 Hamburg", "NDR 2"))
        assertTrue(!FmLinking.namesMatch("NDR 2 Hamburg", "NDR 1 Nieders."))
    }
}
