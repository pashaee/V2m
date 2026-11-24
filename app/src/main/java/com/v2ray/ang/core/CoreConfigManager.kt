package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils

object CoreConfigManager {
    @Volatile
    private var initConfigCache: String? = null

    @Volatile
    private var initConfigCacheWithTun: String? = null

    private val CUSTOM_VLESS_NETWORKS = setOf("ws", "xhttp", "httpupgrade")
    private val IPV4_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    private const val CUSTOM_EDGE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

    private const val CUSTOM_TLS_CIPHER_SUITES =
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:" +
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:" +
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:" +
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:" +
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:" +
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:" +
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:" +
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:" +
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:" +
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

    //region get config function

    /**
     * Build the runtime configuration for normal startup.
     */
    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        try {
            val serverConfig = MmkvManager.decodeServerConfig(guid)
            if (isCustomVlessTlsProfile(serverConfig)) {
                buildCustomVlessTlsConfig(serverConfig!!, isSpeedtest = false)?.let { customJson ->
                    return ConfigResult(
                        status = true,
                        guid = guid,
                        content = customJson
                    )
                }
            }

            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(
                    status = false,
                    guid = guid,
                    errorMessage = "Failed to build config context"
                )
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            return toConfigResult(configContext, buildUnifiedConfig(configContext))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build a lightweight configuration for latency testing.
     * The core flow is reused, then non-essential sections are removed.
     */
    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        try {
            val serverConfig = MmkvManager.decodeServerConfig(guid)
            if (isCustomVlessTlsProfile(serverConfig)) {
                buildCustomVlessTlsConfig(serverConfig!!, isSpeedtest = true)?.let { customJson ->
                    return ConfigResult(
                        status = true,
                        guid = guid,
                        content = customJson
                    )
                }
            }

            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(
                    status = false,
                    guid = guid,
                    errorMessage = "Failed to build config context"
                )
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            val v2rayConfig = buildUnifiedConfig(configContext)
            postProcessForSpeedtest(v2rayConfig)

            return toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Check whether a profile matches the custom VLESS + TLS (ws, xhttp, httpupgrade) flow.
     */
    private fun isCustomVlessTlsProfile(serverConfig: ProfileItem?): Boolean {
        if (serverConfig == null) return false
        val network = serverConfig.network?.trim()?.lowercase() ?: return false
        return serverConfig.configType == EConfigType.VLESS &&
                serverConfig.security.equals(AppConfig.TLS, ignoreCase = true) &&
                network in CUSTOM_VLESS_NETWORKS
    }

    /**
     * Build a type-safe, JSON-escaped custom VLESS + TLS config for both normal startup and speedtest.
     */
    private fun buildCustomVlessTlsConfig(serverConfig: ProfileItem, isSpeedtest: Boolean): String? {
        val dynUuid = serverConfig.password?.trim().orEmpty()
        val dynAddress = serverConfig.server?.trim().orEmpty()
        if (dynUuid.isEmpty() || dynAddress.isEmpty()) {
            return null
        }

        val network = serverConfig.network?.trim()?.lowercase() ?: "ws"
        val dynPort = serverConfig.serverPort?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
        val dynHost = serverConfig.host?.trim()?.takeIf { it.isNotEmpty() }
            ?: serverConfig.sni?.trim()?.takeIf { it.isNotEmpty() }
            ?: dynAddress
        val dynSni = serverConfig.sni?.trim()?.takeIf { it.isNotEmpty() } ?: dynHost
        val dynPath = serverConfig.path?.takeIf { it.isNotEmpty() } ?: "/"
        val logLevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"

        val root = JsonObject()
        root.addProperty("remarks", serverConfig.remarks.ifEmpty { "کانفیگ اختصاصی شما" })
        root.add("log", JsonObject().apply {
            addProperty("loglevel", logLevel)
        })

        // Resolve outbound domain upfront to avoid DNS bootstrap deadlocks
        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true
        val shouldResolveOutbound = !isSpeedtest &&
                !isIpAddress(dynAddress) &&
                MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") == "1"
        val resolvedServerIps = if (shouldResolveOutbound) {
            HttpUtil.resolveHostToIP(dynAddress, preferIpv6)?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        if (!isSpeedtest) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
                root.add("stats", JsonObject())
                root.add("policy", JsonObject().apply {
                    add("system", JsonObject().apply {
                        addProperty("statsOutboundUplink", true)
                        addProperty("statsOutboundDownlink", true)
                    })
                })
            }

            // DNS configuration (routed exclusively through proxy)
            val hostsObj = JsonObject().apply {
                add("cloudflare-dns.com", JsonArray().apply {
                    add("1.1.1.1")
                    add("1.0.0.1")
                })
                if (!resolvedServerIps.isNullOrEmpty()) {
                    if (resolvedServerIps.size == 1) {
                        addProperty(dynAddress, resolvedServerIps[0])
                    } else {
                        add(dynAddress, JsonArray().apply {
                            resolvedServerIps.forEach { add(it) }
                        })
                    }
                }
            }

            root.add("dns", JsonObject().apply {
                add("hosts", hostsObj)
                add("servers", JsonArray().apply {
                    add("https://1.1.1.1/dns-query")
                })
                addProperty("queryStrategy", if (preferIpv6) "UseIP" else "UseIPv4")
                addProperty("tag", "dns-module")
            })

            root.add("inbounds", buildCustomInbounds())
        } else {
            root.add("inbounds", JsonArray())
        }

        val proxyOutbound = buildCustomProxyOutbound(
            dynAddress = dynAddress,
            dynPort = dynPort,
            dynUuid = dynUuid,
            network = network,
            dynSni = dynSni,
            dynHost = dynHost,
            dynPath = dynPath,
            serverConfig = serverConfig,
            hasResolvedHost = !resolvedServerIps.isNullOrEmpty(),
            preferIpv6 = preferIpv6
        )

        val outboundsArray = JsonArray().apply {
            add(proxyOutbound)
            if (!isSpeedtest) {
                add(JsonObject().apply {
                    addProperty("tag", "dns-out")
                    addProperty("protocol", "dns")
                })
                add(JsonObject().apply {
                    addProperty("tag", AppConfig.TAG_DIRECT)
                    addProperty("protocol", "freedom")
                    add("streamSettings", JsonObject().apply {
                        add("sockopt", JsonObject().apply {
                            addProperty("domainStrategy", "UseIP")
                        })
                    })
                })
                add(JsonObject().apply {
                    addProperty("tag", AppConfig.TAG_BLOCKED)
                    addProperty("protocol", "blackhole")
                })
            }
        }
        root.add("outbounds", outboundsArray)

        if (!isSpeedtest) {
            root.add("routing", buildCustomRouting())
        } else {
            root.add("routing", JsonObject().apply {
                add("rules", JsonArray())
            })
        }

        return JsonUtil.toJsonPretty(root)
    }

    private fun buildCustomInbounds(): JsonArray {
        val localPort = SettingsManager.getSocksPort()
        val httpPort = SettingsManager.getHttpPort()
        val vpnMtu = SettingsManager.getVpnMtu()
        val listenAddr = if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) == true) {
            "0.0.0.0"
        } else {
            AppConfig.LOOPBACK
        }
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val enableUdp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true)

        val standardSniffing = {
            JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply {
                    add("http")
                    add("tls")
                    add("quic")
                })
                addProperty("routeOnly", false)
            }
        }

        val inbounds = JsonArray()

        inbounds.add(JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("port", localPort)
            addProperty("listen", listenAddr)
            addProperty("protocol", "mixed")
            add("sniffing", standardSniffing())
            add("settings", JsonObject().apply {
                if (socksUsername != null && socksPassword != null) {
                    addProperty("auth", "password")
                    add("accounts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("user", socksUsername)
                            addProperty("pass", socksPassword)
                        })
                    })
                } else {
                    addProperty("auth", "noauth")
                }
                addProperty("udp", enableUdp)
                addProperty("allowTransparent", false)
            })
        })

        if (httpPort != localPort) {
            inbounds.add(JsonObject().apply {
                addProperty("tag", "http-local")
                addProperty("port", httpPort)
                addProperty("listen", AppConfig.LOOPBACK)
                addProperty("protocol", "http")
                add("sniffing", standardSniffing())
                add("settings", JsonObject().apply {
                    addProperty("allowTransparent", false)
                })
            })
        }

        if (needTun()) {
            inbounds.add(JsonObject().apply {
                addProperty("tag", "tun")
                addProperty("protocol", "tun")
                add("settings", JsonObject().apply {
                    addProperty("mtu", vpnMtu)
                })
                add("sniffing", standardSniffing())
            })
        }

        return inbounds
    }

    private fun buildCustomProxyOutbound(
        dynAddress: String,
        dynPort: Int,
        dynUuid: String,
        network: String,
        dynSni: String,
        dynHost: String,
        dynPath: String,
        serverConfig: ProfileItem,
        hasResolvedHost: Boolean,
        preferIpv6: Boolean
    ): JsonObject {
        val tlsSettings = JsonObject().apply {
            addProperty("serverName", dynSni)
            add("alpn", JsonArray().apply {
                if (network == "xhttp") {
                    add("h2")
                }
                add("http/1.1")
            })
            addProperty("enableSessionResumption", true)
            // Omitting uTLS fingerprint ensures Xray uses Go TLS so custom cipherSuites take effect reliably
            addProperty("cipherSuites", CUSTOM_TLS_CIPHER_SUITES)
        }

        val streamSettings = JsonObject().apply {
            addProperty("network", network)
            addProperty("security", "tls")
            add("tlsSettings", tlsSettings)

            when (network) {
                "ws" -> {
                    add("wsSettings", JsonObject().apply {
                        addProperty("path", dynPath)
                        add("headers", JsonObject().apply {
                            addProperty("Host", dynHost)
                            addProperty("User-Agent", CUSTOM_EDGE_USER_AGENT)
                        })
                    })
                }

                "httpupgrade" -> {
                    add("httpupgradeSettings", JsonObject().apply {
                        addProperty("path", dynPath)
                        addProperty("host", dynHost)
                    })
                }

                "xhttp" -> {
                    val xhttpMode = serverConfig.xhttpMode?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"
                    val extraObj = serverConfig.xhttpExtra?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { JsonUtil.parseString(it) }
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject

                    add("xhttpSettings", JsonObject().apply {
                        addProperty("path", dynPath)
                        addProperty("host", dynHost)
                        addProperty("mode", xhttpMode)
                        if (extraObj != null) {
                            add("extra", extraObj)
                        }
                    })
                }
            }

            if (hasResolvedHost) {
                add("sockopt", JsonObject().apply {
                    addProperty("domainStrategy", "UseIP")
                    add("happyEyeballs", JsonObject().apply {
                        addProperty("prioritizeIPv6", preferIpv6)
                        addProperty("interleave", 2)
                    })
                })
            }
        }

        return JsonObject().apply {
            addProperty("tag", AppConfig.TAG_PROXY)
            addProperty("protocol", "vless")
            add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", dynAddress)
                        addProperty("port", dynPort)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", dynUuid)
                                addProperty("email", "t@t.tt")
                                addProperty("encryption", "none")
                            })
                        })
                    })
                })
            })
            add("streamSettings", streamSettings)
        }
    }

    /**
     * Exact original routing rules with all DNS traffic routed through proxy.
     */
    private fun buildCustomRouting(): JsonObject {
        val rules = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply {
                    add("socks")
                    add("tun")
                    add("http-local")
                })
                addProperty("port", 53)
                addProperty("outboundTag", AppConfig.TAG_PROXY)
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply {
                    add("dns-module")
                })
                addProperty("outboundTag", AppConfig.TAG_PROXY)
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                add("ip", JsonArray().apply {
                    add("1.1.1.1")
                    add("1.0.0.1")
                })
                addProperty("outboundTag", AppConfig.TAG_PROXY)
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "udp")
                addProperty("port", 443)
                addProperty("outboundTag", AppConfig.TAG_BLOCKED)
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", AppConfig.TAG_DIRECT)
                add("protocol", JsonArray().apply {
                    add("bittorrent")
                })
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", AppConfig.TAG_BLOCKED)
                add("domain", JsonArray().apply {
                    add("geosite:category-ads-all")
                })
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", AppConfig.TAG_DIRECT)
                add("domain", JsonArray().apply {
                    add("geosite:ir")
                    add("domain:.ir")
                })
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", AppConfig.TAG_DIRECT)
                add("ip", JsonArray().apply {
                    add("geoip:ir")
                })
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", AppConfig.TAG_DIRECT)
                add("ip", JsonArray().apply {
                    add("geoip:private")
                })
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", AppConfig.TAG_DIRECT)
                add("domain", JsonArray().apply {
                    add("geosite:private")
                })
            })

            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "tcp,udp")
                addProperty("outboundTag", AppConfig.TAG_PROXY)
            })
        }

        return JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            add("rules", rules)
        }
    }

    private fun isIpAddress(host: String): Boolean {
        return IPV4_REGEX.matches(host) || host.contains(":")
    }

    /**
     * Build configuration for custom profiles.
     */
    private fun buildV2rayCustomConfig(configContext: CoreConfigContext): ConfigResult {
        val context = configContext.context
        val raw = MmkvManager.decodeServerRaw(configContext.guid)
            ?: return ConfigResult(
                status = false,
                guid = configContext.guid,
                errorMessage = "Failed to build config context, config is empty"
            )
        val result = ConfigResult(true, configContext.guid, raw)

        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return result

        // Inject or clean up traffic statistics configuration without wiping unrelated policy levels
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            if (!json.has("stats")) {
                json.add("stats", JsonObject())
            }
            val policyObj = json.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { json.add("policy", it) }
            val systemObj = policyObj.get("system")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { policyObj.add("system", it) }
            systemObj.addProperty("statsOutboundUplink", true)
            systemObj.addProperty("statsOutboundDownlink", true)
        } else {
            json.remove("stats")
            json.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject?.let { policyObj ->
                policyObj.get("system")?.takeIf { it.isJsonObject }?.asJsonObject?.let { systemObj ->
                    systemObj.remove("statsOutboundUplink")
                    systemObj.remove("statsOutboundDownlink")
                    if (systemObj.size() == 0) {
                        policyObj.remove("system")
                    }
                }
                if (policyObj.size() == 0) {
                    json.remove("policy")
                }
            }
        }

        if (!needTun()) {
            return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
        }

        // Check whether package names need to be replaced with UIDs
        if (SettingsManager.canUseProcessRouting()) {
            val rulesJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: JsonArray()

            for (elem in rulesJson) {
                val rule = elem.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val process = rule.get("process")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val packages = process.mapNotNull {
                    it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }.takeIf { it.isNotEmpty() } ?: continue
                val uids = PackageUidResolver.packageNamesToUids(context, packages).takeIf { it.isNotEmpty() } ?: continue

                rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
            }
        }

        // Check if tun inbound exists
        val inboundsJson = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { json.add("inbounds", it) }
        val tunNotExists = inboundsJson.none { elem ->
            elem.isJsonObject && elem.asJsonObject.get("protocol")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString == "tun"
        }

        if (tunNotExists) {
            val templateConfig = initV2rayConfig(configContext)
            templateConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { inboundTun ->
                inboundTun.settings?.mtu = SettingsManager.getVpnMtu()
                inboundsJson.add(JsonUtil.parseString(JsonUtil.toJson(inboundTun)))
            }
        }

        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
    }

    /**
     * Build one unified configuration for every non-custom profile type.
     */
    private fun buildUnifiedConfig(configContext: CoreConfigContext): V2rayConfig {
        require(configContext.resolvedOutbounds.isNotEmpty()) { "resolvedOutbounds must not be empty for a non-CUSTOM context" }
        val primaryResolvedOutbound = configContext.resolvedOutbounds.first()

        val v2rayConfig = initV2rayConfig(configContext)
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.remarks = primaryResolvedOutbound.profile.remarks

        configureInbounds(v2rayConfig)

        if (v2rayConfig.outbounds.isNotEmpty()) {
            v2rayConfig.outbounds.removeAt(0)
        }
        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        val policyGroupBalancerTags = mutableMapOf<String, String>()
        val balancerStrategies = mutableListOf<BalancerStrategy>()

        configContext.resolvedOutbounds.forEachIndexed { index, spec ->
            buildOutbounds(
                resolvedOutbound = spec,
                prepend = index == 0,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }

        configureRouting(configContext, v2rayConfig, policyGroupBalancerTags)
        configureFakeDns(v2rayConfig)
        configureDns(configContext, v2rayConfig, policyGroupBalancerTags)
        configureLocalDns(configContext, v2rayConfig)
        configureRootModeDns(v2rayConfig)

        if (primaryResolvedOutbound.resolvedType == CoreResolvedType.POLICYGROUP) {
            if (v2rayConfig.routing.domainStrategy == "IPIfNonMatch") {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        ip = arrayListOf("0.0.0.0/0", "::/0"),
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp,udp",
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            }
        }

        applyObservability(v2rayConfig, balancerStrategies)
        applySpeedDisabled(v2rayConfig)
        resolveOutboundDomainsToHosts(v2rayConfig)

        return v2rayConfig
    }

    /**
     * Convert one analyzed outbound entry into concrete outbounds and register
     * them to the runtime configuration.
     */
    private fun buildOutbounds(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        if (resolvedOutbound.tag in existingTags) {
            LogUtil.w(AppConfig.TAG, "Resolved outbound tag '${resolvedOutbound.tag}' already exists, skipping duplicated entry")
            return
        }

        when (resolvedOutbound.resolvedType) {
            CoreResolvedType.NORMAL -> handleNormalResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.PROXYCHAIN -> handleProxyChainResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.POLICYGROUP -> handlePolicyGroupResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }
    }

    /**
     * Build and insert a single-node outbound entry.
     */
    private fun handleNormalResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val profile = resolvedOutbound.resolvedProfiles.firstOrNull() ?: run {
            LogUtil.w(AppConfig.TAG, "NORMAL resolved outbound '${resolvedOutbound.tag}' has empty resolvedProfiles, skipping")
            return
        }
        val outbound = convertProfile2Outbound(profile) ?: run {
            LogUtil.w(AppConfig.TAG, "Could not convert NORMAL resolved outbound '${resolvedOutbound.tag}' profile to outbound, skipping")
            return
        }
        outbound.tag = resolvedOutbound.tag
        if (prepend) {
            v2rayConfig.outbounds.add(0, outbound)
        } else {
            v2rayConfig.outbounds.add(outbound)
        }
        existingTags.add(resolvedOutbound.tag)
    }

    /**
     * Build and insert a multi-hop chain entry.
     */
    private fun handleProxyChainResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val chainOutbounds = resolvedOutbound.resolvedProfiles
            .mapNotNull { convertProfile2Outbound(it) }
            .toMutableList()
        if (chainOutbounds.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has no valid profiles, skipping")
            return
        }
        if (chainOutbounds.size == 1) {
            val outbound = chainOutbounds.first()
            outbound.tag = resolvedOutbound.tag
            if (prepend) {
                v2rayConfig.outbounds.add(0, outbound)
            } else {
                v2rayConfig.outbounds.add(outbound)
            }
            existingTags.add(resolvedOutbound.tag)
            return
        }

        val chainTags = chainOutbounds.mapIndexed { index, _ ->
            if (index == 0) {
                resolvedOutbound.tag
            } else {
                "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-$index"
            }
        }
        if (chainTags.any { it in existingTags }) {
            LogUtil.w(
                AppConfig.TAG,
                "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has colliding hop tags, skipping"
            )
            return
        }

        chainOutbounds.forEachIndexed { index, outbound ->
            outbound.tag = chainTags[index]
        }
        for (i in 0 until chainOutbounds.size - 1) {
            chainOutbounds[i].ensureSockopt().dialerProxy = chainOutbounds[i + 1].tag
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, chainOutbounds)
        } else {
            v2rayConfig.outbounds.addAll(chainOutbounds)
        }
        chainOutbounds.forEach { existingTags.add(it.tag) }
    }

    /**
     * Build and insert a policy-group entry and its balancer metadata.
     */
    private fun handlePolicyGroupResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        val memberPairs = resolvedOutbound.resolvedProfiles.mapNotNull { profile ->
            convertProfile2Outbound(profile)?.let { ob -> ob to profile }
        }
        if (memberPairs.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' has no valid member outbounds, skipping")
            return
        }

        val memberTagPrefix = "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-"
        val membersToAdd = mutableListOf<V2rayConfig.OutboundBean>()
        memberPairs.forEachIndexed { index, (outbound, profile) ->
            val memberTag = "$memberTagPrefix${index + 1}-${profile.remarks.trim()}"
            if (memberTag in existingTags) {
                return@forEachIndexed
            }
            outbound.tag = memberTag
            membersToAdd.add(outbound)
            existingTags.add(memberTag)
        }

        if (membersToAdd.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' produced no unique member tags, skipping"
            )
            return
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, membersToAdd)
        } else {
            v2rayConfig.outbounds.addAll(membersToAdd)
        }

        val balancerTag = if (resolvedOutbound.tag == AppConfig.TAG_PROXY) {
            AppConfig.TAG_BALANCER
        } else {
            "${AppConfig.TAG_BALANCER_PRE}-${resolvedOutbound.tag}"
        }
        val strategyType = BalancerStrategyType.from(resolvedOutbound.profile.policyGroupType)
        val fallbackTag = if (strategyType.supportsObservatory && resolvedOutbound.profile.policyGroupTestOutbounds != false) {
            resolvedOutbound.profile.policyGroupFallbackTag
                ?.takeIf { it.isNotEmpty() && it != AppConfig.TAG_PROXY }
                ?: membersToAdd.first().tag
        } else null
        val strategy = buildBalancerStrategy(
            strategyType = strategyType,
            selector = listOf(memberTagPrefix),
            balancerTag = balancerTag,
            fallbackTag = fallbackTag,
        )
        val existingBalancers = v2rayConfig.routing.balancers?.toMutableList() ?: mutableListOf()
        if (existingBalancers.none { it.tag == balancerTag }) {
            existingBalancers.add(strategy.balancer)
            v2rayConfig.routing.balancers = existingBalancers
        }
        balancerStrategies.add(strategy)
        policyGroupBalancerTags[resolvedOutbound.tag] = balancerTag
    }

    /**
     * Trim runtime sections that are not needed for latency testing.
     */
    private fun postProcessForSpeedtest(v2rayConfig: V2rayConfig) {
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.inbounds.clear()
        v2rayConfig.routing.rules.clear()
        v2rayConfig.dns = null
        v2rayConfig.fakedns = null
        v2rayConfig.stats = null
        v2rayConfig.policy = null
        v2rayConfig.outbounds.forEach { key -> key.mux = null }
    }

    /**
     * Serialize a runtime configuration into a standard result object.
     */
    private fun toConfigResult(configContext: CoreConfigContext, v2rayConfig: V2rayConfig): ConfigResult {
        return ConfigResult(
            status = true,
            guid = configContext.guid,
            content = JsonUtil.toJsonPretty(v2rayConfig) ?: ""
        )
    }

    /**
     * Load the base template from cache or assets and parse it.
     */
    private fun initV2rayConfig(configContext: CoreConfigContext): V2rayConfig {
        val context = configContext.context
        val assets: String
        if (needTun()) {
            assets = initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config_with_tun.json")
            }
            initConfigCacheWithTun = assets
        } else {
            assets = initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config.json")
            }
            initConfigCache = assets
        }
        return JsonUtil.fromJson(assets, V2rayConfig::class.java)
            ?: error("Failed to parse config template")
    }

    //endregion

    //region some sub function

    private fun needTun(): Boolean {
        return SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()
    }

    /**
     * Configure inbound listeners and related runtime options.
     */
    private fun configureInbounds(v2rayConfig: V2rayConfig) {
        val inbound1 = v2rayConfig.inbounds.firstOrNull() ?: return

        val vpn = SettingsManager.isVpnMode()
        val useHev = SettingsManager.isUsingHevTun()
        val forcedByHev = vpn && useHev
        val forcedBySocksRoot = SettingsManager.isRootMode()
                || MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)

        val enableLocalProxy = forcedByHev || forcedBySocksRoot || MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)

        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        if (inbound1.settings == null) {
            inbound1.settings = V2rayConfig.InboundBean.InSettingsBean()
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) != true) {
            inbound1.listen = AppConfig.LOOPBACK
        }
        inbound1.port = socksPort
        inbound1.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        if (socksUsername != null && socksPassword != null) {
            inbound1.settings?.auth = "password"
            inbound1.settings?.accounts = listOf(
                V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(
                    user = socksUsername,
                    pass = socksPassword
                )
            )
        } else {
            inbound1.settings?.auth = "noauth"
            inbound1.settings?.accounts = null
        }
        val fakedns = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        val sniffAllTlsAndHttp =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false
        inbound1.sniffing?.enabled = fakedns || sniffAllTlsAndHttp
        inbound1.sniffing?.routeOnly =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
        if (!sniffAllTlsAndHttp) {
            inbound1.sniffing?.destOverride?.clear()
        }
        if (fakedns) {
            inbound1.sniffing?.destOverride?.add("fakedns")
        }

        if (!Utils.isXray()) {
            val inbound2 = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java)
                ?: error("Failed to clone inbound template")
            inbound2.tag = EConfigType.HTTP.name.lowercase()
            inbound2.port = SettingsManager.getHttpPort()
            inbound2.protocol = EConfigType.HTTP.name.lowercase()
            inbound2.settings?.auth = null
            inbound2.settings?.udp = null
            v2rayConfig.inbounds.add(inbound2)
        }

        if (!enableLocalProxy) {
            v2rayConfig.inbounds.removeIf { it.protocol == "socks" || it.protocol == "http" }
        }

        if (needTun()) {
            val inboundTun = v2rayConfig.inbounds.firstOrNull { e -> e.tag == "tun" }
            inboundTun?.settings?.mtu = SettingsManager.getVpnMtu()
            inboundTun?.sniffing = inbound1.sniffing
        }
    }

    /**
     * Enable fake DNS when local DNS and fake DNS are both enabled.
     */
    private fun configureFakeDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        ) {
            v2rayConfig.fakedns = listOf(V2rayConfig.FakednsBean())
        }
    }

    /**
     * Configure local DNS inbounds, outbounds, and routing rules.
     */
    private fun configureLocalDns(configContext: CoreConfigContext, v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) != true) {
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) {
            val geositeCn = arrayListOf(AppConfig.GEOSITE_CN)
            val routingDomains = configContext.routingDomainRules
                .asSequence()
                .filter { it.outboundTag != AppConfig.TAG_BLOCKED }
                .flatMap { it.domain.asSequence() }
                .distinct()
                .toList()
            val finalDomain = geositeCn + routingDomains
            v2rayConfig.dns?.servers?.add(
                0,
                V2rayConfig.DnsBean.ServersBean(
                    address = "fakedns",
                    domains = finalDomain
                )
            )
        }

        if (SettingsManager.isVpnMode()) {
            if (SettingsManager.isUsingHevTun()) {
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("socks"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("tun"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            }
        }

        if (v2rayConfig.outbounds.none { e -> e.protocol == "dns" && e.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * Hijack port-53 in root mode to the core's DNS module.
     */
    private fun configureRootModeDns(v2rayConfig: V2rayConfig) {
        if (!SettingsManager.isRootMode()) return

        if (v2rayConfig.routing.rules.none { it.outboundTag == "dns-out" && it.port == "53" }) {
            v2rayConfig.routing.rules.add(
                0,
                V2rayConfig.RoutingBean.RulesBean(
                    inboundTag = arrayListOf("socks"),
                    outboundTag = "dns-out",
                    port = "53",
                )
            )
        }
        if (v2rayConfig.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * Remove speed-test runtime sections when the feature is disabled.
     */
    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) {
            v2rayConfig.stats = null
            v2rayConfig.policy = null
        }
    }

    /**
     * Configure DNS servers, hosts, and route ALL DNS traffic exclusively through proxy.
     */
    private fun configureDns(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>,
    ) {
        val servers = ArrayList<Any>()
        val remoteDns = SettingsManager.getRemoteDnsServers()
        if (remoteDns.isNotEmpty()) {
            remoteDns.forEach { servers.add(it) }
        } else {
            servers.add("https://1.1.1.1/dns-query")
        }

        val hosts = buildDnsHostsFromRoutingRules(configContext)

        v2rayConfig.dns = V2rayConfig.DnsBean(
            servers = servers,
            hosts = hosts,
            tag = AppConfig.TAG_DNS,
            enableParallelQuery = if (servers.size > 1) true else null
        )

        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]

        // Force all internal DNS module traffic through proxy (highest priority)
        if (dnsProxyBalancerTag != null) {
            v2rayConfig.routing.rules.add(
                0,
                V2rayConfig.RoutingBean.RulesBean(
                    balancerTag = dnsProxyBalancerTag,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        } else {
            v2rayConfig.routing.rules.add(
                0,
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_PROXY,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        }

        // When local DNS hijacking (dns-out) is disabled, force all port-53 DNS traffic straight through proxy
        val usesDnsOut = MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true ||
                SettingsManager.isRootMode()
        if (!usesDnsOut) {
            if (dnsProxyBalancerTag != null) {
                v2rayConfig.routing.rules.add(
                    0,
                    V2rayConfig.RoutingBean.RulesBean(
                        port = "53",
                        balancerTag = dnsProxyBalancerTag,
                        domain = null
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    0,
                    V2rayConfig.RoutingBean.RulesBean(
                        port = "53",
                        outboundTag = AppConfig.TAG_PROXY,
                        domain = null
                    )
                )
            }
        }
    }

    private fun buildDnsHostsFromRoutingRules(configContext: CoreConfigContext): MutableMap<String, Any> {
        val hosts = mutableMapOf<String, Any>()

        val blockDomains = configContext.routingDomainRules
            .asSequence()
            .filter { it.outboundTag == AppConfig.TAG_BLOCKED }
            .flatMap { it.domain.asSequence() }
            .toList()
        if (blockDomains.isNotEmpty()) {
            hosts.putAll(blockDomains.map { it to AppConfig.LOOPBACK })
        }

        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES
        hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",").orEmpty()
                .filter { it.isNotBlank() && it.contains(":") }
                .associate {
                    val parts = it.split(":", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
            hosts.putAll(userHostsMap)
        }

        return hosts
    }

    //endregion

    //region outbound related functions

    /**
     * Resolve outbound domains to IPs and write resolved hosts to DNS map.
     */
    private fun resolveOutboundDomainsToHosts(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "1") {
            return
        }

        val proxyOutboundList = v2rayConfig.getAllProxyOutbound()
        val dns = v2rayConfig.dns ?: return
        val newHosts = dns.hosts?.toMutableMap() ?: mutableMapOf()
        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true

        for (item in proxyOutboundList) {
            val domain = item.getServerAddress()
            if (domain.isNullOrEmpty() || isIpAddress(domain)) {
                continue
            }

            if (newHosts.containsKey(domain)) {
                item.ensureSockopt().domainStrategy = "UseIP"
                item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                    prioritizeIPv6 = preferIpv6,
                    interleave = 2
                )
                continue
            }

            val resolvedIps = HttpUtil.resolveHostToIP(domain, preferIpv6)
            if (resolvedIps.isNullOrEmpty()) {
                continue
            }

            item.ensureSockopt().domainStrategy = "UseIP"
            item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                prioritizeIPv6 = preferIpv6,
                interleave = 2
            )
            newHosts[domain] = if (resolvedIps.size == 1) {
                resolvedIps[0]
            } else {
                resolvedIps
            }
        }

        dns.hosts = newHosts
    }

    /**
     * Convert one profile object into one outbound object.
     */
    private fun convertProfile2Outbound(profileItem: ProfileItem): V2rayConfig.OutboundBean? {
        return CoreOutboundBuilder.convert(profileItem)
    }

    //endregion

    //region routing related functions

    /**
     * Merge probe settings from all balancer strategies into the runtime config.
     */
    private fun applyObservability(v2rayConfig: V2rayConfig, strategies: List<BalancerStrategy>) {
        val allObsSelectors = strategies
            .mapNotNull { it.observatory?.subjectSelector }
            .flatten()
            .distinct()
        val obsTemplate = strategies.firstNotNullOfOrNull { it.observatory }
        if (obsTemplate != null && allObsSelectors.isNotEmpty()) {
            v2rayConfig.observatory = V2rayConfig.ObservatoryObject(
                subjectSelector = allObsSelectors,
                probeUrl = obsTemplate.probeUrl,
                probeInterval = obsTemplate.probeInterval,
                enableConcurrency = obsTemplate.enableConcurrency
            )
        }

        val allBurstSelectors = strategies
            .mapNotNull { it.burstObservatory?.subjectSelector }
            .flatten()
            .distinct()
        val burstTemplate = strategies.firstNotNullOfOrNull { it.burstObservatory }
        if (burstTemplate != null && allBurstSelectors.isNotEmpty()) {
            v2rayConfig.burstObservatory = V2rayConfig.BurstObservatoryObject(
                subjectSelector = allBurstSelectors,
                pingConfig = burstTemplate.pingConfig
            )
        }
    }

    /**
     * Configure routing domain strategy and append enabled user rules.
     */
    private fun configureRouting(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {
        v2rayConfig.routing.domainStrategy =
            MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
                ?: "AsIs"

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            appendRoutingUserRule(configContext, key, v2rayConfig, policyGroupBalancerTags)
        }
    }

    /**
     * Convert one rule item and append it to routing rules.
     */
    private fun appendRoutingUserRule(
        configContext: CoreConfigContext,
        item: RulesetItem?,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {
        val context = configContext.context
        if (item == null || !item.enabled) {
            return
        }

        val rule = JsonUtil.fromJson(JsonUtil.toJson(item), V2rayConfig.RoutingBean.RulesBean::class.java) ?: return

        rule.ip?.let { ipList ->
            val updatedIpList = ArrayList<String>(ipList.size)
            ipList.forEach { ip ->
                when (ip) {
                    AppConfig.GEOIP_CN -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn")
                    AppConfig.GEOIP_PRIVATE -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private")
                    else -> updatedIpList.add(ip)
                }
            }
            rule.ip = updatedIpList
        }

        if (SettingsManager.canUseProcessRouting()) {
            rule.process?.let { processList ->
                if (processList.isNotEmpty()) {
                    val uids = PackageUidResolver.packageNamesToUids(context, processList)
                    rule.process = uids.ifEmpty { null }
                }
            }
        } else {
            rule.process = null
        }

        val outboundTag = rule.outboundTag

        policyGroupBalancerTags[outboundTag]?.let { balancerTag ->
            rule.outboundTag = null
            rule.balancerTag = balancerTag
        }

        if (!outboundTag.isNullOrBlank()
            && outboundTag !in policyGroupBalancerTags
            && outboundTag !in AppConfig.BUILTIN_OUTBOUND_TAGS
            && v2rayConfig.outbounds.none { it.tag == outboundTag }
        ) {
            LogUtil.w(AppConfig.TAG, "Outbound tag '$outboundTag' not found, falling back to '${AppConfig.TAG_PROXY}'")
            rule.outboundTag = AppConfig.TAG_PROXY
        }

        v2rayConfig.routing.rules.add(rule)
    }

    /**
     * Build balancer and probe settings from one policy-group strategy value.
     */
    private fun buildBalancerStrategy(
        strategyType: BalancerStrategyType,
        selector: List<String>,
        balancerTag: String = AppConfig.TAG_BALANCER,
        fallbackTag: String? = null,
    ): BalancerStrategy {
        val probeUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
        val leastPingInterval = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
        val leastLoadInterval = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
        val leastLoadMethod = MmkvManager.decodeSettingsString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
        val leastLoadSampling = decodeObservatorySampling()
        val leastLoadTimeout = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)
        val balancer = V2rayConfig.RoutingBean.BalancerBean(
            tag = balancerTag,
            selector = selector,
            fallbackTag = fallbackTag,
            strategy = V2rayConfig.RoutingBean.StrategyObject(type = strategyType.policyGroupType)
        )
        val observatory = if (strategyType.requiresObservatory || fallbackTag != null) {
            V2rayConfig.ObservatoryObject(
                subjectSelector = selector,
                probeUrl = probeUrl,
                probeInterval = leastPingInterval,
                enableConcurrency = true
            )
        } else null
        val burstObservatory = if (strategyType.requiresBurstObservatory) {
            V2rayConfig.BurstObservatoryObject(
                subjectSelector = selector,
                pingConfig = V2rayConfig.BurstObservatoryObject.PingConfigObject(
                    destination = probeUrl,
                    httpMethod = leastLoadMethod,
                    interval = leastLoadInterval,
                    sampling = leastLoadSampling,
                    timeout = leastLoadTimeout
                )
            )
        } else null
        return BalancerStrategy(balancer, observatory, burstObservatory)
    }

    private fun decodeObservatoryDuration(key: String, default: String): String {
        val value = MmkvManager.decodeSettingsString(key)?.trim()
        return if (!value.isNullOrEmpty() && AppConfig.OBSERVATORY_DURATION_PATTERN.matches(value)) {
            value
        } else {
            default
        }
    }

    private fun decodeObservatorySampling(): Int {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING.toInt()
    }

    /**
     * Carry balancer data plus optional probe settings for later merge.
     */
    private data class BalancerStrategy(
        val balancer: V2rayConfig.RoutingBean.BalancerBean,
        val observatory: V2rayConfig.ObservatoryObject? = null,
        val burstObservatory: V2rayConfig.BurstObservatoryObject? = null,
    )

    //endregion
}