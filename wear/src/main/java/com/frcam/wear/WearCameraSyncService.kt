package com.frcam.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Servicio en segundo plano que escucha los eventos de sincronización del celular en Wear OS.
 */
class WearCameraSyncService : WearableListenerService() {

    companion object {
        private const val TAG = "WearSyncService"
        private const val CAMERAS_PATH = "/frcam/cameras"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == CAMERAS_PATH) {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val json = dataMap.getString("cameras_json")
                    if (!json.isNullOrBlank()) {
                        Log.d(TAG, "Cámaras recibidas via DataEvent en reloj")
                        WearCameraStorage.saveJsonString(applicationContext, json)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando DataEvent", e)
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == CAMERAS_PATH) {
            try {
                val json = String(messageEvent.data, Charsets.UTF_8)
                if (json.isNotBlank()) {
                    Log.d(TAG, "Cámaras recibidas via MessageEvent en reloj")
                    WearCameraStorage.saveJsonString(applicationContext, json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando MessageEvent", e)
            }
        }
    }
}
