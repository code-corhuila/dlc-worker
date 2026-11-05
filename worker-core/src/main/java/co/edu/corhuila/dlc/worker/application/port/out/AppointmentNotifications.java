package co.edu.corhuila.dlc.worker.application.port.out;

import co.edu.corhuila.dlc.worker.application.model.ReminderDetails;
import co.edu.corhuila.dlc.worker.application.model.ReminderOutcome;
import java.util.Optional;
import java.util.UUID;

/** Appointments private contract for the worker (scopes appointments:notification-details:read / -outcome:write). */
public interface AppointmentNotifications {
    /** Empty when the appointment does not exist (404). Throws {@link OwnerUnavailableException} when transient. */
    Optional<ReminderDetails> details(UUID appointmentId);

    /** True when recorded or repeated (200); false when Appointments rejects it (409): never retried. */
    boolean report(UUID appointmentId, ReminderOutcome outcome);
}
