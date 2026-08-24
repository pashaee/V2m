package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDivider
import kotlinx.coroutines.launch

// گزینه‌ها را در Enum نگه می‌داریم تا فایلهای دیگر برنامه ارور ندهند
enum class MainDestination(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    Subscriptions(R.drawable.ic_subscriptions_24dp, R.string.title_sub_setting),
    PerAppProxy(R.drawable.ic_per_apps_24dp, R.string.per_app_proxy_settings),
    Routing(R.drawable.ic_routing_24dp, R.string.routing_settings_title),
    UserAssets(R.drawable.ic_file_24dp, R.string.title_user_asset_setting),
    Settings(R.drawable.ic_settings_24dp, R.string.title_settings),
    Promotion(R.drawable.ic_promotion_24dp, R.string.title_pref_promotion),
    Logcat(R.drawable.ic_logcat_24dp, R.string.title_logcat),
    CheckUpdate(R.drawable.ic_check_update_24dp, R.string.update_check_for_update),
    BackupRestore(R.drawable.ic_restore_24dp, R.string.title_configuration_backup_restore),
    About(R.drawable.ic_about_24dp, R.string.title_about)
}

@Composable
fun MainDrawerContent(
    drawerState: DrawerState,
    onNavigate: (MainDestination) -> Unit,
    onCheckUpdate: () -> Unit // دستور جدید برای آپدیت مستقیم
) {
    val drawerScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.fillMaxWidth(), // عرض 100 درصد (تمام صفحه)
        drawerContainerColor = Color(0xFF000000), // پس‌زمینه مشکی خالص
        drawerShape = RectangleShape // حذف گوشه‌های گرد برای القای حس تمام‌صفحه
    ) {
        // هدر منو به همراه دکمه برگشت
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { scope.launch { drawerState.close() } }) {
                Icon(painterResource(R.drawable.ic_arrow_back_24dp), tint = Color.White, contentDescription = "Back")
            }
            Text(
                text = "Menu",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        AppDivider() // ارور در این خط بود که با حذف کلمه color برطرف شد

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(drawerScrollState)
                .padding(top = 8.dp)
        ) {

            // 1. گزینه Check Update
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.update_check_for_update), color = Color.White) },
                selected = false,
                onClick = {
                    onCheckUpdate() // اجرای چک آپدیت مستقیم
                    scope.launch { drawerState.close() } // بستن منو
                },
                icon = { Icon(painterResource(R.drawable.ic_check_update_24dp), tint = Color(0xFFB0B0B0), contentDescription = null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
            )

            // 2. گزینه About
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.title_about), color = Color.White) },
                selected = false,
                onClick = {
                    onNavigate(MainDestination.About)
                    scope.launch { drawerState.close() }
                },
                icon = { Icon(painterResource(R.drawable.ic_about_24dp), tint = Color(0xFFB0B0B0), contentDescription = null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
            )

            // 3. گزینه Contact Us (لینک مستقیم به تلگرام)
            NavigationDrawerItem(
                label = { Text("Contact Us", color = Color.White) },
                selected = false,
                onClick = {
                    uriHandler.openUri("https://t.me/v2raymasterss") // انتقال به تلگرام
                    scope.launch { drawerState.close() }
                },
                icon = { Icon(painterResource(R.drawable.ic_promotion_24dp), tint = Color(0xFF0088FF), contentDescription = null) }, // رنگ آیکون به صورت اختصاصی آبی تنظیم شد
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
            )
        }
    }
}