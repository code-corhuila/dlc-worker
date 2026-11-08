package co.edu.corhuila.dlc.worker.application.port.out;

import co.edu.corhuila.dlc.worker.application.model.InvoiceSummary;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing private contracts GET /internal/v1/invoices/{id}/notification-summary and /pdf, both as of the triggering
 * {@code sourceEventId}. Empty when Billing answers 404; {@link OwnerUnavailableException} when it is unavailable.
 */
public interface BillingDeliveries {
    Optional<InvoiceSummary> summary(UUID invoiceId, UUID sourceEventId);

    Optional<byte[]> pdf(UUID invoiceId, UUID sourceEventId);
}
