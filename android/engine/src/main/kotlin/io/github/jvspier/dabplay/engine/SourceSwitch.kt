package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.core.Reception
import io.github.jvspier.dabplay.data.ServiceKey

/**
 * The FM fork's rule for a station that is on DAB and on FM, with the source on automatic: DAB first; FM takes over
 * when DAB is lost for [LOST_TO_FM_MS] or weak for [WEAK_TO_FM_MS], and DAB comes back after [BACK_TO_DAB_MS] of good
 * reception. Only the timers and the decision live here (plain Kotlin, tested on the JVM); RadioController feeds it
 * the engine's state twice a second and does the switching.
 */
class SourceSwitch {
    enum class Move { STAY, TO_FM, TO_DAB }

    private var badSince = 0L
    private var goodSince = 0L

    /** A new station, or the listener chose a source: the timers start again. */
    fun reset() { badSince = 0; goodSince = 0 }

    /**
     * One look at the engine. [key]: the listener's station. [onFm]: FM is the source now. The engine keeps receiving
     * silently while FM covers for it, so [e] says how DAB is doing in both cases.
     */
    fun step(now: Long, e: EngineState, key: ServiceKey, onFm: Boolean): Move {
        val onDab = e.current?.key == key || e.announcement != null
        val bad = !e.dongle || e.problem != null || (e.playing && onDab && e.reception != Reception.GOOD)
        val good = e.dongle && e.playing && onDab && !e.tuning && e.reception == Reception.GOOD && e.problem == null
        badSince = if (bad) (badSince.takeIf { it > 0 } ?: now) else 0
        goodSince = if (good) (goodSince.takeIf { it > 0 } ?: now) else 0
        if (onFm) return if (goodSince > 0 && now - goodSince >= BACK_TO_DAB_MS) Move.TO_DAB else Move.STAY
        val limit = if (e.reception == Reception.WEAK) WEAK_TO_FM_MS else LOST_TO_FM_MS
        // not during a DAB scan (the engine isn't playing anything then)
        return if (badSince > 0 && now - badSince >= limit && e.scan == null) Move.TO_FM else Move.STAY
    }

    companion object {
        /** DAB lost this long: FM takes over (the engine's own DAB following starts after 6 s). */
        const val LOST_TO_FM_MS = 2_500L
        /** DAB weak (dropouts) this long: FM takes over. */
        const val WEAK_TO_FM_MS = 6_000L
        /** DAB good again this long: back to DAB. */
        const val BACK_TO_DAB_MS = 5_000L
    }
}
