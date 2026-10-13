package Elevator;

import Elevator.model.Direction;
import Elevator.model.Request;
import Elevator.model.RequestType;

import java.util.ArrayList;
import java.util.List;

public class Elevator {

    private static final int MIN_FLOOR = 0;
    private static final int MAX_FLOOR = 9;

    private int floor;
    private Direction direction;
    private final List<Request> requests;

    public Elevator(int floor) {
        if (floor < MIN_FLOOR || floor > MAX_FLOOR) {
            throw new IllegalArgumentException("Not a valid floor");
        }

        this.floor = floor;
        this.direction = Direction.IDLE;
        this.requests = new ArrayList<>();
    }

    /**
     * Advances the elevator by one floor per call.
     */
    public void step() {

        // Case 1: Nothing to do
        if (requests.isEmpty()) {
            direction = Direction.IDLE;
            return;
        }

        // Case 2: Pick direction if idle
        if (direction == Direction.IDLE) {

            Request nearest = findNearestRequest();

            if (nearest.floor() > floor) {
                direction = Direction.UP;
            } else if (nearest.floor() < floor) {
                direction = Direction.DOWN;
            }
        }

        // Case 3: Stop at current floor?
        RequestType pickupType =
                direction == Direction.UP
                        ? RequestType.PICKUP_UP
                        : RequestType.PICKUP_DOWN;

        Request hallCallRequest =
                new Request(floor, pickupType);

        Request destinationRequest =
                new Request(floor, RequestType.DESTINATION);

        if (requests.contains(hallCallRequest)
                || requests.contains(destinationRequest)) {

            requests.remove(hallCallRequest);
            requests.remove(destinationRequest);

            stop();
            return;
        }

        // Case 4: Reverse if nothing is ahead
        if (!hasRequestsAhead(direction)) {

            direction = direction == Direction.UP
                    ? Direction.DOWN
                    : Direction.UP;
        }

        // Case 5: Move one floor
        if (direction == Direction.UP) {

            if (floor < MAX_FLOOR) {
                floor++;
            }

        } else if (direction == Direction.DOWN) {

            if (floor > MIN_FLOOR) {
                floor--;
            }
        }
    }

    /**
     * Adds a request to this elevator.
     */
    public void addRequest(Request request) {

        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }

        if (request.floor() < MIN_FLOOR
                || request.floor() > MAX_FLOOR) {
            throw new IllegalArgumentException("Not a valid floor");
        }

        requests.add(request);
    }

    /**
     * Finds the request closest to the current elevator floor.
     */
    private Request findNearestRequest() {

        Request nearest = null;
        int minDistance = Integer.MAX_VALUE;

        for (Request request : requests) {

            int distance =
                    Math.abs(request.floor() - floor);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = request;
            }
        }

        return nearest;
    }

    /**
     * Returns true if there is any request ahead
     * in the current direction.
     *
     * Important:
     * Request type does NOT matter here.
     * We travel toward all requests but only stop
     * when the request matches our direction.
     */
    private boolean hasRequestsAhead(Direction direction) {

        for (Request request : requests) {

            if (direction == Direction.UP
                    && request.floor() > floor) {
                return true;
            }

            if (direction == Direction.DOWN
                    && request.floor() < floor) {
                return true;
            }
        }

        return false;
    }

    /**
     * Represents the elevator stopping at its current floor.
     *
     * The actual movement is handled by step().
     * Returning from step() means we do not move during this tick.
     */
    private void stop() {
        // Elevator stops at current floor.
    }

    public int getFloor() {
        return floor;
    }

    public Direction getDirection() {
        return direction;
    }

    public List<Request> getRequests() {
        return new ArrayList<>(requests);
    }

    @Override
    public String toString() {
        return "Elevator{" +
                "floor=" + floor +
                ", direction=" + direction +
                ", requests=" + requests +
                '}';
    }
}