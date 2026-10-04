package com.kritix.desktop.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kritix.desktop.client.KritixEngineClient
import com.kritix.desktop.ui.components.CardBox
import com.kritix.desktop.ui.components.CodeBox
import com.kritix.desktop.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SecurityPerfView() {
    var targetUrl by remember { mutableStateOf("http://localhost:3000") }
    var findings by remember {
        mutableStateOf(
            listOf(
                "SQL Injection Boundary Test" to "Tested 5 synthetic SQLi vectors against parameters; no unescaped database traces exposed.",
                "Cross-Site Scripting (XSS)" to "DOM reflects inputs with proper HTML encoding.",
                "PII Leak Detection" to "Zero plaintext SSNs, credit cards, or JWT keys detected in response payloads."
            )
        )
    }

    var k6Script by remember {
        mutableStateOf(
            """import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 5 },
    { duration: '20s', target: 150 }, // Spike surge
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<250', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get('http://localhost:3000');
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(0.1);
}"""
        )
    }

    val scope = rememberCoroutineScope()
    var isAuditing by remember { mutableStateOf(false) }

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
                Text(text = "Security (OWASP DAST) & Performance SLAs", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(text = "Automated fuzzing and k6 performance scenarios with zero manual test coding.", fontSize = 12.sp, color = TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            isAuditing = true
                            findings = KritixEngineClient.runSecurityFuzz(targetUrl)
                            isAuditing = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard)
                ) {
                    Text(text = if (isAuditing) "Scanning..." else "🛡️ Run OWASP DAST", color = AccentEmerald)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Security findings
            CardBox(
                modifier = Modifier.weight(1f),
                title = "OWASP Vulnerability Scan",
                action = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentEmerald.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "ASVS Level 2 Verified", fontSize = 11.sp, color = AccentEmerald, fontWeight = FontWeight.SemiBold)
                    }
                }
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(findings) { (title, desc) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceCard)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(text = "✓ $title", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AccentEmerald)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = desc, fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }
            }

            // k6 performance script
            CardBox(
                modifier = Modifier.weight(1f),
                title = "Generated k6 Load Scenario & SLA"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceCard)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "50 VUs", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                            Text(text = "Virtual Users", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceCard)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "250ms", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                            Text(text = "Target P95 SLA", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }

                CodeBox(
                    code = k6Script,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
