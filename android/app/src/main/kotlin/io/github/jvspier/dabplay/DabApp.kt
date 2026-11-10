package io.github.jvspier.dabplay

import android.app.Application
import io.github.jvspier.dabplay.data.FmTransmitters
import io.github.jvspier.dabplay.playback.CarLocation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.usb.UsbManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.jvspier.dabplay.audio.AudioPlayer
import io.github.jvspier.dabplay.data.LogoStore
import io.github.jvspier.dabplay.diag.Diagnostics
import io.github.jvspier.dabplay.diag.DiagnosticsExport
import io.github.jvspier.dabplay.data.SettingsStore
import io.github.jvspier.dabplay.data.StationStore
import io.github.jvspier.dabplay.engine.AudioFocus
import io.github.jvspier.dabplay.engine.DabEngine
import io.github.jvspier.dabplay.fyt.FytRadio
import io.github.jvspier.dabplay.playback.RadioController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.jvspier.dabplay.logos.LogoFetcher
import java.io.File

/** Manual DI: one engine and one station store for the whole process (UI and media service share them). */
class DabApp : Application() {
    lateinit var store: StationStore; private set
    lateinit var engine: DabEngine; private set
    lateinit var settings: SettingsStore; private set
    lateinit var logos: LogoStore; private set
    lateinit var logoFetcher: LogoFetcher; private set
    /** The event log (logcat + a file to pull after a drive). */
    lateinit var events: Diagnostics; private set
    val diagnosticsExport by lazy { DiagnosticsExport(this, events) }
    /** The head unit's own FM tuner (FYT head units only), connected while Settings → FM test is open. */
    val fmRadio by lazy { FytRadio(this, ::fmEvent) }
    /** DAB and FM as one radio (the FM fork): what the UI and the media session talk to. */
    lateinit var radio: RadioController; private set
    private val fmLines = MutableStateFlow<List<String>>(emptyList())
    /** The FM test's recent events, for its screen (the diagnostics log has them all). */
    val fmLog: StateFlow<List<String>> = fmLines
    private val fmTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun fmEvent(msg: String) {
        events.i("FytRadio", msg)
        val line = "${synchronized(fmTime) { fmTime.format(Date()) }} $msg"
        fmLines.update { (it + line).takeLast(200) }
    }

    /** The FYT system properties that say which tuner the head unit has (getprop: no hidden API needed). */
    fun fytProps(): String = listOf("sys.fyt.radio_type", "persist.lsec.radio.ms_handler", "ro.fyt.platform", "sys.fyt.platform").joinToString(", ") { k ->
        val v = runCatching { Runtime.getRuntime().exec(arrayOf("getprop", k)).inputStream.bufferedReader().readText().trim() }.getOrDefault("?")
        "$k=${v.ifEmpty { "-" }}"
    }

    /**
     * The offline FM transmitter lists (assets/fm/: Germany, Austria, Switzerland, one file each), merged, for FM
     * station names and FM nearby: near a border both sides count. Null if none loads.
     */
    val fmList: FmTransmitters? by lazy {
        val files = runCatching { assets.list("fm").orEmpty().filter { it.endsWith(".tsv") }.sorted() }.getOrDefault(emptyList())
        val all = files.flatMap { f ->
            runCatching { assets.open("fm/$f").bufferedReader().use { FmTransmitters.parse(it.readText()).all } }
                .onFailure { events.w("Radio", "FM transmitter list $f didn't load", it) }.getOrDefault(emptyList())
        }
        if (all.isEmpty()) null else FmTransmitters(all).also { events.i("Radio", "FM transmitter lists ${files.joinToString()}: ${all.size} transmitters") }
    }
    val carLocation by lazy { CarLocation(this) }

    /** MainActivity is on screen (started, not stopped): a dongle appearing then connects without opening anything. */
    @Volatile var uiVisible = false
    private val main = Handler(Looper.getMainLooper())
    /** Deep sleep so far at the last [sleepWatch] tick. */
    private var asleepMark = deepSleepSoFar()
    /** Deep sleep so far when a wake-up was seen, while [confirmWake] waits. */
    private var asleepAtWake = 0L

    /**
     * Start with the car on head units that sleep instead of switching off (the Dudu3): the app lives on, and a car
     * start brings no boot, no USB event and, on the Dudu3, not even "screen on" (log 2026-10-27). So the app notices
     * the sleep itself: a plain timer (no alarm, no wake lock: it can't wake the unit or keep it awake) ticks while the
     * unit is awake; during deep sleep it stops, and on the first tick after it the time spent asleep (elapsed time
     * goes on in deep sleep, uptime doesn't) has jumped.
     */
    private val sleepWatch = object : Runnable {
        override fun run() {
            val asleep = deepSleepSoFar()
            val sleptS = (asleep - asleepMark) / 1000
            asleepMark = asleep
            if (sleptS >= WAKE_MIN_SLEEP_S) {
                events.i("DabApp", "awake again after $sleptS s of deep sleep")
                asleepAtWake = asleep
                main.removeCallbacks(confirmWake); main.postDelayed(confirmWake, STAY_AWAKE_MS)
            }
            main.postDelayed(this, SLEEP_CHECK_MS)
        }
    }

    /** Only a unit that stays awake opens the radio: not one that wakes for a moment (the car starting to charge). */
    private val confirmWake = Runnable {
        if (deepSleepSoFar() - asleepAtWake > 1000) events.i("DabApp", "asleep again straight away: not opening")
        else woke()
    }

    override fun onCreate() {
        super.onCreate()
        events = Diagnostics(getExternalFilesDir("diagnostics") ?: File(filesDir, "diagnostics"))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        events.i("DabApp", "DABplay+ $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        store = StationStore(File(filesDir, "stations.json"), onError = { events.e("DabApp", "saving stations failed", it) })
        settings = SettingsStore(this)
        engine = DabEngine(store, AudioFocus(getSystemService(AudioManager::class.java)),
            newAudio = { AudioPlayer { events.i("AudioPlayer", it) } }, events = events,
            resumeOnStart = { settings.state.value.resumeOnStart }, followStation = { settings.state.value.followStation },
            trafficAnnouncements = { settings.state.value.trafficAnnouncements },
            usbThreadSetup = { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) })
        radio = RadioController(engine, fmRadio, store, events,
            trafficAnnouncements = { settings.state.value.trafficAnnouncements },
            uiVisible = { uiVisible },
            fmList = { fmList },
            location = carLocation::now,
            rememberEstimate = carLocation::rememberEstimate,
            bringBack = {
                runCatching { startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
                    .onFailure { events.w("Radio", "bringing the app back failed", it) }
            })
        // an FM-only station last time: the engine leaves it to the app (a DAB one resumes when the dongle is ready)
        if (settings.state.value.resumeOnStart && store.state.value.lastPlayed?.isFm == true) radio.resume()
        logos = LogoStore(File(filesDir, "logos"))
        logoFetcher = LogoFetcher(this, store, logos, settings, current = { engine.state.value.current?.key }).also { it.start() }
        // in the app process, not an activity: the radio plays on with no screen open, and an unplug must still stop it
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                events.i("DabApp", "USB device removed: ${dev?.deviceName} ${dev?.let { "%04x:%04x".format(it.vendorId, it.productId) }}")
                engine.detached(dev?.deviceName)                          // only our dongle stops the radio
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        main.postDelayed(sleepWatch, SLEEP_CHECK_MS)
        // for the log only (the Dudu3 sends neither): what a head unit tells about its screen
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                events.i("DabApp", if (intent.action == Intent.ACTION_SCREEN_OFF) "screen off" else "screen on")
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * The unit is awake after a deep sleep (the car was off) and has stayed awake. A screen that only went dark while
     * something played keeps the CPU awake, so that never counts.
     */
    private fun woke() {
        val s = settings.state.value
        if (!s.startWithCar) return
        // Android 10+ lets an app open itself from the background only with "Display over other apps" allowed
        if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(this))
            events.w("DabApp", "back from sleep: can't open, \"Display over other apps\" isn't allowed")
        else {
            events.i("DabApp", "back from sleep: Start with the car is on, opening")
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { events.w("DabApp", "couldn't open the app: ${it.message}") }
        }
        // as at a cold start: the last station plays again (Settings → Resume last station)
        if (s.resumeOnStart && !radio.playing) radio.resume()
    }

    private companion object {
        /** Seconds asleep that count as the car having been off. */
        const val WAKE_MIN_SLEEP_S = 10
        /** How often the sleep watch looks, while the unit is awake. */
        const val SLEEP_CHECK_MS = 3000L
        /** How long a unit must stay awake after a sleep before the radio opens. */
        const val STAY_AWAKE_MS = 3000L
        /** Elapsed time goes on in deep sleep, uptime doesn't. */
        fun deepSleepSoFar() = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
    }
}
