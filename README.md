# dlc-worker

> Transversal Notifications capability — asynchronous email delivery.

Part of the **Di Lucca Dental Care & Technology** distributed system. Under [ADR-005](https://github.com/code-corhuila/dlc-docs/blob/main/05-architecture/decisions/ADR-005-hybrid-integration-and-notifications.md), Appointments and Billing decide **why and when** to notify; this worker decides **how to deliver** through an email-provider adapter. It is a supporting component, not a business service: it owns no appointment, clinical or financial state.

Governance, requirements, contracts and decisions live in [`code-corhuila/dlc-docs`](https://github.com/code-corhuila/dlc-docs):

| Topic | Location in dlc-docs |
|---|---|
| Decision | `05-architecture/decisions/ADR-005-hybrid-integration-and-notifications.md` |
| Consumed event | `07-api/contracts/events/` (`AppointmentReminderRequested`) |
| Private owner contracts (`/internal/v1`) | `07-api/contracts/openapi/internal-coordination.yaml` |
| Repository boundary | `09-microservices/transversal-repositories.md` |
| Store (`notifications` schema) | `06-data/models.md` |

## Status

| Scope | State |
|---|---|
| Informational appointment reminders (HU-APT-002, issue #1) | Implemented; reproducible end-to-end test against Appointments `qa` (`e2e/run.sh`) |
| Patients `GET /internal/v1/patients/{id}/billing-contact` | Exposed by Patients (dlc-patient-api#50); the client is also tested against a double of the contract |
| Invoice and payment emails (HU-BIL-002/003, issues #2 and #3) | Implemented: `InvoiceIssued` and `PaymentRecorded` from `billing.events`, Billing's event-time summary and PDF attached |
| Service credential issuer | Platform gap: no component issues service JWTs yet. The worker reads tokens provisioned as files |

## Technology stack

- Java 21, Spring Boot 3.5, Maven
- RabbitMQ 3.13 (consumer of `appointments.events` and `billing.events`)
- PostgreSQL 16+ (schema `notifications`, role `notifications_runtime`)
- SMTP email provider (Jakarta Mail); Mailpit for local stages
- Docker

## Architecture

Hexagonal, in three Maven modules:

- `worker-core`: `DeliverReminder`, `DeliverFinancialEmail`, their templates and the ports, with no Spring or infrastructure dependency (enforced).
- `worker-adapters`: RabbitMQ consumers, strict event readers, Appointments, Patients and Billing clients, SMTP sender, JDBC stores.
- `worker-app`: composition root and configuration. No HTTP surface.

`db/` holds the Liquibase changelog of the `notifications` schema and its migration job. The worker runtime never migrates.

## Reminder delivery (ADR-005)

1. Appointments publishes `AppointmentReminderRequested` exactly 24 hours before the start. The worker declares its own durable queue `worker.appointment-reminder-requested`, bound by the event name to `appointments.events`, with the dead-letter queue `worker.appointment-reminder-requested.dlq` through the direct exchange `worker.consumers.dlx`.
2. A message outside the v1 schema goes to the DLQ. A repeated `eventId` (at-least-once delivery) is acknowledged without effect.
3. `GET .../appointment-notifications/{id}/details`: a 404, another `appointmentVersion` (D13), another patient or a status other than `PROGRAMADA`/`CONFIRMADA` discards the delivery, with no email and no outcome.
4. `GET .../patients/{id}/billing-contact` resolves the address and display name, kept in memory only.
5. At most 3 email attempts with exponential backoff (`WORKER_EMAIL_BACKOFF`, 2 s then 4 s by default). A permanent provider rejection stops at once. The final result is saved before it is reported, keyed by the producer's `deliveryId`, so a redelivery never sends twice.
6. `POST .../outcomes` reports `SENT` with the provider `Message-ID` or `DELIVERY_FAILED` with its code and attempts. A 409 (`STALE_VERSION`, `IDEMPOTENCY_CONFLICT`) is final and never retried. A failure never changes anything in the owner.
7. The message is acknowledged only after the result is recorded. Owner or store outages are retried in place (`WORKER_CONSUMER_ATTEMPTS`, `WORKER_CONSUMER_BACKOFF`) and then dead-lettered.

The email is plain text in Spanish, with date and time in `America/Bogota`, and has no links, tokens, actions or clinical content. No scheduling, rescheduling, cancellation or no-show email exists. Logs are JSON with `correlationId`, `eventId` and `deliveryId`, never an address or a message body.

## Invoice and payment emails (ADR-005, HU-BIL-002/003)

1. Billing publishes `InvoiceIssued` and each successful `PaymentRecorded` after it commits. The worker declares its own queue `worker.billing-financial-emails`, bound by both names to `billing.events`, with the DLQ `worker.billing-financial-emails.dlq` through `worker.consumers.dlx`. `InvoicePaid` is not consumed: the final payment already arrives as `PaymentRecorded`.
2. A message outside the v1 schemas goes to the DLQ. A repeated `eventId` is acknowledged without effect; the `eventId` is the delivery key, so each fact sends at most one email.
3. `GET .../invoices/{id}/notification-summary?sourceEventId=` and `.../pdf?sourceEventId=` return Billing's representation as of that event. A 404 from Billing, or from Patients for the contact, discards the delivery without email.
4. At most 3 email attempts with exponential backoff, as for reminders. The result (`SENT` with the `Message-ID`, or `DELIVERY_FAILED` with its code) is saved in `notifications.financial_deliveries` and the event is marked `RECORDED`. Billing receives no report: a failed email never undoes issuance or payment.

The email is plain text in Spanish with the PDF attached: invoice number and date, the invoice labels, total, paid amount, balance and status, and for a payment the new amount. It has no links and no clinical narrative, and the worker never calculates money. The summary and the PDF stay in memory for one delivery.

## Configuration

Copy `.env.example` to `.env` and fill it in. Never commit `.env`, tokens, passwords or keys.

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `notifications` schema as `notifications_runtime` |
| `WORKER_RABBITMQ_URI` | Broker URI with credentials (secret) |
| `APPOINTMENTS_EVENTS_EXCHANGE`, `WORKER_QUEUE_PREFIX` | Consumed exchange (`appointments.events`) and queue prefix (`worker`) |
| `WORKER_CONSUMER_ATTEMPTS`, `WORKER_CONSUMER_BACKOFF` | Retries of a message on owner or store outages (`5`, `PT1S`) |
| `APPOINTMENTS_INTERNAL_URL`, `APPOINTMENTS_SERVICE_TOKEN_FILE` | Appointments private contract; token with `appointments:notification-details:read appointments:notification-outcome:write` |
| `PATIENTS_INTERNAL_URL`, `PATIENTS_SERVICE_TOKEN_FILE` | Patients private contract; token with `patients:billing-contact:read` |
| `BILLING_INTERNAL_URL`, `BILLING_SERVICE_TOKEN_FILE` | Billing private contracts; token with `billing:notification-summary:read billing:invoice-pdf:read` |
| `WORKER_BILLING_ENABLED`, `BILLING_EVENTS_EXCHANGE` | `false` runs reminders only (`true`); consumed Billing exchange (`billing.events`) |
| `WORKER_OWNER_TIMEOUT` | Owner call timeout (`PT3S`) |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_STARTTLS`, `SMTP_FROM`, `SMTP_USERNAME`, `SMTP_PASSWORD_FILE`, `SMTP_TIMEOUT` | Email provider; credentials optional |
| `WORKER_EMAIL_BACKOFF` | First delay between email attempts (`PT2S`) |
| `WORKER_ENABLED` | `false` starts the process without the pipeline |
| `WORKER_SECRETS_DIR`, `PLATFORM_NETWORK` | Docker Compose only: host folder mounted read-only at `/run/secrets`, and the external network |

Service tokens are short-lived RS256 JWTs with `sub` `dlc-worker`, the owner's audience and space-separated scopes, one per line in a file. The worker rereads the file on every call, so replacing it atomically rotates the token. It holds no signing key and never uses a staff token.

## Database migration

The platform DBA runs `db/deploy/bootstrap.sql` once per environment (roles `notifications_migrator` and `notifications_runtime`, tracking schema `notifications_liquibase`), sets both passwords, runs the migration, and runs the bootstrap again to revoke `CREATE` on the database:

```bash
docker compose --env-file .env -f db/deploy/compose.migrate.yml --profile tooling run --rm notifications-db-migrate update
```

## Build and test

```bash
mvn -B verify
```

Database and broker tests run only when `TEST_DATABASE_URL` (administrative JDBC URL; the test creates and drops its own database) and `TEST_RABBITMQ_URL` are set; otherwise they are skipped.

Coverage is measured by JaCoCo per module (`target/site/jacoco`) and in aggregate (`worker-app/target/site/jacoco-aggregate`). With every integration variable set (`TEST_DATABASE_URL`, `TEST_RABBITMQ_URL`, `DLC_DOCS_DIR`), `mvn -B verify -Pcoverage` fails when a module drops below its baseline, measured on 2026-11-05:

| Module | Line | Branch |
|---|---|---|
| `worker-core` | 0.98 | 0.85 |
| `worker-adapters` | 0.94 | 0.73 |
| `worker-app` | 0.77 | 0.91 |

With `DLC_DOCS_DIR` set to a `dlc-docs` checkout, every call the worker makes to the Appointments and Patients doubles, and every successful reply of those doubles, is validated against `07-api/contracts/openapi/internal-coordination.yaml`. The contract is read in place and never copied into this repository; a violation fails the test that produced it. Without the variable the check is skipped.

## End-to-end test (HU-APT-002)

`e2e/run.sh` builds a disposable stage on the Docker network `dlc-worker-e2e` (`e2e/compose.yml`):

- PostgreSQL with the `appointments` schema migrated from a `dlc-appointments-db` checkout and the `notifications` schema migrated from `db/`.
- RabbitMQ and Mailpit.
- `dlc-appointments-api` built from `APPOINTMENTS_REF` (default `origin/qa`) of its checkout.
- A WireMock double of Patients `billing-contact` that follows `internal-coordination.yaml`, until Patients exposes that route.
- The worker image.

The script generates every password, RSA key and service token for each run in a temporary folder and removes them at the end. It signs the RS256 service tokens itself because no IAM component issues them yet. Then it runs `ReminderEndToEndTest` and checks that the worker logs hold no recipient address or name. Requirements: Docker, `git`, `openssl`.

```bash
APPOINTMENTS_API_DIR=../dlc-appointments-api APPOINTMENTS_DB_DIR=../dlc-appointments-db e2e/run.sh
```

The test checks these cases:

- An appointment due in 24 hours makes Appointments publish `AppointmentReminderRequested`. The worker reads `details`, sends one email (visible in Mailpit, date and time in `America/Bogota`, no links or clinical data) and reports `SENT`. The appointment ends with `reminder_status=SENT`.
- The same event published again sends nothing.
- A request for an appointment cancelled after it was published is discarded without email.
- A message outside the event schema goes to `worker.appointment-reminder-requested.dlq`.

`WORKER_IMAGE` runs an existing image instead of building this checkout, and `KEEP_STAGE=1` leaves the stage running. `ReminderEndToEndTest` is skipped unless `E2E_DATABASE_URL`, `E2E_RABBITMQ_URL` and `E2E_MAILPIT_URL` are set, which only the script does.

## Run with Docker Compose

Requirements: the external network `platform`, PostgreSQL from `dlc-infra-postgres` with the `notifications` schema migrated, RabbitMQ on the same network (the `broker` profile of `dlc-appointments-api` while there is no shared broker) and a `.env` file at the repository root:

```bash
docker compose --env-file .env -f deploy/compose.yml up -d --build
```

For a local stage without an email provider, add the `mailpit` profile and set `SMTP_HOST=dlc-mailpit`, `SMTP_PORT=1025`, `SMTP_STARTTLS=false`. Captured emails are visible at `http://127.0.0.1:8025`:

```bash
docker compose --env-file .env -f deploy/compose.yml --profile mailpit up -d --build
```

The image is multi-stage and runs as the non-root user `worker`. It opens no port.

## Branching

Permanent branches do not accept direct commits.

```text
develop  <--PR--  feat/... fix/... test/... chore/...
qa       <--PR--  promotion/qa-worker-N
main     <--PR--  release/... hotfix/...
```

Promotion re-applies `develop` commits with `git cherry-pick -x`; permanent branches are never merged into each other. Changes to `main` require the teacher's approval. See `00-governance/branching-policy.md` in dlc-docs.
