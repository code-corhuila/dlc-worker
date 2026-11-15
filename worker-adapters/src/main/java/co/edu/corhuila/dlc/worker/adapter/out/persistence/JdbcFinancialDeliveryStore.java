package co.edu.corhuila.dlc.worker.adapter.out.persistence;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.FinancialDelivery;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import co.edu.corhuila.dlc.worker.application.port.out.FinancialDeliveryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Financial email results in schema {@code notifications}, used as notifications_runtime (SELECT and INSERT only).
 * The Billing eventId is both the inbox key and the delivery key. Rows are final: a repeated insert is ignored.
 */
public final class JdbcFinancialDeliveryStore implements FinancialDeliveryStore {
    private static final Set<String> RESULTS = Set.of("DISCARDED", "RECORDED");
    private final JdbcTemplate jdbc;

    public JdbcFinancialDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean processed(UUID eventId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notifications.inbox_events WHERE event_id = ?)", Boolean.class, eventId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<FinancialDelivery> delivery(UUID eventId) {
        return jdbc.query("SELECT event_id, invoice_id, notification_type, status, attempts, provider_message_id,"
                + " failure_code, occurred_at FROM notifications.financial_deliveries WHERE event_id = ?",
                JdbcFinancialDeliveryStore::delivery, eventId).stream().findFirst();
    }

    @Override
    public void save(FinancialDelivery delivery) {
        jdbc.update("INSERT INTO notifications.financial_deliveries (event_id, invoice_id, notification_type, status,"
                + " attempts, provider_message_id, failure_code, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (event_id) DO NOTHING",
                delivery.eventId(), delivery.invoiceId(), delivery.kind().name(), delivery.status().name(),
                delivery.attempts(), delivery.providerMessageId(), delivery.failureCode(),
                Timestamp.from(delivery.occurredAt()));
    }

    @Override
    public void complete(FinancialRequest request, String result) {
        if (!RESULTS.contains(result)) throw new IllegalArgumentException("Unknown result.");
        jdbc.update("INSERT INTO notifications.inbox_events (event_id, event_type, delivery_id, correlation_id,"
                + " result, processed_at) VALUES (?, ?, ?, ?, ?, now()) ON CONFLICT (event_id) DO NOTHING",
                request.eventId(), request.kind().eventType(), request.eventId(), request.correlationId(), result);
    }

    private static FinancialDelivery delivery(ResultSet row, int index) throws SQLException {
        return new FinancialDelivery(row.getObject("event_id", UUID.class), row.getObject("invoice_id", UUID.class),
                FinancialRequest.Kind.valueOf(row.getString("notification_type")),
                Delivery.Status.valueOf(row.getString("status")), row.getInt("attempts"),
                row.getString("provider_message_id"), row.getString("failure_code"),
                row.getTimestamp("occurred_at").toInstant());
    }
}
