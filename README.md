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

Under construction. Scope of the first delivery: informational appointment reminders (HU-APT-002, issue #1). Billing invoice and payment email (issues #2 and #3) are not started.

## Branching

Permanent branches do not accept direct commits.

```text
develop  <--PR--  feat/... fix/... test/... chore/...
qa       <--PR--  promotion/qa-worker-N
main     <--PR--  release/... hotfix/...
```

Promotion re-applies `develop` commits with `git cherry-pick -x`; permanent branches are never merged into each other. Changes to `main` require the teacher's approval. See `00-governance/branching-policy.md` in dlc-docs.
