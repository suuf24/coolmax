package id.coolmax.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import id.coolmax.app.CoolMaxApplication
import id.coolmax.app.R
import id.coolmax.app.domain.model.PresetType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Homescreen AppWidget displaying the active Screen-Off preset and providing one-tap buttons
 * to toggle Screen-Off Guard or cycle presets (`BATTERY_SAVER` -> `BALANCED` -> `CUSTOM`).
 */
class CoolMaxWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CYCLE_PRESET = "id.coolmax.app.ACTION_WIDGET_CYCLE_PRESET"
        const val ACTION_TOGGLE_SCREEN_OFF = "id.coolmax.app.ACTION_WIDGET_TOGGLE_SCREEN_OFF"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext as? CoolMaxApplication ?: return
        when (intent.action) {
            ACTION_TOGGLE_SCREEN_OFF -> {
                CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    val current = app.preferences.screenOffSettingsFlow.first()
                    val nextEnabled = !current.enabled
                    app.preferences.updateScreenOffSettings(current.copy(enabled = nextEnabled))
                    if (nextEnabled) {
                        ScreenOffGuardService.startServiceIfNeeded(context)
                    } else {
                        ScreenOffGuardService.stopService(context)
                    }
                    refreshWidgets(context)
                }
            }
            ACTION_CYCLE_PRESET -> {
                CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    val current = app.preferences.screenOffSettingsFlow.first()
                    val nextPreset = when (current.preset) {
                        PresetType.BATTERY_SAVER -> PresetType.BALANCED
                        PresetType.BALANCED -> PresetType.CUSTOM
                        PresetType.CUSTOM -> PresetType.BATTERY_SAVER
                    }
                    app.preferences.updateScreenOffSettings(current.copy(preset = nextPreset))
                    refreshWidgets(context)
                }
            }
        }
    }

    private fun refreshWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CoolMaxWidgetProvider::class.java))
        updateAllWidgets(context, manager, ids)
    }

    private fun updateAllWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val app = context.applicationContext as? CoolMaxApplication ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val settings = app.preferences.screenOffSettingsFlow.first()
            for (widgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_coolmax)
                val statusText = if (settings.enabled) {
                    context.getString(R.string.widget_status_on, settings.preset.name)
                } else {
                    context.getString(R.string.widget_status_off)
                }
                views.setTextViewText(R.id.widget_status_text, statusText)

                val toggleIntent = Intent(context, CoolMaxWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE_SCREEN_OFF
                }
                val togglePending = PendingIntent.getBroadcast(
                    context,
                    10,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_toggle, togglePending)

                val cycleIntent = Intent(context, CoolMaxWidgetProvider::class.java).apply {
                    action = ACTION_CYCLE_PRESET
                }
                val cyclePending = PendingIntent.getBroadcast(
                    context,
                    11,
                    cycleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_preset, cyclePending)

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}
