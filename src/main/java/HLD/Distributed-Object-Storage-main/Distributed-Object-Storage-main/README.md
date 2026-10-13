# Distributed Object Storage — S3-Style High-Level Design

A senior/principal-level HLD case study for designing a large-scale S3-style distributed object storage system.

The core challenge is:

> Object storage must make enormous volumes of immutable or append-style blob data **durable, addressable, scalable, and repairable**, while separating metadata correctness from bulk data movement.

---

## 1. Problem Statement

Design a globally scalable object storage system supporting:

- buckets/namespaces
- object PUT/GET/DELETE
- very large objects
- multipart uploads
- object versioning
- checksums
- replication
- erasure coding
- lifecycle policies
- storage tiers
- multi-region replication
- strong durability
- background repair

The system should tolerate:

- disk failure
- node failure
- rack/AZ failure
- bit rot
- network partitions
- metadata node loss
- incomplete multipart uploads
- hot prefixes / hot objects
- region failure

---

## 2. Functional Requirements

Core APIs:

```http
PUT    /buckets/{bucket}/objects/{key}
GET    /buckets/{bucket}/objects/{key}
HEAD   /buckets/{bucket}/objects/{key}
DELETE /buckets/{bucket}/objects/{key}
LIST   /buckets/{bucket}?prefix=...
```

Multipart APIs:

```http
POST /multipart/initiate
PUT  /multipart/{uploadId}/parts/{partNumber}
POST /multipart/{uploadId}/complete
DELETE /multipart/{uploadId}
```

---

## 3. Non-Functional Requirements

- extremely high durability
- high availability
- virtually unlimited namespace scale
- horizontal storage scalability
- high aggregate throughput
- efficient large-object streaming
- cost-efficient cold storage
- integrity verification
- background repair
- multi-region resilience
- predictable metadata semantics

---

## 4. Core Domain Model

```text
Bucket
ObjectKey
ObjectVersion
ObjectMetadata
Part
Chunk
StorageNode
PlacementGroup
Replica / Erasure Fragment
Checksum
LifecyclePolicy
```

---

## 5. High-Level Architecture

```text
Clients
   |
   v
API Gateway / Auth
   |
   +--> Metadata Service
   |
   +--> Placement Service
   |
   +--> Object Data Path
             |
             v
        Storage Nodes
             |
     +-------+--------+
     |                |
Replication      Erasure Coding
     |                |
     v                v
Background Scrub / Repair
```

---

## 6. Metadata Plane vs Data Plane

Metadata plane handles:

```text
bucket
object key
version
size
checksum
storage locations
ACL
lifecycle state
```

Data plane handles:

```text
actual bytes
```

Keep large payload transfer out of metadata databases.

---

## 7. Why Separate Them?

A 5 GB object should not pass through a metadata service that only needs to update a few hundred bytes of state.

Separation enables:

- independent scaling
- direct data transfer
- smaller metadata latency
- lower control-plane blast radius

---

## 8. Object Identity

Logical identity:

```text
(bucket, objectKey, versionId)
```

Example:

```text
photos/user123/avatar.jpg
```

Object keys are opaque strings, not real directories.

---

## 9. Prefixes Are Logical

Key:

```text
images/2026/08/photo.jpg
```

may look hierarchical but is fundamentally an ordered string namespace.

Prefix listing is implemented by metadata/index structures.

---

## 10. PUT Object Flow

```text
Client
  |
  v
Authenticate
  |
  v
Create upload intent
  |
  v
Placement decision
  |
  v
Stream bytes to storage nodes
  |
  v
Verify checksums
  |
  v
Commit object metadata atomically
```

---

## 11. Commit Point

Do not expose an object as successfully stored before durability requirements are met.

Example policy:

```text
ACK only after enough replicas/fragments are durably written
+
metadata commit succeeds
```

---

## 12. Incomplete Upload

If client disconnects halfway:

```text
staged chunks exist
but object metadata not committed
```

Those chunks are garbage candidates, not visible objects.

---

## 13. Upload Intent

Create durable upload session:

```text
uploadId
bucket
key
expected metadata
state=IN_PROGRESS
```

This supports multipart and cleanup.

---

## 14. Multipart Upload

Large objects are split into independent parts.

Example:

```text
5 TB object
-> thousands of parts
```

Benefits:

- parallel upload
- retry only failed parts
- resumability

---

## 15. Multipart Flow

```text
Initiate
  |
Upload part 1
Upload part 2
Upload part 3
  |
Complete
  |
Atomic object version published
```

---

## 16. Multipart Part Idempotency

Uploading same:

```text
uploadId + partNumber
```

again should replace or return the same logical part according to API semantics.

Completion itself must be idempotent.

---

## 17. Multipart Completion

Client sends ordered list of parts/checksums.

Metadata service validates all required parts then atomically creates final object version.

Do not expose partially assembled object.

---

## 18. Abandoned Multipart Uploads

Users may never call complete/abort.

Background lifecycle job removes abandoned staged parts after configured age.

---

## 19. Large Object Streaming

GET should stream data incrementally.

Do not load entire object into application memory.

Use ranged/chunked reads.

---

## 20. Range Requests

Support:

```http
Range: bytes=1000000-1999999
```

Useful for:

- media playback
- resumable downloads
- parallel clients

---

## 21. Chunking

Internally, large object may be split into fixed-size chunks.

Example:

```text
64 MB / 256 MB chunks
```

Placement and repair can operate at chunk granularity.

---

## 22. Fixed vs Variable Chunking

Fixed-size:

- simple addressing
- easy erasure coding

Content-defined:

- better dedup opportunities
- more complexity

Object storage often favors fixed-size chunks unless dedup is a core goal.

---

## 23. Checksums

Every part/chunk should have checksum.

Examples:

```text
CRC32C
SHA-256
```

Purpose:

- detect transmission corruption
- detect disk bit rot
- verify repair

---

## 24. End-to-End Integrity

Best design verifies checksum at multiple boundaries:

```text
client -> ingress
storage write
storage read
replication
repair
```

---

## 25. Bit Rot

Disk may return corrupt bytes without total disk failure.

Background scrubber periodically reads blocks and verifies checksum.

Corrupt fragments are reconstructed from healthy copies/fragments.

---

## 26. Storage Node

Storage node manages local disks and exposes chunk operations:

```text
writeChunk
readChunk
deleteChunk
verifyChunk
```

It should report capacity/health to placement system.

---

## 27. Placement Service

Determines where data fragments live.

Inputs:

- shard/placement group
- available capacity
- AZ/rack diversity
- device health
- storage class

---

## 28. Failure-Domain Awareness

Never place all replicas on one rack.

Example:

```text
Replica 1 -> AZ A
Replica 2 -> AZ B
Replica 3 -> AZ C
```

Durability comes from independent failures.

---

## 29. Replication

Simple policy:

```text
store 3 complete copies
```

Advantages:

- simple
- fast reads
- fast repair

Cost:

```text
3x raw storage
```

---

## 30. Erasure Coding

Encode object/chunk into:

```text
k data fragments + m parity fragments
```

Example:

```text
10 data + 4 parity
```

Can reconstruct from any sufficient subset.

---

## 31. Why Erasure Coding?

Compared with 3x replication:

- much lower storage overhead
- similar durability if engineered correctly

Trade-offs:

- more CPU
- more network on repair/read under failures
- more complex implementation

---

## 32. Replication vs Erasure Coding

Replication often fits:

- hot/recent data
- low-latency workloads
- small objects

Erasure coding often fits:

- large/cold objects
- cost-sensitive durable storage

A system can use both by storage class/data age.

---

## 33. Small Object Problem

Millions of tiny objects can create huge metadata overhead.

Potential optimizations:

- pack small objects into larger storage containers
- compact metadata structures

Be careful with update/delete complexity.

---

## 34. Object Immutability Advantage

Object versions are often immutable once committed.

Updates create new version/object.

This greatly simplifies replication and integrity.

---

## 35. Overwrite

Logical overwrite:

```text
PUT same bucket/key again
```

creates a new committed generation/version internally.

Atomic metadata pointer switches current version.

---

## 36. Versioning

If bucket versioning enabled:

```text
key -> version1
    -> version2
    -> version3(current)
```

Old versions remain retrievable until lifecycle deletion.

---

## 37. Delete Marker

With versioning, DELETE can create logical delete marker rather than physically deleting all prior data immediately.

---

## 38. Physical Deletion

Actual chunk deletion is asynchronous after:

- no metadata references remain
- retention/legal rules permit

Use garbage collector.

---

## 39. Reference Safety

Never delete storage chunk merely because current object version changed.

Other versions/uploads may still reference it.

---

## 40. Metadata Store

Metadata requires:

- strong key-level consistency
- high write/read throughput
- ordered listing/indexing
- sharding

Possible implementations:

- distributed SQL
- strongly consistent KV
- B-tree/LSM-based metadata service

---

## 41. Metadata Partition Key

Possible logical key:

```text
bucketId + objectKey
```

Need support both exact lookup and prefix listing.

---

## 42. Ordered Metadata Index

LIST with prefix benefits from ordered keyspace.

Example:

```text
bucketA/images/...
```

Range scans return matching keys.

---

## 43. Hot Prefix Problem

If partitioning maps lexical prefixes directly to nodes:

```text
logs/2026/08/12/...
```

all current writes may hit one metadata partition.

---

## 44. Hot Prefix Mitigation

Use:

- hash-prefix partitioning internally
- adaptive partition splitting
- sufficient partition fan-out
- routing layer that hides physical layout from API ordering

---

## 45. Adaptive Partition Splitting

If metadata partition grows/hot:

```text
range A-Z
-> split A-M and N-Z
```

or use hash/range hybrid.

Splits happen online with versioned routing.

---

## 46. Metadata Routing

Client/gateway maps object key to metadata partition via partition map.

Partition map itself requires consistent/versioned distribution.

---

## 47. Strong Read-After-Write

Desired semantic:

```text
successful PUT
-> subsequent GET sees new object
```

Requires metadata commit/serving path to avoid stale routing/read replicas.

---

## 48. Strong LIST Semantics

If system promises strongly consistent listing, LIST must reflect completed PUT/DELETE operations according to documented ordering.

This is harder than eventual listing but useful for application correctness.

---

## 49. Read Path

```text
GET bucket/key
  |
  v
Metadata lookup
  |
  v
Current version + fragment locations
  |
  v
Read from healthy storage node(s)
  |
  v
Verify checksum
  |
  v
Stream to client
```

---

## 50. Replica Read Selection

Choose replica based on:

- health
- latency
- locality
- load

Can issue hedged read if one replica is slow.

---

## 51. Erasure-Coded Read

Normal case may read only data fragments.

If fragment unavailable/corrupt:

```text
read parity/other fragments
-> reconstruct missing data
```

---

## 52. Read Repair

If GET discovers corrupt/missing replica:

- serve from healthy data
- asynchronously repair bad copy

Do not unnecessarily block client on full repair.

---

## 53. Write Quorum Concept

For replication factor 3, ACK policy might require:

```text
all 3
or
2 of 3
```

Trade-off:

- durability
- latency
- availability

---

## 54. Why Quorum Alone Is Not Enough

Placement must also ensure replicas are independent.

Three replicas on one power domain are not strong durability.

---

## 55. Storage Node Failure

Failure detector marks node unavailable.

Placement/repair service identifies under-replicated chunks/fragments.

---

## 56. Repair Queue

Priority classes:

```text
critical: only minimum fragments remain
high: under-replicated
normal: rebalancing
low: scrub optimization
```

Repair the most durability-threatening objects first.

---

## 57. Repair Bandwidth

Repair consumes network/disk.

If unlimited, recovery can hurt foreground traffic.

Throttle repair with reserved bandwidth.

---

## 58. Rebuild Storm

A full node/AZ failure can trigger petabytes of reconstruction.

Do not rebuild everything instantly if failure may be temporary.

Use delayed/graded repair policy based on durability margin.

---

## 59. Anti-Entropy

Background scanners compare metadata and physical fragments to detect:

- missing chunks
- orphan chunks
- checksum mismatch
- stale placement state

---

## 60. Scrubbing

Periodic full/partial data integrity scan.

Critical because latent corruption + later device failure can exceed redundancy.

---

## 61. Durability Math Intuition

Durability depends on:

- independent failure probabilities
- replication/EC scheme
- detection time
- repair time
- correlated failures

Do not quote durability numbers without stating assumptions.

---

## 62. Mean Time to Repair Matters

The longer data remains under-replicated, the larger the window for permanent loss.

Repair latency is a durability metric.

---

## 63. Correlated Failure

Examples:

- bad firmware
- rack power loss
- AZ outage
- operator error

Replication across identical failure domains may not help.

---

## 64. Placement Groups

Group objects/chunks into logical placement domains for scalable metadata and balanced distribution.

Placement should avoid too much correlation between fragments.

---

## 65. Capacity Balancing

New nodes join with empty disks.

Move data gradually to balance utilization.

Avoid huge migrations that saturate cluster.

---

## 66. Consistent/Rendezvous Hashing

Can map chunks/placement groups to candidate nodes with limited movement when membership changes.

But practical placement must also account for capacity/failure domains.

---

## 67. Weighted Placement

Larger nodes can receive proportionally more data.

Health and utilization should influence placement.

---

## 68. Data Locality

Object storage prioritizes durability/throughput over compute locality in many cases.

But keeping fragments near regional clients can improve latency/cost.

---

## 69. Storage Classes

Example classes:

```text
HOT
STANDARD
INFREQUENT_ACCESS
ARCHIVE
```

Each can differ in:

- redundancy
- media
- access latency
- cost

---

## 70. Lifecycle Policies

Example:

```text
after 30 days -> infrequent access
after 180 days -> archive
after 7 years -> delete
```

Transitions are asynchronous background workflows.

---

## 71. Lifecycle State Machine

```text
STANDARD
  |
  v
TRANSITION_PENDING
  |
  v
ARCHIVE
```

Do not delete source copy until destination state is durably verified.

---

## 72. Archive Retrieval

Cold archive may require delayed restore.

Model restore job explicitly:

```text
RESTORE_REQUESTED
RESTORING
RESTORED_TEMPORARILY
```

---

## 73. Object Lock / Retention

Some workloads require WORM-like retention:

```text
cannot delete/overwrite until retention date
```

Enforce in metadata authorization path.

---

## 74. Legal Hold

Legal hold prevents deletion regardless of lifecycle until explicitly released by authorized workflow.

Audit every change.

---

## 75. Encryption

Use:

- TLS in transit
- encryption at rest
- per-object/data encryption keys
- KMS-managed wrapping keys

---

## 76. Envelope Encryption

Typical pattern:

```text
object encrypted with data key
Data key encrypted with KMS key
```

Store encrypted data key in metadata.

---

## 77. Server-Side vs Client-Side Encryption

Server-side:

- platform manages encryption

Client-side:

- storage never sees plaintext

Trade-off affects features such as checksum/search/dedup.

---

## 78. Access Control

Authorization may use:

- bucket policies
- object ACLs
- IAM-like principals
- signed URLs

Check before exposing metadata/data locations.

---

## 79. Pre-Signed URLs

Allow temporary direct data access without exposing permanent credentials.

Token should bind:

```text
object
method
expiry
optional headers
```

---

## 80. Do Not Expose Physical Node Addresses Directly Forever

Use signed temporary endpoints/tokens so placement can change without breaking clients.

---

## 81. Direct Upload Path

Possible architecture:

```text
Client -> metadata/auth service
       <- signed upload plan
Client -> storage nodes directly
```

Reduces gateway bandwidth bottleneck.

---

## 82. Proxy Upload Path

Alternative:

```text
Client -> object gateway -> storage nodes
```

Simpler clients/control, but gateway must handle massive bandwidth.

---

## 83. Data Plane Scalability

At PB/s aggregate throughput, gateways cannot all proxy every byte centrally.

Scale-out/direct data paths become essential.

---

## 84. GET Hot Object

One viral object receives millions of reads.

Mitigations:

- CDN
- edge caching
- replica fan-out
- object chunk caching

Object storage should not serve all internet hot reads directly from origin.

---

## 85. CDN Integration

```text
User -> CDN -> object storage origin
```

CDN absorbs repeated public/static content reads.

---

## 86. Origin Shield

A middle cache layer protects object storage from many CDN edge misses for same object.

---

## 87. Metadata Hot Key

Even if data is CDN-cached, HEAD/auth metadata lookup for one object can become hot.

Cache immutable metadata carefully with version/TTL.

---

## 88. LIST Scalability

Listing billions of objects requires pagination.

Never return whole bucket.

Use continuation token/cursor.

---

## 89. Stable Pagination

Continuation token may encode:

```text
bucket
prefix
lastKey
snapshot/version info
```

Exact consistency semantics should be documented.

---

## 90. Object Metadata Cache

Cache:

```text
key -> current version/size/location hints
```

But cache must not violate read-after-write semantics.

Use invalidation/versioning.

---

## 91. Location Metadata Evolution

Physical fragment locations change during repair/rebalance.

Clients should not treat locations as permanent.

Use versioned placement metadata or indirection.

---

## 92. Two-Level Metadata

Possible design:

```text
Object metadata -> placement group ID
Placement group metadata -> physical nodes
```

This avoids rewriting every object record when nodes move.

---

## 93. Metadata Replication

Metadata is small but critical.

Use strongly replicated consensus-based partitions.

Losing object metadata can make perfectly healthy bytes inaccessible.

---

## 94. Metadata Backup

Replication is not enough against operator/software corruption.

Use snapshots/backups/audit logs.

---

## 95. Metadata Corruption Scenario

If metadata accidentally deletes pointers but storage fragments remain:

anti-entropy/orphan retention window may allow recovery.

Do not eagerly erase unreferenced fragments immediately.

---

## 96. Garbage Collection Grace Period

Mark object/chunk unreachable first.

Delete physically only after safety window.

Helps recover from metadata bugs/races.

---

## 97. DELETE Semantics

Successful DELETE updates metadata first.

Physical storage cleanup can happen later.

User-visible deletion does not require synchronous disk erasure unless compliance demands it.

---

## 98. Secure Erasure

For encrypted storage, deleting encryption key can provide rapid cryptographic erasure semantics, subject to threat/compliance model.

Physical media cleanup still follows lifecycle.

---

## 99. Data Deduplication

Optional:

```text
content hash -> shared chunk
```

Advantages:

- lower storage

Risks:

- reference management
- privacy side channels
- GC complexity

Not required for baseline object storage.

---

## 100. Content-Addressed Chunks

Chunk key:

```text
hash(bytes)
```

Useful for integrity and dedup, but object namespace still needs metadata mapping logical key to chunk list.

---

## 101. Metadata Transaction

Object publish may need atomic update of:

```text
new version
current pointer
multipart completion state
```

Keep this in one strongly consistent partition/transaction where possible.

---

## 102. Idempotent PUT

Clients may retry after timeout.

Options:

- request id/idempotency key
- conditional writes
- deterministic multipart upload IDs

Avoid creating unintended duplicate logical versions if API promises idempotency.

---

## 103. Conditional Writes

Support semantics such as:

```text
If-Match: version/etag
If-None-Match: *
```

Useful for optimistic concurrency.

---

## 104. ETag / Object Digest

ETag can represent object/version identity or content digest depending implementation.

Do not assume it is always a raw MD5 for multipart/encrypted objects.

---

## 105. Multi-Region Architecture

```text
Global DNS / Endpoint
        |
   +----+----+
   |         |
 Region A  Region B
   |         |
 Local object clusters
```

Buckets may be region-owned or configured for replication.

---

## 106. Region Ownership

Simpler model:

```text
bucket -> home region
```

Writes go to home region.

Other regions can serve replicated reads if semantics permit.

---

## 107. Cross-Region Replication

After local commit:

```text
replication event -> destination region
```

Usually asynchronous for latency/cost reasons.

---

## 108. Cross-Region RPO

Async replication means recent objects may not yet exist in secondary when primary region is lost.

Define explicit RPO.

---

## 109. Synchronous Multi-Region Writes

Can provide lower RPO but adds WAN latency and coupled availability.

Use only when product requires it.

---

## 110. Multi-Region Conflict

If same object key can be written independently in two regions:

```text
A writes version X
B writes version Y
```

Need deterministic conflict/version ordering.

Home-region ownership avoids many such conflicts.

---

## 111. Region Failover

If home region fails:

- promote secondary metadata/data replica
- update ownership epoch
- reroute endpoints
- reject stale old-region writes if it recovers partitioned

---

## 112. Fencing Epoch

Old region/node may believe it still owns bucket.

Metadata mutations carry ownership epoch.

Newer epoch wins; stale writes rejected.

---

## 113. Disaster Recovery

Define:

```text
RPO
RTO
```

for:

- metadata
- object data
- lifecycle state
- replication progress

---

## 114. Replication Status Tracking

Per object/version:

```text
LOCAL_COMMITTED
REPLICATION_PENDING
REPLICATED
FAILED
```

Useful for DR observability/compliance.

---

## 115. Multi-Region Delete

Deletes/version markers must replicate just like PUTs.

Beware resurrecting deleted objects from lagging region.

---

## 116. Tombstone Retention

Keep deletion markers long enough for all replicas to observe them.

Otherwise a stale copy can reappear during reconciliation.

---

## 117. Background Reconciliation

Cross-region scanner compares manifests/versions to detect missing replication events.

Never rely only on event delivery.

---

## 118. Eventing

Object changes can emit events:

```text
OBJECT_CREATED
OBJECT_DELETED
OBJECT_RESTORED
```

Consumers:

- image processing
- analytics
- indexing

Use outbox/durable change log if events must not be lost.

---

## 119. Event Delivery Semantics

Typically at-least-once.

Consumers dedupe using:

```text
bucket + key + version + eventId
```

---

## 120. Backpressure

If storage nodes or metadata partitions are saturated:

- reject/throttle uploads
- queue only bounded work
- protect reads
- isolate tenants

Do not buffer unlimited object bytes in gateway memory.

---

## 121. Multi-Tenant Isolation

Use:

- per-tenant bandwidth quotas
- request-rate limits
- storage quotas
- multipart limits
- repair isolation

One tenant uploading petabytes should not starve others.

---

## 122. Slow Client Problem

A client uploads at 1 KB/sec for hours.

Use:

- idle timeout
- bounded connection resources
- multipart/resumable protocol

Do not pin scarce server memory indefinitely.

---

## 123. Admission Control

If cluster is near full capacity:

```text
stop accepting new writes before disks reach 100%
```

Maintain repair/headroom reserve.

---

## 124. Why Free Space Headroom Matters

Repair/rebalancing requires free destination capacity.

A 99.9% full object store may be unable to recover safely.

---

## 125. Disk Watermarks

Example conceptual thresholds:

```text
70% normal
80% rebalance warning
90% reject new noncritical writes
```

Exact values depend on architecture.

---

## 126. Node Decommission

Before removing node:

- copy/reconstruct its fragments elsewhere
- verify target redundancy
- update placement metadata
- only then retire node

---

## 127. Rolling Upgrade

Upgrade storage nodes gradually while maintaining redundancy.

Do not take multiple replicas from same placement group offline simultaneously.

---

## 128. Schema / Format Versioning

On-disk chunk format may evolve.

Store version headers and support rolling readers/writers.

Avoid mandatory global rewrite.

---

## 129. Repair Compatibility

New nodes must understand old fragment formats during long migrations.

Object storage retains data for years; format compatibility is long-lived.

---

## 130. Observability

Key metrics:

```text
PUT_qps
GET_qps
PUT_latency_p95/p99
GET_latency_p95/p99
bytes_in/sec
bytes_out/sec
metadata_latency
under_replicated_objects
repair_backlog
repair_time
checksum_failures
scrub_errors
disk_utilization
cross_region_replication_lag
```

---

## 131. Durability Metrics

Track:

```text
objects below target redundancy
objects at minimum recoverable fragments
mean_time_to_repair
latent_corruption_rate
```

These are more important than average disk utilization alone.

---

## 132. Capacity Estimation

Assume:

```text
10 EB logical data
```

At 3x replication:

```text
30 EB physical before metadata/headroom
```

With 10+4 erasure coding:

```text
14/10 = 1.4x overhead
-> ~14 EB physical before other overheads
```

This illustrates why erasure coding dominates economics at massive scale.

---

## 133. Throughput Estimation

Suppose:

```text
100M PUTs/day
1B GETs/day
```

Average request rate is manageable, but object sizes vary from bytes to TBs.

Therefore capacity planning must prioritize **bytes/sec and concurrent streams**, not only QPS.

---

## 134. Metadata vs Data Scaling

1 billion tiny objects may stress metadata more than network.

1 million 100-GB objects may stress bandwidth/capacity more than metadata.

Design both dimensions separately.

---

## 135. Principal Engineer Failure Scenario

Assume cluster stores:

```text
5 EB logical data
10+4 erasure coding
```

Then:

```text
one entire rack fails
another rack shows elevated checksum corruption due to firmware bug
repair workers start reconstructing aggressively
network utilization rises to 95%
foreground GET p99 jumps 8x
metadata service begins timing out because health updates explode
cross-region replication also falls behind
```

A weak response can cause:

```text
rack failure
-> repair storm
-> network saturation
-> GET timeouts
-> client retries
-> even more load
-> metadata overload
-> broader cluster outage
```

Principal-level response:

1. prioritize objects closest to losing recoverability
2. throttle normal repair/rebalancing to preserve foreground bandwidth
3. isolate health/repair metadata traffic from request metadata path
4. stop or slow noncritical bulk uploads
5. suppress client retry amplification with Retry-After/backoff
6. route reads to healthy replicas/fragments
7. use reconstructed reads only where necessary because they consume more network/CPU
8. immediately quarantine suspect firmware/device class
9. increase scrub priority for data sharing the suspected failure domain
10. preserve enough free space for emergency reconstruction
11. pause optional compaction/lifecycle migrations
12. keep cross-region replication prioritized for newest/high-value objects according to policy
13. monitor objects at minimum recoverable fragment count as top durability SLO
14. do not trigger full-cluster rebalance during unstable failure
15. replace bad hardware gradually and verify checksum after repair
16. recover foreground traffic before resuming bulk repair/migration rates

Core principle:

> In object storage, repair is part of the normal system, not an emergency exception. The dangerous moment is when recovery traffic competes with foreground traffic and amplifies the original failure.

---

## 136. Production Architecture

```text
                            Clients
                               |
                       API Gateway / Auth
                               |
             +-----------------+------------------+
             |                                    |
             v                                    v
      Metadata Service                     Upload / Read Planner
             |                                    |
      Strong Metadata DB                         |
             |                              Placement Service
             |                                    |
             +------------------+-----------------+
                                |
                    +-----------+-----------+
                    |                       |
                    v                       v
                Storage Nodes           Storage Nodes
                    |                       |
             Replicas / EC Fragments across AZs
                    |
                    v
            Scrubber / Repair Service
                    |
                    v
              Lifecycle / Tiering
                    |
                    v
           Cross-Region Replication
```

---

## 137. Interview Follow-Up Questions

Be prepared for:

- Why separate metadata and data planes?
- How does multipart upload work?
- How do incomplete uploads get cleaned up?
- Why use checksums?
- How do you detect bit rot?
- Replication vs erasure coding?
- Why might hot data use replication and cold data use EC?
- How do you design metadata partitioning?
- What is a hot-prefix problem?
- How do LIST operations scale?
- How do strong read-after-write semantics work?
- What happens when a storage node fails?
- How does background repair work?
- Why must repair be throttled?
- What is anti-entropy/scrubbing?
- How does object versioning work?
- How do lifecycle tiers work?
- How do cross-region replication and RPO work?
- How do you avoid resurrecting deleted objects?
- What metrics indicate durability risk?

---

## 138. Interview Summary

The transferable concepts in this design are:

```text
Metadata / data plane separation
Object namespaces
Multipart upload
Idempotent completion
Chunking
Checksums
Bit-rot detection
Replication
Erasure coding
Failure-domain-aware placement
Strong metadata consistency
Prefix/range partitioning
Hot-prefix mitigation
Versioning
Delete markers
Garbage collection
Scrubbing / anti-entropy
Background repair
Repair prioritization
Storage tiering
Lifecycle transitions
Encryption / KMS
Pre-signed URLs
Multi-region replication
RPO / RTO
Tombstone retention
Backpressure
Capacity headroom
Observability
```

A strong HLD answer should emphasize that object storage is primarily a **durability and placement system**. The bytes themselves are easy to store; the hard part is proving where they are, detecting corruption, restoring redundancy quickly, keeping metadata correct, and ensuring recovery traffic does not destroy foreground availability.
