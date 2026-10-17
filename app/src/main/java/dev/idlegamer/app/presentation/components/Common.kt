package dev.idlegamer.app.presentation.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.idlegamer.app.R
import dev.idlegamer.app.domain.model.ActivityError
import dev.idlegamer.app.domain.model.AuthError
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.presentation.theme.LocalStatusColors
import kotlinx.coroutines.delay

/** Monotonic "now" that ticks once per second only while the screen is at least STARTED (R14). */
@Composable
fun rememberElapsedTicker(periodMillis: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = android.os.SystemClock.elapsedRealtime()
                delay(periodMillis)
            }
        }
    }
    return now
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
}

fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun relativeTime(epochMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun dateTime(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(epochMillis))

@Composable
fun steamErrorText(error: SteamError): String = when (error) {
    SteamError.NetworkUnavailable -> stringResource(R.string.error_network_unavailable)
    SteamError.Timeout -> stringResource(R.string.error_timeout)
    SteamError.ConnectionReset -> stringResource(R.string.error_connection_reset)
    SteamError.DnsFailure -> stringResource(R.string.error_dns)
    is SteamError.ServerUnavailable -> stringResource(R.string.error_server_unavailable, error.resultCode)
    is SteamError.RateLimited -> stringResource(R.string.error_rate_limited, error.resultCode)
    is SteamError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials, error.resultCode)
    is SteamError.AccountRestricted -> stringResource(R.string.error_account_restricted, error.resultCode)
    is SteamError.SessionReplaced -> stringResource(R.string.error_session_replaced, error.resultCode)
    is SteamError.PlayingBlocked -> stringResource(R.string.error_playing_blocked, error.playingAppId)
    is SteamError.ProtocolIncompatible -> stringResource(R.string.error_protocol, error.detail)
    is SteamError.Unsupported -> stringResource(R.string.error_unsupported, error.operation)
    is SteamError.Unexpected -> stringResource(R.string.error_unexpected, error.resultCode)
    SteamError.IdentityMismatch -> stringResource(R.string.error_identity_mismatch)
}

@Composable
fun authErrorText(error: AuthError): String = when (error) {
    AuthError.QrExpired -> stringResource(R.string.error_qr_expired)
    AuthError.PollingLimitReached -> stringResource(R.string.error_polling_limit)
    AuthError.StorageFailure -> stringResource(R.string.error_storage)
    is AuthError.Steam -> steamErrorText(error.error)
}

@Composable
fun activityErrorText(error: ActivityError): String = when (error) {
    ActivityError.NotSignedIn -> stringResource(R.string.error_not_signed_in)
    ActivityError.RiskNotAcknowledged -> stringResource(R.string.error_risk_not_ack)
    ActivityError.NothingSelected -> stringResource(R.string.error_nothing_selected)
    is ActivityError.TooManyGames -> stringResource(R.string.error_too_many_games, error.max)
    is ActivityError.InvalidAppId -> stringResource(R.string.error_invalid_app_id, error.appId.toInt())
    is ActivityError.Steam -> steamErrorText(error.error)
    ActivityError.ForegroundServiceNotAllowed -> stringResource(R.string.error_fgs_not_allowed)
    ActivityError.StoppedBySystem -> stringResource(R.string.error_stopped_by_system)
}

@Composable
fun connectionText(state: ConnectionState, nowElapsed: Long): String = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.conn_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.conn_connecting)
    ConnectionState.Connected -> stringResource(R.string.conn_connected)
    ConnectionState.Authenticating -> stringResource(R.string.conn_authenticating)
    is ConnectionState.Authenticated -> stringResource(R.string.conn_authenticated)
    is ConnectionState.Reconnecting ->
        if (state.waitingForNetwork) {
            stringResource(R.string.conn_waiting_network)
        } else {
            stringResource(R.string.conn_reconnecting, state.attempt, formatDuration(state.nextAttemptAtElapsedMillis - nowElapsed))
        }
    ConnectionState.AuthExpired -> stringResource(R.string.conn_auth_expired)
    is ConnectionState.Failed -> stringResource(R.string.conn_failed)
    ConnectionState.Stopping -> stringResource(R.string.conn_stopping)
}

@Composable
fun StatusDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(10.dp)
                .background(color, CircleShape)
                .semantics { contentDescription = label },
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun connectionColor(state: ConnectionState): Color {
    val c = LocalStatusColors.current
    return when (state) {
        is ConnectionState.Authenticated -> c.ok
        ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.Authenticating, is ConnectionState.Reconnecting -> c.warning
        ConnectionState.AuthExpired, is ConnectionState.Failed -> c.error
        else -> c.neutral
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
