package edu.eci.arsw.warehouse.core;

import edu.eci.arsw.warehouse.model.DeliveryRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe delivery registry.
 *
 * register() groups the read of nextPosition, its increment and the insertion
 * into `deliveries` inside a single critical region guarded by a private lock
 * (I2: arrival positions are unique). snapshot() uses the same lock so it never
 * observes a torn read while a robot is mid-registration.
 */
public class DeliveryRegistry {

    private final Object lock = new Object();
    private int nextPosition = 1;
    private final List<DeliveryRecord> deliveries = new ArrayList<>();

    public void register(int robotId, int parcelId, long elapsedMillis) {
        synchronized (lock) {
            int assignedPosition = nextPosition;
            nextPosition = nextPosition + 1;
            deliveries.add(new DeliveryRecord(assignedPosition, robotId, parcelId, elapsedMillis));
        }
    }

    public List<DeliveryRecord> snapshot() {
        synchronized (lock) {
            return List.copyOf(deliveries);
        }
    }
}
