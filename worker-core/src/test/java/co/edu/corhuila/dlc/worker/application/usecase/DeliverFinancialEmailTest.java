package co.edu.corhuila.dlc.worker.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.FinancialDelivery;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import co.edu.corhuila.dlc.worker.application.model.InvoiceSummary;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import co.edu.corhuila.dlc.worker.application.port.out.BillingDeliveries;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import co.edu.corhuila.dlc.worker.application.port.out.EmailSender;
import co.edu.corhuila.dlc.worker.application.port.out.FinancialDeliveryStore;
import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import co.edu.corhuila.dlc.worker.application.port.out.PatientContacts;
import co.edu.corhuila.dlc.worker.application.usecase.DeliverFinancialEmail.Result;
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

class DeliverFinancialEmailTest {
    private static final Instant NOW = Instant.parse("2026-11-08T16:00:00Z");
    private static final FinancialRequest ISSUED = new FinancialRequest(UUID.randomUUID(), Kind.INVOICE_ISSUED,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final byte[] PDF = {37, 80, 68, 70};

    private final FakeBilling billing = new FakeBilling();
    private final FakePatients patients = new FakePatients();
    private final FakeSender sender = new FakeSender();
    private final FakeStore store = new FakeStore();
    private final List<Duration> waits = new ArrayList<>();
    private final DeliverFinancialEmail deliver = new DeliverFinancialEmail(billing, patients, sender, store,
            waits::add, Duration.ofSeconds(2), Clock.fixed(NOW, ZoneOffset.UTC));

    DeliverFinancialEmailTest() {
        billing.summary = Optional.of(new InvoiceSummary(ISSUED.invoiceId(), "FAC-1", NOW, List.of("Limpieza"),
                "COP", "60000.00", "0.00", "60000.00", "ISSUED", null));
        billing.pdf = Optional.of(PDF);
        patients.contact = Optional.of(new Recipient("ana@example.com", "Ana"));
    }

    private static FinancialRequest payment() {
        return new FinancialRequest(UUID.randomUUID(), Kind.PAYMENT_RECORDED, ISSUED.invoiceId(),
                ISSUED.patientId(), UUID.randomUUID());
    }

    @Test
    void sendsTheEventTimeSummaryAndPdfOnceAndRecordsTheResult() {
        assertEquals(Result.SENT, deliver.handle(ISSUED));

        assertEquals(List.of(ISSUED.eventId(), ISSUED.eventId()), billing.sourceEvents);
        assertEquals(1, sender.sent.size());
        EmailMessage message = sender.sent.get(0);
        assertEquals(ISSUED.eventId(), message.deliveryId());
        assertEquals("ana@example.com", message.to());
        assertEquals(PDF.length, message.attachment().content().length);
        FinancialDelivery saved = store.deliveries.get(ISSUED.eventId());
        assertEquals(Delivery.Status.SENT, saved.status());
        assertEquals(ISSUED.invoiceId(), saved.invoiceId());
        assertEquals(Kind.INVOICE_ISSUED, saved.kind());
        assertEquals(1, saved.attempts());
        assertEquals("provider-1", saved.providerMessageId());
        assertNull(saved.failureCode());
        assertEquals(NOW, saved.occurredAt());
        assertEquals("RECORDED", store.processed.get(ISSUED.eventId()));
    }

    @Test
    void aRedeliveredEventSendsNothing() {
        deliver.handle(ISSUED);
        assertEquals(Result.DUPLICATE, deliver.handle(ISSUED));
        assertEquals(1, sender.sent.size());
    }

    @Test
    void aSavedButUnfinishedDeliveryIsCompletedWithoutResending() {
        store.deliveries.put(ISSUED.eventId(), new FinancialDelivery(ISSUED.eventId(), ISSUED.invoiceId(),
                Kind.INVOICE_ISSUED, Delivery.Status.SENT, 1, "provider-0", null, NOW));

        assertEquals(Result.SENT, deliver.handle(ISSUED));
        assertTrue(sender.sent.isEmpty());
        assertEquals("RECORDED", store.processed.get(ISSUED.eventId()));
    }

    @Test
    void missingSummaryPdfOrContactDiscardsWithoutSending() {
        billing.summary = Optional.empty();
        assertEquals(Result.DISCARDED, deliver.handle(ISSUED));
        billing.summary = Optional.of(billing.paymentSummary());
        billing.pdf = Optional.empty();
        assertEquals(Result.DISCARDED, deliver.handle(payment()));
        billing.pdf = Optional.of(PDF);
        patients.contact = Optional.empty();
        FinancialRequest third = payment();
        assertEquals(Result.DISCARDED, deliver.handle(third));

        assertTrue(sender.sent.isEmpty());
        assertEquals("DISCARDED", store.processed.get(third.eventId()));
    }

    @Test
    void transientProviderFailuresRetryWithBackoff() {
        sender.failures.add(new EmailDeliveryException("SMTP_UNAVAILABLE", false));
        sender.failures.add(new EmailDeliveryException("SMTP_UNAVAILABLE", false));

        assertEquals(Result.SENT, deliver.handle(ISSUED));
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), waits);
        assertEquals(3, store.deliveries.get(ISSUED.eventId()).attempts());
    }

    @Test
    void aFailedDeliveryIsRecordedAndNeverUndoesTheFinancialFact() {
        sender.failures.add(new EmailDeliveryException("RECIPIENT_REJECTED", true));

        assertEquals(Result.DELIVERY_FAILED, deliver.handle(ISSUED));
        FinancialDelivery saved = store.deliveries.get(ISSUED.eventId());
        assertEquals(Delivery.Status.DELIVERY_FAILED, saved.status());
        assertEquals(1, saved.attempts());
        assertEquals("RECIPIENT_REJECTED", saved.failureCode());
        assertEquals("RECORDED", store.processed.get(ISSUED.eventId()));
    }

    @Test
    void anUnavailableOwnerLeavesTheEventPendingForRetry() {
        billing.unavailable = true;
        assertThrows(OwnerUnavailableException.class, () -> deliver.handle(ISSUED));
        assertTrue(store.processed.isEmpty());
        assertTrue(store.deliveries.isEmpty());
    }

    @Test
    void requestsRejectMissingIdentifiers() {
        assertThrows(NullPointerException.class,
                () -> new FinancialRequest(null, Kind.INVOICE_ISSUED, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID()));
        assertEquals("InvoiceIssued", Kind.INVOICE_ISSUED.eventType());
        assertEquals("PaymentRecorded", Kind.PAYMENT_RECORDED.eventType());
    }

    private static final class FakeBilling implements BillingDeliveries {
        Optional<InvoiceSummary> summary;
        Optional<byte[]> pdf;
        boolean unavailable;
        final List<UUID> sourceEvents = new ArrayList<>();

        InvoiceSummary paymentSummary() {
            return new InvoiceSummary(ISSUED.invoiceId(), "FAC-1", NOW, List.of("Limpieza"), "COP", "60000.00",
                    "20000.00", "40000.00", "PARTIALLY_PAID", "20000.00");
        }

        @Override
        public Optional<InvoiceSummary> summary(UUID invoiceId, UUID sourceEventId) {
            if (unavailable) throw new OwnerUnavailableException("BILLING_UNAVAILABLE");
            sourceEvents.add(sourceEventId);
            return summary;
        }

        @Override
        public Optional<byte[]> pdf(UUID invoiceId, UUID sourceEventId) {
            sourceEvents.add(sourceEventId);
            return pdf;
        }
    }

    private static final class FakePatients implements PatientContacts {
        Optional<Recipient> contact;

        @Override
        public Optional<Recipient> contact(UUID patientId) {
            return contact;
        }
    }

    private static final class FakeSender implements EmailSender {
        final List<EmailMessage> sent = new ArrayList<>();
        final Deque<EmailDeliveryException> failures = new ArrayDeque<>();

        @Override
        public String send(EmailMessage message) {
            if (!failures.isEmpty()) throw failures.removeFirst();
            sent.add(message);
            return "provider-" + sent.size();
        }
    }

    private static final class FakeStore implements FinancialDeliveryStore {
        final Map<UUID, String> processed = new HashMap<>();
        final Map<UUID, FinancialDelivery> deliveries = new HashMap<>();

        @Override
        public boolean processed(UUID eventId) {
            return processed.containsKey(eventId);
        }

        @Override
        public Optional<FinancialDelivery> delivery(UUID eventId) {
            return Optional.ofNullable(deliveries.get(eventId));
        }

        @Override
        public void save(FinancialDelivery delivery) {
            deliveries.put(delivery.eventId(), delivery);
        }

        @Override
        public void complete(FinancialRequest request, String result) {
            processed.put(request.eventId(), result);
        }
    }
}
