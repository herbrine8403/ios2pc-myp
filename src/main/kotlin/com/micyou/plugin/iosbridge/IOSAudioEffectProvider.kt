package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.AudioConfig
import com.lanrhyme.micyou.plugin.AudioEffectProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class IOSAudioEffectProvider : AudioEffectProvider {

    override val id: String = "com.micyou.plugin.iosbridge.audio"
    override val name: String = "iOS Bridge Audio"
    override val description: String = "Audio input from connected iOS device via UDP bridge"
    override var isEnabled: Boolean = true

    private val audioBuffer = ConcurrentLinkedQueue<ShortArray>()
    private val _isActive = AtomicBoolean(false)

    val isActive: Boolean get() = _isActive.get()

    private var currentSampleRate: Int = 48000
    private var currentChannelCount: Int = 1

    fun addPcm16LeData(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return

        val shorts = ShortArray(pcmBytes.size / 2)
        for (i in shorts.indices) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt() and 0xFF
            shorts[i] = ((high shl 8) or low).toShort()
        }

        if (shorts.isNotEmpty()) {
            audioBuffer.offer(shorts)
            _isActive.set(true)
        }
    }

    fun setAudioFormat(sampleRate: Int, channelCount: Int) {
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
    }

    override fun process(input: ShortArray, channelCount: Int, sampleRate: Int): ShortArray {
        if (!isEnabled) return input

        val bufferedAudio = audioBuffer.poll()
        return if (bufferedAudio != null && bufferedAudio.isNotEmpty()) {
            bufferedAudio
        } else {
            input
        }
    }

    override fun reset() {
        audioBuffer.clear()
        _isActive.set(false)
    }

    override fun release() {
        reset()
    }

    override fun onConfigChanged(config: AudioConfig) {
    }
}
