package net.pekmen.one.core.platform.samsung

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.platform.R

/**
 * Quick Settings tile showing live Focus state and toggling pause/resume/next phase.
 * Exported strictly with `android.permission.BIND_QUICK_SETTINGS_TILE` in AndroidManifest.xml.
 */
class FocusQuickSettingsTileService : TileService() {

    private val serviceJob = SupervisorJob()

    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var tileActivityStarter: ((Intent, PendingIntent) -> Unit)? = null

    private val serviceScope: CoroutineScope
        get() = CoroutineScope(serviceJob + mainDispatcher)

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTileAsync()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileAsync()
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            handleTileClick()
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun refreshTileAsync() {
        serviceScope.launch {
            refreshTileState()
        }
    }

    internal suspend fun refreshTileState() {
        val provider = applicationContext as? FocusTileBridgeProvider
        val status = if (provider != null) {
            withContext(ioDispatcher) { provider.focusTileStateBridge().currentTileStatus() }
        } else {
            FocusTileStatus(status = null)
        }
        renderTileStatus(status)
    }

    internal suspend fun handleTileClick() {
        val provider = applicationContext as? FocusTileBridgeProvider
        if (provider == null) {
            renderTileStatus(FocusTileStatus(status = null))
            launchOrUnlock(activityClass = null)
            return
        }
        val bridge = provider.focusTileStateBridge()
        val current = withContext(ioDispatcher) { bridge.currentTileStatus() }
        val isActive = current.status == FocusSessionStatus.RUNNING ||
            current.status == FocusSessionStatus.PAUSED ||
            current.status == FocusSessionStatus.AWAITING_NEXT_PHASE

        if (!isActive) {
            renderTileStatus(current)
            launchOrUnlock(provider.launcherActivityClass())
            return
        }

        val updated = withContext(ioDispatcher) { bridge.toggleFromTile() }
        renderTileStatus(updated)
    }

    internal fun renderTileStatus(status: FocusTileStatus) {
        val tile = qsTile ?: return
        val displayLabel = status.profileName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.tile_focus_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_focus)

        when (status.status) {
            FocusSessionStatus.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = displayLabel
                tile.subtitle = getString(
                    R.string.tile_focus_subtitle_running,
                    phaseLabel(status.phase)
                )
            }
            FocusSessionStatus.PAUSED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = displayLabel
                tile.subtitle = getString(R.string.tile_focus_subtitle_paused)
            }
            FocusSessionStatus.AWAITING_NEXT_PHASE -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = displayLabel
                tile.subtitle = getString(R.string.tile_focus_subtitle_awaiting)
            }
            FocusSessionStatus.COMPLETED,
            FocusSessionStatus.ABANDONED,
            null -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_focus_label)
                tile.subtitle = getString(R.string.tile_focus_subtitle_idle)
            }
        }
        tile.updateTile()
    }

    internal fun buildLaunchIntent(activityClass: Class<out Activity>?): Intent? {
        val baseIntent = if (activityClass != null) {
            Intent(this, activityClass)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        } ?: return null
        return baseIntent.apply {
            `package` = packageName
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun launchOrUnlock(activityClass: Class<out Activity>?) {
        if (isLocked) {
            unlockAndRun { launchAppFromTile(activityClass) }
        } else {
            launchAppFromTile(activityClass)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchAppFromTile(activityClass: Class<out Activity>?) {
        val intent = buildLaunchIntent(activityClass) ?: return
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_TILE_LAUNCH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val customStarter = tileActivityStarter
        if (customStarter != null) {
            customStarter(intent, pendingIntent)
            return
        }
        val launched = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pendingIntent)
            } else {
                startActivityAndCollapse(intent)
            }
        }.isSuccess
        if (!launched) {
            runCatching { startActivity(intent) }
        }
    }

    private fun phaseLabel(phase: FocusPhase?): String {
        return when (phase) {
            FocusPhase.SHORT_BREAK -> getString(R.string.notif_phase_short_break)
            FocusPhase.LONG_BREAK -> getString(R.string.notif_phase_long_break)
            FocusPhase.FOCUS, null -> getString(R.string.notif_phase_focus)
        }
    }

    companion object {
        private const val REQUEST_CODE_TILE_LAUNCH = 4301

        fun requestTileUpdate(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                requestListeningState(
                    appContext,
                    ComponentName(appContext, FocusQuickSettingsTileService::class.java)
                )
            }
        }
    }
}

data class FocusTileStatus(
    val status: FocusSessionStatus?,
    val profileName: String? = null,
    val phase: FocusPhase? = null
)

interface FocusTileStateBridge {
    suspend fun currentTileStatus(): FocusTileStatus
    suspend fun toggleFromTile(): FocusTileStatus
}

interface FocusTileBridgeProvider {
    fun focusTileStateBridge(): FocusTileStateBridge
    fun launcherActivityClass(): Class<out Activity>
}
