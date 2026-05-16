package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.*
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.concurrent.Volatile

class IOSBridgePlugin : Plugin, AudioEffectPlugin {

    override val manifest = PluginManifest(
        id = "com.micyou.plugin.iosbridge",
        name = "iOS To PC",
        version = "3.0.0 Beta",
        author = "herbrine8403",
        description = "Enables iOS devices to connect to MicYou PC as wireless microphones (V3)",
        tags = listOf("ios", "wireless", "microphone", "bridge"),
        platform = PluginPlatform.DESKTOP,
        minApiVersion = "1.0.0",
        mainClass = "com.micyou.plugin.iosbridge.IOSBridgePlugin"
    )

    override val effectPriority: Int = 5
    override val audioEffectProvider: IOSAudioEffectProvider = IOSAudioEffectProvider()

    private var context: PluginContext? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var controlChannel: PluginDataChannel? = null
    private var audioChannel: PluginDataChannel? = null

    private val deviceId: String = "micyou-ios-${UUID.randomUUID()}"

    @Volatile
    var controlPort: Int = 0
        private set

    @Volatile
    var audioPort: Int = 0
        private set

    private val tcpReadBufferMaxSize = 64 * 1024

    @Volatile
    private var tcpReadBuffer = byteArrayOf()

    @Volatile
    private var virtualClient: VirtualAndroidClient? = null

    companion object {
        const val CHANNEL_TCP_CONTROL = "ios-bridge-tcp-control"
        const val CHANNEL_UDP_AUDIO = "ios-bridge-udp-audio"
        const val DEFAULT_TCP_PORT = 8900
        const val DEFAULT_UDP_PORT = 8901
    }

    override fun onLoad(ctx: PluginContext) {
        this.context = ctx
        ctx.log("iOS To PC plugin loaded, deviceId=$deviceId")

        controlChannel = ctx.host.createDataChannel(
            id = CHANNEL_TCP_CONTROL,
            config = DataChannelConfig(
                mode = DataChannelMode.Tcp,
                bufferSize = 64 * 1024
            )
        )

        audioChannel = ctx.host.createDataChannel(
            id = CHANNEL_UDP_AUDIO,
            config = DataChannelConfig(
                mode = DataChannelMode.Udp,
                bufferSize = 256 * 1024
            )
        )

        ctx.log("Data channels created: control=${controlChannel?.id}, audio=${audioChannel?.id}")
    }

    override fun onEnable() {
        val ctx = context ?: return
        ctx.log("iOS To PC plugin enabled")

        ctx.host.registerAudioEffect(audioEffectProvider, effectPriority)

        scope.launch {
            try {
                val ctrlChannel = controlChannel ?: return@launch
                val result = ctrlChannel.bind(DEFAULT_TCP_PORT)
                if (result.isSuccess) {
                    controlPort = ctrlChannel.localPort
                    ctx.log("Control channel bound to port $controlPort")
                    startControlListener(ctrlChannel)
                } else {
                    ctx.logError("Failed to bind control channel to port $DEFAULT_TCP_PORT", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                ctx.logError("Control channel setup failed", e)
            }
        }

        scope.launch {
            try {
                val audChannel = audioChannel ?: return@launch
                val result = audChannel.bind(DEFAULT_UDP_PORT)
                if (result.isSuccess) {
                    audioPort = audChannel.localPort
                    ctx.log("Audio channel bound to port $audioPort")
                    startAudioListener(audChannel)
                } else {
                    ctx.logError("Failed to bind audio channel to port $DEFAULT_UDP_PORT", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                ctx.logError("Audio channel setup failed", e)
            }
        }

        scope.launch {
            delay(500)
            val ip = getLocalIpAddress()
            ctx.host.showNotification("iOS Bridge Ready", "Please connect iOS device to port $controlPort")
            ctx.log("Plugin ready. Control port: $controlPort, Audio port: $audioPort, IP: $ip")
        }
    }

    override fun onDisable() {
        val ctx = context ?: return
        ctx.log("iOS To PC plugin disabling...")

        ctx.host.unregisterAudioEffect(audioEffectProvider)
        runBlocking {
            stopChannels()
        }
        ctx.log("iOS To PC plugin disabled")
    }

    override fun onUnload() {
        context?.log("iOS To PC plugin unloading...")
        runBlocking {
            stopChannels()
        }
        scope.cancel()
        controlChannel = null
        audioChannel = null
        context = null
    }

    private suspend fun stopChannels() {
        try {
            controlChannel?.close()
        } catch (e: Exception) {
            context?.logError("Error closing control channel", e)
        }
        try {
            audioChannel?.close()
        } catch (e: Exception) {
            context?.logError("Error closing audio channel", e)
        }
        controlPort = 0
        audioPort = 0
        tcpReadBuffer = byteArrayOf()
        audioEffectProvider.reset()
        try {
            virtualClient?.stop()
        } catch (e: Exception) {
            context?.logError("Error stopping virtual client", e)
        }
        virtualClient = null
    }

    private fun startControlListener(channel: PluginDataChannel) {
        scope.launch {
            try {
                channel.receive().collect { data ->
                    try {
                        tcpReadBuffer += data
                        processTcpBuffer(channel)
                    } catch (e: Exception) {
                        context?.logError("Error processing TCP data", e)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    context?.logError("Control listener error", e)
                }
            }
        }
    }

    private fun processTcpBuffer(channel: PluginDataChannel) {
        if (tcpReadBuffer.size > tcpReadBufferMaxSize) {
            context?.logError("TCP read buffer exceeded max size ($tcpReadBufferMaxSize), clearing buffer", null)
            tcpReadBuffer = byteArrayOf()
            return
        }

        var offset = 0
        while (offset + 16 <= tcpReadBuffer.size) {
            val header = IosProtocol.parseHeader(tcpReadBuffer.copyOfRange(offset, tcpReadBuffer.size))
            if (header == null || header.magic != IosProtocol.MAGIC_HEADER) {
                offset += 1
                continue
            }
            val totalMsgLen = 16 + header.payloadLength
            if (offset + totalMsgLen > tcpReadBuffer.size) break
            val payload = tcpReadBuffer.copyOfRange(offset + 16, offset + totalMsgLen)
            handleMessage(header, payload, channel)
            offset += totalMsgLen
        }
        if (offset > 0) {
            tcpReadBuffer = if (offset < tcpReadBuffer.size) {
                tcpReadBuffer.copyOfRange(offset, tcpReadBuffer.size)
            } else {
                byteArrayOf()
            }
        }
    }

    private fun handleMessage(
        header: IosProtocol.Header,
        payload: ByteArray,
        channel: PluginDataChannel
    ) {
        when (header.type) {
            IosProtocol.MessageType.Hello.value -> {
                handleHello(payload, channel)
            }
            IosProtocol.MessageType.KeepAlive.value -> {
                scope.launch {
                    val ack = IosProtocol.encodeKeepAlive(header.sequence)
                    channel.send(ack)
                }
            }
            IosProtocol.MessageType.AudioFrame.value -> {
                handleAudioFrame(payload)
            }
            IosProtocol.MessageType.Disconnect.value -> {
                context?.log("iOS device disconnected")
                audioEffectProvider.reset()
                virtualClient?.stop()
                virtualClient = null
            }
        }
    }

    private fun handleHello(payload: ByteArray, channel: PluginDataChannel) {
        val ctx = context ?: return
        val hello = IosProtocol.parseHelloPayload(payload) ?: return

        ctx.log("iOS device connected: ${hello.deviceName}")
        audioEffectProvider.setAudioConfig(hello.sampleRate, hello.channelCount)

        val client = VirtualAndroidClient()
        virtualClient = client
        client.start(hello.deviceName, hello.sampleRate, hello.channelCount)

        ctx.host.showNotification("iOS Bridge", "Device '${hello.deviceName}' connected and ready for audio streaming")
        ctx.host.showSnackbar("iOS设备 '${hello.deviceName}' 已连接")

        scope.launch {
            val ack = IosProtocol.encodeAck(true, audioPort, "Connected to MicYou PC")
            channel.send(ack)
        }
    }

    private fun handleAudioFrame(payload: ByteArray) {
        val frame = IosProtocol.parseAudioFramePayload(payload) ?: return
        if (frame.pcmData.isNotEmpty()) {
            val client = virtualClient
            if (client != null && client.isConnected()) {
                val androidPacket = IosProtocol.convertToAndroidAudioPacket(
                    seq = frame.sequence,
                    timestamp = frame.timestamp,
                    sampleRate = frame.sampleRate,
                    channelCount = frame.channelCount,
                    pcmData = frame.pcmData
                )
                client.sendAudioData(frame.pcmData, frame.timestamp, frame.sequence)
            } else {
                audioEffectProvider.addPcm16LeData(frame.pcmData)
            }
        }
    }

    private fun startAudioListener(channel: PluginDataChannel) {
        scope.launch {
            try {
                channel.receive().collect { data ->
                    try {
                        processSinglePacket(data)
                    } catch (e: Exception) {
                        context?.logError("Error processing UDP data", e)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    context?.logError("Audio listener error", e)
                }
            }
        }
    }

    private fun processSinglePacket(data: ByteArray) {
        val header = IosProtocol.parseHeader(data) ?: return
        if (data.size < 16 + header.payloadLength) return

        if (header.type == IosProtocol.MessageType.AudioFrame.value) {
            val payload = data.copyOfRange(16, 16 + header.payloadLength)
            handleAudioFrame(payload)
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            java.net.InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            context?.logError("Failed to get local IP address", e)
            "127.0.0.1"
        }
    }
}
