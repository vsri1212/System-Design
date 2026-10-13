# Payment System — Stripe-Style High-Level Design

A senior/principal-level HLD case study for designing a large-scale payment platform focused on **financial correctness, idempotency, ledgers, reconciliation, refunds, chargebacks, and failure recovery**.

The core lesson is simple:

> In payments, a system can be temporarily unavailable. It cannot casually lose, duplicate, or invent money.

---

## 1. Problem Statement

Design a payment platform that allows merchants to:

- create payment intents
- authorize cards/bank instruments
- capture funds
- refund payments
- handle asynchronous provider responses
- receive webhooks
- view payment status
- reconcile with external processors
- track balances through a ledger

The system must handle:

- retries
- duplicate requests
- network timeouts
- provider ambiguity
- partial failures
- asynchronous settlement
- chargebacks/disputes
- multi-region failure
- auditability

---

## 2. Functional Requirements

Core APIs:

```http
POST /payment-intents
POST /payment-intents/{id}/confirm
POST /payments/{id}/capture
POST /payments/{id}/refund
GET  /payments/{id}
POST /webhooks/provider
GET  /balances/{accountId}
```

Example payment intent:

```json
{
  "merchantId": "m123",
  "amount": 4999,
  "currency": "INR",
  "paymentMethodToken": "pm_abc",
  "idempotencyKey": "checkout-991"
}
```

Use integer minor units:

```text
4999 paise, not floating-point 49.99
```

---

## 3. Non-Functional Requirements

- no duplicate charges
- durable financial records
- auditable ledger
- idempotent APIs
- strong consistency for money movement
- high availability
- secure handling of payment data
- clear state machines
- asynchronous provider integration
- reconciliation capability
- deterministic failure recovery
- multi-region safety

---

## 4. Core Domain Model

```text
Merchant
Customer
PaymentIntent
Payment
Authorization
Capture
Refund
Dispute
LedgerAccount
LedgerEntry
Payout
ReconciliationRecord
```

---

## 5. High-Level Architecture

```text
Merchant / Client
      |
      v
  API Gateway
      |
      v
Payment Service
      |
      +--> Payment DB
      +--> Risk/Fraud Service
      +--> Provider Adapter
      |        |
      |        v
      |   Card Network / PSP / Bank
      |
      +--> Ledger Service
      |
      +--> Outbox -> Kafka
                       |
                       +--> Webhooks
                       +--> Reconciliation
                       +--> Analytics
                       +--> Notifications
```

---

## 6. Payment Intent

A payment intent represents the merchant's intention to collect a specific amount.

Example states:

```text
REQUIRES_PAYMENT_METHOD
REQUIRES_CONFIRMATION
PROCESSING
REQUIRES_ACTION
AUTHORIZED
CAPTURED
FAILED
CANCELLED
```

The intent survives retries and client disconnects.

---

## 7. Why Use a Payment Intent?

Bad design:

```text
POST /charge-card
```

with no durable workflow object.

If the client times out, nobody knows whether the processor charged the card.

Better:

```text
PaymentIntent pi_123
```

The client can query the durable state later.

---

## 8. Idempotency Keys

A network timeout may cause the merchant to retry:

```text
POST /payment-intents
```

Without idempotency:

```text
charge twice
```

Store:

```text
(merchantId, idempotencyKey) -> response/paymentIntentId
```

Repeated request returns the same logical result.

---

## 9. Idempotency Scope

Keys should be scoped correctly.

For example:

```text
merchantId + endpoint + idempotencyKey
```

Do not globally deduplicate unrelated merchants that happen to choose the same key.

---

## 10. Exactly-Once Delivery vs Exactly-Once Effect

Networks and queues may deliver messages more than once.

Achieving exactly-once delivery end-to-end is difficult.

The practical goal is:

```text
at-least-once delivery
+
idempotent processing
=
exactly-once financial effect
```

---

## 11. Payment State Machine

Example:

```text
CREATED
   |
   v
AUTHORIZING
   |
   +--> AUTH_FAILED
   |
   v
AUTHORIZED
   |
   +--> CANCELLED / VOIDED
   |
   v
CAPTURING
   |
   +--> CAPTURE_FAILED
   |
   v
CAPTURED
```

Refunds are separate objects rather than simply changing CAPTURED backward.

---

## 12. Authorization vs Capture

Authorization:

```text
reserve funds / validate payment instrument
```

Capture:

```text
actually request transfer/settlement
```

For hotel/ticket/e-commerce flows this separation is extremely useful.

---

## 13. Payment Provider Adapter

Do not couple core payment logic to one processor.

```text
Payment Service
      |
      v
Provider Abstraction
  +---+---+
  |       |
 PSP A   PSP B
```

Adapter normalizes:

- authorize
- capture
- refund
- void
- status lookup
- webhook events

---

## 14. Provider Timeout Ambiguity

Suppose:

```text
Payment Service -> Provider: capture 1000
Provider captures successfully
Response times out
```

The internal system does not know whether capture succeeded.

Never blindly retry without idempotency/provider lookup.

---

## 15. Unknown State

Model ambiguity explicitly:

```text
CAPTURE_PENDING
UNKNOWN
RECONCILIATION_REQUIRED
```

Then query provider or wait for webhook/reconciliation.

A payment system should never turn uncertainty into a guessed success/failure.

---

## 16. Provider-Side Idempotency

If the provider supports idempotency keys, send a stable external operation ID:

```text
capture:payment123
```

Repeated retries should map to the same processor action.

---

## 17. Internal Operation ID

Each money-moving operation gets a unique durable ID:

```text
payment_operation_id
```

Example:

```text
op_9001 = AUTHORIZE payment p123
op_9002 = CAPTURE payment p123
```

This improves tracing, deduplication, and reconciliation.

---

## 18. Why a Ledger?

A payment status table alone is not enough.

Money movement requires an auditable accounting history.

Use an append-only ledger.

---

## 19. Double-Entry Accounting

Every financial transaction creates balanced debit/credit entries.

Example customer payment of 100:

```text
Debit  Processor Receivable  +100
Credit Merchant Payable      +100
```

Or, depending on account conventions:

```text
sum(debits) = sum(credits)
```

The important invariant is that each journal balances.

---

## 20. Ledger Journal

Example:

```text
journal_id = j100
operation_id = op9002
currency = INR
```

Entries:

```text
account A   debit  10000
account B   credit 10000
```

The journal is immutable after posting.

Corrections use compensating entries, not destructive updates.

---

## 21. Append-Only Ledger

Do not do:

```text
UPDATE balance = balance + 100
```

as the only source of truth.

Instead:

```text
append financial entries
```

Balances may be materialized/cached from the ledger.

---

## 22. Ledger Invariants

Examples:

```text
sum(debits) == sum(credits)
entries never mutate after posting
operationId is unique
currency is never mixed inside one balanced journal without explicit FX entries
```

These invariants should be enforced transactionally.

---

## 23. Balance Model

Possible balances:

```text
pending
available
reserved
```

Do not collapse all money into one number.

Example:

```text
Captured payment -> pending merchant balance
Settlement clears -> available balance
Payout starts -> reserved/removed from available
```

---

## 24. Ledger Transaction Boundary

Posting all debit/credit entries for one journal must be atomic.

If only one side commits:

```text
money is created/destroyed
```

Use a database transaction or equivalent strongly atomic storage boundary.

---

## 25. Ledger and Payment State

Payment state and ledger posting should not drift.

Possible design:

```text
single database transaction
or
well-defined workflow + outbox + reconciliation
```

Avoid uncontrolled dual writes.

---

## 26. Transactional Outbox

Suppose payment DB commits:

```text
CAPTURED
```

then crashes before publishing event.

Use:

```text
DB transaction:
  update payment state
  insert outbox event
```

Publisher later sends to Kafka.

---

## 27. Inbox / Consumer Deduplication

Consumers may receive the same Kafka event multiple times.

Store:

```text
consumerName + eventId
```

If already processed:

```text
skip
```

This is the inbox pattern.

---

## 28. Refund Model

A refund is its own durable object:

```text
refund_id
payment_id
amount
state
provider_ref
idempotency_key
```

States:

```text
CREATED
PROCESSING
SUCCEEDED
FAILED
UNKNOWN
```

---

## 29. Partial Refunds

A payment of 1000 may be refunded:

```text
300
then 200
```

Invariant:

```text
total successful refunds <= captured amount
```

Enforce atomically.

---

## 30. Refund Ledger

Refund posts reversing/compensating ledger entries.

Do not delete the original payment journal.

Audit history remains intact.

---

## 31. Chargebacks / Disputes

A dispute may occur days or weeks later.

Possible flow:

```text
CAPTURED
   |
   v
DISPUTE_OPENED
   |
   +--> WON
   |
   +--> LOST
```

Ledger may reserve or remove merchant funds according to policy.

---

## 32. Settlement

Card capture does not mean money instantly reaches the merchant's bank.

There may be:

```text
processor settlement
platform balance
merchant payout
```

Model these as separate financial events.

---

## 33. Payouts

Payout state machine:

```text
CREATED
   |
   v
SUBMITTED
   |
   v
PROCESSING
   |
   +--> FAILED
   |
   v
PAID
```

Payouts require idempotency and reconciliation just like payments.

---

## 34. Reconciliation

Internal records can drift from external processors due to:

- timeout ambiguity
- missing webhook
- provider bug
- manual adjustment
- settlement delay

Reconciliation compares:

```text
internal operations
vs
provider reports/statements
```

---

## 35. Reconciliation Workflow

```text
Provider Settlement File/API
          |
          v
Reconciliation Service
          |
          +--> match by provider transaction ID
          +--> detect missing/mismatched records
          |
          v
Exception Queue
```

Never assume webhook delivery is perfect.

---

## 36. Reconciliation Types

Examples:

```text
Payment exists internally but not at provider
Provider payment exists but not internally
Amount mismatch
Currency mismatch
Duplicate provider transaction
Refund mismatch
Settlement mismatch
```

---

## 37. Webhooks

External processors send asynchronous events:

```text
payment_succeeded
payment_failed
chargeback_opened
refund_succeeded
```

Webhook handling must be:

- authenticated
- idempotent
- replay-safe
- order-tolerant

---

## 38. Webhook Signature Verification

Verify provider signature before processing.

Also consider:

- timestamp tolerance
- replay prevention
- secret rotation

Never trust raw public webhook bodies without verification.

---

## 39. Out-of-Order Webhooks

Possible arrival:

```text
CAPTURE_SUCCEEDED
then AUTHORIZED
```

Do not blindly overwrite state with older events.

Use provider event version/timestamp/state-machine validation.

---

## 40. Duplicate Webhooks

Provider may send the same event multiple times.

Store:

```text
provider_event_id
```

Process once.

---

## 41. Risk / Fraud Service

Risk signals may include:

- velocity
- device fingerprint
- location mismatch
- card/account history
- merchant risk

Possible result:

```text
ALLOW
REVIEW
BLOCK
```

Keep risk decision latency bounded.

---

## 42. Graceful Risk Degradation

Do not silently fail-open for every high-risk payment if risk service is unavailable.

Policy may depend on:

- amount
- merchant risk
- payment type

Examples:

```text
low-risk small amount -> degrade cautiously
high-value payment -> fail closed / require review
```

---

## 43. Currency Handling

Never use floating-point arithmetic for money.

Use:

```text
amountMinor = 1050
currency = INR
```

or exact decimal types with currency-aware rules.

---

## 44. FX

Foreign exchange should be modeled as explicit transactions.

Example:

```text
USD account
FX clearing account
INR account
```

Store applied exchange rate and source/version for audit.

---

## 45. Rounding

Rounding policy must be deterministic.

Record:

```text
original amount
rate
rounding mode
rounded amount
```

Never rely on language-default floating behavior.

---

## 46. Database Choice

Core payment/ledger data generally benefits from strongly consistent transactional storage.

Possible choices:

- relational database
- distributed SQL
- carefully partitioned transactional store

Do not choose eventual-consistency storage for the core ledger just because it scales horizontally.

---

## 47. Sharding

Possible keys:

```text
merchantId
accountId
paymentId
```

Ledger locality often benefits from grouping entries by account/merchant domain.

Cross-shard journal posting is much harder.

---

## 48. Avoid Cross-Shard Financial Transactions

Where possible, choose account/shard ownership so one financial journal posts locally.

If cross-shard movement is required, use a rigorously designed transfer protocol rather than informal dual writes.

---

## 49. Internal Transfer State Machine

Example transfer between accounts:

```text
INITIATED
   |
   v
DEBIT_RESERVED
   |
   v
CREDIT_POSTED
   |
   v
COMPLETED
```

Compensation/reconciliation handles incomplete states.

---

## 50. Why Two-Phase Commit Is Often Avoided Across Services

2PC can create:

- blocking
- coordinator dependency
- availability issues
- operational complexity

Payment systems often prefer:

```text
local ACID transactions
+
durable workflow state
+
idempotent operations
+
reconciliation
```

---

## 51. Saga in Payments

Example e-commerce payment saga:

```text
Authorize
  |
Reserve inventory
  |
Capture
  |
Confirm order
```

Compensations:

```text
void authorization
refund capture
release inventory
```

Financial compensation is not the same as rollback; it creates new auditable events.

---

## 52. Retry Policy

Retries are appropriate for transient failures.

Use:

- exponential backoff
- jitter
- bounded attempts
- stable idempotency keys

Never create a new payment operation ID on every network retry.

---

## 53. Retry Ambiguity

If a provider call times out after request transmission:

Do not assume failure.

First:

```text
query provider by operation/idempotency key
```

or wait for webhook/reconciliation.

---

## 54. Circuit Breakers

A failing PSP can saturate connection/thread pools.

Use:

- strict timeouts
- circuit breaker
- bulkheads
- provider routing/failover when safe

---

## 55. Provider Failover Caveat

Failing over a payment to PSP B after an ambiguous timeout at PSP A can double-charge.

Only fail over when ownership/status is known or cross-provider idempotency strategy makes it safe.

This is a classic Principal-level trap.

---

## 56. Provider Routing

Possible routing criteria:

- geography
- card network
- success rate
- cost
- merchant preference
- provider health

But correctness comes before lowest cost or latency.

---

## 57. Payment Tokenization

Do not store raw card details in normal application databases.

Use tokenization/provider vaults.

Core platform should work with:

```text
paymentMethodToken
```

rather than PAN/CVV.

---

## 58. Security

Key concerns:

- TLS everywhere
- tokenization
- encryption at rest
- least privilege
- secrets management
- audit logs
- signed webhooks
- fraud controls
- replay prevention
- secure admin operations

PCI scope should be minimized where possible.

---

## 59. Audit Log

Capture administrative and financial state changes:

```text
who
what
when
before
after
requestId
operationId
```

Audit logs should be tamper-resistant and separately retained.

---

## 60. Multi-Region Reads

Read-only merchant dashboards can be served from replicated regional stores/caches.

Stale analytical views are often acceptable.

Core payment writes are different.

---

## 61. Multi-Region Payment Writes

Possible strategy:

```text
merchant/account/payment -> home write region
```

All mutations route to the owner region.

This simplifies:

- idempotency
- ordering
- ledger correctness
- duplicate prevention

---

## 62. Why Not Active-Active Money Writes Everywhere?

During a partition:

```text
Region A spends balance 100
Region B also spends balance 100
```

You can create a double-spend.

For financial state, strong ownership/consensus is usually preferable to unconstrained active-active conflict resolution.

---

## 63. Region Failover

Failover must ensure the old writer cannot continue accepting transactions.

Use:

- leader epochs
- fencing tokens
- consensus-backed ownership

Stale primary writes must be rejected.

---

## 64. RPO/RTO Trade-Off

For payment ledgers, RPO should be extremely low.

If synchronous replication is required for committed financial writes, latency may increase.

This is a deliberate correctness trade-off.

---

## 65. Ledger Replication

Replicas should never independently invent conflicting journal order.

Use a strong replication protocol appropriate for the database.

A follower may serve some reads, but writes route through authoritative consensus/leader ownership.

---

## 66. Event Ordering

Events should carry:

```text
paymentId
operationId
version
```

Consumers reject stale transitions.

Kafka ordering by paymentId can help, but state-machine validation is still required.

---

## 67. Materialized Balances

Computing an account balance by summing billions of ledger rows on every request is expensive.

Maintain a transactional/materialized balance:

```text
accountId
available
pending
version
```

The ledger remains the audit source of truth.

---

## 68. Balance Rebuild

You should be able to recompute materialized balances from ledger entries.

If rebuilt balance differs from cached/materialized balance:

```text
raise reconciliation alert
```

---

## 69. Negative Balance Policy

Do not assume balances can never be negative.

Chargebacks/refunds may create negative merchant balances.

Policy must be explicit:

- allow negative
- reserve future incoming funds
- debit bank account
- suspend payouts

---

## 70. Payout Scheduling

Payout system may batch merchant funds periodically.

Example:

```text
available balance -> payout reservation -> bank transfer
```

The reservation must atomically prevent the same balance from funding two payouts.

---

## 71. Reconciliation as a First-Class System

Do not treat reconciliation as an emergency script.

Build it as a permanent subsystem with:

- scheduled ingestion
- matching rules
- exception queues
- automated repairs where safe
- manual operations tooling

---

## 72. Manual Operations

Operators need controlled tools to:

- replay webhook
- retry reconciliation
- issue refund
- inspect ledger
- mark exceptions resolved

Every manual action must be audited and idempotent where possible.

---

## 73. Observability

Key metrics:

```text
payment_authorization_success_rate
capture_success_rate
provider_timeout_rate
payment_unknown_state_count
idempotency_replay_rate
ledger_post_failure_rate
ledger_imbalance_count
webhook_lag
reconciliation_mismatch_count
refund_failure_rate
chargeback_rate
payout_failure_rate
```

Financial correctness alerts are more important than average latency alone.

---

## 74. Traceability

Every request should carry:

```text
requestId
paymentId
operationId
providerTransactionId
journalId
```

This lets support/engineering trace a payment end-to-end.

---

## 75. Capacity Estimation

Example assumptions:

```text
100M payment attempts/day
```

Average:

```text
~1,157 attempts/sec
```

At 20x peak:

```text
~23K attempts/sec
```

Each payment may generate multiple operations/events/ledger entries, so internal write amplification is much higher than API QPS.

The important conclusion is:

> Payment scale is manageable only if correctness boundaries remain explicit and asynchronous noncritical work stays off the synchronous money path.

---

## 76. Production-Grade Architecture

```text
                              Clients / Merchants
                                      |
                                  API Gateway
                                      |
                               Payment Service
                     +----------------+----------------+
                     |                |                |
                     v                v                v
                Payment DB        Risk Service    Provider Router
                     |                                  |
                     |                           +------+------+
                     |                           |             |
                     |                          PSP A         PSP B
                     |
                     v
                Ledger Service
                     |
                     v
                Ledger Database
                     |
                     +--> Materialized Balances

Payment DB transaction -> Outbox -> Kafka
                                  |
               +------------------+-------------------+
               |                  |                   |
               v                  v                   v
          Webhook Service   Reconciliation       Notifications
                                  |
                                  v
                         Provider Reports/APIs
```

---

## 77. Principal Engineer Failure Scenario

Assume a major shopping event creates:

```text
50K payment attempts/sec
```

At the same time:

```text
PSP A p99 jumps from 500 ms to 18 sec
15% of requests time out after reaching provider
webhook delivery is delayed by 20 minutes
merchant clients aggressively retry
```

A weak system may do:

```text
provider timeout
-> client retry
-> new charge
-> provider receives duplicate captures
-> webhook delay hides results
-> fallback to PSP B
-> double charges
-> ledger/payment state diverges
```

Principal-level response:

1. stable merchant idempotency keys
2. stable internal operation IDs
3. stable provider idempotency keys
4. strict PSP timeouts and bulkheads
5. ambiguous timeouts enter UNKNOWN/PENDING state
6. query PSP A before attempting another processor
7. do not fail over ambiguous captures blindly
8. circuit-break PSP A for new clearly unsubmitted traffic only
9. keep ledger posting atomic
10. use outbox/inbox for internal events
11. throttle aggressive client retries with Retry-After/backoff guidance
12. accept eventual webhook delay; reconciliation/status polling repairs truth
13. prioritize money operations over analytics/notifications
14. continuously reconcile provider reports
15. alert on rising UNKNOWN states and mismatch counts
16. never convert uncertainty into guessed financial state

Core principle:

> A payment timeout is not the same as a payment failure. When money movement is uncertain, preserve ambiguity, reconcile it, and never retry in a way that can create a second financial effect.

---

## 78. Interview Follow-Up Questions

Be prepared for:

- Why use PaymentIntent?
- How do idempotency keys prevent duplicate charges?
- Exactly-once delivery vs exactly-once effect?
- What happens when provider times out after charging?
- How do you model UNKNOWN state?
- Why use double-entry ledger?
- Why not just maintain a balance column?
- How do you ensure debit and credit both commit?
- What are pending vs available balances?
- How do refunds work?
- How do partial refunds work?
- How do chargebacks affect ledger?
- What is reconciliation?
- Why are webhooks insufficient as source of truth?
- How do you handle duplicate/out-of-order webhooks?
- How do outbox/inbox patterns help?
- Why is blind PSP failover dangerous?
- How do you support multi-region payments safely?
- Why not active-active balance writes?
- How do fencing tokens help during region failover?

---

## 79. Interview Summary

The transferable concepts in this design are:

```text
Financial state machines
Payment intents
Idempotency
Exactly-once effect
Authorization / capture
Ambiguous outcomes
Provider abstraction
Double-entry accounting
Append-only ledger
Materialized balances
Refunds
Chargebacks
Settlement
Payouts
Reconciliation
Webhooks
Outbox / Inbox
Saga / compensation
Strong transactional boundaries
Sharding locality
Multi-region ownership
Fencing tokens
Fraud controls
Auditability
Observability
```

A strong HLD answer should make this distinction explicit: ordinary distributed systems try to prevent duplicate work; payment systems must prevent duplicate **financial effects**. The ledger, idempotency model, state machines, and reconciliation system are what make that possible even when networks, processors, clients, and regions fail.
