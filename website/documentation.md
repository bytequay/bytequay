# ByteQuay documentation

Set up a workspace, review a change, and build with an agent.

- [Get started](#getting-started)
- [Keyboard shortcuts](#keyboard-shortcuts)
- [Use the Assistant](#assistant)
- [Review pull requests](#pull-requests)
- [Browse web pages](#web-pages)
- [Models, accounts, and agent tasks](#models)
- [Plan and organize](#issues-and-jira)
- [Connect integrations](#jira)
- [Use ByteQuay Lab](#lab)
- [Get help](#help)

<a id="what-is-bytequay"></a>

## About ByteQuay

ByteQuay is a macOS app for GitHub reviews and local coding agents. A
**workspace** holds a repository’s work; a **task** is an agent conversation
with its own working copy. App data stays on your Mac.

ByteQuay is pre-1.0. These guides follow the development version; check your
release notes.

<a id="getting-started"></a>

## Get started

1. Download ByteQuay from
   [GitHub Releases](https://github.com/bytequay/bytequay/releases) and drag it
   to Applications. It needs macOS 14+ and includes Java.
2. Follow the setup guide to connect GitHub or GitLab and, if you like, an agent.
3. For GitHub, choose **Home → Repos you watch → + Watch** and pick a repository.
   For GitLab, follow [Set up a GitLab workspace](#set-up-a-gitlab-workspace).
4. In a GitHub workspace, open **Pull requests**, select a PR, and inspect **WorkBench → Changes**.

### Choose your setup

1. **Code hosting:** for GitHub, choose **Use my GitHub CLI login** after
   `gh auth login`, or **Connect with token →** with a classic token that has
   `repo` and `read:user`. For GitLab, select **GitLab** and connect the account
   on GitLab.com or your company server.
2. **Agent:** sign in to Codex or Claude Code, or add a DeepSeek API key, then
   pick the Development model. Review follows it unless you change it.
3. **Automation:** choose how far new tasks go, from a local commit to a merged
   PR. New installations start at **Auto-Commit**.
4. **Features:** turn on Today or the Assistant if you want them. Both use your
   Development model allowance.

You can skip the agent and still work with GitHub and PRs. To revisit setup,
choose **Settings → General → Setup guide → Open setup**.

<a id="keyboard-shortcuts"></a>

## Keyboard shortcuts

Use the **Go** menu to move between the main pages, including from a web page
or the code editor. Finish or dismiss an open dialog before navigating.

| Shortcut | Open |
| --- | --- |
| **⌘H** or **⌥⌘1** | Home |
| **⌥⌘2** | Workspaces |
| **⌥⌘3** | Circles |
| **⌥⌘4** | Recent |
| **⌥⌘5** | Pull requests |
| **⌘,** | Settings; repository settings from a workspace |
| **⇧⌘N** | Focus or open the current workspace's task composer |
| **⌃⌘T** | Switch between open tasks in the current workspace |

**⌘H** opens Home; use **ByteQuay → Hide ByteQuay** to hide the app.

**New task** preserves a composer already on screen; **Send** creates the task.
In the task switcher, use arrow keys to choose, **Enter** to open, and **Escape**
to return. Task commands open **Workspaces** when no workspace is selected;
choose a workspace, then press the shortcut again. **⇧⌘J** jumps directly to
the next task waiting on you.

On app pages, **⌘K** opens Recent search and **⌘[ / ⌘]** goes back or
forward. **⌘1–6** selects PR list filters; **⌘1–4** selects Circle tabs.
In a task workspace, **⇧⌘\\** opens the task overview.

Choose **Help → Keyboard shortcuts** or **Settings → Personal → Keyboard
shortcuts** for the full reference, including search, composers, and the
editor. **⌘** means Command, **⌥** Option, **⇧** Shift, and **⌃** Control.

<a id="workspaces"></a>

## Home and workspaces

Home shows your work, what others ask of you, and activity from people you
follow. A workspace holds a repository’s tasks, PRs, work items, commits, and
memory.

- **My work** sorts your issues, tickets, tasks, and PRs into **In progress**,
  **In review**, **Needs you**, and **Ready to merge**. Open issues you opened
  anywhere on GitHub, updated in the last 30 days, wait in **In review**.
  **All my work** lists everything, including finished work.
- **Needs attention** shows up to five review requests, re-reviews, and
  mentions. **Snooze** hides one until the time you pick.
- **Approved by me** shows up to five open PRs you approved. A new review
  request brings one back for review.
- Add a workspace as a **Direct clone** or **Personal fork**. **Unwatch** keeps
  local history; **Delete local data** removes it.

### Set up a GitLab workspace

In **Workspaces → Watch repository**, choose **GitLab**. Select a saved account,
or use **Browser sign-in**. ByteQuay automatically detects an existing GitLab CLI
installation. Choose **Sign in with GitLab** when it is ready, or **Install and
sign in** to set it up first. Use your normal email or SSO login in the browser.

If Homebrew is missing, choose **Open Homebrew setup**, install it, then choose
**Check again**. The same guide appears in **Settings → Integrations → GitLab**.
The server defaults to `https://gitlab.com`; enter your company GitLab HTTPS URL
when needed, including its port or path prefix.

GitLab CLI owns browser authorization and token refresh. It requests its standard
API and repository permissions. For a company server, configure its public OAuth
application's client ID in `glab` first, as described in the
[GitLab CLI authentication guide](https://docs.gitlab.com/cli/authentication/).
The CLI keeps one active account per server; use personal access tokens to keep
additional accounts connected. Disconnecting in ByteQuay does not sign out of `glab`.

To connect with a personal access token instead, select `read_api` and
`read_repository`. This option also works when browser sign-in is unavailable.

Choose **Create access token** to open that server's token settings in your
browser with the ByteQuay name and required permissions prefilled. Sign in to
GitLab normally, create the token, then paste it into ByteQuay and choose
**Connect GitLab**. If your server does not preselect the permissions, select
`read_api` and `read_repository` yourself before generating the token.

Enter the full **Project path**, such as `group/subgroup/project`, and choose
**Find project**. Check the account, server and project, then choose **Create
workspace**. ByteQuay creates a direct HTTPS clone using that account. **Sync
workspace** fetches updates; **Choose GitLab account** switches accounts on the
same server. Accounts can also be connected or disconnected in **Settings →
Integrations → GitLab**.

Open **Merge requests** in the workspace to browse cached requests. **Sync merge requests**
updates them using the selected account; **Load more** reads the next cached
page. Search and state filters apply to loaded rows. Select a merge request to
read its description, status, branches, labels and assignees in
ByteQuay. Saved details appear while the selected account checks for updates;
you can refresh them again from the overview. GitHub workspaces use the same
cached list and keep their existing review and monitoring controls.

GitLab diffs, discussions, checks, review actions, issues, agent tasks and delivery
inside ByteQuay are not available yet. Use **Open in GitLab** for those features,
uploaded attachments and GitLab-specific references in descriptions.

### Plan the day with Today

Turn on Today in setup or **Settings → Agents → Features**. On working days it
lists up to five things worth doing first, each with a reason. Making the list
uses a model session; it never writes to GitHub or starts a task on its own.

- Click a row for its reason and action. **Done** or **Tomorrow** sets it aside.
- **Start task** starts suggested work with your saved automation.
- On the last working day, **This week** looks back at what merged and slipped.

<a id="assistant"></a>

## Use the Assistant

Bring your day and waiting tasks into one conversation. Turn on **Enable
Assistant** in **Settings → Agents → Features**; each message uses your model
allowance.

1. Press **⌘J** and ask what needs you, or about the item on screen.
2. Browse **Decision**, **Running**, **Finished**, and **Today** cards. Answer
   approvals and questions right on them.
3. Ask it to find work or open a page, such as a repository's Tasks. A page
   with a message box, such as a task, opens with the cursor in it. Pull
   request cards can **Approve**, **Merge**, **Comment**, and, on your own PRs,
   **Close**.
4. Ask it for today's agenda. It reads the one on Home, or makes it when you
   ask, as **Make again** does.
5. Ask for a new Epic issue. Nothing is created until you click **Create
   issue**.
6. Start a new topic with **New conversation** or **⌘N**. After 15 minutes
   away, ⌘J opens on a new one. Go back to an earlier one from the clock
   button to keep going.

**⇧⌘J** opens the task that has waited longest. Task alerts also show as macOS
notifications.

**⌥Space** opens the same Assistant from any app, even over a full-screen
window, with voice, cards and replies. What you open from it shows in
ByteQuay. Escape puts it away and keeps what you typed. If another app, such
as Claude, already uses ⌥Space, quit it or change its shortcut, then restart
ByteQuay.

<a id="assistant-actions"></a>

### Let it act when you ask

In **Settings → Agents → Features**, you can let the Assistant start tasks,
answer tasks, change Epics and work items, or acknowledge and snooze items. Each
switch is off by default.

Ask it to work on one of your own pull requests and it uses that PR's
development task, as the PR's **Development task** button does, and sends your
words there. A task started some other way can't be linked to a PR later.

Every action leaves a receipt with **Open**; **Stop** cancels the first turn of
a task it started. The Assistant never edits files or runs code itself.

<a id="dictation"></a>

## Dictate instead of typing

Speak into any agent composer, the Assistant, a review, or a discussion. Your
Mac turns the speech into text, and nothing is sent until you press **Send**.
Dictation needs macOS 26 or later.

1. Turn on **Enable dictation** in **Settings → Agents → Features**. If this
   Mac still needs something, such as a speech model or microphone access, the
   Voice card says so and offers the fix.
2. In a composer, hold **right ⌥** while you speak, then let go. Or click the
   microphone to start and again to stop. **Esc** cancels.
3. Your words show above the draft as you talk. The finished text lands at the
   cursor, and **⌘Z** removes it.

To speak one language and write in another, choose **I speak** and **Write it
in** on the Voice card. The first time, Apple asks in a small window to
download that translation. Hover the finished words to see what you said.

### Your voice stays on your Mac

- Apple's speech engine on your Mac turns your voice into text. No audio goes
  to a cloud service.
- Agents and model providers see only the text you send, as if you had typed
  it. They never receive audio.
- A recording exists only while you talk and is deleted once its text is in
  your draft. If it can't be turned into text, choose **Retry** or
  **Discard**; either way it is deleted when ByteQuay quits.
- Translation runs on your Mac too. The only downloads are Apple's speech and
  translation models, which come to your Mac.


<a id="pull-requests"></a>

## Review a pull request

1. Open **Pull requests** and filter, or search by title, `#number`, or
   `@author`.
2. Read checks and activity in **PR**. Inspect files in **WorkBench → Changes**.
3. Select diff lines, choose **Add to review**, then **Submit review**.
4. Merge from the header. For branches with a merge queue, **Merge when ready**
   schedules the merge while CI runs; use **Cancel scheduled merge** to cancel
   before it enters the queue. **Rebase and merge** waits for passing checks.
   **Monitor PR** follows updates.

**Post now** and **Reply** publish at once; **Add to review** keeps comments
pending until you submit.

To update **Reviewers** or **Assignees**, search and select each person (or
reviewer team), then choose **Save changes** to apply the selection together.
**Cancel** or closing the picker discards unsaved selections.

<a id="explore-locally"></a>

## Explore code and tests

For a watched PR, open **WorkBench → Tears of time → Create worktree to
explore**. It opens a local editor and starts no agent.

- **Shift twice** or **⌘⇧F** searches everywhere; **⌘F** searches the file.
- A test’s play button runs or debugs it.

On your own PR, **Development task** opens or creates its agent task.

<a id="private-review"></a>

## Ask for a private review

Watch the repository and pick a ready Review model first.

1. Choose **WorkBench → Summary → Start review**, or **Review changes** in a
   task. If the task's agent is working, the review starts when it finishes.
2. Read **What changed** and the findings. **Ask reviewer** opens a private
   discussion.
3. **Add to my review** turns a finding into a comment you publish with **Submit
   review**. **Request fix → Send to developer** asks the development agent to
   fix it instead.
4. **Review latest** checks new commits.

If a review could not finish, use **Resume review**.

<a id="ask-from-review"></a>

## Ask from a review card

1. On someone else’s review comment, choose **Ask agent** and ask about it.
2. **Draft with agent** writes a reply that stays private until you choose
   **Comment**.

**Resolve locally** closes only the private discussion. On a development task’s
PR, **Fix with agent** sends the comment to that task’s agent.

<a id="link-previews"></a>

## Preview PR and issue links

Hover a PR or issue link to see its title, status, and description. Drag the
card to keep it open; **Escape** closes it.

<a id="web-pages"></a>

## Browse web pages

Choose **New page** in the Web pages column and enter an address, or type
words to search Google. **Pin** keeps a page after restart, and **⌘F** finds
text on it. **Back to app** keeps the page open.

<a id="models"></a>

## Choose your model

Open **Settings → Agents → Models**.

- **Codex or Claude Code:** choose **Sign in**. If the CLI is missing or too
  old, run the command shown, then choose **Check**.
- **API key:** choose **Add API key** and a provider, such as DeepSeek.
  DeepSeek tasks need the Codex CLI, but no Codex account.
- Once it shows **Ready**, pick your Development and Review models. Repository
  **Model overrides** can choose differently.

Keys are stored encrypted on your Mac.

<a id="claude-accounts"></a>

## Keep Claude Code accounts separate

1. In **Models → Claude accounts**, choose **Add Claude account** and sign in.
2. Pick the account for Development or Review.
3. To keep an account to one repository, add it in that repository’s **Model
   overrides**, or choose **Use only here**.

For a company gateway such as LiteLLM, choose **Gateway token** and enter its
URL and token instead of an email. The token is stored encrypted on your Mac.

Existing tasks keep the account they started with.

<a id="agent-tasks"></a>

## Start an agent task

1. Open **Tasks → + New task** and pick a ready model.
2. Describe the outcome and how to check it.
3. Check **Automation**, then **Send**. The task gets its own branch and
   working copy.

**Steer** adds guidance, **Run next** queues a message, and **Stop this turn**
interrupts. Check **WorkBench → Changes** and the tests before **Mark it
complete**.

<a id="delivery-and-ci"></a>

## Deliver changes and repair CI

The composer’s automation chip sets how far a new task goes. Each step includes
the ones before it:

- **Auto-Commit:** commits on the task’s branch. Nothing leaves your Mac.
- **Auto-Draft PR:** pushes and opens a draft PR.
- **Auto-CI fix:** reruns flaky jobs, repairs failures the PR caused, and marks
  the PR ready once checks pass.
- **Auto-Review fix:** answers review comments and pushes fixes, at most three
  rounds per thread.
- **Auto-merge:** merges after the delay in **Settings → Agents → Workflow**,
  once GitHub allows it and no review thread is open.

When the agent rewrites pushed commits, for example by splitting or squashing
them, the next push replaces them with a lease. It stops instead when the PR
branch has commits the task never had. When automation stops on an error, your
next message retries the delivery, even if that turn changes nothing.

Every step after Auto-Commit writes to GitHub, and repairs use your model
allowance. The stop square above the composer halts automation and keeps your
draft. CI watching runs while ByteQuay is open and resumes after a restart.

<a id="commits-and-backports"></a>

## Cherry-pick and backport

Link related repositories once under **Related workspaces** in repository
settings. Fork links form a repo line that every workspace in it shows.
Tasks can read the code and memory of every watched repository; a link adds
guidance and lets a task start work there.

1. In **Commits → History**, pick upstream commits or a merged PR.
2. Choose **Cherry-pick**, check the **Dry run**, then **Start cherry-pick**.
   This lets ByteQuay push a work branch, open a draft PR, and fix CI.
3. Follow the task as the agent resolves conflicts, builds, and tests. Message
   it at any time.
4. When CI is green, choose **Approve squash**, then **Merge pull request**.

**Pause** holds the task; **Abort** closes the PR and deletes the work branch.
**Backport** carries a PR down the line, one cherry-pick per repository.
Each backport PR uses the destination repository’s template and title conventions
from recently merged PRs. Without a clear convention, its title is
**Backport: _source PR title_**.

<a id="issues-and-jira"></a>

## Start from a work item

Open **Work items**. **Local** items stay on your Mac; **GitHub issues** and
**Jira** show remote work.

1. Choose **Local → New** and add context and acceptance criteria.
2. **Start development** starts the agent. GitHub and Jira items open a
   prepared composer first.
3. Open the item to follow its tasks and PRs. By default it is done when its PR
   merges, or choose **Mark finished**.
4. **Push to remote…** publishes it as a GitHub issue or Jira ticket.

**Link work item** in a Local item’s **Context** references other work.

<a id="epics"></a>

## Organize an Epic

An Epic groups related work items and their tasks. Any Local item, GitHub issue,
or Jira ticket can be one; the grouping stays on your Mac.

1. On workspace Home, choose **Create Epic** from the Epic tab, or **Mark as
   Epic** on a work item. **Talk it through** lets an agent draft it.
2. Add work with **Add existing** or **Suggest work**.
3. Track **Planned**, **Working**, and **Done**. **Run** starts a task.
4. When a PR merges, **Next in Epic** suggests what to start next. Nothing
   starts by itself.
5. Review the outcomes, then **Conclude Epic**.

<a id="artifacts"></a>

## Learn with Artifacts

1. Open **Artifacts** and ask in **Ask about this repository**. Answers cite
   files and end with a question card. A card waits five minutes; if it times
   out, an alert opens the conversation so you can reply there.
2. Choose **Make the page** when offered, or ask for one at any time.
3. Ask for changes in the same conversation; **Versions** keeps each save.

The agent reads the repository but never changes it.

<a id="memory-and-specs"></a>

## Build repository memory

1. Open **Memory → Learn from my PRs**, or **Learn from this PR** on a closed or
   merged PR.
2. Check **Rules**, **Recipes**, and **Specs**, and edit or delete entries.
3. Choose whether **Reviews**, **Development tasks**, or both use memory.

**Memory → Check against spec** has an agent compare code with a spec and
reproduce failures.

<a id="circles"></a>

## Follow a Circle

Choose **Circles → Create a circle**, then pick people and repositories. It
sends no invitations, and **Insights** cover only what ByteQuay has collected.

<a id="jira"></a>

## Connect Jira

1. Open **Settings → Integrations → Jira → Add account** with your site, email,
   and API token.
2. In **Work items → Jira**, choose a board and a sprint or backlog.
3. Open a ticket and choose **Start development**.

**Open in Jira** handles anything ByteQuay doesn’t.

<a id="gmail-and-google-calendar"></a>

## Connect Gmail and Calendar

1. In **Settings → Integrations → Google Apps**, install the connector and
   **Sign in with Google**.
2. Choose **Compose** or **Reply**, then check **Review email** before **Send
   email**.
3. Save a Gemini API key to use **Ask Gemini**; it uses your key’s quota.

Mail and conversations stay in memory until you quit.

<a id="lab"></a>

## Measure with ByteQuay Lab

Choose **Settings → Integrations → Lab → Open Lab**. No terminal or Python is
needed.

- **App health** shows recent errors, send latency, and resource use, with
  suggested actions. **Analyze now** refreshes it.
- **Start resource monitoring** tracks memory and CPU until you stop it on
  **Processes**.
- **Agent activity** shows agent performance, review guidance, and context
  costs.
- **Improve** writes and tests new review rules on cases the Lab never saw;
  every step runs from a button.

[Explore the Lab](dist/lab.html) · [Start the Lab](dist/lab-guide.html)

## Take a companion break

Open **Settings → Personal → General → Focus breaks** and turn on **Break
reminders**. Choose **Chubby orange cat** or **Dancing husky** under **Break
companion**. Your companion walks into ByteQuay first. When it reaches the
center, it blocks interaction with your work while your current page stays
visible behind it. The orange cat plays a smooth video: it lies down, rolls onto its
side, yawns, then sleeps with closed eyes and gentle breathing for the remainder of the break.
When the break ends, the cat wakes up and walks offscreen. The husky stands up,
steps, and sways its paws. Reduced motion skips the walk and shows a still
companion. If the cat video cannot play, the break uses its resting image.

Choose your focus and break times; the default is 25 minutes of focus followed
by a 5-minute break. The break fills only the ByteQuay window, and your agents
keep working.

Use **Pause timer** or **Take a break now** in the same settings. The break
shows only your companion and a small **Dismiss** button. Choose **Dismiss**
or press **Esc** to see the remaining break time. Choose **End break** to return
to work, or **Keep taking a break** to close the confirmation. During the cat's
walk out, choose **Dismiss** or press
**Esc** to return immediately. The next focus session starts when the companion leaves.

Reminders are off by default. Settings stay on this Mac; reopening ByteQuay or
changing the times starts a fresh session. Changing your companion keeps the
current timer and pause state. Focus time pauses while the window is hidden
or minimized. A due break waits until you finish an open form or confirmation.
If you open one during the walk, your companion waits before locking the app.
Switching native pages during the walk restarts the entrance over the new page
and keeps the full break time.

<a id="help"></a>

## Get help

- **GitHub unavailable:** renew your CLI sign-in or token in **Settings →
  Integrations → GitHub**.
- **Agent unavailable:** choose **Check** in **Settings → Agents → Models**.
- **Review could not finish:** use **Resume review**, or **Recover runtime**
  when shown.
- **Queue paused:** fix the cause shown, then choose **Resume queue**.
- **Delivery unconfirmed:** check GitHub, then choose **Confirmed posted** or
  **Confirmed not posted** in **Submit review**.
- **Check failed:** open **PR → Show log**, or **Open in GitHub**.

To build from source, read [CONTRIBUTING](../CONTRIBUTING.md). For product
intent, read the [design](../docs/design.md) and
[architecture](../docs/architecture.md).
