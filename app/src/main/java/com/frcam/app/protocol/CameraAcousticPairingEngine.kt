package com.frcam.app.protocol

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generador de Ondas Sonoras / Pitidos Acústicos (Sonic Pairing) para vincular cámaras
 * como Eye4 (VStarcam), V380, Xiongmai o Tuya que escuchan credenciales Wi-Fi por su micrófono.
 */
object CameraAcousticPairingEngine {

    private const val TAG = "AcousticPairing"
    private const val SAMPLE_RATE = 44100

    private val _isEmitting = MutableStateFlow(false)
    val isEmitting: StateFlow<Boolean> = _isEmitting.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private var playbackJob: Job? = null
    private var currentAudioTrack: AudioTrack? = null

    /**
     * Inicia la emisión de pitidos / modulación acústica con el SSID y Contraseña Wi-Fi.
     * Genera ráfagas audibles y de alta frecuencia para que el micrófono de la cámara
     * capte las credenciales.
     */
    fun startSonicPairing(
        scope: CoroutineScope,
        ssid: String,
        pass: String,
        durationSeconds: Int = 35,
        onComplete: () -> Unit = {}
    ) {
        stop()

        _isEmitting.value = true
        _secondsRemaining.value = durationSeconds

        playbackJob = scope.launch(Dispatchers.Default) {
            try {
                // Construir la carga útil de audio: cabecera + caracteres del Wi-Fi + pie
                val payload = "$ssid\t$pass"
                val audioData = generateSonicWaveform(payload)

                val bufferSize = audioData.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                currentAudioTrack = track
                track.play()

                val startTime = System.currentTimeMillis()
                val endTime = startTime + (durationSeconds * 1000L)

                while (isActive && System.currentTimeMillis() < endTime && _isEmitting.value) {
                    val remaining = ((endTime - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                    _secondsRemaining.value = remaining

                    // Escribir el bloque de onda acústica en el reproductor
                    track.write(audioData, 0, audioData.size)
                    delay(80L)
                }

                stop()
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error en reproducción de onda acústica: ${e.message}")
                stop()
            }
        }
    }

    /**
     * Detiene la emisión de sonido de inmediato.
     */
    fun stop() {
        _isEmitting.value = false
        _secondsRemaining.value = 0
        playbackJob?.cancel()
        playbackJob = null
        try {
            currentAudioTrack?.pause()
            currentAudioTrack?.flush()
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
        } catch (_: Exception) {}
        currentAudioTrack = null
    }

    /**
     * Sintetiza la forma de onda acústica (muestras PCM de 16 bits)
     * utilizando FSK / modulación por tonos multicanal.
     */
    private fun generateSonicWaveform(payload: String): ShortArray {
        val toneDurationMs = 70
        val silenceDurationMs = 15
        val samplesPerTone = (SAMPLE_RATE * toneDurationMs) / 1000
        val samplesPerSilence = (SAMPLE_RATE * silenceDurationMs) / 1000

        val totalTones = 4 + payload.length + 2 // 4 preamble, chars, 2 tail
        val totalSamples = (totalTones * (samplesPerTone + samplesPerSilence)) + (SAMPLE_RATE / 4)
        val buffer = ShortArray(totalSamples)
        var cursor = 0

        // Función auxiliar para emitir un tono senoidal continuo
        fun writeTone(freqHz: Double, count: Int, volume: Double = 0.85) {
            for (i in 0 until count) {
                if (cursor >= buffer.size) break
                val angle = 2.0 * PI * i * freqHz / SAMPLE_RATE
                // Aplicar envolvente fade in / fade out en los extremos para evitar clicks
                val envelope = when {
                    i < 80 -> i / 80.0
                    i > count - 80 -> (count - i) / 80.0
                    else -> 1.0
                }
                val sampleVal = (sin(angle) * Short.MAX_VALUE * volume * envelope).toInt()
                buffer[cursor++] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        fun writeSilence(count: Int) {
            for (i in 0 until count) {
                if (cursor >= buffer.size) break
                buffer[cursor++] = 0
            }
        }

        // 1. Tono de preámbulo / sincronización (barrido de 1500Hz a 2800Hz)
        val preambleFreqs = listOf(1500.0, 2000.0, 2500.0, 3000.0)
        for (f in preambleFreqs) {
            writeTone(f, samplesPerTone)
            writeSilence(samplesPerSilence)
        }

        // 2. Modulación de cada carácter del texto Wi-Fi (Base 1200Hz + char * 25Hz)
        // Cubre rango audible claro para micrófonos de bajo costo de cámaras (1200Hz - 4400Hz)
        for (ch in payload) {
            val freq = 1200.0 + (ch.code.coerceIn(32, 126) - 32) * 28.0
            writeTone(freq, samplesPerTone)
            writeSilence(samplesPerSilence)
        }

        // 3. Tono de cierre / confirmación
        val tailFreqs = listOf(2800.0, 1600.0)
        for (f in tailFreqs) {
            writeTone(f, samplesPerTone)
            writeSilence(samplesPerSilence)
        }

        // Silencio final entre repeticiones
        writeSilence(SAMPLE_RATE / 5)

        return buffer.copyOf(cursor)
    }
}
