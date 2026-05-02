package com.micyou.plugin.iosbridge

import com.lanrhyme.micyou.plugin.AudioEffectProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioEffectProvider that injects iOS audio into the MicYou audio pipeline.
 *
 * This provider sits at the beginning of the audio effect chain (low priority)
 * and replaces or mixes the incoming audio with iOS audio data.
 */
class IOSAudioEffectProvider(
    override val id: String = "ios-bridge-audio"
) : AudioEffectProvider {

    override val name: String = "iOS Audio Bridge"
    override val description: String = "Injects audio from connected iOS devices"

    private val _isEnabled = AtomicBoolean(true)
    override var isEnabled: Boolean
        get() = _isEnabled.get()
        set(value) = _isEnabled.set(value)

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // Audio buffer queue for iOS audio frames
    private val audioQueue = ConcurrentLinkedQueue<ShortArray>()

    // Current audio configuration
    @Volatile
    private var currentSampleRate: Int = 48000

    @Volatile
    private var currentChannels: Int = 1

    @Volatile
    private var bufferSize: Int = 480 // 10ms at 48kHz mono

    // Statistics
    private val framesReceived = java.util.concurrent.atomic.AtomicLong(0)
    private val framesDropped = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Feed audio data from iOS into the provider.
     * Called by the plugin when audio packets arrive from iOS.
     */
    fun feedAudio(frame: IosAudioFrame) {
        if (!isEnabled) return

        // Convert PCM16LE bytes to ShortArray
        val samples = pcm16leToShortArray(frame.pcm16le)

        // Update configuration if changed
        if (frame.sampleRate != currentSampleRate || frame.channels != currentChannels) {
            currentSampleRate = frame.sampleRate
            currentChannels = frame.channels
            bufferSize = (currentSampleRate * currentChannels * 10) / 1000 // 10ms buffer
        }

        // Add to queue (limit queue size to prevent memory growth)
        if (audioQueue.size < MAX_QUEUE_SIZE) {
            audioQueue.offer(samples)
            framesReceived.incrementAndGet()
            _isActive.value = true
        } else {
            framesDropped.incrementAndGet()
            // Drop oldest frame to make room
            audioQueue.poll()
            audioQueue.offer(samples)
        }
    }

    /**
     * Process audio data. This is called by the MicYou audio pipeline.
     * When iOS audio is available, it replaces the input audio.
     * When no iOS audio is available, it passes through the original audio.
     */
    override fun process(input: ShortArray, channelCount: Int, sampleRate: Int): ShortArray {
        if (!isEnabled) return input

        val iosAudio = audioQueue.poll()
        return if (iosAudio != null) {
            // Return iOS audio, resampling if necessary
            if (iosAudio.size == input.size && sampleRate == currentSampleRate && channelCount == currentChannels) {
                iosAudio
            } else {
                resample(iosAudio, currentSampleRate, currentChannels, input.size, sampleRate, channelCount)
            }
        } else {
            // No iOS audio available, pass through original
            // If we previously had iOS audio but now don't, mark as inactive
            if (_isActive.value && audioQueue.isEmpty()) {
                _isActive.value = false
            }
            input
        }
    }

    override fun reset() {
        audioQueue.clear()
        framesReceived.set(0)
        framesDropped.set(0)
    }

    override fun release() {
        isEnabled = false
        audioQueue.clear()
        _isActive.value = false
    }

    /**
     * Get statistics for debugging/monitoring.
     */
    fun getStats(): AudioStats = AudioStats(
        framesReceived = framesReceived.get(),
        framesDropped = framesDropped.get(),
        queueSize = audioQueue.size,
        isActive = isActive.value
    )

    /**
     * Convert PCM16LE byte array to ShortArray.
     */
    private fun pcm16leToShortArray(pcmBytes: ByteArray): ShortArray {
        val sampleCount = pcmBytes.size / 2
        return ShortArray(sampleCount) { i ->
            ((pcmBytes[i * 2].toInt() and 0xFF) or
                    ((pcmBytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
    }

    /**
     * Simple linear resampling between different sample rates/channel counts.
     * For production, consider using a proper resampling library.
     */
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
            // Just copy, possibly truncating or padding
            val copySize = minOf(input.size, outputSize)
            System.arraycopy(input, 0, output, 0, copySize)
            return output
        }

        // Simple linear interpolation for sample rate conversion
        val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()
        val channelRatio = inputChannels.toDouble() / outputChannels.toDouble()

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

    data class AudioStats(
        val framesReceived: Long,
        val framesDropped: Long,
        val queueSize: Int,
        val isActive: Boolean
    )

    companion object {
        const val MAX_QUEUE_SIZE = 32 // Maximum audio frames to buffer
    }
}
