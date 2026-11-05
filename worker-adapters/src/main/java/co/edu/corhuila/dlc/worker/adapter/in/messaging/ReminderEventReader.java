package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import co.edu.corhuila.dlc.worker.application.model.ReminderRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict reader of the v1 AppointmentReminderRequested schema (dlc-docs/07-api/contracts/events): every required
 * field, no additional property, constant type/producer/aggregate, UUID and RFC 3339 formats, and aggregateId equal
 * to the payload's appointmentId. Failures carry only a safe code, never message content.
 */
public final class ReminderEventReader {
    static final String EVENT_TYPE = "AppointmentReminderRequested";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "aggregateId", "aggregateType",
            "occurredAt", "version", "producer", "payload", "metadata");
    private static final Set<String> METADATA = Set.of("correlationId", "causationId", "actorUserId");
    private static final Set<String> PAYLOAD = Set.of("appointmentId", "patientId", "startAt",
            "appointmentVersion", "deliveryId", "requestedAt");

    public ReminderRequest read(byte[] body) {
        JsonNode event;
        try {
            event = JSON.readTree(Objects.requireNonNull(body, "body"));
        } catch (IOException malformed) {
            throw new InvalidEventException("MALFORMED_EVENT");
        }
        if (event == null || !event.isObject()) throw new InvalidEventException("MALFORMED_EVENT");
        JsonNode type = event.path("eventType");
        JsonNode version = event.path("version");
        if (type.isTextual() && !EVENT_TYPE.equals(type.asText())
                || version.isIntegralNumber() && version.asLong() != 1) {
            throw new InvalidEventException("UNSUPPORTED_EVENT");
        }
        if (!type.isTextual() || !version.isIntegralNumber()) throw invalid();
        exactly(event, ENVELOPE);
        text(event, "aggregateType", "Appointment");
        text(event, "producer", "appointments-service");
        instant(event, "occurredAt");
        JsonNode metadata = exactly(event.get("metadata"), METADATA);
        uuid(metadata, "causationId");
        if (!metadata.get("actorUserId").isNull()) uuid(metadata, "actorUserId");
        JsonNode payload = exactly(event.get("payload"), PAYLOAD);
        instant(payload, "requestedAt");
        JsonNode appointmentVersion = payload.get("appointmentVersion");
        if (!appointmentVersion.isIntegralNumber() || !appointmentVersion.canConvertToLong()
                || appointmentVersion.asLong() < 1) {
            throw invalid();
        }
        UUID appointmentId = uuid(payload, "appointmentId");
        if (!appointmentId.equals(uuid(event, "aggregateId"))) throw invalid();
        return new ReminderRequest(uuid(event, "eventId"), uuid(payload, "deliveryId"), appointmentId,
                uuid(payload, "patientId"), appointmentVersion.asLong(), instant(payload, "startAt"),
                uuid(metadata, "correlationId"));
    }

    private static JsonNode exactly(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!fields.contains(names.next())) throw invalid();
        }
        return node;
    }

    private static void text(JsonNode node, String field, String expected) {
        if (!node.get(field).isTextual() || !expected.equals(node.get(field).asText())) throw invalid();
    }

    private static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !UUID_TEXT.matcher(value.asText()).matches()) throw invalid();
        return UUID.fromString(value.asText());
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        try {
            return OffsetDateTime.parse(value.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException notRfc3339) {
            throw invalid();
        }
    }

    private static InvalidEventException invalid() {
        return new InvalidEventException("INVALID_EVENT");
    }
}
