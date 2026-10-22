# ByteQuay design

ByteQuay is a macOS desktop app for GitHub work and local coding agents. Its
purpose is to keep repository context, implementation, review, and delivery in
one workspace, so people can spend more time understanding and deciding.

As agents write more code, keeping up with the details and judging the next
change can become harder. ByteQuay lets agents build and guide while the person
chooses how deeply to explore. Any part of the work can become a discussion:
understand the change and its assumptions, then merge with confidence.

“Review is all you need” describes that direction. The person keeps control of
the goal, accepted work, and publication.

This document describes the product at a high level. [Architecture](architecture.md)
explains the system boundaries. The [user guide](../website/documentation.md)
describes features and everyday usage. Detailed implementation contracts belong
with the source and tests rather than a separate public specification per feature.

## Product experience

Start with a repository and the work that needs attention. Open a pull request,
start a development task, or organize a larger outcome. Keep the source context
and the resulting changes close enough to inspect together.

| Feature | What it helps people do |
| --- | --- |
| Home and workspaces | Follow repositories, activity, and work that needs attention. |
| Today | Start each working day with a few things worth doing first, each with its reason, picked from your own work. |
| Assistant | Ask about your day, find and open your work, start tasks, and answer them from one panel. |
| Development tasks | Give Codex or Claude a request, follow its progress, inspect changes, and continue the conversation. |
| Pull-request review | Understand the purpose of a change, read patches and checks, discuss findings privately, and choose what to publish. |
| Cherry-pick | Select commits, inspect their destination and order, then follow one task from local conflicts through build, CI fixes, rebases and the approved squash to the merge. |
| Artifacts | Ask about a repository in a conversation, then keep what you learned as an interactive page beside it. |
| Epics | Group related work, break it into useful items, follow attempts, and review progress toward a shared result. |
| Tears of time | Inspect and edit the task's working copy, run supported development tools, and examine their output. |
| Work items | Read GitHub issues, Jira tickets, and private local work together; retain their context through development and optional publication. |
| Memory and specifications | Retain useful repository guidance and compare code with its intended behavior. |
| Related workspaces and backports | Follow changes across related repositories and choose where they should be carried next. |
| Google apps | Browse memory-cached mail and day/week calendars, write email, switch accounts, and talk to Gemini inside Google Apps. |
| Circles | Keep a personal view of the people and work that matter to you. |

These are feature descriptions, not fixed screen layouts or promises about every
provider and release. The app, source, tests, and release notes describe the
available behavior. The website provides short guides and representative app views.

## Design principles

### Keep context connected

Repositories, source requests, tasks, commits, and pull requests should be easy
to move between. A conversation and its code belong to the same work. Switching
views should preserve useful context and unfinished input.

New work created by ByteQuay starts as a private local work item, including agent
follow-ups, Epic subtasks, and confirmed specification findings. GitHub issues and
Jira tickets share its workspace view and agent reading tools. Starting development
keeps the description, source, accepted criteria, and attempts connected. Publishing
a local item to GitHub or Jira is a separate explicit action and preserves its local
identity; delivering a pull request does not require publishing the item.

Epics are ByteQuay's local planning groups. A user or agent can mark a local work
item as an Epic and assign related work to it. A GitHub issue or Jira ticket can
also provide an Epic's context; its classification and work membership remain
local and do not change the remote issue, ticket, or hierarchy.

A local work item can also link other work in its workspace as context. Users and
agents can add or remove these references to local items, GitHub issues, or Jira
tickets. Context links keep related requests discoverable without making them
subtasks, blockers, or sources of the current item. Agents see direct linked
summaries and can read each target when needed.

### Make the change understandable

Show intent, code, evidence, and discussion together. Distinguish an agent's
explanation from a verified result. Keep missing information and unresolved
questions visible instead of presenting uncertainty as success.

### Keep the person in control

Reading and private discussion do not publish anything. GitHub writes need a
direct user action or an explicitly accepted automation scope. A completed
agent task does not by itself prove that the user's goal has been achieved.

### Keep work local

All ByteQuay app data is stored locally on the Mac, including conversations,
reviews, settings, and working copies. Development tasks use separate local
worktrees so their changes can be inspected independently. A task’s reviewer
shares that task’s checkout and can inspect uncommitted changes. Its conversation
and review permissions remain separate, and ending review preserves the checkout. GitHub, model
providers, and optional integrations remain external network services.

### Keep the interface coherent

Use the same navigation, review, conversation, and status patterns across
features. Support readable light and dark appearances. Prefer focused surfaces
with clear next actions over exposing internal machinery to the user.

### Keep the system proportionate

Extend the current application and agent services when adding features. Introduce
new modules or durable state only for a real ownership, recovery, or dependency
boundary. Preserve uncertain user work rather than discarding it automatically.

## Documentation boundary

`docs/` contains only this document and [architecture.md](architecture.md).
Keep these two files focused on durable intent, responsibilities, data flow, and
tradeoffs. Describe individual capabilities as features in the
[user guide](../website/documentation.md), with instructions that match the app.
Do not add implementation plans, per-feature design documents, progress logs,
verification captures, or prototype collections here. Use Git history for past
decisions and GitHub Releases for published release notes.
