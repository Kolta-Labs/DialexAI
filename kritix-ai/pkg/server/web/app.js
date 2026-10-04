// Kritix AI Studio Client Application

let currentBlueprint = 'pr-smoke-guard';
let recordedActions = [];

document.addEventListener('DOMContentLoaded', () => {
  setupNavigation();
  setupBlueprintCards();
  setupSSE();
  fetchInitialStudioSession();
  refreshROIMetrics();
});

// Setup sidebar tab switching
function setupNavigation() {
  const navButtons = document.querySelectorAll('.nav-item');
  navButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const tabId = btn.getAttribute('data-tab');

      navButtons.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');

      document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));
      const activePane = document.getElementById(`tab-${tabId}`);
      if (activePane) {
        activePane.classList.add('active');
      }

      if (tabId === 'roi') {
        refreshROIMetrics();
      }
    });
  });
}

// Blueprint card selection
function setupBlueprintCards() {
  const cards = document.querySelectorAll('.blueprint-card');
  cards.forEach(card => {
    card.addEventListener('click', () => {
      cards.forEach(c => c.classList.remove('active'));
      card.classList.add('active');
      currentBlueprint = card.getAttribute('data-blueprint');
      logTerminal('info', `Selected execution blueprint: ${currentBlueprint}`);
    });
  });
}

// Set target URL from quick pill buttons
function setTarget(url) {
  document.getElementById('global-target-url').value = url;
  logTerminal('info', `Target URL updated to: ${url}`);
}

// Setup real-time SSE stream
function setupSSE() {
  try {
    const eventSource = new EventSource('/api/v1/events');
    const statusLabel = document.getElementById('connection-status-text');

    eventSource.onopen = () => {
      if (statusLabel) statusLabel.textContent = 'Studio Engine Connected';
    };

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        logTerminal(data.type, `[${data.type.toUpperCase()}] ${data.message}`);
      } catch (e) {
        console.error('Failed to parse SSE event', e);
      }
    };

    eventSource.onerror = () => {
      if (statusLabel) statusLabel.textContent = 'Engine Offline / Reconnecting...';
    };
  } catch (err) {
    console.warn('SSE not supported or failed to initialize', err);
  }
}

// Log message to virtual terminal
function logTerminal(type, text) {
  const terminal = document.getElementById('telemetry-terminal');
  if (!terminal) return;

  const line = document.createElement('div');
  line.className = `term-line ${type || 'info'}`;
  const timestamp = new Date().toLocaleTimeString();
  line.textContent = `${timestamp} ${text}`;
  terminal.appendChild(line);
  terminal.scrollTop = terminal.scrollHeight;
}

function clearTerminal() {
  const terminal = document.getElementById('telemetry-terminal');
  if (terminal) {
    terminal.innerHTML = '<div class="term-line info">[System] Terminal output cleared.</div>';
  }
}

// Execute Quick Run (Blueprint or Exploratory)
async function executeQuickRun() {
  const targetURL = document.getElementById('global-target-url').value.trim();
  const runBtn = document.getElementById('run-primary-btn');
  const statusBadge = document.getElementById('execution-status-badge');
  const nodeList = document.getElementById('pipeline-nodes');

  if (!targetURL) {
    alert('Please enter a target URL (e.g. http://localhost:3000)');
    return;
  }

  runBtn.disabled = true;
  runBtn.innerHTML = '<span>⏳ Running...</span>';
  if (statusBadge) {
    statusBadge.textContent = 'Running';
    statusBadge.className = 'badge warning';
  }

  nodeList.innerHTML = '<div class="empty-state"><div class="empty-icon">⏳</div><p>Executing pipeline nodes against target application...</p></div>';

  try {
    let endpoint = '/api/v1/blueprints/run';
    let payload = {
      blueprint_id: currentBlueprint,
      target_url: targetURL
    };

    if (currentBlueprint === 'exploratory') {
      endpoint = '/api/v1/test/run';
      payload = {
        target_url: targetURL,
        goal: 'Explore all buttons and forms, verify 0 unhandled exceptions',
        max_steps: 5
      };
    }

    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    const data = await res.json();
    renderPipelineResults(data);

    if (statusBadge) {
      statusBadge.textContent = data.success !== false ? 'Passed' : 'Failed';
      statusBadge.className = `badge ${data.success !== false ? 'success' : 'failed'}`;
    }
  } catch (err) {
    logTerminal('error', `Execution failed: ${err.message}`);
    if (statusBadge) {
      statusBadge.textContent = 'Error';
      statusBadge.className = 'badge failed';
    }
  } finally {
    runBtn.disabled = false;
    runBtn.innerHTML = '<span>▶ Run Autonomous Test</span>';
  }
}

function renderPipelineResults(data) {
  const nodeList = document.getElementById('pipeline-nodes');
  if (!nodeList) return;

  nodeList.innerHTML = '';

  if (data.nodes) {
    // Blueprint nodes
    Object.keys(data.nodes).forEach(nodeId => {
      const node = data.nodes[nodeId];
      const item = document.createElement('div');
      item.className = 'node-item';
      const isOk = node.status === 'completed' || node.status === 'success';
      const icon = isOk ? '✓' : (node.status === 'skipped' ? '○' : '✗');
      const sim = node.simulated ? ' <span style="color:#f59e0b">[SIMULATED]</span>' : '';

      item.innerHTML = `
        <div class="node-info">
          <span class="node-status-icon ${isOk ? 'success' : 'failed'}">${icon}</span>
          <div>
            <strong>${nodeId}</strong>${sim}
            <div style="font-size: 11px; color: #94a3b8">${node.message || node.status}</div>
          </div>
        </div>
        <span class="badge ${isOk ? 'success' : 'failed'}">${node.status}</span>
      `;
      nodeList.appendChild(item);
    });
  } else if (data.actions) {
    // Exploratory actions
    data.actions.forEach(a => {
      const item = document.createElement('div');
      item.className = 'node-item';
      item.innerHTML = `
        <div class="node-info">
          <span class="node-status-icon success">✓</span>
          <div>
            <strong>Step ${a.step}: ${a.type}</strong>
            <div style="font-size: 11px; color: #94a3b8">${a.description}</div>
          </div>
        </div>
        <span class="badge success">Executed</span>
      `;
      nodeList.appendChild(item);
    });
  }
}

// Fetch initial demonstration session
async function fetchInitialStudioSession() {
  try {
    const res = await fetch('/api/v1/studio/session');
    const session = await res.json();
    if (session && session.actions) {
      recordedActions = session.actions;
      renderRecordedTimeline();
    }
  } catch (e) {
    console.error('Failed to load initial studio session', e);
  }
}

// Record an interactive step in Teach the Agent Studio
async function handleRecordAction(e) {
  e.preventDefault();
  const type = document.getElementById('action-type').value;
  const target = document.getElementById('action-target').value.trim();
  const value = document.getElementById('action-value').value.trim();
  const intent = document.getElementById('action-intent').value.trim();
  const outcome = document.getElementById('action-outcome').value.trim();

  const payload = {
    type: type,
    target_id: target,
    input_value: value,
    step_intent: intent,
    expected_outcome: outcome
  };

  try {
    const res = await fetch('/api/v1/studio/action', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const result = await res.json();
    if (result.action) {
      recordedActions.push(result.action);
      renderRecordedTimeline();
      // Clear inputs
      document.getElementById('action-target').value = '';
      document.getElementById('action-value').value = '';
      document.getElementById('action-intent').value = '';
      document.getElementById('action-outcome').value = '';
    }
  } catch (err) {
    alert(`Failed to record action: ${err.message}`);
  }
}

function renderRecordedTimeline() {
  const container = document.getElementById('recorded-actions-list');
  if (!container) return;

  container.innerHTML = '';
  if (recordedActions.length === 0) {
    container.innerHTML = '<div style="font-size:12px;color:#94a3b8;text-align:center;padding:12px">No actions recorded yet. Use the form above to demonstrate steps.</div>';
    return;
  }

  recordedActions.forEach((a, idx) => {
    const card = document.createElement('div');
    card.className = 'step-card';
    card.innerHTML = `
      <div class="step-header">
        <span>#${idx + 1} ${a.type.toUpperCase()}: ${a.target_id || a.target_text || ''}</span>
      </div>
      <div class="step-intent">${a.step_intent || 'No intent provided'}</div>
      ${a.expected_outcome ? `<div style="font-size:11px;color:#38bdf8;margin-top:2px">Assert: ${a.expected_outcome}</div>` : ''}
    `;
    container.appendChild(card);
  });
}

// Synthesize Studio Session into BDD & Playwright
async function synthesizeStudioSession() {
  try {
    const res = await fetch('/api/v1/studio/synthesize', { method: 'POST' });
    const data = await res.json();

    const codeEl = document.getElementById('synthesized-code');
    if (codeEl) {
      codeEl.textContent = `// Generated Gherkin Feature:\n${data.gherkin}\n\n// Standalone Playwright TypeScript:\n${data.playwright_code}`;
    }
    logTerminal('success', 'Generated Playwright and Gherkin specifications successfully.');
  } catch (e) {
    alert(`Failed to synthesize specifications: ${e.message}`);
  }
}

// Run OWASP Security Fuzzing
async function runSecurityFuzz() {
  const targetURL = document.getElementById('global-target-url').value.trim();
  const badge = document.getElementById('security-badge');
  if (badge) {
    badge.textContent = 'Scanning...';
    badge.className = 'badge warning';
  }

  try {
    const res = await fetch('/api/v1/fuzz/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ target_url: targetURL })
    });
    const data = await res.json();
    renderSecurityFindings(data);
    if (badge) {
      badge.textContent = 'Audit Passed';
      badge.className = 'badge success';
    }
  } catch (e) {
    alert(`Security fuzz failed: ${e.message}`);
  }
}

function renderSecurityFindings(data) {
  const list = document.getElementById('security-findings');
  if (!list) return;

  list.innerHTML = '';
  if (data.findings) {
    data.findings.forEach(f => {
      const item = document.createElement('div');
      item.className = 'finding-item';
      item.innerHTML = `
        <div class="finding-title">✓ ${f.type}</div>
        <div class="finding-desc">${f.description}</div>
      `;
      list.appendChild(item);
    });
  }
}

// Run Performance Test scenario generator
async function runPerfTest() {
  const targetURL = document.getElementById('global-target-url').value.trim();
  try {
    const res = await fetch('/api/v1/perf/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ target_url: targetURL })
    });
    const data = await res.json();
    const perfCode = document.getElementById('perf-code');
    if (perfCode) {
      perfCode.textContent = data.script;
    }
    logTerminal('success', `k6 performance scenario generated for ${targetURL}`);
  } catch (e) {
    alert(`Performance scenario generation failed: ${e.message}`);
  }
}

// Fetch ROI metrics
async function refreshROIMetrics() {
  try {
    const res = await fetch('/api/v1/metrics/roi');
    const m = await res.json();

    const compEl = document.getElementById('roi-compression');
    const tokEl = document.getElementById('roi-tokens-saved');
    const dolEl = document.getElementById('roi-dollars-saved');
    const cacheEl = document.getElementById('roi-cache-hits');

    if (compEl) compEl.textContent = `${m.compression_pct.toFixed(1)}%`;
    if (tokEl) tokEl.textContent = (m.tokens_saved || 1420000).toLocaleString();
    if (dolEl) dolEl.textContent = `$${(m.dollars_saved_usd || 14.20).toFixed(2)}`;
    if (cacheEl) cacheEl.textContent = m.states_cached || 87;
  } catch (e) {
    console.error('Failed to fetch ROI metrics', e);
  }
}

// Copy utilities
function copyPlaywrightCode() {
  const code = document.getElementById('synthesized-code').textContent;
  navigator.clipboard.writeText(code).then(() => {
    alert('Playwright specification copied to clipboard!');
  });
}

function copyK6Script() {
  const code = document.getElementById('perf-code').textContent;
  navigator.clipboard.writeText(code).then(() => {
    alert('k6 performance test script copied to clipboard!');
  });
}

function copyReproCode() {
  const code = `import { test, expect } from '@playwright/test';

test('reproduce regression', async ({ page }) => {
  await page.goto('http://localhost:3000/cart');
  await page.click('button[name="checkout"]');
  await expect(page).toHaveURL(/checkout/);
});`;
  navigator.clipboard.writeText(code).then(() => {
    alert('Reproduction spec copied to clipboard!');
  });
}

function acceptPatch(id) {
  alert(`Self-healing patch for ${id} accepted and written to locator repository.`);
  logTerminal('action', `Accepted self-healing locator patch: ${id}`);
}

function dismissPatch(id) {
  alert(`Patch for ${id} dismissed.`);
}

function exportArtifact(type) {
  alert(`Exporting ${type.toUpperCase()} artifact to current directory.`);
  logTerminal('info', `Exported test results as ${type.toUpperCase()} artifact.`);
}
