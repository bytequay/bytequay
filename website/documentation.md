# ByteQuay documentation

Set up a workspace, review a change, and build with an agent.

- [Get started](#getting-started)
- [Review pull requests](#pull-requests)
- [Work with agents](#agent-tasks)
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
2. Choose **Use my GitHub CLI login** after running `gh auth login`, or
   **Connect with token →**. The app recommends a classic token with `repo` and
   `read:user` scopes.
3. Choose **Home → Repos you watch → + Watch**. Select a repository, check its
   branch and publication destinations, then **Watch repository**.
4. Open **Pull requests**, select a PR, and inspect **WorkBench → Changes**.

You can review without an agent. For coding tasks,
[connect a provider](#agent-tasks). Change appearance in **Settings → Personal →
Appearance**.

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

On working days, after the time you set, Home opens with **Today**: up to five
things worth doing first, each with a reason. An agent picks them from
ByteQuay's records; it never writes to GitHub or starts a task. If ByteQuay was
closed at that time, Today is made when you return.

- Click a row to open its PR, task, issue, ticket, or work item. Hover it for
  **Done** or **Not today**; a row also closes when its work happens.
- The circular arrow makes the list again. A workspace Home shows only its
  repository's rows; **Needs attention** marks Today rows with a sun.
- On the last working day, **This week** looks back at what merged and slipped.
- **Settings → General → Daily agenda** sets the time and working days, and can
  plan around your calendar's busy times (never event titles).

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
new prompt. If none exists, it creates a task and local worktree, then waits
for your message. Leaving the PR while creation is in progress does not cancel
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
   publishes it. **Request fix → Send to developer** asks the development agent
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

<a id="agent-tasks"></a>

## Start an agent task

1. Install Codex or Claude CLI, then open **Settings → Agents → Models**. **Sign
   in**, choose **Check**, and wait for **Ready**. Supported API-key providers
   are also available.
2. Select Development and Review defaults. Use a repository’s **Model
   overrides** for different choices.
3. Open **Tasks → + New task**. Describe the outcome, useful context, and how to
   check success.
4. Review **Automation**, then **Send**. ByteQuay prepares a separate branch and
   working copy.

During work, **Steer** adds guidance when supported; **Run next** queues a
message. Inspect permission requests before allowing them. **Stop this turn**
interrupts the agent.

Check **WorkBench → Changes**, **Summary**, and tests before accepting the
result. Use **Mark it complete** when the task is idle and its queue is handled.
**Archive** retains history; **Delete** removes local task data.

### Accounts, usage, and skills

- **Usage** shows provider-reported limits or balances, including activity
  outside ByteQuay. Missing values are unknown.
- Repository-specific Claude accounts are available only there. Existing tasks
  keep their selected account.
- **Settings → Agents → Skills → Add skill** saves reusable instructions for
  subsequent turns.

<a id="delivery-and-ci"></a>

## Deliver changes and repair CI

Review **Automation** before the first submission; the choices lock when the
task starts.

- **Auto draft PR** pushes commits and creates or updates a PR. It defaults on.
- **Auto CI fix** repairs supported failures and can mark a draft ready. It also
  defaults on and can write to GitHub.
- **Auto review summary** adds private notes; it defaults off.
- **Keep commits local for this turn** disables publication and CI follow-up for
  that submission.

Finished development work is committed automatically. Set new-task defaults in
**Settings → Agents → Workflow**.

During CI repair, use `@ci` to guide the agent when supported. To resume
development, choose **Stop automation and continue…**. Review and merge the PR
yourself.

<a id="commits-and-backports"></a>

## Cherry-pick and backport

1. In **Commits → History**, check the first control for the destination and the
   second for the source.
2. Select commits or a range, then **Cherry-pick**. Review the route, order, and
   resolver before **Start cherry-pick**.
3. Follow the run under **Commits → Cherry pick**. Use **Send note** or **Cancel
   execution** if needed.
4. When ready, choose **Deliver this generated branch → Push branch**, then
   **Create pull request**.

Cherry-picking starts locally. **Dry run** shows the plan. PR creation also
authorizes the disclosed CI monitor.

For chained backports, configure **Related workspaces** in repository settings,
then use **Backport** or send `@backport example/project #123`. Follow **Tasks →
Backport chains**. Each step waits for its source PR to merge; you control
merges.

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

<a id="epics"></a>

## Organize an Epic

An Epic connects a larger issue or ticket to its work, attempts, and
dependencies.

1. Open the Epic’s **Work** view. Review offered source children before
   **Sync**.
2. Add a Local item, **Add existing**, or **Suggest work**. Inspect suggestions
   before **Accept as Local**.
3. Use **Planned**, **Working**, and **Done** to track progress. **Run** on
   eligible work starts its development task immediately.
4. Review outcomes, then **Conclude Epic**. Closing the source issue or ticket
   is separate.

<a id="goals"></a>

## Explore Goals

**Development-only experiment.** Packaged releases hide Goals and automatic
guidance.

1. Open the floating **Goal** control or press **⌥G**. Describe the outcome in
   **Guide**.
2. Review the objective, success definition, and constraints before **Accept
   goal**.
3. Review recommendations and follow **Work → Next / Working / Blocked / Done**.
   Use **Waits on** for dependencies.
4. Add evidence and choose **Accept result** when the outcome is achieved.
   Finishing a task alone does not complete the work item.

Use **Pause guidance** to pause suggestions.

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

Use the separate Python dashboard to inspect resources, test review guidance,
and understand context costs. Record a change and check the results.

[Explore the Lab](dist/lab.html) · [Start the Lab](dist/lab-guide.html)

<a id="help"></a>

## Get help

- **GitHub unavailable:** renew CLI sign-in or update the token in **Settings →
  Integrations → GitHub**. Check repository access.
- **Agent unavailable:** sign in under **Settings → Agents → Models**, then
  **Check**. Inspect repository overrides too.
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
