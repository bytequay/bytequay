# Review discovery calibration

`run.py` runs one bounded, read-only Codex CLI discovery pass against exact Git
objects. Use it to inspect whether a policy elicits useful hypotheses before
running a production review comparison. It does not exercise ByteQuay's reporting
tools, child agents, validation turns, final selection, or persistence. It cannot
establish that the complete review workflow has improved.

The script exports the merge-base and head as ordinary files, without Git history,
working-tree edits, untracked files or later commits. It also captures the diff,
policy and supplied context, hashes every input, and records the CLI version,
model, reasoning setting, exact command, elapsed time, JSONL events and response.
The source checkout is read-only. Output must be a new directory outside it.
Symlinks and submodules are rejected rather than silently changing source meaning.
This is a small-fixture tool; exporting a large repository copies every blob.

The CLI pins the built-in OpenAI provider and uses the existing authenticated account. It ignores user configuration
and execution rules, disables memory, plugins, apps, native delegation and web
search, uses an ephemeral read-only session, and redirects its SQLite state and
logs to the output directory. The invocation does not change ByteQuay settings,
reset a database, or write to GitHub. A hard wall-clock timeout kills the provider
process group. An interrupted or failed arm has no discovery score.

## Freeze and run

Before changing policy, save the old `ReviewPrompts.java` and `bytequay-review.md`
outside the checkout. Record the exact source commit and hashes with them. Keep
that manifest beside the results; do not reconstruct a baseline from memory.
`run.py` compares the policy resource only. Its identical discovery-only wrapper
replaces the unavailable report tools; it does not reproduce the Java request or
the production workflow. Keep that limitation attached to any result.

Supply a context file containing only intent, requirements and PR prose available
at the review cutoff. Exclude later review comments, fixes and reference answers.
Record the prose source and cutoff in that file; Git timestamps alone do not
prove when a comment was available. A real historical fixture requires an
independently frozen context snapshot. Do not substitute today's PR description.

Run each policy against the same refs, context, model, reasoning and test timeout.
Replace `CONFIGURED_REVIEW_MODEL` and `CONFIGURED_REVIEW_EFFORT` with the actual
Review configuration; both arguments are required. The harness ignores user
configuration, so it cannot infer those settings. Replace `TEST_TIMEOUT_SECONDS`
with the explicit wall-clock budget for this experiment. This timeout belongs
only to the harness. Production turns have no time limit unless
`bytequay.agent.maximum-turn-runtime` is set, and these runs do not change it.

```sh
python3 scripts/review-calibration/run.py \
  --repo /path/to/source --base BASE_SHA --head HEAD_SHA \
  --policy /path/to/frozen-baseline/bytequay-review.md \
  --context /path/to/frozen-context.md --output /tmp/review-baseline-run1 \
  --model CONFIGURED_REVIEW_MODEL --reasoning CONFIGURED_REVIEW_EFFORT \
  --timeout TEST_TIMEOUT_SECONDS

python3 scripts/review-calibration/run.py \
  --repo /path/to/source --base BASE_SHA --head HEAD_SHA \
  --policy backend/app/src/main/resources/agent-skills/bytequay-review.md \
  --context /path/to/frozen-context.md --output /tmp/review-proposed-run1 \
  --model CONFIGURED_REVIEW_MODEL --reasoning CONFIGURED_REVIEW_EFFORT \
  --timeout TEST_TIMEOUT_SECONDS
```

Inspect `runtime.json` first. Keep failures and partial transcripts; do not count
a timeout or `incomplete_output` as zero findings. Completion requires a successful
turn event and a schema-valid response, even when the CLI exits zero. Then inspect
`response.json` and the source cited by
each hypothesis. Check both manifests agree on all source and context hashes.
Do not publish model hypotheses as validated findings.

## Scoring

`case.py`, `judge.py` and `report.py` score runs against a human reviewer's
inline comments. They need Python 3.12 or later and the one dependency in
`pyproject.toml`; run them with [uv](https://docs.astral.sh/uv/) from this
directory. The judge calls DeepSeek and reads its key only from
`DEEPSEEK_API_KEY`.

A case is one public pull request: the reviewer who opened the most threads, or
the one named; the commit their first comment was written on; that comment's
time as the cut-off; and their thread-opening inline comments on that commit as
the answer key. The description is the version from before the cut-off, read
from GitHub's edit history, and is omitted when that version is gone. A case
never falls back to a later commit, because the comments would point at other
code. `case.py list` prints recently merged pull requests that fit, so cases
are chosen by a rule. Private repositories are refused: the judge sends comment
text and leads to a model provider. A case holds other people's comment text,
so `--output` must be outside this repository; record chosen cases here only as
pointers (repository, number, commit, comment ids) in `cases.json`. A case whose
comments or scores anyone has read is a `tuning` case and can no longer serve
as an unseen holdout.

```sh
uv run case.py list --repo OWNER/NAME --since YYYY-MM-DD
uv run case.py build --repo OWNER/NAME --pr NUMBER --clone /path/to/source \
  --output /tmp/lab/cases/NAME-NUMBER
```

`build` prints the fork point and the reviewed commit. Pass them to `run.py` as
`--base` and `--head`, with the case's `context.md` as `--context`. Write each
run to `ROOT/SETTING/RUN`, with one `SETTING` directory per rules file or other
arm, then judge it and print the table:

```sh
uv run judge.py run --case /tmp/lab/cases/NAME-NUMBER/case.json --run ROOT/SETTING/RUN
uv run report.py ROOT
```

The judge makes one call per reviewer comment and answers FOUND, PARTLY or
MISSED with the leads that match. Leads are shuffled by `--seed` and numbered,
so the judge sees neither their order nor their setting. An answer that is
empty, cut off or the wrong shape is asked again once and then recorded as a
judge error, never as MISSED. Only completed runs are judged. `report.py` lists
failed, timed-out and unjudged runs apart and leaves them out of every total,
and it names a setting whose runs used different rules files or models. Leads
that match no comment are counted as extra leads and are not judged: the
reviewer may have missed a real problem.

Check the judge before trusting a table. The answer key as leads must be all
FOUND, another case's leads must be all MISSED, and two judgements of one run
show how many verdicts flip, since the judge has no seed:

```sh
uv run judge.py run --case CASE --oracle --output /tmp/lab/checks/known-good
uv run judge.py run --case CASE --leads OTHER_RUN/response.json --output /tmp/lab/checks/known-bad
uv run judge.py run --case CASE --run RUN --output /tmp/lab/checks/again
uv run judge.py agree --judgement RUN/judgement.json --labels /tmp/lab/checks/again/judgement.json
uv run judge.py agree --judgement RUN/judgement.json --labels LABELS.json
```

`LABELS.json` maps a comment id to the verdict a person gave it. Set the
agreement to reach before reading the judge's answers, and after changing the
judge prompt measure it again on calls nobody has read.

## Synthetic smoke fixture

`fixture/` is a deliberately small example for checking the harness and reading
its output. The contract permits using CSV rows after leaving the context, while
the changed implementation defers reading an owned file until iteration. It is
an exposed answer, not an unseen quality benchmark. Its result must never be
reported as holdout recall or precision.

Create a disposable Git repository from the two trees, commit `fixture/base/`
then `fixture/head/`, record both SHAs, and supply `fixture/context.md` to the
commands above. Keep the fixture README, `fixture/case.json` and this
explanation outside the exported input. No test or runtime mutation is needed
for discovery. `fixture/case.json` is a hand-written answer key with one
comment about the closed file; judging the fixture run against it and printing
the table should give one FOUND row. `fixture/unrelated-leads.json` holds two
leads about other code, to pass as `--leads` for the all-MISSED check. Verify
the tools without a provider call using:

```sh
uv run python -m unittest discover -p 'test_*.py'
```

## Production comparison and adjudication

For an actual quality claim, freeze a small calibration set and a separate unseen
holdout set before adjusting the policy. Use the existing frozen review/replay
path with the same repository rules, source, PR context, provider, model, budget
and tool access for both arms. Start fresh review tasks; do not reuse a conversation
that has seen the other arm or reference answers. Preserve each run's raw discovery,
validation, full supported pool, final selection and transcript. Do not reset a
user's live review database to replay a fixture.

Use the design's proposed pilot of three calibration and six holdout PRs with two
runs per arm. Freeze the proposed prompt before opening holdout outputs. Pool
candidates from both arms with an independent manual review, hide arm attribution,
and adjudicate issue families against the frozen source. Record for each family:
independent contract, reachable trigger, consequence or concrete removable cost,
source/control evidence, duplicate root cause, discovery phase, validation result,
and selection membership. Later maintainer comments are reference leads requiring
verification, not automatically correct ground truth.

Report initial discovery and cumulative discovery separately; include baseline
late nit checks and validation discoveries in cumulative recall. Separate discovery
recall, validation precision and selected-finding precision so a filter cannot hide
missed discovery. Give per-PR and pooled results, denominators, duplicate/unsupported
counts, timeouts, cost and elapsed time. Precision with zero selected findings is
undefined. Describe unjudged hypotheses and missing historical context explicitly.
Without an adjudicated holdout corpus, report execution feasibility and observed
hypotheses only; leave improvement claims unmeasured.
