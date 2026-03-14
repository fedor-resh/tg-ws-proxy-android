package org.flowseal.tgwsproxy.android

object DcAddressParser {
    fun parse(lines: List<String>): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        for (raw in lines) {
            val entry = raw.trim()
            if (entry.isEmpty()) {
                continue
            }

            val idx = entry.indexOf(':')
            require(idx > 0 && idx < entry.lastIndex) {
                "Invalid DC mapping '$entry', expected DC:IP"
            }

            val dc = entry.substring(0, idx).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid DC number in '$entry'")
            val ip = entry.substring(idx + 1).trim()
            require(isIpv4(ip)) { "Invalid IPv4 address in '$entry'" }
            result[dc] = ip
        }
        return result
    }

    fun normalizeText(text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) {
            return false
        }
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            number in 0..255
        }
    }
}
