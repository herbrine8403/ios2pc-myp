package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.AudioEffectProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class IOSAudioEffectProvider(
    override val id: String = "ios-bridge-audio"
) : AudioEffectProvider {

    override val name: String = "iOS Audio Bridge"
    override val description: String = "Injects audio from connected iOS devices"

    private val _isEnabled = AtomicBoolean(true)
    override var isEnabled: Boolean
        get() = _isEnabled.get()
        set(value) = _isEnabled.set(value)

    private val audioQueue = ConcurrentLinkedQueue<ShortArray>()
    @Volatile
    var sampleRate = 44100
        private set
    @Volatile
    var channelCount = 1
        private set

    private val _isActive = AtomicBoolean(false)
    val isActive: Boolean get() = _isActive.get()

    fun setAudioConfig(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
    }

    fun addPcm16LeData(data: ByteArray) {
        val samples = pcm16leToShortArray(data)
        if (audioQueue.size < 32) {
            audioQueue.offer(samples)
            _isActive.set(true)
        }
    }

    override fun process(input: ShortArray, channelCount: Int, sampleRate: Int): ShortArray {
        if (!isEnabled) return input

        val iosAudio = audioQueue.poll()
        return if (iosAudio != null) {
            if (iosAudio.size == input.size && sampleRate == this.sampleRate && channelCount == this.channelCount) {
                iosAudio
            } else {
                resample(iosAudio, this.sampleRate, this.channelCount, input.size, sampleRate, channelCount)
            }
        } else {
            if (_isActive.get() && audioQueue.isEmpty()) {
                _isActive.set(false)
            }
            input
        }
    }

    override fun reset() {
        audioQueue.clear()
        _isActive.set(false)
    }

    override fun release() {
        isEnabled = false
        audioQueue.clear()
        _isActive.set(false)
    }

    private fun pcm16leToShortArray(pcmBytes: ByteArray): ShortArray {
        val sampleCount = pcmBytes.size / 2
        return ShortArray(sampleCount) { i ->
            ((pcmBytes[i * 2].toInt() and 0xFF) or ((pcmBytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
    }

    private fun resample(
        input: ShortArray,
        inputSampleRate: Int,
        inputChannels: Int,
        outputSize: Int,
        outputSampleRate: Int,
        outputChannels: Int
    ): ShortArray {
        val output = ShortArray(outputSize)

        if (inputSampleRate == outputSampleRate && inputChannels == outputChannels) {
            val copySize = minOf(input.size, outputSize)
            System.arraycopy(input, 0, output, 0, copySize)
            return output
        }

        val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()

        for (i in 0 until outputSize) {
            val inputIndex = (i * ratio).toInt() * inputChannels
            if (inputIndex < input.size) {
                output[i] = input[inputIndex]
            } else {
                output[i] = 0
            }
        }

        return output
    }
}
