package co.edu.corhuila.dlc.worker.application.model;

import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The final result of one financial email, keyed by the Billing eventId and saved before the event is marked
 * processed. Billing receives no report: a failure is recorded here and never undoes issuance or payment.
 * Holds no recipient address, amounts or message body.
 */
public record FinancialDelivery(UUID eventId, UUID invoiceId, Kind kind, Delivery.Status status, int attempts,
        String providerMessageId, String failureCode, Instant occurredAt) {
    public FinancialDelivery {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (attempts < 1) throw new IllegalArgumentException("attempts must be at least 1.");
    }
}
