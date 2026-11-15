package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Strict v1 InvoiceIssued and PaymentRecorded reading (dlc-docs/07-api/contracts/events). */
class FinancialEventReaderTest {
    static final String EVENT = "5b1f0e2a-7c3d-4e8f-9a1b-2c3d4e5f6a7b";
    static final String INVOICE = "0d9c8b7a-6f5e-4d3c-8b2a-1f0e9d8c7b6a";
    static final String PATIENT = "11111111-2222-4333-8444-555555555555";
    static final String CORRELATION = "99999999-8888-4777-8666-555555555555";
    private static final String ACTOR = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    private final FinancialEventReader reader = new FinancialEventReader();

    static String envelope(String type, String aggregateId, String payload) {
        return "{\"eventId\":\"" + EVENT + "\",\"eventType\":\"" + type + "\",\"aggregateId\":\"" + aggregateId
                + "\",\"aggregateType\":\"Invoice\",\"occurredAt\":\"2026-11-08T15:30:00Z\",\"version\":1,"
                + "\"producer\":\"billing-service\",\"payload\":" + payload + ",\"metadata\":{\"correlationId\":\""
                + CORRELATION + "\",\"causationId\":\"" + ACTOR + "\",\"actorUserId\":\"" + ACTOR + "\"}}";
    }

    static String issued() {
        return envelope("InvoiceIssued", INVOICE, "{\"invoiceId\":\"" + INVOICE + "\",\"patientId\":\"" + PATIENT
                + "\",\"total\":\"145000.00\",\"currency\":\"COP\",\"issuedAt\":\"2026-11-08T15:30:00Z\","
                + "\"issuedBy\":\"" + ACTOR + "\"}");
    }

    static String payment(String amount) {
        return envelope("PaymentRecorded", INVOICE, "{\"paymentId\":\"" + ACTOR + "\",\"invoiceId\":\"" + INVOICE
                + "\",\"patientId\":\"" + PATIENT + "\",\"amount\":\"" + amount + "\",\"currency\":\"COP\","
                + "\"remainingBalance\":\"100000.00\",\"method\":\"CASH\",\"recordedAt\":\"2026-11-08T16:00:00Z\","
                + "\"recordedBy\":\"" + ACTOR + "\"}");
    }

    private FinancialRequest read(String json) {
        return reader.read(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsAnIssuedInvoiceAsIdentifiersOnly() {
        FinancialRequest request = read(issued());

        assertThat(request.eventId()).isEqualTo(UUID.fromString(EVENT));
        assertThat(request.kind()).isEqualTo(Kind.INVOICE_ISSUED);
        assertThat(request.invoiceId()).isEqualTo(UUID.fromString(INVOICE));
        assertThat(request.patientId()).isEqualTo(UUID.fromString(PATIENT));
        assertThat(request.correlationId()).isEqualTo(UUID.fromString(CORRELATION));
    }

    @Test
    void acceptsTheOptionalAppointmentAndANullActor() {
        String json = issued().replace("\"total\"", "\"appointmentId\":\"" + ACTOR + "\",\"total\"")
                .replace("\"actorUserId\":\"" + ACTOR + "\"", "\"actorUserId\":null");
        assertThat(read(json).kind()).isEqualTo(Kind.INVOICE_ISSUED);
    }

    @Test
    void readsARecordedPayment() {
        assertThat(read(payment("45000.00")).kind()).isEqualTo(Kind.PAYMENT_RECORDED);
    }

    @Test
    void otherEventsAndVersionsAreUnsupported() {
        assertThatThrownBy(() -> read(issued().replace("InvoiceIssued", "InvoicePaid")))
                .hasMessage("UNSUPPORTED_EVENT");
        assertThatThrownBy(() -> read(issued().replace("\"version\":1", "\"version\":2")))
                .hasMessage("UNSUPPORTED_EVENT");
    }

    @Test
    void contractDeviationsAreInvalid() {
        String[] invalid = {
            issued().replace("\"producer\":\"billing-service\"", "\"producer\":\"other\""),
            issued().replace("\"aggregateType\":\"Invoice\"", "\"aggregateType\":\"Payment\""),
            envelope("InvoiceIssued", ACTOR, issued().substring(issued().indexOf("\"payload\":") + 10,
                    issued().indexOf(",\"metadata\""))),
            issued().replace("\"145000.00\"", "\"145000\""),
            issued().replace("\"currency\":\"COP\"", "\"currency\":\"USD\""),
            issued().replace("\"issuedBy\"", "\"extra\":1,\"issuedBy\""),
            issued().replace("\"occurredAt\":\"2026-11-08T15:30:00Z\"", "\"occurredAt\":\"yesterday\""),
            payment("45000.00").replace("\"method\":\"CASH\"", "\"method\":\"\""),
            payment("-1.00"),
            issued().replace("\"version\":1", "\"version\":\"1\""),
        };
        for (String json : invalid) {
            assertThatThrownBy(() -> read(json)).as(json).hasMessage("INVALID_EVENT");
        }
        assertThatThrownBy(() -> read("{not json")).hasMessage("MALFORMED_EVENT");
        assertThatThrownBy(() -> read("[]")).hasMessage("MALFORMED_EVENT");
    }
}
