# Repository Context discovery during agent work

The approved [discover, learn, and use refactor](repository-context-refactor.md)
replaces automatic offers/ranking with agent-owned paginated literal lookup.
See [refactor checkpoints](repository-context-impl-tmp.md#refactor-development-checkpoints)
for delivery status. Retained source/receipt ownership remains applicable.

This is the first agent-discovery extension to [Repository Context](repository-context.md).
The bounded development offer, review/development search and exact read, retained
receipts, and usage view are implemented. Live provider acceptance and controlled
quality/performance comparison remain open. Existing notebook creation,
explicit Learn/Refresh, review preparation and review snapshots remain the
starting point.

## 1. Decision in simple terms

ByteQuay gives an agent a small selection of likely notes when a turn starts.
While working, the agent can search the same workspace notebook by a topic or
repository path, then open an exact note version. Search returns suggestions,
not conclusions. Opening a note returns its saved text and source references;
the agent still checks the relevant code and original evidence before relying
on it. Search and open never create or refresh knowledge.

The first implementation scans the current notebook in memory. A notebook has
at most 100 entries, so it needs no vector database, embedding, background
indexer, extra repository or provider-native memory. Existing merged-PR and
closed-issue learning can produce source-linked **knowledge** in this notebook;
user-saved **lessons** are its **memory** entries. Both are searchable. Raw PRs
and issues that have not been learned remain in their existing stores and are
not silently turned into notes by a search.

## 2. Overall flow

| Moment | Development task | PR review |
| --- | --- | --- |
| User starts work | A new default-on **Use in development** workspace setting permits a bounded notebook offer on each accepted root user turn. The project overview and notes matched by the request appear first. | The existing default-on **Use in reviews** setting and frozen run selection keep supplying the required bootstrap. |
| Agent encounters a component or question | It calls `repository_context_lookup` with a topic and/or paths. | It calls `review_get_context` with `kind=note_search`. The frozen changed paths are default hints. |
| Search returns | Up to five note identities, versions, summaries, scope and exact match reasons. A result outside the matched scope says so. | The same candidates, plus the observed notebook revision and whether it is newer than the run's original snapshot. |
| Agent opens one | An exact ID, version and search reference produce bounded text and saved source evidence. | The existing selected-note read still works; a newly found note needs the saved search reference. Its exact read is retained in the run. |
| Agent uses it | It checks current worktree code and original sources, then may mention the note in its answer. The UI says **Offered**, **Found** or **Opened**, not “understood”. | It checks the frozen reviewed code and original sources. The UI can additionally say **Cited in review** only when a saved finding/report references the retained evidence. |
| After the task | Repository context shows confirmed note delivery, searches, exact reads and gaps by development task and note version. | The same view groups review runs separately and links back to their saved context receipts. |

For example, a development request mentioning Iceberg first receives Project
overview and any term-matched Iceberg note. After the agent finds
`plugin/trino-iceberg/`, it can search with that path and “snapshot isolation”.
The result may include an Iceberg knowledge note and a user lesson about a
previous failure. If the agent never searches, it still receives the initial
offer; ByteQuay does not claim it read or applied anything beyond that offer.

When enabled notes exist but none match a development request, the accepted
turn receives only a short search reminder. It names no note or claim. Disabled
or suppressed notes do not cause that reminder.

A short managed instruction describes the lookup tool and asks the agent to
search when it enters a component, encounters a new topic, or needs to check a
saved lesson. Tool use remains optional; matched notes are offered even if the
agent never calls it. No scheduler or extra model turn is started.

An empty notebook displays **Not prepared** or the recorded **Empty/Failed**
preparation state. A populated notebook with no search hits displays **No
match in saved notes**. These are different states. Development does not start
a Learn task implicitly. Existing review first-use preparation rules continue
to operate as designed.

## 3. When and how matching works

Automatic selection continues to use `RepositoryContextMatcher`. Review keeps
its frozen diff paths and PR text. A development offer uses the accepted user
message without guessing changed paths. It includes Project overview when
present, then whole matching entries within an 8 KiB UTF-8 offer budget.
Entries that do not fit are listed by ID/version/title and a short summary
preview for an explicit read, up to the same total offer budget; omitted
entries have an explicit count. This offer is
captured once at actual launch of each accepted root user turn, including
queued starts; later notebook edits cannot rewrite it. The On/Off choice is
frozen in that turn's accepted settings, so changing the switch while queued
applies to subsequently accepted turns. The notebook revision and note versions
are observed at launch. Generated CI, publication and Learn turns do not
receive a development offer. A later user turn makes a new selection.

An on-demand query has one plain-text topic of at most 200 UTF-8 bytes and up
to 20 normalized repository-relative path hints. At least one must be present;
in review, the frozen required-review paths count even when the agent supplies
none. The agent-supplied path-hint list is limited to 4 KiB in total. Frozen
review paths follow the existing matcher bound and appear in the response as
a count, not a copied list.
Path hints rank candidates; they are not proof that a file changed or exists.
The server derives workspace and monitored repository from the bound task/run,
never from model arguments. It excludes disabled entries, disabled scopes and
suppressed knowledge before matching.

Search reuses the matcher's Unicode/word normalization, component-boundary path
rules, whole configured terms and lesson topic words. The exact rank groups
are (1) naturally matched entries other than scope-only lessons, in the
current matcher's order, (2) meaningful topic matches in the **title and
summary only** of other enabled entries, and (3) naturally matched lessons
with no lesson-specific topic overlap. Group 2 makes unknown components
discoverable; its hits say **topic match only; component not matched**. A
query does not search note bodies, claims or source excerpts, and one repeated
word does not add score. Equal results retain notebook order. At most five
hits are returned. Summary previews are bounded to 300 UTF-8 bytes each and
match reasons to 1 KiB per hit, with truncation flags. The complete response
is measured before return and keeps at least one complete hit when matches
exist. No hit is an
applicability or correctness verdict.

If a search was made after review preparation saved a new knowledge note, that
new entry can be found, but its receipt says it was observed at a later
notebook revision. It is never inserted retroactively into the run's initial
selection or bootstrap. The review's Off choice is frozen when the run begins;
an Off run cannot search notes even if the workspace setting later changes.

## 4. Tool result and exact read

Illustrative search response (real IDs, bytes and revision come from the
backend):

~~~json
{
  "searchRef": "context-search:<sha256>",
  "notebookRevision": 14,
  "originalNotebookRevision": 12,
  "newerThanSnapshot": true,
  "contextState": "MATCHES",
  "query": "snapshot isolation",
  "paths": ["plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java"],
  "omittedPathHints": 0,
  "pathHintCount": 2,
  "frozenPathHints": 1,
  "scopeStates": [{"scopeId":"path:plugin/trino-iceberg/","preparation":"READY"}],
  "hits": [
    {
      "entryId": "knowledge:path:plugin/trino-iceberg/",
      "entryVersion": 3,
      "kind": "KNOWLEDGE",
      "scopeId": "path:plugin/trino-iceberg/",
      "title": "Iceberg connector",
      "summary": "Snapshot and table operations; check the cited source version.",
      "matchReasons": ["Agent path hint matches plugin/trino-iceberg/", "Topic words: snapshot, isolation"],
      "applicability": "UNVERIFIED"
    }
  ],
  "omittedCount": 0,
  "omittedScopes": 0
}
~~~

The review tool returns these fields at the top level. Its `paths` are retained
agent-supplied hints; `pathHintCount` includes bounded frozen review paths, and
`frozenPathHints` counts those added from the review scope. The development tool
returns `searchRef`, the saved query/path hints and a `result` object containing
the search fields.

`contextState` describes this query: `MATCHES` or `NO_MATCH`; development Off
does not register the lookup tool, and review Off rejects `note_search`.
Each relevant scope separately reports its
actual `NOT_PREPARED`, `READY`, `EMPTY` or `FAILED` preparation state and
missing-coverage reasons. A mixed notebook never receives one misleading
global “empty” or “failed” state. Search output is at most 16 KiB, including
metadata and omissions. It keeps complete ID/version pairs and omits whole
hits if the cap is reached; `omittedCount` states how many were left out.
Scope statuses are also bounded with an omitted count so they cannot displace
the first complete hit.

The two provider-facing request shapes are deliberately small:

~~~json
[
  {"tool":"repository_context_lookup","action":"SEARCH","query":"snapshot isolation","paths":["plugin/trino-iceberg/"],"operationKey":"search-1"},
  {"tool":"repository_context_lookup","action":"READ","searchRef":"context-search:opaque-id","entryId":"knowledge:path:plugin/trino-iceberg/","entryVersion":3},
  {"tool":"review_get_context","kind":"note_search","query":"snapshot isolation","paths":[],"operationKey":"search-1"},
  {"tool":"review_get_context","kind":"note","searchRef":"context-search:opaque-id","entryId":"knowledge:path:plugin/trino-iceberg/","entryVersion":3}
]
~~~

The development `SEARCH` also returns an `offerRef` in its automatic turn
offer; `READ` may use that ref instead of `searchRef`. A review `note` read
without `searchRef` retains its existing frozen-selection behavior.

To read, the agent passes `searchRef` (or the turn's `offerRef`), `entryId` and
`entryVersion`; an originally selected review note may instead use its
existing selection. A **new** search/offer read requires that this exact pair
appeared in its bound result and is still enabled in the same scope/version at
read time; it captures note text and source excerpts before returning them.
A changed, disabled or removed dynamic version yields `VERSION_UNAVAILABLE`
with a suggestion to search again, never a replacement. Existing frozen
review selections retain their current rule: captured text is readable even
after a notebook edit, and a deferred entry may read the same ID/version
without rechecking today's enablement. A retained successful read is returned
first on exact retry, even if the notebook later changes. Review reads keep
their existing 64 KiB response and 1 MiB cumulative evidence limits;
development reads use the same response bound and a 512 KiB per-turn retained
text/evidence cap. The entry body itself is already limited to 8 KiB.

The read returns `entryId`, `entryVersion`, observed `notebookRevision`,
`text`, `sourceEvidenceRefs`, `applicability`, `limitations` and an opaque
`evidenceRef`. A development read also includes saved source references,
versions and excerpts as whole bounded pieces within its 64 KiB response;
omitted source excerpts are counted. The review tool continues to expose those
retained sources through its existing evidence selector. For development,
applicability starts `UNVERIFIED` because the
worktree may have uncommitted changes. Newly discovered review notes also
start `UNVERIFIED`; originally selected review notes retain their captured
frozen-head source comparison.
`MATCHED` means source identity matched the reviewed code, never that a claim
was proved. Source excerpts are untrusted reference data. No search/read tool
can edit a note, start learning, fetch a whole site or write to GitHub.

~~~json
{
  "status": "AVAILABLE",
  "entryId": "knowledge:path:plugin/trino-iceberg/",
  "entryVersion": 3,
  "notebookRevision": 14,
  "applicability": "UNVERIFIED",
  "evidenceRef": "context-note:opaque-id",
  "text": "Saved note text with claim and source references.",
  "sourceEvidenceRefs": {"source-connector-doc": "context-source:opaque-id"},
  "sources": [{"ref":"context-source:opaque-id","kind":"REPOSITORY_FILE","version":"illustrative-commit","excerpt":"Saved source passage."}],
  "omittedSources": 0,
  "limitations": ["Check the source version against this worktree or reviewed head."]
}
~~~

## 5. Binding, storage and visible activity

Use one app-level notebook lookup implementation for matching and exact
version/source capture. Register one fixed, read-only development tool through
the existing per-task broker for Codex and Claude. Extend the existing review
tool with `note_search` and supplemental `note` reads. Do not expose a
`ReviewBridge` to an ordinary development task: review presence changes its
command policy. Do not add a generic tool registry.

`useInDevelopment` is a separate default-on boolean in the same workspace
notebook configuration. The existing strict JSON decoder must explicitly
normalize an absent field in an older saved notebook to `true`, while still
rejecting malformed or null values; adding a primitive record field alone
would reject old notebooks. The existing `useInReviews` switch remains
independent. Development Off omits its offer and lookup tool; review Off omits
its packet and rejects note search. Neither switch deletes notes.
The development choice is copied into accepted turn settings so queued and
resumed turns use the user's choice at acceptance, while note content itself
is selected at actual launch and its revision is captured in the offer.

Only the current RUNNING development turn with a provider reference and a
verified task worktree claim can
use the development tool; the explicit Learn builder, review-owned tasks and
generated automation turns do not receive it. The existing provider MCP token
is task-scoped and may be inherited by native children. Child calls therefore
have the same read-only workspace boundary and are logged against the owning
turn; there is no separate automatic child offer or claim of independent child
delivery. Review search is available
only in active context-enabled investigation, validation or evidence-collection
steps. A new dynamic exact read has the same active-step gate. Old-card
discussion continues to use its original captured run: it may read an
originally selected note or replay an already retained read, but cannot search
today's notebook or first open an earlier dynamic search hit. The broker
re-resolves ownership on every call. A workspace setting change applies to
newly accepted development turns; it cannot remove reference text already
delivered to an active turn or erase it from a resumed provider conversation.

Review saves search results and exact reads in its existing run operation
journal/evidence map, separately from `selectedEntries`. Development saves a
small task-and-turn-owned offer/search/read receipt in one Hibernate-owned
SQLite table, under the task's existing retention and cleanup lifetime. Rows
are deleted with the task; a removed notebook does not erase still-retained
task evidence. This is one bounded append-only receipt path, not another
notebook or database. Its key is `(task_id, turn_id, operation_key)`; a row has
`kind`, `payload_json`, `payload_digest` and `created_at`. The bounded payload
records the observed notebook revision, entry IDs/versions, query/path reasons,
and exact offered or returned bytes and source evidence. Delivery status is
derived from existing provider receipts rather than asserted by the row.
The initial offer is keyed by turn ID and reused on accepted-turn retry.
Search requires a 1–100 character `operationKey` and saves the displayed hits before
returning `searchRef`. A retry with the same key and arguments replays the saved
result; the same key with altered arguments conflicts. A genuinely fresh search
uses a new key. An exact read has a server-derived key from its search reference,
entry ID and version; it saves text and evidence before returning, then replays
that row on retry.
Review permits at most 20 distinct searches and 320 KiB of search-result JSON
per run. Development permits the same per turn, plus at most ten exact reads
and the 512 KiB read cap above. All development context receipts together
are capped at 8 MiB per task, including repeated turns. Replays do not
consume quota; exhaustion
returns an explicit bounded error. Notebook parsing, matching and source reads
happen outside SQLite write transactions; only receipt writes and ownership
checks occur inside them.

The UI uses the current task transcript and review context details:

- **Offered**: exact note versions and byte count prepared for a turn;
  **Provided to agent** only with the existing provider acceptance receipt.
- **Search result prepared**: saved query, paths, match reasons, observed
  revision, hit count and truncation. Show **Found later** only when the
  provider's completed tool event can be correlated to this search receipt.
- **Exact read prepared**: saved version, evidence link and source warning.
  Show **Opened** only when the provider's completed tool event can be
  correlated to this read; otherwise show delivery as uncertain.
- **Cited in review**: only when a saved review report/finding names that
  retained evidence. Development shows no “applied” badge inferred from prose.

A compact activity card links to a detailed receipt; it does not dump note
bodies or private discussion into the main log. Current notebook edits can
appear as “this note has since changed” beside old captured text. The UI must
not show a search hit as if its body was opened. The development setting and
tool instructions explain that lessons may contain private task discussion:
reading them does not grant permission to publish their text to GitHub.

## 6. Implementation contracts

These are proposed method contracts, not currently implemented Java APIs.
The two provider tool shapes adapt to the same service.

~~~java
/** Searches only enabled notes in the server-bound task/run workspace.
 *  Reads one immutable notebook revision and never refreshes sources or writes
 *  the notebook. The caller retains the bounded result before returning it.
 */
SearchResult search(ContextBinding binding, SearchQuery query);

/** Reads only an ID/version offered or found for this binding. Captures note
 *  text and referenced source evidence atomically with its receipt. Returns
 *  VERSION_UNAVAILABLE on a changed/deleted version, without substitution.
 */
ReadResult read(ContextBinding binding, String searchRef,
        String entryId, long entryVersion);

/** Composes the bounded development offer for one accepted root user turn.
 *  Persists the exact packet before provider launch and reuses it on retry;
 *  returns the original message unchanged when the turn is ineligible or Off.
 */
String prepareDevelopmentInput(AgentTask task, AgentTurn turn, String message);
~~~

`ContextBinding` is constructed only by trusted task/review services and holds
the task ID, accepted turn ID, workspace and repository identity, optional
review run, and captured On/Off choice. It is never parsed from tool JSON.
`SearchQuery` validates UTF-8 bytes and paths before matching. In core, rename
the review-only accepted-turn input hook to a neutral hook and dispatch to
existing review input or this development input; do not prepare twice.

## 7. Small delivery slices and checks

1. Add shared read-only search with deterministic ranking, version checks,
   no-match state and bounded JSON. Prove disabled/suppressed and cross-workspace
   entries cannot appear; prove a topic-only query finds an otherwise unmatched
   lesson but labels it as a pointer.
2. Extend active review `review_get_context` and its saved operations/projection.
   Prove a note learned during that review can be found later without changing
   the frozen bootstrap; Off and old-card discussions stay isolated.
3. Add the separate default-on development setting, accepted-turn offer, one
   fixed task-bound tool and task receipts for both providers. Prove queued
   launch/retry, changed note versions, user turn scope changes and task/child
   boundaries. Keep provider-native memory behavior unchanged.
4. Add friendly task/review activity cards and perform real Codex/Claude checks.
   Compare one context On/Off development task and review a stale or misleading
   lesson. Measure bytes, lookup time, SQLite write time and actual retrieval;
   do not equate a tool call with correct judgment.
5. Add the small usage projection and Repository context usage view in section 8.
   Verify distinct-task counts, retries, uncertain delivery, note versions,
   missing-scope demand and deletion. Add optional quality feedback only after
   the receipt counts are trustworthy.

No background learning scheduler, automatic lesson creation, raw-history crawl
or general semantic-search service is part of these slices.

## 8. Usage and missing-coverage view

The first view answers four different questions: **What has been saved? Which
tasks were offered it? What did the agent retrieve? Where did work need
knowledge that was not available?** These are observation counts, not a claim
that the agent understood a note or that it improved the result. A merged PR,
completed task or source citation alone cannot prove that a particular note
helped. Explicit Learn tasks create knowledge and are not counted as using it.

### 8.1 Count only observable events

| Display | Count when | Do not count when |
| --- | --- | --- |
| **Selected** | Matching chose this ID and version for an automatic offer, including a budget-deferred note. | It only appeared in the notebook or matched a broad source. |
| **Provided** | Its complete text was included in the automatic input and the provider accepted that input. | The packet was merely prepared, delivery is uncertain, or the note was deferred. |
| **Found** | A completed search tool response contained this ID and version. | Search was prepared but delivery is uncertain, or the hit was omitted by its byte limit. |
| **Opened** | A completed exact-note read returned this ID and version to the agent. | A search only showed the title/summary, or a read was prepared without a correlated completed tool event. |
| **Cited in review** | A saved review finding or conclusion explicitly references this note's retained evidence ID. | The agent cites an original PR, document or source file, or mentions a note in unstructured prose. |

These are not steps in one funnel: an automatic offer can be provided without a
search, and a search hit can be opened without an offer. Show both event count
and distinct development-task/review-run count. **Delivered or opened in N
tasks** is the union of confirmed **Provided** and **Opened** tasks; search
hits and selected/deferred notes do not increase it. Replays, duplicate provider
events and repeated reads with the same operation key count once. A deliberate
new search can add one search event but not another distinct task. Group by
**Development** and **PR review**, then by **Knowledge** and **Saved lesson**.
The all-version task count is a union of task IDs, so a task that saw both v2
and v3 still counts once for that note.
Review discussion receipts remain under their owning review run and can be
identified in its detail, rather than counted as another review.

The current review UI's source-citation count is not a note-citation count.
Each included bootstrap note and later exact read has its own retained note
evidence reference; a saved finding or conclusion must cite that exact reference
to count as **Cited in review**. Older runs without such references are outside
this new citation tracking boundary. Development has no structured note citation
yet, so it shows Provided, Found and Opened, with no inferred “applied” or
“helped” count.

### 8.2 Show demand separately from retrieval

An enabled component scope can be relevant even when it has no knowledge note.
Record a **scope demand** for an accepted automatic offer or completed search
when matching identifies that scope and its canonical knowledge entry is
missing or disabled. Repeated receipt keys add no new event; the overview also
shows distinct demanding tasks/runs so repeated questions in one task do not
inflate demand. Preserve the observed preparation state:
`NOT_PREPARED`, `EMPTY`, `FAILED` or `READY` with a missing entry. Also show the
scope's **current** state, since the user may have learned it afterward. An
`EMPTY` preparation means the attempt finished without a durable useful note;
it is not the same as never built. A populated scope with no note match is
**No matching saved note**, while a query with no configured scope is **No
matching scope**. Neither is evidence that knowledge was never built. Off and
uncertain delivery are shown separately. The gap view links to the existing
explicit **Learn / Refresh** action with the scope preselected; viewing stats
never starts learning. Saved lessons have no promised entry per scope: a
lesson search with no hit is a no-match observation, not an automatic request
to create a lesson.

### 8.3 One small projection beside the existing receipts

Keep immutable review operations and the proposed development receipts as the
source of truth. In the existing SQLite database, add one indexed
`context_usage_fact` table for fast workspace-level grouping. An eligibility
fact marks each root development turn as it begins launch, or each prepared PR
review run, including its captured On/Off choice; this supplies the denominator even when no note
matches. A workspace-owned tracking-start marker is created with the first
eligibility fact and remains until workspace deletion. A completed no-hit
search also records its known scope status without
inventing a missing-knowledge demand. Other rows store
`workspaceId`, `taskId`, `turnId` or `reviewRunId`, task kind, source receipt
reference, stage, delivery status (`PREPARED`, `CONFIRMED`, `UNCERTAIN`), event
time, and either scope demand plus its observed preparation state or a note's
ID, version, `KNOWLEDGE`/`MEMORY`, scope ID and short title snapshot. No note
body, search text, source excerpt or provider transcript is copied into this
table. A unique source-reference/stage/note-version key makes receipt retry and
replay idempotent; confirmation updates the same fact instead of inserting a
second use. Prepared facts are written atomically with their saved receipt;
provider acceptance or a correlated completed tool event confirms them later.
Uncorrelated receipts stay prepared, including when a bounded retained-event
replay cannot establish delivery. These observations never start learning or
change the notebook revision. The first view reads indexed facts in the chosen window on demand
and pages the displayed notes and gaps; it does not parse retained review
snapshots. Its distinct-task aggregation still scans those fact rows and grows
with retained activity, especially for **All retained**. Measure read and write
time in the performance check; move grouping into SQLite if real workspaces
make this view slow.

`trackingSince` comes from the retained workspace marker. Before any eligible
task, show **No tracked tasks yet**, not a zero-use verdict. Do not manufacture
historical events from old prose or incomplete logs. Routine seven-day task
runtime-log cleanup does not remove these facts or durable task/review receipts.
Explicit task/review deletion removes its linked facts; workspace deletion
removes all workspace facts. Editing, refreshing, disabling or deleting a note
does not rewrite already retained task facts. The view says **Recorded delivery
and reads in retained tasks since tracking began**, never “never used.” It reports the
current note version separately from all retained versions; use of v2 does not
prove that refreshed v3 was seen. If durable lifetime totals after task deletion
become necessary, design their privacy and aggregate-retention rule separately.

### 8.4 Location, API and example

Add **Usage** inside Workspace settings → **Repository context**, with a
default last-30-days filter and **All retained** option. Show `trackingSince`
and the counted period beside every summary. At the top, show eligible
Development/PR review counts, tasks with context Off, tasks that received or
opened a note, active notes with no confirmed delivery/open, and scopes demanded
while missing and still missing now. A note table shows title,
type, scope, current version, distinct Provided/Found/Opened/Cited task counts,
last confirmed use and a link to task/review receipts. A gap list shows the
observed and current preparation states. The task/review detail shows its exact
note versions, match reasons and confirmation state; it never calls a prepared
or uncertain packet “used.” A scope's current knowledge presence is shown
separately from its preparation state, because a ready note may later be
disabled or removed.

`GET /api/workspaces/{workspaceId}/repository-context/usage?window=30d&page=0`
aggregates only that workspace's indexed facts and current notebook state.
Return bounded pages of notes and scope gaps, not note text. `window=all`
means all retained facts, not lifetime use before tracking or after task
deletion. A note-row drilldown uses
`GET /api/workspaces/{workspaceId}/repository-context/usage/activities?entryId={encodedEntryId}&window=30d&page=0`;
it returns bounded task/review links, exact versions, stages, timestamps and
receipt references. Note IDs can contain `/`, so the ID is a query parameter.
The backend checks workspace ownership before resolving
any link. An illustrative overview response is:

~~~json
{
  "trackingSince": "2026-09-24T00:00:00Z",
  "window": "30d",
  "coverage": "RETAINED_TASKS_AND_REVIEWS",
  "summary": {
    "eligibleDevelopmentTasks": 12,
    "eligibleReviewRuns": 8,
    "contextOffTasks": 1,
    "tasksProvidedOrOpened": 9,
    "activeNotesWithoutConfirmedDeliveryOrOpen": 3,
    "scopesDemandedWhileMissing": 2,
    "scopesStillMissingAfterDemand": 1,
    "searchesWithNoMatchingScope": 2,
    "searchesWithScopeButNoNote": 3
  },
  "byTaskAndNoteKind": [
    {"taskKind":"DEVELOPMENT","noteKind":"KNOWLEDGE","providedOrOpenedTasks":5,"foundTasks":3,"openedTasks":2},
    {"taskKind":"PR_REVIEW","noteKind":"MEMORY","providedOrOpenedTasks":2,"foundTasks":1,"openedTasks":1}
  ],
  "entries": [{
    "entryId": "knowledge:path:plugin/trino-iceberg/",
    "kind": "KNOWLEDGE",
    "scopeId": "path:plugin/trino-iceberg/",
    "title": "Iceberg connector",
    "currentVersion": 3,
    "currentEnabled": true,
    "currentVersionCounts": {"selectedTasks": 4, "providedTasks": 3, "searchHits": 2, "foundTasks": 2, "openedTasks": 1, "citedReviews": 0},
    "allVersionCounts": {"providedTasks": 7, "foundTasks": 4, "openedTasks": 2},
    "lastConfirmedUseAt": "2026-09-23T10:15:00Z"
  }],
  "scopeGaps": [{
    "scopeId": "path:plugin/trino-iceberg/",
    "demandingTasks": 2,
    "observedPreparationCounts": {"NOT_PREPARED":2,"EMPTY":0,"FAILED":0,"READY":0},
    "currentPreparation": "READY",
    "currentKnowledge": "PRESENT"
  }],
  "hasMoreEntries": false,
  "hasMoreScopeGaps": false
}
~~~

The proposed read contract is small and does not touch the notebook:

~~~java
/** Returns paged, workspace-owned usage counts from retained facts and current
 *  note metadata. Separates task kind, note kind, note version, delivery stage
 *  and missing-scope state; returns the tracking boundary with every page.
 *  It never reads note bodies or launches learning.
 */
UsageOverview usage(String workspaceId, UsageWindow window, int page);

/** Returns bounded task/review receipt links for one note ID after checking
 *  workspace ownership. Exact versions and uncertain receipts remain visible;
 *  deleted task details are never reconstructed from provider logs.
 */
UsageActivities usageForEntry(String workspaceId, String entryId,
        UsageWindow window, int page);
~~~

For quality, the smallest later addition is an optional **Helpful / Not
relevant / Misleading** rating on a task's exact note version, editable by the
user. It is feedback, not a required approval for every lesson and not an
automatic notebook edit. Show ratings apart from retrieval counts, with links
to inspect or disable misleading notes. Controlled On/Off comparisons and
source checks remain the way to evaluate whether the feature improves review
judgment; usage numbers alone measure reach.
