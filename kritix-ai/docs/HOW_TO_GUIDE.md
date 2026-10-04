# Kritix AI — Developer How-To Guide

This guide provides practical, step-by-step instructions for testing **Websites, Python applications, Kotlin Multiplatform (KMP) apps, and Mobile Web** using Kritix AI.

---

## Table of Contents
1. [Quick Start with the Visual Studio](#1-quick-start-with-the-visual-studio)
2. [Testing Websites & Web SPAs (React, Next.js, Vue, Vite)](#2-testing-websites--web-spas)
3. [Testing Python Web Apps (Django, Flask, FastAPI, Streamlit)](#3-testing-python-web-apps)
4. [Testing Kotlin Multiplatform (KMP) & Mobile Apps](#4-testing-kotlin-multiplatform-kmp--mobile-apps)
5. [Using "Teach the Agent" Visual Studio](#5-using-teach-the-agent-visual-studio)
6. [Running Security (OWASP Top 10) & Performance Audits](#6-running-security-owasp-top-10--performance-audits)
7. [Automating in CI/CD (GitHub Actions)](#7-automating-in-cicd-github-actions)

---

## 1. Quick Start with the Visual Studio

The easiest way for a solo developer or small team to use Kritix is through the visual interface.

### Option A: Embedded Web Studio (Zero Setup, Any Browser)
No Node.js, Python, or JVM installation required:
```bash
cd kritix-ai
./bin/kritix studio
```
This automatically launches the embedded studio at `http://localhost:9090` in your default browser.

### Option B: Compose Desktop Cockpit (Native macOS / Windows / Linux)
If you prefer a native desktop window:
```bash
cd kritix-ai
./gradlew :app:run
```

---

## 2. Testing Websites & Web SPAs

Kritix AI natively inspects the live Chrome DevTools Protocol (CDP) accessibility tree, meaning it tests standard HTML5, Shadow DOM, and modern JavaScript SPAs without extra instrumentation.

### Step 1: Start your local development server
```bash
# Example: Next.js / Vite / React
npm run dev
# Server running on http://localhost:3000
```

### Step 2: Run an Autonomous Exploratory Test
In the **Quick Runner** tab:
1. Enter `http://localhost:3000` in the target bar.
2. Select **Autonomous Explorer**.
3. Click **"Run Autonomous Test"**.

Or via CLI:
```bash
./bin/kritix test http://localhost:3000 "Explore all buttons, test search forms, and check for 500 errors"
```

### What Kritix Does:
- Scans all actionable elements (buttons, inputs, links).
- Injects synthetic boundary values (special characters, empty inputs, large strings).
- Checks that zero unhandled JavaScript errors, 404 broken links, or 500 server crashes occur.

---

## 3. Testing Python Web Apps

Whether your backend is **FastAPI**, **Django**, **Flask**, or a data dashboard like **Streamlit**:

### Step 1: Start your Python app
```bash
# FastAPI / Uvicorn
uvicorn main:app --reload --port 8000

# Django
python manage.py runserver 8000

# Streamlit
streamlit run app.py
```

### Step 2: Run a Smoke Guard Test
```bash
./bin/kritix run pr-smoke-guard
```
Or in the Web Studio, click the `:8080` pill and set it to `http://localhost:8000`.

---

## 4. Testing Kotlin Multiplatform (KMP) & Mobile Apps

### Scenario A: KMP Compose for Web / Wasm / JS (Fully Supported)
If your KMP project compiles to Web (Compose HTML or WebAssembly):
```bash
cd your-kmp-project
./gradlew :wasmJsBrowserDevelopmentRun
# Runs on http://localhost:8080
```
Then point Kritix AI to `http://localhost:8080`. The engine will test the rendered interactive canvas or DOM nodes directly.

### Scenario B: Mobile Web & Responsive Viewports
Kritix AI supports mobile viewport emulation (touch gestures, small screens, device pixel ratios):
- In the Studio or CLI, specify mobile viewport emulation to test how your web or KMP web app renders on iPhone or Android devices.

### Scenario C: Native Android / iOS APKs (WebView / Hybrid Shells)
For native mobile applications:
1. **Hybrid / WebView screens**: Enable WebView debugging in your Android debug build:
   ```kotlin
   // In your Application or MainActivity.kt
   if (BuildConfig.DEBUG) {
       android.webkit.WebView.setWebContentsDebuggingEnabled(true)
   }
   ```
2. Port-forward the emulator's CDP socket to your host:
   ```bash
   adb forward tcp:9222 localabstract:chrome_devtools_remote
   ```
3. Point Kritix to `http://localhost:9222` to inspect and drive the app.

*(Note: Direct native UIAutomator/Appium drivers for pure native non-web views are slated for the upcoming v1.0 release).*

---

## 5. Using "Teach the Agent" Visual Studio

When you want Kritix to test a specific user journey (like Login → Add to Cart → Checkout):

1. Open the **"Teach Agent"** tab in the Studio (`http://localhost:9090`).
2. Add your demonstrated steps:
   - **Click**: `#login-btn` | Intent: *User opens authentication dialog*
   - **Type**: `input[name=email]` | Value: `dev@example.com`
   - **Click**: `#submit-order` | Intent: *Confirm purchase*
   - **Assert**: *Order confirmation number displayed*
3. Click **"✨ Synthesize Test Specs"**.
4. Kritix will instantly generate:
   - **Gherkin BDD Feature File** (`spec.feature`)
   - **Standalone Playwright TypeScript Spec** (`repro.spec.ts`)
5. Click **"📋 Copy Playwright Spec"** and paste it directly into your codebase or run it locally:
   ```bash
   npx playwright test repro.spec.ts
   ```

---

## 6. Running Security (OWASP Top 10) & Performance Audits

Solo developers often don't have dedicated QA or security teams. Kritix automates both:

### OWASP Top 10 DAST & PII Audit
In the **Security & Perf** tab, click **"🛡️ Run OWASP DAST"** (or run `./bin/kritix fuzz <url>`):
- Injects non-destructive SQL Injection (SQLi) vectors.
- Tests Cross-Site Scripting (XSS) input reflections.
- Scans HTTP response payloads for plaintext PII leaks (Social Security numbers, raw credit cards, unmasked tokens).

### Performance SLA & k6 Load Testing
In the **Security & Perf** tab, click **"🚀 Run k6 Perf Scenario"** (or run `./bin/kritix perf <url>`):
- Generates a production-ready k6 load test script.
- Configures traffic spikes up to 50 concurrent Virtual Users (VUs).
- Evaluates your application against a strict **P95 < 250ms** and **Error Rate < 1%** SLA budget.
- Copy the generated script and run it with `k6 run script.js`.

---

## 7. Automating in CI/CD (GitHub Actions)

Add this lightweight step to `.github/workflows/quality.yml`:

```yaml
name: Kritix Autonomous PR Smoke Guard

on: [pull_request]

jobs:
  smoke-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Go
        uses: actions/setup-go@v5
        with:
          go-version: '1.27'

      - name: Build Kritix AI
        run: |
          cd kritix-ai
          go build -o bin/kritix ./cmd/kritix

      - name: Start Staging / Dev Server
        run: |
          npm run build && npm run start &
          npx wait-on http://localhost:3000

      - name: Run Kritix Autonomous Smoke Guard
        run: |
          ./kritix-ai/bin/kritix test http://localhost:3000 "Critical user journey smoke check"
```

Failures will generate copy-pasteable Playwright reproduction specs and JUnit XML for the GitHub Actions summary.
