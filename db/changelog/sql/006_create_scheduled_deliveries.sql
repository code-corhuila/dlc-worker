CREATE TABLE notifications.scheduled_deliveries (
    event_id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SENT', 'DELIVERY_FAILED')),
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 3),
    provider_message_id TEXT,
    failure_code TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'SENT' AND provider_message_id IS NOT NULL AND failure_code IS NULL)
        OR (status = 'DELIVERY_FAILED' AND provider_message_id IS NULL AND failure_code IS NOT NULL))
);
GRANT SELECT, INSERT ON notifications.scheduled_deliveries TO notifications_runtime;
