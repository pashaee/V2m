package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    @Volatile
    private var mInterface: ParcelFileDescriptor? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var tun2SocksService: Tun2SocksControl? = null

    private val isStartingLock = AtomicBoolean(false)
    private val isStoppingLock = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService(isForced = true)
    }

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        if (isRunning || CoreServiceManager.isRunning() || mInterface != null || tun2SocksService != null) {
            stopAllService(isForced = false)
        } else {
            closeVpnInterfaceSafely()
            NotificationManager.cancelNotification()
        }
        unlockStart()
        if (CoreServiceManager.serviceControl?.get() === this) {
            CoreServiceManager.serviceControl = null
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE

        // Already running cleanly; keep sticky without rebuilding the active tunnel
        if (isRunning && CoreServiceManager.isRunning()) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Already running, ignoring duplicate start")
            return START_STICKY
        }

        if (isSystemVpnStart) {
            unlockStart()
        }

        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return if (isRunning) START_STICKY else START_NOT_STICKY
        }

        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        return try {
            if (!setupVpnService()) {
                stopSelf()
                START_NOT_STICKY
            } else if (!startCoreInternal()) {
                START_NOT_STICKY
            } else {
                START_STICKY
            }
        } finally {
            unlockStart()
        }
    }

    override fun getService(): Service = this

    override fun startService() {
        startCoreInternal()
    }

    private fun startCoreInternal(): Boolean {
        val vpnInterface = mInterface
        if (vpnInterface == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            stopAllService(isForced = true)
            return false
        }
        if (!CoreServiceManager.startCoreLoop(vpnInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService(isForced = true)
            return false
        }

        RootLanSharing.startClientSharing(this)
        return true
    }

    override fun stopService() {
        stopAllService(isForced = true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Prepares the VPN and configures it if permission is granted.
     */
    private fun setupVpnService(): Boolean {
        val prepareIntent = prepare(this)
        if (prepareIntent != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (!configureVpnService()) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures and establishes the VPN interface.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()
        builder.setSession(getString(R.string.app_name))

        configureNetworkSettings(builder)
        configurePerAppProxy(builder)
        configurePlatformFeatures(builder)

        // Close any previous interface before establishing a new one
        closeVpnInterfaceSafely()

        return try {
            val established = builder.establish()
            if (established == null) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: builder.establish() returned null")
                stopAllService(isForced = false)
                false
            } else {
                mInterface = established
                isRunning = true
                true
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to establish VPN interface", e)
            stopAllService(isForced = false)
            false
        }
    }

    /**
     * Configures IP addresses, routing rules, and DNS servers on the VPN Builder.
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach { cidr ->
                val parts = cidr.split('/')
                val prefix = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && prefix != null) {
                    builder.addRoute(parts[0], prefix)
                }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3)
                builder.addRoute("fc00::", 18)
            } else {
                builder.addRoute("::", 0)
            }
        }

        val validDnsServers = SettingsManager.getVpnDnsServers()
            .filter { Utils.isPureIpAddress(it) }
        if (validDnsServers.isNotEmpty()) {
            validDnsServers.forEach { builder.addDnsServer(it) }
        } else {
            val fallbackDns = AppConfig.DNS_VPN.takeIf { Utils.isPureIpAddress(it) } ?: "1.1.1.1"
            builder.addDnsServer(fallbackDns)
        }
    }

    /**
     * Configures platform-specific VPN features for Android Q+.
     */
    private fun configurePlatformFeatures(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules while guaranteeing the VPN app itself never loops through TUN.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            addDisallowedAppSafely(builder, selfPackageName)
            return
        }

        val savedApps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (savedApps.isNullOrEmpty()) {
            addDisallowedAppSafely(builder, selfPackageName)
            return
        }

        val apps = savedApps.toMutableSet()
        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        if (bypassApps) {
            apps.add(selfPackageName)
        } else {
            apps.remove(selfPackageName)
        }

        var configuredCount = 0
        apps.forEach { packageName ->
            try {
                if (bypassApps) {
                    builder.addDisallowedApplication(packageName)
                } else {
                    builder.addAllowedApplication(packageName)
                }
                configuredCount++
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Package not found for per-app proxy: $packageName", e)
            }
        }

        // Prevent infinite VPN loop if all allowed packages were uninstalled
        if (!bypassApps && configuredCount == 0) {
            addDisallowedAppSafely(builder, selfPackageName)
        }
    }

    private fun addDisallowedAppSafely(builder: Builder, packageName: String) {
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to disallow package: $packageName", e)
        }
    }

    /**
     * Starts tun2socks when HEV tunnel mode is enabled.
     */
    private fun runTun2socks() {
        stopTun2SocksSafely()
        if (!SettingsManager.isUsingHevTun()) {
            return
        }

        val vpnInterface = mInterface ?: return
        val service = TProxyService(
            context = applicationContext,
            vpnInterface = vpnInterface,
            isRunningProvider = { isRunning && mInterface != null },
            restartCallback = {
                if (isRunning && mInterface != null) {
                    runTun2socks()
                }
            }
        )
        tun2SocksService = service
        service.startTun2Socks()
    }

    private fun stopTun2SocksSafely() {
        val current = tun2SocksService
        tun2SocksService = null
        if (current != null) {
            try {
                current.stopTun2Socks()
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to stop tun2socks", e)
            }
        }
    }

    private fun closeVpnInterfaceSafely() {
        val iface = mInterface
        mInterface = null
        if (iface != null) {
            try {
                iface.close()
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }

    private fun stopAllService(isForced: Boolean = true) {
        if (!isStoppingLock.compareAndSet(false, true)) {
            return
        }
        try {
            unlockStart()
            isRunning = false

            stopTun2SocksSafely()

            try {
                RootLanSharing.stopClientSharing(this)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to stop LAN sharing", e)
            }

            CoreServiceManager.stopCoreLoop()

            if (isForced) {
                // stopSelf() before closing the interface avoids port-in-use issues on quick restarts
                stopSelf()
            }

            closeVpnInterfaceSafely()
        } finally {
            isStoppingLock.set(false)
        }
    }

    fun tryLockStart(): Boolean {
        val locked = isStartingLock.compareAndSet(false, true)
        LogUtil.d(AppConfig.TAG, "StartCore-VPN: tryLockStart -> $locked")
        return locked
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.d(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}