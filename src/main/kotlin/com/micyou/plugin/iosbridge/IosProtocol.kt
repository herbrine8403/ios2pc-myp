package com.micyou.plugin.iosbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

object IosProtocol {

    const val MAGIC_HEADER: Int = 0x694F5354

    enum class MessageType(val value: Int) {
        Hello(1),
        Ack(2),
        KeepAlive(3),
        Disconnect(4),
        AudioFrame(16)
    }

    data class MessageHeader(
        val magic: Int,
        val type: Int,
        val payloadLength: Int,
        val sequence: Int
    )

    data class HelloPayload(
        val deviceName: String,
        val deviceId: String,
        val sampleRate: Int,
        val channelCount: Int
    )

    data class AckPayload(
        val success: Boolean,
        val udpPort: Int,
        val message: String
    )

    data class AudioFramePayload(
        val sequence: Int,
        val timestamp: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val pcmData: ByteArray
    )

    fun parseHeader(buffer: ByteArray): MessageHeader? {
        if (buffer.size < 16) return null
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        val magic = bb.int
        if (magic != MAGIC_HEADER) return null
        return MessageHeader(
            magic = magic,
            type = bb.int,
            payloadLength = bb.int,
            sequence = bb.int
        )
    }

    fun parseHelloPayload(payload: ByteArray): HelloPayload? {
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val nameLen = bb.int
            val nameBytes = ByteArray(nameLen)
            bb.get(nameBytes)
            val idLen = bb.int
            val idBytes = ByteArray(idLen)
            bb.get(idBytes)
            val sampleRate = bb.int
            val channelCount = bb.int
            HelloPayload(
                deviceName = String(nameBytes, Charsets.UTF_8),
                deviceId = String(idBytes, Charsets.UTF_8),
                sampleRate = sampleRate,
                channelCount = channelCount
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseAudioFramePayload(payload: ByteArray): AudioFramePayload? {
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val sequence = bb.int
            val timestamp = bb.long
            val sampleRate = bb.int
            val channelCount = bb.int
            val dataLen = bb.int
            val pcmData = ByteArray(dataLen)
            bb.get(pcmData)
            AudioFramePayload(
                sequence = sequence,
                timestamp = timestamp,
                sampleRate = sampleRate,
                channelCount = channelCount,
                pcmData = pcmData
            )
        } catch (e: Exception) {
            null
        }
    }

    fun encodeAck(success: Boolean, udpPort: Int, message: String): ByteArray {
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val payloadLen = 1 + 4 + 4 + msgBytes.size
        val buffer = ByteBuffer.allocate(16 + payloadLen).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(MAGIC_HEADER)
        buffer.putInt(MessageType.Ack.value)
        buffer.putInt(payloadLen)
        buffer.putInt(0)

        buffer.put(if (success) 1.toByte() else 0.toByte())
        buffer.putInt(udpPort)
        buffer.putInt(msgBytes.size)
        buffer.put(msgBytes)

        return buffer.array()
    }

    fun encodeKeepAlive(sequence: Int): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC_HEADER)
        buffer.putInt(MessageType.KeepAlive.value)
        buffer.putInt(0)
        buffer.putInt(sequence)
        return buffer.array()
    }
}
