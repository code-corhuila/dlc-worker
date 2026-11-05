package co.edu.corhuila.dlc.worker.adapter.out.http;

import co.edu.corhuila.dlc.worker.application.model.Recipient;
import co.edu.corhuila.dlc.worker.application.port.out.PatientContacts;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Patients private contract GET /internal/v1/patients/{id}/billing-contact (scope patients:billing-contact:read,
 * response {patientId, email, displayName}). The address stays in memory for one delivery and never reaches logs
 * or exceptions.
 */
public final class HttpPatientContacts implements PatientContacts {
    private final OwnerClient client;

    public HttpPatientContacts(String baseUrl, FileServiceToken token, Duration timeout) {
        this.client = new OwnerClient("PATIENTS", baseUrl, token, timeout);
    }

    @Override
    public Optional<Recipient> contact(UUID patientId) {
        HttpResponse<String> response = client.get("/internal/v1/patients/" + patientId + "/billing-contact");
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw client.unexpected(response);
        JsonNode body = client.body(response);
        if (!patientId.equals(client.uuid(body, "patientId"))) throw client.unavailable("CONTRACT_MISMATCH");
        return Optional.of(new Recipient(client.text(body, "email"), client.text(body, "displayName")));
    }
}
