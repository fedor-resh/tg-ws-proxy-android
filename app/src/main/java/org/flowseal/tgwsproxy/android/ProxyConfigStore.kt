package org.flowseal.tgwsproxy.android

import android.content.Context

class ProxyConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): ProxyConfig {
        val dcText = prefs.getString(
            KEY_DC_IP,
            ProxyConfig.DEFAULT_DC_IP.joinToString("\n"),
        ).orEmpty()
        val mergedDcIp = mergeWithDefaultDcIps(
            DcAddressParser.normalizeText(dcText).ifEmpty { ProxyConfig.DEFAULT_DC_IP },
        )

        return ProxyConfig(
            host = prefs.getString(KEY_HOST, ProxyConfig.DEFAULT_HOST) ?: ProxyConfig.DEFAULT_HOST,
            port = prefs.getInt(KEY_PORT, ProxyConfig.DEFAULT_PORT),
            dcIp = mergedDcIp,
            verbose = prefs.getBoolean(KEY_VERBOSE, false),
        )
    }

    fun save(config: ProxyConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_DC_IP, config.dcIp.joinToString("\n"))
            .putBoolean(KEY_VERBOSE, config.verbose)
            .putString(KEY_DRAFT_HOST, config.host)
            .putString(KEY_DRAFT_PORT, config.port.toString())
            .putString(KEY_DRAFT_DC_IP, config.dcIp.joinToString("\n"))
            .putBoolean(KEY_DRAFT_VERBOSE, config.verbose)
            .apply()
    }

    fun loadDraftOrConfig(): ProxyDraft {
        val config = load()
        return ProxyDraft(
            hostText = prefs.getString(KEY_DRAFT_HOST, config.host) ?: config.host,
            portText = prefs.getString(KEY_DRAFT_PORT, config.port.toString()) ?: config.port.toString(),
            dcIpText = prefs.getString(KEY_DRAFT_DC_IP, config.dcIp.joinToString("\n"))
                ?: config.dcIp.joinToString("\n"),
            verbose = prefs.getBoolean(KEY_DRAFT_VERBOSE, config.verbose),
        )
    }

    fun saveDraft(draft: ProxyDraft) {
        prefs.edit()
            .putString(KEY_DRAFT_HOST, draft.hostText)
            .putString(KEY_DRAFT_PORT, draft.portText)
            .putString(KEY_DRAFT_DC_IP, draft.dcIpText)
            .putBoolean(KEY_DRAFT_VERBOSE, draft.verbose)
            .apply()
    }

    private fun mergeWithDefaultDcIps(lines: List<String>): List<String> {
        val userMap = runCatching { DcAddressParser.parse(lines) }
            .getOrElse { return ProxyConfig.DEFAULT_DC_IP }
        val defaultMap = DcAddressParser.parse(ProxyConfig.DEFAULT_DC_IP)

        val merged = mutableListOf<String>()
        for ((dc, ip) in userMap) {
            merged += "$dc:$ip"
        }
        for ((dc, ip) in defaultMap) {
            if (dc !in userMap) {
                merged += "$dc:$ip"
            }
        }
        return merged
    }

    companion object {
        private const val PREFS_NAME = "tg_ws_proxy_prefs"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_DC_IP = "dc_ip"
        private const val KEY_VERBOSE = "verbose"
        private const val KEY_DRAFT_HOST = "draft_host"
        private const val KEY_DRAFT_PORT = "draft_port"
        private const val KEY_DRAFT_DC_IP = "draft_dc_ip"
        private const val KEY_DRAFT_VERBOSE = "draft_verbose"
    }
}
