package dev.idlegamer.app.domain.model

/**
 * A game as IdleGamer knows it. Fields that Steam did not confirm are nullable instead of guessed.
 */
data class Game(
    val appId: Int,
    val name: String,
    val iconUrl: String?,
    val headerImageUrl: String?,
    /** OWNED when it came from `Player.GetOwnedGames`; UNKNOWN for manually entered App IDs. */
    val ownership: Ownership,
    /** Minutes, as last reported by Steam. Null for manual entries. */
    val playtimeForeverMinutes: Int?,
    val playtimeTwoWeeksMinutes: Int?,
    val lastPlayedEpochSeconds: Long?,
    val lastUpdatedEpochMillis: Long,
    val isSelected: Boolean,
) {
    enum class Ownership { OWNED, UNKNOWN }

    companion object {
        /** GameID packs the app id into 24 bits; anything larger cannot be sent as a plain app id. */
        const val MAX_APP_ID: Long = 0xFFFFFFL

        /** Limit enforced by the Steam network for concurrent `games_played` entries (see STEAM_PROTOCOL.md). */
        const val MAX_CONCURRENT_GAMES = 32

        fun parseAppId(input: String): Int? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.length > 8 || !trimmed.all(Char::isDigit)) return null
            val value = trimmed.toLongOrNull() ?: return null
            return if (value in 1..MAX_APP_ID) value.toInt() else null
        }

        fun iconUrl(appId: Int, iconHash: String?): String? =
            iconHash?.takeIf { it.matches(Regex("^[0-9a-f]{40}$")) }
                ?.let { "https://media.steampowered.com/steamcommunity/public/images/apps/$appId/$it.jpg" }

        fun headerUrl(appId: Int): String = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/header.jpg"
    }
}

enum class LibrarySort { NAME, PLAYTIME, RECENTLY_PLAYED }

enum class LibraryFilter { ALL, SELECTED, PLAYED, NEVER_PLAYED, MANUAL }

/** A finished or interrupted local activity session (history screen). */
data class ActivitySessionRecord(
    val id: Long,
    val appIds: List<Int>,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    /** Time presence was live on an authenticated connection, measured with the monotonic clock. */
    val activeMillis: Long,
    val endReason: SessionEndReason?,
)

data class EventLogEntry(
    val id: Long,
    val timestampEpochMillis: Long,
    val type: String,
    val message: String,
)
