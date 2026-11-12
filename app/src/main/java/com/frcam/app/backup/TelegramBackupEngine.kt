package com.frcam.app.backup

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Motor de respaldo automático y alertas en tiempo real hacia Telegram.
 * Envía clips animados en formato GIF o fotos instantáneas a un chat personal,
 * grupo o canal privado de Telegram ante cualquier detección de movimiento.
 *
 * Incluye resolución DNS optimizada con prioridad IPv4 para prevenir timeouts
 * causados por rutas IPv6 rotas en redes Wi-Fi hogareñas o móviles.
 */
object TelegramBackupEngine {

    private const val TAG = "TelegramBackupEngine"

    /**
     * DNS personalizado que prioriza direcciones IPv4 sobre IPv6 para evitar el problema
     * común en Android donde el router anuncia IPv6 pero no enruta paquetes, causando
     * SocketTimeoutException de 10-15s en HttpURLConnection clásica.
     * También incluye fallback a IPs oficiales del Datacenter de Telegram si el DNS falla.
     */
    private val telegramDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                Log.w(TAG, "DNS del sistema falló para $hostname: ${e.message}")
                emptyList()
            }

            // Priorizar IPv4 primero
            val sorted = resolved.sortedWith(compareBy { if (it is Inet4Address) 0 else 1 })
            if (sorted.isNotEmpty()) {
                return sorted
            }

            // Fallback a direcciones IP oficiales de Telegram Core (DC4 / DC2) si el DNS local está bloqueado o caído
            if (hostname.equals("api.telegram.org", ignoreCase = true)) {
                Log.i(TAG, "Usando fallback a IPs de Datacenter de Telegram para $hostname")
                val fallbackIps = listOf(
                    byteArrayOf(149.toByte(), 154.toByte(), 167.toByte(), 220.toByte()),
                    byteArrayOf(149.toByte(), 154.toByte(), 166.toByte(), 110.toByte()),
                    byteArrayOf(91.toByte(), 108.toByte(), 56.toByte(), 170.toByte())
                )
                return fallbackIps.mapNotNull { bytes ->
                    try {
                        InetAddress.getByAddress(hostname, bytes)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(telegramDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Limpia y normaliza el token del bot ingresado por el usuario.
     * Elimina espacios, comillas y el prefijo redundante "bot" si fue pegado accidentalmente.
     */
    fun sanitizeToken(token: String): String {
        var t = token.trim().replace("\"", "").replace("'", "")
        if (t.startsWith("bot", ignoreCase = true)) {
            t = t.substring(3).trim()
        }
        return t
    }

    /**
     * Limpia y normaliza el Chat ID ingresado por el usuario.
     */
    fun sanitizeChatId(chatId: String): String {
        return chatId.trim().replace("\"", "").replace("'", "")
    }

    /**
     * Envía un mensaje de prueba a Telegram para validar que el Bot Token y el Chat ID
     * configurados por el usuario sean correctos y tengan permisos de escritura.
     *
     * Realiza un diagnóstico en dos fases:
     * 1. Consulta 'getMe' para verificar el token y obtener el nombre del bot.
     * 2. Envía 'sendMessage' al chat ID para confirmar recepción.
     */
    suspend fun sendTestMessage(botToken: String, chatId: String): Result<String> = withContext(Dispatchers.IO) {
        val token = sanitizeToken(botToken)
        val chat = sanitizeChatId(chatId)

        if (token.isBlank() || chat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("El Bot Token y el Chat ID no pueden estar vacíos."))
        }

        // FASE 1: Verificar el token del bot mediante getMe
        var botUsername = ""
        try {
            val getMeRequest = Request.Builder()
                .url("https://api.telegram.org/bot$token/getMe")
                .get()
                .header("User-Agent", "FrCam-Android/1.3")
                .build()

            httpClient.newCall(getMeRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                    val code = response.code
                    val desc = json.optString("description", "")
                    return@withContext when {
                        code == 401 || desc.contains("Unauthorized", ignoreCase = true) -> {
                            Result.failure(Exception("Token inválido (Error 401). Verifica el token entregado por @BotFather."))
                        }
                        desc.isNotBlank() -> {
                            Result.failure(Exception("Telegram API ($code): $desc"))
                        }
                        else -> {
                            Result.failure(Exception("Error $code al consultar estado del bot en Telegram."))
                        }
                    }
                }
                val resultObj = json.optJSONObject("result")
                botUsername = resultObj?.optString("username", "").orEmpty()
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Timeout conectando a Telegram getMe", e)
            return@withContext Result.failure(Exception("Tiempo de espera agotado al conectar con Telegram (TimeOut). Verifica si tu conexión Wi-Fi/datos bloquea o restringe Telegram."))
        } catch (e: IOException) {
            Log.e(TAG, "Error de red en Telegram getMe", e)
            return@withContext Result.failure(Exception("Error de red al conectar con Telegram: ${e.localizedMessage ?: "Sin conexión"}"))
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en Telegram getMe", e)
            return@withContext Result.failure(e)
        }

        // FASE 2: Enviar mensaje de prueba al chat indicado
        try {
            val text = buildString {
                append("🛡️ <b>FrCam Monitor Pro</b>\n\n")
                append("✅ <b>¡Conexión con Telegram exitosa!</b>\n")
                if (botUsername.isNotBlank()) {
                    append("🤖 <i>Bot: @$botUsername</i>\n")
                }
                append("Las alertas de movimiento y los clips GIF animados de tus cámaras se respaldarán automáticamente en este chat.")
            }

            val jsonBody = JSONObject().apply {
                put("chat_id", chat)
                put("text", text)
                put("parse_mode", "HTML")
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val sendRequest = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(requestBody)
                .header("User-Agent", "FrCam-Android/1.3")
                .build()

            httpClient.newCall(sendRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                if (response.isSuccessful && json.optBoolean("ok", false)) {
                    val botInfo = if (botUsername.isNotBlank()) " desde @$botUsername" else ""
                    Result.success("¡Mensaje de prueba recibido en Telegram$botInfo con éxito!")
                } else {
                    val desc = json.optString("description", "")
                    val friendlyError = when {
                        desc.contains("chat not found", ignoreCase = true) -> {
                            val botPrompt = if (botUsername.isNotBlank()) "@$botUsername" else "tu bot"
                            "Chat no encontrado. En Telegram busca a $botPrompt y presiona 'Iniciar' (/start) primero."
                        }
                        desc.contains("bot can't initiate conversation", ignoreCase = true) -> {
                            val botPrompt = if (botUsername.isNotBlank()) "@$botUsername" else "tu bot"
                            "Abre Telegram, busca a $botPrompt y envíale un mensaje o presiona 'Iniciar' (/start)."
                        }
                        desc.contains("bot was blocked by the user", ignoreCase = true) -> {
                            val botPrompt = if (botUsername.isNotBlank()) "@$botUsername" else "el bot"
                            "Has bloqueado a $botPrompt en Telegram. Desbloquéalo para recibir alertas."
                        }
                        desc.isNotBlank() -> "Telegram API: $desc"
                        else -> "Error ${response.code} al enviar mensaje a Telegram."
                    }
                    Result.failure(Exception(friendlyError))
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Timeout enviando mensaje de prueba", e)
            Result.failure(Exception("Tiempo de espera agotado al entregar el mensaje a Telegram. Intenta de nuevo."))
        } catch (e: IOException) {
            Log.e(TAG, "Error de red enviando mensaje de prueba", e)
            Result.failure(Exception("Error de red con Telegram: ${e.localizedMessage ?: "Conexión interrumpida"}"))
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando mensaje de prueba", e)
            Result.failure(e)
        }
    }

    /**
     * Sube un GIF animado (sendAnimation) o foto (sendPhoto) del evento de movimiento a Telegram
     * con encabezado formateado y etiqueta de IA (Persona, Mascota, Vehículo).
     */
    suspend fun sendMotionAlert(
        botToken: String,
        chatId: String,
        cameraName: String,
        gifFile: File?,
        snapshot: Bitmap?,
        aiLabel: String?,
        timestampStr: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = sanitizeToken(botToken)
        val chat = sanitizeChatId(chatId)

        if (token.isBlank() || chat.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Token o Chat ID de Telegram no configurados."))
        }

        try {
            val caption = buildString {
                append("🚨 <b>Alerta de Movimiento: $cameraName</b>\n")
                append("⏰ <b>Hora:</b> $timestampStr\n")
                if (!aiLabel.isNullOrBlank()) {
                    append("🔍 <b>Detección IA:</b> $aiLabel\n")
                }
                append("📹 <i>FrCam Monitor Pro</i>")
            }

            val hasValidGif = gifFile != null && gifFile.exists() && gifFile.length() > 0
            val endpoint = if (hasValidGif) "sendAnimation" else "sendPhoto"
            val fileParamName = if (hasValidGif) "animation" else "photo"
            val fileName = if (hasValidGif) "motion.gif" else "snapshot.jpg"

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chat)
                .addFormDataPart("caption", caption)
                .addFormDataPart("parse_mode", "HTML")

            if (hasValidGif && gifFile != null) {
                val fileBody = gifFile.asRequestBody("image/gif".toMediaType())
                multipartBuilder.addFormDataPart(fileParamName, fileName, fileBody)
            } else if (snapshot != null) {
                val baos = ByteArrayOutputStream()
                snapshot.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val imageBytes = baos.toByteArray()
                val imageBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
                multipartBuilder.addFormDataPart(fileParamName, fileName, imageBody)
            } else {
                return@withContext Result.failure(IllegalArgumentException("Sin contenido multimedia para enviar."))
            }

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/$endpoint")
                .post(multipartBuilder.build())
                .header("User-Agent", "FrCam-Android/1.3")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                val ok = json.optBoolean("ok", false)
                if (response.isSuccessful && ok) {
                    Log.d(TAG, "Alerta con GIF respaldada exitosamente en Telegram ($cameraName)")
                    Result.success(true)
                } else {
                    val desc = json.optString("description", "Error ${response.code} en Telegram")
                    Log.w(TAG, "Respuesta de error de Telegram: $desc")
                    Result.failure(Exception(desc))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transmitiendo alerta a Telegram", e)
            Result.failure(e)
        }
    }
}
