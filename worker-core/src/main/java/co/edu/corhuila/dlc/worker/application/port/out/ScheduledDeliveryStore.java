package co.edu.corhuila.dlc.worker.application.port.out;

import co.edu.corhuila.dlc.worker.application.model.ScheduledRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable results for scheduling notices, separate from 24-hour reminders. */
public interface ScheduledDeliveryStore {
    record Outcome(String status, int attempts, String providerMessageId, String failureCode, Instant occurredAt) { }
    boolean processed(UUID eventId);
    Optional<Outcome> delivery(UUID eventId);
    void save(ScheduledRequest request, Outcome outcome);
    void complete(ScheduledRequest request, String result);
}
