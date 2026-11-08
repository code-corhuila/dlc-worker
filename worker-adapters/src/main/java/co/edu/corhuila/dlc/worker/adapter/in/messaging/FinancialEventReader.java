package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict reader of the v1 Billing InvoiceIssued and PaymentRecorded schemas (dlc-docs/07-api/contracts/events):
 * required fields, no additional property, constant type/producer/aggregate, UUID, RFC 3339 and amount formats,
 * and aggregateId equal to the payload's invoiceId. Failures carry only a safe code, never message content.
 */
public final class FinancialEventReader {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern AMOUNT = Pattern.compile("[0-9]+\\.[0-9]{2}");
    private static final Map<String, Kind> KINDS =
            Map.of(Kind.INVOICE_ISSUED.eventType(), Kind.INVOICE_ISSUED,
                    Kind.PAYMENT_RECORDED.eventType(), Kind.PAYMENT_RECORDED);
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "aggregateId", "aggregateType",
            "occurredAt", "version", "producer", "payload", "metadata");
    private static final Set<String> METADATA = Set.of("correlationId", "causationId", "actorUserId");
    private static final Set<String> ISSUED = Set.of("invoiceId", "patientId", "appointmentId", "total", "currency",
            "issuedAt", "issuedBy");
    private static final Set<String> PAYMENT = Set.of("paymentId", "invoiceId", "patientId", "amount", "currency",
            "remainingBalance", "method", "recordedAt", "recordedBy");

    public FinancialRequest read(byte[] body) {
        JsonNode event;
        try {
            event = JSON.readTree(Objects.requireNonNull(body, "body"));
        } catch (IOException malformed) {
            throw new InvalidEventException("MALFORMED_EVENT");
        }
        if (event == null || !event.isObject()) throw new InvalidEventException("MALFORMED_EVENT");
        JsonNode type = event.path("eventType");
        JsonNode version = event.path("version");
        if (type.isTextual() && !KINDS.containsKey(type.asText())
                || version.isIntegralNumber() && version.asLong() != 1) {
            throw new InvalidEventException("UNSUPPORTED_EVENT");
        }
        if (!type.isTextual() || !version.isIntegralNumber()) throw invalid();
        Kind kind = KINDS.get(type.asText());
        exactly(event, ENVELOPE, ENVELOPE.size());
        text(event, "aggregateType", "Invoice");
        text(event, "producer", "billing-service");
        instant(event, "occurredAt");
        JsonNode metadata = exactly(event.get("metadata"), METADATA, METADATA.size());
        uuid(metadata, "causationId");
        if (!metadata.get("actorUserId").isNull()) uuid(metadata, "actorUserId");
        JsonNode payload = kind == Kind.INVOICE_ISSUED ? issued(event.get("payload")) : payment(event.get("payload"));
        text(payload, "currency", "COP");
        UUID invoiceId = uuid(payload, "invoiceId");
        if (!invoiceId.equals(uuid(event, "aggregateId"))) throw invalid();
        return new FinancialRequest(uuid(event, "eventId"), kind, invoiceId, uuid(payload, "patientId"),
                uuid(metadata, "correlationId"));
    }

    private static JsonNode issued(JsonNode payload) {
        boolean withAppointment = payload != null && payload.has("appointmentId");
        exactly(payload, ISSUED, withAppointment ? ISSUED.size() : ISSUED.size() - 1);
        if (withAppointment) uuid(payload, "appointmentId");
        amount(payload, "total");
        instant(payload, "issuedAt");
        uuid(payload, "issuedBy");
        return payload;
    }

    private static JsonNode payment(JsonNode payload) {
        exactly(payload, PAYMENT, PAYMENT.size());
        uuid(payload, "paymentId");
        amount(payload, "amount");
        amount(payload, "remainingBalance");
        if (!payload.get("method").isTextual() || payload.get("method").asText().isEmpty()) throw invalid();
        instant(payload, "recordedAt");
        uuid(payload, "recordedBy");
        return payload;
    }

    private static JsonNode exactly(JsonNode node, Set<String> fields, int size) {
        if (node == null || !node.isObject() || node.size() != size) throw invalid();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!fields.contains(names.next())) throw invalid();
        }
        return node;
    }

    private static void text(JsonNode node, String field, String expected) {
        if (!node.get(field).isTextual() || !expected.equals(node.get(field).asText())) throw invalid();
    }

    private static void amount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (!value.isTextual() || !AMOUNT.matcher(value.asText()).matches()) throw invalid();
    }

    private static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !UUID_TEXT.matcher(value.asText()).matches()) throw invalid();
        return UUID.fromString(value.asText());
    }

    private static void instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        try {
            OffsetDateTime.parse(value.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException notRfc3339) {
            throw invalid();
        }
    }

    private static InvalidEventException invalid() {
        return new InvalidEventException("INVALID_EVENT");
    }
}
