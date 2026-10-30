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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.frcam.app.model.StreamProtocol
import com.frcam.app.protocol.V380NativeProtocol
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
 * cámaras V720/MJPEG y flujos RTSP/ONVIF estándar con ExoPlayer y control de sonido.
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

    val isV380 = camera.isV380NativeProtocol()
    val isRing = camera.brand == CameraBrand.RING_CLOUD
    val isMjpeg = !isV380 && !isRing && (
        camera.protocol == StreamProtocol.HTTP_MJPEG ||
        camera.brand == CameraBrand.V720_NAXCLOW ||
        camera.mainStreamPath.contains("mjpeg", ignoreCase = true) ||
        camera.mainStreamPath.contains("video", ignoreCase = true) ||
        camera.mainStreamPath.contains("stream", ignoreCase = true)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ComposeColor.Black)
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            // 1. Cámaras Ring Cloud (captura de snapshots periódicos de alta eficiencia)
            isRing -> {
                LaunchedEffect(camera.id) {
                    isConnecting = true
                    errorMessage = null
                    val token = camera.password.trim()
                    val snapUrl = camera.customStreamUrl.takeIf { it.startsWith("http") }
                    val doorbotId = camera.deviceId.filter { it.isDigit() }.toLongOrNull() ?: 0L

                    while (isActive) {
                        try {
                            val bmp = withContext(Dispatchers.IO) {
                                if (!snapUrl.isNullOrBlank()) {
                                    BitmapFactory.decodeStream(URL(snapUrl).openStream())
                                } else if (doorbotId > 0L && token.isNotBlank()) {
                                    val conn = (URL("https://api.ring.com/clients_api/doorbots/$doorbotId/snapshot")
                                        .openConnection() as HttpURLConnection).apply {
                                        connectTimeout = 4000
                                        readTimeout = 4000
                                        setRequestProperty("Authorization", "Bearer $token")
                                        setRequestProperty("User-Agent", "android:com.ringapp")
                                    }
                                    if (conn.responseCode in 200..299) {
                                        conn.inputStream.use { BitmapFactory.decodeStream(it) }
                                    } else {
                                        conn.disconnect()
                                        null
                                    }
                                } else {
                                    null
                                }
                            }
                            if (bmp != null) {
                                currentBitmap = bmp
                                isConnecting = false
                                errorMessage = null
                            } else if (currentBitmap == null) {
                                errorMessage = "Sin snapshot Ring"
                            }
                        } catch (e: Exception) {
                            if (currentBitmap == null) {
                                errorMessage = e.message
                            }
                        }
                        delay(2500L)
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

            // 2. Cámaras V380 Pro Nativo (decodificación por hardware MediaCodec + TextureView + AudioTrack G.711A)
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

                LaunchedEffect(camera.id, camera.host, camera.password, activeSurface) {
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
                        V380NativeProtocol.runLiveStreamSession(
                            cameraId = camera.id,
                            deviceIdStr = camera.deviceId,
                            hostIp = camera.host,
                            port = camera.rtspPort,
                            username = camera.username,
                            password = camera.password,
                            preferCloud = camera.deviceId.isNotBlank() && (camera.host.isBlank() || camera.host == "0.0.0.0"),
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

            // 3. Cámaras V720 / Naxclow y flujos HTTP MJPEG (decodificación directa por frames JPEG)
            isMjpeg -> {
                LaunchedEffect(camera.id, camera.host) {
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

            // 4. Cámaras estándar RTSP / ONVIF / Hikvision / Dahua / Eye4 via ExoPlayer
            else -> {
                var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
                var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }

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

                DisposableEffect(camera.id) {
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

                    val url = camera.buildStreamUrl(useSubStream = true)
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

                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                isConnecting = false
                                errorMessage = null
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            errorMessage = "Error de conexión"
                            isConnecting = false
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

        // Overlay de error
        if (errorMessage != null && currentBitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = ComposeColor(0xFF94A3B8),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
