package com.frcam.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.protocol.RingWebRtcController
import com.frcam.app.protocol.V380NativeProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.min

object G711Utils {

    // ITU-T G.711 A-law decompression table (8-bit A-law -> 16-bit linear PCM)
    private val aLawToPcmTable = ShortArray(256) { i ->
        var a = i xor 0x55
        var t = (a and 0x0F) shl 4
        val seg = (a and 0x70) shr 4
        when (seg) {
            0 -> t += 8
            1 -> t += 0x108
            else -> t = (t + 0x108) shl (seg - 1)
        }
        (if ((a and 0x80) != 0) t else -t).toShort()
    }

    fun decodeAlawToPcm(alawBytes: ByteArray, offset: Int = 0, length: Int = alawBytes.size - offset): ShortArray {
        val count = min(length, alawBytes.size - offset).coerceAtLeast(0)
        val pcm = ShortArray(count)
        for (i in 0 until count) {
            val unsigned = alawBytes[offset + i].toInt() and 0xFF
            pcm[i] = aLawToPcmTable[unsigned]
        }
        return pcm
    }

    fun encodePcmToAlaw(pcmSamples: ShortArray, count: Int = pcmSamples.size): ByteArray {
        val alaw = ByteArray(count)
        for (i in 0 until count) {
            var sample = pcmSamples[i].toInt()
            val sign = if (sample >= 0) 0x80 else 0x00
            if (sample < 0) sample = -sample
            if (sample > 32767) sample = 32767

            val compressed: Int = when {
                sample >= 16384 -> 0x70 or ((sample shr 10) and 0x0F)
                sample >= 8192 -> 0x60 or ((sample shr 9) and 0x0F)
                sample >= 4096 -> 0x50 or ((sample shr 8) and 0x0F)
                sample >= 2048 -> 0x40 or ((sample shr 7) and 0x0F)
                sample >= 1024 -> 0x30 or ((sample shr 6) and 0x0F)
                sample >= 512 -> 0x20 or ((sample shr 5) and 0x0F)
                sample >= 256 -> 0x10 or ((sample shr 4) and 0x0F)
                else -> sample shr 4
            }
            alaw[i] = ((compressed or sign) xor 0x55).toByte()
        }
        return alaw
    }

    /** G.711 µ-law (PCMU), usado por el intercomunicador ISAPI de Hikvision. */
    fun encodePcmToUlaw(pcmSamples: ShortArray, count: Int = pcmSamples.size): ByteArray {
        val bias = 0x84
        val clip = 32635
        val out = ByteArray(count)
        for (i in 0 until count) {
            var s = pcmSamples[i].toInt()
            val sign = (s shr 8) and 0x80
            if (sign != 0) s = -s
            if (s > clip) s = clip
            s += bias
            var exponent = 7
            var mask = 0x4000
            while (exponent > 0 && (s and mask) == 0) {
                exponent--
                mask = mask shr 1
            }
            val mantissa = (s shr (exponent + 3)) and 0x0F
            out[i] = (sign or (exponent shl 4) or mantissa).inv().toByte()
        }
        return out
    }
}

/** Autenticación HTTP Digest mínima (RFC 2617, qop=auth) para los canales de audio de cámaras IP. */
internal object HttpDigest {
    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun param(challenge: String, name: String): String? =
        Regex("""$name="?([^",]*)"?""", RegexOption.IGNORE_CASE).find(challenge)?.groupValues?.get(1)

    /** Devuelve el valor de la cabecera Authorization para [method] [uri], o null si no hay desafío usable. */
    fun header(method: String, uri: String, user: String, pass: String, challenge: String): String? {
        if (challenge.startsWith("Basic", ignoreCase = true)) {
            val b64 = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
            return "Basic $b64"
        }
        if (!challenge.startsWith("Digest", ignoreCase = true)) return null
        val realm = param(challenge, "realm") ?: return null
        val nonce = param(challenge, "nonce") ?: return null
        val opaque = param(challenge, "opaque")
        val qop = param(challenge, "qop")?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val nc = "00000001"
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
        }
    }

    /** Hace una petición de sondeo para obtener el desafío WWW-Authenticate del equipo. */
    fun probeChallenge(baseUrl: String, path: String): String? {
        return try {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            val challenge = conn.getHeaderField("WWW-Authenticate")
            conn.disconnect()
            if (code == 401) challenge else ""
        } catch (_: Exception) {
            null
        }
    }
}

/** Canal HTTP de audio saliente (hacia el parlante de la cámara) para equipos Hikvision y Dahua. */
class HttpTalkSession private constructor(
    private val conn: HttpURLConnection,
    private val stream: java.io.OutputStream,
    private val onClose: (() -> Unit)?
) {
    fun write(bytes: ByteArray): Boolean = try {
        stream.write(bytes)
        stream.flush()
        true
    } catch (_: Exception) {
        false
    }

    fun close() {
        try { stream.close() } catch (_: Exception) {}
        try { conn.disconnect() } catch (_: Exception) {}
        try { onClose?.invoke() } catch (_: Exception) {}
    }

    companion object {
        /** µ-law para Hikvision, A-law para Dahua. */
        fun open(camera: CameraConfig): Pair<HttpTalkSession, Boolean>? {
            val host = camera.host.trim()
            if (host.isBlank()) return null
            val base = "http://$host:${camera.httpPort}"
            val isHik = camera.brand == CameraBrand.HIKVISION
            val path = if (isHik) "/ISAPI/System/TwoWayAudio/channels/1/audioData"
            else "/cgi-bin/audio.cgi?action=postAudio&httptype=singlepart&channel=1"
            val method = if (isHik) "PUT" else "POST"
            val challenge = HttpDigest.probeChallenge(base, if (isHik) "/ISAPI/System/deviceInfo" else "/cgi-bin/magicBox.cgi?action=getSystemInfo")
                ?: return null

            fun auth(m: String, p: String) = if (challenge.isBlank()) null
            else HttpDigest.header(m, p, camera.username, camera.password, challenge)

            if (isHik) {
                // Abrir el canal bidireccional antes de enviar audio.
                try {
                    val openPath = "/ISAPI/System/TwoWayAudio/channels/1/open"
                    val c = (URL(base + openPath).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        requestMethod = "PUT"
                        auth("PUT", openPath)?.let { setRequestProperty("Authorization", it) }
                        doOutput = true
                        setFixedLengthStreamingMode(0)
                    }
                    c.outputStream.close()
                    val code = c.responseCode
                    c.disconnect()
                    if (code !in 200..299) return null
                } catch (_: Exception) {
                    return null
                }
            }

            return try {
                val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 0
                    requestMethod = method
                    doOutput = true
                    setFixedLengthStreamingMode(999_999_999L)
                    setRequestProperty("Content-Type", if (isHik) "application/octet-stream" else "Audio/G.711A")
                    auth(method, path)?.let { setRequestProperty("Authorization", it) }
                }
                val closer: (() -> Unit)? = if (isHik) {
                    {
                        try {
                            val closePath = "/ISAPI/System/TwoWayAudio/channels/1/close"
                            val c = (URL(base + closePath).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 2000
                                readTimeout = 2000
                                requestMethod = "PUT"
                                auth("PUT", closePath)?.let { setRequestProperty("Authorization", it) }
                                doOutput = true
                                setFixedLengthStreamingMode(0)
                            }
                            c.outputStream.close()
                            c.responseCode
                            c.disconnect()
                        } catch (_: Exception) {}
                    }
                } else null
                HttpTalkSession(conn, conn.outputStream, closer) to isHik
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Real-time audio player for playing the live microphone sound from the camera
 * (e.g. V380 Pro 0x16/0x1A G.711A/PCM audio stream) through the Android phone speaker.
 */
class CameraAudioPlayer(private val sampleRate: Int = 8000) {

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var isMuted = true

    init {
        try {
            // Buffer de ~1 s: con escritura no bloqueante un buffer mínimo descartaba casi todo el audio recibido.
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate * 2)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        try {
            if (muted) {
                if (isPlaying) {
                    audioTrack?.pause()
                    isPlaying = false
                }
            } else {
                if (!isPlaying && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack?.play()
                    isPlaying = true
                }
            }
        } catch (_: Exception) {
        }
    }

    fun playAudioFrame(audioBytes: ByteArray, isAlaw: Boolean = true) {
        if (isMuted || audioTrack == null || audioBytes.isEmpty()) return
        try {
            if (!isPlaying && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
                isPlaying = true
            }

            val pcm: ShortArray = if (isAlaw) {
                // If the packet has a small 4 or 8 byte header, skip it
                val offset = if (audioBytes.size > 16 && (audioBytes[0].toInt() == 0 || audioBytes[1].toInt() == 0)) 4 else 0
                G711Utils.decodeAlawToPcm(audioBytes, offset = offset)
            } else {
                val samples = ShortArray(audioBytes.size / 2)
                for (i in samples.indices) {
                    samples[i] = ((audioBytes[i * 2].toInt() and 0xFF) or (audioBytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
                samples
            }

            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        isPlaying = false
    }
}

/**
 * Real-time intercom broadcaster: captures the user's voice from the phone microphone
 * using hardware AEC (Acoustic Echo Cancellation) and transmits it to the camera speaker.
 */
class CameraIntercomBroadcaster(private val context: Context) {

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _voiceAmplitude = MutableStateFlow(0f)
    val voiceAmplitude: StateFlow<Float> = _voiceAmplitude.asStateFlow()

    fun startBroadcasting(
        camera: CameraConfig,
        scope: CoroutineScope,
        onError: (String) -> Unit
    ) {
        if (_isBroadcasting.value) return

        if (camera.isRingCloudNative()) {
            // El micrófono de Ring viaja por la pista WebRTC del WebView (sin AudioRecord propio)
            _isBroadcasting.value = true
            RingWebRtcController.setMicrophoneEnabled(camera.id, true)
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Se requiere permiso de Micrófono para hablar por la cámara")
            return
        }

        val supportsTalk = camera.isV380NativeProtocol() ||
            camera.brand == CameraBrand.EYE4_VSTARCAM ||
            camera.brand == CameraBrand.HIKVISION ||
            camera.brand == CameraBrand.DAHUA
        if (!supportsTalk) {
            onError(
                "FrCam todavía no puede hablar por cámaras ${camera.brand.displayName}: " +
                    "usa V380, Eye4, Hikvision, Dahua o Ring para el intercomunicador."
            )
            return
        }

        val sampleRate = 8000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(1024)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("No se pudo inicializar el micrófono del celular")
                return
            }

            audioRecord?.startRecording()
            _isBroadcasting.value = true

            recordJob = scope.launch(Dispatchers.IO) {
                val pcmBuffer = ShortArray(320) // 40ms of 8000Hz mono audio
                var seq = 0
                var failedChunks = 0
                var talk: Pair<HttpTalkSession, Boolean>? = null

                if (camera.brand == CameraBrand.HIKVISION || camera.brand == CameraBrand.DAHUA) {
                    talk = HttpTalkSession.open(camera)
                    if (talk == null) {
                        withContext(Dispatchers.Main) {
                            onError(
                                "No se pudo abrir el canal de audio de la cámara (revisa IP, usuario/contraseña y que " +
                                    "tenga habilitado el audio bidireccional)"
                            )
                            stopBroadcasting()
                        }
                        return@launch
                    }
                }

                try {
                    while (isActive && _isBroadcasting.value) {
                        val readSamples = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                        if (readSamples > 0) {
                            // Calculate amplitude for UI visualizer
                            var sum = 0L
                            for (i in 0 until readSamples) {
                                sum += abs(pcmBuffer[i].toInt())
                            }
                            val avg = (sum.toFloat() / (readSamples * 32768f)).coerceIn(0f, 1f)
                            _voiceAmplitude.value = avg

                            val ok: Boolean = when {
                                camera.isV380NativeProtocol() -> {
                                    val alaw = G711Utils.encodePcmToAlaw(pcmBuffer, readSamples)
                                    V380NativeProtocol.sendAudioTalkChunk(camera.id, alaw, seq++)
                                }
                                camera.brand == CameraBrand.EYE4_VSTARCAM -> {
                                    sendEye4AudioTalkChunk(camera, G711Utils.encodePcmToAlaw(pcmBuffer, readSamples))
                                    true
                                }
                                talk != null -> {
                                    val (session, isUlaw) = talk
                                    val bytes = if (isUlaw) G711Utils.encodePcmToUlaw(pcmBuffer, readSamples)
                                    else G711Utils.encodePcmToAlaw(pcmBuffer, readSamples)
                                    session.write(bytes)
                                }
                                else -> true
                            }
                            if (ok) {
                                failedChunks = 0
                            } else if (++failedChunks == 25) {
                                withContext(Dispatchers.Main) {
                                    onError(
                                        if (camera.isV380NativeProtocol())
                                            "Sin sesión de video activa con la cámara: abre la imagen en vivo antes de hablar"
                                        else "La cámara cerró el canal de audio"
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    talk?.first?.close()
                }
            }
        } catch (e: Exception) {
            _isBroadcasting.value = false
            onError("Error de micrófono: ${e.localizedMessage ?: "AudioRecord error"}")
        }
    }

    fun stopBroadcasting() {
        _isBroadcasting.value = false
        _voiceAmplitude.value = 0f
        RingWebRtcController.setMicrophoneEnabled(null, false)
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    private fun sendEye4AudioTalkChunk(camera: CameraConfig, alawBytes: ByteArray) {
        if (camera.host.isBlank()) return
        try {
            val url = "http://${camera.host}:${camera.httpPort}/audiostream.cgi"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1000
                readTimeout = 1000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "audio/basic")
                val creds = "${camera.username}:${camera.password}"
                val b64 = android.util.Base64.encodeToString(creds.toByteArray(), android.util.Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $b64")
            }
            conn.outputStream.use { it.write(alawBytes) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}
