package co.edu.corhuila.dlc.worker.adapter.out.persistence;

import co.edu.corhuila.dlc.worker.application.model.ScheduledRequest;
import co.edu.corhuila.dlc.worker.application.port.out.ScheduledDeliveryStore;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcScheduledDeliveryStore implements ScheduledDeliveryStore {
    private final JdbcTemplate jdbc;
    public JdbcScheduledDeliveryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean processed(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM notifications.inbox_events WHERE event_id = ?)", Boolean.class, eventId));
    }
    public Optional<Outcome> delivery(UUID eventId) {
        return jdbc.query("SELECT status, attempts, provider_message_id, failure_code, occurred_at FROM notifications.scheduled_deliveries WHERE event_id = ?",
                (row, n) -> new Outcome(row.getString("status"), row.getInt("attempts"), row.getString("provider_message_id"),
                        row.getString("failure_code"), row.getTimestamp("occurred_at").toInstant()), eventId).stream().findFirst();
    }
    public void save(ScheduledRequest request, Outcome result) {
        jdbc.update("INSERT INTO notifications.scheduled_deliveries (event_id, appointment_id, status, attempts, provider_message_id, failure_code, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                request.eventId(), request.appointmentId(), result.status(), result.attempts(), result.providerMessageId(), result.failureCode(), Timestamp.from(result.occurredAt()));
    }
    public void complete(ScheduledRequest request, String result) {
        if (!Set.of("DISCARDED", "RECORDED").contains(result)) throw new IllegalArgumentException("Unknown result");
        jdbc.update("INSERT INTO notifications.inbox_events (event_id, event_type, delivery_id, correlation_id, result, processed_at) VALUES (?, 'AppointmentScheduled', ?, ?, ?, now()) ON CONFLICT (event_id) DO NOTHING",
                request.eventId(), request.eventId(), request.correlationId(), result);
    }
}
