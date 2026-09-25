package benchmark

// BundledDialexBench10 returns the 10 canonical architectural dilemma benchmark test cases.
func BundledDialexBench10() []BenchmarkCase {
	return []BenchmarkCase{
		{
			ID:      "DB01",
			Title:   "High-Throughput Ingestion Engine: SQLite WAL vs RocksDB vs Postgres",
			Domain:  "STORAGE_CONCURRENCY",
			Dilemma: "Design a local telemetry ingestion engine sustaining 25,000 writes/sec with sub-5ms p99 latency on constrained edge hardware (4 cores, 8GB RAM).",
			Constraints: []string{
				"Maximum persistent disk footprint: 20GB",
				"Must support ACID guarantees on sudden power loss",
				"Zero external daemon processes allowed",
			},
			GroundTruthTraps: []string{
				"Asserting that SQLite WAL mode supports concurrent multiple writers without SQLITE_BUSY",
				"Ignoring fsync disk I/O bottlenecks when durability is set to synchronous=FULL",
				"Recommending PostgreSQL despite the constraint forbidding external daemon processes",
			},
			RequiredTradeOffAxes: []string{
				"Write amplification vs Read amplification (LSM-tree vs B-Tree)",
				"Memory allocation under spike saturation",
				"Crash recovery time and WAL checkpointing latency spikes",
			},
			MandatoryBoundaryConditions: []string{
				"Sudden SIGKILL or power outage handling without corrupting partial pages",
				"Compaction stalls under continuous write saturation",
			},
			IsBundled: true,
		},
		{
			ID:      "DB02",
			Title:   "Multi-Region Distributed Consensus: Raft vs Multi-Paxos across WAN",
			Domain:  "DISTRIBUTED_CONSENSUS",
			Dilemma: "Select and specify a consensus algorithm for an active-active cluster spanning US-East, EU-Central, and AP-East with cross-ocean round-trip latencies of 80ms to 180ms.",
			Constraints: []string{
				"Tolerate complete loss of any single geographic region without service downtime",
				"Read latency for strong consistency must remain under 10ms in the local region",
				"Network partitions between regions must never produce split-brain writes",
			},
			GroundTruthTraps: []string{
				"Claiming Raft leader leases guarantee strong consistency across WAN without synchronized physical clocks or read-index RPCs",
				"Assuming Raft can run sub-10ms writes across WAN without pipelined or multi-leader variants",
				"Ignoring CAP theorem boundaries by claiming CA properties across WAN partitions",
			},
			RequiredTradeOffAxes: []string{
				"Leader bottleneck in standard Raft vs Leaderless consensus overhead (EPaxos)",
				"Read-index round-trips vs synchronized clock TrueTime drift bounds",
				"Quorum membership reconfiguration overhead during WAN fiber cuts",
			},
			MandatoryBoundaryConditions: []string{
				"Asymmetric network partition where Node A can talk to B, but not C",
				"Leader flapping caused by sporadic WAN packet loss spikes",
			},
			IsBundled: true,
		},
		{
			ID:      "DB03",
			Title:   "Zero-Trust Microservice Auth: Stateless JWTs vs mTLS & SPIFFE/SPIRE",
			Domain:  "SECURITY_AUTH",
			Dilemma: "Architect an internal service-to-service and user-to-service authentication architecture across 120 Kubernetes microservices subject to instant token revocation requirements.",
			Constraints: []string{
				"Sub-1ms overhead added to inter-service RPC hops",
				"Compromised credential must be revokable globally within 5 seconds",
				"Must survive Redis/central session store outage without shutting down traffic",
			},
			GroundTruthTraps: []string{
				"Claiming purely stateless JWTs can achieve instant (5-second) revocation without centralized state or bloom filters",
				"Underestimating mTLS cryptographic handshake latency without session resumption or connection pooling",
				"Relying solely on Redis blacklist lookups that violate the resilience constraint during Redis outages",
			},
			RequiredTradeOffAxes: []string{
				"Stateless verification speed vs Centralized revocation propagation latency",
				"Cryptographic signature verification CPU load at 100k QPS",
				"Short-lived token refresh storm impact on identity provider",
			},
			MandatoryBoundaryConditions: []string{
				"Identity Provider (IdP) complete outage for 15 minutes",
				"Malicious insider token theft with active token TTL remaining",
			},
			IsBundled: true,
		},
		{
			ID:      "DB04",
			Title:   "Event-Driven Financial Ledger: Out-of-Order Delivery & Idempotency",
			Domain:  "FINTECH_LEDGER",
			Dilemma: "Design a high-volume double-entry financial settlement pipeline processing $50M/day where message brokers (Kafka/RabbitMQ) guarantee at-least-once delivery with occasional out-of-order delivery.",
			Constraints: []string{
				"Zero double-crediting or balance drift permitted under any circumstances",
				"Processing throughput: 5,000 settlement operations/sec",
				"Audit ledger must be cryptographically immutable and tamper-evident",
			},
			GroundTruthTraps: []string{
				"Believing Kafka partition ordering guarantees ordering across producer retries without idempotence enabled (enable.idempotence=true and max.in.flight=1)",
				"Using floating-point decimals for currency calculations instead of arbitrary-precision fixed decimals (Int64/BigInt)",
				"Relying on database UPSERTs without transactional balance invariant verification",
			},
			RequiredTradeOffAxes: []string{
				"Optimistic concurrency control (version checks) vs Pessimistic row locking on hot accounts",
				"Saga pattern compensation logic vs Two-Phase Commit (2PC) latency penalties",
				"Event sourcing append-only log storage costs vs Snapshot compaction intervals",
			},
			MandatoryBoundaryConditions: []string{
				"Duplicate webhook delivery arriving 4 hours after initial transaction",
				"Concurrent balance debits depleting account from multiple edge servers simultaneously",
			},
			IsBundled: true,
		},
		{
			ID:      "DB05",
			Title:   "Real-Time Telemetry Event Bus: Kafka vs Apache Pulsar vs NATS JetStream",
			Domain:  "MESSAGING_STREAMING",
			Dilemma: "Select a real-time event messaging backbone for IoT telemetry ingesting 100,000 events/sec with dynamic per-device topic creation (up to 500,000 distinct topics).",
			Constraints: []string{
				"Support 500,000 independent topics without broker memory crash",
				"Strict retention of 7 days with tiered object storage offloading",
				"Low operational maintenance for a 3-engineer DevOps team",
			},
			GroundTruthTraps: []string{
				"Recommending standard Apache Kafka for 500,000 topics without acknowledging metadata/partition file descriptor collapse",
				"Downplaying Apache Pulsar operational complexity (requires BookKeeper, ZooKeeper, and Pulsar brokers)",
				"Claiming NATS JetStream supports unlimited multi-tiered S3 storage offloading out of the box like Pulsar",
			},
			RequiredTradeOffAxes: []string{
				"Compute/storage decoupled architecture vs Single-tier broker storage simplicity",
				"Operational maintenance footprint (ZooKeeper/BookKeeper dependencies) vs Scalability ceilings",
				"Consumer group rebalancing latency during autoscaling events",
			},
			MandatoryBoundaryConditions: []string{
				"Sudden influx of 50,000 new ephemeral topics within a 1-minute window",
				"Broker disk saturation reaching 95% threshold",
			},
			IsBundled: true,
		},
		{
			ID:      "DB06",
			Title:   "Multi-Tenant Vector Search Pipeline: Dedicated Cluster vs Shared Indexing",
			Domain:  "AI_ML_INFRA",
			Dilemma: "Design an enterprise RAG vector search backend hosting 2,000 corporate tenants, where tenant dataset sizes range from 1,000 to 5,000,000 vectors with strict data isolation.",
			Constraints: []string{
				"Absolute zero data leakage across tenant boundaries (SOC2/GDPR requirement)",
				"p95 query latency under 25ms for 1536-dimensional embeddings",
				"Cold tenant queries must not incur cold-start penalties over 100ms",
			},
			GroundTruthTraps: []string{
				"Relying purely on application-level filtering (metadata filter) in a shared index without index-level tenant namespace isolation",
				"Proposing a dedicated Milvus/Qdrant instance per tenant which would exhaust cluster memory at 2,000 tenants",
				"Ignoring HNSW graph memory explosion when building separate in-memory indexes per small tenant",
			},
			RequiredTradeOffAxes: []string{
				"Memory footprint of in-memory HNSW graphs vs Disk-backed IVFPQ search latency",
				"Strict cryptographic namespace separation vs Multi-tenant shared cluster hardware utilization",
				"Tenant-specific re-indexing costs during model embedding upgrades",
			},
			MandatoryBoundaryConditions: []string{
				"Large tenant bulk-inserting 1M vectors without starving queries of smaller tenants",
				"Malicious prompt injection attempting to modify tenant_id query filter",
			},
			IsBundled: true,
		},
		{
			ID:      "DB07",
			Title:   "Core Banking Database Migration: Zero-Downtime Sharding & Dual-Write",
			Domain:  "DATABASE_SHARDING",
			Dilemma: "Migrate an active 15TB single-node PostgreSQL database running 12,000 transactions/sec to a distributed sharded architecture (Citus or CockroachDB) with zero business downtime.",
			Constraints: []string{
				"Zero maintenance downtime windows allowed",
				"Maximum permissible data replication lag during migration: 500ms",
				"Instant 1-click fallback to legacy system in event of data mismatch",
			},
			GroundTruthTraps: []string{
				"Proposing dual-writes directly from application code without change data capture (CDC) or outbox pattern (leads to distributed dual-write inconsistency)",
				"Underestimating distributed transaction overhead in CockroachDB for multi-shard foreign key constraints",
				"Failing to account for sequence generation and unique constraint conflicts across shards",
			},
			RequiredTradeOffAxes: []string{
				"CDC replication (Debezium/Kafka) vs Shadow traffic dark-launching",
				"Distributed joins and cross-shard transaction latency vs Denormalization",
				"Rollback complexity if data corruption occurs 48 hours after cutover",
			},
			MandatoryBoundaryConditions: []string{
				"Replication network disconnect for 30 seconds during peak trading volume",
				"Reconciliation job detecting 10 out-of-sync ledger rows during shadow comparison",
			},
			IsBundled: true,
		},
		{
			ID:      "DB08",
			Title:   "Modular Monolith vs Distributed Microservices for Core Platform",
			Domain:  "SYSTEMS_ARCHITECTURE",
			Dilemma: "A growing engineering organization (80 engineers, 6 product squads) debates splitting an existing fast-growing monolith into 15 domain microservices or refactoring into a Modular Monolith.",
			Constraints: []string{
				"Squads must deploy features independently without release train blocking",
				"Infra cloud cost cannot increase by more than 20%",
				"Current 99.95% system uptime SLA must not degrade",
			},
			GroundTruthTraps: []string{
				"Assuming microservices automatically solve organizational coupling without domain-driven boundary discipline (leads to distributed monolith)",
				"Ignoring distributed tracing, telemetry, and network serialization CPU overhead of 15 microservices",
				"Claiming modular monoliths cannot support independent team releases without polyrepo tooling",
			},
			RequiredTradeOffAxes: []string{
				"Independent deployability vs Distributed debugging and transactional complexity",
				"Network serialization overhead (JSON/gRPC) vs In-memory pointer passing",
				"Infrastructure orchestration cost (Kubernetes/Istio) vs Monolithic CI build times",
			},
			MandatoryBoundaryConditions: []string{
				"One domain service experiencing an unhandled crash loop under high traffic",
				"Cross-boundary business transaction requiring atomicity across 3 squad domains",
			},
			IsBundled: true,
		},
		{
			ID:      "DB09",
			Title:   "Edge-Compute Low-Latency Cache: Stampede Prevention & Stale-While-Revalidate",
			Domain:  "CACHING_SYSTEMS",
			Dilemma: "Architect a global edge cache (Cloudflare Workers / Fastly Compute) fronting an expensive dynamic API experiencing 100,000 requests/sec with sudden cache invalidation thundering herds.",
			Constraints: []string{
				"Edge cache hit ratio must exceed 98%",
				"Stale content must never be served older than 60 seconds",
				"Origin API capacity is capped at 1,000 concurrent queries",
			},
			GroundTruthTraps: []string{
				"Relying on standard TTL expiration without single-flight mutex / request coalescing (causes origin collapse on expiration)",
				"Failing to purge edge cache nodes globally when an emergency data retraction occurs",
				"Assuming edge KV stores provide immediate global consistency across all POPs",
			},
			RequiredTradeOffAxes: []string{
				"Stale-while-revalidate eventual consistency vs Origin load protection",
				"Centralized Redis lock for cache refresh vs Decentralized edge single-flight deduplication",
				"Edge memory capacity limits vs Bandwidth egress billing",
			},
			MandatoryBoundaryConditions: []string{
				"Breaking news item causing a 50x traffic surge at the exact moment cache key expires",
				"Origin API returning HTTP 503 during a background cache revalidation attempt",
			},
			IsBundled: true,
		},
		{
			ID:      "DB10",
			Title:   "HIPAA-Compliant Cloud Data Lake: De-Identification & Audit Logging",
			Domain:  "COMPLIANCE_SECURITY",
			Dilemma: "Build a centralized healthcare analytics data lake ingesting 50 million electronic health records (EHR) supporting both researcher ad-hoc SQL queries and strict HIPAA Safe Harbor de-identification.",
			Constraints: []string{
				"All 18 HIPAA Safe Harbor identifiers must be deterministically masked/tokenized",
				"Full immutable tamper-proof access audit logging for 7 years",
				"Researcher cohort queries must finish in under 30 seconds across 10B rows",
			},
			GroundTruthTraps: []string{
				"Assuming standard pseudonymization/hashing preserves k-anonymity against re-identification linkage attacks",
				"Storing audit logs in mutable object storage without S3 Object Lock / WORM compliance",
				"Allowing researchers arbitrary free-text clinical notes access without NLP de-identification (PII leaks)",
			},
			RequiredTradeOffAxes: []string{
				"Cryptographic format-preserving tokenization vs Columnar query compression (Parquet/Iceberg)",
				"Row-level security enforcement latency vs Analytical scanning speed",
				"Granular audit event write amplification vs Storage costs",
			},
			MandatoryBoundaryConditions: []string{
				"Auditor demanding immediate cryptographic proof of zero tampering for a patient record from 5 years ago",
				"Data export request attempting differential privacy re-identification via demographic cross-matching",
			},
			IsBundled: true,
		},
	}
}

// GetBundledCaseByID retrieves a bundled case by ID or returns nil.
func GetBundledCaseByID(id string) *BenchmarkCase {
	cases := BundledDialexBench10()
	for i := range cases {
		if cases[i].ID == id {
			return &cases[i]
		}
	}
	return nil
}
