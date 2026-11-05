-- Final result of one delivery, keyed by the producer's deliveryId (D11). Saved before it is reported, so a
-- redelivered message re-reports it without sending the email again. No recipient address or message body.
CREATE TABLE notifications.deliveries (
    delivery_id uuid NOT NULL CONSTRAINT pk_deliveries PRIMARY KEY,
    event_id uuid NOT NULL,
    appointment_id uuid NOT NULL,
    appointment_version bigint NOT NULL,
    notification_type text NOT NULL,
    status text NOT NULL,
    attempts integer NOT NULL,
    provider_message_id text,
    failure_code text,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_deliveries_version CHECK (appointment_version >= 1),
    CONSTRAINT ck_deliveries_type CHECK (notification_type = 'REMINDER'),
    CONSTRAINT ck_deliveries_attempts CHECK (attempts BETWEEN 1 AND 3),
    CONSTRAINT ck_deliveries_status CHECK (
        (status = 'SENT' AND provider_message_id IS NOT NULL AND failure_code IS NULL)
        OR (status = 'DELIVERY_FAILED' AND provider_message_id IS NULL AND failure_code IS NOT NULL)),
    CONSTRAINT ck_deliveries_reference CHECK (provider_message_id IS NULL OR length(provider_message_id) BETWEEN 1 AND 200),
    CONSTRAINT ck_deliveries_failure_code CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
