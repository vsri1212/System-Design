# Design an elevator control system for a building. The system should handle multiple elevators, floor requests, and move elevators efficiently to service requests.

1. primary capabilities
2. error handling
3. scope boundaries

## Primary capabilities:

- How many elevators and floors? Fixed or configurable?
- Are Hall calls just up and down or do they choose a floor?
- Can passengers select multiple destination floors inside the
  elevator?
- The problem says cars should "move efficiently", what does this mean?

## Error handling:

- What about invalid floor requests?
- What if someone requests the floor they're already on?

## Scope boundaries:

- Capacity, weight limits, door mechanics, emergency stops?
- Simulation (step/tick) or actual hardware control software?


## Requirements:
1. System manages 3 elevators serving 10 floors (0-9)
2. Users can request an elevator from any floor (hall call). System decides which elevator to dispatch.
3. Once inside, users can select one or more destination floors
4. Simulation runs in discrete time steps (a step() call advances time)
5. Elevator stops come in two types:
    - Hall calls: Request from a floor with direction (UP or DOWN)
    - Destination: Request from inside elevator (no direction specified)
6. System handles multiple concurrent pickup requests across floors
7. Invalid requests should be rejected (return false)
    - Non-existent floor numbers
8. Requests for the current floor are treated as a no-op

## Out of scope:
- Weight capacity and passenger limits
- Door open/close mechanics
- Emergency stop functionality


### Entities:
- Elevator (class)
- Floor (number)
- Request (class, need direction and potentially the floor)
- ElevatorController (class)
  """

class ElevatorController:
- elevators: List<Elevators>

    + ElevatorController()
    + requestElevator(floor, direction) -> boolean
    + step() -> void


class Elevator:
- floor: int
- directin: Direction
- requests: Set<Requests>

    + Elevator()
    + addRequests(request) -> boolean
    + step() -> void
    + getFloor() -> int
    + getDirection() -> Direction
    + stop()


enum Direction:
UP
DOWN
IDLE


class Request:
"""
1. hall call: floor + direction
2. desitination: floor
"""
floor: int
type: RequestType

    + Request(floor, type)
    + getFloor() -> int
    + getType() -> RequestType



enum RequestType:
PICKUP_UP
PICKUP_DOWN
DESTINATION



## Implementation
 1. Define the core logic
 2. Consider the edge cases

 ElevatorController.requestElevator
 Elevator.step

    class ElevatorController:
    step():
    for e in elevators
    e.step()

    requestElevator(floor, type: PICKUP_UP | PICKUP_DOWN):
        """
        Core logic:
        1. find the best elevator to hindle this request
        2. send that request to the elevator
 
        Edge cases:
        1. Floor out of bounds -> throw error
        """
        if floor < 0 || floor > 9:
            throw Error ('Not a valid floor')
 
        request = Request(floor, type)
        best = selectBestElevator(request)
        return best.addRequests(request)
 
    private selectBestElevator(request):
        """
        Core logic:
        1. try to find the elevator moving toward 
        2. if none, try idle elevators (nearest)
        3. pick the nearest
        """
        best = findMovingToward(request)
        if best != null
            return best
 
        best = findNearestIdle(request.getFloor())
        if best != null
            return best
 
        return findNearest(request.getFloor())
 
 
    private findMovingToward(request):
        """
        Core logic:
        1. scan eleavtors going that direction
        2. keep track of closest
        3. return closest
        """
 
        floor = request.getFloor()
        direction = request.getType() == PICKUP_UP ? UP : DOWN 
 
        nearst = null
        minDistance = MAX_VALUE
 
        for e in elevators
            if e.getDirection() != direction
                continue
 
            if (direction == UP && e.getFloor() > floor) || (direction == DOWN && e.getFloor() < floor)
                continue
 
            distance = abs(e.getFloor() - floor)
            if distance < minDistance
                minDistance = distance
                nearest e 
 
        return nearest



class Elevator:
step():
"""
Core Logic:
1. if idle with requests, pick direction towrad the nearest request
2. Check if we should stop of the current floor (matches a hall call or a destination)
3. If no requests ahead of us (and other requests pending), reverse
4. Move on floor in current direction

        Edge cases:
        1. no requests -> set IDLE return
        2. Stop move cant happen is same tick and reverse and move 
        """
 
        # Case 1: Nothing to do
        if requests.isEmpty()
            direction = IDLE
            return
 
        # Case 2: Pick direction if idle
        if direction == IDLE
            nearest = findNearestRequest()
            direction = nearest.getFloor() > floor ? UP : DOWN
 
        # Case 3: Stop at current floor?
        type = direction == UP ? PICKUP_UP : PICKUP_DOWN
        hallCallRequest = Request(floor, type)
        destinationRequest = Request(floor, DESTINATION)
 
        if requests,contains(hallCallRequest) || requests.contains(destinationRequest)
            requests.remove(hallCallRequest)
            requests.remove(destinationRequest)
            stop()
            return 
 
        # Case 4: reverse if nothing ahead 
        if !hasRequestsAhead(direction)
            direction = direction == UP ? DOWN : UP 
 
        # Case 5: Move 
        if direction == UP
            floor++
        else if direction == DOWN
            floor --
 
    private findNearestRequest():
        """
        Core logic:
        1. Scan all requests, find closest by floor distance
 
        Edge cases:
        - ties broken arbitrarily
        """
        nearest = null
        minDistance = MAX_VALUE
 
        for req in requests
            distance = abs(req.getFloor() - floor)
            if distance < minDistance
                minDistance = distance
                nearest = req
 
        return nearest
 
 
    private hasRequestsAhead(dir):
        """
        Core logic:
        1. Check if ANY request exists ahead in the given direction
        2. Regardless of type—we travel toward all requests, only stop for matching ones
 
        Edge cases:
        - none, naturally handles floor boundaries (nothing above 9, nothing below 0)
        """
        for request in requests
            if dir == UP && request.getFloor() > currentFloor
                return true
            if dir == DOWN && request.getFloor() < currentFloor
                return true
        return false


## Extensions
"""
1. "What if multiple hall calls come in at the same time? What if a hall call comes in while step is running?"
2. "How would you add priority floors or an express elevator?"
3. "How would you add the ability to cancel a floor request?"
   """


## "What if multiple hall calls come in at the same time? What if a hall call comes in while step is running?"

requestElevator(floor, type)
lock.acquire()
...
lock.release()

step()
lock.acquire()
...
lock.release()


addRequest(request)
pendingRequests.enqueue(request)  // thread-safe queue

step()
while !pendingRequests.isEmpty()
activeRequests.add(pendingRequests.dequeue())
// all logic uses activeRequests
...


## How would you add priority floors or an express elevator?"

class Elevator:
isExpress: boolean
priorityFloors: [2,5,9]



## "How would you add the ability to cancel a floor request?"

removeRequest(request)
lock.acquire()
requests.remove(request)
lock.release()
 

