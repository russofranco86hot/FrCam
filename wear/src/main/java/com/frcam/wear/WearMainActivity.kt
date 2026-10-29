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
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class WearDisplayMode {
    GRID,      // "Juntas" (Cuadrícula / Mosaico)
    FULLSCREEN // "Individual" (100% Pantalla Completa con Zoom)
}

class WearMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WearMainActivity"
        private const val REQUEST_SYNC_PATH = "/frcam/request_sync"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WearCameraStorage.init(this)

        // Si no hay cámaras al abrir, pedir inmediatamente sincronización al celular
        if (WearCameraStorage.cameras.value.isEmpty()) {
            requestSyncFromPhone()
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
                                    requestSyncFromPhone()
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

    private fun requestSyncFromPhone() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                if (nodes.isEmpty()) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@WearMainActivity, "Celular no conectado via Bluetooth", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val messageClient = Wearable.getMessageClient(this@WearMainActivity)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, REQUEST_SYNC_PATH, byteArrayOf()).await()
                    Log.d(TAG, "Solicitud de cámaras enviada al celular (${node.displayName})")
                }
                launch(Dispatchers.Main) {
                    Toast.makeText(this@WearMainActivity, "Sincronizando con celular...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pidiendo cámaras al celular", e)
            }
        }
    }
}
