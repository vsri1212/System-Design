package Elevator;

import Elevator.model.Direction;
import Elevator.model.Request;
import Elevator.model.RequestType;

import java.util.List;

public record ElevatorController(List<Elevator> elevators) {

    private static final int MIN_FLOOR = 0;
    private static final int MAX_FLOOR = 9;

    public ElevatorController {

        if (elevators == null || elevators.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one elevator is required"
            );
        }

    }

    /**
     * Advances every elevator by one step.
     */
    public void step() {

        for (Elevator elevator : elevators) {
            elevator.step();
        }
    }

    /**
     * Requests an elevator from a floor.
     */
    public Elevator requestElevator(
            int floor,
            RequestType type) {

        // Edge case: invalid floor
        if (floor < MIN_FLOOR || floor > MAX_FLOOR) {
            throw new IllegalArgumentException(
                    "Not a valid floor"
            );
        }

        // Pickup requests only
        if (type != RequestType.PICKUP_UP
                && type != RequestType.PICKUP_DOWN) {
            throw new IllegalArgumentException(
                    "Elevator requests must be PICKUP_UP or PICKUP_DOWN"
            );
        }

        Request request =
                new Request(floor, type);

        Elevator best =
                selectBestElevator(request);

        best.addRequest(request);

        return best;
    }

    /**
     * Finds the best elevator for the request.
     * Priority:
     * 1. Elevator already moving toward the request
     * 2. Nearest idle elevator
     * 3. Nearest elevator overall
     */
    private Elevator selectBestElevator(Request request) {

        Elevator best =
                findMovingToward(request);

        if (best != null) {
            return best;
        }

        best =
                findNearestIdle(request.floor());

        if (best != null) {
            return best;
        }

        return findNearest(request.floor());
    }

    /**
     * Finds the closest elevator that is already
     * moving in the requested direction and has
     * not passed the requested floor.
     */
    private Elevator findMovingToward(Request request) {

        int floor = request.floor();

        Direction direction =
                request.type() == RequestType.PICKUP_UP
                        ? Direction.UP
                        : Direction.DOWN;

        Elevator nearest = null;
        int minDistance = Integer.MAX_VALUE;

        for (Elevator elevator : elevators) {

            // Elevator must be moving in requested direction
            if (elevator.getDirection() != direction) {
                continue;
            }

            // Elevator must not have passed the request floor
            if (direction == Direction.UP
                    && elevator.getFloor() > floor) {
                continue;
            }

            if (direction == Direction.DOWN
                    && elevator.getFloor() < floor) {
                continue;
            }

            int distance =
                    Math.abs(elevator.getFloor() - floor);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = elevator;
            }
        }

        return nearest;
    }

    /**
     * Finds the closest idle elevator.
     */
    private Elevator findNearestIdle(int floor) {

        Elevator nearest = null;
        int minDistance = Integer.MAX_VALUE;

        for (Elevator elevator : elevators) {

            if (elevator.getDirection() != Direction.IDLE) {
                continue;
            }

            int distance =
                    Math.abs(elevator.getFloor() - floor);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = elevator;
            }
        }

        return nearest;
    }

    /**
     * Finds the closest elevator regardless of direction.
     */
    private Elevator findNearest(int floor) {

        Elevator nearest = null;
        int minDistance = Integer.MAX_VALUE;

        for (Elevator elevator : elevators) {

            int distance =
                    Math.abs(elevator.getFloor() - floor);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = elevator;
            }
        }

        return nearest;
    }
}