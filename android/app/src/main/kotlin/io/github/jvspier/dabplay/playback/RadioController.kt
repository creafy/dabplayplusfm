package io.github.jvspier.dabplay.playback

import android.media.AudioManager
import io.github.jvspier.dabplay.core.Reception
import io.github.jvspier.dabplay.core.pad.DlsParser
import io.github.jvspier.dabplay.core.pad.NowPlaying
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.Station
import io.github.jvspier.dabplay.data.StationStore
import io.github.jvspier.dabplay.data.StoredFm
import io.github.jvspier.dabplay.engine.AudioFocus
import io.github.jvspier.dabplay.engine.DabEngine
import io.github.jvspier.dabplay.engine.EngineLog
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.engine.FocusChange
import io.github.jvspier.dabplay.fyt.FytRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the station plays from now. */
enum class Source { DAB, FM }

/** The listener's choice on Now playing: the best source (DAB first), or one pinned by hand (owner, 2026-10-30). */
enum class SourceChoice { AUTO, DAB, FM }

/** What the source chip and the FM row on Now playing show. */
data class SourceUi(
    /** The head unit has an FM tuner (an FYT MainServer). */
    val fmAvailable: Boolean = false,
    val active: Source = Source.DAB,
    val choice: SourceChoice = SourceChoice.AUTO,
    /** The station plays on both: the chip can switch. */
    val canSwitch: Boolean = false,
    /** FM frequency now (10 kHz units), when FM plays. */
    val fmFreq: Int = 0,
    val fmStereo: Boolean = false,
    val fmSeeking: Boolean = false,
    /** FM scan in progress: the frequency reached and the stations found so far. */
    val fmScan: Pair<Int, Int>? = null,
)

/**
 * The FM fork's one radio: DAB through the engine and the dongle, FM through the head unit's own tuner ([FytRadio]),
 * one station whatever it's received on. Picks the source (DAB first; FM when DAB is weak or lost, or the station has
 * no DAB; back to DAB once it's good again) unless the listener pinned one. While FM covers for DAB, the engine keeps
 * receiving silently, so DAB coming back is seen at once. The UI and the media session see [state]: the engine's state,
 * with the station and FM's radio text filled in while FM plays. Runs on the main thread.
 */
class RadioController(
    private val engine: DabEngine,
    private val fm: FytRadio,
    private val store: StationStore,
    private val log: EngineLog,
    am: AudioManager,
    /** Settings → Traffic announcements: FM's TA follows it. */
    private val trafficAnnouncements: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Our own audio focus while FM plays and the engine doesn't (an FM-only station): other media apps pause. */
    private val fmFocus = AudioFocus(am)
    private val fmAvailable = fm.available

    /** The listener's station (null: nothing chosen yet). */
    private var wanted: ServiceKey? = null
    private var choice = SourceChoice.AUTO
    private var active = Source.DAB
    private var fmPlaying = false
    /** A seek or step from the FM row: the station is whatever the tuner lands on. */
    private var tunedByHand: Station? = null
    /** Paused by the listener: nothing switches on by itself. */
    private var paused = false
    private var dabBadSince = 0L
    private var dabGoodSince = 0L
    private var scanJob: Job? = null
    private val ui = MutableStateFlow(SourceUi(fmAvailable = fmAvailable))
    val source: StateFlow<SourceUi> = ui

    val state: StateFlow<EngineState> = combine(engine.state, fm.state, store.state, ui) { e, f, db, u ->
        if (u.active != Source.FM && wanted?.isFm != true) return@combine e
        val st = tunedByHand ?: wanted?.let(db::station) ?: e.current
        val rt = f.radioText.takeIf { it.isNotBlank() && u.active == Source.FM }?.let { t ->
            if (st != null && st.hasDab) DlsParser.parse(t, st.key.ecc, st.key.sid, st.label) else NowPlaying(t)
        }
        e.copy(current = st, playing = fmPlaying, tuning = false, nowPlaying = rt, slide = null, reception = Reception.GOOD,
            problem = if (!fmAvailable) "This head unit has no FM tuner" else if (!f.connected) "FM tuner not connected yet" else null,
            following = null, announcement = null, taSupported = f.tp, dongle = true, scan = e.scan,
            status = "FM ${Station.fmText(f.freq)}", signalBars = 4)
    }.stateIn(scope, SharingStarted.Eagerly, engine.state.value)

    init {
        if (fmAvailable) {
            fm.connect()
            scope.launch { while (isActive) { tick(); delay(500) } }
            scope.launch { fm.state.collect(::onFm) }
        }
    }

    // ---- what the UI and the media session call ----

    fun play(key: ServiceKey) {
        val db = store.state.value
        val st = db.station(key) ?: tunedByHand?.takeIf { it.key == key }
        wanted = st?.key ?: key
        tunedByHand = null
        choice = SourceChoice.AUTO
        paused = false
        dabBadSince = 0; dabGoodSince = 0
        when {
            st == null -> engine.play(key)
            !st.hasDab -> { engine.pause(); engine.setMuted(false); store.played(st.key); startFm(st.fm!!, ownFocus = true) }
            st.fm != null && fmAvailable && !engine.state.value.dongle -> {        // no dongle: FM straight away
                store.played(st.key); startFm(st.fm!!, ownFocus = true)
            }
            else -> { stopFm(); engine.setMuted(false); engine.play(st.key) }
        }
        publish()
    }

    fun pause() {
        paused = true
        if (active == Source.FM || fmPlaying) stopFm()
        engine.setMuted(false)
        engine.pause()
        publish()
    }

    fun resume() {
        val key = wanted ?: engine.state.value.current?.key ?: store.state.value.lastPlayed
        if (key != null) play(key) else engine.resume()
    }

    val playing get() = state.value.playing

    fun togglePlay() = if (playing) pause() else resume()

    /** Next/previous favourite (steering-wheel buttons); falls back to all stations when there are no favourites. */
    fun skip(delta: Int) {
        val s = store.state.value
        val list = s.favouriteStations.ifEmpty { s.stations }
        if (list.isEmpty()) return
        val cur = engine.state.value.announcement?.returnTo ?: wanted ?: engine.state.value.current?.key
        val i = list.indexOfFirst { it.key == cur }
        play(list[Math.floorMod((if (i < 0) -1 else i) + delta, list.size)].key)
    }

    /** The source chip: automatic → DAB → FM → automatic (only the ones this station has). */
    fun cycleSource() {
        val st = wanted?.let(store.state.value::station) ?: return
        if (!st.hasDab || st.fm == null || !fmAvailable) return
        choice = when (choice) { SourceChoice.AUTO -> SourceChoice.DAB; SourceChoice.DAB -> SourceChoice.FM; SourceChoice.FM -> SourceChoice.AUTO }
        log.i(TAG, "source for ${st.label}: $choice")
        when (choice) {
            SourceChoice.FM -> toFm(st)
            SourceChoice.DAB -> toDab()
            SourceChoice.AUTO -> { dabGoodSince = 0; dabBadSince = 0 }       // the next ticks decide
        }
        publish()
    }

    fun seekUp() = byHand { fm.seekUp() }
    fun seekDown() = byHand { fm.seekDown() }
    fun stepUp() = byHand { fm.stepUp() }
    fun stepDown() = byHand { fm.stepDown() }

    private fun byHand(cmd: () -> Unit) {
        if (active != Source.FM) return
        tunedByHand = currentStation()
        cmd()
    }

    /** Link the playing DAB station to the FM frequency playing now, or (null) never to FM; [auto]: back to automatic. */
    fun linkFm(key: ServiceKey, freq: Int?) = store.linkFm(key, freq)
    fun autoLinkFm(key: ServiceKey) = store.autoLinkFm(key)

    /**
     * Settings-free FM scan (Stations → Scan FM): seek up through the band from 87.5 MHz, wait at each stop for the RDS
     * name (up to 3 s), until the tuner wraps around. The FM source is taken for the scan, so it's audible.
     */
    fun scanFm() {
        if (!fmAvailable || scanJob?.isActive == true) return
        val before = wanted
        scanJob = scope.launch {
            engine.pause(); engine.setMuted(false)
            startFm(8750, ownFocus = true)
            withTimeoutOrNull(5000) { fm.state.first { it.connected && it.sourceIsRadio } }
            delay(800)
            val found = LinkedHashMap<Int, String>()
            var last = 8750
            ui.update { it.copy(fmScan = 8750 to 0) }
            log.i(TAG, "FM scan")
            for (n in 0 until 80) {
                fm.seekUp()
                val f = settle(last) ?: break                           // no seek happened: give up
                if (f <= last) break                                   // wrapped around: the band is done
                last = f
                ui.update { it.copy(fmScan = f to found.size) }
                val ps = withTimeoutOrNull(3000) { fm.state.first { it.freq == f && it.ps.isNotBlank() } }?.ps ?: ""
                found[f] = ps.trim()
                ui.update { it.copy(fmScan = f to found.size) }
            }
            log.i(TAG, "FM scan done: ${found.size} stations")
            store.applyFmScan(found.map { (f, ps) -> StoredFm(f, ps, 0) })
            ui.update { it.copy(fmScan = null) }
            stopFm()
            before?.let(::play)
        }
    }

    /**
     * After a seek command: wait for it to start (the frequency moves or the seek state says so), then until the
     * tuner rests on one frequency for 0.7 s with no seek running. Null: nothing moved.
     */
    private suspend fun settle(from: Int): Int? {
        withTimeoutOrNull(3000) { fm.state.first { it.search != 0 || it.freq != from } } ?: return null
        return withTimeoutOrNull(20_000) {
            var f = fm.state.value.freq
            var since = System.currentTimeMillis()
            while (true) {
                delay(150)
                val s = fm.state.value
                if (s.search != 0 || s.freq != f) { f = s.freq; since = System.currentTimeMillis() }
                else if (System.currentTimeMillis() - since >= 700) break
            }
            f
        }
    }

    // ---- switching ----

    private fun currentStation(): Station? {
        val f = fm.state.value.freq
        val db = store.state.value
        return db.stations.firstOrNull { it.fm == f } ?: Station.fmOnly(StoredFm(f, fm.state.value.ps, 0))
    }

    private fun startFm(freq: Int, ownFocus: Boolean) {
        if (!fmAvailable) return
        if (ownFocus) fmFocus.request { c -> if (c == FocusChange.LOST && active == Source.FM && !engine.state.value.playing) { log.i(TAG, "FM: another app took the audio"); pause() } }
        active = Source.FM; fmPlaying = true
        fmSourceSeen = false; sourceTakenSince = 0
        fm.requestSource()
        fm.bandFm()
        fm.tune(freq)
        if (trafficAnnouncements() != fm.state.value.ta) fm.toggleTa()
        if (!fm.state.value.af) fm.toggleAf()
        publish()
    }

    private fun stopFm() {
        if (active == Source.FM || fmPlaying) fm.releaseSource()
        fmFocus.abandon()
        active = Source.DAB; fmPlaying = false; tunedByHand = null
    }

    /** DAB → FM for the same station: DAB keeps receiving, silent. */
    private fun toFm(st: Station) {
        val f = st.fm ?: return
        enginePlayedUnderFm = false
        log.i(TAG, "${st.label}: FM ${Station.fmText(f)} takes over")
        engine.setMuted(true)
        startFm(f, ownFocus = !engine.state.value.playing)
    }

    private fun toDab() {
        val st = wanted?.let(store.state.value::station) ?: return
        log.i(TAG, "${st.label}: back to DAB")
        stopFm()
        engine.setMuted(false)
        if (!engine.state.value.playing || engine.state.value.current?.key != st.key) engine.play(st.key)
    }

    /** Twice a second: is DAB good enough, or should FM cover for it? */
    private fun tick() {
        val e = engine.state.value
        val now = System.currentTimeMillis()
        // another source took the head unit's audio (BT, the stock radio app): FM isn't playing for us any more
        if (active == Source.FM && fmPlaying && sourceTakenSince > 0 && now - sourceTakenSince > SOURCE_TAKEN_MS) {
            log.i(TAG, "FM: the head unit switched to source ${fm.state.value.appId}"); pause(); return
        }
        if (paused) return
        if (wanted == null && e.playing) wanted = e.current?.key          // started by the engine (resume at start-up)
        val st = wanted?.let(store.state.value::station) ?: return
        if (!st.hasDab || st.fm == null || choice != SourceChoice.AUTO) return
        val onDab = e.current?.key == st.key || e.announcement != null
        val bad = !e.dongle || e.problem != null || (e.playing && onDab && e.reception != Reception.GOOD)
        val good = e.dongle && e.playing && onDab && !e.tuning && e.reception == Reception.GOOD && e.problem == null
        dabBadSince = if (bad) (dabBadSince.takeIf { it > 0 } ?: now) else 0
        dabGoodSince = if (good) (dabGoodSince.takeIf { it > 0 } ?: now) else 0
        when (active) {
            Source.DAB -> {
                val limit = if (e.reception == Reception.WEAK) WEAK_TO_FM_MS else LOST_TO_FM_MS
                if (dabBadSince > 0 && now - dabBadSince >= limit && fmPlayingAllowed(e)) toFm(st)
            }
            Source.FM -> {
                if (dabGoodSince > 0 && now - dabGoodSince >= BACK_TO_DAB_MS) toDab()
                // the engine stopped by itself (another media app took the audio focus): the radio stops, FM too
                else if (e.dongle && !e.playing && !e.tuning && e.scan == null && enginePlayedUnderFm) {
                    log.i(TAG, "DAB stopped while FM covered for it (audio focus?): pausing"); pause(); return
                }
                if (e.playing) enginePlayedUnderFm = true
            }
        }
        publish()
    }
    /** While FM covers for DAB: the engine was seen playing (so its stopping later means something). */
    private var enginePlayedUnderFm = false

    /** Not during a DAB scan (the engine isn't playing anything then). */
    private fun fmPlayingAllowed(e: EngineState) = e.scan == null

    private fun onFm(f: FytRadio.State) {
        // the FM row's seek/step landed: that frequency is the station now
        if (tunedByHand != null && f.search == 0) tunedByHand = currentStation()
        // an FM-only station's name, heard while listening (the scan may have caught none)
        val k = wanted
        if (active == Source.FM && k != null && k.isFm && f.freq == k.fmFreq && f.ps.isNotBlank()) store.fmName(f.freq, f.ps.trim())
        // another source took over on the head unit (seen in tick): only after ours was there, a request takes a moment
        if (f.sourceIsRadio) { fmSourceSeen = true; sourceTakenSince = 0 }
        else if (fmSourceSeen && fmPlaying && active == Source.FM && sourceTakenSince == 0L) sourceTakenSince = System.currentTimeMillis()
        publish()
    }
    private var fmSourceSeen = false
    private var sourceTakenSince = 0L

    private fun publish() {
        val st = wanted?.let(store.state.value::station)
        val f = fm.state.value
        ui.value = SourceUi(fmAvailable, active, choice, canSwitch = fmAvailable && st != null && st.hasDab && st.fm != null,
            fmFreq = if (active == Source.FM) f.freq else 0, fmStereo = f.stereo, fmSeeking = f.search != 0, fmScan = ui.value.fmScan)
    }

    private companion object {
        const val TAG = "Radio"
        /** DAB lost this long: FM takes over (the engine's own DAB following starts after 6 s). */
        const val LOST_TO_FM_MS = 2_500L
        /** DAB weak (dropouts) this long: FM takes over. */
        const val WEAK_TO_FM_MS = 6_000L
        /** DAB good again this long: back to DAB. */
        const val BACK_TO_DAB_MS = 5_000L
        /** The head unit on another source this long: FM counts as stopped. */
        const val SOURCE_TAKEN_MS = 4_000L
    }
}
