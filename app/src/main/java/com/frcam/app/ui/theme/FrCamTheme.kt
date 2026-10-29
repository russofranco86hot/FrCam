package com.frcam.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.frcam.app.data.AppSettings

private val FrCamDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF04131D),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = Color(0xFF34D399),
    background = Color(0xFF090D16),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFF87171)
)

/** Variante AMOLED: negro puro en fondos para apagar píxeles y ahorrar batería. */
private val FrCamAmoledColorScheme = FrCamDarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF14181F),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF111111),
    surfaceContainerHighest = Color(0xFF181818),
    primaryContainer = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0B3340)
)

/**
 * Paleta de fondos usada por las pantallas. Se resuelve según el ajuste AMOLED,
 * y al leer un estado de Compose la interfaz se actualiza en caliente.
 */
object FrColors {
    private val amoled get() = AppSettings.amoled

    val background: Color get() = if (amoled) Color.Black else Color(0xFF090D16)
    val deepest: Color get() = if (amoled) Color.Black else Color(0xFF070B14)
    val surface: Color get() = if (amoled) Color.Black else Color(0xFF111827)
    val surfaceAlt: Color get() = if (amoled) Color(0xFF080808) else Color(0xFF0F172A)
    val card: Color get() = if (amoled) Color(0xFF0B0B0B) else Color(0xFF141D2F)
    val cardHigh: Color get() = if (amoled) Color(0xFF141414) else Color(0xFF1E293B)
}

@Composable
fun FrCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (AppSettings.amoled) FrCamAmoledColorScheme else FrCamDarkColorScheme,
        content = content
    )
}
