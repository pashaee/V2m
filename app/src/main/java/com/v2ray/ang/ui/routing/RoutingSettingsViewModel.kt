package com.v2ray.ang.ui.routing

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

class RoutingSettingsViewModel(application: Application) : BaseViewModel(application) {

    private val rulesets: MutableList<RulesetItem> = mutableListOf()

    private val _rulesetsFlow = MutableStateFlow<List<RulesetItem>>(emptyList())
    val rulesetsFlow: StateFlow<List<RulesetItem>> = _rulesetsFlow.asStateFlow()

    private val _domainStrategyFlow = MutableStateFlow(DEFAULT_DOMAIN_STRATEGY)
    val domainStrategyFlow: StateFlow<String> = _domainStrategyFlow.asStateFlow()

    fun getAll(): List<RulesetItem> = rulesets.toList()

    fun reload() {
        val loaded = MmkvManager.decodeRoutingRulesets()?.toMutableList() ?: mutableListOf()
        val seenIds = HashSet<String>(loaded.size)
        var needsSave = false

        loaded.forEach { item ->
            if (item.id.isBlank() || !seenIds.add(item.id)) {
                val uniqueId = UUID.randomUUID().toString()
                item.id = uniqueId
                seenIds.add(uniqueId)
                needsSave = true
            }
        }

        if (needsSave) {
            MmkvManager.encodeRoutingRulesets(loaded)
        }

        rulesets.clear()
        rulesets.addAll(loaded)
        _rulesetsFlow.value = rulesets.toList()

        val savedStrategy = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DOMAIN_STRATEGY
        _domainStrategyFlow.value = savedStrategy
    }

    fun updateDomainStrategy(value: String) {
        val cleanValue = value.takeIf { it.isNotBlank() } ?: DEFAULT_DOMAIN_STRATEGY
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, cleanValue)
        _domainStrategyFlow.value = cleanValue
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        val index = rulesets.indexOfFirst { it.id == id }
        if (index in rulesets.indices) {
            val updated = rulesets[index].copy(enabled = enabled)
            rulesets[index] = updated
            SettingsManager.saveRoutingRuleset(index, updated)
            _rulesetsFlow.value = rulesets.toList()
        }
    }

    fun update(position: Int, item: RulesetItem) {
        if (position in rulesets.indices) {
            rulesets[position] = item
            SettingsManager.saveRoutingRuleset(position, item)
            _rulesetsFlow.value = rulesets.toList()
        }
    }

    fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in rulesets.indices || toPosition !in rulesets.indices || fromPosition == toPosition) {
            return
        }
        if (rulesets.moveItem(fromPosition, toPosition)) {
            MmkvManager.encodeRoutingRulesets(rulesets)
            _rulesetsFlow.value = rulesets.toList()
        }
    }

    suspend fun ensureV2mDefaultsIfFirstLaunch(): Boolean {
        if (MmkvManager.decodeSettingsBool(KEY_V2M_ROUTING_FORCE_V1, false)) {
            return false
        }
        val success = restoreV2mDefaults()
        if (success) {
            MmkvManager.encodeSettings(KEY_V2M_ROUTING_FORCE_V1, true)
        }
        return success
    }

    suspend fun restoreV2mDefaults(): Boolean {
        val success = withContext(Dispatchers.IO) {
            try {
                SettingsManager.resetRoutingRulesets(buildV2mDefaultsJson())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to inject V2m routing defaults", e)
                false
            }
        }
        if (success) {
            updateDomainStrategy(DEFAULT_DOMAIN_STRATEGY)
            reload()
        }
        return success
    }

    suspend fun importRulesets(rawJson: String?): Boolean {
        val clean = rawJson?.trim()
        if (clean.isNullOrEmpty()) return false

        val success = withContext(Dispatchers.IO) {
            try {
                SettingsManager.resetRoutingRulesets(clean)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import routing rulesets", e)
                false
            }
        }
        if (success) {
            reload()
        }
        return success
    }

    fun exportRulesetsJson(): String? {
        val list = MmkvManager.decodeRoutingRulesets()
        if (list.isNullOrEmpty()) return null
        return JsonUtil.toJson(list)
    }

    private fun buildV2mDefaultsJson(): String {
        return """
        [
          {
            "id": "${Utils.getUuid()}",
            "remarks": "Iran IP Direct",
            "ip": ["geoip:ir"],
            "outboundTag": "direct",
            "enabled": true
          },
          {
            "id": "${Utils.getUuid()}",
            "remarks": "China IP Direct",
            "ip": ["geoip:cn"],
            "outboundTag": "direct",
            "enabled": true
          },
          {
            "id": "${Utils.getUuid()}",
            "remarks": "Russia IP Direct",
            "ip": ["geoip:ru"],
            "outboundTag": "direct",
            "enabled": true
          },
          {
            "id": "${Utils.getUuid()}",
            "remarks": "Block Porn",
            "domain": ["geosite:category-porn"],
            "outboundTag": "block",
            "enabled": false
          },
          {
            "id": "${Utils.getUuid()}",
            "remarks": "Block Ads",
            "domain": ["geosite:category-ads-all"],
            "outboundTag": "block",
            "enabled": true
          }
        ]
        """.trimIndent()
    }

    companion object {
        const val DEFAULT_DOMAIN_STRATEGY = "IPIfNonMatch"
        private const val KEY_V2M_ROUTING_FORCE_V1 = "v2m_routing_setup_force_v1"
    }
}