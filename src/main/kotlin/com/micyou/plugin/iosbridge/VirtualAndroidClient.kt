package com.micyou.plugin.iosbridge

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer

/**
 * 虚拟 Android 客户端核心。
 * 模拟 Android 端行为，向 PC 服务器发起连接并发送音频数据。
 *
 * 职责包括：
 * 1. TCP 连接到 127.0.0.1:6000，完成 Android 握手流程
 * 2. UDP 连接到 127.0.0.1:6001，发送音频数据（Protobuf + Magic Header）
 * 3. 心跳保活（Ping/Pong，1 秒间隔）
 * 4. 资源清理和断开连接
 */
class VirtualAndroidClient {

    companion object {
        private const val CHECK_1 = "MicYouCheck1"
        private const val CHECK_2 = "MicYouCheck2"
        private const val CHECK_1_LEN = 11
        private const val CHECK_2_LEN = 11

        private const val TCP_HOST = "127.0.0.1"
        private const val TCP_PORT = 6000
        private const val UDP_PORT = 6001

        private const val PING_INTERVAL_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val MAX_PACKET_SIZE = 2 * 1024 * 1024 // 2MB
        private const val MESSAGE_CHANNEL_CAPACITY = 64
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connected = false

    private var tcpSocket: Socket? = null
    private var tcpInput: java.io.InputStream? = null
    private var tcpOutput: java.io.OutputStream? = null

    private var udpSocket: DatagramSocket? = null
    private var udpAddress: InetSocketAddress? = null

    private var sendChannel: Channel<MessageWrapper>? = null
    private var writerJob: Job? = null
    private var pingJob: Job? = null
    private var receiveJob: Job? = null

    private var deviceName: String = ""
    private var sampleRate: Int = 48000
    private var channelCount: Int = 2

    /**
     * 启动虚拟 Android 客户端连接。
     *
     * @param deviceName 设备名称
     * @param sampleRate 音频采样率
     * @param channelCount 音频通道数
     */
    fun start(deviceName: String, sampleRate: Int, channelCount: Int) {
        if (connected) {
            return
        }

        this.deviceName = deviceName
        this.sampleRate = sampleRate
        this.channelCount = channelCount

        scope.launch(Dispatchers.IO) {
            try {
                connectTcp()
                setupUdp()
                startMessageLoops()
                connected = true
            } catch (e: Exception) {
                e.printStackTrace()
                cleanup()
            }
        }
    }

    /**
     * 发送音频数据（通过 UDP）。
     *
     * @param pcmData PCM 音频数据
     * @param timestamp 时间戳
     * @param sequence 序列号
     */
    fun sendAudioData(pcmData: ByteArray, timestamp: Long, sequence: Int) {
        if (!connected) return

        val socket = udpSocket ?: return
        val address = udpAddress ?: return

        val audioPacket = AudioPacketMessage(
            buffer = pcmData,
            sampleRate = sampleRate,
            channelCount = channelCount,
            audioFormat = 2 // ENCODING_PCM_16BIT
        )

        val orderedPacket = AudioPacketMessageOrdered(
            sequenceNumber = sequence,
            audioPacket = audioPacket,
            timestamp = timestamp
        )

        val wrapper = MessageWrapper(audioPacket = orderedPacket)

        try {
            @OptIn(ExperimentalSerializationApi::class)
            val payload = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
            val payloadLength = payload.size

            val packetBytes = ByteArray(8 + payloadLength)
            val buffer = ByteBuffer.wrap(packetBytes)
            buffer.putInt(UDP_PACKET_MAGIC)
            buffer.putInt(payloadLength)
            buffer.put(payload)

            val packet = DatagramPacket(packetBytes, packetBytes.size, address)
            socket.send(packet)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 断开连接并清理所有资源。
     */
    fun stop() {
        val previousConnected = connected
        connected = false
        runBlocking {
            withTimeoutOrNull(2000) {
                writerJob?.join()
                pingJob?.join()
                receiveJob?.join()
            }
        }
        cleanup()
        if (previousConnected) {
            println("VirtualAndroidClient stopped and resources cleaned up")
        }
    }

    /**
     * 返回当前连接状态。
     */
    fun isConnected(): Boolean = connected

    private suspend fun connectTcp() {
        val socket = Socket()
        socket.connect(InetSocketAddress(TCP_HOST, TCP_PORT), CONNECT_TIMEOUT_MS)
        tcpSocket = socket
        tcpInput = socket.getInputStream()
        tcpOutput = socket.getOutputStream()

        performHandshake()
    }

    private suspend fun performHandshake() {
        val input = tcpInput ?: throw IOException("TCP input stream not available")
        val output = tcpOutput ?: throw IOException("TCP output stream not available")

        output.write(CHECK_1.encodeToByteArray())
        output.flush()

        val check2Buf = ByteArray(CHECK_2_LEN)
        var totalRead = 0
        while (totalRead < CHECK_2_LEN) {
            val read = input.read(check2Buf, totalRead, CHECK_2_LEN - totalRead)
            if (read == -1) {
                throw EOFException("Handshake failed: connection closed while reading Check2")
            }
            totalRead += read
        }

        val check2String = check2Buf.decodeToString()
        if (check2String != CHECK_2) {
            throw IOException("Handshake failed: expected '$CHECK_2', got '$check2String'")
        }
    }

    private fun setupUdp() {
        udpSocket = DatagramSocket()
        udpAddress = InetSocketAddress(TCP_HOST, UDP_PORT)
    }

    private fun startMessageLoops() {
        sendChannel = Channel(MESSAGE_CHANNEL_CAPACITY)

        writerJob = scope.launch(Dispatchers.IO) {
            processSendQueue()
        }

        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && connected) {
                sendPing()
                delay(PING_INTERVAL_MS)
            }
        }

        receiveJob = scope.launch(Dispatchers.IO) {
            processReceiveLoop()
        }
        
        // 立即发送第一个Ping，确保连接活跃
        scope.launch(Dispatchers.IO) {
            delay(500)
            sendPing()
        }
    }

    private suspend fun processSendQueue() {
        val channel = sendChannel ?: return
        val output = tcpOutput ?: return

        for (msg in channel) {
            try {
                @OptIn(ExperimentalSerializationApi::class)
                val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                val length = packetBytes.size

                val packet = ByteArray(8 + length)
                val buffer = ByteBuffer.wrap(packet)
                buffer.putInt(PACKET_MAGIC)
                buffer.putInt(length)
                buffer.put(packetBytes)

                output.write(packet)
                output.flush()
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processReceiveLoop() {
        val input = tcpInput ?: return

        val headerBuf = ByteArray(8)

        while (currentCoroutineContext().isActive && connected) {
            try {
                // 读取 8 字节头部（magic + length）
                var totalRead = 0
                while (totalRead < 8) {
                    val read = input.read(headerBuf, totalRead, 8 - totalRead)
                    if (read == -1) {
                        return
                    }
                    totalRead += read
                }

                val buffer = ByteBuffer.wrap(headerBuf)
                val magic = buffer.int

                if (magic != PACKET_MAGIC) {
                    // 简单的重新同步：逐字节滑动查找 magic
                    resyncMagic(input, magic)
                    continue
                }

                val length = buffer.int

                if (length > MAX_PACKET_SIZE || length < 0) {
                    continue
                }

                if (length == 0) {
                    continue
                }

                val packetBytes = ByteArray(length)
                totalRead = 0
                while (totalRead < length) {
                    val read = input.read(packetBytes, totalRead, length - totalRead)
                    if (read == -1) {
                        return
                    }
                    totalRead += read
                }

                val wrapper: MessageWrapper = proto.decodeFromByteArray(
                    MessageWrapper.serializer(),
                    packetBytes
                )

                if (wrapper.pong != null) {
                    // 收到 Pong，可计算 RTT
                }

                if (wrapper.mute != null) {
                    // 处理静音状态变更
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is EOFException) {
                    return
                }
                e.printStackTrace()
            }
        }
    }

    private suspend fun resyncMagic(input: java.io.InputStream, initialMagic: Int) {
        var resyncMagic = initialMagic
        val byteBuf = ByteArray(1)

        while (currentCoroutineContext().isActive && connected) {
            val read = input.read(byteBuf)
            if (read == -1) return

            val byte = byteBuf[0].toInt() and 0xFF
            resyncMagic = (resyncMagic shl 8) or byte
            if (resyncMagic == PACKET_MAGIC) {
                break
            }
        }
    }

    private suspend fun sendPing() {
        try {
            sendChannel?.send(MessageWrapper(ping = PingMessage(System.currentTimeMillis())))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanup() {
        connected = false

        writerJob?.cancel()
        pingJob?.cancel()
        receiveJob?.cancel()
        writerJob = null
        pingJob = null
        receiveJob = null

        try {
            sendChannel?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        sendChannel = null

        try {
            tcpInput?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tcpInput = null

        try {
            tcpOutput?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tcpOutput = null

        try {
            tcpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tcpSocket = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        udpSocket = null
        udpAddress = null
    }
}
