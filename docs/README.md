# ByteQuay documentation

Start with the [user guide](documentation/README.md) to learn what ByteQuay is,
what it aims to do, and how to use its main features.

| Part | Purpose | Start here |
| --- | --- | --- |
| `documentation/` | Product introduction, goals, setup, and simple feature guides | [Use ByteQuay](documentation/README.md) |
| `overview/` | High-level product and technical design | [Understand the design](overview/design.md) |
| `design/` | Canonical working specifications, implementation plans, and supporting assets | [Working design index](design/design.md) |

## Documentation

- [What ByteQuay is and its goals](documentation/README.md#what-is-bytequay)
- [Get started](documentation/README.md#getting-started)
- [Home and workspaces](documentation/README.md#workspaces)
- [Pull requests](documentation/README.md#pull-requests) and [private review](documentation/README.md#private-review)
- [Agent tasks](documentation/README.md#agent-tasks) and [delivery and CI](documentation/README.md#delivery-and-ci)
- [Goals](documentation/README.md#goals) and [repository memory and spec checks](documentation/README.md#memory-and-specs)
- [Issues and Jira](documentation/README.md#issues-and-jira)
- [Commits and backports](documentation/README.md#commits-and-backports)
- [Circles](documentation/README.md#circles) and [help](documentation/README.md#help)

## High-level design

- [Product and scope](overview/product.md)
- [Architecture](overview/architecture.md)
- [Agent service](overview/agent-service.md)
- [Workflows](overview/workflows.md)
- [Trust, data, and safety](overview/security.md)
- [Remaining work](overview/roadmap.md)

The [project README](../README.md) covers installation;
[CONTRIBUTING](../CONTRIBUTING.md) covers development and verification.
The [website](../website/README.md) contains the product landing page,
user documentation, and a reader-friendly design overview. Published release notes belong in
[GitHub Releases](https://github.com/bytequay/bytequay/releases).

## Working documents

`documentation/` and `overview/` supply the website's user guides and high-level
design. `design/` retains working
specifications, implementation checklists, and their supporting assets at their
existing paths. Ongoing work may depend on these files, including
[Goal guidance](design/goal-guidance.md) and its
[implementation plan](design/goal-guidance-impl-tmp.md). Do not delete, move, or
untrack an active document merely because its name contains `tmp` or a public
summary exists. Publication cleanup must preserve those working dependencies.
