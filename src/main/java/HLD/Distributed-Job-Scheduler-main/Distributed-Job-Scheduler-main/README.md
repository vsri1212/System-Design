# Distributed Job Scheduler — High-Level Design

A senior/principal-level HLD case study for designing a distributed job scheduling platform that supports cron jobs, one-time jobs, delayed execution, retries, leases, worker coordination, leader election, and failure recovery.

The core idea is:

> Scheduling is not just deciding *when* a job should run. A production scheduler must decide **who owns execution, how duplicate execution is controlled, how stuck work is recovered, and what correctness guarantee exists when machines and networks fail**.

---

## 1. Problem Statement

Design a system where users/services can submit:

- one-time jobs
- delayed jobs
- recurring cron jobs
- high-priority jobs
- long-running jobs
- retryable jobs

The system should:

- trigger jobs near their scheduled time
- assign jobs to workers
- prevent uncontrolled duplicate execution
- recover from worker crashes
- retry transient failures
- isolate poison jobs
- scale to millions/billions of scheduled tasks
- operate across regions

---

## 2. Functional Requirements

Core APIs:

```http
POST /jobs
GET  /jobs/{jobId}
POST /jobs/{jobId}/pause
POST /jobs/{jobId}/resume
POST /jobs/{jobId}/cancel
POST /jobs/{jobId}/retry
GET  /jobs/{jobId}/runs
```

Example job definition:

```json
{
  "jobId": "job-123",
  "scheduleType": "CRON",
  "cron": "0 */5 * * * *",
  "priority": "HIGH",
  "payloadRef": "s3://bucket/input.json",
  "maxRetries": 5,
  "timeoutSeconds": 600,
  "idempotencyKey": "billing-run-2026-08-12"
}
```

---

## 3. Non-Functional Requirements

- high availability
- horizontally scalable scheduling
- bounded execution delay
- durable job definitions
- durable run history
- controlled duplicate execution
- automatic failover
- retry/backoff support
- backpressure handling
- multi-tenant isolation
- strong observability

---

## 4. Core Entities

```text
JobDefinition
JobSchedule
JobRun
Worker
Lease
RetryPolicy
Shard
```

Possible job states:

```text
ACTIVE
PAUSED
CANCELLED
COMPLETED
```

Run states:

```text
SCHEDULED
CLAIMED
RUNNING
SUCCEEDED
FAILED
RETRY_WAIT
TIMED_OUT
CANCELLED
```

---

## 5. High-Level Architecture

```text
Clients
   |
   v
API Gateway
   |
   v
Job Service
   |
   v
Job Metadata Store
   |
   v
Scheduler Fleet
   |
   v
Due-Job Queue / Dispatch Bus
   |
   v
Worker Fleet
   |
   v
Result / Run Store
```

Coordination plane:

```text
Scheduler Nodes
    |
    v
Leader Election / Shard Ownership
    |
    v
Lease Store / Consensus System
```

---

## 6. Scheduling vs Execution

Separate responsibilities:

```text
Scheduler -> determines due work
Worker    -> executes work
```

This makes both layers easier to scale independently.

---

## 7. One-Time Job

Example:

```text
run at 2026-08-12 21:00:00
```

The scheduler persists the desired execution time and later makes the job eligible for dispatch.

---

## 8. Delayed Job

Example:

```text
run 30 minutes from now
```

Store as absolute execution time internally.

This avoids ambiguity when scheduler restarts.

---

## 9. Recurring / Cron Job

A recurring job creates multiple run instances.

Important distinction:

```text
JobDefinition != JobRun
```

One definition may produce thousands of runs.

---

## 10. Why Store Run Instances?

You need auditability:

```text
scheduledAt
startedAt
completedAt
attempt
workerId
status
error
```

Without durable run records, debugging retries and missed executions becomes difficult.

---

## 11. Scheduler Polling

Simple design:

```text
SELECT jobs
WHERE next_run_at <= now()
LIMIT N
```

This works at small scale but can overload one database at very large scale.

---

## 12. Time-Bucket Partitioning

Partition scheduled jobs by time bucket.

Example:

```text
2026-08-12T18:00 bucket
2026-08-12T18:01 bucket
```

Schedulers scan only near-future buckets.

This reduces full-table scans.

---

## 13. Timing Wheel

A timing wheel can efficiently manage huge numbers of timers.

Concept:

```text
second slots
minute slots
hour slots
```

Jobs move toward finer-grained buckets as execution time approaches.

Useful for large delayed-task systems.

---

## 14. Database + Queue Hybrid

Durable source:

```text
Job Store
```

Fast dispatch path:

```text
Queue
```

The database remembers what should happen; the queue carries work ready to execute.

---

## 15. Why Queue Alone Is Not Enough

If a delayed message expires from queue metadata or queue state is lost/misconfigured, durable schedule may disappear.

Keep authoritative job definition/run state separately.

---

## 16. Scheduler Leader Election

If multiple scheduler nodes scan the exact same shard, they can duplicate dispatch.

One approach:

```text
one leader per scheduling shard
```

Use a consensus-backed coordinator such as an etcd/ZooKeeper-style system.

---

## 17. Global Leader vs Shard Leaders

Single global leader:

- simple
- bottleneck
- large failover blast radius

Shard leaders:

- horizontally scalable
- smaller failure domains
- more coordination complexity

At large scale, shard ownership is usually better.

---

## 18. Sharding

Possible shard key:

```text
hash(jobId) % N
```

or

```text
time bucket + hash
```

Each scheduler owns one or more shards.

---

## 19. Shard Ownership Lease

Scheduler A owns shard 7 with lease:

```text
owner=A
leaseUntil=T
leaseEpoch=81
```

It periodically renews.

If renewal stops, another scheduler may take ownership after expiry.

---

## 20. Why Lease Instead of Permanent Lock?

If owner crashes, a permanent lock can leave the shard stuck forever.

Lease automatically expires.

---

## 21. Fencing Token

Lease expiry alone is not enough.

Old scheduler may pause due to GC/network issue, then wake up after new owner takes over.

Use monotonically increasing epoch/fencing token:

```text
Scheduler A epoch 81
Scheduler B epoch 82
```

Downstream state changes reject stale epoch 81.

---

## 22. Split-Brain Scenario

Without fencing:

```text
A thinks it owns shard
B also thinks it owns shard
both dispatch same jobs
```

Leases + fencing reduce this risk.

---

## 23. Claiming a Due Job

Even with shard ownership, job claim should be atomic.

Example:

```sql
UPDATE job_run
SET status='CLAIMED', worker_token=?
WHERE run_id=?
  AND status='SCHEDULED';
```

Affected row 1 -> success.

---

## 24. Exactly-Once Execution Is Hard

Consider:

```text
worker performs side effect
worker crashes before marking success
scheduler retries
```

The job may run twice.

Therefore true exactly-once execution across arbitrary external systems is generally impossible without cooperation from those systems.

---

## 25. Practical Guarantee

Use:

```text
at-least-once execution
+
idempotent jobs
+
execution/run IDs
```

This gives exactly-once business effect where possible.

---

## 26. Job Idempotency

Example billing job:

```text
idempotency key = customerId + billingPeriod
```

If run twice, second execution returns existing result instead of charging again.

---

## 27. Execution Token

Each run attempt gets a unique token:

```text
runId
attempt
fencingToken
```

External systems can reject stale/duplicate operations where supported.

---

## 28. Worker Pull Model

Workers request work:

```text
worker -> queue -> claim job
```

Advantages:

- natural backpressure
- workers control concurrency
- easier autoscaling

---

## 29. Worker Push Model

Scheduler directly pushes jobs to workers.

Advantages:

- low dispatch latency

Disadvantages:

- scheduler must track worker capacity
- harder failure handling

Pull model is often simpler for large distributed systems.

---

## 30. Worker Registration

Worker advertises:

```text
workerId
capabilities
maxConcurrency
region
heartbeat
```

Useful when jobs need specific runtimes/resources.

---

## 31. Capability Routing

Example jobs:

```text
GPU
JAVA
PYTHON
HIGH_MEMORY
```

Route to compatible worker pools.

---

## 32. Worker Heartbeat

Long-running job worker periodically updates:

```text
lastHeartbeat
leaseUntil
progress
```

If heartbeat stops, scheduler assumes worker may be dead.

---

## 33. Job Execution Lease

Worker claims run for bounded time:

```text
leaseUntil = now + 60 sec
```

Worker renews while running.

If worker crashes, lease expires and another worker may retry.

---

## 34. Stale Worker Problem

Worker A freezes.

Lease expires.

Worker B retries same run.

Worker A wakes up and continues.

Use fencing token or downstream idempotency so A cannot overwrite B's newer ownership.

---

## 35. Job Timeout

Each job should have a maximum execution time.

Example:

```text
timeout = 10 min
```

After timeout:

```text
RUNNING -> TIMED_OUT
```

Retry/compensation depends on policy.

---

## 36. Hard Kill vs Cooperative Cancellation

Not every workload can be safely terminated.

Prefer cooperative cancellation when possible:

```text
cancelRequested=true
```

Worker checks and stops safely.

---

## 37. Retry Policy

Possible policy:

```text
maxAttempts=5
backoff=exponential
jitter=true
```

Example:

```text
10s -> 30s -> 2m -> 10m
```

---

## 38. Retry Classification

Transient:

- network timeout
- downstream 503
- temporary DB issue

Permanent:

- invalid payload
- missing required config
- unauthorized request

Permanent failures should not be retried endlessly.

---

## 39. DLQ

After retries are exhausted:

```text
FAILED -> DLQ / manual exception queue
```

DLQ should contain:

- job ID
- run ID
- payload reference
- error history
- timestamps
- attempts

---

## 40. Poison Job Isolation

One always-failing job should not block a whole shard/queue.

Use bounded retries and move it aside.

---

## 41. Priority Queues

Example priorities:

```text
P0 critical
P1 normal
P2 batch
P3 maintenance
```

High-priority work should not wait behind millions of low-value batch jobs.

---

## 42. Priority Starvation

If P0 traffic never stops, P3 may never run.

Use:

- weighted scheduling
- reserved capacity
- aging

---

## 43. Tenant Isolation

One tenant can submit millions of jobs.

Protect platform with:

- per-tenant quotas
- concurrency limits
- queue partitions
- rate limits
- fair scheduling

---

## 44. Backpressure

If incoming due jobs:

```text
100K/sec
```

worker capacity:

```text
60K/sec
```

backlog grows by 40K/sec.

Scheduler must expose lag and protect itself from unbounded growth.

---

## 45. Queue Lag

Measure:

```text
now - scheduledAt
```

for oldest unstarted job.

This is often more useful than raw queue depth.

---

## 46. Autoscaling Workers

Scale based on:

- queue depth
- scheduling lag
- execution latency
- CPU/memory
- per-capability backlog

Do not scale purely on queue count if downstream dependency capacity is fixed.

---

## 47. Downstream Saturation

Adding more workers can make things worse if all jobs hit the same overloaded database/API.

Use dependency-aware concurrency limits.

---

## 48. Bulkheads

Separate worker pools by:

```text
job type
tenant
priority
dependency
```

A broken report-generation service should not consume all billing workers.

---

## 49. Circuit Breakers

If downstream dependency is failing:

```text
stop hammering it
```

Jobs can enter retry wait rather than consuming worker threads repeatedly.

---

## 50. Cron Semantics

Cron scheduling must define:

- timezone
- DST behavior
- missed-run behavior
- overlap policy

These are product semantics, not implementation details.

---

## 51. Timezone

Store schedule timezone explicitly.

Example:

```text
Asia/Kolkata
```

Do not assume server local timezone.

---

## 52. Daylight Saving Time

In DST regions, local times may repeat or disappear.

Policy must define:

- skip missing hour?
- run twice in repeated hour?

Convert schedule carefully using timezone rules.

---

## 53. Clock Skew

Distributed machines do not have perfectly identical clocks.

If scheduler relies on local wall clock, skew can trigger jobs early/late.

Use synchronized clocks and design with bounded tolerance.

---

## 54. Monotonic vs Wall Clock

Wall clock:

- needed for calendar schedules

Monotonic clock:

- better for measuring lease durations/timeouts locally

Do not use wall-clock jumps to measure elapsed execution time when monotonic time is available.

---

## 55. Missed Runs

Scheduler may be down for 20 minutes.

A 5-minute cron missed four runs.

Possible policies:

- run all missed instances
- run only latest
- skip missed runs

Store policy per job.

---

## 56. Catch-Up Storm

After outage, millions of missed jobs may become due simultaneously.

Do not dispatch all at once.

Use controlled catch-up rate and priority.

---

## 57. Overlapping Runs

Cron fires every minute, but one execution takes 5 minutes.

Policies:

```text
ALLOW overlap
SKIP if previous running
QUEUE next
REPLACE previous
```

Make this explicit.

---

## 58. Singleton Job

Some jobs must have only one active execution globally.

Use lease/atomic claim keyed by logical job identity.

Example:

```text
monthly-ledger-close
```

---

## 59. Per-Key Concurrency

Maybe jobs for same customer must serialize, but different customers can run in parallel.

Use:

```text
concurrencyKey = customerId
```

and enforce one active lease per key.

---

## 60. Workflow / DAG Extension

A scheduler can evolve into workflow orchestration.

Example:

```text
A -> B -> C
 \-> D
```

But DAG execution introduces dependencies, retries, and partial state beyond basic scheduling.

Keep it conceptually separate unless required.

---

## 61. Event-Triggered Jobs

Not every job is time-triggered.

Example:

```text
file uploaded -> process file
```

This is closer to event processing than scheduling.

A unified platform may support both.

---

## 62. Job Payload Storage

Do not put massive payloads directly into scheduling tables/queues.

Store:

```text
payloadRef -> object storage/database
```

Queue carries lightweight metadata.

---

## 63. Transactional Outbox for Job Creation

Suppose business transaction commits but scheduler event is lost.

Example:

```text
invoice created
but email job never scheduled
```

Use outbox in source service when job scheduling must be atomic with business state.

---

## 64. Scheduler Idempotency

Repeated job-create request should not create duplicates when caller retries.

Store:

```text
owner + idempotencyKey -> jobId
```

---

## 65. Run Generation Idempotency

Cron scheduler may rescan same time bucket after failover.

Use unique constraint:

```text
(jobId, scheduledFireTime)
```

Only one logical run instance is created.

---

## 66. Dispatch Idempotency

Queue publish can be retried.

Worker must dedupe by:

```text
runId
```

or atomically claim run state.

---

## 67. Scheduler Failover

Scheduler A owns shard 2 and dies.

After lease expiry:

```text
Scheduler B acquires shard 2 with higher epoch
```

B rescans due jobs and safely recreates missing dispatches idempotently.

---

## 68. Why Rescan Is Important

Queues/events can fail.

The scheduler should be able to reconstruct pending dispatch from durable job/run state.

This makes the database/run store the recovery source of truth.

---

## 69. Queue Failure

If dispatch queue is unavailable:

- keep job run durable as SCHEDULED
- retry publishing later

Do not mark job executed merely because dispatch was attempted.

---

## 70. Worker Failure

Worker crashes after claim.

Execution lease expires.

Run becomes retryable.

Potential duplicate side effect is handled through job idempotency/fencing.

---

## 71. Scheduler Database Failure

If scheduling metadata store is unavailable:

- stop creating/claiming new authoritative runs
- preserve workers already executing where safe
- avoid inventing schedule state from cache

Correctness may require temporary unavailability.

---

## 72. Cache Role

Caches may accelerate:

- job metadata reads
- worker capability lookup
- tenant quotas

Do not make cache the sole authority for leases/run state.

---

## 73. Shard Rebalancing

When scheduler nodes are added/removed:

```text
move shard ownership gradually
```

Avoid reassigning every shard simultaneously.

Use consistent hashing/rendezvous hashing or coordinator assignments.

---

## 74. Consistent Hashing

Maps shard/job groups to scheduler nodes with limited movement when membership changes.

Still use lease ownership before acting.

Hash assignment alone is not enough for correctness.

---

## 75. Multi-Region Architecture

```text
Global API
   |
   +--> Region A Scheduler
   +--> Region B Scheduler
```

Possible model:

```text
job/tenant -> home region
```

All schedule mutations and execution ownership for that job remain in one region.

---

## 76. Why Not Active-Active Scheduling of Same Job Everywhere?

During partition:

```text
Region A fires job
Region B fires same job
```

Duplicate external effects may occur.

Use single-region ownership or globally coordinated lease/fencing when strict singleton semantics matter.

---

## 77. Regional Jobs

Some jobs should execute near data.

Example:

```text
EU data-processing job -> EU workers
```

Schedule metadata may be global, but execution region is explicit.

---

## 78. Region Failure

If home region fails:

- promote secondary ownership using higher epoch
- restore durable schedule/run state
- throttle catch-up
- avoid executing stale jobs from old primary

---

## 79. RPO / RTO

If schedule metadata replicates asynchronously, latest job creation may be lost during regional disaster.

Define:

```text
RPO
RTO
```

Critical workloads may justify synchronous replication.

---

## 80. At-Least-Once vs At-Most-Once Jobs

At-least-once:

- retry after uncertainty
- duplicates possible

At-most-once:

- never retry uncertain execution
- possible lost work

Choose per job type.

---

## 81. Example: Email Job

At-least-once with deduplication is usually acceptable.

Duplicate visible emails should be suppressed using message/event idempotency.

---

## 82. Example: Financial Transfer Job

Blind retry is dangerous.

Use durable operation ID and downstream financial idempotency/reconciliation.

---

## 83. Example: Cache Warmup

Duplicates are harmless.

No heavy exactly-once machinery is needed.

Correctness guarantees should match business impact.

---

## 84. Execution Result Storage

Store:

```text
runId
attempt
status
startedAt
finishedAt
workerId
resultRef
errorCode
```

Large logs/results belong in external storage with references.

---

## 85. Job Logs

Workers stream logs asynchronously to logging platform.

Do not block job execution on logging availability.

---

## 86. Progress Tracking

Long jobs may report:

```text
30%
45%
80%
```

Progress is informational; final job state remains authoritative.

---

## 87. Cancellation Race

User cancels while worker completes.

Need explicit transition rules.

Example:

```text
RUNNING -> SUCCEEDED
RUNNING -> CANCEL_REQUESTED
```

Only valid atomic transition wins according to policy.

---

## 88. Retry vs Cancel Race

A retry timer may fire exactly when user cancels job.

Run creation checks latest job status/version before dispatch.

---

## 89. Priority Inversion

Low-priority long job may occupy scarce worker needed by urgent job.

Possible solutions:

- separate pools
- preemption for safe workloads
- reserved capacity

---

## 90. Preemption

Preemption only works if job is safely resumable/cancellable.

Otherwise killing work may waste significant progress or corrupt external state.

---

## 91. Checkpointing

Long-running jobs can periodically save progress.

On retry:

```text
resume from checkpoint
```

Useful for batch processing.

---

## 92. Retry Storm

A downstream database fails.

Millions of jobs fail and retry at same fixed 30-second delay.

At 30 seconds:

```text
massive synchronized retry wave
```

Use exponential backoff + jitter.

---

## 93. Recovery Ramp-Up

When dependency recovers, do not release entire backlog instantly.

Use token-bucket/admission-controlled recovery.

---

## 94. Thundering Herd on Lease Expiry

Many schedulers observe expired ownership simultaneously.

Use consensus CAS and randomized acquisition backoff.

Only one wins.

---

## 95. Scheduling Precision

Not all jobs need millisecond precision.

Classes:

```text
sub-second
seconds
minutes
hours
```

Higher precision costs more coordination and scanning.

---

## 96. Near-Future Cache

Schedulers may preload jobs due in next few seconds/minutes into memory.

Durable store remains authoritative.

If node crashes, next owner rebuilds from store.

---

## 97. Hierarchical Scheduling

At very large scale:

```text
Far future -> coarse storage buckets
Near future -> fine timing wheel
Due now    -> execution queue
```

This reduces active timer memory.

---

## 98. Capacity Estimation

Example:

```text
1B active scheduled jobs
100M job executions/day
```

Average execution dispatch:

```text
100M / 86,400 ≈ 1,157/sec
```

But spikes may be extreme at clock boundaries:

```text
00:00
09:00
end of month
```

The scheduler must design for burst concentration, not only daily averages.

---

## 99. Top-of-Hour Hotspot

Many customers configure:

```text
0 * * * *
```

Millions fire simultaneously.

Mitigations:

- jitter where semantics allow
- tenant staggering
- queue buffering
- admission control
- priority classes

---

## 100. Storage Choices

Metadata/run state needs durable transactional storage.

Possible:

- relational DB
- distributed SQL
- sharded KV with conditional writes

Scheduling queues may use:

- Kafka
- SQS-like queues
- dedicated delayed queue

Choose based on ordering, delay, scale, and operational model.

---

## 101. Consistency Requirements

Stronger consistency for:

- job ownership
- run claims
- lease epochs
- singleton execution state

Eventual consistency acceptable for:

- dashboards
- aggregated metrics
- logs

---

## 102. Security

- authenticated job submission
- per-tenant authorization
- payload encryption
- secret references, not plaintext secrets
- worker identity
- audit logs
- quota enforcement

Workers should receive only permissions needed for their job.

---

## 103. Auditability

Record:

```text
who created job
who changed schedule
who cancelled
which scheduler created run
which worker executed
which attempt succeeded
```

Important for critical batch/financial workloads.

---

## 104. Observability

Key metrics:

```text
jobs_created/sec
schedule_scan_latency
dispatch_latency
scheduler_shard_count
lease_renew_failure_rate
worker_utilization
queue_depth
oldest_job_lag
execution_success_rate
retry_rate
timeout_rate
DLQ_rate
stuck_job_count
```

---

## 105. Scheduling SLO

Example:

```text
99.9% of normal jobs start within 30 sec of scheduled time
```

Critical jobs may have tighter SLOs.

Measure scheduled-to-start delay separately from execution duration.

---

## 106. Alerting

Alert on:

```text
scheduler shard unowned
lease renewal failures
oldest queue lag increasing
retry rate explosion
worker heartbeat loss
DLQ growth
run creation gap
```

---

## 107. Production Architecture

```text
                           Clients
                              |
                         API Gateway
                              |
                         Job Service
                              |
                     Job Metadata Store
                              |
               +--------------+--------------+
               |                             |
               v                             v
        Scheduler Shard A             Scheduler Shard B
               |                             |
         Lease / Epoch Store          Lease / Epoch Store
               |                             |
               +-------------+---------------+
                             |
                             v
                      Due-Job Dispatcher
                             |
                   +---------+---------+
                   |                   |
                   v                   v
              High Priority        Normal Queue
                   |                   |
                   +---------+---------+
                             |
                             v
                         Worker Fleet
                             |
                             v
                       Run State Store
                             |
                             v
                      Logs / Metrics / DLQ
```

---

## 108. Principal Engineer Failure Scenario

Assume:

```text
10M jobs are scheduled for 09:00
```

At 08:59:50:

```text
scheduler leader in one shard pauses for 20 sec due to GC
its lease expires
new scheduler acquires higher epoch
old scheduler wakes up
worker queue depth jumps 30x
primary downstream DB starts returning 503
workers begin retrying
```

A weak architecture can collapse:

```text
split ownership
-> duplicate dispatch
-> duplicate executions
-> downstream overload
-> retry storm
-> queue explosion
-> scheduler lag
-> more catch-up pressure
```

Principal-level response:

1. use lease epochs/fencing so stale scheduler cannot continue authoritative dispatch
2. create run instances idempotently with `(jobId, scheduledFireTime)` uniqueness
3. workers atomically claim run IDs
4. downstream effects use idempotency keys
5. apply queue admission control
6. keep high-priority workload isolated
7. circuit-break failing downstream database
8. exponential backoff + jitter retries
9. do not blindly autoscale workers against a saturated dependency
10. throttle catch-up of missed jobs
11. use oldest-schedule-lag as primary backlog signal
12. recover shard ownership gradually
13. keep scheduler metadata store separate from worker execution pressure
14. use bulkheads by job type/tenant
15. move permanently failing work to DLQ
16. ramp workers back up only as downstream health recovers

Core principle:

> Scheduler correctness comes from durable run identity and ownership fencing. Scheduler availability comes from leases and failover. Execution safety comes from idempotency, because a crashed worker can always leave uncertainty about whether a side effect happened.

---

## 109. Interview Follow-Up Questions

Be prepared for:

- How do you schedule millions of delayed jobs?
- Polling vs timing wheel?
- Why separate scheduler and workers?
- Why use leader election?
- Global leader vs shard leaders?
- What are leases?
- Why do leases need fencing tokens?
- What happens if old scheduler wakes after lease expiry?
- Can you guarantee exactly-once execution?
- How do you make jobs idempotent?
- How do worker heartbeats work?
- How are stuck jobs recovered?
- How do retries/DLQs work?
- How do you handle cron missed runs?
- How do you handle overlapping cron executions?
- How do you avoid top-of-hour traffic spikes?
- How do you autoscale workers safely?
- How do you handle multi-region scheduling?
- What happens during a regional failover?
- How do clock skew and DST affect scheduling?

---

## 110. Interview Summary

The transferable concepts in this design are:

```text
Delayed scheduling
Cron scheduling
Timing wheels
Time buckets
Leader election
Shard ownership
Leases
Fencing tokens
Atomic claims
At-least-once execution
Idempotent side effects
Worker heartbeats
Execution leases
Retries / DLQ
Backpressure
Priority queues
Tenant isolation
Clock skew
DST semantics
Missed-run policy
Catch-up throttling
Consistent hashing
Multi-region ownership
Transactional outbox
Observability
```

A strong HLD answer should clearly separate **schedule durability**, **scheduler ownership**, and **execution semantics**. The scheduler decides what *should* run; leases and fencing decide who is allowed to dispatch it; idempotency determines whether retries are safe when execution outcome is uncertain.
