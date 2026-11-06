package co.edu.corhuila.dlc.worker.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.Recipient;
import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Patients billing-contact contract (internal-coordination.yaml), exercised against a double of the contract. */
class HttpPatientContactsTest {
    private static final String AUDIENCE = "patients-service";
    private static final String SCOPE = "patients:billing-contact:read";
    private static final UUID PATIENT = UUID.fromString("2c3d4e5f-6071-4829-93a4-b5c6d7e8f901");
    private static final String PATH = "/internal/v1/patients/" + PATIENT + "/billing-contact";

    @TempDir
    Path secrets;
    private FakeOwner owner;
    private HttpPatientContacts client;

    @BeforeEach
    void start() throws Exception {
        owner = new FakeOwner(AUDIENCE);
        owner.requiredScopes.put("/billing-contact", SCOPE);
        client = new HttpPatientContacts(owner.baseUrl(), new FileServiceToken(
                FakeOwner.tokenFile(secrets, "worker-to-patients", FakeOwner.token(AUDIENCE, SCOPE))),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        owner.close();
    }

    @Test
    void resolvesTheRecipientWithItsOwnScopedToken() {
        owner.replies.put(PATH, new FakeOwner.Reply(200,
                "{\"patientId\":\"" + PATIENT + "\",\"email\":\"ana@example.com\",\"displayName\":\"Ana Pérez\"}"));

        Recipient recipient = client.contact(PATIENT).orElseThrow();

        assertThat(recipient.email()).isEqualTo("ana@example.com");
        assertThat(recipient.displayName()).isEqualTo("Ana Pérez");
        assertThat(owner.calls.get(0).method()).isEqualTo("GET");
    }

    @Test
    void anUnknownPatientIsEmpty() {
        owner.replies.put(PATH, new FakeOwner.Reply(404, "{\"code\":\"NOT_FOUND\"}"));

        assertThat(client.contact(PATIENT)).isEmpty();
    }

    @Test
    void anotherPatientOrAMalformedReplyIsTransientAndNeverEchoesTheEmail() {
        owner.replies.put(PATH, new FakeOwner.Reply(200, "{\"patientId\":\"" + UUID.randomUUID()
                + "\",\"email\":\"ana@example.com\",\"displayName\":\"Ana\"}"));
        assertThatThrownBy(() -> client.contact(PATIENT)).isInstanceOf(OwnerUnavailableException.class)
                .hasMessageNotContaining("ana@example.com");

        owner.replies.put(PATH, FakeOwner.Reply.offContract(200, "{\"patientId\":\"" + PATIENT + "\"}"));
        assertThatThrownBy(() -> client.contact(PATIENT)).isInstanceOf(OwnerUnavailableException.class);

        owner.replies.put(PATH, new FakeOwner.Reply(503, ""));
        assertThatThrownBy(() -> client.contact(PATIENT)).isInstanceOf(OwnerUnavailableException.class);
    }
}
