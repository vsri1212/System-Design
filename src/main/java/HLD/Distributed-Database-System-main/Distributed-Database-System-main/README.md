# Distributed Database System — DynamoDB / Cassandra-Style High-Level Design

A senior/principal-level HLD case study for designing a horizontally scalable, highly available distributed key-value / wide-column database inspired by Dynamo-style and Cassandra-style systems.

The core idea is:

> Distributed databases scale by partitioning data, replicating it across failure domains, and making consistency a deliberate policy rather than an accidental side effect.

---

## 1. Problem Statement

Design a distributed database that supports:

- massive horizontal scale
- high write throughput
- low-latency key-based reads
- replication across nodes/AZs/regions
- configurable read/write consistency
- automatic failover
- partition tolerance
- background repair
- large data volumes
- incremental cluster expansion

The system should remain useful even when:

- nodes fail
- networks partition
- replicas lag
- disks fill
- hotspots appear
- compaction falls behind

---

## 2. Functional Requirements

Core APIs:

```text
PUT(key, value)
GET(key)
DELETE(key)
BATCH_WRITE(...)
QUERY(partitionKey, rangePredicate)
```

For a wide-column model:

```text
Partition Key + Clustering Key -> Row
```

Example:

```text
(userId, eventTime) -> activity row
```

---

## 3. Non-Functional Requirements

- horizontal scalability
- no single data-node bottleneck
- high availability
- configurable consistency
- durable writes
- predictable partitioning
- efficient sequential disk writes
- background repair
- rolling upgrades
- multi-region replication
- tenant isolation
- observability

---

## 4. Core Components

```text
Client Driver
Coordinator Node
Partitioner
Token Ring / Partition Map
Replica Set
Commit Log / WAL
Memtable
SSTable
Compaction
Bloom Filter
Gossip / Membership
Repair Service
```

---

## 5. High-Level Architecture

```text
Client
  |
  v
Coordinator Node
  |
  v
Partitioner
  |
  +--> Replica A
  +--> Replica B
  +--> Replica C

Each replica:

Write -> Commit Log -> Memtable -> SSTables -> Compaction
Read  -> Memtable + SSTables + Bloom Filters + Indexes
```

---

## 6. Leaderless Replication Model

Unlike a primary-replica database, any suitable node may coordinate a request.

The coordinator routes requests to the replica set responsible for the key.

There is no mandatory single leader for ordinary writes in the classic Dynamo/Cassandra-style model.

---

## 7. Partition Key

Every row/object has a partition key.

Example:

```text
userId
accountId
orderId
```

The partition key determines which nodes own the data.

Good partition-key design is one of the most important decisions in this architecture.

---

## 8. Hash Partitioning

Compute:

```text
token = hash(partitionKey)
```

The token maps to a partition/range in the cluster.

This spreads keys more uniformly than range-partitioning by raw user value.

---

## 9. Consistent Hashing

Nodes own token ranges on a logical ring.

Example:

```text
0 ------------------------------ maxToken
   A       B        C       D
```

A key hashes to a token and is assigned to the next owning range/node according to the ring policy.

---

## 10. Why Consistent Hashing?

With naive modulo hashing:

```text
hash(key) % N
```

adding/removing a node remaps a large fraction of keys.

Consistent hashing limits movement to affected ranges.

---

## 11. Virtual Nodes

A physical node owns many token ranges rather than one.

Benefits:

- smoother load distribution
- easier rebalancing
- heterogeneous capacity support

---

## 12. Partition Map

Clients/coordinators need metadata:

```text
token range -> replicas
```

The map changes as nodes join, leave, or fail.

---

## 13. Replication Factor

Example:

```text
RF = 3
```

Each logical partition is stored on three replicas.

Spread replicas across independent failure domains.

---

## 14. Rack / AZ Awareness

Bad placement:

```text
3 replicas in one rack
```

Better:

```text
replica 1 -> AZ A
replica 2 -> AZ B
replica 3 -> AZ C
```

This protects against correlated failures.

---

## 15. Coordinator Node

The client can send request to any healthy coordinator.

Coordinator:

- computes replica set
- sends read/write requests
- waits for required responses
- reconciles results if needed

Coordinator does not necessarily own the data itself.

---

## 16. Tunable Consistency

Read/write consistency can be configured per operation.

Common conceptual levels:

```text
ONE
QUORUM
ALL
LOCAL_QUORUM
```

---

## 17. Write Quorum

Let:

```text
N = replication factor
W = write acknowledgements required
R = read responses required
```

If:

```text
W + R > N
```

read and write quorums overlap, improving the chance of reading the latest acknowledged version under the assumed replica/version model.

---

## 18. Example Quorum

```text
N = 3
W = 2
R = 2
```

Then:

```text
W + R = 4 > 3
```

This is a common quorum configuration.

---

## 19. Consistency vs Availability

Higher quorum:

- stronger consistency
- higher latency
- lower availability during replica failure

Lower quorum:

- lower latency
- higher availability
- greater stale-read/conflict risk

---

## 20. Write Path

```text
Client
  |
  v
Coordinator
  |
  +--> Replica A
  +--> Replica B
  +--> Replica C
```

Each replica:

```text
append WAL / commit log
update memtable
ACK
```

Coordinator returns once required W acknowledgements arrive.

---

## 21. Commit Log / WAL

Every accepted write is first appended to durable sequential log.

Purpose:

- recover memtable after crash
- avoid random-write-heavy data files

---

## 22. Memtable

Recent writes live in an in-memory sorted structure.

Advantages:

- fast writes
- organized flush order

When full:

```text
flush -> immutable SSTable
```

---

## 23. SSTable

Sorted String Table is an immutable sorted on-disk file.

Contains:

- keys/rows
- indexes
- metadata
- checksums

Immutability simplifies crash safety and sequential IO.

---

## 24. LSM Tree

Write path:

```text
WAL
-> Memtable
-> SSTable
-> Compaction
```

This is a Log-Structured Merge Tree architecture.

It converts random writes into sequential appends/flushes.

---

## 25. Why LSM Trees Fit Write-Heavy Systems

Instead of updating disk pages randomly for every write:

```text
append log + memory write
```

Then flush sorted runs later.

Excellent for high write throughput.

---

## 26. Read Path

A read may need to inspect:

```text
memtable
multiple SSTables
```

Because latest value may exist in any of them.

Use indexes/Bloom filters to avoid unnecessary disk reads.

---

## 27. Bloom Filter

Each SSTable can have a Bloom filter.

It answers:

```text
definitely not present
or
possibly present
```

This avoids many useless SSTable lookups.

---

## 28. Bloom Filter Trade-Off

False positive:

```text
might read an SSTable unnecessarily
```

False negative:

```text
should never happen
```

Memory size controls false-positive probability.

---

## 29. Sparse Index

Because SSTable is sorted, maintain sparse key-to-offset index.

Lookup can seek near target rather than scan whole file.

---

## 30. Read Amplification

Many SSTables can mean one read touches many files.

Compaction controls this.

---

## 31. Write Amplification

Compaction rewrites data multiple times.

LSM trees trade cheap foreground writes for background write amplification.

---

## 32. Space Amplification

Old versions and tombstones remain until compaction removes them.

Disk usage temporarily exceeds live logical data size.

---

## 33. Compaction

Merge SSTables:

```text
SSTable A + B + C
-> new SSTable D
```

During merge:

- keep latest versions
- remove obsolete data when safe
- purge tombstones after safety window

---

## 34. Compaction Strategies

Common conceptual strategies:

```text
Size-tiered
Leveled
Time-window
```

Each optimizes different workloads.

---

## 35. Size-Tiered Compaction

Merge similarly sized SSTables.

Good write throughput.

Trade-off:

- more overlapping SSTables
- potentially higher read amplification

---

## 36. Leveled Compaction

Organize SSTables into levels with controlled overlap.

Advantages:

- lower read amplification

Costs:

- higher write amplification

---

## 37. Time-Window Compaction

Group SSTables by time windows.

Useful for time-series/event workloads where old partitions stop changing.

---

## 38. Compaction Debt

If writes arrive faster than compaction can process:

```text
SSTable count rises
read latency rises
disk fills
```

Compaction throughput must be capacity-planned.

---

## 39. Compaction Backpressure

When compaction debt becomes dangerous:

- throttle writes
- increase compaction resources
- shed noncritical traffic

Do not let disk reach 100%.

---

## 40. Delete Semantics

SSTables are immutable.

DELETE cannot simply erase old bytes.

Write a tombstone.

---

## 41. Tombstone

```text
key = K
value = TOMBSTONE
timestamp = T
```

Reads treat older values as deleted.

Compaction later removes tombstone and shadowed values when safe.

---

## 42. Why Tombstones Need Retention

Suppose replica C is offline during delete.

If tombstone disappears too early:

```text
C returns with old value
-> deleted data resurrects
```

Keep tombstone long enough for repair/replica convergence.

---

## 43. Tombstone Storm

Large deletes/TTL-heavy workloads can create millions of tombstones.

Reads scanning tombstones become slow.

Mitigations:

- good partition design
- time-window compaction
- bounded partitions
- appropriate TTL patterns

---

## 44. TTL

A row/cell may expire after TTL.

Expiry usually becomes tombstone-like deletion semantics.

Heavy TTL workloads need compaction planning.

---

## 45. Read Repair

Coordinator reads from multiple replicas.

If values differ:

```text
return winning/latest value
repair stale replica asynchronously or inline depending design
```

---

## 46. Hinted Handoff

Replica B is temporarily unavailable.

Coordinator/another node stores a hint:

```text
when B returns, deliver missed write
```

This improves write availability during short outages.

---

## 47. Hinted Handoff Is Not Permanent Repair

Hints expire and may not cover long failures.

Need anti-entropy repair for full convergence.

---

## 48. Anti-Entropy Repair

Replicas periodically compare data and synchronize differences.

At large scale, comparing every key directly is expensive.

Use Merkle trees or range hashes.

---

## 49. Merkle Tree

Tree summarizes hashes of key ranges.

If root differs, recursively inspect only differing branches.

This identifies divergent ranges efficiently.

---

## 50. Repair Flow

```text
Replica A Merkle tree
vs
Replica B Merkle tree
```

Find mismatched ranges, stream missing/newer rows.

---

## 51. Repair Bandwidth

Repair can consume huge disk/network resources.

Throttle and schedule it.

Recovery traffic must not cause production outage.

---

## 52. Gossip Membership

Nodes exchange cluster state periodically:

```text
alive/dead
load
tokens
schema/version metadata
```

Gossip spreads information without one central broadcaster.

---

## 53. Failure Detector

Node liveness is probabilistic.

Network delay can look like failure.

Use heartbeat history/phi-accrual-style suspicion or equivalent bounded failure detector.

---

## 54. Why Failure Detection Is Hard

In asynchronous networks:

```text
slow != dead
```

Failover decisions must tolerate transient delay.

---

## 55. Conflict Resolution

Concurrent writes can reach different replicas during network partition.

Need deterministic resolution.

Options:

```text
Last-Write-Wins
Vector clocks/version vectors
Application merge
CRDT-like types
```

---

## 56. Last-Write-Wins (LWW)

Choose value with highest timestamp.

Advantages:

- simple

Risks:

- clock skew
- silently drops concurrent update

---

## 57. Clock Skew Problem

Client/node with clock far in future writes value.

Later legitimate writes may lose for long time under naive LWW.

Use synchronized clocks and carefully control timestamp source.

---

## 58. Vector Clock Concept

Tracks causal versions across writers.

Can distinguish:

```text
A happened before B
```

from

```text
A and B were concurrent
```

---

## 59. Sibling Versions

If two writes are concurrent, system may retain both as siblings.

Application/client merges them later.

This preserves information but increases complexity.

---

## 60. Version Vectors / Dotted Versions

Variants reduce metadata growth while tracking causality.

Exact implementation varies.

---

## 61. Application Conflict Resolution

Shopping cart example:

Concurrent cart additions can be merged as set union rather than choosing one LWW winner.

Data semantics should drive conflict policy.

---

## 62. CRDT-Like Data Types

For counters/sets/maps, conflict-free replicated data types can make concurrent updates merge deterministically.

Costs:

- metadata
- semantic restrictions

---

## 63. Read Consistency Levels

Example:

```text
ONE -> fastest, may be stale
QUORUM -> stronger
ALL -> strongest among available replicas, lowest availability
```

---

## 64. Write Consistency Levels

Example:

```text
ONE -> ACK after one replica
QUORUM -> majority
ALL -> all replicas
```

Choose per workload.

---

## 65. LOCAL_QUORUM

In multi-region topology:

```text
wait for quorum only in local region/DC
```

Reduces WAN latency while replicating remotely asynchronously.

---

## 66. EACH_QUORUM

Require quorum in each region/DC.

Stronger cross-region durability/consistency, but higher latency and lower availability.

---

## 67. CAP Perspective

Leaderless Dynamo-style stores typically favor availability and partition tolerance with tunable consistency.

But actual behavior depends on chosen quorum level and topology.

Do not reduce the entire system to a simplistic CP/AP label without operation semantics.

---

## 68. PACELC Perspective

Even without partition:

```text
stronger consistency often costs latency
```

PACELC helps explain the normal-operation trade-off too.

---

## 69. Read-Your-Writes

With write ONE then read ONE from another stale replica, client may not see its own write.

To improve:

- stronger read/write quorum
- sticky replica/session routing
- version tokens

---

## 70. Monotonic Reads

Client should not see version 10 then later version 9.

Can use session version tracking or stronger consistency.

---

## 71. Partition Design

Good partition key distributes load and keeps query locality.

Bad key:

```text
country = "IN"
```

if most traffic is India.

Result:

```text
hot partition
```

---

## 72. High-Cardinality Partition Key

Good candidates often have many values:

```text
userId
orderId
deviceId
```

But query needs must also be considered.

---

## 73. Wide Partition

One partition grows to millions of rows/gigabytes.

Problems:

- hot shard
- slow repair
- large compactions
- expensive reads

Bound partition size.

---

## 74. Bucketing

Instead of:

```text
partitionKey = userId
```

for unbounded event history, use:

```text
(userId, dayBucket)
```

This bounds partition size.

---

## 75. Time Bucketing Trade-Off

Smaller buckets:

- better size control
- more query fan-out

Larger buckets:

- fewer reads
- risk of oversized partitions

---

## 76. Hot Key

One key receives extreme read/write traffic.

Replication helps reads, but writes to same logical key still create coordination/replica pressure.

Mitigations depend on semantics:

- sharded counters
- write coalescing
- data model redesign

---

## 77. Hot Partition Detection

Monitor per token/range:

```text
read QPS
write QPS
latency
disk usage
compaction debt
```

Cluster averages hide hotspots.

---

## 78. Rebalancing

Adding node requires moving token ranges/data.

Flow:

```text
new node joins
-> receives token ranges
-> streams existing SSTables/data
-> starts serving
```

---

## 79. Streaming During Rebalance

Data movement uses substantial network/disk.

Throttle to protect foreground traffic.

---

## 80. Bootstrap Safety

Do not let new node serve full ownership before it has enough required data/state.

Use explicit bootstrap state.

---

## 81. Decommission

Move replicas away before removing node.

Abrupt removal reduces replication factor until repair/rebuild.

---

## 82. Node Replacement

Replacement node can assume failed node's ranges and stream data from surviving replicas.

---

## 83. Disk Failure

A node process may be healthy while disk is failing.

Health checks must include:

- IO errors
- latency
- free space

---

## 84. Disk Full Scenario

If disk fills:

- WAL may fail
- compaction cannot proceed
- node becomes unstable

Maintain substantial free-space headroom.

---

## 85. Compaction Requires Free Space

Merging SSTables creates new files before deleting old ones.

Need free disk significantly above live data size.

---

## 86. Read Amplification Failure

Compaction lag -> hundreds of SSTables -> Bloom/index checks -> disk seeks -> p99 read latency explosion.

Monitor SSTable count per partition/table.

---

## 87. Write Stall

If memtables cannot flush or compaction debt is extreme, system may throttle/stall writes to protect itself.

Better controlled backpressure than disk collapse.

---

## 88. Secondary Index

Global secondary indexes are difficult at scale because data may live on unrelated partitions.

Options:

- local secondary index
- materialized query table
- dedicated search system

---

## 89. Query-Driven Modeling

In Cassandra-style systems, model tables around known query patterns.

Instead of joins at query time, denormalize writes into multiple tables.

---

## 90. Denormalization

Example:

```text
orders_by_user
orders_by_status
```

Same logical order may appear in multiple query tables.

Trade-off:

- faster reads
- more write amplification/consistency complexity

---

## 91. No Cross-Partition Joins

Distributed joins across many shards are expensive.

Use application/query-specific denormalized tables where practical.

---

## 92. Lightweight Transactions / CAS

Some systems add consensus-based conditional operations:

```text
IF version = 5 THEN update
```

Useful for uniqueness/compare-and-set.

Costs much more latency than normal writes.

---

## 93. Paxos/Raft-Style Conditional Path

For strict compare-and-set, replicas run consensus for that key/partition.

Do not use this path for every ordinary high-throughput write.

---

## 94. Idempotent Writes

Client retries after timeout may resend write.

Using deterministic key/version allows safe overwrite of same logical state.

For counters/non-idempotent mutations, retries are more subtle.

---

## 95. Non-Idempotent Counter Retry

```text
INCREMENT +1
```

If response lost and client retries:

```text
+2 accidentally
```

Use operation IDs/dedup or CRDT counter semantics.

---

## 96. Batch Writes

Atomicity across unrelated partitions is expensive.

Prefer local-partition batches if database supports efficient atomic batch semantics there.

---

## 97. Multi-Key Transactions

Full serializable multi-key transactions require much stronger coordination than classic Dynamo/Cassandra data paths.

If workload requires them heavily, a different distributed SQL architecture may be more appropriate.

---

## 98. Schema

Wide-column schema may include:

```text
partition key
clustering columns
regular columns
TTL
```

Schema changes should be versioned and propagated carefully.

---

## 99. Gossip Schema Propagation

Schema/config metadata can propagate among nodes.

Need agreement checks before assuming every node understands new schema.

---

## 100. Rolling Upgrade

Support mixed software versions temporarily.

Use backward-compatible protocols and staged upgrade.

---

## 101. Snapshot / Backup

SSTables are immutable, making snapshot-style backups natural.

Capture consistent file references/metadata and copy to object storage.

---

## 102. Point-in-Time Recovery

Requires WAL/CDC/archive history beyond periodic snapshots.

This adds storage and replay complexity.

---

## 103. CDC

Change Data Capture stream emits row mutations to downstream systems.

Use for:

- search indexing
- analytics
- caches

Do not make downstream consumers part of core write acknowledgement unless required.

---

## 104. Multi-Region Replication

Replicas can be placed across data centers/regions.

Example:

```text
RF=3 in India
RF=3 in US
```

Local quorum serves local traffic; cross-region replication converges asynchronously.

---

## 105. Region-Local Writes

Each region accepts writes.

Concurrent writes to same key may conflict.

Need LWW/vector/application merge semantics.

---

## 106. Home-Region Alternative

For stronger ordering, assign each key/tenant a home region.

Remote writes route to owner.

This sacrifices some write locality for simpler consistency.

---

## 107. WAN Partition

Region A and B cannot communicate.

If both accept writes:

```text
availability preserved
conflicts may arise
```

After network heals, reconciliation/merge resolves versions.

---

## 108. Multi-Region Quorum

If every write waits on remote region quorum:

- stronger global consistency
- WAN latency on every write
- lower partition availability

Use only if business needs justify cost.

---

## 109. RPO / RTO

Async cross-region replication implies possible non-zero RPO during total region loss.

Define:

```text
RPO
RTO
```

Explicitly.

---

## 110. Repair Across Regions

Anti-entropy across WAN can be expensive.

Schedule/throttle and prefer local repair where possible.

---

## 111. Read Path Example

For key K with replicas A/B/C and `R=2`:

```text
Coordinator -> A,B
A returns version 8
B returns version 7
```

Coordinator returns version 8 and may repair B.

---

## 112. Write Path Example

With `W=2`:

```text
A ACK
B ACK
C timeout
```

Client receives success.

Later hinted handoff/repair brings C up to date.

---

## 113. Network Partition Example

A/B reachable, C isolated.

With `W=2`:

```text
writes continue
```

With `W=3`:

```text
writes fail
```

That is the consistency/availability trade-off in concrete form.

---

## 114. Read ONE Staleness Example

Write succeeds on A/B.

Read ONE happens to query C before repair.

Client may see old data.

This is expected under weak consistency level.

---

## 115. Quorum Is Not Magic

`R + W > N` assumes replica sets, version resolution, and failure model behave as expected.

Sloppy quorums, hinted replicas, clocks, and concurrent writes complicate real semantics.

Always explain actual consistency guarantee, not just memorize formula.

---

## 116. Sloppy Quorum Concept

If natural replica is unavailable, write may be stored temporarily on another healthy node to preserve availability.

Later hinted handoff moves it to natural replica.

This increases availability but weakens strict quorum-overlap assumptions.

---

## 117. Durable Delete

Delete must propagate to all replicas before tombstone expiry.

Repair frequency and tombstone grace period are linked.

---

## 118. Repair SLA

If tombstones retained 10 days but some replicas are not repaired for 20 days, deleted data can resurrect.

Operational repair discipline is part of correctness.

---

## 119. Anti-Entropy Scheduling

Prioritize:

- under-replicated ranges
- recently recovered nodes
- high-value tables

Throttle background work to avoid IO collapse.

---

## 120. Capacity Estimation

Assume:

```text
1 PB logical data
RF = 3
```

Raw replicated storage:

```text
~3 PB
```

Before:

- compaction headroom
- tombstones
- snapshots
- indexes
- WAL

Actual cluster capacity must be significantly larger.

---

## 121. Write Throughput Estimation

Suppose:

```text
5M writes/sec
1 KB average row mutation
```

Logical ingress:

```text
~5 GB/sec
```

With RF=3:

```text
~15 GB/sec replica write traffic before protocol/compaction overhead
```

---

## 122. Read Throughput Estimation

Read amplification depends on:

- cache hit rate
- SSTable count
- Bloom filters
- compaction strategy

Benchmark with realistic partitions and query patterns.

---

## 123. Caching

Use:

- row cache
- key cache/index cache
- OS page cache

But cache should not hide poor partition/data modeling.

---

## 124. Page Cache

Hot SSTable blocks may stay in OS memory.

Sequential immutable files work well with page cache.

---

## 125. Coordinator Hotspot

Clients should not route every request through one coordinator.

Use token-aware drivers and load balancing across nodes.

---

## 126. Token-Aware Routing

Smart client knows partition map and sends request directly to replica/coordinator near responsible nodes.

Reduces extra network hop.

---

## 127. Request Hedging

For read-heavy workloads, if one replica is slow, send backup request to another after small delay.

Improves tail latency but adds load.

Use selectively.

---

## 128. Speculative Retry Danger

Aggressive speculative reads/writes can amplify overload.

Only hedge when latency distribution and spare capacity justify it.

---

## 129. Backpressure

If compaction/disk cannot keep up:

```text
foreground writes must be throttled
```

Infinite acceptance only converts latency problem into disk-full outage.

---

## 130. Admission Control

Limit:

- per-tenant QPS
- concurrent requests
- oversized partitions
- expensive scans

Protect cluster before saturation.

---

## 131. No Full Table Scan by Default

Distributed scan across all partitions is extremely expensive.

For analytics, export/CDC to dedicated analytical system.

---

## 132. Search Workload

Text search belongs in search index, not primary wide-column scan.

Maintain search system asynchronously from CDC.

---

## 133. Security

- TLS
- node authentication
- client authentication
- RBAC
- encryption at rest
- audit logs
- tenant isolation

---

## 134. Encryption at Rest

Encrypt SSTables/WAL or underlying volumes.

Key management must support rotation without cluster-wide outage.

---

## 135. Observability

Key metrics:

```text
read_latency_p50/p95/p99
write_latency_p50/p95/p99
read_repair_rate
hint_backlog
repair_bytes
compaction_pending_bytes
SSTables_per_read
tombstones_scanned
disk_used_percent
replica_lag
unavailable_errors
```

---

## 136. Node Metrics

Track:

```text
CPU
heap/memory
GC
network
IOPS
IO latency
free disk
```

Distributed databases are often storage/IO constrained.

---

## 137. Cluster Health Metrics

```text
nodes_up/down
under-replicated ranges
token ownership skew
repair completion
compaction debt
hot partitions
```

---

## 138. SLOs

Example:

```text
99.9% writes < 20 ms at LOCAL_QUORUM
99.9% point reads < 30 ms
```

Exact values depend heavily on hardware, region topology, and payload sizes.

---

## 139. Production Architecture

```text
                         Client Drivers
                              |
                        Token-Aware Routing
                              |
            +-----------------+-----------------+
            |                 |                 |
            v                 v                 v
         Node A            Node B            Node C
       Coord/Data        Coord/Data        Coord/Data
            |                 |                 |
        WAL/Memtable      WAL/Memtable      WAL/Memtable
            |                 |                 |
          SSTables          SSTables          SSTables
            \                 |                 /
             \------ Replicated Partition ----/

Cluster Services:

Gossip / Membership
Failure Detection
Partition Metadata
Hinted Handoff
Repair / Merkle Trees
Compaction
Backup / Snapshot
```

---

## 140. Principal Engineer Failure Scenario

Assume cluster handles:

```text
4M writes/sec
RF=3
LOCAL_QUORUM
```

During peak traffic:

```text
one AZ loses 30% of nodes
surviving nodes receive replica/retry traffic
hint backlog grows rapidly
compaction debt was already high
several disks cross 85%
read repair traffic increases
clients begin speculative retries
```

A weak response can create:

```text
node failure
-> retry amplification
-> hint accumulation
-> compaction slowdown
-> disk saturation
-> higher read/write latency
-> more speculative retries
-> more nodes fail
```

Principal-level response:

1. preserve required consistency for critical tables; do not silently lower quorum globally
2. immediately suppress aggressive client retries/speculative execution
3. throttle repair and bootstrap traffic against foreground workload priorities
4. pause nonessential compactions only if safe; prioritize compactions preventing disk exhaustion
5. apply admission control to low-priority tenants
6. ensure hinted handoff queues are bounded and disk-backed
7. monitor tombstone/repair guarantees before extending outages too long
8. add replacement capacity gradually rather than streaming the entire ring at once
9. rebalance token ranges incrementally
10. identify hot partitions separately from cluster-wide load
11. keep enough free disk for compaction; 85-90% usage is already dangerous
12. use LOCAL_QUORUM to avoid cross-region dependency if topology supports it
13. keep anti-entropy repair scheduled after stability returns
14. verify replicas across remaining AZs still meet RF/quorum
15. recover background repair/compaction bandwidth gradually
16. audit any period where weaker consistency was intentionally used

Core principle:

> In an LSM-based replicated database, failure recovery competes for the same disk and network resources as production traffic. Repair, hinted handoff, compaction, retries, and rebalancing must be throttled together as one recovery budget.

---

## 141. Interview Follow-Up Questions

Be prepared for:

- Why consistent hashing?
- What are virtual nodes?
- How does replication factor work?
- What does a coordinator do?
- What are R, W, and N?
- Why does `R + W > N` matter?
- What are tunable consistency levels?
- How does the write path work?
- Why WAL + memtable + SSTables?
- What is an LSM tree?
- Why use Bloom filters?
- What is read amplification?
- What is compaction?
- Size-tiered vs leveled compaction?
- What are tombstones?
- Why can deleted data resurrect?
- What is hinted handoff?
- What is read repair?
- What is anti-entropy repair?
- How do Merkle trees help?
- How does gossip work?
- LWW vs vector clocks?
- How do multi-region writes resolve conflicts?
- How do you avoid hot partitions?
- Why can disk-full/compaction debt become catastrophic?

---

## 142. Interview Summary

The transferable concepts in this design are:

```text
Consistent hashing
Virtual nodes
Partition keys
Leaderless replication
Replication factor
Quorum reads/writes
Tunable consistency
WAL / commit log
Memtables
SSTables
LSM trees
Bloom filters
Compaction
Read/write/space amplification
Tombstones
Hinted handoff
Read repair
Anti-entropy
Merkle trees
Gossip membership
Failure detection
LWW
Vector clocks
Conflict resolution
Hot partitions
Bucketing
Multi-region replication
Backpressure
Repair throttling
Observability
```

A strong HLD answer should separate three layers:

```text
1. Data placement — partitioning + replication
2. Storage engine — WAL + memtable + SSTables + compaction
3. Consistency/recovery — quorum, hints, repair, conflict resolution
```

The Principal-level insight is that **replication gives availability, but repair is what keeps replication trustworthy over time; LSM trees give write throughput, but compaction is the bill you eventually have to pay**.
