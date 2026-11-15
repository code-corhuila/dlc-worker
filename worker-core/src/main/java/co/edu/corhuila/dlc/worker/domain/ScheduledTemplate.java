package co.edu.corhuila.dlc.worker.domain;

import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.model.Recipient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Informational scheduling notice; does not imply that the patient confirmed attendance. */
public final class ScheduledTemplate {
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy 'a las' HH:mm", Locale.forLanguageTag("es-CO"));
    private ScheduledTemplate() { }

    public static EmailMessage compose(UUID eventId, Recipient recipient, Instant startAt) {
        String body = "Hola:\n\nSu cita en Di Lucca Dental Care & Technology fue programada para el "
                + DATE.format(startAt.atZone(ZONE)) + " (hora de Colombia).\n\n"
                + "Si necesita confirmar, cancelar o reprogramar su cita, comuníquese con la clínica.\n\n"
                + "Este es un mensaje informativo; por favor no responda a este correo.\n";
        return new EmailMessage(eventId, recipient.email(), "Su cita fue programada en Di Lucca", body);
    }
}
