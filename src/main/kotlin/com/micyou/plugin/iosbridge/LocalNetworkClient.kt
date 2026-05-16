package com.micyou.plugin.iosbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Socket

class LocalNetworkClient(
    private val tcpPort: Int = 6000,
    private val scope: CoroutineScope
) {
    private var tcpSocket: Socket? = null
    private var isConnected = false

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                connect()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun stop() {
        try {
            tcpSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        isConnected = false
    }

    private fun connect() {
        try {
            tcpSocket = Socket("127.0.0.1", tcpPort)

            val output = tcpSocket?.getOutputStream()
            val input = tcpSocket?.getInputStream()

            val check1 = "MicYouCheck1".toByteArray()
            output?.write(check1)
            output?.flush()

            val check2Buf = ByteArray(12)
            val read = input?.read(check2Buf)

            if (read == 12 && String(check2Buf) == "MicYouCheck2") {
                isConnected = true
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
