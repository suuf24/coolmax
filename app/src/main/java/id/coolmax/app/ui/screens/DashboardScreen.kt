package id.coolmax.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.coolmax.app.R
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.ui.CoolMaxViewModel
import id.coolmax.app.ui.RealtimeSample
import id.coolmax.app.ui.components.NeuButton
import id.coolmax.app.ui.components.NeuCard
import id.coolmax.app.ui.components.NeuSwitch
import id.coolmax.app.ui.components.NeuVariant
import id.coolmax.app.ui.theme.LocalNeuPalette
import id.coolmax.app.util.FreqFormatters
import java.util.Locale

/**
 * Dashboard screen displaying:
 * - Emergency Safe Mode alert banner (if triggered by bootloop guard)
 * - Live CPU temperature card (from `/sys/class/thermal`)
 * - Per-cluster live cards (current frequency, min/max, active governor, online cores)
 * - Real-time frequency & temperature trend chart (updated every 1s only while visible)
 * - Time-in-State horizontal bar distribution per cluster
 * - Read-only Device Info (SoC, clusters/cores, kernel, root provider, SELinux)
 * - Developer Dry-Run toggle & Event History with manual log export
 */
@Composable
fun DashboardScreen(viewModel: CoolMaxViewModel) {
    val clusters by viewModel.clusters.collectAsState()
    val thermals by viewModel.thermalReadings.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val realtimeHistory by viewModel.realtimeHistory.collectAsState()
    val safeModeActive by viewModel.safeModeActive.collectAsState()
    val safeModeReason by viewModel.safeModeReason.collectAsState()
    val dryRunMode by viewModel.dryRunMode.collectAsState()
    val palette = LocalNeuPalette.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Safe Mode Alert Banner (if active)
        if (safeModeActive) {
            item {
                NeuCard(
                    // 0.12 is the strongest tint that still keeps the danger text at AA on this card.
                    backgroundColor = palette.danger.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.safe_mode_banner_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.danger
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = safeModeReason ?: stringResource(R.string.safe_mode_banner_default_reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onBase
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        NeuButton(onClick = { viewModel.dismissSafeMode() }) {
                            Text(text = stringResource(R.string.btn_acknowledge_safe_mode))
                        }
                    }
                }
            }
        }

        // 2. CPU Thermal Summary Card
        item {
            val maxTemp = thermals.maxOfOrNull { it.tempCelsius }
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.dashboard_thermal_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (thermals.isNotEmpty()) {
                                thermals.take(3).joinToString(" • ") {
                                    "${it.type}: ${String.format(Locale.US, "%.1f°C", it.tempCelsius)}"
                                }
                            } else {
                                stringResource(R.string.dashboard_thermal_unavailable)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = maxTemp?.let { String.format(Locale.US, "%.1f °C", it) } ?: "-- °C",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if ((maxTemp ?: 0f) > 45f) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }

        // 3. Per-Cluster Live Cards (or the empty state when no cluster could be read)
        if (clusters.isEmpty()) {
            item {
                NeuCard(modifier = Modifier.fillMaxWidth(), variant = NeuVariant.INSET) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_empty_clusters_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.dashboard_empty_clusters_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted
                        )
                    }
                }
            }
        } else {
            items(clusters, key = { it.policyId }) { cluster ->
                ClusterDashboardCard(cluster = cluster)
            }
        }

        // 4. Real-time Frequency & Temperature Line Chart
        if (realtimeHistory.size >= 2) {
            item {
                RealtimeTelemetryCard(samples = realtimeHistory)
            }
        }

        // 5. Time-in-State Statistics per Cluster
        items(clusters.filter { it.timeInState.isNotEmpty() }, key = { "tis_${it.policyId}" }) { cluster ->
            TimeInStateCard(cluster = cluster)
        }

        // 6. Device & Kernel Information + Dry-Run Developer Option
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.device_info_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    deviceInfo?.let { info ->
                        InfoRow(stringResource(R.string.device_info_soc), "${info.socModel} (${info.boardPlatform})")
                        InfoRow(stringResource(R.string.device_info_clusters), "${info.clusterCount} clusters / ${info.totalCores} cores")
                        InfoRow(stringResource(R.string.device_info_governors), info.availableGovernors.joinToString(", "))
                        InfoRow(stringResource(R.string.device_info_kernel), info.kernelVersion)
                        InfoRow(stringResource(R.string.device_info_root), "${info.rootProvider} (Root=${info.isRooted})")
                        InfoRow(stringResource(R.string.device_info_selinux), info.selinuxStatus)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.pref_dry_run_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.pref_dry_run_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        NeuSwitch(
                            checked = dryRunMode,
                            onCheckedChange = { viewModel.setDryRunMode(it) }
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun ClusterDashboardCard(cluster: CpuCluster) {
    val palette = LocalNeuPalette.current
    val progress = if (cluster.cpuinfoMaxFreqKhz > 0L) {
        (cluster.currentFreqKhz.toFloat() / cluster.cpuinfoMaxFreqKhz.toFloat()).coerceIn(0f, 1f)
    } else 0f

    NeuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cluster.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = FreqFormatters.formatKhzToMhz(cluster.currentFreqKhz),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                color = palette.accent,
                trackColor = palette.shadow.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Min: ${FreqFormatters.formatKhzToMhz(cluster.minFreqKhz)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Gov: ${cluster.activeGovernor}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Max: ${FreqFormatters.formatKhzToMhz(cluster.maxFreqKhz)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = stringResource(
                    R.string.cluster_cores_online_format,
                    cluster.onlineCpus.joinToString(", ") { "cpu$it" },
                    cluster.relatedCpus.joinToString(", ") { "cpu$it" }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RealtimeTelemetryCard(samples: List<RealtimeSample>) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    NeuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.realtime_chart_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                if (samples.size < 2) return@Canvas
                val maxFreq = samples.flatMap { it.clusterFreqsKhz.values }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

                for (i in 0 until samples.size - 1) {
                    val f1 = samples[i].clusterFreqsKhz.values.maxOrNull() ?: 0L
                    val f2 = samples[i + 1].clusterFreqsKhz.values.maxOrNull() ?: 0L
                    val y1 = size.height - (f1.toFloat() / maxFreq.toFloat()) * size.height
                    val y2 = size.height - (f2.toFloat() / maxFreq.toFloat()) * size.height
                    drawLine(
                        color = primaryColor,
                        start = Offset(i * stepX, y1),
                        end = Offset((i + 1) * stepX, y2),
                        strokeWidth = 4f
                    )

                    val t1 = (samples[i].maxTempCelsius / 100f).coerceIn(0f, 1f)
                    val t2 = (samples[i + 1].maxTempCelsius / 100f).coerceIn(0f, 1f)
                    drawLine(
                        color = errorColor,
                        start = Offset(i * stepX, size.height * (1f - t1)),
                        end = Offset((i + 1) * stepX, size.height * (1f - t2)),
                        strokeWidth = 2.5f
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeInStateCard(cluster: CpuCluster) {
    val palette = LocalNeuPalette.current
    val totalTime = cluster.timeInState.sumOf { it.timeMs }.coerceAtLeast(1L)
    NeuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${stringResource(R.string.time_in_state_title)}: ${cluster.displayName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            cluster.timeInState.take(8).forEach { entry ->
                val ratio = (entry.timeMs.toFloat() / totalTime.toFloat()).coerceIn(0f, 1f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = FreqFormatters.formatKhzToMhz(entry.frequencyKhz),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(0.24f)
                    )
                    LinearProgressIndicator(
                        progress = { ratio },
                        color = palette.accent,
                        trackColor = palette.shadow.copy(alpha = 0.35f),
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                    )
                    Text(
                        text = "${(ratio * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
