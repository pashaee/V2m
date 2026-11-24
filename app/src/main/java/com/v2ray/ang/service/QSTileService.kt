package com.v2ray.ang.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.WeakReference

class QSTileService : TileService() {

    private var mMsgReceive: BroadcastReceiver? = null
    private var lastClickElapsedMs: Long = 0L

    private val tileIcon: Icon by lazy {
        Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    /**
     * Updates the Quick Settings tile state, icon, and label safely.
     */
    fun setState(state: Int) {
        val tile = qsTile ?: return
        tile.icon = tileIcon
        when (state) {
            Tile.STATE_ACTIVE -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = CoreServiceManager.getRunningServerName()
                    .takeIf { it.isNotBlank() }
                    ?: getString(R.string.app_name)
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
            }
        }
        try {
            tile.updateTile()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "QSTileService: Failed to update tile", e)
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        syncCurrentTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        syncCurrentTileState()
        registerReceiverSafely()
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onStopListening() {
        unregisterReceiverSafely()
        super.onStopListening()
    }

    override fun onDestroy() {
        unregisterReceiverSafely()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickElapsedMs < CLICK_DEBOUNCE_MS) {
            return
        }
        lastClickElapsedMs = now

        val tile = qsTile ?: return
        try {
            when (tile.state) {
                Tile.STATE_INACTIVE -> {
                    LauncherManager.startServiceFromToggle(this)
                }

                Tile.STATE_ACTIVE -> {
                    LauncherManager.stopService(this)
                }

                else -> {
                    syncCurrentTileState()
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "QSTileService: Failed to handle tile click", e)
            syncCurrentTileState()
        }
    }

    private fun syncCurrentTileState() {
        setState(if (CoreServiceManager.isRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }

    private fun registerReceiverSafely() {
        unregisterReceiverSafely()
        val receiver = ReceiveMessageHandler(this)
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        try {
            ContextCompat.registerReceiver(applicationContext, receiver, filter, Utils.receiverFlags())
            mMsgReceive = receiver
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "QSTileService: Failed to register receiver", e)
        }
    }

    private fun unregisterReceiverSafely() {
        val receiver = mMsgReceive ?: return
        mMsgReceive = null
        try {
            applicationContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "QSTileService: Failed to unregister receiver", e)
        }
    }

    private class ReceiveMessageHandler(service: QSTileService) : BroadcastReceiver() {
        private val mReference = WeakReference(service)

        override fun onReceive(ctx: Context?, intent: Intent?) {
            val service = mReference.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    service.setState(Tile.STATE_ACTIVE)
                }

                AppConfig.MSG_STATE_NOT_RUNNING,
                AppConfig.MSG_STATE_START_FAILURE,
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    service.setState(Tile.STATE_INACTIVE)
                }
            }
        }
    }

    companion object {
        private const val CLICK_DEBOUNCE_MS = 500L
    }
}