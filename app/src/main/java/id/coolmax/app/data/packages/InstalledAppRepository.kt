package id.coolmax.app.data.packages

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One launchable application shown in the per-app picker. */
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

/**
 * Loads launchable applications for the per-app governor picker.
 *
 * Uses `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` together with the `<queries>` block in
 * AndroidManifest.xml, so no QUERY_ALL_PACKAGES permission (and no legacy broad package access) is
 * required. All package-manager work runs on [Dispatchers.IO].
 */
class InstalledAppRepository(private val context: Context) {

    suspend fun loadLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrDefault(emptyList())

        resolved.asSequence()
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: packageName
                val isSystem = appInfo != null &&
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                InstalledAppInfo(packageName = packageName, label = label, isSystem = isSystem)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            .toList()
    }


}
