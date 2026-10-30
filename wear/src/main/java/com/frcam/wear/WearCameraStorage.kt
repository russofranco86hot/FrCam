package com.frcam.wear

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.frcam.app.model.CameraConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Almacenamiento local de cámaras en el reloj Wear OS.
 * Mantiene en memoria y en SharedPreferences la lista de cámaras sincronizadas desde el celular.
 */
object WearCameraStorage {

    private const val PREFS_NAME = "frcam_wear_prefs"
    private const val KEY_CAMERAS_JSON = "cameras_json"
    private const val KEY_LAST_PHONE_IP = "last_phone_ip"
    private const val TAG = "WearCameraStorage"

    var lastKnownPhoneIp: String? = null
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _cameras = MutableStateFlow<List<CameraConfig>>(emptyList())
    val cameras: StateFlow<List<CameraConfig>> = _cameras.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastKnownPhoneIp = prefs.getString(KEY_LAST_PHONE_IP, null)
        val raw = prefs.getString(KEY_CAMERAS_JSON, null)
        if (!raw.isNullOrBlank()) {
            try {
                val list = json.decodeFromString<List<CameraConfig>>(raw)
                val activeList = list.map { it.copy(liveInDashboard = true) }
                _cameras.value = activeList
                Log.d(TAG, "Cámaras cargadas desde caché Wear OS: ${activeList.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Error decodificando cámaras en reloj", e)
            }
        }
    }

    fun saveCameras(context: Context, cameraList: List<CameraConfig>) {
        val activeList = cameraList.map { it.copy(liveInDashboard = true) }
        _cameras.value = activeList
        try {
            val raw = json.encodeToString(activeList)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CAMERAS_JSON, raw)
                .apply()
            Log.d(TAG, "Cámaras guardadas con éxito en reloj: ${activeList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando cámaras en reloj", e)
        }
    }

    fun saveJsonString(context: Context, camerasJson: String): Int {
        return try {
            val list = json.decodeFromString<List<CameraConfig>>(camerasJson)
            saveCameras(context, list)
            list.size
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando json recibido en reloj: ${e.message}", e)
            0
        }
    }

    fun setLastPhoneIp(context: Context, ip: String) {
        if (ip.isNotBlank() && ip != lastKnownPhoneIp) {
            lastKnownPhoneIp = ip
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_PHONE_IP, ip)
                    .apply()
            } catch (_: Exception) {}
        }
    }
}
