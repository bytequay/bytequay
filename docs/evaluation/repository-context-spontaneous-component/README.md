# Unprompted component-note development comparison

Two fresh Codex `gpt-6-sol`/high development tasks received the same natural
customer report at the same regressed commit. The Off arm disabled repository
context; the On arm enabled one previously generated `knowledge:window` note.
The prompt named no note, lookup tool, PR, source file, or desired fix. Each arm
had a separate disposable SQLite database, workspace clone, task worktree and
provider conversation. PR automation was disabled and nothing was written to
GitHub.

| Measure | Off | On |
| --- | ---: | ---: |
| Root and Refine summary turns completed | Yes | Yes |
| Restored the inclusive end comparison | Yes | Yes |
| Added a shared-boundary regression test | Yes | Yes |
| Independent focused test rerun | 2 passed | 2 passed |
| New test against original regressed code | 1 failure | 1 failure |
| Clean worktree with a local commit | Yes | Yes |
| Initial note entries / bytes | 0 / 0 | 0 / 0 |
| Dynamic note SEARCH / READ | 0 / 0 | 0 / 0 |
| Task elapsed time | 94.107 s | 98.110 s |

**This pair shows no spontaneous note lookup and no observed quality gain.**
The On task's empty initial OFFER was confirmed, and no SEARCH or READ receipt
exists for either turn. Both agents inspected the local Git history, including
the earlier inclusive-end commit, then independently made the same behavior
change and a focused test. The note was built, but no note content was
delivered through ByteQuay in the On arm. The answer could be reached from Git
history alone; this one pair does not measure a benefit from the note or prove
that Codex would never search in another task. Elapsed time and token counts
in [`results.json`](results.json) are single-run observations, not performance
evidence.

The explicit **Learn** used the cached merged PR #7 and its maintainer
discussion. It generated `knowledge:window` with a recorded-decision claim:
the shared endpoint was intentionally visible in both neighboring dashboard
panels. The selected scope has `pathPrefixes: ["sensors/"]` and
`terms: ["window"]`. Because the customer prompt has no `window` term and an
initial development turn has no changed-file paths, the note did not match
the automatic offer. The On agent still had the managed lookup tool available
for a later search. The note was learned at the merged base; a READ against
the regressed head would still require checking its source versions. The On
usage view counted one demanded but missing
**project** overview, because that repository-wide scope has no note; it did
not mean the built `window` component note was missing. Its `window` note
counts remained zero for selection, provision, search hit, found task and
opened task.

[`case.json`](case.json) holds the exact prompt and proposed oracle.
[`oracle-results.json`](oracle-results.json) verifies the generated commit
chain and cached PR patches: the ordinary suite passes at all three commits,
but an independent exact-endpoint probe is false, true, false across pre,
merged base and regressed head. The new regression test from each task fails
against the original head and passes after its fix. The proposed oracle still
has `humanApproved: false`; a historical merge is evidence of a decision, not
proof that the requirement applies forever. [`learn-build.json`](learn-build.json)
and [`learned-notebook.json`](learned-notebook.json) save bounded source-linked
Learn evidence. [`results.json`](results.json) saves frozen task identities,
receipts, usage counts, diffs, independent checks and provider metrics without
raw transcripts or credentials. A separate independent replay applied each
saved diff to a fresh `fixture-head`; each added test failed before the fix and
both tests passed after it.

An earlier unscored setup attempted a term-only `window` scope on the original
root-level fixture. ByteQuay correctly deferred generation because it could
not fingerprint any local component path; see
[`setup-attempt.json`](setup-attempt.json). The corrected corpus puts the
implementation and test in a real `sensors/` directory. Run
`python3 -B fixture.py /private/tmp/new-component-case` from this directory to
recreate its three commits; a separate recreation produced exactly the saved
SHAs. The disposable GitHub fixture returned no PR #7 fetch during Learn or
the task pair, so Learn used ByteQuay's seeded PR cache.
