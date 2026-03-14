package org.flowseal.tgwsproxy.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DcInfo(val dc: Int?, val isMedia: Boolean)

object MtProtoInit {
    private const val INIT_PACKET_SIZE = 64

    fun isHttpTransport(data: ByteArray): Boolean {
        return data.startsWithAscii("POST ") ||
            data.startsWithAscii("GET ") ||
            data.startsWithAscii("HEAD ") ||
            data.startsWithAscii("OPTIONS ")
    }

    fun extractDc(data: ByteArray): DcInfo {
        if (data.size < INIT_PACKET_SIZE) {
            return DcInfo(null, false)
        }

        return try {
            val key = data.copyOfRange(8, 40)
            val iv = data.copyOfRange(40, 56)
            val keystream = aesCtrEncrypt(key, iv, ByteArray(INIT_PACKET_SIZE))
            val plainTail = ByteArray(8)
            for (index in plainTail.indices) {
                plainTail[index] = (data[56 + index].toInt() xor keystream[56 + index].toInt()).toByte()
            }

            val proto = littleEndianInt(plainTail, 0)
            val dcRaw = littleEndianShort(plainTail, 4).toInt()
            if (proto == 0xEFEFEFEF.toInt() || proto == 0xEEEEEEEE.toInt() || proto == 0xDDDDDDDD.toInt()) {
                val dc = kotlin.math.abs(dcRaw)
                if (dc in 1..1000) {
                    DcInfo(dc, dcRaw < 0)
                } else {
                    DcInfo(null, false)
                }
            } else {
                DcInfo(null, false)
            }
        } catch (error: Exception) {
            ProxyLog.d("DC extraction failed: ${error.message}")
            DcInfo(null, false)
        }
    }

    fun patchDc(data: ByteArray, dc: Int): ByteArray {
        if (data.size < INIT_PACKET_SIZE) {
            return data
        }

        return try {
            val key = data.copyOfRange(8, 40)
            val iv = data.copyOfRange(40, 56)
            val keystream = aesCtrEncrypt(key, iv, ByteArray(INIT_PACKET_SIZE))
            val newDc = littleEndianShortBytes(dc.toShort())
            val patched = data.copyOf()
            patched[60] = (keystream[60].toInt() xor newDc[0].toInt()).toByte()
            patched[61] = (keystream[61].toInt() xor newDc[1].toInt()).toByte()
            patched
        } catch (error: Exception) {
            ProxyLog.w("Failed to patch init dc_id", error)
            data
        }
    }

    private fun aesCtrEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(plain)
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Short {
        return ByteBuffer.wrap(bytes, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
    }

    private fun littleEndianShortBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value)
            .array()
    }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean {
        val expected = prefix.encodeToByteArray()
        if (size < expected.size) {
            return false
        }
        return expected.indices.all { this[it] == expected[it] }
    }
}

class MsgSplitter(initData: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(initData.copyOfRange(8, 40), "AES"),
            IvParameterSpec(initData.copyOfRange(40, 56)),
        )
        update(ByteArray(64))
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        val plain = cipher.update(chunk) ?: return listOf(chunk)
        val boundaries = mutableListOf<Int>()
        var pos = 0

        while (pos < plain.size) {
            val first = plain[pos].toInt() and 0xFF
            val messageLengthWords: Int
            if (first == 0x7F) {
                if (pos + 4 > plain.size) {
                    break
                }
                messageLengthWords = littleEndianInt32(plain, pos + 1) and 0x00FF_FFFF
                pos += 4
            } else {
                messageLengthWords = first
                pos += 1
            }

            val messageLengthBytes = messageLengthWords * 4
            if (messageLengthBytes == 0 || pos + messageLengthBytes > plain.size) {
                break
            }

            pos += messageLengthBytes
            boundaries += pos
        }

        if (boundaries.size <= 1) {
            return listOf(chunk)
        }

        val parts = ArrayList<ByteArray>(boundaries.size + 1)
        var previous = 0
        for (boundary in boundaries) {
            parts += chunk.copyOfRange(previous, boundary)
            previous = boundary
        }
        if (previous < chunk.size) {
            parts += chunk.copyOfRange(previous, chunk.size)
        }
        return parts
    }

    private fun littleEndianInt32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
