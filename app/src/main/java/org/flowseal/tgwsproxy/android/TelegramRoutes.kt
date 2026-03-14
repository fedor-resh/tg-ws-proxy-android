package org.flowseal.tgwsproxy.android

import java.net.InetAddress

data class DcEndpoint(val dc: Int, val isMedia: Boolean)

object TelegramRoutes {
    // Official Telegram address ranges:
    // https://core.telegram.org/resources/cidr.txt
    private val telegramCidrs = listOf(
        "91.108.56.0/22",
        "91.108.4.0/22",
        "91.108.8.0/22",
        "91.108.16.0/22",
        "91.108.12.0/22",
        "149.154.160.0/20",
        "91.105.192.0/23",
        "91.108.20.0/22",
        "185.76.151.0/24",
        "2001:b28:f23d::/48",
        "2001:b28:f23f::/48",
        "2001:67c:4e8::/48",
        "2001:b28:f23c::/48",
        "2a0a:f280::/32",
    ).map(::parseCidr)

    private val ipToDc = mapOf(
        "149.154.175.50" to DcEndpoint(1, false),
        "149.154.175.51" to DcEndpoint(1, false),
        "149.154.175.53" to DcEndpoint(1, false),
        "149.154.175.54" to DcEndpoint(1, false),
        "149.154.175.52" to DcEndpoint(1, true),
        "149.154.167.41" to DcEndpoint(2, false),
        "149.154.167.50" to DcEndpoint(2, false),
        "149.154.167.51" to DcEndpoint(2, false),
        "149.154.167.35" to DcEndpoint(2, false),
        "149.154.167.220" to DcEndpoint(2, false),
        "95.161.76.100" to DcEndpoint(2, false),
        "149.154.167.151" to DcEndpoint(2, true),
        "149.154.167.222" to DcEndpoint(2, true),
        "149.154.167.223" to DcEndpoint(2, true),
        "149.154.175.100" to DcEndpoint(3, false),
        "149.154.175.101" to DcEndpoint(3, false),
        "149.154.175.102" to DcEndpoint(3, true),
        "149.154.167.91" to DcEndpoint(4, false),
        "149.154.167.92" to DcEndpoint(4, false),
        "149.154.164.250" to DcEndpoint(4, true),
        "149.154.166.120" to DcEndpoint(4, true),
        "149.154.166.121" to DcEndpoint(4, true),
        "149.154.167.118" to DcEndpoint(4, true),
        "149.154.165.111" to DcEndpoint(4, true),
        "91.108.56.100" to DcEndpoint(5, false),
        "91.108.56.101" to DcEndpoint(5, false),
        "91.108.56.116" to DcEndpoint(5, false),
        "91.108.56.126" to DcEndpoint(5, false),
        "149.154.171.5" to DcEndpoint(5, false),
        "149.154.171.255" to DcEndpoint(5, false),
        "91.108.56.102" to DcEndpoint(5, true),
        "91.108.56.128" to DcEndpoint(5, true),
        "91.108.56.151" to DcEndpoint(5, true),
        "91.105.192.100" to DcEndpoint(203, false),
    )

    // Telegram production DC IPv6 prefixes. F00N matches DC N.
    private val ipv6DcCidrs = listOf(
        "2001:b28:f23d:f001::/64" to DcEndpoint(1, false),
        "2001:67c:4e8:f002::/64" to DcEndpoint(2, false),
        "2001:b28:f23d:f003::/64" to DcEndpoint(3, false),
        "2001:67c:4e8:f004::/64" to DcEndpoint(4, false),
        "2001:b28:f23f:f005::/64" to DcEndpoint(5, false),
    ).map { (cidr, endpoint) -> parseCidr(cidr) to endpoint }

    fun isTelegramIp(ip: String): Boolean {
        val bytes = parseNumericAddress(ip) ?: return false
        return telegramCidrs.any { it.matches(bytes) }
    }

    fun lookupDc(ip: String): DcEndpoint? {
        ipToDc[ip]?.let { return it }
        val bytes = parseNumericAddress(ip) ?: return null
        return ipv6DcCidrs.firstOrNull { (cidr, _) -> cidr.matches(bytes) }?.second
    }

    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val base = if (dc > 5) "telegram.org" else "web.telegram.org"
        return if (isMedia) {
            listOf("kws$dc-1.$base", "kws$dc.$base")
        } else {
            listOf("kws$dc.$base", "kws$dc-1.$base")
        }
    }

    private fun parseNumericAddress(value: String): ByteArray? {
        val looksLikeIpv4 = value.isNotEmpty() && value.all { it.isDigit() || it == '.' }
        val looksLikeIpv6 = ':' in value
        if (!looksLikeIpv4 && !looksLikeIpv6) {
            return null
        }
        return runCatching { InetAddress.getByName(value).address }.getOrNull()
    }

    private fun parseCidr(cidr: String): IpCidr {
        val (address, prefixLength) = cidr.split('/', limit = 2)
        val bytes = InetAddress.getByName(address).address
        return IpCidr(bytes, prefixLength.toInt())
    }
}

private data class IpCidr(
    private val networkBytes: ByteArray,
    private val prefixLength: Int,
) {
    fun matches(addressBytes: ByteArray): Boolean {
        if (addressBytes.size != networkBytes.size) {
            return false
        }

        val fullBytes = prefixLength / 8
        val tailBits = prefixLength % 8

        for (index in 0 until fullBytes) {
            if (addressBytes[index] != networkBytes[index]) {
                return false
            }
        }

        if (tailBits == 0) {
            return true
        }

        val mask = ((0xFF shl (8 - tailBits)) and 0xFF)
        val addressTail = addressBytes[fullBytes].toInt() and mask
        val networkTail = networkBytes[fullBytes].toInt() and mask
        return addressTail == networkTail
    }
}
