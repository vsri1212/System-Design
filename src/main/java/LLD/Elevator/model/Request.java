package Elevator.model;

public record Request(int floor, RequestType type) {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Request(int floor1, RequestType type1))) {
            return false;
        }

        return floor == floor1
                && type == type1;
    }

    @Override
    public String toString() {
        return "Request{" +
                "floor=" + floor +
                ", type=" + type +
                '}';
    }
}