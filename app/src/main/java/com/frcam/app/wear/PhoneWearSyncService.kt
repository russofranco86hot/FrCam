package com.frcam.app.wear

import android.util.Log
import com.frcam.app.data.CameraRepository
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Servicio en segundo plano en el celular que escucha solicitudes de sincronización
 * enviadas por el reloj Wear OS incluso si la app del teléfono no está en primer plano.
 */
class PhoneWearSyncService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneWearSyncService"
        private const val REQUEST_SYNC_PATH = "/frcam/request_sync"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            FrCamPhoneBridgeService.start(applicationContext)
        } catch (_: Exception) {}
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            FrCamPhoneBridgeService.start(applicationContext)
        } catch (_: Exception) {}
        if (messageEvent.path == REQUEST_SYNC_PATH) {
            val sourceId = messageEvent.sourceNodeId
            Log.d(TAG, "Solicitud de cámaras recibida en el celular desde nodo: $sourceId")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = CameraRepository(applicationContext)
                    val cameras = repo.camerasFlow.first()
                    PhoneWearSyncEngine.syncCamerasToWatch(applicationContext, cameras, targetNodeId = sourceId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando solicitud de sincronización en celular", e)
                }
            }
        } else if (messageEvent.path == "/frcam/ring_mic") {
            try {
                val b64 = android.util.Base64.encodeToString(messageEvent.data, android.util.Base64.NO_WRAP)
                com.frcam.app.protocol.RingWebRtcController.deliverMicChunk(b64)
            } catch (_: Exception) {}
        } else if (messageEvent.path == "/frcam/set_motion") {
            try {
                val text = String(messageEvent.data, Charsets.UTF_8)
                val parts = text.split(":")
                if (parts.size >= 2) {
                    val id = parts[0]
                    val enabled = parts[1].toBoolean()
                    PhoneWearSyncEngine.onToggleMotion?.invoke(id, enabled, if (enabled) "GIF" else "OFF")
                }
            } catch (_: Exception) {}
        }
    }
}
