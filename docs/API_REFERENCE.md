# Dialex AI REST & Server-Sent Events (SSE) API Reference

The **Dialex AI Go Orchestration Engine** exposes a high-performance HTTP REST API and real-time Server-Sent Events (SSE) stream on port `8080` (by default).

---

## 1. Authentication & Headers

Protected endpoints require a JSON Web Token (JWT) passed in the `Authorization` header:

```http
Authorization: Bearer <jwt_token>
Content-Type: application/json
```

---

## 2. Authentication Endpoints

### 2.1 Login & Obtain Token
`POST /api/auth/login`

#### Request:
```json
{
  "username": "admin",
  "password": "your_password"
}
```

#### Response (`200 OK`):
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_at": 1758153600,
  "user": {
    "id": "u_01j9a",
    "username": "admin",
    "role": "administrator"
  }
}
```

---

## 3. Deliberation Management Endpoints

### 3.1 List Deliberations
`GET /api/debates`

Returns all active and archived debate discussions.

#### Response (`200 OK`):
```json
[
  {
    "id": "disc_01j9a123",
    "name": "Microservices vs Modular Monolith",
    "status": "RUNNING",
    "created_at": 1758067200,
    "updated_at": 1758067400,
    "round_count": 2,
    "max_rounds": 5
  }
]
```

---

### 3.2 Create Deliberation
`POST /api/debates`

#### Request:
```json
{
  "name": "Kafka vs Pulsar Architecture",
  "topic": "Should we migrate from Kafka to Apache Pulsar for real-time telemetry?",
  "context": "Current throughput: 50k msgs/sec. Need multi-tenancy and tiered S3 storage.",
  "config": {
    "maxRounds": 4,
    "primary": {
      "provider": "ANTHROPIC",
      "model": "claude-3-7-sonnet",
      "mode": "API",
      "role": "The Facilitator",
      "personaId": "sys_facilitator"
    },
    "secondary": {
      "provider": "OPENAI",
      "model": "gpt-4o",
      "mode": "API",
      "role": "Devil's Advocate",
      "personaId": "sys_devils_advocate"
    },
    "tertiary": {
      "provider": "GOOGLE",
      "model": "gemini-2.0-flash",
      "mode": "API",
      "role": "The Pragmatist",
      "personaId": "sys_pragmatist"
    },
    "humanDialogue": true,
    "topicDriftGuard": true
  }
}
```

#### Response (`201 Created`):
```json
{
  "id": "disc_01j9a123",
  "status": "DRAFT",
  "message": "Discussion created successfully"
}
```

---

### 3.3 Start / Pause / Stop Deliberation
- `POST /api/debates/{id}/start`: Initiates or resumes the debate turn loop.
- `POST /api/debates/{id}/pause`: Pauses deliberation cleanly after the current turn completes.
- `POST /api/debates/{id}/stop`: Immediately stops deliberation and requests final summary synthesis.

---

## 4. Live Human Interventions

### 4.1 Queue Comment for Next Round
`POST /api/debates/{id}/comment`

Queues a comment into the orchestrator's thread-safe interjection queue. It will be drained and injected as a user prompt turn before the next round begins.

#### Request:
```json
{
  "content": "Assume our infrastructure budget is capped at $5,000/month."
}
```

#### Response (`200 OK`):
```json
{
  "status": "QUEUED",
  "position": 1
}
```

---

### 4.2 Immediate Interrupt
`POST /api/debates/{id}/interrupt`

Immediately cancels the in-flight generation context of the active agent, safely finalizes the partial message, and forces the user's interjection to be processed immediately.

#### Request:
```json
{
  "content": "Stop. Do not consider Cassandra; we have already selected PostgreSQL."
}
```

#### Response (`200 OK`):
```json
{
  "status": "INTERRUPTED",
  "turn_finalized": true
}
```

---

## 5. Alternative Deliverables

### 5.1 Generate Alternative Deliverable
`POST /api/debates/{id}/deliverable`

Prompts the moderator model to synthesize an alternative deliverable format based on the full debate transcript.

#### Request:
```json
{
  "format": "DECISION_MATRIX"
}
```

*Valid formats: `ACTION_PLAN`, `DECISION_MATRIX`, `PRO_CON_LIST`, `EXECUTIVE_BRIEF`, `DECISION_SUMMARY`, `CUSTOM_FORMAT`.*

#### Response (`202 Accepted`):
```json
{
  "status": "SYNTHESIZING",
  "format": "DECISION_MATRIX"
}
```

---

## 6. Real-Time Server-Sent Events (SSE) Stream

### `GET /api/debates/{id}/stream`
Streams real-time events from the turn orchestrator.

#### Headers Required:
```http
Accept: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

### Event Specifications

#### 1. `event: debate_started`
Fired when the debate state machine initializes.
```
event: debate_started
data: {"id":"disc_01j9a123","timestamp":1758067200}
```

#### 2. `event: agent_turn_started`
Fired when an agent begins formulating a turn.
```
event: agent_turn_started
data: {"seat_index":0,"provider":"ANTHROPIC","model":"claude-3-7-sonnet","role":"The Facilitator","round":1}
```

#### 3. `event: agent_token_chunk`
Fired for every raw text delta streamed from the model or CLI.
```
event: agent_token_chunk
data: {"seat_index":0,"delta":"When evaluating Apache Pulsar, "}
```

#### 4. `event: agent_turn_completed`
Fired when an agent turn finishes.
```
event: agent_turn_completed
data: {"seat_index":0,"message_id":"msg_01j9a","token_count":342,"duration_ms":1820}
```

#### 5. `event: consensus_reached`
Fired when agents reach consensus or when the final round completes.
```
event: consensus_reached
data: {"consensus_score":0.92,"synthesizer_seat":0}
```

#### 6. `event: deliverable_generated`
Fired when the Moderator Consensus Outcome Bubble or an alternative deliverable is produced.
```
event: deliverable_generated
data: {"format":"CONSENSUS_OUTCOME","markdown_content":"# Consensus Outcome\n..."}
```

#### 7. `event: error`
Fired when a provider returns a rate limit, authentication error, or timeout.
```
event: error
data: {"code":"RATE_LIMIT_EXCEEDED","provider":"OPENAI","message":"RPM limit reached. Retrying in 12s."}
```

---

## 📄 License

Dialex AI is licensed under the [PolyForm Noncommercial License 1.0.0](file:///LICENSE).  
Copyright (c) 2026 Kolta Labs. Free for personal, academic, and noncommercial research. Commercial deployments require an enterprise license from Kolta Labs.
