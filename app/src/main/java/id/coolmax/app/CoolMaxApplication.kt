package id.coolmax.app

import android.app.Application
import id.coolmax.app.data.freeze.AppFreezer
import id.coolmax.app.data.network.NetworkModeController
import id.coolmax.app.data.preferences.CoolMaxPreferences
import id.coolmax.app.data.repository.CpuRepository
import id.coolmax.app.data.root.RootManager
import id.coolmax.app.data.root.SocVendor
import id.coolmax.app.data.sysfs.LibsuMtkOppFallbackWriter
import id.coolmax.app.data.sysfs.LibsuQualcommFallbackWriter
import id.coolmax.app.data.sysfs.LibsuSysfsShellExecutor
import id.coolmax.app.data.sysfs.SysfsReader
import id.coolmax.app.data.sysfs.SysfsWriter
import id.coolmax.app.domain.model.TriggerType
import id.coolmax.app.service.ScreenOffGuardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application entry point initializing `libsu` shell configuration, DataStore preferences,
 * and checking bootloop guard (`pending_apply` leftover detection) on startup.
 */
class CoolMaxApplication : Application() {

    lateinit var rootManager: RootManager
        private set
    lateinit var sysfsReader: SysfsReader
        private set
    lateinit var sysfsWriter: SysfsWriter
        private set
    lateinit var preferences: CoolMaxPreferences
        private set
    lateinit var cpuRepository: CpuRepository
        private set
    lateinit var networkModeController: NetworkModeController
        private set
    lateinit var appFreezer: AppFreezer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        RootManager.configureMainShell()
        rootManager = RootManager()
        sysfsReader = SysfsReader(rootManager)
        // MediaTek: /proc/cpufreq/MT_CPU_DVFS_*/cpufreq_oppidx fallback (perubahan.md §3.3).
        // Qualcomm: own min/max + governor neutralization strategy. Vendor is detected at runtime.
        val shellExecutor = LibsuSysfsShellExecutor()
        val socVendor = SocVendor.detect()
        sysfsWriter = SysfsWriter(
            shellExecutor = shellExecutor,
            mtkOppFallback = LibsuMtkOppFallbackWriter(shellExecutor),
            qualcommFallback = LibsuQualcommFallbackWriter(shellExecutor),
            socVendorProvider = { socVendor }
        )
        preferences = CoolMaxPreferences(this)
        networkModeController = NetworkModeController(
            rootManager = rootManager,
            preferences = preferences
        )
        appFreezer = AppFreezer(
            context = this,
            rootManager = rootManager,
            preferences = preferences
        )
        cpuRepository = CpuRepository(
            rootManager = rootManager,
            sysfsReader = sysfsReader,
            sysfsWriter = sysfsWriter,
            preferences = preferences
        )

        // Check if previous session crashed/rebooted while pending_apply was true
        appScope.launch {
            val enteredSafeMode = preferences.checkAndEnterSafeModeIfPendingApplyLeftover()
            if (enteredSafeMode) {
                cpuRepository.restoreSystemDefaultSnapshot(TriggerType.SAFE_MODE)
            }
        }

        // An app update kills the guard service and nothing brings it back until the user changes a
        // setting or reboots, which silently disables the screen-off guard (2G, freeze, presets).
        appScope.launch {
            val screenOff = preferences.screenOffSettingsFlow.first()
            val triggers = preferences.triggerSettingsFlow.first()
            val anyActive = screenOff.enabled ||
                triggers.batteryTriggerEnabled ||
                triggers.scheduleTriggerEnabled ||
                triggers.perAppTriggerEnabled ||
                triggers.thermalGuardEnabled
            if (anyActive) ScreenOffGuardService.startServiceIfNeeded(this@CoolMaxApplication)
        }
    }
}
