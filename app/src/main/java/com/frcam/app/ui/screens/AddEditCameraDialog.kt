@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.frcam.app.ui.screens

import com.frcam.app.ui.theme.FrColors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.frcam.app.media.CameraMediaEngine
import com.frcam.app.media.MotionEventRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.frcam.app.protocol.CameraProvisioningEngine
import com.frcam.app.protocol.WaitingCameraAp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import com.frcam.app.protocol.CameraAcousticPairingEngine
import com.frcam.app.protocol.QrWifiFormat
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.ConnectionMode
import com.frcam.app.model.DiscoveredCameraDevice
import com.frcam.app.model.PtzControlType
import com.frcam.app.model.TransportMode
import com.frcam.app.protocol.CameraControlEngine
import com.frcam.app.protocol.ConnectionDiagnosticResult
import com.frcam.app.protocol.RingCloudDevice
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditCameraSheet(
    initialCamera: CameraConfig?,
    isTestingConnection: Boolean,
    diagnosticResult: ConnectionDiagnosticResult?,
    onTestConnection: (CameraConfig, (String) -> Unit) -> Unit,
    onSave: (CameraConfig) -> Unit,
    onDismiss: () -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var selectedBrand by remember {
        mutableStateOf(initialCamera?.brand ?: CameraBrand.V380_PRO)
    }
    var connectionMode by remember {
        mutableStateOf(initialCamera?.connectionMode ?: selectedBrand.defaultConnectionMode)
    }
    var deviceId by remember {
        mutableStateOf(initialCamera?.deviceId ?: "")
    }
    var modelCode by remember {
        mutableStateOf(initialCamera?.modelCode ?: "MVT26101")
    }
    var name by remember {
        mutableStateOf(initialCamera?.name ?: "Cámara ${selectedBrand.displayName}")
    }
    var host by remember {
        mutableStateOf(initialCamera?.host ?: "")
    }
    var rtspPortText by remember {
        mutableStateOf((initialCamera?.rtspPort ?: selectedBrand.defaultRtspPort).toString())
    }
    var httpPortText by remember {
        mutableStateOf((initialCamera?.httpPort ?: selectedBrand.defaultHttpPort).toString())
    }
    var onvifPortText by remember {
        mutableStateOf((initialCamera?.onvifPort ?: selectedBrand.defaultOnvifPort).toString())
    }
    var username by remember {
        mutableStateOf(initialCamera?.username ?: selectedBrand.defaultUsername)
    }
    var password by remember {
        mutableStateOf(initialCamera?.password ?: "")
    }
    var showPassword by remember { mutableStateOf(false) }
    var mainPath by remember {
        mutableStateOf(initialCamera?.mainStreamPath ?: selectedBrand.defaultMainPath)
    }
    var subPath by remember {
        mutableStateOf(initialCamera?.subStreamPath ?: selectedBrand.defaultSubPath)
    }
    var customUrl by remember {
        mutableStateOf(initialCamera?.customStreamUrl ?: "")
    }
    var transportMode by remember {
        mutableStateOf(initialCamera?.transportMode ?: TransportMode.TCP)
    }
    var ptzType by remember {
        mutableStateOf(initialCamera?.ptzType ?: selectedBrand.defaultPtzType)
    }
    var invertPan by remember {
        mutableStateOf(initialCamera?.invertPan ?: false)
    }
    var invertTilt by remember {
        mutableStateOf(initialCamera?.invertTilt ?: false)
    }
    var motionDetectionEnabled by remember {
        mutableStateOf(initialCamera?.motionDetectionEnabled ?: true)
    }
    var showV380IniDialog by remember { mutableStateOf(false) }

    fun buildCurrentConfig(): CameraConfig {
        val labelSuffix = if (deviceId.isNotBlank()) deviceId else host
        return CameraConfig(
            id = initialCamera?.id ?: UUID.randomUUID().toString(),
            name = name.ifBlank { "${selectedBrand.displayName} ($labelSuffix)" },
            brand = selectedBrand,
            connectionMode = connectionMode,
            deviceId = deviceId.trim(),
            modelCode = modelCode.trim(),
            host = host.trim(),
            remoteHost = initialCamera?.remoteHost.orEmpty(),
            remoteRtspPort = initialCamera?.remoteRtspPort ?: 0,
            remoteHttpPort = initialCamera?.remoteHttpPort ?: 0,
            upnpMapped = initialCamera?.upnpMapped ?: false,
            rtspPort = rtspPortText.toIntOrNull() ?: selectedBrand.defaultRtspPort,
            httpPort = httpPortText.toIntOrNull() ?: selectedBrand.defaultHttpPort,
            onvifPort = onvifPortText.toIntOrNull() ?: selectedBrand.defaultOnvifPort,
            username = username.trim(),
            password = password,
            mainStreamPath = mainPath.ifBlank { selectedBrand.defaultMainPath },
            subStreamPath = subPath.ifBlank { selectedBrand.defaultSubPath },
            customStreamUrl = customUrl.trim(),
            protocol = selectedBrand.defaultProtocol,
            transportMode = transportMode,
            ptzType = ptzType,
            invertPan = invertPan,
            invertTilt = invertTilt,
            motionDetectionEnabled = motionDetectionEnabled
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = FrColors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (initialCamera == null) "Agregar Cámara IP / Cloud" else "Configurar Cámara",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                }
            }

            Text(
                text = "1. Selecciona la Marca / App:",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF38BDF8)
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CameraBrand.entries.forEach { brand ->
                    FilterChip(
                        selected = selectedBrand == brand,
                        onClick = {
                            selectedBrand = brand
                            connectionMode = brand.defaultConnectionMode
                            rtspPortText = brand.defaultRtspPort.toString()
                            httpPortText = brand.defaultHttpPort.toString()
                            onvifPortText = brand.defaultOnvifPort.toString()
                            username = brand.defaultUsername
                            mainPath = brand.defaultMainPath
                            subPath = brand.defaultSubPath
                            ptzType = brand.defaultPtzType
                            if (name.startsWith("Cámara ")) {
                                name = "Cámara ${brand.displayName}"
                            }
                        },
                        label = {
                            Text(brand.displayName, fontSize = 12.sp)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Connection Mode Selector (Cloud Device ID vs Local LAN IP)
            Text(
                text = "2. Modo de Conexión:",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF38BDF8)
            )
            // FlowRow: las opciones saltan de línea en vez de apretarse y dejar un hueco vacío debajo.
            // Se quita el área táctil mínima de 48dp de los chips, que era la causa del espacio en blanco.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified
            ) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ConnectionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = connectionMode == mode,
                            onClick = { connectionMode = mode },
                            label = { Text(mode.label, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Brand setup guide banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C4A6E).copy(alpha = 0.45f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = selectedBrand.subtitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF7DD3FC)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedBrand.setupTip,
                        fontSize = 11.sp,
                        color = Color(0xFFE0F2FE)
                    )
                    if (selectedBrand == CameraBrand.V380_PRO) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    deviceId = "65077425"
                                    modelCode = "MVT26101"
                                    name = "V380 MVT26101 (65077425)"
                                    connectionMode = ConnectionMode.CLOUD_DEVICE_ID
                                }
                            ) {
                                Text("ID 65077425", fontSize = 11.sp)
                            }
                            FilledTonalButton(
                                onClick = {
                                    deviceId = "65078885"
                                    modelCode = "MVT26101"
                                    name = "V380 MVT26101 (65078885)"
                                    connectionMode = ConnectionMode.CLOUD_DEVICE_ID
                                }
                            ) {
                                Text("ID 65078885", fontSize = 11.sp)
                            }
                            TextButton(onClick = { showV380IniDialog = true }) {
                                Icon(Icons.Default.SdCard, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ceshi.ini", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre de la cámara (ej. Casa 2, Frente)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Device ID & Model Row (Especially for V380 Cloud Relay / Eye4 UID / Ring)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("ID de Cámara (V380 / Eye4 UID)") },
                    placeholder = { Text("65077425") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = modelCode,
                    onValueChange = { modelCode = it },
                    label = { Text("Modelo") },
                    placeholder = { Text("MVT26101") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario de Cámara") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña de Cámara") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Ocultar" else "Ver", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.weight(1.3f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = {
                        Text(
                            if (connectionMode == ConnectionMode.CLOUD_DEVICE_ID)
                                "IP Local Opcional (vacío si es remota)"
                            else "Dirección IP Local / Host"
                        )
                    },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    modifier = Modifier.weight(1.7f)
                )
                OutlinedTextField(
                    value = rtspPortText,
                    onValueChange = { rtspPortText = it.filter(Char::isDigit) },
                    label = { Text("Puerto (8800/554)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (connectionMode == ConnectionMode.LAN_IP || selectedBrand != CameraBrand.V380_PRO) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = mainPath,
                        onValueChange = { mainPath = it },
                        label = { Text("Ruta HD (Main)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = subPath,
                        onValueChange = { subPath = it },
                        label = { Text("Ruta SD (Sub)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = httpPortText,
                        onValueChange = { httpPortText = it.filter(Char::isDigit) },
                        label = { Text("Puerto HTTP/CGI") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = onvifPortText,
                        onValueChange = { onvifPortText = it.filter(Char::isDigit) },
                        label = { Text("Puerto ONVIF") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // PTZ Selector
            Text(
                text = "Protocolo de Movimiento PTZ:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF94A3B8)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PtzControlType.entries.forEach { type ->
                    FilterChip(
                        selected = ptzType == type,
                        onClick = { ptzType = type },
                        label = { Text(type.label, fontSize = 11.sp) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invertir giro horizontal (Pan)", fontSize = 12.sp)
                Switch(checked = invertPan, onCheckedChange = { invertPan = it })
                Text("Invertir vertical (Tilt)", fontSize = 12.sp)
                Switch(checked = invertTilt, onCheckedChange = { invertTilt = it })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(FrColors.cardHigh)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Alerta de Movimiento (Estilo Ring)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Notificación push con foto capturada de la cámara en vivo",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Switch(
                    checked = motionDetectionEnabled,
                    onCheckedChange = { motionDetectionEnabled = it }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Connection Diagnostic Tester Button
            OutlinedButton(
                onClick = {
                    val cfg = buildCurrentConfig()
                    onTestConnection(cfg) { resolvedUri ->
                        val afterScheme = resolvedUri.substringAfter("://")
                        val hostPortPath = afterScheme.substringAfter("@", afterScheme)
                        val detectedPort = hostPortPath.substringBefore("/").substringAfter(":", "")
                        val detectedPath = "/" + hostPortPath.substringAfter("/", "")
                        if (detectedPort.isNotBlank()) rtspPortText = detectedPort
                        if (detectedPath.length > 1) mainPath = detectedPath
                    }
                },
                enabled = !isTestingConnection,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verificando conexión / Cloud Relay V380...")
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Probar Conexión y Autenticación")
                }
            }

            diagnosticResult?.let { diag ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (diag.success) Color(0xFF064E3B) else Color(0xFF450A0A)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (diag.success) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (diag.success) Color(0xFF34D399) else Color(0xFFFCA5A5),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = diag.summary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        diag.details.forEach { line ->
                            Text(
                                text = "• $line",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancelar")
                }
                Button(
                    onClick = {
                        onSave(buildCurrentConfig())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Guardar Cámara", fontWeight = FontWeight.Bold)
                }
            }

            if (initialCamera != null && initialCamera.id.isNotBlank() && onDelete != null) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Eliminar Cámara de FrCam", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (showDeleteConfirm && initialCamera != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("¿Eliminar Cámara?", fontWeight = FontWeight.Bold) },
            text = { Text("¿Estás seguro de que deseas eliminar permanentemente \"${name}\" de tu lista de cámaras?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(initialCamera.id)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Eliminar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showV380IniDialog) {
        V380UnlockGuideDialog(
            context = context,
            onDismiss = { showV380IniDialog = false }
        )
    }
}

@Composable
fun RingCloudLoginDialog(
    savedRefreshToken: String,
    isLoggingIn: Boolean,
    statusMessage: String,
    needsTwoFactor: Boolean,
    discoveredDevices: List<RingCloudDevice>,
    onAuthenticate: (String, String, String, String) -> Unit,
    onImportDevice: (RingCloudDevice, String) -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var twoFactorCode by remember { mutableStateOf("") }
    var refreshToken by remember(savedRefreshToken) { mutableStateOf(savedRefreshToken) }
    var bridgeHost by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Conectar Cuenta Ring (Cloud)", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Inicia sesión con tu cuenta oficial de Ring (correo + contraseña + código 2FA) o pega un Refresh Token para descubrir tus timbres y cámaras Ring.",
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1)
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Correo de Ring") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña de Ring") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (needsTwoFactor) {
                    OutlinedTextField(
                        value = twoFactorCode,
                        onValueChange = { twoFactorCode = it },
                        label = { Text("Código 2FA (SMS / Correo / App)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = refreshToken,
                    onValueChange = { refreshToken = it },
                    label = { Text("Opcional: Refresh Token de Ring") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (statusMessage.isNotBlank()) {
                    Text(
                        text = statusMessage,
                        fontSize = 11.sp,
                        color = if (discoveredDevices.isNotEmpty()) Color(0xFF34D399) else Color(0xFF38BDF8)
                    )
                }

                if (discoveredDevices.isNotEmpty()) {
                    HorizontalDivider(color = FrColors.cardHigh)
                    Text(
                        text = "Selecciona tus cámaras para importarlas. Dejar la IP vacía activará Ring Cloud Nativo directo sin puente.",
                        fontSize = 11.sp,
                        color = Color(0xFF34D399)
                    )
                    OutlinedTextField(
                        value = bridgeHost,
                        onValueChange = { bridgeHost = it },
                        label = { Text("IP de puente RTSP (Opcional, vacío = Cloud Directo)") },
                        placeholder = { Text("Dejar vacío para Ring Cloud Directo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    discoveredDevices.forEach { dev ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onImportDevice(dev, bridgeHost) },
                            colors = CardDefaults.cardColors(containerColor = FrColors.cardHigh),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(dev.description, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                    Text("ID: ${dev.id} • Tipo: ${dev.kind}", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                                Icon(
                                    imageVector = Icons.Default.AddCircleOutline,
                                    contentDescription = "Importar",
                                    tint = Color(0xFF34D399)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAuthenticate(email, password, twoFactorCode, refreshToken) },
                enabled = !isLoggingIn
            ) {
                Text(if (isLoggingIn) "Autenticando..." else if (needsTwoFactor) "Verificar 2FA" else "Conectar Ring")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
fun V380UnlockGuideDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val iniContent = remember { CameraControlEngine.generateV380RtspUnlockIni() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Desbloqueo RTSP / ONVIF en V380 PRO",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Nota: FrCam ya incluye el cliente nativo V380 (Puerto 8800 y Cloud Relay por ID), por lo que NO necesitas modificar tu cámara V380.",
                    fontSize = 12.sp,
                    color = Color(0xFF34D399)
                )
                Text(
                    text = "Si además deseas habilitar RTSP estándar (puerto 554) y ONVIF (puerto 8899) en la cámara V380 mediante tarjeta MicroSD, crea 'ceshi.ini' con este contenido:",
                    fontSize = 12.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FrColors.background, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = iniContent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFF34D399)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ceshi.ini", iniContent))
                    Toast.makeText(context, "Contenido de ceshi.ini copiado al portapapeles", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copiar ceshi.ini")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
fun LanDiscoveryDialog(
    localPhoneIp: String?,
    isScanning: Boolean,
    scanProgress: Float,
    scanStatusText: String,
    discoveredDevices: List<DiscoveredCameraDevice>,
    onStartScan: () -> Unit,
    onSelectDevice: (DiscoveredCameraDevice) -> Unit,
    waitingCameraAps: List<WaitingCameraAp> = emptyList(),
    isScanningWaiting: Boolean = false,
    isProvisioning: Boolean = false,
    provisioningStatus: String? = null,
    onScanWaitingCameras: () -> Unit = {},
    onProvisionWaitingCamera: (WaitingCameraAp, String, String) -> Unit = { _, _, _ -> },
    onAddWaitingCameraDirect: (WaitingCameraAp) -> Unit = {},
    onOpenManualAdd: ((CameraBrand) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var pairingMethod by remember { mutableIntStateOf(0) }
    var selectedQrFormat by remember { mutableStateOf(QrWifiFormat.UNIVERSAL_WIFI) }

    // Estados para el aprovisionamiento de cámara nueva a Wi-Fi de casa
    var targetApToProvision by remember { mutableStateOf<WaitingCameraAp?>(null) }
    var homeWifiSsid by remember { mutableStateOf("") }
    var homeWifiPassword by remember { mutableStateOf("") }
    var showHomeWifiPassword by remember { mutableStateOf(false) }

    val isEmittingSound by CameraAcousticPairingEngine.isEmitting.collectAsState()
    val soundSecondsRemaining by CameraAcousticPairingEngine.secondsRemaining.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            CameraAcousticPairingEngine.stop()
        }
    }

    // Auto-detectar SSID de la red Wi-Fi actual del móvil
    LaunchedEffect(Unit) {
        val (curSsid, _) = CameraProvisioningEngine.getCurrentWifiConnection(context)
        if (!curSsid.isNullOrBlank() && homeWifiSsid.isBlank()) {
            homeWifiSsid = curSsid
        }
    }

    val qrBitmap = remember(homeWifiSsid, homeWifiPassword, selectedQrFormat) {
        if (homeWifiSsid.isNotBlank()) {
            CameraProvisioningEngine.generateWifiQrBitmap(homeWifiSsid, homeWifiPassword, selectedQrFormat, size = 512)
        } else {
            null
        }
    }

    // Al abrir la pestaña de cámaras nuevas en modo AP, escanear automáticamente
    LaunchedEffect(selectedTab, pairingMethod) {
        if (selectedTab == 1 && pairingMethod == 2 && waitingCameraAps.isEmpty() && !isScanningWaiting) {
            onScanWaitingCameras()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (selectedTab == 0) Icons.Default.Radar else Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedTab == 0) "Buscar Cámaras en Red" else "Cámaras Nuevas o Reseteadas",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "IP del teléfono: ${localPhoneIp ?: "Conectando al Wi-Fi..."}",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Selector de Pestañas
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = FrColors.cardHigh,
                    contentColor = Color(0xFF38BDF8)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("En Red (LAN)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Nuevas / AP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if (waitingCameraAps.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF10B981), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "${waitingCameraAps.size}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                // ================= PESTAÑA 0: CÁMARAS EN RED (LAN) =================
                if (selectedTab == 0) {
                    Text(
                        text = "Busca cámaras ya conectadas a tu router Wi-Fi: V380 (10008/8800), Eye4 (10554), V720 (6123), ONVIF y RTSP.",
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1)
                    )

                    if (isScanning) {
                        LinearProgressIndicator(
                            progress = { scanProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = scanStatusText,
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8)
                        )
                    } else if (scanStatusText.isNotBlank()) {
                        Text(
                            text = scanStatusText,
                            fontSize = 11.sp,
                            color = Color(0xFF34D399)
                        )
                    }

                    HorizontalDivider(color = FrColors.cardHigh)

                    if (discoveredDevices.isEmpty() && !isScanning) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Presiona 'Escanear Ahora' para buscar cámaras en tu red.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "¿La cámara es nueva o la acabas de resetear? Toca la pestaña 'Nuevas / AP'.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(discoveredDevices, key = { it.ipAddress }) { dev ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectDevice(dev) },
                                    colors = CardDefaults.cardColors(containerColor = FrColors.cardHigh),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Router,
                                                contentDescription = null,
                                                tint = Color(0xFF38BDF8),
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = dev.deviceName,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = Color.White
                                                )
                                                Text(
                                                    text = "${dev.detectedBrand.displayName} • ${dev.macOrHardwareInfo}",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF94A3B8)
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Default.AddCircleOutline,
                                            contentDescription = "Agregar",
                                            tint = Color(0xFF34D399)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ================= PESTAÑA 1: VINCULAR CÁMARA (MULTIMODAL) =================
                if (selectedTab == 1) {
                    // Selector de Alternativas de Vinculación
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = pairingMethod == 0,
                                onClick = { pairingMethod = 0 },
                                label = { Text("1. Mostrar QR (Fácil)", fontSize = 11.sp, fontWeight = if (pairingMethod == 0) FontWeight.Bold else FontWeight.Normal) }
                            )
                            FilterChip(
                                selected = pairingMethod == 1,
                                onClick = { pairingMethod = 1 },
                                label = { Text("2. Onda Sonora (Pitido)", fontSize = 11.sp, fontWeight = if (pairingMethod == 1) FontWeight.Bold else FontWeight.Normal) }
                            )
                            FilterChip(
                                selected = pairingMethod == 2,
                                onClick = { pairingMethod = 2 },
                                label = { Text("3. Wi-Fi AP Cámara", fontSize = 11.sp, fontWeight = if (pairingMethod == 2) FontWeight.Bold else FontWeight.Normal) }
                            )
                            FilterChip(
                                selected = pairingMethod == 3,
                                onClick = { pairingMethod = 3 },
                                label = { Text("4. Manual", fontSize = 11.sp, fontWeight = if (pairingMethod == 3) FontWeight.Bold else FontWeight.Normal) }
                            )
                        }
                    }

                    // Datos Wi-Fi de Casa para los Métodos 1, 2 y 3
                    if (pairingMethod in 0..2) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = FrColors.cardHigh),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Datos de tu red Wi-Fi de Casa (2.4 GHz):",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF38BDF8)
                                )
                                OutlinedTextField(
                                    value = homeWifiSsid,
                                    onValueChange = { homeWifiSsid = it },
                                    label = { Text("Nombre Wi-Fi de casa (SSID)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = homeWifiPassword,
                                    onValueChange = { homeWifiPassword = it },
                                    label = { Text("Contraseña Wi-Fi de casa") },
                                    singleLine = true,
                                    visualTransformation = if (showHomeWifiPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { showHomeWifiPassword = !showHomeWifiPassword }) {
                                            Text(if (showHomeWifiPassword) "Ocultar" else "Ver", fontSize = 11.sp)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // ---------- MÉTODO 0: MOSTRAR CÓDIGO QR AL LENTE (PREVALECE / POR DEFECTO) ----------
                    if (pairingMethod == 0) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Selector de Formato QR
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Formato:", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                QrWifiFormat.entries.forEach { fmt ->
                                    FilterChip(
                                        selected = selectedQrFormat == fmt,
                                        onClick = { selectedQrFormat = fmt },
                                        label = {
                                            Text(
                                                text = when (fmt) {
                                                    QrWifiFormat.UNIVERSAL_WIFI -> "Universal"
                                                    QrWifiFormat.JSON_CAMERA -> "V720/JSON"
                                                    QrWifiFormat.EYE4_VSTARCAM -> "Eye4"
                                                    QrWifiFormat.PLAIN_TAB -> "Texto"
                                                },
                                                fontSize = 9.sp
                                            )
                                        }
                                    )
                                }
                            }

                            if (qrBitmap != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(200.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Image(
                                            bitmap = qrBitmap.asImageBitmap(),
                                            contentDescription = "Código QR para Cámara",
                                            modifier = Modifier.size(185.dp)
                                        )
                                    }
                                }
                            } else {
                                Text("Ingresa el SSID de tu Wi-Fi para ver el código QR", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "1. Muestra este código QR directamente al lente de la cámara a 15-20 cm.\n2. La cámara emitirá un pitido o aviso: 'QR code scanned'.\n3. Presiona el botón abajo para encontrarla en la red.",
                                        fontSize = 11.sp,
                                        color = Color(0xFFCBD5E1)
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    selectedTab = 0
                                    onStartScan()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                            ) {
                                Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Buscar Cámara en Red tras Escaneo QR", fontSize = 11.sp)
                            }
                        }
                    }

                    // ---------- MÉTODO 1: EMITIR ONDA SONORA / PITIDO (SONIC PAIRING) ----------
                    if (pairingMethod == 1) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (isEmittingSound) Color(0xFF78350F) else FrColors.cardHigh),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = if (isEmittingSound) Color(0xFFF59E0B) else Color(0xFF38BDF8),
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = if (isEmittingSound) "EMITIENDO PITIDOS SONOROS (${soundSecondsRemaining}s)" else "Emparejamiento por Onda Sonora (Pitidos)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isEmittingSound) Color(0xFFF59E0B) else Color.White
                                    )
                                    Text(
                                        text = "Modula tus credenciales Wi-Fi en tonos acústicos. Sube el volumen multimedia al máximo y acerca el altavoz del celular al micrófono de la cámara (5-10 cm).",
                                        fontSize = 11.sp,
                                        color = Color(0xFFCBD5E1),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            if (isEmittingSound) {
                                Button(
                                    onClick = { CameraAcousticPairingEngine.stop() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Detener Emisión de Sonido", fontSize = 11.sp)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (homeWifiSsid.isNotBlank()) {
                                            CameraAcousticPairingEngine.startSonicPairing(
                                                scope = coroutineScope,
                                                ssid = homeWifiSsid,
                                                pass = homeWifiPassword
                                            ) {
                                                Toast.makeText(context, "Transmisión sonora completada", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Ingresa el nombre de red Wi-Fi", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = homeWifiSsid.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Emitir Pitidos Sonoros a la Cámara", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // ---------- MÉTODO 2: MODO AP (PUNTO DE ACCESO DE LA CÁMARA) ----------
                    if (pairingMethod == 2) {
                        if (isScanningWaiting) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF10B981)
                            )
                            Text(
                                text = "Buscando puntos de acceso Wi-Fi de cámaras (NAX*, V720*, VSTC*, MV*)...",
                                fontSize = 11.sp,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        if (!provisioningStatus.isNullOrBlank()) {
                            Text(
                                text = provisioningStatus,
                                fontSize = 11.sp,
                                color = if (provisioningStatus.contains("Error", ignoreCase = true)) Color(0xFFEF4444) else Color(0xFF34D399)
                            )
                        }

                        val connectedCam = waitingCameraAps.find { it.isCurrentlyConnected }
                        if (connectedCam != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.WifiTethering, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("¡Conectado al Wi-Fi de la cámara!", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF34D399))
                                    }
                                    Text("${connectedCam.detectedBrand.displayName} • Red: ${connectedCam.ssid}", fontSize = 11.sp, color = Color.White)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { onProvisionWaitingCamera(connectedCam, homeWifiSsid, homeWifiPassword) },
                                            modifier = Modifier.weight(1f),
                                            enabled = homeWifiSsid.isNotBlank() && !isProvisioning,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                                        ) {
                                            Text(if (isProvisioning) "Enviando..." else "Vincular a Router", fontSize = 11.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { onAddWaitingCameraDirect(connectedCam) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Modo Directo", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        val otherAps = waitingCameraAps.filter { !it.isCurrentlyConnected }
                        if (otherAps.isNotEmpty()) {
                            Text("Puntos de acceso de cámaras encontrados:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8))
                            LazyColumn(modifier = Modifier.fillMaxWidth().height(140.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(otherAps, key = { it.ssid }) { ap ->
                                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FrColors.cardHigh), shape = RoundedCornerShape(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(ap.ssid, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                                Text("${ap.detectedBrand.displayName} • Señal: ${ap.signalPercent}%", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    CameraProvisioningEngine.openWifiSettings(context)
                                                    Toast.makeText(context, "Conéctate al Wi-Fi '${ap.ssid}' y regresa aquí para vincularla", Toast.LENGTH_LONG).show()
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("Conectar", fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (connectedCam == null && !isScanningWaiting) {
                            OutlinedButton(
                                onClick = { CameraProvisioningEngine.openWifiSettings(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Abrir Ajustes Wi-Fi de Android", fontSize = 11.sp)
                            }
                        }
                    }

                    // ---------- MÉTODO 3: INGRESO MANUAL CON PLANTILLAS ----------
                    if (pairingMethod == 3) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Toca para agregar directamente con IP o Cloud ID, usuario y clave:",
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1)
                            )
                            val manualBrands = listOf(
                                CameraBrand.EYE4_VSTARCAM to "Eye4 / VStarcam (RTSP 10554, Web 81, ONVIF 10080)",
                                CameraBrand.V720_NAXCLOW to "V720 Naxclow (UDP 6123, RTSP 554, Web 80/8080)",
                                CameraBrand.V380_PRO to "V380 PRO (Cloud Relay ID o Puerto 8800)",
                                CameraBrand.ONVIF_GENERIC to "ONVIF Estándar (Puerto 80/8899)",
                                CameraBrand.CUSTOM_RTSP to "RTSP Personalizado (Puerto 554)"
                            )
                            manualBrands.forEach { (brand, desc) ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenManualAdd?.invoke(brand) },
                                    colors = CardDefaults.cardColors(containerColor = FrColors.cardHigh),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(brand.displayName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                            Text(desc, fontSize = 10.sp, color = Color(0xFF94A3B8))
                                        }
                                        Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab == 0) {
                Button(
                    onClick = onStartScan,
                    enabled = !isScanning
                ) {
                    Text(if (isScanning) "Escaneando..." else "Escanear Red")
                }
            } else if (pairingMethod == 2) {
                Button(
                    onClick = onScanWaitingCameras,
                    enabled = !isScanningWaiting
                ) {
                    Text(if (isScanningWaiting) "Buscando..." else "Buscar Wi-Fi de Cámaras")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
fun MotionEventsHistoryDialog(
    events: List<MotionEventRecord>,
    onDismiss: () -> Unit,
    onSelectCamera: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Eventos de Movimiento (${events.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aún no se han detectado eventos de movimiento.\nLas capturas de video en vivo aparecerán aquí con fotos estilo Ring.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onSelectCamera(event.cameraId)
                                },
                            colors = CardDefaults.cardColors(containerColor = FrColors.cardHigh),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = event.cameraName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color.White
                                        )
                                        if (event.aiLabel != null) {
                                            Text(
                                                text = event.aiLabel,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp,
                                                color = Color(0xFF34D399)
                                            )
                                        }
                                    }
                                    Text(
                                        text = event.formattedTime,
                                        fontSize = 11.sp,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                val frameCount = event.animationFrames.size
                                var frameIdx by remember(event.id) { mutableIntStateOf(0) }
                                if (frameCount > 1) {
                                    LaunchedEffect(event.id) {
                                        while (isActive) {
                                            delay(280L)
                                            frameIdx = (frameIdx + 1) % frameCount
                                        }
                                    }
                                }
                                val currentBmp = event.animationFrames.getOrNull(frameIdx) ?: event.snapshot
                                Image(
                                    bitmap = currentBmp.asImageBitmap(),
                                    contentDescription = "Clip animado de movimiento",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${event.aiLabel?.let { "$it • " } ?: ""}${"%.1f".format(event.motionScorePercent)}% actividad • Toca para abrir cámara",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}
