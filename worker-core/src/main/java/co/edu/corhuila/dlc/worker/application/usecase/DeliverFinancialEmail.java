package co.edu.corhuila.dlc.worker.application.usecase;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.FinancialDelivery;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import co.edu.corhuila.dlc.worker.application.model.InvoiceSummary;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import co.edu.corhuila.dlc.worker.application.port.out.Backoff;
import co.edu.corhuila.dlc.worker.application.port.out.BillingDeliveries;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import co.edu.corhuila.dlc.worker.application.port.out.EmailSender;
import co.edu.corhuila.dlc.worker.application.port.out.FinancialDeliveryStore;
import co.edu.corhuila.dlc.worker.application.port.out.PatientContacts;
import co.edu.corhuila.dlc.worker.domain.FinancialTemplate;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Delivers one invoice or payment email (ADR-005, HU-BIL-002/003). Billing decides why and when by publishing the
 * fact; this use case takes Billing's summary and PDF as of that event, resolves the recipient through Patients,
 * makes at most three provider attempts with exponential backoff and records the result before the event is marked
 * processed. A delivery failure is recorded only: it never changes the invoice or the payment.
 */
public final class DeliverFinancialEmail {
    static final int MAX_ATTEMPTS = 3;

    public enum Result { DUPLICATE, DISCARDED, SENT, DELIVERY_FAILED }

    private final BillingDeliveries billing;
    private final PatientContacts patients;
    private final EmailSender sender;
    private final FinancialDeliveryStore store;
    private final Backoff backoff;
    private final Duration firstDelay;
    private final Clock clock;

    public DeliverFinancialEmail(BillingDeliveries billing, PatientContacts patients, EmailSender sender,
            FinancialDeliveryStore store, Backoff backoff, Duration firstDelay, Clock clock) {
        this.billing = Objects.requireNonNull(billing, "billing");
        this.patients = Objects.requireNonNull(patients, "patients");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.store = Objects.requireNonNull(store, "store");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.firstDelay = Objects.requireNonNull(firstDelay, "firstDelay");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result handle(FinancialRequest request) {
        if (store.processed(request.eventId())) return Result.DUPLICATE;
        Optional<FinancialDelivery> previous = store.delivery(request.eventId());
        FinancialDelivery delivery;
        if (previous.isPresent()) {
            delivery = previous.get();
        } else {
            Optional<InvoiceSummary> summary = billing.summary(request.invoiceId(), request.eventId());
            if (summary.isEmpty()) return discard(request);
            Optional<byte[]> pdf = billing.pdf(request.invoiceId(), request.eventId());
            if (pdf.isEmpty()) return discard(request);
            Optional<Recipient> recipient = patients.contact(request.patientId());
            if (recipient.isEmpty()) return discard(request);
            delivery = attempt(request, FinancialTemplate.compose(request.eventId(), recipient.get(), summary.get(),
                    request.kind(), pdf.get()));
            store.save(delivery);
        }
        store.complete(request, "RECORDED");
        return delivery.status() == Delivery.Status.SENT ? Result.SENT : Result.DELIVERY_FAILED;
    }

    private Result discard(FinancialRequest request) {
        store.complete(request, "DISCARDED");
        return Result.DISCARDED;
    }

    private FinancialDelivery attempt(FinancialRequest request, EmailMessage message) {
        Duration delay = firstDelay;
        for (int attempt = 1; ; attempt++) {
            try {
                String reference = sender.send(message);
                return delivery(request, Delivery.Status.SENT, attempt, reference, null);
            } catch (EmailDeliveryException failure) {
                if (failure.permanent() || attempt == MAX_ATTEMPTS) {
                    return delivery(request, Delivery.Status.DELIVERY_FAILED, attempt, null, failure.failureCode());
                }
                backoff.await(delay);
                delay = delay.multipliedBy(2);
            }
        }
    }

    private FinancialDelivery delivery(FinancialRequest request, Delivery.Status status, int attempts,
            String reference, String failureCode) {
        return new FinancialDelivery(request.eventId(), request.invoiceId(), request.kind(), status, attempts,
                reference, failureCode, clock.instant());
    }
}
