package co.edu.corhuila.dlc.worker.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.ReminderDetails;
import co.edu.corhuila.dlc.worker.application.model.ReminderOutcome;
import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

/** Appointments private contract for the worker (internal-coordination.yaml, D11, D13). */
class HttpAppointmentNotificationsTest {
    private static final String AUDIENCE = "appointments-service";
    private static final String DETAILS_SCOPE = "appointments:notification-details:read";
    private static final String OUTCOME_SCOPE = "appointments:notification-outcome:write";
    private static final UUID APPOINTMENT = UUID.fromString("1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9");
    private static final UUID PATIENT = UUID.fromString("2c3d4e5f-6071-4829-93a4-b5c6d7e8f901");
    private static final UUID DELIVERY = UUID.fromString("3d4e5f60-7182-493a-a4b5-c6d7e8f90123");
    private static final String BASE = "/internal/v1/appointment-notifications/" + APPOINTMENT;

    @TempDir
    Path secrets;
    private FakeOwner owner;
    private Path tokenFile;
    private HttpAppointmentNotifications client;

    @BeforeEach
    void start() throws Exception {
        owner = new FakeOwner(AUDIENCE);
        owner.requiredScopes.put("/details", DETAILS_SCOPE);
        owner.requiredScopes.put("/outcomes", OUTCOME_SCOPE);
        tokenFile = FakeOwner.tokenFile(secrets, "worker-to-appointments",
                FakeOwner.token(AUDIENCE, DETAILS_SCOPE + " " + OUTCOME_SCOPE));
        client = new HttpAppointmentNotifications(owner.baseUrl(), new FileServiceToken(tokenFile),
                Duration.ofSeconds(2));
        MDC.put("correlationId", "4e5f6071-8293-4a4b-b5c6-d7e8f9012345");
    }

    @AfterEach
    void stop() {
        owner.close();
        MDC.clear();
    }

    @Test
    void readsTheCurrentDetailsWithTheScopedTokenAndCorrelation() {
        owner.replies.put(BASE + "/details", new FakeOwner.Reply(200, "{\"appointmentId\":\"" + APPOINTMENT
                + "\",\"patientId\":\"" + PATIENT + "\",\"appointmentVersion\":4,\"startAt\":\"2026-11-06T15:00:00Z\","
                + "\"endAt\":\"2026-11-06T15:30:00Z\",\"status\":\"CONFIRMADA\"}"));

        ReminderDetails details = client.details(APPOINTMENT).orElseThrow();

        assertThat(details).isEqualTo(new ReminderDetails(APPOINTMENT, PATIENT, 4,
                Instant.parse("2026-11-06T15:00:00Z"), Instant.parse("2026-11-06T15:30:00Z"), "CONFIRMADA"));
        FakeOwner.Call call = owner.calls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.correlationId()).isEqualTo("4e5f6071-8293-4a4b-b5c6-d7e8f9012345");
    }

    @Test
    void aMissingAppointmentIsEmpty() {
        owner.replies.put(BASE + "/details", new FakeOwner.Reply(404, "{\"code\":\"NOT_FOUND\"}"));

        assertThat(client.details(APPOINTMENT)).isEmpty();
    }

    @Test
    void serverErrorsForbiddenAndMalformedRepliesAreTransient() throws Exception {
        owner.replies.put(BASE + "/details", new FakeOwner.Reply(503, ""));
        assertThatThrownBy(() -> client.details(APPOINTMENT)).isInstanceOf(OwnerUnavailableException.class);

        owner.replies.put(BASE + "/details", new FakeOwner.Reply(200, "{\"appointmentId\":\"x\"}"));
        assertThatThrownBy(() -> client.details(APPOINTMENT)).isInstanceOf(OwnerUnavailableException.class);

        Files.writeString(tokenFile, FakeOwner.token(AUDIENCE, "appointments:other"));
        assertThatThrownBy(() -> client.details(APPOINTMENT)).isInstanceOf(OwnerUnavailableException.class)
                .hasMessage("APPOINTMENTS_REJECTED_CREDENTIALS");
    }

    @Test
    void anUnreachableOwnerIsTransient() {
        HttpAppointmentNotifications down = new HttpAppointmentNotifications("http://127.0.0.1:1",
                new FileServiceToken(tokenFile), Duration.ofMillis(500));

        assertThatThrownBy(() -> down.details(APPOINTMENT)).isInstanceOf(OwnerUnavailableException.class);
    }

    @Test
    void reportsASentOutcomeWithTheContractBody() throws Exception {
        owner.replies.put(BASE + "/outcomes", new FakeOwner.Reply(200, ""));

        boolean recorded = client.report(APPOINTMENT, new ReminderOutcome(DELIVERY, 4, ReminderOutcome.Status.SENT,
                2, Instant.parse("2026-11-05T15:00:01Z"), "<" + DELIVERY + "@dlc-worker>", null));

        assertThat(recorded).isTrue();
        FakeOwner.Call call = owner.calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(new ObjectMapper().readTree(call.body())).isEqualTo(new ObjectMapper().readTree(
                "{\"deliveryId\":\"" + DELIVERY + "\",\"appointmentVersion\":4,\"notificationType\":\"REMINDER\","
                + "\"status\":\"SENT\",\"attempts\":2,\"occurredAt\":\"2026-11-05T15:00:01Z\","
                + "\"providerMessageId\":\"<" + DELIVERY + "@dlc-worker>\"}"));
    }

    @Test
    void reportsAFailedOutcomeWithItsCode() throws Exception {
        owner.replies.put(BASE + "/outcomes", new FakeOwner.Reply(200, ""));

        client.report(APPOINTMENT, new ReminderOutcome(DELIVERY, 4, ReminderOutcome.Status.DELIVERY_FAILED, 3,
                Instant.parse("2026-11-05T15:00:01Z"), null, "PROVIDER_UNAVAILABLE"));

        assertThat(new ObjectMapper().readTree(owner.calls.get(0).body()).path("failureCode").asText())
                .isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(new ObjectMapper().readTree(owner.calls.get(0).body()).has("providerMessageId")).isFalse();
    }

    @Test
    void aConflictIsARejectionNotARetry() {
        owner.replies.put(BASE + "/outcomes", new FakeOwner.Reply(409, "{\"code\":\"STALE_VERSION\"}"));

        assertThat(client.report(APPOINTMENT, new ReminderOutcome(DELIVERY, 4, ReminderOutcome.Status.SENT, 1,
                Instant.parse("2026-11-05T15:00:01Z"), "ref", null))).isFalse();
        assertThat(owner.calls).hasSize(1);
    }

    @Test
    void theTokenFileIsReadOnEveryCall() throws Exception {
        owner.replies.put(BASE + "/details", new FakeOwner.Reply(404, ""));
        client.details(APPOINTMENT);
        String rotated = FakeOwner.token(AUDIENCE, DETAILS_SCOPE + " rotated:scope");
        Files.writeString(tokenFile, rotated);

        client.details(APPOINTMENT);

        assertThat(owner.calls.get(1).authorization()).isEqualTo("Bearer " + rotated);
    }

    @Test
    void aMissingTokenFileIsTransientAndCallsNothing() throws Exception {
        Files.delete(tokenFile);

        assertThatThrownBy(() -> client.details(APPOINTMENT)).isInstanceOf(OwnerUnavailableException.class)
                .hasMessage("SERVICE_TOKEN_UNAVAILABLE");
        assertThat(owner.calls).isEmpty();
    }
}
