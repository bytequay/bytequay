# ByteQuay documentation

Set up a workspace, review a change, and build with an agent.

- [Get started](#getting-started)
- [Use the Assistant](#assistant)
- [Review pull requests](#pull-requests)
- [Models, accounts, and agent tasks](#models)
- [Plan and organize](#issues-and-jira)
- [Connect integrations](#jira)
- [Use ByteQuay Lab](#lab)
- [Get help](#help)

<a id="what-is-bytequay"></a>

## About ByteQuay

ByteQuay is a macOS app for GitHub reviews and local coding agents. Let agents
build and guide you, choose how much detail to explore, and merge when you feel
confident.

A **workspace** holds a repository’s work. A **task** is an agent conversation
with its own working copy. App data stays on your Mac; GitHub and model requests
use the services you connect.

ByteQuay is pre-1.0. These guides cover the development version; check your
release notes for availability.

<a id="getting-started"></a>

## Get started

1. Download ByteQuay from
   [GitHub Releases](https://github.com/bytequay/bytequay/releases). Open the
   DMG and drag the app to Applications. Follow your release’s requirements;
   current builds need macOS 14+ and include Java 25, so no separate JDK
   installation is required.
2. Follow the setup guide to choose your appearance, connect GitHub, and decide
   which agent features to use. You can continue without an agent.
3. Choose **Home → Repos you watch → + Watch**. Select a repository, check its
   branch and publication destinations, then **Watch repository**.
4. Open **Pull requests**, select a PR, and inspect **WorkBench → Changes**.

### Choose your setup

1. **Appearance and GitHub:** choose System, Light, or Dark. Connect with
   **Use my GitHub CLI login** after running `gh auth login`, or **Connect with
   token →**. The app recommends a classic token with `repo` and `read:user`
   scopes.
2. **Connect an agent and choose your default:** check Codex or Claude Code,
   sign in, or add a DeepSeek API key. Select the account and model for
   Development; Review follows it unless you choose another in Settings.
3. **Automation:** choose how far new tasks go on their own, from a local
   commit to a merged PR. It starts at **Auto-Commit** on a fresh
   installation. Existing preferences are preserved.
4. **Your features:** watch the previews to explore Today and Assistant, then
   enable either feature if you want it. Each uses your default Development
   agent and model allowance. Assistant task actions have separate switches.

After GitHub is connected, **Continue without an agent** keeps GitHub and PR
work available. If an agent is already available, **Skip remaining setup**
keeps the remaining saved choices. New agent actions stay hidden until their
selected agent and model are usable; saved tasks, reviews, and notices remain.

Unfinished setup resumes where you left it. Once completed, it never opens
automatically again, including after a sign-in expires. To revisit it, choose
**Settings → General → Setup guide → Open setup**. You can also
[connect a provider](#models) or change **Settings → Personal → Appearance**
directly.

<a id="workspaces"></a>

## Home and workspaces

Home brings together watched repositories and recent activity. A workspace holds
tasks, PRs, work items, commits, and memory.

- Choose **Direct clone** to work in the selected repository, or **Personal
  fork** to publish through your fork. Review the source, push, and PR
  destinations before confirming.
- **My PRs** shows your open work. **Snooze** hides a card temporarily; **Needs
  attention** collects updates needing a response.
- **Unwatch** retires a workspace while keeping local history. **Watch again**
  restores it. **Delete local data** is a separate, confirmed removal.

### Plan the day with Today

Today is off until you enable it during setup or in **Settings → Agents →
Features**. It needs a usable global Development model, and each day's
list spends a model session from that account. Once on, Home opens on working
days, after the time you set, with **Today**: up to five things worth doing
first, each with a reason. An agent
picks them from ByteQuay's records; it never writes to GitHub or starts a task.
If ByteQuay was closed at that time, Today is made when you return.

- Click a row to open its PR, task, issue, ticket, or work item. Hover it for
  **Done** or **Not today**; a row also closes when its work happens.
- A row that suggests starting work also has **Start**, which starts the task
  with your Development model and saved automation, as the item's own Start
  does. The row then reads **Started**.
- The circular arrow makes the list again. A workspace Home shows only its
  repository's rows; **Needs attention** marks Today rows with a sun.
- On the last working day, **This week** looks back at what merged and slipped.
- **Settings → Agents → Features** sets the time and working days, and can
  plan around your calendar's busy times (never event titles).

<a id="assistant"></a>

## Use the Assistant

Bring your day and waiting tasks into one conversation. Choose a usable global
Development model in **Settings → Agents → Models**, then turn on **Enable
Assistant** during setup or in **Settings → Agents → Features**. Assistant
starts off on a fresh installation; existing users keep their setting. Each
message uses your model allowance.

1. Press **⌘J** to open the Assistant. From a web page, ByteQuay returns to the
   app first.
2. Ask what needs attention or about the item you are viewing. **On screen**
   includes that item as context; remove its **×** to leave it out.
3. Browse **Decision**, **Running**, **Finished**, or **Today**. Use **← →** to
   page through cards, or **View all**. Answer approvals and questions on their
   cards; **Esc** closes the panel.
4. Ask it to find or list work, such as your open pull requests. It shows them
   as a list; switch between rows, cards and a timeline from the list's header,
   and click an item to open it. Ask it to take you to a page or an item, such
   as model settings, and it opens it; the receipt's **Open** goes there again.
   In cards, a pull request has its own **Approve** until you approve it,
   **Merge**, **Comment** and, on your own pull requests, **Close**. They act
   at once, as the pull request page's buttons do; **Comment** opens it at its
   comment box. When one cannot act, the card says why, with **Open**.
5. Ask for a new GitHub issue as an Epic, and it drafts one for a watched
   repository. Edit the title and description, then click **Create issue** to
   create it on GitHub and mark it as an Epic. Nothing is created until you
   click; then its receipt takes the draft's place.

While you use another app, a new task alert also shows as a macOS
notification; click it to answer. The Dock icon counts the tasks waiting on
you, and **⇧⌘J** opens the one that has waited longest. Press it again for the
next.

When chat is off or your agent needs reconnecting, task notices and approval
buttons remain available. Saved conversation history is preserved.

### Let it act when you ask

In **Settings → Agents → Features**, optionally enable **Assistant can start
tasks**, **Assistant can answer tasks**, **Assistant can change Epics and work
items** or **Assistant can acknowledge and snooze items**. All are off by
default and are separate from enabling Assistant conversation.

- **Start tasks:** ask it to start work on an item. The task uses your saved
  automation settings.
- **Answer tasks:** send your reply to a task or answer a question. A task
  that is working gets your reply after its current turn; **Steer** on the
  task's page sends it sooner. For supported command approvals inside the
  sandbox, allow once or deny; other approvals require the card's buttons.
- **Change Epics and work items:** mark an issue, ticket or work item as an
  Epic, add work to an Epic, or create a work item titled with your words. All
  of it stays in ByteQuay; unmarking an Epic stays a button.
- **Acknowledge and snooze items:** clear a **Needs attention** item until
  something new happens, or snooze it for an hour, until tomorrow or until
  Monday. Bringing an item back stays a button.
- Each action leaves a receipt showing the words it acted on. **Open** takes
  you to the task; **Stop** cancels the first turn of a task it started. A
  refused action says why, with **Settings** when its switch is off.

The Assistant does not edit files or run code itself. Development work happens
in the tasks it starts. Enable the optional daily agenda in **Settings → Agents
→ Features** to populate **Today**; generating it uses a model session.

<a id="pull-requests"></a>

## Review a pull request

1. Open **Pull requests**. Filter by **Review** or **My work**, or search by
   title, `#number`, or `@author`.
2. Read checks and activity in **PR**. Inspect files and commits in **WorkBench
   → Changes**.
3. Select diff lines and choose **Add to review**. In **Submit review**, check
   your comments and choose **Comment**, **Approve**, or **Request Changes**.
4. Inspect the header’s merge/check-status control when ready to merge. Use
   **Monitor PR** to follow updates.

For branches with a merge queue, **Merge when ready** is available while CI
runs. GitHub adds the PR to the queue once its requirements pass. Without a
merge queue, **Rebase and merge** stays disabled while checks run.

When GitHub removes a PR from the merge queue, its activity entry and merge
dialog show the removal reason and links to failed checks when available.
These checks belong to the merge-queue commit, so they can fail while the PR’s
own checks pass. **View removal on GitHub** opens the original event.

**Post now** and **Reply** publish immediately. **Add to review** keeps comments
pending. Monitoring alone does not start CI repair.

<a id="explore-locally"></a>

## Explore code and tests

For a watched PR, open **WorkBench → Tears of time → Create worktree to
explore**. A local editor uses memory, CPU, and disk; opening it does not start
an agent.

- Use **Changes** for diffs and **Comment** for review cards beside the code.
- Press **Shift twice** or **⌘⇧F** for Search Everywhere; **⌘F** searches the
  current file.
- Use a test’s play button to run or debug it. **Run** and **Debug** retain
  their results.

On your own PR, **Development task** opens its conversation without sending a
new prompt. If none exists, it creates a task and local worktree using your
current **Settings → Agents → Workflow** defaults, then waits for your message.
Reopening an existing task preserves that task's saved automation choices.
Leaving the PR while creation is in progress does not cancel
it or take you away from your new page when it finishes.

<a id="private-review"></a>

## Ask for a private review

First watch the repository and configure a ready Review model in **Settings →
Agents → Models**.

1. Choose **WorkBench → Summary → Start review**. For unpublished development
   work, choose **Review changes** in the task instead.
2. Read the explanation and findings. Use **View in Changes** for code, **Review
   details** for evidence, or **Ask reviewer** for a private discussion.
3. Choose **Add to my review** to edit a finding before **Submit review**
   publishes it. Once GitHub publication is confirmed, the private finding is
   marked **Resolved**, with **Accepted** beside it on the card.
   **Request fix → Send to developer** asks the development agent
   to address it privately.
4. Choose **Update review** after the PR changes, or **Review again** for local
   development changes.

For **Could not finish**, use **Resume review**; typing “continue” starts a
discussion. If required, use **Recover runtime** first. Findings remain
provisional until the review finishes.

<a id="ask-from-review"></a>

## Ask from a review card

1. On someone else’s review comment, choose **Ask agent**.
2. Ask about the reasoning, a fix, or a tradeoff. The PR’s code context is
   prepared automatically.
3. Choose **Draft with agent** for an editable reply. It remains private until
   you choose **Comment**.

**Resolve locally** closes the private discussion without resolving the GitHub
thread.

<a id="link-previews"></a>

## Preview PR and issue links

Hover over a PR or issue link to read its title, status, description, and
labels. Move into the card to follow references, or drag its header to keep it
open. Choose **Open** for the full item; **Escape** dismisses the preview.

<a id="models"></a>

## Choose your model

Open **Settings → Agents → Models**.

- **Codex or Claude Code:** use **Sign in** to connect your account. If the CLI
  is missing or too old, copy the displayed install or upgrade command, run it
  in your terminal, then choose **Check**. ByteQuay does not install or update
  these CLIs itself.
- **Your agent API:** choose **Add API key**, select a supported provider, such
  as DeepSeek, enter a label and key, then **Add key**. DeepSeek tasks require
  Codex CLI, but not a Codex account.
- Once **Ready**, choose your Development and Review models. Use repository
  **Model overrides** for a different choice.

Keys are stored encrypted on your Mac. Saved OpenAI keys cannot run agent tasks
yet. Installing a CLI or saving a key alone is not enough: the CLI must meet
the required version, and the selected account or key must have an available model.
Today and Assistant use the global Development selection; an account available
only to one repository cannot enable them.

<a id="claude-accounts"></a>

## Keep Claude Code accounts separate

Use multiple accounts with isolated sign-ins and sessions.

1. In **Models → Claude accounts**, choose **Add Claude account**. Enter a name
   and email, then **Add and sign in**.
2. Select the account for Development or Review. Global accounts are available
   across repositories.
3. For a dedicated account, add it in the repository's **Model overrides**, or
   choose **Use only here** on a global account. Other repositories cannot use
   it.

Changing defaults affects new tasks. Existing tasks keep their selected account.

<a id="agent-tasks"></a>

## Start an agent task

1. Open **Tasks → + New task** and choose a ready model.
2. Describe the outcome, useful context, and how to check success.
3. Review **Automation**, then **Send**. ByteQuay prepares a separate branch and
   working copy.

**Steer** adds guidance when supported; **Run next** queues a message.
**Stop this turn** interrupts the agent.

Review approvals, inspect **WorkBench → Changes**, and check tests before
**Mark it complete**.

Check limits in **Usage**. Add reusable instructions in
**Settings → Agents → Skills**.

<a id="delivery-and-ci"></a>

## Deliver changes and repair CI

The composer's automation chip shows one icon for each step a new task will
take. Click it and drag the slider to choose how far the task goes; each step
includes the ones before it:

- **Auto-Commit:** commits finished work on the task's branch. Nothing leaves
  your Mac.
- **Auto-Draft PR:** pushes commits and creates or updates a draft PR.
- **CI fix:** watches CI beside the task after each push. It reruns flaky
  jobs and repairs failures the PR caused. Failures that also fail on the base
  branch are left alone until the next push. Once every check passes, it marks
  the draft PR ready for review. **CI repair agent** follows Development
  unless you choose another supported agent.
- **Auto-Review fix:** gives new comments on the PR's open review threads to
  the task's agent. It fixes what it agrees with and explains what it doesn't.
  Once the fix is pushed, ByteQuay posts each reply and resolves the threads
  that were fixed. A thread gets at most three rounds.
- **Auto-merge:** once the PR can merge, waits for the delay in
  **Settings → Agents → Workflow** (one hour unless you change it), then
  rebase-merges it or joins the merge queue. GitHub decides whether the PR can
  merge; the task's CI fix must also pass and no review thread may be open.

The reset button returns to your saved default. A task's automation is fixed
once it starts: its chip then shows each step as done (green), running
(yellow), not started (grey) or needing you (red); hover a step for details.
Every step after Auto-Commit can write to GitHub. Change the default for new
tasks during setup or in **Settings → Agents → Workflow**. Automatic repairs
and fixes use your model allowance.

The pill above the composer says what automation is doing now. Its stop
square stops automation and keeps your draft; while a merge counts down, it
cancels the merge and the PR stays open.

You can keep sending messages while CI Autofix watches. A message stops a
repair in progress, and your next turn starts from the fixes CI already pushed.
The pill's stop square ends the current CI run; the next push starts another.

CI monitoring runs locally while ByteQuay is open, has no time limit, and
resumes after a restart. ByteQuay notifies you when failures already fail on
the base branch, when three repairs don't make CI pass, or when a repair needs
your input. Quitting ByteQuay stops a commit or PR step in progress; send a new
instruction to continue.

<a id="commits-and-backports"></a>

## Cherry-pick and backport

Link related repositories once in repository settings under **Related
workspaces**. A Fork means your code builds on that repository, so Fork links
form a repo line, such as an open-source project, its internal fork, and a
product built on that fork. Every workspace in the line shows it, and agents can
read and start work anywhere in it.

1. In **Commits → History**, choose a repository upstream in the repo line. Select
   commits, or open **Pull requests** and pick a merged one.
2. Choose **Cherry-pick**. Check the plan with **Dry run**, then **Start
   cherry-pick**, and follow it in **Commits → Cherry pick**.
3. Review the result, then **Deliver this generated branch → Push branch** and
   **Create pull request**. PR creation also starts the disclosed CI monitor.

To carry a pull request down the line, pick it and choose **Backport**, then
confirm the repositories to include. Each one gets its own cherry-pick and pull
request after the pull request before it merges. Chains show in **Commits →
Cherry pick** of every repository they touch; you can also ask an agent with
`@backport`.

<a id="issues-and-jira"></a>

## Start from a work item

Open **Work items** and choose **Local**, **GitHub issues**, or **Jira**.

1. Choose **Local → New** and add context and acceptance criteria. It stays on
   your Mac.
2. **Start development** on a Local item immediately starts the configured
   agent. GitHub and Jira items open a prepared composer for you to review and
   send.
3. Open the item to follow tasks, PRs, and history. In an Epic, select a card to
   zoom in; **Escape** returns to the Epic.
4. To publish an item, use **Push to remote…**, inspect the preview, then create
   the GitHub issue or Jira ticket. Conversations and local history are not
   included.

If publication is uncertain, link the existing remote item before retrying.

In a Local item's **Context**, choose **Link work item** to reference another
Local item, GitHub issue, or Jira ticket in the workspace. Linked titles open
their details; **Unlink** removes only the reference. Agents can create and read
these links too. Use Epic membership for subtasks and dependencies for blockers.

<a id="epics"></a>

## Organize an Epic

An Epic groups work items, their development attempts, and dependencies in
ByteQuay. A Local work item, GitHub issue, or Jira ticket can be an Epic. The
Epic designation and its work assignments stay on your Mac; a Local Epic does
not need a published issue or ticket.

1. On workspace Home, point at the Epic tab on the sidebar edge and choose
   **Create Epic**. Choose **Talk it through** to let the Development agent ask
   about the Epic and draft it; approve the draft with **Create Epic** or by
   saying yes. Or choose **Create yourself** for a Local Epic with a title,
   description, and context, or mark one of the workspace's GitHub issues or
   Jira tickets. You can also open any work item and choose **Mark as Epic**.
   For GitHub or Jira Epics, review offered source children in **Work** before
   **Sync**.
2. Add a Local item, **Add existing**, or **Suggest work**. Inspect suggestions
   before **Accept as Local**.
3. Use **Planned**, **Working**, and **Done** to track progress. **Run** on
   eligible work starts its development task immediately.
4. Review outcomes, then **Conclude Epic**. Closing the source issue or ticket
   is separate.

Agents can also create Local work items, mark them as Epics, and assign existing
items or add Local subtasks. Creating an Epic or assigning work does not change
GitHub or Jira. **Push to remote…** remains a separate action; publishing an item is
optional and keeps its local identity and Epic membership.

<a id="artifacts"></a>

## Learn with Artifacts

1. Open **Artifacts** in a workspace and ask in **Ask about this repository**.
2. The agent answers from the code, citing files by path and line, and ends each
   answer with a question card for what to look at next. Pick an option, or
   write your own reply.
3. When nothing is left open, it offers a page. Choose **Make the page**, or
   **Not yet** to keep talking. You can also ask for a page at any time.
4. The page opens beside the conversation. Ask for changes in the same
   conversation; each save adds a version under **Versions**.

The agent reads the repository but never changes it. A page's buttons and
quizzes work inside the page, which reaches nothing outside it.

<a id="memory-and-specs"></a>

## Build repository memory

1. Open **Memory → Learn from my PRs**, or choose **Learn from this PR** on a
   closed or merged PR.
2. Inspect **Rules**, **Recipes**, and **Specs**, including their scope and
   sources. Edit, delete, or restore entries as needed.
3. Choose whether agents use memory in **Reviews**, **Development tasks**, or
   both. Review entries under **Needs you**.

For a specification check, choose **Memory → Check against spec**, select the
code and source pages, and start a ready agent. Inspect reproduced failures
under **Spec checks**; **Open local work item** continues a finding.
Unreproduced candidates stay separate.

<a id="circles"></a>

## Follow a Circle

Choose **Circles → Create a circle**, name it, and select people and optional
repositories. Use **Needs you**, **Activity**, **Members**, and **Insights** to
follow their work.

Creating a Circle sends no invitations. **Refresh activity** updates collected
information; insights reflect that coverage, not a complete measure of a
person’s work.

<a id="jira"></a>

## Connect Jira

1. Open **Settings → Integrations → Jira → Add account**. Enter your site,
   account email, and API token with the required permissions.
2. In **Work items → Jira**, choose the site, board, and sprint or backlog.
   Filter by assignee and status.
3. Open an eligible ticket and choose **Start development**. Assign a workspace
   if prompted, then review and submit the composer.

**Add subtask** creates Local work in the ByteQuay Epic. **Open in Jira**
handles unsupported fields and actions.

<a id="gmail-and-google-calendar"></a>

## Connect Gmail and Calendar

1. Open **Settings → Integrations → Google Apps**, install the connector, and
   **Sign in with Google**. Use **Add account** for another account.
2. **Refresh** mail or the calendar agenda. Choose **Compose** or **Reply**,
   then inspect **Review email** before **Send email**.
3. For **Ask Gemini**, save a Gemini API key in the same settings page. Ask a
   question or attach an email or event; approve requested actions in the
   conversation.

Mail, events, and Gemini conversations stay in memory until quit; sign-ins and
the key remain. Task-agent access is optional and reads only the selected
account’s loaded items. Its consent resets on restart or account switch.

Gemini uses your key’s quota and billing. If a send is uncertain, check Sent and
Drafts before retrying.

<a id="lab"></a>

## Measure with ByteQuay Lab

Choose **Settings → Integrations → Lab → Open Lab**. ByteQuay refreshes the local
statistics and opens the dashboard automatically. Turn on **Show the Lab** for
a sidebar shortcut. No terminal commands or Python installation are needed.

Use the dashboard to inspect resources, test review guidance,
and understand context costs. Record a change and check the results.

[Explore the Lab](dist/lab.html) · [Start the Lab](dist/lab-guide.html)

<a id="help"></a>

## Get help

- **GitHub unavailable:** renew CLI sign-in or update the token in **Settings →
  Integrations → GitHub**. Check repository access.
- **Agent unavailable:** open **Settings → Agents → Models** and choose
  **Check**. Verify the selected account or API key and repository overrides.
- **Review could not finish:** use **Resume review**, or **Recover runtime**
  first when shown. Wait for a provider limit to reset or select another
  available provider.
- **Queue paused:** address the displayed cause, then **Resume queue**.
- **Comment delivery unconfirmed:** check GitHub. For a missing comment, choose
  **Submit review → Confirmed not posted** before submitting again. If it posted,
  choose **Confirmed posted** so it is never sent again.
- **Review text unconfirmed:** the text and verdict stay as sent and **Submit
  review** is off. Choose **Confirmed posted** to clear it, or **Confirmed not
  posted** to submit it again.
- **Post now unconfirmed:** the comment waits in **Submit review** as **Delivery
  unconfirmed**. Posting it again is refused until you confirm it there.
- **Check failed:** expand it in **PR → Show log**, or use **Open in GitHub**.
  **Rerun failed tests** appears when supported and permitted.

For building from source, read [CONTRIBUTING](../CONTRIBUTING.md). For product
intent, read the [design](../docs/design.md) and
[architecture](../docs/architecture.md).
