package co.edu.corhuila.dlc.worker.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.InvoiceSummary;
import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Billing notification-summary and PDF contracts (internal-coordination.yaml), against a double of the contract. */
class HttpBillingDeliveriesTest {
    private static final String AUDIENCE = "billing-service";
    private static final UUID INVOICE = UUID.fromString("0d9c8b7a-6f5e-4d3c-8b2a-1f0e9d8c7b6a");
    private static final UUID EVENT = UUID.fromString("5b1f0e2a-7c3d-4e8f-9a1b-2c3d4e5f6a7b");
    private static final String SUMMARY = "/internal/v1/invoices/" + INVOICE + "/notification-summary";
    private static final String PDF = "/internal/v1/invoices/" + INVOICE + "/pdf";

    @TempDir
    Path secrets;
    private FakeOwner owner;
    private HttpBillingDeliveries client;

    @BeforeEach
    void start() throws Exception {
        owner = new FakeOwner(AUDIENCE);
        owner.requiredScopes.put("/notification-summary", "billing:notification-summary:read");
        owner.requiredScopes.put("/pdf", "billing:invoice-pdf:read");
        client = new HttpBillingDeliveries(owner.baseUrl(), new FileServiceToken(FakeOwner.tokenFile(secrets,
                "worker-to-billing", FakeOwner.token(AUDIENCE,
                        "billing:notification-summary:read billing:invoice-pdf:read"))), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        owner.close();
    }

    private static String summary(String invoiceId, String extra) {
        return "{\"invoiceId\":\"" + invoiceId + "\",\"invoiceNumber\":\"FAC-2026-0007\",\"issuedAt\":"
                + "\"2026-11-08T15:30:00Z\",\"concepts\":[\"Resina\",\"Profilaxis\"],\"currency\":\"COP\","
                + "\"total\":\"145000.00\",\"paidTotal\":\"45000.00\",\"remainingBalance\":\"100000.00\","
                + "\"status\":\"PARTIALLY_PAID\"" + extra + "}";
    }

    @Test
    void readsTheEventTimeSummaryWithItsOwnScope() {
        owner.replies.put(SUMMARY, new FakeOwner.Reply(200, summary(INVOICE.toString(),
                ",\"recentPaymentAmount\":\"45000.00\"")));

        InvoiceSummary read = client.summary(INVOICE, EVENT).orElseThrow();

        assertThat(read.invoiceId()).isEqualTo(INVOICE);
        assertThat(read.invoiceNumber()).isEqualTo("FAC-2026-0007");
        assertThat(read.issuedAt()).isEqualTo(Instant.parse("2026-11-08T15:30:00Z"));
        assertThat(read.concepts()).isEqualTo(List.of("Resina", "Profilaxis"));
        assertThat(read.paidTotal()).isEqualTo("45000.00");
        assertThat(read.remainingBalance()).isEqualTo("100000.00");
        assertThat(read.status()).isEqualTo("PARTIALLY_PAID");
        assertThat(read.recentPaymentAmount()).isEqualTo("45000.00");
        assertThat(owner.calls.get(0).query()).isEqualTo("sourceEventId=" + EVENT);
    }

    @Test
    void anIssuedSummaryHasNoRecentPayment() {
        owner.replies.put(SUMMARY, new FakeOwner.Reply(200, summary(INVOICE.toString(), "")));

        assertThat(client.summary(INVOICE, EVENT).orElseThrow().recentPaymentAmount()).isNull();
    }

    @Test
    void readsTheEventTimePdfAsBytes() {
        owner.replies.put(PDF, FakeOwner.Reply.pdf("%PDF-1.4 test"));

        assertThat(client.pdf(INVOICE, EVENT)).hasValueSatisfying(
                bytes -> assertThat(new String(bytes, StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.4 test"));
        assertThat(owner.calls.get(0).query()).isEqualTo("sourceEventId=" + EVENT);
    }

    @Test
    void anUnknownInvoiceOrEventIsEmpty() {
        owner.replies.put(SUMMARY, new FakeOwner.Reply(404, "{\"error\":\"NOT_FOUND\"}"));
        owner.replies.put(PDF, new FakeOwner.Reply(404, "{\"error\":\"NOT_FOUND\"}"));

        assertThat(client.summary(INVOICE, EVENT)).isEmpty();
        assertThat(client.pdf(INVOICE, EVENT)).isEmpty();
    }

    @Test
    void anotherInvoiceAMalformedReplyOrAnOutageIsTransient() {
        owner.replies.put(SUMMARY, new FakeOwner.Reply(200, summary(UUID.randomUUID().toString(), "")));
        assertThatThrownBy(() -> client.summary(INVOICE, EVENT)).isInstanceOf(OwnerUnavailableException.class)
                .hasMessage("BILLING_CONTRACT_MISMATCH");

        owner.replies.put(SUMMARY, FakeOwner.Reply.offContract(200, "{\"invoiceId\":\"" + INVOICE + "\"}"));
        assertThatThrownBy(() -> client.summary(INVOICE, EVENT)).isInstanceOf(OwnerUnavailableException.class);

        owner.replies.put(SUMMARY, FakeOwner.Reply.offContract(200, summary(INVOICE.toString(), "")
                .replace("[\"Resina\",\"Profilaxis\"]", "\"Resina\"")));
        assertThatThrownBy(() -> client.summary(INVOICE, EVENT)).isInstanceOf(OwnerUnavailableException.class);

        owner.replies.put(PDF, new FakeOwner.Reply(503, ""));
        assertThatThrownBy(() -> client.pdf(INVOICE, EVENT)).isInstanceOf(OwnerUnavailableException.class)
                .hasMessage("BILLING_UNAVAILABLE");

        owner.replies.put(PDF, FakeOwner.Reply.offContract(200, ""));
        assertThatThrownBy(() -> client.pdf(INVOICE, EVENT)).isInstanceOf(OwnerUnavailableException.class);
    }
}
