-- Both tables are append-only for the runtime: results are final and never edited or deleted.
GRANT USAGE ON SCHEMA notifications TO notifications_runtime;
GRANT SELECT, INSERT ON notifications.inbox_events, notifications.deliveries TO notifications_runtime;
