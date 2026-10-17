package dev.idlegamer.app.core.logging

/**
 * Last line of defence: scrubs anything that looks like a credential from text before it is logged,
 * stored in the event log or exported. Callers must still never pass secrets on purpose.
 */
object Redactor {
    private const val MASK = "[REDACTED]"

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // JWTs (Steam refresh/access tokens are JWTs: three base64url segments, header starts with eyJ).
        Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}""") to { _ -> "[REDACTED_JWT]" },
        // Steam QR challenge URLs (https://s.team/q/<version>/<clientId>).
        Regex("""https?://s\.team/q/\S+""", RegexOption.IGNORE_CASE) to { _ -> "[REDACTED_QR_URL]" },
        // Bearer headers (before key=value, which would otherwise mask only the word "Bearer").
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*""") to { _ -> "Bearer $MASK" },
        // key=value / key: value pairs for sensitive keys.
        Regex(
            """(?i)\b(refresh_?token|access_?token|token|password|passwd|guard_?data|auth_?code|two_?factor_?code|""" +
                """steamLoginSecure|sessionid|cookie|authorization|shared_?secret|identity_?secret)\b(\s*[:=]\s*)("[^"]*"|\S+)""",
        ) to { m -> "${m.groupValues[1]}${m.groupValues[2]}$MASK" },
        // Long opaque base64/hex blobs (machine auth, sentry, generic tokens).
        Regex("""\b[A-Za-z0-9+/_-]{48,}={0,2}""") to { _ -> "[REDACTED_BLOB]" },
        // Five-character Steam Guard codes when explicitly labelled.
        Regex("""(?i)(steam\s*guard\s*code\s*[:=]?\s*)[A-Z0-9]{5}\b""") to { m -> "${m.groupValues[1]}$MASK" },
        // Full SteamID64 values: keep a recognisable prefix/suffix only.
        Regex("""\b7656119\d{10}\b""") to { m -> m.value.take(7) + "…" + m.value.takeLast(4) },
    )

    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var out: String = input
        for ((regex, replace) in rules) {
            out = regex.replace(out, replace)
        }
        return out
    }

    /** Throwable description without message bodies from third-party code that might embed payloads. */
    fun describe(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(4).joinToString(" <- ") { e ->
            "${e.javaClass.simpleName}: ${redact(e.message).take(200)}"
        }
        return chain
    }
}
