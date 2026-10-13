# Distributed Cache System — Redis-Style High-Level Design

A senior/principal-level HLD case study for designing a Redis-style distributed cache and in-memory data platform.

The core idea is:

> A distributed cache improves latency and protects databases only if its **consistency, eviction, replication, sharding, and failure behavior** are designed explicitly. A fast cache with unclear correctness semantics can make the entire system less reliable.

---

## 1. Problem Statement

Design a distributed cache platform that supports:

- in-memory key-value operations
- TTL / expiry
- high read/write throughput
- replication
- failover
- horizontal sharding
- hot-key protection
- eviction
- persistence options
- multi-tenant isolation
- distributed locks/coordination primitives

The system should survive:

- node crashes
- replica lag
- primary failure
- network partition
- cache stampede
- memory exhaustion
- hot keys
- cluster rebalancing

---

## 2. Functional Requirements

Core operations:

```text
GET key
SET key value
DEL key
EXPIRE key ttl
MGET keys
INCR key
SETNX key value
```

Optional richer structures:

```text
Hash
List
Set
Sorted Set
Streams
```

This HLD focuses primarily on distributed-cache architecture rather than reproducing every Redis command.

---

## 3. Non-Functional Requirements

- sub-millisecond to low-millisecond server-side latency for hot data
- very high QPS
- horizontal scalability
- predictable memory usage
- controlled failover
- cache availability during partial failures
- explicit durability guarantees
- graceful overload behavior
- observability
- tenant isolation

---

## 4. Core Entities

```text
CacheNode
Primary
Replica
Shard
HashSlot
Key
Value
TTL
ClusterMetadata
```

---

## 5. High-Level Architecture

```text
Applications
     |
     v
Cache Client / Proxy
     |
     v
Cluster Metadata / Routing
     |
  +--+---------+---------+
  |            |         |
  v            v         v
Shard A      Shard B   Shard C
Primary      Primary   Primary
  |            |         |
Replica       Replica   Replica
```

---

## 6. Why Cache?

Without cache:

```text
Application -> Database
```

At 100K repeated reads/sec, database may perform expensive repeated work.

With cache:

```text
Application -> Cache -> Database on miss
```

Benefits:

- lower latency
- lower DB load
- absorb traffic bursts
- reduce repeated computation

---

## 7. Cache Is Not Automatically the Source of Truth

For ordinary application caching:

```text
Database = source of truth
Cache = derived acceleration layer
```

If cache disappears, the system should ideally reconstruct state.

Redis-style systems can also be used as primary state stores, but that requires very different durability assumptions.

---

## 8. Cache-Aside Pattern

Read path:

```text
GET cache
  |
 hit -> return
  |
 miss
  v
read DB
  |
  v
populate cache
```

Write path:

```text
write DB
invalidate/update cache
```

Very common because application controls loading.

---

## 9. Cache-Aside Failure

Possible race:

```text
T1 reads old DB value
T2 updates DB
T2 deletes cache
T1 writes old value into cache
```

Result:

```text
stale cache resurrected
```

Mitigations:

- short TTL
- versioning
- delayed double-delete where justified
- event-driven invalidation
- write ordering

---

## 10. Read-Through Cache

Application asks cache.

Cache layer loads backing store automatically on miss.

Advantages:

- simpler application API

Trade-off:

- cache platform must understand loader/backing-store behavior

---

## 11. Write-Through Cache

```text
Application -> Cache -> DB
```

Cache synchronously persists write to backing store.

Advantages:

- cache and DB can stay aligned

Trade-off:

- write latency includes backing-store latency
- cache becomes part of write availability path

---

## 12. Write-Back / Write-Behind

```text
Application -> Cache
Cache -> async DB write later
```

Advantages:

- very fast writes
- batchable DB writes

Risks:

- data loss if cache fails before flush
- ordering/retry complexity

Use only when business semantics tolerate or explicitly protect against this risk.

---

## 13. Write-Around

Writes bypass cache:

```text
Application -> DB
```

Reads populate cache later.

Useful when newly written data is unlikely to be read soon.

---

## 14. TTL

Each key may carry expiry:

```text
session:123 -> expires in 30 min
product:456 -> expires in 5 min
```

TTL bounds staleness and memory growth.

---

## 15. Why TTL Matters

Without TTL/invalidation:

```text
cache may serve obsolete data forever
```

TTL gives eventual self-healing even if an invalidation event is missed.

---

## 16. Expiry Strategies

Two common approaches:

```text
lazy expiry
active expiry
```

Lazy:

```text
check TTL when key is accessed
```

Active:

```text
background sampling/removal of expired keys
```

A hybrid avoids expensive global scans.

---

## 17. TTL Jitter

Bad:

```text
1M keys all expire exactly at 12:00
```

Result:

```text
mass cache miss -> DB spike
```

Add randomized TTL jitter:

```text
300s +/- random window
```

---

## 18. Cache Stampede

Popular key expires:

```text
home-page-config
```

Thousands of clients miss simultaneously and all query DB.

This can take down the backing store.

---

## 19. Single-Flight / Request Coalescing

On cache miss:

```text
first request loads
others wait for same result
```

Only one expensive backend fetch occurs per key.

---

## 20. Stale-While-Revalidate

Instead of hard-expiring immediately:

```text
serve slightly stale value
while one worker refreshes
```

Useful when temporary staleness is better than backend overload.

---

## 21. Cache Penetration

Requests repeatedly ask for data that does not exist.

Example:

```text
GET product:does-not-exist
```

Every request misses and reaches DB.

Mitigations:

- negative caching
- Bloom filters
- input validation

---

## 22. Negative Caching

Cache:

```text
product:999 -> NOT_FOUND for 30 sec
```

Prevents repeated database misses.

TTL should be shorter because object might later be created.

---

## 23. Cache Avalanche

Large percentage of cache becomes unavailable or expires together.

Possible causes:

- cluster restart
- synchronized TTLs
- network outage

Mitigations:

- staggered TTLs
- replication
- warmup
- DB admission control
- local fallback cache

---

## 24. Cache Warming

After deployment/restart, an empty cache causes cold-start pressure.

Preload:

- hottest keys
- configuration
- known popular entities

Do not attempt to preload everything blindly.

---

## 25. In-Memory Storage

Memory provides very low latency.

Trade-off:

```text
RAM is expensive and volatile
```

Therefore cache value size and memory efficiency matter greatly.

---

## 26. Memory Accounting

Memory consumption includes:

```text
key bytes
value bytes
object metadata
allocator overhead
replication buffers
client buffers
fragmentation
```

Raw value size alone underestimates capacity.

---

## 27. Memory Fragmentation

Frequent allocations/deallocations can leave fragmented memory.

Monitor:

```text
allocator allocated vs resident memory
```

A cache may hit OS memory limits despite apparently lower logical data size.

---

## 28. Eviction

When memory is full, choose which keys to remove.

Policies may include:

```text
no eviction
LRU-style
LFU-style
random
TTL-aware
```

---

## 29. LRU

Least Recently Used approximates:

```text
evict keys not accessed recently
```

Good when recency predicts future access.

---

## 30. LFU

Least Frequently Used considers access frequency.

Useful when long-lived hot items should survive temporary inactivity.

---

## 31. LRU vs LFU

LRU adapts quickly to changing working set.

LFU better preserves consistently popular keys.

Real systems often use approximations rather than maintaining perfect global order.

---

## 32. noeviction

If memory fills:

```text
writes fail
```

This can be appropriate when silently evicting important coordination/session data would violate semantics.

---

## 33. Per-Key Maximum Size

One giant cached value can hurt:

- network latency
- memory fragmentation
- replication
- event-loop responsiveness

Set practical limits and avoid using cache as a blob store.

---

## 34. Single-Threaded Command Execution Concept

Redis-style designs often serialize command mutation through an event loop per shard/node.

Benefits:

- simpler data-structure correctness
- avoids fine-grained locking

Networking/background IO may still use multiple threads depending on implementation.

---

## 35. Why Single-Threaded Can Be Fast

In-memory operations are usually tiny.

Avoiding lock contention and context switching can beat complicated concurrent mutation.

The bottleneck often becomes network or memory bandwidth rather than CPU command execution.

---

## 36. Long-Running Command Risk

One expensive command can block other clients on the same execution thread.

Avoid:

- scanning massive keyspaces synchronously
- huge Lua/scripts
- giant collection operations

Use bounded/incremental APIs.

---

## 37. Pipelining

Client sends multiple commands without waiting for each response.

```text
SET A
SET B
GET C
```

Benefits:

- fewer network round trips
- higher throughput

---

## 38. MGET / Batching

Fetching many keys in one request can reduce RTT.

But in a sharded cluster, keys may belong to different shards and require scatter-gather.

---

## 39. Sharding

To scale beyond one machine:

```text
keys -> multiple cache nodes
```

Need deterministic routing.

---

## 40. Simple Modulo Hashing

```text
node = hash(key) % N
```

Problem:

when N changes, many keys remap.

This causes massive cold cache/rebalancing.

---

## 41. Consistent Hashing

Nodes and keys are placed on a logical ring.

Adding/removing one node moves only part of the keyspace.

Useful for client-side distributed caching.

---

## 42. Virtual Nodes

One physical node owns many positions on consistent-hash ring.

Benefits:

- smoother balance
- easier weighted capacity

---

## 43. Hash Slots

Another approach divides keyspace into fixed logical slots.

Conceptually:

```text
hash(key) -> slot
slot -> shard owner
```

Changing cluster membership moves slots rather than changing hash function.

---

## 44. Why Hash Slots Help

Cluster metadata can say:

```text
slots 0-5000 -> shard A
5001-10000 -> shard B
```

Rebalancing moves bounded slot ranges.

---

## 45. Client-Side Routing

Smart client maintains cluster slot map.

```text
key -> slot -> node
```

Advantages:

- no proxy hop

Trade-off:

- clients must handle metadata changes/redirections

---

## 46. Proxy-Based Routing

```text
Application -> Cache Proxy -> shard
```

Advantages:

- simpler clients
- central policy

Trade-off:

- extra hop
- proxy becomes capacity/failure layer

---

## 47. Key Tagging

Sometimes multiple keys must land on same shard.

Example:

```text
user:{123}:profile
user:{123}:settings
```

Hash only tagged portion.

Useful for atomic multi-key operations.

---

## 48. Cross-Shard Operations

Operations across different shards are expensive and often non-atomic.

Avoid data models that require frequent global multi-key transactions.

Design keys for locality.

---

## 49. Rebalancing

When adding node:

```text
move slots/keys gradually
```

Rebalancing consumes:

- network bandwidth
- CPU
- memory

Throttle migration to protect normal traffic.

---

## 50. Dual Routing During Migration

During slot movement, clients/proxy may need to understand:

```text
source owner
new owner
migration state
```

Requests can be redirected safely until ownership completes.

---

## 51. Replication

Each shard may have:

```text
Primary
Replica 1
Replica 2
```

Writes go to primary; replicas copy changes.

---

## 52. Asynchronous Replication

Common cache design:

```text
primary ACKs before replicas fully catch up
```

Advantages:

- lower latency

Risk:

- latest acknowledged writes may be lost during abrupt primary failure

---

## 53. Replica Lag

Replica may trail primary due to:

- network
- CPU
- disk persistence
- large writes

Monitor lag before selecting failover target.

---

## 54. Read Replicas

Can scale reads from replicas.

But replication lag means stale reads.

Do not use replica reads for operations requiring read-your-write unless semantics account for lag.

---

## 55. Failover

When primary fails:

```text
failure detector
-> elect/promote replica
-> publish new ownership
-> clients reconnect
```

---

## 56. Sentinel-Style Coordination

A set of monitor/coordinator nodes can:

- detect primary failure
- reach quorum
- choose replica
- promote it
- notify clients

The key idea is quorum-based failover rather than one monitor making unilateral decisions.

---

## 57. False Failure Detection

Network delay may make healthy primary appear dead.

If failover is too aggressive:

```text
unnecessary primary promotion
```

Timeouts balance failover speed vs false positives.

---

## 58. Split Brain

Network partition:

```text
old primary continues accepting writes
new primary promoted elsewhere
```

Now two writers exist.

This is dangerous.

---

## 59. Split-Brain Mitigation

Possible controls:

- quorum-based promotion
- primary refuses writes when it cannot see enough peers/replicas
- fencing epochs
- topology ownership version

No simple async replication system can simultaneously guarantee zero data loss, low latency, and full availability under every partition.

---

## 60. CAP Trade-Off

Under network partition, choose behavior.

For cache data:

```text
availability may often be preferred
```

For locks/counters/coordination:

```text
consistency may matter more
```

Do not assign one CAP answer to every Redis use case.

---

## 61. Cluster Metadata

Need mapping:

```text
slot -> primary -> replicas
```

Metadata updates require ordering/versioning so clients do not permanently use stale ownership.

---

## 62. Node Failure in Cluster

Suppose shard B primary fails.

Other shards remain healthy.

Promote B replica only; avoid cluster-wide outage.

This is why independent shard failure domains matter.

---

## 63. Replica Promotion

Choose candidate based on:

- liveness
- replication freshness
- failure domain

After promotion, increment topology epoch/version.

---

## 64. Fencing Epoch

Old primary may return after failover.

It must not reclaim write authority simply because it was previously primary.

Use higher cluster/configuration epoch on new ownership.

---

## 65. Persistence

Cache data may optionally survive restart using disk snapshots/logging.

Two broad approaches:

```text
snapshot
append log
```

---

## 66. Snapshot / RDB-Style Persistence

Periodically write point-in-time snapshot.

Advantages:

- compact
- fast restore

Risk:

- data since last snapshot can be lost

---

## 67. Append-Only Log / AOF-Style Persistence

Record mutations sequentially.

Advantages:

- lower potential data loss

Costs:

- more write IO
- larger logs
- rewrite/compaction needed

---

## 68. fsync Trade-Off

Possible policies:

```text
fsync every write
fsync every second
OS-managed
```

Stronger durability increases latency/IO.

For pure cache workloads, maximum durability may be unnecessary.

---

## 69. AOF Rewrite / Compaction

Mutation history grows forever otherwise.

Rewrite current logical state into compact new log, then atomically switch.

Background rewrite must avoid memory/IO spikes affecting serving.

---

## 70. Snapshot Fork Memory Risk

Copy-on-write snapshotting can temporarily increase memory usage while dataset is changing heavily.

Capacity planning must include persistence overhead, not only resident dataset.

---

## 71. Restart Recovery

Node restart:

```text
load snapshot
replay append log
rejoin cluster
catch up from primary if replica
```

Do not serve authoritative traffic before state/topology is valid.

---

## 72. Cache vs Persistent Store Configuration

Pure cache:

- persistence optional
- rebuild from DB

Session/queue/coordination state:

- persistence/durability may be much more important

Same technology can require very different settings by workload.

---

## 73. Hot Key

Example:

```text
GET celebrity:profile
```

millions of requests/sec hit one shard.

Sharding by key does not help because one key maps to one shard.

---

## 74. Hot-Key Mitigations

- application L1 cache
- replicate read-only hot value
- CDN/edge cache
- request coalescing
- split derived data if semantics allow

---

## 75. Local L1 Cache

Architecture:

```text
Application
  |
  +--> L1 local cache
  |
  +--> L2 distributed cache
  |
  +--> DB
```

This protects Redis from ultra-hot reads.

---

## 76. L1 Invalidation

Challenge:

```text
hundreds of app instances hold local copies
```

Options:

- very short TTL
- pub/sub invalidation
- versioned values

Never assume invalidation delivery is perfect without TTL fallback.

---

## 77. Hot Shard

Not one key, but many hot keys hash to same shard.

Mitigations:

- more slots/shards
- better balancing
- weighted nodes
- migrate hot slots

---

## 78. Large Collection Hotspot

One giant sorted set/hash can create hot shard even if member keys differ logically.

Possible redesign:

```text
partition logical collection
```

if operation semantics permit.

---

## 79. Distributed Lock

Simple pattern:

```text
SET lock:resource owner NX PX 30000
```

Only one client gets lock initially.

But safe distributed locking is more subtle than `SETNX` alone.

---

## 80. Lock Ownership Token

Store random unique owner token.

Unlock only if stored token matches caller token.

Otherwise one client could accidentally delete another client's newer lock.

---

## 81. Atomic Unlock

Need atomic compare-and-delete:

```text
if value == myToken:
    delete key
```

Use transaction/script primitive on one authoritative shard.

---

## 82. Lock Lease

TTL prevents permanent deadlock if owner crashes.

But TTL creates another problem:

```text
client continues work after lease expires
```

---

## 83. Fencing Token for Locks

Better design:

```text
lock acquisition -> monotonically increasing fencing token
```

Downstream resource accepts only newest token.

This protects against paused stale clients.

---

## 84. Why Lock Alone Is Not Enough

Client A acquires lock token 10 and freezes.

Lease expires.

Client B gets token 11 and updates resource.

A wakes.

Without fencing, A can overwrite B despite no longer owning lock.

---

## 85. Distributed Lock Across Failover

Async replication can lose a just-created lock during primary failover.

Then another client may acquire same logical lock.

For correctness-critical mutual exclusion, use a coordination system with appropriate consensus semantics or downstream fencing.

---

## 86. Atomic Counters

Single-shard INCR is straightforward.

Globally distributed exact counter is harder.

If exactness is unnecessary, use sharded counters + aggregation.

---

## 87. Rate Limiter Use Case

Redis can hold token-bucket/sliding-window state.

Need atomic script/transaction per limiter key.

But Redis outage policy must be explicit:

```text
fail open?
fail closed?
local emergency limiter?
```

---

## 88. Session Storage

Redis is often used for sessions.

Requirements differ from ordinary cache:

- TTL important
- loss logs users out
- replication may be desirable

Session consistency must match authentication risk.

---

## 89. Pub/Sub

Pub/Sub can broadcast transient messages.

If subscriber is offline, message may be lost depending on primitive.

Do not confuse transient pub/sub with durable queue/log semantics.

---

## 90. Streams / Durable Messaging Extension

A Redis-style stream can retain ordered records and consumer state.

This is a different workload from caching and should be capacity-isolated if critical.

---

## 91. Multi-Tenancy

One tenant can consume:

- memory
- QPS
- large values
- long commands

Use:

- quotas
- namespacing
- max memory per tenant where possible
- dedicated clusters for critical workloads

---

## 92. Noisy Neighbor

Tenant performs massive scans.

Other tenants see latency spikes.

Mitigate with command restrictions, isolation, quotas, and dedicated shards/clusters.

---

## 93. Backpressure

Cache is fast, so clients often retry aggressively when it slows.

If Redis latency rises:

```text
clients retry
-> more load
-> latency increases
-> retry storm
```

Use strict timeouts and bounded retries.

---

## 94. Slow Cache Is Worse Than Dead Cache

Suppose normal cache latency:

```text
2 ms
```

During failure:

```text
2 sec
```

Application thread pools can exhaust waiting for cache.

Use:

- tiny cache-specific timeout
- circuit breaker
- fallback to DB only if DB can absorb it

---

## 95. Cache Failure and Database Meltdown

If cache cluster disappears, all traffic may hit DB.

This is a classic cascading failure.

Need:

- DB admission control
- request shedding
- stale local cache
- progressive cache recovery
- priority isolation

---

## 96. Fail-Open vs Fail-Closed Cache

For product page cache:

```text
fail open to DB
```

For security denylist:

```text
maybe fail closed
```

Behavior depends on business semantics.

---

## 97. Connection Management

Thousands of application instances opening huge connection counts can overwhelm node resources.

Use:

- pooled/persistent connections
- connection limits
- multiplexing where supported

---

## 98. Client Timeouts

Set different budgets for:

```text
connect timeout
command timeout
```

Cache timeout should generally be far below end-user request timeout.

---

## 99. Serialization

Cache serialization affects:

- value size
- CPU
- compatibility

Options:

- JSON
- protobuf
- compact binary

Large verbose objects reduce memory efficiency.

---

## 100. Schema Evolution

Cached serialized objects may outlive application deployment.

Use versioned schemas or tolerate unknown/additive fields.

Otherwise rolling deployment can fail deserialization.

---

## 101. Key Design

Good keys are:

- namespaced
- bounded in length
- stable
- sharding-aware

Example:

```text
product:v3:12345
```

Versioned namespace simplifies invalidation after schema changes.

---

## 102. Delete vs Versioned Namespace

Instead of deleting millions of old cache keys during deployment:

```text
v1:user:123
```

switch to:

```text
v2:user:123
```

Old keys expire naturally.

---

## 103. Cache Invalidation Events

DB update can emit:

```text
PRODUCT_UPDATED productId=123 version=9
```

Consumers invalidate/update related cache entries.

Use outbox if event must be durable with DB commit.

---

## 104. Event Loss

Invalidation event might be delayed/lost.

TTL remains an important safety net.

For strongly fresh reads, use versions or read-through validation rather than relying solely on pub/sub invalidation.

---

## 105. Read-Your-Write

User updates profile, then immediately reads it.

Replica/cache may still have old value.

Solutions:

- invalidate synchronously
- write new cache value after DB commit
- temporarily route to source of truth
- version checks

---

## 106. Cache Consistency Models

Possible guarantees:

```text
best-effort eventual
bounded staleness
read-your-write
strong single-key atomicity
```

Different keys/workloads may choose differently.

---

## 107. Multi-Region Cache

Architecture:

```text
India app -> India cache
US app    -> US cache
```

Keep cache near application to avoid cross-region latency.

---

## 108. Region-Local Derived Cache

If backing DB replicates globally, each region can independently build cache.

Cache invalidation propagates asynchronously.

This is usually simpler than synchronous global cache coherence.

---

## 109. Why Global Active-Active Cache Writes Are Hard

Same key updated concurrently in two regions:

```text
Region A -> value X
Region B -> value Y
```

Need conflict policy.

For pure derived caches, let authoritative DB resolve truth and caches converge.

---

## 110. Geo-Replication

For state that must survive region loss, replication may be asynchronous across regions.

Trade-off:

- lower latency
- non-zero RPO

Do not promise zero data loss without synchronous consensus/replication cost.

---

## 111. Region Failure

If one cache region fails:

- rebuild/fail traffic to another region cautiously
- protect backing DB
- use local stale cache if available

Never redirect an entire region to remote DB/cache without capacity analysis.

---

## 112. Observability

Key metrics:

```text
cache_hit_rate
miss_rate
get_latency_p50/p95/p99
set_latency
memory_used
memory_fragmentation
evictions/sec
expired_keys/sec
connections
network_in/out
replication_lag
failover_count
hot_key_qps
```

---

## 113. Cache Hit Ratio Interpretation

A 95% hit ratio can still be bad if remaining 5% equals 100K DB queries/sec.

Always combine hit ratio with absolute miss QPS and backend capacity.

---

## 114. Per-Key Metrics Caveat

Tracking every key creates enormous cardinality.

Use:

- sampling
- top-N hot-key detection
- sketches

Avoid exploding observability systems.

---

## 115. SLO

Example:

```text
99.9% cache operations < 10 ms
```

But application SLO also needs fallback behavior when cache is unavailable.

---

## 116. Capacity Estimation

Assume:

```text
1B cached objects
average encoded object = 500 bytes
```

Raw values:

```text
~500 GB
```

With keys, metadata, fragmentation, replicas, headroom:

```text
actual cluster RAM may need multiple TB
```

Do not size cache using raw payload bytes only.

---

## 117. QPS Estimation

Suppose:

```text
2M reads/sec
200K writes/sec
```

If one shard safely handles 100K mixed operations/sec at target p99:

```text
need at least dozens of shard primaries after redundancy/headroom
```

Benchmark using real value sizes and commands.

---

## 118. Production Architecture

```text
                        Applications
                             |
                  +----------+----------+
                  |                     |
                  v                     v
              L1 Cache            Cache Client/Proxy
                                        |
                             Cluster Slot Metadata
                                        |
                 +----------------------+----------------------+
                 |                      |                      |
                 v                      v                      v
             Shard A                Shard B                Shard C
             Primary                Primary                Primary
               |                      |                      |
             Replica                Replica                Replica

Backing DB <---- cache miss / rebuild / invalidation pipeline

DB Outbox -> Invalidation Events -> Cache refresh/invalidate workers
```

---

## 119. Principal Engineer Failure Scenario

Assume:

```text
cache cluster handles 3M ops/sec
hit rate = 97%
```

During peak traffic:

```text
one primary enters 1-second latency due to memory pressure
its replicas lag
clients begin timing out and retrying
failover promotes a slightly stale replica
20% of keys on that shard miss after promotion
backing DB CPU jumps from 45% to 95%
```

A weak response:

```text
slow cache
-> retries
-> failover
-> cold misses
-> DB flood
-> DB latency
-> application retries
-> total outage
```

Principal-level response:

1. use strict cache timeouts so threads do not wait seconds
2. immediately suppress retry amplification
3. circuit-break the unhealthy shard/path
4. serve safe stale L1/L2 values where semantics permit
5. protect backing DB with admission control and load shedding
6. promote only sufficiently caught-up replica when consistency matters
7. expect some acknowledged-write loss if replication is asynchronous and communicate that semantic explicitly
8. warm hottest keys first after promotion
9. use single-flight to prevent miss stampedes
10. throttle bulk cache repopulation
11. isolate noncritical tenants/commands
12. inspect memory fragmentation/large keys/eviction pressure before simply adding traffic back
13. rebalance hot slots gradually
14. restore cache capacity before relaxing DB protections
15. verify cluster ownership epoch prevents old primary from accepting authoritative writes
16. recover traffic progressively rather than all at once

Core principle:

> A cache outage is dangerous mainly because of what happens *behind* the cache. The design must protect the database from cache failure just as carefully as it protects the cache from application traffic.

---

## 120. Interview Follow-Up Questions

Be prepared for:

- Cache-aside vs read-through vs write-through vs write-back?
- What is cache stampede?
- What is cache penetration?
- How do TTL jitter and stale-while-revalidate help?
- LRU vs LFU?
- Why can Redis-style single-threaded execution be fast?
- How do you shard cache data?
- Consistent hashing vs hash slots?
- What happens when you add a node?
- How do replicas/failover work?
- What data can be lost with async replication?
- How do you prevent split brain?
- Snapshot vs append-only persistence?
- How do you handle a hot key?
- Why can a slow cache be worse than a dead cache?
- How does cache failure take down a database?
- How do distributed locks work?
- Why do distributed locks need fencing tokens?
- How would you design multi-region caching?
- What metrics matter most?

---

## 121. Interview Summary

The transferable concepts in this design are:

```text
Cache-aside
Read-through / write-through / write-back
TTL / expiry
TTL jitter
Cache stampede
Cache penetration
Negative caching
Stale-while-revalidate
LRU / LFU eviction
Memory fragmentation
Sharding
Consistent hashing
Hash slots
Replication
Replica lag
Failover
Split brain
Fencing epochs
RDB-style snapshots
AOF-style append logs
Hot keys
L1/L2 caching
Distributed locks
Fencing tokens
Backpressure
Circuit breakers
Multi-region derived caches
Observability
```

A strong HLD answer should not describe a distributed cache as merely `GET/SET in memory`. The real design is about **memory economics, key distribution, failure semantics, staleness, protecting the source of truth, and deciding where consistency is actually required**.
