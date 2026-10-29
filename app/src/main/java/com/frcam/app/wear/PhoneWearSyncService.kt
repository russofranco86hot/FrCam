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

    override fun onMessageReceived(messageEvent: MessageEvent) {
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
        }
    }
}
