package co.edu.corhuila.dlc.worker.application.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReminderRequestTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-11-06T15:00:00Z");

    @Test
    void requiresEveryIdentifierAndTheStart() {
        assertThrows(NullPointerException.class, () -> new ReminderRequest(null, ID, ID, ID, 1, START, ID));
        assertThrows(NullPointerException.class, () -> new ReminderRequest(ID, null, ID, ID, 1, START, ID));
        assertThrows(NullPointerException.class, () -> new ReminderRequest(ID, ID, null, ID, 1, START, ID));
        assertThrows(NullPointerException.class, () -> new ReminderRequest(ID, ID, ID, null, 1, START, ID));
        assertThrows(NullPointerException.class, () -> new ReminderRequest(ID, ID, ID, ID, 1, null, ID));
        assertThrows(NullPointerException.class, () -> new ReminderRequest(ID, ID, ID, ID, 1, START, null));
    }

    @Test
    void requiresAPositiveAppointmentVersion() {
        assertThrows(IllegalArgumentException.class, () -> new ReminderRequest(ID, ID, ID, ID, 0, START, ID));
    }
}
