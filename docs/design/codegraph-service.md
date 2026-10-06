# CodeGraph service — retired

CodeGraph is no longer an executable ByteQuay service. Its CLI bundle, task and
baseline indexes, eager preparation/cleanup hooks, settings, task flag, counters,
HTTP symbol routes and `codegraph_explore` tool have been removed. Git history
retains the previous design; it is not a compatibility contract.

[Code & Tests](code-tests.md) uses one lazily started IDE runtime per worktree.
The editor and fixed task-bound `code_query` tool share its Java, Go and Rust
language providers. Bounded operations include symbols, definitions, references,
implementations, calls and diagnostics, with explicit unavailable/unsupported
results and revision/fingerprint validation. There is no replacement graph
store or natural-language graph-query alias.

Goal planning reads bounded text excerpts from the monitored Git baseline using
`GoalSourceContext`; those excerpts do not claim semantic relationships and do
not start another index. The [refactor plan](code-tests-ide-refactor.md) and
[runtime report](code-tests-ide-verification/runtime-gate.md) record the migration
and its verification limits. Development schema handling is documented in
[Code & Tests](code-tests.md#verification-and-development-data).
