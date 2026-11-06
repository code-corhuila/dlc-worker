package co.edu.corhuila.dlc.worker.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * HU-APT-002 end to end (e2e/run.sh): Appointments from its qa image, its migrated schema, RabbitMQ, the worker
 * image with its migrated store, Mailpit and a Patients double of billing-contact. Skipped unless E2E_DATABASE_URL
 * (administrative JDBC URL of the stage database), E2E_RABBITMQ_URL and E2E_MAILPIT_URL are set.
 */
class ReminderEndToEndTest {
    private static final String DATABASE = System.getenv("E2E_DATABASE_URL");
    private static final String BROKER = System.getenv("E2E_RABBITMQ_URL");
    private static final String MAILPIT = System.getenv("E2E_MAILPIT_URL");
    private static final String EXCHANGE = "appointments.events";
    private static final String DLQ = "worker.appointment-reminder-requested.dlq";
    private static final Duration WAIT = Duration.ofSeconds(120);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Connection db;
    private static CachingConnectionFactory rabbit;
    private static RabbitTemplate template;

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(DATABASE != null && !DATABASE.isBlank() && BROKER != null && !BROKER.isBlank()
                && MAILPIT != null && !MAILPIT.isBlank(), "E2E_DATABASE_URL, E2E_RABBITMQ_URL and E2E_MAILPIT_URL");
        db = DriverManager.getConnection(DATABASE);
        rabbit = new CachingConnectionFactory(URI.create(BROKER));
        template = new RabbitTemplate(rabbit);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (rabbit != null) rabbit.destroy();
        if (db != null) db.close();
    }

    @Test
    void anAppointmentDueIn24HoursIsRemindedOnceAndReportedSent() throws Exception {
        Instant start = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
        UUID patient = UUID.randomUUID();
        UUID appointment = schedule(patient, start, "PROGRAMADA", 1);

        await("the reminder is reported SENT", () -> "SENT".equals(value(
                "SELECT status FROM appointments.appointment_reminders WHERE appointment_id = ?", appointment))
                && "SENT".equals(value(
                "SELECT reminder_status FROM appointments.appointments WHERE appointment_id = ?", appointment)));

        assertThat(value("SELECT count(*) FROM appointments.outbox_events WHERE aggregate_id = ? AND event_type = "
                + "'AppointmentReminderRequested' AND status = 'PUBLISHED'", appointment)).isEqualTo("1");
        String delivery = value("SELECT delivery_id FROM appointments.appointment_reminders WHERE appointment_id = ?",
                appointment);
        assertThat(value("SELECT status FROM notifications.deliveries WHERE delivery_id = ?", UUID.fromString(delivery)))
                .isEqualTo("SENT");
        JsonNode messages = mails(patient);
        assertThat(messages.path("messages_count").asInt()).isEqualTo(1);
        String body = mailText(messages.path("messages").get(0).path("ID").asText());
        String local = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(start.atZone(ZoneId.of("America/Bogota")));
        assertThat(body).contains("a las " + local + " (hora de Colombia)").contains("Paciente E2E")
                .doesNotContain("http", "www.", "token", appointment.toString(), patient.toString());

        republishReminderOf(appointment);
        drained();
        assertThat(mails(patient).path("messages_count").asInt()).as("a duplicate eventId sends nothing").isEqualTo(1);
    }

    @Test
    void aCancelledAppointmentIsDiscardedWithoutEmail() throws Exception {
        UUID patient = UUID.randomUUID();
        Instant start = Instant.now().plus(30, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        UUID appointment = schedule(patient, start, "CANCELADA", 2);
        UUID event = UUID.randomUUID();
        // The request Appointments published for version 1, delivered after the cancellation (version 2).
        publish(envelope(event, appointment, patient, start, 1, UUID.randomUUID()));

        await("the request is discarded", () -> "DISCARDED".equals(value(
                "SELECT result FROM notifications.inbox_events WHERE event_id = ?", event)));
        assertThat(mails(patient).path("messages_count").asInt()).isZero();
    }

    @Test
    void aMessageOutsideTheSchemaGoesToTheDeadLetterQueue() {
        String marker = UUID.randomUUID().toString();
        publish("{\"eventType\":\"AppointmentReminderRequested\",\"marker\":\"" + marker + "\"}");

        Message dead = template.receive(DLQ, WAIT.toMillis());
        while (dead != null && !new String(dead.getBody(), StandardCharsets.UTF_8).contains(marker)) {
            dead = template.receive(DLQ, WAIT.toMillis());
        }
        assertThat(dead).as("the invalid message in " + DLQ).isNotNull();
    }

    /** Inserts an appointment and its pending reminder exactly as Appointments scheduling does (due = start - 24 h). */
    private static UUID schedule(UUID patient, Instant start, String status, int version) throws SQLException {
        UUID appointment = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp due = Timestamp.from(start.minus(24, ChronoUnit.HOURS));
        update("""
                INSERT INTO appointments.appointments (appointment_id, patient_id, dentist_id, start_at, end_at,
                    clinic_timezone, reason, status, confirmation_status, reminder_due_at, reminder_status,
                    version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'America/Bogota', 'Control', ?, 'PENDING', ?, 'PENDING', ?, ?, ?, ?, ?)""",
                appointment, patient, UUID.randomUUID(), Timestamp.from(start),
                Timestamp.from(start.plus(30, ChronoUnit.MINUTES)), status, due, version, now, staff, now, staff);
        if (status.equals("PROGRAMADA")) {
            update("""
                    INSERT INTO appointments.appointment_reminders (reminder_id, appointment_id, appointment_version,
                        due_at, channel, status, attempts, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'EMAIL', 'PENDING', 0, ?, ?)""",
                    UUID.randomUUID(), appointment, version, due, now, now);
        }
        return appointment;
    }

    /** Publishes again the exact envelope Appointments relayed (same eventId and deliveryId). */
    private static void republishReminderOf(UUID appointment) throws Exception {
        try (PreparedStatement query = db.prepareStatement("""
                SELECT event_id, payload::text, occurred_at, correlation_id FROM appointments.outbox_events
                WHERE aggregate_id = ? AND event_type = 'AppointmentReminderRequested'""")) {
            query.setObject(1, appointment);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                JsonNode payload = JSON.readTree(row.getString(2));
                String correlation = row.getString(4);
                publish(JSON.writeValueAsString(JSON.createObjectNode()
                        .put("eventId", row.getString(1)).put("eventType", "AppointmentReminderRequested")
                        .put("aggregateId", appointment.toString()).put("aggregateType", "Appointment")
                        .put("occurredAt", row.getTimestamp(3).toInstant().toString()).put("version", 1)
                        .put("producer", "appointments-service")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("payload", payload)
                        .set("metadata", JSON.createObjectNode().put("correlationId", correlation)
                                .put("causationId", correlation).putNull("actorUserId"))));
            }
        }
    }

    private static String envelope(UUID event, UUID appointment, UUID patient, Instant start, int version,
            UUID delivery) throws Exception {
        String correlation = UUID.randomUUID().toString();
        return JSON.writeValueAsString(JSON.createObjectNode()
                .put("eventId", event.toString()).put("eventType", "AppointmentReminderRequested")
                .put("aggregateId", appointment.toString()).put("aggregateType", "Appointment")
                .put("occurredAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()).put("version", 1)
                .put("producer", "appointments-service")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("payload", JSON.createObjectNode()
                        .put("appointmentId", appointment.toString()).put("patientId", patient.toString())
                        .put("startAt", start.toString()).put("appointmentVersion", version)
                        .put("deliveryId", delivery.toString())
                        .put("requestedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                .set("metadata", JSON.createObjectNode().put("correlationId", correlation)
                        .put("causationId", correlation).putNull("actorUserId")));
    }

    private static void publish(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        template.send(EXCHANGE, "AppointmentReminderRequested",
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    /** Waits until the worker queue is empty and nothing is in flight. */
    private static void drained() throws Exception {
        Thread.sleep(5000);
        await("the worker queue drains", () -> {
            var info = new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit)
                    .getQueueInfo("worker.appointment-reminder-requested");
            return info != null && info.getMessageCount() == 0;
        });
        Thread.sleep(5000);
    }

    private static JsonNode mails(UUID patient) throws Exception {
        String query = URLEncoder.encode("to:\"paciente-" + patient + "@example.test\"", StandardCharsets.UTF_8);
        return JSON.readTree(get("/api/v1/search?query=" + query));
    }

    private static String mailText(String id) throws Exception {
        return JSON.readTree(get("/api/v1/message/" + id)).path("Text").asText();
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(MAILPIT + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static void update(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static String value(String sql, Object key) {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setObject(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant limit = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(limit)) throw new AssertionError("Timed out waiting until " + what);
            Thread.sleep(1000);
        }
    }
}
