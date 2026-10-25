package com.frcam.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frcam.app.model.CameraAccessRecord
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.CameraDeviceInfo
import com.frcam.app.model.WifiNetworkScanResult

enum class AdminTab(val title: String) {
    INFO("Info Dispositivo"),
    WIFI("Red Wi-Fi"),
    PASSWORD("Contraseña"),
    TIME("Hora / Reloj"),
    RECORD_PTZ("Grabación & PTZ"),
    ACCESS_LOGS("Registro Acceso"),
    IP_NETWORK("Config IP"),
    VOICE("Voz & Sonido"),
    MAINTENANCE("Mantenimiento")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraAdminBottomSheet(
    camera: CameraConfig,
    deviceInfo: CameraDeviceInfo?,
    accessLogs: List<CameraAccessRecord>,
    wifiScanList: List<WifiNetworkScanResult>,
    isScanningWifi: Boolean,
    statusMessage: String?,
    onDismiss: () -> Unit,
    onSyncTime: () -> Unit,
    onUpdatePassword: (String) -> Unit,
    onUpdateWifi: (String, String) -> Unit,
    onScanWifi: () -> Unit,
    onUpdateIp: (Boolean, String, String, String, String) -> Unit,
    onToggleVoicePrompts: (Boolean) -> Unit,
    onToggleMotionTracking: (Boolean) -> Unit,
    onTogglePtzPatrol: (Boolean) -> Unit,
    onFormatSdCard: () -> Unit,
    onReboot: () -> Unit,
    onFactoryReset: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(AdminTab.INFO) }

    var showRebootConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showFormatConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Administración: ${camera.name}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Control total y configuración interna de la cámara",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cerrar")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Feedback banner
            if (!statusMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Scrollable tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                AdminTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(text = tab.title, fontSize = 13.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    AdminTab.INFO -> DeviceInfoTab(camera, deviceInfo, onSyncTime)
                    AdminTab.WIFI -> WifiSettingsTab(
                        camera = camera,
                        deviceInfo = deviceInfo,
                        wifiScanList = wifiScanList,
                        isScanningWifi = isScanningWifi,
                        onScanWifi = onScanWifi,
                        onUpdateWifi = onUpdateWifi
                    )
                    AdminTab.PASSWORD -> PasswordSettingsTab(camera, onUpdatePassword)
                    AdminTab.TIME -> DeviceTimeTab(deviceInfo, onSyncTime)
                    AdminTab.RECORD_PTZ -> RecordAndPtzTab(
                        deviceInfo = deviceInfo,
                        onToggleMotionTracking = onToggleMotionTracking,
                        onTogglePtzPatrol = onTogglePtzPatrol,
                        onPromptFormatSd = { showFormatConfirm = true }
                    )
                    AdminTab.ACCESS_LOGS -> AccessLogsTab(accessLogs)
                    AdminTab.IP_NETWORK -> IpNetworkTab(camera, deviceInfo, onUpdateIp)
                    AdminTab.VOICE -> VoicePromptsTab(deviceInfo, onToggleVoicePrompts)
                    AdminTab.MAINTENANCE -> MaintenanceTab(
                        onPromptReboot = { showRebootConfirm = true },
                        onPromptReset = { showResetConfirm = true }
                    )
                }
            }
        }
    }

    // Confirmation dialogs
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("¿Reiniciar Cámara?") },
            text = { Text("La cámara se reiniciará inmediatamente. Estará desconectada durante aproximadamente 30 segundos.") },
            confirmButton = {
                Button(
                    onClick = {
                        showRebootConfirm = false
                        onReboot()
                    }
                ) {
                    Text("Reiniciar ahora")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRebootConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("¿Restablecer de Fábrica?") },
            text = { Text("Esta acción borrará la configuración de Wi-Fi, contraseñas y parámetros de la cámara, devolviéndola al estado original de fábrica.") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        onFactoryReset()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Restablecer")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showFormatConfirm) {
        AlertDialog(
            onDismissRequest = { showFormatConfirm = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("¿Formatear Tarjeta MicroSD?") },
            text = { Text("Se borrarán todos los videos y fotos almacenados en la tarjeta de memoria de la cámara.") },
            confirmButton = {
                Button(
                    onClick = {
                        showFormatConfirm = false
                        onFormatSdCard()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Formatear")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFormatConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun DeviceInfoTab(
    camera: CameraConfig,
    deviceInfo: CameraDeviceInfo?,
    onSyncTime: () -> Unit
) {
    val info = deviceInfo ?: CameraDeviceInfo(deviceId = camera.deviceId)
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Datos del Hardware y Firmware",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                InfoRow(label = "Modelo de Cámara:", value = info.model)
                InfoRow(label = "ID del Dispositivo:", value = info.deviceId)
                InfoRow(label = "Versión de Firmware:", value = info.firmwareVersion)
                InfoRow(label = "Dirección MAC:", value = info.macAddress)
                InfoRow(label = "Dirección IP:", value = info.ipAddress)
                InfoRow(label = "Servidor Cloud Relay:", value = info.cloudRelayServer)
                InfoRow(label = "Modo de Red:", value = if (info.isDhcp) "DHCP (Automático)" else "IP Estática")
                InfoRow(label = "Red Wi-Fi Conectada:", value = "${info.connectedWifiSsid} (${info.wifiSignalPercent}%)")
                InfoRow(label = "Hora del Dispositivo:", value = info.deviceTimeFormatted)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        FilledTonalButton(
            onClick = onSyncTime,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sincronizar reloj de la cámara con el teléfono")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WifiSettingsTab(
    camera: CameraConfig,
    deviceInfo: CameraDeviceInfo?,
    wifiScanList: List<WifiNetworkScanResult>,
    isScanningWifi: Boolean,
    onScanWifi: () -> Unit,
    onUpdateWifi: (String, String) -> Unit
) {
    var targetSsid by remember { mutableStateOf(deviceInfo?.connectedWifiSsid ?: "") }
    var wifiPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Wi-Fi Actual de la Cámara",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Conectada a: ${deviceInfo?.connectedWifiSsid ?: "Buscando..."} (Señal: ${deviceInfo?.wifiSignalPercent ?: 85}%)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Redes Wi-Fi Detectadas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(
                onClick = onScanWifi,
                enabled = !isScanningWifi
            ) {
                if (isScanningWifi) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Escaneando...")
                } else {
                    Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Escanear Wi-Fi")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (wifiScanList.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(8.dp)
            ) {
                wifiScanList.forEach { ap ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { targetSsid = ap.ssid }
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (ap.isEncrypted) Icons.Default.WifiLock else Icons.Default.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (targetSsid == ap.ssid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ap.ssid,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (targetSsid == ap.ssid) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Text(
                            text = "${ap.signalPercent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Cambiar Red Wi-Fi",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = targetSsid,
            onValueChange = { targetSsid = it },
            label = { Text("Nombre de Red (SSID)") },
            leadingIcon = { Icon(Icons.Default.Router, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = wifiPassword,
            onValueChange = { wifiPassword = it },
            label = { Text("Contraseña Wi-Fi") },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onUpdateWifi(targetSsid, wifiPassword) },
            modifier = Modifier.fillMaxWidth(),
            enabled = targetSsid.isNotBlank()
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Guardar y Conectar Wi-Fi")
        }
    }
}

@Composable
private fun PasswordSettingsTab(
    camera: CameraConfig,
    onUpdatePassword: (String) -> Unit
) {
    var showCurrentPassword by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Contraseña Actual de Acceso",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showCurrentPassword) {
                            camera.password.ifBlank { "(Sin contraseña / admin)" }
                        } else {
                            "••••••••"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showCurrentPassword = !showCurrentPassword }) {
                        Icon(
                            imageVector = if (showCurrentPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Mostrar contraseña"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Modificar Contraseña de la Cámara",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = newPassword,
            onValueChange = {
                newPassword = it
                errorMessage = null
            },
            label = { Text("Nueva Contraseña") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                    Icon(
                        imageVector = if (newPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            },
            visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                errorMessage = null
            },
            label = { Text("Confirmar Nueva Contraseña") },
            leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
            visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (newPassword.isBlank()) {
                    errorMessage = "La contraseña no puede estar vacía"
                } else if (newPassword != confirmPassword) {
                    errorMessage = "Las contraseñas no coinciden"
                } else {
                    onUpdatePassword(newPassword)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Security, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Actualizar Contraseña")
        }
    }
}

@Composable
private fun DeviceTimeTab(
    deviceInfo: CameraDeviceInfo?,
    onSyncTime: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hora Actual del Dispositivo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = deviceInfo?.deviceTimeFormatted ?: "Sincronizando...",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Zona Horaria: Automática (Sincronizada con Android)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onSyncTime,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sincronizar Hora con el Celular")
        }
    }
}

@Composable
private fun RecordAndPtzTab(
    deviceInfo: CameraDeviceInfo?,
    onToggleMotionTracking: (Boolean) -> Unit,
    onTogglePtzPatrol: (Boolean) -> Unit,
    onPromptFormatSd: () -> Unit
) {
    val totalMb = deviceInfo?.sdCardTotalMb ?: 61440L
    val freeMb = deviceInfo?.sdCardFreeMb ?: 31200L
    val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
    val usedPercent = if (totalMb > 0) (usedMb.toFloat() / totalMb.toFloat()).coerceIn(0f, 1f) else 0.5f

    Column(modifier = Modifier.fillMaxWidth()) {
        // SD Card Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SdCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tarjeta MicroSD y Almacenamiento",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { usedPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Usado: ${usedMb / 1024} GB / ${totalMb / 1024} GB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Libre: ${freeMb / 1024} GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onPromptFormatSd,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Formatear Tarjeta MicroSD")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Motion and PTZ Patrol Switches
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Movimientos y Patrulla PTZ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Motion Tracking
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TrackChanges, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Seguimiento Humano Inteligente", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            text = "La cámara gira y sigue personas detectadas en movimiento",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deviceInfo?.motionTrackingEnabled ?: false,
                        onCheckedChange = onToggleMotionTracking
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // PTZ Patrol Cruise
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Patrulla Crucero 360°", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            text = "Giro horizontal continuo y escaneo panorámico",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deviceInfo?.ptzPatrolActive ?: false,
                        onCheckedChange = onTogglePtzPatrol
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessLogsTab(accessLogs: List<CameraAccessRecord>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Historial de Conexiones a la Cámara",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Registro de auditoría indicando fecha, hora, dirección IP y dispositivo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (accessLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No hay registros de acceso aún", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            accessLogs.forEach { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = log.timestampFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = log.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (log.status.contains("Activa", ignoreCase = true) || log.status.contains("OK", ignoreCase = true) || log.status.contains("Éxito", ignoreCase = true)) {
                                    Color(0xFF2E7D32)
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "IP: ${log.clientIp}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = log.connectionType,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "Cliente: ${log.clientDevice}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IpNetworkTab(
    camera: CameraConfig,
    deviceInfo: CameraDeviceInfo?,
    onUpdateIp: (Boolean, String, String, String, String) -> Unit
) {
    var isDhcp by remember { mutableStateOf(deviceInfo?.isDhcp ?: camera.isDhcp) }
    var ipAddress by remember { mutableStateOf(deviceInfo?.ipAddress ?: camera.host) }
    var netmask by remember { mutableStateOf(deviceInfo?.netmask ?: camera.staticNetmask.ifBlank { "255.255.255.0" }) }
    var gateway by remember { mutableStateOf(deviceInfo?.gateway ?: camera.staticGateway.ifBlank { "192.168.1.1" }) }
    var dns by remember { mutableStateOf(deviceInfo?.dns ?: camera.staticDns.ifBlank { "8.8.8.8" }) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Asignación de IP",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isDhcp) "DHCP: El router asigna la IP automáticamente" else "Manual: Dirección IP estática fija",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isDhcp,
                        onCheckedChange = { isDhcp = it }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isDhcp) {
            Text(
                text = "Parámetros de Red Estática",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                label = { Text("Dirección IP (ej. 192.168.1.150)") },
                leadingIcon = { Icon(Icons.Default.Lan, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = netmask,
                onValueChange = { netmask = it },
                label = { Text("Máscara de Subred") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = gateway,
                onValueChange = { gateway = it },
                label = { Text("Puerta de Enlace (Gateway)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = dns,
                onValueChange = { dns = it },
                label = { Text("Servidor DNS") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = { onUpdateIp(isDhcp, ipAddress, netmask, gateway, dns) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Guardar Configuración de Red")
        }
    }
}

@Composable
private fun VoicePromptsTab(
    deviceInfo: CameraDeviceInfo?,
    onToggleVoicePrompts: (Boolean) -> Unit
) {
    val enabled = deviceInfo?.voicePromptsEnabled ?: true
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (enabled) Icons.Default.RecordVoiceOver else Icons.Default.VoiceOverOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Mensajes de Voz de la Cámara",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (enabled) "Avisos por altavoz ACTIVADOS" else "Avisos por altavoz SILENCIADOS",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Silencia o activa los audios hablados que reproduce la cámara al iniciar, conectar Wi-Fi o alarmas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggleVoicePrompts
                    )
                }
            }
        }
    }
}

@Composable
private fun MaintenanceTab(
    onPromptReboot: () -> Unit,
    onPromptReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Mantenimiento y Control Remoto",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reinicia la cámara de forma segura o restablece los valores de fábrica si necesitas configurarla desde cero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onPromptReboot,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reiniciar Cámara (Reboot)")
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onPromptReset,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restablecer a Valores de Fábrica")
                }
            }
        }
    }
}
