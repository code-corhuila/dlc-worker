package co.edu.corhuila.dlc.worker.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The final result of one delivery, persisted before it is reported so a redelivered message never sends the
 * email again. Holds no recipient address or message body.
 */
public record Delivery(UUID deliveryId, UUID eventId, UUID appointmentId, long appointmentVersion, Status status,
        int attempts, String providerMessageId, String failureCode, Instant occurredAt) {

    public enum Status { SENT, DELIVERY_FAILED }

    public Delivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
