package com.castlevania.nes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class NesAudioPlayer {

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var audioThread: Thread? = null

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = (AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2).coerceAtLeast(8192)

    fun start() {
        if (isPlaying) return

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            Log.i("NesAudioPlayer", "AudioTrack initialized: sampleRate=$sampleRate, bufferSize=$bufferSize")

            audioThread = Thread({
                val tempBuffer = ShortArray(2048)
                var writeLogCounter = 0
                while (isPlaying) {
                    val read = NativeBridge.nativeGetAudioSamples(tempBuffer)
                    if (read > 0) {
                        val written = audioTrack?.write(tempBuffer, 0, read) ?: 0
                        if (++writeLogCounter % 150 == 1) {
                            Log.i("NesAudioPlayer", "AudioTrack wrote $written shorts (sample[0]=${tempBuffer[0]})")
                        }
                    } else {
                        try {
                            Thread.sleep(4)
                        } catch (ignored: InterruptedException) {
                            break
                        }
                    }
                }
            }, "NesAudioThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            Log.e("NesAudioPlayer", "Error initializing AudioTrack", e)
        }
    }

    fun stop() {
        isPlaying = false
        audioThread?.interrupt()
        audioThread = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e("NesAudioPlayer", "Error stopping AudioTrack", e)
        }
        audioTrack = null
    }
}
