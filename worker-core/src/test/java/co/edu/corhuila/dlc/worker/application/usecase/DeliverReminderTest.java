package co.edu.corhuila.dlc.worker.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.ReminderDetails;
import co.edu.corhuila.dlc.worker.application.model.ReminderOutcome;
import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import co.edu.corhuila.dlc.worker.application.port.out.AppointmentNotifications;
import co.edu.corhuila.dlc.worker.application.port.out.DeliveryStore;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import co.edu.corhuila.dlc.worker.application.port.out.EmailSender;
import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import co.edu.corhuila.dlc.worker.application.port.out.PatientContacts;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverReminder.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliverReminderTest {
    private static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");
    private static final Instant START = Instant.parse("2026-11-06T15:00:00Z");
    private static final UUID PATIENT = UUID.randomUUID();
    private static final ReminderRequest REQUEST = new ReminderRequest(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), PATIENT, 2, START, UUID.randomUUID());

    private final FakeAppointments appointments = new FakeAppointments();
    private final FakePatients patients = new FakePatients();
    private final FakeSender sender = new FakeSender();
    private final FakeStore store = new FakeStore();
    private final List<Duration> waits = new ArrayList<>();
    private final DeliverReminder deliver = new DeliverReminder(appointments, patients, sender, store, waits::add,
            Duration.ofSeconds(2), Clock.fixed(NOW, ZoneOffset.UTC));

    DeliverReminderTest() {
        appointments.details = Optional.of(details(2, "PROGRAMADA"));
        patients.contact = Optional.of(new Recipient("ana@example.com", "Ana"));
    }

    private static ReminderDetails details(long version, String status) {
        return new ReminderDetails(REQUEST.appointmentId(), PATIENT, version, START, START.plusSeconds(1800),
                status);
    }

    @Test
    void sendsOnceAndReportsSent() {
        assertEquals(Result.SENT_REPORTED, deliver.handle(REQUEST));

        assertEquals(1, sender.sent.size());
        assertEquals("ana@example.com", sender.sent.get(0).to());
        ReminderOutcome outcome = appointments.reported.get(0);
        assertEquals(REQUEST.deliveryId(), outcome.deliveryId());
        assertEquals(2, outcome.appointmentVersion());
        assertEquals(ReminderOutcome.Status.SENT, outcome.status());
        assertEquals(1, outcome.attempts());
        assertEquals("provider-1", outcome.providerMessageId());
        assertNull(outcome.failureCode());
        assertEquals(NOW, outcome.occurredAt());
        assertEquals("REPORTED", store.processed.get(REQUEST.eventId()));
    }

    @Test
    void confirmedAppointmentsAreRemindedToo() {
        appointments.details = Optional.of(details(2, "CONFIRMADA"));

        assertEquals(Result.SENT_REPORTED, deliver.handle(REQUEST));
    }

    @Test
    void theSameEventTwiceSendsOnce() {
        deliver.handle(REQUEST);

        assertEquals(Result.DUPLICATE, deliver.handle(REQUEST));
        assertEquals(1, sender.sent.size());
        assertEquals(1, appointments.reported.size());
    }

    @Test
    void staleVersionCancelledOrMissingAppointmentsAreDiscardedWithoutEmailOrOutcome() {
        for (Optional<ReminderDetails> current : List.of(Optional.of(details(3, "PROGRAMADA")),
                Optional.of(details(2, "CANCELADA")), Optional.of(details(2, "EN_ATENCION")),
                Optional.<ReminderDetails>empty())) {
            FakeStore fresh = new FakeStore();
            appointments.details = current;
            DeliverReminder handler = new DeliverReminder(appointments, patients, sender, fresh, waits::add,
                    Duration.ofSeconds(2), Clock.fixed(NOW, ZoneOffset.UTC));

            assertEquals(Result.DISCARDED, handler.handle(REQUEST));
            assertEquals("DISCARDED", fresh.processed.get(REQUEST.eventId()));
        }
        assertTrue(sender.sent.isEmpty());
        assertTrue(appointments.reported.isEmpty());
    }

    @Test
    void aPatientMismatchOrMissingContactIsDiscarded() {
        appointments.details = Optional.of(new ReminderDetails(REQUEST.appointmentId(), UUID.randomUUID(), 2,
                START, START.plusSeconds(1800), "PROGRAMADA"));
        assertEquals(Result.DISCARDED, deliver.handle(REQUEST));

        appointments.details = Optional.of(details(2, "PROGRAMADA"));
        patients.contact = Optional.empty();
        FakeStore fresh = new FakeStore();
        assertEquals(Result.DISCARDED, new DeliverReminder(appointments, patients, sender, fresh, waits::add,
                Duration.ofSeconds(2), Clock.fixed(NOW, ZoneOffset.UTC)).handle(REQUEST));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void providerDownThreeTimesReportsDeliveryFailedWithThreeAttemptsAndBackoff() {
        sender.failures.add(new EmailDeliveryException("PROVIDER_UNAVAILABLE", false));
        sender.failures.add(new EmailDeliveryException("PROVIDER_UNAVAILABLE", false));
        sender.failures.add(new EmailDeliveryException("PROVIDER_UNAVAILABLE", false));

        assertEquals(Result.FAILED_REPORTED, deliver.handle(REQUEST));

        ReminderOutcome outcome = appointments.reported.get(0);
        assertEquals(ReminderOutcome.Status.DELIVERY_FAILED, outcome.status());
        assertEquals(3, outcome.attempts());
        assertEquals("PROVIDER_UNAVAILABLE", outcome.failureCode());
        assertNull(outcome.providerMessageId());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), waits);
        assertEquals(3, sender.calls);
    }

    @Test
    void aTransientFailureThenSuccessReportsTheAttempts() {
        sender.failures.add(new EmailDeliveryException("PROVIDER_UNAVAILABLE", false));

        assertEquals(Result.SENT_REPORTED, deliver.handle(REQUEST));
        assertEquals(2, appointments.reported.get(0).attempts());
    }

    @Test
    void aPermanentProviderRejectionStopsRetrying() {
        sender.failures.add(new EmailDeliveryException("RECIPIENT_REJECTED", true));

        assertEquals(Result.FAILED_REPORTED, deliver.handle(REQUEST));
        assertEquals(1, sender.calls);
        assertEquals(1, appointments.reported.get(0).attempts());
        assertEquals("RECIPIENT_REJECTED", appointments.reported.get(0).failureCode());
        assertTrue(waits.isEmpty());
    }

    @Test
    void aRejectedOutcomeIsNotRetried() {
        appointments.accept = false;

        assertEquals(Result.OUTCOME_REJECTED, deliver.handle(REQUEST));
        assertEquals(1, appointments.reportCalls);
        assertEquals("REJECTED", store.processed.get(REQUEST.eventId()));
    }

    @Test
    void anUnavailableOwnerIsTransientAndLeavesTheEventPending() {
        appointments.unavailable = true;

        assertThrows(OwnerUnavailableException.class, () -> deliver.handle(REQUEST));
        assertFalse(store.processed.containsKey(REQUEST.eventId()));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void aFailedReportIsRetriedWithoutSendingTheEmailAgain() {
        appointments.reportUnavailable = true;
        assertThrows(OwnerUnavailableException.class, () -> deliver.handle(REQUEST));
        assertEquals(Delivery.Status.SENT, store.deliveries.get(REQUEST.deliveryId()).status());

        appointments.reportUnavailable = false;
        assertEquals(Result.SENT_REPORTED, deliver.handle(REQUEST));

        assertEquals(1, sender.sent.size());
        assertEquals("provider-1", appointments.reported.get(0).providerMessageId());
    }

    @Test
    void theEmailIsInformativeWithoutLinksOrClinicalData() {
        deliver.handle(REQUEST);

        EmailMessage message = sender.sent.get(0);
        assertEquals(REQUEST.deliveryId(), message.deliveryId());
        assertTrue(message.body().contains("Ana"));
        assertTrue(message.body().contains("10:00"), message.body());
        assertFalse(message.toString().contains("ana@example.com"));
    }

    static final class FakeAppointments implements AppointmentNotifications {
        Optional<ReminderDetails> details = Optional.empty();
        boolean unavailable;
        boolean reportUnavailable;
        boolean accept = true;
        int reportCalls;
        final List<ReminderOutcome> reported = new ArrayList<>();

        @Override
        public Optional<ReminderDetails> details(UUID appointmentId) {
            if (unavailable) throw new OwnerUnavailableException("APPOINTMENTS_UNAVAILABLE");
            return details;
        }

        @Override
        public boolean report(UUID appointmentId, ReminderOutcome outcome) {
            reportCalls++;
            if (reportUnavailable) throw new OwnerUnavailableException("APPOINTMENTS_UNAVAILABLE");
            if (accept) reported.add(outcome);
            return accept;
        }
    }

    static final class FakePatients implements PatientContacts {
        Optional<Recipient> contact = Optional.empty();

        @Override
        public Optional<Recipient> contact(UUID patientId) {
            return contact;
        }
    }

    static final class FakeSender implements EmailSender {
        final List<EmailMessage> sent = new ArrayList<>();
        final Deque<EmailDeliveryException> failures = new ArrayDeque<>();
        int calls;

        @Override
        public String send(EmailMessage message) {
            calls++;
            if (!failures.isEmpty()) throw failures.poll();
            sent.add(message);
            return "provider-" + sent.size();
        }
    }

    static final class FakeStore implements DeliveryStore {
        final Map<UUID, String> processed = new HashMap<>();
        final Map<UUID, Delivery> deliveries = new HashMap<>();

        @Override
        public boolean processed(UUID eventId) {
            return processed.containsKey(eventId);
        }

        @Override
        public Optional<Delivery> delivery(UUID deliveryId) {
            return Optional.ofNullable(deliveries.get(deliveryId));
        }

        @Override
        public void save(Delivery delivery) {
            deliveries.put(delivery.deliveryId(), delivery);
        }

        @Override
        public void complete(ReminderRequest request, String result) {
            processed.put(request.eventId(), result);
        }
    }
}
