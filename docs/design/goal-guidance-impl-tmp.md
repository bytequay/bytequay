# Agent guided goals implementation plan

Status: The first seven implementation slices are complete and the eighth is in
progress. The later items of Steps 3 to 6 below remain as listed.
The [Goal guidance design](goal-guidance.md) owns the product and behavior contract;
this checklist records delivery order, concrete actions and proof required.

The first useful release must help an unsure user clarify a goal, learn through a
course built from research, accept and start prepared work, and return to a
recorded result and next recommendation. The agent leads clarification, research,
discovery and ongoing assessment, and decides when to recommend; the user starts
or rejects each recommendation. The user need not invent prompts, manually
search for every ticket or ask for the next step after every result. The three
Plane takeaways are part of that loop, not separate architecture projects.

## Delivery order

| Step | User-visible outcome | Dependency |
| --- | --- | --- |
| 1 Correct existing progress | Failed or abandoned work no longer looks achieved | Independent |
| 2 Introduce outcome identity | One work item connects sources, task attempts and results | 1 |
| 3 Add agent led clarification and guidance | The Goal window asks useful questions, saves the Goal and shows recommendation cards | 2 |
| 4 Research, teach and discover work | Research runs, a course taught in the Goal window, issue/ticket and spec-check discovery and prepared tasks | 2 and 3 |
| 5 Record results, learn the user and reassess | Receipts, topic levels from lessons, work and private cards, and the next recommendation | 2 through 4 |
| 6 Complete organization and acceptance | Both Homes and the Goal panel agree about next work and progress | 1 through 5 |

Keep each behavior change independently reviewable and passing. Separate mechanical
extractions from functional changes. Do not ship a manual board as the completed
agent-guidance feature.

## First implementation slice

Deliver one complete agent-led path through the existing Goal surface before
adding more board controls. The floating and expanded view must share the
same guidance and conversation. The current storage and manual controls are a
foundation, not the intended finished interaction.

The slice follows the design's reference journey, thinly:

1. Replace the current empty/name-first opening with "what do you want to
   achieve?", contextual directions and a free-text answer. A broad existing
   title receives the same help, and an answer starts the conversation directly.
2. Extend Goal replies with the current assessment, one clarification with
   suggested answers, including what the user already knows, a proposed path and
   a supported next action. Persist answers, input revision, starting knowledge
   and decisions through the existing Goal service, and save the Goal.
3. Show recommendation cards under the Goal control, with Start and Not now and
   agent-chosen timing. One research card starts a research run that saves
   knowledge notes with sources from the workspace and its Dependency workspace.
4. Propose a course from those notes, save accepted lessons as ordered Learn
   items, and open one lesson in the Goal window with its exercise and check.
   Make guidance the default panel content; keep the floating control, pinning
   and the draggable, resizable window.
5. Recommend one practice card that opens the prepared composer through
   canonical task admission, and connect its task result to a durable receipt
   and the next recommendation. One private-card question must change a topic
   level and, through it, that recommendation.

This slice spans Steps 3 through 5. A scripted conversation, attractive static
panel, renamed manual buttons or an unconnected chat box does not complete it.

Progress: items 1 and 2 are implemented. The Guide view is the panel's default
and opens on "what do you want to achieve?" with three local directions; free
text or a suggested answer starts the Goal. A Goal without a conversation gets
the same question rather than its title, with the focused answer field under it.
New goal in the switcher asks it over the current Goal, Cancel goal archives a
user-started Goal and returns to it, and the title renames in place. Durable Goal exchanges record the
message, input revision, model, validated reply or failure. The reply carries an
assessment, one question with suggested answers and an optional definition;
accepting it saves the title, objective, success definition, starting knowledge
and constraints behind a revision check. A proposed path arrives with the course
in item 4.

Item 3 is implemented. Accepting a definition tells the agent, whose answer may
float a research card under the Goal control; Start runs one read-only research
turn on the shared restricted-run slot, reading the workspace and its Dependency
checkouts, and `goal_knowledge_save` stores sourced notes. A finished run tells
the agent what it saved. Stale-note marking, the repository-memory Remember offer
and verification that a cited page was read in the run remain open.

Item 4 is implemented: the agent proposes a course from the notes, and accepting
it saves each lesson as an ordered Learn work item of the Goal, grouped by part.
A lesson opens in the expanded Goal window, written once from the notes with its
exercise and check; questions asked there carry the lesson, and Mark lesson done
accepts its work item. Lesson cards open the lesson. Stale lessons and course
changes remain open.

Item 5 is implemented: a Practice card names an open issue the workspace holds,
and Start opens that issue's composer with the prepared instruction; the task
starts only when the user sends it. A completed turn of a Goal's task leaves a
receipt in its work item's history, once per turn, and tells the agent. Each
request carries the user's newest private-card questions and the topic levels;
a question cited as evidence changes a level, and the levels steer the next
recommendation. Receipts for turns that ended while ByteQuay was stopped are
recovered by the second slice; a model run against real data is still to be done.
## Second implementation slice

Carry the reference journey from practice to a recorded result, and add the
other two discovery sources. The first slice's rules hold: the agent picks the
moment, the user starts or dismisses each card, and nothing writes to GitHub or
Jira without a direct user action.

1. A Practice card becomes Goal work. Start finds or creates the Develop work
   item for the card's source on the Goal, with the card's scope as its
   acceptance criteria and merged delivery as its rule, then opens the source's
   composer with the prepared instruction. The task the user sends becomes that
   item's attempt through the existing admission. The card stays started until
   its work item is Done or Dropped; only then may another Practice card float,
   and the agent hears the decision.
2. Results arrive by themselves. A turn's receipt also links the pull request
   the task published, and a linked pull request that merges records one
   receipt and tells the agent, which may suggest accepting the result.
3. Receipts missed while ByteQuay was stopped are recorded at startup, before
   retention can remove the turn's answer.
4. Discovery reads issues and mapped Jira tickets. A guidance request carries up
   to 100 compact candidates: open issues and the Jira tickets mapped to the
   workspace, without sources that already have work or a dismissed card, the
   ones nearest the Goal's topics and notes first, with their coverage stated. A
   Practice card names one of those candidates; any other reference is refused.
5. Spec checks find what nobody reported. The agent may recommend a spec check
   naming a module, its pages and a reproduction count. Start runs the existing
   check and Adjust opens its drawer filled in; the finished check tells the
   agent.
6. A reproduced gap becomes Goal work. The Goal window lists each reproduced gap
   with its draft issue, which only the user files; adding one makes it a Develop
   work item. No other spec check is recommended while a gap of the last one is
   neither done, dropped nor dismissed.

Progress: item 1 is implemented. Start on a Practice card adopts its issue as the
Goal's Develop work item, so the task sent from the composer joins it, and the
Goal window shows the practice with its task, or the composer until it is sent.
Accepting, dropping or reopening Goal work tells the agent, and a decided practice
settles its card.

Item 2 is implemented: a turn's receipt links the pull request its task
published, list refreshes refresh each open pull request of the Goals' open
work, and a merge records one receipt and tells the agent; the planner sees each
work item's links and the Goal window offers Review result.

Item 3 is implemented: at startup, before the cleanup that can remove a turn's
answer, completed turns of the Goals' work without a receipt are recorded, and
the agent hears once per work item what it missed.

Item 4 is implemented: each guidance request carries up to 100 candidates from
the 500 open issues updated last and the cached Jira tickets mapped to the
workspace, without worked, tasked, Goal or dismissed sources, ranked by the words
they share with the Goal, with the coverage read. A Practice card names one of
them, a ticket kept by its stable reference, and Start opens the ticket's
composer with the instruction filled in.

Item 5 is implemented: the planner sees the fifteen modules whose code cites a
specification most, and a Spec check card names one with its pages and
reproduction count. Start runs the existing check, Adjust opens the drawer
filled in, and the check's end settles the card and tells the agent.

Item 6 is implemented: the Goal window lists the last check's reproduced,
undismissed gaps with File issue and Add to goal, a gap becoming a Develop work
item once whichever check of its module reported it, and no other spec check
is recommended while one is open. The second slice is complete; a live model run
against real data is still to be done.

Done when: a Practice card leads to a task whose merged pull request is recorded
on the Goal and moves the topic it practiced, after which a new card can float;
an issue and a mapped Jira ticket can each be recommended and started; and a
spec-check card's reproduced gap can become Goal work.

## Third implementation slice

Let the user see where a Goal stands and steer its agent, as live use asked for:
a reasoning model takes minutes to answer, and the panel did not say what the
Goal was waiting on.

1. Where the Goal stands. One plain-language line under the title, a projection
   of local facts: clarifying, researching, learning a lesson, practicing on
   work, checking a module, the user's decision (a proposal, a course, a merged
   result) or ready to conclude. The Goal control shows the number of decisions
   waiting instead of nothing.
2. Steer a slow reply. The pending reply shows how long the agent has been
   working, and Stop cancels the provider turn; the message stays retryable.
3. Choose the Goal's model. The Goal window names the model its agent uses and
   lets the user pick another configured model and effort for this Goal; the
   workspace Development model stays the default, and each request records the
   model it used.
4. Pause guidance and Update now. A paused Goal receives no automatic events;
   the user can still ask. Update now asks the agent to reassess from the
   current facts.
5. Failures and reviews first. A failed or interrupted turn of a Goal's task and
   a review that requests changes on its work's pull request tell the agent,
   which leads with recovery before new work.

Progress: item 1 is implemented. The line under the title reads, in order, a
pending or failed reply, the user's decisions, clarifying, a started card, ready
to conclude, the next lesson or the open work; the Goal control counts the
proposals, courses, merged results and unadded gaps waiting for the user.

Item 2 is implemented: a pending reply counts its minutes and seconds, and Stop
fails the message as stopped at once and cancels the provider turn under the
exchange's key, before or after it started; Ask again sends it once more.

Item 3 is implemented: the Guide and Work row names the Goal's model, and the
user picks another configured model and effort or returns to the workspace
default. The choice is a per-Goal setting read when each reply or lesson starts.

Item 4 is implemented: a paused Goal is told no automatic event, its facts are
still recorded, and the head shows Paused; Update now sends an automatic
exchange asking the agent to reassess, paused or not, refused while a reply is
pending.

Item 5 is implemented: a failed or interrupted turn of a Goal's task is recorded
and told with its reason, at once or at startup; a review whose reviewer's latest
verdict on a linked open pull request requests changes is recorded and told once
per review from the stored detail; and the agent's instructions put recovery
first.

Done when: the panel says what the Goal waits on, a slow reply can be stopped
and retried, a Goal can use its own model, guidance can pause, and a failed
attempt or a requested change brings recovery guidance without the user asking.

## Fourth implementation slice

Keep the path current as work moves: a practice card floats only for a source
that is still open and unclaimed, the course changes with what the user learns,
and a busy task no longer costs a Goal request per turn.

1. Ready practice only. Before a Practice card floats, ByteQuay reads its source
   again: an issue's state and the pull requests linked to close it through one
   GitHub read, a Jira ticket through the existing ticket refresh. A closed
   issue, an issue with an open or merged linked pull request, or a ticket in a
   done status is set aside as dismissed, so the candidates leave it out, and
   the agent is told why and asked for another. When the read fails, the card
   floats from the cached candidate as before.
2. Course changes. With a course accepted, the agent may propose a revised
   course with a one-line reason: each lesson keeps an existing lesson by its id
   or is new, and every finished lesson stays. The Goal window shows the
   revision against the current course; accepting it creates the new lessons as
   Learn work items, saves the new parts and order, and drops each removed
   unfinished lesson with the reason. Nothing changes until the user accepts. A
   revision that leaves out a finished lesson or names an unknown one fails the
   reply, which the user can ask again.
3. Events wait instead of vanishing. Background events, such as task turns,
   merges, reviews and finished research runs and checks, are held while the
   agent answers and until five minutes after the Goal's last automatic
   exchange, then told together in one exchange. The user's decisions, such as
   an accepted definition or course, a finished lesson or decided work, reach
   the agent as soon as it is free; the user's own messages and Update now are
   never held. Held events live in memory: after a restart the next request
   still reads the recorded facts. A paused Goal holds nothing.

Progress: item 1 is implemented. A Practice card's issue is read again through
one GraphQL request for its state and the pull requests linked to close it, a
ticket through the Jira refresh; an unready source is saved as a dismissed card
and the agent is told why and asked for another.

Item 2 is implemented: a later reply's course revises the accepted one; lessons
keep their work items by ID, new ones get deterministic IDs, a left-out
unfinished lesson is dropped with the revision's reason, and the Goal window
shows the revision with its new and leaving lessons. A revision that names an
unknown lesson or leaves out a finished one fails the reply.

Item 3 is implemented: background events are held per Goal in memory and
flushed as one numbered exchange, the newest six shown, when the agent is free
and five minutes have passed since the last automatic exchange, waking itself
at the window's end; a decision flushes as soon as the agent is free, Update now
takes the held events, and an operation is told once. The fourth slice is
complete.

Done when: no practice card floats for a closed or already fixed issue or a done
ticket; an accepted course can be revised without losing finished lessons; and
a task that ends ten turns in a minute brings at most two Goal exchanges, while
an acceptance made during an answer is still told.

## Fifth implementation slice

Make results last and keep what the Goal knows honest: finished work keeps a
summary, investigations become work items, a filed gap issue joins its work, and
notes say when the code they cite moved.

1. Summaries of finished work. When Goal work other than a lesson is accepted
   as done, a tool-free request on the Goal's model summarizes its history: what
   changed, the evidence and what is left open. The summary is saved once in the
   item's history, and the agent's next request reads it. A failed summary
   leaves the facts as they are, and the Work view offers Summarize again.
2. Investigation cards. The agent may recommend an investigation, such as a
   specification feature the code may lack, as a card naming its scope. Start
   creates one Investigate work item for the card under an accepted result, so
   starting it twice creates one item, and opens it in the Work view, where the
   user links a task or accepts the result.
3. A filed gap's issue joins its work. Once the user files a gap's drafted issue
   and the issue list refreshes, the gap row shows the open issue the user filed
   with that exact title after the check, with Link to work. Linking makes the
   gap's work item own the issue, so a task started from the issue joins it.
4. Stale notes. A note citing a repository file is stale when the file changed
   between the commit the research read and the monitored head of its workspace
   or Dependency. The Guide view marks it, and the agent sees which notes are
   stale and may recommend a refresh. Pages are not checked.

Progress: item 1 is implemented. Accepting Goal work other than a lesson queues
one summary request after the agent hears the decision, keyed by the acceptance;
the summary is a SUMMARY event in the item's history, shown above its sources
and sent with the work item to the planner. A failed request records nothing.

Item 3 is implemented: a gap with a draft reads the 100 issues updated last for
the user's open issue with the draft's exact title opened after the check, and
offers it until the gap's work links it; adopting the gap, again if need be,
links the issue through the work item's ordinary link with its revision.

Item 4 is implemented: notes are read with a stale flag from one git diff per
repository and commit against the monitored head of the workspace or a
Dependency, cached per commit pair and skipped when no note cites a file; the
Guide view marks stale notes and the planner sends their topics marked stale.

Item 2 is implemented: an INVESTIGATE card names a scope only; Start creates
the Investigate work item with an ID derived from the card, under an accepted
result, and the started card opens that work. The fifth slice is complete.

Done when: an accepted result carries a saved summary; an investigation card
becomes one work item however often it is started; a filed gap issue links to
the gap's work in one click; and a moved file marks the notes that cite it.

## Sixth implementation slice

Let one outcome take several explicit attempts: an ended task no longer blocks
starting over on the same issue or ticket, and every attempt stays on the same
work item.

1. Another attempt on an issue. The issue's current task still opens by
   default. Once that task has ended and no delivery runs for it, Start another
   attempt in the Goal's Work view opens the issue composer for a new attempt.
   Sending it creates a new task that becomes the issue's task, while the ended
   one keeps its source and stays the work item's earlier attempt. Admission
   checks the ended state under the issue's lock, and the new task's identity
   makes a retried request create nothing twice.
2. Another attempt on a Jira ticket, the same way through the ticket composer.

Progress: item 1 is implemented. The issue task row keeps the canonical source
slot for the current attempt and a retired attempt's slot adds its task ID;
a create request naming the ended task it replaces is admitted under the
issue's lock, the old row is retired and the new one inserted in the task's
creation transaction, and the work item records both attempts.

Item 2 is implemented the same way for a ticket's task row under the ticket's
lock, through the ticket composer over the ended task. The sixth slice is
complete.

Done when: a failed issue task can be followed by a new attempt without deleting
it, both tasks show on the one work item, and an open task cannot be replaced.

## Seventh implementation slice

Put a Goal's work in order: the user sees what to do next and what waits on
what, and the agent's next card follows the same order.

1. Priority, order and blockers. A work item has a High, Normal or Low local
   priority, Normal until the user sets one, a place in its Goal's order and
   blockers among the same Goal's work. New work joins the end of the order, so
   an accepted course keeps its lesson order, and the user moves open work up or
   down. A blocker cannot be the item itself, another Goal's work or part of a
   cycle. Finishing a blocker makes its dependents ready without starting them;
   dropping work that open items wait on requires removing those dependencies in
   the same decision, which the dependents' history records.
2. Next, Working, Blocked and Done views. The Work view opens on Next: planned
   work with no open blocker, by priority, then work that unblocks others, then
   order, each saying why it is there. Blocked lists work waiting on an open
   blocker or on a source that reports itself blocked, with what it waits on.
   A ticket's own Jira priority shows beside its link, never mapped. Filters
   narrow each view by priority and by source.
3. The agent follows the order. Each request carries every item's priority,
   place and blockers; the planner prefers ready work that unblocks an accepted
   step, says why, and recommends nothing blocked.

Progress: item 1 is implemented. A work item saves its priority, its rank in the
Goal's order and its blockers; new work takes the next rank. Order in the work
details sets the priority, moves open work earlier or later by saving the whole
Goal's order, and adds or removes what the work waits on, refusing itself, other
Goals' work and cycles. Dropping work that open work waits on is refused unless
the decision also releases it, which the released work's history records, and
work with dependencies cannot leave its Goal.

Items 2 and 3 are implemented: the Work view's Next, Working, Blocked and Done
views partition the Goal's work, Next ordered by priority, then the open work
waiting on it, then rank, with priority and source filters and the board's
four columns; a goal item carries its source's own priority, read from Jira's
field. The planner sends work items in rank order with priority and blockers
and is told to recommend ready work only, and a lesson card naming a lesson
that waits on open work is dropped. The seventh slice is complete.

Done when: the user can make one item High, block another on it and see the
blocked one leave Next until the first is done; dropping that blocker asks to
remove the dependency; and the agent's next recommendation names ready work.

## Eighth implementation slice

Make the Goal's decisions clear: every proposal can be accepted, adjusted or
skipped, what the user already knows sets the first topic levels, an older
proposal never overrides a newer decision, and the Goal control asks for
attention once.

1. Accept, Adjust or Skip. A proposed goal or course also offers Adjust, which
   puts "Adjust it: " in the reply field and focuses it, and Skip, which tells
   the agent the user passes on it for now; the next answer replaces the
   proposal either way.
2. Starting knowledge sets the first levels. Once the user accepts a definition
   that says what they already know, the agent may set topic levels citing that
   definition; a level the user set still wins.
3. Older proposals stay older. A course proposed before the accepted course
   cannot replace it.
4. One attention cue. The Goal control shows one count, the work needing the
   user and the decisions waiting together, naming both on hover, and an empty
   Goal's Work view gives directions instead of empty counts.

Done when: a proposal can be adjusted or skipped without accepting it, a stated
starting knowledge sets levels the user can correct, an older course cannot
replace a newer one, and the Goal control shows one count.

The following steps retain the full identity, source, recovery and verification
requirements.

## Step 1 Correct existing progress

The progress correction ships directly with work-item progress; there is no
intermediate reference-count percentage to maintain.

- [x] Separate ended tasks, unmerged PR closures and unknown source data from Done.
- [x] Replace missing-open-cache-is-closed with Unknown; reads stay local.
- [x] Update the Goal contracts, summary, panel and layer together. Count work
  items, show linked PR activity separately and exclude Dropped from the denominator.
- [x] Cover failed/completed tasks, closed and merged PRs, missing issues, partial
  delivery and one-outcome counting in backend and frontend checks.

## Step 2 Introduce one work item per outcome

- [x] Add Work item, stable source/task links, accepted source snapshots and
  decision history in `core`, with SQLite persistence and identity uniqueness.
- [x] Add local create/bind commands, retry identity and revision checks. Goal
  membership is separate from execution parent/child relationships.
- [x] Reserve source/task ownership during existing GitHub/Jira task creation,
  in its existing transaction before the first turn. Refuse Done, Dropped or
  other-workspace work before the worktree, and keep a Jira ticket's assignment
  with its work item's workspace.
- [x] Add Planned, Working, Done and Dropped. Explicit acceptance records criteria,
  evidence and scope coverage; merged delivery additionally needs a merged PR and
  no open or unavailable linked PR, recording closed unmerged PRs as abandoned.
- [x] Replace reference rows with work-item cards and linked evidence. Create
  source-free work, edit scope, link existing tasks, reopen, detach and move Goals.
- [x] Keep a source's Goal on unmarking while it holds work, archive it when
  empty, restore the kept Goal when the source is marked again, and retain
  work/history when archiving.
- [x] Inspect development data before changing the Goal mapping: no Goals or
  source-task rows existed. No runtime data was changed or reset.
- [x] Add explicit another-attempt admission to GitHub/Jira stores and both
  services. Keep opening the current task by default; require the previous attempt
  to be terminal with delivery settled before a new task can be admitted.
- [ ] Add unique accepted-proposal mappings when Goal proposals arrive in Step 3.
- [ ] Add explicit accepted deliverable/base/criteria mappings and automatic
  merged-delivery evaluation with outcome harvesting in Step 5. The current
  result-acceptance action always requires the user's scope confirmation.
- [x] Add blocker-aware moves when same-Goal dependencies arrive in Step 6:
  work with blockers or dependents cannot leave its Goal.

Checks cover SQLite revision conflicts and ownership rollback, source cache loss,
reference grouping, abandoned and open PRs at acceptance, command retry, reopen,
repeated source marking, archival and moving work without losing evidence.
SQLite-backed launch checks prove one work item per issue attempt and refusal of
Dropped or other-workspace work before the source fetch; a Jira check proves
reassignment is refused while a work item owns the ticket. A Chromium journey exercises a
failed result save, the same-operation retry, reopening and detached history.
The remaining unchecked items are not represented as implemented behavior.

Verification on the committed tree: backend `mvn verify` (3,396 tests),
frontend lint and TypeScript checks, all 1,421 frontend tests including the
work-item and Goal window Chromium journeys, and desktop packaging passed.

## Step 3 Add the Goal conversation and proposals

- [ ] Add durable Goal exchanges and versioned proposals beside the existing Goal
  records. Save user input, objective, constraints, acceptance decisions and
  response coverage; no task transcript is used as the Goal's database.
- [x] Add a bounded Goal planner in `app` using the existing provider SPI and
  `TEXT_ONLY` profile. Follow the naming-helper resource and verified-close
  pattern; register request/helper ownership for cancellation and restart.
- [x] Use the workspace Development model default, visible per-Goal override and
  readiness checks. Prove Codex and Claude tool-free behavior with fixtures.
- [x] Build bounded context from canonical plan/results and recent conversation.
  Retain full user-visible history and pinned decisions; do not silently truncate
  constraints or depend on provider session memory.
- [ ] Validate typed model replies, evidence IDs, reference ownership, proposed
  dependency cycles and size limits before saving. Invalid output leaves the
  previous plan intact and produces a retryable error.
- [x] Include current assessment, one consequential clarification, short suggested
  answers and a prepared supported action in that same reply. Reuse known
  context; do not repeat answered questions or infer unprovided preferences.
- [ ] Ask what the user already knows before proposing a course, and save the
  accepted starting knowledge with the Goal and as initial topic levels.
- [x] Add recommendation records and cards under the Goal control: kind, title,
  reason, Start and Not now, at most three, newest first. Nothing starts without
  Start, a dismissed card does not return unchanged, and cards of one kind do
  not stack.
- [ ] Extend `GoalController`, main/preload bridge and `frontend/src/goals` with
  suggested-answer and free-text messages, editable suggested title and Accept /
  Adjust / Skip proposal controls. An existing vague Goal receives clarification
  without requiring a separate Plan with agent prompt. Manual entry stays secondary.
- [ ] Refactor `GoalPanel` around current situation, one primary agent intervention,
  compact path and recent result. Empty Goals show helpful directions, not 0/0,
  empty board columns and source-search instructions. Preserve current app styling.
- [ ] Preserve `GoalLayer` pill, pinning, floating window and expansion. Show one
  meaningful attention cue and keep drafts/selection stable across modes. Keep
  one panel mounted across resizing and expansion, hidden from interaction when closed.
- [x] Keep Accept plan separate from Start. Draft proposals do not affect progress.

Evidence for the checked items: `TestGoalConversationService` (a slow reply
stopped, restart settlement, cards that float only once the Goal is defined and
never stack or repeat a dismissed one, course acceptance), `TestTextOnlyHelper`,
`TestModelSettingsService` for the Goal's own model, the Codex and Claude driver
text-only session tests, `TestGoalPlanner` and the `goalGuideBrowser` and
`goalWindowBrowser` journeys. Still partial: response coverage is not saved;
replies cannot propose dependencies, so there are no proposed cycles to refuse;
starting knowledge does not seed topic levels; a proposal offers Accept without
Adjust or Skip; an empty Goal's switcher and Work view still show empty counts
rather than directions; and the pill shows two counts, not one attention cue.

Done when: opening an empty Goal or one named “Iceberg connector expertise” offers
useful directions; a suggested answer can lead to a saved goal definition and
proposed path without typing a prompt. A free-text request works equally well.
Neither flow allocates an execution task, Git branch, worktree or GitHub write.
An edited or duplicated acceptance cannot overwrite newer decisions or duplicate
work items. Closing and reopening the app retains the conversation and plan.

## Step 4 Research, teach and discover work

- [ ] Add the approved research task kind: noninteractive, read-only, started
  only from a card, reading the Goal's workspace, its Dependency workspaces and
  the pages the card names, and saving notes through one fixed task-bound tool.
- [ ] Store knowledge notes with topic, sources and staleness. Mark notes stale
  when a cited specification or path moves; offer a repository-memory Remember
  through the existing capture policy.
- [x] Propose a course of parts and lessons from the notes and the starting
  knowledge. Accepted lessons become ordered Learn items with a milestone; a
  tool-free request writes and saves each lesson's explanation, exercise and check.
- [ ] Show the open lesson in the expanded Goal window beside the conversation.
  Questions there belong to the lesson; Done stays the user's decision.
- [x] Recommend spec checks as cards naming the module, pages and reproduction
  count. Start runs the existing check and Adjust opens its drawer; a reproduced
  gap becomes a proposed work item with its draft issue, which the user files.
- [x] Build a Goal context reader over existing issue, Jira, PR and task stores.
  Use exact monitored/mapped workspace scope and identify incomplete coverage.
- [ ] Add deterministic eligibility and relevance filtering before a bounded model
  shortlist. Preserve high-priority items before display limits. Never suppress
  uncertain duplicates or possible fixes as if they were confirmed.
- [ ] Refresh shortlisted sources through existing services. Add only the missing
  duplicate/closing-PR evidence reads needed for the readiness decision. Record
  source revision, freshness and skipped coverage.
- [ ] Supply at most 100 compact candidates, inspect at most five selected sources
  and display at most three recommendations. Reject model-invented IDs and URLs.
  Cache conclusions by input revision; unchanged candidates need no new model call.
- [ ] Include reason, scope, prerequisites, completion criteria and uncertainty
  on each proposal. Allow source-free learning/investigation items.
- [ ] Trigger discovery as the next useful step of an active guidance request or
  meaningful source/work change. Users need not open search and formulate a new
  query. Explain insufficient coverage instead of presenting arbitrary candidates.
- [x] Where code has not been inspected, recommend a research run or an
  investigation card. Pass its result back to the Goal; never label a
  metadata-only assessment as code verification.
- [ ] Extend canonical task creation with an optional work-item reference and the
  accepted Goal context. Preserve current composer defaults, source refresh,
  readiness, worktree selection, publication authority and replay behavior.
- [ ] Prepare the execution request from accepted scope, evidence and constraints.
  Start opens the existing composer ready for review and submission; the user
  need not rewrite the agent's proposed task in another input.
- [ ] At Start, recheck source identity, role, state, work-item revision and linked
  work. Open an existing task when appropriate; preserve the draft on failure.

Evidence for the checked items: `TestGoalLessonService`, `TestGoalSpecChecks`,
`TestGoalCandidates`, `TestGoalResearchService` and the investigation-card test
in `TestGoalConversationService`. Still partial: a research run is confined by
its prompt and its own disposable worktree, not by a read-only tool policy;
cited pages are never checked for staleness and no Remember is offered; a lesson
replaces the conversation instead of sitting beside it; candidates are not kept
by priority before the limit, and an issue with an open linked pull request is
set aside as if it were fixed; shortlisted sources record no duplicate read,
revision or skipped coverage; research pages accept any https address;
conclusions are not cached by input revision, so each event costs a request;
cards carry no prerequisites, completion criteria or uncertainty; source
changes do not trigger discovery; a task carries no explicit work-item reference
or Goal context; and Start rechecks neither the work item's revision nor the
source.

Done when: a research card saves sourced notes, an accepted course opens a lesson
in the Goal window, and a spec-check card turns a reproduced gap into a proposed
work item. One actual GitHub issue and one mapped Jira ticket can each be found,
explained, accepted and started through the existing task UI. A changed or
already-started source cannot slip through stale recommendations.

## Step 5 Record outcomes, learn the user and propose the next step

- [ ] Add topic levels (new, learning, practiced, strong) with their evidence:
  lessons finished, check answers, questions and merged work. The agent proposes
  changes with evidence; a user correction wins.
- [ ] Read the user's private cards from `task_discussion`, `local_review_thread`
  and `agent_review_message` in every workspace, locally. Select those on the
  Goal's topics, cite them in assessments and treat their text as reference data.
- [x] Pace recommendations: practice an area until it is practiced, then advance
  the course; propose the next spec check only after the last one's gaps are
  resolved or dropped; put blockers, failures and reviews first.
- [ ] Connect task settlement and existing source refresh to idempotent factual
  receipts. Capture final result/check evidence before runtime retention removes
  it. Reconcile missed receipts after restart and guard linked-task retention or
  deletion until capture succeeds. Preserve missing-evidence and agent-reported
  distinctions.
- [x] Verify selected delivery rules through application facts. Provide explicit
  result acceptance for learning and investigation, and coverage review where a
  merge does not establish the entire accepted scope.
- [ ] Generate a concise summary from those receipts: what changed, evidence,
  insights, unresolved work and next recommendation. Saving a summary cannot
  change completion state or accepted scope.
- [ ] Reassess the next intervention from real status: running, failed, blocked,
  waiting for review, result ready or milestone delivered. Prefer recovery and
  outstanding user decisions over fresh discovery. A meaningful change updates
  guidance without “what next?” or manual refresh.
- [ ] Propose specific plan differences when scope/order/dependencies should
  change, with their reason. Refresh recommendations within accepted constraints
  automatically; preserve accepted decisions until the user changes them.
- [ ] Reconsider after meaningful linked work/source changes. Persist the input
  fingerprint, coalesce events and respect quiet hours and the design's call
  limits. Add Pause guidance and Update now; panel polling remains local.
- [ ] Use request and input revision checks to discard stale proposed changes.
  An interrupted request stays retryable; do not launch or resend work on startup.
- [ ] Save Goal insights locally with citations. Route a user-selected Remember
  action through the existing repository-memory policy, without a new memory engine.
- [ ] Exercise archived Goal, task deletion, log expiry, summary failure and
  uncertain provider cleanup. Preserve receipts and user decisions in every case.

Evidence for the checked items: the spec-check and blocked-lesson card tests in
`TestGoalConversationService` and the delivery tests in `TestWorkItemService`.
Still partial: check answers are not captured, and Goal or lesson questions
cannot be cited; private cards are the twenty newest rather than those on the
Goal's topics; task deletion and runtime cleanup do not wait for a receipt;
summaries carry no insights or next recommendation; blocker changes, task starts
and finished milestones are not reported to the agent; plan differences cover
the course only; there is no persisted input fingerprint or quiet hours; a
course accepted from an older revision can replace a newer one; there is no
Remember action; and task deletion, log expiry and uncertain provider cleanup are
not exercised.

Done when: completing or merging accepted work records one supported outcome,
updates the panel and produces a useful next proposal without manual bookkeeping.
A private-card question and a merged fix each move the topic level they touch,
and the next card follows from it.
Repeated refreshes do not repeat model calls or notifications. A restart and
runtime-log cleanup do not erase the history.

## Step 6 Complete organization and verify the whole loop

This finishes the clear-organization takeaway using the current surfaces.

- [x] Add local priority, accepted order and same-Goal `blockedBy`; reject cycles
  and keep blockers separate from execution failures. Dropping a blocker requires
  an explicit dependency decision.
- [x] Extend the existing list/board with Next, Working, Blocked and Done views.
  Show source priority separately and explain the recommended ordering.
- [ ] Feed global and workspace Needs attention from one work-item projection.
  Deduplicate source/recommendation cards for the same action; preserve urgent
  execution attention, Ack and Snooze.
- [ ] Add results/history display with evidence links, scope changes, reopenings
  and factual coverage. Do not count retries, comments or PRs as extra outcomes.
- [ ] Run browser journeys at normal and narrow panel sizes, including keyboard
  use, background updates, preserved drafts, stale responses and offline recovery.
- [ ] Include an unsure-user journey using suggested answers only, a vague-title
  clarification, prepared task launch with no new prompt, automatic failure
  recovery guidance and proposed replanning after a blocker changes. Check visual
  hierarchy in floating and expanded view; the next useful action must be readable
  before statistics or board management. Opening the panel alone adds no model call.
- [ ] Include the reference journey: an Iceberg expertise Goal clarified from a
  vague answer, research and a fitted course, a lesson in the Goal window, a
  Delta spec-check card reproducing a #31252-style gap, and a practice card that
  ends in a merged pull request recorded on the Goal.
- [ ] Run `mvn verify`; frontend lint, `npx tsc --noEmit`, tests and packaging.
  Complete opt-in live planning/source handoff smoke checks for supported lanes
  and report live-service limits separately from fixture results.
- [ ] Update the canonical agent, architecture, issue and Jira design sections to
  the implemented behavior and remove superseded descriptions. Mark checklist
  entries complete only with their actual evidence.

Done when: the full Goal conversation → research → course → lesson → proposal →
accepted work → task → verified result → summary → next proposal journey works,
and all panels agree about it.

## Later additions

Only after usage establishes a need: cross-workspace Goals, scheduled discovery
while the app is not running, longer-term trend reports with adequate recorded
history, custom saved views, cycles/modules, YAML import/export and external
agent integrations. None is necessary for the first complete guidance loop.
