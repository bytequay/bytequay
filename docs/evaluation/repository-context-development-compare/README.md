# Repository-context development comparison

One synthetic development investigation ran twice against the same five-file repository at `99dac517a7758debe9005eec0b910109c7ce8d32`, with the same prompt and Claude Sonnet/high settings. The Off arm disabled repository context; the warm On arm enabled a previously learned `knowledge:project` version 1 note. Each arm used a separate SQLite database, workspace clone, task worktree and provider conversation. Neither wrote to GitHub. Both used backend JAR SHA-256 1204933b67c0aa171ba4f10d224a6c2bd42e1b158f898e785feefd441a58ad2d.

| Measure | Off | Warm On |
| --- | ---: | ---: |
| Development turn completed with a valid result | Yes | Yes |
| Proposed no-change oracle matched | Yes | Yes |
| Focused tests | 2 passed | 2 passed |
| Tracked changes | 0 | 0 |
| Confirmed context offers | 0 | 1 (2,348 bytes) |
| Dynamic searches / exact-note reads | 0 / 0 | 0 / 0 |
| Recorded task elapsed time | 15.290 s | 13.129 s |
| Input / cached input / output tokens | 138,040 / 127,656 / 992 | 113,414 / 96,302 / 759 |
| Provider-reported cost | 0.0769672 | 0.0952824 |

Both agents correctly rejected the proposed namespace bug. The two `resolver.resolve` calls use the same immutable all-fields index map, while `visible_names` is only a filtered view. The second index is the first plus `parent_offset`. Both agents ran `python3 -B test_contract.py`; the saved command output says two tests passed. An independent rerun passed in each worktree, and both remained at the baseline commit with clean tracked files. The completed turns left their reusable tasks OPEN, which is the expected task state after a development turn.

The warm On run selected the repository-wide note and recorded one CONFIRMED offer of 2,348 bytes. Its usage dashboard counted one selected and provided task, with no dynamic search or exact-note open. The Off dashboard counted one context-off task and no provided notes. Delivery proves that the note was available to the On agent; it does not establish that the note caused its answer. Both arms reached the same result. On used fewer tokens and less recorded task time but had a higher reported cost. Each arm recorded one provider rate-limit warning, so this pair does not support a performance or cost claim.

An unscored setup attempt returned HTTP 503 before a model turn because the disposable fixture workspace had updatedAt before createdAt. The fixture timestamps were corrected in both databases, then fresh tasks supplied the scored pair.

A separate, **unscored On-only lookup smoke** used a fresh development task after the paired run. Its initial offer (2,348 bytes), SEARCH for `window index contract` at `window.py` (789 bytes), and exact READ of `knowledge:project` v1 (12,252 bytes) were all CONFIRMED. The first SEARCH and READ attempts had extra parameters and failed; valid retries succeeded. The note's applicability was marked UNVERIFIED, and the agent compared its claims with the README and code. It reported no discrepancy or code edits. It did not run tests or submit a valid development-result report, so development automation stopped after the provider turn. The dashboard captured after this smoke counted **two** On development tasks, two provided tasks, one search hit, one found task and one opened task. Those totals include the earlier paired On run and are not the paired comparison's usage counts. A later [Codex lookup acceptance check](../repository-context-codex-lookup/README.md) used the same fixture and confirmed an OFFER, SEARCH and READ in a real Codex turn; it remains outside this scored Claude pair.

[`development-results.json`](development-results.json) retains the paired answers, delivery receipts, dashboard counters and provider metrics, plus the separate lookup smoke. It excludes raw provider events, shell transcripts and local credentials. The fixture prompt and proposed oracle are in [`case.json`](case.json); its `humanApproved` flag is still false. The baseline test result is in [`oracle-results.json`](oracle-results.json). The warm note and five complete source reads are in [`learned-notebook.json`](learned-notebook.json) and [`learn-build.json`](learn-build.json). The corpus generator is [`scripts/repository-context-cases.py`](../../../scripts/repository-context-cases.py).

This is one small, no-defect acceptance check, not a statistical quality evaluation. The proposed oracle still needs human adjudication. Larger tasks, real defects, stale or misleading notes, and resource measures such as CPU, memory and database wait were not tested here. The lookup smoke checks dynamic retrieval separately; its result cannot be treated as a third paired arm.
