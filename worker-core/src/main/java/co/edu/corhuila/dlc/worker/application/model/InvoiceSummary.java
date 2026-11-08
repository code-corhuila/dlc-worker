package co.edu.corhuila.dlc.worker.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Billing's minimized event-time financial summary (InvoiceNotificationSummary). Amounts stay as Billing's decimal
 * strings; the worker never calculates money. {@code recentPaymentAmount} is present for PaymentRecorded only.
 */
public record InvoiceSummary(UUID invoiceId, String invoiceNumber, Instant issuedAt, List<String> concepts,
        String currency, String total, String paidTotal, String remainingBalance, String status,
        String recentPaymentAmount) {
    public InvoiceSummary {
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(invoiceNumber, "invoiceNumber");
        Objects.requireNonNull(issuedAt, "issuedAt");
        concepts = List.copyOf(Objects.requireNonNull(concepts, "concepts"));
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(paidTotal, "paidTotal");
        Objects.requireNonNull(remainingBalance, "remainingBalance");
        Objects.requireNonNull(status, "status");
    }

    @Override
    public String toString() {
        return "InvoiceSummary[invoiceId=" + invoiceId + ", content redacted]";
    }
}
