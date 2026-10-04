package com.kritix.desktop.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

data class DemonstratedStep(
    val actionType: String,
    val target: String,
    val value: String,
    val intent: String,
    val assertion: String
)

@Composable
fun TeachAgentView() {
    var actionType by remember { mutableStateOf("Click") }
    var target by remember { mutableStateOf("#login-submit") }
    var inputValue by remember { mutableStateOf("") }
    var intent by remember { mutableStateOf("User submits credentials to authenticate") }
    var assertion by remember { mutableStateOf("Dashboard renders without error") }

    var steps by remember {
        mutableStateOf(
            listOf(
                DemonstratedStep("Click", "#login-btn", "", "Navigate to login screen", "Login modal visible"),
                DemonstratedStep("Type", "input[name=email]", "admin@enterprise.internal", "Enter authorized user email", "Input reflects text"),
                DemonstratedStep("Click", "#login-submit", "", "Submit credentials", "Redirect to /dashboard with HTTP 200")
            )
        )
    }

    var synthesizedCode by remember {
        mutableStateOf(
            """import { test, expect } from '@playwright/test';

test('Synthesized Human Demonstration: User Authentication', async ({ page }) => {
  await page.goto('http://localhost:3000');
  
  // Step 1: Navigate to login screen
  await page.click('#login-btn');
  await expect(page.locator('#modal')).toBeVisible();

  // Step 2: Enter authorized user email
  await page.fill('input[name=email]', 'admin@enterprise.internal');

  // Step 3: Submit credentials
  await page.click('#login-submit');
  await expect(page).toHaveURL(/.*dashboard/);
});"""
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "\"Teach the Agent\" Studio", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(text = "Demonstrate user journeys once; synthesize self-healing BDD & Playwright specs.", fontSize = 12.sp, color = TextSecondary)
            }
            Button(
                onClick = {
                    synthesizedCode = """import { test, expect } from '@playwright/test';

test('Synthesized Human Demonstration (${steps.size} Steps)', async ({ page }) => {
  await page.goto('http://localhost:3000');
${steps.joinToString("\n") { "  // Intent: ${it.intent}\n  await page.${if (it.actionType == "Type") "fill('${it.target}', '${it.value}')" else "click('${it.target}')"};\n  // Assert: ${it.assertion}" }}
  await expect(page).toHaveURL(/.*/);
});"""
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
            ) {
                Text(text = "✨ Synthesize Test Specs")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Left: Record Interaction Form & Timeline
            CardBox(
                modifier = Modifier.weight(1f),
                title = "Record Step Demonstration"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = actionType,
                            onValueChange = { actionType = it },
                            label = { Text("Action (Click/Type)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = target,
                            onValueChange = { target = it },
                            label = { Text("Target Selector / Text") },
                            modifier = Modifier.weight(2f)
                        )
                    }

                    OutlinedTextField(
                        value = intent,
                        onValueChange = { intent = it },
                        label = { Text("Mandatory Business Intent") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = assertion,
                        onValueChange = { assertion = it },
                        label = { Text("Expected Invariant / Assertion") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            if (target.isNotBlank() && intent.isNotBlank()) {
                                steps = steps + DemonstratedStep(actionType, target, inputValue, intent, assertion)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(text = "+ Append Step to Session", color = AccentCyan)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "Recorded Timeline (${steps.size} steps):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextSecondary)

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(steps) { idx, s ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "#${idx + 1} ${s.actionType.uppercase()}: ${s.target}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AccentIndigo)
                                }
                                Text(text = s.intent, fontSize = 11.sp, color = TextSecondary)
                                Text(text = "Assert: ${s.assertion}", fontSize = 10.sp, color = AccentCyan)
                            }
                        }
                    }
                }
            }

            // Right: Synthesized Playwright Spec
            CardBox(
                modifier = Modifier.weight(1f),
                title = "Synthesized Playwright TypeScript (`spec.ts`)"
            ) {
                CodeBox(
                    code = synthesizedCode,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
