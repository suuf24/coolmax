package id.coolmax.app.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.coolmax.app.R
import id.coolmax.app.domain.model.ClusterConfig
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import id.coolmax.app.ui.CoolMaxViewModel
import id.coolmax.app.ui.components.GovernorDropdown
import id.coolmax.app.ui.components.NeuButton
import id.coolmax.app.ui.components.NeuCard
import id.coolmax.app.ui.components.NeuOutlinedButton
import id.coolmax.app.ui.components.NeuSlider
import id.coolmax.app.ui.components.NeuTextField
import id.coolmax.app.util.FreqFormatters
import kotlin.math.roundToInt

/**
 * Screen-On CPU Frequency & Governor Control Screen:
 * - Per-cluster min/max frequency selector (dropdown + step slider restricted strictly to `availableFrequenciesKhz`)
 * - Enforces `min <= max`, blocks `< 30%` max frequency, shows warning if `< 50%`
 * - Governor selector chips
 * - Apply button (triggers 15s auto-revert confirmation dialog)
 * - Undo button (restores previous snapshot)
 * - Save as Custom Profile option
 */
@Composable
fun CpuControlScreen(viewModel: CoolMaxViewModel) {
    val clusters by viewModel.clusters.collectAsState()
    val draftConfigs by viewModel.draftConfigs.collectAsState()
    val previousSnapshot by viewModel.previousSnapshot.collectAsState()
    val dryRunMode by viewModel.dryRunMode.collectAsState()
    var newProfileName by remember { mutableStateOf("") }
    var showDryRunConfirm by remember { mutableStateOf(false) }

    if (showDryRunConfirm) {
        AlertDialog(
            onDismissRequest = { showDryRunConfirm = false },
            title = { Text(text = stringResource(R.string.dry_run_confirm_title)) },
            text = { Text(text = stringResource(R.string.dry_run_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDryRunConfirm = false
                        viewModel.applyDraftConfigsWith15sConfirmation()
                    }
                ) { Text(text = stringResource(R.string.dry_run_confirm_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showDryRunConfirm = false }) {
                    Text(text = stringResource(R.string.dry_run_confirm_cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(clusters, key = { it.policyId }) { cluster ->
            val config = draftConfigs[cluster.policyId] ?: ClusterConfig(
                policyId = cluster.policyId,
                minFreqKhz = cluster.minFreqKhz,
                maxFreqKhz = cluster.maxFreqKhz,
                governor = cluster.activeGovernor
            )
            ClusterControlCard(
                cluster = cluster,
                config = config,
                onConfigChange = { updated ->
                    viewModel.updateDraftClusterConfig(cluster.policyId) { updated }
                },
                onGovernorSelected = { governor ->
                    viewModel.selectGovernor(cluster.policyId, governor)
                }
            )
        }

        // Action Bar: Apply (with 15s confirmation), Undo, Reset
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NeuButton(
                        onClick = {
                            // Never let a Dry-Run apply look like a real apply (perubahan.md P0).
                            if (dryRunMode) showDryRunConfirm = true
                            else viewModel.applyDraftConfigsWith15sConfirmation()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_apply_manual_15s))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NeuOutlinedButton(
                            onClick = { viewModel.undoLastChange() },
                            enabled = previousSnapshot != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(R.string.btn_undo_last_apply))
                        }
                        NeuOutlinedButton(
                            onClick = { viewModel.resetToSystemDefault() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(R.string.btn_reset_system_default))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    NeuTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text(stringResource(R.string.label_save_as_profile_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    NeuOutlinedButton(
                        onClick = {
                            viewModel.createProfileFromDraft(newProfileName)
                            newProfileName = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_save_current_as_profile))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterControlCard(
    cluster: CpuCluster,
    config: ClusterConfig,
    onConfigChange: (ClusterConfig) -> Unit,
    /** When provided, selecting a governor writes it to sysfs immediately (CPU Control screen). */
    onGovernorSelected: ((String) -> Unit)? = null
) {
    val freqs = cluster.availableFrequenciesKhz
    val hardwareMax = freqs.maxOrNull() ?: cluster.cpuinfoMaxFreqKhz
    // Filter out frequencies below 30% of hardware max for maxFreq selection (safety floor)
    val allowedMaxFreqs = freqs.filter { f ->
        hardwareMax <= 0L || (f.toDouble() / hardwareMax.toDouble()) >= SafeFrequencyPlanner.MIN_ALLOWED_MAX_FREQ_RATIO
    }.ifEmpty { freqs }

    val ratio = if (hardwareMax > 0L) config.maxFreqKhz.toDouble() / hardwareMax.toDouble() else 1.0
    val isBelow50Warning = ratio < SafeFrequencyPlanner.WARNING_MAX_FREQ_RATIO

    var minExpanded by remember { mutableStateOf(false) }
    var maxExpanded by remember { mutableStateOf(false) }

    NeuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = cluster.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Min Frequency Dropdown + Step Slider
            ExposedDropdownMenuBox(
                expanded = minExpanded,
                onExpandedChange = { minExpanded = !minExpanded }
            ) {
                NeuTextField(
                    value = FreqFormatters.formatKhzToMhz(config.minFreqKhz),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_scaling_min_freq)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = minExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = minExpanded,
                    onDismissRequest = { minExpanded = false }
                ) {
                    freqs.filter { it <= config.maxFreqKhz }.forEach { freq ->
                        DropdownMenuItem(
                            text = { Text(FreqFormatters.formatKhzToMhz(freq)) },
                            onClick = {
                                onConfigChange(config.copy(minFreqKhz = freq))
                                minExpanded = false
                            }
                        )
                    }
                }
            }

            // Max Frequency Dropdown + Step Slider (restricted to >= 30% of cluster max)
            ExposedDropdownMenuBox(
                expanded = maxExpanded,
                onExpandedChange = { maxExpanded = !maxExpanded }
            ) {
                NeuTextField(
                    value = FreqFormatters.formatKhzToMhz(config.maxFreqKhz),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_scaling_max_freq)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = maxExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = maxExpanded,
                    onDismissRequest = { maxExpanded = false }
                ) {
                    allowedMaxFreqs.forEach { freq ->
                        DropdownMenuItem(
                            text = { Text(FreqFormatters.formatKhzToMhz(freq)) },
                            onClick = {
                                val adjustedMin = config.minFreqKhz.coerceAtMost(freq)
                                onConfigChange(config.copy(maxFreqKhz = freq, minFreqKhz = adjustedMin))
                                maxExpanded = false
                            }
                        )
                    }
                }
            }

            if (allowedMaxFreqs.size > 1) {
                val maxIdx = allowedMaxFreqs.indexOf(config.maxFreqKhz).coerceAtLeast(0)
                NeuSlider(
                    value = maxIdx.toFloat(),
                    onValueChange = { rawIdx ->
                        val chosen = allowedMaxFreqs[rawIdx.roundToInt().coerceIn(0, allowedMaxFreqs.lastIndex)]
                        val adjustedMin = config.minFreqKhz.coerceAtMost(chosen)
                        onConfigChange(config.copy(maxFreqKhz = chosen, minFreqKhz = adjustedMin))
                    },
                    valueRange = 0f..allowedMaxFreqs.lastIndex.toFloat(),
                    steps = (allowedMaxFreqs.size - 2).coerceAtLeast(0)
                )
            }

            if (isBelow50Warning) {
                Text(
                    text = stringResource(R.string.warning_max_below_50_percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Governor Selection (dropdown, options read live from scaling_available_governors)
            Text(
                text = stringResource(R.string.label_scaling_governor),
                style = MaterialTheme.typography.labelLarge
            )
            GovernorDropdown(
                selected = config.governor,
                options = cluster.availableGovernors,
                onSelected = { governor ->
                    if (onGovernorSelected != null) {
                        onGovernorSelected(governor)
                    } else {
                        onConfigChange(config.copy(governor = governor))
                    }
                }
            )
            Text(
                text = stringResource(R.string.governor_active_format, cluster.activeGovernor.ifBlank { "?" }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
