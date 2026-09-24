# Codex development note lookup acceptance check

A real Codex development task completed against the disposable five-file Python
repository used by the [development comparison](../repository-context-development-compare/README.md).
ByteQuay offered `knowledge:project` v1, then the agent explicitly searched
for `window index contract` at `window.py` and read the exact returned note.
All three delivery receipts are **CONFIRMED**. The agent compared the saved
claims with the current README and source, found no discrepancy, and reported
two passing tests. An independent rerun of `python3 -B test_contract.py`
passed; the task worktree had no tracked or untracked changes. PR automation
was disabled and nothing was published.

| Delivered item | Bytes | Result |
| --- | ---: | --- |
| Initial offer | 2,348 | CONFIRMED |
| Dynamic SEARCH | 789 | CONFIRMED; found `knowledge:project` v1 |
| Exact READ | 12,252 | CONFIRMED; opened `knowledge:project` v1 |

The prior Codex HTTP 503 happened **before** a model turn because ByteQuay
launched App Server with `forced_login_method="chatgpt"`. That setting itself
made `configRequirements/read` non-null, so ByteQuay's isolation guard correctly
rejected the task. The task launch no longer supplies that setting. The guard
still requires no managed requirements and now checks `account/read` for
`requiresOpenaiAuth: true` and `account.type: chatgpt` before a turn. A direct
read-only App Server probe confirmed those fields and a null requirements value
for the isolated ByteQuay profile. The live task then started and completed
under the unchanged managed-requirements rule.

[`result.json`](result.json) saves the task identity, backend build hash,
confirmed cards, final answer, development-result receipt, focused test and
clean-worktree checks. The saved usage dashboard is **cumulative** for this
disposable workspace; its totals include earlier development tasks. Only the
three cards with this task's turn ID establish this task's use.

This is an **unscored acceptance check**, not a paired quality comparison. The
prompt explicitly asked Codex to use the lookup tool, so it does not prove
that Codex would discover the note without prompting. It also does not prove
that the note improved the answer: the task has no Off arm. The earlier Claude
Off/On comparison is recorded separately.

The required backend `mvn verify` run passed the Codex module but had one
Claude HTTP event-order test fail while running tests concurrently. That exact
test passed on an isolated rerun. Packaging with `-DskipTests` then passed and
produced the JAR used for this live check. The full suite was not green in a
single run; the failure and rerun are retained in the local test logs.
