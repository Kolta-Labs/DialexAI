package com.kritix.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kritix.desktop.ui.theme.*

@Composable
fun TabHeader(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        "🚀 Quick Runner",
        "🎥 Teach Agent",
        "🛡️ Security & Perf",
        "🐞 Triage & Repro",
        "💰 Token ROI"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(BgSecondary)
            .border(1.dp, BorderSubtle)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Brand
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentIndigo),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚡",
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Kritix AI",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Testing Studio",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }

        // Navigation Tabs
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) AccentIndigo.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color(0xFFA5B4FC) else TextSecondary
                    )
                }
            }
        }

        // Connection Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceCard)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentEmerald)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Engine v0.3.0",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSecondary)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(18.dp)
    ) {
        if (title != null || action != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
                action?.invoke()
            }
            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            Spacer(modifier = Modifier.height(14.dp))
        }
        content()
    }
}

@Composable
fun CodeBox(
    code: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF06090E))
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(14.dp)
    ) {
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFFE2E8F0),
            lineHeight = 18.sp
        )
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    subtitle: String,
    valueColor: Color = AccentCyan,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgSecondary)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(16.dp)
    ) {
        Text(text = title, fontSize = 12.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = subtitle, fontSize = 11.sp, color = TextDim)
    }
}
