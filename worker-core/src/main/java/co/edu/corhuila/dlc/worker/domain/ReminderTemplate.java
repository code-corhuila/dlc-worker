package co.edu.corhuila.dlc.worker.domain;

import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The informational appointment reminder (ADR-005, HU-APT-002): date and time in America/Bogota, no links, tokens,
 * actions or clinical content. The patient contacts the clinic for any change.
 */
public final class ReminderTemplate {
    static final ZoneId CLINIC_ZONE = ZoneId.of("America/Bogota");
    private static final Locale SPANISH = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", SPANISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", SPANISH);
    static final String SUBJECT = "Recordatorio de su cita en Di Lucca";

    private ReminderTemplate() {
    }

    public static EmailMessage compose(UUID deliveryId, Recipient recipient, Instant startAt) {
        ZonedDateTime start = startAt.atZone(CLINIC_ZONE);
        String body = greeting(recipient.displayName()) + "\n\n"
                + "Le recordamos su cita en Di Lucca Dental Care & Technology el " + DATE.format(start)
                + " a las " + TIME.format(start) + " (hora de Colombia).\n\n"
                + "Si necesita confirmar, cancelar o reprogramar su cita, comuníquese con la clínica.\n\n"
                + "Este es un mensaje informativo; por favor no responda a este correo.\n";
        return new EmailMessage(deliveryId, recipient.email(), SUBJECT, body);
    }

    /** Control characters collapse to spaces; a name that looks like a link is not echoed at all. */
    private static String greeting(String displayName) {
        String name = displayName.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || lower.contains("://") || lower.contains("www.") || lower.contains("http")) {
            return "Hola:";
        }
        return "Hola, " + name + ":";
    }
}
