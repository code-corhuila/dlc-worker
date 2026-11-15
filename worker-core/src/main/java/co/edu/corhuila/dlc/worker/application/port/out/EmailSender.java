package co.edu.corhuila.dlc.worker.application.port.out;

import co.edu.corhuila.dlc.worker.application.model.EmailMessage;

/** Email-provider port. Returns the provider message reference or throws {@link EmailDeliveryException}. */
public interface EmailSender {
    String send(EmailMessage message);
}
