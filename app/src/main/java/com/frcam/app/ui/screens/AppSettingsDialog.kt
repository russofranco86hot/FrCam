package com.frcam.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frcam.app.BuildConfig
import com.frcam.app.data.AppSettings
import com.frcam.app.data.MotionSensitivity
import com.frcam.app.model.CameraConfig
import com.frcam.app.ui.theme.FrColors
import com.frcam.app.wear.PhoneWearSyncEngine

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppSettingsDialog(
    cameras: List<CameraConfig> = emptyList(),
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var amoled by remember { mutableStateOf(AppSettings.amoled) }
    var motionSeconds by remember { mutableIntStateOf(AppSettings.motionClipSeconds) }
    var startMinutes by remember { mutableIntStateOf(AppSettings.scheduleStartMinutes) }
    var endMinutes by remember { mutableIntStateOf(AppSettings.scheduleEndMinutes) }
    var segmentMinutes by remember { mutableIntStateOf(AppSettings.scheduleSegmentMinutes) }
    var sensitivity by remember { mutableStateOf(AppSettings.motionSensitivity) }
    var aiEnabled by remember { mutableStateOf(AppSettings.aiDetectionEnabled) }
    var aiFilterSmart by remember { mutableStateOf(AppSettings.aiFilterOnlySmart) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8)
                )
                Text("Ajustes Generales", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // SECCIÓN TEMA / APARIENCIA
                Text(
                    text = "Apariencia",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF38BDF8)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Modo AMOLED (Negro Puro)",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Fondos negros (#000000) por defecto para ahorrar batería en pantallas OLED.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = amoled,
                                onCheckedChange = {
                                    amoled = it
                                    AppSettings.updateAmoled(it)
                                }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // SECCIÓN SENSIBILIDAD Y DETECCIÓN INTELIGENTE
                Text(
                    text = "Sensibilidad de Movimiento (Anti-Hojas)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF38BDF8)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Nivel de sensibilidad del detector:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Si tienes cámaras en el patio o jardín donde el viento agita ramas y hojas de árboles, elige 'Baja'.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MotionSensitivity.entries.forEach { opt ->
                                val isSelected = sensitivity == opt
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            sensitivity = opt
                                            AppSettings.updateMotionSensitivity(opt)
                                        },
                                    color = if (isSelected) Color(0xFF0369A1).copy(alpha = 0.25f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                sensitivity = opt
                                                AppSettings.updateMotionSensitivity(opt)
                                            }
                                        )
                                        Column {
                                            Text(
                                                text = opt.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (isSelected) Color(0xFF38BDF8) else Color.White
                                            )
                                            Text(
                                                text = opt.description,
                                                fontSize = 10.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // SECCIÓN INTELIGENCIA ARTIFICIAL
                Text(
                    text = "Inteligencia Artificial (IA On-Device)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF38BDF8)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Reconocimiento IA (Personas, Perros, Autos)",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Analiza los cuadros con IA en el teléfono para identificar personas (🚶), mascotas (🐕) o vehículos (🚗) y detallarlo en la notificación.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = aiEnabled,
                                onCheckedChange = {
                                    aiEnabled = it
                                    AppSettings.updateAiDetectionEnabled(it)
                                }
                            )
                        }

                        if (aiEnabled) {
                            HorizontalDivider(color = Color(0xFF334155))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "Filtrar solo con IA (Anti-falsas alarmas)",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF34D399)
                                    )
                                    Text(
                                        text = "Solo notifica si la IA confirma una persona, mascota o vehículo. Silencia completamente hojas agitadas por el viento o sombras.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = aiFilterSmart,
                                    onCheckedChange = {
                                        aiFilterSmart = it
                                        AppSettings.updateAiFilterOnlySmart(it)
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // SECCIÓN CONFIGURACIÓN DE GRABACIÓN
                Text(
                    text = "Configuración de Grabación",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF38BDF8)
                )

                // 1. Grabación por movimiento
                Card(
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Duración de clip al detectar movimiento",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Tiempo en segundos que graba video en MP4 luego de una detección de movimiento:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 15, 20, 30, 45, 60).forEach { sec ->
                                FilterChip(
                                    selected = motionSeconds == sec,
                                    onClick = {
                                        motionSeconds = sec
                                        AppSettings.updateMotionClipSeconds(sec)
                                    },
                                    label = { Text("${sec}s", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 2. Franja horaria programada
                Card(
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Grabación en Franja Horaria",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Graba de forma continua mientras la hora esté dentro del rango programado:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Desde", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    AppSettings.formatMinutes(startMinutes),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF34D399)
                                )
                            }
                            Text("➜", color = Color(0xFF64748B))
                            Column {
                                Text("Hasta", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    AppSettings.formatMinutes(endMinutes),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF34D399)
                                )
                            }
                        }

                        // Presets rápidos de franja horaria
                        Text("Franjas recomendadas:", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Triple("Noche (22:00 a 06:00)", 22 * 60, 6 * 60),
                                Triple("Madrugada (00:00 a 07:00)", 0, 7 * 60),
                                Triple("Jornada (08:00 a 18:00)", 8 * 60, 18 * 60),
                                Triple("24 Horas", 0, 0)
                            ).forEach { (label, s, e) ->
                                val selected = startMinutes == s && endMinutes == e
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        startMinutes = s
                                        endMinutes = e
                                        AppSettings.updateSchedule(s, e)
                                    },
                                    label = { Text(label, fontSize = 10.sp) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Duración de cada archivo de video:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(2, 5, 10, 15).forEach { min ->
                                FilterChip(
                                    selected = segmentMinutes == min,
                                    onClick = {
                                        segmentMinutes = min
                                        AppSettings.updateScheduleSegmentMinutes(min)
                                    },
                                    label = { Text("${min} min", fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // SECCIÓN WEAR OS (RELOJ INTELIGENTE)
                Text(
                    text = "Wear OS (Reloj Inteligente)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF38BDF8)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Watch,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Sincronización con FrCam Wear OS",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Transfiere tus ${cameras.size} cámaras configuradas a tu reloj inteligente Wear OS para verlas juntas o en pantalla completa.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                PhoneWearSyncEngine.syncCamerasToWatch(context, cameras)
                                Toast.makeText(
                                    context,
                                    "Enviando ${cameras.size} cámaras al reloj...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sincronizar cámaras con reloj ahora", fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider()

                // SECCIÓN "ACERCA DE"
                Text(
                    text = "Acerca de",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF38BDF8)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = FrColors.card),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "FrCam Monitor Pro",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "Versión ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Creador:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = AppSettings.CREATOR,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Protocolos:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Text("V380 • Eye4 • Hikvision • Dahua • Ring • ONVIF", fontSize = 11.sp, color = Color(0xFFE2E8F0))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Almacenamiento:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Text("Galería / Pictures / Movies (FrCam)", fontSize = 11.sp, color = Color(0xFFE2E8F0))
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
