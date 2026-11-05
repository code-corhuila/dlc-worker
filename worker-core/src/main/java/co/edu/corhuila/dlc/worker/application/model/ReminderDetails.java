package co.edu.corhuila.dlc.worker.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimum current appointment data from the owner contract (GET .../details). */
public record ReminderDetails(UUID appointmentId, UUID patientId, long appointmentVersion, Instant startAt,
        Instant endAt, String status) {
    public ReminderDetails {
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(status, "status");
    }
}
