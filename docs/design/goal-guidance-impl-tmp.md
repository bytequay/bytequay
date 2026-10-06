# Agent guided goals implementation plan

Earlier CodeGraph references below are historical implementation checkpoints.
The [IDE refactor](code-tests.md#shared-code-queries-and-retired-code) supersedes
that engine with shared language queries and bounded baseline Git excerpts.

Status: complete. Twenty-six implementation slices and a live check on a real
Goal landed; four items below were left out on 2026-10-06 by the user's
decision, each marked where it stands with its reason.
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
Goal independently of its source and returns to it, and the title renames in place. Durable Goal exchanges record the
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
workspace, without worked, tasked, Epic or dismissed sources, ranked by the words
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

Progress: items 2 and 3 are implemented. The accepted definition's id is
evidence the agent may cite for a topic level once its starting knowledge is not
empty, and the planner sends that id and asks for first levels; accepting a
course whose request came before the accepted course's is refused.

Items 1 and 4 are implemented: a proposed goal or course offers Adjust, which
seeds and focuses the reply field, and Skip, which sends the agent a fixed
message; the Goal control's two badges became one count with both named on
hover, and an empty Goal's Work view and switcher show directions instead of
empty counts. The eighth slice is complete.

Done when: a proposal can be adjusted or skipped without accepting it, a stated
starting knowledge sets levels the user can correct, an older course cannot
replace a newer one, and the Goal control shows one count.

## Ninth implementation slice

Keep discovery honest: a high-priority source is not cut by the candidate
limit, a possible fix is shown as a doubt rather than treated as confirmed, a
practice card is read again when the user starts it, and every card can say
what the agent is unsure of.

1. Priority survives the limit. Tickets whose own Jira priority is Highest or
   High are kept before the relevance cut of the 100 candidates; the candidates
   still reach the agent nearest the Goal first.
2. Possible fixes stay visible. An issue whose linked pull request is still open
   is no longer set aside: its card floats with the doubt that the pull request
   may already fix it. A closed issue, a merged linked pull request and a done
   or claimed ticket are still set aside.
3. Start reads the source again. Start on a practice card reads its issue or
   ticket again; if it is no longer open for practice, the card is set aside,
   the agent is told why and nothing starts.
4. Cards say what is uncertain. A card may carry a short uncertainty, such as an
   area the agent has not read, and the card shows it under its reason.

Progress: the ninth slice is complete. The candidate pool keeps tickets whose
Jira priority is Highest or High before the cut and sorts the kept candidates
by relevance; reading a practice source again returns either why it is no longer
open (closed, fixed by a merged pull request, a done or claimed ticket) or the
doubt of an open linked pull request, which the card carries in its new
uncertainty beside the agent's own; Start on a practice card reads the source
again and sets the card aside, telling the agent, when it closed; and the card
under the Goal control shows its uncertainty. A card set aside once is still the
same card when only its doubts differ.

Done when: a Highest ticket that shares no words with the Goal is still a
candidate, an issue with an open linked pull request floats with that doubt, a
card whose issue closed after it floated cannot start, and a card shows the
uncertainty the agent gave.

## Tenth implementation slice

Learn beside the conversation: the expanded Goal window teaches an open lesson
next to the Goal's conversation, the user answers the lesson's check there, and
what the user says, a question or a check answer, can move the level of the
topic it shows.

1. The lesson sits beside the conversation. In the expanded window an open
   lesson takes the left of the panel and the Goal's conversation, with its
   reply field, the right; a message sent while the lesson is open belongs to
   it. The floating window still shows the lesson alone, with Back to guidance.
2. The check takes an answer. Under the lesson's check, the user writes an
   answer and sends it as a message about the lesson; the agent says whether it
   shows what the lesson's outcome promises and what it misses.
3. The user's own words count. Every message the user sent the Goal, a lesson
   question or a check answer included, reaches the agent with an id it may
   cite for a topic level, and ByteQuay keeps the level only when that message
   exists.

Progress: the tenth slice is complete. Each message the user sent the Goal
reaches the agent with a message:<id> it may cite, a lesson question also naming
its lesson, and a level citing such a message is kept; one citing an event
ByteQuay reported or an unknown message is not. The expanded window shows an
open lesson beside the conversation, whose reply field then asks about the
lesson; the floating window keeps the lesson alone with its own question field.
The check takes an answer under it, sent as "My answer to the check: " and the
answer, which the agent assesses against the lesson's outcome.

Done when: in the expanded window an open lesson and the conversation show side
by side and a question sent there belongs to the lesson; a check answer reaches
the agent as an answer to the open lesson's check; and a level citing a message
the user sent is kept, while one citing an invented or automatic message is not.

## Eleventh implementation slice

Ask the model less: background guidance waits out the quiet hours, and routine
progress no longer asks the agent by itself.

1. Quiet hours hold background events. During the app's quiet hours, the window
   that already idles GitHub and Jira refresh, a Goal's background events wait
   and go together in one exchange once the window ends. The user's own
   messages, decisions and Update now are never held.
2. A finished turn is reported, not asked about. A turn of a Goal work item's
   task that finished normally is recorded and held without asking the agent; it
   goes with the next event, decision or Update now, and a newer turn of the
   same work replaces it. A failed or interrupted turn, a merge, a requested
   change and a finished research run or check still ask. Turns recovered at
   startup follow the same rule.

Progress: the eleventh slice is complete. Held events wait for the later of the
five-minute window and the end of the quiet hours, read from the app settings,
unless a decision waits; a normally finished turn, or a recovery whose missed
turns all finished, is a routine report that starts no exchange and is replaced
by a newer report on the same work item.

Done when: an event told during the quiet hours waits until they end while a
decision does not, and a normally finished turn starts no model call but reaches
the agent with the next event or Update now.

## Twelfth implementation slice

Read lessons as a book: a lesson is an illustrated chapter, and the expanded
Goal window reads the course like an e-book.

1. A lesson is a chapter. The lesson writer returns a short summary, up to six
   invariants to remember and two to six short sections, each with an optional
   diagram, then the exercise, what to observe and the check. A diagram is a
   FLOW of steps or a TREE whose nodes name their parents, two to ten short
   boxes that ByteQuay validates and draws; the model never writes markup. An
   answer with only an explanation is refused and written again, and a lesson
   written before chapters is written again when next opened.
2. The window is a book. The expanded window shows the course's contents at the
   left, each part with its chapters, done ones checked, and the open chapter's
   sections to jump to; the chapter in the middle with its summary, what to
   remember, its sections with their diagrams, Try it and Check yourself, and
   Previous and Next; and the conversation at the right only when the user asks
   or answers the check. The floating window shows the chapter alone.

Progress: the twelfth slice is complete. The chapter is a validated record with
its sections and diagrams, the agent reads it as text when the user asks about
it, and the frontend draws each diagram as SVG with boxes sized to their text.

Done when: a lesson reads as a chapter with diagrams, the contents jump between
chapters and sections, the conversation opens on demand, and an old lesson is
written again as a chapter.

## Thirteenth implementation slice

Lessons read like an engineering handbook: diagrams carry the content, the text
adds only key context, and lessons have their own tab.

1. Diagrams first. The lesson writer is told to draw every structure, process or
   interaction and to keep text to key invariants, context, names and edge
   cases, in short declarative sentences or bullets. Two diagram kinds join FLOW
   and TREE: COMPONENTS, a system's parts in the columns of their groups with
   labelled arrows, and SEQUENCE, participants and their numbered messages.
2. A Lessons tab. Once a course is accepted the Goal window shows Guide, Lessons
   and Work; opening a chapter anywhere reads it under Lessons, and without an
   open chapter the tab shows the course with where to continue.

Progress: the thirteenth slice is complete. A diagram's edges join its own
nodes, a components or sequence diagram needs them and a flow or tree has none;
class names break at their camel-case humps, an arrow past the boxes of its
column goes around the column, and arrow labels never cover each other.

Done when: a chapter can show its components and a sequence of calls, the
writer is told to prefer them to prose, and the Lessons tab opens the course or
the open chapter.

## Fourteenth implementation slice

Tell the agent what a result changes: summaries carry what the work taught and
what follows it, and decisions name the work and milestones they open.

1. Summaries have five parts. The summary of accepted work is written in What
   changed, Evidence, Insights, Left open and Next, Next being the one follow-up
   its history points to.
2. Decisions name what they unblock. Accepting or dropping work tells the agent
   which of the Goal's open work it leaves ready, waiting on nothing open.
3. Lessons name the milestone. Finishing the last lesson of a course part tells
   the agent that part is complete and which part comes next.

A task start is not reported on its own: it is recorded inside the task's own
transaction, where telling the agent could stall the single database
connection, and the task's first finished turn already reaches the agent.

Progress: the fourteenth slice is complete.

Done when: an accepted result's event names the work it unblocks, the lesson
that finishes a part names that part and the next, and the summary prompt asks
for insights and the next step.

## Fifteenth implementation slice

Ground chapters in the code: a chapter is written from what CodeGraph finds for
it, the course has one map of its concepts, and the writer follows diagram
rules.

1. Diagram rules. The lesson writer is told that every box is one real thing,
   such as a component, a class or a piece of state, every group a real
   boundary, such as a process, a module or a service, and every arrow a real
   call, message or data flow labelled with what moves; that a diagram keeps
   one zoom level, named in its caption; that the question a diagram answers
   picks its kind; and that commands belong to the exercise and reference
   facts to a short table. The writer also sees the course in order, so a
   chapter builds on the ones before it.
2. A code map for each chapter. Before a chapter is written, ByteQuay asks
   CodeGraph one bounded question, the lesson's title and outcome, against the
   baseline of the Goal's repository, the index of its monitored default
   branch. The tool-free writer gets the symbols, files and relationships found
   at that commit, names boxes and arrows after them and links files at that
   commit. Without CodeGraph or a baseline the chapter is written from the
   notes alone. Research runs are told to name the classes and methods behind
   each note and how they connect.
3. A map of the course. The first chapter written for an accepted course first
   draws the course map: one COMPONENTS diagram of the concepts the course
   teaches and how they connect, from the notes, the course and a code map for
   the Goal. It is saved with the course, drawn at the top of the course
   overview and given to every chapter, so one thing keeps one name; a revised
   course draws a new one. A failed map leaves the chapter to be written
   without it.
4. Write again. A written chapter can be written again from the current notes,
   map and code.

Progress: the fifteenth slice is complete. The baseline query runs in an empty
source directory, so CodeGraph answers from the index alone, with no checkout:
symbols with their files and lines and how they relate. The course map is one
components diagram saved with the course; a course accepted before maps, or a
revised one, gets its map when its next chapter is written.

Done when: a chapter's request carries its code map, the course map and the
diagram rules, the course overview draws the map, and Write again replaces a
written chapter.

## Sixteenth implementation slice

Plan changes the agent proposes: changes to the priority, order and
dependencies of the Goal's open work, with a reason, which the user accepts,
adjusts or skips like a definition or a course.

1. A plan change in the reply. A reply may propose a plan change: the work
   items whose priority or whole list of open work they wait on should change,
   and optionally every open work item in a new order, with a one-line reason.
   A change that names work the Goal does not hold or has concluded, makes work
   wait on itself or on concluded work, closes a cycle, or orders anything but
   the Goal's open work fails the reply, which the user can ask again; the plan
   stays as it was.
2. Shown as the difference it makes. The Guide view shows the change against
   the work as it stands, one line per difference: a new priority, a
   dependency added or removed, the new order. Accepting applies those
   differences as the user's own decisions, each in its item's history, and
   tells the agent. Once nothing differs the proposal is gone; a newer plan
   change replaces it, and an older one cannot be accepted.
3. When to propose. The agent proposes a plan change when what the user said,
   learned or did, a blocker, a failure or finished work shows the order should
   change, or when the user asks for a new order; an unchanged plan needs no
   proposal. Changes to a work item's scope or criteria stay with the user.

Progress: the sixteenth slice is complete. Accepting stops work waiting before
it adds new waits, so no step on the way closes a cycle the whole change does
not, and the open work takes the places open work held, leaving concluded work
where it was. The Guide view works out what still differs from the work it
already shows, so no Goal record notes the acceptance.

Done when: a reply's plan change is validated before it is saved, the Guide
view shows its differences with Accept, Adjust and Skip, accepting applies
them through the work items' own decisions, and a cycle or an unknown item
fails the reply.

## Seventeenth implementation slice

Results and history, and the questions that matter: the Goal shows what each
piece of finished work achieved and every decision and event behind it, and
the agent reads the user's questions on the Goal's topics first.

1. Questions on the Goal's topics. The agent reads the user's private
   questions that share the most words with the Goal, its topics and its work
   first, the newest first among equals, then the newest others, instead of
   only the newest.
2. Results. The Done view shows each concluded work item's outcome: an
   accepted result in the user's words with the pull requests observed merged,
   or why it was dropped. Retries, comments and pull requests are its
   evidence, never extra outcomes.
3. History. The Work view's latest decisions name each event's kind, such as
   accepted, dropped, reopened, scope changed, priority, waits on, task turn or
   summary, and the work it belongs to, which opens its details. All history
   lists every decision and verified event of the Goal's work, newest first.

Progress: the seventeenth slice is complete. Questions rank by the words they
share with the Goal, as practice candidates do, and the topic levels the agent
saves may cite any question it was shown. A summary appears in the history by
its kind alone, since its work shows it in full.

Done when: the planner puts the questions on the Goal's topics first, a
finished item shows its result and evidence in Done, and All history shows
every event of the Goal's work with its kind and item.

## Eighteenth implementation slice

Discovery follows the repository: the agent hears about issues opened near the
Goal without being asked, and a card says when its source could not be read
again.

1. New issues near the Goal. Every half hour ByteQuay reads each defined,
   guided Goal's candidates locally. The open issues that share words with the
   Goal and were opened since its agent last looked, at most five, reach the
   agent as one background event, held with the others and through the quiet
   hours. When nothing new was opened nothing is told, so no model is asked.
2. Cards say when they rest on the last sync. When a practice card's issue or
   ticket cannot be read again, the card says so instead of floating silently
   from the cache.

Progress: the eighteenth slice is complete. The Goal's last exchange is the
fingerprint: once the event is told, the same issues are no longer new. Cached
Jira tickets do not say when they were opened, so a new ticket still waits for
the next exchange.

Done when: a defined Goal hears once about an issue opened near it, a quiet
repository asks no model, and an unreadable source shows on its card.

## Nineteenth implementation slice

Guidance from real status: the agent hears what the pull requests of the
Goal's work are waiting on, and may propose new criteria for a work item.

1. Real status reaches the agent. Besides merges and reviews requesting
   changes, a linked open pull request's approvals and the checks failing on
   its head commit are recorded once on the work and told to the agent, which
   treats failing checks as recovery; that the pull request is open and waits
   for review is recorded once and goes with the agent's next event. The
   History view names each receipt by what it records.
2. Criteria in plan changes. A plan change may give an open work item new
   acceptance criteria, shown as the difference it makes and applied as the
   user's scope change when accepted.

Progress: the nineteenth slice is complete. A receipt still moves its work to
the next revision, so a decision the user makes while one lands is sent again
once at the new revision when nothing it rests on changed. A criteria change is
the user's scope edit when accepted, recorded in the item's history.

Done when: an approval and failing checks reach the agent once each, the
History view names them, and a plan change can rewrite a work item's criteria.

## Twentieth implementation slice

Prepared starts: a card says what comes first and when its work is done, and
Start opens a task the user only needs to review.

1. What comes first and when it is done. A card may name what the user should
   know or have done first, shown under its reason, and when its work is done,
   shown with its scope on hover. A work or investigation card's criteria become
   its work item's acceptance criteria; its scope stays the instruction.
2. A prepared task. Start on a work card, Open the composer and Start another
   attempt open the ordinary composer with a request prepared from the work's
   accepted criteria, the card's scope and uncertainty, and the Goal's objective
   and constraints, which the user reviews and sends. Source-free Goal work, such
   as an investigation, starts its task the same way from its details, as that
   work's attempt.

Progress: the twentieth slice is complete. The Goal context a task carries is
the request the user sent; nothing hidden is added to the task's guidance. The
request is prepared from the work as it stands when the composer opens, and a
composer left open while the work's criteria change sends what the user
reviewed.

Done when: a card shows what to do first, the work it becomes is accepted
against its criteria, and Start opens a composer ready to send.

## Twenty-first implementation slice

Results that outlive their task: the Goal keeps what a task did after the task
and its log are gone.

1. Receipts before removal. Before runtime cleanup or deletion removes a task's
   event log or rows, the Goal work it attempted records each of its turns not
   recorded yet. When that fails, cleanup stays pending for its retry and the
   deletion is refused, so the task and its log stay.
2. The receipt outlives the agent. Telling the agent stays best effort: a
   cancelled Goal's work still records the turn though no agent hears of it.

Progress: the twenty-first slice is complete. Summaries that fail stay retryable
with Summarize, and a cleanup that fails for any reason stays pending without
changing the task's terminal facts.

Done when: deleting a task or letting retention clean it up never loses a turn
the Goal had not recorded, and a refused removal leaves the task whole.

## Twenty-second implementation slice

Remember what research found in a specification, and record how research is
confined.

1. Remember for spec notes. A knowledge note citing a specification page offers
   Remember, which makes it a SPEC memory of the workspace, the user's own,
   through the existing capture policy: its topic the title, its text cut to a
   memory's size in whole sentences, its pages the sources and the files of the
   repository it cites the scope. The note then names the memory. A note citing
   no page offers nothing, since repository memory keeps no description of how
   code works.
2. Research stays unblocked. The user declined a read-only tool policy for
   research runs, so the design records that instructions and a disposable
   worktree confine them.

Progress: the twenty-second slice is complete. Remembering again while the
memory is kept changes nothing; once the user deleted it, the note offers
Remember again.

Done when: Remember on a spec note adds one SPEC memory and the note names it,
and a code-only note cannot be remembered.

## Twenty-third implementation slice

One next action in Needs attention: the Goal's card and its source's card do not
both ask for the same thing.

1. Both views read the pinned Goal the same way. The global and workspace Needs
   attention views already mark each card's place in the pinned Goal from the
   Goal's work; they now also know which issue or ticket a card under the Goal
   control offers as practice.
2. One card per next action. While such a card is shown, the source's own card
   folds away unless it asks for an approval, a review or a recovery; Ack and
   Snooze keep working on it, and it returns once the Goal's card is started or
   set aside.

Progress: the twenty-third slice is complete.

Done when: an issue the Goal offers as practice shows once, as the Goal's card,
and a pull request waiting for the user's review still shows on its own.

## Twenty-fourth implementation slice

What the agent reads: the live check showed the agent seeing notes by topic
only, so its course and cards rested on titles.

1. The nearest notes in full. The agent sees every saved note by its topic and
   reads the six that share the most words with its message in full, then those
   nearest the Goal, then the newest; a lesson still reads them all.
2. Each answer keeps what it rested on. The notes in full out of all notes, the
   user's questions from private cards and the practice candidates out of the
   open issues are saved with the answer and shown under it as counts, with the
   words on hover.

Progress: the twenty-fourth slice is complete.

Done when: an answer about a topic the notes cover reads those notes in full,
and the Guide view says what each answer read.

## Twenty-fifth implementation slice

Fewer model calls: an answer keeps a digest of the facts it was given, so asking
again about the same facts asks no model.

1. A persisted input fingerprint. Each answer saves a digest of everything the
   agent was given about the Goal but the conversation: its definition, course,
   work, cards, notes by topic, candidates, topics and the user's questions.
2. Update now asks only when something changed. With no held event and the
   same digest as the agent's last answer, Update now is refused with "Nothing
   changed since the agent's last answer" and no model is asked.

Progress: the twenty-fifth slice is complete. Background events were already
told once each and discovery already waits for new issues, so the digest only
had to cover the user's own Update now.

Done when: a second Update now with nothing new asks no model, and one after the
work changed does.

## Twenty-sixth implementation slice

Pages that exist: the live check showed the agent naming research pages from
memory, so ByteQuay opens them before the card floats.

1. Research pages are opened once. Before a research card floats, each page it
   names is asked for with one request, all at once. A page that is not found,
   gone, malformed or on a host that does not resolve is left out of the card,
   and the card's uncertainty names it. A page that refuses the request or times
   out is kept, since it may well exist.

Progress: the twenty-sixth slice is complete.

Done when: a research card floats without a page that does not exist and says
which one it left out.

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
- [x] Keep Epic classification independent of Goal guidance: marking and
  unmarking create or archive no Goal. Preserve existing source references and
  accepted work/history; every Goal has its own archive action.
- [x] Inspect development data before changing the Goal mapping: no Goals or
  source-task rows existed. No runtime data was changed or reset.
- [x] Add explicit another-attempt admission to GitHub/Jira stores and both
  services. Keep opening the current task by default; require the previous attempt
  to be terminal with delivery settled before a new task can be admitted.
- [x] Add unique accepted-proposal mappings when Goal proposals arrive in Step 3.
- [ ] Add explicit accepted deliverable/base/criteria mappings and automatic
  merged-delivery evaluation with outcome harvesting in Step 5. The current
  result-acceptance action always requires the user's scope confirmation.
  Left out: accepting a result stays the user's own decision, even after a merge.
- [x] Add blocker-aware moves when same-Goal dependencies arrive in Step 6:
  work with blockers or dependents cannot leave its Goal.

Checks cover SQLite revision conflicts and ownership rollback, source cache loss,
reference grouping, abandoned and open PRs at acceptance, command retry, reopen,
independent Epic marking, Goal archival and moving work without losing evidence.
SQLite-backed launch checks prove one work item per issue attempt and refusal of
Dropped or other-workspace work before the source fetch; a Jira check proves
reassignment is refused while a work item owns the ticket. A Chromium journey exercises a
failed result save, the same-operation retry, reopening and detached history.
Accepted proposals map to work by identities derived from them: a definition by
its exchange, a course's lessons, a practice or investigation card's work and a
reproduced gap's work by their ids, and a plan change's decisions by their
operations, so accepting or starting again finds the same work, as
`TestGoalConversationService` and `TestGoalSpecChecks` show. The remaining
unchecked item is not represented as implemented behavior.

Verification on the committed tree: backend `mvn verify` (3,396 tests),
frontend lint and TypeScript checks, all 1,421 frontend tests including the
work-item and Goal window Chromium journeys, and desktop packaging passed.

## Step 3 Add the Goal conversation and proposals

- [x] Add durable Goal exchanges and versioned proposals beside the existing Goal
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
- [x] Validate typed model replies, evidence IDs, reference ownership, proposed
  dependency cycles and size limits before saving. Invalid output leaves the
  previous plan intact and produces a retryable error.
- [x] Include current assessment, one consequential clarification, short suggested
  answers and a prepared supported action in that same reply. Reuse known
  context; do not repeat answered questions or infer unprovided preferences.
- [x] Ask what the user already knows before proposing a course, and save the
  accepted starting knowledge with the Goal and as initial topic levels.
- [x] Add recommendation records and cards under the Goal control: kind, title,
  reason, Start and Not now, at most three, newest first. Nothing starts without
  Start, a dismissed card does not return unchanged, and cards of one kind do
  not stack.
- [x] Extend `GoalController`, main/preload bridge and `frontend/src/goals` with
  suggested-answer and free-text messages, editable suggested title and Accept /
  Adjust / Skip proposal controls. An existing vague Goal receives clarification
  without requiring a separate Plan with agent prompt. Manual entry stays secondary.
- [x] Refactor `GoalPanel` around current situation, one primary agent intervention,
  compact path and recent result. Empty Goals show helpful directions, not 0/0,
  empty board columns and source-search instructions. Preserve current app styling.
- [x] Preserve `GoalLayer` pill, pinning, floating window and expansion. Show one
  meaningful attention cue and keep drafts/selection stable across modes. Keep
  one panel mounted across resizing and expansion, hidden from interaction when closed.
- [x] Keep Accept plan separate from Start. Draft proposals do not affect progress.

Evidence for the checked items: `TestGoalConversationService` (a slow reply
stopped, restart settlement, cards that float only once the Goal is defined and
never stack or repeat a dismissed one, course acceptance), `TestTextOnlyHelper`,
`TestModelSettingsService` for the Goal's own model, the Codex and Claude driver
text-only session tests, `TestGoalPlanner` and the `goalGuideBrowser` and
`goalWindowBrowser` journeys, and for validation the plan-change tests in
`TestGoalConversationService`, which also keeps each answer's coverage.

Done when: opening an empty Goal or one named “Iceberg connector expertise” offers
useful directions; a suggested answer can lead to a saved goal definition and
proposed path without typing a prompt. A free-text request works equally well.
Neither flow allocates an execution task, Git branch, worktree or GitHub write.
An edited or duplicated acceptance cannot overwrite newer decisions or duplicate
work items. Closing and reopening the app retains the conversation and plan.

## Step 4 Research, teach and discover work

- [x] Add the approved research task kind: noninteractive, confined to reading by
  its instructions and its disposable worktree, as the user decided instead of a
  read-only tool policy, started only from a card, reading the Goal's workspace,
  its Dependency workspaces and the pages the card names, and saving notes
  through one fixed task-bound tool.
- [ ] Store knowledge notes with topic, sources and staleness. Mark notes stale
  when a cited specification or path moves; offer a repository-memory Remember
  through the existing capture policy. Left out for pages: notes, staleness for
  cited files and Remember are built, but cited pages are not read again, since
  repository memory gave up page captures on purpose.
- [x] Propose a course of parts and lessons from the notes and the starting
  knowledge. Accepted lessons become ordered Learn items with a milestone; a
  tool-free request writes and saves each lesson as an illustrated chapter.
- [x] Show the open lesson in the expanded Goal window beside the conversation.
  Questions there belong to the lesson; Done stays the user's decision.
- [x] Recommend spec checks as cards naming the module, pages and reproduction
  count. Start runs the existing check and Adjust opens its drawer; a reproduced
  gap becomes a proposed work item with its draft issue, which the user files.
- [x] Build a Goal context reader over existing issue, Jira, PR and task stores.
  Use exact monitored/mapped workspace scope and identify incomplete coverage.
- [x] Add deterministic eligibility and relevance filtering before a bounded model
  shortlist. Preserve high-priority items before display limits. Never suppress
  uncertain duplicates or possible fixes as if they were confirmed.
- [ ] Refresh shortlisted sources through existing services. Add only the missing
  duplicate/closing-PR evidence reads needed for the readiness decision. Record
  source revision, freshness and skipped coverage. Left out for the record: a
  card's source is read again before it floats and at Start, but its revision and
  freshness are not kept.
- [x] Supply at most 100 compact candidates, inspect at most five selected sources
  and display at most three recommendations. Reject model-invented IDs and URLs.
  Cache conclusions by input revision; unchanged candidates need no new model call.
- [x] Include reason, scope, prerequisites, completion criteria and uncertainty
  on each proposal. Allow source-free learning/investigation items.
- [x] Trigger discovery as the next useful step of an active guidance request or
  meaningful source/work change. Users need not open search and formulate a new
  query. Explain insufficient coverage instead of presenting arbitrary candidates.
- [x] Where code has not been inspected, recommend a research run or an
  investigation card. Pass its result back to the Goal; never label a
  metadata-only assessment as code verification.
- [x] Extend canonical task creation with an optional work-item reference and the
  accepted Goal context. Preserve current composer defaults, source refresh,
  readiness, worktree selection, publication authority and replay behavior.
- [x] Prepare the execution request from accepted scope, evidence and constraints.
  Start opens the existing composer ready for review and submission; the user
  need not rewrite the agent's proposed task in another input.
- [ ] At Start, recheck source identity, role, state, work-item revision and linked
  work. Open an existing task when appropriate; preserve the draft on failure.
  Left out for the revision: the composer is prepared from the work as it stands
  when it opens, and the user reviews the request before sending it.

Evidence for the checked items: `TestGoalLessonService`, `TestGoalSpecChecks`,
`TestGoalCandidates`, `TestGoalResearchService` for runs, stale notes and
Remember, `TestGoalPages` and the research-card test in
`TestGoalConversationService` for pages that do not exist, `TestGoalDiscovery`,
the card parsing in `TestGoalPlanner`, the investigation-card test in
`TestGoalConversationService`, the prepared request in `goals.test.tsx` and the
`goalGuideBrowser` journey. Still partial: cited pages are never checked for
staleness; shortlisted sources record no duplicate read or revision, though a
card whose source could not be read again says so; spec-check pages are not
opened before their card floats; conclusions are cached by the Goal's last
exchange and the digest of its facts, not per candidate; new Jira tickets do not
trigger discovery; and Start rechecks the source, its role, the work's state and
its current task, but not the work item's revision.

Done when: a research card saves sourced notes, an accepted course opens a lesson
in the Goal window, and a spec-check card turns a reproduced gap into a proposed
work item. One actual GitHub issue and one mapped Jira ticket can each be found,
explained, accepted and started through the existing task UI. A changed or
already-started source cannot slip through stale recommendations.

## Step 5 Record outcomes, learn the user and propose the next step

- [x] Add topic levels (new, learning, practiced, strong) with their evidence:
  lessons finished, check answers, questions and merged work. The agent proposes
  changes with evidence; a user correction wins.
- [x] Read the user's private cards from `task_discussion`, `local_review_thread`
  and `agent_review_message` in every workspace, locally. Select those on the
  Goal's topics, cite them in assessments and treat their text as reference data.
- [x] Pace recommendations: practice an area until it is practiced, then advance
  the course; propose the next spec check only after the last one's gaps are
  resolved or dropped; put blockers, failures and reviews first.
- [x] Connect task settlement and existing source refresh to idempotent factual
  receipts. Capture final result/check evidence before runtime retention removes
  it. Reconcile missed receipts after restart and guard linked-task retention or
  deletion until capture succeeds. Preserve missing-evidence and agent-reported
  distinctions.
- [x] Verify selected delivery rules through application facts. Provide explicit
  result acceptance for learning and investigation, and coverage review where a
  merge does not establish the entire accepted scope.
- [x] Generate a concise summary from those receipts: what changed, evidence,
  insights, unresolved work and next recommendation. Saving a summary cannot
  change completion state or accepted scope.
- [x] Reassess the next intervention from real status: running, failed, blocked,
  waiting for review, result ready or milestone delivered. Prefer recovery and
  outstanding user decisions over fresh discovery. A meaningful change updates
  guidance without “what next?” or manual refresh.
- [x] Propose specific plan differences when scope/order/dependencies should
  change, with their reason. Refresh recommendations within accepted constraints
  automatically; preserve accepted decisions until the user changes them.
- [x] Reconsider after meaningful linked work/source changes. Persist the input
  fingerprint, coalesce events and respect quiet hours and the design's call
  limits. Add Pause guidance and Update now; panel polling remains local.
- [x] Use request and input revision checks to discard stale proposed changes.
  An interrupted request stays retryable; do not launch or resend work on startup.
- [x] Save Goal insights locally with citations. Route a user-selected Remember
  action through the existing repository-memory policy, without a new memory engine.
- [x] Exercise archived Goal, task deletion, log expiry, summary failure and
  uncertain provider cleanup. Preserve receipts and user decisions in every case.

Evidence for the checked items: the spec-check and blocked-lesson card tests in
`TestGoalConversationService`, the delivery tests in `TestWorkItemService` and
`TestGoalQuestions` for questions on the Goal's topics, `TestGoalDeliveries` for
reviews, failing checks and pull requests waiting for review, `TestGoalReceipts`
and `TestDefaultAgentCompletionService` for receipts kept through cleanup and
deletion, the summary retry and the unchanged Update now in
`TestGoalConversationService`, and the facts digest in `TestGoalPlanner`. Still
partial: a task start reaches the agent only with its first finished turn, and
plan differences cover the course and the open work's priorities, order,
dependencies and criteria, not a work item's title or completion rule.

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
- [x] Feed global and workspace Needs attention from one work-item projection.
  Deduplicate source/recommendation cards for the same action; preserve urgent
  execution attention, Ack and Snooze.
- [x] Add results/history display with evidence links, scope changes, reopenings
  and factual coverage. Do not count retries, comments or PRs as extra outcomes.
- [x] Run browser journeys at normal and narrow panel sizes, including keyboard
  use, background updates, preserved drafts, stale responses and offline recovery.
- [x] Include an unsure-user journey using suggested answers only, a vague-title
  clarification, prepared task launch with no new prompt, automatic failure
  recovery guidance and proposed replanning after a blocker changes. Check visual
  hierarchy in floating and expanded view; the next useful action must be readable
  before statistics or board management. Opening the panel alone adds no model call.
- [x] Include the reference journey: an Iceberg expertise Goal clarified from a
  vague answer, research and a fitted course, a lesson in the Goal window, a
  Delta spec-check card reproducing a #31252-style gap, and a practice card that
  ends in a merged pull request recorded on the Goal.
- [x] Run `mvn verify`; frontend lint, `npx tsc --noEmit`, tests and packaging.
  Complete opt-in live planning/source handoff smoke checks for supported lanes
  and report live-service limits separately from fixture results.
- [x] Update the canonical agent, architecture, issue and Jira design sections to
  the implemented behavior and remove superseded descriptions. Mark checklist
  entries complete only with their actual evidence.

Evidence for the checked items: `TestWorkItemService` for priority, order and
blockers, and the `workItemsBrowser` journeys for the views, the results Done
shows and All history. Both Needs attention views fold a source the Goal's card
offers as practice, as `AttentionList.test.tsx` and `goals.test.tsx` show. The
`goalGuideBrowser` reference journey walks a vague Iceberg goal through a
suggested answer to a definition, research and the course fitted to its notes, a
lesson read in the Goal window, a Delta spec check whose reproduced gap becomes
the goal's work, and practice on that gap whose merged pull request is recorded
on the work. The agent service, architecture, agent task UI, repository memory
and Jira designs describe what is implemented. The `goalGuideBrowser` journeys
clarify a vague goal, take questions that arrive on their own, keep a draft
whose send failed and resend it as the same operation, start research and a
prepared practice task, and accept a plan change; one of them guides an unsure
user by suggested answers alone, by keyboard, in a window 380 pixels wide, where
nothing scrolls sideways, opening the window asks no model and a read from
before an answer, arriving after it, does not undo it. `goalWindowBrowser` keeps
drafts through moves, resizes and reopening. Still partial: no journey shows the
agent's recovery guidance after a failed turn, which `TestGoalPlanner` and
`TestGoalConversationService` cover in the backend, and the visual hierarchy is
checked only for fit.

Live check, 2026-10-05, on the user's Lakekeeper Goal, apart from the fixture
results above: the Goal's agent and its lessons on Claude Code (Opus, Max
effort), research on Codex. The first research run saved 20 notes in about 20
minutes. The agent's answer to it proposed a course of four parts and 16 lessons
fitted to the first pull request the user had chosen, and a second research card
for what the notes missed; accepting the course made 16 Learn items. The first
lesson, with the course map before it, took about 24 minutes to write and came
back with six sections, each with a diagram. Asked for practice while a card was
shown, the agent kept to one card at a time; after the second run saved 19
notes, it recommended issue #159 with what to do first, five done-when criteria
and its uncertainty, and Start made the issue Develop work against those
criteria. Every reply validated, and no task started. Limits seen: the agent
reads notes by their topics only, so its course and card scopes rest on titles;
a lesson at Max effort is slow; and the reference journey's spec check and
merged pull request were not part of this run.

Done when: the full Goal conversation → research → course → lesson → proposal →
accepted work → task → verified result → summary → next proposal journey works,
and all panels agree about it.

## Later additions

Only after usage establishes a need: cross-workspace Goals, scheduled discovery
while the app is not running, longer-term trend reports with adequate recorded
history, custom saved views, cycles/modules, YAML import/export and external
agent integrations. None is necessary for the first complete guidance loop.
