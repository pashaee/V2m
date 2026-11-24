package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.os.UserManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        val action = intent?.action ?: return

        LogUtil.i(AppConfig.TAG, "BootReceiver received: $action")

        if (action !in SUPPORTED_ACTIONS) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: Unhandled action: $action")
            return
        }

        // Guard against accessing credential-encrypted MMKV storage before user unlock
        val userManager = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: User is locked, skipping $action")
            return
        }

        // Avoid duplicate execution when LOCKED_BOOT_COMPLETED and BOOT_COMPLETED arrive back-to-back
        val now = SystemClock.elapsedRealtime()
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            lastHandledBootElapsedMs > 0L &&
            (now - lastHandledBootElapsedMs) < DUPLICATE_BOOT_WINDOW_MS
        ) {
            LogUtil.i(AppConfig.TAG, "BootReceiver: Ignoring duplicate boot broadcast ($action)")
            return
        }
        lastHandledBootElapsedMs = now

        // Always wipe stale connection timer state on reboot or app update
        clearStaleTimerState()

        // Keep scheduled subscription updates active regardless of auto-start preference
        try {
            SubscriptionUpdater.sync(appContext)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "BootReceiver: Failed to sync subscriptions", e)
        }

        if (!MmkvManager.decodeStartOnBoot()) {
            LogUtil.i(AppConfig.TAG, "BootReceiver: Auto-start on boot is disabled")
            return
        }

        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty() || MmkvManager.decodeServerConfig(selectedGuid) == null) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: No valid server selected")
            return
        }

        if (SettingsManager.isVpnMode() && VpnService.prepare(appContext) != null) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: VPN permission not granted, skipping auto-start")
            return
        }

        try {
            LogUtil.i(AppConfig.TAG, "BootReceiver: Starting V2Ray service")
            LauncherManager.startService(appContext)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "BootReceiver: Failed to auto-start service", e)
        }
    }

    private fun clearStaleTimerState() {
        try {
            MmkvManager.encodeSettings(KEY_START_TIME, "")
            MmkvManager.encodeSettings(KEY_START_ELAPSED, "")
            MmkvManager.encodeSettings(KEY_START_BOOT_COUNT, "")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: Failed to clear stale timer state", e)
        }
    }

    companion object {
        private const val KEY_START_TIME = "v2m_start_time"
        private const val KEY_START_ELAPSED = "v2m_start_elapsed"
        private const val KEY_START_BOOT_COUNT = "v2m_start_boot_count"
        private const val DUPLICATE_BOOT_WINDOW_MS = 10_000L

        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        @Volatile
        private var lastHandledBootElapsedMs: Long = 0L
    }
}