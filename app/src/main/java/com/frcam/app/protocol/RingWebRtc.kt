package com.frcam.app.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cliente de señalización de Ring "Live View" (Signaling 4.0).
 *
 * Protocolo (según python-ring-doorbell `webrtcstream.py`, que replica la app oficial):
 *  1. POST /api/v1/clap/ticket/request/signalsocket  -> {"ticket": "..."}
 *  2. WebSocket wss://api.prod.signalling.ring.devices.a2z.com/ws?...&token=ticket
 *  3. El CLIENTE es el "offerer": envía {"method":"live_view","dialog_id",body:{doorbot_id,stream_options,sdp,type:"offer"}}
 *  4. Ring responde: session_created -> sdp (answer) -> notification(camera_connected) -> ice...
 *  5. Tras el answer el cliente envía activate_session y luego "ping" cada 5 s.
 */
class RingLiveSession(
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
                if (!closed) onFailed("Señalización Ring falló: ${t.localizedMessage ?: response?.code}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) onFailed("Ring cerró la sesión ($code $reason)")
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
                    onStatus("Respuesta SDP recibida")
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
                    onFailed("Ring cerró: ${reason?.optString("text") ?: "sin motivo"} (${reason?.optString("code")})")
                }
            }
        } catch (_: Exception) {
        }
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
        try {
            socket?.close(1000, "bye")
        } catch (_: Exception) {
        }
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}

/** Control del WebView WebRTC activo (altavoz y micrófono). */
object RingWebRtcController {
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
        webView.post {
            webView.evaluateJavascript("window.onRingIce(${JSONObject.quote(candidate)}, $mLineIndex)", null)
        }
    }
}

/** Puente JS -> Kotlin. */
class RingJsBridge(
    private val session: RingLiveSession,
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
        } catch (_: Exception) {
        }
    }
}

object RingWebRtcHtml {
    fun build(mutedInitially: Boolean): String = PAGE.replace("__MUTED__", mutedInitially.toString())

    private const val PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;overflow:hidden}
video{width:100%;height:100%;object-fit:contain;background:transparent;opacity:0}
video::-webkit-media-controls{display:none !important}
video::-webkit-media-controls-start-playback-button{display:none !important}
</style></head><body>
<video id="v" autoplay playsinline></video>
<script>
var A = window.AndroidRing;
var videoEl = document.getElementById('v');
videoEl.muted = __MUTED__;
videoEl.controls = false;
var pc = null, micStream = null, micTrack = null, offerSdp = null, capTimer = null;
var remote = new MediaStream();
var remoteSet = false, pendingIce = [];
function log(m){ try{ A.onLog(String(m)); }catch(e){} }

videoEl.addEventListener('playing', function(){
  videoEl.style.opacity = '1';
  try{ A.onLive(videoEl.videoWidth + 'x' + videoEl.videoHeight); }catch(e){}
  startCapture();
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
  }catch(err){ log('Micrófono: ' + err.message); }
};

function startCapture(){
  if(capTimer) return;
  capTimer = setInterval(function(){
    try{
      if(!videoEl.videoWidth) return;
      var w = Math.min(videoEl.videoWidth, 480);
      var h = Math.round(w * videoEl.videoHeight / videoEl.videoWidth);
      var c = document.createElement('canvas'); c.width = w; c.height = h;
      c.getContext('2d').drawImage(videoEl, 0, 0, w, h);
      A.onFrame(c.toDataURL('image/jpeg', 0.7));
    }catch(e){}
  }, 500);
}

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
    log('Respuesta aplicada, negociando medios…');
    for(var i=0;i<pendingIce.length;i++){ try{ await pc.addIceCandidate(pendingIce[i]); }catch(e){} }
    pendingIce = [];
  }catch(e){ A.onFail('SDP answer: ' + e.message); }
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
      if(pc.iceConnectionState === 'failed'){ A.onFail('Conexión ICE fallida (red bloquea UDP?)'); }
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
