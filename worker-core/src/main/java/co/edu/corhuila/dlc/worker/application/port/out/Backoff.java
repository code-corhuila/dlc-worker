package co.edu.corhuila.dlc.worker.application.port.out;

import java.time.Duration;

/** Waits between provider attempts; a port so tests do not sleep. */
@FunctionalInterface
public interface Backoff {
    void await(Duration delay);
}
