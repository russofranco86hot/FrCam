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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor de sincronización del celular hacia el reloj inteligente (Wear OS).
 * Transmite automáticamente la lista completa de cámaras configuradas mediante:
 * 1. Google Play Services Wearable Data Layer (DataClient persistente + MessageClient inmediato).
 * 2. Difusión directa local por Wi-Fi / UDP (puerto 49880) como canal ultra-rápido cuando
 *    celular y reloj están en la misma red Wi-Fi o Bluetooth tiene demoras de emparejamiento.
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
    private var lastCamerasJson: String = "[]"

    /**
     * Inicia un listener UDP local para responder a solicitudes del reloj por la red local Wi-Fi.
     */
    fun startLanSyncServer(context: Context, getCameras: () -> List<CameraConfig>) {
        if (isLanServerRunning.getAndSet(true)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                DatagramSocket(LAN_SYNC_PORT).use { socket ->
                    socket.broadcast = true
                    val buffer = ByteArray(2048)
                    Log.d(TAG, "Servidor UDP de sincronización con reloj iniciado en puerto $LAN_SYNC_PORT")

                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()

                        if (text == "FRCAM_WEAR_REQUEST_SYNC") {
                            Log.d(TAG, "Solicitud de sincronización Wi-Fi recibida desde ${packet.address.hostAddress}")
                            val cameras = getCameras()
                            val camerasJson = json.encodeToString(cameras)
                            lastCamerasJson = camerasJson
                            val bytes = ("FRCAM_CAMERAS_JSON:" + camerasJson).toByteArray(Charsets.UTF_8)
                            val replyPacket = DatagramPacket(bytes, bytes.size, packet.address, packet.port)
                            socket.send(replyPacket)
                            Log.d(TAG, "Respuesta Wi-Fi enviada a ${packet.address.hostAddress} (${cameras.size} cámaras)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Servidor UDP local detenido: ${e.message}")
            } finally {
                isLanServerRunning.set(false)
            }
        }
    }

    /**
     * Envía la lista completa de cámaras al reloj por todos los canales disponibles.
     */
    fun syncCamerasToWatch(context: Context, cameras: List<CameraConfig>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val camerasJson = json.encodeToString(cameras)
                lastCamerasJson = camerasJson
                val bytes = camerasJson.toByteArray(Charsets.UTF_8)

                // 1. DataClient urgente (almacenamiento persistente en la capa Wearable de Google)
                try {
                    val dataMap = PutDataMapRequest.create(CAMERAS_PATH).apply {
                        dataMap.putString("cameras_json", camerasJson)
                        dataMap.putLong("timestamp", System.currentTimeMillis())
                    }
                    val putReq = dataMap.asPutDataRequest().setUrgent()
                    Wearable.getDataClient(context).putDataItem(putReq).await()
                    Log.d(TAG, "Cámaras enviadas via DataClient urgente: ${cameras.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error enviando DataClient: ${e.message}")
                }

                // 2. MessageClient (entrega inmediata a todos los relojes conectados por Bluetooth/Cloud)
                try {
                    val nodeClient = Wearable.getNodeClient(context)
                    val nodes = nodeClient.connectedNodes.await()
                    val messageClient = Wearable.getMessageClient(context)
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, CAMERAS_PATH, bytes).await()
                        Log.d(TAG, "Mensaje de cámaras entregado al nodo: ${node.displayName} (${node.id})")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error enviando MessageClient: ${e.message}")
                }

                // 3. Difusión local por Wi-Fi / UDP a toda la red local
                try {
                    DatagramSocket().use { udpSocket ->
                        udpSocket.broadcast = true
                        val udpPayload = ("FRCAM_CAMERAS_JSON:" + camerasJson).toByteArray(Charsets.UTF_8)
                        val broadcastAddr = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(udpPayload, udpPayload.size, broadcastAddr, LAN_SYNC_PORT)
                        udpSocket.send(packet)
                        Log.d(TAG, "Cámaras transmitidas por difusión Wi-Fi UDP puerto $LAN_SYNC_PORT")
                    }
                } catch (_: Exception) {}

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
