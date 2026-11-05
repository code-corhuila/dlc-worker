package co.edu.corhuila.dlc.worker.application.model;

import java.util.Objects;
import java.util.UUID;

/** A composed email; {@code deliveryId} lets the provider adapter make the send idempotent. Never printed. */
public record EmailMessage(UUID deliveryId, String to, String subject, String body) {
    public EmailMessage {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }

    @Override
    public String toString() {
        return "EmailMessage[deliveryId=" + deliveryId + ", content redacted]";
    }
}
