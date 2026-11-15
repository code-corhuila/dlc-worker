package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Strict v1 AppointmentReminderRequested schema (dlc-docs/07-api/contracts/events). */
class ReminderEventReaderTest {
    static final String EVENT = "8f1c3a52-6d0e-4c8b-9a47-0b3f2e1d4c5a";
    static final String APPOINTMENT = "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9";
    static final String PATIENT = "2c3d4e5f-6071-4829-93a4-b5c6d7e8f901";
    static final String DELIVERY = "3d4e5f60-7182-493a-a4b5-c6d7e8f90123";
    static final String CORRELATION = "4e5f6071-8293-4a4b-b5c6-d7e8f9012345";
    static final String CAUSATION = "5f607182-93a4-4b5c-86d7-e8f901234567";

    private final ReminderEventReader reader = new ReminderEventReader();

    static String event(String eventId, String eventType, String aggregateId, String version, String producer,
            String payload, String actor) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"aggregateId\":\""
                + aggregateId + "\",\"aggregateType\":\"Appointment\",\"occurredAt\":\"2026-11-05T15:00:00Z\","
                + "\"version\":" + version + ",\"producer\":\"" + producer + "\",\"payload\":" + payload
                + ",\"metadata\":{\"correlationId\":\"" + CORRELATION + "\",\"causationId\":\"" + CAUSATION
                + "\",\"actorUserId\":" + actor + "}}";
    }

    static String payload(String appointmentVersion, String deliveryId, String startAt) {
        return "{\"appointmentId\":\"" + APPOINTMENT + "\",\"patientId\":\"" + PATIENT + "\",\"startAt\":\""
                + startAt + "\",\"appointmentVersion\":" + appointmentVersion + ",\"deliveryId\":\"" + deliveryId
                + "\",\"requestedAt\":\"2026-11-05T15:00:00Z\"}";
    }

    static String valid() {
        return event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1", "appointments-service",
                payload("3", DELIVERY, "2026-11-06T15:00:00Z"), "null");
    }

    private ReminderRequest read(String json) {
        return reader.read(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsEveryFieldTheWorkerNeeds() {
        ReminderRequest request = read(valid());

        assertThat(request.eventId()).isEqualTo(UUID.fromString(EVENT));
        assertThat(request.appointmentId()).isEqualTo(UUID.fromString(APPOINTMENT));
        assertThat(request.patientId()).isEqualTo(UUID.fromString(PATIENT));
        assertThat(request.deliveryId()).isEqualTo(UUID.fromString(DELIVERY));
        assertThat(request.appointmentVersion()).isEqualTo(3);
        assertThat(request.startAt()).isEqualTo(Instant.parse("2026-11-06T15:00:00Z"));
        assertThat(request.correlationId()).isEqualTo(UUID.fromString(CORRELATION));
    }

    @Test
    void acceptsAnActorUuidAndAnOffsetStart() {
        String json = event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1", "appointments-service",
                payload("1", DELIVERY, "2026-11-06T10:00:00-05:00"), "\"" + CAUSATION + "\"");

        assertThat(read(json).startAt()).isEqualTo(Instant.parse("2026-11-06T15:00:00Z"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> read("{not json")).isInstanceOf(InvalidEventException.class)
                .hasMessage("MALFORMED_EVENT");
        assertThatThrownBy(() -> read("[]")).hasMessage("MALFORMED_EVENT");
        assertThatThrownBy(() -> read(valid() + "{}")).hasMessage("MALFORMED_EVENT");
    }

    @Test
    void rejectsAnotherEventTypeOrVersion() {
        assertThatThrownBy(() -> read(event(EVENT, "AppointmentNoShow", APPOINTMENT, "1", "appointments-service",
                payload("1", DELIVERY, "2026-11-06T15:00:00Z"), "null"))).hasMessage("UNSUPPORTED_EVENT");
        assertThatThrownBy(() -> read(event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "2",
                "appointments-service", payload("1", DELIVERY, "2026-11-06T15:00:00Z"), "null")))
                .hasMessage("UNSUPPORTED_EVENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "producer", "aggregate", "eventId", "deliveryId", "startAt", "appointmentVersion", "extraTop",
            "extraPayload", "missingDelivery", "duplicateKey", "actor", "versionText"})
    void rejectsEnvelopesOutsideTheSchema(String defect) {
        String json = switch (defect) {
            case "producer" -> event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1", "billing-service",
                    payload("1", DELIVERY, "2026-11-06T15:00:00Z"), "null");
            case "aggregate" -> event(EVENT, "AppointmentReminderRequested", PATIENT, "1", "appointments-service",
                    payload("1", DELIVERY, "2026-11-06T15:00:00Z"), "null");
            case "eventId" -> event("not-a-uuid", "AppointmentReminderRequested", APPOINTMENT, "1",
                    "appointments-service", payload("1", DELIVERY, "2026-11-06T15:00:00Z"), "null");
            case "deliveryId" -> event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1",
                    "appointments-service", payload("1", "x", "2026-11-06T15:00:00Z"), "null");
            case "startAt" -> event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1",
                    "appointments-service", payload("1", DELIVERY, "2026-11-06 15:00"), "null");
            case "appointmentVersion" -> event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1",
                    "appointments-service", payload("0", DELIVERY, "2026-11-06T15:00:00Z"), "null");
            case "versionText" -> event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1",
                    "appointments-service", payload("\"1\"", DELIVERY, "2026-11-06T15:00:00Z"), "null");
            case "extraTop" -> valid().replaceFirst("\\{", "{\"email\":\"a@b.co\",");
            case "extraPayload" -> valid().replace("\"deliveryId\"", "\"email\":\"a@b.co\",\"deliveryId\"");
            case "missingDelivery" -> valid().replace("\"deliveryId\":\"" + DELIVERY + "\",", "");
            case "duplicateKey" -> valid().replaceFirst("\\{", "{\"eventId\":\"" + EVENT + "\",");
            case "actor" -> event(EVENT, "AppointmentReminderRequested", APPOINTMENT, "1", "appointments-service",
                    payload("1", DELIVERY, "2026-11-06T15:00:00Z"), "\"staff\"");
            default -> throw new IllegalArgumentException(defect);
        };

        assertThatThrownBy(() -> read(json)).isInstanceOf(InvalidEventException.class)
                .hasMessageMatching("INVALID_EVENT|MALFORMED_EVENT");
    }

    @Test
    void failureMessagesNeverEchoTheContent() {
        String json = valid().replace("\"deliveryId\"", "\"email\":\"patient@example.com\",\"deliveryId\"");

        assertThatThrownBy(() -> read(json)).hasMessageNotContaining("patient@example.com");
    }
}
