package com.micyou.plugin.iosbridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.ByteBuffer
import java.nio.ByteOrder

object IosProtocol {
    const val MAGIC_HEADER = 0x694F5354

    enum class MessageType(val value: Int) {
        Hello(1),
        Ack(2),
        KeepAlive(3),
        Disconnect(4),
        AudioFrame(16)
    }

    data class Header(
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

    data class AudioFramePayload(
        val sequence: Int,
        val timestamp: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val pcmData: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioFramePayload
            if (sequence != other.sequence) return false
            if (timestamp != other.timestamp) return false
            if (sampleRate != other.sampleRate) return false
            if (channelCount != other.channelCount) return false
            if (!pcmData.contentEquals(other.pcmData)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = sequence
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + sampleRate
            result = 31 * result + channelCount
            result = 31 * result + pcmData.contentHashCode()
            return result
        }
    }

    fun parseHeader(data: ByteArray): Header? {
        if (data.size < 16) return null
        val buffer = ByteBuffer.wrap(data, 0, 16).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.int
        val type = buffer.int
        val payloadLength = buffer.int
        val sequence = buffer.int
        return Header(magic, type, payloadLength, sequence)
    }

    fun parseHelloPayload(payload: ByteArray): HelloPayload? {
        try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val nameLen = buffer.int
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val idLen = buffer.int
            val idBytes = ByteArray(idLen)
            buffer.get(idBytes)
            val sampleRate = buffer.int
            val channelCount = buffer.int
            return HelloPayload(
                String(nameBytes, Charsets.UTF_8),
                String(idBytes, Charsets.UTF_8),
                sampleRate,
                channelCount
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun parseAudioFramePayload(payload: ByteArray): AudioFramePayload? {
        try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val sequence = buffer.int
            val timestamp = buffer.long
            val sampleRate = buffer.int
            val channelCount = buffer.int
            val dataLen = buffer.int
            val pcmData = ByteArray(dataLen)
            buffer.get(pcmData)
            return AudioFramePayload(sequence, timestamp, sampleRate, channelCount, pcmData)
        } catch (e: Exception) {
            return null
        }
    }

    fun encodeAck(success: Boolean, audioPort: Int, message: String): ByteArray {
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(16 + 1 + 4 + 4 + msgBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC_HEADER)
        buffer.putInt(MessageType.Ack.value)
        buffer.putInt(1 + 4 + 4 + msgBytes.size)
        buffer.putInt(0)
        buffer.put(if (success) 1.toByte() else 0.toByte())
        buffer.putInt(audioPort)
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

    @OptIn(ExperimentalSerializationApi::class)
    fun convertToAndroidAudioPacket(
        seq: Int,
        timestamp: Long,
        sampleRate: Int,
        channelCount: Int,
        pcmData: ByteArray
    ): ByteArray {
        val audioPacket = AudioPacketMessage(
            buffer = pcmData,
            sampleRate = sampleRate,
            channelCount = channelCount,
            audioFormat = 2
        )

        val orderedPacket = AudioPacketMessageOrdered(
            sequenceNumber = seq,
            audioPacket = audioPacket,
            timestamp = timestamp
        )

        val wrapper = MessageWrapper(audioPacket = orderedPacket)

        val proto = ProtoBuf { }
        val payload = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        val payloadLength = payload.size

        val packetBytes = ByteArray(8 + payloadLength)
        val buffer = ByteBuffer.wrap(packetBytes)
        buffer.putInt(UDP_PACKET_MAGIC)
        buffer.putInt(payloadLength)
        buffer.put(payload)

        return packetBytes
    }
}
