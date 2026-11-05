package co.edu.corhuila.dlc.worker.application.port.out;

/** An owner contract is unreachable or failed transiently; the message stays pending and is retried. */
public final class OwnerUnavailableException extends RuntimeException {
    public OwnerUnavailableException(String code) {
        super(code, null, false, false);
    }
}
