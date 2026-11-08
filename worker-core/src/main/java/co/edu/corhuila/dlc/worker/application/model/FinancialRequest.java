package co.edu.corhuila.dlc.worker.application.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A Billing InvoiceIssued or PaymentRecorded fact as the worker needs it (ADR-005, HU-BIL-002/003): identifiers only.
 * The {@code eventId} is the delivery key and the {@code sourceEventId} Billing uses to rebuild the event-time summary
 * and PDF, so each financial fact produces at most one email.
 */
public record FinancialRequest(UUID eventId, Kind kind, UUID invoiceId, UUID patientId, UUID correlationId) {
    public enum Kind {
        INVOICE_ISSUED("InvoiceIssued"),
        PAYMENT_RECORDED("PaymentRecorded");

        private final String eventType;

        Kind(String eventType) {
            this.eventType = eventType;
        }

        public String eventType() {
            return eventType;
        }
    }

    public FinancialRequest {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
