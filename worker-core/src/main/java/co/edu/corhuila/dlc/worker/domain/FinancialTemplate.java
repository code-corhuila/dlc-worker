package co.edu.corhuila.dlc.worker.domain;

import co.edu.corhuila.dlc.worker.application.model.Attachment;
import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest.Kind;
import co.edu.corhuila.dlc.worker.application.model.InvoiceSummary;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Invoice and payment emails (ADR-005, HU-BIL-002/003): Billing's minimized event-time summary plus its PDF. Only
 * necessary invoice labels and Billing's own amounts; no links, clinical narrative or worker-side calculation.
 */
public final class FinancialTemplate {
    private static final ZoneId CLINIC_ZONE = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-CO"));
    private static final Map<String, String> STATUS =
            Map.of("ISSUED", "Emitida", "PARTIALLY_PAID", "Parcialmente pagada", "PAID", "Pagada");

    private FinancialTemplate() {
    }

    public static EmailMessage compose(UUID deliveryId, Recipient recipient, InvoiceSummary summary, Kind kind,
            byte[] pdf) {
        String number = line(summary.invoiceNumber());
        StringBuilder body = new StringBuilder(greeting(recipient.displayName())).append("\n\n");
        String subject;
        if (kind == Kind.PAYMENT_RECORDED) {
            subject = "Pago registrado en la factura " + number;
            body.append("Registramos un pago de ").append(money(summary, summary.recentPaymentAmount()))
                    .append(" en su factura ").append(number).append(".\n\n");
        } else {
            subject = "Factura " + number + " de Di Lucca";
            body.append("Le compartimos su factura ").append(number).append(" emitida el ")
                    .append(DATE.format(summary.issuedAt().atZone(CLINIC_ZONE))).append(".\n\n");
        }
        body.append("Conceptos:\n");
        summary.concepts().forEach(concept -> body.append("- ").append(line(concept)).append('\n'));
        body.append("\nTotal: ").append(money(summary, summary.total()))
                .append("\nPagado: ").append(money(summary, summary.paidTotal()))
                .append("\nSaldo pendiente: ").append(money(summary, summary.remainingBalance()))
                .append("\nEstado: ").append(STATUS.getOrDefault(summary.status(), line(summary.status())))
                .append("\n\nAdjuntamos el documento PDF con el detalle. Para cualquier consulta, comuníquese con ")
                .append("la clínica.\n\nEste es un mensaje informativo; por favor no responda a este correo.\n");
        String fileName = "factura-" + summary.invoiceNumber().replaceAll("[^A-Za-z0-9-]", "") + ".pdf";
        return new EmailMessage(deliveryId, recipient.email(), subject, body.toString(),
                new Attachment(fileName, "application/pdf", pdf));
    }

    /** Billing decimal string as "COP 145.000,00"; a value that is not a decimal is shown as received. */
    private static String money(InvoiceSummary summary, String amount) {
        String currency = line(summary.currency());
        if (amount == null) return currency;
        try {
            var symbols = new DecimalFormatSymbols(Locale.forLanguageTag("es-CO"));
            symbols.setGroupingSeparator('.');
            symbols.setDecimalSeparator(',');
            return currency + " " + new DecimalFormat("#,##0.00", symbols).format(new BigDecimal(amount));
        } catch (NumberFormatException notDecimal) {
            return currency + " " + line(amount);
        }
    }

    /** Control characters and line breaks collapse to spaces, so no text can add headers or lines. */
    private static String line(String text) {
        return text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    }

    private static String greeting(String displayName) {
        String name = line(displayName);
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || lower.contains("://") || lower.contains("www.") || lower.contains("http")) {
            return "Hola:";
        }
        return "Hola, " + name + ":";
    }
}
