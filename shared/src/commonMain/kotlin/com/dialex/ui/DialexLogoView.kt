package com.dialex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dialex.theme.LocalCcColors
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Universal Dialex logo component across Android, Desktop, and iOS.
 * Uses official brand assets directly from the logos directory (not 3D style):
 * - Transparent background file (logo.webp) when on light/neutral backgrounds.
 * - White background file (logo_white_bg.webp) when on dark backgrounds for high contrast.
 * Falls back cleanly to accent gradient forum icon if graphics decoding is unavailable.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun DialexLogoView(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    useWhiteBg: Boolean? = null,
    shape: Shape? = null,
    contentDescription: String? = "Dialex"
) {
    val cc = LocalCcColors.current
    // Appropriately pick white background on dark surfaces for high contrast,
    // and transparent background on light surfaces for seamless blending.
    val isWhiteBg = useWhiteBg ?: cc.isDark
    val finalShape = shape ?: if (isWhiteBg) RoundedCornerShape(percent = 22) else RectangleShape

    val imageBitmap: ImageBitmap? = remember(isWhiteBg) {
        runCatching {
            val raw = if (isWhiteBg) DIALEX_LOGO_WHITE_BG_BASE64 else DIALEX_LOGO_BASE64
            val bytes = Base64.Default.decode(raw)
            decodeImageBitmap(bytes)
        }.getOrNull()
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(finalShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(finalShape)
                .background(cc.accentGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Forum,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

