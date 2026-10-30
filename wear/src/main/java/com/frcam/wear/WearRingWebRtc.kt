package com.frcam.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.frcam.app.model.CameraConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cliente de señalización Ring Live View (WebRTC) adaptado para Wear OS.
 * Permite video en tiempo real a 30 FPS, audio bidireccional y escucha en vivo.
 */
class WearRingLiveSession(
    private val doorbotId: Long,
    private val ticket: String,
    private val onStatus: (String) -> Unit,
    private val onAnswer: (String) -> Unit,
    private val onRemoteIce: (String, Int) -> Unit,
    private val onFailed: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    @Volatile private var isOpen = false
    @Volatile private var sessionId: String? = null
    @Volatile private var closed = false
    private val dialogId = UUID.randomUUID().toString()
    private val pending = ArrayDeque<String>()
    private var pingJob: Job? = null

    fun connect() {
        val url = "wss://api.prod.signalling.ring.devices.a2z.com:443/ws" +
            "?api_version=4.0&auth_type=ring_solutions&client_id=ring_site-${UUID.randomUUID()}&token=$ticket"
        onStatus("Conectando señalización Ring…")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "android:com.ringapp")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                onStatus("Señalización conectada")
                synchronized(pending) {
                    while (pending.isNotEmpty()) webSocket.send(pending.removeFirst())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onFailed("Señalización falló: ${t.localizedMessage ?: response?.code}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) onFailed("Ring cerró sesión ($code)")
            }
        })
    }

    private fun send(json: JSONObject) {
        val s = json.toString()
        if (isOpen) {
            socket?.send(s)
        } else {
            synchronized(pending) { pending.addLast(s) }
        }
    }

    fun sendOffer(sdp: String) {
        onStatus("Enviando oferta WebRTC…")
        send(JSONObject().apply {
            put("method", "live_view")
            put("dialog_id", dialogId)
            put("body", JSONObject().apply {
                put("doorbot_id", doorbotId)
                put("stream_options", JSONObject().apply {
                    put("audio_enabled", true)
                    put("video_enabled", true)
                })
                put("sdp", sdp)
                put("type", "offer")
            })
        })
    }

    fun sendIce(candidate: String, mLineIndex: Int) {
        send(JSONObject().apply {
            put("method", "ice")
            put("dialog_id", dialogId)
            put("body", JSONObject().apply {
                put("doorbot_id", doorbotId)
                put("ice", candidate)
                put("mlineindex", mLineIndex)
                sessionId?.let { put("session_id", it) }
            })
        })
    }

    private fun sessionMessage(method: String, extra: JSONObject = JSONObject()): JSONObject {
        extra.put("doorbot_id", doorbotId)
        extra.put("session_id", sessionId ?: JSONObject.NULL)
        return JSONObject().apply {
            put("method", method)
            put("dialog_id", dialogId)
            put("body", extra)
        }
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val body = msg.optJSONObject("body") ?: JSONObject()
            when (msg.optString("method")) {
                "session_created" -> {
                    sessionId = body.optString("session_id")
                    onStatus("Sesión Ring creada")
                }
                "sdp" -> {
                    onStatus("Recibiendo video...")
                    onAnswer(body.optString("sdp"))
                    send(sessionMessage("activate_session"))
                    startPing()
                }
                "ice" -> onRemoteIce(body.optString("ice"), body.optInt("mlineindex", 0))
                "notification" -> {
                    val t = body.optString("text")
                    onStatus("Ring: $t")
                    if (t == "camera_connected") {
                        send(sessionMessage("camera_options", JSONObject().put("stealth_mode", false)))
                    }
                }
                "close" -> {
                    val reason = body.optJSONObject("reason")
                    onFailed("Cerrado: ${reason?.optString("text") ?: "ok"}")
                }
            }
        } catch (_: Exception) {}
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && !closed) {
                delay(5000)
                send(sessionMessage("ping"))
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { socket?.close(1000, "bye") } catch (_: Exception) {}
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}

/** Control del WebView WebRTC activo en Wear OS */
object WearRingWebRtcController {
    private var webViewRef: WeakReference<WebView>? = null
    private var activeCameraId: String? = null

    fun register(cameraId: String, webView: WebView) {
        activeCameraId = cameraId
        webViewRef = WeakReference(webView)
    }

    fun unregister(cameraId: String) {
        if (activeCameraId == cameraId) {
            activeCameraId = null
            webViewRef = null
        }
    }

    fun setSpeakerMuted(muted: Boolean) {
        val wv = webViewRef?.get() ?: return
        wv.post { wv.evaluateJavascript("window.setSpeakerMuted && window.setSpeakerMuted($muted)", null) }
    }

    fun setMicrophoneEnabled(cameraId: String?, enabled: Boolean) {
        if (cameraId != null && activeCameraId != cameraId) return
        val wv = webViewRef?.get() ?: return
        wv.post { wv.evaluateJavascript("window.setMicEnabled && window.setMicEnabled($enabled)", null) }
    }

    fun deliverAnswer(webView: WebView, sdp: String) {
        webView.post { webView.evaluateJavascript("window.onRingAnswer(${JSONObject.quote(sdp)})", null) }
    }

    fun deliverIce(webView: WebView, candidate: String, mLineIndex: Int) {
        webView.post { webView.evaluateJavascript("window.onRingIce(${JSONObject.quote(candidate)}, $mLineIndex)", null) }
    }
}

class WearRingJsBridge(
    private val session: WearRingLiveSession,
    private val liveCb: (String) -> Unit,
    private val statusCb: (String) -> Unit,
    private val failCb: (String) -> Unit,
    private val frameCb: (Bitmap) -> Unit
) {
    @JavascriptInterface fun sendOffer(sdp: String) = session.sendOffer(sdp)
    @JavascriptInterface fun sendIce(candidate: String, mLineIndex: Int) = session.sendIce(candidate, mLineIndex)
    @JavascriptInterface fun onLive(resolution: String) = liveCb(resolution)
    @JavascriptInterface fun onLog(message: String) = statusCb(message)
    @JavascriptInterface fun onFail(message: String) = failCb(message)

    @JavascriptInterface
    fun onFrame(base64Jpeg: String) {
        try {
            val raw = base64Jpeg.substringAfter(',')
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(frameCb)
        } catch (_: Exception) {}
    }
}

object WearRingWebRtcHtml {
    fun build(mutedInitially: Boolean): String = PAGE.replace("__MUTED__", mutedInitially.toString())

    private const val PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;overflow:hidden}
video{width:100%;height:100%;object-fit:cover;background:transparent;opacity:0}
video::-webkit-media-controls{display:none !important}
video::-webkit-media-controls-start-playback-button{display:none !important}
</style></head><body>
<video id="v" autoplay playsinline></video>
<script>
var A = window.AndroidRing;
var videoEl = document.getElementById('v');
videoEl.muted = __MUTED__;
videoEl.controls = false;
var pc = null, micStream = null, micTrack = null, offerSdp = null;
var remote = new MediaStream();
var remoteSet = false, pendingIce = [];
function log(m){ try{ A.onLog(String(m)); }catch(e){} }

videoEl.addEventListener('playing', function(){
  videoEl.style.opacity = '1';
  try{ A.onLive(videoEl.videoWidth + 'x' + videoEl.videoHeight); }catch(e){}
});
window.setSpeakerMuted = function(m){ videoEl.muted = m; if(!m){ videoEl.play().catch(function(){}); } };
var audioSender = null;
window.setMicEnabled = async function(e){
  try{
    if(e && !micTrack){
      micStream = await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true, noiseSuppression:true}, video:false});
      micTrack = micStream.getAudioTracks()[0];
      if(audioSender){ await audioSender.replaceTrack(micTrack); }
    }
    if(micTrack){ micTrack.enabled = e; }
  }catch(err){ log('Mic: ' + err.message); }
};

function dirs(sdp){
  var res = [], parts = sdp.split(/\r?\n(?=m=)/);
  for(var i=1;i<parts.length;i++){
    var m = parts[i].match(/a=(sendrecv|sendonly|recvonly|inactive)/);
    res.push(m ? m[1] : 'sendrecv');
  }
  return res;
}
function fixAnswer(offer, answer){
  var od = dirs(offer), parts = answer.split(/\r?\n(?=m=)/);
  for(var i=1;i<parts.length;i++){
    if(od[i-1] === 'recvonly'){ parts[i] = parts[i].replace(/a=sendrecv/, 'a=sendonly'); }
  }
  return parts.join('\r\n');
}

window.onRingAnswer = async function(sdp){
  try{
    await pc.setRemoteDescription({type:'answer', sdp: fixAnswer(offerSdp, sdp)});
    remoteSet = true;
    log('Respuesta aplicada, esperando video…');
    for(var i=0;i<pendingIce.length;i++){ try{ await pc.addIceCandidate(pendingIce[i]); }catch(e){} }
    pendingIce = [];
  }catch(e){ A.onFail('SDP: ' + e.message); }
};
window.onRingIce = function(c, idx){
  var cand = {candidate: c, sdpMLineIndex: idx};
  if(!remoteSet){ pendingIce.push(cand); return; }
  pc.addIceCandidate(cand).catch(function(){});
};

async function start(){
  try{
    pc = new RTCPeerConnection({
      iceServers:[{urls:'stun:stun.l.google.com:19302'},{urls:'stun:stun.kinesisvideo.us-east-1.amazonaws.com:443'}],
      bundlePolicy:'max-bundle'
    });
    pc.oniceconnectionstatechange = function(){
      log('ICE: ' + pc.iceConnectionState);
      if(pc.iceConnectionState === 'failed'){ A.onFail('ICE falló'); }
    };
    pc.onicecandidate = function(e){ if(e.candidate){ A.sendIce(e.candidate.candidate, e.candidate.sdpMLineIndex); } };
    pc.ontrack = function(ev){
      remote.addTrack(ev.track);
      if(videoEl.srcObject !== remote){ videoEl.srcObject = remote; }
      videoEl.play().catch(function(){});
      log('Pista recibida: ' + ev.track.kind);
    };

    audioSender = pc.addTransceiver('audio', {direction:'sendrecv'}).sender;
    pc.addTransceiver('video', {direction:'recvonly'});

    var offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    offerSdp = offer.sdp;
    A.sendOffer(offer.sdp);
  }catch(e){ A.onFail('WebRTC: ' + e.message); }
}
window.onload = start;
window.onbeforeunload = function(){ try{ if(pc) pc.close(); }catch(e){} };
</script></body></html>"""
}

/**
 * Vista de streaming Ring en vivo para Wear OS:
 * Combina WebRTC en tiempo real para video y audio + fotos periódicas continuas de respaldo.
 */
@Composable
fun WearRingStreamSurface(
    camera: CameraConfig,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val doorbotId = remember(camera.id) {
        camera.deviceId.filter { it.isDigit() }.toLongOrNull() ?: 0L
    }
    val phoneHost = remember(camera.id) {
        (WearCameraStorage.lastKnownPhoneIp ?: camera.remoteHost.ifBlank { camera.host })
            .trim().removePrefix("http://").substringBefore(":")
    }
    val token = camera.password.trim()

    var latestBitmap by remember(camera.id) { mutableStateOf<Bitmap?>(null) }
    var rtcTicket by remember(camera.id) { mutableStateOf<String?>(null) }
    var rtcLive by remember(camera.id) { mutableStateOf(false) }
    var statusText by remember(camera.id) { mutableStateOf("Conectando con Ring…") }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var webViewRef by remember(rtcTicket) { mutableStateOf<WebView?>(null) }

    // 1. Obtener ticket WebRTC de señalización
    LaunchedEffect(camera.id) {
        if (doorbotId <= 0L) {
            statusText = "ID de Ring inválido"
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            // Intento 1: Pedir ticket al celular vía proxy HTTP local
            if (phoneHost.isNotBlank()) {
                try {
                    val conn = (URL("http://$phoneHost:49880/ring_ticket?id=$doorbotId").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 4000
                    }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        val t = JSONObject(body).optString("ticket")
                        if (t.isNotBlank()) {
                            withContext(Dispatchers.Main) { rtcTicket = t }
                            return@withContext
                        }
                    } else {
                        conn.disconnect()
                    }
                } catch (_: Exception) {}
            }

            // Intento 2: Solicitar ticket directamente a Ring Cloud API con Bearer token
            if (token.isNotBlank()) {
                val endpoints = listOf(
                    "https://prd-api-us.prd.rings.solutions/api/v1/clap/ticket/request/signalsocket",
                    "https://api.ring.com/api/v1/clap/ticket/request/signalsocket"
                )
                for (ep in endpoints) {
                    try {
                        val conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4500
                            readTimeout = 4500
                            requestMethod = "POST"
                            doOutput = true
                            setFixedLengthStreamingMode(0)
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("User-Agent", "android:com.ringapp")
                            setRequestProperty("Accept", "application/json")
                        }
                        conn.outputStream.close()
                        if (conn.responseCode in 200..299) {
                            val json = JSONObject(conn.inputStream.bufferedReader().readText())
                            conn.disconnect()
                            val t = json.optString("ticket")
                            if (t.isNotBlank()) {
                                withContext(Dispatchers.Main) { rtcTicket = t }
                                return@withContext
                            }
                        } else {
                            conn.disconnect()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // 2. Bucle continuo de fotos (snapshots) mientras se conecta o en caso de fallback
    LaunchedEffect(camera.id) {
        val snapUrl = camera.customStreamUrl.takeIf { it.startsWith("http") }

        while (isActive) {
            if (!rtcLive) {
                val bmp = withContext(Dispatchers.IO) {
                    // Intento 1: Proxy celular
                    if (doorbotId > 0L && phoneHost.isNotBlank()) {
                        try {
                            val conn = (URL("http://$phoneHost:49880/ring_snapshot?id=$doorbotId").openConnection() as HttpURLConnection).apply {
                                connectTimeout = 2500
                                readTimeout = 2500
                                setRequestProperty("Accept", "image/jpeg, */*")
                            }
                            if (conn.responseCode in 200..299) {
                                val bytes = conn.inputStream.readBytes()
                                conn.disconnect()
                                if (bytes.isNotEmpty()) {
                                    val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (d != null) return@withContext d
                                }
                            } else {
                                conn.disconnect()
                            }
                        } catch (_: Exception) {}
                    }

                    // Intento 2: URL personalizada
                    if (!snapUrl.isNullOrBlank()) {
                        try {
                            val conn = (URL(snapUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 2500
                                readTimeout = 2500
                            }
                            if (conn.responseCode in 200..299) {
                                val bytes = conn.inputStream.readBytes()
                                conn.disconnect()
                                if (bytes.isNotEmpty()) {
                                    val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (d != null) return@withContext d
                                }
                            } else {
                                conn.disconnect()
                            }
                        } catch (_: Exception) {}
                    }

                    // Intento 3: Ring API directa
                    if (doorbotId > 0L && token.isNotBlank()) {
                        val now = System.currentTimeMillis()
                        val endpoints = listOf(
                            "https://api.ring.com/clients_api/snapshots/image/$doorbotId?_ts=$now",
                            "https://app-snaps.ring.com/next/$doorbotId?_t=$now"
                        )
                        for (ep in endpoints) {
                            try {
                                val conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 3000
                                    readTimeout = 3000
                                    setRequestProperty("Authorization", "Bearer $token")
                                    setRequestProperty("User-Agent", "android:com.ringapp")
                                    setRequestProperty("Accept", "image/jpeg, */*")
                                }
                                if (conn.responseCode in 200..299) {
                                    val bytes = conn.inputStream.readBytes()
                                    conn.disconnect()
                                    if (bytes.isNotEmpty()) {
                                        val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (d != null) return@withContext d
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
                    latestBitmap = bmp
                    statusText = "Snapshot activo"
                }
            }
            delay(1800L)
        }
    }

    // 3. WebRTC Session Management
    val ticket = rtcTicket
    val session = remember(ticket) {
        if (!ticket.isNullOrBlank()) {
            WearRingLiveSession(
                doorbotId = doorbotId,
                ticket = ticket,
                onStatus = { s -> mainHandler.post { statusText = s } },
                onAnswer = { sdp -> webViewRef?.let { WearRingWebRtcController.deliverAnswer(it, sdp) } },
                onRemoteIce = { c, i -> webViewRef?.let { WearRingWebRtcController.deliverIce(it, c, i) } },
                onFailed = { err ->
                    mainHandler.post {
                        rtcLive = false
                        statusText = err
                    }
                }
            )
        } else null
    }

    LaunchedEffect(isMuted) {
        WearRingWebRtcController.setSpeakerMuted(isMuted)
    }

    DisposableEffect(session) {
        session?.connect()
        onDispose {
            WearRingWebRtcController.unregister(camera.id)
            session?.close()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Capa Base: Snapshot
        latestBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = camera.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Capa WebRTC: Video interactivo con sonido y micrófono
        if (session != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.domStorageEnabled = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.let { r -> mainHandler.post { r.grant(r.resources) } }
                            }
                        }
                        webViewClient = WebViewClient()
                        addJavascriptInterface(
                            WearRingJsBridge(
                                session = session,
                                liveCb = {
                                    mainHandler.post {
                                        rtcLive = true
                                        statusText = "Ring en vivo"
                                    }
                                },
                                statusCb = { s -> mainHandler.post { statusText = s } },
                                failCb = { e ->
                                    mainHandler.post {
                                        rtcLive = false
                                        statusText = e
                                    }
                                },
                                frameCb = { bmp -> mainHandler.post { latestBitmap = bmp } }
                            ),
                            "AndroidRing"
                        )
                        WearRingWebRtcController.register(camera.id, this)
                        webViewRef = this
                        loadDataWithBaseURL(
                            "https://app.frcam.local/",
                            WearRingWebRtcHtml.build(isMuted),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                }
            )
        }

        // Indicador de conexión inicial mientras no haya video ni snapshot
        if (!rtcLive && latestBitmap == null) {
            CircularProgressIndicator(
                color = Color(0xFF38BDF8),
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
