package id.coolmax.app.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import id.coolmax.app.CoolMaxApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings Tile allowing one-tap toggling of the Screen-Off CPU Profile directly from the notification shade.
 */
class ScreenOffTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? CoolMaxApplication ?: return
        tileScope.launch {
            val current = app.preferences.screenOffSettingsFlow.first()
            val newEnabled = !current.enabled
            app.preferences.updateScreenOffSettings(current.copy(enabled = newEnabled))
            if (newEnabled) {
                ScreenOffGuardService.startServiceIfNeeded(this@ScreenOffTileService)
            } else {
                ScreenOffGuardService.stopService(this@ScreenOffTileService)
            }
            refreshTileState()
        }
    }

    private fun refreshTileState() {
        val app = applicationContext as? CoolMaxApplication ?: return
        tileScope.launch {
            val settings = app.preferences.screenOffSettingsFlow.first()
            val tile = qsTile ?: return@launch
            tile.state = if (settings.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            // Tile.subtitle was added in API 29; on Android 8 and 9 the tile simply shows its label.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = settings.preset.name
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }
}
