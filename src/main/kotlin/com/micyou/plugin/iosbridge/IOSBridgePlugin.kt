package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.DataChannelConfig
import com.lanrhyme.micyou.plugin.Plugin
import com.lanrhyme.micyou.plugin.PluginContext
import com.lanrhyme.micyou.plugin.PluginDataChannel
import com.lanrhyme.micyou.plugin.PluginManifest
import java.util.UUID
import kotlin.concurrent.Volatile

class IOSBridgePlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.micyou.plugin.iosbridge",
        name = "iOS To PC",
        version = "0.5.0",
        author = "herbrine8403",
        description = "Bridge MicYou desktop with the iOS companion client",
        minApiVersion = "1.0.0",
        mainClass = "com.micyou.plugin.iosbridge.IOSBridgePlugin"
    )

    private var context: PluginContext? = null
    @Volatile private var controlChannel: PluginDataChannel? = null
    private val deviceId: String = "micyou-ios-${UUID.randomUUID()}"

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.log("iOS To PC loaded")
        controlChannel = context.host.createDataChannel(
            id = "ios2pc-control",
            config = DataChannelConfig(bufferSize = 64 * 1024)
        )
    }

    override fun onEnable() {
        context?.log("iOS To PC enabled: $deviceId")
        val channel = controlChannel ?: return
        val hello = IosCodec.encodeHello(deviceId, "MicYou iOS", 48000, 2)
        val keepAlive = IosCodec.encodeKeepAlive(1)
        runCatching { channel.send(hello) }
        runCatching { channel.send(keepAlive) }
        runCatching { context?.host?.showSnackbar("iOS To PC ready") }
    }

    override fun onDisable() {
        context?.log("iOS To PC disabled")
    }

    override fun onUnload() {
        context?.log("iOS To PC unloaded")
        controlChannel?.let { channel ->
            runCatching { context?.host?.closeDataChannel(channel.id) }
        }
        controlChannel = null
        context = null
    }
}
