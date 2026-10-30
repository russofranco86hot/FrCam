package com.frcam.app.protocol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gestor de sesiones WebRTC de Ring "Live View" en segundo plano (headless).
 * Permite que el reloj Wear OS reciba video y audio en tiempo real y transmita
 * micrófono bidireccional hacia la cámara Ring incluso si la app del teléfono
 * está minimizada o con la pantalla apagada.
 */
object RingHeadlessLiveViewManager {
    private const val TAG = "RingHeadlessLiveView"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private class ActiveSession(
        val doorbotId: Long,
        var session: RingLiveSession? = null,
        var webView: WebView? = null,
        val clientCount: AtomicInteger = AtomicInteger(1),
        var cleanupJob: Job? = null
    )

    private val sessions = ConcurrentHashMap<Long, ActiveSession>()

    fun acquire(context: Context, doorbotId: Long) {
        val existing = sessions[doorbotId]
        if (existing != null) {
            existing.cleanupJob?.cancel()
            existing.cleanupJob = null
            val count = existing.clientCount.incrementAndGet()
            Log.d(TAG, "Stream headless reutilizado para doorbot $doorbotId, clientes: $count")
            return
        }

        val active = ActiveSession(doorbotId)
        sessions[doorbotId] = active

        scope.launch {
            try {
                Log.d(TAG, "Iniciando stream headless para doorbot $doorbotId...")
                RingCloudClient.wakeLiveStream(doorbotId)
                val ticket = RingCloudClient.requestSignalingTicket()
                if (ticket.isNullOrBlank()) {
                    Log.w(TAG, "No se pudo obtener ticket de señalización para doorbot $doorbotId")
                    sessions.remove(doorbotId)
                    return@launch
                }

                mainHandler.post {
                    try {
                        val session = RingLiveSession(
                            doorbotId = doorbotId,
                            ticket = ticket,
                            onStatus = { s -> Log.d(TAG, "[$doorbotId] $s") },
                            onAnswer = { sdp ->
                                active.webView?.let { RingWebRtcController.deliverAnswer(it, sdp) }
                            },
                            onRemoteIce = { c, i ->
                                active.webView?.let { RingWebRtcController.deliverIce(it, c, i) }
                            },
                            onFailed = { e ->
                                Log.e(TAG, "[$doorbotId] Falló señalización: $e")
                            }
                        )
                        active.session = session

                        val wv = WebView(context.applicationContext).apply {
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
                                RingJsBridge(
                                    doorbotId = doorbotId,
                                    session = session,
                                    liveCb = { res -> Log.d(TAG, "[$doorbotId] Live WebRTC: $res") },
                                    statusCb = { s -> Log.d(TAG, "[$doorbotId] $s") },
                                    failCb = { e -> Log.e(TAG, "[$doorbotId] $e") },
                                    frameCb = { bmp ->
                                        RingCloudClient.updateLiveFrame(doorbotId, bmp)
                                    }
                                ),
                                "AndroidRing"
                            )
                            RingWebRtcController.register("headless_$doorbotId", this)
                            onResume()
                            resumeTimers()
                            loadDataWithBaseURL(
                                "https://app.frcam.local/",
                                RingWebRtcHtml.build(mutedInitially = false),
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                        active.webView = wv
                        session.connect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error instanciando WebView headless", e)
                        sessions.remove(doorbotId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando sesión headless", e)
                sessions.remove(doorbotId)
            }
        }
    }

    fun release(doorbotId: Long) {
        val active = sessions[doorbotId] ?: return
        val count = active.clientCount.decrementAndGet()
        Log.d(TAG, "Cliente desconectado de stream $doorbotId, restantes: $count")
        if (count <= 0) {
            active.cleanupJob?.cancel()
            active.cleanupJob = scope.launch {
                delay(12000L) // Mantener vivo 12s por si el reloj reconecta
                if (active.clientCount.get() <= 0) {
                    sessions.remove(doorbotId)
                    mainHandler.post {
                        try {
                            RingWebRtcController.unregister("headless_$doorbotId")
                            active.session?.close()
                            active.webView?.destroy()
                            active.webView = null
                            RingCloudClient.clearAudio(doorbotId)
                            Log.d(TAG, "Sesión headless liberada exitosamente para doorbot $doorbotId")
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}
