package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.concurrent.Volatile

@OptIn(ExperimentalSerializationApi::class)
class IOSBridgePlugin : Plugin, PluginUIProvider {
    override val manifest = PluginManifest(
        id = "com.micyou.plugin.iosbridge",
        name = "iOS To PC",
        version = "0.5.0",
        author = "herbrine8403",
        description = "Bridge MicYou desktop with the iOS companion client",
        minApiVersion = "1.0.0",
        mainClass = "com.micyou.plugin.iosbridge.IOSBridgePlugin",
        tags = listOf("ios", "bridge", "audio"),
        platform = PluginPlatform.DESKTOP
    )

    private var context: PluginContext? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var controlChannel: PluginDataChannel? = null
    private var audioChannel: PluginDataChannel? = null
    private var audioEffectProvider: IOSAudioEffectProvider? = null

    // Single-device mode: only one iOS device allowed at a time
    @Volatile
    private var connectedDevice: IOSDevice? = null
    private val deviceLock = Any()

    private val deviceId: String = "micyou-ios-${UUID.randomUUID()}"
    private val proto = ProtoBuf { }

    // Port information for UI display
    @Volatile
    var controlPort: Int = 0
        private set

    @Volatile
    var audioPort: Int = 0
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    companion object {
        const val CONTROL_CHANNEL_ID = "ios2pc-control"
        const val AUDIO_CHANNEL_ID = "ios2pc-audio"

        // Fixed port range for iOS plugin (avoid conflict with Android's 6000/6001)
        // Android uses: TCP 6000 + UDP 6001 (UDP_PORT_OFFSET = 1)
        // iOS plugin uses: TCP 16000 + UDP 16001
        const val IOS_CONTROL_PORT = 16000
        const val IOS_AUDIO_PORT = 16001

        const val IOS_PACKET_MAGIC = 0x694F5354 // "iOST"
    }

    // ==================== Plugin Lifecycle ====================

    override fun onLoad(ctx: PluginContext) {
        this.context = ctx
        ctx.log("iOS To PC plugin loaded, deviceId=$deviceId")

        controlChannel = ctx.host.createDataChannel(
            id = CONTROL_CHANNEL_ID,
            config = DataChannelConfig(
                mode = DataChannelMode.Tcp,
                bufferSize = 64 * 1024
            )
        )

        audioChannel = ctx.host.createDataChannel(
            id = AUDIO_CHANNEL_ID,
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

        // Bind control channel to FIXED port (avoid conflict with Android)
        scope.launch {
            try {
                val ctrlChannel = controlChannel ?: return@launch
                val result = ctrlChannel.bind(IOS_CONTROL_PORT)
                if (result.isSuccess) {
                    controlPort = ctrlChannel.localPort
                    ctx.log("Control channel bound to port $controlPort")
                    startControlListener(ctrlChannel)
                } else {
                    ctx.logError("Failed to bind control channel to port $IOS_CONTROL_PORT", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                ctx.logError("Control channel setup failed", e)
            }
        }

        // Bind audio channel to FIXED port (avoid conflict with Android)
        scope.launch {
            try {
                val audChannel = audioChannel ?: return@launch
                val result = audChannel.bind(IOS_AUDIO_PORT)
                if (result.isSuccess) {
                    audioPort = audChannel.localPort
                    ctx.log("Audio channel bound to port $audioPort")
                    startAudioListener(audChannel)
                } else {
                    ctx.logError("Failed to bind audio channel to port $IOS_AUDIO_PORT", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                ctx.logError("Audio channel setup failed", e)
            }
        }

        val provider = IOSAudioEffectProvider(deviceId)
        audioEffectProvider = provider
        ctx.host.registerAudioEffect(provider, priority = 5)

        scope.launch {
            delay(500)
            isReady = true
            val ip = getLocalIpAddress()
            ctx.host.showSnackbar("iOS To PC ready - Connect to $ip:$controlPort")
            ctx.log("Plugin ready. Control port: $controlPort, Audio port: $audioPort, IP: $ip")
        }
    }

    override fun onDisable() {
        val ctx = context ?: return
        ctx.log("iOS To PC plugin disabling...")

        isReady = false

        audioEffectProvider?.let { provider ->
            ctx.host.unregisterAudioEffect(provider)
        }
        audioEffectProvider = null

        synchronized(deviceLock) {
            connectedDevice?.close()
            connectedDevice = null
        }

        runBlocking {
            controlChannel?.close()
            audioChannel?.close()
        }

        controlPort = 0
        audioPort = 0

        ctx.log("iOS To PC plugin disabled")
    }

    override fun onUnload() {
        context?.log("iOS To PC plugin unloading...")
        scope.cancel()
        controlChannel = null
        audioChannel = null
        context = null
    }

    // ==================== Plugin UI Provider ====================

    override val hasMainWindow: Boolean = true
    override val windowWidth = 500.dp
    override val windowHeight = 400.dp
    override val windowTitle: String = "iOS To PC Bridge"
    override val windowResizable: Boolean = false

    @Composable
    override fun MainWindow(onClose: () -> Unit) {
        val device by remember { derivedStateOf { connectedDevice } }
        val provider = audioEffectProvider
        val stats by remember { derivedStateOf { provider?.getStats() } }

        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "iOS To PC Bridge",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    ConnectionInfoCard()

                    Text(
                        text = "Connected Device",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (device == null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = "No iOS device connected.\nOpen the MicYou iOS app and connect to this PC.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        DeviceCard(device!!)
                    }

                    if (stats != null) {
                        AudioStatsCard(stats!!)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectionInfoCard() {
        val ip = remember { getLocalIpAddress() }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Connection Info",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "PC IP Address",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = ip,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = if (isReady) "Ready" else "Initializing...",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Control Port (TCP)",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "$IOS_CONTROL_PORT",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Audio Port (UDP)",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "$IOS_AUDIO_PORT",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (isReady) {
                    Text(
                        text = "Enter $ip:$IOS_CONTROL_PORT in the iOS app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    @Composable
    private fun DeviceCard(device: IOSDevice) {
        val isOnline = System.currentTimeMillis() - device.lastSeen < 15000

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isOnline)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${device.sampleRate}Hz, ${device.channels}ch",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isOnline)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = if (isOnline) "Online" else "Offline",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    @Composable
    private fun AudioStatsCard(stats: IOSAudioEffectProvider.AudioStats) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Audio Statistics",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Frames received: ${stats.framesReceived}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Frames dropped: ${stats.framesDropped}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Queue size: ${stats.queueSize}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Active: ${if (stats.isActive) "Yes" else "No"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // ==================== Network Handlers ====================

    private fun startControlListener(channel: PluginDataChannel) {
        scope.launch {
            try {
                channel.receive().collect { data ->
                    try {
                        val message = IosCodec.decodeControlMessage(data)
                        handleControlMessage(message, channel)
                    } catch (e: Exception) {
                        context?.logError("Failed to decode control message", e)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    context?.logError("Control listener error", e)
                }
            }
        }
    }

    private fun startAudioListener(channel: PluginDataChannel) {
        scope.launch {
            try {
                channel.receive().collect { data ->
                    try {
                        processAudioPacket(data)
                    } catch (e: Exception) {
                        context?.logError("Audio packet processing error", e)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    context?.logError("Audio listener error", e)
                }
            }
        }
    }

    /**
     * Handle control messages from iOS.
     * Single-device mode: rejects new connections if already connected.
     */
    private fun handleControlMessage(message: IosControlMessage, channel: PluginDataChannel) {
        when (message.type) {
            IosMessageType.HELLO -> {
                val newDeviceId = message.deviceId ?: return
                val newDeviceName = message.deviceName ?: "Unknown iOS Device"

                synchronized(deviceLock) {
                    // Single-device mode: reject if already connected to another device
                    if (connectedDevice != null && connectedDevice!!.id != newDeviceId) {
                        context?.log("Rejecting connection from $newDeviceName: already connected to ${connectedDevice!!.name}")
                        scope.launch {
                            val reject = IosCodec.encodeControlMessage(
                                IosControlMessage(
                                    type = IosMessageType.ERROR,
                                    deviceId = newDeviceId,
                                    payload = "BUSY: Another device is already connected"
                                )
                            )
                            channel.send(reject)
                        }
                        return
                    }

                    // Accept connection (new or reconnecting same device)
                    val device = IOSDevice(
                        id = newDeviceId,
                        name = newDeviceName,
                        sampleRate = message.sampleRate ?: 48000,
                        channels = message.channels ?: 1
                    )
                    connectedDevice = device
                }

                context?.log("iOS device connected: $newDeviceName ($newDeviceId)")
                context?.host?.showSnackbar("iOS connected: $newDeviceName")

                // Send acknowledgment with fixed ports
                scope.launch {
                    val ack = IosCodec.encodeAck(
                        serverId = deviceId,
                        controlPort = IOS_CONTROL_PORT,
                        audioPort = IOS_AUDIO_PORT
                    )
                    channel.send(ack)
                }
            }
            IosMessageType.KEEPALIVE -> {
                val msgDeviceId = message.deviceId ?: return
                synchronized(deviceLock) {
                    if (connectedDevice?.id == msgDeviceId) {
                        connectedDevice!!.lastSeen = System.currentTimeMillis()
                    }
                }
            }
            IosMessageType.DISCONNECT -> {
                val msgDeviceId = message.deviceId ?: return
                synchronized(deviceLock) {
                    if (connectedDevice?.id == msgDeviceId) {
                        connectedDevice!!.close()
                        connectedDevice = null
                        context?.log("iOS device disconnected: $msgDeviceId")
                        context?.host?.showSnackbar("iOS disconnected")
                    }
                }
            }
            IosMessageType.CONFIG -> {
                context?.log("Received config from iOS: ${message.payload}")
            }
            else -> {}
        }
    }

    private fun processAudioPacket(data: ByteArray) {
        // Single-device mode: ignore audio if no device connected
        synchronized(deviceLock) {
            if (connectedDevice == null) return
        }

        if (data.size < 8) return

        val magic = ByteBuffer.wrap(data, 0, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        if (magic != IOS_PACKET_MAGIC) {
            val micYouMagic = ByteBuffer.wrap(data, 0, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            if (micYouMagic != 0x4D696355) { // "MicU"
                return
            }
        }

        val payloadLength = ByteBuffer.wrap(data, 4, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        if (payloadLength <= 0 || payloadLength > data.size - 8) return

        val payload = data.copyOfRange(8, 8 + payloadLength)

        try {
            val frame = proto.decodeFromByteArray(IosAudioFrame.serializer(), payload)
            audioEffectProvider?.feedAudio(frame)
        } catch (e: Exception) {
            context?.logError("Failed to decode audio frame", e)
        }
    }

    // ==================== Helpers ====================

    private fun getLocalIpAddress(): String {
        return try {
            InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    data class IOSDevice(
        val id: String,
        val name: String,
        val sampleRate: Int,
        val channels: Int,
        @Volatile var lastSeen: Long = System.currentTimeMillis()
    ) {
        fun close() {
            // Cleanup device-specific resources
        }
    }
}
