DROP TABLE notifications.financial_deliveries;
ALTER TABLE notifications.inbox_events DROP CONSTRAINT ck_inbox_events_result;
ALTER TABLE notifications.inbox_events ADD CONSTRAINT ck_inbox_events_result
    CHECK (result IN ('DISCARDED', 'REPORTED', 'REJECTED'));
