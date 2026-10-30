package io.github.jvspier.dabplay.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The head unit's own FM tuner on FYT head units (the EKIY T900 and others with `com.syu.ms`). The tuner sits behind
 * the MCU; the MainServer drives it and routes its audio straight to the amplifier, so no audio passes through the
 * app. The app asks for the radio as the audio source (main module), then sends tuner commands (radio module) and
 * follows the updates. Codes from the FYT radio app (../docs/fyt-radio.md). One connection per process; all Binder
 * calls run on one thread.
 */
class FytRadio(context: Context, private val log: (String) -> Unit) {
    data class State(
        val connected: Boolean = false,
        /** The MainServer's current audio source: 1 is the radio, 0 none, 10 a third-party player, -1 unknown. */
        val appId: Int = -1,
        val band: Int = -1,
        /** 10 kHz units for FM (8750 = 87.50 MHz), kHz for AM. */
        val freq: Int = 0,
        val region: Int = -1,
        val ps: String = "",
        val radioText: String = "",
        val stereo: Boolean = false,
        /** 0 none, 1 auto store, 2 up, 3 down. */
        val search: Int = 0,
        val rds: Boolean = false,
        val af: Boolean = false,
        val ta: Boolean = false,
        val tp: Boolean = false,
        val taActive: Boolean = false,
        val tunerOn: Boolean = false,
        val pty: Int = -1,
        /** FM range from the server: min, max, step (10 kHz units). */
        val range: IntArray? = null,
    ) {
        val sourceIsRadio get() = appId == APP_ID_RADIO
    }

    /** Log every update (the FM test page); otherwise only source changes, frequencies and names. */
    @Volatile var verbose = false
    private val ctx = context.applicationContext
    private val thread = HandlerThread("fyt-radio").apply { start() }
    private val handler = Handler(thread.looper)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Commands sent before the connection was up; run once it is (on [thread]). */
    private val pending = ArrayList<Pair<String, () -> Unit>>()
    @Volatile private var main: RemoteModule? = null
    @Volatile private var radio: RemoteModule? = null
    @Volatile private var bound = false

    private val mainCb = ModuleCallback { u, ints, strs -> onMain(u, ints, strs) }
    private val radioCb = ModuleCallback { u, ints, strs -> onRadio(u, ints, strs) }

    /** The MainServer is installed: an FYT head unit. */
    val available: Boolean
        get() = runCatching { ctx.packageManager.getPackageInfo(Syu.PACKAGE, 0); true }.getOrDefault(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handler.post {
                log("connected to ${Syu.PACKAGE}/${Syu.SERVICE}")
                runCatching {
                    val toolkit = RemoteToolkit(binder)
                    val m = toolkit.module(Syu.MODULE_MAIN); val r = toolkit.module(Syu.MODULE_RADIO)
                    log("modules: main ${if (m != null) "ok" else "missing"}, radio ${if (r != null) "ok" else "missing"}")
                    main = m; radio = r
                    m?.register(mainCb, MAIN_APP_ID)
                    if (r != null) for (u in 0 until RADIO_UPDATES) r.register(radioCb, u)
                    _state.update { it.copy(connected = m != null && r != null) }
                    m?.get(MAIN_APP_ID)?.ints?.firstOrNull()?.let { id -> log("get: source $id"); _state.update { it.copy(appId = id) } }
                    r?.get(R_FREQ)?.ints?.firstOrNull()?.let { f -> log("get: frequency ${fmt(f)}"); _state.update { it.copy(freq = f) } }
                }.onFailure { log("setting up the modules failed: $it") }
                if (main != null && radio != null) { val p = pending.toList(); pending.clear(); p.forEach { (what, b) -> run(what, b) } }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            log("MainServer connection lost (Android binds again when it's back)")
            main = null; radio = null
            _state.update { it.copy(connected = false) }
        }
    }

    fun connect() {
        if (bound) return
        val intent = Intent().setClassName(Syu.PACKAGE, Syu.SERVICE)
        bound = runCatching { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            .onFailure { log("bindService threw: $it") }.getOrDefault(false)
        log(if (bound) "binding ${Syu.PACKAGE}/${Syu.SERVICE}" else "bindService refused (MainServer missing or not exported)")
    }

    fun disconnect() {
        if (!bound) return
        handler.post {
            runCatching {
                main?.unregister(mainCb, MAIN_APP_ID)
                radio?.let { r -> for (u in 0 until RADIO_UPDATES) r.unregister(radioCb, u) }
            }
        }
        handler.post { pending.clear() }
        runCatching { ctx.unbindService(conn) }
        bound = false; main = null; radio = null
        _state.update { State() }
        log("disconnected")
    }

    /**
     * Make the radio the audio source. As the FYT radio app does: ask, and ask again until the server reports it
     * (every second, five times at most).
     */
    fun requestSource() = onThread("request source") {
        main?.cmd(0, intArrayOf(APP_ID_RADIO)) ?: log("not connected")
        retrySource(5)
    }

    private fun retrySource(left: Int) {
        handler.postDelayed({
            if (!_state.value.sourceIsRadio && left > 0) {
                log("source is still ${_state.value.appId}, asking again")
                main?.cmd(0, intArrayOf(APP_ID_RADIO)); retrySource(left - 1)
            }
        }, if (left == 5) 300L else 1000L)
    }

    /** Give the source back, only if it is still the radio (another source may have taken over meanwhile). */
    fun releaseSource() = onThread("release source") {
        if (_state.value.sourceIsRadio) main?.cmd(0, intArrayOf(APP_ID_NULL)) else log("source isn't the radio (${_state.value.appId}), left alone")
    }

    fun stepUp() = radioCmd("step up", 3)
    fun stepDown() = radioCmd("step down", 4)
    fun seekUp() = radioCmd("seek up", 5)
    fun seekDown() = radioCmd("seek down", 6)
    fun bandFm() = radioCmd("band FM", 11, -3)
    /** [freq] in 10 kHz units. */
    fun tune(freq: Int) = radioCmd("tune ${fmt(freq)}", 13, 3, freq)
    fun toggleAf() = radioCmd("AF toggle", 20, 2)
    fun toggleTa() = radioCmd("TA toggle", 19, 2)
    fun setRds(on: Boolean) = radioCmd("RDS ${if (on) "on" else "off"}", 16, if (on) 1 else 0)

    private fun radioCmd(what: String, code: Int, vararg ints: Int) = onThread(what) {
        radio?.cmd(code, if (ints.isEmpty()) null else ints) ?: log("not connected")
    }

    private fun onThread(what: String, block: () -> Unit) {
        handler.post {
            if (main == null || radio == null) { if (bound) { log("> $what (once connected)"); pending += what to block } else log("> $what: not connected") }
            else run(what, block)
        }
    }

    private fun run(what: String, block: () -> Unit) {
        log("> $what")
        runCatching(block).onFailure { log("$what failed: $it") }
    }

    private fun onMain(u: Int, ints: IntArray?, strs: Array<String>?) {
        if (u == MAIN_APP_ID) ints?.firstOrNull()?.let { id ->
            log("< source $id${if (id == APP_ID_RADIO) " (radio)" else ""}")
            _state.update { it.copy(appId = id) }
        }
    }

    private fun onRadio(u: Int, ints: IntArray?, strs: Array<String>?) {
        val i = ints?.firstOrNull()
        val s = strs?.firstOrNull()?.trim()
        when (u) {
            R_BAND -> i?.let { v -> _state.update { it.copy(band = v) } }
            R_FREQ -> i?.let { v -> _state.update { it.copy(freq = v, ps = if (v != it.freq) "" else it.ps, radioText = if (v != it.freq) "" else it.radioText) } }
            R_REGION -> i?.let { v -> _state.update { it.copy(region = v) } }
            R_PTY -> i?.let { v -> _state.update { it.copy(pty = v) } }
            R_AF -> i?.let { v -> _state.update { it.copy(af = v != 0) } }
            R_TA_ACTIVE -> i?.let { v -> _state.update { it.copy(taActive = v != 0) } }
            R_TP -> i?.let { v -> _state.update { it.copy(tp = v != 0) } }
            R_TA -> i?.let { v -> _state.update { it.copy(ta = v != 0) } }
            R_RT -> _state.update { it.copy(radioText = s.orEmpty()) }
            R_RDS -> i?.let { v -> _state.update { it.copy(rds = v != 0) } }
            R_RANGE -> if (ints != null && ints.size >= 3) _state.update { it.copy(range = ints.copyOf(3)) }
            R_STEREO -> i?.let { v -> _state.update { it.copy(stereo = v != 0) } }
            R_SEARCH -> i?.let { v -> _state.update { it.copy(search = v) } }
            R_PS -> _state.update { it.copy(ps = s.orEmpty()) }
            R_TUNER_ON -> i?.let { v -> _state.update { it.copy(tunerOn = v != 0) } }
        }
        // radio text can be a song title: its length only, like the app's other song texts (Diagnostics)
        val shown = when (u) {
            R_RT -> "radio text (${s?.length ?: 0} chars)"
            R_FREQ -> "frequency ${i?.let(::fmt)}"
            else -> "u$u ${ints?.joinToString(",", "[", "]") ?: "-"}${if (strs != null) " \"${strs.joinToString("|")}\"" else ""}"
        }
        if (verbose || u == R_FREQ || u == R_PS) log("< $shown")
    }

    companion object {
        const val APP_ID_NULL = 0
        const val APP_ID_RADIO = 1
        private const val MAIN_APP_ID = 0
        private const val RADIO_UPDATES = 30
        const val R_BAND = 0; const val R_FREQ = 1; const val R_REGION = 2; const val R_PTY = 5; const val R_AF = 6
        const val R_TA_ACTIVE = 7; const val R_TP = 8; const val R_TA = 9; const val R_RT = 13; const val R_RDS = 15
        const val R_RANGE = 16; const val R_STEREO = 21; const val R_SEARCH = 22; const val R_PS = 26; const val R_TUNER_ON = 27

        /** "101.70 MHz" for FM (10 kHz units), "1008 kHz" below that. */
        fun fmt(freq: Int) = if (freq >= 5000) "%d.%02d MHz".format(freq / 100, freq % 100) else "$freq kHz"
    }
}
