package co.edu.corhuila.dlc.worker.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.worker.application.model.ReminderDetails;
import co.edu.corhuila.dlc.worker.application.model.ReminderOutcome;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import co.edu.corhuila.dlc.worker.application.model.ScheduledRequest;
import co.edu.corhuila.dlc.worker.application.port.out.AppointmentNotifications;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import co.edu.corhuila.dlc.worker.application.port.out.ScheduledDeliveryStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeliverScheduledEmailTest {
    private final Instant start = Instant.parse("2026-11-15T15:00:00Z");
    private final ScheduledRequest request = new ScheduledRequest(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), start, UUID.randomUUID());
    private final Store store = new Store();
    private final AtomicInteger attempts = new AtomicInteger();
    private String status = "PROGRAMADA";
    private boolean fail;
    private final AppointmentNotifications appointments = new AppointmentNotifications() {
        public Optional<ReminderDetails> details(UUID id) {
            return Optional.of(new ReminderDetails(id, request.patientId(), 1, start, start.plusSeconds(3600), status));
        }
        public boolean report(UUID id, ReminderOutcome outcome) { throw new AssertionError("Must not report a reminder"); }
    };

    private DeliverScheduledEmail delivery() {
        return new DeliverScheduledEmail(appointments, id -> Optional.of(new Recipient("demo@example.test", "Paciente")),
                message -> { attempts.incrementAndGet(); if (fail) throw new EmailDeliveryException("SMTP_TEMPORARY", false);
                    assertEquals("demo@example.test", message.to());
                    assertTrue(message.body().contains("programada"));
                    return "test-message"; },
                store, delay -> { }, Duration.ofMillis(1), Clock.systemUTC());
    }

    @Test void sendsOnceWithoutCompletingReminder() {
        var handler = delivery();
        assertEquals(DeliverScheduledEmail.Result.SENT, handler.handle(request));
        assertEquals(DeliverScheduledEmail.Result.DUPLICATE, handler.handle(request));
        assertEquals(1, attempts.get());
    }

    @Test void discardsCancelledAppointmentWithoutSending() {
        status = "CANCELADA";
        assertEquals(DeliverScheduledEmail.Result.DISCARDED, delivery().handle(request));
        assertEquals(0, attempts.get());
        assertFalse(store.delivery(request.eventId()).isPresent());
    }

    @Test void recordsFailureAfterThreeAttempts() {
        fail = true;
        assertEquals(DeliverScheduledEmail.Result.DELIVERY_FAILED, delivery().handle(request));
        assertEquals(3, attempts.get());
        assertEquals("SMTP_TEMPORARY", store.delivery(request.eventId()).orElseThrow().failureCode());
    }

    private static final class Store implements ScheduledDeliveryStore {
        final Set<UUID> processed = new HashSet<>();
        final Map<UUID, Outcome> outcomes = new HashMap<>();
        public boolean processed(UUID id) { return processed.contains(id); }
        public Optional<Outcome> delivery(UUID id) { return Optional.ofNullable(outcomes.get(id)); }
        public void save(ScheduledRequest request, Outcome outcome) { outcomes.put(request.eventId(), outcome); }
        public void complete(ScheduledRequest request, String result) { processed.add(request.eventId()); }
    }
}
