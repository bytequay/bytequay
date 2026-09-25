# Repository-context review comparison

One synthetic pull request was reviewed twice with Claude Sonnet/high against
the same backend JAR, frozen base/head, cached PR and model settings. The first
arm had repository context turned **Off**. The second had a previously learned
project note turned **On**. Separate SQLite databases, workspace clones and
provider conversations prevented carry-over between arms. Neither run
published to GitHub.

| Measure | Off | Warm On |
| --- | ---: | ---: |
| Accepted review; coverage complete | Yes | Yes |
| Expected boundary bug found | Yes | Yes |
| Other findings | 0 | 0 |
| Note selected and delivered | 0 | 1 |
| Confirmed context packets | 0 | 2 |
| Bootstrap context bytes | 0 | 6,100 |
| Dynamic note opens | 0 | 0 |
| Completed provider turns | 2 | 2 |
| Provider turn span | 44.7 s | 59.6 s |
| Reported input / cached input / output tokens | 354,286 / 318,683 / 3,247 | 461,722 / 395,527 / 4,493 |
| Provider-reported cost | 0.404058 | 0.581210 |

Both reviews independently identified the only seeded regression: changing
`window.contains` from `< end` to `<= end` violates the documented half-open
window contract. Both cited a failing `END_EXCLUSIVE` test and the adjacent
window boundary behavior. The On run selected `knowledge:project` version 1
because of its repository-wide scope. It correctly marked the note
`VERSION_MISMATCH`, since `window.py` changed after the note's baseline. Its
6,100-byte initial packet and 1,644-byte continuation were both confirmed as
delivered. The usage dashboard recorded one review provided with knowledge,
and zero dynamic searches or note opens. Delivery proves access, not that the
note caused the finding. This small, easy case shows no quality gain; the On
run took longer and used more tokens. Both arms also received provider rate
limit warnings, so these timing and cost numbers should not be generalized.

[`review-results.json`](review-results.json) retains the finding text, evidence
explanations, selected-note reasons, delivery receipts, usage counters and
provider metrics. It excludes provider transcript, shell output and local
credential material. [`learned-notebook.json`](learned-notebook.json) captures
the warm note and its five complete source reads; [`learn-build.json`](learn-build.json)
captures the explicit Learn result. The fixed corpus oracle is in
[`oracle-results.json`](oracle-results.json).

The five-file Python corpus comes from
[`scripts/repository-context-cases.py`](../../../scripts/repository-context-cases.py).
Its baseline is `99dac517a7758debe9005eec0b910109c7ce8d32`; review head
is `a8cd427b4b6b04af139b329269fdc3aa231d0c2e`. The oracle fails at
`END_EXCLUSIVE` at the head and passes at the baseline. The review clone
contained neither evaluator tags nor the oracle guide. A local HTTPS Git
server provided only `main` at the baseline and `refs/pull/1/head` at the
bug head. A local fake `api.github.com` endpoint answered `GET /user`,
`GET /repositories/101`, and `GET /repos/contextfixture/review-case`; it
returned 404 for other requests. Its repository metadata gave the same local
HTTPS clone URL. No real GitHub account, repository or database was used.

To rebuild the data portion of this fixture, first generate the corpus with
`python3 scripts/repository-context-cases.py create <scratch>/cases`, create a
bare Git remote with the two refs above, and start isolated ByteQuay against a
new SQLite database. Use
[`fixture.py seed`](fixture.py) after Hibernate creates the schema to
insert the workspace and cached PR. Explicitly invoke `Learn project` with
scope `project`, and wait until it completes; the learned note must be version
1. [`fixture.py split`](fixture.py) then copies that post-Learn DB into two
independent arms and gives each a local HTTPS origin with its own trusted CA.
The script removes only the completed Learn turn's stale provider-launch row:
its launch marker belonged to the source runtime, which is not copied. Start
each arm separately, turn Off only `useInReviews` in the Off DB, start the same
review, and wait for an accepted run. Save each arm's final `GET
/api/agent-review/{id}`, events (`?after=0&limit=1000`) and repository-context
usage responses. [`fixture.py summarize`](fixture.py) produces the
sanitized result JSON from those three response files per arm. The helper
script refuses to overwrite an earlier arm so a rerun does not silently
replace evidence.

From this directory, the data helpers are:

```bash
python3 fixture.py seed <database> <cases> <managed-repository> <bare-remote>
python3 fixture.py split <database> <arms-directory> <bare-remote> <https-url> <ca-certificate>
python3 fixture.py summarize <arms-directory> <result.json>
```

The backend JAR SHA-256 was
`1204933b67c0aa171ba4f10d224a6c2bd42e1b158f898e785feefd441a58ad2d`;
Claude CLI was 2.1.281. Codex CLI 0.156.1 reported READY, but an earlier
Codex review attempt returned [`codex-blocked.json`](codex-blocked.json):
HTTP 503, “Codex isolation check failed: Codex managed requirements apply to
this host.” It reached no model turn and was not scored. The cause was a
ByteQuay launch setting that made the requirements response non-null; the
subsequent [Codex development lookup check](../repository-context-codex-lookup/README.md)
completed after that launch setting was removed and ChatGPT account validation
was moved to `account/read`. A live Codex review comparison is still untested.
A copied, already completed Learn task initially left a launch record pointing at a runtime
marker absent from the split arms; startup recovery blocked agent operations.
The split helper now validates completion and removes that copied record. The
guard itself was not bypassed for an active process. The background workspace
metadata refresh also received 404 from the deliberately narrow fake API;
the cached PR review path still completed in both arms.
The unscored setup attempts are separately recorded in
[`setup-attempts.json`](setup-attempts.json).

This is one paired acceptance check, not a statistical quality evaluation.
The note was learned at the baseline, and the review patch changed one of its
sources; other note types, larger PRs and dynamic discovery remain unmeasured
by this review pair.
