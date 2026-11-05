package co.edu.corhuila.dlc.worker.adapter.out.persistence;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import co.edu.corhuila.dlc.worker.application.port.out.DeliveryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The worker's own store in schema {@code notifications}, used as notifications_runtime (SELECT and INSERT only).
 * Rows are final: a repeated insert of the same key is ignored, so the first result always wins.
 */
public final class JdbcDeliveryStore implements DeliveryStore {
    private static final Set<String> RESULTS = Set.of("DISCARDED", "REPORTED", "REJECTED");
    private final JdbcTemplate jdbc;

    public JdbcDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean processed(UUID eventId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notifications.inbox_events WHERE event_id = ?)", Boolean.class, eventId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<Delivery> delivery(UUID deliveryId) {
        return jdbc.query("SELECT delivery_id, event_id, appointment_id, appointment_version, status, attempts,"
                + " provider_message_id, failure_code, occurred_at FROM notifications.deliveries"
                + " WHERE delivery_id = ?", JdbcDeliveryStore::delivery, deliveryId).stream().findFirst();
    }

    @Override
    public void save(Delivery delivery) {
        jdbc.update("INSERT INTO notifications.deliveries (delivery_id, event_id, appointment_id,"
                + " appointment_version, notification_type, status, attempts, provider_message_id, failure_code,"
                + " occurred_at) VALUES (?, ?, ?, ?, 'REMINDER', ?, ?, ?, ?, ?) ON CONFLICT (delivery_id) DO NOTHING",
                delivery.deliveryId(), delivery.eventId(), delivery.appointmentId(), delivery.appointmentVersion(),
                delivery.status().name(), delivery.attempts(), delivery.providerMessageId(), delivery.failureCode(),
                Timestamp.from(delivery.occurredAt()));
    }

    @Override
    public void complete(ReminderRequest request, String result) {
        if (!RESULTS.contains(result)) throw new IllegalArgumentException("Unknown result.");
        jdbc.update("INSERT INTO notifications.inbox_events (event_id, event_type, delivery_id, correlation_id,"
                + " result, processed_at) VALUES (?, 'AppointmentReminderRequested', ?, ?, ?, now())"
                + " ON CONFLICT (event_id) DO NOTHING",
                request.eventId(), request.deliveryId(), request.correlationId(), result);
    }

    private static Delivery delivery(ResultSet row, int index) throws SQLException {
        Instant occurredAt = row.getTimestamp("occurred_at").toInstant();
        return new Delivery(row.getObject("delivery_id", UUID.class), row.getObject("event_id", UUID.class),
                row.getObject("appointment_id", UUID.class), row.getLong("appointment_version"),
                Delivery.Status.valueOf(row.getString("status")), row.getInt("attempts"),
                row.getString("provider_message_id"), row.getString("failure_code"), occurredAt);
    }
}
