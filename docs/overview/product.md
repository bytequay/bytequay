# Product design

ByteQuay is a local-first macOS app for GitHub pull-request work and local agent
tasks. It brings repository context, review, development, and follow-up into a
single desktop workspace. The public website introduces this foundation;
individual design decisions are not promises of a finished release.

## Surfaces

| Surface | Purpose |
| --- | --- |
| Home and Workspaces | Find watched repositories, recent activity, and work that needs attention. |
| Pull requests | Inspect descriptions, patches, checks, conversation, and perform explicit GitHub actions. |
| WorkBench | Inspect changes, summaries, private review findings, and task-bound code and tests. |
| Agent tasks | Work with Codex or Claude using a shared transcript, approvals, steering, queues, and child-agent inspection. |
| Issues, Jira, and Goals | Connect external work to local outcomes and development tasks; plan, research, and learn with user-controlled acceptance. |
| Settings | Manage credentials, providers, repository overrides, skills, and application preferences. |
| Embedded browser | Keep GitHub and other supporting pages alongside the desktop workflow. |

## Repository identity and navigation

One physical repository has one durable workspace identity. For foreign
repositories, the monitored source and personal fork have different roles:
the source supplies issues, PRs, and the development base; the selected push
remote owns publication. Creating a missing GitHub fork requires explicit
confirmation. Repository renames must preserve identity.

Home and repository views navigate to the same work. Opening a PR or issue over
another surface preserves the underlying route, scroll, and composer state.
Published PR changes remain distinct from edits in a task's working copy.

Unwatching retires a workspace while retaining its checkout, settings, tasks,
and history. Rewatching restores the existing workspace after routing is
revalidated. Deleting local data is a separate confirmed action, refuses active
or unverifiable work, and never deletes GitHub repositories or forks.

## Review and learning

WorkBench connects the purpose of a change, explanations of individual commits,
code, evidence, and actionable findings. Development summaries explain committed
work; an explicit review starts a separate reviewer task and worktree. Merely
opening the view never starts an agent.

Findings and their discussions are private until the user publishes. Users may
ask about a quoted answer or reviewer comment in a separate read-only
conversation. Optional automatic comment summaries do not authorize code fixes
or GitHub replies. Drafting a reply fills an editor; the user posts it.

## Planning and context

A Goal records an objective; a WorkItem records an accepted outcome. Tasks and
attempts execute work without becoming the definition of success. Source issue,
Jira ticket, and task associations retain one outcome identity across retries
and moves. Done requires the user's acceptance; merge-based outcomes also require
verified merged deliverables covering the accepted scope. Task completion and
source closure alone do not establish Done. Proposals become work only when
accepted.

Goal conversations, research, courses, practice, and learning records share this
model. Learning claims need evidence and remain user-correctable. Ongoing
discovery and guidance have explicit limits; see [remaining work](roadmap.md).

Repository memory holds sourced rules, recipes, and specifications. Guideline
files, maintainer history, user corrections, and proven commands provide context;
reading arbitrary code does not authorize inventing a rule. User-owned memories
remain under user control, and agents receive only the applicable repository
context.

A spec check compares a selected directory to linked specifications and reports
gaps with reproduced test evidence. Draft issues remain private until the user
files them. Research or lesson work does not silently become development work.

Private circles are account-local attention preferences, not shared teams,
permission groups, or GitHub writes. Their activity and collaboration summaries
describe observed data rather than complete measures of contribution.

## Availability

ByteQuay remains pre-1.0 and macOS-only. The working code includes both the PR
foundation and the replacement agent service; the former task-agent and staged
development-flow systems remain removed. The [roadmap](roadmap.md) distinguishes
remaining work from implemented foundations without preserving delivery logs.
