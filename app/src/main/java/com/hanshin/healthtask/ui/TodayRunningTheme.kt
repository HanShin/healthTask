package com.hanshin.healthtask.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object RunPalette {
    val Ink = Color(0xFF17251F)
    val Lime = Color(0xFFD5F58A)
    val Muted = Color(0xFF65726A)
    val Background = Color(0xFFF5F7F5)
    val Line = Color(0xFFE5EAE6)
}

@Composable
fun TodayRunningTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = RunPalette.Ink, onPrimary = Color.White,
            primaryContainer = RunPalette.Lime, onPrimaryContainer = RunPalette.Ink,
            secondary = Color(0xFF35745A), onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5F1E9), onSecondaryContainer = RunPalette.Ink,
            tertiary = Color(0xFF4D668E), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE9EFF8), onTertiaryContainer = Color(0xFF26364F),
            background = RunPalette.Background, onBackground = RunPalette.Ink,
            surface = Color.White, onSurface = RunPalette.Ink,
            surfaceVariant = Color(0xFFEDF1ED), onSurfaceVariant = Color(0xFF637168),
            surfaceTint = Color.Transparent,
            surfaceContainer = Color.White, surfaceContainerLow = Color.White,
            surfaceContainerHigh = Color(0xFFF0F4EF), surfaceContainerHighest = Color(0xFFE9EEE8),
            outline = Color(0xFF818D84), outlineVariant = RunPalette.Line,
            error = Color(0xFFB64135), onError = Color.White,
        ),
        typography = Typography(
            displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
            displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
            displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    val shape = RoundedCornerShape(24.dp)
    if (onClick == null) Card(modifier.fillMaxWidth(), shape = shape, colors = colors, border = border) {
        Column(Modifier.padding(20.dp), content = content)
    } else Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, border = border) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
internal fun SectionHeading(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun StatusPill(text: String, dark: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (dark) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (dark) RunPalette.Lime else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun AppIconBadge(icon: ImageVector, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier.size(44.dp).background(
            if (accent) RunPalette.Lime else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp), tint = RunPalette.Ink) }
}

@Composable
internal fun EmptyRecordState(icon: ImageVector, title: String, description: String) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIconBadge(icon, accent = true)
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
internal fun SummaryMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (dark) Color(0xFFB9C6BD) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"), color = foreground)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = muted)
    }
}
