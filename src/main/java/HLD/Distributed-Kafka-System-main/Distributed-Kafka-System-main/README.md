# Distributed Kafka System — High-Level Design

A senior/principal-level HLD case study for designing a Kafka-style distributed commit log and messaging platform from first principles.

The core architectural idea is:

> Kafka is not primarily a queue. It is a durable, partitioned, replicated, append-only log that many independent consumers can read at their own pace.

---

## 1. Problem Statement

Design a distributed log system that supports:

- high-throughput producers
- durable append-only storage
- ordered partitions
- replicated brokers
- consumer groups
- replayable history
- independent offsets
- retention
- log compaction
- broker failure recovery
- controller/metadata management
- idempotent and transactional producers

The hard parts are balancing throughput, ordering, durability, availability, recovery, and consumer coordination.

---

## 2. Functional Requirements

Producers should be able to:

- publish records to topics
- choose keys
- receive acknowledgements
- retry safely

Consumers should be able to:

- subscribe to topics
- consume partitions
- commit offsets
- replay data
- work in consumer groups

Administrators should be able to:

- create topics
- configure partitions
- configure replication
- set retention
- inspect lag
- move replicas

---

## 3. Non-Functional Requirements

- very high throughput
- durable writes
- horizontal scalability
- ordered records within partitions
- fault tolerance
- efficient sequential disk IO
- consumer replay
- low replication overhead
- predictable failure recovery
- backpressure visibility
- strong observability

---

## 4. Core Concepts

```text
Cluster
Broker
Topic
Partition
Replica
Leader
Follower
Producer
Consumer
Consumer Group
Offset
Controller
ISR
```

---

## 5. High-Level Architecture

```text
Producers
   |
   v
Metadata Lookup
   |
   v
Partition Leader Broker
   |
   +--> Local Log
   +--> Follower Replicas
   |
   v
Committed Log
   |
   v
Consumers / Consumer Groups
```

---

## 6. Topic

A topic is a logical stream name.

Example:

```text
orders
payments
notifications
```

A topic is split into partitions for scalability.

---

## 7. Partition

A partition is an ordered append-only log.

Example:

```text
Offset 0 -> A
Offset 1 -> B
Offset 2 -> C
```

Ordering is guaranteed only within one partition.

---

## 8. Why Partition?

One broker cannot handle infinite throughput.

Partitions allow:

```text
Topic
  |
  +--> Partition 0 -> Broker A
  +--> Partition 1 -> Broker B
  +--> Partition 2 -> Broker C
```

This enables parallel writes and reads.

---

## 9. Partition Key

Producer may choose:

```text
key = orderId
```

Partitioner computes:

```text
hash(key) % partitionCount
```

All events for the same key land in the same partition, preserving key order.

---

## 10. Ordering Trade-Off

Need order per customer/order?

Use stable key.

Need maximum throughput with no ordering relationship?

Round-robin or sticky batching can spread records more evenly.

Ordering has a scaling cost.

---

## 11. Hot Partition Problem

Suppose:

```text
key = celebrityId
```

and that key receives huge traffic.

All records hit one partition.

Mitigations:

- better key design
- key salting where ordering can be relaxed
- split logical entity workload
- isolate hot tenants

You cannot preserve strict single-key order and simultaneously distribute that same key across independent partitions without additional coordination.

---

## 12. Append-Only Log

Writes append sequentially:

```text
segment file
  A
  B
  C
  D
```

Sequential disk IO is efficient and works well with OS page cache.

---

## 13. Log Segment

A partition is split into segment files:

```text
000000000000.log
000000010000.log
000000020000.log
```

Old segments can be deleted independently based on retention.

---

## 14. Offset

Each record gets a monotonically increasing offset within a partition.

```text
partition 7, offset 91821
```

Offset identifies position, not global time.

---

## 15. Producer Flow

```text
Producer
   |
   | fetch metadata
   v
Partitioner
   |
   v
Partition Leader
   |
   v
Append Log
   |
   v
Replicate
   |
   v
ACK Producer
```

---

## 16. Producer Batching

Sending one network request per message is inefficient.

Batch records by partition.

Benefits:

- fewer syscalls
- fewer network packets
- better compression
- better sequential IO

---

## 17. linger.ms Trade-Off

Producer can wait briefly to build a batch.

Higher linger:

- better throughput
- slightly higher latency

Lower linger:

- lower latency
- smaller batches

---

## 18. Compression

Compress batches rather than individual records.

Possible codecs:

```text
gzip
snappy
lz4
zstd
```

Trade-off:

```text
CPU vs network/disk savings
```

---

## 19. Replication

Each partition has multiple replicas.

Example replication factor 3:

```text
Partition P0
Leader: Broker A
Follower: Broker B
Follower: Broker C
```

---

## 20. Leader Replica

All writes normally go through the leader.

Followers fetch from leader and replicate the log.

This simplifies ordering.

---

## 21. ISR — In-Sync Replicas

ISR is the set of replicas sufficiently caught up with the leader.

Example:

```text
ISR = {A, B, C}
```

If C falls too far behind:

```text
ISR = {A, B}
```

---

## 22. Why ISR Matters

Leader election should prefer replicas that have all committed data.

Electing an out-of-sync follower can lose acknowledged messages.

---

## 23. acks=0

Producer does not wait for broker acknowledgement.

Advantages:

- lowest latency
- highest throughput

Disadvantages:

- possible message loss without producer knowing

---

## 24. acks=1

Leader acknowledges after local write.

If leader fails before followers replicate:

```text
acknowledged message may be lost
```

---

## 25. acks=all

Leader waits for required in-sync replicas.

Provides stronger durability.

Latency is higher than acks=1.

---

## 26. min.insync.replicas

Example:

```text
replication.factor = 3
min.insync.replicas = 2
acks = all
```

Write succeeds only if enough ISR replicas participate.

This trades availability for durability.

---

## 27. Durability vs Availability

During failures:

```text
only 1 replica alive
```

With `min.insync.replicas=2`:

```text
reject writes
```

That may be correct for critical data.

---

## 28. High Watermark

Consumers should normally read only committed records.

High watermark represents the offset replicated enough to be considered safe/committed.

---

## 29. Leader Failure

If leader dies:

```text
Controller detects failure
-> choose new leader from eligible replicas
-> update metadata
-> producers/consumers refresh metadata
```

---

## 30. Clean Leader Election

Elect from ISR.

Benefit:

- preserve committed data

Possible cost:

- partition unavailable if no ISR replica remains

---

## 31. Unclean Leader Election

Elect an out-of-sync replica.

Benefit:

- restore availability

Risk:

- acknowledged data loss

For critical systems, this trade-off must be explicit.

---

## 32. Controller

The controller manages cluster metadata/control-plane operations such as:

- broker membership
- partition leadership
- replica assignments
- elections

Modern designs typically use a replicated metadata quorum rather than relying on a single unreplicated controller state.

---

## 33. Control Plane vs Data Plane

Control plane:

```text
metadata
leader election
broker membership
replica assignment
```

Data plane:

```text
produce
fetch
replicate records
```

Keep control-plane instability from overwhelming data-plane serving where possible.

---

## 34. Metadata Quorum

Cluster metadata should be replicated through consensus.

Why?

If two controllers independently think different brokers are leaders:

```text
split brain
```

Consensus provides one authoritative metadata history.

---

## 35. Broker Registration

Broker joins cluster and registers:

```text
brokerId
endpoints
rack/AZ
```

Controller tracks liveness.

---

## 36. Rack Awareness

Replicas should be spread across independent failure domains.

Bad:

```text
3 replicas in same rack
```

Better:

```text
replica 1 -> AZ A
replica 2 -> AZ B
replica 3 -> AZ C
```

---

## 37. Follower Replication

Follower pulls batches from leader.

Flow:

```text
Follower -> fetch from leader
Follower -> append locally
Follower -> report progress
```

Pull-based replication lets followers control their fetch pace.

---

## 38. Replica Lag

Monitor:

```text
leader end offset - follower replicated offset
```

Large lag may remove follower from ISR.

---

## 39. Slow Follower Problem

Slow disk/network follower can reduce ISR size.

Possible causes:

- disk saturation
- GC pause
- network issue
- noisy neighbor

Observability must distinguish broker-wide and partition-specific lag.

---

## 40. Producer Retry Problem

Producer sends message M.

Leader writes it, response is lost.

Producer retries.

Without deduplication:

```text
M
M
```

---

## 41. Idempotent Producer

Producer gets producer ID + sequence numbers.

Broker tracks recent sequence state per producer/partition.

Duplicate retry with same sequence is ignored.

This provides idempotent append within the supported producer session semantics.

---

## 42. Producer Epoch

If producer restarts/reinitializes, epoch/version helps fence stale producer instances.

Older producer incarnation should not continue writing conflicting sequences.

---

## 43. max.in.flight and Ordering

Multiple unacknowledged requests improve throughput.

But retries can reorder batches unless idempotent ordering guarantees are enforced.

Ordering configuration must match producer guarantees.

---

## 44. Transactions

Kafka-style transactions can atomically publish across multiple partitions and commit consumer offsets together.

Use case:

```text
consume input
process
produce output
commit consumed offset
```

as one logical transaction.

---

## 45. Transaction Coordinator

Transaction coordinator tracks:

- producer transactional identity
- partitions involved
- commit/abort state

Markers in logs let consumers distinguish committed from aborted transactional records.

---

## 46. Exactly-Once Semantics

EOS is contextual.

Inside Kafka processing pipelines, transactions + idempotent producer + offset coordination can provide strong exactly-once processing semantics.

But external databases/APIs still require their own idempotency/transaction strategy.

---

## 47. read_committed

Consumer isolation can choose to expose only committed transactional data.

Aborted transaction records are skipped.

---

## 48. Consumer

Consumer fetches records from partition leaders.

Unlike classic push queues, consumers pull at their own pace.

---

## 49. Consumer Group

Consumers sharing a group divide partitions.

Example:

```text
Topic has 6 partitions
Group has 3 consumers
```

Each consumer may own 2 partitions.

---

## 50. One Partition, One Consumer per Group

Within one consumer group, a partition is actively assigned to at most one consumer at a time.

This preserves partition processing order.

---

## 51. More Consumers Than Partitions

Example:

```text
3 partitions
5 consumers
```

2 consumers are idle.

Consumer parallelism is bounded by partition count.

---

## 52. Multiple Consumer Groups

Different groups maintain independent offsets.

Example:

```text
orders topic
  -> fraud group
  -> analytics group
  -> shipping group
```

Each sees the full logical stream independently.

---

## 53. Consumer Offset

Offset represents the next/last processed position depending on API convention.

Persisted offset allows resume after restart.

---

## 54. Auto Commit Risk

If offset is committed before business processing completes:

```text
consumer crashes
-> message skipped on restart
```

This can produce at-most-once behavior.

---

## 55. Commit After Processing

Process first, then commit offset.

Crash after processing but before commit:

```text
record is redelivered
```

This gives at-least-once behavior.

Consumer side effects must be idempotent.

---

## 56. At-Most-Once

```text
commit before processing
```

Possible loss, no duplicates.

Useful only where loss is acceptable.

---

## 57. At-Least-Once

```text
process then commit
```

No intentional loss, but duplicates possible.

Most robust business systems combine this with idempotent consumers.

---

## 58. Exactly-Once Business Effects

For external side effects:

```text
Kafka at-least-once
+
consumer idempotency/inbox
```

is often more important than claiming magical global exactly-once delivery.

---

## 59. Rebalancing

Consumer joins/leaves group.

Partitions must be reassigned.

Naive stop-the-world rebalance pauses consumption.

---

## 60. Eager Rebalance

All consumers revoke assignments and redistribute.

Simple but disruptive.

---

## 61. Cooperative Rebalance

Only partitions that need movement are transferred incrementally.

This reduces pause time.

---

## 62. Sticky Assignment

Try to preserve previous partition ownership where possible.

Benefits:

- fewer movements
- warmer caches/state
- less disruption

---

## 63. Group Coordinator

A coordinator manages:

- membership
- heartbeats
- assignment protocol
- generation/epoch

Consumers must prove they belong to the current generation when committing offsets.

---

## 64. Consumer Heartbeats

If heartbeats stop:

```text
consumer considered dead
-> rebalance
```

Timeouts must balance fast failover vs false rebalances during GC/network pauses.

---

## 65. max.poll Interval Problem

Consumer may be alive but processing takes too long.

Group coordinator can decide it is unhealthy and reassign partitions.

Slow processing must be bounded or partition work delegated carefully.

---

## 66. Poison Message

One message always fails.

Naive consumer can retry forever and block partition progress.

Use:

- bounded retry
- retry topic
- DLQ
- manual replay

---

## 67. Retry Topics

Example:

```text
orders
orders.retry.30s
orders.retry.5m
orders.dlq
```

This moves delayed retries out of the main partition path.

---

## 68. Retry Ordering Trade-Off

Moving failed messages to retry topics can break strict per-key ordering.

If strict ordering is mandatory, you may need to pause that key/partition instead.

This reduces throughput.

---

## 69. Consumer Backpressure

Producer rate:

```text
100K/sec
```

Consumer capacity:

```text
60K/sec
```

Lag grows by:

```text
40K/sec
```

Kafka buffers this temporarily, but storage is finite.

---

## 70. Consumer Lag

Lag:

```text
log end offset - committed consumer offset
```

Lag is a symptom, not the root cause.

Investigate:

- downstream DB latency
- consumer CPU
- partition skew
- retries
- insufficient consumers

---

## 71. Why Kafka Does Not Solve Infinite Backpressure

Kafka provides durable buffering.

It does not make downstream capacity infinite.

If lag grows forever:

```text
retention/disk limits eventually matter
```

Use autoscaling, throttling, load shedding, or upstream admission control.

---

## 72. Broker Backpressure

Broker can become saturated by:

- produce throughput
- consumer fetches
- replication traffic
- page-cache misses
- disk IO

Use quotas and capacity planning.

---

## 73. Producer Quotas

Per-client/tenant quotas prevent one producer from consuming all broker bandwidth.

Useful for multi-tenant clusters.

---

## 74. Consumer Fetch Batching

Consumers fetch batches, not individual records.

Larger fetch:

- better throughput
- more latency/memory

Smaller fetch:

- lower latency
- more requests

---

## 75. Zero-Copy / Efficient Transfer

Kafka-like systems benefit from transferring file/page-cache data to sockets with minimal user-space copies.

This improves throughput and lowers CPU.

---

## 76. Page Cache

OS page cache often holds hot log segments.

Consumers reading recent data may be served from memory even though data is durably stored on disk.

---

## 77. Retention by Time

Example:

```text
retain 7 days
```

Old segments are deleted after policy threshold.

Consumers must not lag beyond retention if they need all data.

---

## 78. Retention by Size

Example:

```text
max 1 TB per partition/topic
```

Oldest segments removed when limit exceeded.

---

## 79. Log Compaction

For keyed records:

```text
K1 -> A
K2 -> X
K1 -> B
```

Compaction eventually keeps latest value per key:

```text
K1 -> B
K2 -> X
```

Useful for changelog/state topics.

---

## 80. Tombstones

A record with key and null value can represent deletion.

Compaction eventually removes older value and later tombstone according to retention policy.

---

## 81. Retention vs Compaction

Retention:

```text
keep recent history by time/size
```

Compaction:

```text
keep latest state per key
```

They solve different problems.

---

## 82. Segment Indexes

Each log segment can maintain indexes mapping offsets/timestamps to byte positions.

This avoids scanning entire files for fetch operations.

---

## 83. Time-Based Lookup

Consumer may ask:

```text
start from timestamp T
```

Broker uses time index to find approximate offset.

---

## 84. Partition Reassignment

As cluster grows or brokers fail, replicas move.

Movement consumes:

- network
- disk IO

Throttle reassignment so normal traffic is not overwhelmed.

---

## 85. Adding Brokers

Adding brokers does not automatically rebalance historical partitions perfectly.

Partitions may need explicit reassignment.

---

## 86. Increasing Partition Count

More partitions improve future parallelism.

But key-to-partition mapping changes with simple modulo hash.

Existing records do not move automatically.

Per-key global ordering across the expansion boundary becomes more complex.

---

## 87. Too Many Partitions

Partitions are not free.

Costs include:

- metadata
- file handles
- leader elections
- replication streams
- recovery time

Choose partition count from throughput and consumer parallelism needs.

---

## 88. Partition Skew

Monitor per-partition:

```text
bytes in
records in
lag
CPU impact
```

Average topic throughput can hide one overloaded partition.

---

## 89. Broker Failure Scenario

Broker B dies.

For partitions led by B:

```text
controller elects ISR followers
```

For partitions where B was follower:

```text
replication factor temporarily reduced
```

Later replacement replicas are assigned and catch up.

---

## 90. Disk Failure

A broker may be alive but disk fails.

Partition replicas on that disk become unavailable.

Operationally distinguish process liveness from storage health.

---

## 91. Network Partition

Broker cannot reach controller/peers but may still serve some clients.

Use metadata epochs/fencing so stale leaders cannot continue writing indefinitely.

---

## 92. Leader Epoch

Each leadership change increments an epoch.

Clients/followers use epoch to reject stale leadership assumptions.

This protects against split-brain behavior.

---

## 93. Data Loss Scenario

Suppose:

```text
RF=3
acks=1
leader writes M
followers have not replicated M
leader dies
```

New leader lacks M.

Producer had already received success.

This is why acknowledgement semantics matter.

---

## 94. Strong Durability Setup

Typical stronger configuration:

```text
replication.factor=3
acks=all
min.insync.replicas=2
unclean leader election disabled
```

Trade-off:

less write availability during failures.

---

## 95. Cross-Region Replication

One Kafka cluster spanning distant regions synchronously is often expensive due to latency and failure coupling.

Common strategy:

```text
regional clusters
+
asynchronous replication between clusters
```

---

## 96. Mirror / Replication Service

Cross-cluster replicator consumes from source and produces to destination.

Consider:

- topic mapping
- offset translation
- loop prevention
- duplicate handling

---

## 97. DR Strategy

Primary region fails.

Secondary cluster may lag.

Need defined:

```text
RPO
RTO
producer failover
consumer restart offsets
```

Cross-region async replication usually accepts non-zero RPO.

---

## 98. Active-Active Regions

Possible, but avoid letting the same ordered key be independently written in two regions unless conflict/ordering semantics are explicitly designed.

Regional ownership is simpler.

---

## 99. Schema Management

Events evolve.

Use versioned schemas.

Compatibility policies:

- backward
- forward
- full

A schema registry-style service can enforce compatibility.

---

## 100. Why Schema Compatibility Matters

Producer may deploy before consumer.

Additive compatible fields allow independent deployment.

Breaking field changes can crash downstream pipelines.

---

## 101. Security

- TLS
- authentication
- topic ACLs
- tenant isolation
- encryption at rest where required
- quotas
- audit logs

Do not expose cluster metadata or admin APIs broadly.

---

## 102. Multi-Tenancy

One noisy tenant can create:

- broker network saturation
- disk pressure
- hot partitions
- controller churn

Use:

- quotas
- topic limits
- partition limits
- isolation for critical tenants

---

## 103. Observability

Broker metrics:

```text
bytes_in/sec
bytes_out/sec
request_latency
under_replicated_partitions
ISR_shrink_rate
leader_count
disk_usage
page_cache_hit behavior
network_saturation
```

---

## 104. Producer Metrics

```text
record_send_rate
batch_size
compression_rate
request_latency
retry_rate
error_rate
record_queue_time
```

---

## 105. Consumer Metrics

```text
records_consumed/sec
consumer_lag
rebalance_rate
commit_latency
poll_latency
processing_latency
DLQ_rate
```

---

## 106. Critical Alerts

Examples:

```text
under-replicated partitions > 0
ISR continuously shrinking
controller election churn
disk > 80%
consumer lag growing monotonically
produce error rate spike
```

---

## 107. Capacity Estimation

Assume:

```text
5M records/sec
average record = 1 KB
```

Raw ingest:

```text
~5 GB/sec
```

With replication factor 3:

```text
~15 GB/sec internal write/replication traffic before overhead/compression
```

At 24 hours retention:

```text
5 GB/sec * 86,400 ≈ 432 TB raw/day
```

This shows why compression, retention, partitioning, and disk/network planning dominate Kafka design.

---

## 108. Throughput Scaling

To scale:

```text
more partitions
more brokers
larger producer batches
compression
more consumer instances
```

But partition count and broker count introduce control/recovery costs.

---

## 109. Latency vs Throughput

Kafka-style systems optimize throughput through batching and sequential IO.

Ultra-low single-message latency may require smaller batches at some efficiency cost.

---

## 110. Production Architecture

```text
                         Metadata Quorum
                       /        |        \
                      v         v         v
                 Controller / Cluster Metadata
                              |
        +---------------------+----------------------+
        |                     |                      |
        v                     v                      v
     Broker A              Broker B               Broker C
   P0 Leader             P1 Leader              P2 Leader
   P1 Follower           P2 Follower            P0 Follower
   P2 Follower           P0 Follower            P1 Follower
        ^                     ^                      ^
        |                     |                      |
        +-------- Producer traffic by leader -------+

Consumers -> Metadata -> Partition Leaders -> Fetch Batches

Consumer Group Coordinator -> Membership / Assignments / Offsets
```

---

## 111. Principal Engineer Failure Scenario

Assume:

```text
cluster ingest = 8 GB/sec
RF = 3
```

During peak traffic:

```text
one broker loses a disk
another broker has 2-second GC pauses
ISR shrinks across hundreds of partitions
consumer lag rises rapidly
producers begin retrying timeouts
partition reassignment starts automatically
```

A weak response can cause:

```text
broker failure
-> replica catch-up traffic
-> disk/network saturation
-> producer timeouts
-> retries
-> more network pressure
-> ISR shrink
-> leader elections
-> consumer rebalances
-> cascading cluster outage
```

Principal-level response:

1. preserve `acks=all` / min ISR guarantees for critical topics
2. avoid unclean leader election unless explicit data-loss trade-off is accepted
3. throttle replica reassignment/recovery traffic
4. stop automatic aggressive balancing during instability
5. enforce producer quotas/backoff to prevent retry amplification
6. isolate or throttle noncritical high-volume producers
7. prioritize replication/control-plane traffic appropriately
8. identify hot/skewed partitions separately from broker-wide issues
9. stabilize broker GC/memory pressure
10. scale consumers only if downstream dependencies have capacity
11. avoid constant consumer restarts that trigger rebalance storms
12. increase retention/disk headroom if lag threatens data expiry
13. replace failed disk/broker and rebuild replicas gradually
14. keep metadata quorum healthy and isolated
15. use rack-aware replica placement so one AZ failure does not erase quorum
16. recover traffic gradually after ISR returns

Core principle:

> In a distributed log, recovery traffic is itself production traffic. Replica catch-up, retries, elections, and rebalances can amplify the original failure, so recovery must be throttled and prioritized—not unleashed blindly.

---

## 112. Interview Follow-Up Questions

Be prepared for:

- Why is Kafka a log rather than a traditional queue?
- Why partitions?
- What ordering does Kafka guarantee?
- How does key-based partitioning work?
- What creates hot partitions?
- Why sequential disk IO?
- What are leader and follower replicas?
- What is ISR?
- acks=0 vs 1 vs all?
- What does min.insync.replicas do?
- What happens if the leader fails?
- Clean vs unclean leader election?
- What does the controller do?
- How does idempotent producer work?
- How do producer sequence numbers prevent duplicates?
- How do transactions/EOS work?
- How do consumer groups work?
- Why can consumers exceed partition count but remain idle?
- When do duplicate consumptions happen?
- Eager vs cooperative rebalancing?
- What is log compaction?
- How does consumer lag become dangerous?
- How do you handle cross-region Kafka?
- What happens during a broker disk failure?

---

## 113. Interview Summary

The transferable concepts in this design are:

```text
Distributed append-only log
Topics / partitions
Offsets
Partition ordering
Batching
Compression
Leader/follower replication
ISR
Acknowledgement semantics
min.insync.replicas
Leader election
Metadata consensus
Rack awareness
Idempotent producer
Producer fencing/epochs
Transactions / EOS
Consumer groups
Offset management
At-most-once / at-least-once
Rebalancing
Retry topics / DLQ
Backpressure / consumer lag
Retention
Log compaction
Hot partitions
Quotas
Cross-region replication
Recovery throttling
Observability
```

A strong HLD answer should explain Kafka as a **partitioned replicated log with pull-based consumers**. Throughput comes from partitioning, batching, sequential IO, and page cache; correctness comes from leader epochs, ISR, acknowledgement semantics, idempotent producers, consumer offset discipline, and carefully controlled failure recovery.
