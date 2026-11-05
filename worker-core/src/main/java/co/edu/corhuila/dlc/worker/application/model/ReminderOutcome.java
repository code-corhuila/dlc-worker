package co.edu.corhuila.dlc.worker.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Body of POST .../outcomes: provider reference when SENT, failure code when DELIVERY_FAILED. */
public record ReminderOutcome(UUID deliveryId, long appointmentVersion, Status status, int attempts,
        Instant occurredAt, String providerMessageId, String failureCode) {

    public enum Status { SENT, DELIVERY_FAILED }

    public ReminderOutcome {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (attempts < 1 || attempts > 3) throw new IllegalArgumentException("attempts must be 1 to 3.");
        if (status == Status.SENT && (providerMessageId == null || failureCode != null)) {
            throw new IllegalArgumentException("SENT requires providerMessageId only.");
        }
        if (status == Status.DELIVERY_FAILED && (failureCode == null || providerMessageId != null)) {
            throw new IllegalArgumentException("DELIVERY_FAILED requires failureCode only.");
        }
    }

    public static ReminderOutcome of(Delivery delivery) {
        return new ReminderOutcome(delivery.deliveryId(), delivery.appointmentVersion(),
                delivery.status() == Delivery.Status.SENT ? Status.SENT : Status.DELIVERY_FAILED,
                delivery.attempts(), delivery.occurredAt(), delivery.providerMessageId(), delivery.failureCode());
    }
}
