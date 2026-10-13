# Autocomplete / Typeahead System — High-Level Design

A senior/principal-level HLD case study for designing a Google-style autocomplete/typeahead service focused on **prefix search, Top-K ranking, hot-prefix handling, freshness, personalization, and very low latency**.

The core challenge is:

> Return the most useful suggestions for every keystroke in tens of milliseconds while query popularity, trends, languages, and user context constantly change.

---

## 1. Problem Statement

Design an autocomplete system where users type:

```text
g
go
goo
goog
```

and receive ranked suggestions such as:

```text
google
google maps
google translate
```

The system should support:

- prefix search
- top-K ranking
- trending suggestions
- personalization
- typo tolerance
- multilingual text
- offensive/spam filtering
- high QPS
- multi-region serving
- low latency

---

## 2. Functional Requirements

The system should:

- accept a prefix
- return top K suggestions
- rank by popularity/freshness/context
- optionally personalize
- filter unsafe/offensive suggestions
- update trending queries quickly
- support multiple locales/languages

Example API:

```http
GET /autocomplete?prefix=goo&limit=10&locale=en-IN
```

Example response:

```json
{
  "suggestions": [
    "google",
    "google maps",
    "google translate"
  ]
}
```

---

## 3. Non-Functional Requirements

- p95 latency under ~100 ms
- high availability
- very high read throughput
- low CPU per request
- graceful degradation
- scalable global serving
- freshness for trending queries
- cache-friendly design
- hot-prefix protection
- versioned index deployment

---

## 4. Capacity Estimation

Assume:

```text
500M daily search users
20 searches/user/day
8 autocomplete requests/search
```

Requests/day:

```text
500M * 20 * 8 = 80B requests/day
```

Average:

```text
80B / 86,400 ≈ 926K requests/sec
```

At peak, several million requests/sec is plausible.

These are interview assumptions, not facts to memorize.

The main conclusion:

> Autocomplete is an extremely read-heavy, cache-heavy, latency-sensitive serving problem.

---

## 5. High-Level Architecture

```text
Client
  |
  v
Edge / API Gateway
  |
  v
Autocomplete Service
  |
  +--> Local Cache
  +--> Distributed Prefix Cache
  +--> Prefix Index / Trie
  +--> Personalization Layer
  +--> Safety Filter
  |
  v
Top-K Suggestions
```

Data pipeline:

```text
Search Events
   |
   v
Kafka
   |
   v
Streaming Aggregation
   |
   +--> Trending Scores
   +--> Popularity Counters
   |
   v
Index Builder
   |
   v
Versioned Prefix Index
```

---

## 6. Why Not Query a Normal Database for Every Keystroke?

Bad design:

```sql
SELECT query
FROM queries
WHERE query LIKE 'goo%'
ORDER BY popularity DESC
LIMIT 10;
```

At millions of QPS this is too expensive and unpredictable.

Autocomplete needs a precomputed serving structure optimized for prefix lookup.

---

## 7. Trie

A Trie stores strings character by character.

Example:

```text
        g
        |
        o
       / \
      o   l
      |
      g
```

Lookup cost is roughly proportional to prefix length rather than total query count.

---

## 8. Top-K at Trie Nodes

Instead of traversing the entire subtree on every request, store precomputed top suggestions at each node.

Example:

```text
node "goo"
  topK =
  [
    google,
    google maps,
    google translate
  ]
```

Then serving becomes extremely fast.

---

## 9. Memory Trade-Off

Storing Top-K on every Trie node increases memory.

Trade-off:

```text
More memory
-> lower read latency
```

This is usually worthwhile for latency-sensitive autocomplete.

---

## 10. Prefix Table Alternative

Instead of a pointer-heavy Trie, precompute:

```text
prefix -> topK suggestions
```

Example:

```text
g     -> [...]
go    -> [...]
goo   -> [...]
```

This works naturally with KV stores and immutable index files.

---

## 11. Trie vs Prefix KV Table

Trie:

- compact shared prefixes
- natural traversal
- memory efficient with compression

Prefix table:

- simple distributed lookup
- easy immutable snapshots
- duplication across prefixes

Either can be valid depending on scale and implementation.

---

## 12. Compressed Trie / Radix Tree

Long single-child chains can be compressed.

Instead of:

```text
g -> o -> o -> g -> l -> e
```

store:

```text
google
```

as one edge where possible.

This reduces memory overhead.

---

## 13. Ranking Signals

Suggestions may rank by:

- query frequency
- recency
- trend velocity
- geographic popularity
- language
- user history
- device/context
- safety/business rules

A simple score:

```text
score = w1*frequency + w2*recency + w3*trend
```

---

## 14. Frequency Aggregation

Raw search events:

```text
user searched "java"
user searched "java"
user searched "javascript"
```

Pipeline:

```text
Search Events -> Kafka -> Stream Processor -> Counters
```

Store aggregate counts rather than querying raw events at request time.

---

## 15. Time Windows

Popularity should not use only lifetime frequency.

Possible windows:

```text
5 min
1 hour
1 day
7 day
lifetime
```

Combine them for freshness + stability.

---

## 16. Trending Score

A query can become popular rapidly.

Trend signal may compare:

```text
current rate
vs
historical baseline
```

Example:

```text
current 10K/min
baseline 50/min
```

strong trend boost.

---

## 17. Stream Processing

Kafka + Flink/Spark Streaming-style pipeline:

```text
Search Event
   |
   v
Partitioned Stream
   |
   v
Windowed Aggregation
   |
   v
Trending / Prefix Score Updates
```

This allows near-real-time trending suggestions.

---

## 18. Event Time vs Processing Time

Events can arrive late.

For trend calculation, event-time windows may be more accurate.

Use watermarks/late-event policy where necessary.

Autocomplete does not require perfect accounting precision, so practical bounded lateness is acceptable.

---

## 19. Offline + Real-Time Hybrid

Use two sources:

```text
Stable Base Index
+
Real-Time Trending Overlay
```

Base index rebuilt periodically.

Trending overlay updated continuously.

Serving merges both.

---

## 20. Why Hybrid?

Rebuilding a giant Trie every second is expensive.

Pure streaming state may be unstable/noisy.

Hybrid gives:

- stable long-term popularity
- fast trend freshness

---

## 21. Index Build Pipeline

```text
Aggregated Query Stats
      |
      v
Filter / Normalize
      |
      v
Compute Ranking Scores
      |
      v
Build Trie / Prefix Table
      |
      v
Publish Versioned Index
```

---

## 22. Immutable Indexes

Build:

```text
index-v101
```

then publish atomically.

Advantages:

- safe concurrent reads
- easy rollback
- easy replication
- no partially updated Trie

---

## 23. Index Versioning

Serving nodes load:

```text
v101
```

New build produces:

```text
v102
```

Deployment:

```text
load -> warm -> health check -> switch pointer
```

Rollback to v101 if needed.

---

## 24. Distributed Index Partitioning

Autocomplete indexes can be partitioned by prefix.

Example:

```text
A-F -> shard 1
G-M -> shard 2
N-S -> shard 3
T-Z -> shard 4
```

But naive alphabet partitions create skew.

---

## 25. Hash Partitioning vs Prefix Partitioning

Hash partitioning:

- balances load
- loses prefix locality

Prefix partitioning:

- natural lookup locality
- can create hot shards

A hybrid can split hot prefix ranges further.

---

## 26. Hot Prefix Problem

Prefixes like:

```text
a
s
g
```

receive enormous traffic.

One shard may become a hotspot.

Mitigations:

- edge cache
- process-local cache
- replicated hot-prefix entries
- prefix shard splitting
- request coalescing

---

## 27. Replicate Hot Prefixes

Cold prefix:

```text
xyloph...
```

may live on one shard.

Hot prefix:

```text
a
```

can be replicated across many serving nodes.

This is asymmetric replication based on popularity.

---

## 28. Local Memory Cache

Autocomplete responses are tiny and repetitive.

Each service instance can cache hot prefixes:

```text
"go" -> top10
```

This removes network hops.

---

## 29. Distributed Cache

Second layer:

```text
Redis / distributed cache
```

for prefixes not found locally.

Serving path:

```text
L1 process cache
-> L2 distributed cache
-> index store
```

---

## 30. Cache TTL

Hot prefix entries can use short TTLs.

Trend-sensitive prefixes may refresh more frequently.

Stable prefixes can live longer.

---

## 31. Cache Stampede

Popular prefix expires:

```text
"a"
```

millions of requests miss simultaneously.

Use:

- stale-while-revalidate
- single-flight/request coalescing
- TTL jitter
- proactive refresh

---

## 32. Prefix Length Threshold

Single-character prefixes have huge candidate spaces and QPS.

Options:

- cache aggressively
- serve precomputed global Top-K
- delay richer personalization until prefix length >= 2 or 3

---

## 33. Personalization

Global suggestions:

```text
python
python download
python tutorial
```

User-specific additions may include:

```text
python interview questions
```

based on prior searches.

---

## 34. Personalization Architecture

```text
Global Top-K
    +
User Recent Queries
    +
Contextual Features
    |
    v
Lightweight Re-Ranker
```

Do not build a unique giant Trie per user.

---

## 35. Why Re-Rank Instead of Personalized Trie?

Per-user Trie would explode storage and update cost.

Better:

- global candidate generation
- lightweight per-user re-ranking

This follows the same candidate-generation principle used in feeds/search/recommendations.

---

## 36. User History Storage

Recent history may be stored as:

```text
userId -> recent normalized queries
```

Use bounded retention and privacy controls.

---

## 37. Privacy

Autocomplete personalization can expose sensitive search history.

Use:

- opt-out controls
- retention limits
- encryption
- access controls
- avoid showing sensitive past queries unexpectedly

---

## 38. Anonymous Users

For users without history, rank by:

- locale
- geography
- global popularity
- trends

No personalization is required for good autocomplete.

---

## 39. Geographic Ranking

Prefix:

```text
weather
```

Suggestions can differ by region.

Possible ranking dimension:

```text
(prefix, locale, region) -> Top-K
```

Do not create infinitely fine geographic partitions; use coarse regions/countries/cities as justified.

---

## 40. Language / Locale

Index should understand:

```text
en-IN
en-US
hi-IN
te-IN
```

Separate ranking/popularity by locale where useful.

---

## 41. Unicode Normalization

Normalize Unicode consistently.

Examples:

- composed/decomposed accents
- case folding
- scripts

Incorrect normalization creates duplicate or missing suggestions.

---

## 42. Transliteration

In some locales users type native-language terms using Latin characters.

Example:

```text
hyderabad biryani
```

vs local-script equivalent.

Transliteration can be a query-understanding enhancement, not necessarily core Trie logic.

---

## 43. Typo Tolerance

User types:

```text
gogle
```

Possible approaches:

- edit distance
- fuzzy prefix index
- spell correction model
- n-gram index

Do not run expensive fuzzy matching over the entire vocabulary for every keystroke.

---

## 44. Trie Fuzzy Search

Limited edit distance can be explored through Trie states.

Works for smaller candidate spaces but can become expensive.

Often better to use a separate typo-correction candidate generator.

---

## 45. Safety Filtering

Autocomplete can amplify harmful/offensive/spam queries.

Use filters during both:

- index build
- online serving

Examples:

- blocked terms
- policy classifiers
- spam detection
- age/locale policies

---

## 46. Why Filter at Two Layers?

Offline filtering keeps bad candidates out of the main index.

Online filtering provides fast policy response without waiting for a full rebuild.

Defense in depth.

---

## 47. Query Normalization

Before lookup:

```text
trim spaces
case fold
Unicode normalize
possibly normalize punctuation
```

Preserve enough original text for display.

---

## 48. Prefix Key Format

Possible key:

```text
locale|region|prefix
```

Example:

```text
en-IN|IN-SOUTH|goo
```

This supports localized serving.

---

## 49. Top-K Data Structure

During index build, maintain a min-heap of size K at each node/prefix.

For candidate with score S:

```text
if heap.size < K -> insert
else if S > min -> replace min
```

---

## 50. Offline Top-K Computation

Given query frequencies:

```text
google             10M
google maps         8M
google translate    5M
good morning        2M
```

Every prefix node receives relevant top suggestions.

---

## 51. Ranking Stability

If suggestions reorder on every keystroke/request due to tiny score changes, UX feels unstable.

Possible techniques:

- score smoothing
- minimum trend threshold
- rank hysteresis

---

## 52. Trending Overlay Merge

Serving may fetch:

```text
Base Top-K: 20 candidates
Trending Top-K: 10 candidates
```

Merge, deduplicate, re-rank, return 10.

---

## 53. Deduplication

Candidate sources can overlap.

Deduplicate normalized query text before final ranking.

---

## 54. Freshness

Stable suggestions may update hourly/daily.

Breaking trends may need seconds/minutes.

Use tiered freshness rather than rebuilding everything equally often.

---

## 55. Hot Trend Scenario

A previously obscure term suddenly becomes globally popular.

Streaming layer should promote it quickly into trending overlay even before next base index build.

---

## 56. Kafka Partitioning

Search events can be partitioned by:

```text
normalizedQuery hash
```

This keeps updates for one query together for aggregation.

---

## 57. Aggregator State

Stream processor maintains:

```text
query -> window counters
```

At huge vocabulary size, memory can be substantial.

Use state backend/checkpointing and expiration.

---

## 58. Approximate Counting

Exact counts may not be necessary.

Possible techniques:

- Count-Min Sketch
- approximate heavy hitters

Useful when cardinality is enormous.

---

## 59. Count-Min Sketch

Provides approximate frequency with compact memory.

Trade-off:

- overestimation possible
- excellent for high-volume popularity detection

Autocomplete ranking usually tolerates small approximation errors.

---

## 60. Heavy Hitters

We care most about popular queries.

Algorithms can focus resources on top-frequency candidates rather than tracking every rare query precisely.

---

## 61. Index Builder Sharding

Build index partitions independently.

Example:

```text
prefix range shard builders
```

Then publish one global manifest:

```text
version -> shard files/checksums
```

---

## 62. Index Distribution

New immutable index files replicate to serving regions.

Serving nodes:

```text
download
verify checksum
memory-map/load
warm
switch
```

---

## 63. Memory-Mapped Index

Large immutable prefix structures may be memory-mapped rather than fully deserialized into heap objects.

Benefits:

- lower startup cost
- efficient OS page cache

Implementation-dependent trade-off.

---

## 64. Multi-Region Serving

```text
Global DNS
   |
   +--> India Region
   +--> US Region
   +--> EU Region
```

Each region has a full or appropriate replicated autocomplete index.

Requests go to nearest healthy region.

---

## 65. Why Full Replication Can Be Reasonable

Autocomplete index may be much smaller than the full search corpus.

Replicating it to every region gives:

- low latency
- no cross-region lookup
- simple failover

---

## 66. Regional Trending Overlay

Global base index can be shared.

Trending overlay may differ by region:

```text
India trends
US trends
Europe trends
```

This improves relevance.

---

## 67. Multi-Region Update Strategy

Base indexes propagate asynchronously.

A region can continue serving version N while version N+1 is downloading.

Availability is more important than every region switching simultaneously.

---

## 68. Region Failure

If one region fails:

```text
route to nearest healthy region
```

Need spare capacity and admission controls.

Autocomplete can also degrade to local client history if network serving is unavailable.

---

## 69. Graceful Degradation

If personalization service fails:

```text
serve global Top-K
```

If trending overlay fails:

```text
serve stable base index
```

If distributed cache fails:

```text
use local cache/index
```

Autocomplete should remain useful despite optional dependency failures.

---

## 70. Latency Budget

Example:

```text
total p95 target = 80 ms
```

Budget:

```text
edge/network        20 ms
service processing  10 ms
index lookup         5 ms
personalization     15 ms
safety/ranking      10 ms
margin               20 ms
```

The exact numbers are illustrative.

---

## 71. Cancellation

Users type quickly:

```text
g
then go
then goo
```

Older requests become irrelevant.

Client/server should cancel or ignore stale requests to avoid wasted work.

---

## 72. Debouncing

Client may debounce keystrokes slightly before calling backend.

Trade-off:

- fewer requests
- slightly delayed suggestions

Useful especially on slow/mobile networks.

---

## 73. Request Sequencing

Responses can return out of order.

Example:

```text
request 5: "goo"
request 6: "goog"
```

If request 5 returns last, client should not overwrite newer suggestions.

Use request sequence IDs.

---

## 74. Rate Limiting

Protect API from:

- bots
- scraping
- abusive clients

Rate limit by:

- user
- IP
- device/session

But legitimate typing traffic is naturally bursty, so limits must account for this.

---

## 75. Security

- TLS
- authentication for personalized features
- privacy-preserving logs
- abuse filtering
- rate limiting
- safe admin/index publishing

Do not expose raw sensitive query history through logs or debug APIs.

---

## 76. Observability

Key metrics:

```text
autocomplete_qps
latency_p50/p95/p99
cache_hit_rate
local_cache_hit_rate
index_lookup_latency
prefix_hotspot_rate
personalization_latency
trending_overlay_lag
index_version_by_region
index_load_failure_rate
empty_suggestion_rate
```

---

## 77. Relevance Metrics

System quality metrics:

```text
suggestion_click_through_rate
acceptance_rate
keystrokes_saved
MRR / rank quality
bad-suggestion reports
```

Latency alone is not enough.

---

## 78. Production Architecture

```text
                             Clients
                                |
                         Edge / API Gateway
                                |
                       Autocomplete Service
                  +-------------+-------------+
                  |             |             |
                  v             v             v
              L1 Cache       L2 Cache    Prefix Index
                                              |
                                              v
                                        Base Top-K
                                              |
                  +---------------------------+----------------+
                  |                                            |
                  v                                            v
          Trending Overlay                             Personalization
                  |                                            |
                  +---------------------+----------------------+
                                        |
                                        v
                                 Lightweight Re-Ranker
                                        |
                                        v
                                  Safety Filtering
                                        |
                                        v
                                   Suggestions

Search Events -> Kafka -> Stream Aggregation -> Trending Store
                         |
                         v
                  Offline Aggregation
                         |
                         v
                    Index Builder
                         |
                         v
                 Versioned Index Files
                         |
                         v
                  Regional Distribution
```

---

## 79. Principal Engineer Failure Scenario

Assume a major world event occurs.

Within 30 seconds:

```text
traffic jumps 6x
80% of requests begin with two hot prefixes
a trending term changes every few seconds
Redis p99 rises from 2 ms to 300 ms
personalization service starts timing out
streaming aggregation is 2 minutes behind
```

A weak system may collapse:

```text
hot prefixes
-> Redis overload
-> cache timeouts
-> backend index traffic spikes
-> personalization calls pile up
-> thread pools saturate
-> retries increase
-> global latency explosion
```

Principal-level response:

1. serve hot prefixes from process-local L1 cache
2. replicate/prewarm hottest prefix entries
3. use stale-while-revalidate
4. strict Redis timeout; do not wait 300 ms
5. bypass Redis and read immutable local index when needed
6. circuit-break personalization
7. return global/regional Top-K without personalization
8. keep base index independent from streaming freshness
9. merge whatever trending overlay is safely available
10. shed optional typo/personalization features under load
11. coalesce identical prefix requests
12. cancel stale keystroke requests
13. protect servers with admission control
14. avoid client retry storms
15. independently scale streaming pipeline, but do not let its lag break serving
16. recover Redis/personalization gradually

Core principle:

> Autocomplete should have a tiny, deterministic core serving path. Personalization and real-time trends improve relevance, but neither should be required to return useful suggestions.

---

## 80. Interview Follow-Up Questions

Be prepared for:

- Why use a Trie?
- Why store Top-K at every prefix node?
- Trie vs prefix KV table?
- How do you rank suggestions?
- How do you update trending queries quickly?
- Why use base index + realtime overlay?
- How do you handle prefixes like `a` becoming hot?
- How do you shard the Trie?
- Why might you replicate hot prefixes?
- How do you personalize without a per-user Trie?
- How do you handle typo tolerance?
- What is Count-Min Sketch useful for?
- How do you support multiple languages?
- How do you filter offensive suggestions?
- How do you deploy new Trie/index versions safely?
- What happens if Redis fails?
- What happens if personalization fails?
- How do you achieve sub-100-ms latency globally?

---

## 81. Interview Summary

The transferable concepts in this design are:

```text
Trie / Radix Tree
Prefix index
Precomputed Top-K
Heap-based Top-K construction
Popularity ranking
Windowed aggregation
Trending detection
Kafka / stream processing
Base + realtime overlay
Hot keys / hot prefixes
L1/L2 caching
Request coalescing
Stale-while-revalidate
Approximate counters
Count-Min Sketch
Personalized re-ranking
Typo correction
Unicode / locale handling
Immutable index versions
Multi-region replication
Graceful degradation
Observability
```

A strong HLD answer should emphasize that autocomplete is mostly a **precomputation problem**. Do expensive aggregation and Top-K construction away from the request path, then make each keystroke lookup extremely cheap. Real-time trends and personalization should be overlays on top of a fast, reliable base index—not hard dependencies for serving.
