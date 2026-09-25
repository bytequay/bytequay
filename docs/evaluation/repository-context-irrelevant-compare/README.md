# Irrelevant project-note review comparison

A fixed, safe PR replaces a repeated `Fields.index_of(name)` call in
`resolver.py` with the index already read from the same immutable map. The
independent fixture suite passes at the base and reviewed head. Its proposed
oracle expects **no finding**, but a human has not approved that oracle.

The same local PR was reviewed in two fresh Claude Sonnet/High conversations.
Each arm had its own SQLite database and checkout, copied after an explicit
Learn produced `knowledge:project`. A normal entry edit narrowed that note to
the documented half-open **window** rule, with exact captured README and
`window.py` sources. The project scope still made it eligible for a review of
`resolver.py`. The Off arm disabled `useInReviews`; the On arm kept it enabled.
No evaluator guide or Git tags were present in either review checkout.

| Observation | Off | On |
| --- | ---: | ---: |
| Review accepted with complete coverage | Yes | Yes |
| Findings | 0 | 0 |
| Window note selected | No | `knowledge:project` v2 |
| Initial context bytes | 0 | 2,568 |
| Confirmed context packets | 0 | 2 |
| Dynamic note searches / exact opens | 0 / 0 | 0 / 0 |
| Provider turn span | 31.556 s | 30.970 s |

Both reviews explained that reusing the value preserves the immutable lookup's
result. Neither produced a window-related finding. The On run's two packet
receipts are **CONFIRMED**, so the unrelated note was available, but delivery
cannot show whether it affected reasoning. The usage view counted one selected
and provided task for that note, with no search, open or cited review.

The On note was also labeled `VERSION_MISMATCH`: the project-wide preparation
fingerprint includes `resolver.py`, which changed in this PR. Its edited claim
cites only README and `window.py`, whose bytes did not change. The warning is
therefore a real limitation of this case as a pure relevance test; it may have
helped the agent set aside the note. This pair does not establish a quality
benefit or general protection against distraction. Both arms saw two provider
rate-limit warnings, so one-run time, token and cost figures are descriptive
only.

[`result.json`](result.json) saves the frozen SHAs and patch hash, note identity,
version, source blobs, selected reasons, packet digests and delivery states,
findings, usage counts and bounded provider metrics. It excludes raw provider
transcripts and credentials. The local fake GitHub endpoint received only GET
requests, the Git server received only GET/HEAD, and no real GitHub write was
made. The disposable fixture is under `/private/tmp`.

To reproduce the data path, create the five-file corpus with
[`scripts/repository-context-cases.py`](../../../scripts/repository-context-cases.py),
serve only its `fixture-base` as `main` and `fixture-safe` as
`refs/pull/2/head` from a read-only local HTTPS bare remote, then seed the
workspace and cached PR as in the
[earlier review comparison](../repository-context-review-compare/README.md).
Run explicit Learn for `project`, edit the generated note through
`PUT /api/workspaces/{workspaceId}/repository-context/entries` to retain only
the window claim and its captured source IDs, and copy that completed state into
two independent arms. Set `useInReviews=false` only in Off. Start a new review
in each arm and save the final review, events and usage response. The frozen
corpus's `safe` oracle must exit 0. The copied completed Learn task lacks its
old runtime resources, so startup logs a build-coordinator warning; both fresh
reviews completed despite it.

A prior [Off/On review pair](../repository-context-review-compare/README.md)
observed `VERSION_MISMATCH` for a changed window source and found the seeded
boundary defect in both arms. That is a source-identity mismatch check; its
note's exclusive-boundary claim was still correct. An obsolete *wrong* rule
remains untested. Human assessment of all proposed cases remains pending.
