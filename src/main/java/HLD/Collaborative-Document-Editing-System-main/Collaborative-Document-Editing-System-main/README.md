# Collaborative Document Editing System — Google Docs High-Level Design

A senior/principal-level HLD case study for designing a Google Docs-style real-time collaborative editor.

The core challenge is:

> Multiple users can concurrently edit the same logical document from unreliable networks, yet every participant must eventually converge on a coherent document without losing valid edits.

This is not a CRUD problem. It is a **distributed concurrency + realtime synchronization + durable history** problem.

---

## 1. Problem Statement

Design a collaborative editor where users can:

- create documents
- edit text concurrently
- see remote edits in near real time
- see collaborator presence/cursors
- work offline and reconnect
- autosave
- view version history
- share documents with permissions
- recover from client/server/network failures

The system must handle:

- simultaneous insert/delete operations
- duplicate and out-of-order messages
- reconnects
- long-lived WebSocket sessions
- hot documents with thousands of viewers/editors
- multi-region users
- failover without split-brain editing

---

## 2. Functional Requirements

Core APIs:

```http
POST /documents
GET  /documents/{documentId}
POST /documents/{documentId}/share
GET  /documents/{documentId}/history
```

Realtime channel:

```text
WebSocket /documents/{documentId}/session
```

Messages may include:

```text
JOIN
EDIT_OPERATION
ACK
REMOTE_OPERATION
CURSOR_UPDATE
PRESENCE_UPDATE
SNAPSHOT_REQUIRED
```

---

## 3. Non-Functional Requirements

- low-latency edit propagation
- eventual convergence
- no lost acknowledged edits
- durable operation history
- reconnect/replay
- scalable active sessions
- hot-document resilience
- graceful client/network failure recovery
- multi-region safety
- auditability
- high availability

---

## 4. Core Domain Model

```text
Document
DocumentSnapshot
DocumentOperation
Revision
Session
Collaborator
Permission
CursorPresence
```

---

## 5. High-Level Architecture

```text
Clients
   |
   v
WebSocket Gateway
   |
   v
Collaboration Service
   |
   +--> Document Session / Sequencer
   +--> OT or CRDT Engine
   +--> Presence Service
   |
   +--> Operation Log
   +--> Snapshot Store
   +--> Metadata / ACL Store
```

Async consumers:

```text
Operation Events
   |
   +--> Search indexing
   +--> Analytics
   +--> Notifications
   +--> Version history compaction
```

---

## 6. Separate Metadata from Collaborative State

Metadata:

```text
title
owner
ACL
createdAt
```

Collaborative state:

```text
snapshot
revision
operations
active sessions
```

These have different consistency and storage patterns.

---

## 7. Realtime Connection

Use WebSocket for bidirectional low-latency messaging.

Benefits:

- server can push remote edits
- client can send local operations
- efficient persistent connection

---

## 8. Why Not Polling?

Polling every second creates:

- unnecessary requests
- poor latency
- inefficient fan-out

Collaborative editing benefits strongly from persistent realtime channels.

---

## 9. WebSocket Gateway

Gateway responsibilities:

- authenticate connection
- maintain socket
- heartbeat
- route document messages
- apply connection-level backpressure

Keep document conflict-resolution logic out of gateway where possible.

---

## 10. Session Routing

All participants editing one document should reach the same logical collaboration owner/session.

Possible routing key:

```text
documentId
```

---

## 11. Document Session

A document session maintains active collaborative state:

```text
currentRevision
connectedClients
recentOperations
presence
```

It may be an in-memory actor/session backed by durable log/snapshot storage.

---

## 12. Why Document Affinity Matters

If operations for one document are processed independently by many unordered servers, resolving concurrent edits becomes much harder.

Logical per-document sequencing simplifies correctness.

---

## 13. Basic Operation Model

Instead of sending entire document on every keystroke, send operations.

Example:

```json
{
  "operationId": "op-123",
  "baseRevision": 88,
  "type": "INSERT",
  "position": 5,
  "text": "hello"
}
```

---

## 14. Insert Operation

```text
INSERT(position=5, text="abc")
```

means insert text into logical document position.

---

## 15. Delete Operation

```text
DELETE(position=3, length=4)
```

means delete a range based on the client's view/revision.

Concurrent edits require transformation or conflict-free identifiers.

---

## 16. The Concurrent Edit Problem

Suppose document is:

```text
ABCDEFGH
```

User A inserts `X` at position 5.

User B concurrently deletes positions 3–6 based on the old revision.

If server applies both raw positional operations without adjustment, clients can diverge or delete the wrong characters.

---

## 17. Two Major Approaches

```text
Operational Transformation (OT)
Conflict-Free Replicated Data Types (CRDT)
```

Both can solve collaborative convergence, but with different complexity and trade-offs.

---

## 18. Operational Transformation

OT transforms an operation against concurrent operations already accepted by the server.

Example:

```text
A inserts before B's target position
```

Then B's position is shifted.

---

## 19. OT Central Idea

Client sends operation based on revision R.

Server is currently at revision R+N.

Server transforms client operation against intervening operations before applying it.

---

## 20. OT Flow

```text
Client op(baseRevision=10)
        |
        v
Server currently revision=12
        |
        v
Transform op against revisions 11 and 12
        |
        v
Apply transformed operation
        |
        v
revision=13
```

---

## 21. OT Requirements

Transformation functions must correctly handle combinations such as:

```text
insert vs insert
insert vs delete
delete vs insert
delete vs delete
```

This is algorithmically subtle.

---

## 22. OT Convergence

Correct transformation rules should ensure all clients eventually reach the same document state despite concurrent operations.

---

## 23. OT Server Sequencer

A centralized/per-document sequencer is natural with OT.

It assigns a canonical revision order.

This simplifies transformation and replay.

---

## 24. CRDT

CRDT assigns stable logical identifiers to content/elements so concurrent edits can be merged deterministically without one central transform sequence.

---

## 25. Sequence CRDT Concept

Instead of identifying text only by numeric positions, characters/runs may have IDs.

Example:

```text
A(id1) B(id2) C(id3)
```

Insertions reference neighboring logical IDs.

---

## 26. CRDT Convergence

If all replicas receive the same set of operations, deterministic CRDT merge rules lead them to the same state regardless of delivery order.

---

## 27. CRDT Benefits

- natural offline edits
- decentralized merge
- less dependence on central transform ordering

---

## 28. CRDT Costs

- metadata overhead
- tombstones/identifier growth
- implementation complexity
- compaction complexity

No approach is universally superior.

---

## 29. OT vs CRDT

OT often fits:

- centralized collaboration server
- revision-based protocol
- compact operations

CRDT often fits:

- offline-first editing
- peer/decentralized merge
- weaker sequencing dependency

Choose from product/network requirements.

---

## 30. Interview Position

Do not say:

```text
Google Docs definitely uses exactly this algorithm today
```

Real production internals evolve and are proprietary.

Say:

> A Google Docs-style design can use OT or CRDT; here are the trade-offs.

---

## 31. Optimistic Local Editing

Client should apply its keystroke immediately without waiting for server RTT.

```text
user types
-> local UI updates instantly
-> operation sent asynchronously
```

This is essential for good UX.

---

## 32. Pending Local Operations

Client tracks unacknowledged operations:

```text
pending = [op17, op18]
```

Remote operations may need transformation/merge against these pending local changes.

---

## 33. Operation ID

Each client operation gets globally unique ID:

```text
clientId + localSequence
```

Example:

```text
client-42:991
```

Used for deduplication and acknowledgement.

---

## 34. Duplicate Operation

Client sends operation, server accepts it, ACK is lost.

Client reconnects/retries.

Without dedupe:

```text
same text inserted twice
```

Store/recently remember operation IDs.

---

## 35. Idempotent Operation Processing

If operation ID already accepted:

```text
return original acknowledgement/revision
```

Do not apply it again.

---

## 36. Revision Number

Server-assigned revision:

```text
r0, r1, r2, r3...
```

Each accepted operation advances revision.

Useful with centralized sequencing.

---

## 37. Revision Is Document-Local

No need for global revision across every document.

```text
docA revision 1000
docB revision 7
```

Local ordering scales better.

---

## 38. ACK Flow

```text
Client -> op(baseRevision=20)
Server -> transform/apply -> revision=23
Server -> ACK(opId, revision=23)
```

Client removes operation from pending queue.

---

## 39. Fan-Out to Other Clients

Once operation is accepted:

```text
Session -> broadcast to connected collaborators
```

Clients apply remote operation according to OT/CRDT protocol.

---

## 40. Per-Document Ordering

Broadcast operations in canonical revision order where OT-style protocol depends on order.

Do not rely on cross-machine network arrival order.

---

## 41. Operation Log

Persist accepted operations:

```text
revision
operationId
author
operation
serverTimestamp
```

Benefits:

- reconnect replay
- recovery
- audit/version history

---

## 42. Why Operation Log Alone Is Not Enough

A document with millions of edits would require replaying millions of operations on every load.

Use periodic snapshots.

---

## 43. Document Snapshot

Snapshot contains materialized document at revision R.

```text
snapshot revision=10000
```

To reconstruct revision 10050:

```text
load snapshot 10000
+
replay operations 10001..10050
```

---

## 44. Snapshot Frequency

Trade-off:

frequent snapshots:

- faster recovery
- more write/storage cost

infrequent:

- cheaper
- longer replay

Choose by document edit rate and size.

---

## 45. Snapshot Compaction

Background worker can create new snapshot and compact old operation segments.

Do not block active editing for long compaction work.

---

## 46. Autosave

User-visible save should be continuous.

An accepted durable operation can be considered autosaved once it reaches required durability boundary.

---

## 47. Durability Boundary

Possible policy:

```text
ACK only after operation durably appended/replicated
```

This prevents acknowledging edits that vanish on server crash.

---

## 48. Write-Ahead Operation Log

Before broadcasting/ACK:

```text
append accepted operation durably
```

Then session state can be reconstructed after failure.

---

## 49. Session Recovery

Session node crashes.

New owner:

```text
load latest snapshot
replay durable operations
resume at latest revision
```

Clients reconnect and resynchronize.

---

## 50. Client Reconnect

Client sends:

```text
documentId
lastAckedRevision
pending operation IDs
```

Server determines missing operations or requires fresh snapshot.

---

## 51. Delta Replay

If client is only slightly behind:

```text
send operations revision 101..108
```

Cheaper than full document reload.

---

## 52. Snapshot Resync

If client is extremely stale or transformation history was compacted:

```text
send latest snapshot + new baseline revision
```

Client rebases local pending changes if protocol supports it.

---

## 53. Offline Editing

Client may disconnect and continue editing locally.

On reconnect, its edits must be merged with server changes.

CRDT naturally supports this model; OT can support it but rebasing/transform history becomes more involved.

---

## 54. Offline Conflict UX

Some changes cannot be cleanly represented at semantic level.

For rich objects/tables/comments, product may surface conflicts or choose deterministic merge behavior.

Do not assume text convergence alone solves all semantic conflicts.

---

## 55. Presence

Presence state:

```text
user joined
user active
user left
```

This state is ephemeral.

It does not need the same durability as document edits.

---

## 56. Cursor Position

Collaborators see remote cursors/selections.

Cursor updates are high-frequency and lossy.

Latest value matters more than every intermediate event.

---

## 57. Presence Transport

Use realtime broadcast with aggressive coalescing/throttling.

Do not persist every cursor movement to durable operation log.

---

## 58. Presence Expiry

If heartbeat stops:

```text
presence TTL expires
```

User is removed from active collaborator list.

---

## 59. Rich Document Model

Real documents contain more than plain text:

- paragraphs
- formatting
- images
- tables
- comments
- suggestions

Represent structured document tree rather than one giant string.

---

## 60. Structured Operations

Examples:

```text
insertText(nodeId, offset)
deleteRange(...)
setBold(range)
insertTable(parentId)
addComment(anchor)
```

Stable node IDs improve conflict resolution.

---

## 61. Formatting Conflicts

Two users concurrently change same text style.

Need deterministic merge policy or operation transformation rules for attributes.

Collaborative editing algorithms must cover rich-text semantics, not only character insertion.

---

## 62. Comments

Comments can be separate entities referencing document ranges/node IDs.

Stable logical anchors are preferable to raw character offsets because text shifts over time.

---

## 63. Suggestion Mode

Suggested edits may be modeled as annotations/operations requiring accept/reject state rather than immediately mutating canonical visible content.

This adds another collaboration layer.

---

## 64. Document Permissions

Roles:

```text
OWNER
EDITOR
COMMENTER
VIEWER
```

Authorization checked when joining session and for sensitive operations.

---

## 65. Revoked Permission During Session

User's edit permission can be removed while socket remains connected.

Session must receive permission change or periodically revalidate.

Do not rely only on authorization performed at connection establishment.

---

## 66. Sharing Links

Share tokens should be:

- unguessable
- revocable
- permission scoped
- auditable

---

## 67. Document Encryption

Use TLS in transit and encryption at rest.

Keys/ACLs must be isolated by tenant/user where required.

---

## 68. Hot Document

A normal document may have 3 editors.

A public/event document may have:

```text
10K viewers
500 active editors
```

One collaboration server can become hotspot.

---

## 69. Separate Editors from Viewers

Editors require write sequencing.

Viewers mainly consume broadcasts.

Architecture:

```text
Document Leader / Sequencer
      |
      +--> editor connections
      |
      +--> fan-out relays -> many viewers
```

---

## 70. Fan-Out Relay

Instead of leader writing individually to 100K sockets:

```text
leader -> regional/cluster relay -> gateways -> viewers
```

This reduces leader network load.

---

## 71. Broadcast Backpressure

Slow client cannot consume operations quickly enough.

Do not buffer infinitely per socket.

Options:

- bounded buffer
- drop ephemeral presence events
- force document resync for very stale client

Never silently drop durable edits if client protocol requires them; resync instead.

---

## 72. Slow Client Recovery

If operation backlog exceeds threshold:

```text
disconnect/resync from latest snapshot
```

This protects session memory.

---

## 73. Document Sharding

Shard by:

```text
hash(documentId)
```

All state for one document maps to one logical owner/shard.

---

## 74. Consistent Hashing / Rendezvous Hashing

Useful for assigning document sessions to collaboration nodes with limited movement when fleet changes.

But ownership must still be protected with lease/epoch where split-brain matters.

---

## 75. Session Ownership Lease

Metadata:

```text
documentId
ownerNode
leaseUntil
epoch
```

Only current epoch may append canonical operations.

---

## 76. Fencing Token

Old collaboration owner may freeze, lose lease, then wake up.

New owner has higher epoch.

Operation store rejects writes from stale epoch.

This prevents two canonical histories.

---

## 77. Split Brain

Worst case:

```text
Region/Node A accepts edits
Region/Node B also accepts edits
```

If using centralized OT sequencing, independent canonical histories are extremely difficult to merge safely.

Prevent split-brain ownership.

---

## 78. OT and Availability Trade-Off

Centralized sequencer usually prefers one canonical writer per document.

During partition, losing writer ownership may temporarily block edits rather than create divergent authoritative histories.

---

## 79. CRDT and Partition Tolerance

CRDT can permit independent edits during disconnection and merge later.

But permissions, business semantics, storage ordering, and deletion/compaction still require careful system design.

CRDT does not eliminate all coordination.

---

## 80. Multi-Region Serving

Users connect to nearby region:

```text
India user -> India gateway
US user    -> US gateway
```

Gateway may proxy operations to document's home collaboration region.

---

## 81. Home Region per Document

Map:

```text
documentId -> home region
```

Canonical operation sequencing occurs there.

Remote gateways maintain local sockets but forward edits to home region.

---

## 82. Multi-Region Latency

Cross-region editors may experience higher edit-ACK latency.

Optimistic local rendering hides much of this latency.

Remote edits still arrive after network RTT.

---

## 83. Region Failover

If home region fails:

```text
promote secondary operation store/session owner
with higher fencing epoch
```

Clients reconnect via new route.

---

## 84. Failover Safety

Before new region accepts canonical writes, ensure old owner cannot continue writing.

Consensus-backed ownership/fencing is critical for OT-style architecture.

---

## 85. RPO

If operation replication across regions is asynchronous, recently acknowledged edits may be at risk during region loss.

For strong durability, ACK policy may require synchronous replication across independent failure domains.

This increases latency.

---

## 86. RTO

Failover flow includes:

```text
detect region failure
acquire ownership
reconstruct latest document state
reroute clients
replay/rebase pending edits
```

Set explicit RTO target.

---

## 87. Storage Model

Possible separation:

```text
Document Metadata -> relational/distributed DB
Snapshots         -> object/blob storage
Operation Log     -> replicated append log / DB
Presence          -> memory/Redis-like ephemeral store
```

Choose storage by semantics.

---

## 88. Operation Log Partitioning

Partition by `documentId`.

This preserves local document ordering and locality.

---

## 89. Kafka?

Kafka can distribute accepted document events to downstream consumers.

But do not blindly use Kafka as the only interactive conflict-resolution state machine.

Realtime document sequencing still needs document-aware coordination.

---

## 90. Transactional Outbox

If metadata change must emit event:

```text
update document metadata
+
insert outbox event
```

within one DB transaction.

Useful for ACL/search/indexing notifications.

---

## 91. Search Indexing

Document search index can update asynchronously from snapshots/operation events.

Search may lag collaboration state slightly.

That is acceptable for many products.

---

## 92. Version History

Create named/automatic historical checkpoints.

User can view/revert to earlier document state.

Revert should create new operation/revision, not erase history.

---

## 93. Revert

If current revision is 500 and user restores revision 300:

Do not set revision counter backward.

Create new revision 501 whose content corresponds to restored snapshot/state.

---

## 94. Audit History

Track:

```text
author
operation/revision
timestamp
permission change
restore action
```

Useful for enterprise collaboration.

---

## 95. Deletion

Document deletion may be soft initially:

```text
ACTIVE -> TRASHED -> PURGED
```

Collaboration sessions must stop edits once deletion/permission state changes.

---

## 96. Autosave Indicator

UI may show:

```text
Saving...
Saved
Offline
```

`Saved` should correspond to clearly defined durability/ACK semantics, not merely local memory update.

---

## 97. Client Crash

If server ACKed edits, client can restore them from server after restart.

Unsent local edits may require local durable buffer if offline-first behavior is required.

---

## 98. Server Crash Before ACK

If server durably appended operation but crashes before ACK:

client retries operation ID.

Dedup returns prior result.

---

## 99. Server Crash Before Durable Append

Client does not get successful ACK.

Retry can safely resend.

This is why ACK boundary matters.

---

## 100. Out-of-Order Network Delivery

WebSocket normally provides ordered bytes on one connection, but reconnect/multi-path systems can still present stale messages.

Every durable operation carries revision/version metadata.

---

## 101. Client Sequence Numbers

Client increments:

```text
localSeq = 1,2,3...
```

Server can detect duplicate/gaps for that client session.

---

## 102. Session Reauthentication

Long-lived sockets may outlive auth-token lifetime.

Support token refresh/revalidation without unnecessary document loss.

---

## 103. Abuse Protection

Protect against:

- operation floods
- giant paste operations
- malicious documents
- reconnect storms

Use rate limits and maximum operation/document sizes.

---

## 104. Large Paste

User pastes 10 MB text.

Do not encode it as millions of tiny per-character operations.

Batch/range operation or blob-backed insertion can reduce overhead.

---

## 105. Large Documents

One giant document may exceed comfortable in-memory session size.

Possible techniques:

- structured chunks/sections
- lazy loading
- subtree snapshots

But cross-section edits/formatting still require coherent document semantics.

---

## 106. Operation Batching

Clients can batch rapid local keystrokes into small operations.

Benefits:

- fewer network messages
- lower transformation/storage overhead

Trade-off:

- slight remote visibility delay

---

## 107. Presence Throttling

Cursor movement can generate dozens of updates/sec.

Throttle/coalesce:

```text
send latest every 50-100 ms
```

Exact value is product-dependent.

---

## 108. Backpressure

If operation processing rate exceeds session capacity:

- bound inbound queues
- slow/reject abusive clients
- prioritize durable edits over presence
- split viewer fan-out

Do not allow unbounded memory growth.

---

## 109. Collaboration SLO

Example assumptions:

```text
local edit UI response: immediate optimistic
remote edit propagation p95 < 200 ms within region
operation durability p99 < 500 ms
```

Exact values depend on product/regions.

---

## 110. Capacity Estimation

Assume:

```text
10M concurrent connected users
1M actively editing
3 edit operations/sec per active editor
```

Then:

```text
~3M edit operations/sec
```

Presence/cursor traffic may be several times higher.

The important insight is that **socket count, edit rate, fan-out, and hot-document skew** must be capacity-planned separately.

---

## 111. Operation Storage Estimation

Suppose average encoded operation:

```text
200 bytes
```

At 3M ops/sec:

```text
~600 MB/sec raw operation ingress
```

Before replication/indexing overhead.

Snapshots and compaction are essential for long-term efficiency.

---

## 112. Hot Document Capacity

Average document has 2 editors, but one event document has:

```text
1000 editors
100K viewers
```

Design for skew, not only average sessions.

---

## 113. Observability

Key metrics:

```text
active_websockets
active_document_sessions
edit_ops/sec
operation_ack_latency
remote_propagation_latency
operation_dedup_rate
transform_conflict_rate
snapshot_age
operation_log_lag
reconnect_rate
resync_rate
presence_qps
hot_document_count
```

---

## 114. Failure Metrics

Track:

```text
session_owner_failover_count
stale_epoch_rejections
operation_persistence_failures
client_backlog_size
slow_socket_disconnects
cross_region_latency
```

---

## 115. Graceful Degradation

During stress:

- drop/throttle presence
- reduce cursor update frequency
- delay search indexing
- reduce version-history compaction priority
- move viewers to cached/read-only fan-out

Do not drop accepted durable edits.

---

## 116. Read-Only Degradation

If canonical operation store loses quorum:

```text
continue serving last durable document snapshot read-only
```

if product permits.

Better temporary read-only mode than creating divergent canonical histories.

---

## 117. Principal Engineer Failure Scenario

Assume a globally shared document has:

```text
20K viewers
800 active editors
```

Then:

```text
home-region collaboration owner experiences 15-second GC pause
ownership lease expires
secondary owner acquires epoch 221
old owner had accepted some in-memory operations not yet durably committed
clients begin reconnecting
viewer fan-out creates a reconnect storm
cross-region network latency doubles
```

A weak system may do:

```text
old owner wakes
-> both owners accept edits
-> two revision histories
-> clients receive conflicting operation streams
-> reconnect retries multiply
-> collaboration state diverges
```

Principal-level response:

1. ACK edits only after required durable append boundary
2. new owner acquires higher fencing epoch
3. durable operation store rejects old epoch writes
4. old owner must stop serving authoritative edits immediately after lease loss
5. reconstruct new session from latest snapshot + durable log
6. clients reconnect with last acknowledged revision and operation IDs
7. dedupe retried operations
8. replay missing operations when history is available
9. force snapshot resync for very stale clients
10. preserve optimistic local pending edits and rebase/merge via OT/CRDT protocol
11. throttle reconnects with jitter/backoff
12. separate editor admission from massive viewer reconnects
13. degrade presence/cursor updates first
14. fan out viewers through relays rather than canonical sequencer
15. avoid declaring in-memory unacknowledged edits committed
16. restore cross-region traffic gradually

Core principle:

> In collaborative editing, the authoritative history must have exactly one valid ownership lineage at a time unless the chosen merge model is explicitly designed for independent replicas. Realtime UX can be optimistic; canonical durability cannot be ambiguous.

---

## 118. Concurrent Insert/Delete Example

Initial document:

```text
ABCDEFGH
```

Both users start from revision 10.

User A:

```text
INSERT position 5 -> X
```

User B:

```text
DELETE position 3 length 4
```

With OT-style sequencing, suppose A is accepted first as revision 11.

Server transforms B against A.

The exact transformed range depends on position/index conventions and intended deletion semantics, but the important invariant is:

```text
all replicas apply equivalent transformed operations
-> all replicas converge to the same final document
```

Do not memorize one string outcome without first defining index boundaries and transformation rules.

---

## 119. Interview Follow-Up Questions

Be prepared for:

- Why WebSockets?
- What is a document session?
- Why route one document to one logical collaboration owner?
- What is OT?
- What is CRDT?
- OT vs CRDT trade-offs?
- How do concurrent insert/delete operations converge?
- Why optimistic local editing?
- How do operation IDs prevent duplicates?
- What is a document revision?
- How do reconnect and replay work?
- Why use snapshots plus an operation log?
- How do offline edits work?
- How do cursor/presence updates differ from durable edits?
- How do you handle a hot document?
- How do you prevent slow viewers from blocking editors?
- How do you shard documents?
- Why do session leases need fencing tokens?
- How does multi-region collaboration work?
- What happens during region failover?

---

## 120. Interview Summary

The transferable concepts in this design are:

```text
WebSockets
Realtime sessions
Document affinity
Operational Transformation
CRDT
Optimistic local updates
Operation IDs / deduplication
Revision ordering
Pending operations
Operation logs
Snapshots
Reconnect / replay
Offline editing
Presence / cursor state
Structured document operations
Hot-document fan-out
Backpressure
Session ownership leases
Fencing tokens
Multi-region home ownership
Read-only degradation
Observability
```

A strong HLD answer should separate three concerns:

```text
1. User experience: optimistic local edits and low-latency broadcasts
2. Concurrency correctness: OT/CRDT and deterministic convergence
3. Durability/ownership: operation log, snapshots, leases, fencing, failover
```

The editor can feel instantaneous to the user while the backend carefully maintains one coherent durable history.
