package co.edu.corhuila.dlc.worker.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SDD check of the owner doubles (Annex C): with DLC_DOCS_DIR, every call the worker makes and every successful reply
 * of a double must match internal-coordination.yaml, or the test that produced it fails.
 */
class OwnerContractTest {
    private static final String AUDIENCE = "appointments-service";
    private static final String SCOPE = "appointments:notification-outcome:write";
    private static final String DETAILS = "appointments:notification-details:read";
    private static final String BASE = "/internal/v1/appointment-notifications/" + UUID.randomUUID();

    private FakeOwner owner;

    @BeforeEach
    void start() throws Exception {
        assumeTrue(OwnerContract.ENABLED, "DLC_DOCS_DIR is not set");
        owner = new FakeOwner(AUDIENCE);
        owner.requiredScopes.put("/outcomes", SCOPE);
        owner.requiredScopes.put("/details", DETAILS);
    }

    @AfterEach
    void stop() {
        if (owner != null) owner.discard();
    }

    @Test
    void aCallOutsideTheContractFailsTheTest() throws Exception {
        owner.replies.put(BASE + "/outcomes", new FakeOwner.Reply(200, ""));

        send("POST", BASE + "/outcomes", SCOPE, "{\"deliveryId\":\"" + UUID.randomUUID() + "\"}");

        assertThatThrownBy(owner::close).isInstanceOf(AssertionError.class).hasMessageContaining("/outcomes");
    }

    @Test
    void aSuccessfulReplyOutsideTheContractFailsTheTest() throws Exception {
        owner.replies.put(BASE + "/details", new FakeOwner.Reply(200, "{\"appointmentId\":\"x\"}"));

        send("GET", BASE + "/details", DETAILS, null);

        assertThatThrownBy(owner::close).isInstanceOf(AssertionError.class).hasMessageContaining("/details");
    }

    @Test
    void aReplyMarkedOffContractIsNotChecked() throws Exception {
        owner.replies.put(BASE + "/details", FakeOwner.Reply.offContract(200, "{\"appointmentId\":\"x\"}"));

        send("GET", BASE + "/details", DETAILS, null);

        assertThatCode(owner::close).doesNotThrowAnyException();
    }

    private void send(String method, String path, String scope, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(owner.baseUrl() + path))
                .header("Authorization", "Bearer " + FakeOwner.token(AUDIENCE, scope))
                .header("X-Correlation-Id", UUID.randomUUID().toString());
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding());
    }
}
