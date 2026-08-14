package edu.eci.arsw.warehouse.core;

/**
 * Pause/resume control based on monitor coordination (synchronized + wait()/notifyAll()).
 *
 * Replaces the original busy-waiting implementation: paused robots block inside
 * wait() instead of spinning, so they consume no CPU while paused and are woken
 * up immediately (not on the next scheduler turn) when resume() is called.
 */
public class SimulationControl {

    private final Object lock = new Object();
    private boolean paused;

    public void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    public void awaitIfPaused() {
        synchronized (lock) {
            while (paused) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }
}
