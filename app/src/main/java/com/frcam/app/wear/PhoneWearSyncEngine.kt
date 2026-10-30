package com.frcam.app.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.frcam.app.model.CameraConfig
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor de sincronización del celular hacia el reloj inteligente (Wear OS).
 * Transmite automáticamente la lista completa de cámaras configuradas mediante:
 * 1. Google Play Services Wearable Data Layer (DataClient persistente + MessageClient inmediato).
 * 2. Servidor HTTP local embebido en el puerto 49880 para entrega ultra-rápida por Wi-Fi.
 * 3. Difusión UDP de detección automática en red local.
 */
object PhoneWearSyncEngine {

    private const val TAG = "PhoneWearSync"
    const val CAMERAS_PATH = "/frcam/cameras"
    const val REQUEST_SYNC_PATH = "/frcam/request_sync"
    const val LAN_SYNC_PORT = 49880

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val isLanServerRunning = AtomicBoolean(false)
    @Volatile
    private var cachedCameras: List<CameraConfig> = emptyList()

    @Volatile
    var onToggleMotion: ((String, Boolean, String) -> Unit)? = null

    /**
     * Inicia los servidores locales (HTTP + UDP) en el celular para sincronización directa por Wi-Fi.
     */
    fun startLanSyncServer(context: Context, getCameras: () -> List<CameraConfig>) {
        if (isLanServerRunning.getAndSet(true)) return

        // 1. Servidor HTTP local (puerto 49880) para entrega confiable de JSON completo
        CoroutineScope(Dispatchers.IO).launch {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(LAN_SYNC_PORT)
                Log.d(TAG, "Servidor HTTP local de FrCam iniciado en puerto $LAN_SYNC_PORT")

                while (true) {
                    val client = serverSocket.accept()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val firstLine = reader.readLine() ?: ""
                            Log.d(TAG, "Petición HTTP recibida desde ${client.inetAddress.hostAddress}: $firstLine")

                            if (firstLine.contains("/set_motion")) {
                                val id = Regex("""[?&]id=([^&\s]+)""").find(firstLine)?.groupValues?.get(1).orEmpty()
                                val enabled = Regex("""[?&]enabled=(true|false)""").find(firstLine)?.groupValues?.get(1)?.toBoolean() ?: true
                                val recordMode = if (enabled) "GIF" else "OFF"
                                onToggleMotion?.invoke(id, enabled, recordMode)
                                val resp = """{"status":"ok","id":"$id","motion":$enabled,"recordMode":"$recordMode"}"""
                                val out = client.getOutputStream()
                                val header = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json; charset=utf-8\r\n" +
                                        "Content-Length: ${resp.length}\r\n" +
                                        "Connection: close\r\n\r\n"
                                out.write(header.toByteArray(Charsets.UTF_8))
                                out.write(resp.toByteArray(Charsets.UTF_8))
                                out.flush()
                                client.close()
                                return@launch
                            }

                            if (firstLine.contains("/ring_stream")) {
                                val idStr = Regex("""[?&]id=(\d+)""").find(firstLine)?.groupValues?.get(1)
                                val id = idStr?.toLongOrNull() ?: 0L
                                if (id > 0L) {
                                    val out = client.getOutputStream()
                                    val boundary = "frame_boundary"
                                    val header = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n" +
                                            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                                            "Pragma: no-cache\r\n" +
                                            "Connection: close\r\n\r\n"
                                    out.write(header.toByteArray(Charsets.UTF_8))
                                    out.flush()

                                    // Despertar cámara en segundo plano
                                    com.frcam.app.protocol.RingCloudClient.wakeLiveStream(id)

                                    try {
                                        while (!client.isClosed && client.isConnected) {
                                            val liveBmp = com.frcam.app.protocol.RingCloudClient.getLiveFrame(id)
                                            val bmp = liveBmp ?: com.frcam.app.protocol.RingCloudClient.getCachedSnapshot(id)?.bitmap
                                            if (bmp != null) {
                                                val bos = java.io.ByteArrayOutputStream()
                                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, bos)
                                                val imgBytes = bos.toByteArray()
                                                val partHeader = "--$boundary\r\n" +
                                                        "Content-Type: image/jpeg\r\n" +
                                                        "Content-Length: ${imgBytes.size}\r\n\r\n"
                                                out.write(partHeader.toByteArray(Charsets.UTF_8))
                                                out.write(imgBytes)
                                                out.write("\r\n".toByteArray(Charsets.UTF_8))
                                                out.flush()
                                            } else {
                                                com.frcam.app.protocol.RingCloudClient.fetchDeviceSnapshotWithMeta(id)
                                            }
                                            // Refresco a 4 FPS para movimiento en vivo real
                                            delay(250L)
                                        }
                                    } catch (_: Exception) {}
                                    try { client.close() } catch (_: Exception) {}
                                    return@launch
                                }
                            }

                            if (firstLine.contains("/ring_ticket")) {
                                val idStr = Regex("""[?&]id=(\d+)""").find(firstLine)?.groupValues?.get(1)
                                val id = idStr?.toLongOrNull() ?: 0L
                                if (id > 0L) {
                                    com.frcam.app.protocol.RingCloudClient.wakeLiveStream(id)
                                    val ticket = com.frcam.app.protocol.RingCloudClient.requestSignalingTicket()
                                    if (!ticket.isNullOrBlank()) {
                                        val jsonResp = """{"ticket":"$ticket"}"""
                                        val out = client.getOutputStream()
                                        val header = "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: application/json; charset=utf-8\r\n" +
                                                "Content-Length: ${jsonResp.length}\r\n" +
                                                "Connection: close\r\n\r\n"
                                        out.write(header.toByteArray(Charsets.UTF_8))
                                        out.write(jsonResp.toByteArray(Charsets.UTF_8))
                                        out.flush()
                                        client.close()
                                        return@launch
                                    }
                                }
                                val out = client.getOutputStream()
                                val header = "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n"
                                out.write(header.toByteArray(Charsets.UTF_8))
                                out.flush()
                                client.close()
                                return@launch
                            }

                            if (firstLine.contains("/ring_snapshot")) {
                                val idStr = Regex("""[?&]id=(\d+)""").find(firstLine)?.groupValues?.get(1)
                                val id = idStr?.toLongOrNull() ?: 0L
                                if (id > 0L) {
                                    val liveBmp = com.frcam.app.protocol.RingCloudClient.getLiveFrame(id)
                                    val bmp = liveBmp ?: com.frcam.app.protocol.RingCloudClient.getCachedSnapshot(id)?.bitmap
                                        ?: com.frcam.app.protocol.RingCloudClient.fetchDeviceSnapshotWithMeta(id)?.bitmap
                                    if (bmp != null) {
                                        val bos = java.io.ByteArrayOutputStream()
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos)
                                        val imgBytes = bos.toByteArray()
                                        val out = client.getOutputStream()
                                        val header = "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: image/jpeg\r\n" +
                                                "Content-Length: ${imgBytes.size}\r\n" +
                                                "Connection: close\r\n\r\n"
                                        out.write(header.toByteArray(Charsets.UTF_8))
                                        out.write(imgBytes)
                                        out.flush()
                                        client.close()
                                        if (liveBmp == null) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                try {
                                                    com.frcam.app.protocol.RingCloudClient.triggerSnapshotUpdate(id)
                                                    com.frcam.app.protocol.RingCloudClient.fetchDeviceSnapshotWithMeta(id)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        return@launch
                                    }
                                } else {
                                    val out = client.getOutputStream()
                                    val header = "HTTP/1.1 404 Not Found\r\n" +
                                            "Content-Type: text/plain\r\n" +
                                            "Content-Length: 0\r\n" +
                                            "Connection: close\r\n\r\n"
                                    out.write(header.toByteArray(Charsets.UTF_8))
                                    out.flush()
                                    client.close()
                                    return@launch
                                }
                            }

                            val localIp = com.frcam.app.protocol.NetworkCameraScanner.getLocalIpv4Address().orEmpty()
                            val ringToken = com.frcam.app.protocol.RingCloudClient.ensureValidAccessToken()
                                ?: com.frcam.app.protocol.RingCloudClient.getActiveToken()
                            val cams = (cachedCameras.ifEmpty { getCameras() }).map { cam ->
                                var updated = cam.copy(liveInDashboard = true)
                                if (updated.brand == com.frcam.app.model.CameraBrand.RING_CLOUD) {
                                    if (updated.password.isBlank() && ringToken.isNotBlank()) {
                                        updated = updated.copy(password = ringToken)
                                    }
                                    if (localIp.isNotBlank()) {
                                        val doorbotId = updated.deviceId.filter { it.isDigit() }
                                        updated = updated.copy(
                                            customStreamUrl = "http://$localIp:$LAN_SYNC_PORT/ring_snapshot?id=$doorbotId",
                                            remoteHost = localIp
                                        )
                                    }
                                }
                                updated
                            }
                            val body = json.encodeToString(cams).toByteArray(Charsets.UTF_8)

                            val out = client.getOutputStream()
                            val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json; charset=utf-8\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.UTF_8))
                            out.write(body)
                            out.flush()
                            client.close()
                            Log.d(TAG, "Entregadas ${cams.size} cámaras por HTTP a ${client.inetAddress.hostAddress}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Error en cliente HTTP: ${e.message}")
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Servidor HTTP local detenido: ${e.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                isLanServerRunning.set(false)
            }
        }

        // 2. Servidor UDP Responder para descubrimiento en Wi-Fi
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DatagramSocket(LAN_SYNC_PORT).use { udpSocket ->
                    udpSocket.broadcast = true
                    val buffer = ByteArray(1024)
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()

                        if (text == "FRCAM_WEAR_REQUEST_SYNC") {
                            Log.d(TAG, "Probe UDP recibido desde reloj ${packet.address.hostAddress}")
                            val replyData = "FRCAM_SERVER_HTTP:$LAN_SYNC_PORT".toByteArray(Charsets.UTF_8)
                            val replyPacket = DatagramPacket(replyData, replyData.size, packet.address, packet.port)
                            udpSocket.send(replyPacket)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Envía la lista completa de cámaras al reloj por todos los canales disponibles.
     */
    fun syncCamerasToWatch(context: Context, cameras: List<CameraConfig>, targetNodeId: String? = null) {
        val localIp = com.frcam.app.protocol.NetworkCameraScanner.getLocalIpv4Address().orEmpty()
        val ringToken = com.frcam.app.protocol.RingCloudClient.getActiveToken()
        val activeCameras = cameras.map { cam ->
            var updated = cam.copy(liveInDashboard = true)
            if (updated.brand == com.frcam.app.model.CameraBrand.RING_CLOUD) {
                if (updated.password.isBlank() && ringToken.isNotBlank()) {
                    updated = updated.copy(password = ringToken)
                }
                if (localIp.isNotBlank()) {
                    val doorbotId = updated.deviceId.filter { it.isDigit() }
                    updated = updated.copy(
                        customStreamUrl = "http://$localIp:$LAN_SYNC_PORT/ring_snapshot?id=$doorbotId",
                        remoteHost = localIp
                    )
                }
            }
            updated
        }
        if (activeCameras.isNotEmpty()) {
            cachedCameras = activeCameras
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Asegurar token de acceso fresco si hay cámaras Ring
                val hasRing = activeCameras.any { it.brand == com.frcam.app.model.CameraBrand.RING_CLOUD }
                val freshRingToken = if (hasRing) {
                    com.frcam.app.protocol.RingCloudClient.ensureValidAccessToken() ?: ringToken
                } else ringToken

                val camsToSend = (if (activeCameras.isNotEmpty()) activeCameras else cachedCameras).map { cam ->
                    if (cam.brand == com.frcam.app.model.CameraBrand.RING_CLOUD && freshRingToken.isNotBlank()) {
                        cam.copy(password = freshRingToken)
                    } else cam
                }
                if (camsToSend.isEmpty()) {
                    Log.w(TAG, "No hay cámaras para sincronizar")
                    return@launch
                }

                val camerasJson = json.encodeToString(camsToSend)
                val bytes = camerasJson.toByteArray(Charsets.UTF_8)

                // 1. DataClient urgente (almacenamiento persistente en Google Play Services Wearable)
                try {
                    val dataMap = PutDataMapRequest.create(CAMERAS_PATH).apply {
                        dataMap.putString("cameras_json", camerasJson)
                        dataMap.putLong("timestamp", System.currentTimeMillis())
                    }
                    val putReq = dataMap.asPutDataRequest().setUrgent()
                    Wearable.getDataClient(context).putDataItem(putReq).await()
                    Log.d(TAG, "Cámaras enviadas via DataClient urgente: ${camsToSend.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error enviando DataClient: ${e.message}")
                }

                // 2. MessageClient directo a nodo específico si se conoce
                val messageClient = Wearable.getMessageClient(context)
                if (!targetNodeId.isNullOrBlank()) {
                    try {
                        messageClient.sendMessage(targetNodeId, CAMERAS_PATH, bytes).await()
                        Log.d(TAG, "Mensaje enviado DIRECTAMENTE al reloj (nodo: $targetNodeId)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error enviando directo a $targetNodeId: ${e.message}")
                    }
                }

                // 3. MessageClient a todos los nodos conectados
                try {
                    val nodeClient = Wearable.getNodeClient(context)
                    val nodes = nodeClient.connectedNodes.await()
                    for (node in nodes) {
                        if (node.id != targetNodeId) {
                            try {
                                messageClient.sendMessage(node.id, CAMERAS_PATH, bytes).await()
                                Log.d(TAG, "Mensaje de cámaras entregado al nodo: ${node.displayName} (${node.id})")
                            } catch (e: Exception) {
                                Log.w(TAG, "Error enviando a nodo ${node.displayName}: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error consultando nodos en celular: ${e.message}")
                }

                // Feedback visual en el teléfono
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "Sincronizando ${camsToSend.size} cámaras con Wear OS...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error general sincronizando con Wear OS: ${e.message}")
            }
        }
    }

    /**
     * Registra un listener para responder cuando el reloj solicita las cámaras de forma manual.
     */
    fun registerListener(context: Context, getCameras: () -> List<CameraConfig>): MessageClient.OnMessageReceivedListener {
        startLanSyncServer(context, getCameras)

        val listener = MessageClient.OnMessageReceivedListener { messageEvent ->
            if (messageEvent.path == REQUEST_SYNC_PATH) {
                Log.d(TAG, "Solicitud de sincronización recibida desde el reloj (${messageEvent.sourceNodeId})")
                val cams = getCameras().ifEmpty { cachedCameras }
                syncCamerasToWatch(context, cams, targetNodeId = messageEvent.sourceNodeId)
            } else if (messageEvent.path == "/frcam/set_motion") {
                try {
                    val text = String(messageEvent.data, Charsets.UTF_8)
                    val parts = text.split(":")
                    if (parts.size >= 2) {
                        val id = parts[0]
                        val enabled = parts[1].toBoolean()
                        onToggleMotion?.invoke(id, enabled, if (enabled) "GIF" else "OFF")
                    }
                } catch (_: Exception) {}
            }
        }
        Wearable.getMessageClient(context).addListener(listener)
        return listener
    }

    fun unregisterListener(context: Context, listener: MessageClient.OnMessageReceivedListener) {
        Wearable.getMessageClient(context).removeListener(listener)
    }
}
