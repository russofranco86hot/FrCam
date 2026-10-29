package com.frcam.app.wear

import android.content.Context
import android.util.Log
import com.frcam.app.model.CameraConfig
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Motor de sincronización del celular hacia el reloj inteligente (Wear OS).
 * Transmite automáticamente la lista completa de cámaras configuradas mediante
 * Google Play Services Wearable Data Layer (Bluetooth / Wi-Fi local).
 */
object PhoneWearSyncEngine {

    private const val TAG = "PhoneWearSync"
    private const val CAMERAS_PATH = "/frcam/cameras"
    private const val REQUEST_SYNC_PATH = "/frcam/request_sync"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Envía la lista completa de cámaras al reloj.
     */
    fun syncCamerasToWatch(context: Context, cameras: List<CameraConfig>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val camerasJson = json.encodeToString(cameras)

                // 1. DataClient (almacenamiento persistente en la capa Wearable)
                val dataMap = PutDataMapRequest.create(CAMERAS_PATH).apply {
                    dataMap.putString("cameras_json", camerasJson)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                    setUrgent()
                }
                Wearable.getDataClient(context).putDataItem(dataMap.asPutDataRequest()).await()
                Log.d(TAG, "Cámaras enviadas via DataClient: ${cameras.size}")

                // 2. MessageClient (entrega inmediata a todos los relojes conectados)
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()
                val messageClient = Wearable.getMessageClient(context)
                val bytes = camerasJson.toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, CAMERAS_PATH, bytes).await()
                    Log.d(TAG, "Mensaje de cámaras enviado al nodo: ${node.displayName} (${node.id})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo sincronizar con Wear OS: ${e.message}")
            }
        }
    }

    /**
     * Registra un listener para responder cuando el reloj solicita las cámaras de forma manual.
     */
    fun registerListener(context: Context, getCameras: () -> List<CameraConfig>): MessageClient.OnMessageReceivedListener {
        val listener = MessageClient.OnMessageReceivedListener { messageEvent ->
            if (messageEvent.path == REQUEST_SYNC_PATH) {
                Log.d(TAG, "Solicitud de sincronización recibida desde el reloj")
                syncCamerasToWatch(context, getCameras())
            }
        }
        Wearable.getMessageClient(context).addListener(listener)
        return listener
    }

    fun unregisterListener(context: Context, listener: MessageClient.OnMessageReceivedListener) {
        Wearable.getMessageClient(context).removeListener(listener)
    }
}
