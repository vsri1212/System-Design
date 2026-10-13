# Video Streaming System — High-Level Design

A senior/principal-level HLD case study for designing a YouTube/Netflix-style video platform with upload, transcoding, adaptive streaming, CDN delivery, recommendations, analytics, and multi-region resilience.

## 1. Problem Statement

Design a large-scale video streaming platform where users can:

- upload videos
- watch videos globally
- stream at different resolutions
- resume playback
- search/browse metadata
- receive recommendations
- see view/engagement counts
- support live or near-live extensions
- handle viral videos
- survive CDN/origin/transcoder failures

The main challenge is that video bytes are huge, playback is bandwidth-heavy, and processing is expensive. The system must separate metadata/control-plane operations from bulk media/data-plane operations.

---

## 2. Functional Requirements

Users should be able to:

- upload large video files
- pause/resume upload
- view upload/processing status
- stream published content
- choose/auto-select quality
- seek within video
- resume playback from last position
- like/comment/share
- search videos
- receive recommendations
- view subtitles/captions

Example APIs:

```http
POST /videos
POST /videos/{id}/upload-session
GET  /videos/{id}
GET  /videos/{id}/playback
POST /videos/{id}/progress
```

---

## 3. Non-Functional Requirements

- high availability
- massive global read scale
- very high aggregate bandwidth
- low startup latency
- low rebuffering ratio
- durable original uploads
- horizontally scalable processing
- resumable uploads
- graceful degradation
- cost efficiency
- multi-region delivery
- strong security/DRM controls where required

---

## 4. Control Plane vs Data Plane

This separation is fundamental.

Control plane:

```text
metadata
user auth
catalog
recommendations
playback authorization
upload session management
```

Data plane:

```text
large video uploads
video segments
CDN traffic
object storage
```

Do not send multi-GB video bytes through ordinary application servers if object storage can handle them directly.

---

## 5. High-Level Architecture

```text
Client
  |
  +--> API Gateway --> Metadata / Auth / Playback Services
  |
  +--> Direct Upload --> Object Storage
                          |
                          v
                     Upload Event
                          |
                          v
                    Transcoding Queue
                          |
                          v
                    Transcoder Fleet
                          |
                          v
                   Encoded Renditions
                          |
                          v
                    Object Storage
                          |
                          v
                         CDN
                          |
                          v
                        Viewer
```

---

## 6. Upload Flow

Bad design:

```text
Client -> App Server -> App Server buffers 5GB -> Storage
```

This wastes application-server bandwidth and memory.

Better:

```text
Client
  |
  | request upload session
  v
Upload Service
  |
  | signed upload URLs
  v
Client ----------------------> Object Storage
```

The backend remains responsible for authorization and metadata, but object storage carries the heavy bytes.

---

## 7. Multipart / Resumable Upload

Large uploads are unreliable on mobile and consumer networks.

Split file into parts:

```text
part 1
part 2
part 3
...
part N
```

Each chunk can be retried independently.

Benefits:

- resume after connection loss
- parallel upload
- smaller retry scope
- lower failure cost

Store upload-session state:

```text
uploadId
videoId
uploadedParts
checksum
expiresAt
```

---

## 8. Upload Idempotency

Clients may retry create/upload-session requests.

Use an idempotency key:

```text
creatorId + clientUploadId
```

Repeated requests should return the existing logical video/upload session instead of creating duplicates.

---

## 9. Original Object Storage

Persist the original upload durably before processing.

```text
raw/video123/original.mov
```

Why retain original?

- re-transcode with future codecs
- regenerate corrupted renditions
- new device profiles
- archival/legal requirements

Retention policy may vary for cost.

---

## 10. Async Processing Pipeline

After upload completes:

```text
Object Storage Event
       |
       v
Processing Orchestrator
       |
       v
Transcoding Queue
       |
       v
Worker Fleet
```

Do not block the upload request until all renditions are generated.

The user can receive:

```text
UPLOADED
PROCESSING
READY
FAILED
```

---

## 11. Transcoding

One source file may become many renditions:

```text
240p
360p
480p
720p
1080p
1440p
4K
```

Potential codecs:

```text
H.264 / AVC
H.265 / HEVC
VP9
AV1
```

Codec trade-offs:

- compression efficiency
- encode CPU cost
- decode/device support
- licensing
- storage and bandwidth cost

There is no single universally correct codec.

---

## 12. Transcoding Parallelism

A long video can be segmented and transcoded in parallel.

```text
Video
  |
  +--> segment 1 -> worker A
  +--> segment 2 -> worker B
  +--> segment 3 -> worker C
```

Then package outputs into streaming manifests.

This shortens processing latency but adds orchestration complexity.

---

## 13. Processing Idempotency

Workers may retry after crashes.

Each processing task needs a deterministic identity:

```text
videoId + rendition + segmentNumber + codecVersion
```

If output already exists and checksum/version matches, the task can safely skip or overwrite atomically.

---

## 14. Retry and DLQ for Transcoding

Transient failures:

- worker crash
- temporary storage error
- capacity shortage

Permanent failures:

- corrupt source
- unsupported format

Use bounded retries and a DLQ/manual-recovery path.

Do not retry corrupt input forever.

---

## 15. HLS / DASH

Instead of serving one huge MP4, package video into small segments.

Example:

```text
manifest.m3u8
segment001.ts
segment002.ts
segment003.ts
```

Protocols:

- HLS
- MPEG-DASH

The manifest lists available renditions and segments.

This enables adaptive bitrate streaming and efficient seeking.

---

## 16. Adaptive Bitrate Streaming

The client dynamically chooses quality based on:

- measured bandwidth
- buffer level
- device capabilities
- screen size

Example:

```text
good network -> 1080p
network drops -> 480p
network recovers -> 720p/1080p
```

Goal:

> Minimize rebuffering while maximizing perceived quality.

---

## 17. Segment Size Trade-Off

Small segments:

- faster adaptation
- lower startup latency
- more HTTP requests/metadata overhead

Large segments:

- fewer requests
- slower adaptation
- potentially larger wasted downloads during seek/quality switch

Choose based on latency and CDN efficiency goals.

---

## 18. Playback Flow

```text
Viewer
  |
  v
Playback API
  |
  | auth / entitlement / region policy
  v
Return manifest URL + token
  |
  v
Viewer -> CDN -> video segments
```

The application server should not proxy every video segment.

---

## 19. CDN

CDN is essential for global video scale.

Without CDN:

```text
millions of viewers -> origin storage
```

With CDN:

```text
Viewer -> nearby edge cache
              |
              | miss
              v
          regional/origin shield
              |
              v
          object storage
```

Benefits:

- lower latency
- lower origin load
- bandwidth offload
- better regional scalability

---

## 20. CDN Cache Key

Typical segment identity:

```text
videoId / rendition / segment
```

Example:

```text
/video123/1080p/seg009.m4s
```

Immutable versioned media objects are highly CDN-friendly.

Avoid mutating existing segment URLs when content changes; publish a new version.

---

## 21. Origin Shield

If thousands of CDN edges miss simultaneously for a new viral video, origin storage can be hammered.

Use an origin-shield layer:

```text
Edges
  |
  v
Regional Shield
  |
  v
Object Storage
```

This coalesces misses and reduces origin amplification.

---

## 22. Hot Video Problem

A viral video may suddenly receive millions of concurrent viewers.

Mitigations:

- CDN edge caching
- origin shield
- pre-warm popular content
- multi-CDN routing
- immutable segment URLs
- request coalescing

The goal is for origin request rate to grow much slower than viewer count.

---

## 23. Metadata Storage

Video metadata is much smaller than video bytes.

Example:

```text
video_id
owner_id
title
description
status
duration
created_at
visibility
thumbnail_refs
manifest_ref
```

This can live in relational or distributed metadata storage depending on scale.

---

## 24. Search

Metadata/search indexing pipeline:

```text
Video Metadata
    |
    v
Kafka/Event Bus
    |
    v
Search Indexer
    |
    v
Search Engine
```

Search should not scan the transactional metadata DB.

---

## 25. Thumbnails

Generate thumbnails asynchronously during processing.

```text
video -> frames -> thumbnail variants
```

Store in object storage/CDN.

---

## 26. Subtitles / Captions

Sources:

- creator upload
- speech-to-text pipeline
- manual editing

Store caption tracks separately from video bytes.

The manifest/player can select by language.

---

## 27. View Counts

Do not update one DB row synchronously for every playback event on a viral video.

Better:

```text
Playback Events
    |
    v
Kafka
    |
    v
Stream Aggregation
    |
    v
Counter Store / Analytics DB
```

Counts can be eventually consistent.

---

## 28. Exactly What Counts as a View?

Product rule matters.

Possible criteria:

- playback started
- watched >= 10 seconds
- watched >= X%
- authenticated unique viewer

HLD should separate event collection from product-specific aggregation logic.

---

## 29. Playback Progress

Store:

```text
(userId, videoId) -> lastPosition
```

Progress writes are high frequency and can be coalesced.

Do not persist every second.

Client may send periodically:

```text
position = 1832 sec
```

Last-write-wins is often acceptable.

---

## 30. Recommendations

A simplified recommendation pipeline:

```text
Playback / Search / Like Events
            |
            v
         Kafka
            |
     +------+------+
     |             |
     v             v
Offline Training  Streaming Features
     |             |
     +------+------+
            v
      Candidate Generation
            |
            v
         Ranking
            |
            v
     Recommendation API
```

Keep recommendation failure isolated from basic playback.

---

## 31. Recommendation Degradation

If ranking service fails:

Do not make home page entirely unavailable.

Fallbacks:

- trending list
- cached recommendations
- recently watched
- popular-by-region

This is graceful degradation.

---

## 32. Authentication and Authorization

Playback API may validate:

- logged-in user
- subscription tier
- content visibility
- age/region restrictions
- rental/purchase rights

The CDN needs a way to enforce authorization without calling the auth service for every segment.

Use short-lived signed URLs/cookies/tokens.

---

## 33. Signed Playback URLs

Example concept:

```text
https://cdn.example/video123/master.m3u8?token=...
```

Token includes:

- content ID
- expiry
- user/session scope
- signature

The edge validates the token locally.

---

## 34. DRM

Premium content may require DRM.

Typical architecture includes:

```text
Encrypted media
+
License Service
+
Client DRM module
```

Examples of ecosystems include Widevine/FairPlay/PlayReady.

At HLD level, discuss the license path and key separation; do not try to invent cryptography.

---

## 35. Malware / Content Safety

Creator uploads may require:

- malware scanning
- policy moderation
- copyright/fingerprint checks
- metadata abuse checks

These can run asynchronously before publication or in parallel depending on policy.

---

## 36. Multi-Region Upload

Creator should upload to a nearby region.

```text
Creator India -> India ingest
Creator US    -> US ingest
```

Original objects can replicate asynchronously to other regions/archive storage.

Processing can occur near ingest or in dedicated compute regions.

---

## 37. Multi-Region Playback

```text
Viewer
  |
  v
Global DNS / Traffic Manager
  |
  v
Nearest healthy CDN edge
```

Most playback traffic should terminate at the CDN rather than application regions.

Control-plane APIs remain regionally distributed.

---

## 38. Multi-CDN

A large platform may use multiple CDN providers.

Benefits:

- failover
- regional performance differences
- commercial leverage
- capacity isolation

Traffic steering can use:

- DNS
- client-side manifest selection
- edge routing layer

---

## 39. CDN Failure

Suppose CDN A has a regional outage.

Do not route every viewer directly to object storage.

That may melt origin.

Better:

```text
CDN A failure
  -> traffic manager shifts bounded traffic to CDN B
  -> preserve origin protection
```

Use controlled ramp-up to avoid overloading the backup CDN.

---

## 40. Origin Failure

If object storage/origin becomes unavailable but CDN still has cached segments:

- existing cached content can continue playing
- cache misses fail

Possible protection:

- replicated origin buckets
- regional origins
- origin failover
- high cache retention for immutable popular assets

---

## 41. Cache Eviction Strategy

Video content is large.

CDN cannot keep everything forever.

Popularity is highly skewed.

Cache policies naturally favor:

- recent content
- viral content
- regionally popular content

Long-tail content may miss and fetch from origin.

---

## 42. Storage Tiering

Not all videos need premium storage indefinitely.

Possible tiers:

```text
hot
warm
cold/archive
```

Old long-tail originals may move to cheaper storage while active renditions remain accessible.

Trade-off: restore latency vs cost.

---

## 43. Deletion

Video deletion touches multiple systems:

- metadata
- search index
- recommendation candidates
- CDN cache
- encoded renditions
- original storage
- analytics references

Use an event-driven deletion workflow.

Strong immediate guarantee may be applied at authorization/metadata layer while physical cleanup happens asynchronously.

---

## 44. Consistency

Stronger consistency desirable for:

- publish state
- entitlement/access rights
- deletion/visibility

Eventual consistency acceptable for:

- view counts
- likes
- search indexing
- recommendations

---

## 45. Live Streaming Extension

Live streaming differs from VOD because content is produced while being consumed.

Possible flow:

```text
Creator ingest
   |
   v
Live Transcoder
   |
   v
Segmenter
   |
   v
CDN
   |
   v
Viewers
```

Latency may range from seconds to sub-second depending on protocol/design.

---

## 46. Backpressure in Processing

Suppose uploads spike:

```text
100K hours of video uploaded/hour
```

but transcoders can process only:

```text
70K hours/hour
```

Queue backlog grows.

Use:

- autoscaling worker pools
- priority queues
- creator-tier priority
- admission control
- delayed noncritical renditions
- per-tenant quotas

Do not let backlog grow without retention/capacity limits.

---

## 47. Priority Transcoding

Possible priority:

```text
premium/live-critical
public recent uploads
backfill new codec versions
archive regeneration
```

Separate queues prevent backfill work from delaying newly uploaded content.

---

## 48. Cost-Aware Transcoding

Generating every codec/resolution for every video may be wasteful.

Possible adaptive policy:

- always generate baseline renditions
- generate expensive codecs for popular content
- generate 4K only if source/device demand justifies it

This trades processing/storage cost against playback efficiency.

---

## 49. Observability

Key upload metrics:

```text
upload_success_rate
upload_resume_rate
upload_latency
processing_queue_lag
transcode_failure_rate
processing_time
```

Playback metrics:

```text
startup_time
rebuffer_ratio
average_bitrate
playback_error_rate
CDN_cache_hit_rate
origin_fetch_rate
segment_latency
```

Business/quality metrics:

```text
watch_time
completion_rate
view_count_lag
recommendation_ctr
```

---

## 50. Capacity Planning

Example assumptions:

```text
100M daily viewers
60 minutes watched/viewer/day
average bitrate = 3 Mbps
```

Bandwidth dominates cost.

Rough aggregate average streaming bandwidth:

```text
100M * 3600 sec/day * 3 Mb/sec
```

The point of the interview estimate is to conclude:

> Origin-only delivery is impossible at this scale; CDN offload is mandatory.

Also estimate:

- upload volume/day
- transcoding CPU/GPU hours
- encoded storage multiplier
- CDN egress
- metadata QPS

---

## 51. Production-Grade Architecture

```text
                            Clients
                  +------------+------------+
                  |                         |
                  v                         v
             Control APIs               Upload Path
                  |                         |
                  v                         v
             API Gateway             Upload Service
                  |                         |
          +-------+-------+                 |
          |       |       |                 | signed URLs
          v       v       v                 v
      Metadata  Auth  Recommendation   Object Storage (Raw)
          |                                  |
          |                                  v
          |                           Processing Events
          |                                  |
          |                                  v
          |                          Transcoding Queue
          |                                  |
          |                            +-----+-----+
          |                            |           |
          |                            v           v
          |                       Worker Fleet  Retry/DLQ
          |                            |
          |                            v
          |                      Encoded Storage
          |                            |
          |                            v
          +--------------------------> CDN / Multi-CDN
                                       |
                                       v
                                     Viewer

Events -> Kafka -> Analytics / View Aggregation / Search / ML Features
```

---

## 52. Principal Engineer Failure Scenario

Assume a globally awaited premiere starts.

Within 3 minutes:

```text
25M concurrent viewers
```

At the same time:

```text
CDN A in Asia has 40% packet loss
origin shield latency rises
recommendation service is timing out
```

A weak design may do this:

```text
CDN misses/failure
-> viewers retry
-> traffic falls through to origin
-> origin saturates
-> segment failures increase
-> clients retry more aggressively
-> control APIs also slow
-> global outage
```

Principal-level response:

1. multi-CDN traffic shifting
2. strict origin protection; never unlimited direct-origin fallback
3. origin shield/request coalescing
4. signed CDN URLs remain locally verifiable
5. recommendation service circuit breaker
6. serve cached/trending home page if recommendations fail
7. prioritize playback control APIs over noncritical analytics
8. client retry backoff + jitter
9. CDN health-based regional steering
10. pre-warm premiere segments/manifests
11. cache immutable assets aggressively
12. protect origin with admission controls and rate limits
13. degrade max bitrate if regional bandwidth/capacity is constrained
14. keep analytics asynchronous
15. controlled recovery/ramp-up after CDN region returns

Core principle:

> Playback availability must not depend on recommendation, analytics, or even one CDN provider. The data plane should degrade independently and protect origin at all costs.

---

## 53. Interview Follow-Up Questions

Be prepared for:

- Why direct-to-object-storage upload?
- How do resumable uploads work?
- Why keep the original file?
- How do you make transcoding idempotent?
- HLS vs DASH?
- How does adaptive bitrate work?
- Why CDN?
- What is an origin shield?
- How do you handle a viral video?
- What if CDN goes down?
- Why not fall back directly to origin?
- How do you count views at scale?
- How do you store playback progress?
- How do recommendations integrate without hurting playback?
- How do signed URLs/DRM work at a high level?
- How do you process deletes?
- How do you support multi-region uploads/playback?
- How do you prioritize transcoding backlogs?
- How do you reduce video infrastructure cost?

---

## 54. Interview Summary

The transferable concepts in this design are:

```text
Control plane vs data plane
Direct uploads
Multipart/resumable upload
Object storage
Async processing
Transcoding
Idempotent workers
Retry/DLQ
HLS/DASH
Adaptive bitrate
CDN
Origin shield
Hot content
Multi-CDN
Signed URLs
DRM
Stream aggregation
Recommendations
Graceful degradation
Storage tiering
Backpressure
Priority queues
Multi-region architecture
Observability
Cost optimization
```

A strong HLD answer should emphasize that the dominant constraints are bandwidth, storage, and media-processing cost. Application servers orchestrate the system; object storage, transcoders, and CDNs move and serve the heavy data.
