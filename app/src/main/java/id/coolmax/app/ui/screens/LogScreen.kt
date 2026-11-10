package id.coolmax.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.coolmax.app.R
import id.coolmax.app.domain.model.EventLogEntry
import id.coolmax.app.ui.CoolMaxViewModel
import id.coolmax.app.ui.components.NeuButton
import id.coolmax.app.ui.components.NeuCard
import id.coolmax.app.ui.components.NeuOutlinedButton
import id.coolmax.app.ui.components.NeuVariant
import id.coolmax.app.ui.theme.LocalNeuPalette
import id.coolmax.app.util.FreqFormatters

/**
 * Dedicated event log screen: the history used to sit at the bottom of the Dashboard and pushed the
 * live CPU/thermal cards down. It now has its own sidebar entry, shows the newest entries first, and
 * keeps the export / clear actions together.
 */
@Composable
fun LogScreen(viewModel: CoolMaxViewModel) {
    val logs by viewModel.eventLogs.collectAsState()
    val context = LocalContext.current
    val palette = LocalNeuPalette.current
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.btn_clear_logs)) },
            text = { Text(stringResource(R.string.logs_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearLogs()
                    }
                ) { Text(stringResource(R.string.btn_clear_logs)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.event_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.logs_screen_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted
                    )
                    Text(
                        text = stringResource(R.string.logs_count_format, logs.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NeuOutlinedButton(
                            onClick = { showClearConfirm = true },
                            enabled = logs.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.btn_clear_logs))
                        }
                        NeuButton(
                            onClick = { shareLogs(context, logs) },
                            enabled = logs.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.btn_export_logs))
                        }
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.event_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted
                )
            }
        } else {
            items(logs.asReversed(), key = { it.id }) { log ->
                LogEntryCard(log = log)
            }
        }
    }
}

@Composable
private fun LogEntryCard(log: EventLogEntry) {
    val palette = LocalNeuPalette.current
    NeuCard(modifier = Modifier.fillMaxWidth(), variant = NeuVariant.RAISED) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (log.success) palette.success else palette.danger, CircleShape)
                )
                Text(
                    text = "${FreqFormatters.formatTimestamp(log.timestampEpochMs)} • ${log.trigger.displayLabelEn}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (log.success) palette.onBase else palette.danger
                )
            }
            Text(
                text = log.profileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted
            )
            if (log.commandsExecuted.isNotEmpty()) {
                // Long sysfs one-liners would otherwise dominate the list; keep each entry compact.
                Text(
                    text = log.commandsExecuted.joinToString(" ; "),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun shareLogs(context: android.content.Context, logs: List<EventLogEntry>) {
    val exportText = logs.joinToString("\n\n") { entry ->
        "[${FreqFormatters.formatTimestamp(entry.timestampEpochMs)}] " +
            "Trigger=${entry.trigger.displayLabelEn} | Profile=${entry.profileName} | " +
            "Success=${entry.success}\n${entry.message}\n" +
            entry.commandsExecuted.joinToString("\n  cmd: ", prefix = "  cmd: ")
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "CoolMax Event Logs")
        putExtra(Intent.EXTRA_TEXT, exportText)
    }
    runCatching { context.startActivity(Intent.createChooser(sendIntent, "Export Logs")) }
}
