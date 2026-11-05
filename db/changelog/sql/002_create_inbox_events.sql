-- Worker inbox: one row per consumed event once its result is final, so an at-least-once redelivery has no second
-- effect. Identifiers and a result code only; event content is never copied here.
CREATE TABLE notifications.inbox_events (
    event_id uuid NOT NULL CONSTRAINT pk_inbox_events PRIMARY KEY,
    event_type text NOT NULL,
    delivery_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    result text NOT NULL,
    processed_at timestamptz NOT NULL,
    CONSTRAINT ck_inbox_events_event_type CHECK (event_type ~ '^[A-Z][A-Za-z]{2,99}$'),
    CONSTRAINT ck_inbox_events_result CHECK (result IN ('DISCARDED', 'REPORTED', 'REJECTED'))
);
