package io.github.jvspier.dabplay.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import io.github.jvspier.dabplay.data.GeoPoint

/**
 * Where the car is, for the FM transmitter list: the head unit's last known location (GPS first; no updates are
 * requested, the navigation keeps it fresh), else a place estimated from an FM scan, kept across restarts.
 */
class CarLocation(context: Context) {
    private val ctx = context.applicationContext
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val prefs = ctx.getSharedPreferences("fm_location", Context.MODE_PRIVATE)

    val permitted get() = PERMISSIONS.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** The head unit's location (younger than 6 h), else the last estimate, else null. */
    fun now(): GeoPoint? = fromSystem() ?: estimated()

    /** The head unit's own location (no estimate), or null. */
    fun fromSystem(): GeoPoint? {
        if (!permitted || lm == null) return null
        return runCatching {
            @SuppressLint("MissingPermission")
            val l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < MAX_AGE_MS }
                .maxByOrNull { it.time }
            l?.let { GeoPoint(it.latitude, it.longitude) }
        }.getOrNull()
    }

    fun estimated(): GeoPoint? =
        if (prefs.contains("lat")) GeoPoint(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble()) else null

    fun rememberEstimate(p: GeoPoint) = prefs.edit().putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat()).apply()

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        private const val MAX_AGE_MS = 6 * 3600_000L
    }
}
