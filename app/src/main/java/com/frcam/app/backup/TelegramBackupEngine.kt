package com.frcam.app.backup

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Motor de respaldo automático y alertas en tiempo real hacia Telegram.
 * Envía clips animados en formato GIF o fotos instantáneas a un chat personal,
 * grupo o canal privado de Telegram ante cualquier detección de movimiento.
 */
object TelegramBackupEngine {

    private const val TAG = "TelegramBackupEngine"
    private const val BOUNDARY = "===FrCamTelegramBoundary==="
    private const val LINE_FEED = "\r\n"

    /**
     * Envía un mensaje de prueba a Telegram para validar que el Bot Token y el Chat ID
     * configurados por el usuario sean correctos y tengan permisos de escritura.
     */
    suspend fun sendTestMessage(botToken: String, chatId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = botToken.trim()
            val chat = chatId.trim()
            if (token.isBlank() || chat.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("El Bot Token y el Chat ID no pueden estar vacíos."))
            }

            val text = "🛡️ <b>FrCam Monitor Pro</b>\n\n✅ <b>¡Conexión con Telegram exitosa!</b>\nLas alertas de movimiento y los GIF animados de tus cámaras se respaldarán automáticamente en este chat."
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chat&text=$encodedText&parse_mode=HTML")

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "FrCam-Android/1.3")
            }

            val responseCode = conn.responseCode
            val responseText = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(responseText)
            if (json.optBoolean("ok", false)) {
                Result.success("¡Mensaje de prueba recibido en Telegram con éxito!")
            } else {
                val desc = json.optString("description", "Error de Telegram (Código $responseCode)")
                Result.failure(Exception("Telegram API: $desc"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar mensaje de prueba a Telegram", e)
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
        try {
            val token = botToken.trim()
            val chat = chatId.trim()
            if (token.isBlank() || chat.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Token o Chat ID de Telegram no configurados."))
            }

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
            val mimeType = if (hasValidGif) "image/gif" else "image/jpeg"

            val url = URL("https://api.telegram.org/bot$token/$endpoint")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = 15000
                readTimeout = 25000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                setRequestProperty("User-Agent", "FrCam-Android/1.3")
            }

            DataOutputStream(conn.outputStream).use { out ->
                // Campo chat_id
                writeFormField(out, "chat_id", chat)
                // Campo caption
                writeFormField(out, "caption", caption)
                // Campo parse_mode
                writeFormField(out, "parse_mode", "HTML")

                // Archivo adjunto (GIF o Foto)
                out.writeBytes("--$BOUNDARY$LINE_FEED")
                out.writeBytes("Content-Disposition: form-data; name=\"$fileParamName\"; filename=\"$fileName\"$LINE_FEED")
                out.writeBytes("Content-Type: $mimeType$LINE_FEED$LINE_FEED")

                if (hasValidGif && gifFile != null) {
                    FileInputStream(gifFile).use { fis ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                } else if (snapshot != null) {
                    val baos = ByteArrayOutputStream()
                    snapshot.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    out.write(baos.toByteArray())
                }

                out.writeBytes(LINE_FEED)
                out.writeBytes("--$BOUNDARY--$LINE_FEED")
                out.flush()
            }

            val responseCode = conn.responseCode
            val responseText = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = JSONObject(responseText)
            val ok = json.optBoolean("ok", false)
            if (ok) {
                Log.d(TAG, "Alerta con GIF respaldada exitosamente en Telegram ($cameraName)")
                Result.success(true)
            } else {
                val desc = json.optString("description", "Error $responseCode en Telegram")
                Log.w(TAG, "Respuesta de error de Telegram: $desc")
                Result.failure(Exception(desc))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transmitiendo alerta a Telegram", e)
            Result.failure(e)
        }
    }

    private fun writeFormField(out: DataOutputStream, name: String, value: String) {
        out.writeBytes("--$BOUNDARY$LINE_FEED")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"$LINE_FEED")
        out.writeBytes("Content-Type: text/plain; charset=UTF-8$LINE_FEED$LINE_FEED")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes(LINE_FEED)
    }
}
