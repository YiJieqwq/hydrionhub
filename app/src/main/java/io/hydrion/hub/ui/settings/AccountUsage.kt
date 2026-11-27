package io.hydrion.hub.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubShapes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A snapshot supplied by an authenticated account. Provider API keys and per-message tokens are unrelated. */
internal data class SignedInAccountUsage(val accountLabel: String, val plan: String?, val windows: List<QuotaWindow>)

internal data class QuotaWindow(
    val durationMinutes: Long?, val reportedLabel: String?,
    val usedPercent: Double?, val resetsAt: Instant?
) {
    val validPercent get() = usedPercent?.takeIf { it in 0.0..100.0 }
}

@Composable
internal fun AccountUsage(accounts: List<SignedInAccountUsage>) {
    if (accounts.isEmpty()) {
        Text(stringResource(R.string.settings_usage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val dateTime = remember(locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    }
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (account in accounts) {
            Column(Modifier.fillMaxWidth().clip(HubShapes.Large)
                .background(MaterialTheme.colorScheme.surface).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.accountLabel, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    account.plan?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp) }
                }
                if (account.windows.isEmpty()) Text(stringResource(R.string.settings_usage_empty), fontSize = 13.sp)
                for (window in account.windows) {
                    val label = when (window.durationMinutes) {
                        5L * 60 -> stringResource(R.string.settings_usage_five)
                        7L * 24 * 60 -> stringResource(R.string.settings_usage_week)
                        else -> window.reportedLabel?.takeIf { it.isNotBlank() } ?: window.durationMinutes?.takeIf { it > 0L }
                            ?.let { stringResource(R.string.settings_usage_minutes, it) } ?: stringResource(R.string.settings_usage)
                    }
                    val used = window.validPercent
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, Modifier.weight(1f), fontSize = 13.sp)
                        Text(used?.let { percentFormat.format(it / 100.0) } ?: stringResource(R.string.settings_usage_empty),
                            fontSize = 13.sp, textAlign = TextAlign.End)
                    }
                    if (used != null) LinearProgressIndicator(progress = { (used / 100.0).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp), trackColor = MaterialTheme.colorScheme.outlineVariant)
                    window.resetsAt?.let {
                        Text(stringResource(R.string.settings_usage_reset, dateTime.format(it)), Modifier.fillMaxWidth(),
                            fontSize = 12.sp, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
