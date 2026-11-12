package co.edu.corhuila.dlc.worker.application.usecase;

import co.edu.corhuila.dlc.worker.application.model.ScheduledRequest;
import co.edu.corhuila.dlc.worker.application.port.out.AppointmentNotifications;
import co.edu.corhuila.dlc.worker.application.port.out.Backoff;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import co.edu.corhuila.dlc.worker.application.port.out.EmailSender;
import co.edu.corhuila.dlc.worker.application.port.out.PatientContacts;
import co.edu.corhuila.dlc.worker.application.port.out.ScheduledDeliveryStore;
import co.edu.corhuila.dlc.worker.domain.ScheduledTemplate;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/** Sends a scheduling notice without modifying the later reminder's state. */
public final class DeliverScheduledEmail {
    public enum Result { DUPLICATE, DISCARDED, SENT, DELIVERY_FAILED }
    private final AppointmentNotifications appointments;
    private final PatientContacts patients;
    private final EmailSender sender;
    private final ScheduledDeliveryStore store;
    private final Backoff backoff;
    private final Duration delay;
    private final Clock clock;

    public DeliverScheduledEmail(AppointmentNotifications appointments, PatientContacts patients, EmailSender sender,
            ScheduledDeliveryStore store, Backoff backoff, Duration delay, Clock clock) {
        this.appointments = appointments; this.patients = patients; this.sender = sender;
        this.store = store; this.backoff = backoff; this.delay = delay; this.clock = clock;
    }

    public Result handle(ScheduledRequest request) {
        if (store.processed(request.eventId())) return Result.DUPLICATE;
        var previous = store.delivery(request.eventId());
        ScheduledDeliveryStore.Outcome outcome;
        if (previous.isPresent()) {
            outcome = previous.get();
        } else {
            var details = appointments.details(request.appointmentId());
            if (details.isEmpty() || !details.get().patientId().equals(request.patientId())
                    || !details.get().startAt().equals(request.startAt())
                    || !Set.of("PROGRAMADA", "CONFIRMADA").contains(details.get().status())) return discard(request);
            var recipient = patients.contact(request.patientId());
            if (recipient.isEmpty()) return discard(request);
            var message = ScheduledTemplate.compose(request.eventId(), recipient.get(), request.startAt());
            Duration next = delay;
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    outcome = new ScheduledDeliveryStore.Outcome("SENT", attempt, sender.send(message), null, clock.instant());
                    break;
                } catch (EmailDeliveryException failure) {
                    if (failure.permanent() || attempt == 3) {
                        outcome = new ScheduledDeliveryStore.Outcome("DELIVERY_FAILED", attempt, null, failure.failureCode(), clock.instant());
                        break;
                    }
                    backoff.await(next);
                    next = next.multipliedBy(2);
                }
            }
            store.save(request, outcome);
        }
        store.complete(request, "RECORDED");
        return "SENT".equals(outcome.status()) ? Result.SENT : Result.DELIVERY_FAILED;
    }

    private Result discard(ScheduledRequest request) {
        store.complete(request, "DISCARDED");
        return Result.DISCARDED;
    }
}
