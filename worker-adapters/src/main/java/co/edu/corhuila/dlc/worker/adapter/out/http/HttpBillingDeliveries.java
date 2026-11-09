package co.edu.corhuila.dlc.worker.adapter.out.http;

import co.edu.corhuila.dlc.worker.application.model.InvoiceSummary;
import co.edu.corhuila.dlc.worker.application.port.out.BillingDeliveries;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing private contracts GET /internal/v1/invoices/{id}/notification-summary (scope
 * billing:notification-summary:read) and /pdf (scope billing:invoice-pdf:read), both rebuilt by Billing as of the
 * triggering sourceEventId. The worker never stores or recalculates them; they live in memory for one delivery.
 */
public final class HttpBillingDeliveries implements BillingDeliveries {
    private final OwnerClient client;

    public HttpBillingDeliveries(String baseUrl, ServiceToken token, Duration timeout) {
        this.client = new OwnerClient("BILLING", baseUrl, token, timeout);
    }

    @Override
    public Optional<InvoiceSummary> summary(UUID invoiceId, UUID sourceEventId) {
        HttpResponse<String> response = client.get(path(invoiceId, "notification-summary", sourceEventId));
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw client.unexpected(response);
        JsonNode body = client.body(response);
        if (!invoiceId.equals(client.uuid(body, "invoiceId"))) throw client.unavailable("CONTRACT_MISMATCH");
        JsonNode concepts = body.get("concepts");
        if (concepts == null || !concepts.isArray()) throw client.unavailable("CONTRACT_MISMATCH");
        List<String> labels = new ArrayList<>();
        for (JsonNode concept : concepts) {
            if (!concept.isTextual()) throw client.unavailable("CONTRACT_MISMATCH");
            labels.add(concept.asText());
        }
        String recent = body.hasNonNull("recentPaymentAmount") ? client.text(body, "recentPaymentAmount") : null;
        return Optional.of(new InvoiceSummary(invoiceId, client.text(body, "invoiceNumber"),
                client.instant(body, "issuedAt"), labels, client.text(body, "currency"), client.text(body, "total"),
                client.text(body, "paidTotal"), client.text(body, "remainingBalance"), client.text(body, "status"),
                recent));
    }

    @Override
    public Optional<byte[]> pdf(UUID invoiceId, UUID sourceEventId) {
        HttpResponse<byte[]> response = client.getBytes(path(invoiceId, "pdf", sourceEventId), "application/pdf");
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw client.unexpected(response);
        if (response.body().length == 0) throw client.unavailable("CONTRACT_MISMATCH");
        return Optional.of(response.body());
    }

    private static String path(UUID invoiceId, String representation, UUID sourceEventId) {
        return "/internal/v1/invoices/" + invoiceId + "/" + representation + "?sourceEventId=" + sourceEventId;
    }
}
