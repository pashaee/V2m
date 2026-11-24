package com.v2ray.ang.ui.main

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import kotlin.math.abs

private const val KEY_START_TIME = "v2m_start_time"
private const val KEY_START_ELAPSED = "v2m_start_elapsed"
private const val KEY_START_BOOT_COUNT = "v2m_start_boot_count"
private const val GEOIP_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private val PING_MS_REGEX = Regex("""(\d+)\s*ms""")

private fun getCurrentBootCount(context: Context): Int {
    return try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }
}

private fun saveFreshTimerState(context: Context) {
    val nowWall = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val bootCount = getCurrentBootCount(context)
    MmkvManager.encodeSettings(KEY_START_TIME, nowWall.toString())
    MmkvManager.encodeSettings(KEY_START_ELAPSED, nowElapsed.toString())
    MmkvManager.encodeSettings(KEY_START_BOOT_COUNT, bootCount.toString())
}

private fun clearSavedTimerState() {
    MmkvManager.encodeSettings(KEY_START_TIME, "")
    MmkvManager.encodeSettings(KEY_START_ELAPSED, "")
    MmkvManager.encodeSettings(KEY_START_BOOT_COUNT, "")
}

/**
 * استخراج زمان شروع معتبرِ نشست فعلی با تشخیص خودکار ریست گوشی و تغییر ساعت سیستم
 */
private fun resolveOrInitSessionStartElapsed(context: Context): Long {
    val nowWall = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val estimatedBootWall = nowWall - nowElapsed
    val currentBootCount = getCurrentBootCount(context)

    val savedWall = MmkvManager.decodeSettingsString(KEY_START_TIME)?.toLongOrNull() ?: 0L
    val savedElapsed = MmkvManager.decodeSettingsString(KEY_START_ELAPSED)?.toLongOrNull() ?: 0L
    val savedBootCount = MmkvManager.decodeSettingsString(KEY_START_BOOT_COUNT)?.toIntOrNull() ?: -1

    val isSavedWallFromCurrentBoot =
        savedWall > 0L &&
                savedWall >= (estimatedBootWall - 5_000L) &&
                savedWall <= (nowWall + 5_000L)

    val isSavedElapsedFromCurrentBoot =
        savedElapsed > 0L &&
                savedElapsed <= nowElapsed &&
                (currentBootCount == -1 || savedBootCount == currentBootCount)

    return when {
        isSavedWallFromCurrentBoot -> {
            val computedElapsedFromWall = (nowElapsed - (nowWall - savedWall).coerceAtLeast(0L)).coerceAtLeast(0L)
            if (isSavedElapsedFromCurrentBoot && abs(savedElapsed - computedElapsedFromWall) <= 5_000L) {
                savedElapsed
            } else {
                MmkvManager.encodeSettings(KEY_START_ELAPSED, computedElapsedFromWall.toString())
                MmkvManager.encodeSettings(KEY_START_BOOT_COUNT, currentBootCount.toString())
                computedElapsedFromWall
            }
        }

        isSavedElapsedFromCurrentBoot -> {
            val repairedWall = (nowWall - (nowElapsed - savedElapsed)).coerceAtLeast(0L)
            MmkvManager.encodeSettings(KEY_START_TIME, repairedWall.toString())
            savedElapsed
        }

        else -> {
            // داده قبلی نامعتبر یا مربوط به قبل از ریست گوشی است؛ شروع مجدد از صفر
            saveFreshTimerState(context)
            nowElapsed
        }
    }
}

private suspend fun fetchGeoLocationViaProxy(): Triple<String, String, ImageBitmap?>? =
    withContext(Dispatchers.IO) {
        val httpPort = SettingsManager.getHttpPort()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))

        var country = ""
        var code = ""

        val endpoints = listOf(
            "https://api.ip.sb/geoip" to ("country" to "country_code"),
            "https://ipwho.is/" to ("country" to "country_code")
        )

        for ((endpointUrl, keys) in endpoints) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpointUrl).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", GEOIP_USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val parsedCountry = jsonObject.optString(keys.first, "").trim()
                    val parsedCode = jsonObject.optString(keys.second, "").trim().uppercase(Locale.US)
                    if (parsedCountry.isNotEmpty() && !parsedCountry.equals("Unknown", ignoreCase = true)) {
                        country = parsedCountry
                        code = parsedCode
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                connection?.disconnect()
            }
        }

        if (country.isEmpty()) return@withContext null

        val downloadedFlag = if (code.length == 2) {
            var flagConn: HttpURLConnection? = null
            try {
                flagConn = (URL("https://flagcdn.com/w80/${code.lowercase(Locale.US)}.png")
                    .openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", GEOIP_USER_AGENT)
                }
                if (flagConn.responseCode == HttpURLConnection.HTTP_OK) {
                    flagConn.inputStream.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            } finally {
                flagConn?.disconnect()
            }
        } else {
            null
        }

        Triple(country, code, downloadedFlag)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }
    var showConfigList by remember { mutableStateOf(false) }
    var lastToggleClickMs by remember { mutableLongStateOf(0L) }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }

    val removeServer: (String) -> Unit = remember(confirmRemove, onAction) {
        { guid ->
            if (confirmRemove) showRemoveConfirm = guid else onAction(MainAction.RemoveServer(guid))
        }
    }

    val dismissConfigSheet: () -> Unit = {
        showConfigList = false
        if (showSearch || searchQuery.isNotEmpty()) {
            showSearch = false
            searchQuery = ""
            onAction(MainAction.Search(""))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }
    var locateInProgress by remember { mutableStateOf(false) }

    // استخراج ایمن نام و آی‌پی کانفیگ انتخاب شده
    val selectedProfile = remember(selectedGuid, groups, showConfigList) {
        selectedGuid?.takeIf { it.isNotEmpty() }?.let { MmkvManager.decodeServerConfig(it) }
    }
    val configName = selectedProfile?.remarks?.takeIf { it.isNotBlank() } ?: "Select Config"
    val configIp = selectedProfile?.server?.takeIf { it.isNotBlank() } ?: "Tap to change"

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    val latestDoubleColumnDisplay by rememberUpdatedState(doubleColumnDisplay)

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)
    val latestLocateInProgress by rememberUpdatedState(locateInProgress)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (!latestLocateInProgress && page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    // پینگ خودکار هر ۲۰ ثانیه فقط زمانی که برنامه در پیش‌زمینه (Foreground) است
    LaunchedEffect(isRunning, selectedGuid, lifecycleOwner) {
        if (isRunning) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(2000L)
                while (isActive) {
                    onAction(MainAction.TestCurrentServer)
                    delay(20000L)
                }
            }
        }
    }

    // پردازش متن پینگ برای نمایش تمیزتر
    val finalFormattedPing = remember(displayText, isRunning) {
        if (!isRunning) return@remember ""
        val pingValue = when {
            displayText.contains("Tap to check", ignoreCase = true) -> "Testing..."
            displayText.contains("timeout", ignoreCase = true) ||
                    displayText.contains("failed", ignoreCase = true) ||
                    displayText.contains("-1") -> "Timeout"
            displayText.contains("succeeded", ignoreCase = true) ||
                    displayText.contains("ms", ignoreCase = true) -> {
                val match = PING_MS_REGEX.find(displayText)
                if (match != null) "${match.groupValues[1]} ms" else "Error"
            }
            else -> "Testing..."
        }
        "Ping: $pingValue"
    }

    LaunchedEffect(uiState.locateTarget) {
        val target = uiState.locateTarget ?: return@LaunchedEffect
        if (target.groupIndex !in 0 until pagerState.pageCount) {
            mainViewModel.onAction(MainAction.LocateHandled(target))
            return@LaunchedEffect
        }

        locateInProgress = true
        try {
            if (pagerState.settledPage != target.groupIndex) {
                pagerState.navigateToPageOptimized(
                    targetPage = target.groupIndex,
                    animateAdjacentPage = false
                )
            }
            onAction(MainAction.SelectGroup(target.groupId))

            repeat(10) {
                val ready = if (latestDoubleColumnDisplay) {
                    lazyGridStates[target.groupId] != null
                } else {
                    lazyListStates[target.groupId] != null
                }
                if (ready) return@repeat
                delay(16L)
            }

            if (latestDoubleColumnDisplay) {
                lazyGridStates[target.groupId]?.let { gridState ->
                    gridState.scrollToItem(
                        index = target.itemPosition,
                        scrollOffset = -gridState.layoutInfo.viewportSize.height / 3
                    )
                }
            } else {
                lazyListStates[target.groupId]?.let { listState ->
                    listState.scrollToItem(
                        index = target.itemPosition,
                        scrollOffset = -listState.layoutInfo.viewportSize.height / 3
                    )
                }
            }
        } finally {
            delay(32L)
            locateInProgress = false
            mainViewModel.onAction(MainAction.LocateHandled(target))
        }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = {
            showDelAllConfirm = false
            onAction(MainAction.RemoveAllServers)
        },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = {
            showDelDuplicateConfirm = false
            onAction(MainAction.RemoveDuplicateServers)
        },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = {
            showDelInvalidConfirm = false
            onAction(MainAction.RemoveInvalidServers)
        },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid ->
            showRemoveConfirm = null
            onAction(MainAction.RemoveServer(guid))
        }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }

    if (shareQRCodeBitmap != null) {
        QRCodeDialog(
            bitmap = shareQRCodeBitmap,
            onDismiss = { onAction(MainAction.DismissQRCodeDialog) }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                },
                onCheckUpdate = {
                    scope.launch { drawerState.close() }
                    onNavigate(MainDestination.CheckUpdate)
                }
            )
        }
    ) {
        Scaffold(
            containerColor = Color.Black,
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black)
            ) {
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_menu),
                        contentDescription = "Menu",
                        tint = Color.White
                    )
                }

                TopStatusAndTimer(
                    isRunning = isRunning,
                    selectedGuid = selectedGuid,
                    pingText = finalFormattedPing,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 24.dp, start = 24.dp, end = 72.dp)
                )

                CenterMagicButton(
                    isRunning = isRunning,
                    modifier = Modifier.align(Alignment.Center),
                    onClick = {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastToggleClickMs < 500L) return@CenterMagicButton
                        lastToggleClickMs = now

                        if (isRunning) {
                            clearSavedTimerState()
                        } else {
                            saveFreshTimerState(context)
                        }
                        onAction(MainAction.ToggleService)
                    }
                )

                BottomConfigSelector(
                    configName = configName,
                    configIp = if (selectedGuid.isNullOrEmpty()) "Tap to select server" else configIp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    onClick = { showConfigList = true }
                )
            }
        }
    }

    if (showConfigList) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = dismissConfigSheet,
            sheetState = sheetState,
            containerColor = Color(0xFF1E1E1E)
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(0.85f)
            ) {
                MainTopBar(
                    isLoading = isLoading,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { query: String ->
                        searchQuery = query
                        onAction(MainAction.Search(query))
                    },
                    onSearchClose = {
                        searchQuery = ""
                        onAction(MainAction.Search(""))
                        showSearch = false
                    },
                    onSearchToggle = { show: Boolean -> showSearch = show },
                    onMenuClick = {
                        dismissConfigSheet()
                        scope.launch { drawerState.open() }
                    },
                    onAction = onAction,
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> {
                                if (isRunning) saveFreshTimerState(context)
                                onAction(MainAction.RestartService)
                            }
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                        }
                    }
                )

                if (groups.isNotEmpty()) {
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(
                                        targetPage = targetIndex,
                                        animateAdjacentPage = true
                                    )
                                }
                            }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = true,
                        beyondViewportPageCount = 1,
                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                    ) { page ->
                        val group = groups.getOrNull(page) ?: return@HorizontalPager

                        GroupPagerPage(
                            groupId = group.id,
                            mainViewModel = mainViewModel,
                            selectedGuid = selectedGuid,
                            doubleColumnDisplay = doubleColumnDisplay,
                            confirmRemove = confirmRemove,
                            searchQuery = searchQuery,
                            lazyListStates = lazyListStates,
                            lazyGridStates = lazyGridStates,
                            onSelectServer = { guid ->
                                onAction(MainAction.SelectServer(guid))
                                dismissConfigSheet()
                            },
                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },
                            onRemoveServer = removeServer,
                            contentPadding = PaddingValues(
                                start = 0.dp,
                                top = 0.dp,
                                end = 0.dp,
                                bottom = 20.dp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopStatusAndTimer(
    isRunning: Boolean,
    pingText: String,
    modifier: Modifier = Modifier,
    selectedGuid: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var secondsConnected by remember { mutableLongStateOf(0L) }
    var wasRunning by remember { mutableStateOf(false) }
    var locationText by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("") }
    var flagBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // مدیریت تایمر اتصال: مقاوم در برابر ریست گوشی، بسته شدن برنامه و تغییر ساعت سیستم
    LaunchedEffect(isRunning, lifecycleOwner) {
        if (isRunning) {
            wasRunning = true
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val startElapsed = resolveOrInitSessionStartElapsed(context)
                while (isActive) {
                    secondsConnected = ((SystemClock.elapsedRealtime() - startElapsed) / 1000L).coerceAtLeast(0L)
                    delay(1000L)
                }
            }
        } else {
            secondsConnected = 0L
            if (wasRunning) {
                wasRunning = false
                clearSavedTimerState()
            } else {
                // مهلت کوتاه در شروع سرد برای اینکه اگر سرویس در پس‌زمینه متصل است، تایمر آن پاک نشود
                delay(1500L)
                clearSavedTimerState()
            }
        }
    }

    // واکشی لوکیشن و پرچم هنگام اتصال یا تعویض سرور
    LaunchedEffect(isRunning, selectedGuid) {
        if (!isRunning) {
            locationText = ""
            countryCode = ""
            flagBitmap = null
            return@LaunchedEffect
        }

        locationText = ""
        countryCode = ""
        flagBitmap = null

        var attempt = 0
        while (attempt < 3 && isActive) {
            delay(if (attempt == 0) 1800L else 2500L)
            val result = fetchGeoLocationViaProxy()
            if (result != null) {
                locationText = "Location: ${result.first}"
                countryCode = result.second
                flagBitmap = result.third
                break
            }
            attempt++
        }
    }

    val hours = secondsConnected / 3600
    val minutes = (secondsConnected % 3600) / 60
    val seconds = secondsConnected % 60
    val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = if (isRunning) R.drawable.ic_check_circle else R.drawable.ic_warning),
                contentDescription = null,
                tint = if (isRunning) Color(0xFF20D841) else Color.Gray,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("Status Connection", color = Color(0xFF888888), fontSize = 12.sp)
                Text(
                    text = if (isRunning) "Connected" else "Not Connected",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedVisibility(
            visible = isRunning,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = timeString,
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp,
                    maxLines = 1
                )
                if (pingText.isNotEmpty()) {
                    Text(
                        text = pingText,
                        color = Color(0xFFB0B0B0),
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (locationText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            text = locationText,
                            color = Color(0xFFB0B0B0),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        AnimatedCountryFlag(
                            countryCode = countryCode,
                            flagBitmap = flagBitmap
                        )
                    }
                }
            }
        }
    }
}

/**
 * کامپوننت نمایش گرافیکی پرچم کشور به همراه افکت انیمیشنی اهتزاز سه‌بعدی، موج نوری و هاله ضربان‌دار
 */
@Composable
fun AnimatedCountryFlag(
    countryCode: String,
    flagBitmap: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "flagWaveTransition")

    val waveRotationY by infiniteTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveRotationY"
    )

    val waveRotationZ by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveRotationZ"
    )

    val floatOffsetY by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatOffsetY"
    )

    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -40f,
        targetValue = 80f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val neonGreen = Color(0xFFC6F044)
    val flagEmoji = remember(countryCode) { countryCodeToFlagEmoji(countryCode) }

    Box(
        modifier = modifier.size(width = 34.dp, height = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            neonGreen.copy(alpha = glowAlpha * 0.45f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(6.dp)
                )
        )

        Box(
            modifier = Modifier
                .size(width = 26.dp, height = 18.dp)
                .graphicsLayer {
                    rotationY = waveRotationY
                    rotationZ = waveRotationZ
                    translationY = floatOffsetY
                    cameraDistance = 8f * density
                }
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A1F1A))
                .border(
                    width = 1.dp,
                    color = neonGreen.copy(alpha = glowAlpha),
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (flagBitmap != null) {
                Image(
                    bitmap = flagBitmap,
                    contentDescription = "Country Flag",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = flagEmoji,
                    fontSize = 13.sp
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            start = Offset(shimmerOffset, 0f),
                            end = Offset(shimmerOffset + 24f, 20f)
                        )
                    )
            )
        }
    }
}

/**
 * تبدیل کد دوحرفی کشور (ISO 3166-1 alpha-2) به کاراکتر گرافیکی پرچم
 */
private fun countryCodeToFlagEmoji(countryCode: String): String {
    val cleanCode = countryCode.trim().uppercase(Locale.US)
    if (cleanCode.length != 2 || cleanCode[0] !in 'A'..'Z' || cleanCode[1] !in 'A'..'Z') {
        return "🌐"
    }
    val firstChar = Character.codePointAt(cleanCode, 0) - 0x41 + 0x1F1E6
    val secondChar = Character.codePointAt(cleanCode, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
}

@Composable
fun CenterMagicButton(isRunning: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val neonGreen = Color(0xFFC6F044)
    val brightHighlight = Color(0xFFF2FFCC)
    val midGreen = Color(0xFF689F38)
    val darkCoreCenter = if (isRunning) Color(0xFF162410) else Color(0xFF101612)
    val darkCoreEdge = Color(0xFF070A08)
    val activeAlpha = if (isRunning) 1f else 0.45f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            neonGreen.copy(alpha = if (isRunning) 0.30f else 0.08f),
                            midGreen.copy(alpha = if (isRunning) 0.10f else 0.02f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(darkCoreCenter, darkCoreEdge)
                    )
                )
                .border(
                    width = 3.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            midGreen.copy(alpha = 0.55f * activeAlpha),
                            neonGreen.copy(alpha = 0.90f * activeAlpha),
                            brightHighlight.copy(alpha = activeAlpha)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_power),
                    contentDescription = "Power",
                    tint = if (isRunning) neonGreen else neonGreen.copy(alpha = 0.65f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isRunning) "Disconnect" else "Connect",
                    color = if (isRunning) neonGreen else neonGreen.copy(alpha = 0.75f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun BottomConfigSelector(
    configName: String,
    configIp: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(Color(0xFF1E1E1E))
            .clickable { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_public),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = configName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = configIp,
                    color = Color(0xFF888888),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Icon(
            painter = painterResource(id = R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(24.dp)
        )
    }
}