package io.github.jvspier.dabplay.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FmTransmittersTest {
    // a few real lines of assets/fm/de.tsv (Hamburg, Hannover, Köln)
    private val list = FmTransmitters.parse("""
        # comment
        8760	53.5192	10.1028	80	NDR 2 Hamburg
        8760	50.9481	6.9431	0.3	WDR 1 live
        9030	53.5192	10.1028	80	NDR 90.3
        8870	53.5631	9.9764	3.2	Deutschlandfunk (Dlf)
        8740	52.3667	9.7333	100	NDR 2 Niedersachsen
        9550	52.3667	9.7333	100	NDR 2 Niedersachsen
        9910	52.3667	9.7333	100	NDR 1 Niedersachsen
    """.trimIndent())
    private val hamburg = GeoPoint(53.55, 10.0)
    private val hannover = GeoPoint(52.37, 9.73)

    @Test fun parses() = assertEquals(7, list.all.size)

    @Test fun nameDependsOnThePlace() {
        assertEquals("NDR 2 Hamburg", list.nameAt(8760, hamburg))
        assertEquals("WDR 1 live", list.nameAt(8760, GeoPoint(50.94, 6.95)))
        assertNull(list.nameAt(9030, hannover))          // ~130 km: out of reach
    }

    @Test fun nearbyHasEachProgrammeOnce() {
        val near = list.nearby(hannover)
        assertEquals(listOf("NDR 2 Niedersachsen", "NDR 1 Niedersachsen"), near.map { it.ps })
        assertTrue(near[0].freq in setOf(8740, 9550))
    }

    @Test fun bestFrequencyForADabLabel() {
        assertEquals(8760, list.bestFreq("NDR 2", hamburg))
        assertEquals(8870, list.bestFreq("DLF", hamburg))
        assertNull(list.bestFreq("Radio Bob", hamburg))
    }

    @Test fun locatesFromScannedFrequencies() {
        val p = assertNotNull(list.locate(listOf(8760, 9030, 8870)))
        assertTrue(FmTransmitters.distanceKm(p, hamburg) < 20)
        assertNull(list.locate(listOf(8760)))
    }

    @Test fun distance() = assertTrue(FmTransmitters.distanceKm(hamburg, hannover) in 130.0..140.0)
}
