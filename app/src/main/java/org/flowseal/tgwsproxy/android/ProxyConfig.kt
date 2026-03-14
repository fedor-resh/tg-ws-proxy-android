package org.flowseal.tgwsproxy.android

data class ProxyConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val dcIp: List<String> = DEFAULT_DC_IP,
    val verbose: Boolean = false,
) {
    fun dcMap(): Map<Int, String> = DcAddressParser.parse(dcIp)

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1080
        val DEFAULT_DC_IP = listOf(
            "2:149.154.167.220",
            "4:149.154.167.220",
            "5:149.154.171.5",
        )
    }
}

data class ProxyDraft(
    val hostText: String = ProxyConfig.DEFAULT_HOST,
    val portText: String = ProxyConfig.DEFAULT_PORT.toString(),
    val dcIpText: String = ProxyConfig.DEFAULT_DC_IP.joinToString("\n"),
    val verbose: Boolean = false,
) {
    companion object {
        fun fromConfig(config: ProxyConfig): ProxyDraft {
            return ProxyDraft(
                hostText = config.host,
                portText = config.port.toString(),
                dcIpText = config.dcIp.joinToString("\n"),
                verbose = config.verbose,
            )
        }
    }
}
