package com.frcam.app.ui.screens

import com.frcam.app.ui.theme.FrColors

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.GridLayoutMode
import com.frcam.app.protocol.NetworkCameraScanner
import com.frcam.app.model.PtzControlType
import com.frcam.app.ui.player.UniversalCameraPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraDashboardScreen(
    cameras: List<CameraConfig>,
    gridMode: GridLayoutMode,
    localPhoneIp: String?,
    onToggleGridMode: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenRingLogin: () -> Unit,
    onOpenMotionHistory: () -> Unit,
    onAddCamera: (CameraBrand?) -> Unit,
    onSelectCamera: (CameraConfig) -> Unit,
    onEditCamera: (CameraConfig) -> Unit,
    onToggleLiveDashboard: (CameraConfig, Boolean) -> Unit,
    onToggleMotion: (CameraConfig, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onExportCameras: (Context, Uri, (String) -> Unit) -> Unit,
    onImportCameras: (Context, Uri, (Result<Int>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var showBackupDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            onExportCameras(context, uri) { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCameras(context, uri) { result ->
                result.onSuccess { count ->
                    Toast.makeText(context, "Se importaron $count cámaras con éxito", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "Error al importar cámaras", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8)
                    )
                    Text("Copia de Seguridad de Cámaras", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Guarda o restaura la configuración de todas tus cámaras (${cameras.size} configuradas) para no perderlas al cambiar de celular o reinstalar la app.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = {
                            showBackupDialog = false
                            val filename = "FrCam_Respaldo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
                            exportLauncher.launch(filename)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Exportar Cámaras a Archivo JSON")
                    }

                    FilledTonalButton(
                        onClick = {
                            showBackupDialog = false
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Importar Cámaras desde Archivo JSON")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showBackupDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "FrCam Monitor Pro",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${cameras.size} cámara(s) • LAN: ${localPhoneIp ?: "Wi-Fi"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGallery) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Galería de fotos, videos y GIF",
                            tint = Color(0xFFA78BFA)
                        )
                    }
                    IconButton(onClick = onOpenMotionHistory) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Alertas de Movimiento",
                            tint = Color(0xFFF59E0B)
                        )
                    }
                    IconButton(onClick = onToggleGridMode) {
                        Icon(
                            imageVector = when (gridMode) {
                                GridLayoutMode.SINGLE -> Icons.Default.ViewAgenda
                                GridLayoutMode.GRID_2X2 -> Icons.Default.GridView
                                GridLayoutMode.GRID_3X3 -> Icons.Default.GridOn
                            },
                            contentDescription = "Cambiar Cuadrícula (${gridMode.label})"
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Más opciones",
                                tint = Color(0xFFCBD5E1)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Ajustes Generales") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Escanear Red Wi-Fi") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Radar,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenScanner()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Conectar Cuenta Ring") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = null,
                                        tint = Color(0xFF34D399)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenRingLogin()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copia de Seguridad y Restauración") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.SettingsBackupRestore,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showBackupDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAddCamera(null) },
                containerColor = Color(0xFF0284C7),
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Agregar Cámara", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        if (cameras.isEmpty()) {
            EmptyCameraOnboarding(
                onOpenScanner = onOpenScanner,
                onOpenRingLogin = onOpenRingLogin,
                onQuickAddBrand = { brand -> onAddCamera(brand) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridMode.columns),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = paddingValues.calculateTopPadding() + 10.dp,
                    bottom = paddingValues.calculateBottomPadding() + 88.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(cameras, key = { it.id }) { camera ->
                    CameraGridTile(
                        camera = camera,
                        isCompact = gridMode.columns >= 2,
                        onToggleLiveDashboard = { active -> onToggleLiveDashboard(camera, active) },
                        onToggleMotion = { enabled -> onToggleMotion(camera, enabled) },
                        onOpenDetail = { onSelectCamera(camera) },
                        onEdit = { onEditCamera(camera) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraGridTile(
    camera: CameraConfig,
    isCompact: Boolean,
    onToggleLiveDashboard: (Boolean) -> Unit,
    onToggleMotion: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
    onEdit: () -> Unit
) {
    var isMuted by remember(camera.id) { mutableStateOf(true) }
    val useSubStreamInGrid = isCompact && camera.preferSubStreamInGrid

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FrColors.cardHigh, RoundedCornerShape(14.dp))
            .clickable { onOpenDetail() },
        colors = CardDefaults.cardColors(containerColor = FrColors.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Color.Black)
            ) {
                // La vista previa y las alertas de movimiento son independientes:
                // si hay vigilancia activa (alertas o grabación automática) el video sigue procesándose
                // por detrás de una tarjeta opaca aunque la vista previa esté desactivada.
                val monitoring = camera.motionDetectionEnabled || camera.autoRecordMode != "OFF"
                if (camera.liveInDashboard || monitoring) {
                    UniversalCameraPlayer(
                        camera = camera,
                        useSubStream = useSubStreamInGrid,
                        isMuted = isMuted,
                        enablePinchZoom = !isCompact && camera.liveInDashboard,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (camera.liveInDashboard) 1f else 0.02f }
                    )
                }
                if (!camera.liveInDashboard) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(FrColors.surfaceAlt)
                            .clickable { onToggleLiveDashboard(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircleOutline,
                                contentDescription = "Activar vista previa",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(38.dp)
                            )
                            Text(
                                text = if (camera.motionDetectionEnabled) "Vista previa desactivada" else "Vista previa pausada",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (monitoring) "Vigilando en segundo plano • toca para ver" else "Toca para ver en vivo",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Brand, Location & PTZ Badge Top-Right
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isLocal = NetworkCameraScanner.isSameLocalSubnet(camera.host)
                    Box(
                        modifier = Modifier
                            .background(
                                if (isLocal) Color(0xFF059669) else Color(0xFF0284C7),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (isLocal) "LAN" else "NUBE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    if (camera.isPtzCapable()) {
                        Icon(
                            imageVector = Icons.Default.OpenWith,
                            contentDescription = "PTZ",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    Text(
                        text = camera.brand.displayName.substringBefore(" "),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF7DD3FC)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = camera.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompact) 12.sp else 14.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = camera.displayAddressLabel(),
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Interruptor 1: vista previa en la pantalla principal
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Vista previa",
                        tint = if (camera.liveInDashboard) Color(0xFF38BDF8) else Color(0xFF64748B),
                        modifier = Modifier.size(14.dp)
                    )
                    Switch(
                        checked = camera.liveInDashboard,
                        onCheckedChange = { onToggleLiveDashboard(it) },
                        modifier = Modifier.scale(0.7f),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0284C7),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )

                    // Interruptor 2: notificaciones de movimiento (independiente de la vista previa)
                    IconButton(
                        onClick = { onToggleMotion(!camera.motionDetectionEnabled) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (camera.motionDetectionEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                            contentDescription = if (camera.motionDetectionEnabled) "Desactivar notificaciones de movimiento" else "Activar notificaciones de movimiento",
                            tint = if (camera.motionDetectionEnabled) Color(0xFFF59E0B) else Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (!isCompact) {
                        IconButton(
                            onClick = { isMuted = !isMuted },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Audio",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Editar",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = onOpenDetail,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Abrir consola PTZ y pantalla completa",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCameraOnboarding(
    onOpenScanner: () -> Unit,
    onOpenRingLogin: () -> Unit,
    onQuickAddBrand: (CameraBrand) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Centro Universal de Cámaras IP",
            fontSize = 21.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Conecta tus cámaras V380 PRO (Remotas por ID o Local), Eye4 (VStarcam), V720, ONVIF y Ring sin publicidad.",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onOpenScanner,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.Radar, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Escanear mi Red Wi-Fi Automáticamente",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        FilledTonalButton(
            onClick = onOpenRingLogin,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CloudDone, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Conectar Cuenta Ring (Cloud + 2FA)")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "O agrega directamente por marca:",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF7DD3FC)
        )

        Spacer(modifier = Modifier.height(10.dp))

        val featuredBrands = listOf(
            CameraBrand.V380_PRO,
            CameraBrand.EYE4_VSTARCAM,
            CameraBrand.V720_NAXCLOW,
            CameraBrand.RING_CLOUD,
            CameraBrand.ONVIF_GENERIC,
            CameraBrand.TPLINK_TAPO
        )

        featuredBrands.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { brand ->
                    FilledTonalButton(
                        onClick = { onQuickAddBrand(brand) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = brand.displayName,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
