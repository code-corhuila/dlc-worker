package co.edu.corhuila.dlc.worker.application.port.out;

import co.edu.corhuila.dlc.worker.application.model.FinancialDelivery;
import co.edu.corhuila.dlc.worker.application.model.FinancialRequest;
import java.util.Optional;
import java.util.UUID;

/** Worker inbox and financial delivery results (schema notifications). Never stores email, amounts or body. */
public interface FinancialDeliveryStore {
    boolean processed(UUID eventId);

    Optional<FinancialDelivery> delivery(UUID eventId);

    void save(FinancialDelivery delivery);

    /** Marks the event processed with its result: DISCARDED or RECORDED. */
    void complete(FinancialRequest request, String result);
}
