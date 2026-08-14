package edu.eci.arsw.warehouse.core;

/**
 * Thread-safe processed-parcel counters.
 *
 * recordProcessed() updates processedParcels and totalProcessingMillis inside
 * a single critical region guarded by a private lock, so both counters advance
 * atomically together (I3: processed count must match the registry size).
 */
public class WarehouseStatistics {

    private final Object lock = new Object();
    private int processedParcels;
    private long totalProcessingMillis;

    public void recordProcessed(long elapsedMillis) {
        synchronized (lock) {
            int current = processedParcels;
            Thread.yield();
            processedParcels = current + 1;

            long accumulated = totalProcessingMillis;
            Thread.yield();
            totalProcessingMillis = accumulated + elapsedMillis;
        }
    }

    public int processedParcels() {
        synchronized (lock) {
            return processedParcels;
        }
    }

    public long totalProcessingMillis() {
        synchronized (lock) {
            return totalProcessingMillis;
        }
    }
}
