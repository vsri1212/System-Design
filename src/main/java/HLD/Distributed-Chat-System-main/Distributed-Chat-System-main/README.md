# Distributed Chat System — High-Level Design

A senior/principal-level HLD case study for designing a WhatsApp/Signal/Messenger-style real-time chat platform.

## 1. Problem Statement

Design a globally distributed chat system supporting:

- one-to-one messaging
- group chats
- online/offline users
- message acknowledgements
- delivery/read receipts
- message ordering
- multi-device sync
- media attachments
- presence/last-seen
- retries and deduplication
- regional failover
- end-to-end encryption considerations

The core challenge is not just sending a message. It is maintaining reliable low-latency communication across millions of long-lived connections while handling disconnects, retries, ordering, fan-out, storage, and multi-region delivery.

---

## 2. Functional Requirements

Users should be able to:

- send text messages
- create group chats
- send images/videos/documents
- receive delivery/read receipts
- see online/offline status
- reconnect and receive missed messages
- use multiple devices
- search recent conversation metadata
- mute/block users
- delete messages according to product policy

Example send API/event:

```json
{
  "clientMessageId": "c-123",
  "conversationId": "conv-9",
  "senderId": "u1",
  "type": "TEXT",
  "payload": "hello",
  "timestamp": 1720000000000
}
```

---

## 3. Non-Functional Requirements

- very low messaging latency
- high availability
- durable messages
- horizontal scalability
- ordering within a conversation
- at-least-once transport with deduplication
- offline delivery
- backpressure handling
- multi-region support
- secure transport
- privacy-aware storage
- fault isolation

For chat, availability and latency matter heavily, but message durability and ordering are also important.

---

## 4. Why WebSockets?

Polling is inefficient for real-time chat.

```text
Client -> poll -> no message
Client -> poll -> no message
Client -> poll -> message
```

WebSockets keep a long-lived bidirectional connection:

```text
Client <=================> Gateway
       persistent socket
```

Benefits:

- low latency
- server push
- fewer repeated HTTP handshakes
- efficient duplex communication

Alternatives may include SSE for one-way streaming or HTTP/2/HTTP/3 based streaming, but full-duplex chat naturally fits WebSockets.

---

## 5. High-Level Architecture

```text
                      Global DNS
                          |
                          v
                  Regional Load Balancer
                          |
                          v
                WebSocket Gateway Fleet
                          |
               +----------+----------+
               |                     |
               v                     v
       Connection Registry      Auth Service
               |
               v
          Chat Service
               |
      +--------+--------+
      |                 |
      v                 v
 Message Store       Kafka/Event Bus
      |                 |
      |                 v
      |            Fan-out Workers
      |                 |
      |          +------+------+
      |          |             |
      v          v             v
Offline Queue  Online User   Push Notification
                 Delivery       Service
```

---

## 6. Connection Management

Each online device holds a connection to a WebSocket gateway.

Example:

```text
u123/deviceA -> Gateway 7
u123/deviceB -> Gateway 12
```

We need a distributed connection registry:

```text
(userId, deviceId) -> gatewayId
```

Possible backing stores:

- Redis
- distributed in-memory registry
- region-local service discovery layer

The registry is ephemeral. The durable message state belongs elsewhere.

---

## 7. Stateless vs Stateful Gateways

WebSocket gateways are inherently connection-stateful because sockets live on specific machines.

But business logic should remain stateless as much as possible.

Gateway responsibility:

- maintain socket
- authenticate session
- heartbeat
- forward inbound messages
- push outbound messages

Chat Service responsibility:

- validate conversation membership
- assign sequence/order metadata
- persist message
- emit delivery events

This separation reduces blast radius and makes gateways easier to scale.

---

## 8. Heartbeats and Dead Connections

A socket may disappear without a clean TCP close.

Use ping/pong heartbeats:

```text
Gateway -> PING
Client  -> PONG
```

If no heartbeat within threshold:

```text
mark connection dead
remove from registry
```

Timeouts must balance fast failure detection against mobile-network instability.

---

## 9. Send Message Flow

```text
Sender Device
    |
    v
WebSocket Gateway
    |
    v
Chat Service
    |
    | validate membership
    | deduplicate clientMessageId
    | assign messageId/sequence
    v
Message Store
    |
    v
Kafka/Event Bus
    |
    v
Fan-out Worker
    |
    +--> online recipient gateway
    |
    +--> offline queue / push notification
```

The sender should receive an acknowledgement after the system has durably accepted the message according to the chosen durability guarantee.

---

## 10. Message IDs

A message needs a globally unique identifier.

Options:

- UUID
- Snowflake-style ID
- database sequence
- per-conversation sequence + shard metadata

A Snowflake-style ID can provide uniqueness and rough time ordering without central coordination.

But global timestamp order is not the same as conversation order.

---

## 11. Message Ordering

Users care primarily about order within a conversation.

The goal is usually:

```text
M1 before M2 before M3
```

for a single chat thread.

Possible strategy:

```text
conversationId -> partition owner
```

All messages for the same conversation are routed through one logical ordering domain.

Kafka partitioning:

```text
key = conversationId
```

This preserves event order within that partition.

Trade-off:

A huge group chat can become a hot partition.

---

## 12. Client Timestamp vs Server Ordering

Do not trust client clocks for authoritative ordering.

Mobile devices may have clock skew.

Better:

- client timestamp for UX hints
- server-assigned sequence for authoritative order

Example:

```text
conversationSequence = 91821
```

The client renders based on sequence rather than local clock alone.

---

## 13. Idempotency and Duplicate Sends

Mobile networks are unreliable.

A client may send a message, lose the ACK, and retry.

Without deduplication:

```text
hello
hello
```

Use:

```text
clientMessageId = unique per device
```

Store a mapping:

```text
(senderId, clientMessageId) -> messageId
```

Retries return the existing message instead of creating another one.

---

## 14. Delivery Semantics

A practical design commonly uses:

```text
at-least-once transport
+
idempotent processing
```

Exactly-once end-to-end semantics are difficult because clients, gateways, queues, and storage can fail independently.

The better goal is user-visible exactly-once behavior through deduplication.

---

## 15. Delivery Acknowledgements

A useful state model:

```text
SENT_BY_CLIENT
      |
      v
ACCEPTED_BY_SERVER
      |
      v
DELIVERED_TO_DEVICE
      |
      v
READ_BY_USER
```

These states mean different things and should not be conflated.

Example:

- server ACK: durable acceptance
- delivered: recipient device received message
- read: recipient opened/read conversation

---

## 16. Offline Delivery

If recipient is offline:

```text
Connection Registry -> no active device
```

Store pending delivery metadata.

Possible flow:

```text
Message persisted
    |
    v
Offline Delivery Queue
    |
    v
Recipient reconnects
    |
    v
Sync missing messages
```

The message store remains the source of truth; the offline queue can be an optimization/index.

---

## 17. Reconnect and Sync

A client reconnects with:

```text
lastSeenSequence = 91800
```

Server returns:

```text
91801 ... latest
```

This is better than trying to remember every missed socket push.

The durable sync API is the recovery mechanism; WebSocket delivery is the fast path.

---

## 18. Multi-Device Synchronization

A user may have:

```text
phone
web
laptop
```

Each device can have its own connection and sync cursor.

Store:

```text
(userId, deviceId) -> lastDeliveredSequence
```

When the user sends from device A, device B should also receive the message so conversation state stays synchronized.

This turns one-to-one delivery into multi-endpoint fan-out.

---

## 19. Presence

Presence data is ephemeral:

```text
ONLINE
OFFLINE
LAST_SEEN
TYPING
```

Do not store high-frequency presence updates in the primary transactional database.

Use:

- Redis/in-memory state
- TTLs
- event streams

Example:

```text
presence:u123 = online
TTL = 60s
```

Heartbeat refreshes the state.

---

## 20. Typing Indicators

Typing events are ephemeral and should not be durably stored.

```text
u1 typing in conv9
```

Deliver best-effort.

Dropping one typing event is acceptable.

This illustrates an important design principle:

> Different event types require different durability guarantees.

---

## 21. Group Chat Fan-Out

Small group:

```text
100 members
```

Fan-out on write is reasonable.

Large group/community:

```text
1M members
```

Naively creating 1M delivery records per message is expensive.

Possible strategies:

- fan-out on write for small groups
- fan-out on read for massive groups
- hybrid threshold-based strategy

This is similar to the celebrity problem in feed systems.

---

## 22. Group Membership

Group metadata:

```text
groupId
members
admins
roles
createdAt
version
```

Membership checks must happen before accepting a message.

Cache membership for speed, but maintain a durable source of truth.

Large groups may need sharded membership storage.

---

## 23. Media Messages

Do not send large media files through the chat message store or WebSocket payload itself.

Better flow:

```text
Client
  |
  | request upload URL
  v
Media Service
  |
  v
Object Storage
  |
  v
CDN
```

Then send a chat message containing media metadata/reference:

```json
{
  "type": "IMAGE",
  "mediaId": "m789"
}
```

This separates control plane from data plane.

---

## 24. Object Storage and CDN

Media belongs in object storage such as S3/GCS-equivalent storage.

CDN provides:

- edge caching
- lower latency
- reduced origin bandwidth

Security controls may include:

- signed URLs
- expiring download tokens
- access checks

---

## 25. Storage Model

Message access pattern is primarily:

```text
conversationId + sequence range
```

A storage model could use:

```text
partition key = conversationId
sort key = sequence/messageId
```

Suitable technologies may include:

- Cassandra/ScyllaDB
- DynamoDB
- sharded relational DB
- distributed KV/wide-column store

Choice depends on scale, query requirements, operational expertise, and consistency needs.

---

## 26. Why Not One SQL Database Forever?

At moderate scale, SQL can work very well.

Do not prematurely reject relational databases.

At massive scale, chat workloads create:

- huge append volume
- large history
- partition-heavy access
- global replication challenges

That may push the design toward distributed partitioned stores.

In an interview, explain the migration pressure instead of declaring NoSQL as the default answer.

---

## 27. Sharding

Natural shard key:

```text
conversationId
```

Advantages:

- conversation messages colocated
- range reads efficient
- ordering easier

Problem:

A massive conversation may become a hot shard.

Mitigation:

- sub-shard by conversation + bucket
- separate architecture for large broadcast groups

---

## 28. Caching

Good cache candidates:

- group membership
- user profile metadata
- connection registry
- recent conversation metadata
- presence

Do not assume the entire message history belongs in Redis.

Durable history should live in storage optimized for sequential retrieval.

---

## 29. Push Notifications

If recipient is offline, trigger mobile push:

```text
Chat Event
   |
   v
Notification Service
   |
   +--> APNs
   +--> FCM
```

Push notification is a wake-up hint, not the authoritative message channel.

The client should sync the actual message from the chat backend after waking.

---

## 30. Backpressure

Suppose gateway inbound traffic is:

```text
2M messages/sec
```

but downstream persistence can process:

```text
1.5M/sec
```

Lag grows.

Use:

- bounded queues
- admission control
- per-tenant/user limits
- Kafka buffering
- autoscaling
- load shedding for noncritical ephemeral events
- pause/resume where appropriate

Do not let unbounded queues move the outage elsewhere.

---

## 31. Slow Consumer Problem

A recipient may have a very slow connection.

Do not let one socket block a gateway event loop.

Each connection needs bounded outbound buffering.

If buffer exceeds threshold:

```text
stop queueing
mark client behind
force reconnect/resync
```

The durable sync path repairs state later.

---

## 32. WebSocket Gateway Failure

If Gateway 7 dies:

```text
all its sockets disconnect
```

Clients reconnect through the load balancer to another gateway.

Because messages are durable, the new connection resumes using a sync cursor.

Gateway failure should cause temporary disconnects, not data loss.

---

## 33. Connection Registry Consistency

A stale registry may say:

```text
u123 -> Gateway 7
```

but the user has already reconnected to Gateway 12.

Use:

- TTL-based registry entries
- connection generation/version IDs
- heartbeat refresh

Delivery should tolerate stale routing and fall back to durable sync.

---

## 34. Multi-Region Architecture

```text
                    Global DNS
                        |
          +-------------+-------------+
          |                           |
          v                           v
      India Region                 US Region
          |                           |
          v                           v
   WebSocket Gateways          WebSocket Gateways
          |                           |
          v                           v
      Chat Service                 Chat Service
          |                           |
          +-----------+---------------+
                      |
                      v
              Global/Replicated
               Message Storage
```

Possible user routing models:

- home region per user
- nearest region for connection
- conversation home region

Each has trade-offs.

---

## 35. Conversation Home Region

One useful model:

```text
conversationId -> home region
```

All writes for that conversation go through its home region.

Advantages:

- easier ordering
- simpler sequencing

Disadvantages:

- cross-region write latency
- failover complexity

Alternative designs trade stronger local ordering for lower global latency.

---

## 36. Region Failure

If a conversation's home region fails:

- clients reconnect elsewhere
- secondary region takes ownership
- message log/storage must already be replicated enough for the desired RPO
- sequence allocation must avoid overlap

Failover can create duplicate processing, so idempotency remains essential.

---

## 37. Consistency Trade-Offs

Not every feature needs the same consistency.

Strong or stronger consistency desirable for:

- membership updates
- message sequence assignment
- message persistence

Eventual consistency acceptable for:

- presence
- typing indicators
- read receipt counters
- last seen

This is a key interview discussion.

---

## 38. Read Receipts

Read receipts can be high volume.

Instead of storing every tiny read event forever, store a high-water mark:

```text
(userId, conversationId) -> lastReadSequence
```

This compresses many events into one monotonic value.

---

## 39. Unread Counts

Unread count can be derived from:

```text
latestConversationSequence - lastReadSequence
```

or maintained as a cached/materialized counter.

Be careful with counters under retries and multi-device activity.

High-water marks are often easier to reason about.

---

## 40. Search

Message search introduces a separate indexing system.

```text
Message Store
    |
    v
Indexing Pipeline
    |
    v
Search Index
```

For E2EE systems, server-side full-text indexing may conflict with privacy/security goals.

Search design therefore depends heavily on encryption requirements.

---

## 41. End-to-End Encryption Considerations

With E2EE:

```text
sender encrypts
server stores ciphertext
recipient decrypts
```

The server should not possess plaintext message content.

This affects:

- search
- moderation
- push previews
- multi-device key management
- backups

A system design interview generally does not require implementing the cryptographic protocol, but you should acknowledge these architecture impacts.

---

## 42. Security

Security concerns:

- authenticated WebSocket handshake
- short-lived access tokens
- TLS everywhere
- device/session revocation
- rate limiting
- spam/abuse detection
- block lists
- group authorization
- signed media URLs
- encryption at rest
- secrets management
- audit trails for administrative actions

Never trust sender-supplied conversation membership claims.

---

## 43. Rate Limiting and Abuse

Possible limits:

```text
messages/sec/user
new conversations/day
media upload rate
large-group send rate
failed-auth attempts
```

Spam detection can run asynchronously but hard abuse limits should exist at ingress.

---

## 44. Observability

Important metrics:

```text
active_websocket_connections
connection_churn
message_ingress_rate
message_delivery_latency
message_persist_latency
queue_lag
fanout_lag
reconnect_rate
delivery_success_rate
offline_sync_latency
hot_partition_rate
websocket_send_buffer_size
region_failover_events
```

Track p50/p95/p99, not just averages.

---

## 45. Capacity Estimation

Example interview assumptions:

```text
100M DAU
50 messages/user/day
= 5B messages/day
```

Average:

```text
5B / 86,400 ≈ 57.8K messages/sec
```

If peak is 10x:

```text
~578K messages/sec
```

These are sample assumptions, not facts to memorize.

Also estimate:

- concurrent connections
- average message size
- media volume
- storage retention
- fan-out amplification

---

## 46. Production-Grade Architecture

```text
                         Global DNS
                            |
                +-----------+-----------+
                |                       |
                v                       v
            Region A                 Region B
                |                       |
                v                       v
        Load Balancer Fleet      Load Balancer Fleet
                |                       |
                v                       v
        WebSocket Gateways       WebSocket Gateways
                |                       |
                +-----------+-----------+
                            |
                            v
                      Chat Services
                            |
              +-------------+-------------+
              |                           |
              v                           v
       Connection Registry          Message Store
              |                           |
              |                           v
              |                     Kafka/Event Bus
              |                           |
              |                +----------+----------+
              |                |                     |
              v                v                     v
       Online Delivery    Offline Sync          Push Service
                                                   |
                                             APNs / FCM

Media Service -> Object Storage -> CDN
Presence -> Ephemeral Store
Search -> Async Indexing Pipeline
```

---

## 47. Principal Engineer Failure Scenario

Assume:

```text
20M active WebSocket connections
```

A regional network issue causes 30% of clients to reconnect within one minute.

At the same time:

```text
Kafka lag rises
Redis connection registry latency spikes
push provider starts timing out
```

This creates a reconnect storm plus downstream pressure.

A weak system can collapse from:

```text
reconnect storm
-> auth spike
-> registry spike
-> gateway CPU exhaustion
-> message retry amplification
-> queue growth
-> cascading outage
```

Principal-level response:

1. reconnect exponential backoff with jitter
2. gateway admission control
3. connection ramp-up limits
4. cached auth/session validation where safe
5. registry timeout + degraded routing
6. durable message acceptance isolated from push provider
7. circuit breaker around push provider
8. separate pools for connection setup and message delivery
9. priority shedding for typing/presence events
10. bounded outbound socket queues
11. autoscaling with pre-warmed gateway capacity
12. regional failover only with controlled traffic shift
13. Kafka consumer scaling where downstream capacity exists
14. do not blindly retry against already-saturated dependencies
15. rely on sync-after-reconnect for recovery

Core principle:

> Real-time delivery is the fast path; durable message storage plus resumable synchronization is the recovery path.

---

## 48. Interview Follow-Up Questions

Be prepared for:

- Why WebSockets?
- How do you route to an online user's gateway?
- What happens when a gateway dies?
- How do you guarantee ordering?
- Why not use client timestamps?
- How do you prevent duplicate messages?
- At-least-once vs exactly-once?
- How do offline users receive messages?
- How does reconnect synchronization work?
- How do you support multiple devices?
- How do group chats scale?
- What happens with a 1M-member group?
- Where do media files live?
- How do you handle a hot conversation?
- What data belongs in Redis?
- How do read receipts scale?
- How does multi-region routing work?
- What happens during region failure?
- How does E2EE change architecture?
- How do you survive a reconnect storm?

---

## 49. Interview Summary

The transferable concepts in this design are:

```text
WebSockets
Connection management
Heartbeats
Distributed connection registry
Message ordering
Partitioning
Idempotency
At-least-once delivery
Acknowledgements
Offline synchronization
Multi-device sync
Presence
Fan-out
Hot partitions
Media/object storage
CDN
Backpressure
Slow consumers
Sharding
Multi-region ownership
Consistency trade-offs
Read high-water marks
E2EE considerations
Rate limiting
Circuit breakers
Bulkheads
Reconnect storms
Observability
```

A strong answer should emphasize that socket delivery is not the source of truth. Durable storage, idempotent processing, conversation-level ordering, and resumable synchronization are what make the system reliable when connections and regions fail.
