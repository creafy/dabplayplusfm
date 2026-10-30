package io.github.jvspier.dabplay.data

/**
 * A DAB service is identified by ECC + SId (e.g. E3.83C7 = 538), stable across ensembles. An FM-only station (the FM
 * fork: no DAB service it belongs to) gets [FM_ECC], outside the 8-bit ECC range, and its frequency in 10 kHz as the
 * "SId" (100.27BA = 101.70 MHz).
 */
data class ServiceKey(val ecc: Int, val sid: Long) {
    val isFm get() = ecc == FM_ECC
    /** An FM-only station's frequency in 10 kHz units. */
    val fmFreq get() = if (isFm) sid.toInt() else null
    override fun toString() = "%02X.%04X".format(ecc, sid)

    companion object {
        const val FM_ECC = 0x100
        fun fm(freq: Int) = ServiceKey(FM_ECC, freq.toLong())
    }
}
