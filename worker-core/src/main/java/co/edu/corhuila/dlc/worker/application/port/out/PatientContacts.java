package co.edu.corhuila.dlc.worker.application.port.out;

import co.edu.corhuila.dlc.worker.application.model.Recipient;
import java.util.Optional;
import java.util.UUID;

/** Patients billing-contact contract (scope patients:billing-contact:read). Empty when the patient is unknown. */
public interface PatientContacts {
    Optional<Recipient> contact(UUID patientId);
}
