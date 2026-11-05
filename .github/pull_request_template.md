## User Story

Reference the corresponding user story or project issue.

Example:

`code-corhuila/dlc-docs#XX`

---

## What changes and why

Describe briefly:

- what this Pull Request changes;
- why the change is necessary;
- which responsibility of the Notifications worker it addresses.

---

## How it was tested

Describe the tests executed.

Example:

```text
mvn -B verify
```

---

## Promotion trace

Complete this section only when promoting changes to `qa` or `main`.

Include the commits re-applied with:

```text
(cherry picked from commit <sha>)
```

Not applicable for normal development Pull Requests into `develop`.

---

## Checklist

- [ ] The change stays within the worker: delivery only, no appointment, clinical or financial state.
- [ ] No secrets, tokens, private keys, or real `.env` files are committed.
- [ ] Schema changes touch only the `notifications` changelog; the runtime never migrates.
- [ ] `worker-core` does not depend on Spring, JPA, or infrastructure frameworks.
- [ ] Owner contracts and event schemas remain aligned with `dlc-docs`.
- [ ] No recipient email, message body or personal data is stored or logged.
- [ ] Tests were executed successfully.
- [ ] `mvn -B verify` passes.
- [ ] The Pull Request references its corresponding user story or task.
