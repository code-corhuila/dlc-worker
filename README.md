# dlc-worker

Transversal Notifications worker for Di Lucca Dental Care & Technology. This repository is a **Java structure only**: it does not yet consume events, call owner APIs, send email, or record delivery outcomes.

## Architecture

The Java layout follows Annex D with the Di Lucca responsibilities from [ADR-005](https://github.com/code-corhuila/ods-docs/blob/main/05-architecture/decisions/ADR-005-hybrid-integration-and-notifications.md):

| Module | Intended responsibility |
| --- | --- |
| `worker-core` | Notification use cases and inbound/outbound ports; no framework or owner business rules. |
| `worker-adapters` | RabbitMQ event consumer; scoped Appointments/Billing owner clients, email provider, correlation and delivery outcome adapters. |
| `worker-app` | Spring Boot composition root and configuration. |
| `deploy` | Docker image and opt-in Compose scaffold, with no host port. |

Appointments owns reminder timing and current appointment state. The worker will consume only `AppointmentReminderRequested` for appointment mail, verify current version/status through the owner contract, and deduplicate by `eventId`. Billing owns invoice/payment facts and PDF generation; the worker will also consume `InvoiceIssued` and `PaymentRecorded` and obtain the event-time representation through private contracts. It does not access owner databases or decide appointment, clinical or financial state.

Delivery must be idempotent, preserve correlation, make at most three email attempts after receipt, record outcomes and route exhausted work to operational follow-up. Queue topology, retry delays, provider choice and private endpoint details are intentionally not invented in this scaffold.

## Build

Requires Java 21 and Maven 3.6.3 or newer. From the repository root, run `mvn -B verify`. If Maven is unavailable locally, run `docker compose --profile scaffold -f deploy/compose.yml build`; the Docker build executes Maven `verify` in its build stage. The build currently verifies module wiring; there are no functional tests or coverage claims until behavior is implemented.

`deploy/compose.yml` uses the `scaffold` profile so this idle application cannot be mistaken for an operational notification consumer. The service must be wired and tested before the profile is removed.

## Branching

Use child branches of `develop`, `qa` and `main` with PRs. Promote validated commits to `qa` and releases with `git cherry-pick -x`. Changes to `main` require teacher approval. See [branching policy](https://github.com/code-corhuila/ods-docs/blob/main/00-governance/branching-policy.md).
