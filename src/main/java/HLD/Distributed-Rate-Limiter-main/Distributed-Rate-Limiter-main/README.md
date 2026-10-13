# Distributed Rate Limiter — High-Level Design

A senior/principal-level HLD case study for designing a distributed rate limiting platform that can protect APIs at very high scale.

## 1. Problem Statement

Suppose an API normally receives a few requests per second from each client, but one client suddenly sends 100,000 requests/sec.

Without protection:

```text
Client
   |
   | 100K RPS
   v
API Gateway
   |
   v
Backend Service
   |
   v
Database
   X
```

A rate limiter prevents abusive or accidental overload before expensive downstream work begins.

---

## 2. Functional Requirements

Example policies:

```text
Free user     -> 100 requests/minute
Premium user  -> 1,000 requests/minute
Internal API  -> 10,000 requests/minute
```

Limits may be applied by:

- user ID
- API key
- IP address
- endpoint
- tenant
- region
- subscription tier

Example:

```text
user:123 + /payments -> 10 requests/sec
user:123 + /products -> 100 requests/sec
```

---

## 3. Non-Functional Requirements

The system should provide:

- very low latency
- horizontal scalability
- high availability
- consistent enforcement across many gateway instances
- burst control
- tenant isolation
- graceful degradation
- fault isolation
- policy flexibility
- strong observability

---

## 4. Placement in the Architecture

A good first protection layer is close to the edge:

```text
Internet
   |
   v
Load Balancer
   |
   v
API Gateway
   |
   v
Rate Limiter
   |
   +--> REJECT -> HTTP 429
   |
   +--> ALLOW
          |
          v
     Microservices
```

The goal is to reject excess traffic before it consumes backend threads, connections, CPU, database capacity, or third-party quotas.

---

## 5. Fixed Window Counter

Rule:

```text
100 requests/minute
```

Redis key:

```text
rate:user123:12:00 -> 73
```

Each request performs an increment.

```text
INCR rate:user123:12:00
```

Allow if:

```text
count <= 100
```

Otherwise return:

```http
HTTP/1.1 429 Too Many Requests
```

### Boundary burst problem

```text
12:00:59 -> 100 requests
12:01:01 -> 100 requests
```

Both fixed windows are valid, but 200 requests occurred within roughly two seconds.

This makes fixed windows simple but burst-prone.

---

## 6. Sliding Window Log

Instead of counting requests since a wall-clock boundary, count requests over the previous rolling interval.

At 12:01:20, for a 60-second window:

```text
12:00:20 -> 12:01:20
```

A Redis sorted set can store request timestamps.

Conceptually:

```text
ZADD requests timestamp requestId
ZREMRANGEBYSCORE requests 0 oldTimestamp
ZCARD requests
```

Advantages:

- precise rolling-window enforcement

Trade-offs:

- high memory usage
- more Redis operations
- expensive for very high-cardinality traffic

---

## 7. Sliding Window Counter

A practical compromise is to keep counters for adjacent windows and weight the previous window proportionally.

This reduces the fixed-window boundary problem without storing every request timestamp.

Trade-off:

- approximate rather than exact
- much cheaper than a sliding log

---

## 8. Token Bucket

Token Bucket is often a strong default algorithm for API rate limiting.

Example:

```text
Bucket capacity = 100
Refill rate     = 10 tokens/sec
```

Each accepted request consumes one token.

If a token exists:

```text
ALLOW
```

Otherwise:

```text
HTTP 429 Too Many Requests
```

The bucket supports both:

```text
Sustained rate -> 10 requests/sec
Burst capacity -> up to 100 requests
```

This makes it useful when controlled bursts are acceptable.

---

## 9. Token Bucket Example

Suppose:

```text
capacity = 10
refill   = 2 tokens/sec
```

Initially:

```text
tokens = 10
```

Six requests arrive:

```text
10 -> 9 -> 8 -> 7 -> 6 -> 5 -> 4
```

Three seconds pass:

```text
3 * 2 = 6 tokens refilled
```

Result:

```text
min(10, 4 + 6) = 10
```

The bucket never exceeds its configured capacity.

---

## 10. Leaky Bucket

Leaky Bucket smooths traffic at a fixed drain rate.

Conceptually:

```text
Incoming burst
     |
     v
+------------+
|   Queue    |
+------------+
     |
     | fixed drain rate
     v
Backend
```

It is useful when a downstream system needs a predictable request rate.

Trade-off:

- requests may queue rather than execute immediately
- queue growth must be bounded

---

## 11. Why Distributed Rate Limiting Is Hard

Suppose the user limit is:

```text
100 requests/minute
```

Traffic is spread across three gateway servers:

```text
Server A -> 40
Server B -> 35
Server C -> 45
```

Each node locally sees fewer than 100 requests, but globally:

```text
40 + 35 + 45 = 120
```

Therefore local-only counters cannot enforce a global limit accurately.

We need shared state or coordinated quota allocation.

---

## 12. Redis-Based Distributed Architecture

```text
                 Client
                    |
                    v
              API Gateway
                    |
                    v
              Rate Limiter
                    |
                    v
             Redis Cluster
```

Example key:

```text
rl:user:123:/payments
```

Token-bucket state may contain:

```text
tokens = 17
lastRefill = 12:05:32.120
```

Every gateway sees the same logical limiter state.

---

## 13. Distributed Race Condition

Suppose:

```text
tokens = 1
```

Two gateway instances execute simultaneously:

```text
Server A -> GET tokens -> 1
Server B -> GET tokens -> 1
```

Both may decide the request is allowed.

This violates the rate limit.

The operation must therefore be atomic.

---

## 14. Atomic Enforcement with Redis Lua

The following logical steps must execute as one atomic operation:

```text
read current tokens
calculate refill
check token availability
decrement token
update timestamp
```

Redis Lua scripts are commonly used to package these steps into a single atomic operation.

Conceptually:

```text
EVAL rateLimiterScript ...
```

This prevents interleaving between competing gateway requests.

---

## 15. Redis Cluster and Sharding

At high scale, limiter state should be distributed across Redis shards.

```text
                  Rate Limiters
                       |
                       v
                 Redis Cluster
                       |
            +----------+----------+
            v          v          v
         Shard A    Shard B    Shard C
```

A common partition key is:

```text
hash(userId)
```

or a composite identity such as:

```text
hash(tenantId:userId:endpoint)
```

This horizontally distributes limiter state.

---

## 16. Hot-Key Problem

A single extremely active customer may map to one key and therefore one shard.

Example:

```text
5M requests/sec
       |
       v
rl:user:hot-account
       |
       v
Redis Shard 7
       X
```

Normal sharding does not solve this because one logical key still belongs to one shard.

Potential mitigations:

- hierarchical/local quotas
- token leasing
- edge-side limiting
- preallocated quota chunks
- request sampling for coarse-grained limits
- special handling for extremely hot identities

---

## 17. Local + Global Hierarchical Rate Limiting

Calling Redis for every request can become too expensive at extreme scale.

Instead, a global coordinator can lease quota to gateway instances.

Example global quota:

```text
1,000,000 requests/sec
```

With 100 gateways:

```text
Gateway 1   -> 10K tokens
Gateway 2   -> 10K tokens
...
Gateway 100 -> 10K tokens
```

Most requests are now handled by an in-memory local token bucket.

```text
Client
   |
   v
Gateway
   |
   v
Local Token Bucket
```

Gateways periodically replenish quota from a central system.

Trade-off:

- much lower coordination cost
- better latency
- slightly weaker global precision

This is usually a good Principal-level discussion because it highlights the trade-off between exactness and scalability.

---

## 18. Fail-Open vs Fail-Closed

If Redis fails, the system must make a deliberate choice.

### Fail-open

Allow traffic temporarily.

Suitable when availability matters more than strict enforcement.

Example:

```text
GET /product-catalog
```

### Fail-closed

Reject traffic when the limiter cannot verify quota.

Suitable when abuse or downstream overload is dangerous.

Examples:

```text
POST /login
POST /otp
POST /payment
```

A mature design can make this policy configurable per endpoint.

---

## 19. Emergency Local Limiter

Instead of choosing only between "allow everything" and "reject everything", use a local fallback limiter.

```text
                       Redis
                         X
                      failure
                         |
                         v
Client -> Gateway -> Local emergency limiter
                         |
                 +-------+-------+
                 v               v
               Allow           Reject
```

This allows graceful degradation while still protecting downstream systems.

---

## 20. Slow Redis Is More Dangerous Than Dead Redis

Suppose Redis latency changes from:

```text
1 ms
```

to:

```text
800 ms
```

Redis is technically alive, so every gateway waits.

```text
Gateway
  |
  +-- waiting
  +-- waiting
  +-- waiting
  +-- waiting
  +-- waiting
```

This can produce:

```text
Redis slowdown
   -> gateway thread exhaustion
   -> connection pool exhaustion
   -> latency explosion
   -> client retries
   -> even more traffic
   -> backend overload
   -> platform outage
```

### Required defenses

- aggressive Redis timeouts
- circuit breakers
- local fallback limiter
- load shedding
- retry budgets
- bulkheads
- bounded queues
- stale policy/config usage
- gradual traffic ramp-up during recovery

Core lesson:

> A slow dependency can be more dangerous than a dead dependency because callers continue waiting and consuming resources.

---

## 21. Multi-Region Rate Limiting

Suppose a global user has a limit of:

```text
100 requests/sec
```

India sees:

```text
70 RPS
```

US sees:

```text
70 RPS
```

Each region looks valid, but globally:

```text
140 > 100
```

Synchronous cross-region coordination for every request would add unacceptable latency and reduce availability.

A practical approach is quota partitioning:

```text
Global quota = 100

India -> 50
US    -> 30
EU    -> 20
```

Regions enforce their leased quota locally, while a global coordinator periodically reallocates quota according to demand.

Trade-off:

```text
perfect global accuracy
vs
latency + availability + scalability
```

---

## 22. Policy Service

The rate limiter should not hard-code all rules.

A policy/configuration service can maintain limits such as:

```text
free-user:/search = 100/min
premium-user:/search = 1000/min
payment:/charge = 10/sec
```

Gateways should cache policy locally to avoid adding a configuration lookup to every request.

Policy changes can be propagated through push notifications, pub/sub, or periodic refresh.

---

## 23. Rate-Limit Response Headers

Useful HTTP response headers may include:

```text
RateLimit-Limit
RateLimit-Remaining
RateLimit-Reset
Retry-After
```

For a rejected request:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 15
```

These allow well-behaved clients to back off intelligently.

---

## 24. Retry Control

A rate limiter can accidentally create a retry storm if clients immediately retry every 429 response.

Clients should use:

- exponential backoff
- jitter
- Retry-After
- retry budgets
- bounded retry attempts

Infrastructure components should avoid blindly retrying rejected traffic.

---

## 25. Bulkheads and Blast-Radius Control

Separate critical resources where possible:

- connection pools
- worker pools
- tenant quotas
- endpoint quotas
- Redis partitions

The purpose is to prevent one customer or endpoint from exhausting shared capacity for the entire platform.

---

## 26. Observability

Important metrics include:

```text
requests_allowed_total
requests_rejected_total
rate_limit_decision_latency
Redis latency p50/p95/p99
Redis error rate
fallback-mode activations
circuit breaker state
hot-key frequency
quota exhaustion rate
policy lookup failures
gateway thread utilization
connection pool usage
```

Useful alerts:

- Redis p99 latency spike
- sudden 429 increase
- fallback mode activated
- hot shard imbalance
- policy service unavailable
- unusual per-tenant traffic

---

## 27. Production-Grade Architecture

```text
                           Internet
                              |
                              v
                      Global Load Balancer
                              |
                 +------------+------------+
                 v                         v
             India Region               US Region
                 |                         |
                 v                         v
            API Gateway                API Gateway
                 |                         |
                 v                         v
        Local Rate Limiter         Local Rate Limiter
                 |                         |
                 +------------+------------+
                              |
                              v
                       Redis Cluster
                              |
                    +---------+---------+
                    v         v         v
                 Shard A   Shard B   Shard C

                              |
                              v
                      Quota Coordinator
                              |
                              v
                     Policy / Config Store

Allowed requests
       |
       v
Backend Microservices
```

---

## 28. Principal Engineer Failure Scenario

Assume:

```text
Traffic = 10M requests/sec
Redis latency = 800 ms
```

The correct response is not to wait until Redis completely fails.

A resilient system should:

1. use very small dependency timeouts
2. trip a circuit breaker when Redis latency/error thresholds are exceeded
3. switch to local emergency token buckets
4. shed noncritical traffic early
5. keep queues bounded
6. isolate pools using bulkheads
7. suppress retries and apply retry budgets
8. continue using locally cached policies
9. monitor Redis recovery separately from user traffic
10. ramp traffic back gradually rather than reconnecting every gateway at once

The objective is to stop a slow shared dependency from turning into a full-platform cascading failure.

---

## 29. Interview Follow-Up Questions

Be prepared to answer:

- Fixed window vs sliding window vs token bucket?
- Why Token Bucket?
- How do you handle bursts?
- How do you make Redis updates atomic?
- Why use Lua scripts?
- What if two gateways race for the last token?
- What if Redis is unavailable?
- Fail-open or fail-closed?
- What if Redis is slow instead of down?
- How do you handle a hot key?
- How do you avoid Redis on every request?
- How do you support multi-region global quotas?
- How accurate does the global limit need to be?
- How do you support different plans and endpoint limits?
- How do you prevent retry storms after 429 responses?
- How do you protect the rate limiter itself from overload?
- What metrics would you monitor?

---

## 30. Interview Summary

The core concepts demonstrated by this system are:

```text
Rate limiting
Fixed Window
Sliding Window
Token Bucket
Leaky Bucket
Redis
Atomic operations
Lua scripts
Distributed counters
Sharding
Hot keys
Hierarchical quotas
Token leasing
Multi-region coordination
Fail-open vs fail-closed
Circuit breakers
Timeouts
Bulkheads
Load shedding
Retry budgets
Graceful degradation
Observability
Blast-radius control
```

A strong senior/principal answer should not stop at choosing an algorithm. The interviewer wants to know what happens when the shared state store becomes slow, a key becomes extremely hot, traffic spans multiple regions, and accuracy conflicts with latency and availability.
