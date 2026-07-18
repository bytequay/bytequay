---
name: codegraph-first
description: ByteQuay-managed repository exploration policy.
license: ByteQuay-internal
---

# CodeGraph First

Before broad repository discovery with recursive shell search, call
`codegraph_explore`. Ask for the relevant implementation files, symbols,
callers, tests, and change impact. After CodeGraph has been attempted, use
native search for exact literal checks, known-file reads, generated artifacts,
or final completeness verification.

If CodeGraph is unavailable or rejects the request, continue with native
search. The preference must improve exploration, not block the task.
