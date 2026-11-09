package co.edu.corhuila.dlc.worker.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.dlc.worker.application.port.out.OwnerUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-020: the worker exchanges client credentials at IAM for service tokens, cached until 2/3 of their life. */
class IamServiceTokenTest {
    @TempDir Path dir;
    private HttpServer iam;
    private final List<String> requests = new ArrayList<>();
    private final AtomicInteger issued = new AtomicInteger();
    private volatile int status = 200;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeEach
    void start() throws IOException {
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/v1/service-tokens", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization")
                + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"accessToken\":\"tok-" + issued.incrementAndGet()
                + "\",\"tokenType\":\"Bearer\",\"expiresIn\":900,\"scope\":\"billing:invoice-pdf:read\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) exchange.getResponseBody().write(body);
            exchange.close();
        });
        iam.start();
    }

    @AfterEach
    void stop() {
        iam.stop(0);
    }

    private IamServiceToken token() throws IOException {
        Path secret = Files.writeString(dir.resolve("secret"), "s3cret\n");
        return new IamServiceToken("http://127.0.0.1:" + iam.getAddress().getPort(), "dlc-worker", secret,
            "billing-api", "billing:invoice-pdf:read", Duration.ofSeconds(2), clock);
    }

    @Test
    void requestsATokenWithBasicCredentialsAndReusesItUntilTwoThirdsOfItsLife() throws IOException {
        var token = token();
        assertEquals("tok-1", token.current());
        assertEquals("POST Basic " + Base64.getEncoder().encodeToString("dlc-worker:s3cret".getBytes())
            + " {\"audience\":\"billing-api\",\"scope\":\"billing:invoice-pdf:read\"}", requests.get(0));

        now.set(now.get().plusSeconds(599));
        assertEquals("tok-1", token.current());
        now.set(now.get().plusSeconds(1));
        assertEquals("tok-2", token.current());
        assertEquals(2, requests.size());
    }

    @Test
    void anInvalidatedTokenIsReplacedOnTheNextCall() throws IOException {
        var token = token();
        assertEquals("tok-1", token.current());
        token.invalidate();
        assertEquals("tok-2", token.current());
    }

    @Test
    void aRejectedOrUnreachableIssuerMakesTheTokenUnavailable() throws IOException {
        var token = token();
        status = 401;
        assertEquals("SERVICE_TOKEN_UNAVAILABLE",
            assertThrows(OwnerUnavailableException.class, token::current).getMessage());
        iam.stop(0);
        assertThrows(OwnerUnavailableException.class, token::current);
    }
}
