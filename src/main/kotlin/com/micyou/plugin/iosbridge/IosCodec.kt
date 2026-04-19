package com.micyou.plugin.iosbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object IosCodec {
    fun encodeHello(deviceId: String, deviceName: String, sampleRate: Int, channels: Int): ByteArray {
        val payload = buildString {
            append("HELLO|")
            append(deviceId)
            append('|')
            append(deviceName)
            append('|')
            append(sampleRate)
            append('|')
            append(channels)
        }
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun encodeKeepAlive(sequence: Long): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.put("PING".toByteArray(Charsets.UTF_8))
        buffer.putLong(sequence)
        return buffer.array()
    }

    fun encodeAudioFrame(sequence: Long, pcm16le: ByteArray): ByteArray {
        val payload = Base64.getEncoder().encodeToString(pcm16le)
        return "AUDIO|$sequence|$payload".toByteArray(Charsets.UTF_8)
    }
}
