package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IOSBridgePlugin : Plugin, AudioEffectPlugin {

    override val manifest = PluginManifest(
        id = "com.micyou.plugin.iosbridge",
        name = "iOS To PC",
        version = "1.0.0",
        author = "MicYou Team",
        description = "Enables iOS devices to connect to MicYou PC as wireless microphones via TCP/UDP protocol.",
        tags = listOf("ios", "wireless", "microphone", "bridge"),
        platform = PluginPlatform.DESKTOP,
        minApiVersion = "1.0.0",
        mainClass = "com.micyou.plugin.iosbridge.IOSBridgePlugin"
    )

    override val effectPriority: Int = 50

    override val audioEffectProvider: IOSAudioEffectProvider = IOSAudioEffectProvider()

    private var context: PluginContext? = null
    private var pluginScope: CoroutineScope? = null

    private var tcpChannel: PluginDataChannel? = null
    private var udpChannel: PluginDataChannel? = null

    private var sequenceCounter = 0
    private var isRunning = false

    companion object {
        const val CHANNEL_TCP_CONTROL = "ios-bridge-tcp-control"
        const val CHANNEL_UDP_AUDIO = "ios-bridge-udp-audio"
        
        /** 默认 TCP 控制端口，iOS 设备需要连接此端口 */
        const val DEFAULT_TCP_PORT = 8900
        /** 默认 UDP 音频端口 */
        const val DEFAULT_UDP_PORT = 8901
    }

    override fun onLoad(context: PluginContext) {
        this.context = context
        this.pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        context.log("IOSBridgePlugin loaded")
    }

    override fun onEnable() {
        val ctx = context ?: return
        val host = ctx.host

        host.registerAudioEffect(audioEffectProvider, effectPriority)
        ctx.log("IOSBridgePlugin audio effect registered")

        // 监听 MicYou 音频流状态，只在 Streaming 状态时开放端口
        pluginScope?.launch {
            host.streamState
                .distinctUntilChanged()
                .collectLatest { state ->
                    when (state) {
                        StreamState.Streaming -> {
                            ctx.log("MicYou stream started, opening iOS bridge ports...")
                            startTcpControlChannel()
                            startUdpAudioChannel()
                        }
                        StreamState.Idle -> {
                            ctx.log("MicYou stream stopped, closing iOS bridge ports...")
                            stopChannels()
                        }
                        else -> {}
                    }
                }
        }
    }

    override fun onDisable() {
        val ctx = context ?: return
        val host = ctx.host

        host.unregisterAudioEffect(audioEffectProvider)
        ctx.log("IOSBridgePlugin audio effect unregistered")

        stopChannels()
    }

    override fun onUnload() {
        stopChannels()
        pluginScope?.cancel()
        pluginScope = null
        context = null
    }

    private suspend fun startTcpControlChannel() {
        val ctx = context ?: return
        val host = ctx.host

        val config = DataChannelConfig(
            mode = DataChannelMode.Tcp,
            port = DEFAULT_TCP_PORT,
            bufferSize = 8192
        )

        tcpChannel = host.createDataChannel(CHANNEL_TCP_CONTROL, config)

        val channel = tcpChannel ?: return
        val result = channel.bind(DEFAULT_TCP_PORT)

        if (result.isSuccess) {
            val actualPort = channel.localPort
            ctx.log("TCP control channel bound to port $actualPort")
            ctx.host.showNotification(
                "iOS Bridge Ready",
                "Please connect iOS device to TCP port $actualPort"
            )
            isRunning = true
            listenTcpControl(channel)
        } else {
            ctx.logError("Failed to bind TCP control channel to port $DEFAULT_TCP_PORT", result.exceptionOrNull())
            ctx.host.showNotification(
                "iOS Bridge Error",
                "Failed to bind to port $DEFAULT_TCP_PORT. Please check if port is in use."
            )
        }
    }

    private suspend fun startUdpAudioChannel() {
        val ctx = context ?: return
        val host = ctx.host

        val config = DataChannelConfig(
            mode = DataChannelMode.Udp,
            port = DEFAULT_UDP_PORT,
            bufferSize = 65536
        )

        udpChannel = host.createDataChannel(CHANNEL_UDP_AUDIO, config)

        val channel = udpChannel ?: return
        val result = channel.bind(DEFAULT_UDP_PORT)

        if (result.isSuccess) {
            ctx.log("UDP audio channel bound to port ${channel.localPort}")
            listenUdpAudio(channel)
        } else {
            ctx.logError("Failed to bind UDP audio channel to port $DEFAULT_UDP_PORT", result.exceptionOrNull())
        }
    }

    private fun listenTcpControl(channel: PluginDataChannel) {
        val ctx = context ?: return

        pluginScope?.launch {
            channel.receive().collectLatest { data ->
                try {
                    handleTcpMessage(data, channel)
                } catch (e: Exception) {
                    ctx.logError("Error handling TCP message", e)
                }
            }
        }
    }

    private fun listenUdpAudio(channel: PluginDataChannel) {
        val ctx = context ?: return

        pluginScope?.launch {
            channel.receive().collectLatest { data ->
                try {
                    handleUdpAudio(data)
                } catch (e: Exception) {
                    ctx.logError("Error handling UDP audio", e)
                }
            }
        }
    }

    private suspend fun handleTcpMessage(data: ByteArray, channel: PluginDataChannel) {
        val header = IosProtocol.parseHeader(data) ?: return

        val payload = if (header.payloadLength > 0) {
            data.copyOfRange(16, 16 + header.payloadLength.coerceAtMost(data.size - 16))
        } else byteArrayOf()

        when (header.type) {
            IosProtocol.MessageType.Hello.value -> handleHello(payload, channel)
            IosProtocol.MessageType.KeepAlive.value -> handleKeepAlive(channel)
            IosProtocol.MessageType.Disconnect.value -> handleDisconnect(payload)
            else -> {}
        }
    }

    private suspend fun handleHello(payload: ByteArray, channel: PluginDataChannel) {
        val ctx = context ?: return

        val hello = IosProtocol.parseHelloPayload(payload) ?: run {
            ctx.logError("Failed to parse HELLO message", null)
            return
        }

        ctx.log("iOS device connected: ${hello.deviceName} (${hello.deviceId})")
        audioEffectProvider.setAudioFormat(hello.sampleRate, hello.channelCount)

        val udpPort = udpChannel?.localPort ?: 0
        val ack = IosProtocol.encodeAck(true, udpPort, "Connected to MicYou PC")
        channel.send(ack)

        // 显示连接成功通知
        ctx.host.showNotification("iOS Bridge", "Device '${hello.deviceName}' connected and ready for audio streaming.")
        ctx.host.showSnackbar("iOS设备 '${hello.deviceName}' 已连接")
    }

    private suspend fun handleKeepAlive(channel: PluginDataChannel) {
        val response = IosProtocol.encodeKeepAlive(++sequenceCounter)
        channel.send(response)
    }

    private fun handleDisconnect(payload: ByteArray) {
        val ctx = context ?: return
        ctx.log("iOS device disconnected")
        audioEffectProvider.reset()
    }

    private fun handleUdpAudio(data: ByteArray) {
        val header = IosProtocol.parseHeader(data) ?: return
        if (header.type != IosProtocol.MessageType.AudioFrame.value) return

        val payload = if (header.payloadLength > 0) {
            data.copyOfRange(16, 16 + header.payloadLength.coerceAtMost(data.size - 16))
        } else byteArrayOf()

        if (payload.isEmpty()) return

        val frame = IosProtocol.parseAudioFramePayload(payload) ?: return
        if (frame.pcmData.isNotEmpty()) {
            audioEffectProvider.addPcm16LeData(frame.pcmData)
        }
    }

    private fun stopChannels() {
        isRunning = false

        pluginScope?.launch {
            tcpChannel?.close()
            udpChannel?.close()
        }

        val ctx = context
        val host = ctx?.host
        host?.closeDataChannel(CHANNEL_TCP_CONTROL)
        host?.closeDataChannel(CHANNEL_UDP_AUDIO)

        tcpChannel = null
        udpChannel = null

        audioEffectProvider.reset()
        context?.log("IOSBridgePlugin channels stopped")
    }
}
