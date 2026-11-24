package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CoreServiceManager {

    private const val KEY_START_TIME = "v2m_start_time"
    private const val KEY_START_ELAPSED = "v2m_start_elapsed"
    private const val KEY_START_BOOT_COUNT = "v2m_start_boot_count"
    private const val STOP_WAIT_TIMEOUT_MS = 400L

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coreLock = ReentrantLock()

    private val isReceiverRegistered = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    @Volatile
    private var currentConfig: ProfileItem? = null

    @Volatile
    private var processFinder: XrayProcessFinder? = null

    @Volatile
    private var browserDialer: IDialerService? = null

    @Volatile
    private var networkMonitor: NetworkMonitor? = null

    @Volatile
    private var delayTestJob: Job? = null

    @Volatile
    private var isReloading = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    @Volatile
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finder = XrayProcessFinder(service.applicationContext)
                processFinder = finder
                coreController.registerProcessFinder(finder)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        return coreLock.withLock {
            if (isRunning()) {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
                return@withLock false
            }

            isStopping.set(false)
            try {
                doStartCoreLoop(service, vpnInterface)
                true
            } catch (e: Exception) {
                val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
                cleanupFailedStart(service)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                false
            }
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface, isReload = false)
        registerReceiverSafely(service)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (BuildConfig.DEBUG) {
            LogUtil.d(AppConfig.TAG, result.content)
        }
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentConfig = config
        val rawFd = try {
            vpnInterface?.fd ?: 0
        } catch (_: Exception) {
            0
        }
        val tunFd = if (SettingsManager.isUsingHevTun() || rawFd <= 0) 0 else rawFd

        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        } else {
            CoreNativeManager.reconcileBrowserDialer("")
        }

        coreController.startLoop(result.content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

        stopBrowserDialerSafely()
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService().also { it.start(service, dialerAddr) }
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService().also { it.start(service, dialerAddr) }
            }

            else -> Unit
        }

        if (!isReload) {
            saveConnectionTimerState(service)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, clears timer state, and shuts down plugins.
     */
    fun stopCoreLoop(): Boolean {
        isStopping.set(true)
        val service = getService()

        delayTestJob?.cancel()
        delayTestJob = null

        try {
            networkMonitor?.unregister()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to unregister network monitor", e)
        }
        networkMonitor = null
        currentVpnInterface = null
        currentConfig = null

        clearConnectionTimerState()

        if (isRunning()) {
            val stopLatch = CountDownLatch(1)
            managerScope.launch {
                try {
                    coreLock.withLock {
                        if (isRunning()) {
                            coreController.stopLoop()
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                } finally {
                    stopLatch.countDown()
                }
            }
            try {
                stopLatch.await(STOP_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        CoreNativeManager.reconcileBrowserDialer("")
        stopBrowserDialerSafely()

        if (service != null) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            unregisterReceiverSafely(service)
        } else {
            isReceiverRegistered.set(false)
        }

        NotificationManager.cancelNotification()
        return true
    }

    private fun cleanupFailedStart(service: Service) {
        delayTestJob?.cancel()
        delayTestJob = null

        try {
            networkMonitor?.unregister()
        } catch (_: Exception) {
        }
        networkMonitor = null
        currentVpnInterface = null
        currentConfig = null

        clearConnectionTimerState()
        CoreNativeManager.reconcileBrowserDialer("")
        stopBrowserDialerSafely()
        unregisterReceiverSafely(service)
        NotificationManager.cancelNotification()
    }

    private fun registerReceiverSafely(service: Service) {
        if (!isReceiverRegistered.compareAndSet(false, true)) return
        try {
            val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(service, mMsgReceive, filter, Utils.receiverFlags())
        } catch (e: Exception) {
            isReceiverRegistered.set(false)
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to register receiver", e)
        }
    }

    private fun unregisterReceiverSafely(service: Service) {
        if (!isReceiverRegistered.compareAndSet(true, false)) return
        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }
    }

    private fun stopBrowserDialerSafely() {
        val dialer = browserDialer
        browserDialer = null
        if (dialer != null) {
            try {
                dialer.stop()
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to stop browser dialer", e)
            }
        }
    }

    private fun saveConnectionTimerState(context: Context) {
        try {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val bootCount = try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            } catch (_: Exception) {
                -1
            }
            MmkvManager.encodeSettings(KEY_START_TIME, nowWall.toString())
            MmkvManager.encodeSettings(KEY_START_ELAPSED, nowElapsed.toString())
            MmkvManager.encodeSettings(KEY_START_BOOT_COUNT, bootCount.toString())
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to save timer state", e)
        }
    }

    private fun clearConnectionTimerState() {
        try {
            MmkvManager.encodeSettings(KEY_START_TIME, "")
            MmkvManager.encodeSettings(KEY_START_ELAPSED, "")
            MmkvManager.encodeSettings(KEY_START_BOOT_COUNT, "")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to clear timer state", e)
        }
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed.
     */
    private fun reloadCore(): Boolean {
        if (isReloading || isStopping.get()) return false
        val service = getService() ?: return false

        return coreLock.withLock {
            if (isReloading || isStopping.get() || !isRunning()) return@withLock false

            isReloading = true
            try {
                val vpnInterface = currentVpnInterface
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

                coreController.stopLoop()
                if (isStopping.get()) {
                    return@withLock false
                }
                launchCore(service, vpnInterface, isReload = true)

                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
                true
            } catch (e: Exception) {
                val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
                clearConnectionTimerState()
                stopBrowserDialerSafely()
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                NotificationManager.cancelNotification()
                // Tear down the VPN service so traffic is not stuck in a dead interface
                managerScope.launch(Dispatchers.Main) {
                    serviceControl?.get()?.stopService()
                }
                false
            } finally {
                isReloading = false
            }
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        if (!isRunning() || isStopping.get()) return emptyList()

        val payload = try {
            coreController.queryAllOutboundTrafficStats()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to query outbound traffic stats", e)
            return emptyList()
        }

        if (payload.isNullOrBlank()) return emptyList()

        val result = ArrayList<OutboundTrafficStat>()
        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach
            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     */
    private fun measureV2rayDelay() {
        if (!isRunning() || isStopping.get()) {
            return
        }

        delayTestJob?.cancel()
        delayTestJob = managerScope.launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }
            if (time == -1L && isRunning() && !isStopping.get()) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }

            if (!isRunning() || isStopping.get()) return@launch

            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)

            if (time >= 0 && isRunning() && !isStopping.get()) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    if (!isRunning() || isStopping.get()) return@let
                    MessageHelper.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_RESULT,
                        result.copy(
                            country = ip.country,
                            ipAddress = ip.ipAddress,
                        ),
                    )
                }
            }
        }
    }

    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? =
            context.applicationContext.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(
            network: String,
            srcIP: String,
            srcPort: Long,
            destIP: String,
            destPort: Long
        ): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            val connectivityManager = cm ?: return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort <= 0L || srcPort !in 0L..65535L || destPort !in 1L..65535L) {
                return -1L
            }

            return try {
                val uid = connectivityManager.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                if (BuildConfig.DEBUG) {
                    LogUtil.d(AppConfig.TAG, "ProcessFinder: $network $srcIP:$srcPort -> $destIP:$destPort, uid=$uid")
                }
                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val control = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(control.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(control.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT,
                AppConfig.MSG_STATE_START -> Unit

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    control.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val appContext = control.getService().applicationContext
                    val pendingResult = goAsync()
                    managerScope.launch(Dispatchers.Default) {
                        try {
                            control.stopService()
                            delay(500L)
                            LauncherManager.startService(appContext)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    if (isRunning() && !isStopping.get()) {
                        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                        NotificationManager.startSpeedNotification()
                    }
                }
            }
        }
    }
}