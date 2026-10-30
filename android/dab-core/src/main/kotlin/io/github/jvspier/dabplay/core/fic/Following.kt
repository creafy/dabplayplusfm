package io.github.jvspier.dabplay.core.fic

/** Another ensemble that carries a service, and a frequency it's on. */
data class Alternative(val eid: Int, val khz: Int)

/**
 * DAB alternatives for a service, from the ensemble's own FIC: the other ensembles carrying it (FIG 0/24) with their
 * DAB frequencies (FIG 0/21, R&M 0 = DAB ensemble). FM entries (R&M 8/9) are [fmFrequencies].
 * Seen on 5B (2026-09-24/27): RADIONL (SId 8421) is announced on 6 other regional ensembles, with frequencies.
 */
fun dabAlternatives(otherEnsembles: Map<Long, Set<Int>>, frequencies: Map<FiKey, Set<FiFrequency>>, sid: Long, ownEid: Int?) =
    otherEnsembles[sid].orEmpty().filter { it != ownEid }.flatMap { eid ->
        frequencies.filterKeys { it.rm == 0 && it.id == eid }.values.flatten()
            .filterIsInstance<FiFrequency.Khz>().map { Alternative(eid, it.khz) }
    }.distinct()

/**
 * The FM frequencies (kHz) a service is also on, for DAB → FM fallback on head units with their own FM tuner: FIG 0/21
 * entries with R&M 8/9 (FM with / without RDS) whose Id is one of the service's RDS PI codes. Its PI codes: the ones a
 * FIG 0/6 linkage set lists next to the service's SId, and the SId itself (broadcasters that use one code for both;
 * verified for NPO Radio 1/2/3FM/Klassiek and Omroep Zeeland on 12C/5B, findings.md).
 */
fun fmFrequencies(links: Collection<LinkageSet>, frequencies: Map<FiKey, Set<FiFrequency>>, sid: Long): List<Int> {
    val pis = HashSet<Int>()
    if (sid <= 0xFFFF) pis += sid.toInt()
    for (ls in links) if (ls.ids.any { it.type == "DAB" && it.id == sid })
        ls.ids.filter { it.type == "RDS-PI" }.forEach { pis += it.id.toInt() }
    return frequencies.filterKeys { (it.rm == 8 || it.rm == 9) && it.id in pis }.values.flatten()
        .filterIsInstance<FiFrequency.Khz>().map { it.khz }.distinct().sorted()
}
