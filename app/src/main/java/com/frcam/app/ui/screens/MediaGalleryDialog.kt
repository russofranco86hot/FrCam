package com.frcam.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.frcam.app.ui.theme.FrColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MediaFilterType(val label: String) {
    ALL("Todos"),
    PHOTOS("Fotos"),
    VIDEOS("Videos"),
    GIFS("GIFs")
}

data class FrMediaItem(
    val file: File,
    val name: String,
    val type: MediaFilterType,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf(MediaFilterType.ALL) }
    var mediaItems by remember { mutableStateOf<List<FrMediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun refreshMedia() {
        isLoading = true
        val items = withContext(Dispatchers.IO) {
            scanFrCamMedia(context)
        }
        mediaItems = items
        isLoading = false
    }

    LaunchedEffect(Unit) {
        refreshMedia()
    }

    val filteredItems = remember(mediaItems, selectedFilter) {
        when (selectedFilter) {
            MediaFilterType.ALL -> mediaItems
            else -> mediaItems.filter { it.type == selectedFilter }
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
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = Color(0xFFA78BFA)
                )
                Text(
                    text = "Galería FrCam (${filteredItems.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Filtros de categoría
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MediaFilterType.entries.forEach { f ->
                        FilterChip(
                            selected = selectedFilter == f,
                            onClick = { selectedFilter = f },
                            label = { Text(f.label, fontSize = 11.sp) }
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
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                    }
                } else if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PermMedia,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "No hay archivos en esta categoría",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "Las fotos, videos grabados y GIFs de movimiento aparecerán aquí.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
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
                        items(filteredItems, key = { it.file.absolutePath }) { item ->
                            MediaItemRow(
                                item = item,
                                onOpen = { openMediaFile(context, item.file, item.mimeType) },
                                onShare = { shareMediaFile(context, item.file, item.mimeType) },
                                onDelete = {
                                    if (item.file.delete()) {
                                        mediaItems = mediaItems.filter { it.file != item.file }
                                        Toast.makeText(context, "Archivo eliminado", Toast.LENGTH_SHORT).show()
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
private fun MediaItemRow(
    item: FrMediaItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(item.lastModified) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.lastModified))
    }
    val sizeStr = remember(item.sizeBytes) {
        if (item.sizeBytes >= 1024 * 1024) {
            "%.1f MB".format(item.sizeBytes / (1024f * 1024f))
        } else {
            "${item.sizeBytes / 1024} KB"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = FrColors.card),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Miniatura o icono del tipo de medio
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                when (item.type) {
                    MediaFilterType.PHOTOS -> {
                        var thumbBmp by remember(item.file.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(item.file.path) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                    thumbBmp = BitmapFactory.decodeFile(item.file.path, opts)
                                } catch (_: Exception) {}
                            }
                        }
                        if (thumbBmp != null) {
                            Image(
                                bitmap = thumbBmp!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFF38BDF8))
                        }
                    }
                    MediaFilterType.VIDEOS -> {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(28.dp))
                    }
                    MediaFilterType.GIFS -> {
                        Icon(Icons.Default.Gif, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(34.dp))
                    }
                    else -> {
                        Icon(Icons.Default.PermMedia, contentDescription = null, tint = Color(0xFFA78BFA))
                    }
                }
            }

            // Datos del archivo
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White
                )
                Text(
                    text = "$dateStr • $sizeStr",
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            // Botones de acción
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Compartir",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Eliminar",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private fun scanFrCamMedia(context: Context): List<FrMediaItem> {
    val results = mutableListOf<FrMediaItem>()
    val dirsToScan = listOf(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FrCam"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FrCam/Movimientos"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "FrCam"),
        File(context.cacheDir, "motion_gifs")
    )

    for (dir in dirsToScan) {
        if (!dir.exists() || !dir.isDirectory) continue
        val files = dir.listFiles() ?: continue
        for (f in files) {
            if (!f.isFile || f.length() <= 0) continue
            val lower = f.name.lowercase()
            val (type, mime) = when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ->
                    MediaFilterType.PHOTOS to "image/jpeg"
                lower.endsWith(".mp4") ->
                    MediaFilterType.VIDEOS to "video/mp4"
                lower.endsWith(".gif") ->
                    MediaFilterType.GIFS to "image/gif"
                else -> continue
            }
            results.add(
                FrMediaItem(
                    file = f,
                    name = f.name,
                    type = type,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                    mimeType = mime
                )
            )
        }
    }

    return results.sortedByDescending { it.lastModified }
}

private fun openMediaFile(context: Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Abrir con...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo abrir el archivo: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMediaFile(context: Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Compartir...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir el archivo: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
