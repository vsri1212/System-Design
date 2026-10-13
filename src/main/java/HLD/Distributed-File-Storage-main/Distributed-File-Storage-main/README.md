# Distributed File Storage — Google Drive / Dropbox High-Level Design

A senior/principal-level HLD case study for designing a Google Drive / Dropbox-style distributed file storage and synchronization platform.

## 1. Problem Statement

Design a cloud file storage system where users can:

- upload files and folders
- download files
- sync files across multiple devices
- resume interrupted uploads/downloads
- share files and folders
- maintain versions
- recover deleted files
- support offline edits
- detect conflicts
- handle very large files
- scale globally

The challenge is not simply storing bytes. The hard parts are separating metadata from file content, chunking large files, synchronizing devices efficiently, maintaining correctness under concurrent edits, and surviving regional/storage failures.

---

## 2. Functional Requirements

Users should be able to:

- create folders
- upload files
- update files
- rename/move files
- delete/restore files
- download files
- share with users/groups/links
- view version history
- sync changes automatically
- continue after reconnect
- work offline and reconcile later

Example APIs:

```http
POST /files/initiate-upload
PUT  /uploads/{uploadId}/chunks/{chunkIndex}
POST /uploads/{uploadId}/complete
GET  /files/{fileId}/metadata
GET  /files/{fileId}/download
GET  /changes?cursor=...
POST /files/{fileId}/share
```

---

## 3. Non-Functional Requirements

- very high durability
- scalable storage capacity
- resumable uploads/downloads
- efficient multi-device synchronization
- low metadata latency
- support large files
- horizontal scaling
- idempotent operations
- secure sharing
- version history
- multi-region resilience
- strong metadata correctness where required

---

## 4. Core Architectural Principle

Separate:

```text
Metadata Plane
+
Blob / Chunk Storage Plane
```

Metadata includes:

- file ID
- name
- owner
- parent folder
- size
- version
- chunk references
- permissions
- timestamps

Blob storage contains the actual bytes.

Do not store multi-GB file contents directly inside the metadata database.

---

## 5. High-Level Architecture

```text
Clients
   |
   v
API Gateway
   |
   +--> Metadata Service ------> Metadata DB
   |
   +--> Upload Service --------> Chunk/Object Storage
   |
   +--> Sync Service ----------> Change Log
   |
   +--> Sharing/Auth Service

Chunk/Object Storage
   |
   v
Replication / Erasure Coding

Metadata Changes
   |
   v
Kafka/Event Bus
   |
   +--> Notifications
   +--> Search Index
   +--> Audit
   +--> Analytics
```

---

## 6. File Upload Flow

```text
Client
  |
  | initiate upload
  v
Upload Service
  |
  | uploadId + chunk policy
  v
Client uploads chunks
  |
  v
Chunk/Object Storage
  |
  v
Complete Upload
  |
  v
Metadata transaction publishes new file version
```

Large file bytes should usually go directly to object/chunk storage rather than through general API servers.

---

## 7. Why Chunk Files?

Suppose a user uploads a 10 GB file and the connection fails at 9.8 GB.

Without chunking:

```text
restart entire upload
```

With chunking:

```text
only missing chunks are retried
```

Benefits:

- resumable transfer
- parallel upload/download
- deduplication
- efficient delta sync
- lower retry cost
- independent integrity checks

---

## 8. Chunk Model

Example:

```text
File F1
  |
  +--> chunk A
  +--> chunk B
  +--> chunk C
```

Each chunk may have:

```text
chunkHash
size
storageLocation
checksum
replicationState
```

File metadata stores an ordered chunk manifest.

---

## 9. Fixed-Size vs Content-Defined Chunking

Fixed-size chunking:

```text
4 MB chunks
```

Simple and predictable.

Problem: inserting a few bytes near the start shifts all later chunk boundaries.

Content-defined chunking uses rolling hashes to choose boundaries based on file content.

Advantages:

- better deduplication
- better delta sync after insertions

Disadvantages:

- more CPU
- more complexity

---

## 10. Resumable Upload

Track uploaded chunks:

```text
uploadId
fileId
expectedVersion
receivedChunks
expiresAt
```

Client can query:

```text
which chunks are missing?
```

Then upload only missing pieces.

---

## 11. Upload Idempotency

Network retries may repeat upload initiation or completion requests.

Use:

```text
userId + clientUploadId
```

as an idempotency key.

Completion should be idempotent: repeating it must not create duplicate file versions.

---

## 12. Chunk Integrity

Each chunk should carry a cryptographic hash/checksum.

Flow:

```text
client calculates hash
server verifies hash
storage persists chunk
```

Corrupted chunks are rejected or repaired.

---

## 13. Content-Addressable Storage

A chunk can be addressed by content hash:

```text
SHA256(chunkBytes) -> chunkId
```

If the same chunk already exists, it may be reused.

This enables deduplication.

---

## 14. Deduplication

Two users upload identical 100 MB files.

Naive system:

```text
store 200 MB
```

Deduplicated system:

```text
store shared chunks once
metadata references them twice
```

Dedup can happen:

- file-level
- chunk-level

Chunk-level is more powerful but more complex.

---

## 15. Dedup Security Caveat

Global deduplication can leak whether content already exists.

Example:

```text
attacker uploads known confidential file
server instantly says all chunks already exist
```

This can become a side channel.

Security-sensitive systems may limit dedup scope by tenant/user or hide existence responses.

---

## 16. Chunk Reference Counting

If multiple files reference the same chunk, deleting one file must not delete the shared chunk.

Maintain:

```text
chunkId -> referenceCount
```

or an equivalent garbage-collection index.

Physical deletion happens only when no live file version references the chunk.

---

## 17. Garbage Collection

File metadata may be deleted immediately from user view while chunks are deleted later.

Why asynchronous cleanup?

- shared chunks
- version retention
- recovery windows
- race safety

Use a background GC process.

---

## 18. Metadata Model

Example file metadata:

```text
file_id
owner_id
parent_folder_id
name
latest_version_id
created_at
updated_at
deleted_at
```

Version metadata:

```text
version_id
file_id
version_number
chunk_manifest
size
checksum
created_by
created_at
```

---

## 19. Folder Hierarchy

A folder is primarily metadata.

Example:

```text
folder_id
parent_folder_id
name
owner_id
```

Do not physically move chunks when a file moves between folders.

Only metadata relationships change.

---

## 20. Rename / Move

Rename:

```text
update name
```

Move:

```text
update parent_folder_id
```

The blob/chunk content remains unchanged.

This is an important control-plane vs data-plane separation.

---

## 21. Metadata Consistency

Strong consistency is generally desirable for:

- file rename/move
- latest version pointer
- sharing permissions
- delete/restore

Users should not see two conflicting folder states caused by uncontrolled concurrent metadata writes.

---

## 22. Optimistic Concurrency Control

Suppose two devices update the same file metadata.

Use:

```text
version = 18
```

Update only if:

```text
WHERE version = 18
```

Then increment to 19.

If another writer already changed it, return a conflict.

---

## 23. File Versioning

Never overwrite a file's previous content in place conceptually.

Instead:

```text
File F
  |
  +--> V1
  +--> V2
  +--> V3 latest
```

The latest-version pointer changes atomically.

Old versions can be retained according to product policy.

---

## 24. Atomic Version Publish

Chunks may finish uploading before metadata publication.

Only after all chunks are verified should the system atomically publish:

```text
new version manifest
+
latest_version_id
```

Until then, users should continue seeing the previous valid version.

---

## 25. Sync Problem

A user's laptop changes a file.

Their phone should learn about the change without rescanning every file.

Naive approach:

```text
compare entire directory tree every few seconds
```

Too expensive.

Better:

```text
per-user change log
```

---

## 26. Change Log

Every metadata mutation emits a monotonic change entry:

```text
changeSeq=1001 FILE_UPDATED F1
changeSeq=1002 FILE_MOVED   F9
changeSeq=1003 FILE_DELETED F3
```

A device stores:

```text
lastSyncCursor = 1000
```

Then requests:

```text
GET /changes?after=1000
```

---

## 27. Delta Sync

If a large file changes slightly, re-uploading the entire file is inefficient.

Client can compare chunk hashes.

Example:

```text
Old: A B C D E
New: A B X D E
```

Only chunk X needs upload.

Metadata publishes a new manifest:

```text
A B X D E
```

---

## 28. Multi-Device Sync

Each device keeps its own cursor:

```text
(userId, deviceId) -> lastSyncCursor
```

Device reconnects and asks for all missed changes after its cursor.

The change log becomes the durable recovery path.

---

## 29. Push Notifications vs Durable Sync

Push notification:

```text
"something changed"
```

Durable sync API:

```text
"give me every change after cursor 1000"
```

Push is the fast hint.

The change log is the source of truth for recovery.

---

## 30. Offline Edits

Two devices may edit the same file while offline.

Example:

```text
Laptop edits V5 -> V6A
Phone edits V5  -> V6B
```

When both reconnect, the server detects a divergent base version.

---

## 31. Conflict Resolution

Options:

- last-write-wins
- create conflicted copy
- application-specific merge
- CRDT/OT for collaborative documents

For generic binary files, automatic merge is usually unsafe.

A practical system may create:

```text
report.docx
report (conflicted copy from Laptop).docx
```

---

## 32. Why Google Docs Is a Separate HLD

Generic file storage sync is not the same as real-time collaborative document editing.

Drive/Dropbox handles file versions and conflicts.

Google Docs requires operation-level collaboration such as OT/CRDT.

That is why collaborative editing deserves its own case study.

---

## 33. Sharing Model

Possible ACL entry:

```text
resourceId
principalId
role
```

Roles:

```text
OWNER
EDITOR
COMMENTER
VIEWER
```

A file may inherit permissions from its parent folder depending on product design.

---

## 34. Shared Links

A share link may carry:

```text
resourceId
secret/token
expiry
permission
```

Security options:

- random unguessable token
- expiration
- password protection
- download restriction
- domain restrictions

---

## 35. Authorization at Read Time

Never rely only on permissions checked when a link was originally generated.

A permission may be revoked later.

For sensitive resources, authorization must reflect current policy or use short-lived signed access tokens.

---

## 36. Download Flow

```text
Client
  |
  v
Metadata/Auth Service
  |
  | authorized chunk manifest / signed URLs
  v
Client ----------------------> CDN/Object Storage
```

Large bytes should not flow through metadata servers.

---

## 37. Parallel Downloads

Because files are chunked, clients can fetch multiple chunks in parallel.

Benefits:

- better bandwidth use
- retry individual chunks
- resumability

Client reassembles them in manifest order.

---

## 38. CDN

CDN may cache frequently downloaded immutable chunks.

Advantages:

- lower latency
- reduced origin bandwidth
- better performance for shared/public content

Encrypted/private content needs appropriate cache-control and authorization.

---

## 39. Encryption

Use:

- TLS in transit
- encryption at rest
- KMS-managed keys

Possible key hierarchy:

```text
master key
  -> tenant/user key
      -> object/chunk data key
```

For true client-side end-to-end encryption, server-side dedup/search capabilities change significantly.

---

## 40. Virus / Malware Scanning

Uploads may trigger asynchronous scanning.

States:

```text
UPLOADED
SCANNING
READY
QUARANTINED
```

Depending on policy, sharing/download may be blocked until scanning completes.

---

## 41. Search Indexing

Do not use the metadata DB for full-text search.

Pipeline:

```text
Metadata Event
   |
   v
Kafka
   |
   v
Search Indexer
   |
   v
Search Engine
```

Index:

- names
- paths
- owner
- shared principals
- extracted text where allowed

---

## 42. Large Folder Problem

A folder may contain millions of files.

Do not return everything at once.

Use:

- cursor pagination
- indexes by parent_folder_id
- stable ordering

Example:

```text
(parentFolderId, name/fileId)
```

---

## 43. Hot Shared File

A file shared publicly may receive millions of downloads.

Mitigations:

- CDN
- immutable chunk caching
- request coalescing
- replicated metadata cache

The underlying chunk store should not see one request per user.

---

## 44. Metadata Sharding

Natural shard candidates:

```text
ownerId
namespaceId
```

But shared folders complicate ownership.

A common principle is to keep a logical namespace together where possible.

Trade-off:

- locality vs shard balance

---

## 45. Namespace Service

A user/team namespace may own folder/file metadata.

Example:

```text
namespaceId -> metadata shard
```

All operations within a namespace can be routed consistently.

Large enterprise namespaces may require sub-partitioning.

---

## 46. Metadata Cache

Cache candidates:

- file metadata
- folder listings
- permissions
- latest version pointer

Be careful with authorization-sensitive data.

Use explicit invalidation/versioning for critical permissions.

---

## 47. Storage Durability

Chunk storage should tolerate disk/node/AZ failures.

Possible techniques:

- replication
- erasure coding

Replication:

```text
3 full copies
```

Simple but storage-heavy.

Erasure coding:

```text
k data fragments + m parity fragments
```

More storage efficient but repair/read complexity is higher.

---

## 48. Erasure Coding Trade-Off

Replication:

- simpler
- fast reads
- expensive storage

Erasure coding:

- lower storage overhead
- more CPU/network for reconstruction
- useful for cold/large durable objects

A real platform may use different policies by storage tier.

---

## 49. Multi-AZ Storage

Chunks should not rely on one rack or availability zone.

Spread fragments/replicas across independent failure domains.

Metadata databases also need replicated consensus/HA depending on consistency requirements.

---

## 50. Multi-Region Architecture

```text
                 Global Traffic Manager
                    /             \
                   v               v
              Region A         Region B
                 |                |
          Metadata/API       Metadata/API
                 |                |
          Regional Cache     Regional Cache
                 |                |
                 +------ Replication ------+
                        |
                  Durable Storage
```

Users should generally connect to a nearby region.

---

## 51. Metadata Multi-Region Strategy

Options:

1. Home region for metadata writes
2. Active-active metadata with conflict resolution
3. Namespace ownership per region

Strong active-active writes are much harder.

For many systems, a home-region/namespace-owner model simplifies correctness.

---

## 52. Blob Multi-Region Strategy

Chunks can replicate asynchronously across regions.

Reads may use nearest healthy replica.

New uploads can be accepted in a local ingest region, then copied according to durability policy.

---

## 53. Region Failure

If the metadata home region fails:

- fail over to replicated secondary metadata
- ensure latest committed version pointer is recoverable
- avoid accepting conflicting writes from multiple failover regions simultaneously unless designed for it

If a chunk region fails:

- route reads to another replica/region
- rebuild missing fragments later

---

## 54. CAP / Consistency Trade-Off

For namespace metadata during partition:

- choosing stronger consistency may reject some writes
- choosing availability may create conflicts

Generic cloud-drive systems often prefer preserving metadata correctness and explicitly handling offline conflicts rather than silently corrupting namespace state.

---

## 55. Retry Semantics

Retries happen everywhere:

- chunk upload
- chunk replication
- metadata updates
- sync requests
- notifications

Use idempotency keys and deterministic chunk IDs.

Do not turn retries into duplicate versions or duplicate shares.

---

## 56. Transactional Outbox

Suppose metadata DB commits file version V9 but crashes before publishing `FILE_UPDATED`.

Other devices may never learn about it.

Use:

```text
DB transaction:
  update metadata
  insert outbox event
```

Then publish asynchronously to Kafka/change-log infrastructure.

---

## 57. Change Log Ordering

Users need a stable sequence of namespace changes.

Possible design:

```text
namespaceId -> ordered change stream
```

Each change gets:

```text
changeSequence
```

Devices consume monotonically.

---

## 58. Compaction of Change Logs

A device offline for two years should not necessarily replay billions of tiny changes.

Possible approach:

- retain recent log
- periodically create namespace snapshots/checkpoints

Recovery:

```text
old cursor expired
-> download latest snapshot
-> continue from new cursor
```

---

## 59. Backpressure

Suppose a company migrates 10 PB into the service.

Upload and chunk-processing queues surge.

Use:

- per-user/tenant quotas
- bounded upload concurrency
- backpressure
- priority queues
- bandwidth shaping
- storage ingest autoscaling

Protect interactive sync traffic from giant bulk migrations.

---

## 60. Priority Isolation

Separate workloads:

```text
interactive user sync
normal uploads
enterprise bulk migration
background replication
GC
```

A massive low-priority migration must not delay a user's 50 KB document sync.

---

## 61. Storage Repair

If a replica/fragments become unavailable:

```text
repair scanner detects under-replication
```

Then reconstruct/copy chunks in background.

Repair traffic needs rate limiting so it does not overwhelm normal reads/writes.

---

## 62. Bit Rot Detection

Stored data can silently corrupt.

Periodically verify checksums.

On mismatch:

```text
read healthy replica
repair corrupted copy
```

Durability is not just replication; integrity verification matters too.

---

## 63. Delete and Restore

User deletion can first create a tombstone:

```text
deletedAt = timestamp
```

During retention window:

- hidden from normal view
- restorable

After retention:

- metadata purge
- chunk reference decrements
- eventual GC

---

## 64. Ransomware Recovery

Version history protects against destructive mass edits.

Useful capability:

```text
restore namespace to timestamp T
```

This may require retained version metadata and efficient bulk rollback tooling.

---

## 65. Quotas

Quota checks may use logical file sizes rather than physical deduplicated storage.

Why?

Otherwise user billing/limits would depend unpredictably on what other users uploaded.

Keep physical-storage optimization separate from logical usage accounting.

---

## 66. Audit Logging

For enterprise storage, capture:

```text
who
what
when
resource
action
source device/IP
```

Audit should be append-only/tamper-resistant according to policy.

---

## 67. Observability

Key metrics:

```text
metadata_read_latency
metadata_write_latency
upload_success_rate
chunk_retry_rate
chunk_dedup_hit_rate
sync_lag
change_log_lag
conflict_rate
storage_replication_lag
under_replicated_chunks
checksum_failure_rate
CDN_hit_rate
permission_denials
```

Track p50/p95/p99 and regional breakdowns.

---

## 68. Capacity Estimation

Example assumptions:

```text
100M active users
10 GB logical average/user
= 1 EB logical storage
```

Even with lower real usage, storage scale becomes enormous.

Suppose:

```text
10M file updates/day
average changed data = 5 MB
```

That is roughly:

```text
50 TB/day changed bytes
```

The purpose is to recognize that metadata QPS and blob capacity have very different scaling dimensions.

---

## 69. Production-Grade Architecture

```text
                           Clients
                              |
                         API Gateway
                              |
         +--------------------+--------------------+
         |                    |                    |
         v                    v                    v
   Metadata Service       Sync Service        Upload Service
         |                    |                    |
         v                    v                    |
   Metadata DB          Ordered Change Log         | signed/direct upload
         |                    |                    v
         |                    |              Chunk/Object Storage
         |                    |                    |
         |                    |              Replication / EC
         |                    |
         +--------- Outbox / Kafka ---------------+
                              |
              +---------------+---------------+
              |               |               |
              v               v               v
       Notifications      Search Index      Audit/Analytics

Download: Client -> Auth/Metadata -> signed chunk URLs -> CDN/Object Storage
```

---

## 70. Principal Engineer Failure Scenario

Assume an enterprise synchronization client bug causes 3 million devices to reconnect simultaneously after a network outage.

At the same time:

```text
change-log service latency rises
metadata cache hit rate drops
object storage has elevated p99
background replication is repairing an AZ failure
```

A weak system may collapse:

```text
reconnect storm
-> full namespace rescans
-> metadata DB overload
-> chunk download retries
-> storage saturation
-> replication competes with foreground reads
-> cascading outage
```

Principal-level response:

1. clients use exponential backoff + jitter
2. sync resumes from cursors, not full rescans
3. use namespace snapshots when cursors are too old
4. strict metadata-service admission control
5. prioritize interactive sync over GC/repair/bulk migration
6. rate-limit repair traffic
7. stale-but-safe metadata caching where appropriate
8. batch change retrieval
9. signed direct download avoids proxying bytes through metadata servers
10. bounded chunk retries
11. request coalescing for hot metadata
12. temporarily reduce background indexing/audit enrichment if safe
13. autoscale stateless sync gateways
14. preserve metadata correctness even if some writes must be rejected during failover
15. recover gradually rather than allowing all clients to reconnect at once

Core principle:

> Durable bytes and correct metadata are the source of truth. Push notifications and caches are optimizations; resumable chunk transfer and cursor-based sync are the recovery mechanisms.

---

## 71. Interview Follow-Up Questions

Be prepared for:

- Why separate metadata from blob storage?
- Why chunk files?
- Fixed-size vs content-defined chunking?
- How do resumable uploads work?
- How do you deduplicate safely?
- How do chunk reference counts/GC work?
- How do you publish a new file version atomically?
- How do multiple devices sync efficiently?
- Why use a change cursor?
- How do offline conflicts work?
- How do you handle generic binary-file merge conflicts?
- How does sharing/authorization work?
- How do you store folders?
- How do you handle huge folders?
- Replication vs erasure coding?
- How do you detect bit rot?
- How do you recover from regional failures?
- How do you protect interactive sync from bulk migrations?
- Why is Google Docs a separate problem?

---

## 72. Interview Summary

The transferable concepts in this design are:

```text
Metadata vs blob separation
Chunking
Resumable transfer
Content hashes
Deduplication
Reference counting
Garbage collection
File versioning
Optimistic concurrency
Cursor-based synchronization
Delta sync
Offline conflict resolution
ACLs / sharing
Signed downloads
CDN
Transactional outbox
Ordered change logs
Snapshots / compaction
Replication
Erasure coding
Bit rot detection
Repair traffic
Multi-region ownership
Backpressure
Priority isolation
Observability
```

A strong HLD answer should make one thing very clear: cloud file storage is two systems working together. The metadata system requires correctness and ordered namespace changes; the blob system requires extreme durability, bandwidth efficiency, and cheap scalable storage.
