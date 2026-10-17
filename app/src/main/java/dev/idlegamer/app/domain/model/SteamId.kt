package dev.idlegamer.app.domain.model

/**
 * SteamID64. Stored as the raw 64-bit value; always rendered as an *unsigned* decimal string so it
 * survives JSON/JavaScript round-trips (2^53 precision limit) and never shows up negative.
 */
@JvmInline
value class SteamId(val raw: Long) {

    val isValidIndividual: Boolean
        get() {
            // Universe (bits 56-63) Public=1, account type (bits 52-55) Individual=1, account id != 0.
            val universe = (raw ushr 56) and 0xFF
            val type = (raw ushr 52) and 0xF
            val accountId = raw and 0xFFFFFFFFL
            return universe == 1L && type == 1L && accountId != 0L
        }

    /** Full decimal form, for the account screen only. */
    fun asDecimalString(): String = java.lang.Long.toUnsignedString(raw)

    /** Shortened form for diagnostics and logs: `7656119…1234`. */
    fun masked(): String {
        val full = asDecimalString()
        return if (full.length <= 11) "…" else full.take(7) + "…" + full.takeLast(4)
    }

    override fun toString(): String = "SteamId(${masked()})"

    companion object {
        fun parse(decimal: String): SteamId? =
            runCatching { SteamId(java.lang.Long.parseUnsignedLong(decimal.trim())) }.getOrNull()
                ?.takeIf { it.isValidIndividual }
    }
}
