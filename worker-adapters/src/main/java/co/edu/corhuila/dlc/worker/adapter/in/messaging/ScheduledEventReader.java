package co.edu.corhuila.dlc.worker.adapter.in.messaging;

import co.edu.corhuila.dlc.worker.application.model.ScheduledRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Strictly validates the existing AppointmentScheduled v1 contract. */
public final class ScheduledEventReader {
    public static final String EVENT_TYPE = "AppointmentScheduled";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "aggregateId", "aggregateType",
            "occurredAt", "version", "producer", "payload", "metadata");
    private static final Set<String> PAYLOAD = Set.of("appointmentId", "patientId", "dentistId", "startAt",
            "endAt", "reminderDueAt", "scheduledBy");
    private static final Set<String> METADATA = Set.of("correlationId", "causationId", "actorUserId");

    public ScheduledRequest read(byte[] body) {
        try {
            JsonNode event = exactly(JSON.readTree(body), ENVELOPE);
            if (!EVENT_TYPE.equals(event.path("eventType").asText()) || !event.path("version").isIntegralNumber()
                    || event.path("version").asInt() != 1 || !"Appointment".equals(event.path("aggregateType").asText())
                    || !"appointments-service".equals(event.path("producer").asText())) throw invalid();
            instant(event, "occurredAt");
            JsonNode payload = exactly(event.get("payload"), PAYLOAD);
            JsonNode metadata = exactly(event.get("metadata"), METADATA);
            UUID appointment = uuid(payload, "appointmentId");
            if (!appointment.equals(uuid(event, "aggregateId"))) throw invalid();
            uuid(payload, "dentistId");
            uuid(payload, "scheduledBy");
            uuid(metadata, "causationId");
            if (!metadata.get("actorUserId").isNull()) uuid(metadata, "actorUserId");
            instant(payload, "endAt");
            instant(payload, "reminderDueAt");
            return new ScheduledRequest(uuid(event, "eventId"), appointment, uuid(payload, "patientId"),
                    instant(payload, "startAt"), uuid(metadata, "correlationId"));
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static JsonNode exactly(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        for (Iterator<String> names = node.fieldNames(); names.hasNext();) if (!fields.contains(names.next())) throw invalid();
        return node;
    }

    private static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !value.asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString(value.asText());
    }

    private static java.time.Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        try { return OffsetDateTime.parse(value.asText()).toInstant(); }
        catch (DateTimeParseException malformed) { throw invalid(); }
    }

    private static InvalidEventException invalid() { return new InvalidEventException("INVALID_EVENT"); }
}
