package com.frcam.app.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

data class RingSignalingSession(
    val ticket: String,
    val doorbotId: Long,
    val signalingUrl: String
)

/**
 * Controller to manage speaker volume and microphone broadcast for active Ring WebRTC streams.
 */
object RingWebRtcController {
    private var activeWebViewRef: WeakReference<WebView>? = null
    private var activeCameraId: String? = null

    fun registerSession(cameraId: String, webView: WebView) {
        activeCameraId = cameraId
        activeWebViewRef = WeakReference(webView)
    }

    fun unregisterSession(cameraId: String) {
        if (activeCameraId == cameraId) {
            activeCameraId = null
            activeWebViewRef = null
        }
    }

    fun setSpeakerMuted(isMuted: Boolean) {
        val wv = activeWebViewRef?.get() ?: return
        wv.post {
            wv.evaluateJavascript("if (typeof window.setSpeakerMuted === 'function') { window.setSpeakerMuted($isMuted); }", null)
        }
    }

    fun setMicrophoneEnabled(cameraId: String?, enabled: Boolean) {
        if (cameraId != null && activeCameraId != cameraId) return
        val wv = activeWebViewRef?.get() ?: return
        wv.post {
            wv.evaluateJavascript("if (typeof window.setMicEnabled === 'function') { window.setMicEnabled($enabled); }", null)
        }
    }
}

/**
 * JavascriptInterface exposed to the Ring WebRTC WebView instance.
 */
class RingWebRtcBridge(
    private val onLive: (resolution: String) -> Unit,
    private val onError: (error: String) -> Unit,
    private val onFrame: (bitmap: Bitmap) -> Unit
) {
    @JavascriptInterface
    fun onStreamLive(resolution: String) {
        onLive(resolution)
    }

    @JavascriptInterface
    fun onStreamError(error: String) {
        onError(error)
    }

    @JavascriptInterface
    fun onStreamClosed() {
        onError("Conexión finalizada por la cámara Ring")
    }

    @JavascriptInterface
    fun onFrameCaptured(base64Data: String) {
        try {
            val commaIdx = base64Data.indexOf(',')
            val rawB64 = if (commaIdx >= 0) base64Data.substring(commaIdx + 1) else base64Data
            val bytes = android.util.Base64.decode(rawB64, android.util.Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                onFrame(bmp)
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * Builds the HTML5 WebRTC client that connects directly to Ring Cloud Signaling 4.0.
 */
object RingWebRtcHtmlBuilder {

    fun buildHtml(session: RingSignalingSession, isMutedInitially: Boolean): String {
        val doorbotId = session.doorbotId
        val signalingUrl = session.signalingUrl
        val ticket = session.ticket

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
    <style>
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            background-color: #000000;
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        video {
            width: 100%;
            height: 100%;
            object-fit: contain;
            background-color: #000000;
        }
    </style>
</head>
<body>
    <video id="remoteVideo" autoplay playsinline webkit-playsinline></video>
    <script>
        const doorbotId = $doorbotId;
        const signalingUrl = "$signalingUrl";
        let sessionId = null;
        let seq = 1;
        let pc = null;
        let ws = null;
        let micStream = null;
        let micTrack = null;
        let captureTimer = null;
        let isInitialMuted = $isMutedInitially;

        const videoEl = document.getElementById('remoteVideo');
        videoEl.muted = isInitialMuted;

        videoEl.onplaying = () => {
            const res = (videoEl.videoWidth > 0 && videoEl.videoHeight > 0)
                ? (videoEl.videoWidth + "x" + videoEl.videoHeight + " (Ring WebRTC Live)")
                : "1080p (Ring WebRTC Live)";
            if (window.AndroidBridge && window.AndroidBridge.onStreamLive) {
                window.AndroidBridge.onStreamLive(res);
            }
            startFrameCapturing();
        };

        window.setSpeakerMuted = function(muted) {
            if (videoEl) {
                videoEl.muted = muted;
            }
        };

        window.setMicEnabled = function(enabled) {
            if (micTrack) {
                micTrack.enabled = enabled;
            }
            if (ws && ws.readyState === WebSocket.OPEN && sessionId) {
                ws.send(JSON.stringify({
                    method: 'mic_enable',
                    seq: seq++,
                    body: {
                        session_id: sessionId,
                        enabled: enabled
                    }
                }));
            }
        };

        function startFrameCapturing() {
            if (captureTimer) clearInterval(captureTimer);
            captureTimer = setInterval(() => {
                try {
                    if (!videoEl || videoEl.videoWidth === 0 || videoEl.paused) return;
                    const canvas = document.createElement('canvas');
                    const targetW = Math.min(videoEl.videoWidth, 640);
                    const targetH = Math.round(targetW * (videoEl.videoHeight / videoEl.videoWidth));
                    canvas.width = targetW;
                    canvas.height = targetH;
                    const ctx = canvas.getContext('2d');
                    ctx.drawImage(videoEl, 0, 0, targetW, targetH);
                    const b64 = canvas.toDataURL('image/jpeg', 0.80);
                    if (window.AndroidBridge && window.AndroidBridge.onFrameCaptured) {
                        window.AndroidBridge.onFrameCaptured(b64);
                    }
                } catch(e) {}
            }, 1200);
        }

        async function initWebRtc() {
            try {
                // Pre-acquire microphone audio stream for two-way audio
                try {
                    micStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
                    micTrack = micStream.getAudioTracks()[0];
                    if (micTrack) {
                        micTrack.enabled = false; // Muted by default until user presses Talk
                    }
                } catch(e) {
                    console.warn("Local mic not accessible:", e);
                }

                connectWebSocket();
            } catch(e) {
                if (window.AndroidBridge && window.AndroidBridge.onStreamError) {
                    window.AndroidBridge.onStreamError("Error inicializando WebRTC: " + e.message);
                }
            }
        }

        function connectWebSocket() {
            try {
                ws = new WebSocket(signalingUrl);

                ws.onopen = () => {
                    // Send LiveView initiation request
                    ws.send(JSON.stringify({
                        method: 'live_view',
                        seq: seq++,
                        body: {
                            doorbot_id: doorbotId,
                            stream_options: {
                                audio_enabled: true,
                                video_enabled: true
                            }
                        }
                    }));
                };

                ws.onmessage = async (evt) => {
                    try {
                        const msg = JSON.parse(evt.data);

                        if (msg.method === 'ping') {
                            ws.send(JSON.stringify({ method: 'pong', seq: msg.seq }));
                            return;
                        }

                        if (msg.method === 'session_created') {
                            sessionId = msg.body.session_id;
                        } else if (msg.method === 'sdp') {
                            await handleRemoteOffer(msg.body.sdp);
                        } else if (msg.method === 'ice') {
                            if (pc && msg.body.candidate) {
                                try {
                                    await pc.addIceCandidate(new RTCIceCandidate({
                                        candidate: msg.body.candidate,
                                        sdpMLineIndex: msg.body.sdpMLineIndex,
                                        sdpMid: msg.body.sdpMid
                                    }));
                                } catch(_) {}
                            }
                        } else if (msg.method === 'close' || msg.method === 'session_destination') {
                            if (msg.method === 'close' && window.AndroidBridge && window.AndroidBridge.onStreamClosed) {
                                window.AndroidBridge.onStreamClosed();
                            }
                        }
                    } catch(e) {
                        console.error("Error processing signaling message:", e);
                    }
                };

                ws.onerror = (e) => {
                    if (window.AndroidBridge && window.AndroidBridge.onStreamError) {
                        window.AndroidBridge.onStreamError("Error de conexión WebSocket con Ring");
                    }
                };

                ws.onclose = () => {
                    // Closed
                };
            } catch(e) {
                if (window.AndroidBridge && window.AndroidBridge.onStreamError) {
                    window.AndroidBridge.onStreamError("Fallo al conectar WebSocket: " + e.message);
                }
            }
        }

        async function handleRemoteOffer(remoteSdp) {
            const config = {
                iceServers: [
                    { urls: 'stun:stun.kinesisvideo.us-east-1.amazonaws.com:443' },
                    { urls: 'stun:stun.l.google.com:19302' },
                    { urls: 'stun:stun1.l.google.com:19302' }
                ],
                bundlePolicy: 'max-bundle',
                rtcpMuxPolicy: 'require',
                iceTransportPolicy: 'all'
            };

            pc = new RTCPeerConnection(config);

            pc.ontrack = (event) => {
                if (videoEl && event.streams && event.streams[0]) {
                    videoEl.srcObject = event.streams[0];
                    videoEl.play().catch(e => console.warn(e));
                }
            };

            pc.onicecandidate = (event) => {
                if (event.candidate && ws && ws.readyState === WebSocket.OPEN && sessionId) {
                    ws.send(JSON.stringify({
                        method: 'ice',
                        seq: seq++,
                        body: {
                            session_id: sessionId,
                            candidate: event.candidate.candidate,
                            sdpMLineIndex: event.candidate.sdpMLineIndex,
                            sdpMid: event.candidate.sdpMid
                        }
                    }));
                }
            };

            // Add local mic track if present
            if (micTrack && micStream) {
                pc.addTrack(micTrack, micStream);
            } else {
                try {
                    pc.addTransceiver('audio', { direction: 'sendrecv' });
                } catch(_) {}
            }

            try {
                pc.addTransceiver('video', { direction: 'recvonly' });
            } catch(_) {}

            await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: remoteSdp }));

            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);

            // Send client SDP answer
            ws.send(JSON.stringify({
                method: 'sdp',
                seq: seq++,
                body: {
                    session_id: sessionId,
                    sdp: answer.sdp
                }
            }));

            // Activate session
            ws.send(JSON.stringify({
                method: 'activate_session',
                seq: seq++,
                body: {
                    session_id: sessionId
                }
            }));
        }

        window.onload = () => {
            initWebRtc();
        };

        window.onbeforeunload = () => {
            if (captureTimer) clearInterval(captureTimer);
            if (micStream) {
                micStream.getTracks().forEach(t => t.stop());
            }
            if (ws && ws.readyState === WebSocket.OPEN && sessionId) {
                try {
                    ws.send(JSON.stringify({
                        method: 'close_session',
                        seq: seq++,
                        body: { session_id: sessionId }
                    }));
                    ws.close();
                } catch(_) {}
            }
            if (pc) {
                try { pc.close(); } catch(_) {}
            }
        };
    </script>
</body>
</html>
        """.trimIndent()
    }
}
