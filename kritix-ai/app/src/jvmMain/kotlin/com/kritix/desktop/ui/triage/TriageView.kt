package com.kritix.desktop.ui.triage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import com.kritix.desktop.ui.components.CardBox
import com.kritix.desktop.ui.components.CodeBox
import com.kritix.desktop.ui.theme.*

@Composable
fun TriageView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            Text(text = "Triage & Self-Healing Governance", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(text = "Inspect regressions, evaluate proposed self-healing locator patches, and copy reproduction specs.", fontSize = 12.sp, color = TextSecondary)
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Self healing locator patch card
            CardBox(
                modifier = Modifier.weight(1f),
                title = "Healed Locator Regression (REG-1049)",
                action = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentAmber.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "Proposed Patch", fontSize = 11.sp, color = AccentAmber, fontWeight = FontWeight.SemiBold)
                    }
                }
            ) {
                Text(
                    text = "Element '#checkout-btn' was detached in updated frontend build. Self-healing algorithm mapped alternative target via accessibility role:",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF06090E))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "- await page.click('#checkout-btn');",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = AccentRose
                        )
                        Text(
                            text = "+ await page.getByRole('button', { name: 'Pay Now' }).click();",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = AccentEmerald
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                    ) {
                        Text(text = "Accept Patch")
                    }
                    OutlinedButton(onClick = {}) {
                        Text(text = "Dismiss")
                    }
                }
            }

            // Repro Spec Card
            CardBox(
                modifier = Modifier.weight(1f),
                title = "Standalone Repro (`repro.spec.ts`)"
            ) {
                Text(
                    text = "Self-contained reproduction script with synthetic test state and clean session:",
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                CodeBox(
                    code = """import { test, expect } from '@playwright/test';

test('Reproduce Issue: Cart Checkout Regression', async ({ page }) => {
  await page.goto('http://localhost:3000/cart');
  await page.click('button[name="checkout"]');
  await expect(page).toHaveURL(/.*checkout/);
});""",
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard)
                    ) {
                        Text(text = "📋 Copy Repro Spec", color = AccentCyan)
                    }
                }
            }
        }
    }
}
