# Epic work items

Status: approved for implementation. The Epic drawer below is implemented; the
decisions after it are not yet built.

This document decides how an Epic gets its work items and how their status and
the Epic's progress show. It builds on the [work planning design](issue-development.md)
(Epic → Work item) and takes work-item states and the Done rule from
[Agent guided goals](goal-guidance.md#trustworthy-progress), whose rules win where
they differ.

## Current state

An Epic is a GitHub issue or Jira ticket marked locally as an Epic (`isEpic`).
Workspace Home lists the workspace's Epics in the **Epic drawer**, tucked under
the sidebar's right hairline: collapsed, one tick per Epic (up to four) peeks
out; hover, click or the keyboard slides it out over the page without moving
anything. Each card shows the source key, its status, a Jira Epic's child count
from the saved ticket detail, and its age, and opens the issue or ticket.

Work items already exist for Goals: identity, workspace, optional Goal, title,
kind, acceptance criteria, completion rule, state, links to issues, tickets,
tasks and PRs, history, priority, order and blockers.

## Decisions

1. **ByteQuay work items.** An Epic's items are the existing work-item records
   with an Epic membership, one Epic per item. They link issues, tickets, tasks
   and PRs. One item can belong to a Goal and an Epic at the same time, with its
   own place in each. Organizing an Epic never writes to GitHub or Jira.
2. **One entrance: the Epic view.** A drawer card opens an Epic view over the
   page, the way an issue opens in its zoom layer. Adding, editing and ordering
   items happen only there; there is no "Add to Epic" on other surfaces for now.
   The view's header links to the source issue or ticket.
3. **Quick add.** "New work item" takes a title and Return. The item is Develop
   work, done when its PR merges and the user accepts. Its done-when text and
   completion rule are edited afterwards.
4. **Add existing** finds an issue, ticket, PR or task and uses the existing
   find-or-create operation: it reuses the work item the source or task already
   belongs to, naming the Epic it moves from, or creates one.
5. **Source children sync on a click.** The source's GitHub sub-issues and Jira
   children are detected and shown in a "Found in Jira · 6" (or GitHub) strip.
   Sync makes them items; Not now hides the strip until the detected children
   change. Children found later surface the same way. Nothing joins on its own,
   and syncing never writes upstream. A synced child later removed upstream
   keeps its item, marked "no longer a child".
6. **Status rows.** Items keep the four states, Planned, Working, Done and
   Dropped; running, in review, waiting on you and blocked are details derived
   from tasks, PRs and blockers. A row leads with a state icon and one short
   detail ("running 4m", "#24990 in review", "waiting on you", "after DDL
   invalidation", "#24931 merged" with Accept). The full sentence is the row's
   tooltip. Dropped items fold into "1 dropped" at the end of the list.
7. **Synced children follow ByteQuay's rules.** Their upstream status shows as a
   side label ("Jira: In Progress", "GitHub: closed"). When a child closes
   upstream its row offers Accept, one click to Done, as a merged PR does.
   Upstream closure alone never makes an item Done.
8. **Progress.** Progress is Done over the in-scope items; Dropped items are
   counted apart, and an Epic without items shows no progress. The Epic view
   header draws a done/working/planned bar with "2 of 6 done · 2 working · 2
   planned · 1 dropped". With every item done it reads "All 6 done · ready to
   conclude" and offers Conclude Epic. A drawer card draws a thin bar under its
   title and "2/6" at the end of its meta line. The collapsed ticks keep counting
   Epics, not progress.
9. **Order and blockers.** Items are reordered in the Epic view, and blockers
   between the Epic's items are edited there.
10. **Run.** An item whose source is an issue or ticket starts development
    through the existing issue or Jira composer. An item without a source opens
    the task composer with its title, shows no source reference, and the task it
    creates belongs to the item.
11. **Agent breakdown.** An agent reads the Epic's source (title, description
    and children) and proposes items, each a title and a done-when line. The
    proposals wait in the Epic view until the user accepts or dismisses each;
    nothing is created before acceptance.
12. **Conclude.** With every item done, Conclude Epic records the accepted
    outcome and offers closing the source issue or ticket as a separate,
    explicit action. A concluded Epic leaves the drawer.
13. **Unmarking.** Unmarking an Epic that has items asks for confirmation first.
    Its card then leaves Workspace Home and its Epic view's data is removed:
    memberships, order, sync and conclusion records. Items that exist only for
    the Epic are deleted; an item that also belongs to a Goal or holds a task
    attempt keeps those and only leaves the Epic. Marking it again starts with
    an empty Epic view.

## Notes for the specification

- An Epic is identified by its source's canonical identity, the same identity
  work-item links use: GitHub host and stable issue ID, or Jira cloud and issue ID.
- Home renders Epic progress from local reads only; detecting children never
  runs on the drawer's render path.
- Jira children come from the ticket detail read. GitHub sub-issues need a new
  read of GitHub's sub-issues API.
