package com.v2ray.ang.ui.main

import android.graphics.BitmapFactory
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.delay
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

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
    val displayText = mainViewModel.formatStatus(uiState.status) // استخراج وضعیت/پینگ
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    val context = LocalContext.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }
    var showConfigList by remember { mutableStateOf(false) }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String) -> Unit = { guid ->
        if (confirmRemove) showRemoveConfirm = guid else onAction(MainAction.RemoveServer(guid))
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }
    var locateInProgress by remember { mutableStateOf(false) }

    // --- استخراج نام و آی‌پی کانفیگ انتخاب شده ---
    val selectedProfile = remember(selectedGuid, groups) {
        MmkvManager.decodeServerConfig(selectedGuid ?: "")
    }
    val configName = selectedProfile?.remarks ?: "Select Config"
    val configIp = selectedProfile?.server ?: "Tap to change"

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

    // --- پینگ خودکار هر 20 ثانیه ---
    LaunchedEffect(isRunning) {
        if (isRunning) {
            kotlinx.coroutines.delay(2000)
            while (isActive) {
                onAction(MainAction.TestCurrentServer)
                kotlinx.coroutines.delay(20000)
            }
        }
    }

    // پردازش متن پینگ برای نمایش تمیزتر
    val finalFormattedPing = remember(displayText, isRunning) {
        if (!isRunning) return@remember ""
        val pingValue = when {
            displayText.contains("Tap to check", ignoreCase = true) -> "Testing..."
            displayText.contains("succeeded", ignoreCase = true) || displayText.contains("ms", ignoreCase = true) -> {
                val match = Regex("(\\d+)\\s*ms").find(displayText)
                if (match != null) "${match.groupValues[1]} ms" else "Error"
            }
            displayText.contains("timeout", ignoreCase = true) || displayText.contains("failed", ignoreCase = true) -> "Timeout"
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
                delay(16)
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
            delay(32)
            locateInProgress = false
            mainViewModel.onAction(MainAction.LocateHandled(target))
        }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
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
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
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
                    Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
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
                    pingText = finalFormattedPing,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 24.dp, start = 24.dp)
                )

                CenterMagicButton(
                    isRunning = isRunning,
                    modifier = Modifier.align(Alignment.Center),
                    onClick = { onAction(MainAction.ToggleService) }
                )

                BottomConfigSelector(
                    configName = configName,
                    configIp = if (selectedGuid.isNullOrEmpty()) "Tap to select server" else configIp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    onClick = { showConfigList = true }
                )
            }
        }
    }

    if (showConfigList) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = { showConfigList = false },
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
                        showConfigList = false
                        scope.launch { drawerState.open() }
                    },
                    onAction = onAction,
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
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
                                showConfigList = false
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
fun TopStatusAndTimer(isRunning: Boolean, pingText: String, modifier: Modifier = Modifier) {
    var secondsConnected by remember { mutableLongStateOf(0L) }
    var locationText by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("") }
    var flagBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            // واکشی لوکیشن و تصویر گرافیکی پرچم با تزریق مستقیم درخواست به پورت پراکسی محلی Xray
            launch {
                var retryCount = 0
                var isLocationFound = false

                while (!isLocationFound && retryCount < 2 && isActive) {
                    delay(2000) // زمان کوتاه برای اطمینان از آماده بودن Inbound
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val httpPort = SettingsManager.getHttpPort()
                            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
                            val url = URL("https://api.ip.sb/geoip")

                            val connection = url.openConnection(proxy) as HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000

                            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                                val response = connection.inputStream.bufferedReader().use { it.readText() }
                                val jsonObject = JSONObject(response)
                                val country = jsonObject.optString("country", "Unknown")
                                val code = jsonObject.optString("country_code", "").uppercase()

                                // دانلود تصویر گرافیکی پرچم از طریق همان پراکسی محلی
                                val downloadedFlag = if (code.length == 2) {
                                    try {
                                        val flagUrl = URL("https://flagcdn.com/w80/${code.lowercase()}.png")
                                        val flagConn = flagUrl.openConnection(proxy) as HttpURLConnection
                                        flagConn.connectTimeout = 4000
                                        flagConn.readTimeout = 4000
                                        if (flagConn.responseCode == HttpURLConnection.HTTP_OK) {
                                            flagConn.inputStream.use { stream ->
                                                BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                            }
                                        } else null
                                    } catch (_: Exception) {
                                        null
                                    }
                                } else null

                                Triple(country, code, downloadedFlag)
                            } else {
                                Triple("Failed", "", null)
                            }
                        }

                        val (location, code, downloadedFlag) = result
                        if (location.isNotEmpty() && location != "Failed" && location != "Unknown") {
                            locationText = "Location: $location"
                            countryCode = code
                            flagBitmap = downloadedFlag
                            isLocationFound = true
                        } else {
                            retryCount++
                        }
                    } catch (e: Exception) {
                        retryCount++
                    }
                }
            }

            while (isActive) {
                // خواندن زمان استارت از دیتابیس به صورت متن و تبدیل به عدد
                val startTimeStr = com.v2ray.ang.handler.MmkvManager.decodeSettingsString("v2m_start_time")
                val startTime = startTimeStr?.toLongOrNull() ?: 0L

                if (startTime > 0L) {
                    // محاسبه اختلاف زمان فعلی با زمان استارت
                    secondsConnected = (System.currentTimeMillis() - startTime) / 1000
                } else {
                    secondsConnected = 0L
                }
                kotlinx.coroutines.delay(1000)
            }
        } else {
            secondsConnected = 0L
            locationText = ""
            countryCode = ""
            flagBitmap = null
        }
    }

    val hours = secondsConnected / 3600
    val minutes = (secondsConnected % 3600) / 60
    val seconds = secondsConnected % 60
    val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = timeString,
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp
                )
                if (pingText.isNotEmpty()) {
                    Text(
                        text = pingText,
                        color = Color(0xFFB0B0B0),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                // نمایش نام کشور به همراه پرچم گرافیکی و انیمیشنی در جلوی آن
                if (locationText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            text = locationText,
                            color = Color(0xFFB0B0B0),
                            fontSize = 13.sp
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

    // ۱. انیمیشن چرخش و اهتزاز سه‌بعدی پرچم
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

    // ۲. حرکت شناور عمودی نرم
    val floatOffsetY by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatOffsetY"
    )

    // ۳. موج نوری متحرک روی سطح پرچم (شبیه‌سازی تابش نور روی پارچه در حال اهتزاز)
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -40f,
        targetValue = 80f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    // ۴. ضربان هاله نئونی دور پرچم
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
        // هاله نوری پشت پرچم
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

        // قاب اصلی پرچم با افکت حرکت سه‌بعدی
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

            // لایه موج نوری و سایه‌روشن اهتزاز روی پرچم
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
    val cleanCode = countryCode.trim().uppercase()
    if (cleanCode.length != 2 || !cleanCode[0].isLetter() || !cleanCode[1].isLetter()) {
        return "🌐"
    }
    val firstChar = Character.codePointAt(cleanCode, 0) - 0x41 + 0x1F1E6
    val secondChar = Character.codePointAt(cleanCode, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
}

@Composable
fun CenterMagicButton(isRunning: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // رنگ‌های پایه با تم سبز لیمویی برنامه شما
    val neonGreen = Color(0xFFC6F044)
    val brightHighlight = Color(0xFFF2FFCC)
    val midGreen = Color(0xFF689F38)
    val darkCoreCenter = if (isRunning) Color(0xFF162410) else Color(0xFF101612)
    val darkCoreEdge = Color(0xFF070A08)

    // تنظیم شدت شفافیت بر اساس وضعیت اتصال
    val activeAlpha = if (isRunning) 1f else 0.45f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {

        // ۱. هاله نوری پس‌زمینه (Ambient Outer Glow)
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

        // ۲. دایره اصلی دکمه با تک‌حلقه نئونی دور آن (بدون حلقه‌های اضافی بیرونی)
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
            // ۳. آیکون پاور و متن وضعیت
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
fun BottomConfigSelector(configName: String, configIp: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth(0.85f)
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(Color(0xFF1E1E1E))
            .clickable { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

            Column {
                Text(
                    text = configName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = configIp,
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
            }
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(24.dp)
        )
    }
}