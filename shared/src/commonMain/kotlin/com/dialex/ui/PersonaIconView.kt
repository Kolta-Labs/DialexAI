package com.dialex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dialex.theme.LocalCcColors
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Universal avatar / icon display for AI Personas.
 * Supports:
 * - Base64 Data URL or raw base64 image strings (renders platform decoded ImageBitmap).
 * - Predefined semantic icon keys (e.g. "code", "science", "gavel") mapped to Material ImageVectors.
 * - Automatic domain category fallbacks.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun PersonaIconView(
    icon: String?,
    category: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalCcColors.current.accent,
) {
    val imageBitmap: ImageBitmap? = remember(icon) {
        if (!icon.isNullOrBlank() && (icon.startsWith("data:image", ignoreCase = true) || icon.length > 80)) {
            runCatching {
                val rawBase64 = if (icon.contains(",")) icon.substringAfter(",") else icon
                val cleanBase64 = rawBase64.trim().replace("\n", "").replace("\r", "")
                val bytes = Base64.Default.decode(cleanBase64)
                decodeImageBitmap(bytes)
            }.getOrNull()
        } else {
            null
        }
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
        )
    } else {
        val vector = resolvePersonaIcon(icon, category)
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}
