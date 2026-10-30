package com.frcam.wear

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.frcam.app.model.CameraConfig
import kotlinx.coroutines.delay

/**
 * Vista individual a PANTALLA COMPLETA TOTAL para Wear OS.
 * Ocupa el 100% de la pantalla del reloj con:
 * - Zoom interactivo por gestos (Pinch-to-zoom de 1x a 4.5x)
 * - Doble toque para alternar zoom (1x <-> 2.5x)
 * - Arrastre / Pan táctil cuando la imagen está ampliada
 * - Controles táctiles de zoom (+ / - / Reset)
 * - Activación / Silenciado de sonido en vivo (Audio listening)
 * - Activación de micrófono del reloj para hablar por el parlante de la cámara (Intercomunicador)
 * - Navegación entre cámaras y botón de regreso a la vista cuadrícula
 */
@Composable
fun WearSingleCameraFullscreen(
    cameras: List<CameraConfig>,
    initialIndex: Int,
    onBackToGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (cameras.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, cameras.size - 1)) }
    val currentCamera = cameras[currentIndex]

    var scale by remember(currentIndex) { mutableFloatStateOf(1f) }
    var offset by remember(currentIndex) { mutableStateOf(Offset.Zero) }

    var isMuted by remember(currentIndex) { mutableStateOf(true) }

    // Motor de audio bidireccional (Micrófono)
    val broadcaster = remember { WearIntercomBroadcaster(context) }
    val isBroadcasting by broadcaster.isBroadcasting.collectAsState()

    DisposableEffect(currentCamera.id) {
        onDispose {
            broadcaster.stopBroadcasting()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            broadcaster.startBroadcasting(currentCamera, scope) { err ->
                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Se requiere permiso de Micrófono para hablar.", Toast.LENGTH_SHORT).show()
        }
    }

    var showOverlayControls by remember { mutableStateOf(true) }
    var cameraPillVisible by remember(currentIndex) { mutableStateOf(true) }

    // Si el micrófono está transmitiendo, mantener los controles visibles
    LaunchedEffect(showOverlayControls, currentIndex, isBroadcasting) {
        if (showOverlayControls && !isBroadcasting) {
            delay(4000L)
            showOverlayControls = false
        }
    }

    LaunchedEffect(currentIndex) {
        cameraPillVisible = true
        delay(2200L)
        cameraPillVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
    ) {
        // Capa de video con soporte de ZOOM y PAN por gestos
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentIndex) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 4.5f)
                        scale = newScale
                        if (newScale > 1f) {
                            val maxX = (size.width * (newScale - 1f)) / 2f
                            val maxY = (size.height * (newScale - 1f)) / 2f
                            offset = Offset(
                                x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
                .pointerInput(currentIndex) {
                    detectTapGestures(
                        onTap = {
                            showOverlayControls = !showOverlayControls
                        },
                        onDoubleTap = {
                            if (scale > 1.2f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            WearCameraStreamView(
                camera = currentCamera,
                isMuted = isMuted,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Chip flotante rápido con nombre de cámara e índice cuando los controles están ocultos
        AnimatedVisibility(
            visible = cameraPillVisible && !showOverlayControls && !isBroadcasting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.70f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentIndex + 1}/${cameras.size} • ${currentCamera.name}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Banner indicador de micrófono transmitiendo en vivo
        if (isBroadcasting) {
            Surface(
                color = Color(0xFFDC2626).copy(alpha = 0.90f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clickable { broadcaster.stopBroadcasting() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(
                        text = "Hablando por cámara • Toca para parar",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }
        }

        // Controles superpuestos (Barra superior unificada y centrada, Navegación y Barra de Zoom/Mic)
        AnimatedVisibility(
            visible = showOverlayControls || isBroadcasting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Barra superior unificada y centrada (protegida del bisel circular de Wear OS)
                Surface(
                    color = Color.Black.copy(alpha = 0.80f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (isBroadcasting) 38.dp else 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        // Botón volver a vista de cuadrícula
                        IconButton(
                            onClick = {
                                broadcaster.stopBroadcasting()
                                onBackToGrid()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridView,
                                contentDescription = "Ver todas las cámaras",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Título y número de cámara en el centro
                        Text(
                            text = "${currentIndex + 1}/${cameras.size} • ${currentCamera.name}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 105.dp)
                                .padding(horizontal = 2.dp)
                        )

                        // Botón de Sonido (Mute / Unmute)
                        IconButton(
                            onClick = {
                                isMuted = !isMuted
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isMuted) "Activar sonido" else "Silenciar",
                                tint = if (isMuted) Color(0xFF94A3B8) else Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Flechas de cambio de cámara (anterior y siguiente)
                if (cameras.size > 1) {
                    IconButton(
                        onClick = {
                            broadcaster.stopBroadcasting()
                            currentIndex = if (currentIndex > 0) currentIndex - 1 else cameras.size - 1
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 2.dp)
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Cámara anterior",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            broadcaster.stopBroadcasting()
                            currentIndex = (currentIndex + 1) % cameras.size
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp)
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Cámara siguiente",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Barra inferior combinada: Zoom + Botón de Micrófono
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.80f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón Alejar Zoom
                    IconButton(
                        onClick = {
                            val newScale = (scale - 0.5f).coerceAtLeast(1f)
                            scale = newScale
                            if (newScale <= 1f) offset = Offset.Zero
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Alejar zoom",
                            tint = if (scale > 1f) Color(0xFF38BDF8) else Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Indicador de nivel de Zoom actual
                    Text(
                        text = "${"%.1f".format(scale)}x",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .clickable {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            .padding(horizontal = 4.dp)
                    )

                    // Botón Acercar Zoom
                    IconButton(
                        onClick = {
                            scale = (scale + 0.5f).coerceAtMost(4.5f)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Acercar zoom",
                            tint = if (scale < 4.5f) Color(0xFF38BDF8) else Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(18.dp)
                            .width(1.dp)
                            .background(Color(0xFF334155))
                    )

                    // Botón de Micrófono / Intercomunicador
                    IconButton(
                        onClick = {
                            if (isBroadcasting) {
                                broadcaster.stopBroadcasting()
                            } else {
                                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    broadcaster.startBroadcasting(currentCamera, scope) { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (isBroadcasting) Color(0xFFDC2626) else Color.Transparent,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isBroadcasting) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (isBroadcasting) "Detener micrófono" else "Hablar por cámara",
                            tint = if (isBroadcasting) Color.White else Color(0xFFF59E0B),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
