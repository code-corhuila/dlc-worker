package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduledEventReaderTest {
    private static final String ID = "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9";
    private static final String PATIENT = "2c3d4e5f-6071-4829-93a4-b5c6d7e8f901";
    private static final String EVENT = "8f1c3a52-6d0e-4c8b-9a47-0b3f2e1d4c5a";
    static final String JSON = """
        {"eventId":"%s","eventType":"AppointmentScheduled","aggregateId":"%s",
         "aggregateType":"Appointment","occurredAt":"2026-11-05T15:00:00Z","version":1,
         "producer":"appointments-service","payload":{"appointmentId":"%s","patientId":"%s",
         "dentistId":"%s","startAt":"2026-11-06T15:00:00Z","endAt":"2026-11-06T16:00:00Z",
         "reminderDueAt":"2026-11-05T15:00:00Z","scheduledBy":"%s"},
         "metadata":{"correlationId":"%s","causationId":"%s","actorUserId":null}}
        """.formatted(EVENT, ID, ID, PATIENT, EVENT, EVENT, EVENT, EVENT);

    @Test void readsScheduledEvent() {
        var request = new ScheduledEventReader().read(JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(request.eventId()).isEqualTo(UUID.fromString(EVENT));
        assertThat(request.appointmentId()).isEqualTo(UUID.fromString(ID));
        assertThat(request.patientId()).isEqualTo(UUID.fromString(PATIENT));
    }

    @Test void rejectsMismatchedAggregateAndUnexpectedFields() {
        var reader = new ScheduledEventReader();
        assertThatThrownBy(() -> reader.read(JSON.replaceFirst(ID, PATIENT).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> reader.read(JSON.replace("\"version\":1", "\"version\":1,\"extra\":true")
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(InvalidEventException.class);
    }
}
