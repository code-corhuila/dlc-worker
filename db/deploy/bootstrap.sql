-- One-time owner bootstrap of the notifications schema, run by the platform DBA before Liquibase.
-- Set passwords through the environment's secret manager; never put them in this file.
-- Re-runnable: it creates missing objects and refuses unexpected existing state.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notifications_migrator') THEN
    CREATE ROLE notifications_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notifications_runtime') THEN
    CREATE ROLE notifications_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_roles
    WHERE rolname IN ('notifications_migrator', 'notifications_runtime')
      AND (NOT rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
  ) THEN
    RAISE EXCEPTION 'Existing notifications role has unexpected privileges';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_namespace n
    JOIN pg_roles r ON r.oid = n.nspowner
    WHERE n.nspname IN ('notifications', 'notifications_liquibase') AND r.rolname <> 'notifications_migrator'
  ) THEN
    RAISE EXCEPTION 'Existing notifications schema has another owner';
  END IF;
END $$;

-- The first changeset creates the schema as the migrator, which needs CREATE on the database only until the
-- schema exists. Re-run this bootstrap after the first migration to revoke it.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO notifications_migrator, notifications_runtime', current_database());
  IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'notifications') THEN
    EXECUTE format('REVOKE CREATE ON DATABASE %I FROM notifications_migrator', current_database());
  ELSE
    EXECUTE format('GRANT CREATE ON DATABASE %I TO notifications_migrator', current_database());
  END IF;
END $$;

-- Liquibase tracking tables stay outside the notifications schema.
CREATE SCHEMA IF NOT EXISTS notifications_liquibase AUTHORIZATION notifications_migrator;
REVOKE ALL ON SCHEMA notifications_liquibase FROM PUBLIC;
