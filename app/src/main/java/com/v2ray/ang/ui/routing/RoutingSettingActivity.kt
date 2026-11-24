package com.v2ray.ang.ui.routing

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.ReorderableListItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale

private enum class RoutingMenuAction(val label: String) {
    RestoreDefaults("Restore V2m Defaults"),
    ImportClipboard("Import from Clipboard"),
    ImportQRCode("Import from QR Code"),
    ExportClipboard("Export to Clipboard")
}

class RoutingSettingActivity : HelperBaseComponentActivity() {
    private val viewModel: RoutingSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            if (!viewModel.ensureV2mDefaultsIfFirstLaunch()) {
                viewModel.reload()
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        RoutingSettingScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onAddRule = {
                startActivity(Intent(this, RoutingEditActivity::class.java))
            },
            onEditRule = { position ->
                startActivity(
                    Intent(this, RoutingEditActivity::class.java).putExtra("position", position)
                )
            },
            onDomainStrategySelected = { value ->
                viewModel.updateDomainStrategy(value)
            },
            onRestoreDefaults = { restoreV2mDefaults() },
            onImportClipboard = { importFromClipboard() },
            onImportQRcode = { importQRcode() },
            onExportClipboard = { export2Clipboard() }
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.reload()
    }

    private fun restoreV2mDefaults() {
        lifecycleScope.launch {
            if (viewModel.restoreV2mDefaults()) {
                toastSuccess(R.string.toast_success)
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    private fun importFromClipboard() {
        val clipboard = try {
            Utils.getClipboard(this)
        } catch (_: Exception) {
            toastError(R.string.toast_failure)
            return
        }
        if (clipboard.isBlank()) {
            toastError(R.string.toast_failure)
            return
        }
        lifecycleScope.launch {
            if (viewModel.importRulesets(clipboard)) {
                toastSuccess(R.string.toast_success)
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult.isNullOrBlank()) return@launchQRCodeScanner
            lifecycleScope.launch {
                if (viewModel.importRulesets(scanResult)) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun export2Clipboard() {
        val exported = viewModel.exportRulesetsJson()
        if (exported.isNullOrEmpty()) {
            toastError(R.string.toast_failure)
        } else {
            Utils.setClipboard(this, exported)
            toastSuccess(R.string.toast_success)
        }
    }
}

@Composable
fun RoutingSettingScreen(
    viewModel: RoutingSettingsViewModel,
    onBackClick: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (Int) -> Unit,
    onDomainStrategySelected: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportQRcode: () -> Unit,
    onExportClipboard: () -> Unit
) {
    val rulesets by viewModel.rulesetsFlow.collectAsStateWithLifecycle()
    val domainStrategy by viewModel.domainStrategyFlow.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }

    val domainStrategies = stringArrayResource(R.array.routing_domain_strategy).toList()
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = rulesets.indexOfFirst { it.id == from.key }
        val toIndex = rulesets.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            viewModel.move(fromIndex, toIndex)
        }
    }

    Scaffold(
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.routing_settings_title),
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = onAddRule) {
                        Icon(
                            painterResource(R.drawable.ic_add_24dp),
                            contentDescription = stringResource(R.string.acc_add_rule)
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert_24dp),
                                contentDescription = stringResource(R.string.acc_more)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = Color(0xFF1E1E1E)
                        ) {
                            RoutingMenuAction.entries.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label, color = Color.White) },
                                    onClick = {
                                        showMenu = false
                                        when (action) {
                                            RoutingMenuAction.RestoreDefaults -> onRestoreDefaults()
                                            RoutingMenuAction.ImportClipboard -> onImportClipboard()
                                            RoutingMenuAction.ImportQRCode -> onImportQRcode()
                                            RoutingMenuAction.ExportClipboard -> onExportClipboard()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScrollbar(lazyListState),
            contentPadding = NavigationBarsBottomPadding()
        ) {
            item(key = "domain_strategy") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(bottom = 8.dp)
                ) {
                    SettingsListItem(
                        title = stringResource(R.string.routing_settings_domain_strategy),
                        entries = domainStrategies,
                        values = domainStrategies,
                        selectedValue = domainStrategy,
                        onSelected = onDomainStrategySelected
                    )
                }
            }

            item(key = "rules_header") {
                Text(
                    text = stringResource(R.string.routing_settings_rule_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
                )
            }

            itemsIndexed(
                items = rulesets,
                key = { _, ruleset -> ruleset.id }
            ) { index, ruleset ->
                ReorderableItem(reorderableState, key = ruleset.id) { isDragging ->
                    ReorderableListItem(
                        scope = this,
                        isDragging = isDragging
                    ) {
                        RoutingRulesetItem(
                            ruleset = ruleset,
                            onItemClick = { onEditRule(index) },
                            onEnabledChange = { checked ->
                                viewModel.toggleEnabled(ruleset.id, checked)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutingRulesetItem(
    ruleset: RulesetItem,
    onItemClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    val limeGreen = Color(0xFFC6F044)
    val outboundTag = ruleset.outboundTag.orEmpty().trim()
    val badgeColor = when (outboundTag.lowercase(Locale.ROOT)) {
        "direct" -> limeGreen
        "block" -> Color(0xFFFF5555)
        else -> Color(0xFF4FC3F7)
    }

    val ruleSummary = remember(ruleset.domain, ruleset.ip, ruleset.process, ruleset.port) {
        buildList {
            if (!ruleset.domain.isNullOrEmpty()) add(ruleset.domain!!.joinToString(", "))
            if (!ruleset.ip.isNullOrEmpty()) add(ruleset.ip!!.joinToString(", "))
            if (!ruleset.process.isNullOrEmpty()) add(ruleset.process!!.joinToString(", "))
            if (!ruleset.port.isNullOrEmpty()) add("port: ${ruleset.port}")
        }.joinToString(" • ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onItemClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ruleset.remarks.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (ruleset.locked == true) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_lock_24dp),
                        contentDescription = stringResource(R.string.acc_locked),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF888888)
                    )
                }
            }

            if (ruleSummary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ruleSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF888888),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (outboundTag.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF333333))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = outboundTag.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Switch(
                checked = ruleset.enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = limeGreen,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0xFF333333),
                    checkedBorderColor = Color.Transparent,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}