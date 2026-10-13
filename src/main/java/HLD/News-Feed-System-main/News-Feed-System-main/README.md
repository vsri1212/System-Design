# News Feed System — High-Level Design

A senior/principal-level HLD case study for designing a Twitter/X, Facebook, or LinkedIn-style news feed platform.

## 1. Problem Statement

Design a globally scalable news feed system where users can:

- create posts
- follow/unfollow users
- fetch a personalized home feed
- view a user's profile timeline
- like/comment/share content
- handle celebrities with millions of followers
- rank content
- paginate efficiently
- support near-real-time updates
- operate across regions

The core challenge is not storing posts. It is generating and serving personalized feeds efficiently under very uneven fan-out patterns.

---

## 2. Functional Requirements

Users should be able to:

- create a post
- delete a post
- follow/unfollow another user
- fetch home timeline
- fetch profile timeline
- like/repost/comment
- paginate older feed items
- receive fresh posts quickly

Example API:

```http
POST /posts
GET /users/{userId}/feed?cursor=...
POST /users/{userId}/follow/{targetUserId}
```

---

## 3. Non-Functional Requirements

- low feed read latency
- high availability
- high write throughput
- scalable fan-out
- graceful handling of hot users
- eventual consistency acceptable for many feed updates
- durable posts and social graph
- efficient pagination
- ranking flexibility
- multi-region resilience
- fault isolation

---

## 4. Capacity Estimation

Example interview assumptions:

```text
300M DAU
20 feed opens/user/day
= 6B feed reads/day
```

Average reads:

```text
6B / 86,400 ≈ 69K reads/sec
```

At 10x peak:

```text
~690K reads/sec
```

Assume:

```text
50M new posts/day
```

Average writes:

```text
50M / 86,400 ≈ 579 writes/sec
```

The system is strongly read-heavy, but write amplification from fan-out can dominate infrastructure cost.

These numbers are assumptions, not facts to memorize.

---

## 5. Core Entities

```text
User
Post
FollowEdge
FeedEntry
Engagement
```

Possible post model:

```text
post_id
user_id
content_ref
created_at
visibility
```

Possible follow model:

```text
follower_id
followee_id
created_at
```

---

## 6. Basic Architecture

```text
Clients
   |
   v
API Gateway
   |
   +--> Post Service
   +--> Social Graph Service
   +--> Feed Service
   +--> Engagement Service

Post Service
   |
   v
Post Store
   |
   v
Kafka/Event Bus
   |
   v
Fan-out Workers
   |
   v
Feed Store / Feed Cache
```

---

## 7. Profile Timeline vs Home Feed

Profile timeline:

```text
all posts created by user X
```

Home feed:

```text
posts from people I follow
+
ranking/recommendations
```

These are different access patterns and should not be treated as the same storage problem.

Profile timeline can often be served directly from a post index keyed by author.

Home feed requires aggregation across many authors.

---

## 8. Fan-Out on Read

At read time:

```text
User follows A, B, C
```

Feed request:

```text
fetch latest posts from A
fetch latest posts from B
fetch latest posts from C
merge + rank
```

Advantages:

- cheap writes
- no massive precomputation

Disadvantages:

- expensive reads
- latency grows with follow count
- difficult at very large scale

---

## 9. Fan-Out on Write

When Alice posts:

```text
Alice -> Post
```

The system pushes the post ID into follower feed lists.

```text
Alice's followers:
Bob
Carol
Dave
...
```

Fan-out worker writes:

```text
feed:Bob   += post123
feed:Carol += post123
feed:Dave  += post123
```

Advantages:

- very fast reads
- feed mostly precomputed

Disadvantages:

- expensive writes
- huge write amplification for celebrities

---

## 10. Celebrity Problem

Suppose a celebrity has:

```text
100M followers
```

One post creates 100M feed writes.

That is expensive and can overload fan-out workers, storage, and caches.

This is the classic reason pure fan-out-on-write is not enough.

---

## 11. Hybrid Fan-Out

Practical design:

```text
Normal users -> fan-out on write
Celebrities  -> fan-out on read
```

For normal users:

```text
post event -> push into follower feeds
```

For celebrity posts:

```text
store once
merge at read time
```

This hybrid design balances write amplification and read latency.

---

## 12. High-Level Hybrid Architecture

```text
                     Post Service
                         |
                         v
                     Post Store
                         |
                         v
                    Kafka/Event Bus
                         |
              +----------+----------+
              |                     |
              v                     v
       Normal Author          Celebrity Author
              |                     |
              v                     v
      Fan-out Workers        Celebrity Post Index
              |
              v
         Feed Store

Feed Request
    |
    v
Feed Service
    |
    +--> Precomputed Feed Store
    +--> Celebrity Post Index
    +--> Ranking Service
    |
    v
Final Feed
```

---

## 13. Feed Store

A user's precomputed feed can store post IDs rather than full posts.

Example:

```text
feed:u123 = [p900, p899, p850, ...]
```

Then hydrate post content separately.

Why IDs only?

- smaller storage footprint
- post edits/deletes easier to reflect
- avoids duplicating large post content

---

## 14. Feed Cache

Hot feeds can be cached in Redis.

Example:

```text
feed:u123 -> recent 500 post IDs
```

The durable feed store remains the source of truth.

Cache candidates:

- recent feed entries
- social graph summaries
- celebrity post lists
- ranking features

---

## 15. Social Graph Storage

Access patterns:

```text
Who does user X follow?
Who follows user X?
```

Possible storage:

- sharded relational DB
- graph-oriented service over KV/storage
- distributed adjacency lists

Example:

```text
following:u123 -> [u2, u8, u91]
followers:u2  -> [u123, u555, ...]
```

Do not assume a graph database is mandatory. The access pattern is often simple adjacency lookup at massive scale.

---

## 16. Sharding Social Graph

Natural shard key:

```text
userId
```

But celebrity follower lists are huge.

Possible strategies:

- shard follower lists into buckets
- partition by follower ID range/hash
- store large adjacency lists in distributed KV/wide-column storage

---

## 17. Post Storage

Natural query:

```text
posts by author ordered by time
```

Possible model:

```text
partition key = authorId
sort key = createdAt/postId
```

This supports profile timelines efficiently.

Potential technologies:

- Cassandra/Scylla
- DynamoDB
- sharded relational DB

Technology choice depends on scale and operational constraints.

---

## 18. Ranking

A chronological feed is simple:

```text
ORDER BY created_at DESC
```

A modern feed may rank by:

- recency
- author affinity
- engagement
- content quality
- predicted relevance
- diversity
- freshness

Ranking architecture:

```text
Candidate Generation
      |
      v
Feature Fetch
      |
      v
Ranking Model
      |
      v
Post Filters
      |
      v
Final Feed
```

---

## 19. Candidate Generation

Candidate sources may include:

- followed-user posts
- celebrity posts
- recommended content
- ads
- trending content

Do not rank millions of items per request.

First generate a manageable candidate set, then rank.

---

## 20. Ranking Features

Possible features:

```text
user-author affinity
post age
like rate
reply rate
repost rate
content type preference
negative feedback
```

Features may come from:

- online feature store
- cached user profile
- stream processing
- offline ML pipelines

---

## 21. Pagination

Offset pagination is dangerous at scale:

```text
?page=100000
```

Problems:

- expensive scans
- duplicates/missing items when feed changes

Prefer cursor-based pagination.

Example:

```text
cursor = lastScore:lastPostId
```

or

```text
cursor = lastTimestamp:lastPostId
```

---

## 22. Stable Cursor

If ranking changes between page requests, users may see duplicates or missing items.

Possible approaches:

- snapshot/session feed IDs
- time-bounded ranking window
- cursor based on deterministic score ordering

Trade-off:

Perfectly stable feeds cost more state.

---

## 23. Freshness

Users expect recent posts to appear quickly.

Possible flow:

```text
Post created
   |
   v
Kafka
   |
   v
Fan-out workers
   |
   v
Feed cache/store
```

Freshness depends on fan-out lag.

Monitor:

```text
post_to_feed_latency
```

---

## 24. Eventual Consistency

A user may create a post and a follower may not see it immediately.

For home feeds, a small delay is often acceptable.

But profile timeline should usually show the author's own newly-created post immediately.

This is a useful consistency distinction.

---

## 25. Delete Post

Deleting a post is harder under fan-out-on-write because the post ID may exist in millions of feeds.

Do not synchronously remove it from every feed.

Better:

```text
mark post deleted in Post Store
```

At read hydration:

```text
skip deleted posts
```

Background cleanup can remove stale feed references later.

---

## 26. Privacy Changes

Suppose a user changes a post from public to private.

Existing feed references may already exist.

Therefore feed hydration should still enforce current visibility/authorization rules.

Never rely purely on fan-out-time authorization.

---

## 27. Follow Event

When user A follows user B, should old posts from B appear immediately?

Options:

- only future posts
- backfill recent posts from B

A practical design may enqueue a backfill job for recent content.

Unfollow can be handled lazily by filtering stale entries during read.

---

## 28. Kafka/Event Bus

Post events:

```text
POST_CREATED
POST_DELETED
FOLLOW_CREATED
FOLLOW_REMOVED
ENGAGEMENT_UPDATED
```

Kafka can decouple:

- fan-out
- search indexing
- analytics
- notifications
- ranking feature pipelines

---

## 29. Partitioning Kafka

For post events:

```text
key = authorId
```

This keeps author events ordered.

For feed fan-out:

```text
partition by follower bucket
```

Choice depends on which ordering constraint matters.

---

## 30. Backpressure

Suppose a celebrity posts during a major event.

Fan-out queue explodes.

Mitigations:

- hybrid fan-out
- throttle low-priority fan-out
- bounded worker concurrency
- priority queues
- lag-aware autoscaling
- isolate celebrity paths

Do not let one hot event block all normal users.

---

## 31. Hot Key Problem

Hot objects include:

- celebrity profiles
- viral posts
- trending feeds

Redis shard can become overloaded.

Mitigations:

- local caches
- replicated hot-key caches
- CDN for media/static content
- key replication
- request coalescing

---

## 32. Like Counts

Do not synchronously update a single relational row for every like on a viral post.

Possible approach:

```text
Like Event
   |
   v
Kafka
   |
   v
Stream Aggregation
   |
   v
Counter Store
```

Exact real-time counts are often unnecessary.

Approximate/eventually consistent counters may be acceptable.

---

## 33. Read Amplification

A feed request may require:

```text
feed IDs
+
post hydration
+
author hydration
+
engagement counters
+
ranking features
```

Avoid N+1 service calls.

Use:

- batching
- denormalized read models
- caches
- backend-for-frontend aggregation

---

## 34. Feed Hydration

Possible pipeline:

```text
Feed IDs
  |
  v
Batch Post Fetch
  |
  v
Batch Author Fetch
  |
  v
Engagement/Feature Fetch
  |
  v
Ranking
```

Batching is essential for predictable latency.

---

## 35. Cache Stampede

Popular feed or post cache expires.

Thousands of requests hit backend.

Use:

- TTL jitter
- single-flight
- stale-while-revalidate
- proactive refresh

---

## 36. Recommendation Integration

A modern feed may combine follow graph with recommended content.

```text
Following Candidates
      +
Recommendation Candidates
      +
Trending Candidates
      |
      v
Ranking Service
```

Keep candidate generation modular so ranking policy can evolve.

---

## 37. Ads Integration

Ads should be inserted through a controlled ranking/placement layer.

Do not mix ad retrieval deeply into post storage.

A feed assembler can combine:

```text
organic ranked items
+
sponsored candidates
```

according to product constraints.

---

## 38. Multi-Region Architecture

```text
                    Global DNS
                        |
          +-------------+-------------+
          |                           |
          v                           v
      India Region                 US Region
          |                           |
          v                           v
      Feed API                    Feed API
          |                           |
          v                           v
   Regional Cache             Regional Cache
          |                           |
          +-------------+-------------+
                        |
                        v
                Replicated Data
```

Possible strategy:

- user home region for writes
- nearest region for reads
- async cross-region replication

---

## 39. Cross-Region Feed Freshness

If author writes in US region and follower reads from India region, replication lag may delay visibility.

Options:

- accept eventual consistency
- route dependent reads to home region
- replicate post events globally through regional streams

Feed systems commonly accept bounded eventual consistency for latency and availability.

---

## 40. Region Failure

If a user's home region fails:

- route reads to secondary region
- use replicated post/social graph data
- feed cache may be cold
- recompute feed from durable sources if necessary

Do not make cached feed state the only copy.

---

## 41. Security and Abuse

Concerns include:

- authenticated write APIs
- visibility enforcement
- block/mute lists
- spam rate limiting
- bot detection
- malicious media scanning
- abuse/reporting pipelines
- privacy filtering at read time

---

## 42. Observability

Important metrics:

```text
feed_read_latency_p50/p95/p99
post_write_latency
fanout_lag
feed_cache_hit_rate
celebrity_merge_latency
ranking_latency
candidate_count
hot_partition_rate
feed_generation_errors
post_to_feed_freshness
```

Also monitor:

- Kafka lag
- Redis latency
- DB shard imbalance
- follower fan-out size

---

## 43. Production Architecture

```text
                         Clients
                            |
                            v
                       API Gateway
                            |
             +--------------+--------------+
             |              |              |
             v              v              v
        Post Service   Feed Service   Graph Service
             |              |              |
             v              |              v
        Post Store          |         Social Graph Store
             |              |
             v              |
          Kafka             |
             |              |
      +------+-------+      |
      |              |      |
      v              v      |
 Fan-out Workers  Celebrity Index
      |              |      |
      v              |      |
   Feed Store         |      |
      |               |      |
      v               |      |
   Redis Cache <------+------+
      |
      v
 Candidate Generator
      |
      v
 Ranking Service
      |
      v
 Feed Assembler
      |
      v
    Client
```

---

## 44. Principal Engineer Failure Scenario

Assume:

```text
Celebrity has 150M followers
```

They post during a global event.

At the same time:

```text
Redis p99 latency rises to 600 ms
Kafka fan-out lag increases
ranking service latency doubles
```

A weak architecture may experience:

```text
celebrity fan-out explosion
-> Kafka backlog
-> Redis write pressure
-> cache misses
-> ranking dependency slowdown
-> feed API latency explosion
-> retries
-> cascading outage
```

Principal-level response:

1. route celebrity posts through fan-out-on-read path
2. isolate celebrity workloads from normal fan-out workers
3. apply strict Redis timeouts
4. use stale feed cache where acceptable
5. degrade ranking complexity under load
6. serve chronological/pre-ranked fallback feed
7. circuit-break optional recommendation services
8. batch hydration calls
9. shed low-value enrichment work
10. protect DBs with bulkheads and bounded concurrency
11. prioritize fresh normal-user fan-out over giant backfills
12. monitor queue lag by workload class
13. ramp cache refill gradually after recovery
14. avoid retry storms
15. keep feed serving available even if recommendations/ranking partially fail

Core principle:

> Feed availability should not depend on every enrichment or ranking dependency being healthy. Degrade personalization before sacrificing the entire feed.

---

## 45. Interview Follow-Up Questions

Be prepared for:

- Fan-out on write vs fan-out on read?
- How do you handle celebrities?
- How do you store the social graph?
- What is stored in a user's feed?
- Why store post IDs rather than full posts?
- How do you rank feeds?
- How do you paginate safely?
- How do you handle deleted/private posts already fanned out?
- How do you process follow/unfollow?
- How do you avoid hot partitions?
- How do you handle viral posts?
- How do engagement counters scale?
- How do you reduce N+1 calls during hydration?
- How do you support multi-region reads?
- What happens if Redis is slow?
- What if the ranking service fails?
- How do you maintain feed freshness?

---

## 46. Interview Summary

The transferable concepts in this design are:

```text
Fan-out on write
Fan-out on read
Hybrid fan-out
Celebrity problem
Social graph
Feed materialization
Caching
Hot keys
Kafka
Partitioning
Backpressure
Ranking
Candidate generation
Feature fetching
Cursor pagination
Eventual consistency
Batch hydration
Counter aggregation
Multi-region replication
Graceful degradation
Circuit breakers
Bulkheads
Observability
```

A strong HLD answer should focus on the central trade-off: precompute more work during writes for fast reads, or defer more work to read time to avoid massive fan-out. The hybrid architecture exists because real social graphs are extremely uneven.
