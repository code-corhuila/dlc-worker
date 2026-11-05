package co.edu.corhuila.dlc.worker.application.usecase;

import co.edu.corhuila.dlc.worker.application.model.Delivery;
import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import co.edu.corhuila.dlc.worker.application.model.ReminderDetails;
import co.edu.corhuila.dlc.worker.application.model.ReminderOutcome;
import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import co.edu.corhuila.dlc.worker.application.port.out.AppointmentNotifications;
import co.edu.corhuila.dlc.worker.application.port.out.Backoff;
import co.edu.corhuila.dlc.worker.application.port.out.DeliveryStore;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import co.edu.corhuila.dlc.worker.application.port.out.EmailSender;
import co.edu.corhuila.dlc.worker.application.port.out.PatientContacts;
import co.edu.corhuila.dlc.worker.domain.ReminderTemplate;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Delivers one informational reminder (ADR-005). Appointments decides why and when; this use case only checks that
 * the requested version is still the live one, resolves the recipient, makes at most three provider attempts with
 * exponential backoff and reports the final result. The result is saved before it is reported, so a redelivered
 * message never sends the email twice. A failure never changes anything in the owner.
 */
public final class DeliverReminder {
    static final int MAX_ATTEMPTS = 3;
    private static final Set<String> REMINDABLE = Set.of("PROGRAMADA", "CONFIRMADA");

    public enum Result { DUPLICATE, DISCARDED, SENT_REPORTED, FAILED_REPORTED, OUTCOME_REJECTED }

    private final AppointmentNotifications appointments;
    private final PatientContacts patients;
    private final EmailSender sender;
    private final DeliveryStore store;
    private final Backoff backoff;
    private final Duration firstDelay;
    private final Clock clock;

    public DeliverReminder(AppointmentNotifications appointments, PatientContacts patients, EmailSender sender,
            DeliveryStore store, Backoff backoff, Duration firstDelay, Clock clock) {
        this.appointments = Objects.requireNonNull(appointments, "appointments");
        this.patients = Objects.requireNonNull(patients, "patients");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.store = Objects.requireNonNull(store, "store");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.firstDelay = Objects.requireNonNull(firstDelay, "firstDelay");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result handle(ReminderRequest request) {
        if (store.processed(request.eventId())) return Result.DUPLICATE;
        Optional<Delivery> previous = store.delivery(request.deliveryId());
        Delivery delivery;
        if (previous.isPresent()) {
            delivery = previous.get();
        } else {
            Optional<ReminderDetails> details = appointments.details(request.appointmentId());
            if (details.isEmpty() || !current(details.get(), request)) return discard(request);
            Optional<Recipient> recipient = patients.contact(request.patientId());
            if (recipient.isEmpty()) return discard(request);
            delivery = attempt(request, ReminderTemplate.compose(request.deliveryId(), recipient.get(),
                    details.get().startAt()));
            store.save(delivery);
        }
        if (!appointments.report(request.appointmentId(), ReminderOutcome.of(delivery))) {
            store.complete(request, "REJECTED");
            return Result.OUTCOME_REJECTED;
        }
        store.complete(request, "REPORTED");
        return delivery.status() == Delivery.Status.SENT ? Result.SENT_REPORTED : Result.FAILED_REPORTED;
    }

    private static boolean current(ReminderDetails details, ReminderRequest request) {
        return details.appointmentVersion() == request.appointmentVersion()
                && details.patientId().equals(request.patientId())
                && REMINDABLE.contains(details.status());
    }

    private Result discard(ReminderRequest request) {
        store.complete(request, "DISCARDED");
        return Result.DISCARDED;
    }

    private Delivery attempt(ReminderRequest request, EmailMessage message) {
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

    private Delivery delivery(ReminderRequest request, Delivery.Status status, int attempts, String reference,
            String failureCode) {
        return new Delivery(request.deliveryId(), request.eventId(), request.appointmentId(),
                request.appointmentVersion(), status, attempts, reference, failureCode, clock.instant());
    }
}
