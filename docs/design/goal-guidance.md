# Agent guided goals

Status: The first slice is implemented: work items, the Goal conversation,
recommendation cards, research runs, courses and lessons, practice cards that
open a prepared composer, task receipts and the learner profile. The second
slice so far makes a started practice card Goal work, records a task's pull
request, its merge and the receipts missed while ByteQuay was stopped, and finds
practice among open issues and mapped Jira tickets, recommends spec checks and
turns a reproduced gap into Goal work. The later steps of the plan remain. This document specifies the Goal conversation, research, courses,
discovery, guidance and durable progress panel, and three takeaways from the
Plane comparison. The [implementation plan](goal-guidance-impl-tmp.md) lists
actionable changes and acceptance checks.

The user describes an outcome to an agent, often a large and vague one such as
becoming an expert in one connector. ByteQuay helps clarify it, researches what
the outcome requires, teaches the user through a course built from that research,
finds or discovers suitable work, executes accepted work through the existing
task service, and remembers both the results and what the user has learned.
Opening the Goal later should answer: where are we going, what should I learn or
do next, what is blocked, and what have we actually achieved?

This proposal extends the implemented [issue development release](issue-development-plan.md)
and refines the later [work planning roadmap](issue-development.md). It does not
claim that the roadmap is already implemented. The [agent service](agent-service.md),
[development delivery](development-delivery.md), [Jira integration](jira-integration.md)
and [repository memory](repository-memory.md) retain their execution, publication,
source and memory boundaries.

## Current implementation and the missing behavior

Goals now group durable work items. Each item has an identity, kind, acceptance
criteria, completion rule, revision, supporting links and retained decisions.
The panel provides creation, scope editing, linking, explicit result acceptance,
dropping, reopening and moving between Goals. **Goal** means the guided outcome,
conversation, research and learning service. **Epic** means a large scope issue or
ticket classified locally as a container for delivery work. Marking or unmarking
an Epic does not create, archive or otherwise change a Goal. Existing Goals,
source references, accepted work and history remain intact; users create Goals
and link work explicitly. Archiving a Goal retains its work, which remains
accessible through saved work.

One item counts once regardless of its issue, Jira, task or PR links. Failed and
completed tasks are ended activity, unmerged PR closure is not delivery, and
missing cache entries are unknown. Done currently requires a user acceptance with
recorded evidence and scope coverage; merged-delivery acceptance also requires a
linked PR observed merged and every other linked PR merged or closed. A PR closed
without merging is recorded as an abandoned attempt; it neither counts as
delivery nor blocks the PR that replaced it. There is no automatic scope
certification from a merged PR.

GitHub and Jira task admission now reserve work-item source/task ownership in the
same transaction as task creation. Canonical identities use GitHub host/issue ID
or Jira cloud/issue ID, independently of Jira connection credentials. Accepted
source snapshots are retained with the work and its newly admitted attempt.
Admission refuses a source whose work item is Done, Dropped or in another
workspace before preparing a worktree, and a Jira ticket cannot be reassigned
away from its work item's workspace.
The existing source services still reopen the current task by default.

In workspace prose, `#number` identifies an issue or pull request in the
workspace's monitored repository; `owner/repo#number` explicitly selects another
repository. Goal messages, assessments, definitions, notes, lessons,
recommendations and work history share the reference hover card and click-to-zoom
behavior of task conversations. References immediately use the link color and
resolve during rendering, displaying the title alongside the number when
available. An unresolved reference keeps its number. Hover or keyboard focus
reads the title and details. A click opens the existing issue or pull-request zoom, including
uncached issues in watched repositories. An issue outside watched repositories
keeps its browser fallback. Reading a reference never writes to GitHub.

The Goal window opens on guidance. A workspace without a Goal, or a Goal with no
conversation and no definition, asks what the user wants to achieve and offers
three directions written from the repository name without a model request. Each
message is saved as a Goal exchange before a tool-free request to the Goal's
model, rebuilt from the Goal, its accepted definition, its work items and its
recent exchanges. The Goal window names that model: the workspace Development
model until the user picks another configured model and effort for this Goal,
and each exchange and lesson records the model it used. The reply must be one JSON object with an
assessment, at most one question with up to four suggested answers, and an
optional proposed definition; anything else fails the exchange, which the user
can ask again. The user picks any number of the directions or suggested
answers, and Send sends them, one per line, before their own words; Other
options asks the same question again with answers they have not passed over.
The first words only give a new Goal a working title: the agent's answer names
it as a short mission, such as Master Trino's execution engine, and may rename
it as its understanding changes until a definition is accepted. A title the user
chose stays, and a name changes no revision, so pending proposals stay valid.
Accepting a proposal saves its title, objective, success
definition, starting knowledge and constraints, and is refused once the Goal's
text changed after the request was built. A proposed goal or course can also be
adjusted, which starts the reply with "Adjust it: ", or skipped, which tells the
agent the user passes on it; the next answer replaces the proposal either way.
The question on screen, its suggested answers, an open proposal's Accept, Adjust
and Skip and the reply field stay together under the conversation, which scrolls
to the start of its newest turn; a picked answer shows a check, and an event
ByteQuay told the agent shows two lines, the rest on hover. Suggested answers
never offer accepting, adjusting or skipping a proposal, which its buttons do.
One exchange per Goal and two across the app run at a time; a request still
pending at startup is marked interrupted. A reply may take ten minutes and a
lesson twenty; a pending reply shows how long the agent has worked, and Stop
cancels the model's turn and leaves the message to ask again, its late answer
never saved.

Once a definition is accepted, ByteQuay tells the agent, and its answer may
recommend a research card: a scope and up to eight https pages. The Goal
control shows one count, the work needing the user and the decisions waiting
together, naming both on hover. Cards float under the Goal control with Start
and Not now; one of a kind waits at a time and
a dismissed card is not offered again. Start begins a research run as the third
kind of the workspace's restricted run, beside spec checks and memory harvests,
at the monitored head with the checkouts of the workspace's Dependency
workspaces. Its one tool, `goal_knowledge_save`, saves a note whose sources are
https pages or files at a commit the run reads. While it runs, the Guide view
shows how many notes it saved, the newest on hover, and Stop; it is the
workspace's restricted run, not a task with a conversation to open. A finished
run completes the card and tells the agent what it saved; a failed or stopped
one shows the card again. The Guide view lists the notes with their sources,
folded under their count. A note citing a file is marked stale once that file
changed between the commit the research read and the monitored head of its
workspace or Dependency, and the agent sees the mark and may recommend reading
it again; pages are not checked.

Once notes exist, the agent may propose a course: up to four parts with at most
sixteen lessons, each naming what the user can do afterward. Accepting it saves
each lesson as a Learn work item of the Goal, with that outcome as its acceptance
criteria, and records the parts and their order on the Goal; nothing starts. The
same proposal accepted again creates nothing new. When what the user knows or
did shows the course no longer fits, the agent may propose a revision with a
reason: each lesson keeps an accepted lesson by its ID or is new, and every
finished lesson stays. The Guide view shows the revision against the course,
marking new lessons and listing the unfinished ones it leaves out; accepting it
creates the new lessons, saves the new parts and order, and drops each left-out
lesson with the reason. A revision that names an unknown lesson or leaves out a
finished one fails its reply, which the user can ask again. The Guide view shows
the course with each lesson's state. A course proposed before the accepted one
cannot replace it. Once the user accepts a definition that says what they
already know, the agent may set the first topic levels citing that definition.

Once a course is accepted, the Goal window has a Lessons tab beside Guide and
Work, and a lesson opens there as a chapter of a book, in the expanded window.
Without an open chapter the tab shows the course: its map, how many chapters are
done, where to continue, and each part with its chapters and what each teaches.
The first time a chapter opens, ByteQuay asks CodeGraph one bounded question,
the lesson's title and outcome, against the baseline of the Goal's repository,
the index of its monitored default branch, and a tool-free request writes the
chapter from the Goal's notes and the classes, methods and relationships found
at that commit as a concise engineering handbook chapter, diagrams first: a
short summary, up to six invariants to remember, two to six sections, each with
a diagram wherever it shows a structure, a process or an interaction and a
little Markdown that links the notes' sources for what the diagram cannot show,
then one exercise, what to observe and a short check. A diagram is COMPONENTS, a
system's parts in the columns of where they run with labelled arrows between
them; SEQUENCE, participants and their numbered messages over time; FLOW, steps
in order; or TREE, a hierarchy. ByteQuay validates its boxes and arrows and
draws it; the model never writes markup. Every box is one real thing, every
group a real boundary and every arrow a real call or flow labelled with what
moves; a diagram keeps one zoom level, named in its caption, and commands belong
to the exercise. The first chapter written for an accepted course first draws
the course map, one COMPONENTS diagram of the concepts the course teaches and
how they connect, from the notes, the course and what CodeGraph finds for the
Goal; it is saved with the course, every chapter names things by it, and a
revised course draws a new one, while a map that fails leaves the chapter to be
written without it. The writer sees the whole course in order, so a chapter
builds on the ones before it; it names boxes and arrows after the symbols
CodeGraph found and links files at that commit, and without CodeGraph or a
baseline it writes from the notes alone. Reopening a chapter needs no model
call, a failed or interrupted write is retried on opening, and a lesson written
before chapters is written again. The expanded window shows the course's
contents at the left, its parts and chapters with the open chapter's sections to
jump to, the chapter in the middle with its previous and next chapters and All
chapters back to the course, and the Goal's conversation at the right when the
user asks; a message sent there while the lesson is open carries it to the
agent. Restored to the floating window, the chapter shows alone with its own
question field. The user answers the check in a field under it, which opens the
conversation; the answer goes to the agent as the answer to the open lesson's
check, and the agent says whether it shows what the lesson promises. Mark lesson
done accepts the Learn work item and tells the agent, which may recommend the
next lesson as a card; Start on a lesson card opens the lesson.

Once the Goal is defined, the agent also sees up to 100 practice candidates from
the local stores: the workspace's open issues, the 500 updated last, and the
Jira tickets mapped to it, without sources that already have work, a task or a
dismissed card, the ones sharing the most words with the Goal first, and how
much of each source was read. Tickets whose own Jira priority is Highest or High
are kept before that cut. It may recommend one as a Practice card, naming an
issue's number or a ticket's key, with the instruction a developer would give a
coding agent for it; a card naming anything else is dropped. Before the card
floats, ByteQuay reads its source again: the issue's state and the pull requests
linked to close it, or the ticket through its refresh. A closed issue, one fixed
by a merged linked pull request, or a ticket now done or claimed is set aside as
dismissed and the agent is asked for another; an open linked pull request may
fix the issue, so the card floats with that doubt instead. When the read fails,
the card floats from the cached candidate. A card may also say what the agent is
unsure of, such as code it has not read, shown under its reason. Start reads the
source again the same way and sets the card aside, telling the agent, when it is
no longer open for practice. Otherwise Start makes the
issue or ticket a Develop work item of the Goal, with the card's scope as its
criteria under merged delivery, and opens its ordinary composer with the
instruction filled in; the task and its source snapshot are created only when
the user sends it, through the existing issue or ticket admission, as that work
item's attempt. The card
stays started, and no other Practice card floats, until the work is done or
dropped; the agent hears each accept, drop or reopen of the Goal's work. Once
the task of a Goal's issue or ticket work has ended and no delivery runs for it,
the Work view offers Start another attempt: the issue or ticket composer opens
over the ended task, and sending it creates a task that becomes the source's
task, while the ended one keeps its source and stays the work item's earlier
attempt.

Each work item of a Goal has a High, Normal or Low priority, Normal until the
user sets one, and a place in the Goal's order: new work joins the end, and the
user moves open work earlier or later from its details. Work may wait on other
work of the same Goal, never on itself, another Goal's work or anything that
waits on it. Finishing a blocker makes the work waiting on it ready without
starting it. Dropping work that open work waits on is that decision too: Drop
and unblock removes those dependencies, which the waiting work's history
records. Work leaves its Goal only once it waits on nothing and nothing waits on
it.

The Work view opens on Next, or on the first view holding work when nothing is
ready. Next is planned work waiting on nothing open, the user's priority first,
then work that other work waits on, then the Goal's order. Each
card shows the user's priority, how much work waits on it and a source's own
priority, such as a Jira ticket's, as Jira names it and never mapped. Working,
Blocked and Done hold the rest; Blocked names what each item waits on, open work
of the Goal or a source that reports itself blocked. Filters narrow every view
by priority and by source, and the board shows the four views side by side.
The agent reads the work in that order with its priorities and blockers,
prefers ready work that unblocks other work, and a lesson card naming a lesson
that waits on open work is dropped.

When a question needs evidence nobody has gathered, such as whether the code
supports a specification feature, and neither research nor a spec check fits
it, the agent may recommend an investigation card naming what to find out.
Start makes it one Investigate work item of the Goal under an accepted result,
so starting it again finds the same item, and the Guide view opens that work,
where the user links a task or accepts the result.

The agent also sees the workspace's fifteen modules whose code cites a page in
the most sections, read once per monitored head, and may recommend a Spec check
card naming one of them, the specification pages to check it against and how
many gaps it may reproduce; a card naming another module is dropped. Start runs
the existing check with those choices, Adjust opens the Spec check drawer on the
Memory page filled with them, and Open check shows the running check there.
When the check ends, its card settles, a failed or stopped one showing again for
a retry, and the agent hears what it reproduced and what was already reported.
The Goal window lists the gaps of the last finished check that nobody dismissed:
File issue opens GitHub's form with the drafted issue, which only the user
submits, and Add to goal makes the gap a Develop work item under merged delivery,
done once its reproduction test passes and the fix merges. Once the user files
the drafted issue and the issue list reads it, the gap offers that issue: the
newest open issue with the draft's exact title, opened after the check by the
signed-in account. Adding the gap, or adding it again, links the issue to the
gap's work, so a task started from the issue becomes its attempt rather than a
second work item. No other spec check is recommended while one of those gaps is
neither done nor dropped.

When a turn of an interactive task ends and the task belongs to a Goal's work
item, the turn is recorded once in the item's history as a receipt, keyed by the
turn: a completed turn's final answer, or why a failed or interrupted one
stopped. The Goal's agent is told, so it can recommend what comes next; after a
failure it leads with recovery and recommends nothing new until that work
recovers. A turn the user stopped is not reported. Development's own automatic turns and noninteractive runs are not recorded,
and the item's state stays the user's decision. The receipt also links the pull
request the task published. A linked pull request that merges is recorded once
on the open work it delivers and told to the agent, whether a detail refresh, a
list refresh, which also refreshes each open linked pull request, or a restart
shows it; the Goal window then offers Review result, and accepting stays the
user's decision. A review whose reviewer's latest verdict on a linked open pull
request requests changes is recorded and told the same way, once per review, and
the agent leads with that recovery too. When Goal work other than a lesson is
accepted, a tool-free request on the Goal's model writes a short summary from
its history, what changed, the evidence, the insights it gave about the code or
how it is built and reviewed, what is left open and the one follow-up the
history points to, saved once in the item's history. The Work view shows it, the
agent's next request reads it,
and a failed summary leaves Summary pending with Summarize to ask again. Turns that finished while ByteQuay was stopped, or whose receipt
was lost, are recorded at startup, after the requests it was answering are
settled and before the startup cleanup can remove their answers; the agent hears
once per work item what it missed.

Background events, such as failed or interrupted turns, merges, reviews and
finished research runs and checks, are held while the agent answers, until five
minutes after the Goal's last automatic exchange and through the app's quiet
hours, then told together in one exchange, each event once. A turn that finished
normally is only reported: it asks the agent nothing by itself and goes with the
next event, decision or Update now, a newer turn of the same work replacing it.
The user's decisions, such as an accepted definition or course, a finished
lesson or decided work, and a practice pick set aside are told as soon as the
agent is free, quiet hours or not, with whatever is held. Accepted or dropped
work names the work it leaves ready, waiting on nothing open any more, and a
finished lesson that completes its course part names that part and the next.
Held events live in memory; after a restart the next request still reads the
recorded facts.

Pause guidance stops these messages: a paused Goal's agent hears no automatic
event, the user's acceptances included, and the facts are still recorded. The
user's own messages still reach it, and Update now asks it to reassess the Goal
from its current facts, paused or not, one request at a time, taking any held
events with it.

Each request also carries the user's twenty newest questions from their private
cards in every workspace (passage and comment discussions, local review
comments and review questions, without ByteQuay's own summary requests) and the
Goal's topic levels, and each of the user's own messages to the Goal carries an
id. The agent may change a topic's level, New, Learning, Practiced or Strong,
citing the user's messages, such as a lesson question or a check answer, their
private questions, lessons or work items it rests on; ByteQuay keeps only
evidence that exists, never an event it reported, and never changes a level the
user set.
The Guide view lists what the user knows, folded under its count, and the user
can correct any level.

Automatic outcome harvesting and the two-call discovery shortlist remain
pending. The
checklist distinguishes these from implemented behavior.

The checked development database contained no Goals or issue/Jira task bindings.
The final Goal mapping uses `goal`; work items and identity reservations use
`work_item` and `work_item_identity`. No runtime data was reset, copied or deleted.
The superseded empty `workspace_goal` table is not read or rewritten. This is an
unreleased schema redesign, not a released-data migration contract.

Relevant implementation entry points:

- [Goal service](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalServiceImpl.java)
  groups accepted work. The [work-item service](../../backend/app/src/main/java/com/bytequay/app/service/goal/WorkItemService.java)
  owns decisions and identity; the [reference reader](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalReferenceReader.java)
  reads supporting activity without remote requests.
- The [Goal conversation](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalConversationService.java)
  saves and answers exchanges and floats cards; the [Goal planner](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalPlanner.java)
  builds the tool-free request and validates its reply. [Goal research](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalResearchService.java)
  starts and settles research runs, and [its tool](../../backend/app/src/main/java/com/bytequay/app/service/goal/GoalResearchTools.java)
  saves knowledge notes.
- [Goal panel](../../frontend/src/goals/GoalPanel.tsx),
  [summary model](../../frontend/src/goals/model.ts) and
  [Goal loading](../../frontend/src/goals/useGoals.ts) implement the current UI.
- [GitHub admission](../../backend/app/src/main/java/com/bytequay/app/agent/IssueDevelopmentService.java)
  and [Jira admission](../../backend/app/src/main/java/com/bytequay/app/agent/JiraDevelopmentService.java)
  preserve source snapshots and admit ordinary execution tasks.
- [Task naming](../../backend/agent-core/src/main/java/com/bytequay/agent/core/impl/DefaultAgentTaskTitleService.java)
  demonstrates a bounded tool-free provider session without a task row or Git worktree.

## Product flow

A Goal is usually long-term and starts vague. The agent expects the plan to
change as the user learns and as work lands; the first plan is never final.
Three decisions shape every step:

- **Nothing heavy starts without a click.** Research runs, spec checks, lessons
  and development tasks are recommended as cards, and the user starts or rejects
  each one. The agent decides when a recommendation is useful: for example after
  the user resolves the last reproduced spec gap, after a lesson or course part
  finishes, or when nothing it proposed is still waiting and new discovery would
  help.
- **Lessons live in the Goal window.** Reading a lesson, its exercise and
  questions about it happen in the expanded Goal window, not on another page.
- **Private cards teach the agent about the user.** The Goal may read all of the
  user's private cards: private threads on pull-request comments, passage
  discussions on agent answers, local review comments and learning discussions,
  and review questions. They record what the user asked while working, and so
  what they did not yet know.

The reference journey, for a user of the Trino workspace:

1. **Ask for the goal.** Clicking the Goal control asks what the user wants to
   achieve, with two or three directions grounded in the workspace and a
   free-text answer. The user types "I want to become an Iceberg connector
   expert". An existing vague Goal title gets the same help. The user need not
   know a ticket, write a prompt, name the Goal or fill a form. A locally
   rendered opening question needs no model request on panel read; answering it
   starts guidance directly, without a separate Plan with agent gate.
2. **Clarify, then save the Goal.** The agent asks one consequential question at
   a time, with short suggested answers and free text, until it understands the
   scope: what expert means to this user (explain reads and writes, review
   connector pull requests, fix correctness bugs, become a committer), what they
   already know (the Iceberg specification, the Java library, Trino's connector
   code), how much time they have, and whether they want to learn first or start
   on real work. It uses the known objective, constraints, accepted work and
   saved results before asking anything, states assumptions instead of
   inventing preferences, and never requires a questionnaire. It then proposes a
   title, success definition and starting knowledge; accepting them saves the
   Goal.
3. **Research.** The agent recommends a research card: read the Iceberg
   specification, the connector's code, and the Apache Iceberg workspace that
   Trino declares as a Dependency. Once started, the run saves knowledge notes
   with their sources for lessons, discovery and later research to reuse.
4. **Propose a course.** From the notes and the user's starting knowledge, the
   agent proposes a course of parts, each with ordered lessons naming what the
   user can do afterward. A first part might be: understand what an Iceberg table
   is, set up the repository and run focused tests, read table metadata, follow
   snapshots and manifests, trace an append end to end, and evolve schemas by
   field ID. A user who already knows the specification gets a shorter course
   that starts at the connector. Accepting the course saves its lessons as
   ordered Learn work items and starts nothing.
5. **Learn in the Goal window.** A lesson card opens the lesson in the expanded
   window: an explanation grounded in the notes, with links to specification
   sections and to code at a pinned commit, one focused exercise in the
   repository, what to observe, and a short check. The user asks questions in the
   Goal conversation beside it and marks the lesson done when they can do what it
   promised.
6. **Recommend practice and discovery at the right time.** When the user knows
   enough of an area, the agent recommends work in it: an existing issue or Jira
   ticket, or a spec check of the area that finds gaps nobody has reported. Trino
   issue #31252 is that kind of gap: the Delta Lake checkpoint writer keyed add
   and remove actions by path, while the Delta protocol's action reconciliation
   keys a file by path and deletion vector, so a valid commit order lost rows. A
   reproduced gap becomes a proposed work item with its draft issue, which only
   the user files. A recommendation names its reason, evidence, uncertainty,
   acceptance criteria and next action, and discovery returns real source
   references before calling any issue suitable.
7. **Work it to done.** Start on a work card opens the ordinary composer with the
   outcome, source context and execution request already filled in, so the user
   reviews and submits without writing another prompt. The user works the task
   and its pull request to merge; the Goal records the attempt, the merged
   delivery and the result on the Goal page. Open task, View changes, Review
   result and Answer question lead to existing surfaces. An instruction meant for
   an active task goes to that task's composer or steering action, never injected
   silently.
8. **Learn from the user and adapt.** Finished lessons, check answers, questions
   in the Goal conversation and in private cards, and merged work update what the
   agent believes the user knows, topic by topic. A question about manifest lists
   in a review thread shows that area is new; once the user's fix there merges,
   the agent recommends one more piece of work in that area to make it stick,
   then moves to the next part of the course. The agent explains where the Goal
   stands and what should happen next without waiting for "what next?". A
   proposed change to accepted scope, criteria, priority, order or dependencies
   shows the specific change and its reason for the user to accept or adjust; an
   unchanged plan needs no repeated approval.

Each finished lesson and merged contribution moves the user toward the Goal. For
an objective such as becoming a committer, the panel shows progress on agreed
work, not a percentage likelihood of becoming a committer. External recognition
cannot be inferred from merged-PR counts. The user confirms such a Goal outcome.

## Goal panel and user decisions

Enhance the current Goal surface in place. Preserve its floating control,
pinning, Goal switcher, workspace navigation and expansion. Clicking the control
zooms a floating window out from it without narrowing the underlying page. The
header drags the window, its bottom-right corner resizes it, and expansion uses
the same panel. Leaving the control/window area closes it after 1.5 seconds unless
the pointer returns, except during a drag, resize or confirmation. Closing
preserves drafts for reopening; Escape and the close button also dismiss it.
Keep the window inside the page and respect reduced-motion preferences.
Clicking the title renames the Goal in place; Enter or leaving the field saves
and Escape keeps the old name. Cancel goal in the header archives a Goal the
user started, after the user types `cancel` in its confirmation dialog and clicks
Confirm delete goal. The confirmation starts empty each time and stays disabled
until the text matches exactly. The window then returns to the opening
question. Goals with a source reference use the same independent cancellation
action; an Epic marker never controls their lifecycle. New goal in the switcher asks the same question while the current Goal
stays pinned until an answer starts the next one. The opening never asks for a
name and never turns an existing title into the question; its answer field sits
under the question and takes focus.
The default view is guidance, with Work and History as secondary inspection
views. A user should understand the current situation and useful next action
before opening a transcript, search box or board. Both Home surfaces open this
same Goal and the same current guidance.

Use this information hierarchy in both the floating and expanded view:

1. **Goal and current situation.** Show the title, a short agreed outcome and
   one plain-language status such as Clarifying your goal, Checking candidates,
   Working, Your decision needed, Waiting for review or Ready to conclude.
   These are projections of current facts and guidance, not new durable
   execution lifecycle states.
2. **One useful agent intervention.** Show the current clarification or the
   strongest next recommendation, with a short reason, relevant evidence and
   one primary action. A clarification offers concrete answers and optional
   text. Recommendations provide prepared actions such as Review proposed path,
   Investigate this candidate, Review result or Respond to feedback. Urgent
   approvals, failures and blockers take precedence over finding more tickets.
3. **The path in context.** Show the current milestone and the next few accepted
   steps in a compact ordered list. Make prerequisites and proposed changes
   visible. Expand to inspect all work; empty columns and zero-valued counters
   must not dominate an empty or uncertain Goal.
4. **What changed and what remains.** Show a short supported result and the
   latest meaningful event, with evidence and earlier decisions available on
   demand. Keep numerical progress subordinate to these explanations.

Use the app's existing typography, controls, neutral surfaces and blue actions.
Avoid three equal-weight statistics tiles above the guidance. Use meaningful
headings, readable body text, short paragraphs, restrained borders and room
around the primary decision. The floating window uses a single column; expansion gives
guidance and the path more room without showing two competing copies of the
Goal panel. Preserve the draft, selected item and guidance revision when changing
presentation. Render only one panel, and keep it out of keyboard navigation while closed or
covered by another layer. Manual creation, source search and board management remain
available under Work; they are not the opening task given to an unsure user.

The floating control may show one short meaningful cue, such as Goal · 1 decision
or Goal · Review ready, rather than a meaningless 0/0. Recommendations float as up
to three cards under the control, newest first. Each names its kind (Research,
Spec check, Lesson or Work), a title and one line of reason, with Start and Not
now. A card appears only when the agent makes a new recommendation; it does not
open the window, interrupt work, animate repeatedly or return unchanged after Not
now. Start on a research card starts that run, and on a spec-check card it runs
the check with the module, pages and reproduction count the card names, while
Adjust opens the existing Spec check drawer. A lesson card opens the lesson in
the Goal window, and a work card opens the prepared composer. Opening the control
shows the saved current assessment immediately; panel reads stay local.

| Content | What it contains | User actions |
| --- | --- | --- |
| Objective | Outcome, constraints, accepted success definition | Edit, discuss, pause guidance |
| Path | Ordered accepted work items and explicit dependencies | Accept proposed changes, reorder, remove from scope |
| Course | Its map, then parts and ordered lessons, each with what it teaches | Open a lesson, mark it done, ask to change the course |
| Current guidance | One question or recommendation with why, evidence and uncertainty | Answer, accept, adjust, skip, ask why |
| Work | Planned, Working and Done; blocked or waiting details | Start, open task, review result |
| Results | Outcome, supporting evidence and what remains | Accept a result, reopen, inspect PR or artifact |
| What you know | Topics with the agent's assessment and the evidence behind it | Correct an assessment |
| History and insights | User decisions, verified events and attributed summaries | Read earlier decisions and evidence |

Show at most three recommendations at once. Each accepted work item has a stable
identity and an expandable set of related sources, execution attempts and results.
Adding a source or another attempt does not add a second progress card.
Dismissal is local to the proposal and relevant source revision; an unchanged
candidate must not immediately return. A user can explicitly ask to reconsider it.

Proposal text and agent summaries are visibly distinct from observed facts.
Show checked coverage and stale or unavailable evidence where it affects a
decision. Preserve the user's draft and the last good panel on errors. Use
keyboard-accessible controls and announce answering, saved and failed states.

## Guidance follows the work

The service prepares guidance from the latest accepted Goal, constraints, work
items, source observations and saved results. It is responsible for choosing the
next useful intervention; requiring the user to open Discover and invent a
search prompt or to open Working and ask for status does not satisfy this design.

| Situation | Agent responsibility | User decision |
| --- | --- | --- |
| No clear outcome or only a broad title | Ask the smallest useful question with concrete suggested answers | Choose or describe the intended outcome |
| Starting knowledge unknown | Ask what the user already knows, with suggested answers | Describe or confirm their starting point |
| Lessons or discovery need knowledge the Goal lacks | Recommend a research card for the missing area | Start or reject the research |
| Outcome clear, no accepted path | Draft milestones, completion criteria and a suitable starting point | Accept or refine the proposed path |
| Goal saved, no course yet | Propose parts and lessons fitted to the user's starting knowledge | Accept or adjust the course |
| A lesson is open | Answer questions about it and offer its check | Finish the lesson or ask more |
| The user knows enough of an area | Recommend practice there: an issue, a ticket or a spec check | Start or reject the card |
| An area is practiced | Advance to the next course part | Continue, or ask for more practice |
| Ready to find work | Discover within authorized scope and explain candidate fit and uncertainty | Choose a proposed candidate or direction |
| Accepted work is ready | Prepare its task request and explain why it comes next | Start with the existing execution controls |
| Work is running | Show observed activity and the current milestone; avoid unnecessary questions | Answer only when execution needs input |
| Attempt failed or a blocker changed | Explain the impact and recommend recovery or other eligible work | Approve any changed scope or start the prepared recovery |
| A result or review arrives | Summarize evidence and outstanding feedback; prepare the relevant review action | Review, accept a result or respond to feedback |
| Milestone delivered | Save the result, reassess remaining work and recommend the next eligible step | Start it or accept a proposed plan change |
| User returns later | Show saved situation, changes since their last decision and the next useful action | Continue without reconstructing context |

Suggested answers are first-class Goal messages. Prepared actions reference
validated work items, sources and existing task actions; agent text is never an
executable command. One current guidance response contains a brief assessment,
an optional consequential question with suggested answers, proposed plan changes
and the next supported action. This extends the existing bounded planner reply,
not a second workflow engine. Facts remain useful when generation is unavailable;
show the last guidance with its stale/failed status and the specific retry remedy.

Recommendations follow the user's pace. The agent does not stack cards of one
kind: it proposes the next spec check after the user has resolved or dropped the
gaps from the last one, recommends practice in an area only once its basics are
known, and starts new discovery only when nothing it proposed is still waiting
for the user. A blocker, failure or review that needs the user still comes first.

## Courses and lessons

A course is the Goal's learning path. It has parts, and each part has ordered
lessons. A lesson is a Learn work item: its acceptance criteria say what the
user can do afterward, its completion rule is an accepted result, and its part
is the item's milestone. The course is a proposal until the user accepts it, and
the agent may later propose to add, drop, reorder or shorten lessons under the
same rule as any other plan change.

A tool-free Goal request writes each lesson from the Goal's knowledge notes, the
user's profile and what CodeGraph finds for the lesson at the baseline of the
Goal's repository, and the lesson is saved, so reopening it needs no model call.
It holds an explanation with links to specification sections and to code paths
at the commit its notes were read from, one focused exercise with what to
observe, and a short check. An exercise in a related repository names that
workspace; doing it there uses that workspace's ordinary composer or the user's
own IDE and does not move the lesson. When the notes behind a lesson go stale,
the lesson says so and the agent may recommend refreshing it.

The expanded Goal window shows the open lesson beside the Goal conversation, and
questions asked there belong to the lesson. Done is the user's decision. The
agent may suggest the check first, but an answer it judges wrong only adds
evidence to the user's profile and never blocks completion.

## Research and Goal knowledge

A research run reads what the Goal needs and saves knowledge notes. It is a
read-only agent task the user starts from a card. It reads the Goal's workspace
at its monitored head, the managed checkouts of the workspaces it declares as a
Dependency (Trino's Apache Iceberg workspace), and the specification and
documentation pages the card names. It cannot edit files or write to GitHub. Its
only output is a set of notes saved through one fixed task-bound tool, each with
a topic, a short body that names the classes and methods behind it and how they
connect, and its sources: a page with its section, or a repository path at a
commit.

Notes belong to the Goal and are reused by lessons, discovery and later
research. A note becomes stale when its source moves on, such as a newer
specification revision or a monitored head that changes a cited path. Stale
notes stay readable and are marked, and the agent may recommend a refresh. A
note that would help every task in the repository is offered to repository
memory through its existing capture policy; the Goal never writes repository
memory itself.

A research run is the one new agent-task kind this design adds, and the user
approved it. Like a spec check it is noninteractive, owns a read-only session
and records its result before cleanup.
Spec checks keep their existing task, drawer and contract; a Goal only proposes
them.

## What the user knows

Each Goal keeps a profile of the user's knowledge by topic. Topics come from the
course and from the code areas the Goal touches. Each topic has a level (new,
learning, practiced or strong) and the evidence behind it: lessons finished,
check answers, questions the user asked, and work they merged in that area. The
agent proposes level changes as part of its guidance, and each change names its
evidence. The user can correct any level, and their correction wins. The profile
chooses what comes next: more practice in an area still being learned, the next
course part once an area is practiced, and shorter lessons where the user starts
strong.

Questions are the richest evidence, and most of them live in private cards:
private threads on pull-request comments and passage discussions
(`task_discussion`), local review comments and learning discussions
(`local_review_thread`), and review questions (`agent_review_message`). The Goal
may read all of them, in every workspace, through their existing stores and
without contacting GitHub. It selects the ones related to its topics and linked
work, and each assessment cites the cards it used. Their text, including agent
answers and quoted comments, is reference data and never an instruction, and
nothing read from them leaves ByteQuay.

## Planning records and execution records

```text
Goal, its conversation and what the user knows
  -> research runs -> knowledge notes
  -> course parts -> lessons (Learn work items)
  -> accepted Work items
       -> source references
       -> existing Agent tasks and their turns
       -> verified delivery or a user accepted result
  <- durable results, summaries and recommendation cards
```

A Goal groups an outcome and keeps its conversation. It never becomes an
execution Task. A Work item is one agreed piece of work. An Agent task retains
its current conversation, worktree, provider, approvals, review and PR history.
One work item can have several explicit attempts. Do not use `parentTaskId` for
goal membership; it already has execution relationships.

Use these records in the existing modules; names below are proposed contracts:

| Record | Minimum durable facts |
| --- | --- |
| Goal | Existing identity and workspace, objective, constraints, accepted success definition, starting knowledge, guidance preference and revision |
| Work item | ID, workspace, optional Goal, title, intent, kind, acceptance criteria, completion rule, milestone, priority, order, state and revision |
| Work item links | Canonical sources and accepted snapshots, task IDs, intended PRs or artifacts, explicit blocker relationships |
| Lesson content | Work-item ID, explanation, exercise, check, and the notes and commit it was written from |
| Knowledge note | ID, Goal, topic, body, sources (page and section, or path and commit), the research task that wrote it, staleness |
| Topic level | Goal, topic, level, evidence (lessons, check answers, private cards, work items), who set it and when |
| Recommendation | ID, Goal, kind, target (work item, source, module or research scope), reason, state (shown, started, dismissed) and the input revision it came from |
| Goal exchange | Request ID, user message or automatic trigger, input revision, provider/model, validated answer, proposal and failure if any |
| Work history | Stable fact identity, work-item ID, event kind, evidence, actor and occurrence time |

Start with Learn, Investigate and Develop kinds. These describe intent; they are
not new execution engines. A lesson is a Learn item completed in the Goal window
with an accepted result, without starting a task. A repository experiment uses
the ordinary task composer and its existing permission and automation controls.

Keep one primary Goal per work item. Moving the item changes membership, not its
execution history. A move must resolve affected blocker links explicitly; it
cannot leave dangling links or dependencies across Goals. One task belongs to
one primary work item. A PR can support
several items only when its scope is explicitly associated with each. Similar
titles are never sufficient evidence to merge items or declare a duplicate.

Canonical GitHub source identity includes host and stable repository/issue
identity; names, issue numbers and URLs are display metadata. Jira identity uses
cloud/site and stable issue identity, not the connection credential ID. Refresh
and authorization still resolve through the current connection. Scope source
lookups across workspaces before ordinary launch so another view does not create
duplicate work. If already owned elsewhere, offer that existing item and workspace.

The current GitHub and Jira task-link tables each enforce a unique source slot
per task association. Supporting several task attempts requires a deliberate
change: reserve canonical source ownership on the work item, retain each task's
accepted source snapshot, and make admission open the existing attempt by default.
Start another attempt is explicit, requires the earlier attempt to be terminal
and its delivery continuation to have settled, and receives a new operation
identity. An idle but open task is still the existing attempt. Preserve
the source locks and task-creation transaction; merely removing the constraint
would reintroduce duplicate-start races.

## Trustworthy progress

Use four work-item states: Planned, Working, Done and Dropped. Running, awaiting
review, blocked, failed attempt, evidence unavailable and waiting for the user
are details derived from execution or evidence, not additional durable lifecycle
states. A failed attempt leaves its work item Working and offers recovery.

| Observation | Effect on the work item |
| --- | --- |
| Agent finishes answering, or task is completed locally | Save an attempt result; this alone does not establish Done |
| Task fails or is interrupted | Record failure; keep remaining work and its history |
| Linked PR closes without merging | Record abandonment of that PR; never count it as delivered |
| Issue disappears from the open cache | Evidence unavailable until authoritative source state is read |
| GitHub issue closes or Jira enters Done | Record source state; this alone does not establish code delivery |
| Intended PRs merge in the agreed repository and base, covering the accepted scope | Done under the agreed merged-delivery rule |
| User accepts the required learning, investigation or local-result artifact | Done under the agreed accepted-result rule |
| User drops the item from scope | Dropped with a reason; not a delivered outcome |

The completion rule is agreed before execution: merged delivery for ordinary
contribution work, or an explicitly accepted result for learning, investigation
and intentional local-only work. Changes to that rule are recorded user decisions.
An agent can recommend acceptance but cannot certify its own response as success.

For merge-based completion, save the accepted deliverable set, repository/base,
PR identity, observed merge commit/time and which acceptance criteria it covers.
A merged partial PR does not complete the rest of an item. If coverage is
uncertain, ask the user to review the scope mapping before accepting delivery.
Tests are supporting evidence, not a replacement for the selected completion rule.
GitHub or Jira closure remains a separate explicit source action.

Goal progress is Done / current in-scope accepted work items. Display Dropped
separately and record scope additions and removals. Empty Goals have no progress
percentage. A Goal with all work done is Ready to conclude until the user accepts
its overall outcome. Reopening changes current state and adds history; it does
not count as another first completion. Do not infer older historical delivery
from today's source state or display a historical trend without recorded facts.

An independent first change can correct today's panel before the new model:
label it linked-item activity, separate Ended from achieved results, show missing
issues as Unknown, and keep merged PRs as an explicit factual count. Do not
present a renamed reference count as outcome progress.

## One record per outcome

Accepted work receives a Work item even when there is no GitHub issue or Jira
ticket. The primary source, task attempts and deliverables are links on that item.
For example, one bug, two execution attempts and one merged PR remain one outcome.

Accepting a proposal and binding an existing task use the same find-or-create
operation. Persist a unique accepted-item mapping for each proposal item, so
accepting it with a different request ID still returns the same work item.
An operation ID also makes lost-response retries idempotent; reusing it with
different content is a conflict.
Explicit source/task associations are used to find existing work; uncertain
relationships are shown for confirmation. A task already owned by another item
opens that item instead of silently moving it. Moving between Goals preserves
identity, snapshots, history and accepted results.

Persist a compact result before task-log retention can remove its source: final
answer or bounded excerpt, the task/turn identity, relevant verification results,
PR/artifact references, uncertainties and evidence coverage. Save a factual
receipt first, then generate its narrative summary. Summary failure cannot lose
the result or change its completion state. Do not treat a task's unverified claim
that tests passed as a verified check; use normalized command/check evidence and
retain that distinction when only the claim is available.

Reconcile durable task/source facts after restart before retention can remove
uncaptured results. A linked task's retention or deletion path must first save
its receipt, or preserve the evidence and expose a retryable capture failure.
This recovery records facts; it does not restart an interrupted model request.

Goal history outlives task runtime cleanup and source caches. Deleting an
execution task leaves its saved result and an unavailable-task reference. Archive
a Goal without deleting its work. Epic marking is independent: toggling a source's
marker never changes a Goal's plan, conversation, source reference or outcomes.

Goal insights are attributed summaries of this work. They do not automatically
become repository-wide rules. Remember an applicable insight uses the existing
repository-memory capture policy and source evidence, rather than restoring the
removed repository-learning system.

## Clear organization

Keep the existing list and board. Add fixed views for Next, Working, Blocked and
Done, plus priority and source filters. Accepting a plan establishes an order;
the user can reorder directly or ask the agent to propose a new order.

Use High, Normal and Low local priorities. An explicit user priority wins;
source priority is displayed separately and only mapped through explicit fields
or configured mappings. Do not interpret arbitrary labels as urgency. Prefer
ready work that unblocks an accepted step, and explain that choice in words.

Implement only `blockedBy` between items of the same Goal in the first release.
Reject self-links and cycles. Completing a blocker makes its dependents eligible;
it never automatically starts them. A dropped blocker requires revising the
dependency, not pretending it was completed. Show external blockers as evidence
and a reason without importing another repository or constructing a global graph.

Both Needs attention views reuse the same backend work-item projection and open
the same item. A goal recommendation and its linked issue must not become two
cards for the same next action. Existing urgent approvals, failures and review
requests keep priority. Preserve Ack and Snooze, and announce a new proposal only
when it is materially new or requires a decision.

Cycles, modules, custom workflow builders, arbitrary hierarchy, saved query
languages, YAML synchronization and a public MCP/webhook platform are deferred.
Add them only for an observed organization or integration need.

## Bounded discovery and planning

The first release supports one selected ByteQuay workspace per Goal, its watched
GitHub repository and Jira tickets explicitly mapped to that workspace. Research
and lessons may also read the workspaces it declares as a Dependency, which is how
a Trino Goal reads Apache Iceberg; work items and launches stay in the Goal's
workspace. It never silently watches repositories, adds relationships, changes
Jira mappings or expands scope. Show a setup remedy when the requested repository
is not available. Keep cross-workspace goals as a later addition.

Discovery is application-owned and read-only. Use the existing GitHub/Jira refresh
services, credentials, rate-limit handling and source caches. A planning request
may refresh authorized sources; an ordinary panel read never performs discovery.
First filter locally for closed/inaccessible sources, known duplicate markers,
existing active work, accepted delivery, user dismissals and blockers. Inspect
the available candidate set before presentation limits, and disclose source caps,
partial refreshes and stale data. Never claim exhaustive discovery from a sample.

Discovery has three sources. Existing issues and mapped Jira tickets go through
the bounded pass below. A spec check of a module the Goal covers finds gaps that
nobody has reported and reproduces each one with a test; a reproduced gap becomes
a proposed work item carrying the check's draft issue, and an open issue that
already reports the gap is linked instead. Research findings, such as a
specification feature the connector lacks, become proposed investigation items.

Proposed bounded first-release pass:

1. Supply up to 100 compact eligible candidates, selected by explicit constraints,
   priority and relevance. Include existing work and PR associations so the agent
   can avoid recommending work already underway. Record the selection coverage.
2. A tool-free model call shortlists at most five candidate IDs with reasons.
   The backend accepts only IDs from that supplied set.
3. Refresh available detail and relevant known PR relationships for those five
   through fixed application reads. A missing duplicate/closing-PR relationship
   read is a concrete source-adapter addition, not an assumed existing capability.
4. A second tool-free call produces at most three recommendations and the proposed
   path, grounded in those snapshots. Without sufficient evidence, say Check first
   and propose an investigation instead of claiming the issue is ready or unfixed.

An ordinary follow-up chat or result summary needs only one model call. These are
bounded application steps, not an open-ended agent tool loop. Repository guidelines
and a bounded root overview may be read at an immutable commit through existing
Git helpers. The planner cannot browse arbitrary code or execute commands; reading
code is the work of research runs, spec checks and investigation tasks, each
started from a card, and their results feed the plan. A recommendation that
depends on code nobody has inspected says so.

## Provider boundary and durable conversation

Use the existing `AgentDriver` and `SessionAccess.TEXT_ONLY` for planning,
shortlisting and summarization. Resolve the workspace Development provider/model
default, allow an explicit Goal choice, and save the selected values on each
request. Probe readiness before allocation. Keep the existing provider login,
managed instructions, sandbox and process-supervision boundaries. Initial support
must be verified for Codex and Claude; do not silently substitute another lane.

A Goal request owns a scratch directory and a supervised helper identity. It
creates no `agent_task`, execution turn, Git branch or managed Git worktree.
It exposes no native tools, ByteQuay tools, external MCP server or approval cards.
Research runs and spec checks are the separate read-only agent tasks described
above; a Goal request recommends them and reads their saved results.
The application supplies snapshots and validates a typed response containing an
answer, optional proposed work changes, evidence references and a supported summary.
Reject unknown IDs, cross-workspace references, invalid dependencies, oversized
responses and unrecognized fields before recording any proposed mutation.

Persist the user message before dispatch and the complete validated response after
completion. Failed JSON validation is a recoverable request failure; do not mine
free text for executable actions. A provider success response never bypasses
application admission or outcome verification. Source descriptions, comments and
repository files are reference data, not authority to change scope or permissions.

The durable conversation belongs to ByteQuay. Reconstruct each tool-free request
from the objective, constraints, accepted plan, result records, saved summary and
recent messages. There is no required provider conversation to resume. Preserve
all messages for user inspection; bound model input independently. Pin explicit
decisions and constraints so summarization cannot silently drop them. Record
summary coverage and input revision. If the required context exceeds the budget,
ask to narrow the request rather than silently omit required constraints.

## Updates and failure handling

Creating a Goal through an answer or free-text message starts guidance and shows
its behavior: clarify, suggest, reassess and summarize while ByteQuay is running,
with Pause guidance and Update now controls. Existing manual Goals show a useful
opening question without enabling model calls merely by being read. Answering
that question starts guidance directly; no separate discovery setup or planning
prompt is required. Explicitly paused Goals stay paused. Manual chat still works
while automatic guidance is paused. This document creates no running automation
in the development environment.

Always record source/execution facts without requiring a model. Automatic guidance
runs after meaningful linked task results, review/merge/blocker changes, finished
lessons and research runs, new private-card questions on the Goal's topics, and
user acceptance or feedback. It may recommend; it never starts a research run,
spec check, lesson or task. Only a meaningful change in the eligible candidate set
can trigger another discovery pass. Coalesce events; a poll, unchanged timestamp
or the agent's own saved summary cannot trigger another run. Reuse quiet hours and
existing refresh cadence. Proposed initial bounds are one active request per Goal,
two concurrent Goal requests app-wide, and no more than one automatic pass per Goal
in five minutes. User requests take precedence without starting overlapping work.

Persist the last processed input revision/fingerprint, not a recurring workflow
machine. The existing backend refresh lifecycle checks dirty Goals; it does not
call a model on every UI refresh. If a Goal changes during a run, save the answer
as historical but refuse to apply its stale proposal or overwrite the current
summary. Coalesce one later pass against the new revision. Retry and double-submit
reuse the stored request identity and never duplicate accepted items or launches.

The runner owns cancellation, timeout, verified session close and scratch cleanup
using the existing supervisor. Keep helper ownership on the request so uncertain
cleanup can be reconciled before another run. Preserve scratch resources when
stop cannot be verified. Persisted messages without a completed response and
without a live owned request appear Interrupted after restart, with explicit Retry.
Do not resend an interrupted user request or execution automatically on startup.
Fresh factual changes may update the panel; the next guidance request incorporates
them. No off-device daemon or activity while the application is stopped is promised.

| Failure | Required behavior |
| --- | --- |
| Offline, unauthenticated or rate limited | Keep the plan and last results; name the unavailable source and offer its existing remedy |
| Discovery covers only part of the source set | Show checked coverage and uncertainty; do not infer absence or completion |
| Agent fails or emits invalid output | Preserve the user message, current plan and evidence; expose retry |
| User edits while the agent answers | Preserve their edit; stale output cannot become the current plan |
| Item is closed, marked as Epic or started elsewhere before Start | Revalidate admission and open existing work or explain the changed condition |
| Summary generation fails | Facts remain recorded; show Summary pending and retry independently |
| A research run fails or is stopped | Keep the notes it saved; offer a card to retry the missing scope |
| A private card is deleted | Keep the assessment; mark that evidence unavailable |
| Task logs expire | Saved outcomes remain; unavailable evidence is identified |
| Goal is archived or guidance paused | Stop new automatic requests; preserve history and any existing execution tasks |

## Application and storage changes

Keep domain records and store ports in `core`, entities/adapters in
`persistence-sqlite`, and Goal orchestration, discovery, result projection and HTTP
in `app`. Use the existing provider SPI and task creation path. Add no Maven
module, connector framework, execution engine or general event bus.

Extend `GoalController` and the typed Electron bridge with goal messages, proposal
acceptance/dismissal, recommendation Start and Not now, course and lesson reads,
topic-level corrections, work-item editing, result acceptance and guidance
preference.
Commands carry an operation ID and expected revision. Reads are local projections.
Start carries a work-item ID into canonical task creation; the server resolves
and revalidates its accepted source, workspace and intended outcome. Persist task,
source snapshot and work-item association together before the first turn is admitted.
The existing task idempotency identity is reused on lost-response retries.

Use Hibernate-managed tables for final planning, conversation, history, knowledge
note, lesson content, topic-level and recommendation records.
Index source ownership and task associations; use database uniqueness plus the
existing source locks. Enforce task/workspace ownership and revision checks in
the backend, not just the panel. Record result receipts idempotently by their
task/turn or source evidence identity so event replay cannot multiply outcomes.

Replace the old reference-counting Goal projection when work items land. Do not
retain competing progress models or add compatibility adapters for intermediate
designs. Preserve existing task, source and PR histories. The implementation must
explicitly account for any meaningful existing Goal data before an incompatible
schema change: back it up and agree on its disposition, rather than silently
resetting the user's database. Disposable local fixtures may reset under the
existing development convention. This proposal authorizes no data deletion and
introduces no Flyway or migration-history machinery.

## First release and verification

The first complete release includes the conversation, research runs, a course
taught in the Goal window, the profile of what the user knows, bounded GitHub/Jira
and spec-check discovery, recommendation cards, accepted work-item identity,
existing-task handoff, durable outcome receipts, summaries and the updated panel.
Fixing a progress bar alone is not this release. The implementation plan delivers smaller independently
verifiable changes on the way to that complete loop.

Acceptance must demonstrate:

- The reference journey works end to end: a vague expertise Goal is clarified and
  saved, a research card runs only after Start, the proposed course fits the
  user's starting knowledge, a lesson opens and finishes in the Goal window, and a
  later practice card leads to a merged pull request recorded on the Goal.
- A spec-check card reproduces a gap of the #31252 kind and turns it into a
  proposed work item with a draft issue; nothing reaches GitHub until the user
  files it.
- A question in a private card changes the level of the topic it touches, with
  the card cited, and the next recommendation follows from that level.
- No research run, spec check, lesson or task starts without a click, a dismissed
  card does not return unchanged, and cards of one kind do not stack.
- A natural-language Goal produces a saved path and real source-backed proposals
  without starting a task or allocating a Git worktree.
- Accepting a proposal twice creates one item; starting twice creates one task
  and first turn. A source already active in another workspace opens that work.
- One issue, repeated attempts and its PR count as one outcome. Failed attempts,
  unmerged closures, cache loss and partial delivery do not increase Done.
- Learning completes through an explicitly accepted result. A code contribution
  completes only through its selected verified delivery rule.
- Source refresh, task result and merge events update the same panel and both
  attention views; repeated events produce no repeated summary or notification.
- Stale proposals, missing credentials, malicious source instructions, invalid
  model output, pause, restart and uncertain cleanup preserve user work and scope.
- Results and summaries remain readable after execution retention. A reopened
  item preserves earlier achievement and scope history without double counting.
- Codex and Claude fixtures prove tool-free planning and canonical execution
  handoff; opt-in live smoke evidence is reported separately from fixture checks.

Run backend `mvn verify` and frontend lint, TypeScript, tests and packaging for
material implementation changes. Add focused tests at the identity, admission,
outcome and user-interaction boundaries; use the existing test frameworks.

## Reference ideas from Plane

Plane's [work-item model](https://github.com/makeplane/plane/blob/preview/apps/api/plane/db/models/issue.py)
illustrates stable work identity, source references and explicit relations. Its
[state model](https://github.com/makeplane/plane/blob/preview/apps/api/plane/db/models/state.py)
separates completion from cancellation. ByteQuay adapts these concepts to its own
local execution and delivery evidence. This design copies no implementation code
and does not depend on running Plane.
