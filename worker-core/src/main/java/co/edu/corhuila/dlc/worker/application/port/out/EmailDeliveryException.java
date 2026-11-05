package co.edu.corhuila.dlc.worker.application.port.out;

/** A failed provider attempt. Permanent failures (for example a rejected recipient) are not retried. */
public final class EmailDeliveryException extends RuntimeException {
    private final boolean permanent;

    public EmailDeliveryException(String failureCode, boolean permanent) {
        super(failureCode, null, false, false);
        if (!failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("failureCode");
        this.permanent = permanent;
    }

    public String failureCode() {
        return getMessage();
    }

    public boolean permanent() {
        return permanent;
    }
}
