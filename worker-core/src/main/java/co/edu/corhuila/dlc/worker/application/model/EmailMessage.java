package co.edu.corhuila.dlc.worker.application.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A composed email; {@code deliveryId} lets the provider adapter make the send idempotent. Never printed.
 * {@code attachment} is null for messages without one (the informational reminder).
 */
public record EmailMessage(UUID deliveryId, String to, String subject, String body, Attachment attachment) {
    public EmailMessage {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }

    public EmailMessage(UUID deliveryId, String to, String subject, String body) {
        this(deliveryId, to, subject, body, null);
    }

    @Override
    public String toString() {
        return "EmailMessage[deliveryId=" + deliveryId + ", content redacted]";
    }
}
