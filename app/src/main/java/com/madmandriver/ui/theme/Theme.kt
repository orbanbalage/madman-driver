package com.madmandriver.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val SeverityModerate = Color(0xFFF9A825)
val SeverityStrong = Color(0xFFEF6C00)
val SeverityExtreme = Color(0xFFC62828)

fun severityColor(severity: String): Color = when (severity) {
    "EXTREME" -> SeverityExtreme
    "STRONG" -> SeverityStrong
    else -> SeverityModerate
}

@Composable
fun MadmanDriverTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
