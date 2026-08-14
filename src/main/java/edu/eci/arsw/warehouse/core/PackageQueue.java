package edu.eci.arsw.warehouse.core;

import edu.eci.arsw.warehouse.model.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe package queue.
 *
 * takeNext() groups the check (isEmpty), the read (get(0)) and the removal
 * (remove(0)) into a single critical region guarded by a private lock, so no
 * other robot can observe or mutate `pending` between those steps (I1: each
 * parcel is processed at most once).
 */
public class PackageQueue {

    private final Object lock = new Object();
    private final List<Parcel> pending = new ArrayList<>();

    public PackageQueue(List<Parcel> parcels) {
        pending.addAll(parcels);
    }

    public Parcel takeNext() {
        synchronized (lock) {
            if (pending.isEmpty()) {
                return null;
            }

            Parcel selected = pending.get(0);
            Thread.yield();

            pending.remove(0);
            return selected;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }
}
