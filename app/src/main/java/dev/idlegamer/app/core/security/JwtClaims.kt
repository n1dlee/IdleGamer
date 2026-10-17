package dev.idlegamer.app.core.security

import dev.idlegamer.app.domain.model.SteamId
import java.util.Base64

/**
 * Reads (does **not** verify) the payload of a Steam JWT. Used only for consistency checks — e.g. that the
 * token's subject is the account Steam logged us into, and when it expires. Authorization decisions are
 * always Steam's; a forged payload here can only make IdleGamer refuse a session, never accept one.
 */
data class JwtClaims(
    val subject: SteamId?,
    val expiresAtEpochSeconds: Long?,
    val audience: List<String>,
) {
    companion object {
        private val SUB = Regex(""""sub"\s*:\s*"(\d{1,20})"""")
        private val EXP = Regex(""""exp"\s*:\s*(\d{1,12})""")
        private val AUD_ARRAY = Regex(""""aud"\s*:\s*\[([^\]]*)]""")
        private val AUD_SINGLE = Regex(""""aud"\s*:\s*"([^"]*)"""")
        private val QUOTED = Regex(""""([^"]*)"""")

        fun parse(token: Secret): JwtClaims? {
            val parts = token.reveal().split('.')
            if (parts.size != 3) return null
            val json = try {
                String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val subject = SUB.find(json)?.groupValues?.get(1)?.let { java.lang.Long.parseUnsignedLong(it) }?.let(::SteamId)
            val exp = EXP.find(json)?.groupValues?.get(1)?.toLongOrNull()
            val audience = AUD_ARRAY.find(json)?.groupValues?.get(1)
                ?.let { inner -> QUOTED.findAll(inner).map { it.groupValues[1] }.toList() }
                ?: AUD_SINGLE.find(json)?.groupValues?.get(1)?.let(::listOf)
                ?: emptyList()
            return JwtClaims(subject, exp, audience)
        }
    }
}
