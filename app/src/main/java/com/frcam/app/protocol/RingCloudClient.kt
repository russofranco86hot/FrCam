package com.frcam.app.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class RingCloudDevice(
    val id: Long,
    val deviceId: String,
    val description: String,
    val kind: String,
    val isDoorbell: Boolean
)

data class RingFrame(
    val bitmap: Bitmap,
    val ringTimestampMs: Long = System.currentTimeMillis()
)

sealed class RingAuthOutcome {
    data class RequiresTwoFactor(val prompt: String) : RingAuthOutcome()
    data class Authenticated(
        val accessToken: String,
        val refreshToken: String,
        val devices: List<RingCloudDevice>
    ) : RingAuthOutcome()
    data class Error(val message: String) : RingAuthOutcome()
}

object RingCloudClient {

    private const val OAUTH_URL = "https://oauth.ring.com/oauth/token"
    private const val DEVICES_URL = "https://api.ring.com/clients_api/ring_devices"
    private val hardwareId = UUID.randomUUID().toString()

    @Volatile
    private var cachedAccessToken: String = ""

    @Volatile
    private var cachedRefreshToken: String = ""

    fun getActiveAccessToken(): String = cachedAccessToken
    fun getActiveToken(): String = cachedAccessToken.ifBlank { cachedRefreshToken }

    fun setSavedRefreshToken(token: String) {
        if (token.isNotBlank()) {
            cachedRefreshToken = token
        }
    }

    /**
     * Authenticates with Ring Cloud using Email + Password (+ optional 2FA OTP code)
     * or an existing Ring Refresh Token, then discovers all Ring Doorbells & Stickup Cams.
     */
    suspend fun authenticateAndFetchDevices(
        email: String,
        password: String,
        twoFactorCode: String = "",
        existingRefreshToken: String = ""
    ): RingAuthOutcome = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(OAUTH_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "android:com.ringapp")
                setRequestProperty("hardware_id", hardwareId)
                setRequestProperty("2fa-support", "true")
                if (twoFactorCode.isNotBlank()) {
                    setRequestProperty("2fa-code", twoFactorCode.trim())
                }
            }

            val payload = if (existingRefreshToken.isNotBlank()) {
                JSONObject().apply {
                    put("client_id", "ring_official_android")
                    put("grant_type", "refresh_token")
                    put("refresh_token", existingRefreshToken.trim())
                    put("scope", "client")
                }
            } else {
                JSONObject().apply {
                    put("client_id", "ring_official_android")
                    put("grant_type", "password")
                    put("username", email.trim())
                    put("password", password)
                    put("scope", "client")
                }
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code == 412 || code == 400) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.readText().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                conn.disconnect()
                if (code == 412 || errBody.contains("tsv", ignoreCase = true) || errBody.contains("verification", ignoreCase = true)) {
                    return@withContext RingAuthOutcome.RequiresTwoFactor(
                        "Ring envió un código de verificación 2FA por SMS o Correo. Ingrésalo abajo y presiona 'Verificar 2FA'."
                    )
                }
                return@withContext RingAuthOutcome.Error(
                    "Credenciales rechazadas por Ring ($code). Verifica tu correo/contraseña o pega un Refresh Token."
                )
            }

            if (code !in 200..299) {
                conn.disconnect()
                return@withContext RingAuthOutcome.Error(
                    "Ring respondió HTTP $code. Si tu cuenta requiere validación extra, ingresa un Refresh Token."
                )
            }

            val tokenJson = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val accessToken = tokenJson.optString("access_token")
            val refreshToken = tokenJson.optString("refresh_token")
            if (accessToken.isBlank()) {
                return@withContext RingAuthOutcome.Error("No se recibió access_token de Ring")
            }

            cachedAccessToken = accessToken
            if (refreshToken.isNotBlank()) {
                cachedRefreshToken = refreshToken
            }

            val devices = fetchRingDevices(accessToken)
            RingAuthOutcome.Authenticated(
                accessToken = accessToken,
                refreshToken = refreshToken,
                devices = devices
            )
        } catch (e: Exception) {
            RingAuthOutcome.Error("Error de red conectando con Ring: ${e.localizedMessage ?: "Timeout"}")
        }
    }

    suspend fun ensureValidAccessToken(): String? = withContext(Dispatchers.IO) {
        if (cachedAccessToken.isNotBlank()) return@withContext cachedAccessToken
        val refresh = cachedRefreshToken
        if (refresh.isBlank()) return@withContext null
        when (val res = authenticateAndFetchDevices("", "", "", refresh)) {
            is RingAuthOutcome.Authenticated -> res.accessToken
            else -> null
        }
    }

    private fun fetchRingDevices(accessToken: String): List<RingCloudDevice> {
        val list = mutableListOf<RingCloudDevice>()
        try {
            val conn = (URL(DEVICES_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", "android:com.ringapp")
            }
            if (conn.responseCode in 200..299) {
                val root = JSONObject(conn.inputStream.bufferedReader().readText())

                val doorbots = root.optJSONArray("doorbots")
                if (doorbots != null) {
                    for (i in 0 until doorbots.length()) {
                        val obj = doorbots.optJSONObject(i) ?: continue
                        list.add(
                            RingCloudDevice(
                                id = obj.optLong("id"),
                                deviceId = obj.optString("device_id"),
                                description = obj.optString("description", "Timbre Ring"),
                                kind = obj.optString("kind", "doorbell"),
                                isDoorbell = true
                            )
                        )
                    }
                }

                val cams = root.optJSONArray("stickup_cams")
                if (cams != null) {
                    for (i in 0 until cams.length()) {
                        val obj = cams.optJSONObject(i) ?: continue
                        list.add(
                            RingCloudDevice(
                                id = obj.optLong("id"),
                                deviceId = obj.optString("device_id"),
                                description = obj.optString("description", "Cámara Ring"),
                                kind = obj.optString("kind", "stickup_cam"),
                                isDoorbell = false
                            )
                        )
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return list
    }

    private val activeLiveDings = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Solicita el ticket de señalización WebRTC (mismo endpoint que usa la app oficial / python-ring-doorbell).
     * Devuelve el ticket o null; el motivo del fallo queda en [lastTicketError].
     */
    @Volatile
    var lastTicketError: String = ""

    suspend fun requestSignalingTicket(): String? = withContext(Dispatchers.IO) {
        val accessToken = ensureValidAccessToken()
        if (accessToken == null) {
            lastTicketError = "Sin sesión Ring (inicia sesión en el ícono de Ring Cloud)"
            return@withContext null
        }
        val endpoints = listOf(
            "https://prd-api-us.prd.rings.solutions/api/v1/clap/ticket/request/signalsocket",
            "https://api.ring.com/api/v1/clap/ticket/request/signalsocket"
        )
        for (endpoint in endpoints) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "POST"
                    doOutput = true
                    setFixedLengthStreamingMode(0)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("User-Agent", "android:com.ringapp")
                    setRequestProperty("hardware_id", hardwareId)
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.close()
                val code = conn.responseCode
                if (code in 200..299) {
                    val ticket = JSONObject(conn.inputStream.bufferedReader().readText()).optString("ticket")
                    conn.disconnect()
                    if (ticket.isNotBlank()) return@withContext ticket
                    lastTicketError = "Ring devolvió ticket vacío"
                } else {
                    lastTicketError = "Ticket Ring HTTP $code"
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastTicketError = "Ticket Ring: ${e.localizedMessage}"
            }
        }
        null
    }

    suspend fun wakeLiveStream(doorbotId: Long): Boolean = withContext(Dispatchers.IO) {
        val accessToken = ensureValidAccessToken() ?: return@withContext false
        var ok = false

        // 1. Primary Ring Live View trigger (POST /clients_api/doorbots/{id}/live_view)
        try {
            val liveViewUrl = "https://api.ring.com/clients_api/doorbots/$doorbotId/live_view"
            val conn = (URL(liveViewUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", "android:com.ringapp")
                outputStream.use { it.write("{}".toByteArray()) }
            }
            ok = conn.responseCode in 200..299
            conn.disconnect()
        } catch (_: Exception) {
        }

        // 2. Secondary VOD trigger (POST /clients_api/doorbots/{id}/vod)
        try {
            val vodUrl = "https://api.ring.com/clients_api/doorbots/$doorbotId/vod"
            val conn = (URL(vodUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", "android:com.ringapp")
                val body = """{"api_version":"11"}"""
                outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode in 200..299) {
                ok = true
                try {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    if (resp.isNotBlank()) {
                        val json = JSONObject(resp)
                        val vodDingId = json.optLong("id", 0L).takeIf { it > 0 } 
                            ?: json.optString("id_str").toLongOrNull() ?: 0L
                        if (vodDingId > 0) {
                            activeLiveDings[doorbotId] = vodDingId
                        }
                    }
                } catch (_: Exception) {
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }

        // Trigger fresh snapshot capture from Ring Cloud
        triggerSnapshotUpdate(doorbotId)

        ok
    }

    suspend fun triggerSnapshotUpdate(doorbotId: Long): Boolean = withContext(Dispatchers.IO) {
        val accessToken = ensureValidAccessToken() ?: return@withContext false
        var ok = false
        try {
            val tsUrl = "https://api.ring.com/clients_api/snapshots/timestamps"
            val conn = (URL(tsUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", "android:com.ringapp")
                val body = """{"doorbot_ids":[$doorbotId]}"""
                outputStream.use { it.write(body.toByteArray()) }
            }
            ok = conn.responseCode in 200..299
            conn.disconnect()
        } catch (_: Exception) {
        }

        try {
            val updateUrl = "https://api.ring.com/clients_api/snapshots/update_all"
            val conn = (URL(updateUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", "android:com.ringapp")
                val body = """{"doorbot_ids":[$doorbotId],"update_all":true}"""
                outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode in 200..299) ok = true
            conn.disconnect()
        } catch (_: Exception) {
        }
        ok
    }

    suspend fun fetchDeviceSnapshotWithMeta(doorbotId: Long): RingFrame? = withContext(Dispatchers.IO) {
        val accessToken = ensureValidAccessToken() ?: return@withContext null
        var ringServerTs = System.currentTimeMillis()

        // 1. Check latest snapshot timestamp from Ring Cloud
        try {
            val tsUrl = "https://api.ring.com/clients_api/snapshots/timestamps"
            val tsConn = (URL(tsUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", "android:com.ringapp")
                val body = """{"doorbot_ids":[$doorbotId]}"""
                outputStream.use { it.write(body.toByteArray()) }
            }
            if (tsConn.responseCode in 200..299) {
                val jsonStr = tsConn.inputStream.bufferedReader().use { it.readText() }
                val match = Regex(""""timestamp"\s*:\s*(\d+)""").find(jsonStr)
                if (match != null) {
                    val parsed = match.groupValues[1].toLongOrNull() ?: 0L
                    if (parsed > 0) ringServerTs = parsed
                }
            }
            tsConn.disconnect()
        } catch (_: Exception) {
        }

        val now = System.currentTimeMillis()
        var bmp: Bitmap? = null

        // 1. Primary official mobile snapshot endpoint: https://api.ring.com/clients_api/snapshots/image/{device_id}
        try {
            val imageUrl = "https://api.ring.com/clients_api/snapshots/image/$doorbotId?_ts=$now"
            val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", "android:com.ringapp")
            }
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.readBytes()
                if (bytes.isNotEmpty()) {
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }

        // 2. Try app-snaps endpoint: https://app-snaps.ring.com/next/{device_id}
        if (bmp == null) {
            try {
                val appSnapsUrl = "https://app-snaps.ring.com/next/$doorbotId?_t=$now"
                val snapConn = (URL(appSnapsUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("User-Agent", "android:com.ringapp")
                }
                if (snapConn.responseCode in 200..299) {
                    val bytes = snapConn.inputStream.readBytes()
                    if (bytes.isNotEmpty()) {
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                snapConn.disconnect()
            } catch (_: Exception) {
            }
        }

        // 3. Try official /v1/devices/{device_id}/media/image/download (Ring Media Service endpoint)
        if (bmp == null) {
            try {
                val mediaUrl = "https://api.ring.com/v1/devices/$doorbotId/media/image/download?_t=$now"
                val mediaConn = (URL(mediaUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("User-Agent", "android:com.ringapp")
                }
                if (mediaConn.responseCode in 200..299) {
                    val bytes = mediaConn.inputStream.readBytes()
                    if (bytes.isNotEmpty()) {
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                mediaConn.disconnect()
            } catch (_: Exception) {
            }
        }

        if (bmp != null) {
            RingFrame(bitmap = bmp, ringTimestampMs = ringServerTs)
        } else {
            null
        }
    }

    suspend fun fetchDeviceSnapshot(doorbotId: Long): Bitmap? {
        return fetchDeviceSnapshotWithMeta(doorbotId)?.bitmap
    }

    suspend fun fetchLatestRecordingUrl(doorbotId: Long): String? = withContext(Dispatchers.IO) {
        val accessToken = ensureValidAccessToken() ?: return@withContext null
        try {
            var dingId: Long? = activeLiveDings[doorbotId]

            // 1. Check for active ding / live recording if not cached
            if (dingId == null || dingId <= 0L) {
                try {
                    val dingsUrl = "https://api.ring.com/clients_api/dings/active"
                    val conn = (URL(dingsUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $accessToken")
                        setRequestProperty("User-Agent", "android:com.ringapp")
                    }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val array = org.json.JSONArray(body)
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            val dbId = item.optLong("doorbot_id", 0L)
                            if (dbId == doorbotId || doorbotId == 0L) {
                                dingId = item.optLong("id", 0L).takeIf { it > 0 }
                                if (dingId != null) break
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }

            // 2. Query doorbots history for specific camera (POST/GET clients_api/doorbots/{doorbotId}/history)
            if (dingId == null || dingId <= 0L) {
                try {
                    val historyUrl = "https://api.ring.com/clients_api/doorbots/$doorbotId/history?limit=10"
                    val hConn = (URL(historyUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3500
                        readTimeout = 3500
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $accessToken")
                        setRequestProperty("User-Agent", "android:com.ringapp")
                    }
                    if (hConn.responseCode in 200..299) {
                        val hBody = hConn.inputStream.bufferedReader().use { it.readText() }
                        val hArray = org.json.JSONArray(hBody)
                        if (hArray.length() > 0) {
                            dingId = hArray.getJSONObject(0).optLong("id", 0L).takeIf { it > 0 }
                        }
                    }
                    hConn.disconnect()
                } catch (_: Exception) {
                }
            }

            // 3. Fallback to general doorbots history
            if (dingId == null || dingId <= 0L) {
                try {
                    val historyUrl = "https://api.ring.com/clients_api/doorbots/history?limit=15"
                    val hConn = (URL(historyUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3500
                        readTimeout = 3500
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $accessToken")
                        setRequestProperty("User-Agent", "android:com.ringapp")
                    }
                    if (hConn.responseCode in 200..299) {
                        val hBody = hConn.inputStream.bufferedReader().use { it.readText() }
                        val hArray = org.json.JSONArray(hBody)
                        for (i in 0 until hArray.length()) {
                            val item = hArray.getJSONObject(i)
                            val dbObj = item.optJSONObject("doorbot")
                            val itemDoorbotId = dbObj?.optLong("id") ?: item.optLong("doorbot_id", 0L)
                            if (doorbotId == 0L || itemDoorbotId == doorbotId) {
                                dingId = item.optLong("id", 0L).takeIf { it > 0 }
                                if (dingId != null) break
                            }
                        }
                        if (dingId == null && hArray.length() > 0) {
                            dingId = hArray.getJSONObject(0).optLong("id", 0L).takeIf { it > 0 }
                        }
                    }
                    hConn.disconnect()
                } catch (_: Exception) {
                }
            }

            val targetDingId = dingId ?: return@withContext null
            if (targetDingId <= 0L) return@withContext null

            // 4. Request recording URL (handles 302 redirect or JSON response to pre-signed S3 .mp4)
            for (apiVersion in listOf("11", "9")) {
                try {
                    val recUrl = "https://api.ring.com/clients_api/dings/$targetDingId/recording?api_version=$apiVersion"
                    val rConn = (URL(recUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        setRequestProperty("Authorization", "Bearer $accessToken")
                        setRequestProperty("User-Agent", "android:com.ringapp")
                    }
                    var redirectLoc = rConn.getHeaderField("Location")
                    if (redirectLoc.isNullOrBlank() && rConn.responseCode in 200..299) {
                        val body = rConn.inputStream.bufferedReader().use { it.readText() }
                        try {
                            val j = JSONObject(body)
                            redirectLoc = j.optString("url").ifBlank { null }
                        } catch (_: Exception) {
                        }
                    }
                    rConn.disconnect()
                    if (!redirectLoc.isNullOrBlank()) {
                        return@withContext redirectLoc
                    }
                } catch (_: Exception) {
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
