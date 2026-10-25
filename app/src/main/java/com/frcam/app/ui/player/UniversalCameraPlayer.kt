package com.frcam.app.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.frcam.app.media.CameraAudioPlayer
import com.frcam.app.media.FrameMotionDetector
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.StreamProtocol
import com.frcam.app.model.TransportMode
import com.frcam.app.protocol.NetworkCameraScanner
import com.frcam.app.protocol.RingCloudClient
import com.frcam.app.protocol.RingSignalingSession
import com.frcam.app.protocol.RingWebRtcBridge
import com.frcam.app.protocol.RingWebRtcController
import com.frcam.app.protocol.RingWebRtcHtmlBuilder
import com.frcam.app.protocol.V380NativeProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class StreamState {
    CONNECTING,
    LIVE,
    ERROR
}

/**
 * Handle that allows capturing the current rendered video frame (Bitmap) from any camera player
 * for MP4 video recording, JPEG snapshots, and real-time motion detection.
 */
class CameraFrameCaptureHandle {
    internal var textureViewRef: TextureView? = null
    internal var latestBitmapRef: Bitmap? = null

    fun captureCurrentBitmap(targetWidth: Int = 0, targetHeight: Int = 0): Bitmap? {
        val tv = textureViewRef
        if (tv != null && tv.isAvailable) {
            return try {
                if (targetWidth > 0 && targetHeight > 0) {
                    tv.getBitmap(targetWidth, targetHeight)
                } else {
                    tv.bitmap
                }
            } catch (_: Exception) {
                latestBitmapRef
            }
        }
        return latestBitmapRef
    }
}

@OptIn(UnstableApi::class)
@Composable
fun UniversalCameraPlayer(
    camera: CameraConfig,
    useSubStream: Boolean,
    isMuted: Boolean,
    enablePinchZoom: Boolean = true,
    frameCaptureHandle: CameraFrameCaptureHandle? = null,
    onResolutionChanged: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val internalHandle = remember(camera.id) { frameCaptureHandle ?: CameraFrameCaptureHandle() }
    LaunchedEffect(frameCaptureHandle, internalHandle) {
        if (frameCaptureHandle != null && frameCaptureHandle !== internalHandle) {
            frameCaptureHandle.textureViewRef = internalHandle.textureViewRef
            frameCaptureHandle.latestBitmapRef = internalHandle.latestBitmapRef
        }
    }

    val isV380Native = remember(camera) { camera.isV380NativeProtocol() }
    val isRingNative = remember(camera) { camera.isRingCloudNative() }
    val isOutsideHome = remember(camera.host) {
        !NetworkCameraScanner.isSameLocalSubnet(camera.host)
    }
    val candidateUrls = remember(camera, useSubStream, isOutsideHome) {
        val list = camera.buildCandidateUrls(useSubStream).toMutableList()
        if (isOutsideHome && camera.remoteHost.isNotBlank()) {
            val remoteCandidate = list.firstOrNull { it.contains(camera.remoteHost) }
            if (remoteCandidate != null) {
                list.remove(remoteCandidate)
                list.add(0, remoteCandidate)
            }
        }
        list
    }
    var candidateIndex by remember(camera.id, useSubStream) { mutableIntStateOf(0) }
    var retryCounter by remember { mutableIntStateOf(0) }
    val activeUrl = candidateUrls.getOrElse(candidateIndex) { camera.buildStreamUrl(useSubStream) }

    val isMjpegHttp = remember(activeUrl, camera.protocol, isV380Native, isRingNative) {
        !isV380Native && !isRingNative && (
            camera.protocol == StreamProtocol.HTTP_MJPEG ||
                (activeUrl.startsWith("http://", ignoreCase = true) && !activeUrl.endsWith(".m3u8"))
            )
    }

    var scale by remember(camera.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(camera.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(camera.id) { mutableFloatStateOf(0f) }

    // Real-time Motion Detection Loop (Ring-style push notification with image)
    val motionDetector = remember(camera.id) { FrameMotionDetector() }
    var lastMotionBannerTimeMs by remember(camera.id) { mutableLongStateOf(0L) }
    var motionActiveBanner by remember(camera.id) { mutableStateOf(false) }

    LaunchedEffect(camera.id, camera.motionDetectionEnabled) {
        motionDetector.reset()
        if (!camera.motionDetectionEnabled) {
            motionActiveBanner = false
            return@LaunchedEffect
        }
        while (isActive) {
            delay(650L)
            val frame = internalHandle.captureCurrentBitmap(640, 360)
            if (frame != null && !frame.isRecycled) {
                val score = withContext(Dispatchers.Default) {
                    motionDetector.inspectFrame(
                        context = context,
                        cameraId = camera.id,
                        cameraName = camera.name,
                        frameBitmap = frame
                    )
                }
                if (score >= 4.5f) {
                    lastMotionBannerTimeMs = System.currentTimeMillis()
                    motionActiveBanner = true
                }
            }
            if (motionActiveBanner && (System.currentTimeMillis() - lastMotionBannerTimeMs) > 4000L) {
                motionActiveBanner = false
            }
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color(0xFF070B14))
            .then(
                if (enablePinchZoom) {
                    Modifier.pointerInput(camera.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1.01f) {
                                offsetX = (offsetX + pan.x).coerceIn(-600f, 600f)
                                offsetY = (offsetY + pan.y).coerceIn(-600f, 600f)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                } else Modifier
            )
    ) {
        when {
            isRingNative -> {
                RingCloudStreamSurface(
                    camera = camera,
                    isMuted = isMuted,
                    retryKey = retryCounter,
                    captureHandle = internalHandle,
                    onResolution = { res -> onResolutionChanged?.invoke(res) },
                    onManualRetry = { retryCounter++ },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
            isV380Native -> {
                V380HardwareStreamSurface(
                    camera = camera,
                    useSubStream = useSubStream,
                    isMuted = isMuted,
                    retryKey = retryCounter,
                    captureHandle = internalHandle,
                    onResolution = { res -> onResolutionChanged?.invoke(res) },
                    onManualRetry = { retryCounter++ },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
            isMjpegHttp -> {
                MjpegStreamSurface(
                    streamUrl = activeUrl,
                    username = camera.username,
                    password = camera.password,
                    retryKey = retryCounter,
                    captureHandle = internalHandle,
                    onResolution = { res -> onResolutionChanged?.invoke(res) },
                    onErrorNextCandidate = {
                        if (candidateIndex + 1 < candidateUrls.size) {
                            candidateIndex++
                        }
                    },
                    onManualRetry = {
                        candidateIndex = 0
                        retryCounter++
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
            else -> {
                ExoRtspStreamSurface(
                    streamUrl = activeUrl,
                    forceTcp = camera.transportMode != TransportMode.UDP,
                    isMuted = isMuted,
                    retryKey = retryCounter,
                    captureHandle = internalHandle,
                    onResolution = { res -> onResolutionChanged?.invoke(res) },
                    onErrorNextCandidate = {
                        if (candidateIndex + 1 < candidateUrls.size) {
                            candidateIndex++
                        }
                    },
                    onManualRetry = {
                        candidateIndex = 0
                        retryCounter++
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }

        // Motion Detected Live Alert Badge
        if (motionActiveBanner) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color(0xFFDC2626).copy(alpha = 0.90f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "MOVIMIENTO DETECTADO",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        if (scale > 1.08f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Zoom ${"%.1f".format(scale)}x",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun overlayLiveTimestamp(src: Bitmap, timestampStr: String, cameraLabel: String): Bitmap {
    return try {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val fontSize = (bmp.height * 0.038f).coerceIn(16f, 32f)
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        }
        val bgPaint = Paint().apply {
            color = android.graphics.Color.argb(170, 10, 15, 25)
            style = Paint.Style.FILL
        }

        val text = "$timestampStr | $cameraLabel"
        val textWidth = paint.measureText(text)
        val marginX = bmp.width * 0.02f
        val marginY = bmp.height * 0.03f

        canvas.drawRoundRect(
            marginX - 8f,
            marginY - 6f,
            marginX + textWidth + 16f,
            marginY + fontSize + 10f,
            8f,
            8f,
            bgPaint
        )
        canvas.drawText(text, marginX, marginY + fontSize, paint)
        bmp
    } catch (_: Exception) {
        src
    }
}

@Composable
private fun RingCloudStreamSurface(
    camera: CameraConfig,
    isMuted: Boolean,
    retryKey: Int,
    captureHandle: CameraFrameCaptureHandle,
    onResolution: (String) -> Unit,
    onManualRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var webrtcSession by remember(camera.id, retryKey) { mutableStateOf<RingSignalingSession?>(null) }
    var webrtcFailed by remember(camera.id, retryKey) { mutableStateOf(false) }
    var activeRecordingUrl by remember(camera.id, retryKey) { mutableStateOf<String?>(null) }
    var rawFrame by remember(camera.id, retryKey) { mutableStateOf<Bitmap?>(null) }
    var currentFrame by remember(camera.id, retryKey) { mutableStateOf<Bitmap?>(null) }
    var streamState by remember(camera.id, retryKey) { mutableStateOf(StreamState.CONNECTING) }
    var statusText by remember(camera.id, retryKey) { mutableStateOf("Conectando con Ring WebRTC Live...") }

    // Live continuous clock running second by second
    var liveClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(camera.id) {
        while (isActive) {
            liveClockMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val liveTimestampStr = remember(liveClockMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(liveClockMs))
    }

    // Update frame cleanly without burning duplicate timestamps onto raw bitmap
    LaunchedEffect(rawFrame) {
        val rf = rawFrame ?: return@LaunchedEffect
        currentFrame = rf
        captureHandle.latestBitmapRef = rf
    }

    LaunchedEffect(camera.id, camera.deviceId, retryKey) {
        val doorbotId = camera.deviceId.trim().filter(Char::isDigit).toLongOrNull() ?: 0L
        if (doorbotId <= 0L) {
            streamState = StreamState.ERROR
            statusText = "ID de timbre/cámara Ring inválido"
            return@LaunchedEffect
        }
        streamState = StreamState.CONNECTING
        statusText = "Iniciando WebRTC en vivo Ring ($doorbotId)..."

        // 1. Try to obtain Ring WebRTC Signaling session ticket
        val session = RingCloudClient.requestSignalingTicket(doorbotId)
        if (session != null) {
            webrtcSession = session
            return@LaunchedEffect
        }

        // 2. Fallback to snapshot & recording clip if WebRTC ticket could not be obtained
        webrtcFailed = true
        statusText = "WebRTC no disponible, conectando por Cloud Snapshot/VOD..."

        val recUrl = RingCloudClient.fetchLatestRecordingUrl(doorbotId)
        if (!recUrl.isNullOrBlank()) {
            activeRecordingUrl = recUrl
            onResolution("1080p (Ring Motion HD)")
            streamState = StreamState.LIVE
        }

        var fetchCount = 0
        while (isActive) {
            if (activeRecordingUrl.isNullOrBlank()) {
                val rec = RingCloudClient.fetchLatestRecordingUrl(doorbotId)
                if (!rec.isNullOrBlank()) {
                    activeRecordingUrl = rec
                    onResolution("1080p (Ring Motion HD)")
                    streamState = StreamState.LIVE
                }
            }

            if (activeRecordingUrl.isNullOrBlank()) {
                val ringFrame = RingCloudClient.fetchDeviceSnapshotWithMeta(doorbotId)
                if (ringFrame != null) {
                    val bmp = ringFrame.bitmap
                    if (rawFrame == null) {
                        onResolution("${bmp.width}x${bmp.height} (Ring Live)")
                    }
                    rawFrame = bmp
                    streamState = StreamState.LIVE
                } else if (rawFrame == null) {
                    streamState = StreamState.ERROR
                    statusText = "Inicia sesión en el ícono de Ring Cloud (arriba a la derecha)"
                    break
                }
            }

            fetchCount++
            if (fetchCount % 3 == 0) {
                RingCloudClient.triggerSnapshotUpdate(doorbotId)
                val updatedRec = RingCloudClient.fetchLatestRecordingUrl(doorbotId)
                if (!updatedRec.isNullOrBlank() && updatedRec != activeRecordingUrl) {
                    activeRecordingUrl = updatedRec
                    onResolution("1080p (Ring Motion HD)")
                    streamState = StreamState.LIVE
                }
            }

            delay(1500L)
        }
    }

    Box(modifier = modifier) {
        val activeSession = webrtcSession
        if (activeSession != null && !webrtcFailed) {
            RingWebRtcPlayerView(
                camera = camera,
                session = activeSession,
                isMuted = isMuted,
                onLive = { res ->
                    streamState = StreamState.LIVE
                    onResolution(res)
                },
                onError = { err ->
                    webrtcFailed = true
                    statusText = err
                },
                onFrame = { bmp ->
                    rawFrame = bmp
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (!activeRecordingUrl.isNullOrBlank()) {
            ExoRtspStreamSurface(
                streamUrl = activeRecordingUrl!!,
                forceTcp = false,
                isMuted = isMuted,
                retryKey = retryKey,
                captureHandle = captureHandle,
                onResolution = onResolution,
                onErrorNextCandidate = {
                    activeRecordingUrl = null
                },
                onManualRetry = onManualRetry,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            currentFrame?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Ring Live Cloud View",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Top-Left Live OSD: Single Blinking Live Badge + Live Date & Time
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0xD0000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF22C55E), CircleShape)
            )
            Text(
                text = "LIVE • $liveTimestampStr",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }

        // Top-Right Live OSD: Ring Cloud Cam
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(0xD0000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (webrtcSession != null && !webrtcFailed) "RING WEBRTC LIVE"
                       else if (!activeRecordingUrl.isNullOrBlank()) "RING VIDEO HD"
                       else "RING CLOUD",
                color = Color(0xFF38BDF8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (webrtcSession == null && activeRecordingUrl.isNullOrBlank() && currentFrame == null) {
            StreamOverlayState(
                streamState = streamState,
                streamUrl = "Ring Cloud (${camera.deviceId})",
                errorMessage = statusText,
                onRetry = onManualRetry
            )
        }
    }
}

@Composable
private fun RingWebRtcPlayerView(
    camera: CameraConfig,
    session: RingSignalingSession,
    isMuted: Boolean,
    onLive: (String) -> Unit,
    onError: (String) -> Unit,
    onFrame: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(isMuted) {
        RingWebRtcController.setSpeakerMuted(isMuted)
    }

    DisposableEffect(camera.id) {
        onDispose {
            RingWebRtcController.unregisterSession(camera.id)
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)

                settings.apply {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        try {
                            request?.grant(request.resources)
                        } catch (_: Exception) {
                        }
                    }
                }

                webViewClient = object : WebViewClient() {}

                val bridge = RingWebRtcBridge(
                    onLive = onLive,
                    onError = onError,
                    onFrame = onFrame
                )
                addJavascriptInterface(bridge, "AndroidBridge")

                RingWebRtcController.registerSession(camera.id, this)
                webViewInstance = this

                val html = RingWebRtcHtmlBuilder.buildHtml(session, isMuted)
                loadDataWithBaseURL("https://api.ring.com", html, "text/html", "UTF-8", null)
            }
        },
        update = { wv ->
            webViewInstance = wv
        }
    )
}

@Composable
private fun V380HardwareStreamSurface(
    camera: CameraConfig,
    useSubStream: Boolean,
    isMuted: Boolean,
    retryKey: Int,
    captureHandle: CameraFrameCaptureHandle,
    onResolution: (String) -> Unit,
    onManualRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val audioPlayer = remember(camera.id) { CameraAudioPlayer(8000) }

    LaunchedEffect(isMuted) {
        audioPlayer.setMuted(isMuted)
    }

    var streamState by remember(camera.id, camera.password, useSubStream, retryKey) {
        mutableStateOf(StreamState.CONNECTING)
    }
    var statusDetail by remember(camera.id, camera.password, useSubStream, retryKey) {
        mutableStateOf("Resolviendo cámara V380 (${camera.deviceId.ifBlank { camera.host }})...")
    }
    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    LaunchedEffect(camera.id, camera.deviceId, camera.host, camera.password, useSubStream, retryKey, activeSurface) {
        val surface = activeSurface ?: return@LaunchedEffect
        if (!surface.isValid) return@LaunchedEffect

        streamState = StreamState.CONNECTING
        statusDetail = "Conectando túnel V380..."

        var codec: MediaCodec? = null
        var configuredIsH265: Boolean? = null
        val bufferInfo = MediaCodec.BufferInfo()
        var ptsUs = 0L

        fun ensureDecoder(isH265: Boolean, width: Int, height: Int): MediaCodec? {
            if (codec != null && configuredIsH265 == isH265) return codec
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
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
            val isOutsideHome = !NetworkCameraScanner.isSameLocalSubnet(camera.host)
            val preferCloudRelay = camera.connectionMode == ConnectionMode.CLOUD_DEVICE_ID ||
                (camera.connectionMode == ConnectionMode.HYBRID_AUTO && isOutsideHome)

            V380NativeProtocol.runLiveStreamSession(
                cameraId = camera.id,
                deviceIdStr = camera.deviceId,
                hostIp = camera.host,
                port = camera.rtspPort,
                username = camera.username,
                password = camera.password,
                preferCloud = preferCloudRelay,
                hdQuality = !useSubStream,
                scope = this,
                onStatus = { msg ->
                    statusDetail = msg
                },
                onResolution = { w, h, isH265 ->
                    val codecName = if (isH265) "H.265" else "H.264"
                    onResolution("${w}x${h} ($codecName)")
                },
                onVideoNalFrame = { nalBytes, isH265 ->
                    val dec = ensureDecoder(isH265, 1280, 720) ?: return@runLiveStreamSession
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
                            if (streamState != StreamState.LIVE) {
                                streamState = StreamState.LIVE
                            }
                            outIndex = dec.dequeueOutputBuffer(bufferInfo, 0L)
                        }
                    } catch (_: Exception) {
                    }
                },
                onAudioPacket = { audioBytes, packetType ->
                    if (!isMuted) {
                        audioPlayer.playAudioFrame(audioBytes, isAlaw = (packetType == 0x16.toByte() || packetType == 0x1A.toByte()))
                    }
                },
                onError = { errMsg ->
                    streamState = StreamState.ERROR
                    statusDetail = errMsg
                }
            )
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(camera.id) {
        onDispose {
            try {
                audioPlayer.release()
                captureHandle.textureViewRef = null
                activeSurface?.release()
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    captureHandle.textureViewRef = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            activeSurface = Surface(st)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            activeSurface?.release()
                            activeSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            update = { tv ->
                captureHandle.textureViewRef = tv
            },
            modifier = Modifier.fillMaxSize()
        )

        StreamOverlayState(
            streamState = streamState,
            streamUrl = "V380 ${camera.displayAddressLabel()}",
            errorMessage = statusDetail,
            onRetry = onManualRetry
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoRtspStreamSurface(
    streamUrl: String,
    forceTcp: Boolean,
    isMuted: Boolean,
    retryKey: Int,
    captureHandle: CameraFrameCaptureHandle,
    onResolution: (String) -> Unit,
    onErrorNextCandidate: () -> Unit,
    onManualRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var streamState by remember(streamUrl, retryKey) { mutableStateOf(StreamState.CONNECTING) }
    var errorMessage by remember(streamUrl, retryKey) { mutableStateOf("") }

    val exoPlayer = remember(context) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(450, 2200, 200, 350)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    DisposableEffect(exoPlayer, streamUrl, forceTcp, retryKey) {
        streamState = StreamState.CONNECTING
        errorMessage = ""

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> streamState = StreamState.LIVE
                    Player.STATE_BUFFERING -> {
                        if (streamState != StreamState.LIVE) streamState = StreamState.CONNECTING
                    }
                    Player.STATE_ENDED -> {
                        if (streamUrl.contains(".mp4", ignoreCase = true) || streamUrl.contains("amazonaws.com", ignoreCase = true)) {
                            exoPlayer.seekTo(0)
                            exoPlayer.play()
                        } else {
                            streamState = StreamState.ERROR
                            errorMessage = "Flujo finalizado por la cámara"
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onResolution("${videoSize.width}x${videoSize.height}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                streamState = StreamState.ERROR
                errorMessage = error.localizedMessage ?: "Error RTSP (${error.errorCodeName})"
                onErrorNextCandidate()
            }
        }

        exoPlayer.addListener(listener)

        val mediaItem = MediaItem.fromUri(streamUrl)
        if (streamUrl.startsWith("rtsp://", ignoreCase = true)) {
            val rtspSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(forceTcp)
                .setTimeoutMs(4500L)
                .createMediaSource(mediaItem)
            exoPlayer.setMediaSource(rtspSource)
        } else {
            exoPlayer.setMediaItem(mediaItem)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            captureHandle.textureViewRef = null
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    captureHandle.textureViewRef = this
                    exoPlayer.setVideoTextureView(this)
                }
            },
            update = { textureView ->
                captureHandle.textureViewRef = textureView
                exoPlayer.setVideoTextureView(textureView)
            },
            modifier = Modifier.fillMaxSize()
        )

        StreamOverlayState(
            streamState = streamState,
            streamUrl = streamUrl,
            errorMessage = errorMessage,
            onRetry = onManualRetry
        )
    }
}

@Composable
private fun MjpegStreamSurface(
    streamUrl: String,
    username: String,
    password: String,
    retryKey: Int,
    captureHandle: CameraFrameCaptureHandle,
    onResolution: (String) -> Unit,
    onErrorNextCandidate: () -> Unit,
    onManualRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentFrame by remember(streamUrl, retryKey) { mutableStateOf<Bitmap?>(null) }
    var streamState by remember(streamUrl, retryKey) { mutableStateOf(StreamState.CONNECTING) }
    var errorMessage by remember(streamUrl, retryKey) { mutableStateOf("") }

    LaunchedEffect(streamUrl, retryKey) {
        streamState = StreamState.CONNECTING
        errorMessage = ""
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 6000
                    requestMethod = "GET"
                    if (username.isNotBlank()) {
                        val auth = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $auth")
                    }
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw Exception("HTTP ${conn.responseCode}")
                }

                val input = BufferedInputStream(conn.inputStream, 16384)
                val buffer = ByteArrayOutputStream(65536)
                var prevByte = -1
                var capturingFrame = false

                while (isActive) {
                    val b = input.read()
                    if (b == -1) break

                    if (!capturingFrame) {
                        if (prevByte == 0xFF && b == 0xD8) {
                            capturingFrame = true
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
                                if (currentFrame == null) {
                                    onResolution("${bmp.width}x${bmp.height} MJPEG")
                                }
                                currentFrame = bmp
                                captureHandle.latestBitmapRef = bmp
                                streamState = StreamState.LIVE
                            }
                            capturingFrame = false
                        } else if (buffer.size() > 1_500_000) {
                            capturingFrame = false
                            buffer.reset()
                        }
                    }
                    prevByte = b
                }
                conn.disconnect()
            } catch (e: Exception) {
                streamState = StreamState.ERROR
                errorMessage = e.localizedMessage ?: "Sin señal MJPEG"
                onErrorNextCandidate()
            }
        }
    }

    Box(modifier = modifier) {
        currentFrame?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "MJPEG Live Frame",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        StreamOverlayState(
            streamState = streamState,
            streamUrl = streamUrl,
            errorMessage = errorMessage,
            onRetry = onManualRetry
        )
    }
}

@Composable
private fun StreamOverlayState(
    streamState: StreamState,
    streamUrl: String,
    errorMessage: String,
    onRetry: () -> Unit
) {
    val sanitizedUrl = remember(streamUrl) {
        streamUrl.replace(Regex("://([^:]+):([^@]+)@"), "://$1:***@")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (streamState) {
            StreamState.CONNECTING -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF38BDF8),
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (errorMessage.isNotBlank()) errorMessage else "Conectando cámara...",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = sanitizedUrl,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            StreamState.ERROR -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.VideocamOff,
                        contentDescription = null,
                        tint = Color(0xFFF87171),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sin conexión de video",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (errorMessage.isNotBlank()) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFCA5A5),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reintentar", fontSize = 11.sp)
                    }
                }
            }
            StreamState.LIVE -> {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFF22C55E), CircleShape)
                    )
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
