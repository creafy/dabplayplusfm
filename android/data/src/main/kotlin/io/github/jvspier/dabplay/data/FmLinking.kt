package io.github.jvspier.dabplay.data

/**
 * Which FM frequency belongs to which DAB station (the FM fork: one station, one tile). In this order:
 * 1. a link set by hand (0: never FM);
 * 2. the FM frequencies the station's own multiplex announces for it (FIG 0/6 + 0/21, [Snapshot.fm]): one the FM scan
 *    found, else the first announced (the tuner's AF moves to the strongest of the network anyway);
 * 3. the RDS name: an FM station whose name is the DAB name, or the start of it ("WDR 2" for "WDR 2 Rheinland"), and
 *    no other DAB station's.
 */
object FmLinking {
    fun link(dab: List<Station>, fmStations: List<StoredFm>, announced: Map<ServiceKey, List<Int>>, byHand: Map<ServiceKey, Int>): Map<ServiceKey, Int> {
        val found = fmStations.map { it.freq }.toSet()
        val out = HashMap<ServiceKey, Int>()
        val open = ArrayList<Station>()
        for (st in dab) {
            val hand = byHand[st.key]
            if (hand != null) { if (hand > 0) out[st.key] = hand; continue }
            val fromDab = announced[st.key].orEmpty().map { it / 10 }               // kHz → 10 kHz units
            val f = fromDab.firstOrNull { it in found } ?: fromDab.firstOrNull()
            if (f != null) out[st.key] = f else open += st
        }
        val taken = out.values.toMutableSet()
        for (st in open) {
            val m = fmStations.filter { it.freq !in taken && namesMatch(it.ps, st.label) }
            if (m.size != 1 || open.count { namesMatch(m[0].ps, it.label) } != 1) continue      // ambiguous: leave it
            out[st.key] = m[0].freq; taken += m[0].freq
        }
        return out
    }

    fun namesMatch(ps: String, label: String): Boolean {
        val a = norm(ps); val b = norm(label)
        if (a.length < 3 || b.length < 3) return false
        return a == b || (a.length >= 4 && b.startsWith(a)) || (b.length >= 4 && a.startsWith(b))
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
