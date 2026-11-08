-- Invoice and payment emails (HU-BIL-002/003). The inbox gains RECORDED: Billing receives no outcome report, the
-- result is recorded here only and never undoes issuance or payment.
ALTER TABLE notifications.inbox_events DROP CONSTRAINT ck_inbox_events_result;
ALTER TABLE notifications.inbox_events ADD CONSTRAINT ck_inbox_events_result
    CHECK (result IN ('DISCARDED', 'REPORTED', 'REJECTED', 'RECORDED'));
-- Final result of one financial email, keyed by the Billing eventId (one email per InvoiceIssued or
-- PaymentRecorded). No recipient address, amounts, PDF or message body.
CREATE TABLE notifications.financial_deliveries (
    event_id uuid NOT NULL CONSTRAINT pk_financial_deliveries PRIMARY KEY,
    invoice_id uuid NOT NULL,
    notification_type text NOT NULL,
    status text NOT NULL,
    attempts integer NOT NULL,
    provider_message_id text,
    failure_code text,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_financial_deliveries_type CHECK (notification_type IN ('INVOICE_ISSUED', 'PAYMENT_RECORDED')),
    CONSTRAINT ck_financial_deliveries_attempts CHECK (attempts BETWEEN 1 AND 3),
    CONSTRAINT ck_financial_deliveries_status CHECK (
        (status = 'SENT' AND provider_message_id IS NOT NULL AND failure_code IS NULL)
        OR (status = 'DELIVERY_FAILED' AND provider_message_id IS NULL AND failure_code IS NOT NULL)),
    CONSTRAINT ck_financial_deliveries_reference
        CHECK (provider_message_id IS NULL OR length(provider_message_id) BETWEEN 1 AND 200),
    CONSTRAINT ck_financial_deliveries_failure_code
        CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
-- Append-only for the runtime, like the reminder tables.
GRANT SELECT, INSERT ON notifications.financial_deliveries TO notifications_runtime;
