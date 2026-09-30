package com.dialex.presentation.mobile.qr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform Expect for QR Code Scanning View.
 * On Android / iOS: Launches CameraX / AVFoundation barcode scanner.
 * On Desktop / Fallback: Displays manual QR pairing code intake dialog.
 */
@Composable
expect fun QrScannerView(
    onQrCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
)
