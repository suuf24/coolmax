package id.coolmax.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import id.coolmax.app.ui.CoolMaxViewModel
import id.coolmax.app.ui.components.NeuButton
import id.coolmax.app.ui.components.NeuIconButton
import id.coolmax.app.ui.components.NeuSurface
import id.coolmax.app.ui.components.NeuVariant
import id.coolmax.app.ui.screens.CpuControlScreen
import id.coolmax.app.ui.screens.DashboardScreen
import id.coolmax.app.ui.screens.LogScreen
import id.coolmax.app.ui.screens.PerAppScreen
import id.coolmax.app.ui.screens.RootRequiredScreen
import id.coolmax.app.ui.screens.ScreenOffScreen
import id.coolmax.app.ui.screens.ThermalScreen
import id.coolmax.app.ui.theme.CoolMaxTheme
import id.coolmax.app.ui.theme.LocalNeuPalette

enum class MainTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.nav_dashboard, Icons.Default.Dashboard),
    CPU_CONTROL(R.string.nav_cpu_control, Icons.Default.Tune),
    THERMAL(R.string.nav_thermal, Icons.Default.Thermostat),
    SCREEN_OFF(R.string.nav_screen_off, Icons.Default.BatterySaver),
    PER_APP(R.string.nav_per_app, Icons.Default.Apps),
    LOGS(R.string.nav_logs, Icons.Default.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoolMaxTheme {
                val vm: CoolMaxViewModel = viewModel(
                    factory = CoolMaxViewModel.Factory(application)
                )
                CoolMaxMainApp(viewModel = vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoolMaxMainApp(viewModel: CoolMaxViewModel) {
    val isCheckingRoot by viewModel.isCheckingRoot.collectAsState()
    val isRootGranted by viewModel.isRootGranted.collectAsState()
    val manualConfirmation by viewModel.manualConfirmation.collectAsState()
    val bannerMessage by viewModel.bannerMessage.collectAsState()
    val dryRunMode by viewModel.dryRunMode.collectAsState()
    val palette = LocalNeuPalette.current

    var currentTab by rememberSaveable { mutableStateOf(MainTab.DASHBOARD) }
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }

    // Android 13+ suppresses the guard service notification until POST_NOTIFICATIONS is granted.
    // Without this prompt the service runs invisibly, which is exactly the state a user must be
    // able to see and control. The request is made once and never blocks the UI.
    val context = LocalContext.current
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Denial is respected: the service keeps running, only its notification is hidden. */ }
    LaunchedEffect(Unit) {
        if (!notificationAsked &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Start 1-second live polling ONLY when the Activity is in STARTED/RESUMED state (visible to user)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isRootGranted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startLiveMonitoring()
                Lifecycle.Event.ON_STOP -> viewModel.stopLiveMonitoring()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopLiveMonitoring()
        }
    }

    if (isCheckingRoot) {
        LoadingScreen()
        return
    }

    if (!isRootGranted) {
        RootRequiredScreen(
            onRetryRootCheck = { viewModel.checkRootAndLoadInitialData() },
            onContinueInDryRun = { viewModel.enableDryRunAndContinue() }
        )
        return
    }

    BackHandler(enabled = sidebarOpen) { sidebarOpen = false }

    // 15-second auto-revert confirmation dialog after manual Apply
    manualConfirmation?.let { confirmState ->
        AlertDialog(
            onDismissRequest = { /* Require explicit choice or wait for 15s auto-revert */ },
            containerColor = palette.base,
            title = { Text(text = stringResource(R.string.confirm_keep_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.confirm_keep_body,
                        confirmState.appliedProfileName,
                        confirmState.remainingSeconds
                    )
                )
            },
            confirmButton = {
                NeuButton(onClick = { viewModel.confirmKeepManualChanges() }) {
                    Text(
                        text = stringResource(
                            R.string.btn_keep_changes,
                            confirmState.remainingSeconds
                        )
                    )
                }
            },
            dismissButton = {
                NeuButton(
                    onClick = { viewModel.undoLastChange() },
                    style = id.coolmax.app.ui.components.NeuButtonStyle.SOFT
                ) {
                    Text(text = stringResource(R.string.btn_revert_now))
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.base)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = palette.base,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.base),
                    navigationIcon = {
                        NeuIconButton(
                            icon = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.nav_menu_open),
                            onClick = { sidebarOpen = true },
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                        )
                    },
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(currentTab.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted
                            )
                            if (dryRunMode) {
                                Text(
                                    text = stringResource(R.string.badge_dry_run_active),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.warning
                                )
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Optional status/warning/error banner
                bannerMessage?.let { banner ->
                    Banner(
                        text = banner.text,
                        isError = banner.isError,
                        onDismiss = { viewModel.clearBannerMessage() }
                    )
                }

                when (currentTab) {
                    MainTab.DASHBOARD -> DashboardScreen(viewModel = viewModel)
                    MainTab.CPU_CONTROL -> CpuControlScreen(viewModel = viewModel)
                    MainTab.THERMAL -> ThermalScreen(viewModel = viewModel)
                    MainTab.SCREEN_OFF -> ScreenOffScreen(viewModel = viewModel)
                    MainTab.PER_APP -> PerAppScreen(viewModel = viewModel)
                    MainTab.LOGS -> LogScreen(viewModel = viewModel)
                }
            }
        }

        // Scrim behind the sidebar; also closes it on outside tap.
        AnimatedVisibility(
            visible = sidebarOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { sidebarOpen = false }
                    )
            )
        }

        AnimatedVisibility(
            visible = sidebarOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
        ) {
            CoolMaxSidebar(
                currentTab = currentTab,
                dryRunMode = dryRunMode,
                onSelect = { tab ->
                    currentTab = tab
                    sidebarOpen = false
                },
                onClose = { sidebarOpen = false },
                onReset = {
                    sidebarOpen = false
                    viewModel.resetToSystemDefault()
                }
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    val palette = LocalNeuPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.base),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = palette.accent)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.app_loading),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted
            )
        }
    }
}

@Composable
private fun Banner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val palette = LocalNeuPalette.current
    NeuSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        variant = NeuVariant.INSET,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) palette.danger else palette.onBase,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.btn_dismiss), color = palette.accent)
            }
        }
    }
}

@Composable
private fun CoolMaxSidebar(
    currentTab: MainTab,
    dryRunMode: Boolean,
    onSelect: (MainTab) -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit
) {
    val palette = LocalNeuPalette.current
    NeuSurface(
        modifier = Modifier
            .fillMaxHeight()
            .width(298.dp),
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.onBase
                    )
                    Text(
                        text = stringResource(R.string.sidebar_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted
                    )
                }
                NeuIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.nav_menu_close),
                    onClick = onClose
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            MainTab.entries.forEach { tab ->
                SidebarItem(
                    tab = tab,
                    selected = tab == currentTab,
                    onClick = { onSelect(tab) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // The safety exit lives here instead of the top bar, so every screen keeps a full-width
            // title area while the reset stays one tap away.
            NeuButton(
                onClick = onReset,
                style = id.coolmax.app.ui.components.NeuButtonStyle.SOFT,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp)
                )
                Text(
                    text = stringResource(R.string.btn_reset_system_default),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (dryRunMode) {
                Text(
                    text = stringResource(R.string.badge_dry_run_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.warning,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = stringResource(R.string.badge_root_active),
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        }
    }
}

@Composable
private fun SidebarItem(tab: MainTab, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalNeuPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    NeuSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(16.dp),
        variant = if (selected) NeuVariant.INSET else NeuVariant.FLAT,
        focused = focused
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                tint = if (selected) palette.accent else palette.muted
            )
            Text(
                text = stringResource(tab.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) palette.accent else palette.onBase
            )
        }
    }
}
