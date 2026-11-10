package id.coolmax.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.coolmax.app.R
import id.coolmax.app.data.packages.InstalledAppInfo
import id.coolmax.app.domain.model.CpuCluster
import id.coolmax.app.domain.model.PresetType
import id.coolmax.app.domain.model.ScreenOnRestoreMode
import id.coolmax.app.domain.safety.SafeFrequencyPlanner
import id.coolmax.app.ui.CoolMaxViewModel
import id.coolmax.app.ui.components.NeuCard
import id.coolmax.app.ui.components.NeuChip
import id.coolmax.app.ui.components.NeuOutlinedButton
import id.coolmax.app.ui.components.NeuSlider
import id.coolmax.app.ui.components.NeuSwitch
import id.coolmax.app.ui.components.NeuTextField
import kotlin.math.roundToInt

/**
 * Screen-Off Profile & Automatic Triggers Screen (CoolMax's primary differentiator):
 * - Global toggle "Enable profile when screen is off" (Default: OFF)
 * - Ready-made presets: Battery Saver ("Hemat Baterai"), Balanced ("Seimbang"), Custom ("Custom")
 * - Delay before applying (0–60 seconds slider, default 5s)
 * - Exclusions: Skip when charging, Skip when media/audio is playing
 * - Custom per-cluster Screen-Off editor including optional core offlining (`cpu0` is locked online and at least 1 core per cluster must stay online)
 * - Additional Triggers: Low Battery (<20%), Time Schedule (e.g. 23:00–06:00), Per-App via official UsageStats permission
 *   (the custom thermal limit now lives in its own Thermal menu/tab)
 * - Profile Management (Apply/Duplicate/Delete, JSON Export/Import)
 * - Official Doze / Battery Optimization Exemption button
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreenOffScreen(viewModel: CoolMaxViewModel) {
    val screenOffSettings by viewModel.screenOffSettings.collectAsState()
    val triggerSettings by viewModel.triggerSettings.collectAsState()
    val clusters by viewModel.clusters.collectAsState()
    val savedProfiles by viewModel.savedProfiles.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val context = LocalContext.current
    var importJsonInput by remember { mutableStateOf("") }
    var showFreezeWhitelist by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }

    if (showFreezeWhitelist) {
        FreezeWhitelistDialog(
            apps = installedApps,
            selected = screenOffSettings.freezeWhitelist,
            onToggle = { packageName, checked ->
                viewModel.updateScreenOffSettings { settings ->
                    settings.copy(
                        freezeWhitelist = if (checked) {
                            settings.freezeWhitelist + packageName
                        } else {
                            settings.freezeWhitelist - packageName
                        }
                    )
                }
            },
            onDismiss = { showFreezeWhitelist = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Global Screen-Off Toggle Card
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.screen_off_toggle_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.screen_off_toggle_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        NeuSwitch(
                            checked = screenOffSettings.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateScreenOffSettings { it.copy(enabled = enabled) }
                            }
                        )
                    }

                    // Presets: Hemat Baterai (BATTERY_SAVER), Seimbang (BALANCED), Custom (CUSTOM)
                    Text(
                        text = stringResource(R.string.screen_off_preset_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NeuChip(
                            selected = screenOffSettings.preset == PresetType.BATTERY_SAVER,
                            onClick = {
                                viewModel.updateScreenOffSettings { it.copy(preset = PresetType.BATTERY_SAVER) }
                            },
                            label = { Text(stringResource(R.string.preset_battery_saver)) }
                        )
                        NeuChip(
                            selected = screenOffSettings.preset == PresetType.BALANCED,
                            onClick = {
                                viewModel.updateScreenOffSettings { it.copy(preset = PresetType.BALANCED) }
                            },
                            label = { Text(stringResource(R.string.preset_balanced)) }
                        )
                        NeuChip(
                            selected = screenOffSettings.preset == PresetType.CUSTOM,
                            onClick = {
                                viewModel.updateScreenOffSettings { it.copy(preset = PresetType.CUSTOM) }
                            },
                            label = { Text(stringResource(R.string.preset_custom)) }
                        )
                    }

                    // Screen-On restore strategy: pre-screen-off snapshot vs fixed screen-on config
                    Text(
                        text = stringResource(R.string.screen_on_restore_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NeuChip(
                            selected = screenOffSettings.screenOnRestoreMode == ScreenOnRestoreMode.RESTORE_PREVIOUS_SNAPSHOT,
                            onClick = {
                                viewModel.updateScreenOffSettings {
                                    it.copy(screenOnRestoreMode = ScreenOnRestoreMode.RESTORE_PREVIOUS_SNAPSHOT)
                                }
                            },
                            label = { Text(stringResource(R.string.restore_mode_snapshot)) }
                        )
                        NeuChip(
                            selected = screenOffSettings.screenOnRestoreMode == ScreenOnRestoreMode.APPLY_SCREEN_ON_CONFIG,
                            onClick = {
                                viewModel.updateScreenOffSettings {
                                    it.copy(screenOnRestoreMode = ScreenOnRestoreMode.APPLY_SCREEN_ON_CONFIG)
                                }
                            },
                            label = { Text(stringResource(R.string.restore_mode_screen_on_config)) }
                        )
                    }

                    // Delay slider (0..60 seconds, default 5s)
                    Text(
                        text = stringResource(R.string.screen_off_delay_format, screenOffSettings.delaySeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    NeuSlider(
                        value = screenOffSettings.delaySeconds.toFloat(),
                        onValueChange = { v ->
                            viewModel.updateScreenOffSettings { it.copy(delaySeconds = v.roundToInt().coerceIn(0, 60)) }
                        },
                        valueRange = 0f..60f
                    )

                    // Exclusions: Charging & Media playback
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.screen_off_skip_charging),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        NeuSwitch(
                            checked = screenOffSettings.skipWhenCharging,
                            onCheckedChange = { skip ->
                                viewModel.updateScreenOffSettings { it.copy(skipWhenCharging = skip) }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.screen_off_skip_media),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        NeuSwitch(
                            checked = screenOffSettings.skipWhenMediaPlaying,
                            onCheckedChange = { skip ->
                                viewModel.updateScreenOffSettings { it.copy(skipWhenMediaPlaying = skip) }
                            }
                        )
                    }

                    // Screen-Off Network Guard: cellular radio on 2G only while the display is off
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.screen_off_network_2g_title),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.screen_off_network_2g_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        NeuSwitch(
                            checked = screenOffSettings.network2gOnScreenOff,
                            onCheckedChange = { enabled ->
                                viewModel.updateScreenOffSettings { it.copy(network2gOnScreenOff = enabled) }
                            }
                        )
                    }

                    // Screen-Off App Guard: freeze every other running app, keeping the whitelist alive
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.screen_off_freeze_title),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.screen_off_freeze_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        NeuSwitch(
                            checked = screenOffSettings.freezeAppsOnScreenOff,
                            onCheckedChange = { enabled ->
                                viewModel.updateScreenOffSettings { it.copy(freezeAppsOnScreenOff = enabled) }
                            }
                        )
                    }

                    if (screenOffSettings.freezeAppsOnScreenOff) {
                        Text(
                            text = stringResource(
                                R.string.screen_off_freeze_whitelist_count,
                                screenOffSettings.freezeWhitelist.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        NeuOutlinedButton(
                            onClick = { showFreezeWhitelist = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.screen_off_freeze_manage))
                        }
                    }
                }
            }
        }

        // 2. Per-Cluster Screen-Off Editor (including optional core offlining, cpu0 locked)
        if (screenOffSettings.preset == PresetType.CUSTOM) {
            items(clusters.size) { idx ->
                val cluster = clusters[idx]
                val clusterCfg = screenOffSettings.customClusterConfigs[cluster.policyId]
                    ?: SafeFrequencyPlanner.buildPresetConfigForCluster(cluster, PresetType.BALANCED)

                ScreenOffClusterEditorCard(
                    cluster = cluster,
                    config = clusterCfg,
                    onUpdateConfig = { newCfg ->
                        viewModel.updateScreenOffSettings { current ->
                            current.copy(
                                customClusterConfigs = current.customClusterConfigs + (cluster.policyId to newCfg)
                            )
                        }
                    }
                )
            }
        }

        // 3. Additional Automatic Triggers & Priority Hierarchy Card
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.triggers_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.triggers_priority_order_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Low Battery Trigger (<20% default)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.trigger_low_battery_label,
                                triggerSettings.batteryThresholdPercent
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        NeuSwitch(
                            checked = triggerSettings.batteryTriggerEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateTriggerSettings { it.copy(batteryTriggerEnabled = enabled) }
                            }
                        )
                    }

                    // Night Schedule Trigger (23:00 - 06:00)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.trigger_schedule_label,
                                triggerSettings.scheduleStartHour,
                                triggerSettings.scheduleEndHour
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        NeuSwitch(
                            checked = triggerSettings.scheduleTriggerEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateTriggerSettings { it.copy(scheduleTriggerEnabled = enabled) }
                            }
                        )
                    }

                    // Per-App Profile Trigger (UsageStatsManager)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.trigger_per_app_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        NeuSwitch(
                            checked = triggerSettings.perAppTriggerEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateTriggerSettings { it.copy(perAppTriggerEnabled = enabled) }
                            }
                        )
                    }
                    if (triggerSettings.perAppTriggerEnabled) {
                        NeuOutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.btn_grant_usage_stats))
                        }
                    }

                    // Respect Doze / Battery Optimization Exemption (official intent)
                    NeuOutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_ignore_battery_optimizations))
                    }
                }
            }
        }

        // 4. Saved Profiles & JSON Import/Export Card
        item {
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profiles_management_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (savedProfiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.profiles_empty_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        savedProfiles.forEach { profile ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                // FlowRow, not Row: four action buttons overflow a narrow screen and
                                // FlowRow wraps them instead of clipping the last one off-screen.
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NeuOutlinedButton(onClick = { viewModel.applySavedProfile(profile) }) {
                                        Text(stringResource(R.string.btn_profile_apply))
                                    }
                                    NeuOutlinedButton(onClick = { viewModel.duplicateProfile(profile) }) {
                                        Text(stringResource(R.string.btn_profile_duplicate))
                                    }
                                    NeuOutlinedButton(
                                        onClick = {
                                            val jsonStr = viewModel.exportProfileJson(profile)
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_TEXT, jsonStr)
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, profile.name))
                                        }
                                    ) {
                                        Text(stringResource(R.string.btn_profile_export))
                                    }
                                    NeuOutlinedButton(onClick = { viewModel.deleteProfile(profile.id) }) {
                                        Text(stringResource(R.string.btn_profile_delete))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    NeuTextField(
                        value = importJsonInput,
                        onValueChange = { importJsonInput = it },
                        label = { Text(stringResource(R.string.label_import_profile_json)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                    NeuOutlinedButton(
                        onClick = {
                            viewModel.importProfileJson(importJsonInput)
                            importJsonInput = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_import_profile_json))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenOffClusterEditorCard(
    cluster: CpuCluster,
    config: id.coolmax.app.domain.model.ClusterConfig,
    onUpdateConfig: (id.coolmax.app.domain.model.ClusterConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ClusterControlCard(
            cluster = cluster,
            config = config,
            onConfigChange = onUpdateConfig
        )
        NeuCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.screen_off_cores_title, cluster.displayName),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cluster.relatedCpus.forEach { cpuId ->
                        val isOffline = cpuId in config.offlineCores
                        val isCpu0 = cpuId == 0
                        NeuChip(
                            selected = !isOffline,
                            enabled = !isCpu0, // cpu0 can NEVER be offlined
                            onClick = {
                                if (isCpu0) return@NeuChip
                                val nextOffline = if (isOffline) {
                                    config.offlineCores - cpuId
                                } else {
                                    val candidate = config.offlineCores + cpuId
                                    // Keep at least 1 core online in the cluster
                                    if (cluster.relatedCpus.count { it !in candidate } >= 1) {
                                        candidate
                                    } else {
                                        config.offlineCores
                                    }
                                }
                                onUpdateConfig(config.copy(offlineCores = nextOffline))
                            },
                            label = {
                                Text(if (isCpu0) "cpu0 (Locked ON)" else if (isOffline) "cpu$cpuId (Offline)" else "cpu$cpuId (Online)")
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Multi-select picker for the screen-off freeze whitelist: checked packages are never frozen while the
 * display is off. Only launchable third-party apps are listed, so ROM components cannot be selected.
 */
@Composable
private fun FreezeWhitelistDialog(
    apps: List<InstalledAppInfo>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(apps, query) {
        apps.filter { app ->
            !app.isSystem && (
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
                )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_off_freeze_dialog_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                Text(
                    text = stringResource(R.string.screen_off_freeze_dialog_desc),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                NeuTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.perapp_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_off_freeze_empty),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onToggle(app.packageName, app.packageName !in selected)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = app.packageName in selected,
                                    onCheckedChange = { checked ->
                                        onToggle(app.packageName, checked)
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_dismiss)) }
        }
    )
}
