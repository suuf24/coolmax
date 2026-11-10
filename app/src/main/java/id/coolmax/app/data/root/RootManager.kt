package id.coolmax.app.data.root

import android.os.Build
import com.topjohnwu.superuser.Shell
import id.coolmax.app.domain.model.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages root shell lifecycle and root status checks exclusively via `com.github.topjohnwu.libsu`.
 * Never uses `Runtime.getRuntime().exec("su")` directly.
 */
class RootManager {

    companion object {
        @Volatile
        private var isConfigured = false

        fun configureMainShell() {
            if (!isConfigured) {
                synchronized(this) {
                    if (!isConfigured) {
                        Shell.enableVerboseLogging = false
                        Shell.setDefaultBuilder(
                            Shell.Builder.create()
                                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                                .setTimeout(5)
                        )
                        isConfigured = true
                    }
                }
            }
        }
    }

    init {
        configureMainShell()
    }

    /**
     * Checks whether root access is granted. Safe to call on startup; never throws or crashes.
     */
    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(4_000L) {
                val shell = Shell.getShell()
                shell.isRoot
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Executes a single read-only command via libsu with a strict timeout.
     */
    suspend fun execReadCommand(command: String, timeoutMs: Long = 3_000L): ShellCommandResult =
        withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(timeoutMs) {
                    val result = Shell.cmd(command).exec()
                    ShellCommandResult(
                        isSuccess = result.isSuccess,
                        exitCode = result.code,
                        stdout = result.out.joinToString("\n"),
                        stderr = result.err.joinToString("\n")
                    )
                } ?: ShellCommandResult(
                    isSuccess = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "Command timed out after ${timeoutMs}ms"
                )
            } catch (t: Throwable) {
                ShellCommandResult(
                    isSuccess = false,
                    exitCode = -2,
                    stdout = "",
                    stderr = t.message ?: "Shell execution error"
                )
            }
        }

    /**
     * Executes an arbitrary command (read *or* write, e.g. `settings put` / `am broadcast`) via libsu
     * with the same timeout and error handling as [execReadCommand].
     */
    suspend fun execCommand(command: String, timeoutMs: Long = 5_000L): ShellCommandResult =
        execReadCommand(command, timeoutMs)

    /**
     * Reads SELinux status (`Enforcing`, `Permissive`, or `Disabled`) without modifying it.
     */
    suspend fun readSelinuxStatus(): String {
        val res = execReadCommand("getenforce")
        val out = res.stdout.trim()
        return if (res.isSuccess && out.isNotEmpty()) out else "Unknown (Restricted)"
    }

    /**
     * Collects hardware, SoC, kernel, root provider, and SELinux read-only details.
     */
    suspend fun readDeviceInfo(
        clusterCount: Int,
        totalCores: Int,
        availableGovernors: List<String>
    ): DeviceInfo = withContext(Dispatchers.IO) {
        val isRooted = checkRootAccess()
        val suVersion = if (isRooted) {
            execReadCommand("su -v").stdout.trim().ifEmpty { "Granted (libsu)" }
        } else {
            "Not Granted"
        }
        val kernelVersion = System.getProperty("os.version")
            ?: execReadCommand("uname -r").stdout.trim().ifEmpty { "Unknown" }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }.orEmpty()
        } else {
            ""
        }.ifEmpty {
            execReadCommand("getprop ro.board.platform").stdout.trim()
                .ifEmpty { Build.HARDWARE ?: "Unknown SoC" }
        }
        val boardPlatform = Build.BOARD ?: "Unknown"
        val selinux = readSelinuxStatus()

        DeviceInfo(
            socModel = socModel,
            boardPlatform = boardPlatform,
            clusterCount = clusterCount,
            totalCores = totalCores,
            availableGovernors = availableGovernors,
            kernelVersion = kernelVersion,
            isRooted = isRooted,
            rootProvider = suVersion,
            selinuxStatus = selinux
        )
    }
}

data class ShellCommandResult(
    val isSuccess: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)
