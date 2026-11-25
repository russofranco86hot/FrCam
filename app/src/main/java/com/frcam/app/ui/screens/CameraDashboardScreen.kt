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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
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
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
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
    onOpenFullscreen: (CameraConfig) -> Unit = onSelectCamera,
    onEditCamera: (CameraConfig) -> Unit,
    onDeleteCamera: (CameraConfig) -> Unit,
    onToggleLiveDashboard: (CameraConfig, Boolean) -> Unit,
    onToggleMotion: (CameraConfig, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onSyncToWatch: (() -> Unit)? = null,
    onExportCameras: (Context, Uri, (String) -> Unit) -> Unit,
    onImportCameras: (Context, Uri, (Result<Int>) -> Unit) -> Unit,
    onUpdateCamera: ((CameraConfig) -> Unit)? = null,
    onArmAll: (Boolean) -> Unit = {},
    onSetGroupLive: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var showBackupDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var cameraToDelete by remember { mutableStateOf<CameraConfig?>(null) }

    val allGroups = remember(cameras) {
        val set = linkedSetOf("Todas")
        cameras.forEach { cam ->
            if (cam.groupName.isNotBlank()) set.add(cam.groupName.trim())
        }
        listOf("Casa", "Entrada", "Patio", "Oficina", "Garage", "Fondo").forEach { set.add(it) }
        set.toList()
    }
    var selectedGroup by remember { mutableStateOf("Todas") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var isPatrolModeActive by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupNameInput by remember { mutableStateOf("") }
    var cameraToMoveGroup by remember { mutableStateOf<CameraConfig?>(null) }
    var moveTargetGroup by remember { mutableStateOf("") }
    var showGroupAuthDialog by remember { mutableStateOf(false) }
    var groupAuthUser by remember { mutableStateOf("admin") }
    var groupAuthPass by remember { mutableStateOf("") }
    var groupAuthPassVisible by remember { mutableStateOf(false) }

    val displayedCameras = remember(cameras, selectedGroup, searchQuery) {
        val byGroup = if (selectedGroup == "Todas") {
            cameras
        } else {
            cameras.filter { it.groupName.equals(selectedGroup, ignoreCase = true) }
        }
        val q = searchQuery.trim()
        if (q.isBlank()) {
            byGroup
        } else {
            byGroup.filter { cam ->
                cam.name.contains(q, ignoreCase = true) ||
                    cam.host.contains(q, ignoreCase = true) ||
                    cam.brand.displayName.contains(q, ignoreCase = true) ||
                    cam.groupName.contains(q, ignoreCase = true)
            }
        }
    }

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
                    Toast.makeText(context, "Se importaron $count cÃ¡maras con Ã©xito", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "Error al importar cÃ¡maras", Toast.LENGTH_LONG).show()
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
                    Text("Copia de Seguridad de CÃ¡maras", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Guarda o restaura la configuraciÃ³n de todas tus cÃ¡maras (${cameras.size} configuradas) para no perderlas al cambiar de celular o reinstalar la app.",
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
                        Text("Exportar CÃ¡maras a Archivo JSON")
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
                        Text("Importar CÃ¡maras desde Archivo JSON")
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

    if (cameraToDelete != null) {
        val targetCam = cameraToDelete!!
        AlertDialog(
            onDismissRequest = { cameraToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Â¿Eliminar CÃ¡mara?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "Â¿EstÃ¡s seguro de que deseas eliminar \"${targetCam.name}\"? Se quitarÃ¡ de la pantalla principal.",
                    fontSize = 13.sp,
                    color = Color(0xFFCBD5E1)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cam = cameraToDelete
                        cameraToDelete = null
                        if (cam != null) {
                            onDeleteCamera(cam)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Eliminar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { cameraToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = { Text("Nuevo Grupo", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newGroupNameInput,
                    onValueChange = { newGroupNameInput = it },
                    label = { Text("Nombre del grupo (ej. Entrada, Fondo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newGroupNameInput.trim()
                        if (trimmed.isNotBlank()) {
                            selectedGroup = trimmed
                        }
                        newGroupNameInput = ""
                        showNewGroupDialog = false
                    }
                ) {
                    Text("Crear y Ver")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNewGroupDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showGroupAuthDialog) {
        AlertDialog(
            onDismissRequest = { showGroupAuthDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clave para '$selectedGroup'", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Aplica este usuario y contraseÃ±a a todas las ${displayedCameras.size} cÃ¡maras del grupo '$selectedGroup'.",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    OutlinedTextField(
                        value = groupAuthUser,
                        onValueChange = { groupAuthUser = it },
                        label = { Text("Usuario (admin)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groupAuthPass,
                        onValueChange = { groupAuthPass = it },
                        label = { Text("ContraseÃ±a / CÃ³d. verificaciÃ³n (EZVIZ)") },
                        singleLine = true,
                        visualTransformation = if (groupAuthPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { groupAuthPassVisible = !groupAuthPassVisible }) {
                                Icon(
                                    imageVector = if (groupAuthPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Probar clave rÃ¡pida:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("12345", "123456", "admin", "admin123").forEach { pwd ->
                            OutlinedButton(
                                onClick = { groupAuthPass = pwd },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(pwd, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGroupAuthDialog = false
                        displayedCameras.forEach { cam ->
                            onUpdateCamera?.invoke(
                                cam.copy(
                                    username = groupAuthUser.trim(),
                                    password = groupAuthPass
                                )
                            )
                        }
                        Toast.makeText(context, "Credenciales aplicadas a ${displayedCameras.size} cÃ¡mara(s)", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Aplicar a Todas")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGroupAuthDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (cameraToMoveGroup != null) {
        val targetCam = cameraToMoveGroup!!
        AlertDialog(
            onDismissRequest = { cameraToMoveGroup = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = Color(0xFF38BDF8))
                    Spacer(Modifier.width(8.dp))
                    Text("Mover a Grupo", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Mover \"${targetCam.name}\" al grupo:",
                        fontSize = 13.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    OutlinedTextField(
                        value = moveTargetGroup,
                        onValueChange = { moveTargetGroup = it },
                        label = { Text("Nombre del Grupo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Casa", "Entrada", "Patio", "Oficina", "Garage", "Fondo").forEach { grp ->
                            FilterChip(
                                selected = moveTargetGroup.equals(grp, ignoreCase = true),
                                onClick = { moveTargetGroup = grp },
                                label = { Text(grp, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val grp = moveTargetGroup.trim().ifBlank { "Casa" }
                        val updated = targetCam.copy(groupName = grp)
                        onUpdateCamera?.invoke(updated)
                        cameraToMoveGroup = null
                        Toast.makeText(context, "\"${targetCam.name}\" movida a '$grp'", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { cameraToMoveGroup = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "FrCam Monitor Pro",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${cameras.size} cÃ¡mara(s) â€¢ LAN: ${localPhoneIp ?: "Wi-Fi"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGallery) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "GalerÃ­a de fotos, videos y GIF",
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
                            contentDescription = "Cambiar CuadrÃ­cula (${gridMode.label})"
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "MÃ¡s opciones",
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
                            if (onSyncToWatch != null) {
                                DropdownMenuItem(
                                    text = { Text("Sincronizar con Reloj Wear OS") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Watch,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onSyncToWatch.invoke()
                                    }
                                )
                            }
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
                                text = { Text("Copia de Seguridad y RestauraciÃ³n") },
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
                text = { Text("Agregar CÃ¡mara", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding()
                )
        ) {
            if (cameras.isNotEmpty()) {
                // Centro de Mando Inteligente (Estado + Escenas 1-Toque + Modo Patrulla)
                val liveCount = cameras.count { it.liveInDashboard }
                val armedCount = cameras.count { it.motionDetectionEnabled }
                val schedCount = cameras.count { it.autoRecordMode == com.frcam.app.data.AutoRecordMode.SCHEDULE }
                val allArmed = armedCount == cameras.size && cameras.isNotEmpty()
                val groupAnyLive = displayedCameras.any { it.liveInDashboard }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .border(1.dp, FrColors.border, RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (liveCount > 0) FrColors.success else Color(0xFF64748B))
                                    )
                                    Text(
                                        text = "$liveCount en vivo",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = FrColors.textPrimary
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (armedCount > 0) FrColors.warning else Color(0xFF64748B),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "$armedCount vigilando",
                                        fontSize = 11.sp,
                                        color = if (armedCount > 0) FrColors.warning else FrColors.textSecondary
                                    )
                                }
                                if (schedCount > 0) {
                                    Text(
                                        text = "â€¢ $schedCount horario",
                                        fontSize = 11.sp,
                                        color = Color(0xFFF87171),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            if (cameras.size >= 3) {
                                IconButton(
                                    onClick = {
                                        showSearchBar = !showSearchBar
                                        if (!showSearchBar) searchQuery = ""
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showSearchBar) Icons.Default.Close else Icons.Default.Search,
                                        contentDescription = "Buscar cÃ¡mara",
                                        tint = FrColors.accent,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                FilledTonalButton(
                                    onClick = {
                                        val nextArm = !allArmed
                                        onArmAll(nextArm)
                                        Toast.makeText(
                                            context,
                                            if (nextArm) "Sistema armado: alertas activas en todas las cÃ¡maras"
                                            else "Sistema desarmado: alertas pausadas",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (allArmed) FrColors.warning.copy(alpha = 0.22f) else FrColors.cardHigh,
                                        contentColor = if (allArmed) FrColors.warning else FrColors.textPrimary
                                    )
                                ) {
                                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (allArmed) "Desarmar" else "Armar Todo", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            item {
                                FilledTonalButton(
                                    onClick = { onSetGroupLive(selectedGroup, !groupAnyLive) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (groupAnyLive) FrColors.accentDeep.copy(alpha = 0.28f) else FrColors.cardHigh,
                                        contentColor = if (groupAnyLive) FrColors.accent else FrColors.textPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (groupAnyLive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (groupAnyLive) "Pausar Todas" else "Ver Todas", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (displayedCameras.size >= 2) {
                                item {
                                    FilledTonalButton(
                                        onClick = { isPatrolModeActive = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = FrColors.purple.copy(alpha = 0.20f),
                                            contentColor = FrColors.purple
                                        )
                                    ) {
                                        Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Modo Patrulla", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            item {
                                FilledTonalButton(
                                    onClick = onOpenScanner,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = FrColors.success.copy(alpha = 0.18f),
                                        contentColor = FrColors.success
                                    )
                                ) {
                                    Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Auto-Detectar", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (showSearchBar) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Buscar por nombre, IP o marca...", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Limpiar", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(allGroups) { grp ->
                        val count = if (grp == "Todas") cameras.size else cameras.count { it.groupName.equals(grp, ignoreCase = true) }
                        if (count > 0 || grp == "Todas") {
                            FilterChip(
                                selected = selectedGroup.equals(grp, ignoreCase = true),
                                onClick = { selectedGroup = grp },
                                label = {
                                    Text(
                                        text = "$grp ($count)",
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedGroup.equals(grp, ignoreCase = true)) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { showNewGroupDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Nuevo Grupo", fontSize = 11.sp)
                        }
                    }
                    if (selectedGroup != "Todas" && displayedCameras.isNotEmpty()) {
                        item {
                            OutlinedButton(
                                onClick = {
                                    groupAuthUser = "admin"
                                    groupAuthPass = ""
                                    showGroupAuthDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Clave a Grupo", fontSize = 11.sp, color = Color(0xFFF59E0B))
                            }
                        }
                    }
                }
            }

            if (cameras.isEmpty()) {
                EmptyCameraOnboarding(
                    onOpenScanner = onOpenScanner,
                    onOpenRingLogin = onOpenRingLogin,
                    onQuickAddBrand = { brand -> onAddCamera(brand) },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (displayedCameras.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No hay cÃ¡maras en el grupo '$selectedGroup'",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp
                        )
                        OutlinedButton(onClick = { selectedGroup = "Todas" }) {
                            Text("Ver Todas")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridMode.columns),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 4.dp,
                        bottom = 88.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayedCameras, key = { it.id }) { camera ->
                        CameraGridTile(
                            camera = camera,
                            isCompact = gridMode.columns >= 2,
                            onToggleLiveDashboard = { active -> onToggleLiveDashboard(camera, active) },
                            onToggleMotion = { enabled -> onToggleMotion(camera, enabled) },
                            onOpenDetail = { onSelectCamera(camera) },
                            onOpenFullscreen = { onOpenFullscreen(camera) },
                            onEdit = { onEditCamera(camera) },
                            onDelete = { cameraToDelete = camera },
                            onMoveGroup = { cam ->
                                moveTargetGroup = cam.groupName
                                cameraToMoveGroup = cam
                            },
                            onUpdateCamera = onUpdateCamera
                        )
                    }
                }
            }
        }
    }

    if (isPatrolModeActive && displayedCameras.isNotEmpty()) {
        PatrolTourOverlay(
            cameras = displayedCameras,
            onOpenDetail = { cam ->
                isPatrolModeActive = false
                onSelectCamera(cam)
            },
            onClose = { isPatrolModeActive = false },
            onUpdateCamera = onUpdateCamera
        )
    }
}

@Composable
private fun PatrolTourOverlay(
    cameras: List<CameraConfig>,
    onOpenDetail: (CameraConfig) -> Unit,
    onClose: () -> Unit,
    onUpdateCamera: ((CameraConfig) -> Unit)? = null
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }
    var intervalSec by remember { mutableIntStateOf(15) }
    var elapsedSec by remember { mutableIntStateOf(0) }

    val safeIndex = currentIndex.coerceIn(0, (cameras.size - 1).coerceAtLeast(0))
    val activeCam = cameras[safeIndex]

    LaunchedEffect(safeIndex, isPaused, intervalSec, cameras.size) {
        elapsedSec = 0
        if (isPaused || cameras.size <= 1) return@LaunchedEffect
        while (elapsedSec < intervalSec) {
            delay(1000L)
            elapsedSec++
        }
        currentIndex = (safeIndex + 1) % cameras.size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        UniversalCameraPlayer(
            camera = activeCam,
            useSubStream = false,
            isMuted = true,
            enablePinchZoom = true,
            onUpdateCamera = onUpdateCamera,
            modifier = Modifier.fillMaxSize()
        )

        // Barra superior con progreso de rotaciÃ³n
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .background(FrColors.purple.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PATRULLA ${safeIndex + 1}/${cameras.size}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = FrColors.purple
                            )
                        }
                        Text(
                            text = activeCam.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${activeCam.groupName} â€¢ ${activeCam.displayAddressLabel()}",
                        color = FrColors.textSecondary,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Salir de patrulla", tint = Color.White)
                }
            }
            LinearProgressIndicator(
                progress = { (elapsedSec.toFloat() / intervalSec.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = FrColors.accent,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }

        // Controles inferiores
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 15, 30).forEach { sec ->
                    FilterChip(
                        selected = intervalSec == sec,
                        onClick = { intervalSec = sec },
                        label = { Text("${sec}s", fontSize = 11.sp) }
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        currentIndex = if (safeIndex == 0) cameras.lastIndex else safeIndex - 1
                    }
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior", tint = Color.White)
                }
                IconButton(
                    onClick = { isPaused = !isPaused },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(FrColors.accentDeep)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Reanudar" else "Pausar",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { currentIndex = (safeIndex + 1) % cameras.size }
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Siguiente", tint = Color.White)
                }
                IconButton(onClick = { onOpenDetail(activeCam) }) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Abrir control", tint = FrColors.accent)
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
    onOpenFullscreen: () -> Unit = onOpenDetail,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveGroup: (CameraConfig) -> Unit = {},
    onUpdateCamera: ((CameraConfig) -> Unit)? = null
) {
    var isMuted by remember(camera.id) { mutableStateOf(true) }
    val useSubStreamInGrid = isCompact && camera.preferSubStreamInGrid

    val tileBorderColor = when {
        camera.liveInDashboard -> FrColors.borderAccent
        camera.motionDetectionEnabled -> FrColors.warning.copy(alpha = 0.45f)
        else -> FrColors.border
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, tileBorderColor, RoundedCornerShape(16.dp))
            .clickable { onOpenDetail() },
        colors = CardDefaults.cardColors(containerColor = FrColors.card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Color.Black)
            ) {
                // La vista previa en vivo solo decodifica video cuando la cÃ¡mara estÃ¡ visible en pantalla;
                // si hay vigilancia activa (alertas o grabaciÃ³n horaria) el servicio en segundo plano se encarga.
                val monitoring = camera.motionDetectionEnabled || camera.autoRecordMode != "OFF"
                if (camera.liveInDashboard) {
                    UniversalCameraPlayer(
                        camera = camera,
                        useSubStream = useSubStreamInGrid,
                        isMuted = isMuted,
                        enablePinchZoom = !isCompact,
                        onUpdateCamera = onUpdateCamera,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (!camera.liveInDashboard) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(FrColors.surfaceAlt, FrColors.deepest)
                                )
                            )
                            .clickable { onToggleLiveDashboard(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(FrColors.accent.copy(alpha = 0.14f))
                                    .border(1.dp, FrColors.accent.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Activar vista previa",
                                    tint = FrColors.accent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = if (camera.motionDetectionEnabled) "Vigilancia Activa" else "Vista previa pausada",
                                color = FrColors.textPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (monitoring) "Protegiendo en 2Â° plano â€¢ toca para ver" else "Toca para ver en vivo",
                                color = FrColors.textSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Group badge + Live/Schedule badge Top-Left
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (camera.liveInDashboard) {
                        Row(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(FrColors.success)
                            )
                            Text(
                                text = "VIVO",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E293B).copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = camera.groupName.ifBlank { "Casa" },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF1F5F9)
                        )
                    }
                    if (camera.autoRecordMode == com.frcam.app.data.AutoRecordMode.SCHEDULE) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFDC2626).copy(alpha = 0.90f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "REC HORARIO",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
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
                        onClick = { onMoveGroup(camera) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFileMove,
                            contentDescription = "Mover a grupo",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(15.dp)
                        )
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
                        onClick = onDelete,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Eliminar cÃ¡mara",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = onOpenFullscreen,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Ver en Pantalla Completa",
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
            text = "Centro Universal de CÃ¡maras IP",
            fontSize = 21.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Conecta tus cÃ¡maras V380 PRO (Remotas por ID o Local), Eye4 (VStarcam), V720, ONVIF y Ring sin publicidad.",
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
                text = "Escanear mi Red Wi-Fi AutomÃ¡ticamente",
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
