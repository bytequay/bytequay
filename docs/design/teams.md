# Private circles

## Product contract

Circles are private lists of GitHub people selected by the signed-in user. The
product calls them circles everywhere, including navigation and Home's filter,
because "teams" suggests GitHub organization teams with permissions and
notifications. Membership is local, never requires an invitation, and never
changes GitHub membership, subscriptions, permissions, or notifications. Saved
circles have no product member limit. A person may belong to multiple circles
but receives one attention preference and is collected once.

A circle has a name, description, avatar, and optional repository scope. The
avatar is a plain solid color from a fixed palette, drawn as a rounded square
or a circle; a new circle starts on a color no other circle uses,
and a circle saved before avatars existed reads as gray and square. Creation can
include selected people and saves the circle only after all members resolve; a
person who cannot be verified is named, and nothing is saved. Creation and Add
people reuse the repository-assignee picker, searching contributors already
stored locally across repositories. Typing never searches GitHub; selecting
people is explicit. The selected numeric GitHub ID is verified when resolving
the saved login, so stale contributor names cannot silently add a different
person. An empty scope covers available activity; a repository scope matches
canonical owner/repository names and suggests watched repositories. GitHub
numeric user IDs identify members; logins and profile details are display
metadata. Directory and retained observations belong to the connected account.

## Page

The supplied Claude Design prototype (`Teams Redesign.dc.html`, section 1a) is
the authoritative UI. The page has a circle list (All circles, then each circle
with its people count and scope), one work surface, and a person panel beside
the work. Below a 1180px window the list becomes a switcher above the title,
and the panel overlays the work when less than 1000px would remain beside it.

The header names the circle, its description, people and scope, with Add
people and Edit (or New circle on All circles). Four views follow: Needs you,
Activity, Members, and Insights, switched with ⌘1–⌘4. One filter bar holds the
person filter (not on Members), the period (not on Needs you), Activity's type
or drill-down metric, and the collection status with Refresh. Esc closes the
top layer: popover, dialog, then the person panel. The first visit opens All
circles on Needs you; later visits reopen the last circle and view used with the
same account.

Removing a person from a circle and acknowledging or snoozing an item offer an
immediate Undo. Removal is undone by re-adding the stored GitHub ID. The person
panel's circle menu adds and removes that person across circles directly.

## Attention

Needs you lists the current circle backlog, not limited by the period: review
requests (including new commits on a PR the viewer reviews) and issues assigned
to the viewer whose author or review requester is a member in the selected
scope. Circle repository scope applies. Rows name the member and relationship
separately from the latest activity actor, who is labeled when outside every
circle; hovering that latest activity unfolds its comment, up to a hundred
words. The rows are the ones both Homes draw for Needs attention, so resting on
one for two seconds opens the item's hover card there too. Other updates stay
in Activity.

Both Home views reuse the existing eligibility, acknowledgment, snooze, pin,
and source-filter flow, and so does Needs you: handling an item in either place
clears it in both. Circle selection happens before the four-row cap in the
shared selector. Native notification eligibility is unchanged.

Among the last week's activity, Home ranks a circle member's review request or
assignment above other people's asks, and a member's own pull requests and
issues below everything tied to the viewer personally; [Agent Task
UI](agent-task-ui.md) has the full order. A member's comments and updates on
another person's pull request stay out of Home. Home's Circles filter is
independent of the source tabs and narrows the same ranked list before the row
cap. Acknowledgment, snooze and pins keep
their existing visibility rules. Rows name the matching circle, member, and
relationship (PR/issue author or review requester), separately from the latest
activity actor. Another person's comment on a member's PR does not make that
commenter a circle member.
Current attention projections expose author/requester logins, so circle
matching uses saved GitHub logins. A renamed member can be re-added to refresh
their profile without duplicating their numeric membership identity.

A circle member's own item can appear in Home without a personal review or
assignment request. Circle membership itself never creates an update. Adding or
removing a member does not change the source activity version or wake handled
items. Pending-age presentation uses actual source evidence and never labels an
update timestamp as a proven review-request timestamp.

## Activity and insights

The Circles sidebar entrance opens the page. Circle activity is shown only
here; Home's activity sidebar contains GitHub and following activity.
All events retain source identity, actor identity, repository/item identity, and
event time before grouping or display limits. Activity shows one row per work
item headed by its latest update, with earlier updates expandable and each
linking to its source. Verbs separate direct interactions ("mentioned you",
"reviewed your PR") from participation, and actors outside every circle or
unidentified by the source are labeled as such.

Statistics use retained facts, not the visible page. Contributions and direct
interactions with the viewer remain separate from merely participating in the
same work. Every count is a link: its exact event filter (PRs opened, authored
PRs merged, merges performed, PRs reviewed, issues opened, issues closed,
comments, direct interactions, or shared work) applies before the display
limit, together with the person and period. Scope totals sum people, except
shared work, which counts each item once. Insights also shows recorded updates
per day and marks days outside the collected window, before the first pass's
look-back or after the latest pass, as not collected rather than quiet.

Collection reuses GitHub credentials, rate-limit handling, and quiet hours.
Bounded batches rotate through people and repository sources. Public user
events supplement accessible repository activity; they cannot prove complete
private, historical, or real-time coverage. The status reads Partial when the
latest pass skipped people, repositories, or sources, and its popover names
them. Reads serve SQLite; an explicit Refresh requests a bounded collection
pass, and a failed refresh keeps the data already shown. A member count is not
used to conceal a collection limit.
Private repository activity requires a watched repository that the connected
GitHub account can access. Repository scope restricts matching; it does not
subscribe to or watch a repository. A refresh advances the shared collection
batch, independent of the selected person or circle.

Retained observations cover up to 90 days. Person and circle views support a
selected period and link to the underlying source work. Explicit mentions,
review requests between the viewer and a member, and reviews of each other's
PRs count as direct interactions. People are listed alphabetically; no ranking,
productivity score, or inferred trust score is computed.

## Implementation boundaries

Membership uses the existing application-settings storage boundary. Activity
values/storage ports belong to core, Hibernate entities/adapters to
persistence-sqlite, and collection, projections, and HTTP to app. The backend
API and stored keys keep their original `teams` names. The frontend uses the
existing typed Electron bridge, React, and plain CSS. No new library, Maven
module, agent execution path, or GitHub publisher is introduced.

Progress and verification are recorded in [the temporary checklist](team-impl-tmp.md).
