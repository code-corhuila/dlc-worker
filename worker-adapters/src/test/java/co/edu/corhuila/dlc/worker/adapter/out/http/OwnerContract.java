package co.edu.corhuila.dlc.worker.adapter.out.http;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SDD contract check (Annex C) of the owner doubles: the private contract internal-coordination.yaml of a dlc-docs
 * checkout (DLC_DOCS_DIR, read from 07-api/contracts/openapi in place and never copied into this repository).
 * Without DLC_DOCS_DIR the check is skipped, like the database tests without TEST_DATABASE_URL.
 */
final class OwnerContract {
    private static final OpenApiInteractionValidator VALIDATOR = validator();
    static final boolean ENABLED = VALIDATOR != null;

    private OwnerContract() {
    }

    /** Violations of one exchange: the worker's request always, the double's reply only when it claims the contract. */
    static List<String> violations(FakeOwner.Call call, FakeOwner.Reply reply, String requestContentType) {
        if (!ENABLED) return List.of();
        SimpleRequest.Builder request = new SimpleRequest.Builder(Request.Method.valueOf(call.method()), call.path());
        if (call.authorization() != null) request.withAuthorization(call.authorization());
        if (call.correlationId() != null) request.withHeader("X-Correlation-Id", call.correlationId());
        if (!call.body().isEmpty()) request.withContentType(requestContentType).withBody(call.body());
        ValidationReport report = VALIDATOR.validateRequest(request.build());
        if (reply.contract() && reply.status() >= 200 && reply.status() < 300) {
            SimpleResponse.Builder response = SimpleResponse.Builder.status(reply.status());
            if (!reply.body().isEmpty()) response.withContentType("application/json").withBody(reply.body());
            report = report.merge(VALIDATOR.validateResponse(call.path(), Request.Method.valueOf(call.method()),
                    response.build()));
        }
        return report.getMessages().stream()
                .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                .map(message -> call.method() + " " + call.path() + ": " + message.getMessage())
                .toList();
    }

    private static OpenApiInteractionValidator validator() {
        String root = System.getenv("DLC_DOCS_DIR");
        if (root == null || root.isBlank()) return null;
        Path contract = Path.of(root, "07-api", "contracts", "openapi", "internal-coordination.yaml");
        if (!Files.isRegularFile(contract)) {
            throw new IllegalStateException("DLC_DOCS_DIR has no 07-api/contracts/openapi/internal-coordination.yaml");
        }
        return OpenApiInteractionValidator.createForSpecificationUrl(contract.toUri().toString()).build();
    }
}
