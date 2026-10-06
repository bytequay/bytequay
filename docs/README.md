# ByteQuay design

This directory contains ByteQuay's high-level architecture, canonical feature
designs, and supporting design references.

| Part | Purpose | Entry point |
| --- | --- | --- |
| `overview/` | Product scope, architecture, workflows, trust boundaries, and roadmap | [High-level design](overview/design.md) |
| `design/` | Canonical feature contracts, decisions, and approved UI references | [Feature design index](design/design.md) |

## High-level design

- [Product and scope](overview/product.md)
- [Architecture](overview/architecture.md)
- [Agent service](overview/agent-service.md)
- [Workflows](overview/workflows.md)
- [Trust, data, and safety](overview/security.md)
- [Remaining work](overview/roadmap.md)

Keep durable design decisions and acceptance boundaries in the relevant design.
Temporary implementation checklists, execution logs, verification captures, and
release-note drafts do not belong here. Approved UI mockups and their assets
remain with the feature designs they illustrate.

User-facing instructions live in the [website guide](../website/documentation.md).
Development setup and verification commands live in [CONTRIBUTING](../CONTRIBUTING.md),
with runbooks beside their scripts. Published release notes belong in
[GitHub Releases](https://github.com/bytequay/bytequay/releases).
