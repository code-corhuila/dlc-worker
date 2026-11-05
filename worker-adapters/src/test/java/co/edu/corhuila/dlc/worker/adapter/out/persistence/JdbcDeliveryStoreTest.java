package co.edu.corhuila.dlc.worker.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
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
 * The notifications schema against real PostgreSQL (TEST_DATABASE_URL, an administrative JDBC URL; skipped when
 * absent). Each run creates a disposable database, applies db/deploy/bootstrap.sql and the Liquibase changelog as
 * notifications_migrator, and exercises the store as notifications_runtime to prove its least privilege.
 */
class JdbcDeliveryStoreTest {
    private static final String ADMIN_URL = System.getenv("TEST_DATABASE_URL");
    private static final String DATABASE = "worker_it_" + UUID.randomUUID().toString().replace("-", "");
    private static SingleConnectionDataSource runtime;
    private static JdbcDeliveryStore store;

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
        store = new JdbcDeliveryStore(new JdbcTemplate(runtime));
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

    private static ReminderRequest request() {
        return new ReminderRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2,
                Instant.parse("2026-11-06T15:00:00Z"), UUID.randomUUID());
    }

    private static Delivery sent(ReminderRequest request) {
        return new Delivery(request.deliveryId(), request.eventId(), request.appointmentId(), 2,
                Delivery.Status.SENT, 2, "<" + request.deliveryId() + "@dlc-worker>", null,
                Instant.parse("2026-11-05T15:00:01Z"));
    }

    @Test
    void anEventIsProcessedOnceAndARepeatIsHarmless() {
        ReminderRequest request = request();
        assertThat(store.processed(request.eventId())).isFalse();

        store.complete(request, "REPORTED");
        store.complete(request, "DISCARDED");

        assertThat(store.processed(request.eventId())).isTrue();
        assertThat(new JdbcTemplate(runtime).queryForObject(
                "SELECT result FROM notifications.inbox_events WHERE event_id = ?", String.class,
                request.eventId())).isEqualTo("REPORTED");
    }

    @Test
    void aDeliveryIsSavedOnceAndReadBack() {
        ReminderRequest request = request();
        assertThat(store.delivery(request.deliveryId())).isEmpty();

        store.save(sent(request));
        store.save(new Delivery(request.deliveryId(), request.eventId(), request.appointmentId(), 2,
                Delivery.Status.DELIVERY_FAILED, 3, null, "PROVIDER_UNAVAILABLE", Instant.now()));

        assertThat(store.delivery(request.deliveryId())).contains(sent(request));
    }

    @Test
    void aFailedDeliveryKeepsItsCode() {
        ReminderRequest request = request();
        Delivery failed = new Delivery(request.deliveryId(), request.eventId(), request.appointmentId(), 2,
                Delivery.Status.DELIVERY_FAILED, 3, null, "PROVIDER_UNAVAILABLE",
                Instant.parse("2026-11-05T15:00:09Z"));

        store.save(failed);

        assertThat(store.delivery(request.deliveryId())).contains(failed);
    }

    @Test
    void theSchemaHasNoColumnForEmailOrBody() {
        List<Map<String, Object>> columns = new JdbcTemplate(runtime).queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'notifications'");

        assertThat(columns).extracting(c -> (String) c.get("column_name"))
                .noneMatch(name -> name.contains("email") || name.contains("body") || name.contains("name"));
    }

    @Test
    void theRuntimeCannotChangeHistoryOrTheSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(runtime);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM notifications.deliveries"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE notifications.inbox_events SET result = 'REJECTED'"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE notifications.x (id int)"))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void constraintsRejectAnInconsistentDelivery() {
        JdbcTemplate jdbc = new JdbcTemplate(runtime);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notifications.deliveries (delivery_id, event_id,"
                + " appointment_id, appointment_version, notification_type, status, attempts, provider_message_id,"
                + " failure_code, occurred_at) VALUES (?, ?, ?, 1, 'REMINDER', 'SENT', 4, NULL, NULL, now())",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).hasMessageContaining("ck_deliveries");
    }
}
