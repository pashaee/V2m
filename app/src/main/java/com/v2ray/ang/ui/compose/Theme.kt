package com.v2ray.ang.ui.compose

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// تم مشکی مطلق (Pure Black)
private val PureBlackColor = darkColorScheme(
    primary = Color(0xFFFFAD33), // رنگ طلایی نئونی
    onPrimary = Color(0xFFFFFFFF),

    primaryContainer = Color(0xFF331800),
    onPrimaryContainer = Color(0xFFFFFFFF),

    secondary = Color(0xFFFFAD33),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF331800),
    onSecondaryContainer = Color(0xFFFFFFFF),

    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),

    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),

    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFFFFFFF),

    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF1F1F1F),

    error = Color(0xFFFF4C4C),
    errorContainer = Color(0xFF660000),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFFFFFFFF),

    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF000000),

    scrim = Color(0x99000000),
    surfaceTint = Color.Transparent,

    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF292929),
)

// رنگ‌های ثابت
val colorPing = Color(0xFF00E5FF)
val colorPingRed = Color(0xFFFF0055)
val colorConfigType = Color(0xFF00B0FF)
val colorFabActive = Color(0xFFFFAD33)
val colorFabInactiveLight = Color(0xFF333333)
val colorFabInactiveDark = Color(0xFF333333)
val dividerColorLight = Color(0xFF333333)
val dividerColorDark = Color(0xFF333333)

val toastNormalBgLight = Color.Transparent
val toastNormalBgDark = Color.Transparent
val toastSuccessBg = Color.Transparent
val toastErrorBg = Color.Transparent
val toastInfoBg = Color.Transparent
val toastIconCircleBg = Color.Transparent
val toastTextColor = Color.Transparent

object ThemeManager {
    private val _themeMode = MutableStateFlow("2")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "2")
        _themeMode.value = "2"
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, false)
        _dynamicColorEnabled.value = false
    }

    fun refresh() {
        _themeMode.value = "2"
        _dynamicColorEnabled.value = false
    }
}

@Composable
fun resolveDarkTheme(): Boolean = true

val LocalDarkTheme = compositionLocalOf { true }

@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val snackbarController = rememberAppSnackbarController()
    val colorScheme = PureBlackColor
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides true,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme
        ) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(PureBlackColor.background)
            ) {
                // کادر سبز رنگ (Snackbar) از اینجا کاملاً حذف و غیرفعال شد!
                content()
            }
        }
    }
}