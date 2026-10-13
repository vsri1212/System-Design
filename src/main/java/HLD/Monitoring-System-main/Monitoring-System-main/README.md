# Monitoring System — Time-Series & High-Write High-Level Design

A senior/principal-level HLD case study for designing a large-scale monitoring and metrics platform similar in spirit to Prometheus-style collection plus horizontally scalable time-series storage and alerting.

The core idea is:

> Monitoring systems are write-heavy distributed databases with strict latency expectations for recent data, high-cardinality failure modes, and alerting paths that must remain trustworthy precisely when production is failing.

---

## 1. Problem Statement

Design a monitoring platform that can ingest, store, query, aggregate, and alert on time-series metrics from millions of applications, hosts, containers, and services.

The system should support:

- counters
- gauges
- histograms
- summaries/quantiles where appropriate
- labels/tags
- dashboards
- ad-hoc queries
- alert rules
- retention tiers
- rollups/downsampling
- multi-region ingestion
- tenant isolation

---

## 2. Functional Requirements

Core ingestion API:

```http
POST /api/v1/metrics
```

Example sample:

```json
{
  "metric": "http_requests_total",
  "labels": {
    "service": "checkout",
    "region": "ap-south-1",
    "status": "500"
  },
  "timestamp": 1786544400000,
  "value": 1
}
```

Query examples:

```text
rate(http_requests_total{service="checkout"}[5m])
```

Alert rule example:

```text
error_rate > 5% for 10m
```

---

## 3. Non-Functional Requirements

- extremely high write throughput
- low query latency for recent data
- durable ingestion
- high availability
- bounded ingestion lag
- compression efficiency
- scalable retention
- tenant isolation
- cardinality protection
- reliable alerting
- multi-region resilience
- observability of the monitoring system itself

---

## 4. Metrics vs Logs vs Traces

Metrics:

```text
numeric time-series
low payload size
high aggregation value
```

Logs:

```text
unstructured/semi-structured events
higher payload volume
```

Traces:

```text
request-level causal spans
```

This design focuses on **metrics/time-series**, though integrations with logs and traces are useful.

---

## 5. Core Data Model

A time series is uniquely identified by:

```text
metric name + complete label set
```

Example:

```text
http_requests_total{
  service="checkout",
  region="ap-south-1",
  status="500"
}
```

Each series contains ordered samples:

```text
(timestamp, value)
```

---

## 6. High-Level Architecture

```text
Agents / Exporters
      |
      v
Ingestion Gateway
      |
      v
Partition Router
      |
      v
WAL / Durable Buffer
      |
      v
Ingestion Workers
      |
      v
Time-Series Storage
      |
      +--> Recent Store
      +--> Compacted Blocks
      +--> Object Storage
      |
      v
Query Frontend
      |
      v
Query Workers / Aggregators
      |
      v
Dashboards

Alert Rules -> Alert Evaluator -> Notification Router
```

---

## 7. Pull vs Push Collection

Pull model:

```text
monitoring system scrapes targets
```

Advantages:

- central control of scrape interval
- target health visible via scrape failure
- easier service discovery integration

Push model:

```text
applications/agents push metrics
```

Advantages:

- useful for short-lived jobs
- easier across some network boundaries

Large platforms often support both through agents/gateways.

---

## 8. Scrape Agent / Collector

A local or regional agent can:

- scrape targets
- batch samples
- compress payloads
- retry transient failures
- buffer temporarily
- enrich labels

This protects applications from directly coupling to central storage.

---

## 9. Service Discovery

Pull collectors need to discover targets dynamically.

Sources may include:

- Kubernetes
- VM registry
- service registry
- static configuration

Service discovery changes should not require restarting the whole monitoring fleet.

---

## 10. Scrape Interval

Examples:

```text
5s
15s
60s
```

Shorter interval:

- better resolution
- much higher write volume

Longer interval:

- cheaper
- lower diagnostic fidelity

---

## 11. Capacity Estimation

Assume:

```text
10M active series
1 sample / 15 sec
```

Average ingest:

```text
10M / 15 ≈ 666K samples/sec
```

At 100M active series:

```text
~6.67M samples/sec
```

The dominant design factors are series cardinality, sample rate, replication, compression, and retention.

---

## 12. Write Amplification

One sample may cause:

- WAL append
- in-memory head update
- replication
- index metadata update
- block flush
- compaction
- object-store upload

Raw sample rate is only the beginning of capacity planning.

---

## 13. Ingestion Gateway

Responsibilities:

- authentication
- tenant resolution
- request validation
- rate limiting
- decompression
- batching
- routing

Keep expensive query work away from ingestion nodes.

---

## 14. Tenant Isolation

Every sample belongs to tenant/project/account.

Enforce:

- samples/sec quota
- active-series quota
- label count limits
- label value length
- query resource limits

One tenant must not be able to destroy the whole platform with cardinality explosion.

---

## 15. Cardinality

Cardinality means number of unique time series.

Dangerous label:

```text
userId
requestId
UUID
```

Example:

```text
http_requests{userId="..."}
```

can create millions/billions of unique series.

---

## 16. Cardinality Explosion

Suppose labels:

```text
100 services
20 regions
10 status codes
1M user IDs
```

Potential series:

```text
100 * 20 * 10 * 1M = 20B
```

This is catastrophic.

---

## 17. Cardinality Protection

Use:

- active-series limits
- per-metric series limits
- label denylist/policies
- cardinality dashboards
- ingestion rejection before storage saturation

---

## 18. Series Fingerprint

Hash normalized label set:

```text
seriesId = hash(metricName + sortedLabels)
```

Used for routing and lookup.

Hash collisions require a strategy if the hash is not sufficiently wide or if exact identity matters.

---

## 19. Partitioning

Natural shard key:

```text
tenantId + seriesId
```

This keeps samples for one series ordered/local while distributing different series broadly.

---

## 20. Why Not Partition by Timestamp Alone?

Current timestamp receives nearly all writes.

Result:

```text
one hot partition/window
```

Partition by series first, time within each shard/block.

---

## 21. Time Partitioning Within a Series

Store samples in time blocks:

```text
series X
  block 10:00-12:00
  block 12:00-14:00
```

This simplifies retention and compaction.

---

## 22. Write-Ahead Log (WAL)

Before acknowledging ingestion:

```text
append samples to WAL
```

If process crashes, replay WAL to rebuild recent in-memory state.

---

## 23. Why WAL?

Memory is fast but volatile.

WAL gives:

```text
fast sequential durable append
+
recoverable in-memory head
```

---

## 24. WAL Segment

Split WAL into segments.

Old segments can be removed after contained samples are safely persisted into immutable storage blocks.

---

## 25. Replicated Ingestion

Options:

```text
replicate WAL synchronously
or
write to quorum/replicated log
```

Stronger durability increases ingestion latency.

---

## 26. Kafka / Durable Buffer

Another architecture:

```text
Gateway -> Kafka -> TSDB ingesters
```

Benefits:

- burst absorption
- replay
- decoupled storage workers

Trade-offs:

- extra hop/latency
- another large distributed system to operate

---

## 27. Direct WAL vs Kafka

Direct WAL:

- lower latency
- simpler hot path

Kafka buffer:

- stronger decoupling
- easier replay across storage consumers

Both are valid depending on scale and operational goals.

---

## 28. In-Memory Head Block

Recent samples stay in memory for fast ingestion and queries.

Periodically seal the head into immutable block.

---

## 29. Immutable Blocks

Block may contain:

```text
series metadata
compressed samples
index
min/max time
checksums
```

Immutable design simplifies replication, caching, and object-storage placement.

---

## 30. Block Flush

Flow:

```text
WAL + in-memory head
-> seal block
-> write local durable block
-> upload/replicate
-> remove obsolete WAL segments
```

---

## 31. Time-Series Compression

Metrics compress extremely well because timestamps and values are often similar.

Techniques may include:

- delta timestamps
- delta-of-delta timestamps
- XOR floating-point compression
- dictionary compression for labels

---

## 32. Delta-of-Delta Timestamp Compression

Regular scrape intervals mean timestamp deltas change little.

Instead of storing full timestamps, encode tiny changes between deltas.

---

## 33. XOR Value Compression

Adjacent floating-point values often have similar bit patterns.

XOR-based encoding can store only changed bits efficiently.

---

## 34. Label Index

Queries often filter by labels:

```text
service="checkout"
region="india"
```

Maintain inverted index:

```text
label=value -> matching series IDs
```

---

## 35. Metadata Index vs Sample Storage

Separate:

```text
series/label lookup
```

from:

```text
compressed sample blocks
```

Different access patterns benefit from different structures.

---

## 36. Recent vs Historical Storage

Recent data:

- frequently queried
- low-latency local SSD/memory

Historical data:

- cheaper object storage
- larger query latency acceptable

Use tiered storage.

---

## 37. Object Storage

Immutable blocks fit object storage well.

Benefits:

- durable
- cheap
- massive scale

Query workers can cache hot blocks locally.

---

## 38. Storage Tiering

Example:

```text
0-24h       -> hot SSD
1-30 days   -> warm object storage + cache
30d-1 year  -> downsampled cold storage
```

Exact tiers depend on product/SLA.

---

## 39. Retention

Retention policies may differ by tenant/tier.

Example:

```text
raw samples: 30 days
5-minute rollup: 1 year
hourly rollup: 5 years
```

---

## 40. Downsampling

Old raw samples are expensive and rarely need second-level resolution.

Convert:

```text
15-sec samples
-> 5-min aggregates
-> 1-hour aggregates
```

---

## 41. Rollup Fields

For each interval store:

```text
count
sum
min
max
last
```

For counters/histograms, choose aggregation semantics carefully.

---

## 42. Why Average Alone Is Dangerous

Average of averages may be mathematically wrong when bucket counts differ.

Store sum + count so higher-level averages remain correct.

---

## 43. Counter Semantics

Counter only increases except reset.

Example:

```text
requests_total
```

Query usually computes rate/delta over window.

---

## 44. Counter Reset

Application restarts:

```text
1000 -> 0
```

Query engine should detect reset rather than interpreting huge negative rate.

---

## 45. Gauge

Gauge can increase/decrease.

Examples:

```text
CPU usage
queue depth
memory used
```

---

## 46. Histogram

Histograms represent distribution buckets.

Useful for latency:

```text
<=10ms
<=50ms
<=100ms
<=500ms
```

Aggregatable across instances if bucket boundaries align.

---

## 47. Histograms vs Client-Side Quantiles

Precomputed quantiles often do not aggregate correctly across instances.

Histograms are usually easier to aggregate globally.

---

## 48. Exemplars

Metric point may link to trace ID/example request.

Useful for moving from:

```text
p99 latency spike
```

to a representative trace.

---

## 49. Out-of-Order Samples

Network retries/buffering may deliver timestamps out of order.

Options:

- reject too-old samples
- maintain bounded out-of-order window
- buffer/reorder

Supporting arbitrary old writes complicates immutable storage substantially.

---

## 50. Duplicate Samples

Collectors may retry after timeout.

Deduplicate using series + timestamp where semantics allow.

If two different values arrive for same series/timestamp, define deterministic conflict rule.

---

## 51. Future Timestamps

Bad client clocks may submit samples far in future.

Reject/quarantine beyond allowed skew.

Otherwise future data can distort dashboards/retention.

---

## 52. Clock Skew

Monitoring depends heavily on time.

Use synchronized clocks and tolerate bounded skew.

For distributed events, timestamp semantics should be explicit.

---

## 53. Query Flow

```text
Dashboard
   |
   v
Query Frontend
   |
   v
Parse / Plan
   |
   v
Series Index Lookup
   |
   v
Shard / Block Fan-Out
   |
   v
Partial Aggregation
   |
   v
Global Merge
   |
   v
Result
```

---

## 54. Query Frontend

Responsibilities:

- authentication
- query parsing
- caching
- splitting
- fan-out control
- retries
- limits

---

## 55. Query Splitting

Large query:

```text
last 90 days
```

Split by time range/shard and execute in parallel.

Avoid one worker loading months of data serially.

---

## 56. Query Fan-Out

A wildcard query may touch millions of series.

Example:

```text
{job=~".*"}
```

This can be extremely expensive.

Need query limits and cost estimation.

---

## 57. Query Admission Control

Limit by:

- max series scanned
- max time range
- max bytes
- max concurrency
- timeout

One dashboard should not starve ingestion.

---

## 58. Query Isolation

Separate resource pools for:

- interactive dashboards
- alerts
- batch reports

Alerts should not miss evaluation because someone opened a giant 1-year dashboard query.

---

## 59. Query Cache

Cache popular dashboard queries.

Key includes:

```text
query
step
rounded time range
tenant
```

---

## 60. Result Cache Alignment

If dashboard refreshes every 30 sec, round query boundaries where safe so requests reuse cache.

---

## 61. Chunk / Block Cache

Cache decompressed or raw object-store blocks locally on query workers.

Useful for repeated historical dashboard access.

---

## 62. Partial Aggregation

Instead of shipping every sample to coordinator:

```text
shards compute partial sum/count/min/max
```

Coordinator merges partials.

This reduces network transfer.

---

## 63. Query Tail Latency

Large query touches many shards.

Slowest shard can dominate total latency.

Use:

- replicas
- hedged requests selectively
- partial results where acceptable
- strict deadlines

---

## 64. Partial Results

For dashboards, partial data may be preferable to total error if clearly marked.

For alerting, semantics may require different handling.

Never silently hide missing shards.

---

## 65. Alerting Architecture

```text
Rule Store
   |
   v
Alert Scheduler
   |
   v
Alert Evaluators
   |
   v
Alert State Store
   |
   v
Notification Router
```

---

## 66. Alert Rule

Example:

```text
error_rate > 5%
FOR 10 minutes
```

States:

```text
INACTIVE
PENDING
FIRING
```

---

## 67. Why FOR Duration?

Without duration:

```text
one transient spike -> page engineer
```

`FOR` reduces noisy alerts.

---

## 68. Alert Evaluation Interval

Example:

```text
every 30 sec
```

Too frequent:

- expensive

Too infrequent:

- slower detection

---

## 69. Alert Rule Sharding

Partition rules by:

```text
tenant + rule group hash
```

Multiple evaluators process independent shards.

---

## 70. Alert Leader / Ownership

Only one active evaluator should own one rule group at a time to avoid duplicate notifications/state races.

Use lease/consistent shard ownership.

---

## 71. Alert State Durability

Persist:

```text
pendingSince
firing state
lastEvaluation
```

Otherwise evaluator restart resets `FOR 10m` timer unexpectedly.

---

## 72. Alert Deduplication

The same alert may be evaluated repeatedly while firing.

Notification router groups/deduplicates by alert identity.

---

## 73. Alert Grouping

Instead of 10K pages for one outage:

```text
group by service/cluster
```

Send one meaningful notification.

---

## 74. Alert Inhibition

If root-cause alert fires:

```text
datacenter unreachable
```

suppress child alerts:

```text
1000 services unavailable
```

Reduces alert storms.

---

## 75. Alert Silences

Maintenance window can silence selected rules/labels temporarily.

Silence configuration must be auditable and expire automatically.

---

## 76. Notification Delivery

Channels:

- email
- SMS
- push
- Slack/chat
- PagerDuty-style systems

Use retry/idempotency because external providers fail.

---

## 77. Alerting Must Be Isolated

During a production outage, monitoring query volume and alert volume rise.

If alerting shares every resource with dashboard queries, alerts may fail exactly when needed.

Reserve capacity.

---

## 78. Recording Rules

Precompute expensive query expressions periodically.

Example:

```text
service:error_rate:5m
```

Dashboards/alerts query precomputed series instead of repeatedly scanning raw metrics.

---

## 79. Why Recording Rules Matter

Expensive aggregation across millions of series every 15 seconds is wasteful.

Precompute reusable results.

---

## 80. Stream Aggregation

High-volume raw metrics can be aggregated before long-term storage.

Example:

```text
per-host request counters
-> per-service regional counters
```

Trade-off:

less storage, less drill-down detail.

---

## 81. Pre-Aggregation Strategy

Keep raw recent data but store aggregate long-term.

This preserves debugging depth for recent incidents and lowers historical cost.

---

## 82. Compaction

Merge many small immutable blocks into larger blocks.

Benefits:

- fewer object-store reads
- better compression
- faster queries

---

## 83. Compaction Levels

Example:

```text
2-hour blocks
-> 12-hour blocks
-> 1-day blocks
```

Do compaction in background.

---

## 84. Compaction Backpressure

If compaction falls behind:

- block count grows
- query fan-out increases
- storage overhead rises

Monitor compaction debt.

---

## 85. Compaction Isolation

Compaction is IO/CPU heavy.

Do not let it starve ingestion or alerting.

Use dedicated workers/quotas.

---

## 86. Retention Deletion

Immutable blocks older than retention can be deleted by metadata/compactor process.

Object-store lifecycle rules can be additional safety mechanism.

---

## 87. Index Retention

When deleting blocks, remove corresponding series/index metadata carefully.

Do not leave unbounded orphan metadata.

---

## 88. Active Series Index

Recent active series need fast existence lookup.

Store in memory/distributed metadata layer.

Inactive historical series can remain only in immutable block indexes.

---

## 89. Metadata Cardinality Cost

Even if each sample compresses well, 100M unique series consume enormous metadata/index RAM.

Cardinality is often more dangerous than raw sample bytes.

---

## 90. Hot Tenant

One tenant emits 50% of total samples.

Hash sharding spreads series, but tenant can still dominate global resources.

Use tenant quotas and weighted fairness.

---

## 91. Hot Series

One series receives huge update rate.

Example:

```text
global_counter
```

One shard can become hot.

Metrics should generally be emitted at distributed dimensions and aggregated later rather than funneling millions of writers into one exact series.

---

## 92. Push Gateway for Short-Lived Jobs

Short batch job may finish before next scrape.

It can push final metrics to an intermediate gateway.

Do not turn gateway into permanent storage for every long-running service.

---

## 93. Remote Write

Regional monitoring cluster can forward samples to central long-term system.

```text
Regional collector -> remote-write queue -> central storage
```

Use buffering and retries.

---

## 94. Remote Write Backpressure

Central endpoint slow:

```text
regional buffer grows
```

Need:

- bounded disk spool
- lag metrics
- drop policy for noncritical samples when full
- source throttling where possible

---

## 95. Data Loss Policy

Monitoring data is important, but the monitored production system is usually more important.

Agent must not exhaust application disk/memory trying to preserve infinite telemetry backlog.

Bound telemetry buffers.

---

## 96. Sampling / Drop Priority

During overload, prefer preserving:

- critical SLO metrics
- error metrics
- alert inputs

before low-value high-cardinality debug metrics.

---

## 97. Multi-Region Ingestion

Each region collects locally:

```text
India apps -> India collectors
US apps -> US collectors
```

This reduces cross-region dependency for basic observability.

---

## 98. Regional Autonomy

A region should retain enough recent metrics and alerts to operate during WAN partition.

Central global aggregation can be asynchronous.

---

## 99. Global Queries

Global dashboard queries may fan out to regional stores or query a replicated central long-term layer.

Trade-off:

- freshness
- WAN cost
- complexity

---

## 100. Cross-Region Replication

Immutable blocks can replicate asynchronously to global object storage.

Recent samples may have non-zero RPO unless synchronous replication is used.

---

## 101. Alert Locality

Critical regional alerts should evaluate locally, not depend on cross-region central service.

Otherwise WAN outage can blind monitoring of the same outage.

---

## 102. Multi-Region Failure

If central storage is unavailable:

- keep regional ingest running
- keep local alerts running
- spool remote writes within bounds
- delay global dashboards

Observability should degrade, not disappear entirely.

---

## 103. WAL Recovery

Ingester crashes.

On restart:

```text
load latest persisted block
replay WAL
restore head
```

Then resume ingestion.

---

## 104. WAL Corruption

Use checksums/segment boundaries.

Recover valid prefix, quarantine corrupt tail, alert operators.

Do not silently fabricate samples.

---

## 105. Node Failure

Shard replica fails:

- reroute ingestion to healthy replica/owner
- rebuild missing replica from WAL/blocks/object storage

Avoid cluster-wide rebalancing storm.

---

## 106. Rebalancing

Moving many series shards creates network/memory churn.

Rebalance gradually.

Consistent/rendezvous hashing can limit movement.

---

## 107. Replica Strategy

Recent mutable head may need replicated ownership.

Immutable historical blocks can rely on durable object storage plus cache replicas.

Different data ages can use different redundancy strategies.

---

## 108. Quorum Trade-Off

Synchronous multi-replica ACK:

- stronger durability
- higher latency/lower availability

Async:

- faster
- some recent metric loss possible

For monitoring telemetry, small RPO may be acceptable if explicitly defined.

---

## 109. Ingestion Ordering

Samples for one series should usually be processed approximately by timestamp order.

But strict global ordering across series is unnecessary.

This allows enormous horizontal scale.

---

## 110. Idempotency

Collector retries may resend same batch.

Storage should tolerate duplicate `(series, timestamp)` samples according to defined semantics.

Batch request IDs can also help ingestion dedupe.

---

## 111. Batch Ingestion

Never send one HTTP request per metric sample at high scale.

Batch thousands of samples and compress payloads.

---

## 112. Compression on Wire

Metrics batches compress strongly because label names and timestamps repeat.

Use efficient binary protocol/compression where scale justifies it.

---

## 113. Label Dictionary Encoding

Repeated label names/values can be dictionary encoded inside blocks/batches.

Reduces storage significantly.

---

## 114. Query Language

Need operators for:

- label filtering
- range vectors
- aggregation
- rates
- arithmetic
- functions

Query planner should estimate cost before execution.

---

## 115. Rate Calculation

For counter:

```text
rate(requests_total[5m])
```

requires reading samples over range and handling resets.

---

## 116. Percentiles from Histograms

Approximate p95/p99 from histogram bucket distribution.

Accuracy depends on bucket layout.

---

## 117. Dashboard Architecture

Dashboard stores panel definitions, not metric data.

```text
Dashboard UI -> Query API -> TSDB
```

Dashboards are metadata/configuration objects.

---

## 118. Dashboard Refresh Storm

10K users open same dashboard refreshing every 10 sec.

Use query caching and request coalescing.

---

## 119. Alert Storm Scenario

One regional outage causes 100K alerts.

Use:

- grouping
- deduplication
- inhibition
- notification rate limiting

Do not overwhelm humans/provider APIs.

---

## 120. Self-Monitoring

The monitoring system must monitor itself.

Key questions:

- Are samples being dropped?
- Is ingest lag growing?
- Are alerts delayed?
- Is cardinality exploding?
- Is object storage unavailable?

Use independent/minimal health checks to avoid complete circular dependency.

---

## 121. Meta-Monitoring

Critical monitoring health may be observed by a smaller independent monitoring stack.

Otherwise one total monitoring outage cannot alert on itself.

---

## 122. Security

- TLS
- tenant authentication
- RBAC
- encryption at rest
- query authorization
- secrets redaction
- audit logs

Metric labels can accidentally contain sensitive data; enforce policies.

---

## 123. PII in Labels

Do not put:

```text
email
phone
user token
```

into metric labels.

Besides privacy risk, these create huge cardinality.

---

## 124. Schema Governance

Metrics are schemaless-looking but need governance:

- naming conventions
- label conventions
- ownership metadata
- deprecation policy

Otherwise observability becomes unusable.

---

## 125. Metric Naming

Good:

```text
http_requests_total
http_request_duration_seconds
```

Avoid encoding dynamic values into metric names.

---

## 126. SLO Metrics

Monitoring system should support core golden signals:

```text
latency
traffic
errors
saturation
```

and SLI/SLO evaluation.

---

## 127. Error Budget Burn Alerts

Instead of only static threshold:

```text
error_rate > 5%
```

alert on SLO burn rate across multiple windows.

This better connects paging to user impact.

---

## 128. Cost Control

Main cost drivers:

- active series
- scrape frequency
- retention
- replication
- query scans

Expose cost/cardinality by tenant/team.

---

## 129. Downsampling Trade-Off

Once raw data is discarded, detail cannot be reconstructed.

Choose retention tiers based on incident/debugging needs.

---

## 130. Delete / Compliance

Deleting a metric/tenant may require removing:

- recent head
- WAL references
- immutable blocks
- object-store copies
- indexes

Large-scale deletion can be asynchronous with tombstones/manifests.

---

## 131. Query Consistency

Recent query may see data from memory while historical data comes from immutable blocks.

Query layer merges both, deduplicating overlap around block boundary.

---

## 132. Recent Data Gaps

If one ingester is temporarily unavailable, dashboard may have gap.

Query replica or return partial result with explicit warning depending policy.

---

## 133. Compaction and Duplicate Blocks

Retries can upload duplicate overlapping blocks.

Compactor/query layer needs block IDs/manifests and dedup rules.

---

## 134. Object Store Eventual Behavior

Do not depend on object listing alone for critical metadata state.

Use explicit block metadata/catalog if required by storage semantics.

---

## 135. Block Manifest

Track:

```text
blockId
minTime
maxTime
tenant
shard
checksum
compactionLevel
```

Query planner uses manifest to locate relevant blocks.

---

## 136. Bloom Filters / Index Acceleration

Blocks can contain probabilistic/index metadata to quickly skip blocks that cannot contain matching series.

Useful for historical queries.

---

## 137. Query Cost Example

Query:

```text
sum(rate(requests_total[5m])) by (service)
```

If labels narrow to 5K series, manageable.

Query without selectors may scan 100M series and must be limited/preaggregated.

---

## 138. Recording Rule vs Ad-Hoc Query

Frequently repeated expensive query:

```text
precompute
```

Rare diagnostic query:

```text
run ad hoc within limits
```

---

## 139. Failure Isolation

Separate clusters/pools for:

- ingest
- query
- compaction
- alert evaluation

Shared storage can remain, but resource exhaustion should not cascade across all paths.

---

## 140. Graceful Degradation

During overload:

- reject new high-cardinality series
- drop lowest-priority telemetry
- reduce dashboard concurrency
- serve cached historical results
- delay compaction

Protect:

- critical ingestion
- alert inputs
- alert evaluation

---

## 141. Backpressure

If storage workers cannot keep up:

```text
buffer lag grows
```

Apply:

- bounded queues
- tenant throttling
- drop policies
- scale-out where downstream supports it

Infinite buffering is not a strategy.

---

## 142. Retry Storm

Ingestion endpoint slows.

Thousands of agents retry at fixed interval.

Use:

- exponential backoff
- jitter
- server Retry-After
- local bounded buffering

---

## 143. Principal Engineer Failure Scenario

Assume platform normally handles:

```text
8M samples/sec
60M active series
```

During a major production incident:

```text
sample rate jumps to 14M/sec
one team accidentally adds requestId as a label
active series climbs toward 200M
object-store p99 rises to 5 sec
query traffic triples
10K alert rules begin firing
Kafka/WAL backlog grows
```

A weak system may collapse:

```text
cardinality explosion
-> metadata memory exhaustion
-> ingestion retries
-> more load
-> compaction lag
-> query fan-out explosion
-> alert queries delayed
-> monitoring blind during outage
```

Principal-level response:

1. enforce hard per-tenant active-series/cardinality quotas immediately
2. reject/drop the pathological new series before metadata exhaustion
3. preserve existing critical series and SLO metrics
4. isolate alert evaluators from dashboard query traffic
5. rate-limit ad-hoc/historical queries
6. serve historical blocks from cache where possible while object storage is slow
7. extend but bound ingestion spool/WAL headroom
8. apply exponential backoff/jitter to collectors
9. scale ingestion only if storage/buffer layers can absorb it
10. pause/deprioritize compaction if it competes with ingest, while watching compaction debt
11. group/inhibit duplicate alerts to prevent notification storm
12. use recording rules for repeated expensive alert expressions
13. protect regional alerting even if central/global components degrade
14. monitor dropped samples explicitly
15. remove offending label at source and expire high-cardinality series gradually
16. recover query capacity only after active-series and ingest lag stabilize

Core principle:

> A monitoring system must survive the exact moment when telemetry volume, query volume, and alert volume all spike together. Cardinality controls and resource isolation are correctness features, not optional optimizations.

---

## 144. Production Architecture

```text
                         Applications / Hosts
                                 |
                          Agents / Exporters
                                 |
                       Regional Collectors
                                 |
                          Ingestion Gateway
                                 |
                      Auth / Quota / Validation
                                 |
                       Partition / Series Router
                                 |
                       WAL / Kafka Buffer
                                 |
                   +-------------+-------------+
                   |                           |
                   v                           v
               Ingester A                  Ingester B
                   |                           |
                   +-------------+-------------+
                                 |
                        Recent Head Storage
                                 |
                          Immutable Blocks
                                 |
                        Object Storage Tier
                                 |
                 +---------------+----------------+
                 |                                |
                 v                                v
          Query Frontend                    Alert Scheduler
                 |                                |
          Query Workers                    Alert Evaluators
                 |                                |
          Cache / Aggregation               Alert State Store
                 |                                |
                 v                                v
             Dashboards                  Notification Router
```

---

## 145. Interview Follow-Up Questions

Be prepared for:

- Metrics vs logs vs traces?
- Pull vs push monitoring?
- What defines a time series?
- Why is cardinality dangerous?
- How do you partition time-series data?
- Why use a WAL?
- Kafka buffer vs direct WAL?
- How does time-series compression work?
- Why immutable blocks?
- How do you handle out-of-order samples?
- How do counters differ from gauges?
- Why are histograms more aggregatable than client-side quantiles?
- How do you design query fan-out?
- How do recording rules help?
- How does alert `FOR` state work?
- How do you prevent alert storms?
- How do downsampling/retention tiers work?
- How do you handle multi-region monitoring?
- How do you keep alerts working during a monitoring outage?
- What happens during a cardinality explosion?

---

## 146. Interview Summary

The transferable concepts in this design are:

```text
Time-series data model
Pull / push collection
Series cardinality
Series fingerprinting
WAL
Durable buffering
High-write sharding
Immutable blocks
Time-series compression
Label inverted indexes
Hot / warm / cold storage
Retention
Downsampling / rollups
Histograms
Out-of-order samples
Query fan-out
Partial aggregation
Query caching
Recording rules
Alert evaluation
Alert state / dedup / inhibition
Compaction
Backpressure
Tenant isolation
Multi-region observability
Meta-monitoring
Graceful degradation
```

A strong HLD answer should emphasize that a monitoring platform has **three independently critical planes**:

```text
1. Ingestion plane — never collapse under write/cardinality spikes
2. Query plane — efficiently scan and aggregate huge time ranges
3. Alerting plane — remain trustworthy and isolated during production incidents
```

The most important Principal-level insight is that **cardinality, not just sample throughput, determines whether a metrics platform survives at scale**.
