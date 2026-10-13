# ByteQuay user guide

What ByteQuay is, what it aims to do, and short guides to its main features.

- [What is ByteQuay?](#what-is-bytequay)
- [Get started](#getting-started)
- [Home and workspaces](#workspaces)
- [Find and review pull requests](#pull-requests)
- [Ask for a private agent review](#private-review)
- [Work with coding agents](#agent-tasks)
- [Deliver changes and repair CI](#delivery-and-ci)
- [Turn a goal into useful work](#goals)
- [Keep related work together with Epics](#epics)
- [Build repository memory](#memory-and-specs)
- [Start from an issue or Jira ticket](#issues-and-jira)
- [Read Gmail and Google Calendar](#gmail-and-google-calendar)
- [Move changes between branches](#commits-and-backports)
- [Follow people with Circles](#circles)
- [When something needs attention](#help)

<a id="what-is-bytequay"></a>

## What is ByteQuay?

ByteQuay is a macOS desktop app for GitHub pull-request work and local coding agents. It
brings repositories, reviews, tasks, and the context behind a change into one place.

### What is its goal?

“Review is all you need” expresses the direction: spend more time understanding and
deciding, with agents helping investigate, implement, and verify the work.

- **Keep work connected.** Follow a change from an issue or goal through development,
  review, and delivery.
- **Make changes understandable.** Read the code, checks, explanations, and discussion
  together.
- **Keep you in control.** Choose what to run, accept, publish, and merge. Review
  automation choices before starting a task.
- **Keep the workspace close.** App state and working copies live on your Mac. GitHub
  and model-provider requests still use external services.

### Three useful concepts

- A **workspace** is the home for one repository and its work.
- A **task** is an agent conversation with its own working copy and execution history.
- A **goal** describes what you want to achieve. Its **work items** track outcomes; a
  task is one attempt to produce an outcome.

ByteQuay is pre-1.0. These guides describe the current development version; installed
releases may offer fewer controls. Start with one repository and one pull request.

<a id="getting-started"></a>

## Get started

1. **Install ByteQuay.** Download the DMG from [GitHub
   Releases](https://github.com/bytequay/bytequay/releases), open it, and drag
   ByteQuay.app to Applications. Current builds require macOS 14+ and JDK 25; follow the
   instructions for your release.
2. **Connect GitHub.** On the welcome screen, choose **Use my GitHub CLI login**. If
   needed, run `gh auth login` in Terminal and retry. Alternatively, enter a personal
   access token and choose **Connect with token →**. The app recommends a classic token
   with `repo` and `read:user` scopes.
3. **Watch a repository.** From **Home → Repos you watch → + Watch**, search for a
   repository. Check the source branch, push destination, and PR destination, then
   choose **Watch repository**.
4. **Open a pull request.** Open **Pull requests**, select **Review** or **My work**,
   and choose a PR. Read its checks and activity, then open **WorkBench → Changes**.
5. **Set up an agent when you need one.** Install Codex or Claude CLI, then follow
   [Agent tasks](#agent-tasks) to sign in and select a model. GitHub connection and
   agent-provider sign-in are separate.

You can start by browsing and reviewing PRs without starting an agent task. Manage
GitHub credentials later in **Settings → Integrations → GitHub**.

Code & Tests offers IDEA Light and Darcula themes with Atom One-style syntax
colors, plus **GitHub Dark** with a dark canvas and GitHub-style syntax colors.
License headers and imports fold on first open; expand them with the
gutter arrows. Symbol search, callers, and usages remain available through the
editor shortcuts and context menu. Press **Shift twice** or **⌘⇧F**,
or click **Search**, to open Search Everywhere. Exact class and file names rank
first. Other results put normal classes before test classes, then fields and other
symbols, files, and matching source lines. **All** combines a class and its matching
source file into one result that opens the declaration.
The popup shows up to 30 matches and skips content search when name matches fill it.
Module names appear on the right; test-file rows are green. Filter with **Classes**, **Files**,
**Symbols**, or **Text**. **⌘F** opens the same popup for text in the current file,
including unsaved edits. Use **↑/↓** and **Enter** to open a result, or **Escape**
to close. Search respects project ignore and exclude settings; refine the query
when results are limited. **All** favors names and source code; use **Files** for
paths or **Text** to search other content, including fixtures and logs. Changing
the query cancels the previous search; slow searches finish with the available
results and a notice. **⌘-click** a method to see its usages. A single usage
opens directly; otherwise a popup lists filenames, line numbers, and source
lines, with test-file results in green. Usages in the current file appear
first while the rest of the project is searched. Usages inside library JARs
and the JDK are not listed. Select a result to jump there, or press
**Escape** to close the popup. If the language service is still preparing
the project, the popup waits and runs the search automatically when it is
ready. Java code is analyzed
with the newest installed JDK, release 21 or later, so install the release
your build plugins require.
Working-copy reset and discard are in the
**…** menu and require confirmation.

<a id="workspaces"></a>

## Home and workspaces

Home shows your profile, contribution history, watched repositories, and recent
activity. A repository workspace keeps its tasks, PRs, issues, commits, and memory
together.

Workspace home always includes **My PRs**, with up to two of your open or draft
pull requests. Unread updates appear first, followed by the most recently updated
PRs. Opening a card keeps it in **My PRs** while the PR remains open. Use **Snooze**
to hide it until the chosen time. These cards have no acknowledge action.

Updates to merged or closed PRs, including the merge or closure itself, appear in
**Needs attention** until read or acknowledged.

1. Choose **Home → + Watch** or **Workspaces → New workspace**, then select a
   repository.
2. Choose **Direct clone** or **Personal fork** in the confirmation dialog. Private
   and internal repositories, and repositories you own, recommend direct clone.
   Public repositories owned by someone else recommend a personal fork.
3. Direct clone follows the selected repository's commits, issues and pull requests,
   and pushes work branches there. You need write permission to publish development
   changes; without it, you can still clone and follow the repository. Personal fork
   follows the upstream repository and publishes work branches to your own fork.
4. Review **Followed for updates**, **New branches start from**, **Work branches push
   to**, and **Pull requests open against**, then choose **Watch repository** or
   **Create fork and watch**. Changing the workflow does not create a fork until you
   confirm.
5. Open the workspace. Use its sidebar to switch between **Tasks**, **Pull requests**,
   **Work items**, **Commits**, and **Memory**. Commits becomes available after
   the repository has cloned.

**Unwatch** moves the workspace to **Workspaces → Retired** and retains local history.
Use **Watch again** to resume following it. **Delete… → Delete local data** is a
separate confirmed removal of local workspace data; it does not delete a GitHub
repository.

<a id="pull-requests"></a>

## Find and review pull requests

1. Open **Pull requests** globally or within a workspace. Choose **Review**, **Open**,
   **My work**, **Monitored**, **Reviewed**, or **Closed**. Hidden tabs are available
   under **More filters**.
2. Search by title, `#number`, or `@author`. Clear the search to return to the selected
   filter.
3. Open a PR. **PR** contains its overview, checks, and activity. **WorkBench →
   Changes** contains changed files and commit selection.
4. Select a diff line or range, write feedback, and choose **Add to review**. Replies
   to existing review threads also offer **Add to review**; **Reply** posts immediately. When
   ready, choose **Submit review**, inspect the pending comments, select **Comment**,
   **Approve**, or **Request Changes** where allowed, and submit.
5. Use **Monitor PR** (the bell) to keep a PR in **Monitored**. **Stop monitoring PR**
   removes it from that list. Merged PRs cannot be monitored; monitoring stops
   automatically when ByteQuay detects a merge.

**Post now** publishes an individual comment immediately. Review submission and merge
controls write to GitHub; inspect the outgoing content and selected action first. Your
own PR cannot be self-approved.

The **Review comment index** at the left edge of **PR** and **WorkBench → Summary** jumps
to human and agent comments, including private discussions. It shows up to 30 markers,
starting with the latest comments; scroll up within the index to reach earlier ones. Hover a dash marker
to see its author and the first 20 words of the comment (up to 240 characters).
Keyboard focus shows the same preview; Escape dismisses it. Selecting a marker
opens collapsed discussions. The index follows the current findings filter.

### Check failures and merging

Expand the failing-check group in **PR**, then choose **Show log** for a GitHub Actions
job. **Open in GitHub** provides the complete log. **Rerun failed tests** appears when
your permissions and the failed checks support it.

The header’s merge/check-status control shows the actions available for that PR, such as
**Ready for review**, **Rebase and merge**, or **Merge when ready**. Monitoring with the
bell does not enable automatic CI repair.

## Explore a pull request locally

For your own PR, **Development task** opens its existing conversation or prepares a
linked task named after the PR. The PR pill and side panel stay available while
an agent is running. Opening the task does not send a prompt or start agent work:
ask a question or request a change when ready. PR background context is supplied
to the agent with your first message without adding a generated user message to
the conversation. An open task retains its conversation and provider session.
Completed tasks open their retained conversation without creating another task.

**Review task** opens the separate reviewer, including a reviewer started from
the development task before the PR was published. If no reviewer exists, it
starts a review of the PR and opens that conversation.

For a PR in a watched repository, open **WorkBench → Code & Tests** and choose
**Create worktree to explore**. ByteQuay checks out the PR branch's latest commit
into a managed local worktree and opens the editor. For an existing working copy,
choose **Start IDE** if its code server is not running. Visiting the tab reuses a
ready server; it does not start a new one without confirmation.

The confirmation explains the local cost: the code server and language tools use
additional memory and CPU, indexing takes time, and worktrees and index caches
use disk space. Usage depends on the repository and languages. Opening the IDE
does not start an AI agent or use model tokens.

Open a changed file to see gutter markers: green for added lines, blue for
updated lines, and red at deletion points. Markers compare saved contents with
the base and refresh after saving; they hide while the editor differs from the
saved file. Retained read-only files show the same markers.

Browse files, edit code, or run tests without starting an agent review. When you
choose **Start review** in **Summary**, the reviewer reuses this working copy.
An existing development task's working copy remains available directly. Before
starting a review, **Summary → Remove worktree** releases a clean exploration
checkout; saved edits must be discarded first.

Use the play button beside a test in the editor to run or debug it. **Run** and
**Debug** show their last selected or newly started tests with the output log.
Each panel keeps its own **history** picker for earlier results. Debugging tests
uses the supported test runner without a separate launch configuration.

<a id="private-review"></a>

## Ask for a private agent review

Use a private review to understand a change and investigate findings before deciding
what to share. First watch the repository and configure a ready Review model in
**Settings → Agents → Models**.

1. Open a PR and select **WorkBench → Summary → Start review**.
2. Read the change explanation and selected findings. Choose **View in Changes** to
   inspect the relevant code, or **Ask reviewer** to discuss a finding privately.
   Open **Review details** to inspect the recorded candidates, evidence, and coverage
   of behavior, interactions, and design. Progress checkpoints remain provisional
   until the review finishes; other recorded findings stay available in these details.
3. Choose **Add to my review** to copy a finding into your editable pending review.
   Inspect it and use **Submit review** when you want to publish it.
4. Use **Update review** after the PR changes. Later reviews refresh the findings and
   their supporting investigation. The saved change explanation stays attached to
   its original commits and reviewed head. Use the refresh icon beside **Change intention**
   to regenerate the intention and commit summaries from the latest committed version,
   independently of findings. The existing explanation stays visible until refresh succeeds.
   **End review** ends the reviewer task and
   releases its working copy; saved findings and discussions remain.

For development before a PR exists, open the task’s **WorkBench → Summary → Review
changes**. This reviews committed changes, including unpushed commits; it excludes
uncommitted edits.

Choose **Request fix → Send to developer** on a finding to ask its development
agent to address it. The conversation shows a compact **Fix the agent review**
card with the finding number and title. Click it to open the original finding,
or expand its details to inspect the full instructions and review context.

The development agent can also read the saved findings itself, so you can ask in
its task for several at once, such as “fix the open findings”. It reads the review
of its own changes and the open reviews of its pull request; a later task on the
same pull request reads them too. Its reply appears on the finding as **Developer**
and stays private. It resolves a finding only after fixing it and replying; the
card then says **Resolved by Developer** and you can **Reopen** it. A finding it
rejects stays open with its reason, for you to decide. **Update review** still
has the reviewer check the open findings.

### Discuss without publishing

- In a development task or review conversation, highlight text in a finished agent
  answer and choose **Ask about this → Ask** for a private side conversation. Reopen it from **Discussions**.
  The card keeps your question in view; expand **Selected passage** for the full quote.
  The agent can read saved context as needed and check the code, but cannot change it.
- On someone else’s GitHub comment, **Ask agent** opens a private discussion beside
  the comment. Send your question to prepare the PR’s code context automatically;
  a full review is not required. Existing task or review context is reused. If setup
  is needed, use **Watch repository** or **Model settings** and return to your saved
  question. Starting a review later preserves the discussion.
- **Draft with agent** fills an editable reply. It stays **Draft · not posted** until
  you choose **Comment**.
- **Resolve locally** changes the private discussion or finding; it does not resolve a
  GitHub review thread.

<a id="agent-tasks"></a>

## Work with coding agents

### Check account usage

Choose **Usage** at the bottom of the sidebar, then select Codex, a Claude
account, or a saved API-key account. API keys appear with the provider and the
alias you entered, such as **DeepSeek · ds-001**. DeepSeek shows the account’s
available credit balance and warns when it is insufficient for API calls. For
other API-key providers, check the provider’s billing dashboard.
Each reported limit uses one bar: gray is usage within the elapsed
period's pace, green is room up to that pace, and red is usage beyond it. The
reset countdown and “Room to use more” or “Slow down” label explain the comparison.
These are pacing suggestions, not a guarantee of how many requests remain.

Usage includes activity outside ByteQuay. The panel refreshes while open and
caches checks for up to a minute. Missing or expired data has no pace colors.
Claude usage requires Claude Code 2.1.293 or newer. Account plans expose different
limits; Enterprise monthly amounts appear only when the provider reports them,
and pace needs known period boundaries. Unavailable limits are not shown as zero.

### Connect a provider

You only need the provider you want to use: Codex, Claude, or a supported saved
API key. A Claude account is not required for Codex or API-key tasks. New-task
composers keep the configured account when it is unavailable and ask you to
finish setup or explicitly choose another provider. Drafting and **Backport** remain
available while you set up a provider.

1. Install the Codex or Claude CLI on your Mac, then open **Settings → Agents →
   Models**.
2. Under **Providers**, choose **Sign in** if needed and complete sign-in. Choose
   **Check** and wait for **Ready**.
3. Choose the **Development** model and a **Review** model, or **Same as Development**.
   Repository settings → **Model overrides** lets one repository use different choices.
   Each selector names the account or API-key alias alongside its models.

An account added under a repository's **Claude accounts** is selected for both
Development and Review there. It is hidden from other repositories and cannot
run their tasks. Accounts added in global **Models → Claude accounts** are
available to every repository. To restrict an existing global account, open the
intended repository and choose **Use only here**; its saved sign-in is retained.
New tasks follow these defaults. Existing tasks retain their original account,
but cannot start another turn if that account becomes restricted to a different
repository. DeepSeek aliases can be selected as defaults; unsupported API-key
providers remain disabled.

### Start a task

1. Open a workspace’s **Tasks → + New task**.
2. Describe the change in **Let’s ship…**. Include the outcome you want, relevant
   context, and how you will judge success. Select the provider and model.
3. Open **Automation** and review the publication and CI choices described below. They
   lock after the task starts.
4. Choose the upward **Send** arrow or press Enter. ByteQuay prepares a separate branch
   and working copy for the task. Shift+Enter adds a line; **+** attaches an image when
   the selected model supports it.

The initial branch name is provisional. Once the actual change is clear, the agent
names it `<username>/bytequay/<actual-change>` before finishing its first development
turn or pushing, for example `chenjian2664/bytequay/convert-parquet-time-millis-to-micro`.
Renaming is available only during the first turn. If that turn ends without a
concrete change, the initial name stays. Later turns always keep the branch name,
even when it has not been pushed.
Published branches and tasks imported from existing pull requests keep their names.

### Guide the work

- **Steer** sends information into the active turn when supported. **Run next** queues a
  later message; **Resume queue** continues a paused queue after you address its cause.
  To edit a waiting message, clear the composer and press **Up Arrow** to move the
  first queued message back into it, or click the message’s pencil button. Its full
  text, images, and model settings return for editing. Submit it again when ready.
  Claude steering requires Claude Code 2.1.295 or newer. It delivers your update while
  the agent works; a command already running may finish before Claude acts on it.
- On **Approval needed**, inspect the action and permissions, then choose **Allow
  once**, **Allow for this task** if offered, or **Deny**.
- Use **Stop this turn** to interrupt the agent. Use **Stop automation** to stop
  delivery follow-up.
- Inspect **WorkBench → Changes** and **Summary** before accepting the result. **Code &
  Tests** provides file browsing, an available targeted test runner, and a terminal; the
  built-in runner currently supports the Maven-wrapper profile.
  For Claude tasks, a newly opened terminal uses the task's Claude account.
  Existing terminal shells keep their environment until you exit and reopen them.
- When idle with its queue handled, choose **Mark it complete** (the checkmark) to
  finish the task. **Archive** moves it out of the active list; **Delete** separately
  removes its history and generated local work.

If sign-in expires, sign in again and refresh login status. Where offered, inspect
**Retry last message…** before resending. A **Delivery uncertain** message needs
inspection; it is not automatically resent.

App notices stack in the bottom-right corner when a development or review task finishes a turn,
asks a question, requests approval, fails, or has queued messages paused. Choose
the card’s button to open that task or review conversation and close the card.
While you’re viewing a task or review in the active ByteQuay window, its notices
stay quiet, including while you scroll older messages or work in its editor.
They remain available in the counter without popping up when you leave the task.
Completions stay available for 24 hours, including when ByteQuay restarts.
Up to two cards overlap,
with the next card peeking out behind the current one. Cards use compact, two-line
titles and previews; hover over clipped text to read it. **Notices** or **Needs you**
opens navigation through the remaining items, one at a time. At least
one card stays visible until you open its task or close it with **×**. Closed cards
stay in the counter and can be reopened from there; new notices still pop up.
Choose **→** beside the counter to fold the stack into a narrow tab on the right
edge while you type. The tab keeps the count, including new arrivals, without
reopening the cards. Choose **←** to show them again. Folding clears when all
notices are gone or ByteQuay restarts.
Extra completion cards disappear after eight seconds of viewing in front, with the timer
paused while you hover, focus a card, or switch away
from ByteQuay. The counter is amber when something needs your attention and blue
for recent completions; more notices give it a stronger tint. When there are no
notifications, the cards and counter disappear. Notices temporarily hide while a
modal dialog is open so its controls remain accessible. Closing a popup does not answer a question,
grant permission, or resume a queue.

**Task is blocked** means ByteQuay could not verify that the agent stopped and
keeps its working copy locked. Automatic startup cleanup runs first; a notice
appears only if the block remains. Inspect the task before stopping its runtime
or marking it failed. Paused queues contain your submitted follow-up messages
that stopped advancing after an interruption or delivery problem.

### Reuse instructions

Open **Settings → Agents → Skills → Add skill**. Enter a name, choose **Global**,
**Development**, or **Review**, write the instructions, and save. Changes apply from the
next turn.

<a id="delivery-and-ci"></a>

## Deliver changes and repair CI

Before sending a new development task, open **Automation**. Finished development work is
committed automatically. Publication depends on the choices you accept:

- **Auto draft PR** pushes commits and creates or updates the task’s pull request. It is
  on by default.
- **Auto CI fix** repairs supported failing checks, reruns CI, and can mark the draft
  ready when checks pass. It can write to GitHub and is on by default.
- **CI repair agent** chooses the same agent as development or another supported agent.
- **Auto review summary** adds a private note about new review comments. It does not
  post comments or fix code, and is off by default.
- **Keep commits local for this turn** disables publication and CI follow-up for that
  submission.

Change defaults for new tasks in **Settings → Agents → Workflow**. A started task’s
automation settings are read-only.

1. Follow delivery and CI activity in the task header after development finishes.
2. Type `@ci` followed by a text message to guide the running CI repair agent when
   its provider supports steering. When **CI needs your input**, the same action
   replies to the paused run and resumes it.
3. Use **Stop automation** to stop the continuation. Review and merge the resulting PR
   yourself when it is ready.

Ordinary PR details do not have a general “Start CI Autofix” button. Reading logs or
monitoring a PR does not authorize repairs.

<a id="goals"></a>

## Turn a goal into useful work

Goals help you clarify an objective, learn about a repository, and track whether the
work actually achieved its intended result.

1. Open a repository workspace and choose the floating **Goal** control, or press
   **⌥G**.
2. In **Guide**, answer **What do you want to achieve?** in your own words or use a
   suggested direction. Choose **Send** and answer the follow-up questions.
3. Review the proposed objective, success definition, and constraints. Choose **Accept
   goal**, **Adjust**, or **Skip**.
4. Review recommendations. **Start** opens the named lesson or starts the named
   investigation; development recommendations open a prepared composer for you to review
   and submit. **Not now** dismisses a recommendation.
5. In **Work**, follow **Next**, **Working**, **Blocked**, and **Done**. Open an item to
   inspect its sources, attempts, and history.
6. When the outcome is ready, enter the result, evidence, and scope covered, then choose
   **Accept result**. A finished task or closed ticket alone does not mark the work item
   done.

### Learn and organize

For a proposed course, choose **Accept course**, open a lesson, read its explanation and
exercise, and submit **Your answer** with **Answer** under **Check**. Ask questions
beside the lesson and choose **Mark lesson done** when ready.

**Work → New work item** creates an outcome with a title, kind, and success condition.
**Add existing** links issues, tickets, tasks, or PRs. Use **Waits on** to record a
dependency. **Pause guidance** pauses suggestions; **New goal** in the GOAL menu starts
another objective.

<a id="epics"></a>

## Keep related work together with Epics

An Epic connects a larger issue or ticket to the work needed to finish it. Its
**Work** view shows progress, active attempts, and dependencies in one place.

1. Open an Epic issue or Jira ticket and choose **Work** beside its source tab.
2. When new source children are offered, inspect them and choose **Sync** to bring
   them into the Epic, or **Not now** to leave the suggestion for later.
3. Add a work item by title, or choose **Add existing** to link an issue, ticket,
   pull request, or task. Review proposed work before choosing **Accept**.
4. Open a work item to inspect its state and linked attempts. Choose **Run** on
   eligible work to start its development task at once and follow it in the task's
   conversation; the agent can read the Epic, its work list, and the item it is
   delivering. Dependencies explain what is waiting.
5. Review completed outcomes. When the Epic is ready, choose **Conclude Epic**
   and record its outcome. Closing the source issue or ticket is a separate action.

<a id="memory-and-specs"></a>

## Build repository memory

Memory stores sourced rules, useful commands, and specifications so agents can apply
what has already been learned about a repository.

1. Open the workspace’s **Memory** page and choose **Learn from my PRs**. You can also
   use **Learn from this PR** on a closed or merged PR.
2. Inspect entries under **Memories**. Filter by **Rules**, **Recipes**, or **Specs**,
   then open an entry to read **Applies to** and **Sources**.
3. Use **Edit → Save**, **Delete**, or **Restore** where available. Editing an entry
   makes it yours, so agents will not change it.
4. Under **Agents use memory in**, select **Reviews**, **Development tasks**, or both.
   Inspect flagged entries under **Needs you**.

### Check code against a specification

1. Choose **Memory → Check against spec**, then select a module or enter a directory
   path.
2. Select the linked pages to inspect, or use **Add spec** for an additional
   specification URL.
3. Choose **Reproduce up to** and start **Check against spec** with a ready agent
   provider.
4. Open the report under **Spec checks** and inspect each **Failing test** and **Work
   item description**. Confirmed findings are saved as local work items with the full
   specification, reproduction, test source, and output.
5. Choose **Open local work item** to develop the finding or publish it later.
   **Copy body** and **Dismiss** remain available.

Only reproduced findings are shown as confirmed gaps. Unreproduced candidates and
already-reported issues remain separate. **Stop check** stops a running investigation.

<a id="issues-and-jira"></a>

## Work items: local, GitHub, and Jira

Open a workspace’s **Work items** and choose **GitHub issues**, **Jira**, or **Local**.
GitHub keeps its issue list and detail pane; Jira keeps its board, filters, and ticket
views. **Local** lists the work kept on your Mac under **Open**, **Done**, and **Closed**,
with search and filters for Epic, kind, and where the work is visible. **New** saves
only on your Mac. Add the context and acceptance criteria, then choose
**Start development** to start the workspace's configured Development agent and open
its task directly, without reviewing or sending a generated prompt. The agent reads
the saved work-item context and, when present, its parent Epic's link, description,
and related work. Missing or stale parent context is identified in that read.
A work item's page shows its context, its tasks and pull requests, its Epic, and its
history. In an Epic’s
**Work** view, click a Local card to zoom into its details. Linked tasks appear with
their titles and statuses; click one to open its task page over the Epic. Close the
zoom card or press Escape to return to the view underneath.

Agents can list and read the same work items and create local follow-ups. New Epic
subtasks and confirmed spec findings also start locally. Removing Epic membership
preserves the item and its history. Development may deliver a pull request without
publishing any issue or ticket.

To expose local work, choose **Push to remote…**, select GitHub or a connected Jira site,
project key, and work type, inspect the preview, then **Create GitHub issue** or
**Create Jira ticket**. The title and context are sent; task conversations and local
history are not. Pushed work stays in **Local**, linked to its remote copy.
If a request has an uncertain outcome, enter the existing issue or ticket beside
**or link existing** and choose **Link** before trying again. Jira projects requiring
additional creation fields may reject the push; the local item remains available.

### GitHub issues

Open a workspace’s **Work items**, find the GitHub issue, and choose **Start development**
action when available. Review the prepared task composer and submit it. **View task**
returns to linked work. New work always starts locally.

### Jira

1. Open **Settings → Integrations → Jira → Add account**. Enter the **Jira site**,
   **Atlassian account email**, and **API token**, then choose **Connect account**.
   Follow **Required token permissions** for the required scopes.
2. Choose **Work items → Jira** and use **Site, board and sprint** to choose a board and sprint or
   **Backlog**.
3. Browse **Current sprint** or **Completed**. Use **Assigned to me**, **Status**,
   **Search tickets**, and **List** or **Board** to find the work. The assignment menu
   also offers **Co-assigned to me**, **Assigned or co-assigned to me**, and
   **All assignees**. Co-assignment uses the site’s Co-assignee user field.
4. Open an eligible unfinished leaf ticket and choose **Start development**. Select and
   **Assign workspace** if prompted, then review and submit the task composer.
5. Assigned tickets appear in **Work items → Jira**. Use **View task** to return to development.
   **Add subtask** creates local work under the ticket’s ByteQuay Epic. Ticket field
   edits, comments, and status transitions are separate user actions.

**Open in Jira** handles fields and operations the app does not support. Browser sign-in
is shown only when configured. Replace an expired token in settings.

## Gmail and Google Calendar

Connect Gmail and Calendar from **Settings → Integrations → Google Apps**.
Install the connector and choose **Sign in with Google**. For another account,
use **Add account** on the Google Apps page, complete browser sign-in, and switch
with the email-address selector. Each account has its own cached items and
Gemini conversation. Existing shared terminal logins need a separate sign-in
for this account-isolated setup.

The Gmail and Calendar pages load when you refresh them. Mail pages contain up to
25 messages with **Next page** for more; **Inbox**, **Unread**, and **GitHub**
run those searches. GitHub notifications show who acted, the quoted change,
and **View on GitHub**. Calendar offers **Day** and **Week** grids; **Today**
and the arrows move the range, and **Refresh agenda** fetches it. Changing
views or dates does not fetch automatically. Connector result limits can omit
events in busy calendars. Mail and events stay in memory until ByteQuay quits;
account sign-ins remain available for the next launch.

Use **Compose** or **Reply** to write email; a reply opens under the message
you are reading. **Review email** names the recipients before **Send email**
sends it. **Archive** removes a selected message from the inbox. If sending cannot be confirmed, check Sent mail and
Drafts before trying again. Refresh manually after Gemini changes Google data.

To use the conversation on the Google Apps page, save a Gemini API key in the
same settings page. **Create API key…** opens Google AI Studio for its sign-in,
terms, and project selection. Free usage depends on the project's model and
quota; a billing-enabled project can incur charges. ByteQuay does not create
projects or enable billing.

Ask Gemini, for example, “Search Gmail for replies about booking ABC's refund.”
It can search live Google data beyond the displayed mail page. **Ask Gemini**
on an email or event attaches it to your next question. Responses appear
beside your inbox with the searches behind them, and actions needing approval
appear in the conversation. **Stop** ends a running session; use **Clear** to
start fresh after stopping. Conversations clear when ByteQuay quits. Sign-ins and the saved model
key remain. Removing the key ends the app's Gemini sessions but does not revoke
the key in Google AI Studio.

Optional task-agent access reads only the selected account's in-memory items.
Consent resets on restart or account switching. Shared content can remain in
those task conversations under their usual retention settings.

<a id="commits-and-backports"></a>

## Move changes between branches

### Cherry-pick commits

1. Open **Commits → History**. Check the first repository/branch control: it is the
   destination. Select the source with the second control.
2. Choose commits or use **Select range**, select the endpoints, then choose
   **Cherry-pick**.
3. Review the route, commit order, and conflict resolver. Remove unwanted commits, then
   choose **Start cherry-pick (N)**.
4. If it pauses, use **Send note** or **Cancel execution**. Find existing executions in
   **Commits → Cherry pick**.
5. After completion, use **Deliver this generated branch → Push branch**, then **Create
   pull request**, with the displayed confirmations. PR creation also authorizes the
   disclosed CI monitor.

The initial cherry-pick runs locally. **Dry run** shows the execution plan; it does not
run another Git simulation.

### Backport through related workspaces

Related repositories in the sidebar start collapsed. Expand a repository to open
its **Commits** or **Memory** in the main panel while staying in the current
workspace. The panel identifies the related repository whose content is shown.

1. Watch the involved repositories. In each destination’s repository settings, open
   **Related workspaces → + Add related workspace**.
2. Select the source workspace, choose **Fork**, and enable **Use as default backport
   source** to include this destination when requesting a backport without naming
   targets. Choose **Add**. Each destination can have one default source.
   A **Dependency** permits investigation but not backporting.
3. On **Workspace Home** or **Tasks**, click **Backport** beside the repo icon,
   or type `@backp` and select the `@backport` suggestion with Enter, Tab or a
   click. Add `owner/repo #1234` and send it. **Start backport conversation**
   on the Backport chains tab also fills the composer. Use `@backport #1234` when the source is
   unambiguous. Name destinations to override the default route. You can also
   describe a fix and request backports before its source PR exists.
4. Follow **Tasks → Backport chains** or the conversation’s chain card. Inspect linked
   PRs and use **Stop**, **Resume**, or **Select commits** when offered.

Each step waits for its source PR to merge. If private forks internal and internal
forks upstream, private receives internal’s landed changes. If both fork upstream,
they can backport upstream in parallel. If “then” conflicts with these saved routes,
the agent asks which source and order you intend. ByteQuay never merges these PRs
automatically.

<a id="circles"></a>

## Follow people with Circles

Circles are personal collections of people whose work you want to follow. Creating one
does not invite or notify anyone.

1. Open **Circles → Create a circle**, or **New circle** from its menu.
2. Enter a name, optionally add a description, choose **People**, and optionally scope
   it to **Repositories**. Select **Create circle**.
3. Use **Needs you**, **Activity**, **Members**, and **Insights** to follow the
   collected work. Shortcuts **⌘1–⌘4** switch views.
4. Open relevant work, acknowledge it, or **Snooze** it. Choose a time range and inspect
   the activity behind insight counts.
5. Use **Refresh activity** for another collection pass and inspect **Updated** or
   **Partial** for coverage.

The people picker searches contributors cached from watched repositories. Circles do not
grant repository access. Their insights reflect collected activity, not a complete
measure of someone’s work.

<a id="help"></a>

## When something needs attention

- **GitHub will not connect:** retry the GitHub CLI login or update the token in
  **Settings → Integrations → GitHub**. Check repository access when using a
  fine-grained token.
- **An agent is unavailable:** open **Settings → Agents → Models**, sign in, choose
  **Check**, and wait for **Ready**. Check the workspace’s model override too.
- **Commits or files are unavailable:** wait for repository preparation. Code & Tests
  lets you create a working copy for a PR in a watched repository.
- **A queue is paused:** address the displayed cause, then **Resume queue**. Inspect
  uncertain delivery before sending the same message again.
- **A feature differs from this guide:** check your release version and its release
  notes. Some controls depend on repository permissions, provider capabilities, or the
  state of the selected work.

For product intent and system boundaries, read the [design](../docs/design.md)
and [architecture](../docs/architecture.md). For building from source, use
[CONTRIBUTING](../CONTRIBUTING.md).
