package co.edu.corhuila.dlc.worker.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.FinancialDelivery;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The financial delivery store (migration 005) against real PostgreSQL (TEST_DATABASE_URL, an administrative JDBC URL; skipped when
 * absent). Each run creates a disposable database, applies db/deploy/bootstrap.sql and the Liquibase changelog as
 * notifications_migrator, and exercises the store as notifications_runtime to prove its least privilege.
 */
class JdbcFinancialDeliveryStoreTest {
    private static final String ADMIN_URL = System.getenv("TEST_DATABASE_URL");
    private static final String DATABASE = "worker_it_" + UUID.randomUUID().toString().replace("-", "");
    private static SingleConnectionDataSource runtime;
    private static JdbcFinancialDeliveryStore store;

    @BeforeAll
    static void migrate() throws Exception {
        assumeTrue(ADMIN_URL != null && !ADMIN_URL.isBlank(), "TEST_DATABASE_URL not set");
        try (Connection admin = DriverManager.getConnection(ADMIN_URL); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + DATABASE);
        }
        Path db = dbDirectory();
        try (Connection owner = DriverManager.getConnection(urlOf(DATABASE));
                Statement sql = owner.createStatement()) {
            sql.execute(Files.readString(db.resolve("deploy/bootstrap.sql")));
            sql.execute("SET ROLE notifications_migrator");
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(owner));
            database.setLiquibaseSchemaName("notifications_liquibase");
            new Liquibase("changelog/changelog-master.yaml", new DirectoryResourceAccessor(db), database)
                    .update("dev");
        }
        Connection connection = DriverManager.getConnection(urlOf(DATABASE));
        try (Statement sql = connection.createStatement()) {
            sql.execute("SET ROLE notifications_runtime");
        }
        runtime = new SingleConnectionDataSource(connection, true);
        store = new JdbcFinancialDeliveryStore(new JdbcTemplate(runtime));
    }

    @AfterAll
    static void drop() throws SQLException {
        if (ADMIN_URL == null || ADMIN_URL.isBlank()) return;
        if (runtime != null) runtime.destroy();
        try (Connection admin = DriverManager.getConnection(ADMIN_URL); Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    private static String urlOf(String database) {
        return ADMIN_URL.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
    }

    private static Path dbDirectory() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("db/changelog"))) return dir.resolve("db");
        }
        throw new IllegalStateException("db/changelog not found");
    }

    private static FinancialRequest request(Kind kind) {
        return new FinancialRequest(UUID.randomUUID(), kind, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static FinancialDelivery sent(FinancialRequest request) {
        return new FinancialDelivery(request.eventId(), request.invoiceId(), request.kind(), Delivery.Status.SENT, 1,
                "<" + request.eventId() + "@dlc-worker>", null, Instant.parse("2026-11-08T16:00:01Z"));
    }

    @Test
    void aFinancialEventIsProcessedOnceWithItsTypeAndARepeatIsHarmless() {
        FinancialRequest request = request(Kind.PAYMENT_RECORDED);
        assertThat(store.processed(request.eventId())).isFalse();

        store.complete(request, "RECORDED");
        store.complete(request, "DISCARDED");

        assertThat(store.processed(request.eventId())).isTrue();
        assertThat(new JdbcTemplate(runtime).queryForMap("SELECT event_type, delivery_id, result"
                + " FROM notifications.inbox_events WHERE event_id = ?", request.eventId()))
                .containsEntry("event_type", "PaymentRecorded").containsEntry("delivery_id", request.eventId())
                .containsEntry("result", "RECORDED");
        assertThatThrownBy(() -> store.complete(request, "REPORTED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeliveryIsSavedOnceAndReadBack() {
        FinancialRequest request = request(Kind.INVOICE_ISSUED);
        assertThat(store.delivery(request.eventId())).isEmpty();

        store.save(sent(request));
        store.save(new FinancialDelivery(request.eventId(), request.invoiceId(), request.kind(),
                Delivery.Status.DELIVERY_FAILED, 3, null, "PROVIDER_UNAVAILABLE", Instant.now()));

        assertThat(store.delivery(request.eventId())).contains(sent(request));
    }

    @Test
    void aFailedDeliveryKeepsItsCode() {
        FinancialRequest request = request(Kind.PAYMENT_RECORDED);
        FinancialDelivery failed = new FinancialDelivery(request.eventId(), request.invoiceId(), request.kind(),
                Delivery.Status.DELIVERY_FAILED, 1, null, "RECIPIENT_REJECTED", Instant.parse("2026-11-08T16:00:09Z"));

        store.save(failed);

        assertThat(store.delivery(request.eventId())).contains(failed);
    }

    @Test
    void theRuntimeCannotChangeFinancialHistory() {
        JdbcTemplate jdbc = new JdbcTemplate(runtime);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM notifications.financial_deliveries"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE notifications.financial_deliveries SET attempts = 2"))
                .rootCause().hasMessageContaining("permission denied");
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = 'notifications' AND table_name = 'financial_deliveries'");
        assertThat(columns).extracting(c -> (String) c.get("column_name"))
                .noneMatch(name -> name.contains("email") || name.contains("amount") || name.contains("total"));
    }

    @Test
    void constraintsRejectAnInconsistentFinancialDelivery() {
        JdbcTemplate jdbc = new JdbcTemplate(runtime);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notifications.financial_deliveries (event_id, invoice_id,"
                + " notification_type, status, attempts, provider_message_id, failure_code, occurred_at)"
                + " VALUES (?, ?, 'INVOICE_PAID', 'SENT', 1, 'x', NULL, now())", UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("ck_financial_deliveries");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notifications.financial_deliveries (event_id, invoice_id,"
                + " notification_type, status, attempts, provider_message_id, failure_code, occurred_at)"
                + " VALUES (?, ?, 'INVOICE_ISSUED', 'SENT', 1, NULL, NULL, now())", UUID.randomUUID(),
                UUID.randomUUID())).hasMessageContaining("ck_financial_deliveries");
    }
}
