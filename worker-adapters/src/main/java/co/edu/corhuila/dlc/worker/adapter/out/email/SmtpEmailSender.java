package co.edu.corhuila.dlc.worker.adapter.out.email;

import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import co.edu.corhuila.dlc.worker.application.port.out.EmailSender;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

/**
 * Email-provider adapter over SMTP. Sends plain UTF-8 text with Message-ID {@code <deliveryId@dlc-worker>}, which is
 * returned as the provider reference. SMTP 5xx replies and malformed addresses are permanent; connection failures
 * and 4xx replies are transient. Failures carry only a code, never the address or the content.
 */
public final class SmtpEmailSender implements EmailSender {
    private final Session session;
    private final InternetAddress from;

    /** Username and password are optional (a local relay such as Mailpit needs none). */
    public record Settings(String host, int port, String username, String password, boolean startTls, String from,
            Duration timeout) {
        public Settings {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(timeout, "timeout");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
        }

        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + ", startTls=" + startTls + ", credentials redacted]";
        }
    }

    public SmtpEmailSender(Settings settings) {
        Properties properties = new Properties();
        String millis = String.valueOf(settings.timeout().toMillis());
        properties.put("mail.smtp.host", settings.host());
        properties.put("mail.smtp.port", String.valueOf(settings.port()));
        properties.put("mail.smtp.connectiontimeout", millis);
        properties.put("mail.smtp.timeout", millis);
        properties.put("mail.smtp.writetimeout", millis);
        properties.put("mail.smtp.starttls.enable", String.valueOf(settings.startTls()));
        properties.put("mail.smtp.starttls.required", String.valueOf(settings.startTls()));
        boolean auth = settings.username() != null && !settings.username().isBlank();
        properties.put("mail.smtp.auth", String.valueOf(auth));
        this.session = auth ? Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(settings.username(), settings.password());
            }
        }) : Session.getInstance(properties);
        try {
            this.from = new InternetAddress(settings.from(), true);
        } catch (AddressException invalid) {
            throw new IllegalArgumentException("Invalid sender address.");
        }
    }

    @Override
    public String send(EmailMessage message) {
        try {
            MimeMessage mail = new IdentifiedMessage(session, message.deliveryId());
            mail.setFrom(from);
            mail.setRecipient(Message.RecipientType.TO, new InternetAddress(message.to(), true));
            mail.setSubject(message.subject(), "UTF-8");
            mail.setText(message.body(), "UTF-8", "plain");
            mail.saveChanges();
            Transport.send(mail);
            return mail.getMessageID();
        } catch (MessagingException failure) {
            throw classify(failure);
        }
    }

    static EmailDeliveryException classify(MessagingException failure) {
        for (Exception cause = failure; cause != null;
                cause = cause instanceof MessagingException m ? m.getNextException() : null) {
            if (cause instanceof SMTPAddressFailedException rejected) {
                return rejected.getReturnCode() >= 500 ? new EmailDeliveryException("RECIPIENT_REJECTED", true)
                        : new EmailDeliveryException("PROVIDER_UNAVAILABLE", false);
            }
            if (cause instanceof SMTPSendFailedException rejected) {
                return rejected.getReturnCode() >= 500 ? new EmailDeliveryException("PROVIDER_REJECTED", true)
                        : new EmailDeliveryException("PROVIDER_UNAVAILABLE", false);
            }
            if (cause instanceof AddressException) return new EmailDeliveryException("RECIPIENT_INVALID", true);
        }
        return new EmailDeliveryException("PROVIDER_UNAVAILABLE", false);
    }

    /** Uses the stable delivery id as Message-ID, so a provider or relay can recognize a repeat. */
    private static final class IdentifiedMessage extends MimeMessage {
        private final UUID deliveryId;

        IdentifiedMessage(Session session, UUID deliveryId) {
            super(session);
            this.deliveryId = deliveryId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", "<" + deliveryId + "@dlc-worker>");
        }
    }
}
