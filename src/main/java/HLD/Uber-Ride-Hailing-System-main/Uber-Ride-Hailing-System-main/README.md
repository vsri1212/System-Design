# Uber / Lyft Ride-Hailing System — High-Level Design

A senior/principal-level HLD case study for designing a global ride-hailing platform with real-time driver locations, geospatial search, rider-driver matching, trip state management, surge pricing, payments, and multi-region resilience.

## 1. Problem Statement

Design a system that allows riders to:

- request a ride
- see nearby drivers
- receive ETA/fare estimates
- get matched with exactly one driver
- track the driver in real time
- start/complete/cancel trips
- pay for rides

Drivers should be able to:

- go online/offline
- continuously publish location
- receive ride offers
- accept/reject offers
- navigate to pickup/drop-off
- complete trips

The difficult parts are geospatial indexing, millions of location updates, low-latency matching, avoiding double assignment, resilient state transitions, and operating the system across regions.

---

## 2. Functional Requirements

Core APIs/events:

```http
POST /rides/estimate
POST /rides
POST /rides/{rideId}/cancel
POST /drivers/{driverId}/availability
POST /drivers/{driverId}/location
POST /rides/{rideId}/accept
POST /rides/{rideId}/start
POST /rides/{rideId}/complete
GET  /rides/{rideId}
```

Example ride request:

```json
{
  "riderId": "r123",
  "pickup": {"lat": 17.385, "lon": 78.4867},
  "dropoff": {"lat": 17.4401, "lon": 78.3489},
  "vehicleType": "CAB"
}
```

---

## 3. Non-Functional Requirements

- low latency nearby-driver lookup
- high availability
- horizontally scalable location ingestion
- reliable ride state transitions
- no double assignment of drivers
- idempotent APIs/events
- bounded matching latency
- regional isolation
- graceful degradation
- strong payment correctness
- observability

---

## 4. High-Level Architecture

```text
Rider App                  Driver App
   |                           |
   v                           v
API Gateway              Location Gateway
   |                           |
   v                           v
Ride Service             Location Service
   |                           |
   |                           v
   |                    Geospatial Index
   |                           |
   +----------+----------------+
              |
              v
         Matching Service
              |
              v
         Driver Offer Bus
              |
              v
          Driver App

Ride Events -> Kafka -> Pricing / ETA / Analytics / Notifications / Payments
```

---

## 5. Why Geospatial Indexing?

A naive query like:

```text
SELECT all online drivers
then calculate distance
```

is impossible at large scale.

We need to quickly answer:

```text
find available drivers within radius R of point P
```

This requires spatial partitioning.

---

## 6. Geohash

Geohash maps latitude/longitude into hierarchical cells.

Example:

```text
17.385, 78.4867 -> geohash cell
```

Nearby points often share prefixes.

Advantages:

- simple key-based partitioning
- easy neighborhood expansion
- works well with KV/Redis-style structures

Challenges:

- cell boundaries
- uneven density
- latitude distortion

---

## 7. Quadtree

A quadtree recursively splits geographic space:

```text
World
  |
  +-- NW
  +-- NE
  +-- SW
  +-- SE
```

Dense areas can be subdivided further.

Advantages:

- adaptive to density
- efficient spatial search

Challenges:

- more complex updates
- distributed ownership and rebalancing are harder

---

## 8. S2 / H3-Style Cell Systems

Production systems may use hierarchical spherical cells rather than plain Geohash.

Useful properties:

- globally consistent tiling
- hierarchical resolution
- neighbor lookup
- spatial aggregation

In interviews, the key is not memorizing one technology. Explain the trade-off among simplicity, density handling, and boundary effects.

---

## 9. Driver Location Ingestion

Drivers may update every few seconds.

Example:

```text
5M online drivers
1 update / 3 sec
≈ 1.67M location updates/sec
```

Do not write every location update into the primary ride database.

Use a specialized high-write path:

```text
Driver App
   |
   v
Location Gateway
   |
   v
Location Service
   |
   +--> Geospatial Index
   +--> Kafka (optional history/analytics)
```

---

## 10. Ephemeral vs Durable Location

Current driver location is ephemeral operational state.

Store latest location in fast distributed memory/KV/geospatial storage.

Historical GPS traces can be streamed asynchronously into durable analytics/storage.

Do not make historical storage part of the matching critical path.

---

## 11. Location Update Model

Example state:

```text
driverId
lat
lon
cellId
updatedAt
availability
tripId?
```

Use TTLs so stale drivers naturally disappear from nearby-driver queries.

Example:

```text
if no update for 30 sec -> treat driver unavailable/stale
```

---

## 12. Nearby Driver Query

At pickup point:

```text
1. identify pickup cell
2. query drivers in cell
3. expand to neighboring cells
4. filter by freshness/availability
5. calculate precise distance/ETA for candidates
6. rank top N
```

This avoids scanning every driver.

---

## 13. Why Cell Search Is Approximate

Spatial index narrows candidates, but final ranking should use precise distance/road ETA.

Cell proximity != actual travel time.

A driver 1 km away across a river may be slower than one 2 km away on the same road network.

Therefore:

```text
Geospatial index -> coarse candidate generation
Routing/ETA       -> final ranking
```

---

## 14. Matching Flow

```text
Rider requests ride
      |
      v
Ride Service creates REQUESTED ride
      |
      v
Matching Service queries nearby drivers
      |
      v
Rank candidate drivers
      |
      v
Send offers
      |
      v
First valid acceptance wins
      |
      v
Ride becomes MATCHED
```

---

## 15. Sequential vs Batched Offers

Sequential:

```text
Offer D1 -> wait
Offer D2 -> wait
Offer D3
```

Pros:

- fewer simultaneous conflicts

Cons:

- higher matching latency

Batch/race:

```text
Offer top 3 drivers simultaneously
```

Pros:

- low latency

Cons:

- multiple drivers may accept
- requires atomic winner selection

---

## 16. Double Assignment Problem

Suppose Driver D1 receives offers for Ride A and Ride B.

Both riders attempt to reserve the same driver.

We need a strong transition:

```text
AVAILABLE -> RESERVED
```

that only one ride can win.

---

## 17. Atomic Driver Reservation

Possible approaches:

- compare-and-set/versioned update
- database conditional write
- transactional row lock
- Redis atomic script for ephemeral reservation
- lease-based reservation

Example:

```text
UPDATE driver
SET state='RESERVED', ride_id='R123'
WHERE driver_id='D9' AND state='AVAILABLE'
```

Affected rows = 1 means success.

Affected rows = 0 means another ride already won.

---

## 18. Why Distributed Locks Are Not Always Necessary

Do not automatically reach for a distributed lock.

If one atomic conditional write can enforce the invariant, it is often simpler and safer.

Use distributed locks only when the critical section spans multiple independently stored resources and you cannot model the invariant through atomic state transitions.

---

## 19. Reservation Lease

A driver offer may expire.

Example:

```text
reservation lease = 15 sec
```

If driver does not accept in time:

```text
RESERVED -> AVAILABLE
```

Leases avoid permanently stuck drivers.

---

## 20. Fencing / Version Tokens

Stale workers may act after lease expiry.

Assign a monotonically increasing reservation version:

```text
reservationVersion = 9271
```

Downstream updates must carry the current version.

Older versions are rejected.

This prevents stale ownership from mutating newer state.

---

## 21. Ride State Machine

Example:

```text
REQUESTED
   |
   v
SEARCHING
   |
   v
MATCHED
   |
   v
DRIVER_ARRIVING
   |
   v
DRIVER_ARRIVED
   |
   v
IN_PROGRESS
   |
   v
COMPLETED
```

Other transitions:

```text
REQUESTED -> CANCELLED
SEARCHING -> NO_DRIVER_FOUND
MATCHED -> CANCELLED
```

State transitions should be validated explicitly.

---

## 22. Ride State Versioning

Use optimistic concurrency:

```text
ride_id
state
version
```

Update:

```text
WHERE ride_id=? AND version=?
```

This prevents concurrent services from overwriting one another blindly.

---

## 23. Idempotency

Network retries are common.

Example:

```text
POST /rides
Idempotency-Key: rider123-request456
```

Repeated request should return the same ride rather than creating duplicates.

Also apply idempotency to:

- accept ride
- cancel ride
- start ride
- complete ride
- payment capture

---

## 24. ETA Service

ETA depends on road graph and traffic, not just straight-line distance.

Inputs:

```text
origin
destination
road network
traffic state
time of day
```

The ETA service should be isolated from core ride state so routing degradation does not corrupt trip correctness.

---

## 25. ETA Degradation

If ETA service is slow:

- use cached regional travel-time estimates
- use straight-line fallback estimate with conservative adjustment
- degrade precision instead of blocking ride creation indefinitely

This is graceful degradation.

---

## 26. Surge Pricing

Surge depends on local demand/supply.

Example:

```text
cell C17:
ride requests = 500/min
available drivers = 100
```

A pricing pipeline can compute regional multipliers.

```text
Location/ride events -> Kafka -> stream aggregation -> surge state
```

---

## 27. Surge Consistency

Surge multiplier need not be globally strongly consistent to the millisecond.

Use versioned pricing snapshots.

When rider requests an estimate:

```text
quoteId
surgeVersion
price
expiresAt
```

The quote should be honored according to product policy even if surge changes seconds later.

---

## 28. Fare Estimate

Fare may combine:

```text
base fare
+ estimated distance
+ estimated duration
+ surge multiplier
+ fees/tolls
```

The estimate and final fare are distinct objects.

---

## 29. Real-Time Rider Tracking

After matching:

```text
Driver GPS -> Location Service -> event stream -> rider channel
```

Use WebSockets or push streams for active trip tracking.

Do not require the rider app to poll every second.

---

## 30. Connection Management

Active riders/drivers maintain regional real-time connections.

Possible mapping:

```text
userId/deviceId -> gatewayId
```

Use an ephemeral connection registry similar to chat systems.

---

## 31. Kafka/Event Bus

Useful ride events:

```text
RIDE_REQUESTED
DRIVER_RESERVED
DRIVER_ACCEPTED
RIDE_STARTED
LOCATION_UPDATED
RIDE_COMPLETED
PAYMENT_REQUESTED
PAYMENT_CAPTURED
```

Consumers:

- notifications
- analytics
- pricing
- fraud
- payments
- support systems

---

## 32. Transactional Outbox

Suppose Ride Service updates:

```text
state = COMPLETED
```

then crashes before publishing `RIDE_COMPLETED`.

Payment may never happen.

Use outbox:

```text
DB transaction:
  update ride state
  insert outbox event

Outbox publisher -> Kafka
```

This avoids dual-write inconsistency.

---

## 33. Payment Flow

Ride completion should not synchronously depend on every payment provider step.

Example:

```text
Ride COMPLETED
   |
   v
Payment Requested Event
   |
   v
Payment Service
   |
   v
Provider
```

Payment state is separate from ride state.

---

## 34. Payment Idempotency

Never charge twice because Kafka redelivered an event.

Key:

```text
rideId + paymentAction
```

Payment service stores idempotency status before or atomically with processing according to provider semantics.

---

## 35. Driver Disconnect During Matching

A candidate may appear online but disconnect before receiving offer.

Use freshness filters:

```text
lastLocationUpdate < threshold
connection alive
availability = true
```

If offer times out, move immediately to next candidate.

---

## 36. Driver Disconnect During Trip

Trip correctness should not depend on a continuous socket.

If driver loses connectivity:

- trip remains durable
- local app buffers GPS/events where safe
- reconnect resumes from server state
- payment/ride state transitions remain idempotent

Real-time visibility may degrade temporarily, but trip state must survive.

---

## 37. Rider Cancellation Race

Race:

```text
Rider cancels
Driver accepts at same time
```

Need one serialized/atomic ride-state transition.

Example allowed transitions:

```text
SEARCHING -> CANCELLED
SEARCHING -> MATCHED
```

Only one should succeed using version/CAS semantics.

---

## 38. Driver Acceptance Race

If top 3 drivers receive the offer simultaneously:

```text
D1 accepts
D2 accepts
D3 accepts
```

Matching service must select one winner atomically.

Losers receive:

```text
OFFER_EXPIRED / ALREADY_MATCHED
```

---

## 39. Location Sharding

Natural partition key:

```text
cellId / regionId
```

This keeps nearby location state colocated.

But downtown areas can be extremely hot.

Mitigations:

- finer cells
- split hot cells into buckets
- replicate read-heavy cells
- adaptive spatial partitioning

---

## 40. Hot Cell Problem

Airport/stadium/concert:

```text
100K drivers/riders in a tiny area
```

One cell shard can overload.

Use adaptive partitioning:

```text
C17 -> C17-A, C17-B, C17-C...
```

Then merge candidate results.

---

## 41. Supply-Demand Aggregation

Per-cell metrics:

```text
available drivers
ride requests
acceptance rate
cancel rate
ETA distribution
```

Use streaming aggregation for surge, heatmaps, and capacity planning.

---

## 42. Multi-Region Design

Ride-hailing is naturally geography-bound.

```text
Global DNS
   |
   +--> India Region
   +--> US Region
   +--> EU Region
```

Trips should normally remain within one operational region.

This simplifies latency and consistency.

---

## 43. Regional Ownership

Possible ownership:

```text
city/metro -> region cluster
```

Hyderabad rides stay in the India operational region.

Location, matching, ride state, and pricing are handled locally.

Global systems aggregate analytics/account information asynchronously.

---

## 44. Cross-Region User Travel

A user from India visiting the US connects to the nearest active region.

Account/profile data can replicate globally, but active ride state belongs to the current trip region.

---

## 45. Region Failure

If the ride region fails during an active trip:

- traffic shifts to secondary region
- ride state must be replicated according to required RPO
- stale location data may be tolerated temporarily
- matching can be paused/degraded during failover

Do not attempt unsafe matching if current driver ownership cannot be established.

---

## 46. Consistency Choices

Stronger consistency for:

- driver reservation
- ride state transitions
- payment operations
- cancellation/acceptance races

Eventual consistency acceptable for:

- heatmaps
- driver historical location
- analytics
- surge aggregates within bounded windows

---

## 47. Fraud and Safety

Useful asynchronous signals:

- impossible GPS jumps
- spoofed driver location
- abnormal cancellation patterns
- payment fraud
- route deviation

Do not put heavy fraud scoring directly in the lowest-latency location ingestion path unless required for safety.

---

## 48. Security

- authenticated rider/driver APIs
- device/session validation
- TLS
- PII encryption
- signed driver identity/session
- strict authorization on ride state changes
- anti-replay/idempotency
- payment tokenization
- location privacy controls

---

## 49. Observability

Key metrics:

```text
location_updates_per_sec
location_ingest_latency
stale_driver_rate
nearby_query_latency
candidate_count
match_latency
match_success_rate
driver_accept_rate
ride_state_conflict_rate
surge_compute_lag
ETA_latency
payment_failure_rate
regional_hot_cell_rate
```

Track p50/p95/p99.

---

## 50. Capacity Estimation

Example assumptions:

```text
10M online drivers
location update every 4 sec
= 2.5M updates/sec
```

If 5M riders open the app during peak and each requests nearby drivers every 10 sec:

```text
500K nearby queries/sec
```

The key conclusion is:

> Current location data must use a specialized partitioned in-memory/geospatial serving path. A normal transactional database cannot be the critical-path lookup engine for every GPS update and nearby query.

---

## 51. Production-Grade Architecture

```text
                         Global DNS
                             |
                    Regional Entry Point
                             |
         +-------------------+-------------------+
         |                                       |
         v                                       v
     Rider API                              Driver Gateway
         |                                       |
         v                                       v
    Ride Service                          Location Service
         |                                       |
         |                                       v
         |                                Geospatial Index
         |                                       |
         +-------------------+-------------------+
                             |
                             v
                       Matching Service
                             |
                 +-----------+-----------+
                 |                       |
                 v                       v
          Driver Reservation        ETA / Routing
                 |
                 v
            Ride State Store
                 |
                 v
               Outbox
                 |
                 v
               Kafka
      +----------+----------+----------+----------+
      |          |          |          |          |
      v          v          v          v          v
 Pricing   Notifications  Payment   Analytics   Fraud

Driver/Rider realtime updates -> WebSocket Gateway Fleet
```

---

## 52. Principal Engineer Failure Scenario

Assume a major concert ends.

Within two minutes:

```text
200K riders request rides
80K drivers enter the same metro zone
location update rate triples
matching latency spikes
ETA service p99 rises from 100 ms to 4 sec
Kafka lag increases
```

A weak system can collapse through:

```text
hot geospatial cell
-> slow nearby queries
-> matching retries
-> driver offer storms
-> ETA saturation
-> stale reservations
-> duplicate matching attempts
-> cascading overload
```

Principal-level response:

1. split hot spatial cells adaptively
2. cap candidate-set size
3. degrade ETA precision under overload
4. keep geospatial candidate generation independent of routing service
5. use bounded driver-offer batches
6. atomic reservation/CAS for driver ownership
7. short offer leases
8. jitter matching retries
9. protect critical matching pools with bulkheads
10. shed nonessential map-refresh/heatmap traffic
11. scale Kafka consumers only where downstream capacity exists
12. isolate analytics from ride critical path
13. temporarily reduce location update frequency for slow-moving/stationary drivers
14. cache route/ETA approximations for dense areas
15. maintain per-cell admission control
16. recover gradually rather than releasing all queued requests simultaneously

Core principle:

> A ride-hailing system is a real-time allocation system. Correct driver ownership matters more than perfect ETA precision during overload; degrade enrichment before compromising matching correctness.

---

## 53. Interview Follow-Up Questions

Be prepared for:

- Geohash vs Quadtree vs S2/H3?
- How do you ingest millions of location updates/sec?
- Why not store every GPS update in SQL?
- How do you find nearby drivers?
- How do you handle cell boundaries?
- How do you rank drivers?
- What if two riders select the same driver?
- Do you need distributed locks?
- How do reservation leases work?
- How do you handle rider cancel vs driver accept races?
- How does surge pricing work?
- How do you keep ETA failures from breaking matching?
- How do you handle hot cells like airports?
- How do driver disconnects work?
- How do you make payments idempotent?
- How do you use Kafka/outbox?
- How do you design regional ownership?
- What happens during a region failure?

---

## 54. Interview Summary

The transferable concepts in this design are:

```text
Geospatial indexing
Geohash
Quadtree
S2/H3-style cells
High-frequency location ingestion
TTL-based ephemeral state
Nearby-neighbor search
Candidate generation
ETA/routing
Atomic reservation
Compare-and-set
Leases
Fencing/version tokens
State machines
Idempotency
Kafka
Transactional outbox
Surge pricing
Streaming aggregation
WebSockets
Hot-cell partitioning
Multi-region ownership
Payment correctness
Graceful degradation
Bulkheads
Observability
```

A strong HLD answer should separate three concerns clearly: real-time location serving, strongly controlled ride ownership/state transitions, and asynchronous downstream processing. Location can be approximate and eventually refreshed; driver assignment and payment cannot be sloppy.
