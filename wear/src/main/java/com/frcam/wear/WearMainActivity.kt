package com.frcam.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

enum class WearDisplayMode {
    GRID,      // "Juntas" (Cuadrícula / Mosaico)
    FULLSCREEN // "Individual" (100% Pantalla Completa con Zoom)
}

class WearMainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {

    companion object {
        private const val TAG = "WearMainActivity"
        private const val CAMERAS_PATH = "/frcam/cameras"
        private const val REQUEST_SYNC_PATH = "/frcam/request_sync"
        private const val LAN_SYNC_PORT = 49880
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WearCameraStorage.init(this)

        // Sincronizar inmediatamente al abrir si no hay cámaras
        if (WearCameraStorage.cameras.value.isEmpty()) {
            requestSyncFromPhone(isManualTap = false)
        }

        setContent {
            val cameras by WearCameraStorage.cameras.collectAsState()
            var mode by remember { mutableStateOf(WearDisplayMode.GRID) }
            var selectedCameraIndex by remember { mutableIntStateOf(0) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Crossfade(targetState = mode, label = "WearModeSwitch") { currentMode ->
                    when (currentMode) {
                        WearDisplayMode.GRID -> {
                            WearCamerasGridView(
                                cameras = cameras,
                                onSelectCamera = { idx ->
                                    selectedCameraIndex = idx
                                    mode = WearDisplayMode.FULLSCREEN
                                },
                                onRequestSync = {
                                    requestSyncFromPhone(isManualTap = true)
                                }
                            )
                        }

                        WearDisplayMode.FULLSCREEN -> {
                            WearSingleCameraFullscreen(
                                cameras = cameras,
                                initialIndex = selectedCameraIndex,
                                onBackToGrid = {
                                    mode = WearDisplayMode.GRID
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            Wearable.getMessageClient(this).addListener(this)
            Wearable.getDataClient(this).addListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error registrando listeners de Wearable en reloj: ${e.message}")
        }
    }

    override fun onStop() {
        try {
            Wearable.getMessageClient(this).removeListener(this)
            Wearable.getDataClient(this).removeListener(this)
        } catch (_: Exception) {}
        super.onStop()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == CAMERAS_PATH) {
            try {
                val json = String(messageEvent.data, Charsets.UTF_8)
                if (json.isNotBlank()) {
                    Log.d(TAG, "Cámaras recibidas en primer plano via MessageClient!")
                    val count = WearCameraStorage.saveJsonString(this, json)
                    if (count > 0) {
                        runOnUiThread {
                            Toast.makeText(this, "¡$count cámaras sincronizadas!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando mensaje en reloj", e)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == CAMERAS_PATH) {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val json = dataMap.getString("cameras_json")
                    if (!json.isNullOrBlank()) {
                        Log.d(TAG, "Cámaras recibidas en primer plano via DataClient!")
                        val count = WearCameraStorage.saveJsonString(this, json)
                        if (count > 0) {
                            runOnUiThread {
                                Toast.makeText(this, "¡$count cámaras actualizadas!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando DataEvent en reloj", e)
                }
            }
        }
    }

    private fun requestSyncFromPhone(isManualTap: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isManualTap) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WearMainActivity, "Solicitando cámaras al celular...", Toast.LENGTH_SHORT).show()
                }
            }

            // 1. Canal Primario: Consultar la caché de DataClient en el reloj
            try {
                val dataItems = Wearable.getDataClient(this@WearMainActivity).dataItems.await()
                for (item in dataItems) {
                    if (item.uri.path == CAMERAS_PATH) {
                        val dataMap = DataMapItem.fromDataItem(item).dataMap
                        val json = dataMap.getString("cameras_json")
                        if (!json.isNullOrBlank()) {
                            val count = WearCameraStorage.saveJsonString(this@WearMainActivity, json)
                            if (count > 0) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@WearMainActivity, "¡$count cámaras sincronizadas!", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DataClient query error: ${e.message}")
            }

            // 2. Canal Bluetooth: Enviar solicitud de sincronización a todos los nodos conectados
            try {
                val nodes = Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                val messageClient = Wearable.getMessageClient(this@WearMainActivity)
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, REQUEST_SYNC_PATH, byteArrayOf()).await()
                        Log.d(TAG, "Solicitud enviada al celular: ${node.displayName} (${node.id})")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error enviando a nodo ${node.id}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error consultando nodos Bluetooth: ${e.message}")
            }

            // 3. Canal Wi-Fi Local Directo (HTTP + UDP Probe en puerto 49880)
            launch(Dispatchers.IO) {
                tryFetchCamerasViaLocalWifi()
            }

            // 4. Esperar hasta 2.5 segundos de forma reactiva a que se reciban las cámaras
            for (step in 1..8) {
                delay(300L)
                val current = WearCameraStorage.cameras.value.size
                if (current > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WearMainActivity, "¡$current cámaras sincronizadas con éxito!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
            }

            // Si tras 2.5s no se recibieron cámaras y fue toque manual:
            if (isManualTap && WearCameraStorage.cameras.value.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@WearMainActivity,
                        "Esperando celular... En tu teléfono abre FrCam y toca 'Sincronizar cámaras con reloj' en Ajustes.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Intenta sincronizar las cámaras directamente a través de la red Wi-Fi local
     * conectando al servidor HTTP local del celular en el puerto 49880.
     */
    private suspend fun tryFetchCamerasViaLocalWifi() = withContext(Dispatchers.IO) {
        // A. Intentar descubrimiento UDP rápido
        var phoneIp: String? = null
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1200
                val sendData = "FRCAM_WEAR_REQUEST_SYNC".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(sendData, sendData.size, broadcastAddr, LAN_SYNC_PORT))

                val recvBuf = ByteArray(256)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                val reply = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                if (reply.startsWith("FRCAM_SERVER_HTTP")) {
                    phoneIp = recvPacket.address.hostAddress
                    Log.d(TAG, "Celular encontrado via UDP en IP: $phoneIp")
                }
            }
        } catch (_: Exception) {}

        // B. Si se descubrió la IP por UDP, descargar por HTTP
        if (!phoneIp.isNullOrBlank()) {
            if (downloadAndApplyCamerasFromHttp(phoneIp!!)) return@withContext
        }

        // C. Si no respondió UDP pero el reloj está en Wi-Fi, escanear la subred local rápidamente
        val localSubnet = getLocalSubnetPrefix() ?: return@withContext
        val candidateIps = (1..254).map { "$localSubnet.$it" }

        coroutineScope {
            val jobs = candidateIps.map { ip ->
                async(Dispatchers.IO) {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, LAN_SYNC_PORT), 450)
                            Log.d(TAG, "Puerto 49880 abierto en $ip, descargando cámaras...")
                            downloadAndApplyCamerasFromHttp(ip)
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    private fun downloadAndApplyCamerasFromHttp(hostIp: String): Boolean {
        return try {
            val url = URL("http://$hostIp:$LAN_SYNC_PORT/cameras")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 2000
                requestMethod = "GET"
            }
            if (conn.responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (json.isNotBlank()) {
                    val count = WearCameraStorage.saveJsonString(this@WearMainActivity, json)
                    if (count > 0) {
                        Log.d(TAG, "¡$count cámaras sincronizadas exitosamente por HTTP Wi-Fi desde $hostIp!")
                        runOnUiThread {
                            Toast.makeText(this@WearMainActivity, "¡$count cámaras sincronizadas por Wi-Fi!", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
            }
            conn.disconnect()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error descargando cámaras HTTP desde $hostIp: ${e.message}")
            false
        }
    }

    private fun getLocalSubnetPrefix(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val dhcp = wifiManager.dhcpInfo ?: return null
            if (dhcp.gateway != 0) {
                val g = dhcp.gateway
                val ip = "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
                ip.substringBeforeLast(".")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
