-- Own schema of the worker; it never holds owner inbox/outbox records.
CREATE SCHEMA notifications AUTHORIZATION notifications_migrator;
REVOKE ALL ON SCHEMA notifications FROM PUBLIC;
