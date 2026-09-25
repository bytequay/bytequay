# History-dependent repository-context review comparison

One synthetic PR was reviewed twice with Claude Sonnet/High against the same
backend JAR and frozen base/head. The Off arm had repository context disabled;
the warm On arm had a generated project note from an explicit **Learn** of
merged PR #7. The arms used separate SQLite databases, workspace clones and
fresh provider conversations. No real GitHub repository or account was used,
and nothing was published.

| Measure | Off | Warm On |
| --- | ---: | ---: |
| Accepted review; coverage complete | Yes | Yes |
| Expected boundary regression found | Yes | Yes |
| Other findings | 0 | 0 |
| Project note selected and delivered | 0 | 1 |
| Confirmed context packets | 0 | 2 |
| Initial context packet | 0 bytes | 5,074 bytes |
| Dynamic note opens | 0 | 0 |
| Completed provider turns | 2 | 2 |
| Provider turn span | 81.9 s | 66.4 s |
| Reported input / cached input / output tokens | 551,926 / 511,570 / 6,942 | 433,770 / 367,084 / 5,711 |
| Provider-reported cost | 0.513775 | 0.602815 |

**There is no observed quality gain in this pair.** Both reviews found the
only seeded regression on `window.py:2`: PR #8 changes the end check from
inclusive `<=` to exclusive `<`, so a sample exactly at the shared end
disappears from the earlier dashboard window. Both compared base and head
behavior and observed that the ordinary test still passes. The Off agent
found merged PR #7 through ByteQuay's existing selected-PR review tool even
though repository context was off. The On agent received the learned note and
read the selected PR #7 history again. That other review tool is a viable
independent path to the same evidence; this fixture cannot attribute the On
finding to the note. These one-run timing and cost numbers have no performance
significance. Both arms recorded two provider rate-limit warnings.

The explicit Learn task completed READY. It inspected the three baseline
files and read cached merged PR #7, including the maintainer's explanation
that the shared-boundary overlap was intentional. It produced
`knowledge:project` version 1 with a `RECORDED_DECISION` claim and the
historical source reference. During On review, the repository-wide note was
included with `VERSION_MISMATCH`: `window.py` at the reviewed head differs
from the learned baseline. Its 5,074-byte initial packet and 2,266-byte
continuation were confirmed delivered. The usage dashboard recorded one
review provided with knowledge, zero note searches/opens, and zero cited
reviews. The finding cited the underlying PR history and command evidence,
not the note ID. Delivery proves availability, not reliance.

[`case.json`](case.json) contains the synthetic PR text and a **proposed**
oracle. `humanApproved` remains false, so the semantic quality result is
**unscored** until the user or maintainer accepts or revises it. A historical
merge records a decision; it does not prove that the decision is universally
correct or still applies to all callers. [`review-results.json`](review-results.json)
holds bounded finding text, evidence explanations, selected-note reasons,
delivery receipts, usage counters and provider metrics. It excludes provider
transcripts, shell output and credential material. [`learn-build.json`](learn-build.json)
and [`learned-notebook.json`](learned-notebook.json) show the explicit Learn
result and generated note. [`provider-auth.json`](provider-auth.json) records
only the signed-in boolean from ByteQuay's isolated Claude profile.

The generated three-file Python corpus has `fixture-pre` (exclusive end),
`fixture-base` (inclusive end, merged PR #7 result) and `fixture-head`
(exclusive end again, review PR #8). `main` points to `fixture-base` and the
local bare remote exposes `refs/pull/8/head` at `fixture-head`. The review
checkout contains only `README.md`, `window.py` and `test_window.py`; no
oracle or evaluator tag is visible to the agent. The README and ordinary test
do not state or check the end-boundary rule. The decisive explanation is in
the seeded PR #7 description and maintainer discussion. The ordinary suite
passes at all three commits; [`oracle-results.json`](oracle-results.json)
records that an independent shared-end probe is true only at the base.

To recreate the data portion in an empty scratch directory:

```bash
python3 docs/evaluation/repository-context-history-compare/fixture.py create /private/tmp/my-new-history-case
python3 docs/evaluation/repository-context-history-compare/fixture.py verify /private/tmp/my-new-history-case
```

The [`fixture.py seed`](fixture.py) command takes a database, corpus, managed
checkout and local bare remote, then loads cached PR #7, its comment and
default diff, plus PR #8, into a disposable Hibernate-created SQLite database.
It refuses an existing workspace or a database outside `/private/tmp`.

```bash
python3 docs/evaluation/repository-context-history-compare/fixture.py seed <database> <corpus> <managed-checkout> <bare-remote>
```

The fake GitHub endpoint answered only selected identity/repository metadata and
returned 404 for other requests. Its request log had **no PR #7 fetch**;
selected-history Learn used ByteQuay's seeded cache. A local HTTPS Git server
served the bare repository. After Learn, each arm was copied into its own
database/workspace and only the completed Learn turn's stale provider-launch
marker was removed. The first Learn attempt failed before launching because
the workspace Git origin differed from its seeded clone URL; correcting that
fixture mismatch let the next explicit request complete. Background metadata
refresh got expected 404s from the narrow fake endpoint. Neither prevented
the accepted reviews. [`setup-attempts.json`](setup-attempts.json) records
these fixture corrections separately from the scored arms.

Capture an accepted `GET /api/agent-review/{id}`, its events with
`?after=0&limit=500`, and the workspace's repository-context usage response
as `final-review.json`, `events.json` and `usage.json` in each disposable arm.
The [`fixture.py summarize`](fixture.py) command takes the disposable fixture
root and a separate results directory, then exports only bounded facts from
those responses and the Learn notebook; it verifies the frozen commits and
enabled states. Set `CASE_ROOT` to the disposable live-run root:

```bash
mkdir "$CASE_ROOT/results"
cp docs/evaluation/repository-context-history-compare/case.json "$CASE_ROOT/results/case.json"
python3 docs/evaluation/repository-context-history-compare/fixture.py summarize "$CASE_ROOT" "$CASE_ROOT/results"
```

The backend JAR SHA-256 was
`7addad7aa102ec8937809e353af8bb037a34489f63f5c5231e657c30d6553dd7`;
Claude CLI was 2.1.281. The case measures one synthetic history decision, not
general review quality, CPU, memory, SQLite contention or development tasks.
