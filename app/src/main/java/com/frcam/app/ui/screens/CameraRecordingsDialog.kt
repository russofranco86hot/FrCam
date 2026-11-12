package com.frcam.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.frcam.app.backup.TelegramBackupEngine
import com.frcam.app.data.AppSettings
import com.frcam.app.model.CameraConfig
import com.frcam.app.model.CameraRecordingEvent
import com.frcam.app.protocol.V380NativeProtocol
import com.frcam.app.ui.theme.FrColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Diálogo interactivo para consultar, reproducir y respaldar en Telegram
 * las grabaciones de eventos de movimiento de la cámara (MicroSD y almacenamiento del teléfono).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraRecordingsDialog(
    camera: CameraConfig,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var allEvents by remember { mutableStateOf<List<CameraRecordingEvent>>(emptyList()) }
    var selectedFilterOnlyMotion by remember { mutableStateOf(true) }
    var sendingTelegramId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun loadRecordings() {
        isLoading = true
        statusMessage = "Buscando grabaciones en la cámara y almacenamiento..."
        coroutineScope.launch {
            try {
                val nowSec = System.currentTimeMillis() / 1000L
                val startTime = nowSec - 86400L * 3 // Últimos 3 días
                val items = withContext(Dispatchers.IO) {
                    if (camera.isV380NativeProtocol()) {
                        V380NativeProtocol.searchRecordedFiles(
                            camera = camera,
                            startTimeSec = startTime,
                            endTimeSec = nowSec + 60L,
                            alarmOnly = false
                        )
                    } else {
                        // Para otras marcas, buscar clips guardados localmente
                        V380NativeProtocol.searchRecordedFiles(
                            camera = camera,
                            startTimeSec = startTime,
                            endTimeSec = nowSec + 60L,
                            alarmOnly = false
                        )
                    }
                }
                allEvents = items
                statusMessage = if (items.isEmpty()) "Sin grabaciones registradas en este período" else null
            } catch (e: Exception) {
                statusMessage = "Error consultando grabaciones: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(camera.id) {
        loadRecordings()
    }

    val displayList = remember(allEvents, selectedFilterOnlyMotion) {
        if (selectedFilterOnlyMotion) {
            allEvents.filter { it.isMotionAlarm }
        } else {
            allEvents
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B)
                )
                Column {
                    Text(
                        text = "Grabaciones y Eventos",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "${camera.name} • ${if (camera.isV380NativeProtocol()) "MicroSD / V380" else camera.brand.displayName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(490.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Barra de filtros rápidos y recarga
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedFilterOnlyMotion,
                            onClick = { selectedFilterOnlyMotion = true },
                            label = { Text("🚨 Movimiento", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = !selectedFilterOnlyMotion,
                            onClick = { selectedFilterOnlyMotion = false },
                            label = { Text("📹 Todos", fontSize = 11.sp) }
                        )
                    }

                    IconButton(
                        onClick = { loadRecordings() },
                        enabled = !isLoading,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                            Text(
                                text = statusMessage ?: "Cargando grabaciones...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (displayList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = Color(0xFF64748B)
                            )
                            Text(
                                text = "No se encontraron grabaciones",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (selectedFilterOnlyMotion) {
                                    "No hay eventos de movimiento detectados recientemente. Comprueba que la detección de movimiento esté activada."
                                } else {
                                    "No hay archivos en la tarjeta MicroSD o almacenamiento local."
                                },
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayList, key = { it.id }) { item ->
                            RecordingEventCard(
                                event = item,
                                isSendingTelegram = sendingTelegramId == item.id,
                                onPlay = {
                                    playRecording(context, item)
                                },
                                onSendTelegram = {
                                    if (!AppSettings.telegramEnabled || AppSettings.telegramBotToken.isBlank() || AppSettings.telegramChatId.isBlank()) {
                                        Toast.makeText(context, "Configura primero tu Bot y Chat ID en Ajustes › Telegram", Toast.LENGTH_LONG).show()
                                        return@RecordingEventCard
                                    }
                                    sendingTelegramId = item.id
                                    coroutineScope.launch {
                                        sendEventToTelegram(context, camera, item)
                                        sendingTelegramId = null
                                    }
                                }
                            )
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

@Composable
private fun RecordingEventCard(
    event: CameraRecordingEvent,
    isSendingTelegram: Boolean,
    onPlay: () -> Unit,
    onSendTelegram: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FrColors.card),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (event.isMotionAlarm) Color(0xFFDC2626).copy(alpha = 0.2f) else Color(0xFF0284C7).copy(alpha = 0.2f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (event.isMotionAlarm) "🚨 MOVIMIENTO" else "📹 CONTINUO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (event.isMotionAlarm) Color(0xFFF87171) else Color(0xFF38BDF8)
                        )
                    }

                    Text(
                        text = "${event.formattedDate}  ${event.formattedTime}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                Text(
                    text = "${event.durationSeconds}s",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            Text(
                text = event.fileName,
                fontSize = 10.sp,
                color = Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // Botón Reproducir / Ver
                OutlinedButton(
                    onClick = onPlay,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reproducir", fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Botón Enviar a Telegram
                FilledTonalButton(
                    onClick = onSendTelegram,
                    enabled = !isSendingTelegram,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF0284C7).copy(alpha = 0.3f),
                        contentColor = Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.height(30.dp)
                ) {
                    if (isSendingTelegram) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(13.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("A Telegram", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Abre el reproductor del sistema o visor de medios para el clip seleccionado.
 */
private fun playRecording(context: Context, event: CameraRecordingEvent) {
    val path = event.localFilePath
    if (path != null) {
        val file = File(path)
        if (file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val mime = if (file.name.endsWith(".gif", ignoreCase = true)) "image/gif" else "video/mp4"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Reproducir grabación"))
                return
            } catch (e: Exception) {
                Toast.makeText(context, "Error abriendo reproductor: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Si es un archivo remoto en la MicroSD de la cámara V380
    Toast.makeText(context, "Grabación en MicroSD (${event.fileName}) • ${event.durationSeconds}s", Toast.LENGTH_SHORT).show()
}

/**
 * Envía el evento o grabación directamente a Telegram.
 */
private suspend fun sendEventToTelegram(
    context: Context,
    camera: CameraConfig,
    event: CameraRecordingEvent
) {
    val path = event.localFilePath
    val file = path?.let { File(it) }
    val isGif = file != null && file.name.endsWith(".gif", ignoreCase = true)
    val gifFile = if (isGif) file else null

    val snapshotBitmap = if (file != null && !isGif && file.name.endsWith(".jpg", ignoreCase = true)) {
        BitmapFactory.decodeFile(file.absolutePath)
    } else null

    val res = TelegramBackupEngine.sendMotionAlert(
        botToken = AppSettings.telegramBotToken,
        chatId = AppSettings.telegramChatId,
        cameraName = camera.name,
        gifFile = gifFile,
        snapshot = snapshotBitmap,
        aiLabel = if (event.isMotionAlarm) "Detección de Movimiento (Grabación)" else "Clip de Video",
        timestampStr = "${event.formattedDate} ${event.formattedTime}"
    )

    withContext(Dispatchers.Main) {
        res.fold(
            onSuccess = {
                Toast.makeText(context, "✅ ¡Grabación enviada a Telegram con éxito!", Toast.LENGTH_LONG).show()
            },
            onFailure = { err ->
                Toast.makeText(context, "Error enviando a Telegram: ${err.message}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
