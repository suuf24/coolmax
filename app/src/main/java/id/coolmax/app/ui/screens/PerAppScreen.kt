package id.coolmax.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import id.coolmax.app.R
import id.coolmax.app.data.packages.InstalledAppInfo
import id.coolmax.app.ui.CoolMaxViewModel
import id.coolmax.app.ui.components.GovernorDropdown
import id.coolmax.app.ui.components.NeuButton
import id.coolmax.app.ui.components.NeuCard
import id.coolmax.app.ui.components.NeuChip
import id.coolmax.app.ui.components.NeuOutlinedButton
import id.coolmax.app.ui.components.NeuSwitch
import id.coolmax.app.ui.components.NeuTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-app governor settings:
 * - Master switch (UsageStats-based foreground detection; no accessibility service needed)
 * - App picker with search + "show system apps" filter, loaded off the main thread
 * - Configured list with icon, label, governor, edit and restore actions
 * - "Restore all" with an explicit confirmation dialog
 *
 * Priority is documented in the UI: Screen-Off profile > per-app governor > global governor, and the
 * thermal limit caps `scaling_max_freq` above all of them.
 */
@Composable
fun PerAppScreen(viewModel: CoolMaxViewModel) {
    val triggerSettings by viewModel.triggerSettings.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    val governorOptions by viewModel.governorOptions.collectAsState()
    val hasUsageStats by viewModel.hasUsageStatsPermission.collectAsState()
    val context = LocalContext.current

    var showPicker by rememberSaveable { mutableStateOf(false) }
    var editingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showRestoreAllConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
        viewModel.refreshUsageStatsPermission()
    }

    val configured = triggerSettings.perAppGovernors

    if (showPicker) {
        AppPickerDialog(
            apps = installedApps,
            isLoading = isLoadingApps,
            onDismiss = { showPicker = false },
            onAppSelected = { packageName ->
                showPicker = false
                editingPackage = packageName
            }
        )
    }

    editingPackage?.let { packageName ->
        GovernorEditorDialog(
            appLabel = viewModel.labelForPackage(packageName),
            packageName = packageName,
            governorOptions = governorOptions,
            initialGovernor = configured[packageName] ?: governorOptions.firstOrNull().orEmpty(),
            onDismiss = { editingPackage = null },
            onSave = { governor ->
                viewModel.setPerAppGovernor(packageName, governor)
                editingPackage = null
            }
        )
    }

    if (showRestoreAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirm = false },
            title = { Text(stringResource(R.string.perapp_restore_all_confirm_title)) },
            text = { Text(stringResource(R.string.perapp_restore_all_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreAllConfirm = false
                        viewModel.restoreAllPerAppGovernors()
                    }
                ) { Text(stringResource(R.string.perapp_restore_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreAllConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
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
                                text = stringResource(R.string.perapp_master_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.perapp_master_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        NeuSwitch(
                            checked = triggerSettings.perAppTriggerEnabled,
                            onCheckedChange = viewModel::setPerAppTriggerEnabled
                        )
                    }

                    Text(
                        text = stringResource(R.string.perapp_priority_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (!hasUsageStats) {
                        Text(
                            text = stringResource(R.string.perapp_usage_permission_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        NeuOutlinedButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                                viewModel.refreshUsageStatsPermission()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_grant_usage_stats))
                        }
                    }

                    NeuButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.perapp_add_app))
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.perapp_selected_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (configured.isNotEmpty()) {
                    TextButton(onClick = { showRestoreAllConfirm = true }) {
                        Text(stringResource(R.string.perapp_restore_all))
                    }
                }
            }
        }

        if (configured.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.perapp_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(configured.keys.sorted()) { packageName ->
                val governor = configured[packageName].orEmpty()
                NeuCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppIcon(packageName = packageName)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = viewModel.labelForPackage(packageName),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "$packageName · $governor",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (governorOptions.isNotEmpty() && governor !in governorOptions) {
                                Text(
                                    text = stringResource(R.string.perapp_governor_unavailable_warning, governor),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        NeuOutlinedButton(onClick = { editingPackage = packageName }) {
                            Text(stringResource(R.string.perapp_edit))
                        }
                        NeuOutlinedButton(onClick = { viewModel.removePerAppGovernor(packageName) }) {
                            Text(stringResource(R.string.perapp_restore))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledAppInfo>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(apps, query, showSystemApps) {
        apps.asSequence()
            .filter { showSystemApps || !it.isSystem }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.perapp_picker_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp)) {
                NeuTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.perapp_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                NeuChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text(stringResource(R.string.perapp_show_system)) }
                )
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    isLoading -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.perapp_loading))
                    }
                    filtered.isEmpty() -> Text(stringResource(R.string.perapp_no_result))
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAppSelected(app.packageName) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AppIcon(packageName = app.packageName)
                                Column {
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@Composable
private fun GovernorEditorDialog(
    appLabel: String,
    packageName: String,
    governorOptions: List<String>,
    initialGovernor: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var selectedGovernor by rememberSaveable(packageName) { mutableStateOf(initialGovernor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appLabel) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.perapp_governor_for_app, packageName),
                    style = MaterialTheme.typography.bodySmall
                )
                GovernorDropdown(
                    selected = selectedGovernor,
                    options = governorOptions,
                    label = stringResource(R.string.perapp_governor_label),
                    onSelected = { selectedGovernor = it }
                )
                if (selectedGovernor.isBlank() || selectedGovernor !in governorOptions) {
                    Text(
                        text = stringResource(R.string.perapp_governor_unavailable_warning, selectedGovernor),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selectedGovernor) },
                enabled = selectedGovernor.isNotBlank() && selectedGovernor in governorOptions
            ) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/** App icon loaded off the main thread, with a neutral fallback when the package is gone. */
@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(36.dp))
    } else {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = null,
            modifier = modifier.size(36.dp)
        )
    }
}
