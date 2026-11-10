package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.core.Reception
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.Station
import io.github.jvspier.dabplay.data.StoredEnsemble
import io.github.jvspier.dabplay.data.StoredService
import io.github.jvspier.dabplay.engine.SourceSwitch.Move
import kotlin.test.Test
import kotlin.test.assertEquals

/** DAB ⇄ FM on automatic: lost 2.5 s or weak 6 s → FM, good 5 s → DAB, looked at twice a second as RadioController does. */
class SourceSwitchTest {
    private val key = ServiceKey(0xE0, 0xD210)
    private val other = ServiceKey(0xE0, 0xD220)
    private fun station(k: ServiceKey) =
        Station(StoredService(k, "Station", 1, 96, 0), StoredEnsemble(0x10C4, "11C", 220352, "Mux", 0, 0, emptyList()))
    private val good = EngineState(dongle = true, current = station(key), playing = true, reception = Reception.GOOD)
    private val weak = good.copy(reception = Reception.WEAK)
    private val lost = good.copy(reception = Reception.LOST)

    /** Feed [e] every 500 ms from [from] for [ms]; the moves that weren't STAY, with their time. */
    private fun SourceSwitch.run(from: Long, ms: Long, e: EngineState, onFm: Boolean = false): List<Pair<Long, Move>> =
        (from..from + ms step 500).map { it to step(it, e, key, onFm) }.filter { it.second != Move.STAY }

    @Test fun goodDabStaysOnDab() {
        assertEquals(emptyList(), SourceSwitch().run(1_000, 60_000, good))
    }

    @Test fun lostDabGoesToFmAfterTwoAndAHalfSeconds() {
        assertEquals(1_000 + SourceSwitch.LOST_TO_FM_MS to Move.TO_FM, SourceSwitch().run(1_000, 10_000, lost).first())
    }

    @Test fun weakDabGoesToFmOnlyAfterSixSeconds() {
        assertEquals(1_000 + SourceSwitch.WEAK_TO_FM_MS to Move.TO_FM, SourceSwitch().run(1_000, 10_000, weak).first())
    }

    @Test fun aShortDropoutDoesNotSwitch() {
        val s = SourceSwitch()
        assertEquals(emptyList(), s.run(1_000, 2_000, lost))
        assertEquals(emptyList(), s.run(3_500, 1_000, good))
        // the next dropout counts from its own start
        assertEquals(5_000 + SourceSwitch.LOST_TO_FM_MS to Move.TO_FM, s.run(5_000, 10_000, lost).first())
    }

    @Test fun noDongleOrAProblemCountsAsLost() {
        assertEquals(1_000 + SourceSwitch.LOST_TO_FM_MS to Move.TO_FM, SourceSwitch().run(1_000, 10_000, EngineState(dongle = false)).first())
        assertEquals(1_000 + SourceSwitch.LOST_TO_FM_MS to Move.TO_FM,
            SourceSwitch().run(1_000, 10_000, good.copy(problem = "The dongle stopped answering")).first())
    }

    @Test fun neverToFmDuringADabScan() {
        val scanning = EngineState(dongle = true, problem = "x", scan = ScanProgress("5A", 0, 38, 0, 0))
        assertEquals(emptyList(), SourceSwitch().run(1_000, 30_000, scanning))
    }

    @Test fun backToDabAfterFiveSecondsOfGoodReception() {
        val s = SourceSwitch()
        assertEquals(emptyList(), s.run(1_000, 20_000, lost, onFm = true))
        assertEquals(22_000 + SourceSwitch.BACK_TO_DAB_MS to Move.TO_DAB, s.run(22_000, 10_000, good, onFm = true).first())
    }

    @Test fun dabFlickeringUnderFmStaysOnFm() {
        val s = SourceSwitch()
        var t = 1_000L
        repeat(10) {                                         // 4 s good, 1 s weak, again and again
            assertEquals(emptyList(), s.run(t, 4_000, good, onFm = true)); t += 4_500
            assertEquals(emptyList(), s.run(t, 500, weak, onFm = true)); t += 1_000
        }
    }

    @Test fun notBackToDabWhileTheEngineIsStillTuningOrOnAnotherStation() {
        assertEquals(emptyList(), SourceSwitch().run(1_000, 20_000, good.copy(tuning = true), onFm = true))
        assertEquals(emptyList(), SourceSwitch().run(1_000, 20_000, good.copy(current = station(other)), onFm = true))
        assertEquals(emptyList(), SourceSwitch().run(1_000, 20_000, good.copy(playing = false), onFm = true))
    }

    @Test fun anotherStationPlayingOnDabIsNoReasonForFm() {
        // the engine hasn't got to the listener's station yet: nothing is known about its reception
        assertEquals(emptyList(), SourceSwitch().run(1_000, 20_000, lost.copy(current = station(other))))
    }

    @Test fun resetStartsTheTimersAgain() {
        val s = SourceSwitch()
        assertEquals(emptyList(), s.run(1_000, 2_000, lost))
        s.reset()                                            // a new station, or the source chip
        assertEquals(emptyList(), s.run(3_500, 2_000, lost))
        assertEquals(Move.TO_FM, s.step(6_000, lost, key, false))
    }
}
