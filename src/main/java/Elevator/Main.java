package Elevator;

import Elevator.model.RequestType;

import java.util.Arrays;

public class Main {

    static void main() {

        Elevator elevator1 = new Elevator(0);
        Elevator elevator2 = new Elevator(5);
        Elevator elevator3 = new Elevator(9);

        ElevatorController controller = new ElevatorController(
                                                Arrays.asList(
                                                        elevator1,
                                                        elevator2,
                                                        elevator3
                                                )
                                        );

        System.out.println("Initial state:");
        printElevators(controller);

        System.out.println("\nRequest: Floor 3 UP");

        Elevator assigned =
                controller.requestElevator(
                        3,
                        RequestType.PICKUP_UP
                );

        System.out.println(
                "Assigned elevator: " + assigned
        );

        System.out.println("\nRunning steps:");

        for (int i = 0; i < 6; i++) {

            controller.step();

            System.out.println(
                    "After step " + (i + 1) + ":"
            );

            printElevators(controller);
        }
    }

    private static void printElevators(
            ElevatorController controller) {

        for (Elevator elevator :
                controller.elevators()) {

            System.out.println(elevator);
        }
    }
}