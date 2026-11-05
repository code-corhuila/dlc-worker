package co.edu.corhuila.dlc.worker.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An AppointmentReminderRequested fact as the worker needs it (ADR-005): identifiers only, no contact data.
 * {@code deliveryId} is the producer's stable key for this delivery (D11); {@code eventId} deduplicates messages.
 */
public record ReminderRequest(UUID eventId, UUID deliveryId, UUID appointmentId, UUID patientId,
        long appointmentVersion, Instant startAt, UUID correlationId) {

    public ReminderRequest {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (appointmentVersion < 1) throw new IllegalArgumentException("appointmentVersion must be at least 1.");
    }
}
