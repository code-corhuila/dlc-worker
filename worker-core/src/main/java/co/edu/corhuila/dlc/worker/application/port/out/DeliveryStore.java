package co.edu.corhuila.dlc.worker.application.port.out;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import java.util.Optional;
import java.util.UUID;

/** The worker inbox and delivery results (schema notifications). Never stores email or message body. */
public interface DeliveryStore {
    boolean processed(UUID eventId);

    Optional<Delivery> delivery(UUID deliveryId);

    void save(Delivery delivery);

    /** Marks the event processed with its result: DISCARDED, REPORTED or REJECTED. */
    void complete(ReminderRequest request, String result);
}
