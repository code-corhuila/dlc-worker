package co.edu.corhuila.dlc.worker.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-005: an informational reminder in America/Bogota, without links, tokens or clinical content. */
class ReminderTemplateTest {
    private static final UUID DELIVERY = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-11-06T15:00:00Z");

    private static EmailMessage compose(String displayName) {
        return ReminderTemplate.compose(DELIVERY, new Recipient("ana@example.com", displayName), START);
    }

    @Test
    void statesDateAndTimeInBogota() {
        EmailMessage message = compose("Ana Pérez");

        assertEquals("ana@example.com", message.to());
        assertEquals(DELIVERY, message.deliveryId());
        assertEquals("Recordatorio de su cita en Di Lucca", message.subject());
        assertTrue(message.body().startsWith("Hola, Ana Pérez:"), message.body());
        assertTrue(message.body().contains("viernes 6 de noviembre de 2026"), message.body());
        assertTrue(message.body().contains("10:00 (hora de Colombia)"), message.body());
        assertTrue(message.body().contains("comuníquese con la clínica"), message.body());
    }

    @Test
    void containsNoLinksTokensOrClinicalWords() {
        String text = (compose("Ana").subject() + " " + compose("Ana").body()).toLowerCase(Locale.ROOT);

        for (String forbidden : new String[] {"http", "www.", "://", "token", "confirmar aquí", "diagnóstico",
                "tratamiento", "procedimiento", "factura"}) {
            assertFalse(text.contains(forbidden), forbidden);
        }
    }

    @Test
    void aDisplayNameCannotInjectLinksOrHeaders() {
        assertTrue(compose("Ana\r\nBcc: x@y.co").body().startsWith("Hola, Ana Bcc: x@y.co:"));
        assertTrue(compose("visite https://evil.example").body().startsWith("Hola:"));
        assertTrue(compose("www.evil.example").body().startsWith("Hola:"));
        assertTrue(compose("  ").body().startsWith("Hola:"));
    }

    @Test
    void neverPrintsTheRecipientAddress() {
        assertFalse(compose("Ana").toString().contains("ana@example.com"));
        assertFalse(new Recipient("ana@example.com", "Ana").toString().contains("ana@example.com"));
    }
}
