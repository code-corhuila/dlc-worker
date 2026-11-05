package co.edu.corhuila.dlc.worker.adapter.in.messaging;

/** A message outside the consumed schema; it goes to the dead-letter queue. Carries only a safe code. */
public final class InvalidEventException extends RuntimeException {
    public InvalidEventException(String code) {
        super(code, null, false, false);
    }
}
