# Amazon Order Management System — High-Level Design

A senior/principal-level HLD case study for designing an Amazon-style order platform focused on distributed transactions, inventory correctness, payment coordination, shipping, event-driven workflows, and failure recovery.

The core lesson is:

> Orders span multiple independently failing systems. Correctness comes from explicit state machines, idempotent operations, durable events, and compensation—not from pretending the whole workflow is one giant ACID transaction.

---

## 1. Problem Statement

Design an order management system that supports:

- checkout
- inventory reservation
- payment authorization/capture
- order confirmation
- shipment creation
- cancellation
- refunds
- returns
- partial fulfillment
- retries and duplicate events
- multi-region operation

The system must avoid:

- overselling
- duplicate charges
- lost orders
- stuck reservations
- duplicate shipments
- inconsistent order/payment/inventory state

---

## 2. Functional Requirements

Core APIs:

```http
POST /checkout
POST /orders
GET  /orders/{orderId}
POST /orders/{orderId}/cancel
POST /orders/{orderId}/refund
GET  /orders/{orderId}/status
```

Example checkout request:

```json
{
  "userId": "u123",
  "cartId": "c456",
  "shippingAddressId": "addr9",
  "paymentMethodToken": "pm_abc",
  "idempotencyKey": "checkout-991"
}
```

---

## 3. Non-Functional Requirements

- no duplicate orders from retries
- no overselling inventory
- no duplicate payment effects
- durable order state
- idempotent event consumers
- graceful partial failure handling
- horizontal scalability
- high availability
- auditability
- strong observability
- multi-region safety

---

## 4. Core Domain Model

```text
Cart
Order
OrderItem
InventoryReservation
Payment
Shipment
Refund
Return
OrderEvent
```

Order states may include:

```text
CREATED
INVENTORY_RESERVED
PAYMENT_AUTHORIZED
CONFIRMED
FULFILLING
SHIPPED
DELIVERED
CANCELLED
REFUND_PENDING
REFUNDED
```

---

## 5. High-Level Architecture

```text
Client
  |
  v
API Gateway
  |
  v
Checkout / Order Service
  |
  +--> Order DB
  +--> Inventory Service
  +--> Payment Service
  +--> Shipping/Fulfillment Service
  |
  +--> Outbox -> Kafka
                 |
                 +--> Notifications
                 +--> Analytics
                 +--> Fraud
                 +--> Search/History
                 +--> Reconciliation
```

---

## 6. Why Not One Distributed Database Transaction?

A checkout may span:

```text
Order DB
Inventory DB
Payment Provider
Shipping Service
```

These systems do not share one reliable ACID transaction boundary.

Trying to force one global transaction introduces blocking, coupling, and availability problems.

Better:

```text
local ACID transactions
+
durable workflow state
+
idempotent operations
+
compensation
```

---

## 7. Checkout Flow

```text
Client
  |
  v
Create Order
  |
  v
Reserve Inventory
  |
  v
Authorize Payment
  |
  v
Confirm Order
  |
  v
Create Fulfillment
```

Each step must have a durable state and compensation path.

---

## 8. Order Idempotency

Client may retry checkout after timeout.

Use:

```text
userId + idempotencyKey
```

Repeated request returns the same logical order instead of creating duplicates.

---

## 9. Order State Machine

Example:

```text
CREATED
   |
   v
RESERVING_INVENTORY
   |
   +--> INVENTORY_FAILED
   |
   v
INVENTORY_RESERVED
   |
   v
PAYMENT_AUTHORIZING
   |
   +--> PAYMENT_FAILED
   |
   v
PAYMENT_AUTHORIZED
   |
   v
CONFIRMED
```

Cancellation and refund transitions are explicit rather than ad hoc flags.

---

## 10. Inventory Reservation

Do not decrement permanent stock immediately before payment outcome is known.

Use reservation:

```text
available
reserved
sold
```

Example:

```text
on_hand = 100
reserved = 10
available = 90
```

---

## 11. Inventory Invariant

For each SKU/location:

```text
reserved + sold <= on_hand
```

This invariant must be enforced atomically.

---

## 12. Atomic Inventory Reservation

Example conditional update:

```sql
UPDATE inventory
SET reserved = reserved + :qty
WHERE sku = :sku
  AND on_hand - reserved - sold >= :qty;
```

Affected row count:

```text
1 -> success
0 -> insufficient inventory
```

---

## 13. Why Read-Then-Write Is Unsafe

Bad flow:

```text
read stock = 1
request A sees 1
request B sees 1
both decrement
```

Result:

```text
oversell
```

Use conditional writes or transactional locking.

---

## 14. Reservation TTL

Reservations should expire.

Example:

```text
reservation_expires_at = now + 10 min
```

If checkout never completes:

```text
reserved -> available
```

Avoid permanently stuck stock.

---

## 15. Reservation Sweeper + Lazy Expiry

Use both:

- background sweeper
- expiry check during new reservation attempt

Do not depend on one timer process for correctness.

---

## 16. Multi-Item Orders

Order may contain:

```text
SKU A x2
SKU B x1
SKU C x4
```

If all items are on one inventory shard, reserve transactionally.

If they span shards/warehouses, workflow must coordinate partial reservations and compensation.

---

## 17. All-or-Nothing vs Partial Fulfillment

Product policy matters.

Options:

```text
all items must be available
```

or

```text
allow split shipments / backorder
```

The order model should represent this explicitly.

---

## 18. Inventory by Warehouse

Stock may be distributed:

```text
SKU123
  Hyderabad WH: 20
  Mumbai WH: 5
  Bengaluru WH: 13
```

Reservation service may select warehouse based on:

- proximity
- stock
- delivery SLA
- cost

---

## 19. Allocation Service

Possible flow:

```text
Order Items
   |
   v
Allocation Service
   |
   +--> Warehouse A
   +--> Warehouse B
```

Allocation is distinct from physical shipment creation.

---

## 20. Payment Authorization

After inventory reservation:

```text
authorize payment
```

Authorization reduces risk before order confirmation.

Do not blindly capture money before knowing whether inventory is secured.

---

## 21. Authorization vs Capture

A common model:

```text
reserve inventory
-> authorize payment
-> confirm order
-> capture at shipment or immediately depending on policy
```

Exact timing depends on merchant/business rules.

---

## 22. Payment Idempotency

Use stable operation keys:

```text
orderId + AUTHORIZE
orderId + CAPTURE
orderId + REFUND
```

Retries must not create duplicate financial effects.

---

## 23. Saga Pattern

Checkout fits a saga:

```text
Create Order
   |
Reserve Inventory
   |
Authorize Payment
   |
Confirm Order
   |
Create Fulfillment
```

Compensations:

```text
release inventory
void authorization
cancel fulfillment
refund capture
```

---

## 24. Saga Orchestration

A central Order/Checkout Orchestrator controls steps.

Advantages:

- easy to understand workflow state
- explicit compensation
- easier debugging

Disadvantages:

- orchestrator becomes important infrastructure

---

## 25. Saga Choreography

Services react to events:

```text
ORDER_CREATED
-> Inventory reserves
-> INVENTORY_RESERVED
-> Payment authorizes
-> PAYMENT_AUTHORIZED
-> Order confirms
```

Advantages:

- loose coupling

Disadvantages:

- event flow becomes harder to reason about
- hidden cyclic dependencies are possible

---

## 26. When to Prefer Orchestration

For critical multi-step business workflows such as checkout, orchestration often provides clearer ownership and recovery semantics.

Use choreography for simpler downstream side effects.

---

## 27. Transactional Outbox

Suppose Order DB commits:

```text
state = CONFIRMED
```

then crashes before publishing `ORDER_CONFIRMED`.

Shipping may never start.

Use:

```text
DB transaction:
  update order
  insert outbox row
```

Publisher later emits to Kafka.

---

## 28. Inbox Pattern

Consumers may receive duplicate events.

Store:

```text
consumerName + eventId
```

If already processed:

```text
skip
```

This prevents duplicate shipment, duplicate refund, duplicate notification, etc.

---

## 29. Event Versioning

Events should carry:

```text
orderId
version
eventId
timestamp
```

Consumers reject stale transitions.

---

## 30. Kafka Partitioning

Useful key:

```text
orderId
```

This keeps events for one order in partition order.

But consumers must still validate state because replays and cross-topic interactions exist.

---

## 31. Exactly-Once Delivery vs Exactly-Once Effect

Kafka/message delivery may be at-least-once.

Practical design:

```text
at-least-once events
+
idempotent consumers
+
state-machine validation
```

Result:

```text
exactly-once business effect
```

---

## 32. Duplicate Shipment Problem

If `ORDER_READY_TO_SHIP` is delivered twice:

Bad:

```text
create 2 shipments
```

Good:

```text
idempotency key = orderId + fulfillmentGroupId
```

Second call returns existing shipment.

---

## 33. Shipping State Machine

```text
CREATED
  |
  v
PICKING
  |
  v
PACKED
  |
  v
SHIPPED
  |
  v
DELIVERED
```

Exceptions:

```text
FAILED
LOST
RETURN_TO_ORIGIN
```

---

## 34. Partial Shipments

An order may be split:

```text
Order 123
  |
  +--> Shipment A: items 1,2
  +--> Shipment B: item 3
```

Order state must be derived from child fulfillment states.

---

## 35. Order Aggregate vs Fulfillment Aggregate

Do not force all details into one giant row.

Use separate aggregates:

- Order: customer/commercial intent
- Fulfillment: warehouse/shipping execution
- Payment: money movement

They coordinate through workflow/events.

---

## 36. Cancellation

Cancellation rules depend on current state.

Example:

```text
CREATED -> cancel immediately
CONFIRMED -> release inventory / void auth
SHIPPED -> maybe no cancellation, create return instead
```

State machine enforces allowed transitions.

---

## 37. Cancellation Race

Race:

```text
warehouse starts shipment
customer cancels simultaneously
```

Use versioned state transitions and clear ownership.

Only one valid transition path should win.

---

## 38. Refund Flow

Refund is a separate workflow:

```text
REFUND_REQUESTED
  |
  v
REFUND_PROCESSING
  |
  v
REFUNDED
```

Idempotency key:

```text
orderId + refundId
```

---

## 39. Return Flow

Returns may involve:

```text
return requested
label generated
item received
quality inspected
refund issued
inventory restocked/disposed
```

Do not equate refund with return automatically.

---

## 40. Payment Success but Order Confirmation Fails

Scenario:

```text
payment authorized/captured
order DB unavailable
```

Do not guess.

Keep durable operation state and retry confirmation.

If unrecoverable:

```text
void/refund payment
release inventory
```

---

## 41. Order Confirmed but Payment Event Lost

Outbox prevents the common dual-write problem.

Reconciliation detects any remaining mismatch.

---

## 42. Inventory Reserved but Payment Fails

Compensation:

```text
release reservation
mark order payment failed/cancelled
```

This action must itself be idempotent.

---

## 43. Compensation Is Not Rollback

In distributed systems, previous effects may already be visible externally.

Compensation means creating a new inverse business action.

Examples:

```text
refund instead of deleting charge
release inventory instead of pretending reservation never happened
```

---

## 44. Retry Policy

Use:

- exponential backoff
- jitter
- bounded retries
- dead-letter/manual exception path
- stable idempotency key

Do not retry permanent failures indefinitely.

---

## 45. Unknown State

External dependencies may time out after processing.

Model:

```text
PAYMENT_UNKNOWN
SHIPMENT_UNKNOWN
```

Then reconcile/query provider.

Never convert uncertainty into guessed state.

---

## 46. Reconciliation

Periodic reconciliation compares:

```text
Order
Inventory reservation
Payment
Shipment
Refund
```

Detect anomalies:

```text
confirmed order without payment
payment without order confirmation
shipment without confirmed order
expired reservation still active
duplicate shipment
```

---

## 47. Reconciliation as a First-Class Subsystem

Build:

```text
scheduled scanner
matching rules
exception queue
automated repair where safe
operator tooling
```

Do not depend on ad hoc scripts.

---

## 48. Read Model

Order history is read-heavy.

Build denormalized read models:

```text
Order Summary View
Order Tracking View
```

Updated asynchronously from events.

Transactional write model remains authoritative.

---

## 49. CQRS-Style Separation

Possible approach:

```text
Write Model -> strong order state
Read Model  -> optimized customer history/tracking
```

Do not introduce CQRS just for fashion; use it when read patterns justify it.

---

## 50. Search

Order search for support/admin can use async indexing.

Search is eventually consistent.

Order state transitions are not.

---

## 51. Caching

Cache candidates:

- product metadata
- shipping estimates
- order read views

Avoid using cache as authoritative inventory or payment state.

---

## 52. Price Snapshot

Product price may change after checkout begins.

Order should store immutable commercial snapshot:

```text
unit price
discount
tax
shipping fee
currency
```

Do not recalculate historical order total from current product catalog.

---

## 53. Tax Snapshot

Store tax calculation result/version used for the order.

This improves auditability and refunds.

---

## 54. Promotion Consistency

Coupon usage may have limits.

Example:

```text
first 1000 uses
one use per customer
```

Enforce critical limits atomically similar to inventory.

---

## 55. Cart Is Not the Order

Cart is mutable, best-effort user state.

Order is an immutable commercial commitment snapshot.

At checkout:

```text
Cart -> snapshot -> Order
```

---

## 56. Security

- authenticated order APIs
- authorization by customer/merchant role
- tokenized payment methods
- PII encryption
- audit logs
- anti-replay/idempotency
- fraud checks
- least privilege

---

## 57. Fraud Integration

Fraud checks may run before payment authorization or shipment.

Possible results:

```text
ALLOW
REVIEW
BLOCK
```

High-risk orders may enter:

```text
MANUAL_REVIEW
```

---

## 58. Multi-Region Reads

Customer order history may be replicated globally.

Stale read models are acceptable within bounded delay.

Core order writes need stronger ownership.

---

## 59. Multi-Region Write Ownership

Possible mapping:

```text
orderId / customer commerce domain -> home region
```

All mutations for one order route through the owner region.

This simplifies ordering and idempotency.

---

## 60. Why Not Unconstrained Active-Active Order Writes?

During partition:

```text
Region A cancels order
Region B ships order
```

Merging later is not acceptable.

Use controlled ownership/consensus for order state transitions.

---

## 61. Region Failover

Failover must ensure old writer cannot continue mutating order state.

Use:

- leader epoch
- fencing token
- consensus-backed ownership

Stale region writes are rejected.

---

## 62. Inventory Region Ownership

Inventory is often naturally partitioned by warehouse/fulfillment region.

Global order workflow may coordinate multiple regional inventory services.

---

## 63. Backpressure

During a flash sale:

```text
checkout requests > inventory/payment capacity
```

Use:

- admission control
- bounded queues
- per-user limits
- rate limiting
- inventory-aware rejection

Do not let unbounded checkout requests overwhelm payment or inventory systems.

---

## 64. Bulkheads

Separate resource pools for:

- checkout
- order reads
- payment
- inventory
- shipment
- notifications

A shipping carrier outage should not prevent order history reads.

---

## 65. Circuit Breakers

Slow dependencies can cause cascading failures.

Examples:

- payment provider
- tax service
- shipping estimate provider

Use strict timeouts and fallback/degradation where business-safe.

---

## 66. Graceful Degradation

Possible degradations:

- disable recommendations
- use cached shipping estimate
- delay noncritical notifications
- pause analytics enrichment

Do not degrade:

- inventory correctness
- payment correctness
- order state validation

---

## 67. Shipping Carrier Failure

If carrier API fails after order confirmation:

Do not roll back the customer's confirmed order automatically.

Keep:

```text
FULFILLMENT_PENDING
```

Retry or route to another carrier according to policy.

---

## 68. Duplicate Event Handling

Every side-effect consumer must be idempotent.

Examples:

```text
ORDER_CONFIRMED twice -> one shipment
PAYMENT_CAPTURED twice -> one state transition
REFUND_REQUESTED twice -> one refund
```

---

## 69. Poison Messages

Malformed/permanently failing events should not block a whole partition indefinitely.

Use:

- bounded retries
- DLQ
- alerting
- replay tooling

---

## 70. Event Schema Evolution

Order events live for years.

Use versioned schemas and backward-compatible evolution.

Consumers should tolerate additive fields.

---

## 71. Audit Trail

Capture:

```text
who
what
when
order version
request ID
operation ID
source service
```

Critical manual support actions must also be audited.

---

## 72. Observability

Key metrics:

```text
checkout_success_rate
order_creation_latency
inventory_reservation_failure_rate
inventory_oversell_count
payment_authorization_latency
payment_unknown_state_count
order_saga_duration
saga_compensation_rate
outbox_lag
kafka_consumer_lag
shipment_creation_failure_rate
reconciliation_mismatch_count
```

Track p50/p95/p99 and by region/warehouse/provider.

---

## 73. Capacity Estimation

Example assumptions:

```text
50M orders/day
```

Average:

```text
~579 orders/sec
```

At 20x peak:

```text
~11.6K orders/sec
```

But each order creates multiple:

- DB writes
- inventory operations
- payment calls
- events
- read-model updates

Internal amplification is much higher than raw order QPS.

---

## 74. Production-Grade Architecture

```text
                             Client
                               |
                          API Gateway
                               |
                      Checkout / Order API
                               |
                         Order Orchestrator
           +-------------------+-------------------+
           |                   |                   |
           v                   v                   v
   Inventory Service      Payment Service     Fulfillment Service
           |                   |                   |
           v                   v                   v
     Inventory DB          Payment DB        Shipment/Fulfillment DB
           |                   |                   |
           +-------------------+-------------------+
                               |
                           Order DB
                               |
                             Outbox
                               |
                             Kafka
          +--------------------+-----------------------+
          |                    |                       |
          v                    v                       v
   Notifications         Read Models            Reconciliation
                                                   |
                                                   v
                                        Payment/Carrier Providers
```

---

## 75. Principal Engineer Failure Scenario

Assume a major shopping sale starts.

Within five minutes:

```text
30K checkout requests/sec
inventory DB latency rises
payment provider p99 jumps to 10 sec
Kafka consumer lag grows
shipping carrier API returns 50% errors
```

A weak system can collapse:

```text
checkout retries
-> duplicate orders
-> inventory reservation retries
-> payment retries
-> duplicate/unknown charges
-> Kafka backlog
-> duplicate shipment side effects
-> cascading outage
```

Principal-level response:

1. enforce checkout idempotency at ingress
2. use atomic inventory reservation
3. keep inventory reservations bounded with TTL
4. strict payment timeouts
5. ambiguous payment calls enter UNKNOWN state
6. never blindly retry money movement with a new operation ID
7. isolate shipping failure from order confirmation
8. use saga orchestration with durable step state
9. outbox for every critical state/event boundary
10. inbox/idempotent consumers for downstream effects
11. apply admission control before inventory/payment saturation
12. use bulkheads by dependency
13. degrade recommendations, analytics, and optional shipping estimates
14. scale Kafka consumers only if downstream systems can absorb load
15. reconcile order/payment/inventory/shipment continuously
16. prioritize user-visible order correctness over perfect downstream freshness

Core principle:

> Distributed order systems survive failure by making each transition explicit, retryable, idempotent, and compensatable. The workflow is durable; the network is not.

---

## 76. Interview Follow-Up Questions

Be prepared for:

- Why use Saga instead of one distributed transaction?
- Orchestration vs choreography?
- How do you prevent inventory oversell?
- Why reserve inventory before final sale?
- How do reservation TTLs work?
- What if payment succeeds but order confirmation fails?
- What if order confirms but shipment creation fails?
- How do you prevent duplicate orders?
- How do you prevent duplicate charges?
- What is outbox/inbox?
- How do you handle duplicate Kafka events?
- How do you model UNKNOWN external state?
- How does reconciliation work?
- How do partial shipments work?
- How do cancellation races work?
- Why is cart different from order?
- How do multi-region order writes work?
- Why not active-active mutation everywhere?
- What should degrade during overload?

---

## 77. Interview Summary

The transferable concepts in this design are:

```text
Distributed transactions
Saga
Orchestration
Choreography
Compensation
Inventory reservation
Atomic conditional writes
Payment authorization/capture
Idempotency
Exactly-once business effects
Transactional outbox
Inbox deduplication
Kafka partitioning
Event versioning
State machines
Partial fulfillment
Reconciliation
CQRS-style read models
Multi-region ownership
Fencing tokens
Backpressure
Bulkheads
Circuit breakers
Observability
```

A strong HLD answer should clearly separate the **commercial order**, **inventory reservation**, **payment**, and **fulfillment** domains. They cannot be made reliable by one giant distributed transaction; they become reliable through explicit workflow state, local atomicity, durable events, idempotency, and reconciliation.
