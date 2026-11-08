package co.edu.corhuila.dlc.worker.adapter.out.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.dlc.worker.application.model.EmailMessage;
import co.edu.corhuila.dlc.worker.application.port.out.EmailDeliveryException;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** SMTP provider adapter against an in-process fake SMTP server; no real provider or credential. */
class SmtpEmailSenderTest {
    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

    private static final UUID DELIVERY = UUID.fromString("3d4e5f60-7182-493a-a4b5-c6d7e8f90123");
    private static final EmailMessage MESSAGE = new EmailMessage(DELIVERY, "ana@example.com",
            "Recordatorio de su cita en Di Lucca", "Hola, Ana:\n\nLe recordamos su cita el viernes a las 10:00.\n");

    private static SmtpEmailSender sender(int port) {
        return new SmtpEmailSender(new SmtpEmailSender.Settings("localhost", port, null, null, false,
                "recordatorios@dilucca.test", Duration.ofSeconds(2)));
    }

    @Test
    void sendsPlainUtf8TextAndReturnsTheMessageId() throws Exception {
        String reference = sender(SMTP.getSmtp().getPort()).send(MESSAGE);

        MimeMessage[] received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage mail = received[0];
        assertThat(mail.getSubject()).isEqualTo("Recordatorio de su cita en Di Lucca");
        assertThat(mail.getFrom()).containsExactly(new InternetAddress("recordatorios@dilucca.test"));
        assertThat(mail.getAllRecipients()).containsExactly(new InternetAddress("ana@example.com"));
        assertThat(mail.getContentType()).startsWith("text/plain").containsIgnoringCase("UTF-8");
        // SMTP transport normalizes line endings and the final line break.
        assertThat(((String) mail.getContent()).replace("\r\n", "\n").strip()).isEqualTo(MESSAGE.body().strip());
        assertThat(mail.getMessageID()).isEqualTo("<" + DELIVERY + "@dlc-worker>");
        assertThat(reference).isEqualTo(mail.getMessageID()).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void attachesThePdfNextToThePlainTextBody() throws Exception {
        byte[] pdf = "%PDF-1.4 factura".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        EmailMessage invoice = new EmailMessage(DELIVERY, "ana@example.com", "Factura FAC-7 de Di Lucca",
                "Hola, Ana:\n\nLe compartimos su factura.\n",
                new co.edu.corhuila.dlc.worker.application.model.Attachment("factura-FAC-7.pdf", "application/pdf",
                        pdf));

        sender(SMTP.getSmtp().getPort()).send(invoice);

        MimeMessage mail = SMTP.getReceivedMessages()[0];
        assertThat(mail.getContentType()).startsWith("multipart/mixed");
        var parts = (jakarta.mail.internet.MimeMultipart) mail.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        var text = (jakarta.mail.internet.MimeBodyPart) parts.getBodyPart(0);
        assertThat(text.getContentType()).startsWith("text/plain").containsIgnoringCase("UTF-8");
        assertThat(((String) text.getContent()).replace("\r\n", "\n")).contains("Le compartimos su factura.");
        var file = (jakarta.mail.internet.MimeBodyPart) parts.getBodyPart(1);
        assertThat(file.getContentType()).startsWith("application/pdf");
        assertThat(file.getFileName()).isEqualTo("factura-FAC-7.pdf");
        assertThat(file.getDisposition()).isEqualTo("attachment");
        assertThat(file.getInputStream().readAllBytes()).isEqualTo(pdf);
        assertThat(mail.getMessageID()).isEqualTo("<" + DELIVERY + "@dlc-worker>");
    }

    @Test
    void anUnreachableProviderIsTransient() {
        assertThatThrownBy(() -> sender(1).send(MESSAGE)).isInstanceOfSatisfying(EmailDeliveryException.class,
                failure -> {
                    assertThat(failure.failureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(failure.permanent()).isFalse();
                });
    }

    @Test
    void anInvalidAddressIsPermanentAndNotEchoed() {
        EmailMessage invalid = new EmailMessage(DELIVERY, "not an address@@", "s", "b");

        assertThatThrownBy(() -> sender(SMTP.getSmtp().getPort()).send(invalid))
                .isInstanceOfSatisfying(EmailDeliveryException.class, failure -> {
                    assertThat(failure.failureCode()).isEqualTo("RECIPIENT_INVALID");
                    assertThat(failure.permanent()).isTrue();
                    assertThat(failure.getMessage()).doesNotContain("address@@");
                });
    }

    @Test
    void smtpReplyCodesDecideWhetherToRetry() {
        InternetAddress[] none = new InternetAddress[0];
        assertThat(SmtpEmailSender.classify(new SMTPAddressFailedException(null, "RCPT", 550, "no such user")))
                .satisfies(f -> assertThat(f.failureCode()).isEqualTo("RECIPIENT_REJECTED"))
                .satisfies(f -> assertThat(f.permanent()).isTrue());
        assertThat(SmtpEmailSender.classify(new SMTPAddressFailedException(null, "RCPT", 452, "mailbox full")))
                .satisfies(f -> assertThat(f.permanent()).isFalse());
        assertThat(SmtpEmailSender.classify(new SMTPSendFailedException("DATA", 554, "rejected", null, none, none,
                none))).satisfies(f -> assertThat(f.failureCode()).isEqualTo("PROVIDER_REJECTED"))
                .satisfies(f -> assertThat(f.permanent()).isTrue());
        assertThat(SmtpEmailSender.classify(new SMTPSendFailedException("DATA", 421, "busy", null, none, none,
                none))).satisfies(f -> assertThat(f.failureCode()).isEqualTo("PROVIDER_UNAVAILABLE"))
                .satisfies(f -> assertThat(f.permanent()).isFalse());
        assertThat(SmtpEmailSender.classify(new SendFailedException("x", new MessagingException("y"))))
                .satisfies(f -> assertThat(f.permanent()).isFalse());
    }
}
