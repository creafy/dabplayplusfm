package io.github.jvspier.dabplay.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A place on earth (degrees). */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * The offline FM transmitter list (the FM fork; Germany: the German Wikipedia's list of FM transmitters, CC BY-SA
 * 4.0, `assets/fm/de.tsv`, made by tools/gen_fm_list.py). The head unit's stock radio app keeps the RDS names to
 * itself, so FM stations get their names here: what's on a frequency at a place. Also which programmes can be
 * received around a place, and on which frequency a programme is strongest there (as the car moves on).
 *
 * Reception is a rough model: a transmitter reaches [rangeKm] (from its power) and the strongest is the one with
 * the most power for its distance (ERP / d²). Good enough to tell the transmitters on one frequency apart, which
 * are far from each other by design.
 */
class FmTransmitters(val all: List<Tx>) {
    data class Tx(val freq: Int, val at: GeoPoint, val kw: Double, val name: String)

    private val byFreq = all.groupBy { it.freq }

    /** The programme on [freq] (10 kHz units) at [at]: the strongest transmitter in reach, or null. */
    fun nameAt(freq: Int, at: GeoPoint): String? = strongest(byFreq[freq].orEmpty(), at)?.name

    /**
     * The programmes receivable at [at], each once, on its strongest frequency there; strongest first. Names that
     * differ only by region ("NDR 2 Hamburg", "NDR 2 Niedersachsen") stay apart: they're different broadcasts.
     */
    fun nearby(at: GeoPoint): List<StoredFm> =
        all.filter { inReach(it, at) }.groupBy { it.name }.values
            .map { txs -> txs.maxBy { score(it, at) } }
            .sortedByDescending { score(it, at) }
            .map { StoredFm(it.freq, it.name, 0) }

    /** The strongest frequency at [at] of a programme whose name matches the station [label] (see FmLinking). */
    fun bestFreq(label: String, at: GeoPoint): Int? =
        strongest(all.filter { FmLinking.namesMatch(it.name, label) }, at)?.freq

    /**
     * Where the car probably is, from the frequencies a scan found (no location from the head unit): the transmitter
     * site from which the most of them are in reach (ties: the more powerful). Null with fewer than 3 frequencies.
     */
    fun locate(freqs: Collection<Int>): GeoPoint? {
        val fs = freqs.toSet()
        if (fs.size < 3) return null
        val power = all.groupBy { it.at }.mapValues { (_, txs) -> txs.sumOf { it.kw } }
        return power.keys.maxByOrNull { p -> fs.count { f -> byFreq[f].orEmpty().any { inReach(it, p) } } * 1e6 + power.getValue(p) }
    }

    private fun strongest(txs: List<Tx>, at: GeoPoint) = txs.filter { inReach(it, at) }.maxByOrNull { score(it, at) }

    private fun inReach(t: Tx, at: GeoPoint) = distanceKm(t.at, at) <= rangeKm(t.kw)
    private fun score(t: Tx, at: GeoPoint) = t.kw / max(1.0, distanceKm(t.at, at)).pow(2)

    companion object {
        /** 0.1 kW: ~14 km, 1 kW: 25 km, 10 kW: ~44 km, 100 kW: ~79 km (a car aerial, flat land). */
        fun rangeKm(kw: Double) = 25.0 * max(kw, 0.01).pow(0.25)

        fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
            val r = Math.toRadians(1.0)
            val dLat = (b.lat - a.lat) * r; val dLon = (b.lon - a.lon) * r
            val h = sin(dLat / 2).pow(2) + cos(a.lat * r) * cos(b.lat * r) * sin(dLon / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(h))
        }

        /** The asset's text: `freq TAB lat TAB lon TAB kW TAB name` per line, `#` comments. */
        fun parse(text: String) = FmTransmitters(text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
            val c = l.split('\t')
            if (c.size < 5) null
            else runCatching { Tx(c[0].toInt(), GeoPoint(c[1].toDouble(), c[2].toDouble()), c[3].toDouble(), c[4].trim()) }.getOrNull()
        }.toList())
    }
}
