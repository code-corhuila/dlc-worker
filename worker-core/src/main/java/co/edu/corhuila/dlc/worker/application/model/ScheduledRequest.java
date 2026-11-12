package co.edu.corhuila.dlc.worker.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identifiers from AppointmentScheduled; contact information remains owned by Patients. */
public record ScheduledRequest(UUID eventId, UUID appointmentId, UUID patientId, Instant startAt,
                               UUID correlationId) {
    public ScheduledRequest {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
