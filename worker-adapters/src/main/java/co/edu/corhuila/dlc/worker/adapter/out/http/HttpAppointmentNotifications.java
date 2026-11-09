package co.edu.corhuila.dlc.worker.adapter.out.http;

import co.edu.corhuila.dlc.worker.application.model.ReminderDetails;
import co.edu.corhuila.dlc.worker.application.model.ReminderOutcome;
import co.edu.corhuila.dlc.worker.application.port.out.AppointmentNotifications;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Appointments private contract for the worker (internal-coordination.yaml): GET .../details with scope
 * appointments:notification-details:read and POST .../outcomes with appointments:notification-outcome:write.
 * 404 on details means the appointment no longer exists; 409 on outcomes (STALE_VERSION, IDEMPOTENCY_CONFLICT) is a
 * final rejection that is never retried.
 */
public final class HttpAppointmentNotifications implements AppointmentNotifications {
    private static final String BASE = "/internal/v1/appointment-notifications/";
    private final OwnerClient client;

    public HttpAppointmentNotifications(String baseUrl, ServiceToken token, Duration timeout) {
        this.client = new OwnerClient("APPOINTMENTS", baseUrl, token, timeout);
    }

    @Override
    public Optional<ReminderDetails> details(UUID appointmentId) {
        HttpResponse<String> response = client.get(BASE + appointmentId + "/details");
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw client.unexpected(response);
        JsonNode body = client.body(response);
        JsonNode version = body.get("appointmentVersion");
        if (version == null || !version.isIntegralNumber() || version.asLong() < 1) {
            throw client.unavailable("CONTRACT_MISMATCH");
        }
        UUID id = client.uuid(body, "appointmentId");
        if (!id.equals(appointmentId)) throw client.unavailable("CONTRACT_MISMATCH");
        return Optional.of(new ReminderDetails(id, client.uuid(body, "patientId"), version.asLong(),
                client.instant(body, "startAt"), client.instant(body, "endAt"), client.text(body, "status")));
    }

    @Override
    public boolean report(UUID appointmentId, ReminderOutcome outcome) {
        ObjectNode body = OwnerClient.JSON.createObjectNode()
                .put("deliveryId", outcome.deliveryId().toString())
                .put("appointmentVersion", outcome.appointmentVersion())
                .put("notificationType", "REMINDER")
                .put("status", outcome.status().name())
                .put("attempts", outcome.attempts())
                .put("occurredAt", outcome.occurredAt().toString());
        if (outcome.providerMessageId() != null) body.put("providerMessageId", outcome.providerMessageId());
        if (outcome.failureCode() != null) body.put("failureCode", outcome.failureCode());
        HttpResponse<String> response = client.post(BASE + appointmentId + "/outcomes", body.toString());
        if (response.statusCode() == 200) return true;
        if (response.statusCode() == 409) return false;
        throw client.unexpected(response);
    }
}
