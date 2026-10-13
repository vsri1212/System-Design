# Ticket Booking System — Ticketmaster / BookMyShow High-Level Design

A senior/principal-level HLD case study for designing a large-scale ticketing platform for concerts, sports, movies, and high-demand events.

The central problem is **correctness under extreme concurrency**: thousands or millions of users may attempt to reserve the same small set of seats at the same time, and the system must never oversell inventory.

---

## 1. Problem Statement

Design a ticket booking system where users can:

- discover events/shows
- view venue layouts and seat availability
- temporarily hold seats
- complete payment
- receive confirmed tickets
- cancel/refund according to policy

The system must handle:

- flash-sale traffic
- hot events
- seat contention
- temporary holds
- payment races
- retries
- failures between inventory and payment
- multi-region reads

The most important invariant is:

> A seat can belong to at most one confirmed booking for a given event/show.

---

## 2. Functional Requirements

Users should be able to:

- search events
- view show details
- view seat map
- select seats
- place a temporary hold
- pay for held seats
- confirm booking
- cancel/refund
- retrieve ticket details

Example APIs:

```http
GET  /events/{eventId}
GET  /shows/{showId}/seats
POST /shows/{showId}/holds
POST /bookings
POST /payments
POST /bookings/{bookingId}/cancel
GET  /bookings/{bookingId}
```

Example seat hold request:

```json
{
  "showId": "show-123",
  "userId": "u-99",
  "seatIds": ["A10", "A11"],
  "idempotencyKey": "req-abc-123"
}
```

---

## 3. Non-Functional Requirements

- no overselling
- low-latency seat availability reads
- strong correctness for reservations
- high availability
- horizontal scalability
- idempotent writes
- graceful flash-sale handling
- bounded lock/hold duration
- payment correctness
- strong observability
- auditability

---

## 4. Core Domain Model

```text
Event
Venue
Show
Seat
SeatInventory
SeatHold
Booking
Payment
Ticket
```

Example seat inventory record:

```text
show_id
seat_id
status
hold_id
booking_id
version
hold_expires_at
```

Status:

```text
AVAILABLE
HELD
BOOKED
```

---

## 5. High-Level Architecture

```text
Clients
   |
   v
API Gateway
   |
   +--> Event/Search Service
   +--> Seat Inventory Service
   +--> Booking Service
   +--> Payment Service
   +--> Ticket Service

Seat Inventory Service
   |
   v
Strongly Consistent Inventory Store

Booking Events
   |
   v
Kafka/Event Bus
   |
   +--> Notifications
   +--> Analytics
   +--> Audit
   +--> Refund/Reconciliation Workers
```

---

## 6. Seat Availability Is Not the Source of Truth

A cached seat map may say:

```text
A10 = AVAILABLE
```

but another user may have reserved it milliseconds ago.

Therefore:

> Seat-map reads can be cached/eventually consistent, but seat reservation must hit a strongly controlled authoritative inventory path.

This distinction is critical.

---

## 7. Seat State Machine

```text
AVAILABLE
   |
   | hold
   v
HELD
   |
   | payment succeeds
   v
BOOKED
```

Other transition:

```text
HELD --expiry/cancel--> AVAILABLE
```

Invalid transition:

```text
BOOKED -> HELD
```

unless product-specific refund/reissue logic explicitly permits it.

---

## 8. Why Temporary Holds?

Without a hold:

```text
User selects seat
User enters payment details
Another user buys seat
First payment completes
```

Now payment succeeded but inventory disappeared.

A temporary hold gives the user a bounded window to pay.

Example:

```text
hold TTL = 5 minutes
```

---

## 9. Hold Invariant

For a given show and seat:

```text
only one active hold or booking may exist
```

This invariant must be enforced atomically.

Do not rely on application-side read-then-write logic.

Bad:

```text
SELECT seat
if AVAILABLE:
   UPDATE to HELD
```

Two requests can both read AVAILABLE.

---

## 10. Atomic Reservation with Compare-and-Set

Use a conditional update:

```text
UPDATE seat_inventory
SET status='HELD',
    hold_id=?,
    hold_expires_at=?,
    version=version+1
WHERE show_id=?
  AND seat_id=?
  AND status='AVAILABLE'
```

Affected rows:

```text
1 -> success
0 -> someone else already won
```

This is often simpler and safer than introducing a distributed lock.

---

## 11. Optimistic Locking

Example:

```text
seat version = 42
```

Client/service attempts:

```text
UPDATE ... WHERE version=42
```

If another request already changed the seat:

```text
0 rows updated
```

Return conflict and ask caller to refresh.

Optimistic locking works well when contention is moderate.

---

## 12. Pessimistic Locking

Possible SQL pattern:

```sql
SELECT *
FROM seat_inventory
WHERE show_id=? AND seat_id=?
FOR UPDATE;
```

Then update inside transaction.

Advantages:

- simple correctness model

Disadvantages:

- blocked threads/connections
- lock queues under flash-sale contention
- deadlock risk
- reduced throughput

Use carefully for highly contended hot seats.

---

## 13. Optimistic vs Pessimistic Locking

Optimistic:

- high throughput when conflicts are uncommon
- failures retried by losers

Pessimistic:

- blocks competing writers
- useful when conflict probability is very high

For ticketing, atomic conditional writes are often preferred for individual seat claims.

---

## 14. Distributed Locking

A distributed lock might look like:

```text
lock(show123:A10)
```

But locks introduce:

- lease expiry
- stale lock holders
- fencing problems
- coordination bottlenecks
- failover complexity

Do not use a distributed lock when the database can enforce the invariant atomically.

---

## 15. When Distributed Locks May Be Justified

Potential use cases:

- multi-resource critical section spanning systems
- coordinated maintenance operations
- singleton background jobs

For ordinary single-seat reservation, prefer data-store atomicity.

---

## 16. Multi-Seat Reservation

User selects:

```text
A10, A11, A12, A13
```

Requirement:

```text
all seats held together
or none
```

If inventory is in one transactional shard, use a transaction.

```text
BEGIN
claim A10
claim A11
claim A12
claim A13
COMMIT
```

If one fails:

```text
ROLLBACK
```

---

## 17. Shard Locality Matters

A natural partition key is:

```text
showId
```

Why?

All seats for one show are highly related and often reserved together.

Keeping a show's inventory in one shard enables local transactions and simplifies correctness.

Trade-off:

A globally popular show becomes a hot shard.

---

## 18. Hot Show Problem

Suppose:

```text
100K seats
5M users attempt purchase in 30 sec
```

Traffic massively exceeds actual inventory.

The system must avoid allowing all 5M users to hammer the inventory database.

---

## 19. Virtual Waiting Room

Use an admission layer:

```text
Users
  |
  v
Waiting Room
  |
  | controlled release
  v
Booking APIs
```

The waiting room reduces concurrency to a level the booking system can safely process.

It is not just UX. It is overload protection.

---

## 20. Waiting Room Token

A user admitted into purchase flow receives a signed token containing:

```text
userId
eventId
admissionTime
expiry
nonce
```

Booking APIs verify it.

This prevents clients from bypassing the waiting room by directly hitting purchase endpoints.

---

## 21. Fairness

Possible policies:

- FIFO queue
- randomized lottery
- priority members
- geographically segmented release

System design should separate fairness policy from seat correctness.

---

## 22. Seat Hold Creation Flow

```text
Client
  |
  v
Booking API
  |
  v
Inventory Service
  |
  | atomic seat transition
  v
Inventory DB
  |
  v
Create Hold Record
  |
  v
Return holdId + expiresAt
```

The inventory update and hold record should be transactionally consistent if stored together.

---

## 23. Hold Expiry

At expiration:

```text
HELD -> AVAILABLE
```

Do not rely only on a single timer process.

Possible approaches:

- expiry index + sweeper
- TTL store
- delayed queue
- check expiry during reservation attempt

The safest design combines explicit expiry timestamps with background cleanup.

---

## 24. Lazy Expiry

Suppose seat says:

```text
status=HELD
hold_expires_at=10:00:00
```

Current time is 10:01.

Reservation logic may treat the seat as reclaimable even if the sweeper has not yet cleaned it.

This prevents timer delays from reducing inventory availability.

---

## 25. Hold Extension

If product allows extending holds, avoid indefinite starvation.

Use strict rules:

- maximum total hold duration
- limited extension count
- payment-in-progress exception

Otherwise bots can monopolize scarce inventory.

---

## 26. Payment Flow

```text
HELD seats
   |
   v
Payment initiated
   |
   v
Provider
   |
   v
Payment success
   |
   v
Booking confirmation
   |
   v
HELD -> BOOKED
```

Payment and booking live in separate failure domains.

This creates distributed transaction challenges.

---

## 27. Never Use a Naive Two-Step Flow

Bad:

```text
1. charge card
2. mark seats booked
```

If step 1 succeeds and step 2 fails:

```text
money captured
seat not booked
```

Also bad:

```text
1. mark seat booked
2. charge card
```

If payment fails:

```text
inventory blocked without payment
```

We need explicit workflow state and reconciliation.

---

## 28. Booking State Machine

Example:

```text
CREATED
  |
  v
HOLD_ACTIVE
  |
  v
PAYMENT_PENDING
  |
  +--> PAYMENT_FAILED
  |
  v
PAYMENT_SUCCEEDED
  |
  v
CONFIRMED
```

Compensation path:

```text
PAYMENT_SUCCEEDED but confirmation fails
-> retry confirmation
-> if unrecoverable, refund/void payment
```

---

## 29. Payment Authorization Before Capture

A useful pattern:

```text
authorize payment
confirm inventory
capture payment
```

Authorization reserves funds without necessarily settling them.

If booking fails, void authorization.

Exact behavior depends on payment provider capabilities.

---

## 30. Saga Pattern

Ticket booking naturally fits a saga/workflow:

```text
Hold Seats
   |
Authorize Payment
   |
Confirm Booking
   |
Capture Payment
   |
Issue Ticket
```

Compensations:

```text
release seats
void/refund payment
invalidate ticket
```

---

## 31. Orchestration vs Choreography

For critical booking workflows, orchestration is often easier to reason about.

A Booking Orchestrator knows the current step and compensation state.

Pure choreography can become difficult to debug when many asynchronous services independently react.

---

## 32. Idempotency

Critical APIs need idempotency keys:

```text
create hold
create booking
payment authorize
payment capture
refund
cancel booking
```

Example:

```text
Idempotency-Key: user99-book-show123-req77
```

Repeated requests must return the same logical result.

---

## 33. Payment Idempotency

Never double-charge because of retry.

Key:

```text
bookingId + paymentAction
```

Persist the mapping between internal operation and provider transaction ID.

---

## 34. Transactional Outbox

Suppose booking DB commits:

```text
booking=CONFIRMED
```

then crashes before publishing `BOOKING_CONFIRMED`.

Ticket/notification systems may never learn about it.

Use:

```text
DB transaction:
  update booking
  insert outbox row
```

Then publish asynchronously.

---

## 35. Seat Map Caching

Seat maps are read-heavy.

Cache:

```text
showId -> seat availability snapshot
```

But cache is advisory only.

Final hold request must always revalidate against authoritative inventory state.

---

## 36. Cache Invalidation

When seat becomes HELD/BOOKED:

- update/invalidate cache asynchronously
- push availability events to WebSocket/SSE clients if needed

Temporary stale UI is acceptable.

Overselling is not.

---

## 37. Real-Time Seat Updates

During highly interactive seat selection, users benefit from live updates.

```text
Inventory Events
   |
   v
WebSocket/SSE Gateway
   |
   v
Clients
```

Again, these are hints. The authoritative claim happens at hold time.

---

## 38. General Admission Inventory

Not every event has assigned seats.

Example:

```text
GA_STANDING quantity=20,000
```

Need atomic decrement:

```text
remaining >= requestedQuantity
```

Use conditional counter update.

---

## 39. Atomic Inventory Counter

Example:

```sql
UPDATE inventory
SET remaining = remaining - 4
WHERE show_id=?
  AND section='GA'
  AND remaining >= 4;
```

Affected rows 1 -> success.

This prevents overselling.

---

## 40. Overselling Invariant

For reserved seating:

```text
one seat -> one booking
```

For general admission:

```text
sold + held <= capacity
```

These invariants should be enforced at the data layer where possible.

---

## 41. Read Replicas

Use replicas for:

- event browsing
- venue information
- historical bookings

Do not rely on a lagging read replica to decide whether a seat can be sold.

Reservation decisions must use the authoritative write path.

---

## 42. Search

Event search/indexing:

```text
Event Metadata
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

Search can be eventually consistent.

Seat correctness cannot.

---

## 43. Sharding Strategy

Natural shard key:

```text
eventId/showId
```

Advantages:

- local seat transactions
- predictable inventory routing

Challenges:

- hot events
- uneven shard load

Use capacity-aware placement of big events.

---

## 44. Pre-Sharding Hot Events

For giant events, inventory may be partitioned by section:

```text
show123:floor
show123:lower-bowl
show123:upper-bowl
```

This increases write parallelism.

Trade-off:

Multi-section atomic booking becomes harder.

Use only when scale justifies the complexity.

---

## 45. Hot Key Mitigation

Popular seat-map keys may overload cache nodes.

Use:

- local caches
- replicated hot keys
- request coalescing
- snapshot caching
- CDN for static venue layout

Keep dynamic availability separately controlled.

---

## 46. Bot / Abuse Protection

High-value tickets attract bots.

Mitigations:

- rate limiting
- waiting-room tokens
- CAPTCHA/risk challenges
- device fingerprinting
- account-age/reputation signals
- purchase limits
- anti-replay tokens

Bot protection must happen before scarce inventory is consumed.

---

## 47. Purchase Limits

Example:

```text
max 4 tickets/user/event
```

Enforcement should happen transactionally with booking when possible.

Otherwise concurrent requests can bypass the limit.

---

## 48. Inventory Reconciliation

Periodically verify:

```text
seat inventory
vs
bookings
vs
payments
```

Look for anomalies:

- BOOKED seat without booking
- confirmed booking without successful payment
- successful payment without confirmed booking
- expired hold still blocking seat

Financial systems need reconciliation jobs.

---

## 49. Refund Flow

Cancellation may trigger:

```text
CONFIRMED
  |
  v
CANCEL_PENDING
  |
  v
REFUND_PENDING
  |
  v
REFUNDED
```

Inventory release policy depends on event rules.

Refund should be idempotent.

---

## 50. Ticket Issuance

After confirmed booking:

```text
Booking Event
   |
   v
Ticket Service
   |
   v
Generate signed ticket / QR token
```

Ticket identifiers must be unguessable and revocable.

---

## 51. QR / Entry Validation

At venue entry:

```text
scan ticket
-> validate signature/token
-> check not revoked
-> atomically mark admitted
```

Prevent duplicate entry with an atomic first-scan-wins state transition.

---

## 52. Multi-Region Reads

Browsing can be active-active globally:

```text
Global DNS
  |
  +--> Region A
  +--> Region B
```

Event metadata and seat snapshots can replicate.

---

## 53. Multi-Region Writes

For inventory correctness, assign a home region per show/event.

```text
show123 -> Region A
```

All reservation writes route there.

This avoids conflicting active-active seat ownership.

---

## 54. Why Not Active-Active Seat Writes Everywhere?

If two regions independently sell the same seat during a network partition:

```text
Region A -> A10 sold to Alice
Region B -> A10 sold to Bob
```

Conflict resolution cannot fairly undo one confirmed customer later.

Therefore strong single-owner inventory writes are much easier to reason about.

---

## 55. Region Failure

If show home region fails:

Options:

- temporarily stop new sales
- fail over to a synchronously replicated secondary

Correctness may be more important than continuous availability.

This is a classic CAP trade-off.

---

## 56. Failover Safety

Before secondary accepts writes, ensure old primary cannot continue acting as inventory owner.

Use:

- consensus/leader lease
- fencing epoch
- strongly controlled failover

Stale primary writes must be rejected.

---

## 57. Fencing Tokens

Each inventory leader epoch gets a monotonically increasing token:

```text
epoch 41
```

Storage accepts writes only from the latest epoch.

This protects against split-brain/stale leaders.

---

## 58. Consistency Choices

Strong consistency required for:

- seat claim
- booking confirmation
- inventory decrement
- purchase-limit enforcement
- payment transitions

Eventual consistency acceptable for:

- search
- analytics
- recommendation
- seat-map display snapshots

---

## 59. Backpressure

If inventory can process 20K holds/sec but incoming traffic is 500K requests/sec:

Do not allow an unbounded queue.

Use:

- waiting room
- admission control
- bounded request queues
- rate limits
- load shedding

Reject/queue excess before database saturation.

---

## 60. Retry Storms

Clients often retry aggressively when they see timeouts.

This can amplify overload.

Use:

- retry-after headers
- jittered client backoff
- idempotency
- queue tokens
- circuit breakers

---

## 61. Timeouts

Strict dependency timeouts are critical.

A slow payment provider should not hold inventory threads forever.

Use bounded payment windows and workflow state rather than synchronous blocking chains.

---

## 62. Bulkheads

Separate resource pools for:

- event browsing
- seat reads
- inventory writes
- payments
- notifications

A broken notification provider must not block reservation threads.

---

## 63. Graceful Degradation

During overload, degrade:

- recommendations
- seat-map animations
- noncritical analytics
- secondary enrichments

Preserve:

- inventory correctness
- payment workflow
- booking state

---

## 64. Observability

Key metrics:

```text
seat_hold_attempt_rate
seat_hold_success_rate
seat_conflict_rate
hold_expiry_rate
booking_confirmation_latency
payment_authorization_latency
payment_failure_rate
waiting_room_depth
inventory_db_lock_wait
inventory_write_latency
oversell_count
reconciliation_mismatch_count
```

The oversell metric should ideally remain exactly zero.

---

## 65. Capacity Estimation

Example flash-sale assumption:

```text
2M users arrive in 60 sec
= ~33K arrivals/sec average
```

If all repeatedly refresh seat maps and retry holds, effective QPS may be much higher.

The core conclusion:

> Admission control matters more than simply autoscaling application servers because the authoritative inventory write capacity is finite.

---

## 66. Production Architecture

```text
                           Clients
                              |
                         Global DNS
                              |
                    CDN / Edge Protection
                              |
                        Waiting Room
                              |
                         API Gateway
                              |
          +-------------------+--------------------+
          |                   |                    |
          v                   v                    v
     Event/Search       Seat Read Service     Booking Service
          |                   |                    |
          v                   v                    v
      Search Index        Seat Cache        Inventory Service
                                                   |
                                                   v
                                      Strongly Consistent Store
                                                   |
                                                   v
                                               Outbox
                                                   |
                                                   v
                                                 Kafka
                     +-----------------------------+----------------+
                     |                             |                |
                     v                             v                v
                Payment Service               Ticket Service   Notifications
                     |
                     v
               Payment Provider
```

---

## 67. Principal Engineer Failure Scenario

Assume a stadium concert opens sales.

Within 20 seconds:

```text
3M users arrive
400K concurrent hold attempts
inventory DB CPU hits 90%
payment provider p99 jumps from 300 ms to 12 sec
Redis seat-cache latency spikes
```

A weak architecture collapses:

```text
cache latency
-> clients retry
-> booking APIs flood inventory DB
-> lock waits grow
-> hold transactions time out
-> users retry again
-> payment calls pile up
-> inventory holds expire inconsistently
-> cascading outage
```

Principal-level response:

1. activate/strengthen virtual waiting room
2. release users at a bounded rate
3. bypass stale seat cache for final claims but never bypass authoritative inventory
4. use atomic conditional seat claims instead of long-held locks
5. separate seat-map reads from inventory-write pools
6. strict payment timeouts
7. payment authorization workflow is asynchronous/stateful
8. extend hold only for valid payment-in-progress flows, with hard maximum
9. circuit-break noncritical dependencies
10. shed recommendations/animations/analytics
11. use Retry-After and jitter to prevent retry storms
12. keep booking/payment operations idempotent
13. isolate event shards so one hot show does not impact all shows
14. monitor lock wait/conflict rates and waiting-room depth
15. reconcile payments vs bookings continuously
16. if home region becomes unhealthy, prefer temporarily stopping sales over risking double-sell

Core principle:

> In ticketing, correctness beats availability at the final inventory boundary. You can delay a buyer; you cannot safely sell the same seat twice.

---

## 68. Interview Follow-Up Questions

Be prepared for:

- How do you prevent two users from booking the same seat?
- Optimistic vs pessimistic locking?
- Why not use a distributed lock for every seat?
- How do seat holds expire safely?
- How do you reserve multiple seats atomically?
- How do you handle payment success but booking failure?
- Why use a waiting room?
- How do you prevent bots bypassing the queue?
- How do you shard inventory?
- What is the hot-event problem?
- What if one event becomes a hot shard?
- Why can seat maps be eventually consistent?
- Why can final booking not be eventually consistent?
- How do you prevent double charges?
- How do you use saga/outbox?
- How do you handle refunds?
- How do you support multi-region?
- Why might you stop sales during failover?
- Where do fencing tokens matter?

---

## 69. Interview Summary

The transferable concepts in this design are:

```text
Strong invariants
Atomic conditional writes
Optimistic locking
Pessimistic locking
Temporary holds
Leases / expiry
Multi-resource transactions
Distributed lock trade-offs
Waiting rooms
Admission control
Hot shards
Payment workflows
Saga
Compensation
Idempotency
Transactional outbox
Authoritative state vs cache
Multi-region ownership
Fencing tokens
Backpressure
Retry storms
Bulkheads
Reconciliation
Observability
```

A strong HLD answer should clearly separate **read scalability** from **write correctness**. Seat maps can be cached and stale for milliseconds; the authoritative seat claim cannot be ambiguous. The data model and transaction boundary should enforce that invariant even under retries, crashes, and extreme contention.
