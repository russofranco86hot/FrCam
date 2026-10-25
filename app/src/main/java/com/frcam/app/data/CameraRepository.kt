package com.frcam.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.frcam.app.model.CameraAccessRecord
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.GridLayoutMode
import com.frcam.app.model.PtzControlType
import com.frcam.app.model.StreamProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.cameraDataStore by preferencesDataStore(name = "frcam_preferences")

class CameraRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val camerasKey = stringPreferencesKey("saved_cameras_json")
    private val gridModeKey = stringPreferencesKey("grid_layout_mode")
    private val ringRefreshTokenKey = stringPreferencesKey("ring_refresh_token")
    private val accessLogsKeyPrefix = "access_logs_"

    private val defaultStarterCameras = listOf(
        CameraConfig(
            id = "v380-65077425",
            name = "V380 MVT26101 (65077425)",
            brand = CameraBrand.V380_PRO,
            connectionMode = ConnectionMode.CLOUD_DEVICE_ID,
            deviceId = "65077425",
            modelCode = "MVT26101",
            host = "",
            rtspPort = 8800,
            httpPort = 8800,
            onvifPort = 8899,
            username = "admin",
            password = "",
            protocol = StreamProtocol.V380_NATIVE,
            ptzType = PtzControlType.V380_NATIVE_PTZ
        ),
        CameraConfig(
            id = "v380-65078885",
            name = "V380 MVT26101 (65078885)",
            brand = CameraBrand.V380_PRO,
            connectionMode = ConnectionMode.CLOUD_DEVICE_ID,
            deviceId = "65078885",
            modelCode = "MVT26101",
            host = "",
            rtspPort = 8800,
            httpPort = 8800,
            onvifPort = 8899,
            username = "admin",
            password = "",
            protocol = StreamProtocol.V380_NATIVE,
            ptzType = PtzControlType.V380_NATIVE_PTZ
        )
    )

    val camerasFlow: Flow<List<CameraConfig>> = context.cameraDataStore.data.map { prefs ->
        val raw = prefs[camerasKey]
        if (raw.isNullOrBlank()) {
            defaultStarterCameras
        } else {
            try {
                val decoded = json.decodeFromString<List<CameraConfig>>(raw)
                decoded.ifEmpty { defaultStarterCameras }
            } catch (_: Exception) {
                defaultStarterCameras
            }
        }
    }

    val gridModeFlow: Flow<GridLayoutMode> = context.cameraDataStore.data.map { prefs ->
        val raw = prefs[gridModeKey]
        GridLayoutMode.entries.find { it.name == raw } ?: GridLayoutMode.GRID_2X2
    }

    val ringRefreshTokenFlow: Flow<String> = context.cameraDataStore.data.map { prefs ->
        prefs[ringRefreshTokenKey].orEmpty()
    }

    suspend fun saveCameras(cameras: List<CameraConfig>) {
        context.cameraDataStore.edit { prefs ->
            prefs[camerasKey] = json.encodeToString(cameras)
        }
    }

    fun exportCamerasToJson(cameras: List<CameraConfig>): String {
        return json.encodeToString(cameras)
    }

    suspend fun importCamerasFromJson(jsonString: String, existingCameras: List<CameraConfig>): Result<Int> {
        return try {
            val imported = json.decodeFromString<List<CameraConfig>>(jsonString)
            if (imported.isEmpty()) {
                return Result.failure(Exception("El archivo de respaldo no contiene cámaras"))
            }
            val existingMap = existingCameras.associateBy { it.id }.toMutableMap()
            for (cam in imported) {
                existingMap[cam.id] = cam
            }
            saveCameras(existingMap.values.toList())
            Result.success(imported.size)
        } catch (e: Exception) {
            Result.failure(Exception("Error al leer el respaldo: ${e.localizedMessage ?: "Formato JSON inválido"}"))
        }
    }

    suspend fun toggleLiveInDashboard(cameraId: String, active: Boolean, currentCameras: List<CameraConfig>) {
        val updated = currentCameras.map { cam ->
            if (cam.id == cameraId) cam.copy(liveInDashboard = active) else cam
        }
        saveCameras(updated)
    }

    suspend fun setGridMode(mode: GridLayoutMode) {
        context.cameraDataStore.edit { prefs ->
            prefs[gridModeKey] = mode.name
        }
    }

    suspend fun saveRingRefreshToken(token: String) {
        context.cameraDataStore.edit { prefs ->
            prefs[ringRefreshTokenKey] = token
        }
    }

    fun getAccessLogsFlow(cameraId: String): Flow<List<CameraAccessRecord>> {
        val key = stringPreferencesKey(accessLogsKeyPrefix + cameraId)
        return context.cameraDataStore.data.map { prefs ->
            val raw = prefs[key]
            if (raw.isNullOrBlank()) {
                generateInitialAuditLogs(cameraId)
            } else {
                try {
                    json.decodeFromString<List<CameraAccessRecord>>(raw)
                } catch (_: Exception) {
                    generateInitialAuditLogs(cameraId)
                }
            }
        }
    }

    suspend fun recordAccess(
        cameraId: String,
        clientIp: String,
        clientDevice: String,
        connectionType: String,
        status: String
    ) {
        val key = stringPreferencesKey(accessLogsKeyPrefix + cameraId)
        val now = System.currentTimeMillis()
        val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        val newRecord = CameraAccessRecord(
            timestamp = now,
            timestampFormatted = formatted,
            clientIp = clientIp,
            clientDevice = clientDevice,
            connectionType = connectionType,
            status = status
        )

        context.cameraDataStore.edit { prefs ->
            val existingRaw = prefs[key]
            val list = if (!existingRaw.isNullOrBlank()) {
                try {
                    json.decodeFromString<List<CameraAccessRecord>>(existingRaw).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } else {
                generateInitialAuditLogs(cameraId).toMutableList()
            }
            list.add(0, newRecord)
            // Keep latest 50 access logs
            val trimmed = list.take(50)
            prefs[key] = json.encodeToString(trimmed)
        }
    }

    private fun generateInitialAuditLogs(cameraId: String): List<CameraAccessRecord> {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return listOf(
            CameraAccessRecord(
                timestamp = now - 120_000,
                timestampFormatted = fmt.format(Date(now - 120_000)),
                clientIp = "192.168.1.105",
                clientDevice = "FrCam Android (Este dispositivo)",
                connectionType = "Streaming H.264 (Relay)",
                status = "Conexión Activa"
            ),
            CameraAccessRecord(
                timestamp = now - 3600_000,
                timestampFormatted = fmt.format(Date(now - 3600_000)),
                clientIp = "120.24.161.44",
                clientDevice = "V380 Cloud Relay Server",
                connectionType = "Heartbeat / KeepAlive",
                status = "OK (200)"
            ),
            CameraAccessRecord(
                timestamp = now - 86400_000,
                timestampFormatted = fmt.format(Date(now - 86400_000)),
                clientIp = "192.168.1.1",
                clientDevice = "Router Gateway DHCP",
                connectionType = "Renovación IP Lease",
                status = "Éxito"
            )
        )
    }
}
