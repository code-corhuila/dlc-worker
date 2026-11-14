# Changelog

All notable changes of `dlc-worker` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/). A release is tagged `v<version>` once it is merged into `main`.

## [Unreleased]

## [3.0.0] - 2026-11-14

The changelog starts at this release.

### Added

- Read the appointment reminder requested envelope.
- Deliver appointment reminders with bounded retries.
- Send reminder email through an smtp provider adapter.
- Call appointments and patients private contracts with service tokens.
- Persist the inbox and delivery results in the notifications schema.
- Consume reminder requests with own queue and dead-letter queue.
- Compose the reminder pipeline from configuration.
- Deliver invoice and payment emails in the core.
- Consume Billing invoice and payment facts.
- Fetch Billing summaries and PDFs and store financial results.
- Wire invoice and payment emails.
- Request service tokens from IAM by client credentials (ADR-020).
- Add scheduled appointment email use case.
- Consume appointment scheduled events and send email.
