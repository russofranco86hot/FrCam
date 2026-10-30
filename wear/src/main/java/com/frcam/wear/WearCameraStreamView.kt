package com.frcam.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.StreamProtocol
import com.frcam.app.protocol.V380NativeProtocol
import com.frcam.app.protocol.V720StreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reproductor de video ultraliviano para Wear OS.
 * Soporta cámaras V380 Pro nativo (con audio G.711A), Ring Cloud snapshots,
 * cámaras V720/MJPEG y flujos RTSP/ONVIF estándar con ExoPlayer, reintentos automáticos
 * y toque para reconectar.
 */
@OptIn(UnstableApi::class)
@Composable
fun WearCameraStreamView(
    camera: CameraConfig,
    isMuted: Boolean = true,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isConnecting by remember(camera.id) { mutableStateOf(true) }
    var errorMessage by remember(camera.id) { mutableStateOf<String?>(null) }
    var currentBitmap by remember(camera.id) { mutableStateOf<Bitmap?>(null) }
    var retryKey by remember(camera.id) { mutableIntStateOf(0) }

    val isV380 = camera.isV380NativeProtocol()
    val isRing = camera.brand == CameraBrand.RING_CLOUD
    val isV720 = camera.isV720NativeProtocol()
    val isMjpeg = !isV380 && !isRing && !isV720 && (
        camera.protocol == StreamProtocol.HTTP_MJPEG ||
        camera.mainStreamPath.contains("mjpeg", ignoreCase = true) ||
        camera.mainStreamPath.contains("video", ignoreCase = true) ||
        camera.mainStreamPath.contains("stream", ignoreCase = true)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ComposeColor.Black)
            .then(
                if (errorMessage != null) {
                    Modifier.clickable {
                        errorMessage = null
                        isConnecting = true
                        retryKey++
                    }
                } else if (onTap != null) {
                    Modifier.clickable { onTap() }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            // 1. Cámaras Ring Cloud (Streaming MJPEG en tiempo real + Audio L16 + Snapshots dinámicos)
            isRing -> {
                val audioPlayer = remember { WearCameraAudioPlayer(8000) }
                val doorbotId = remember(camera.deviceId) {
                    camera.deviceId.filter { it.isDigit() }.toLongOrNull() ?: 0L
                }

                DisposableEffect(audioPlayer) {
                    onDispose {
                        audioPlayer.release()
                    }
                }

                LaunchedEffect(isMuted) {
                    audioPlayer.setMuted(isMuted)
                }

                LaunchedEffect(isMuted, doorbotId) {
                    if (!isMuted && doorbotId > 0L) {
                        val currentPhoneHost = (WearCameraStorage.lastKnownPhoneIp ?: camera.remoteHost.ifBlank { camera.host })
                            .trim().removePrefix("http://").substringBefore(":")
                        if (currentPhoneHost.isNotBlank() && !currentPhoneHost.endsWith(".")) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val audioUrl = "http://$currentPhoneHost:49880/ring_audio?id=$doorbotId"
                                    val conn = (URL(audioUrl).openConnection() as HttpURLConnection).apply {
                                        connectTimeout = 3000
                                        readTimeout = 4000
                                    }
                                    if (conn.responseCode in 200..299) {
                                        val input = BufferedInputStream(conn.inputStream, 4096)
                                        val buffer = ByteArray(640)
                                        while (isActive && !isMuted) {
                                            val read = input.read(buffer)
                                            if (read <= 0) break
                                            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                                            audioPlayer.playAudioFrame(chunk, isAlaw = false)
                                        }
                                    }
                                    conn.disconnect()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                LaunchedEffect(camera.id, retryKey) {
                    isConnecting = true
                    errorMessage = null
                    val token = camera.password.trim()
                    val snapUrl = camera.customStreamUrl.takeIf { it.startsWith("http") }

                    var failCount = 0
                    var updateCounter = 0

                    while (isActive) {
                        val currentPhoneHost = (WearCameraStorage.lastKnownPhoneIp ?: camera.remoteHost.ifBlank { camera.host })
                            .trim().removePrefix("http://").substringBefore(":")

                        try {
                            // Flujo 1: Si hay servidor del celular, conectar al stream continuo MJPEG en vivo (10 FPS)
                            val streamUrl = if (!snapUrl.isNullOrBlank() && snapUrl.contains("ring_stream")) {
                                snapUrl
                            } else if (doorbotId > 0L && currentPhoneHost.isNotBlank() && !currentPhoneHost.endsWith(".")) {
                                "http://$currentPhoneHost:49880/ring_stream?id=$doorbotId"
                            } else null

                            var streamSuccess = false
                            if (!streamUrl.isNullOrBlank()) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                                            connectTimeout = 3000
                                            readTimeout = 4000
                                        }
                                        if (conn.responseCode in 200..299) {
                                            streamSuccess = true
                                            failCount = 0
                                            val input = BufferedInputStream(conn.inputStream, 32768)
                                            val readBuf = ByteArray(8192)
                                            val frameBuf = ByteArrayOutputStream(65536)
                                            var inFrame = false
                                            var prevByte = -1

                                            while (isActive) {
                                                val count = input.read(readBuf)
                                                if (count <= 0) break

                                                for (i in 0 until count) {
                                                    val b = readBuf[i].toInt() and 0xFF
                                                    if (!inFrame) {
                                                        if (prevByte == 0xFF && b == 0xD8) {
                                                            inFrame = true
                                                            frameBuf.reset()
                                                            frameBuf.write(0xFF)
                                                            frameBuf.write(0xD8)
                                                        }
                                                    } else {
                                                        frameBuf.write(b)
                                                        if (prevByte == 0xFF && b == 0xD9) {
                                                            val jpegBytes = frameBuf.toByteArray()
                                                            val streamBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                                            if (streamBmp != null) {
                                                                withContext(Dispatchers.Main) {
                                                                    currentBitmap = streamBmp
                                                                    isConnecting = false
                                                                    errorMessage = null
                                                                }
                                                            }
                                                            inFrame = false
                                                        }
                                                    }
                                                    prevByte = b
                                                }
                                            }
                                        }
                                        conn.disconnect()
                                    }
                                } catch (_: Exception) {}
                            }

                            // Flujo 2: Si el stream continuo no está disponible, consultar frame individual
                            if (!streamSuccess) {
                                val bmp = withContext(Dispatchers.IO) {
                                    val now = System.currentTimeMillis()

                                    // Opción A: Servidor local del celular en LAN (/ring_snapshot)
                                    if (doorbotId > 0L && currentPhoneHost.isNotBlank() && !currentPhoneHost.endsWith(".")) {
                                        try {
                                            val conn = (URL("http://$currentPhoneHost:49880/ring_snapshot?id=$doorbotId")
                                                .openConnection() as HttpURLConnection).apply {
                                                connectTimeout = 1800
                                                readTimeout = 2000
                                                setRequestProperty("Accept", "image/jpeg, */*")
                                            }
                                            if (conn.responseCode in 200..299) {
                                                val bytes = conn.inputStream.readBytes()
                                                conn.disconnect()
                                                if (bytes.isNotEmpty()) {
                                                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                    if (decoded != null) return@withContext decoded
                                                }
                                            } else {
                                                conn.disconnect()
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    // Opción B: Ring Cloud directo con trigger periódico de actualización
                                    if (doorbotId > 0L && token.isNotBlank()) {
                                        if (updateCounter++ % 3 == 0) {
                                            try {
                                                val upConn = (URL("https://api.ring.com/clients_api/snapshots/update_all").openConnection() as HttpURLConnection).apply {
                                                    connectTimeout = 2000
                                                    readTimeout = 2000
                                                    requestMethod = "PUT"
                                                    doOutput = true
                                                    setRequestProperty("Content-Type", "application/json")
                                                    setRequestProperty("Authorization", "Bearer $token")
                                                    setRequestProperty("User-Agent", "android:com.ringapp")
                                                    outputStream.use { it.write("""{"doorbot_ids":[$doorbotId],"update_all":true}""".toByteArray()) }
                                                }
                                                upConn.responseCode
                                                upConn.disconnect()
                                            } catch (_: Exception) {}
                                        }

                                        val endpoints = listOf(
                                            "https://api.ring.com/clients_api/snapshots/image/$doorbotId?_ts=$now",
                                            "https://app-snaps.ring.com/next/$doorbotId?_t=$now"
                                        )
                                        for (endpoint in endpoints) {
                                            try {
                                                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                                                    connectTimeout = 2500
                                                    readTimeout = 2500
                                                    useCaches = false
                                                    setRequestProperty("Authorization", "Bearer $token")
                                                    setRequestProperty("User-Agent", "android:com.ringapp")
                                                    setRequestProperty("Cache-Control", "no-cache")
                                                    setRequestProperty("Accept", "image/jpeg, */*")
                                                }
                                                if (conn.responseCode in 200..299) {
                                                    val bytes = conn.inputStream.readBytes()
                                                    conn.disconnect()
                                                    if (bytes.isNotEmpty()) {
                                                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                        if (decoded != null) return@withContext decoded
                                                    }
                                                } else {
                                                    conn.disconnect()
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }

                                    null
                                }

                                if (bmp != null) {
                                    currentBitmap = bmp
                                    isConnecting = false
                                    errorMessage = null
                                    failCount = 0
                                } else {
                                    failCount++
                                    if (currentBitmap == null && failCount > 4) {
                                        errorMessage = "Sin conexión a Ring"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            failCount++
                            if (currentBitmap == null && failCount > 4) {
                                errorMessage = e.message ?: "Error conectando con Ring"
                            }
                        }
                        // Reintento o pausa entre ciclos si no hay stream activo
                        delay(400L)
                    }
                }

                currentBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = camera.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                if (isConnecting && currentBitmap == null && errorMessage == null) {
                    CircularProgressIndicator(
                        color = ComposeColor(0xFF38BDF8),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 2. Cámaras V380 Pro Nativo (MediaCodec + AudioTrack G.711A)
            isV380 -> {
                var activeSurface by remember { mutableStateOf<Surface?>(null) }
                val audioPlayer = remember { WearCameraAudioPlayer(8000) }

                DisposableEffect(audioPlayer) {
                    onDispose {
                        audioPlayer.release()
                    }
                }

                LaunchedEffect(isMuted) {
                    audioPlayer.setMuted(isMuted)
                }

                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                    activeSurface = Surface(surfaceTexture)
                                }
                                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    activeSurface?.release()
                                    activeSurface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                LaunchedEffect(camera.id, camera.host, camera.password, activeSurface, retryKey) {
                    val surface = activeSurface ?: return@LaunchedEffect
                    if (!surface.isValid) return@LaunchedEffect

                    isConnecting = true
                    errorMessage = null

                    var codec: MediaCodec? = null
                    var configuredIsH265: Boolean? = null
                    val bufferInfo = MediaCodec.BufferInfo()
                    var ptsUs = 0L

                    fun ensureDecoder(isH265: Boolean, width: Int, height: Int): MediaCodec? {
                        if (codec != null && configuredIsH265 == isH265) return codec
                        try {
                            codec?.stop()
                            codec?.release()
                        } catch (_: Exception) {}
                        return try {
                            val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                            val format = MediaFormat.createVideoFormat(mime, width, height)
                            val newCodec = MediaCodec.createDecoderByType(mime)
                            newCodec.configure(format, surface, null, 0)
                            newCodec.start()
                            codec = newCodec
                            configuredIsH265 = isH265
                            newCodec
                        } catch (_: Exception) {
                            null
                        }
                    }

                    try {
                        val preferCloud = camera.connectionMode == ConnectionMode.CLOUD_DEVICE_ID ||
                            (camera.deviceId.isNotBlank() && (camera.host.isBlank() || camera.host == "0.0.0.0"))

                        V380NativeProtocol.runLiveStreamSession(
                            cameraId = camera.id,
                            deviceIdStr = camera.deviceId,
                            hostIp = camera.host,
                            port = camera.rtspPort,
                            username = camera.username,
                            password = camera.password,
                            preferCloud = preferCloud,
                            hdQuality = false,
                            scope = this,
                            onStatus = {},
                            onResolution = { _, _, _ -> },
                            onVideoNalFrame = { nalBytes, isH265 ->
                                val dec = ensureDecoder(isH265, 640, 360) ?: return@runLiveStreamSession
                                try {
                                    val inIndex = dec.dequeueInputBuffer(15_000L)
                                    if (inIndex >= 0) {
                                        val inputBuffer = dec.getInputBuffer(inIndex)
                                        if (inputBuffer != null) {
                                            inputBuffer.clear()
                                            inputBuffer.put(nalBytes, 0, nalBytes.size.coerceAtMost(inputBuffer.capacity()))
                                            ptsUs += 40_000L
                                            dec.queueInputBuffer(inIndex, 0, nalBytes.size.coerceAtMost(inputBuffer.capacity()), ptsUs, 0)
                                        }
                                    }

                                    var outIndex = dec.dequeueOutputBuffer(bufferInfo, 10_000L)
                                    while (outIndex >= 0) {
                                        dec.releaseOutputBuffer(outIndex, true)
                                        isConnecting = false
                                        outIndex = dec.dequeueOutputBuffer(bufferInfo, 0L)
                                    }
                                } catch (_: Exception) {}
                            },
                            onAudioPacket = { audioBytes, packetType ->
                                audioPlayer.playAudioFrame(audioBytes, isAlaw = (packetType == 0x16.toByte() || packetType == 0x1A.toByte()))
                            },
                            onError = { err ->
                                errorMessage = err
                                isConnecting = false
                            }
                        )
                    } finally {
                        try {
                            codec?.stop()
                            codec?.release()
                        } catch (_: Exception) {}
                    }
                }
            }

            // 3. Cámaras V720 / Naxclow nativo (UDP P2P / HTTP)
            isV720 -> {
                LaunchedEffect(camera.id, camera.host, retryKey) {
                    isConnecting = true
                    errorMessage = null
                    V720StreamEngine.streamV720Camera(
                        camera = camera,
                        onFrame = { bmp ->
                            currentBitmap = bmp
                            isConnecting = false
                            errorMessage = null
                        },
                        onError = { err ->
                            if (currentBitmap == null) {
                                errorMessage = err
                                isConnecting = false
                            }
                        }
                    )
                }

                currentBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = camera.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 4. Flujos HTTP MJPEG estándar
            isMjpeg -> {
                LaunchedEffect(camera.id, camera.host, retryKey) {
                    isConnecting = true
                    errorMessage = null
                    val candidateUrls = camera.buildCandidateUrls(useSubStream = true)

                    withContext(Dispatchers.IO) {
                        for (url in candidateUrls) {
                            if (!isActive) break
                            try {
                                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 3500
                                    readTimeout = 5000
                                    requestMethod = "GET"
                                    if (camera.username.isNotBlank()) {
                                        val auth = Base64.encodeToString("${camera.username}:${camera.password}".toByteArray(), Base64.NO_WRAP)
                                        setRequestProperty("Authorization", "Basic $auth")
                                    }
                                }
                                conn.connect()
                                if (conn.responseCode in 200..299) {
                                    val input = BufferedInputStream(conn.inputStream, 16384)
                                    val buffer = ByteArrayOutputStream(65536)
                                    var prevByte = -1
                                    var capturing = false

                                    while (isActive) {
                                        val b = input.read()
                                        if (b == -1) break

                                        if (!capturing) {
                                            if (prevByte == 0xFF && b == 0xD8) {
                                                capturing = true
                                                buffer.reset()
                                                buffer.write(0xFF)
                                                buffer.write(0xD8)
                                            }
                                        } else {
                                            buffer.write(b)
                                            if (prevByte == 0xFF && b == 0xD9) {
                                                val jpegBytes = buffer.toByteArray()
                                                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                                if (bmp != null) {
                                                    currentBitmap = bmp
                                                    isConnecting = false
                                                    errorMessage = null
                                                }
                                                capturing = false
                                            } else if (buffer.size() > 1_500_000) {
                                                capturing = false
                                                buffer.reset()
                                            }
                                        }
                                        prevByte = b
                                    }
                                    conn.disconnect()
                                    return@withContext
                                }
                                conn.disconnect()
                            } catch (_: Exception) {}
                        }
                        if (currentBitmap == null) {
                            errorMessage = "Sin señal de video V720"
                            isConnecting = false
                        }
                    }
                }

                currentBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = camera.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 4. Cámaras estándar RTSP / ONVIF con rotación de URLs candidatas
            else -> {
                var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
                var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }
                val candidateUrls = remember(camera) { camera.buildCandidateUrls(useSubStream = true) }
                var candidateIndex by remember(camera.id, retryKey) { mutableIntStateOf(0) }

                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                LaunchedEffect(playerRef, isMuted) {
                    playerRef?.volume = if (isMuted) 0f else 1f
                }

                DisposableEffect(camera.id, retryKey) {
                    val loadControl = DefaultLoadControl.Builder()
                        .setBufferDurationsMs(400, 1200, 250, 400)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()

                    val player = ExoPlayer.Builder(context)
                        .setLoadControl(loadControl)
                        .build()

                    playerRef = player
                    textureViewRef?.let { tv ->
                        player.setVideoTextureView(tv)
                    }

                    player.volume = if (isMuted) 0f else 1f
                    player.playWhenReady = true

                    fun playCandidate(index: Int) {
                        if (index !in candidateUrls.indices) {
                            errorMessage = "Sin señal de video"
                            isConnecting = false
                            return
                        }
                        val url = candidateUrls[index]
                        val mediaItem = MediaItem.fromUri(url)
                        if (url.startsWith("rtsp://", ignoreCase = true)) {
                            val mediaSource = RtspMediaSource.Factory()
                                .setForceUseRtpTcp(true)
                                .createMediaSource(mediaItem)
                            player.setMediaSource(mediaSource)
                        } else {
                            player.setMediaItem(mediaItem)
                        }
                        player.prepare()
                    }

                    playCandidate(candidateIndex)

                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                isConnecting = false
                                errorMessage = null
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (candidateIndex + 1 < candidateUrls.size) {
                                candidateIndex++
                                playCandidate(candidateIndex)
                            } else {
                                errorMessage = "Error de conexión"
                                isConnecting = false
                            }
                        }
                    }
                    player.addListener(listener)

                    onDispose {
                        player.removeListener(listener)
                        player.release()
                        playerRef = null
                    }
                }
            }
        }

        // Overlay de carga
        if (isConnecting && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = ComposeColor(0xFF38BDF8),
                strokeWidth = 2.dp
            )
        }

        // Overlay de reconexión / error con botón de toque
        if (errorMessage != null && currentBitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xD9000000))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reintentar",
                        tint = ComposeColor(0xFF38BDF8),
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = errorMessage ?: "Toca para reconectar",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ComposeColor(0xFFCBD5E1),
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
