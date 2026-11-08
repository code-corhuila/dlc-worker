package co.edu.corhuila.dlc.worker.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import co.edu.corhuila.dlc.worker.application.model.InvoiceSummary;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancialTemplateTest {
    private static final UUID DELIVERY = UUID.randomUUID();
    private static final byte[] PDF = {'%', 'P', 'D', 'F'};
    private static final Recipient ANA = new Recipient("ana@example.com", "Ana García");

    private static InvoiceSummary summary(String status, String paid, String balance, String recent) {
        return new InvoiceSummary(UUID.randomUUID(), "FAC-2026-0007", Instant.parse("2026-11-08T15:30:00Z"),
                List.of("Resina compuesta una superficie", "Profilaxis adulto"), "COP", "145000.00", paid, balance,
                status, recent);
    }

    @Test
    void invoiceEmailCarriesTheMinimizedSummaryAndThePdf() {
        EmailMessage message = FinancialTemplate.compose(DELIVERY, ANA,
                summary("ISSUED", "0.00", "145000.00", null), Kind.INVOICE_ISSUED, PDF);

        assertEquals(DELIVERY, message.deliveryId());
        assertEquals("ana@example.com", message.to());
        assertEquals("Factura FAC-2026-0007 de Di Lucca", message.subject());
        assertTrue(message.body().startsWith("Hola, Ana García:"));
        assertTrue(message.body().contains("emitida el 8 de noviembre de 2026"));
        assertTrue(message.body().contains("- Resina compuesta una superficie\n- Profilaxis adulto"));
        assertTrue(message.body().contains("Total: COP 145.000,00"));
        assertTrue(message.body().contains("Saldo pendiente: COP 145.000,00"));
        assertTrue(message.body().contains("Estado: Emitida"));
        assertFalse(message.body().contains("Registramos un pago"));
        assertFalse(message.body().contains("http"));
        assertEquals("factura-FAC-2026-0007.pdf", message.attachment().fileName());
        assertEquals("application/pdf", message.attachment().contentType());
        assertArrayEquals(PDF, message.attachment().content());
    }

    @Test
    void paymentEmailStatesTheNewAmountAndTheDerivedState() {
        EmailMessage partial = FinancialTemplate.compose(DELIVERY, ANA,
                summary("PARTIALLY_PAID", "45000.00", "100000.00", "45000.00"), Kind.PAYMENT_RECORDED, PDF);
        EmailMessage paid = FinancialTemplate.compose(DELIVERY, ANA,
                summary("PAID", "145000.00", "0.00", "100000.00"), Kind.PAYMENT_RECORDED, PDF);

        assertEquals("Pago registrado en la factura FAC-2026-0007", partial.subject());
        assertTrue(partial.body().contains("Registramos un pago de COP 45.000,00"));
        assertTrue(partial.body().contains("Pagado: COP 45.000,00"));
        assertTrue(partial.body().contains("Saldo pendiente: COP 100.000,00"));
        assertTrue(partial.body().contains("Estado: Parcialmente pagada"));
        assertTrue(paid.body().contains("Estado: Pagada"));
        assertTrue(paid.body().contains("Saldo pendiente: COP 0,00"));
    }

    @Test
    void unexpectedAmountsAndStatusesAreShownAsReceived() {
        EmailMessage message = FinancialTemplate.compose(DELIVERY, new Recipient("a@example.com", " "),
                new InvoiceSummary(UUID.randomUUID(), "FAC-8", Instant.now(), List.of(), "COP", "n/a", "0.00",
                        "0.00", "VOID", null), Kind.PAYMENT_RECORDED, PDF);

        assertTrue(message.body().startsWith("Hola:"));
        assertTrue(message.body().contains("Registramos un pago de COP en su factura FAC-8"));
        assertTrue(message.body().contains("Total: COP n/a"));
        assertTrue(message.body().contains("Estado: VOID"));
        assertTrue(FinancialTemplate.compose(DELIVERY, new Recipient("a@example.com", "www.x.co"),
                summary("ISSUED", "0.00", "1.00", null), Kind.INVOICE_ISSUED, PDF).body().startsWith("Hola:"));
        assertTrue(FinancialTemplate.compose(DELIVERY, new Recipient("a@example.com", "a://b"),
                summary("ISSUED", "0.00", "1.00", null), Kind.INVOICE_ISSUED, PDF).body().startsWith("Hola:"));
    }

    @Test
    void untrustedTextIsNeutralized() {
        InvoiceSummary odd = new InvoiceSummary(UUID.randomUUID(), "FAC/../7\r\nBcc: x", Instant.now(),
                List.of("Limpieza\r\nBcc: other@example.com"), "COP", "1.00", "0.00", "1.00", "ISSUED", null);
        EmailMessage message = FinancialTemplate.compose(DELIVERY, new Recipient("a@example.com", "http://evil"),
                odd, Kind.INVOICE_ISSUED, PDF);

        assertTrue(message.body().startsWith("Hola:"));
        assertFalse(message.subject().contains("\n"));
        assertFalse(message.body().contains("\r"));
        assertTrue(message.body().contains("- Limpieza Bcc: other@example.com"));
        assertEquals("factura-FAC7Bccx.pdf", message.attachment().fileName());
    }
}
