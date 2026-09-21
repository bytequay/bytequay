# Discussion Threads

A discussion thread is a short, private, read-only conversation about one
quoted passage of a completed assistant answer in an agent task. The reader
highlights part of an answer, asks about it, and receives a brief reply in a
thread folded under that answer. The task's own conversation is never touched.

This document is the implementation contract. The interfaces below are the
boundary: implementation follows them rather than rediscovering them.

## 1. Decision

A discussion is a **separate provider conversation**, not a turn on the task.

The problem it solves is that a long agent answer invites a small question, and
today the only place to ask it is the task's own composer. That pollutes the
working conversation, consumes its context, and can disturb work in progress.

Three properties follow, and everything else in this design serves them:

1. **Isolation.** A discussion's questions and answers never enter the task's
   provider conversation, its event journal, its outbox, or its event stream.
2. **Grounding.** The discussion starts from the task's own history through the
   selected turn, and may read the repository, so its answers are about the
   real work rather than the quoted words alone.
3. **Containment.** A discussion explains. It cannot change anything.

A discussion is deliberately **not** an agent task. It creates no task row, no
turn row, no worktree, no branch, and no pull request, and it appears in no
task list.

## 2. Boundary

| Concern | Discussion | Task turn |
| --- | --- | --- |
| Provider conversation | its own, one per thread | the task's one conversation |
| Durable identity | one `task_discussion` row | `agent_task` plus `agent_turn` |
| Events | discarded by a private sink | the task's JSONL log, outbox, and stream |
| Repository access | read-only, sandbox-denied writes | read and write under the task policy |
| Concurrency | independent of the task's turn lane | one active turn per task |
| Lifetime of asking | until the worktree is removed | until the task is completed |
| Lifetime of reading | until the task row is deleted | subject to runtime retention |

A discussion inherits nothing from the task's turn machinery. In particular it
never acquires the worktree mutation claim, so it cannot block or be blocked by
the task's own work.

## 3. Process flow

```
FIRST QUESTION
  reader highlights text inside a completed answer
        |
        v
  POST /agent/tasks/{taskId}/discussions   { turnId, quote, occurrence, question }
        |
        +-- verify the quote occurs in that turn's stored answer
        +-- resolve or create the discussion for (task, turn, quote)
        +-- render the seed: the task's user messages and final answers
        |   through the selected turn, as the markdown they already are
        +-- record the question, return immediately
        |
        v   (virtual thread)
  open a read-only provider session on the task's lane, rooted at the
  task worktree, with no session reference
        |
        +-- send: preamble + seed + quote + question
        +-- record the answer and the provider session reference
        +-- close the session
        |
        v
  the client's poll shows the answer; the transcript footer reads "1 reply"

FOLLOW-UP QUESTION
  POST /agent/tasks/{taskId}/discussions/{id}/messages   { question }
        |
        +-- resume the recorded provider session
        +-- send: the question alone
        |
        +-- on a resume failure: discard the reference and re-send
        |   preamble + seed + quote + every prior message + the question
        v
  the answer is recorded the same way

AFTER THE TASK IS COMPLETED
  the worktree is removed; asking is refused and the composer is disabled.
  Every recorded message stays readable.
```

Resuming is what lets a follow-up build on what the previous round read. The
re-seed path is not a second design: it is the same prompt the first question
sends, and it is the only recovery a lost provider session has.

## 4. Contract

### 4.1 Domain

`backend/core/src/main/java/com/bytequay/core/discussion/`. These mirror the
`com.bytequay.core.localreview` records: plain identifiers, no dependency on
`agent-core` or `agent-spi` types.

```java
package com.bytequay.core.discussion;

/**
 * One private discussion about a quoted passage of a completed assistant answer.
 *
 * <p>The whole aggregate is persisted as a single JSON payload, so adding a field
 * needs no schema change. {@code seed} is captured when the discussion is created
 * and never recomputed, so the re-seed after a lost provider session reproduces the
 * original premise, and so the thread stays readable after the task's runtime
 * history is deleted.
 *
 * @param id             opaque assigned identifier
 * @param taskId         the task whose answer is being discussed
 * @param anchor         the quoted passage this discussion belongs to
 * @param seed           the rendered task history through the anchored turn
 * @param providerLane   the lane the conversation runs on, fixed at creation
 * @param providerModel  the model the conversation runs on, fixed at creation
 * @param providerModel  the model the conversation runs on, read from the task's
 *                       defaults and fixed at creation so a later default change
 *                       does not switch the thread mid-conversation
 * @param sessionReference the provider conversation to resume, recorded as soon as
 *                       the driver reports it — on the Claude lane before the first
 *                       process starts — and cleared after a failed resume
 * @param messages       questions and answers in order, oldest first
 * @param resolved       whether the reader has completed this thread
 * @param createdAt      when the discussion was opened
 * @param updatedAt      when its last message was recorded
 * @param version        optimistic-locking version
 */
public record TaskDiscussion(
        String id,
        String taskId,
        DiscussionAnchor anchor,
        String seed,
        String providerLane,
        Optional<String> providerModel,
        Optional<DiscussionSession> sessionReference,
        List<DiscussionMessage> messages,
        boolean resolved,
        Instant createdAt,
        Instant updatedAt,
        long version)
{
    public TaskDiscussion
    {
        requireNonNull(anchor, "anchor is null");
        requireNonNull(providerModel, "providerModel is null");
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(id.matches("[A-Za-z0-9._:-]{1,100}"), "Invalid discussion id");
        checkArgument(!taskId.isBlank(), "Task is required");
        checkArgument(!providerLane.isBlank(), "Lane is required");
        checkArgument(seed.length() <= MAX_SEED_CHARACTERS, "Seed exceeds the context budget");
        checkArgument(version >= 0, "Invalid version");
        messages = ImmutableList.copyOf(messages);
        checkArgument(messages.size() <= 100, "Discussion is full");
    }

    public TaskDiscussion withVersion(long value) { … }

    public TaskDiscussion update(boolean nextResolved, List<DiscussionMessage> nextMessages, Optional<DiscussionSession> nextSession, Instant now) { … }
}

/**
 * The passage a discussion is attached to.
 *
 * <p>{@code quoteHash} is the stable identity of the passage and makes one
 * discussion per passage a database fact. {@code quote} is stored exactly as the
 * reader saw it — rendered text, not markdown source — so the thread still renders
 * once the answer has left the loaded transcript window. It is always presented as
 * text, never as trusted markup.
 *
 * <p>The reader highlights <em>rendered</em> text while the backend holds
 * <em>markdown source</em>, so the two are different strings: a selection crossing
 * emphasis, inline code, a link or a line wrap does not occur verbatim in the
 * stored answer. Both sides are therefore passed through {@code DiscussionQuotes}
 * before they are compared or hashed. See section 5.2.
 *
 * @param turnId     the completed turn whose final answer was quoted
 * @param quote      the highlighted text as rendered, verified against that answer
 * @param occurrence which appearance of {@code quote} in that answer was highlighted,
 *                   1-based; the client knows it from the selection's position
 * <p>No label is stored: the reader-facing label is presentation, derived in the
 * renderer from {@code quote} and {@code occurrence}, for the same reason section
 * 8.1 refuses to store a block ordinal.
 *
 * @param quoteHash  {@code Hashing.sha256().hashString(
 *                   DiscussionQuotes.normalize(quote) + "#" + occurrence, UTF_8).toString()}
 */
public record DiscussionAnchor(String turnId, String quote, int occurrence, String quoteHash) {}

/**
 * One question or one answer.
 *
 * @param error present only on a question whose answer failed, carrying the
 *              reason; an answer never has one
 */
public record DiscussionMessage(
        String id,
        Author author,
        String body,
        Instant createdAt,
        Optional<String> error)
{
    public enum Author { USER, AGENT }
}

/** The provider conversation a discussion resumes, as the driver reported it. The lane is {@code TaskDiscussion.providerLane}. */
public record DiscussionSession(int contractVersion, String providerValue) {}
```

### 4.2 Storage port

`core` owns the port; `persistence-sqlite` owns the adapter. Outside Spring
wiring in `ByteQuayApplication`, `app` imports no persistence entity,
repository or store type.

```java
package com.bytequay.core.discussion;

public interface TaskDiscussionStore
{
    /**
     * Stores a new discussion, or returns the stored one when its anchor already has one.
     * The adapter uses {@code StoreWrites.createOrGet}, so a concurrent duplicate submission
     * re-reads rather than failing.
     */
    TaskDiscussion insertOrGet(TaskDiscussion discussion);

    /** Replaces one discussion. Fails when the stored version has moved on. */
    TaskDiscussion update(TaskDiscussion discussion);

    Optional<TaskDiscussion> find(String discussionId);

    /** The discussion anchored to one passage, when it exists. */
    Optional<TaskDiscussion> findByAnchor(String taskId, String turnId, String quoteHash);

    /** Every discussion on one task, oldest first by {@code created_at}. */
    List<TaskDiscussion> listByTask(String taskId);
}
```

### 4.3 Service

`backend/app/src/main/java/com/bytequay/app/discussion/TaskDiscussionService.java`,
a plain `@Service` class with no interface, matching `LocalReviewService`. The
thin controller is `com.bytequay.app.web.agent.TaskDiscussionController`, beside
`LocalReviewController`. `OpenDiscussionCommand` and `AskDiscussionCommand` nest
inside the service as `TaskDiscussionService.Open` and `.Ask`.

```java
package com.bytequay.app.discussion;

public interface TaskDiscussionService
{
    /**
     * Opens a discussion on a quoted passage and asks its first question, or asks a
     * further question on the discussion that passage already has.
     *
     * <p>Returns as soon as the question is recorded; the answer is produced on a
     * virtual thread and observed through {@link #list}. Repeating the same anchor
     * never creates a second discussion.
     *
     * @throws IllegalArgumentException when the quote does not occur in the stored
     *         final answer of {@code command.turnId()}
     * @throws AgentConflictException when the task has no live worktree, when a
     *         question is already in flight for this discussion, or when the task
     *         already holds {@code MAX_DISCUSSIONS_PER_TASK} discussions
     * @throws InvalidAgentRequestException when the seed exceeds the context budget
     */
    TaskDiscussionView open(OpenDiscussionCommand command);

    /**
     * Asks a follow-up in an existing discussion, resuming its provider conversation.
     * A resume that fails falls back to a re-seeded conversation carrying every
     * prior message, so a lost provider session costs context, not the thread.
     */
    TaskDiscussionView ask(AskDiscussionCommand command);

    /** Marks a discussion done, or reopens it. */
    TaskDiscussionView resolve(String workspaceId, String taskId, String discussionId,
            boolean resolved, long expectedVersion);

    /**
     * Re-runs a question whose answer failed, in place: the recorded question keeps its
     * identity and its error is cleared, so a retry never appends a second copy of it.
     */
    TaskDiscussionView retry(String workspaceId, String taskId, String discussionId, String messageId);

    /** Every discussion on one task, for the transcript footers and the panel. */
    List<TaskDiscussionView> list(String workspaceId, String taskId);
}

/**
 * @param quote      the exact highlighted text; the service verifies it and derives
 *                   the anchor hash from its normalized form
 * @param occurrence which appearance of {@code quote} in that answer the reader
 *                   highlighted, 1-based; refused below 1, so a body omitting the
 *                   field cannot silently mean the first
 */
public record OpenDiscussionCommand(
        String taskId, String turnId, String quote, int occurrence, String question) {}

public record AskDiscussionCommand(String discussionId, String question) {}

/**
 * Display projection of one discussion, and the only type the controller serializes.
 *
 * <p>The seed and the provider session reference are deliberately absent: the seed is
 * bounded only by {@code MAX_SEED_CHARACTERS} and the session reference is a provider
 * handle, and neither is drawn. The anchor is flattened to the three fields the renderer
 * uses; {@code quoteHash} is not projected, because the client locates a footer by
 * searching the rendered blocks for {@code quote} and taking the {@code occurrence}-th
 * match, and never recomputes the hash.
 *
 * @param turnId      the completed turn whose final answer was quoted; the client maps it
 *                    to a transcript turn while that turn is in the loaded window, and
 *                    renders the thread from {@code quote} alone when it is not
 * @param quote       the passage as the reader saw it, always rendered as text
 * @param occurrence  which appearance of {@code quote} in that answer, 1-based; the footer
 *                    places itself with it, and the panel tells apart two threads whose
 *                    labels are otherwise byte-identical
 * @param pending     a question is in flight, read from the service's in-memory set. It is
 *                    the only way to tell a running question from one orphaned by a
 *                    restart, which section 9 requires to render as unanswered: both are
 *                    a trailing question with no answer and no error
 * @param providerLane the lane fixed at creation, for the answer byline; it can differ
 *                    from the task's current default, so it is not read from the task
 * @param version     for the optimistic check on resolve
 * @param updatedAt   when the last message was recorded, or when the thread was resolved
 */
public record TaskDiscussionView(
        String id,
        String taskId,
        String turnId,
        String quote,
        int occurrence,
        boolean resolved,
        boolean pending,
        String providerLane,
        List<Message> messages,
        long version,
        Instant createdAt,
        Instant updatedAt)
{
    /**
     * One question or one answer, oldest first.
     *
     * @param error present only on a question whose answer failed, carrying the
     *              reader-facing reason the card prints beside Retry; an answer never
     *              has one
     */
    public record Message(String id, Author author, String body, Instant createdAt,
            Optional<String> error) {}
}
```

Nothing else belongs on the view. `label` is presentation, derived in the renderer
from `quote` and `occurrence`. A reply count is `messages.size()`. Askability is
`AgentTaskDto.checkoutRemovedAt`, which the task screen already holds. The panel's
group header reads `TranscriptTurn.ordinal` and the answer text for a loaded turn, and
degrades for one whose answer has been evicted. A typed error cause buys nothing while
Retry is the only action offered for every cause.

Internal collaborators are concrete classes, not interfaces: a seed renderer
and a session runner. Neither is a second agent harness — the runner opens the
existing driver directly, in the shape
`DefaultAgentTaskTitleService.generate` already uses.

### 4.4 Session context

`AgentSessionContext` gains one component immediately after `textOnly` and
before `codeGraph`, and its compact constructor gains the matching guard. Both
convenience constructors pass `false`.

```java
/**
 * Whether this session may read the repository it is rooted at but not change it.
 * Mutually exclusive with {@code textOnly}: text-only means no tools at all on the
 * Claude lane and a read-only, ephemeral, shell-less thread on the Codex lane, and
 * either way must stay inside the runtime directory. {@code readOnly} is the axis
 * that may be rooted at a real checkout.
 */
boolean readOnly
```

```java
checkArgument(!(textOnly && readOnly), "readOnly and textOnly are mutually exclusive");
```

`textOnly` keeps its existing guard: a no-tools session still may not be rooted
at a real checkout. `readOnly` is the axis that may.

## 5. Context and budget

The seed is the task's **user messages and final assistant answers** through the
anchored turn. Streaming deltas, tool calls, tool output, plans and activity are
excluded: they are the overwhelming majority of the volume and the agent can
read the repository instead.

Message bodies are carried **verbatim, as the markdown they already are**. They
are not flattened: the agent wrote them as markdown, models read markdown
natively, and flattening would destroy code blocks. Only the envelope around
them is ByteQuay's.

The seed is captured once, at creation, and never recomputed.

### 5.1 Prompt format

The prompt is assembled in this exact order. The re-seed path after a lost
provider session emits the identical envelope with the discussion's own prior
messages appended, so the two paths are one renderer with one test.

```text
You are answering a reader's question about one passage of an answer you
already gave in this task. Explain only; do not implement, continue the task,
or suggest unrelated work.

You may read files and run read-only commands to check something. You cannot
change anything: edits, commits and publishing are refused.

The working tree may be mid-edit by the task's own agent. Say so rather than
guess.

Answer in plain words and short sentences. Aim for 40 to 80 words and normally
stay under 100. Give one small example only when it helps. Give more detail
only when the reader asks. State uncertainty rather than inventing an
explanation.

--- conversation so far ---

[user]
<user message markdown, verbatim>

[agent]
<final answer markdown, verbatim>

... one labelled block per message, in order, through the anchored turn ...

--- passage the reader highlighted ---

<quote, verbatim>

--- discussion so far ---

[reader]
<earlier question>

[you]
<earlier answer>

... omitted entirely on the first question ...

--- question ---

<the new question>
```

A label is a line containing exactly `[user]`, `[agent]`, `[reader]` or `[you]`.
Inherited content is the agent's own prior output rather than foreign input, so
a body reproducing a label line is a cosmetic collision, not a trust boundary.

### 5.2 Matching the quote

The reader highlights **rendered** text; the backend stores **markdown source**.
`Splits are limited per **thread**,\nso small tables` and
`Splits are limited per thread, so small tables` are different strings, so an
exact substring check rejects any selection that crosses emphasis, inline code,
a link or a line wrap — that is, most interesting sentences.

One helper, `DiscussionQuotes.normalize`, is applied to **both** sides before
they are compared and before the quote is hashed. It extends the pass
`TaskTitles` already performs in `agent-core`:

- drop fenced-code fence lines;
- strip leading heading, list and block-quote markers from each line;
- reduce `[text](url)` to `text`;
- remove the inline markers `` ` ``, `*`, `_` and `~`;
- collapse every whitespace run to one space and strip.

Verification is then: the normalized final answer of the anchored turn contains
the normalized quote. A quote normalizing shorter than three characters is
refused as a stray click; nothing else is refused.

The occurrence index is **identity only**. It is inside the hash and is not
verified, because the client counts it over rendered text while this check runs
over normalized source, and image syntax, table pipes and separator rows make
the two counts divergible. A divergent index costs placement, which section 8.1
already degrades correctly, rather than an unactionable refusal.

The anchor is **not** required to be unique, because in real answers it often is
not. Measured over the 87 completed answers in this machine's retained task
logs: a selection of about forty-five characters of prose is unique 98.8% of the
time, but a selection of a single inline-code identifier — the most natural
thing to ask about — is unique only 68.6% of the time, because an agent names
the same symbol several times in one answer. A uniqueness rule would refuse
roughly a third of identifier selections, including the `INVALID_TABLE_PROPERTY`
case the interaction design shows.

Carrying the occurrence index instead costs one integer and makes the awkward
case exact: the reader highlighted the second mention, the footer appears under
the second mention, and a discussion on the first mention is a different passage
because the index is inside the hash. Normalization is versioned by
nothing: changing it silently orphans every stored `quoteHash`, so a change to
this list is a change to stored data and must be treated as one.

The discussion's context is bounded by `MAX_SEED_CHARACTERS = 1_000_000`, a
character budget standing in for the 300,000-token limit and declared on
`TaskDiscussion`. No provider reports a context window and ByteQuay does not
own hidden context content, so the bound is enforced before the session opens
and **fails loudly**. Nothing is silently shortened: a truncated premise
produces a confidently wrong answer, which is the one failure this feature
cannot afford. The refusal names the remedy — anchor the discussion at an
earlier turn.

No summarization step exists. Measured over the retained task event logs on one
developer machine, inside the accepted seven-day detailed-runtime retention
window, summing user messages plus root-thread assistant messages with
`messageKind=ANSWER, phase=FINAL`: a whole readable transcript is about 3,000
characters at the median and about 24,000 at the maximum, well under the budget.
A compactor would be dead code.

## 6. Isolation and containment

Five separations, each load-bearing:

1. **Synthetic resource identity.** The session allocates its own scratch
   runtime and provider directories under a generated identifier, so it cannot
   clobber the task's MCP and sandbox configuration files.
2. **Real task identity on the launch owner.** `AgentSessionContext.taskId`
   carries the **real** task id, and the supervised launch is tagged with a new
   `ProcessOwner.Kind.DISCUSSION` whose owner id is that same task id. This is a
   safety requirement, not a product one: a process tagged with an owner that
   resolves to nothing cannot be attributed after an unclean shutdown, and an
   unattributable process leaves the agent serving gate closed for every task,
   including the endpoint that would reopen it. The context field alone is not
   enough on the Claude lane, which tags a non-`textOnly` launch
   `CLAUDE_TURN`/`command.turnId()` and never reads `context.taskId()`; a
   discussion has no turn row, so that owner resolves to empty.
   `AgentStartupRecovery.ownerTask` gains a `DISCUSSION -> parseTaskId` branch
   and `StoredLaunchIntentRecorder` the matching `LaunchOwnerKind`. `CLI_PROBE`
   is unavailable: it may never cover a task-worktree-capable process.
3. **No registry entry.** The driver is opened directly, so the task's session
   registry neither sees nor closes the discussion, and the discussion cannot
   displace the task's own session.
4. **No turn row.** Nothing accepts a turn, so the unique active-turn column
   and the worktree mutation claim are untouched, and a discussion can run
   while the task runs. On the Codex lane this means a second App Server
   process on the same task id and worktree, which amends the accepted
   one-App-Server-per-task rule: at most one turn-bearing App Server plus at
   most one read-only discussion session per task. `docs/design/agent-service.md`
   §Runtime shape carries the same amendment.
5. **A discarding event sink.** Usage samples and every event that reaches the
   JSONL log, outbox or stream are written only from the task turn service's
   event route; the remaining metrics writes all require a turn row, which a
   discussion never creates. A private `event -> {}` sink never enters either,
   so no discussion byte reaches the task's log, outbox or stream.

Containment is enforced by the sandbox, not by the prompt. The read-only tool
set excludes every editing tool, and the sandbox must deny writes to both the
runtime directory and the worktree. Today `PermissionBroker.sandboxSettings`
denies only the runtime directory and leaves the worktree writable as the
sandbox's default root; adding the worktree to `denyWrite` under `readOnly` is
delivery step 1 work. The denial was observed against the installed CLI with
Bash enabled and auto-allowed — the configuration in which the sandbox is the
only barrier. This is a one-off manual observation, not a checked-in fixture:

| attempt | result |
| --- | --- |
| create a new file in the worktree | refused, `operation not permitted` |
| append to an existing tracked file | refused, `operation not permitted` |
| in-place `sed` edit | refused, `Operation not permitted` |
| read, `ls`, `cat` | permitted |

The working tree was unchanged afterwards. Approvals are denied unconditionally
by a `(request, timeout) -> completedFuture(deny())` handler, so a discussion
cannot produce an approval card. It registers no ByteQuay model tools; the
permission-prompt tool stays, and every approval it raises is denied.

Discussions run on the task's own lane. Nothing in this design is
provider-specific.

## 7. Persistence

One Hibernate-owned table, `task_discussion`, structurally the same as
`local_review_thread`: real columns for `id`, `task_id`, `turn_id`,
`quote_hash`, `payload`, and the version; the whole aggregate lives in the JSON
payload. There is no Flyway and no migration history.

A task holds at most `MAX_DISCUSSIONS_PER_TASK = 50`, checked in `open`. Under
this design each discussion owns one persisted provider conversation, so the
cap bounds provider-side transcripts on disk as well as rows; the analogous
review-thread cap of 500 is far more generous because a review thread starts no
conversation at all.

`(task_id, turn_id, quote_hash)` is unique. That constraint carries the product
rule — one discussion per passage — and removes the duplicate-submission race
without an idempotency table.

Deliberately not persisted: no running state, because nothing is running after a
restart and a durable running row would be a lie needing a recovery sweep; no
separate request or event records, because there is one canonical event
sequence per task and a discussion is not a second transcript.

In-flight is therefore in-memory: the service impl holds a
`Set<String>` of discussion ids with a question in flight, added before the
virtual thread starts and removed in its `finally`. A restart clears it, which
is correct — the answer thread is gone too.

Scratch resources are allocated **per question** and deleted in a `finally`,
the shape `DefaultAgentTaskTitleService.generate` already uses: allocate under
a random `TaskId`, and on the path where the provider may still be running,
retain the directory and log it rather than deleting under a live writer.
Nothing sweeps the runtime root, so a per-discussion allocation would be worse:
`TaskDiscussion` records no scratch identifier, `FileAgentResourceService`
deletes only by `TaskId`, and the directory would be unnameable for the rest of
the installation's life.

## 8. Surface

Three routes under the existing `/agent` prefix. There is no `/api` namespace in
this controller family, and no new event stream.

```
GET  /agent/tasks/{taskId}/discussions?workspaceId=
POST /agent/tasks/{taskId}/discussions?workspaceId=                   { turnId, quote, occurrence, question }
POST /agent/tasks/{taskId}/discussions/{id}/messages?workspaceId=     { question }
PATCH /agent/tasks/{taskId}/discussions/{id}?workspaceId=             { resolved, expectedVersion }
POST /agent/tasks/{taskId}/discussions/{id}/messages/{messageId}/retry?workspaceId=
```

Every route resolves the task through `taskService.getInteractive(workspaceId, id)`
as its first statement, which is what refuses an absent, internal or
other-workspace task. The commands carry no `taskId`; the path supplies it.

Both writes record the question and return immediately. Clients poll the read
route on the interval already used for local review threads.

The read route also reports whether a question is in flight, from the in-memory
set of section 7. Nothing about in-flight becomes persisted; it is only
projected. Askability is read from `AgentTaskDto.checkoutRemovedAt`, which
already exists and is the worktree fact sections 2 and 9 key on — not from the
task's status, and not from a new flag.

Refusals are explicit: the quote is not in that answer; the task has no live
worktree; a question is already in flight; the seed exceeds the budget.

The frontend adds a **Discussions** tab to the task side panel. The tab belongs
in the shared panel-tab slot rather than either header's fixed list, because the
panel header is replaced when the task has a pull request.

### 8.1 Where a footer sits

A reply-count footer sits directly under the **block** that contains the quoted
passage — the paragraph, list item, or code block — not at the end of the whole
answer. An answer with quotes in three paragraphs shows three footers, each
against its own paragraph, and two quotes in one paragraph stack as two rows
under that paragraph in the document order of their passages, which is the order
the panel's cards use; identical offsets break by creation time. The client
already resolves every anchor to an offset in order to place the footer at all,
so the ordering costs one comparator.

Placement is derived in the renderer and needs no stored block field.
`anchor.quote` is already the text as the reader saw it, so the client locates
it by plain substring search over the blocks' text content, taking the
`anchor.occurrence`-th match; the normalization of section 5.2 is a backend
concern, needed only because the backend holds markdown source.

The client sets `occurrence` when the discussion is created, by counting matches
before the selection's own start offset. Anchoring is therefore exact even when
the same identifier appears in five paragraphs.

This requires one change to `agents/markdown.tsx`, which today returns an opaque
`<div className="agt-md">` and discards its block list. It must instead expose
the blocks so `TurnView` can interleave footers between them. The blocks need no
durable identity: a block is addressed only for the duration of one render.

A block ordinal is deliberately **not** stored. The answer text is immutable, but
the renderer is not, and a markdown fix that splits or merges a block would
silently move every stored footer. Searching the rendered text degrades to the
right answer instead; a stored ordinal degrades to the wrong one.

When the quote cannot be located — the renderer changed, or the answer is not in
the loaded history window — the footer falls back to the end of the answer, and
when the answer itself is not loaded the discussion remains reachable in the
panel. A discussion is never dropped from the UI because its passage could not
be found.

A footer row carries the quote as a truncated label, the reply count, relative
time, and a resolved mark once the discussion is resolved. A discussion whose
newest question has no answer yet reads as answering rather than showing a
count.

### 8.2 The renderer's side

Recorded from the built surfaces, so a later change can tell what was deliberate.

```text
frontend/src/agents/discussions/
    contracts.ts          the DTO and the five requests, each field parsed and validated
    useTaskDiscussions.ts the polling hook and the five writes
    DiscussableAnswer.tsx one answer, its selection affordance, its footers
    DiscussionFooter.tsx  the reply-count rows, the label, the reply count
    DiscussionsPanel.tsx  the tab body: the card, the composers
```

`markdown.tsx` exports `renderMarkdownBlocks`, returning each block with the text
it reads as on screen; `renderMarkdown` wraps it, so every existing caller is
unchanged. Blocks carry no durable identity — a block is addressed only for the
duration of one render, for the same reason no block ordinal is stored.

Five bridge methods cross the IPC boundary — list, open, ask, resolve, retry —
and they are named in `AgentBridgePort`, so a fixture rendering the task screen
must supply them rather than failing at run time on a bridge that lacks them.

The hook polls every three seconds, because a discussion answers on a thread the
renderer cannot observe. Each write returns the updated discussion and is
accepted immediately, and a returned row is rejected when the local copy is
already newer, so a poll in flight cannot undo a question just sent. There is no
idempotency map: a discussion dispatches nothing through the task's turn
machinery, so there is no delivery to replay.

Two rules keep the transcript honest. An answer becomes discussable only when it
is whole — a streaming one has no completed turn to anchor to. And the reply-count
footer sits between blocks inside `.agt-md`, whose spacing is a set of
pair-by-pair rules; a rule for the boundary the footer creates carries two
classes deliberately, because the resets that zero those margins are more
specific than a lone child selector and a tidier general rule loses to them.

### 8.3 Decided client behaviour

These are settled and recorded so they are not reopened. All are presentation;
none changes a route or a stored field.

- **Label ordinal.** A footer or card label is the quote, truncated. When a
  sibling thread on the same turn carries an identical label, both gain a muted
  mention ordinal — `INVALID_TABLE_PROPERTY · 2nd mention` — from `occurrence`
  on the view. The transcript separates repeats by position, but only when they
  fall in different blocks, and the panel has no position at all.
- **Asking while a question is in flight.** The backend refusal stands. The
  client disables Ask while the view reports `pending` and keeps the draft, so
  the refusal is unreachable rather than displayed. No client-side queue: a
  timer over a backend refusal misfires whenever a poll misses the transition.
- **A quote spanning more than one block.** No block contains the whole passage,
  so the footer falls back to the end of the answer, with a muted label and the
  title `This passage spans more than one block`. It is an ordinary reading
  gesture, not an exotic case, so it must read as deliberate rather than as a
  placement bug. Selecting across two turns is refused at selection time; an
  anchor names one turn.
- **Refusals that cannot reach a card.** The three-character minimum is mirrored
  client-side by withholding the Ask affordance, so that refusal is never
  reached. The per-task cap and the over-budget seed surface in one error row on
  the inline composer, printing the backend message verbatim, because their
  remedies are instructions the reader must act on.
- **Overlapping selections** create two threads, and that is accepted for now:
  the labels are extended to the first differing word, which distinguishes them.
  A pre-submit overlap check is possible later — the client already resolves
  every anchor to an offset — but is not built.
- **A thread whose turn has left the loaded window** joins one group headed
  `Earlier in this task`, with its jump control disabled. No view field carries a
  turn ordinal or an answer synopsis for it.
- **No stale-premise caveat.** A thread anchored at an early turn is frozen at
  that premise by design. Nothing in the card says so; revisit only if a
  confidently out-of-date answer is actually reported.

## 9. Failure handling

| Situation | Behavior |
| --- | --- |
| Quote is not in the stored answer | Refuse. A client may not assert what the agent said. |
| The same passage is submitted twice | The existing discussion is returned and asked. |
| A question is already in flight | Refuse without losing the draft. |
| The provider session cannot be resumed | Discard the reference, re-seed with every prior message, ask once. |
| A question fails | Record the reason on that question; the discussion stays usable. |
| The application restarts mid-question | The question has neither an answer nor an error, the read route reports the discussion as not pending, and it renders as unanswered; asking again is the remedy. The provider process is not closed by the registry, so the session runner closes it in a `finally` and, when it cannot, retains its scratch directory and logs that the process may still be running. |
| The worktree has been removed | Asking is refused; every recorded message stays readable. |
| The seed exceeds the budget | Refuse and name an earlier turn as the remedy. |
| The quote is under three characters | Refuse as a stray selection. |
| The task already holds 50 discussions | Refuse. Nothing is evicted automatically. |

## 10. Safety invariants

1. A discussion never writes to the repository.
2. A discussion never appears in any task list, counter or aggregate.
3. No discussion content reaches the task's provider conversation, JSONL log,
   outbox or event stream.
4. A discussion never holds the worktree mutation claim.
5. Completing, failing or deleting a discussion never affects the task or
   another discussion.
6. Quoted text is stored and rendered as text.
7. A discussion process is always attributable to its real task.

## 11. Delivery order

Each step compiles and passes the project gates on its own.

1. **Read-only session axis.** The `readOnly` component, the read-only tool set
   and sandbox write denial on both lanes, and process ownership keyed by the
   real task id. This moves the containment boundary and is worth isolating.
2. **Persistence.** Domain records, the store port, the entity, repository and
   adapter. Nothing consumes them yet.
3. **Seed, service, routes.** The seed renderer, the service and its
   implementation, the view projection, the controller.
4. **Transcript and panel.** Selection affordance, reply-count footer,
   Discussions tab, the bridge methods and the polling hook.
5. **Failure pass.** The table in section 9, exercised end to end.

## 12. Acceptance criteria

1. Three discussions on one answer stay independent.
2. A discussion anchored at an early turn never sees later turns.
3. A follow-up reaches the agent without re-sending the seed, and a forced
   resume failure still answers, carrying every prior message.
4. A discussion answers while the task's own turn is running, and neither
   disturbs the other.
5. The driver is opened with `readOnly`, the editing tools are absent from the
   argv and the sandbox settings file names the worktree under `denyWrite` —
   asserted with a fake driver in the project gates. That the real CLI then
   refuses a write is manual local evidence, recorded the way the live
   compatibility fixture's results are, not a gate.
6. No `agent_task`, `agent_turn`, outbox, metrics or Recent row exists after a
   discussion runs, and no task surface lists it.
7. After the worktree is removed, the thread renders and asking is refused.
8. A restart preserves every discussion, its messages and its counts.
9. A quote crossing emphasis, inline code and a line wrap is accepted and
   anchored, and a quote that does not occur in the named answer is refused.
10. A quote appearing five times in one answer anchors to the appearance the
    reader highlighted, and a discussion on a different appearance of the same
    text is a separate thread.
11. A seed over budget is refused with an actionable message, never shortened,
    and the fifty-first discussion on a task is refused.
12. The first-question prompt and the re-seed prompt are produced by one
    renderer: given the same discussion, the re-seed output equals the original
    envelope with the intervening messages appended.
13. Mark done and Reopen round-trip through the view, and a stale
    `expectedVersion` is refused.
14. A restart mid-question leaves the thread rendering as unanswered rather than
    answering, and asking again succeeds.
15. Retrying a failed question re-runs the recorded question in place rather than
    appending a second copy of it.
16. Footers are placed against their own blocks: an answer with quotes in two
    different paragraphs renders one footer under each, two quotes in one
    paragraph render two rows under that paragraph in document order of their
    passages, and a quote that cannot be located falls back to the end of the
    answer rather than disappearing.
17. The reply-count footer shows the right count after N replies, the composer
    is disabled once asking is refused, and the Discussions tab renders a thread
    whose anchored answer has scrolled out of the loaded transcript window —
    each asserted in a Chromium fixture.
