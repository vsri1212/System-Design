# URL Shortener — High-Level Design

A Principal Engineer–level system design walkthrough for building a scalable URL shortening platform similar to TinyURL or Bitly.

## 1. Problem Statement

Convert a long URL such as:

```text
https://very-long-site.com/products/mobile/iphone/17/details
```

into a short URL such as:

```text
https://short.ly/a7Bx91
```

The simple functionality is easy. The real HLD problem is designing it for very high scale, low latency, high availability, durability, and predictable failure behavior.

---

## 2. Functional Requirements

### Create a short URL

```http
POST /urls
```

Request:

```json
{
  "longUrl": "https://example.com/very/long/url"
}
```

Response:

```json
{
  "shortUrl": "https://short.ly/a7Bx91"
}
```

### Redirect a short URL

```http
GET /a7Bx91
```

Response:

```http
HTTP/1.1 302 Found
Location: https://example.com/very/long/url
```

### Optional features

- Custom aliases
- Expiration
- Deletion
- Analytics
- Abuse prevention
- User accounts

---

## 3. Non-Functional Requirements

The system should optimize for:

- Low redirect latency
- High availability
- Massive read scalability
- Durable mappings
- Globally unique short codes
- No collisions
- Horizontal scalability
- Fault isolation
- Graceful degradation

This is primarily a **read-heavy system**.

---

## 4. Capacity Estimation

Assume:

```text
100 million new URLs/day
1 billion redirects/day
```

Average writes per second:

```text
100,000,000 / 86,400 ≈ 1,157 writes/sec
```

With a 10x peak factor:

```text
~12,000 writes/sec
```

Average reads per second:

```text
1,000,000,000 / 86,400 ≈ 11,574 reads/sec
```

With a 10x peak factor:

```text
~116,000 reads/sec
```

The traffic pattern strongly suggests aggressive caching and read scaling.

---

## 5. Data Model

The core access pattern is:

```text
shortCode -> longUrl
```

Minimal schema:

| Field | Description |
|---|---|
| short_code | Unique identifier |
| long_url | Original destination |
| created_at | Creation timestamp |
| expires_at | Optional expiry |

Example:

```text
a7Bx91 -> https://example.com/article/123
```

The redirect path does not need joins or complex relational queries.

---

## 6. First Architecture

```text
                    Internet
                       |
                       v
                 +-----------+
                 |    DNS    |
                 +-----+-----+
                       |
                       v
                +-------------+
                |Load Balancer|
                +------+------+ 
                       |
             +---------+---------+
             v                   v
      +------------+      +------------+
      |URL Service |      |URL Service |
      | Instance 1 |      | Instance 2 |
      +-----+------+      +------+-----+
            |                    |
            +---------+----------+
                      |
                      v
                 +---------+
                 |  Redis  |
                 +----+----+
                      |
                      v
                 +---------+
                 |Database |
                 +---------+
```

---

## 7. Short-Code Generation

A common strategy is:

```text
unique integer ID -> Base62 encoding -> short code
```

Base62 alphabet:

```text
0-9 = 10
a-z = 26
A-Z = 26
Total = 62
```

Example:

```text
12583901 -> q8Tx
```

If the underlying numeric ID is guaranteed unique, the resulting Base62 code is also unique.

### Address space

For a 7-character code:

```text
62^7 ≈ 3.52 trillion combinations
```

That is enough for very large-scale systems.

---

## 8. Distributed ID Generation

Possible approaches:

- Database auto-increment
- UUID
- Snowflake-style IDs
- Random code + collision detection
- Range allocation

A single central database sequence can become a bottleneck or failure dependency.

A scalable alternative is **range allocation**:

```text
             +----------------+
             | ID Generator   |
             +-------+--------+
                     |
        +------------+------------+
        v            v            v
    Service A    Service B    Service C

    1-10,000   10,001-20K   20,001-30K
```

Each service can generate IDs locally until it exhausts its allocated range.

A Snowflake-style generator is another good option when globally unique, time-sortable IDs are desirable.

---

## 9. URL Creation Flow

```text
Client
  |
  | POST /urls
  v
Load Balancer
  |
  v
URL Service
  |
  | request ID
  v
ID Generator
  |
  | 8392174
  v
URL Service
  |
  | Base62(8392174)
  v
"ab91Xz"
  |
  | store mapping
  v
Database
```

Database mapping:

```text
ab91Xz -> https://example.com/article
```

Response:

```text
https://short.ly/ab91Xz
```

---

## 10. Redirect Flow

```text
Client
   |
   v
Load Balancer
   |
   v
URL Service
   |
   v
Redis
```

Lookup:

```text
GET ab91Xz
```

If found:

```text
ab91Xz -> https://example.com/article
```

Return:

```http
HTTP/1.1 302 Found
Location: https://example.com/article
```

The database is avoided on the hot path.

---

## 11. Cache-Aside Pattern

On a cache miss:

```text
Client
   |
   v
URL Service
   |
   v
Redis
   |
   | MISS
   v
Database
   |
   | long URL
   v
URL Service
   |
   +---- SET Redis
   |
   v
Redirect
```

Redis should primarily contain:

- Hot URLs
- Frequently accessed URLs
- Recent URLs

The database remains the durable source of truth.

---

## 12. Cache Stampede / Thundering Herd

A viral short URL may suddenly receive massive traffic.

If the hot cache entry expires, thousands of requests can miss simultaneously and hit the database.

Mitigations:

- TTL jitter
- Request coalescing / single-flight
- Distributed locking
- Proactive refresh for hot keys
- Local in-process L1 cache
- Negative caching for nonexistent keys

---

## 13. 301 vs 302 Redirects

### 301 Permanent Redirect

Pros:

- Browsers and CDNs can cache aggressively
- Lower traffic to backend
- Lower infrastructure cost

Cons:

- Reduced visibility into redirect analytics
- Harder to change destinations later

### 302 Temporary Redirect

Pros:

- Requests continue reaching the service
- Better analytics
- More control over destination changes

Cons:

- Higher backend traffic

This is a product and architecture trade-off rather than a universally correct choice.

---

## 14. Database Sharding

The natural shard key is `shortCode`.

Example:

```text
hash(shortCode) % numberOfShards
```

Architecture:

```text
                    URL Service
                         |
                         v
                  Shard Router
                         |
            +------------+------------+
            v            v            v
         Shard 1      Shard 2      Shard 3
```

Example:

```text
hash(a7Bx91) % 3 = 1
```

So the mapping is routed to Shard 1.

---

## 15. Replication and Read-After-Write

Each shard should have replicas:

```text
              Shard 1
                 |
        +--------+--------+
        v                 v
     Primary           Replica
```

This improves read scalability and resilience.

But it introduces replication lag.

Example failure mode:

1. User creates a short URL.
2. Mapping is written to primary.
3. User immediately opens the short URL.
4. Request goes to a replica that has not received the write yet.
5. Redirect temporarily fails.

Possible solutions:

- Read from primary immediately after creation
- Sticky routing
- Session-consistent reads
- Synchronous replication for selected operations
- Accept a bounded inconsistency window

The right answer depends on latency and consistency requirements.

---

## 16. Analytics Pipeline

Do not synchronously increment analytics counters in the redirect request path.

Bad design:

```text
redirect -> UPDATE click_count -> return redirect
```

Better design:

```text
Redirect Service
      |
      +------------------> User redirect
      |
      v
    Kafka
      |
      v
Analytics Consumers
      |
      v
Analytics Store
```

This keeps the redirect path fast and isolates analytics failures.

---

## 17. Production-Grade Architecture

```text
                           +--------------+
                           |    Client    |
                           +------+-------+
                                  |
                                  v
                              +-------+
                              |  DNS  |
                              +---+---+
                                  |
                                  v
                         +----------------+
                         | Load Balancer  |
                         +-------+--------+
                                 |
                 +---------------+---------------+
                 v               v               v
             URL Service     URL Service     URL Service
                 |               |               |
                 +---------------+---------------+
                                 |
                     +-----------+-----------+
                     v                       v
               +-----------+          +-------------+
               |   Redis   |          |ID Generator |
               |  Cluster  |          +-------------+
               +-----+-----+
                     |
                     v
                Shard Router
                     |
           +---------+---------+
           v         v         v
        DB-1      DB-2      DB-3
          |         |         |
       Replica   Replica   Replica

              Redirect Service
                     |
                     v
                   Kafka
                     |
             +-------+--------+
             v                v
         Analytics         Monitoring
         Consumer           Consumer
             |
             v
        Analytics DB
```

---

## 18. Principal Engineer Failure Scenario

### Scenario

```text
Traffic = 1M requests/sec
Redis cluster = DOWN
```

If every cache miss goes directly to the database, the cache failure can cascade into a full database outage.

### Correct architectural response

Do not allow the database to become the automatic fallback for unlimited traffic.

Use multiple layers of protection:

1. **Circuit breaker around Redis and database paths**
   - Detect cache failure quickly.
   - Stop retry storms.

2. **Rate limiting / load shedding**
   - Protect the database with a hard request ceiling.
   - Reject or degrade noncritical traffic before the database is saturated.

3. **Local L1 cache**
   - Keep frequently accessed mappings in-process.
   - Survive short Redis outages without immediately hitting the database.

4. **Stale cache serving**
   - Prefer slightly stale but valid mappings to total outage.

5. **Request coalescing**
   - One database lookup per missing key while concurrent requests wait on the same result.

6. **Database bulkheads**
   - Dedicated connection pools and strict limits for redirect traffic.
   - Prevent one workload from exhausting all connections.

7. **Fail fast**
   - If the database protection threshold is reached, return a controlled failure instead of letting latency grow until the whole system collapses.

8. **Redis multi-AZ / multi-region failover**
   - Use replicas and automated failover.

9. **Hot-key prewarming**
   - Restore the most valuable mappings first when cache capacity returns.

10. **Observability**
   - Monitor cache hit ratio, DB connections, CPU, p99 latency, error rate, replication lag, and circuit-breaker state.

### Core principle

> Availability is not achieved by blindly falling back. A resilient system deliberately limits blast radius and sheds load before shared dependencies collapse.

---

## 19. Interview Follow-Up Questions

Be prepared for these:

- Why Base62 instead of UUID?
- How do you avoid collisions?
- What happens if the ID generator fails?
- How do you support custom aliases?
- How do you handle expired URLs?
- What happens if Redis fails?
- How do you prevent a cache stampede?
- Why 301 vs 302?
- How do you shard the database?
- How do you handle replication lag?
- How do you support billions of redirects per day?
- How do you support global users?
- How do you prevent malicious URLs?
- How do you capture analytics without slowing redirects?
- How do you recover from a full-region outage?

---

## 20. Interview Summary

The most important concepts demonstrated by this design are:

```text
Requirements
Capacity estimation
API design
Data modeling
Base62 encoding
Distributed ID generation
Caching
Cache-aside
Cache stampede prevention
Load balancing
Database sharding
Replication
Read-after-write consistency
Asynchronous analytics
Kafka
Circuit breakers
Bulkheads
Load shedding
Graceful degradation
Observability
Failure isolation
```

A strong HLD interview answer is not just a diagram. The interviewer is testing whether you can explain **why each component exists, what breaks when it fails, and what trade-offs you deliberately chose**.
