package com.dialex.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform-native image decoding from raw byte arrays (e.g. from base64 data URLs).
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
