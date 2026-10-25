package com.frcam.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.frcam.app.media.CameraIntercomBroadcaster
import com.frcam.app.media.CameraMediaEngine
import com.frcam.app.media.LiveMp4VideoRecorder
import com.frcam.app.model.CameraAccessRecord
import com.frcam.app.model.CameraBrand
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.CameraDeviceInfo
import com.frcam.app.model.PtzControlType
import com.frcam.app.model.WifiNetworkScanResult
import com.frcam.app.protocol.PtzDirection
import com.frcam.app.protocol.V380AuxCommand
import com.frcam.app.ui.player.CameraFrameCaptureHandle
import com.frcam.app.ui.player.UniversalCameraPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SingleCameraDetailScreen(
    camera: CameraConfig,
    isInPipMode: Boolean = false,
    ptzFeedback: String?,
    deviceInfo: CameraDeviceInfo?,
    accessLogs: List<CameraAccessRecord>,
    wifiScanList: List<WifiNetworkScanResult>,
    isScanningWifi: Boolean,
    adminStatusMessage: String?,
    onBack: () -> Unit,
    onEdit: (CameraConfig) -> Unit,
    onDelete: (String) -> Unit,
    onToggleMotionAlert: (Boolean) -> Unit,
    onPtzCommand: (PtzDirection) -> Unit,
    onV380AuxCommand: (V380AuxCommand) -> Unit,
    onGotoPreset: (Int) -> Unit,
    onEnterPipMode: () -> Unit,
    onOpenAdmin: () -> Unit,
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val frameCaptureHandle = remember(camera.id) { CameraFrameCaptureHandle() }
    val videoRecorder = remember(camera.id) { LiveMp4VideoRecorder(context, camera.name) }
    val intercomBroadcaster = remember(camera.id) { CameraIntercomBroadcaster(context) }
    val isBroadcastingVoice by intercomBroadcaster.isBroadcasting.collectAsState()
    val voiceAmplitude by intercomBroadcaster.voiceAmplitude.collectAsState()

    var useSubStream by remember(camera.id) { mutableStateOf(false) }
    var isMuted by remember(camera.id) { mutableStateOf(camera.audioMutedByDefault) }
    var detectedResolution by remember(camera.id) { mutableStateOf("HD") }
    var isRecordingVideo by remember(camera.id) { mutableStateOf(false) }
    var recordingSeconds by remember(camera.id) { mutableIntStateOf(0) }
    var mediaFeedback by remember(camera.id) { mutableStateOf<String?>(null) }
    var showAdminSheet by remember(camera.id) { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            intercomBroadcaster.startBroadcasting(
                camera = camera,
                scope = coroutineScope,
                onError = { err ->
                    mediaFeedback = err
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            Toast.makeText(context, "Se requiere permiso de micrófono para transmitir voz a la cámara", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(camera.id) {
        onDispose {
            if (videoRecorder.isRecording) {
                coroutineScope.launch {
                    videoRecorder.stopAndSaveToGallery()
                }
            }
            intercomBroadcaster.stopBroadcasting()
        }
    }

    var showOnScreenCruceta by remember(camera.id) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (!isInPipMode) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = camera.name,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${camera.brand.displayName} • ${camera.displayAddressLabel()}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                        }
                    },
                    actions = {
                        IconButton(onClick = onEnterPipMode) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Ventana flotante (PiP)",
                                tint = Color(0xFF38BDF8)
                            )
                        }
                        IconButton(
                            onClick = {
                                onOpenAdmin()
                                showAdminSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Administrar cámara (Wi-Fi, IP, Password, SD)",
                                tint = Color(0xFF10B981)
                            )
                        }
                        IconButton(onClick = { onEdit(camera) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Editar cámara")
                        }
                        IconButton(onClick = { onDelete(camera.id) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Eliminar cámara",
                                tint = Color(0xFFF87171)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!isInPipMode) {
                        Modifier
                            .padding(paddingValues)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                )
        ) {
            // Live Video Player Surface (Full screen in PiP mode, 16:9 when regular)
            Box(
                modifier = if (isInPipMode) {
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                }
            ) {
                UniversalCameraPlayer(
                    camera = camera,
                    useSubStream = useSubStream,
                    isMuted = isMuted,
                    enablePinchZoom = !isInPipMode,
                    frameCaptureHandle = frameCaptureHandle,
                    onResolutionChanged = { res -> detectedResolution = res },
                    modifier = Modifier.fillMaxSize()
                )

                if (!isInPipMode) {
                    // Recording Indicator Overlay
                    if (isRecordingVideo) {
                        val mins = recordingSeconds / 60
                        val secs = recordingSeconds % 60
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .background(Color(0xFFDC2626).copy(alpha = 0.90f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "GRABANDO %02d:%02d".format(mins, secs),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // Floating On-Screen Controls: Escuchar, Silenciar, Micrófono y Cruceta sobre la cámara
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Botón de audio (Parlante)
                        FilledIconButton(
                            onClick = { isMuted = !isMuted },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (!isMuted) Color(0xFF059669) else Color(0xFF334155),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isMuted) "Activar Audio" else "Silenciar Audio",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Botón de micrófono (Hablar)
                        FilledIconButton(
                            onClick = {
                                if (isBroadcastingVoice) {
                                    intercomBroadcaster.stopBroadcasting()
                                } else {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        intercomBroadcaster.startBroadcasting(
                                            camera = camera,
                                            scope = coroutineScope,
                                            onError = { err ->
                                                mediaFeedback = err
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isBroadcastingVoice) Color(0xFFDC2626) else Color(0xFF0284C7),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isBroadcastingVoice) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (isBroadcastingVoice) "Cortar Micrófono" else "Hablar por Cámara",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Botón de Cruceta flotante sobre video
                        if (camera.isPtzCapable()) {
                            FilledIconButton(
                                onClick = { showOnScreenCruceta = !showOnScreenCruceta },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (showOnScreenCruceta) Color(0xFF0284C7) else Color(0xFF334155),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenWith,
                                    contentDescription = if (showOnScreenCruceta) "Ocultar Cruceta" else "Cruceta en Pantalla",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Floating On-Screen D-Pad (Cruceta interactiva sobre el video)
                    if (showOnScreenCruceta && camera.isPtzCapable()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 12.dp)
                                .size(140.dp)
                                .background(Color(0xA0000000), CircleShape)
                                .border(1.5.dp, Color(0x9038BDF8), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // Arriba
                            FilledTonalIconButton(
                                onClick = { onPtzCommand(PtzDirection.UP) },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp)
                                    .size(38.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Arriba", tint = Color.White)
                            }
                            // Abajo
                            FilledTonalIconButton(
                                onClick = { onPtzCommand(PtzDirection.DOWN) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 4.dp)
                                    .size(38.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Abajo", tint = Color.White)
                            }
                            // Izquierda
                            FilledTonalIconButton(
                                onClick = { onPtzCommand(PtzDirection.LEFT) },
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 4.dp)
                                    .size(38.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Izquierda", tint = Color.White)
                            }
                            // Derecha
                            FilledTonalIconButton(
                                onClick = { onPtzCommand(PtzDirection.RIGHT) },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp)
                                    .size(38.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Derecha", tint = Color.White)
                            }
                            // Centro
                            FilledIconButton(
                                onClick = { onPtzCommand(PtzDirection.HOME) },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF0284C7)),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CenterFocusStrong,
                                    contentDescription = "Centrar",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Banner de voz en curso sobre la cámara
                    if (isBroadcastingVoice) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 52.dp)
                                .background(Color(0xD0DC2626), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "HABLANDO AL PARLANTE...",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Stream Quick Toolbar + Recording & Motion Actions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111827))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = { useSubStream = !useSubStream },
                            label = {
                                Text(
                                    text = if (useSubStream) "SD (Sub)" else "HD (Main)",
                                    fontSize = 11.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (useSubStream) Icons.Default.Sd else Icons.Default.HighQuality,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )

                        AssistChip(
                            onClick = { isMuted = !isMuted },
                            label = {
                                Text(
                                    text = if (isMuted) "Mute" else "Audio",
                                    fontSize = 11.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )
                    }

                    Text(
                        text = detectedResolution,
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Record Video MP4, Capture Snapshot, and Motion Alert Toggle Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Record MP4 Button
                    Button(
                        onClick = {
                            if (!isRecordingVideo) {
                                val started = videoRecorder.startRecording(
                                    scope = coroutineScope,
                                    frameProvider = { frameCaptureHandle.captureCurrentBitmap(1280, 720) },
                                    onElapsedSeconds = { sec -> recordingSeconds = sec }
                                )
                                if (started) {
                                    isRecordingVideo = true
                                    recordingSeconds = 0
                                    mediaFeedback = "Grabando video en vivo (.mp4)..."
                                } else {
                                    mediaFeedback = "Error al iniciar el grabador MP4"
                                }
                            } else {
                                isRecordingVideo = false
                                mediaFeedback = "Procesando y guardando MP4 en Galería..."
                                coroutineScope.launch {
                                    val res = videoRecorder.stopAndSaveToGallery()
                                    val msg = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Error al guardar MP4" })
                                    mediaFeedback = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecordingVideo) Color(0xFFDC2626) else Color(0xFF0284C7)
                        ),
                        modifier = Modifier.weight(1.1f)
                    ) {
                        Icon(
                            imageVector = if (isRecordingVideo) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRecordingVideo) "Detener REC" else "Grabar Video",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // 2. Capture Snapshot Photo Button
                    FilledTonalButton(
                        onClick = {
                            val bmp = frameCaptureHandle.captureCurrentBitmap()
                            if (bmp == null) {
                                Toast.makeText(context, "Esperando imagen en vivo...", Toast.LENGTH_SHORT).show()
                            } else {
                                coroutineScope.launch {
                                    val res = CameraMediaEngine.saveSnapshotToGallery(context, camera.name, bmp)
                                    val msg = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Error" })
                                    mediaFeedback = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Foto", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Motion Detection + Test Ring Alert Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = camera.motionDetectionEnabled,
                        onClick = { onToggleMotionAlert(!camera.motionDetectionEnabled) },
                        label = {
                            Text(
                                text = if (camera.motionDetectionEnabled)
                                    "Alerta Movimiento Ring: ON"
                                else "Alerta Movimiento Ring: OFF",
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (camera.motionDetectionEnabled)
                                    Icons.Default.NotificationsActive
                                else Icons.Default.NotificationsOff,
                                contentDescription = null,
                                tint = if (camera.motionDetectionEnabled) Color(0xFF34D399) else Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1.3f)
                    )

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val burst = mutableListOf<Bitmap>()
                                for (i in 0 until 4) {
                                    frameCaptureHandle.captureCurrentBitmap(640, 360)?.let { burst.add(it) }
                                    delay(200L)
                                }
                                val lead = burst.firstOrNull() ?: frameCaptureHandle.captureCurrentBitmap(640, 360)
                                if (lead != null) {
                                    CameraMediaEngine.notifyMotionDetected(
                                        context = context,
                                        cameraId = camera.id,
                                        cameraName = camera.name,
                                        motionPercent = 14.8f,
                                        rawBitmap = lead,
                                        burstFrames = burst
                                    )
                                    mediaFeedback = "Notificación con GIF animado enviada a la barra de estado"
                                } else {
                                    Toast.makeText(context, "Espera a que cargue el video en vivo para capturar la alerta", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Probar Alerta", fontSize = 11.sp)
                    }
                }

                if (!mediaFeedback.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = mediaFeedback!!,
                        fontSize = 11.sp,
                        color = Color(0xFF34D399),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // PTZ Control Console (Cruceta Motorizada Arriba / Abajo / Izquierda / Derecha)
            if (camera.isPtzCapable()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141D2F)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Control Motorizado PTZ (Cruceta)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = camera.ptzType.label,
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            if (!ptzFeedback.isNullOrBlank()) {
                                Text(
                                    text = ptzFeedback,
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // D-Pad Circular Console
                            Box(
                                modifier = Modifier
                                    .size(176.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0B111E))
                                    .border(1.5.dp, Color(0xFF1E293B), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                FilledTonalIconButton(
                                    onClick = { onPtzCommand(PtzDirection.UP) },
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 8.dp)
                                        .size(46.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Arriba")
                                }

                                FilledTonalIconButton(
                                    onClick = { onPtzCommand(PtzDirection.DOWN) },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                        .size(46.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Abajo")
                                }

                                FilledTonalIconButton(
                                    onClick = { onPtzCommand(PtzDirection.LEFT) },
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = 8.dp)
                                        .size(46.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Izquierda")
                                }

                                FilledTonalIconButton(
                                    onClick = { onPtzCommand(PtzDirection.RIGHT) },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 8.dp)
                                        .size(46.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Derecha")
                                }

                                FilledIconButton(
                                    onClick = { onPtzCommand(PtzDirection.HOME) },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Color(0xFF0284C7)
                                    ),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CenterFocusStrong,
                                        contentDescription = "Posición Central",
                                        tint = Color.White
                                    )
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { onPtzCommand(PtzDirection.ZOOM_IN) },
                                    modifier = Modifier.width(124.dp)
                                ) {
                                    Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Zoom +", fontSize = 12.sp)
                                }

                                FilledTonalButton(
                                    onClick = { onPtzCommand(PtzDirection.ZOOM_OUT) },
                                    modifier = Modifier.width(124.dp)
                                ) {
                                    Icon(Icons.Default.ZoomOut, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Zoom -", fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = { onPtzCommand(PtzDirection.STOP) },
                                    modifier = Modifier.width(124.dp)
                                ) {
                                    Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Detener", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (camera.brand == CameraBrand.V380_PRO || camera.isV380NativeProtocol()) {
                            Text(
                                text = "Controles Especiales V380 (Luz LED, Infrarrojo e Imagen):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF7DD3FC),
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    V380AuxCommand.LIGHT_ON,
                                    V380AuxCommand.LIGHT_OFF,
                                    V380AuxCommand.LIGHT_AUTO,
                                    V380AuxCommand.IMAGE_COLOR,
                                    V380AuxCommand.IMAGE_BW,
                                    V380AuxCommand.IMAGE_FLIP
                               ).forEach { aux ->
                                    AssistChip(
                                        onClick = { onV380AuxCommand(aux) },
                                        label = { Text(aux.label, fontSize = 11.sp) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (aux == V380AuxCommand.IMAGE_FLIP)
                                                    Icons.Default.FlipCameraAndroid
                                                else Icons.Default.LightMode,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                (1..4).forEach { preset ->
                                    OutlinedButton(
                                        onClick = { onGotoPreset(preset) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BookmarkBorder,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("P$preset", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Two-Way Audio Console (Escuchar sonido ambiente y Hablar por la cámara)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBroadcastingVoice) Color(0xFF1E1428) else Color(0xFF131D2D)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = if (isBroadcastingVoice) Color(0xFFF43F5E) else Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Audio Bidireccional (Intercomunicador)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Text(
                            text = if (isBroadcastingVoice) "HABLANDO EN VIVO" else if (!isMuted) "ESCUCHANDO CÁMARA" else "AUDIO SILENCIADO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isBroadcastingVoice) Color(0xFFF43F5E) else if (!isMuted) Color(0xFF34D399) else Color(0xFF94A3B8)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Escuchar Audio de la Cámara (Altavoz del Celular)
                        FilledTonalButton(
                            onClick = { isMuted = !isMuted },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (!isMuted) Color(0xFF065F46) else Color(0xFF1E293B),
                                contentColor = if (!isMuted) Color(0xFF6EE7B7) else Color(0xFFCBD5E1)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isMuted) "Escuchar Cámara" else "Silenciar Audio",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // 2. Hablar por la Cámara (Micrófono del Celular -> Altavoz de Cámara)
                        Button(
                            onClick = {
                                if (isBroadcastingVoice) {
                                    intercomBroadcaster.stopBroadcasting()
                                } else {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        intercomBroadcaster.startBroadcasting(
                                            camera = camera,
                                            scope = coroutineScope,
                                            onError = { err ->
                                                mediaFeedback = err
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBroadcastingVoice) Color(0xFFDC2626) else Color(0xFF0284C7)
                            ),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Icon(
                                imageVector = if (isBroadcastingVoice) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBroadcastingVoice) "Detener Voz" else "Hablar a Cámara",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // Live Speech Visualizer and Status Banner when speaking
                    AnimatedVisibility(visible = isBroadcastingVoice) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF881337).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Transmitiendo tu voz al altavoz de la cámara...",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFDA4AF),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${(voiceAmplitude * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    color = Color(0xFFF43F5E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { voiceAmplitude.coerceIn(0.05f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFFF43F5E),
                                trackColor = Color(0xFF4C0519)
                            )
                        }
                    }
                }
            }



            // Technical Connection Details Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Detalles Técnicos del Flujo",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color(0xFF38BDF8)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val maskedUrl = camera.buildStreamUrl(useSubStream)
                        .replace(Regex("://([^:]+):([^@]+)@"), "://$1:***@")
                    Text(
                        text = "Conexión: ${camera.connectionMode.label}",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    if (camera.deviceId.isNotBlank()) {
                        Text(
                            text = "ID de Dispositivo: ${camera.deviceId} ${if (camera.modelCode.isNotBlank()) "(${camera.modelCode})" else ""}",
                            fontSize = 12.sp,
                            color = Color(0xFF34D399)
                        )
                    }
                    Text(
                        text = "Endpoint: $maskedUrl",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = camera.brand.setupTip,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showAdminSheet) {
        CameraAdminBottomSheet(
            camera = camera,
            deviceInfo = deviceInfo,
            accessLogs = accessLogs,
            wifiScanList = wifiScanList,
            isScanningWifi = isScanningWifi,
            statusMessage = adminStatusMessage,
            onDismiss = { showAdminSheet = false },
            onSyncTime = onSyncTime,
            onUpdatePassword = onUpdatePassword,
            onUpdateWifi = onUpdateWifi,
            onScanWifi = onScanWifi,
            onUpdateIp = onUpdateIp,
            onToggleVoicePrompts = onToggleVoicePrompts,
            onToggleMotionTracking = onToggleMotionTracking,
            onTogglePtzPatrol = onTogglePtzPatrol,
            onFormatSdCard = onFormatSdCard,
            onReboot = onReboot,
            onFactoryReset = onFactoryReset
        )
    }
}
