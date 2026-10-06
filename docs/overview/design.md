# ByteQuay design

ByteQuay is a local-first macOS app for GitHub pull-request work and local agent
tasks. An Electron and React interface talks to one localhost Spring Boot
sidecar backed by SQLite.

These documents preserve product intent, ownership, data flow, trust boundaries,
and important tradeoffs. They summarize the
[working designs](../design/design.md); they do not replace specifications and
implementation plans needed by ongoing work.

## Reading map

| Document | Question it answers |
| --- | --- |
| [Product](product.md) | What does ByteQuay do, and how do its surfaces relate? |
| [Architecture](architecture.md) | Where do responsibilities, data, and dependencies belong? |
| [Agent service](agent-service.md) | Who owns task execution, provider integration, and recovery? |
| [Workflows](workflows.md) | How do review, delivery, CI repair, and related-repository work proceed? |
| [Trust and safety](security.md) | What is authorized, isolated, retained, or sent outside the Mac? |
| [Remaining work](roadmap.md) | Which capabilities and verification remain incomplete? |

## Design principles

- Keep application state local and the backend bound to localhost.
- Require direct user action or explicit accepted automation for remote writes.
- Put framework-free contracts upstream and infrastructure at the edges.
- Reuse the current agent service and fixed feature workflows; do not restore
  the deleted task-agent or staged development-flow systems.
- Add modules and durable lifecycle states only for real coordination,
  replacement, recovery, or testing needs.
- Preserve uncertain user work instead of guessing that execution or cleanup
  succeeded. Normal cleanup is distinct from explicit destructive deletion.
- Separate implemented capability from unverified release claims and future
  product proposals. Git history supplies chronology, not dates in design files.

Feature expansion requires an explicit approved design. Keep this overview at
the level of user behavior, boundaries, and tradeoffs. Working specifications
and active implementation plans stay in `../design/` at their existing paths.
