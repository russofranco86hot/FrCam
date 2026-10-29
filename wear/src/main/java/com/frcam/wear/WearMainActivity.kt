package com.frcam.wear

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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

        // Sincronizar inmediatamente al abrir
        requestSyncFromPhone()

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
                    WearCameraStorage.saveJsonString(this, json)
                    runOnUiThread {
                        val count = WearCameraStorage.cameras.value.size
                        Toast.makeText(this, "¡$count cámaras sincronizadas!", Toast.LENGTH_SHORT).show()
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
                        WearCameraStorage.saveJsonString(this, json)
                        runOnUiThread {
                            val count = WearCameraStorage.cameras.value.size
                            Toast.makeText(this, "¡$count cámaras actualizadas!", Toast.LENGTH_SHORT).show()
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
            var syncedCount = 0

            // 1. Canal Primario Inmediato: Consultar la caché de DataClient en el reloj
            try {
                val dataItems = Wearable.getDataClient(this@WearMainActivity).dataItems.await()
                for (item in dataItems) {
                    if (item.uri.path == CAMERAS_PATH) {
                        val dataMap = DataMapItem.fromDataItem(item).dataMap
                        val json = dataMap.getString("cameras_json")
                        if (!json.isNullOrBlank()) {
                            Log.d(TAG, "Cámaras leídas inmediatamente de DataClient local!")
                            WearCameraStorage.saveJsonString(this@WearMainActivity, json)
                            syncedCount = WearCameraStorage.cameras.value.size
                            if (syncedCount > 0) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DataClient query error: ${e.message}")
            }

            if (syncedCount > 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WearMainActivity, "¡$syncedCount cámaras sincronizadas!", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // 2. Canal Bluetooth / Cloud: Enviar solicitud al celular via MessageClient
            var nodeCount = 0
            try {
                val nodes = Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                nodeCount = nodes.size
                val messageClient = Wearable.getMessageClient(this@WearMainActivity)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, REQUEST_SYNC_PATH, byteArrayOf()).await()
                    Log.d(TAG, "Solicitud enviada al celular: ${node.displayName} (${node.id})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error solicitando nodos Bluetooth: ${e.message}")
            }

            // 3. Canal Wi-Fi Local (UDP broadcast en puerto 49880)
            try {
                DatagramSocket().use { udpSocket ->
                    udpSocket.broadcast = true
                    udpSocket.soTimeout = 2000
                    val sendData = "FRCAM_WEAR_REQUEST_SYNC".toByteArray(Charsets.UTF_8)
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    udpSocket.send(DatagramPacket(sendData, sendData.size, broadcastAddr, LAN_SYNC_PORT))

                    val recvBuf = ByteArray(32768)
                    val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                    udpSocket.receive(recvPacket)
                    val text = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                    if (text.startsWith("FRCAM_CAMERAS_JSON:")) {
                        val camerasJson = text.removePrefix("FRCAM_CAMERAS_JSON:")
                        WearCameraStorage.saveJsonString(this@WearMainActivity, camerasJson)
                        syncedCount = WearCameraStorage.cameras.value.size
                        Log.d(TAG, "Sincronizado vía Wi-Fi local directo! $syncedCount cámaras")
                    }
                }
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                val current = WearCameraStorage.cameras.value.size
                if (current > 0) {
                    Toast.makeText(this@WearMainActivity, "¡$current cámaras sincronizadas con éxito!", Toast.LENGTH_SHORT).show()
                } else if (isManualTap) {
                    if (nodeCount > 0) {
                        Toast.makeText(this@WearMainActivity, "Pidiendo cámaras al celular ($nodeCount conectado)...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@WearMainActivity, "Buscando celular... Asegúrate de que FrCam esté abierto en tu teléfono.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
