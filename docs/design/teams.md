# Private teams

## Product contract

Teams are private circles of GitHub people selected by the signed-in user.
Membership is local, never requires an invitation, and never changes GitHub
membership, subscriptions, permissions, or notifications. Saved circles have no
product member limit. A person may belong to multiple circles but receives one
attention preference and is collected once.

A circle has a name, description, and optional repository scope. Creation can
include selected people and saves the circle only after all members resolve.
Creation and the People tab reuse the repository-assignee picker, searching
contributors already stored locally across repositories. Typing never searches
GitHub; selecting and saving people is explicit. The selected numeric GitHub ID
is verified when resolving the saved login, so stale contributor names cannot
silently add a different person. An empty scope covers
available activity; a repository scope matches canonical owner/repository names.
GitHub numeric user IDs identify members; logins and profile details are display
metadata. Directory and retained observations belong to the connected account.

## Attention

Both Home views reuse the existing eligibility, acknowledgment, snooze, pin,
and source-filter flow. Circle selection happens before the four-card cap in
the shared card selector. Native notification eligibility is unchanged.

The mixed preview shows the newest source updates across all item types. Circle
membership never replaces a newer item with an older team item. A Team-only
filter is independent of the source tabs and narrows the same newest-first
list before the card cap. Acknowledgment, snooze and pins keep their existing
visibility rules. Cards name the matching circle, member, and relationship
(PR/issue author or review requester), separately from the latest activity actor.
Another person's comment on a member's PR does not make that commenter a circle
member.
Current attention projections expose author/requester logins, so team matching
uses saved GitHub logins. A renamed member can be re-added to refresh their
profile without duplicating their numeric membership identity.

Ordinary updates can appear in Home without a personal review or assignment
request. Circle membership itself never creates an update. Adding or
removing a member does not change the source activity version or wake handled
items. Pending-age presentation uses actual source evidence and never labels an
update timestamp as a proven review-request timestamp.

## Activity and insights

Teams opens a local-first page with circle management, member selection,
activity, people, and insights. Home includes a compact Team activity entrance.
All events retain source identity, actor identity, repository/item identity, and
event time before grouping or display limits. Statistics use retained facts,
not the visible page. Contributions and direct interactions with the viewer
remain separate from merely participating in the same work.
Statistic links apply their exact event filter before the display limit, while
the totals continue to cover all retained observations in the selected period.

Collection reuses GitHub credentials, rate-limit handling, and quiet hours.
Bounded batches rotate through people and repository sources. Public user
events supplement accessible repository activity; they cannot prove complete
private, historical, or real-time coverage. The UI exposes collection coverage,
last refresh, source failures, and observed-history limits. Reads serve SQLite;
an explicit Refresh requests a bounded collection pass. A member count is not
used to conceal a collection limit.
Private repository activity requires a watched repository that the connected
GitHub account can access. Repository scope restricts matching; it does not
subscribe to or watch a repository. A refresh advances the shared collection
batch, independent of the selected person or circle.

Retained observations cover up to 90 days. Person and circle views support a
selected period and link to the underlying source work. Counts distinguish
authored PRs merged, merges performed, reviews, issue activity, and direct
interactions. Explicit mentions, review requests between the viewer and a member,
and reviews of each other's PRs count as direct interactions. No productivity
score or inferred trust score is computed.

## Implementation boundaries

Membership uses the existing application-settings storage boundary. Activity
values/storage ports belong to core, Hibernate entities/adapters to
persistence-sqlite, and collection, projections, and HTTP to app. The frontend
uses the existing typed Electron bridge, React, and plain CSS. No new library,
Maven module, agent execution path, or GitHub publisher is introduced.

Progress and verification are recorded in [the temporary checklist](team-impl-tmp.md).
