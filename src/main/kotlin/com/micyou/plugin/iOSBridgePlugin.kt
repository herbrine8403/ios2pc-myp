package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.Plugin
import com.lanrhyme.micyou.plugin.PluginContext
import com.lanrhyme.micyou.plugin.PluginManifest

class IOSBridgePlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.micyou.plugin.iosbridge",
        name = "MicYou iOS Bridge",
        version = "0.1.0",
        author = "MicYou Community",
        description = "Connect MicYou PC to the MicYou iOS companion",
        minApiVersion = "1.0.0",
        mainClass = "com.micyou.plugin.iosbridge.IOSBridgePlugin",
    )

    private var context: PluginContext? = null

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.log("MicYou iOS Bridge loaded")
    }

    override fun onEnable() {
        context?.log("MicYou iOS Bridge enabled")
    }

    override fun onDisable() {
        context?.log("MicYou iOS Bridge disabled")
    }

    override fun onUnload() {
        context?.log("MicYou iOS Bridge unloaded")
        context = null
    }
}
