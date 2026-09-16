# Repository Context

Status: approved implementation design. The user authorized development from
this design, including default-on review use and the workspace Off switch.
Implementation progress and provider/performance acceptance are recorded in
the linked checklist; this contract alone does not establish those results.

**Start with [Overall flow](#2-overall-flow).** Delivery checkpoints and evidence
belong in [the temporary implementation plan](repository-context-impl-tmp.md).
This document contains behavior, not an implementation progress log.

## 1. Decision and concepts

Repository Context prepares useful project knowledge and relevant evidence for
ByteQuay's existing PR review task. It reuses the managed checkout, cached PRs,
commit enrichment, issues, previous tasks/reviews, provider runtimes, review
investigation/validation, and SQLite. It does not create another agent engine.

| Concept | Meaning | Example | Ownership |
| --- | --- | --- | --- |
| Knowledge | A short, sourced explanation of a project or component | Trino architecture; Iceberg connector contracts and test locations | Workspace notebook |
| Memory / saved lesson | A lesson deliberately saved by the user from previous work, with conditions and evidence | A correction to an earlier review's interpretation | Workspace notebook |
| Context | The particular notes, sources, code identity, and discussions supplied for one review | Iceberg notes plus related PRs and issues at the reviewed version | Review run; later discussion supplements |
| Skill | Instructions about how to perform work | Investigate counterevidence and validate findings | Existing managed-skills service |

“Notebook” means a saved collection of notes. The UI calls it **Repository
context**, with **Knowledge** and **Saved lessons** groups. It is not a provider
conversation or provider-owned memory store.

There are two durable aggregates:

1. One notebook JSON value per workspace in the existing application settings.
2. A context snapshot on the existing review run, with source excerpts in its
   existing evidence map and bounded per-turn delivery/discussion records.

Knowledge is shared across that workspace's review tasks and providers. The
first version does not automatically share notebooks across distinct workspaces,
even when both monitor the same upstream; their user corrections may differ.
The existing repository-ID/SHA enrichment and PR caches remain shared.

### 1.1 First-version boundary

Included:

- Automatic selection and preparation at the beginning of a review investigation.
- Explicit Build/Refresh, note editing, disabling/removal, and Save lesson.
- Existing-data-first related history discovery and targeted missing-data reads.
- Mandatory compact context delivery, larger on-demand reads, and visible provenance.
- Shared generated notes with version checks, explicit empty/failed outcomes,
  preservation of user corrections, and bounded concurrent preparation.
- Existing review validation and a measured enablement gate.

Deferred:

- Full development-turn context integration, CI/cherry-pick integration, and
  independent child-agent delivery. Development-associated PR reviews are included
  because they already use the same review workflow.
- Automatic incoming-comment triage or a full re-review of an unchanged accepted head.
- Autonomous promotion of findings into memory, embeddings/vector search, whole-site
  crawling, a knowledge graph, background model research, or a new scheduler.
- A separate database, Git repository for notes, provider memory framework, or
  new Maven module.

The notebook can be reused by development later; this design does not silently
add context tools or notebook write authority to ordinary development tasks.

## 2. Overall flow

### Setting and comparison

**Workspace settings → Repository context → Use repository context in reviews**
is one switch, **on by default** in the shipped feature after its acceptance
gates pass. There are no separate knowledge/memory/context switches in V1.

- On: new review runs automatically select, prepare as needed, and receive
  Repository Context as described below.
- Off: new review runs use the ordinary review flow, without this feature's
  notebook selection, automatic preparation, extra input, or fixed context
  tools. Existing review tools, native source research, skills, investigation,
  evidence capture and validation remain available.
- Changing the switch applies to newly created runs. Existing runs, their
  validation turns, and later discussions retain their captured setting and
  sources. Already delivered conversation text cannot be removed from a model.
- Turning it off preserves the notebook and review history. Viewing/editing
  notes and explicitly clicking Build/Refresh remain available; those are
  deliberate notebook actions, independent of automatic use in reviews.

The comparison starts with a small representative PR set, using the same frozen
code, provider/model and other settings with the switch off and on. Use fresh
independent review tasks for each arm. Toggling and clicking Update at the same
accepted head is not a comparison: that action creates no new run, and a resumed
conversation may already contain knowledge. No comparison runs happen
automatically for ordinary users. Section 12 defines the evaluation details.

### 2.1 The normal user flow

1. The user clicks the existing **Start review** action.
2. ByteQuay follows the existing readiness, worktree, and frozen-review-scope
   checks and captures the workspace switch for this run. When off, it continues
   the ordinary review flow and records “Repository context off.” The remaining
   steps describe an enabled run. It captures the locally available monitored
   default-branch commit separately from the PR's frozen base/head.
3. ByteQuay reads the notebook and local source metadata, matches notes and
   historical candidates, and creates a bounded bootstrap packet. This includes
   existing applicable note text, source references, versions, matching reasons,
   and missing/stale/failed preparation facts.
4. ByteQuay supplies that packet directly to the existing investigation turn.
   The agent does not need to discover or open a notebook file to receive it.
5. At the opening of investigation, the agent reads relevant sources. If project
   or component knowledge is missing or outdated, it prepares a small sourced
   update through the fixed local reporting tool. ByteQuay validates identities,
   source references, size, ownership, and notebook revision before saving.
6. The agent continues investigating the same PR in the same turn. Its
   investigation report captures the brief, source references, context limitations,
   hypotheses, and assessed files. It does not treat a generated note as proof.
7. Existing validation and, where necessary, evidence-collection turns receive
   the frozen context and challenge the hypotheses against the actual PR code,
   applicable original sources, tests, and counterevidence.
8. The user sees the existing review report plus **Context used**: what was
   provided, newly retrieved, and cited. Findings remain private until the
   user's existing publication action.
9. The user can correct a note or explicitly save a lesson. Subsequent runs use
   the current notebook; completed snapshots keep their original content.

**Cold-start detail:** generated notes cannot be delivered before the turn
that generates them. A cold run initially receives local facts, source pointers,
and explicit unbuilt scopes. Source/tool results and the preparation result
then become available inside that same investigation. Validation receives the
completed brief. There is no extra “acknowledge reading” turn and no mandatory
separate preparation agent for each review.

~~~mermaid
flowchart TD
    A["User: Start review / Update to new head"] --> B["Existing readiness, worktree and frozen scope"]
    B --> R{"Repository context enabled for this run?"}
    R -->|Yes| C["Read notebook and existing local data"]
    R -->|No| O["Ordinary investigation and validation"]
    O --> K
    C --> D["Match notes and history; capture bootstrap"]
    D --> E["Existing investigation turn receives context"]
    E --> F{"Relevant generated knowledge missing or outdated?"}
    F -->|Yes| G["Read sources; save bounded preparation result"]
    F -->|No| H["Reuse saved notes"]
    G --> I["Investigate PR and save context brief"]
    H --> I
    I --> J["Existing validation / evidence loop"]
    J --> K["Private report and Context used"]
    K --> L["Optional user correction / Save lesson"]
    L --> C
~~~

The last arrow means reuse on a later requested run; saving a lesson does not
start a review. Opening UI, background sync, and observing a remote head do
not start this flow.

### 2.2 First Iceberg review, then a second review

**First review:** build a short project overview and an Iceberg component note
from the captured repository baseline and applicable documents. Inspect local
path history, join commits to existing PR mappings, and read selected cached
discussions. Save generated notes and this run's selected evidence.

**Second review:** reuse those notes, regardless of whether the provider is
Codex or Claude. Compare relevant source fingerprints and versions. Build only
missing or outdated generated content. Select related history again because
the new PR has different code and discussions.

A PR-only implementation change belongs in that run's context. It must not
rewrite shared knowledge as though it had already landed on the default branch.

### 2.3 Explicit Build/Refresh without a review

In **Workspace settings → Repository context**, Build context prepares the
project overview and explicitly configured component scopes. Refresh knowledge
operates on selected enabled scopes, or all configured enabled scopes when the
user chooses all. It never invents a project-wide crawl of every directory.

The application:

1. Checks provider readiness before allocation and uses the current local
   baseline, running the existing workspace synchronization only when its normal
   readiness/staleness rules require it.
2. Creates a normal managed-worktree, noninteractive attended AUTO task using
   the existing task service. A saved context-build binding establishes its
   purpose before its first turn starts.
3. Gives it a **policy-only review bridge**, plus the fixed context tools.
   This reuses existing review restrictions and the no-child launch profile
   without fabricating a PR, review, or finding. AUTO by itself is insufficient
   to establish these restrictions.
4. Uses common/Global guidance and a research assignment; it receives neither
   development delivery automation nor PR reporting/publication tools.
5. Records structured preparation results. Completion, Stop, approvals, runtime
   recovery, and verified clean-worktree cleanup use existing task behavior.
   Failed or dirty work is preserved according to that behavior.

After the builder turn settles, its application observer records the preparation
outcome and explicitly invokes AgentCompletionService.complete, then
cleanupWorktree when permitted. An ordinary successful turn alone leaves a task
open. An unresolved runtime ownership/cleanup failure stays visible through the
existing recovery flow; it cannot be reported as a cleaned-up build.

This is a restricted research assignment, not a claim that every native tool is
read-only: the existing review profile permits local scratch/experiment writes.
It grants no commit, push, GitHub publication, or credential-read authority.
The UI links to its existing transcript. There is no separate build-job engine.

### 2.4 Exact trigger matrix

| Event | Notebook effect | Agent execution and context effect |
| --- | --- | --- |
| Read/open settings, notebook, or PR | None | None |
| Change Use repository context in reviews | Save workspace preference; retain content | Applies to new runs; no automatic run, rebuild, or cancellation |
| First requested review needs an unbuilt scope | Prepare missing generated knowledge | Opening part of existing investigation |
| Start a new run with matching existing notes | Check selected source fingerprints; reuse or prepare | Capture a new bootstrap and run snapshot |
| Git/commit synchronization | None by itself | Source data becomes available; no model execution |
| Worktree HEAD changes | None by itself | No generation merely because HEAD moved |
| Build/Refresh button | Prepare selected generated scopes | Explicit research task through the current agent service |
| User edits/disables/removes a note | New notebook content revision | No review restart |
| User saves a lesson | New user-owned MEMORY entry | No review restart |
| Background PR/issue refresh | None | Refresh existing source records only |
| Someone posts a GitHub review/comment | None | Existing refresh sees it; no automatic agent turn |
| User posts a GitHub comment in ByteQuay | None | Existing publication/cache behavior; no automatic agent turn |
| User asks privately about a card/finding | None unless explicitly saving a lesson | Existing immediate/queued discussion gets the source run plus relevant new evidence |
| Update review / applicable Resume moves to a newer head | Check knowledge when preparing new run | Existing guarded sync, then new snapshot and review run |
| Update review with unchanged accepted head | None | Existing PR detail refresh; no new review or automatic comment analysis |
| Investigation becomes validation/evidence collection | None by default | Reuse the same run's context; append needed evidence |

“First relevant use” means an actual requested review preparation needs a
scope with no successful preparation for those inputs. It does not mean first
app launch, first provider login, or every agent invocation.

### 2.5 Discussion after new comments or a newer review

Resolve the accepted turn through the existing ReviewToolAssignment. An old
card's discussion remains bound to that card's source run and code identity,
even when the managed worktree has since advanced.

At actual turn execution, not enqueue time, capture the selected discussion
thread and newly available relevant comments using existing local services.
Append them as a turn-owned supplement with provenance. Do not mutate the
original brief or silently replace its note versions with today's notebook.
If newer knowledge is specifically needed, label it as a supplement.

The current Ask-agent path starts/enqueues before inserting ReviewMessage.
Change this ordering: persist the message's source run, card/finding revision,
and request key with empty delivery IDs first; then start/enqueue and attach
the returned IDs. The existing resolver can bind that saved request key.
Otherwise an idle discussion reaches the new prelaunch context resolver before
its source run can be resolved. Preserve a failed admission as a visible unsent
message with retry using the same request identity; never silently retarget it.

An incoming GitHub comment is only new source data. To have it analyzed in V1,
the user refers to it in the existing agent discussion/composer. This design
does not make Update review on an unchanged accepted head rerun the analysis.

## 3. Reuse existing sources before fetching

Use stable repository identity and workspace routing. The selected repository
owns the physical checkout; the monitored repository may be its upstream.
Never join a fork's PR number to an upstream PR merely because the numbers match.

| Need | Existing application boundary | Necessary narrow change / limit |
| --- | --- | --- |
| Local commits | CommitHistoryService.listHistory; GitBrowseService.log | Add path-filtered Git history; current ordinary list is at most 300 commits |
| Commit → PR | RepositoryEnrichmentService.findCommits(repositoryId, shas) | Reuse exact stored mappings before any provider request |
| Missing commit association | CherryPickPullRequestLookup.lookUpResult | Reuse for selected misses and persist through RepositoryEnrichmentService; never use parsed title suffixes as verified associations |
| PR list/title/number search | PullRequestService.listPullRequests | Current search is title substring or number, not full body/path search |
| PR body, merged state, reviews, discussions | PullRequestService and PrDetailStore | Read saved detail first; selected missing/stale detail uses existing refresh |
| PR final files/diff | PullRequestService.getPullRequestDiffFiles; PrDiffStore | Missing snapshots need existing refreshDiffFiles; no new path index |
| Issues and comments | WorkspaceIssueService.list/find/detail | Open-issue projection; no claim of complete closed-issue history |
| Issue refresh | WorkspaceIssueRefreshService.refreshIssues/refreshDetail | Reuse current services; selected closed/missing references may need existing backend client |
| Past task conclusions | AgentTaskService.listCompleted/getInteractive | Scoped completed interactive summaries; open-task learning reports remain deferred; no general task changed-path index |
| Past review paths and discussion | AgentReviewStore.listReviews/listRuns/listFindings/listMessages | Reuse run diffs and retained evidence |
| User activity/text | RecentService.search/words | Supplemental only: touched items, rolling retention, scoped before final limiting |
| Source files and tests | Existing Git/source readers; optional CodeGraph | Original frozen source remains evidence; graph hits are navigation |
| External documentation | User/repository-selected source roots and native web discovery | Bounded source capture described below; no website crawling for GitHub data |

ReviewTools uses the existing issue row/detail first and refreshes selected
missing or outdated activity. A known PR selected through the issue/PR read
uses the PR cache instead. An uncached issue or closed issue absent from the
open projection uses the existing exact RepoService.getIssuePreview fallback;
it does not refresh the whole issue projection.

PR discovery/retention and issue synchronization are bounded. Older closed PRs
may expire; the open-issue projection drops closed rows; commit enrichment
normally follows recent refs. Expose those limits. “No cached match” must not
be rendered as “no related work exists.”

### 3.1 Source reads and freshness

Selection reads local metadata first. A selected detail may be hydrated through
the current refresh service when absent, known to lag its object update, or
too old under that feature's existing freshness rule for a claim about current
state. Reuse rate-limit handling, unavailable-section flags, and timestamps.
Do not add a broad fetch to the display/read path.

Immutable commit/file content does not expire because of age. Mutable PR/issue
facts retain observed update and retrieval information. A cached limitation
must not be presented as freshly confirmed when refresh failed.

The existing review_get_context kinds pr, timeline and checks accept an optional
PR number within the review's repository. Omitting it selects the reviewed PR.
SelectedPullRequestReader joins the stored PR row and detail before choosing
one existing refresh operation:

1. Reuse the requested section when its required sources are available, its
   detail update matches the row and its status does not need refresh under
   the existing PR freshness rule. Unrelated missing sections remain visible
   without forcing a refresh of this read.
2. Missing detail, known update lag or a missing requested section uses the
   existing full-detail refresh. A conditional PR response can report unchanged
   while retaining stale detail or an unavailable discussion section, so it
   cannot repair that gap by itself.
3. Expired open/monitored PR status uses the existing conditional refresh.
   Unchanged, unmonitored closed PRs have no invented age limit.
4. Recheck the saved result. A successful request can still leave unavailable
   sections or lagging data; expose those facts. On refresh failure, retain the
   original cached result and timestamps. With no cached result, return the
   existing source error. Each call attempts at most one refresh and never
   starts a repository-wide scan.

Each response exposes source and row update times, the last cache update,
the read time, refresh attempt/failure, known staleness and missing sections.
The cache update timestamp includes status-only saves; it does not establish
when every discussion was fetched. Neither a recent timestamp nor an empty
unavailable-sections list proves exhaustive upstream history. Returned PR,
activity and check excerpts retain this provenance in the run's evidence.
PR activity and checks return at most 20 items per page with explicit omission
counts. Their individual evidence references are saved in one run update per
nonempty page, avoiding a full retained-run rewrite for every item.

Current PR code coordinates, commits and diff always come from the frozen
ReviewRun. The response labels the separately observed source head and whether
it matches the reviewed head; current checks can belong to a newer head.
Related PR responses never borrow the current review's code coordinates.
Hydration adds source evidence without replacing the initial context snapshot,
changing notebook claims, or establishing preparation readiness. These existing
review reads also remain available when automatic repository context is Off.

One selected refresh may use the existing detail fetcher's concurrent GitHub
calls and default-diff refresh. Bounded returned excerpts do not mean one HTTP
request or bounded upstream latency. These reads retain the existing review
serialization lock during source access; they hold no notebook lock or
context-owned database transaction. Measure that wait before changing locking.
The existing six-hour background full-detail failure backoff is not a selected
read policy. No new retry cache is introduced: a later explicit source read can
retry a failed or incomplete refresh, subject to the existing GitHub rate-limit
handling. Repeated failing reads therefore remain a latency/rate-budget risk.

The commit_prs kind of review_get_context accepts one to ten distinct full
hexadecimal commit IDs, normalized to lowercase. The backend resolves the
monitored repository and its stable ID from the review's workspace; callers
cannot supply either identity.
SelectedCommitPullRequestReader first batch-reads existing enrichment. Positive
associations are reused. For unresolved IDs, local Git metadata reads with a
two-second timeout and a 256-byte accepted output bound verify the exact commit
and supply its commit time. Missing local objects and unavailable metadata
remain gaps without fetching Git history. Existing process termination/drain
can add time; the runner's hard capture bound remains 5 MiB.

RepositoryEnrichmentService owns the same association retry rule used by
ordinary commit refresh: unchecked commits are due, positive associations are
reused, and checked negatives can retry once per day while the commit is at
most seven days old. Only due selected IDs reach the existing
CherryPickPullRequestLookup. Its bounded positive cache includes the source
branch used to resolve ambiguous matches. Negative results are not memoized
there, so that cache cannot defeat the durable retry rule. A missing response
or a response reaching the 100-candidate page bound is unavailable, not a
checked negative or a verified single match.
Existing durable rows do not record the lookup branch; only the newly selected
lookup can be attributed to the workspace's current default branch.

One enrichment upsert saves successful selected results, preserving existing
avatar fields. Failed IDs keep their previous evidence and check time. A
failed save is exposed separately from the observed lookup result. A successful
save is reread to include concurrent positive mappings; an older check time
cannot confirm that the new observation was saved. Each
returned item records its SHA, selected mapping or lack of a single merged
mapping, check time, lookup attempt/failure and persistence outcome. Persisted
means the returned positive or checked observation is known to be stored; a
row containing only avatar data does not establish an association observation.
Individual
evidence references are retained with one run update for the returned batch.
“No single merged PR” can mean no match or ambiguity; it does not prove that no
related PR exists. A mapping also does not establish the final PR diff, why it
was merged, or applicability to the reviewed code. The initial local context
snapshot remains unchanged; this explicit source read does not build notes.

### 3.2 Related PR patch reads

The diff kind of review_get_context keeps the current review's frozen diff
when number is absent or names that review. A different same-repository number
selects its existing default PR diff through SelectedPullRequestDiffReader.
This reads the PR's patch artifact; it does not reconstruct a merged PR by
comparing today's base branch with the old head. The default cache has no
verified base/head pair, retrieval time or completeness record.

The initial read joins the stored PR identity and PrDiffStore entry. A missing
PR uses the existing selected PR reader; its full-detail hydration can already
populate the diff, so reread before requesting another refresh. A missing diff
uses PullRequestRefreshService.refreshDiffFiles for that exact PR. An explicit
refresh on the initial file-list read can replace an existing artifact through
the same service. Cached artifacts otherwise remain local reads without an
invented age limit. A stored empty file list differs from an absent snapshot.
On refresh or confirming-read failure, retain the original cached artifact and
report failure; with no artifact, report unavailable. Missing HTTP list bodies
must fail rather than being saved as successful empty lists. A valid empty JSON
array and an unchanged conditional response retain their existing meanings.

The returned diffVersion is SHA-256 over the normalized repository, PR number
and complete ordered file list, including framed paths, statuses, counts and
nullable patch text. Null and empty patches hash differently. The fingerprint
identifies the observed contents, not a verified Git revision pair. It excludes
separately changing PR metadata and expanded file bodies. Duplicate paths make
the artifact ambiguous and are rejected. String frames retain exact UTF-16 code
units, including malformed surrogate values from old cached JSON; encoding
replacement cannot make different contents share a version.

Read the initial file-list page to obtain diffVersion. Every path read and
later file-list page requires that value. Continuations are strictly local:
if the PR/cache disappeared or the full artifact's fingerprint differs, return
a source-changed error and require a new initial read. Do not refresh or switch
artifacts midway through pagination. The fingerprint prevents accidental page
mixing; the bound review repository and selected PR number provide authority.
No per-review copy of the entire diff or new cache/table is necessary.
Fingerprinting scans the cached artifact on each page. Measure that cost on
large PRs before adding a stored digest; page-size limits bound transport, not
the existing cache load or the full fingerprint scan.

File lists return at most 20 entries per page. Patch pages contain at most
2,000 Unicode code points, preserving surrogate pairs; they may start or end
mid-line. Even a single enormous line remains bounded. Each returned patch
fragment is retained exactly as evidence, with the PR identity,
fingerprint, page and read time. Related patches have evidence references, never
the current review's inline finding anchors. Finding locations still resolve
only against the frozen reviewed diff. Large page numbers cannot overflow
offset calculations. Paths exceeding the tool's supported length are shown as
unreadable previews, never silently substituted as another exact path.

Report the separately observed PR head/update time and changed-file count as
metadata, with version linkage, freshness, completeness and final-merge status
unverified. A count mismatch or missing patch adds a gap; matching counts do
not prove completeness. GitHub's PR-file endpoint itself returns at most
3,000 files, and the existing generic paginator has its own bounds. See the
[GitHub PR-file contract](https://docs.github.com/en/rest/pulls/pulls#list-pull-requests-files).
Existing cached artifacts can also predate the missing-response guard. Source
pages preserve their exact contents without certifying the complete final
merged change, current applicability or why the PR was accepted. Initial
context capture and notebook knowledge remain unchanged.

### 3.3 Selected documentation reads

External documents are the narrow new capture requirement. Native web can
discover useful URLs, but current provider events do not reliably retain
retrieved page bodies. A model-pasted URL/quote is **agent-reported**, not an
application-verified read.

For durable documentary evidence, the fixed source reader accepts an existing
registered source ID and, for a documentation root, a relative page selector.
For example, docs-trino plus connector/iceberg.html selects one page. It does
not accept an arbitrary absolute URL. The backend resolves the page, validates
it, and issues its evidence ID. It fetches only the selected
public documentation root/page, captures bounded text and version/provenance,
and returns an application-created evidence ID. A user-configured root permits
pages beneath its normalized host/path prefix; validate every redirect and
resolved destination, reject private/loopback/link-local addresses and
non-HTTPS schemes, and never forward GitHub credentials/cookies. Exact-page
sources authorize only that page. Native discovery outside configured roots
remains available as agent-reported research, not automatically trusted capture.

Use existing HTTP/text libraries where sufficient; no browser automation,
whole-site mirror, login flow, or generic fetch service. HTML, plain text and
Markdown only in V1:
stream at most 1 MiB of response data and at most 128 KiB of extracted text for
one page; return pages of at most 16 KiB. Mark truncation and unsupported
formats explicitly. Retain selected/cited excerpts, not full sites.

Documentation roots initially come from user links or explicit links in the
repository's checked README/docs. Save the roots and their provenance in the
notebook before using the fixed reader. User changes use the settings editor.
During cold start, begin may register backend-validated scope/root proposals
from captured module/README evidence before claiming its revision. Registration
and admission are one short mutation. Do not edit source configuration inside
an already admitted attempt: a newly discovered root waits for a later explicit
preparation, or remains agent-reported evidence for this run. Repository-suggested
roots still pass the same destination checks and grant no extra authority.

The implemented selected reader is exposed through the existing
`review_get_context` operation with `kind=document`. With no sourceId it lists
the current notebook's configured sources, five per page, without a network read. With a
sourceId it accepts an optional relativePage, a one-based page and an optional
captureId returned by the first page. A captured enabled run is required before
even listing sources; an absent historical snapshot or an Off snapshot cannot
use this addition. The current workspace switch does not rewrite that run's
choice. User source registration uses the existing notebook configuration API;
automatic repository-derived registration remains part of preparation.

For example, after listing sources with `{"kind":"document"}`, select one:

~~~json
{
  "kind": "document",
  "sourceId": "docs-trino",
  "relativePage": "connector/iceberg.html",
  "page": 1
}
~~~

If the response has a nextPage, use its actual captureId for that continuation
(the UUID below is illustrative):

~~~json
{
  "kind": "document",
  "sourceId": "docs-trino",
  "relativePage": "connector/iceberg.html",
  "captureId": "5f942031-095a-4c62-a05a-0e06872a6505",
  "page": 2
}
~~~

Initial reads reuse a temporary capture if present. Up to 16 selected document
captures are retained in process memory, each expiring ten minutes after capture.
They are keyed by workspace, complete registered source definition and resolved
selected URL. Each contains at most 128 KiB of extracted UTF-8 text; the limits
bound retained text, not the JVM's complete allocation footprint. This is a
pagination cache, not shared generated knowledge or durable site storage.
Later pages require the same captureId and make no network request. Restart,
eviction, expiry, refresh or source replacement requires a new first read.
The exact source definition is checked again after capture, outside notebook
locks and transactions. A changed definition cannot silently redirect a
continuation. Unrelated notebook edits do not invalidate an authorized capture.

An explicit refresh is allowed only on page one without captureId. If it fails,
an existing capture can be returned with its original time/content and an
explicit refresh-failed gap. Without an earlier capture the failure remains
unavailable. Blank successful text has no evidence reference and is distinct
from a failed read. Every successful selected page now retains a typed
ContextDocumentRead in the run's context sourceReads, including a blank page.
A nonblank returned page and its receipt are saved in one run update; the exact
excerpt lives once in the existing evidence map. The full temporary page body
is not persisted.
The existing evidence read and Save lesson action retain access to those
excerpts after the temporary capture is gone. Baseline and selected document
reads share a limit of 128 retained receipts per run. Repeated successful calls
consume that limit, even if they select the same page; source listing does not.
This bounds these additions, not the entire existing run evidence map.

The document HTTP client is separate from authenticated GitHub clients. The
Boot-managed Apache HTTP client supplies a connection-time DNS resolver, so the
validated public addresses are the ones actually used for the connection.
Checking DNS before an ordinary JDK HTTP request would permit another lookup
to change the destination. Redirects are followed manually, at most three,
inside the exact registered page or root's host/path. Cookies, authentication,
ambient proxies, automatic retries and compression are disabled. Unsupported
media/charsets/encodings and unsuccessful or missing responses produce failures.
The reader requests identity encoding and uses explicit response charsets or
UTF-8, without executing HTML scripts or fetching linked resources.

At most two document workers may remain active. Connect/read limits are three
and five seconds, with a fifteen-second caller deadline covering the complete
capture including DNS. A DNS lookup that ignores interruption retains its
worker slot until it exits; later calls fail promptly when both slots are busy.
Response data is capped at 1 MiB and extracted text at 128 KiB. Reaching the
response cap is conservatively marked truncated, including exact-size bodies.
HTML text is a limited parser extraction, not a rendered browser view. Pages
are exact Unicode-safe fragments of that captured text, at most 16 KiB each.

Evidence records the registered source, sourceSelectionNotebookRevision observed
before the read, resolved URL, capture identity, extracted-content hash, retrieval time
and truncation/refresh limitations. `BYTEQUAY_READ` means the application
captured the text; it does not validate its truth or match a project release.
Document version, remote freshness and applicability remain explicitly
unverified. Native-web/model-pasted excerpts are not accepted as these captures.
This increment does not populate generated notebook knowledge or automatically
deliver the prepared bootstrap to the provider.

### 3.4 Frozen baseline source reads and receipts

Reusable repository knowledge needs source material from the captured monitored
default branch. A PR's unmerged behavior must not become shared baseline
knowledge accidentally. The existing fixed source reader now accepts:

~~~json
{
  "kind": "source",
  "revision": "baseline",
  "path": "plugin/trino-iceberg/README.md",
  "startLine": 1,
  "endLine": 100
}
~~~

An enabled snapshot and captured baselineSha are required. The application
resolves the review's workspace and monitored repository; the caller cannot
choose another repository, workspace, commit or capture identity. Existing
head/base/previous reads keep their current behavior. Baseline reads work for
any bound context-enabled review turn, including validation and discussion;
read access grants no shared notebook write authority.

ContextBaselineReader resolves the literal path in that exact commit, then
reads its regular-file blob. It ignores worktree/index edits and disables Git
replacement objects and lazy fetching. Missing local objects remain failures.
Two local commands have separate two-second deadlines; inventory output is
bounded to 8 KiB and the complete blob to 128 KiB. Trees, symlinks, gitlinks,
binary/unsupported UTF-8 content and incomplete output are rejected. The
current text check conservatively also rejects a literal replacement character.
No claim is made about a file's total lines until its complete blob was read.

The response contains at most 200 numbered source lines and 16 KiB of UTF-8
excerpt, plus exact commit/blob identity, total lines, requested bounded range,
read time and a truncation flag. Byte truncation can end within a source line;
the recorded range is not a claim that every character in it was returned.
Nonblank text is retained as ReviewEvidence. A complete empty file returns
startLine=1, endLine=0, totalLines=0 and an evidence-free receipt. Failure returns
no receipt; neither an empty file nor a blank document establishes EMPTY
knowledge preparation or proves there was nothing useful in other sources.

Successful baseline/document responses expose a backend-generated readId and
typed receipt. Each receipt carries only bounded metadata and an optional
evidenceRef; retained source text is not duplicated. The accepted turn is
resolved again after source work before recording the result. A stopped or
replaced turn observed at that check cannot append it. Receipt and evidence
share one optimistic run update, preserving the initial bootstrap, selection
and code identities. This source-read step only retains evidence. The separate atomic shared
preparation admission/save protocol is described in Sections 6 and 10.4.

## 4. Notebook and JSON contracts

The following JSON examples are illustrative, not observations about a real
Trino revision. Runtime observation times are stored normally; documentation
does not maintain authoring or inspection dates.

### 4.1 Notebook

~~~json
{
  "schemaVersion": 1,
  "revision": 7,
  "useInReviews": true,
  "buildRequestSequence": 0,
  "sources": [
    {
      "id": "docs-trino",
      "kind": "DOCUMENTATION_ROOT",
      "url": "https://trino.io/docs/current/",
      "origin": "USER"
    },
    {
      "id": "docs-iceberg",
      "kind": "DOCUMENTATION_ROOT",
      "url": "https://iceberg.apache.org/docs/latest/",
      "origin": "USER"
    }
  ],
  "scopes": [
    {
      "id": "project",
      "title": "Project overview",
      "match": {"kind": "REPOSITORY"},
      "enabled": true,
      "preparation": null
    },
    {
      "id": "path:plugin/trino-iceberg/",
      "title": "Iceberg connector",
      "match": {
        "kind": "SELECTED",
        "pathPrefixes": ["plugin/trino-iceberg/"],
        "terms": ["iceberg"]
      },
      "enabled": true,
      "preparation": {
        "attemptId": "attempt-a",
        "inputFingerprint": {
          "scopeId": "path:plugin/trino-iceberg/",
          "inputs": {"git:plugin/trino-iceberg": "040000 tree <observed-tree-sha>"},
          "digest": "sha256:<computed-from-inputs>",
          "gaps": []
        },
        "outcome": "READY",
        "noteIds": ["knowledge:path:plugin/trino-iceberg/"],
        "checkedReads": ["<full typed baseline/document read receipts; see Section 4.3>"],
        "explanation": "Prepared connector overview from the recorded sources."
      }
    }
  ],
  "entries": [
    {
      "id": "knowledge:path:plugin/trino-iceberg/",
      "kind": "KNOWLEDGE",
      "scopeId": "path:plugin/trino-iceberg/",
      "title": "Iceberg connector",
      "summary": "Concepts, dependency contracts, and test entry points to inspect.",
      "body": "Short Markdown explanation with source references such as [S1].",
      "claims": [
        {
          "text": "Example statement requiring source verification.",
          "basis": "DOCUMENTED",
          "sourceIds": ["source-connector-doc"]
        }
      ],
      "sourceIds": ["source-pom", "source-connector-doc"],
      "origin": "GENERATED",
      "enabled": true,
      "version": 2
    },
    {
      "id": "lesson-a",
      "kind": "MEMORY",
      "scopeId": "path:plugin/trino-iceberg/",
      "title": "Check the conditions from the earlier discussion",
      "summary": "A saved, conditional review lesson.",
      "body": "What was learned, why it matters, and when it applies.",
      "claims": [
        {
          "text": "Conditional lesson saved by the user.",
          "basis": "USER_LESSON",
          "sourceIds": ["source-discussion"]
        }
      ],
      "sourceIds": ["source-discussion"],
      "origin": "USER",
      "enabled": true,
      "version": 1
    }
  ],
  "suppressedKnowledgeIds": [],
  "sourceEvidence": [],
  "activePreparation": null,
  "lastBuildRequest": null
}
~~~

Field rules:

- The settings key supplies workspace identity. Source references carry their
  own repository identity so references are never ambiguous across repositories.
- revision is the notebook's **content revision**. Source configuration, scopes,
  entries, edits, useInReviews, and enable/disable changes increment it. Execution metadata
  alone does not. Entry version increments when that entry changes.
- useInReviews defaults to true when no preference is saved. It controls
  automatic review use, not retention or explicit notebook actions. A settings
  edit uses the same optimistic revision check; an in-flight generated shared
  save can conflict with it while the run retains its already captured context.
- buildRequestSequence increments on admission of an explicit builder request,
  even if extraction later returns EMPTY and content does not change. It fences
  older HTTP retries independently of content revision.
- Scope IDs are stable. The project scope is fixed. Generated component scopes
  use a validated normalized repository-relative directory, not a model's
  changing display title. An agent can propose a component boundary from
  module files; ByteQuay validates and records it before claiming preparation.
- match is either REPOSITORY or SELECTED. Empty SELECTED lists are invalid.
  No accidental repository-wide memory is inferred from missing metadata.
- claims is a short list of important assertions, not a forced sentence-level
  rewrite of the whole note. basis is DOCUMENTED, RECORDED_DECISION, INFERENCE,
  QUESTION, or USER_LESSON. Source IDs must resolve to retained source evidence.
  An unsupported assertion must become a QUESTION or explicitly marked inference.
- Sources and quotes prove provenance, not semantic correctness. Whole-note
  citations must not conceal unsupported decisive claims.
- Editing a generated entry makes origin USER. Generation cannot replace it.
  V1 has one canonical knowledge note per scope, ID knowledge:<scopeId>;
  refresh cannot choose a new ID. Saved lessons have independent stable IDs.
- Disable retains the entry but excludes it from new selection. Removing a
  KNOWLEDGE entry adds its ID to suppressedKnowledgeIds, including an edited
  note now owned by USER. Disabling a scope suppresses creation for that scope.
  Ordinary Build/Refresh preserves suppression. The separate explicit Reset
  action clears selected suppression; it never deletes an existing user note.
  To regenerate an edited note, the user removes it and then resets its scope.
  Reset also clears completed preparation for a selected missing note, so an
  old READY result cannot suppress regeneration. User memories can be deleted
  outright. READY reuse always requires its referenced canonical note to exist.
- sourceEvidence contains bounded source records referenced by current entries
  or preparation outcomes. The example omits their bodies for readability.
  Unreferenced notebook evidence is removed on the next notebook mutation.
- schemaVersion describes the wire shape, not migration-history machinery.
  Hibernate and the current application own the unshipped schema.

### 4.2 Source evidence

~~~json
{
  "id": "source-connector-doc",
  "reference": {
    "kind": "DOCUMENT",
    "sourceId": "docs-trino",
    "url": "https://trino.io/docs/current/connector/iceberg.html"
  },
  "provenance": "Selected documentation page captured through the registered source reader.",
  "capture": "BYTEQUAY_READ",
  "excerpt": "Bounded relevant source text.",
  "retrievedAt": null,
  "contentHash": "sha256:illustrative-content",
  "version": "version verified for the target",
  "truncated": false
}
~~~

JSON reference is a tagged union:

| kind | Reference fields | Observed identity |
| --- | --- | --- |
| REPOSITORY_FILE | repositoryId, repository coordinates, path, commit | blob ID; dependency version when relevant |
| PR | repositoryId, repository coordinates, number, optional comment/review ID | head/merge SHA and object update/retrieval metadata |
| ISSUE | repositoryId, repository coordinates, number, optional comment ID | state/update/retrieval metadata |
| TASK | workspaceId, taskId, optional turnId | persisted summary/message identity |
| REVIEW | reviewId, runId, optional finding/message ID | frozen head and target revision |
| DOCUMENT | registered sourceId, normalized page URL | documented version, content hash, retrieval metadata |

Optional fields serialize as null or absent according to the existing DTO
convention; domain Java uses Optional. retrievedAt is populated for an actual
read; null in this illustrative example is not an actual successful capture.
capture is BYTEQUAY_READ or AGENT_REPORTED. A failed read returns an unavailable
reason in the source result's gaps; it creates no successful retained excerpt
or empty-result proof. BYTEQUAY_READ for a REVIEW reference means the application
captured the stored review record, not that it independently verified the
record's original upstream source. Keep its source label, provenance and
explanation with the copied excerpt.

### 4.3 Preparation facts: never built, empty, failed, or no match

Each scope stores its last completed attempt:

~~~json
{
  "attemptId": "attempt-b",
  "inputFingerprint": {
    "scopeId": "project",
    "inputs": {"git:README.md": "100644 blob <observed-blob-sha>"},
    "digest": "sha256:<computed-from-inputs>",
    "gaps": []
  },
  "outcome": "EMPTY",
  "noteIds": [],
  "checkedReads": ["<full typed baseline/document read receipt>"],
  "explanation": "The inspected sources did not establish useful component-specific knowledge."
}
~~~

| Fact | Meaning | Reuse/retry |
| --- | --- | --- |
| No preparation and no active attempt | No retained completed preparation | Eligible when needed |
| READY | Valid report produced notes | Reuse while applicable |
| EMPTY | Inspected sources yielded no useful notes | Reuse negative result for unchanged inputs; explicit Refresh can retry |
| FAILED | Missing sources, interruption, invalid report, or generation failure | Retain previous good entries; retry only on a new requested preparation or explicit Retry |
| No matching scope/entry | Current selection found none | Distinct from every build outcome |
| Active preparation | Existing task/turn owns an admitted attempt | Display its live execution; do not launch duplicate generation |

EMPTY is an agent extraction judgment about **the checked inputs**, never a
claim that the repository has no useful knowledge. Inaccessible documents,
unreadable files, and truncated essential input are FAILED/limitations, not EMPTY.
No failed refresh deletes the previous usable content.
An EMPTY refresh also preserves saved text; it does not turn a previously
mismatched/unverified note into an applicable contract or silently delete it.

The persisted `checkedReads` are complete typed `ContextRepositoryRead` or
`ContextDocumentRead` values from Section 3, including successful blank reads.
These placeholder strings abbreviate full objects only in the examples above.
For a nonblank read, its notebook `evidenceRef` becomes the globally unique read
ID and resolves to the copied excerpt in `sourceEvidence`; run-local IDs such
as E1 cannot collide across reviews. A blank receipt keeps no evidence reference
and creates no invented prose. Each result permits at most eight checked reads.
The fingerprint is the full input inventory plus computed digest and gaps,
not a model-supplied digest. It is recomputed against the prospective retained
sources before saving, so newly attached sources do not invalidate their own note.

An activePreparation contains attemptId, requestKey, requestDigest, ownerTaskId,
ownerTurnId, ownerTurnKey, reviewId, runId, baseContentRevision, and `inputs`
(the complete per-scope input fingerprints). This is a fencing/ownership fact; running/queued/stopped states
come from the existing task/turn. The standalone lastBuildRequest retains its
requestKey, requestDigest, sequence, preallocated taskId, selected scopes,
baseline and optional completionReceipt so retries/navigation can find the
existing task. The completion receipt holds attemptId, operationKey,
reportDigest and the actual accepted result. Do not create a second execution
status machine.

Explicit Build passes the displayed expectedBuildSequence and expectedRevision.
A matching last request returns that task; the same key with different accepted
inputs conflicts. Any other request with an old sequence is rejected, including
a retry of A after B. Under the lock, increment sequence and save the binding
with a backend-allocated task ID before provisioning. Existing task creation
uses that ID. Recovery inspects that task/worktree identity; it never allocates
a replacement ID or blindly resends an uncertain start. A failed provisioning
request is shown as interrupted/failed through reconciliation, with explicit
Retry admitting a new request after ownership is settled. This bounds replay
storage without promising that every historical request remains replayable.

Input fingerprints include scope/source configuration, relevant local source
blob or directory-tree identities, and dependency versions. Recording a baseline
SHA is provenance; every unrelated baseline commit does not invalidate every note.
An EMPTY result also records the checked source inventory so added relevant
documents/configured roots can invalidate the negative result. Remote content
changes cannot be detected without a fetch; do not imply otherwise.

### 4.4 Review snapshot, delivery, and supplements

~~~json
{
  "id": "context-run-a",
  "enabled": true,
  "notebookRevision": 7,
  "repositoryId": 123,
  "baselineSha": "illustrative-monitored-default-sha",
  "bootstrap": {
    "text": "Exact bounded reference packet prepared for this review.",
    "utf8Bytes": 2048,
    "digest": "sha256:illustrative-packet"
  },
  "selectedEntries": [
    {
      "entryId": "knowledge:path:plugin/trino-iceberg/",
      "entryVersion": 2,
      "title": "Iceberg connector",
      "text": "The actual selected text; not just a live entry ID.",
      "sourceEvidenceRefs": {"source-connector-doc": "context-source:illustrative-digest"},
      "matchReasons": ["Changed file is under plugin/trino-iceberg/"],
      "applicability": "MATCHED",
      "inclusion": "INCLUDED"
    }
  ],
  "relatedItems": [
    {
      "sourceId": "source-related-pr",
      "matchReasons": ["Verified prior commit touches the same file"],
      "presentInReviewedBase": true
    }
  ],
  "inputFingerprints": [
    {
      "scopeId": "path:plugin/trino-iceberg/",
      "inputs": {
        "scope-configuration": "illustrative-scope-configuration-hash",
        "git:plugin/trino-iceberg": "040000 tree illustrative-component-tree",
        "git:pom.xml": "100644 blob illustrative-dependency-manifest"
      },
      "digest": "sha256:illustrative-input",
      "gaps": []
    }
  ],
  "preparationResultRefs": [],
  "sourceReads": [],
  "brief": null,
  "collectionSupplements": [],
  "gaps": [],
  "deliveries": [],
  "discussionSupplements": []
}
~~~

These identities/counts are illustrative. The backend computes real UTF-8 sizes,
digests, and source/code identities; the model never asserts them.

The owning ReviewRun supplies its frozen target baseSha, mergeBaseSha and headSha;
the snapshot does not duplicate them. baselineSha is the independently captured
local monitored default branch. Capture the snapshot and its referenced evidence
in one optimistic run update before provider admission. Null on historical runs
means no snapshot was captured, not that the feature was switched off.
INCLUDED means retained in the prepared packet. Only a separate delivery record
can establish that the provider received it. Deferred notes retain their entry
identity and version with absent text; complete note bodies are never cut to fit.

- enabled is captured from useInReviews at run creation. A disabled run retains
  that flag and code identity with bootstrap/brief absent and empty selections,
  preparation, delivery and supplement collections. Do not perform selection or
  inject an empty feature packet merely to record the off state. The UI shows
  “Repository context off for this run.” Historical enabled runs keep their data.
- Bootstrap is immutable once submitted. The investigation may append preparation
  results and fill brief once; seal it with the first accepted investigation.
  Later COLLECT_EVIDENCE reports append a supplement keyed to their investigation
  step with additional context/evidence/gaps. They cannot replace the original
  brief. Validation receives the brief plus accepted supplements. Existing
  report-repair replay rules apply to each step.
- Entries created during cold preparation are delivered in captured tool results
  and referenced by preparationResultRefs. Do not retroactively claim they
  appeared in the bootstrap. The next validation input contains the finished brief.
- The existing ReviewRun evidence map owns full retained excerpts. References
  from this snapshot point to it; do not duplicate full PR/issue objects.
- sourceReads is implemented as a bounded append-only collection of typed
  successful observations. ContextRepositoryRead has id, reference (repository
  ID/name, path, commit), blobId, readAt, startLine, endLine, totalLines,
  utf8Bytes, truncated and optional evidenceRef. ContextDocumentRead has id,
  reference (registered source ID, resolved URL), the exact registration,
  notebookRevision observed during source selection, captureId, contentHash,
  readAt, page, totalPages, utf8Bytes, blank, truncated, refreshFailed and
  optional evidenceRef. JSON kind is REPOSITORY_FILE or DOCUMENT. The original
  document capture identity/time survives cache reuse or failed refresh.
  These metadata receipts neither assert applicability nor change the original
  packet. Equal receipt-ID retries are idempotent; altered receipts cannot
  replace them. The owning run validates retained references and exact excerpt
  byte counts on append and reload. A maximum of 128 receipts plus validated
  field lengths bounds metadata; no separate aggregate JSON-size guarantee is
  claimed. Preparation must later resolve these typed identities directly,
  rather than parsing descriptive evidence labels or trusting model-pasted IDs.
- applicability is MATCHED, VERSION_MISMATCH, or UNVERIFIED. A mismatch can be a
  useful pointer but cannot silently supply an applicable contract.
- inputFingerprints retain each selected scope's local baseline input inventory.
  Each inventory maps stable input keys to observed Git mode/type/object IDs,
  explicit ABSENT results, or configuration hashes. A canonical SHA-256 covers
  the scope ID followed by sorted input keys and values; every UTF-8 string has
  a 32-bit big-endian length prefix. Baseline SHA and notebook revision are
  provenance, not implicit digest inputs. Any incomplete essential observation
  produces gaps and an absent digest, never a reusable partial or empty result.
  Inventories are bounded to 128 inputs and 32 KiB per scope, and 256 KiB of
  input keys/values across at most 100 scopes. An inventory that exceeds the
  run budget is replaced by an explicit incomplete record. These identity
  records do not consume the model's note-text budget and do not establish
  note applicability, successful preparation, or remote document freshness.
  Evidence receipt IDs and repository-file citation commits do not change
  baseline input equality; the current selected object identities do. Retained
  document content hashes, versions and capture limitations remain input facts.
- inclusion is INCLUDED or DEFERRED. Deferred entries retain a short title,
  reason, and entry/version reference, not their complete body. A later read
  can use that exact current version or an already captured evidence copy.
  If neither survives, return VERSION_UNAVAILABLE; never substitute today's
  text. A fresh version requires an explicitly labeled supplement.
- Each delivery stores turnId, packetDigest, byte count, input/evidence reference,
  and outcome PREPARED, SUBMITTED, CONFIRMED, or UNCERTAIN. Use existing accepted
  input/provider receipt facts to derive these labels. A prelaunch snapshot
  is not proof that a provider accepted it; never resend uncertain input
  automatically. Avoid redundant persisted status if existing facts derive it.
- A discussion supplement stores its accepted turn/message identity, original
  context ID, captured thread/source references, and additions. It is append-only.
- Later notebook corrections do not rewrite old snapshots. The UI can compare
  captured entry versions with current disabled/corrected entries. No retroactive
  model rerun or publication is authorized.

### 4.5 Requests and generated reports

An explicit Build/Refresh request from the notebook settings page:

~~~json
{
  "requestKey": "build-request-a",
  "expectedRevision": 7,
  "expectedBuildSequence": 0,
  "scopeIds": ["project", "path:plugin/trino-iceberg/"],
  "provider": "workspace-selected-provider",
  "model": "workspace-selected-model"
}
~~~

The provider/model values above are placeholders for validated existing task
defaults. The route supplies workspace identity. A duplicate matching request
returns the same builder; a stale sequence returns a conflict. The response
contains taskId, requestKey, buildRequestSequence and notebookRevision plus
status derived from the existing task. It does not return a parallel build job.

A fixed source-read tool request for one documentation page:

~~~json
{
  "operation": "SOURCE",
  "sourceId": "docs-trino",
  "relativePage": "connector/iceberg.html",
  "cursor": null
}
~~~

Other closed read operations are SELECTION and RELATED. SELECTION uses the
bound run/task and optional known entry/version IDs; RELATED accepts bounded
terms, path IDs or explicit references already in that scope. A source response
contains sources (Section 4.2), nextCursor, gaps, and computed utf8Bytes.
No operation accepts caller-selected task/workspace/run ownership.

A generation submission with an explicit negative result:

~~~json
{
  "operation": "SUBMIT",
  "attemptId": "attempt-b",
  "operationKey": "submit-attempt-b",
  "report": {
    "scopeResults": [
      {
        "scopeId": "path:plugin/trino-iceberg/",
        "outcome": "EMPTY",
        "noteIds": [],
        "checkedSourceIds": ["source-readme"],
        "explanation": "The checked material did not establish an additional useful note."
      }
    ],
    "entries": []
  }
}
~~~

GeneratedContextReport consists of scopeResults and entries. A READY result
names that scope's canonical knowledge ID and supplies its proposed entry
fields from Section 4.1. The backend sets origin, version, fingerprint, byte
counts and observed source metadata. A report cannot fabricate source records:
checkedSourceIds and claim sources must resolve to this execution's captures.
Every admitted scope needs an outcome; FAILED is recorded with its reason and
preserves prior notes. EMPTY cannot be used to avoid reporting unreadable input.

The other prepare operation is BEGIN: requestKey, expectedRevision, scopeIds,
and optional newScopeProposals/newSourceProposals. Proposals name captured
repository-file evidence for their module boundary or explicit documentation
link. The backend validates them and builds ContextPreparationPlan from those
facts, including authoritative fingerprints. It never accepts a model-supplied
filesystem root or ownership identity. BEGIN returns admission OWNED, JOINED,
REUSED or DEFERRED, the attempt reference when applicable, current revision,
and source catalog. Repeating the same request joins/reuses its admitted input;
only new admissions compare the caller's revision. JOINED returns immediately
with “still preparing”; it does not wait inside the review call or grant write
ownership. A later request can observe the completed result.

The accepted submission receipt contains attemptId, operationKey, reportDigest,
notebookRevision, the saved scope results, and a bounded result packet. Report
digests and receipts are application-created. This distinguishes proposed text
from the exact content saved and supplied back to the agent.

## 5. Matching and related-history selection

### 5.1 Inputs and ordering

Use the run's frozen diff/required-review paths, both sides of renames, user
request, PR title/body, commit messages, and explicit references. An incremental
run uses its actual required review scope while retaining the parent PR identity.

First establish eligibility: an entry and its scope must be enabled, and a
suppressed entry stays excluded. An explicit entry selection can bypass a missing
scope match, but cannot override those exclusions. Otherwise, the entry's scope
must match the repository-wide rule, a changed path prefix, or a configured term.
Words in a lesson never admit it from an unrelated scope.

Order eligible entries by these ranks:

1. Explicitly selected entries, with their version limitations.
2. Repository-wide knowledge, plus repository-wide lessons with topic overlap.
3. Knowledge from path-matched scopes, plus their lessons with topic overlap.
4. Knowledge from term-only scopes, plus their lessons with topic overlap.
5. Lessons with a scope match but no additional topic overlap.

Within each rank, knowledge comes before lessons. Lessons then sort by the
number of distinct overlapping topic words, descending. Equal matches retain
saved order. Explicit entries do not need additional topic overlap. Missing
knowledge discovery continues to use matching scopes, independently of lesson
ranking.

Path prefixes use normalized repository-relative paths with component boundaries:
plugin/trino-iceberg/ does not match plugin/trino-iceberg-extra/. Reject absolute
paths, traversal, Git-option-like refs, and invalid source routing. Matching is
lexical; evidence reads separately validate safe paths and code identity.

Terms are case-insensitive whole words/phrases after whitespace normalization.
There is no stemming, fuzzy matching, learned score, or automatic synonym
expansion in V1. Users or generated scope proposals can list explicit aliases.
Scope terms and paths are OR conditions; no match is not a failed build.

For each naturally matched lesson, compare its title and summary with the PR
text and changed-file basenames, including both sides of renames. Normalize
Unicode to NFC, split camel-case and acronym boundaries, lowercase independently
of locale, and split punctuation and underscores into words. Ignore words shorter
than three Unicode code points, words with no letters, and a fixed list of common
language and development words. Also ignore vocabulary from that lesson's scope
title and path prefixes, so the shared component name alone cannot boost every
lesson. Configured alternative scope terms are not discarded merely because they
are matching rules: a scope may list distinct topics such as `snapshot isolation`
and `nested fields`, which must remain useful for distinguishing lessons.

Each shared word counts once. Repetition, lesson body text, claim text and evidence
excerpts do not increase the score. Basename overlap is a lexical hint, not proof
that the lesson's original source touches the reviewed file. Selection performs no
extra history lookup, model call, embedding search or source read. Match reasons
show the overlapping words, or disclose that a lesson is a lower-priority
scope-only candidate. Zero-overlap lessons remain eligible at the final rank;
absence of shared words does not prove irrelevance.

The existing packet budgets remain unchanged: selected knowledge and lessons
share 8 KiB, with complete entries deferred when they do not fit. The snapshot
retains selected versions, match reasons and inclusion state. Every selected note
remains `UNVERIFIED`; a higher lexical score does not establish correctness or
applicability.

For a newly encountered component, use the closest actual module/build boundary
or a component explicitly identified in the task. Do not treat every changed
directory as a separate knowledge topic. Record the validated scope once and
show its path/term rule. The project overview stays small and links to component
notes rather than accumulating every component's content.

Selection means relevant enough to inspect. Before a finding relies on a
contract, the agent must check its version and conditions against original
sources and the actual reviewed code.

### 5.2 History algorithm

1. Resolve directly linked PR/issue/task references through existing local stores.
2. Read at most 50 prior commits from local path-filtered Git history of the
   captured monitored baseline. Filter paths **before** limiting, unlike reading
   the latest 300 repository-wide commits and searching those.
3. Batch-join SHAs through RepositoryEnrichmentService. For at most ten selected
   unresolved association candidates, use the existing lookup and persist its
   result. Respect stored negative-check policy and rate limits.
4. Deduplicate by stable repository identity and PR number. Inspect cached final
   changed paths/diffs and relevant discussion; refresh only selected gaps.
5. Broaden to the selected component directory or explicit terms if exact matches
   provide little material. Use local PR title search, bounded issue title/body/
   label matching, existing review paths, and completed task summaries. The
   task-service query scopes workspace, physical repository, interactive
   ownership and completion before limiting rows. RecentService is not needed
   for these durable summaries; any later supplemental activity search must
   likewise scope before limiting.
6. Rank direct link, verified exact file overlap, same component, then term-only.
   Use recency within comparable rank, not as a substitute for relevance.
7. Initially include short excerpts from up to three discovered PRs, three
   issues, and three previous task/review records. Direct references receive
   priority within the byte budget; additional references remain readable.
   These are preview limits, not review coverage claims.

Every result carries why it matched and whether the change is present in the
reviewed base, newer on the baseline, or unknown. Check actual Git ancestry when
available. A newer merged solution is not evidence that the PR already has it.
Here reviewed base means the frozen mergeBaseSha used for the publication diff,
not a newer target-branch tip. An associated commit's ancestry establishes only
that commit's inclusion; it does not establish inclusion of every PR change or
that the reviewed head has preserved the change without reverting it.
Preserve query bounds, missing mappings, unsupported/renamed-path limits, and
unavailable/truncated discussions. Relevance is approximate, not exhaustive.

For “how it finally got merged,” retain the final merged diff and the actual
review requests, follow-up changes, and recorded decisions available locally.
Explain the observed sequence with references. Merge status or a resolved
thread alone does not prove the reason, correctness, or a universal design rule.

#### Completed task summaries in local selection

Reuse `AgentTaskService.listCompleted(workspaceId, repository, 10)`. The physical
repository is the workspace's selected repository, including a fork; PR/issue
and review history continue to use the monitored upstream. SQLite applies the
exact workspace, case-insensitive repository owner/name, interactive ownership,
COMPLETED state and non-null summary predicates before the limit. It orders by
updated time descending, then task ID ascending. Archived tasks and retained
summaries after runtime cleanup remain eligible. No transcript, event stream,
provider session, Git checkout or remote read is needed.

When a selected scope has configured terms, inspect those ten summaries using
whole-term matching against the title, final response and completion notes.
Search at most 256 KiB of UTF-8 text per task, and report truncation. Tasks have
no recorded changed-path list: a filename in prose does not prove overlap.
The optional recorded worktree head is provenance only; applicability and
ancestry remain unknown. An empty conclusion is disclosed and omitted.

An explicit `task:<UUID>` token in the bounded PR/commit text uses
`getInteractive(workspaceId, taskId)` and can reach an older completed task
outside the recent window. Read at most ten distinct IDs. Bare UUIDs are not
task references. Missing, internal, open or foreign-repository tasks produce a
gap; they do not trigger hydration. The current review's task is excluded.
Review-owned tasks use the bounded review-run history instead, preventing the
same work from appearing again as a generic task summary.

Combine matching task and review candidates before the existing TopKSelector:
direct task reference first, exact review-path overlap next, then term-only
matches. Break ties by update time descending and stable source ID ascending.
Retain at most three combined task/review excerpts, each at most 1,536 UTF-8
bytes, within the existing history and total-packet budgets. Each task excerpt
records its ID and durable version, workspace, selected repository, state, update time, optional
recorded head, match reason and unverified-agent-conclusion label. The frozen
run retains the selected excerpt even if later task cleanup removes runtime
files. This does not promote the conclusion into notebook knowledge or memory.

Coverage gaps disclose the ten-task window, combined three-item cap, and omitted
older unreferenced tasks. Open tasks' per-turn learning reports remain outside
this increment. At most ten directly referenced and ten recent task rows are
read, but each summary JSON is decoded in full before matching/excerpt limits.
The query can scan more rows than it returns. Read-only selection and bounded
row counts do not establish production latency or peak memory; those remain
acceptance measurements.

## 6. Preparation, updates, concurrency, and recovery

### 6.1 One coordinated preparation per workspace

Use one notebook-mutation lock keyed by workspace for short local mutation:

1. Read current content. Resolve identical admitted request/normalized-plan
   replay first; compare expected revision only for a new admission. The first
   admission's own scope/root additions must not break its identical retry.
2. Validate any pre-admission scope/root additions from captured local evidence,
   save them, then capture their resulting content revision, stable attempt ID
   and owning accepted task/turn key. Save activePreparation in the same mutation.
   Same request/inputs returns its existing attempt.
3. Release the lock/connection before Git inspection, network reads, or model work.
4. Validate a generated batch outside the transaction.
5. Reacquire the lock, require the same attempt and unchanged content revision,
   validate again against current ownership, and atomically save entries,
   outcome/source receipts, and any cleared active marker.

Only one shared generation owner is admitted per workspace. Another consumer
can immediately reuse already valid notes. For the same pending scope/input it
may join by receiving “still preparing” immediately and retry later or continue
with an explicit gap. No waiting loop runs inside the review call.
Different missing scopes are deferred to that review's next requested read or
an explicit refresh; do not create a second background queue. No database or
workspace/task lock is held while waiting. Display “Waiting for shared knowledge”
with the owning task link.

This lock is confined to notebook preparation/mutation. Existing hydrators can
have their own synchronization: WorkspaceIssueRefreshService currently holds
WorkspaceOperationLocks during a remote refresh. Invoke those services outside
the notebook lock and every context-owned transaction, and measure their wait.
This design does not claim all existing application locks are already short.

The fixed report is atomic and fenced. A user edit, disable, removal, source
configuration change, or workspace deletion invalidates a stale generated batch.
Return the current revision/conflict; never three-way merge model text into user
edits. One bounded re-read may let the caller reuse the newer saved content;
do not automatically repeat expensive generation.

Repeated review-owned tool operations use the existing run operation journal.
Commit that final journal receipt/run update and the notebook update in **one
short local SQLite transaction**, inside the workspace mutation lock. Existing
settings/review store transactions join this application transaction; no new
persistence engine or distributed transaction is needed. Standalone builds
store their operation key, normalized payload digest, and actual result with
lastBuildRequest in the same notebook write. A key with different input conflicts.
An older standalone attempt whose receipt has been replaced returns a stale
request error and cannot write. There is no new unbounded replay ledger.

Task cancellation, active-turn identity, and publication permissions remain
independent of notebook locking. Stop revokes reporting authority. Existing
quiescence/ownership checks still control task/worktree cleanup.

### 6.2 Failure/restart

- Failed or cancelled preparation keeps previous notes and the review's supplied
  packet. Store FAILED for attempted scopes, with the concrete reason.
- If the process stops before reporting, derive the interrupted attempt from its
  existing task/turn and fence further writes. Startup reconciliation clears it
  only after existing runtime classification permits this; record FAILED and
  require a new user request. Never silently restart model research.
- A failure after notebook commit but before tool response replays the recorded
  result. Notebook mutation and its receipt must commit together.
- Source reads captured earlier in the review remain valid if a later shared
  notebook save fails. Roll back the final shared-save operation and its success
  receipt together. The review can continue with those sources and an explicit
  “not saved for reuse” gap; an authorized identical retry may finish the save.
  Never journal shared-save success before the notebook commit.
- A successful note report is useful content, not proof that the entire provider
  turn or runtime cleanup completed. Use existing completion facts for those.

### 6.3 Freshness and user ownership

Compare only selected source inventories/blob identities/dependency versions.
Unchanged inputs reuse READY when its note still exists, or EMPTY for the same
successfully checked inventory. A relevant change permits preparation
on the next requested run; sync and HEAD movement alone do nothing.

Local inventory reads use the captured monitored commit, independently of
working-tree edits or later HEAD movement. Project input discovery covers
bounded root documentation/build entries rather than hashing the entire
repository tree. Component inputs use their selected subtree identities and
referenced repository files, with ancestor dependency manifests. Explicit
absence and the documentation-source catalog participate in the fingerprint,
so newly added relevant input invalidates an earlier negative preparation.
Sources from saved MEMORY lessons do not participate in baseline knowledge
preparation fingerprints; saving a lesson must not force knowledge regeneration.
They retain their own source references and applicability limits in the review.
Manifest blob equality establishes unchanged dependency declarations; it does
not resolve effective versions, remote parents, profiles, lockless ranges or
environment-dependent build inputs. Those remain explicit applicability limits.
Comparing baseline inventories must never promote a claim to MATCHED for a PR.

Refresh generated baseline knowledge from the captured monitored branch, not
from an older task or an unmerged experiment. Version-specific differences
required by a PR are retained in its context. Documentation refresh occurs on
explicit Build/Refresh or when the applicable version is missing. Unknown
remote freshness is displayed as last observed, not guessed.

MEMORY is written only through the user's Save lesson/Edit action. No merged
PR, passing test, resolved finding, or agent confidence score promotes itself.
A saved lesson remains conditional and subject to applicability checks.

## 7. Delivery, budgets, and observable use

### 7.1 Required packet plus on-demand evidence

For an enabled run, ByteQuay selects and supplies the compact bootstrap directly
in task input. Disabled runs omit these Repository Context additions.
Only small trusted policy instructions go through managedInstructions; source
text is clearly delimited reference data and grants no authority. Do not inject
the whole notebook into Claude's process arguments.

At every actual provider turn start, resolve the accepted assignment and include
the correct immutable packet/brief or old-run discussion supplement. Use the
existing input delivery and replay boundary, including queued/resumed turns.
Keep a digest and reference to one saved body; avoid duplicating it in logs.

Bootstrap selection uses the persisted run/task identity and does not require
a turn ID that has not been allocated yet. At the accepted-turn launch boundary,
compose the reference packet into that turn's actual input before provider
submission and record its binding. This is a narrow launch-path addition;
managedInstructions alone is insufficient for the reference-body transport.
Queued discussion supplements are captured here, rather than when enqueued.

Fixed tools return additional source excerpts and captured preparation results.
Notebook IDs are not evidence IDs until the backend binds their actual content
to the current snapshot. The report gate requires valid context references and
explicit essential gaps; it does not infer tool decisions from assistant prose.

| Budget | Initial constant |
| --- | ---: |
| Selected knowledge + lessons in initial packet | 8 KiB |
| Task brief + historical excerpts in initial packet | 16 KiB |
| Total additional initial packet, including metadata/index | 32 KiB |
| One context source response | 16 KiB, paginated |
| One notebook entry body | 8 KiB |
| Entries per workspace | 100 |
| Sources per entry / claims per entry | 8 / 12 |
| Aggregate current notebook prose | 256 KiB |
| Serialized notebook, including receipts/source excerpts | 1 MiB |

KiB means 1,024 UTF-8 bytes. Count actual encoded text, not Java characters.
Keep code-point boundaries. Include source labels and pagination metadata within
response caps. These are starting safety/UX budgets, not measured optimal
model budgets. The notebook-size cap may be reached before entry-count limits.
Reject oversized user saves visibly; do not evict their notes to fit.

Prioritize required scope, direct references, concise applicable summaries,
then lower-ranked excerpts. Defer whole optional pieces with readable IDs and
reasons rather than silently cutting a contract. A packet must not omit essential
review identity or erase a limitation to satisfy a prose target.

Existing prompts, skills, diffs, tools and output also consume model context.
Check total transport/argument sizes: the current 100,000-byte skill limit
does not bound the final composed instructions. Measure provider token usage
where reported; never pretend UTF-8 bytes are exact tokens. Keep room for code,
tests and responses, and test the largest accepted composition in both lanes.

Additional evidence is available on demand with per-response limits and cumulative
usage measurement. There is no arbitrary total source-reading cap that silently
turns incomplete review into complete. Cancellation, rate limits, unavailable
sources, and the existing evidence-loop budget remain explicit.

### 7.2 What the UI can truthfully say

| Label | Evidence |
| --- | --- |
| Prepared | Packet persisted before provider submission |
| Provided to agent | Existing provider acceptance confirms submission of this packet |
| Delivery uncertain | Submission/acceptance could not be established |
| Retrieved by agent | Successful context-tool read returned these bytes/source IDs |
| Agent-reported source | Native research was reported but not independently captured |
| Cited in review | A saved finding or validation conclusion references this evidence |
| Refreshed / reused / empty / failed | Actual saved preparation receipt |

Never label a read as “understood,” “verified correct,” or a proven improvement
in quality. Do not use an acknowledgment turn or an echoed packet ID as proof
of comprehension. Required packet delivery failure fails that turn's preparation;
optional documentation failure permits qualified work. Essential missing scope
must prevent complete coverage, through existing reporting rules.

### 7.3 Friendly presentation

The first implemented UI is **Workspace settings → Repository context**. It
contains the default-on Use repository context in reviews switch, Knowledge and
Saved lessons, matching scopes, and registered documentation sources. The switch
applies to new runs; turning it off preserves notes and historical run snapshots.
Opening the page only reads the local notebook. Source registration saves a
public HTTPS root or page without fetching it. Build/Refresh and preparation
outcomes remain pending the generation workflow.

Users can add one knowledge note per scope, edit existing knowledge or lessons,
disable entries or scopes, and remove notes. Removing knowledge records its
suppression; **Reset removal** allows a new note for that scope without restoring
deleted text. Scopes can be disabled rather than removed. Existing source links
and claim classifications survive text edits; saving generated text makes that
note user-managed. Text fields display UTF-8 byte limits.

All writes use the displayed notebook revision and wait for the backend before
updating the page. A failed write retains the open draft and requires an explicit
reload before retry. Reload preserves draft text and lets the user compare the
latest saved note. A changed source/claim structure requires reopening the latest
entry; a reloaded scope retains its current enabled state. Old workspace responses
cannot replace a newly opened workspace's notebook.

**Save lesson** on a retained private review message opens the shared editor with
the original message text. The user writes its title and summary, edits its
conditions, and can select additional retained evidence before saving. New
lessons start with no scope selected and show **Choose where this lesson applies**.
The user must choose a scope before saving. Project remains an explicit option;
choosing a repository-wide scope explains that it applies across the repository
and suggests a component scope for a more specific note.
The backend binds the source to that message's original review/run, including
older discussions. This is a local notebook action and creates no provider turn
or GitHub comment. Failed saves keep the draft; after an uncertain response, a
reload recognizes an identical accepted lesson instead of duplicating it. Newer
draft edits remain visible with a notice that another save creates another lesson.

The implemented Review details disclosure renders the immutable run snapshot:
**Prepared**, **Off for this run**, or **Not recorded**, captured revision, exact
packet and UTF-8 byte count, selected note versions and match reasons, related
history, input fingerprints, source receipts/excerpts and gaps. Missing historical
context is not interpreted as Off. It does not label prepared text as delivered,
read, understood or responsible for a better conclusion. Automatic delivery and
citation navigation require their later integration gates.

Extend the existing Review details disclosure, not the top-level report card
types. The completed delivery workflow should support this example display,
with illustrative counts:

~~~text
Repository context provided · 18 KiB

Project overview       Reused
Iceberg connector      Refreshed — dependency version changed
Saved lessons          2 included
Related history        3 PRs · 2 issues

Why selected · View exact text · Sources and versions
4 sources retrieved · 2 cited in findings
1 documentation source unavailable
~~~

The transcript folds preparation into ordinary activity with concise messages:
“Reusing project overview,” “Preparing Iceberg knowledge,” “Waiting for shared
knowledge,” and “Context ready.” Show elapsed time, additional bytes/token
metrics when available, source/cache counts, and a link to the builder task.
Use existing events and task status; no Reading/Thinking lifecycle states.

At completion, let users navigate from a cited source to the finding or rejected
hypothesis. Citation count is not a claim that context caused a better review.
Preserve details across replay/reconnect and runtime cleanup.

## 8. Prevent incorrect shared knowledge from becoming authority

1. Preserve original excerpts and classify decisive statements as documented,
   decisions, inferences, questions, or user lessons.
2. Before reporting a contract violation, inspect the applicable original source
   and show the behavior in the frozen PR code. Current code describes behavior;
   specifications may describe intended behavior. Investigate conflicts rather
   than declaring either source universally authoritative.
3. Use existing validation to challenge notebook-derived hypotheses with
   counterevidence and focused executable checks where useful. A notebook-only
   citation cannot establish a defect.
4. Keep shared baseline knowledge separate from unmerged PR observations.
5. Preserve user edits and suppression. A correction stops future use of the
   bad entry; old snapshots remain auditable and show that their entry changed.
6. Treat all PR/issue/repository/document text as untrusted reference data.
   It cannot request tool installation, change permissions, publish, or direct
   unrelated filesystem/network actions.

Both providers intentionally resume task conversation. Claude currently sets
autoMemoryEnabled=false. Codex isolates its app-owned home but the current
profile has no explicit native-memory disable/verification. Before enabling a
lane, verify supported behavior and either disable autonomous cross-task memory
or document/test the limitation without claiming isolation. Model pretraining
cannot be disabled. Current snapshot identity and source revalidation are
required even when a provider remembers other material.

Review remains no-child. This design makes no guarantee of independent context
delivery to future development subagents.

## 9. Storage, transactions, and cleanup

Use AppSettingsStore under the key **workspace.repositoryContext.<workspaceId>**.
A concrete application service owns JSON validation, revision checks and
workspace locking. AppSettingsStore itself is get/put/delete; it is not a
database compare-and-swap API.

Add a context JSON field to the existing ReviewRun persistence and reference its
existing evidence map. No separate context table/database or duplicate PR store
is required. New records/ports belong upstream; SQLite mapping stays in
persistence-sqlite. Hibernate owns the current schema.

ByteQuay configures WAL and a one-connection pool. Both reads and writes currently
serialize through that pool. Agents can execute concurrently while brief saves
serialize. Keep model/network/Git work outside every transaction and release
connections after local reads. A separate table does not remove
[SQLite's single-writer property](https://www.sqlite.org/wal.html#concurrency);
separate storage is deferred until measured contention
survives bounded writes and transaction fixes.

| Data | Lifetime |
| --- | --- |
| Current generated notes | Until refresh/replacement, explicit removal, or workspace deletion; no age TTL |
| User notes/lessons | Until explicit removal or workspace deletion |
| Old full notebook revisions | No separate archive; runs retain the text they actually used |
| Last per-scope result and suppression | Until replaced/reset or owning workspace deletion |
| Active preparation | Fenced through existing task/turn completion/cancellation/recovery; never an orphan background job |
| Review snapshot and cited excerpts | Follow owning review records, independently of detailed runtime logs |
| Temporary page extraction/downloads | Discard after bounded capture; remaining task-owned temporary files follow runtime cleanup |
| Task runtime logs | Existing seven-day policy after terminal task state |
| Existing PR/issue caches | Existing feature retention; necessary review/lesson excerpts survive cache eviction |

Staleness triggers inspection, not deletion. Unwatch/Retired preserves the
notebook. End review and remote PR merge/closure do not erase local reports.
Workspace deletion explicitly deletes its notebook key after existing stop/
ownership checks and removes owned snapshots through current deletion paths;
do not delete shared repository data independently referenced elsewhere.

User saves and retained snapshots have byte limits; no background “least used
memory” eviction in V1. Preserve pending drafts/local work under existing rules.

## 10. Java interfaces and method contracts

These are proposed API contracts, not implemented classes. Public values belong
in **com.bytequay.core.context**. Application orchestration/HTTP/tool adapters
belong in **com.bytequay.app.context**, with implementations in impl when split.
Provider-neutral fixed descriptors stay in agent-spi; the adapters must not
depend on core context or persistence types. No new module is introduced.

Use records, Optional, JDK collection interfaces, and Guava immutable defensive
copies. The snippets omit repetitive imports/constructors; every method below
must obey the validation, byte bounds and trust rules in this document.
“Interfaces” here specify real controller/review/task or storage boundaries;
do not create a generic context provider/plugin registry.

### 10.1 Main values

~~~java
/** Current workspace-owned content and bounded preparation receipts. */
public record RepositoryNotebook(
        int schemaVersion,
        long revision,
        boolean useInReviews,
        long buildRequestSequence,
        List<RegisteredContextSource> sources,
        List<ContextScope> scopes,
        List<ContextEntry> entries,
        List<String> suppressedKnowledgeIds,
        List<ContextSourceEvidence> sourceEvidence,
        Optional<ActiveContextPreparation> activePreparation,
        Optional<ContextBuildRequest> lastBuildRequest) {}

/**
 * One reusable note. Scope is explicit and source IDs resolve within the
 * notebook. USER content and disabled/suppressed IDs are never regenerated.
 */
public record ContextEntry(
        String id,
        ContextEntryKind kind,
        String scopeId,
        String title,
        String summary,
        String body,
        List<ContextClaim> claims,
        List<String> sourceIds,
        ContextEntryOrigin origin,
        boolean enabled,
        long version) {}

/**
 * Last completed extraction for a scope and captured inputs.
 * EMPTY requires successful reads and an explanation. FAILED may coexist
 * with previous usable entries and must never erase them.
 */
public record ContextPreparationResult(
        String attemptId,
        ContextInputFingerprint inputFingerprint,
        ContextPreparationResult.Outcome outcome,
        List<String> noteIds,
        List<ContextSourceRead> checkedReads,
        String explanation) {}

/**
 * Trusted identity of an actual accepted execution, supplied by the
 * application. Never deserialize this value from model or renderer input.
 * A discussion's run is its source run, not necessarily the newest run.
 */
public record ContextExecution(
        String workspaceId,
        String taskId,
        String turnId,
        String turnKey,
        Optional<String> reviewRunId,
        ContextExecutionPurpose purpose) {}

/**
 * Immutable local input capture. Baseline and reviewed code are distinct.
 * Inputs carry provenance and bounded source inventories, not a live
 * database session, provider callback, or unchecked filesystem root.
 */
public record ContextSelectionRequest(
        ContextSubject subject,
        ContextCodeIdentity code,
        List<String> changedPaths,
        String taskText,
        List<ContextReference> explicitReferences) {}

/**
 * Server-owned task/run identity available before a turn is accepted.
 * This permits bootstrap creation without inventing an accepted turn ID.
 */
public record ContextSubject(
        String workspaceId,
        String taskId,
        Optional<String> reviewRunId,
        ContextExecutionPurpose purpose) {}

/**
 * Selects an authorized source. relativePage is permitted only for a
 * registered documentation root and cannot escape its normalized prefix.
 * A pagination cursor is bound to this same resolved source and version.
 */
public record ContextSourceRequest(
        String sourceId,
        Optional<String> relativePage,
        Optional<String> cursor) {}

/**
 * Packet body actually supplied to a turn, plus its backend-computed digest
 * and UTF-8 size. sourceIds identify retained evidence or deferred reads.
 */
public record ContextPacket(
        String snapshotId,
        String text,
        String digest,
        int utf8Bytes,
        List<String> sourceIds,
        List<ContextGap> gaps) {}

/** A bounded read. Empty data, unavailable data, and truncation are distinct. */
public record ContextSourcePage(
        List<ContextSourceEvidence> sources,
        Optional<String> nextCursor,
        List<ContextGap> gaps,
        int utf8Bytes) {}
~~~

Other values have the fields in Section 4: ContextScope/match, tagged
ContextReference, ContextSourceEvidence, ContextClaim, ContextCodeIdentity,
ActiveContextPreparation, ContextBuildRequest, ReviewContextSnapshot and
ContextDiscussionSupplement. Use sealed reference/match interfaces for their
closed variants rather than nullable bags of mutually exclusive fields.

Commands below are typed records: EditContextEntry contains only user-editable
fields and expected entry ID; SaveContextLesson contains the selected local
discussion/evidence IDs, edited lesson text and explicit scope; StartContextBuild
contains workspace ID, expectedRevision, expectedBuildSequence, request key,
selected scope IDs and selected provider/model defaults. It accepts no filesystem
path, caller-supplied task ownership,
secret, or arbitrary target repository.

### 10.2 Workspace notebook service

~~~java
public interface RepositoryContextService
{
    /**
     * Reads the current notebook from local settings.
     *
     * @param workspaceId existing retained workspace identity
     * @return saved notebook, or an empty revision-zero notebook with
     *         useInReviews=true when absent
     * @throws ContextNotFoundException if the workspace does not exist
     * @throws ContextStorageException if saved JSON is corrupt or unreadable
     *
     * This method performs no Git fetch, provider request, source hydration,
     * preparation, or write. Corrupt data is never replaced with empty data.
     */
    RepositoryNotebook getNotebook(String workspaceId);

    /**
     * Saves the review-use preference, documentation sources and scope configuration.
     *
     * @param workspaceId owning workspace
     * @param expectedRevision revision displayed by the settings editor
     * @param edit optional useInReviews change and typed source/scope mutations
     * @return updated notebook with incremented content revision
     * @throws ContextConflictException for a stale revision
     * @throws ContextInvalidRequestException for unsafe roots, invalid paths,
     *         identity changes, or dangling current entry/source references
     *
     * No fetch or generation occurs. Existing scope IDs stay stable; disable
     * a populated scope rather than deleting its identity. Retain cited source
     * evidence when removing a configured root. This endpoint is user-owned;
     * begin permits only narrowly validated new repository-derived proposals.
     * A preference change affects new runs, preserves notes/history, and
     * neither starts nor cancels an agent. Models cannot change the preference.
     */
    RepositoryNotebook configure(String workspaceId, long expectedRevision, EditContextConfiguration edit);

    /**
     * Clears removal suppression for selected knowledge scopes.
     *
     * @param workspaceId owning workspace
     * @param expectedRevision revision accepted by the user
     * @param scopeIds stable scopes explicitly selected in Reset
     * @return updated notebook, without starting preparation
     * @throws ContextConflictException for a stale revision
     *
     * Does not overwrite USER notes, re-enable disabled content, or run a
     * model. A subsequent requested preparation may recreate a missing note.
     * Clear that missing note's completed preparation when clearing suppression.
     * Ordinary Build/Refresh does not call this method implicitly.
     */
    RepositoryNotebook resetSuppression(String workspaceId, long expectedRevision, List<String> scopeIds);

    /**
     * Adds/edits one user-selected entry under the workspace mutation lock.
     *
     * @param workspaceId owning workspace
     * @param expectedRevision content revision displayed by the editor
     * @param edit explicit fields to change; never a whole-notebook replacement
     * @return notebook with incremented content and changed-entry revisions
     * @throws ContextConflictException if revision or entry ownership changed
     * @throws ContextInvalidRequestException for invalid scope/sources/size
     *
     * User editing converts generated content to USER. Validate the entire
     * mutation before committing; never hold this lock during external work.
     */
    RepositoryNotebook editEntry(String workspaceId, long expectedRevision, EditContextEntry edit);

    /**
     * Enables/disables one entry without changing its provenance.
     *
     * @param workspaceId owning workspace
     * @param expectedRevision optimistic content revision
     * @param entryId existing stable entry identity
     * @param enabled whether new selections may include this entry
     * @return updated notebook; existing review snapshots remain unchanged
     * @throws ContextConflictException for stale revision
     *
     * Disabling must also prevent generation from recreating the same
     * content under a fresh ID. This never starts an agent or edits a run.
     */
    RepositoryNotebook setEntryEnabled(String workspaceId, long expectedRevision, String entryId, boolean enabled);

    /**
     * Removes a user-selected entry and preserves generated-ID suppression.
     *
     * @param workspaceId owning workspace
     * @param expectedRevision optimistic content revision
     * @param entryId entry to remove
     * @return updated notebook with unused local evidence pruned
     * @throws ContextConflictException for stale revision
     *
     * Retained review snapshots and evidence are not deleted. A generated
     * knowledge ID's suppression survives until an explicit reset, even if
     * editing previously converted its origin to USER.
     */
    RepositoryNotebook removeEntry(String workspaceId, long expectedRevision, String entryId);

    /**
     * Saves a lesson explicitly accepted through the user-facing editor.
     *
     * @param workspaceId owning workspace
     * @param expectedRevision optimistic content revision
     * @param lesson edited lesson, explicit scope and selected local sources
     * @return notebook containing a USER-owned MEMORY entry
     * @throws ContextInvalidRequestException if sources cross unauthorized
     *         workspace boundaries or required evidence cannot be retained
     *
     * Capture the supporting excerpt before its runtime/cache can expire.
     * This method is not exposed to an autonomous model-write tool.
     */
    RepositoryNotebook saveLesson(String workspaceId, long expectedRevision, SaveContextLesson lesson);

    /**
     * Starts or returns an explicit Build/Refresh using the existing agent
     * task service, after readiness and workspace ownership checks.
     *
     * @param request accepted scope/provider, content revision and build sequence
     * @return existing/new builder task identity and current notebook revision
     * @throws ContextConflictException for conflicting in-flight work or
     *         reuse of a request key with different accepted inputs
     *
     * Establish the binding with preallocated task ID before provisioning. Provision
     * a separate managed worktree and apply the policy-only review bridge.
     * Replay the matching last request; reject an older sequence. Never allocate
     * a second task ID for a retained request or resend uncertain input.
     */
    ContextBuildView startBuild(StartContextBuild request);

    /**
     * Deletes notebook content as part of verified explicit workspace deletion.
     *
     * @param workspaceId workspace whose task/runtime ownership is already cleared
     *
     * This local idempotent delete is not invoked by Unwatch, End review,
     * PR merge, or routine task runtime retention.
     */
    void deleteWorkspaceData(String workspaceId);
}
~~~

### 10.3 Selection and fixed source reads

~~~java
public interface RepositoryContextReader
{
    /**
     * Selects notebook entries and bounded historical candidates locally.
     *
     * @param request server-built accepted task/run input and frozen identities
     * @return ordered selection with reasons, source pointers and explicit gaps
     * @throws ContextInvalidRequestException for invalid code/reference identity
     *
     * No model call or network request is permitted. File history may use
     * bounded local Git. Selected misses are returned for explicit hydration,
     * never interpreted as proof that no relevant history exists.
     */
    ContextSelection select(ContextSelectionRequest request);

    /**
     * Reads one authorized source or its next bounded page.
     *
     * @param execution actual active accepted turn, resolved by the backend
     * @param request registered source, optional relative page, and bound cursor
     * @return evidence page, unavailable reasons, and next cursor when present
     * @throws ContextConflictException if the turn was cancelled/replaced
     * @throws ContextInvalidRequestException for unknown/cross-run source IDs
     *
     * Use cached application data first. Hydrate selected misses/stale facts
     * through existing services. Registered documentation reads obey Section 3.
     * The backend resolves root-relative pages and creates their evidence IDs.
     * A cursor cannot change the source or bypass byte/destination checks.
     * Each returned excerpt is registered to this execution before delivery.
     * No model text supplies credentials, root paths, or arbitrary URLs.
     */
    ContextSourcePage readSource(ContextExecution execution, ContextSourceRequest request);

    /**
     * Discovers bounded related candidates using the same existing-data-first
     * algorithm as initial selection.
     *
     * @param execution actual active turn and its allowed repository/run scope
     * @param query bounded explicit terms or source/path IDs already in scope
     * @return ranked references with match reasons and search coverage limits
     *
     * Prefer verified Git path history plus persisted commit associations.
     * This is a fixed capability, not arbitrary SQL, shell, or GitHub search.
     * Candidate metadata alone is not a retrieved full discussion.
     */
    ContextCandidates findRelated(ContextExecution execution, ContextRelatedQuery query);
}
~~~

The application also captures baseline input identities before the initial
review turn; this is a local collaborator, not an agent-callable tool:

~~~java
/**
 * Captures bounded input identities for the selected notebook scopes.
 *
 * @param workspace application-resolved checkout and monitored repository routing
 * @param baselineSha captured local monitored commit, absent when unavailable
 * @param notebook immutable notebook read used for this selection
 * @param scopes enabled scopes selected from that notebook
 * @return one inventory per selected scope; incomplete reads have gaps and no digest
 *
 * Read immutable Git objects only. Reuse batched path reads, retain explicit
 * absence, and bound commands, output, and inventory storage. Never follow
 * working-tree symlinks, resolve remote dependencies, or fetch documentation.
 * A valid digest proves equality of the recorded inventory, not correctness
 * of a note or compatibility with the PR. No notebook lock or transaction is
 * held while inspecting Git. Off and already-captured runs bypass this read.
 */
List<ContextInputFingerprint> capture(
        WorkspaceRepositoryContext workspace,
        Optional<String> baselineSha,
        RepositoryNotebook notebook,
        List<ContextScope> scopes);
~~~

### 10.4 Generated preparation and review integration

The implemented review-owned slice uses the concrete application service below.
It operates only on existing configured scopes and registered sources. Scope/root
proposals, standalone Build/Refresh and automatic delivery remain pending. The
broader planned interfaces that follow still describe the remaining builder work.

~~~java
public class RepositoryContextPreparation
{
    /**
     * Reads the selected scopes and their frozen-baseline inputs outside locks.
     * @param execution trusted accepted investigation, resolved by ReviewTools
     * @param scopeIds existing configured IDs, never inferred filesystem roots
     * @return notebook revision and bounded authoritative input inventories
     */
    Plan plan(Execution execution, List<String> scopeIds);

    /**
     * Records one owner or returns immediate JOINED/REUSED/DEFERRED.
     * @param requestKey stable caller key, scoped to BEGIN in the run journal
     * @param requestDigest application-computed normalized payload digest
     * @param expectedRevision revision observed in the current scope catalog
     * @param plan observations obtained before opening the transaction
     * @return owner identity, reused outcomes, gaps and bounded actual note text
     * The caller rechecks the accepted turn under the review lock and commits
     * this mutation and its exact journal result within AgentUnitOfWork.
     */
    Admission begin(Execution execution, String requestKey, String requestDigest,
            long expectedRevision, Plan plan);

    /**
     * Validates all admitted scope outcomes and source IDs, and computes input
     * identities against the prospective retained sources outside locks.
     * @param attemptId current owner attempt; every reported read belongs to its run
     * @param report generated entries plus READY/EMPTY/FAILED for each owned scope
     * @return exact expected/replacement notebooks and actual accepted-text receipt
     * No shared write occurs here. Only baseline/document typed receipts qualify.
     */
    Completion prepareCompletion(Execution execution, String attemptId, GeneratedReport report);

    /**
     * Rechecks ownership and the exact expected notebook before saving.
     * @param completion validated proposal from prepareCompletion
     * @return saved result with packet bytes and digest, bounded to 32 KiB text
     * The caller saves the SUBMIT operation receipt in the same transaction.
     * Any user content revision change rejects the entire proposed batch.
     */
    Receipt complete(Execution execution, Completion completion);

    /**
     * Clears only the exact settled owner and records FAILED, preserving notes.
     * @param expected retained ownership fact resolved by application cleanup
     * @param reason bounded explanation; no provider restart is initiated
     * Cleanup requires the recorded turn to be terminal and the task's runtime
     * mutation/recovery claim to be absent. Uncertain runtime ownership stays fenced.
     */
    void fail(String workspaceId, ActiveContextPreparation expected, String reason);
}
~~~

`review_get_context(kind=assignment)` exposes a paged current scope catalog,
notebook revision, frozen baseline and retained read IDs to the enabled initial
investigation. `repository_context_prepare` has exactly BEGIN and SUBMIT:

- BEGIN accepts requestKey, expectedRevision and existing scopeIds. It returns
  status, notebookRevision, optional attempt, reused outcomes, gaps and packet.
- SUBMIT accepts attemptId, operationKey and report. Report fields are scopeResults
  and entries. Entries contain id, scopeId, title, summary, body, claims and sourceIds;
  server-owned origin/enabled/version and ownership fields are rejected.
- checkedSourceIds in a report and note/claim sourceIds name returned **typed read
  IDs**, not review evidence aliases. The backend copies nonblank evidence using
  those identities and retains successful blank receipts for negative reuse.
- Submission returns attemptId, notebookRevision, saved scopeResults and packet.
  Responses project compact scope/attempt metadata, not full stored inventories
  or checked-read objects. The packet defers complete scope sections that do not
  fit 32 KiB and explicitly reports omissions. The exact serialized tool response
  is capped at 64 KiB before the journal write; excess rolls back the shared
  mutation and returns a bounded error. Request digest and operation keys remain in the existing run journal; BEGIN and
  SUBMIT have separate namespaces. The journal stores the exact serialized result.
- JOINED/DEFERRED are transient and not journaled; a retry can see a settled result.
  Accepted ownership/reuse and submission receipts replay before new revision checks.
  Validation, evidence collection, discussion, children and context-off runs have no
  preparation capability. Direct automatic bootstrap/report delivery is still pending.

Settings displays last completed READY/EMPTY/FAILED distinctly from no completed
result, plus retained owner details when present. This is extraction metadata,
not a claim of current applicability, live execution or model comprehension.


~~~java
public interface RepositoryContextPreparation
{
    /**
     * Admits one workspace-scoped generation owner or joins a matching attempt.
     *
     * @param execution trusted review-investigation or explicit builder turn
     * @param plan captured scopes/sources/fingerprints/revision and optional
     *         repository-derived additions validated before admission
     * @return owner/join/reuse result and stable attempt identity
     * @throws ContextConflictException when the captured revision is stale
     *
     * Persist only short ownership/input facts under the workspace lock.
     * Never call a model or hold a connection while waiting for another task.
     * Commit permitted new scopes/roots before capturing the admitted revision
     * in the same local mutation. Do not register more roots mid-attempt.
     * Disabled scopes and user-owned entries cannot be replaced autonomously.
     */
    ContextPreparationAdmission begin(ContextExecution execution, ContextPreparationPlan plan);

    /**
     * Accepts a completed generated batch from the admitted root execution.
     *
     * @param execution current accepted owner turn
     * @param attemptId active fenced attempt returned by begin
     * @param operationKey stable key reused for an identical tool retry
     * @param report READY/EMPTY/FAILED results, entries, captured source references
     *         and explanations for every admitted scope
     * @return durable receipt, updated revision, and bounded actual result packet
     * @throws ContextConflictException if ownership/revision changed or the key
     *         was reused with a different normalized report
     * @throws ContextInvalidRequestException for invalid claim basis, unresolved
     *         references, target scope, or byte limits
     *
     * Validate structure/identity, not semantic truth. Only source-backed
     * generated entries may change. Commit content and replay receipt together.
     * Return the text actually accepted so callers cannot assume every proposal
     * was saved. User edits/disables always defeat a stale batch.
     */
    ContextPreparationReceipt complete(ContextExecution execution, String attemptId, String operationKey, GeneratedContextReport report);

    /**
     * Records a failed/interrupted attempt without deleting prior good content.
     *
     * @param execution trusted owner or application reconciliation identity
     * @param attemptId still-current attempt to fence
     * @param reason bounded user-facing reason, without credentials/raw logs
     *
     * A late callback for another attempt cannot mutate current ownership.
     * Startup uses existing task/runtime recovery before clearing uncertain
     * owners. This method never schedules a replacement model run.
     */
    void fail(ContextExecution execution, String attemptId, String reason);
}

public interface ReviewContextService
{
    /**
     * Captures the bootstrap for a newly frozen review run.
     *
     * @param review trusted owning review resolved by startup or recovery
     * @param run persisted run with frozen code identities
     * @param workspace verified managed repository and monitored upstream routing
     * @param previous accepted predecessor for incremental changed-path matching
     * @return stored run with captured On/Off, packet and retained evidence;
     *         replay of an existing snapshot returns the same run unchanged
     *
     * Reuse local selection. Persist packet body/digest before provider input
     * submission and leave acceptance to existing delivery receipts. Missing
     * generated notes are explicit; this method cannot synchronously invent them.
     * Off records the disabled snapshot and bypasses selection/preparation.
     */
    ReviewRun prepareRun(AgentReview review, ReviewRun run,
            WorkspaceRepositoryContext workspace, Optional<ReviewRun> previous);

    /**
     * Resolves the packet for an actual investigation/validation/discussion turn.
     *
     * @param execution backend-resolved accepted turn, including old-card binding
     * @return exact run packet/brief and discussion supplement, or empty when
     *         the source run captured Off
     * @throws ContextConflictException if the binding is no longer active
     *
     * Called at execution, not enqueue. Never substitute current notebook text
     * for an old snapshot or automatically move a worktree to another head.
     * Resolve the source run's saved setting, not today's workspace switch.
     */
    Optional<ContextPacket> forTurn(ContextExecution execution);

    /**
     * Seals a context contribution alongside an accepted investigation report.
     *
     * @param execution current investigation or collection assignment/step
     * @param brief bounded explanation of applicable contracts and gaps
     * @param evidenceRefs references already captured for this run
     * @return persisted context snapshot
     * @throws ContextInvalidRequestException for missing/cross-run evidence
     *
     * The existing review operation key/report transaction owns replay.
     * The first investigation seals the original brief. Later collection
     * appends a step-owned supplement; it never overwrites that brief.
     * Validation reads both without editing either contribution.
     * Valid references establish provenance, not comprehension or correctness.
     */
    ReviewContextSnapshot sealInvestigation(ContextExecution execution, String brief, List<String> evidenceRefs);
}
~~~

The interface split represents notebook mutation, source reads, generation
authority, and review ownership. Implement with the smallest concrete
collaborators; do not add a provider/factory layer per source type. Existing
AppSettingsStore and AgentReviewStore remain the persistence ports.

### 10.5 Existing interface additions

~~~java
/**
 * Reads recent local history affecting the supplied relative paths.
 *
 * @param repository verified managed repository resolved by the application
 * @param ref trusted captured commit/ref, never an unchecked model option
 * @param paths one to twenty distinct literal relative files or directories
 * @param limit positive bounded count, at most fifty commits
 * @return commit facts only; no GitHub lookup, fetch, or enrichment write
 *
 * Added overload on existing GitBrowseService. Use the shared Git runner
 * with path arguments after --. Surface unsupported/truncated results;
 * do not silently claim exhaustive rename-following across many paths.
 */
public List<Commit> log(Path repository, String ref, List<String> paths, int limit);

/**
 * Persists the updated run and its already-validated context facts.
 *
 * Existing AgentReviewStore.updateRun retains optimistic version checking;
 * add the optional context field to ReviewRun instead of a parallel context
 * repository. ReviewContextService must use this existing ownership boundary.
 */
ReviewRun updateRun(ReviewRun run);

/**
 * Reads local prior runs from open and closed reviews of one workspace/repository.
 *
 * @param workspaceId owning workspace
 * @param repo case-insensitive owner/repository identity
 * @param limit one to ten runs
 * @return descending update time, then ascending run ID; no source hydration
 *
 * AgentReviewStore applies ownership and limit in the storage query before
 * loading run JSON. The result is a bounded discovery window, not complete
 * history or proof that no matching review exists outside that window.
 */
List<ReviewRun> listRecentRuns(String workspaceId, String repo, int limit);
~~~

Do not change existing methods to fetch merely because context needs data.
Add explicit selected hydration through the already-existing refresh services.
Use the current framework-free storage values and local read APIs.

## 11. Fixed tool and HTTP boundary

Extend the existing task MCP broker with exactly:

| Fixed operation | Allowed execution | Contract |
| --- | --- | --- |
| repository_context_read | Context-enabled review turns; explicitly bound builder | Read packet/notebook selection, registered source plus optional relative page/cursor, or related candidates by typed kind |
| repository_context_prepare | Context-enabled review investigation; explicitly bound builder | Begin/join with validated new scope/root proposals, or submit generated report using stable attempt/operation keys |

The operation discriminator is a closed schema, not a registry. Existing
review_save_investigation gains bounded context brief/reference fields for the
initial investigation, and context additions for each later collection step;
no separate acknowledgment tool/turn is added. Reuse existing tool descriptor,
strict schema, broker authentication and replay mechanisms. A policy-only
ReviewBridge supplies builder restrictions without PR tool authority.
Disabled runs use the existing report contract without requiring the new
context brief, preparation receipts, or references.

Backend binding supplies task, workspace, run, actor and accepted turn. Model
arguments never select them. Source IDs must belong to the bound catalog.
Context generation cannot call editEntry/saveLesson/delete or enable disabled
content or useInReviews. The two new tools and their extra policy/input are
absent for disabled review runs; this does not remove existing review tools
such as review_get_context. No model-callable GitHub writer is introduced.

Use the current authenticated Electron transport; proposed endpoints are:

| Method/path | Purpose |
| --- | --- |
| GET /api/workspaces/{workspaceId}/repository-context | Local notebook and preparation view |
| PUT /api/workspaces/{workspaceId}/repository-context/entries | Edit fields with id and expectedRevision in the body |
| PUT /api/workspaces/{workspaceId}/repository-context/entries/enabled | Change enabled with entryId and expectedRevision in the body |
| DELETE /api/workspaces/{workspaceId}/repository-context/entries | Remove/suppress with entryId and expectedRevision in the body |
| POST /api/workspaces/{workspaceId}/repository-context/lessons | Explicit edited Save lesson |
| PUT /api/workspaces/{workspaceId}/repository-context/configuration | Edit useInReviews and/or registered source roots/scopes with expectedRevision |
| POST /api/workspaces/{workspaceId}/repository-context/reset | Explicit selected suppression reset with expectedRevision |
| POST /api/workspaces/{workspaceId}/repository-context/build | Scoped Build/Refresh, requestKey, expectedRevision, expectedBuildSequence and provider choices |

Source editing uses the same guarded mutation semantics as entry editing.
Entry IDs travel in request bodies because canonical knowledge IDs contain
repository paths and slashes; they are not URL path segments. Every mutation
requires an explicit nonnegative expectedRevision rather than defaulting a
missing field to zero. Source/excerpt provenance, generated origin and entry
versions remain assigned by the backend.
Reuse existing task/turn endpoints for transcript, Stop, approvals and runtime
recovery through an ownership-checked context-build route/adapter if interactive
routes exclude workflow tasks. Do not weaken those interactive ownership guards.
Review context is added to the existing review DTO/read path; do not expose
another cross-task history endpoint.

Conflicts return current revision and safe explanation; invalid input is 400,
missing workspace/source is 404, conflict is 409, and unavailable source is a
typed bounded source result. The renderer does not call GitHub or SQLite.

## 12. Risks, verification, and enablement

| Risk | Required control/evidence |
| --- | --- |
| Repeated cold builds | Shared attempt, READY/EMPTY reuse, scoped fingerprints; demonstrate reuse across both providers |
| Wrong shared note | Source-linked claims, original-source recheck before findings, user correction/suppression, poisoned-note evaluation |
| Irrelevant or stale context | Explainable selection, actual versions, cache/gap labels, old-run discussion binding |
| SQLite contention | No slow work in transactions; concurrent sync/build/review connection-wait measurement |
| CPU/RAM and network growth | Bounded path history/document extraction/response size; no full scans/crawl; record cold/warm measurements |
| Storage/prompt growth | Notebook and per-packet bounds, single saved bodies, current metadata cleanup, retained evidence only |
| Native memory/prompt confusion | Per-lane executable delivery/resume/isolation checks; disclose residual limits |
| Misleading UI | Prepared/provided/retrieved/cited distinction, uncertain delivery and gaps preserved in replay |

Enablement proceeds through the linked checkpoints: contracts and storage,
source reuse, preparation, review/provider integration, friendly UI, and measured
verification. Use fixture data and disposable repositories first. Live provider
checks are opt-in and follow the existing separate-profile credential rules.
Do not send comments/push/merge merely to test this feature.

Compare a small fixed representative PR set with/without context at the same
frozen revisions, provider/model and other settings. Start with small PRs that
cover distinct behaviors, including a case helped by history and a case with
irrelevant/stale knowledge; size alone is not a quality criterion. Use the same
workspace switch as the user, with independent fresh review tasks/provider
conversations for each arm. Do not use same-head Update or reuse an enabled
conversation for the disabled control. Record useful findings, false positives, misses,
tokens, wall time, local CPU/RAM, source counts, and DB wait. Include seeded
wrong/stale notes and conflicting documentation; verify source-based rejection
instead of repeating them. Schema checks or citation counts alone do not pass
the semantic-quality gate. Record noise and limitations; do not invent a
performance benefit from one run.

After the intended provider lanes pass acceptance, ship with useInReviews=true
by default and retain the visible Off switch. The comparison is an implementation
acceptance exercise, not a recurring background benchmark or a prerequisite
each user must run. Show the captured On/Off state on every review run.

Run the repository's full required checks for material implementation:
backend mvn verify; frontend npm run lint, npx tsc --noEmit, npm test,
and npm run package. These are implementation gates, not claims about this
documentation-only change.

## 13. Canonical integration and references

Implementation must reconcile the relevant sections of:

- [Architecture](architecture.md): existing modules, SQLite and composition.
- [Agent Service](agent-service.md): accepted-turn context and builder ownership.
- [Agent Review](agent-review.md): context reads, report fields, evidence and discussions.
- [Managed Agent Skills](agent-skills.md): small policy versus reference payload.
- [Agent Task UI](agent-task-ui.md): existing transcript/replay presentation.
- [Managed Worktree Service](worktree-service.md): explicit builder provisioning and cleanup.
- [Commit History](commits.md): local source truth and enrichment reuse.

This proposed design does not silently change those shipped contracts. In
particular, it does not authorize automatic remote-comment analysis, full
development integration, relaxed task ownership, provider children in review,
or GitHub publication.

External source examples and technical basis:

- [Trino concepts and architecture](https://trino.io/docs/current/overview/concepts.html)
- [Trino glossary](https://trino.io/docs/current/glossary.html)
- [Trino Iceberg connector](https://trino.io/docs/current/connector/iceberg.html)
- [Iceberg documentation](https://iceberg.apache.org/docs/latest/)
- [Iceberg specification](https://iceberg.apache.org/spec/)
- [SQLite WAL concurrency](https://www.sqlite.org/wal.html#concurrency)

The current/latest links are entry points; actual preparation records the
version it inspected and checks applicability to the target code.
