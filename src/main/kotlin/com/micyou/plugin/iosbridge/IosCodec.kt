package com.micyou.plugin.iosbridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol codec for iOS <-> PC plugin communication.
 *
 * Message format:
 * - 4 bytes: Magic (0x694F5354 = "iOST")
 * - 4 bytes: Payload length (big-endian)
 * - N bytes: Protobuf-encoded payload
 */
@OptIn(ExperimentalSerializationApi::class)
object IosCodec {

    const val IOS_MAGIC = 0x694F5354 // "iOST"

    private val proto = ProtoBuf { }

    /**
     * Encode a control message for sending to iOS.
     */
    fun encodeControlMessage(message: IosControlMessage): ByteArray {
        val payload = proto.encodeToByteArray(IosControlMessage.serializer(), message)
        return wrapWithHeader(payload)
    }

    /**
     * Decode a control message received from iOS.
     */
    fun decodeControlMessage(data: ByteArray): IosControlMessage {
        val payload = unwrapHeader(data)
        return proto.decodeFromByteArray(IosControlMessage.serializer(), payload)
    }

    /**
     * Encode an audio frame for sending.
     */
    fun encodeAudioFrame(frame: IosAudioFrame): ByteArray {
        val payload = proto.encodeToByteArray(IosAudioFrame.serializer(), frame)
        return wrapWithHeader(payload)
    }

    /**
     * Decode an audio frame.
     */
    fun decodeAudioFrame(data: ByteArray): IosAudioFrame {
        val payload = unwrapHeader(data)
        return proto.decodeFromByteArray(IosAudioFrame.serializer(), payload)
    }

    /**
     * Create a HELLO message for iOS device handshake.
     */
    fun encodeHello(deviceId: String, deviceName: String, sampleRate: Int, channels: Int): ByteArray {
        return encodeControlMessage(
            IosControlMessage(
                type = IosMessageType.HELLO,
                deviceId = deviceId,
                deviceName = deviceName,
                sampleRate = sampleRate,
                channels = channels
            )
        )
    }

    /**
     * Create a KEEPALIVE message.
     */
    fun encodeKeepAlive(deviceId: String, sequence: Int = 0): ByteArray {
        return encodeControlMessage(
            IosControlMessage(
                type = IosMessageType.KEEPALIVE,
                deviceId = deviceId,
                sequence = sequence
            )
        )
    }

    /**
     * Create an ACK message in response to HELLO.
     */
    fun encodeAck(serverId: String, controlPort: Int, audioPort: Int = 0): ByteArray {
        return encodeControlMessage(
            IosControlMessage(
                type = IosMessageType.ACK,
                deviceId = serverId,
                controlPort = controlPort,
                audioPort = audioPort
            )
        )
    }

    /**
     * Create a DISCONNECT message.
     */
    fun encodeDisconnect(deviceId: String, reason: String = ""): ByteArray {
        return encodeControlMessage(
            IosControlMessage(
                type = IosMessageType.DISCONNECT,
                deviceId = deviceId,
                payload = reason
            )
        )
    }

    /**
     * Create a CONFIG message.
     */
    fun encodeConfig(deviceId: String, config: Map<String, String>): ByteArray {
        val configString = config.entries.joinToString(",") { "${it.key}=${it.value}" }
        return encodeControlMessage(
            IosControlMessage(
                type = IosMessageType.CONFIG,
                deviceId = deviceId,
                payload = configString
            )
        )
    }

    /**
     * Wrap payload with magic header and length.
     */
    private fun wrapWithHeader(payload: ByteArray): ByteArray {
        val result = ByteArray(8 + payload.size)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(IOS_MAGIC)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return result
    }

    /**
     * Unwrap payload from header.
     */
    private fun unwrapHeader(data: ByteArray): ByteArray {
        if (data.size < 8) {
            throw IllegalArgumentException("Data too small for header: ${data.size} bytes")
        }
        val magic = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (magic != IOS_MAGIC) {
            throw IllegalArgumentException("Magic mismatch: expected 0x${IOS_MAGIC.toString(16)}, got 0x${magic.toString(16)}")
        }
        val length = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        if (length < 0 || length > data.size - 8) {
            throw IllegalArgumentException("Invalid payload length: $length")
        }
        return data.copyOfRange(8, 8 + length)
    }
}

/**
 * Message types for iOS <-> PC communication.
 */
enum class IosMessageType {
    HELLO,      // Initial handshake
    ACK,        // Acknowledgment of HELLO
    KEEPALIVE,  // Periodic keepalive
    DISCONNECT, // Graceful disconnect
    CONFIG,     // Configuration update
    ERROR       // Error notification
}

/**
 * Control message structure for iOS <-> PC plugin communication.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class IosControlMessage(
    val type: IosMessageType,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val sequence: Int = 0,
    val controlPort: Int = 0,
    val audioPort: Int = 0,
    val payload: String = ""
)

/**
 * Audio frame structure for streaming PCM audio.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class IosAudioFrame(
    val streamId: String,
    val sequence: Long,
    val timestamp: Long,
    val sampleRate: Int,
    val channels: Int,
    val pcm16le: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as IosAudioFrame
        return streamId == other.streamId &&
                sequence == other.sequence &&
                timestamp == other.timestamp &&
                sampleRate == other.sampleRate &&
                channels == other.channels &&
                pcm16le.contentEquals(other.pcm16le)
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + pcm16le.contentHashCode()
        return result
    }
}
