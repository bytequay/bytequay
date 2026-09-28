# Jira development integration

## Product boundary

Jira Cloud is the source of truth for ticket content, comments, sprint membership,
assignee, labels and workflow. ByteQuay provides a native Jira page with current
sprint and completed views, cached source context and its existing development
task flow. The Jira browser page remains available through Open in Jira for
unsupported fields and administration.

The first release includes:

- Connect a Jira Cloud account with a personal API token, or through the system
  browser when OAuth is configured; choose a site, board and active/future sprint
  or backlog. Assigned to me is the default filter.
- Switch between list and status-grouped board views. The default board shows
  To do, In progress, In review and Done together, with Jira's actual status on
  each card. Review is a display group for an in-progress status whose name
  contains “review”; it is not a fourth Jira category or a new transition.
  Unknown categories retain their actual status name. Clicking anywhere on a
  ticket card opens a large centered dialog over the board or list, with a short
  zoom animation (disabled for reduced motion), description, comments (newest
  first, under the comment box) and fields.
  Action icons and parent links keep their own actions. Escape or Close returns
  focus to the card; the board keeps its layout and scroll position. Ticket dialogs
  hide while the embedded browser or another layer is above them and restore with
  their drafts on return, so the layer opened last stays on top.
- Open a Jira Cloud ticket link (`https://<site>/browse/<KEY>`) in rendered
  Markdown as the native ticket, layered over the page, issue or pull request it
  came from. An explicit lookup resolves the key once; the ticket is then cached
  and addressed by its issue id. Links to unconnected sites, and tickets the
  connected account cannot read, stay web pages. Needs attention cards and task
  source cards open their ticket the same way, and a card's Start development
  opens the composer over its ticket. A ticket opened this way has no board, so sprint
  moves stay on the Jira page.
- Use a distinct Jira source icon across navigation, attention and task source
  cards. Ticket cards place the title first, labels/parent below it, and the issue
  type/key, priority and assignee in the footer. Unknown custom priorities retain
  their names without inferring rank from tenant-specific IDs. Assignee avatars
  use Jira's HTTPS Atlassian/Gravatar image URLs without API credentials, with
  initials when an image is unavailable. Both show the full name on hover.
- Browse Completed separately from the selected sprint. This view includes
  currently Done tickets across sprints that match the selected board and its
  mapped statuses, with the Assigned to me filter preserved. Group tickets into
  Today, Yesterday, This week, Last week and Earlier using local calendar dates;
  show missing completion dates in a separate group.
- Edit summary, labels, assignee, priority and due date when Jira permits them;
  move tickets to an active/future sprint or backlog; choose an available Jira
  transition and supply supported required fields. Additional field types use
  Open in Jira.
- Post a comment explicitly. Drafts survive failures. An uncertain send is not
  retried automatically, including after reconnecting through another connection.
- Mark a ticket as a local Goal, or start development on a leaf ticket. If it
  has no ByteQuay workspace, either action asks the user to choose and confirm
  one first. Save the choice locally and show the workspace name on the card and
  detail dialog. Later actions reuse it. Development uses the existing
  provider/model/worktree composer; canceling the composer keeps the assignment
  without creating a task. View task opens the resulting task instead of creating
  another one. Marking a ticket as a Goal also gives its workspace a goal named
  after it, which unmarking removes.
- Surface cached, assigned, unfinished leaf tickets from watched active sprints
  in global Needs attention. A workspace's Home lists only the tickets assigned
  to that workspace; Jira project names are never guessed to be
  repository/workspace names.
- Each card says what last changed on its ticket. Every refresh compares the
  ticket it fetched with the cached one it replaces: a status move is kept with
  where it came from and went, and any comment that arrived with it; a change to
  the title, assignee, priority, labels, due date, type or parent names the
  fields; a detail refresh names a new comment with its author and opening text.
  When the scheduled refresh finds a ticket in Needs attention updated with
  nothing ByteQuay saves changed, it reads that ticket's newest comment once.
  Jira's changelog is never read, so a status or field change names no actor,
  and the description is not compared because board and ticket reads format it
  differently. The card's strip says the ticket is assigned to you; the change
  is its folded actor line, and a card with no recorded change names the
  ticket's status and sprint there instead.

Automatic Done, outbound agent comments, webhook delivery, parent progress
statistics, Goal pinning, comprehensive custom-field editing, and inferred
high-value suggestions are follow-up work. Manual Done uses an available Jira
transition. Agent completion and successful checks are not Jira delivery events.
A later automatic rule must explicitly define the project's accepted delivery
signal (for example merged PR or deployment) and required transition fields.

## UI and task reuse

Home, Workspaces and Recent stay in the global sidebar group. Jira sits below
that group in application navigation and also appears as a workspace tab. Both
Jira navigation entries are shown only while a connected account has at least
one site; Settings → Integrations → Jira remains available to connect an account.
The workspace tab reuses the Jira page with an explicit workspace-assignment
filter for its current and completed tickets, including status choices and
counts. Ticket dialogs retain the workspace route. The shared Jira source mark
uses a monochrome outline matching the surrounding navigation icons.

The native page uses ByteQuay's current card, badge, dialog, Markdown and
composer styles. Start development and Goal are accessible icon actions with
tooltips and selected Goal styling. The task transcript shows a compact source
request card; the source attachment sits above the composer with issue details
on hover. No raw generated instruction block is rendered as the user's prompt.

Jira sources are distinct from GitHub sources. Their identity is `(cloudId,
issueId)`, never the mutable issue key. Display includes the familiar key and
site. A Jira ticket may belong to any explicitly selected repository workspace.
A persisted association admits one development task per source across workspaces
and connections. Goal changes and task admission share a source lock. Parent
items and completed tickets cannot launch development; the admission refresh
checks subtasks, hierarchy information and the Jira parent query.

Workspace assignment is keyed by the same cloud/issue identity and persists
separately from remote ticket snapshots in the existing SQLite-backed Jira local
metadata store. It is shared across connections and survives refresh or restart.
The workspace name is resolved from the current workspace record. A linked task
owns the assignment and cannot be moved to another workspace. A missing workspace
leaves an unstarted ticket unassigned. Goal marking requires an assignment, and
new development admission validates that its selected workspace matches it.

Task creation follows the canonical interactive task path, including provider
selection, execution, events, retries and delivery. Source persistence belongs to
the same unit of work as the new task, before the first turn is dispatched.
Retries reuse the accepted source and task; subsequent cache or account loss
cannot duplicate the launch. Removing a connection does not delete task history.

`read_issue_context` resolves the task-bound source. It returns accepted metadata
and locally cached ticket/comment context, with stale/missing-cache limitations.
It never retrieves a credential or calls Jira. Ticket text and comments are
source material, not instructions. Jira credentials and app secrets never enter
agent prompts, tools or transcript metadata. The renderer handles a personal API
token only while submitting the local settings form; saved credentials are never
returned to it.

## Authentication and hosting

Settings → Integrations → Jira owns account management. The native Jira page
links there rather than maintaining a second connection editor. Add account
opens a native form for the Jira site URL, Atlassian email and personal API token.
This personal setup requires no OAuth app registration or hosted service. Create
a scoped Jira token in [Atlassian account settings](https://id.atlassian.com/manage-profile/security/api-tokens).
The scopes used by the supported reads and writes are `read:jira-user`,
`read:jira-work`, `write:jira-work`, `read:board-scope:jira-software`,
`read:project:jira`, `read:sprint:jira-software`, `write:sprint:jira-software`,
`read:issue-details:jira` and `read:jql:jira`.

The sidecar accepts only a canonical HTTPS `*.atlassian.net` site origin. It
discovers the cloud ID through `/_edge/tenant_info` without credentials, then
verifies the account through `/rest/api/3/myself` using email/token Basic auth at
the fixed `https://api.atlassian.com/ex/jira/{cloudId}` gateway. Site discovery
never receives the token, and credentialed requests never follow redirects.
The encrypted SQLite connection store holds the credential; public connection
data includes its authentication type, login email and verified account/site
identity. No token appears in responses, exception text or stored public state.
The settings form clears its password field on submission and cancellation.

Personal tokens cannot refresh automatically. Replace token verifies the new
credential against the same account and site before changing the saved secret;
a failed replacement leaves the previous connection intact. Authentication
failures offer replacement, while permission failures retain the credential.
Removing a token connection deletes the local credential; users revoke the
token separately in Atlassian settings. Tokens remain subject to organization
policies and administrator visibility. This path is a personal setup, not a
claim of compliance with Atlassian's distributed Cloud-app requirements.

When a distributor configures OAuth, Sign in with browser remains available.
The separate hosted `services/jira-auth` service owns the client secret and
rotating refresh tokens, which cannot safely live in a shipped desktop binary.
ByteQuay stores an encrypted pairing credential locally and requests short-lived
access tokens from this service. OAuth identity comes from Atlassian's User
Identity API; personal-token identity comes from Jira's authenticated account
endpoint. Neither path extracts browser cookies or trusts a user-entered name.

OAuth opens the system browser and shows a pairing code. Settings checks pending
sign-in automatically with bounded, non-overlapping polling; completion or
failure stops the loop. Browser denial and expiry remain visible, and leaving
settings cancels polling without deleting a saved connection. Release packaging
embeds the HTTPS authentication origin from `BYTEQUAY_JIRA_AUTH_URL`; a runtime
override supports local deployments. The backend exposes whether browser sign-in
is configured. A build without an origin still supports personal tokens.
Existing OAuth connections retain their original service origin for reconnect
and removal.

Each connection binds one authenticated account and its authorized site set.
Multiple accounts and multiple sites are supported. The site picker identifies
the account and domain, and remembers valid selections. A reconnect preserves
the connection ID, task links and local preferences; approving a different
account cannot silently replace that identity. Equivalent account/site-set
connections are reported as duplicates within the local installation. They are
not automatically merged or deleted: OAuth grants and rotating credentials stay
separate, including resource-restricted grants. Ticket identity remains the
cloud/issue pair while API writes and cached access remain connection-scoped.

The service uses browser-bound, expiring, one-use state plus a code displayed in
ByteQuay. Authenticated desktop endpoints accept no browser Origin. Refreshes
are serialized and persisted atomically. Tokens are encrypted at rest and all
remote errors are sanitized. Jira calls use the fixed Atlassian gateway origin;
remote redirects are not followed with credentials.

The optional OAuth broker is a separate deployment, not another local sidecar. Its
[setup guide](../../services/jira-auth/README.md) describes app registration,
scopes, HTTPS callback, secret storage, single-instance storage, revocation and
operational limits. Local protocol tests do not prove tenant consent: release
acceptance for browser sign-in still needs a configured OAuth app/domain and an
authorized real Jira Cloud account. Token authentication has no broker dependency.

## Cache, edits and reconciliation

Local reads use persisted cached projections. Background polling updates watched
sprint scopes every 10 minutes, starting one minute after startup. The Jira
page's Refresh tickets button requests an immediate update without waiting for
the next poll. Shared quiet hours in Settings → General pause both Jira's
scheduled poll and automatic open-ticket refresh, using the same saved enabled
flag and time range as GitHub. The range uses the Mac's local time and may cross
midnight. Explicit page opening, manual refresh, edits and development start
remain available. Automatic detail updates reread the board from local cache
without triggering another remote board refresh.

Completed history has a separate cache and uses the enhanced board issues
endpoint with a Done-category filter across sprints. History refreshes on request
and stays out of watched scopes and Needs attention. Jira board filters and mapped-status rules still bound the
result; the board endpoint excludes Scrum epics. Pagination remains bounded and
partial results are identified in the UI.

A completion date is meaningful only while the ticket's current status category
is Done. Prefer Jira's status-category change date, then its resolution date.
Some workflows retain an old resolution when a ticket is reopened, which is why
resolution is the fallback. Never substitute the last-updated timestamp. A
reopened ticket leaves the completed view; this list represents current completed
work, not an event ledger of every completion or a claim of software delivery.
Date buckets are mutually exclusive and use local midnight/Monday boundaries.
Future timestamps and missing dates have explicit groups.

Cache records are namespaced by connection and cloud ID; source identity and
Goal/task association additionally
use immutable cloud/issue IDs. A failed refresh retains the prior result and
reports that it is stale. Page limits and unsupported metadata are visible
limitations rather than an assertion that the result is complete.

Jira workflow names and transition IDs are discovered per ticket. Before a write,
ByteQuay fetches the current issue and compares its update timestamp with the
editor baseline. A mismatch requires refreshing. Only submitted fields are sent.
This detects ordinary stale edits; Jira does not provide a universal atomic
compare-and-swap transaction across those REST calls, so simultaneous external
edits can still race. Jira remains the final permission and validation authority.

Comments persist an operation marker before sending and a confirmed marker after
success. Confirmed retries refresh the result without posting again. Unknown
outcomes stop with an explicit instruction to check the remote conversation;
there is no blind POST retry. A successful write followed by a failed refresh
reports “saved, refresh failed” separately from a failed write.

Jira rich text is rendered into safe Markdown for reading and task context.
Descriptions are not round-tripped through that projection. Attachments and
unsupported rich content remain accessible in Jira; this release does not
implement the full Atlassian document editor.

## Verification

Offline tests cover source admission/retry, cross-workspace uniqueness, persistence
rollback, cache-only agent access, paginated issue reads, stale-edit rejection,
field conversion, comment retry protection, rich-text links, completed-history
pagination and scope, completion-date selection, reopened-ticket membership,
authenticated transport bounds, token validation/replacement, credential
redaction and OAuth pairing/rotation. Frontend tests and a
fixture exercise connection, list/board/detail, comments, Goal and Start/View
task states, completed-history membership, and date grouping across time zones,
daylight saving and week/year boundaries. Repository backend and frontend gates
run from the isolated Jira worktree.

Live acceptance remains a separate step: token authentication or OAuth consent
on an authorized site; board
and sprint selection; edit/transition validation in a test project; comment
round-trip; development start and task reopen; revoked/expired permissions; and
company app-access policies. The implementation must not imply these live checks
have run until they actually have.
