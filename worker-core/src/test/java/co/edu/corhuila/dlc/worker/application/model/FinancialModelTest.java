package co.edu.corhuila.dlc.worker.application.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancialModelTest {
    @Test
    void attachmentsAreDefensiveCopiesAndNeverPrinted() {
        byte[] content = {1, 2, 3};
        Attachment attachment = new Attachment("f.pdf", "application/pdf", content);
        content[0] = 9;
        byte[] read = attachment.content();
        read[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, attachment.content());
        assertFalse(attachment.toString().contains("f.pdf"));
        assertNull(new EmailMessage(UUID.randomUUID(), "a@example.com", "s", "b").attachment());
    }

    @Test
    void summariesAreImmutableAndRedacted() {
        List<String> concepts = new ArrayList<>(List.of("Limpieza"));
        InvoiceSummary summary = new InvoiceSummary(UUID.randomUUID(), "FAC-1", Instant.now(), concepts, "COP",
                "1.00", "0.00", "1.00", "ISSUED", null);
        concepts.add("Otro");

        assertEquals(List.of("Limpieza"), summary.concepts());
        assertFalse(summary.toString().contains("Limpieza"));
        assertThrows(NullPointerException.class, () -> new InvoiceSummary(UUID.randomUUID(), null, Instant.now(),
                List.of(), "COP", "1.00", "0.00", "1.00", "ISSUED", null));
    }

    @Test
    void deliveriesNeedAtLeastOneAttempt() {
        assertThrows(IllegalArgumentException.class, () -> new FinancialDelivery(UUID.randomUUID(),
                UUID.randomUUID(), Kind.INVOICE_ISSUED, Delivery.Status.SENT, 0, null, null, Instant.now()));
        assertTrue(new FinancialDelivery(UUID.randomUUID(), UUID.randomUUID(), Kind.PAYMENT_RECORDED,
                Delivery.Status.SENT, 1, "p", null, Instant.now()).attempts() == 1);
    }
}
