/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
export type HandledAction =
  | 'APPROVED'
  | 'MERGED'
  | 'COMMENTED'
  | 'CHANGES_REQUESTED'
  | 'DISMISSED'
  | 'MANUAL';

/**
 * Why a PR is promoted to "Needs attention". Computed by the backend from
 * the latest detail fetch and stored on the pr row, so cards can render
 * the colored top banner without an additional round-trip.
 *
 * v1 rules — see docs/design/settings-redesign.md §6.5:
 *   CI_FAILING — aggregate CI status is FAILING
 *   MENTIONED  — TODO; needs comment-body parsing on the backend timeline
 *   BLOCKING   — any label contains "block" (covers blocking, priority/blocker, etc.)
 *   STALE      — updatedAt is 7+ days old
 */
export type AttentionReason =
  | 'CI_FAILING'
  | 'MERGE_CONFLICT'
  | 'MENTIONED'
  | 'NEW_COMMENT'
  | 'BLOCKING'
  | 'STALE'
  | 'MINE';

export type PullRequestDto = {
  id: number;
  repo: string;
  number: number;
  title: string;
  author: string | null;
  htmlUrl: string;
  /** When the PR was opened (GitHub created_at). Null on legacy rows that
   *  pre-date V19 — UI falls back to updatedAt for display. */
  createdAt: string | null;
  updatedAt: string;
  origin: 'AUTHORED' | 'REVIEW_REQUESTED';
  labels: string[];
  /** Optional sidecar map of label name → hex color, populated from
   *  GitHub's label.color field. Missing for legacy rows. */
  labelColors: Record<string, string> | null;
  draft: boolean;
  viewedAt: string | null;
  reviewedAt: string | null;
  handledAction: HandledAction | null;
  requestedReviewers: string[];
  // Detail-derived fields, populated by the sync job from the per-PR detail
  // fetch. Nullable when not yet enriched (e.g. brand-new PR seen by the
  // sync job before its detail call lands).
  ciStatus: CiStatus | null;
  additions: number;
  deletions: number;
  commentCount: number;
  attentionReason: AttentionReason | null;
  // Phase 1 kanban-refactor fields (V26 backend migration). All optional so
  // existing rows can flow through before the next sync touches them. See
  // docs/design/kanban-refactor.md.
  /** GitHub PR state. "merged" is synthesized from closed + mergedAt. */
  state: 'open' | 'closed' | 'merged' | string | null;
  closedAt: string | null;
  mergedAt: string | null;
  /** GitHub's mergeable boolean. Null while GitHub is computing it. */
  mergeable: boolean | null;
  /** GitHub's mergeable_state — "clean", "dirty", "blocked", "behind"… */
  mergeableState: string | null;
  /** Latest commit timestamp on the PR head — drives "last push Xd ago". */
  headPushedAt: string | null;
  /** Per-reviewer verdict map (login → APPROVED / CHANGES_REQUESTED /
   *  COMMENTED / DISMISSED). Empty until the PR's detail has been synced
   *  at least once after V26. */
  reviewerVerdicts: Record<string, string> | null;
  /** When this PR is snoozed until (ISO-8601). Null when not snoozed.
   *  The page-header Snoozed tab filters to rows with this set. */
  snoozedUntil: string | null;
  /** Reason an auto-wake fired ("CI_FAILING" / "CHANGES_REQUESTED" /
   *  "MERGE_CONFLICT"). Cleared once the user acknowledges the
   *  green "PR woke up" banner. */
  snoozeWakeReason: string | null;
};

/** One page of historic (closed/merged) PRs returned by /prs/history.
 *  Backed by GitHub search, capped at 1000 total results. */
export type PullRequestHistoryPageDto = {
  items: PullRequestDto[];
  page: number;
  perPage: number;
  totalCount: number;
  hasMore: boolean;
};

export type CiStatus = 'PASSING' | 'FAILING' | 'PENDING' | 'NONE';

export type ChangedFileDto = {
  filename: string;
  additions: number;
  deletions: number;
  status: string;
};

export type DiffFileDto = {
  filename: string;
  status: string;
  additions: number;
  deletions: number;
  patch: string | null;
};

export type PullRequestCommitDto = {
  sha: string;
  authorLogin: string | null;
  authorName: string | null;
  authoredAt: string | null;
  message: string | null;
};

export type ActivityItemDto = {
  actor: string;
  eventType: string;
  timestamp: string | null;
  /** Comment text for "commented" / "reviewed" events (markdown). null for
   *  structural events like "review_requested" / "merged". */
  body: string | null;
  /** Review verdict for "reviewed" events: APPROVED / CHANGES_REQUESTED /
   *  COMMENTED / DISMISSED. null otherwise. */
  state: string | null;
  /** SHA pair for head_ref_force_pushed events; null otherwise. Powers the
   *  "force-pushed · before → after" line in the conversation panel. */
  beforeSha: string | null;
  afterSha: string | null;
  /** Login of the user being invited to review on review_requested events.
   *  The actor is the inviter — this is the invitee. Null on every other
   *  event type. */
  requestedReviewer: string | null;
  /** GitHub review id for `reviewed` events — exact link to the per-line
   *  review comments submitted with this review. Null on every other
   *  event type. */
  reviewId: number | null;
  /** Author's relationship to the repo — same value set as
   *  {@link ReviewMessageDto.authorAssociation}. Null for structural
   *  events that don't carry a comment author. */
  authorAssociation: string | null;
  /** Stable GitHub event id. For {@code commented} events this is the
   *  issue-comment id (used as the reactions-endpoint target). Null on
   *  legacy / id-less timeline rows. */
  githubId: number | null;
  /** Reactions tally for `commented` events. Always present (zero-
   *  filled for non-comment timeline events). */
  reactions: ReactionsDto | null;
};

export type CheckRunDto = {
  /** Stable check-run id from GitHub. Used by /prs/checkLog to fetch
   *  the raw Actions log inline. Null for legacy cached rows. */
  githubId: number | null;
  name: string | null;
  status: string | null;
  conclusion: string | null;
  htmlUrl: string | null;
  /** GitHub's per-check `output.title` — short one-liner like
   *  "5 tests failed". Null when the runner doesn't publish an output. */
  outputTitle: string | null;
  /** GitHub's per-check `output.summary` — markdown blob, often the actual
   *  error excerpt. Surfaced inside the merge bar's failure cards. */
  outputSummary: string | null;
};

export type ReactionsDto = {
  plusOne: number;
  minusOne: number;
  laugh: number;
  hooray: number;
  confused: number;
  heart: number;
  rocket: number;
  eyes: number;
};

export type ReviewMessageDto = {
  githubId: number;
  author: string | null;
  body: string | null;
  createdAt: string | null;
  reactions: ReactionsDto | null;
  /** GitHub review id this message was submitted with. Lets the UI fold
   *  per-line comments under their parent reviewed event. */
  reviewId: number | null;
  /** Author's relationship to the repo — OWNER / COLLABORATOR / MEMBER /
   *  CONTRIBUTOR / FIRST_TIME_CONTRIBUTOR / FIRST_TIMER / MANNEQUIN /
   *  NONE. Powers the role pill rendered next to the author name. */
  authorAssociation: string | null;
};

export type ReviewThreadDto = {
  rootGithubId: number;
  filePath: string | null;
  line: number | null;
  side: string | null;
  diffHunk: string | null;
  messages: ReviewMessageDto[];
  /** True iff GitHub marks this thread resolved. REST doesn't expose
   *  this — null/false until a GraphQL pass populates it. The UI
   *  defaults resolved threads to folded. */
  resolved: boolean | null;
  /** True iff the thread anchors to a line that no longer exists in
   *  the current diff (typically after a force-push). Drives the
   *  "Outdated" badge on the thread header. */
  outdated: boolean;
  /** First line of the multi-line range (V27 / GitHub start_line).
   *  Null for single-line threads (the common case). When set, the
   *  thread renders "Comment on lines L455 to R467" in its header. */
  startLine: number | null;
  /** Side of {@link #startLine}; usually matches {@link #side}. */
  startSide: string | null;
  /** Original line numbers — the file-side coordinates that match
   *  {@link #diffHunk}. After post-comment edits, line / startLine
   *  shift forward but these stay anchored to whatever GitHub
   *  recorded when the comment landed. The DiffHunk slicer prefers
   *  these (V38 backend); falls back to line/startLine on legacy
   *  rows where the new fields are still null. */
  originalLine: number | null;
  originalStartLine: number | null;
};

export type LinkedIssueDto = {
  number: number;
  title: string;
  state: string;
  htmlUrl: string;
};

export type PullRequestDetailDto = {
  repo: string;
  number: number;
  body: string | null;
  labels: string[];
  draft: boolean;
  mergeable: boolean | null;
  mergeableState: string | null;
  additions: number;
  deletions: number;
  changedFiles: number;
  approvalCount: number;
  changesRequestedCount: number;
  pendingReviewerCount: number;
  /** Logins of reviewers GitHub still considers pending — same source
   *  as pendingReviewerCount but as a list, so the reviewer sidebar
   *  can render one row per login. Always present (empty array when
   *  no reviewer is pending). */
  requestedReviewers: string[];
  ciStatus: CiStatus;
  files: ChangedFileDto[];
  recentActivity: ActivityItemDto[];
  checkRuns: CheckRunDto[];
  reviewThreads: ReviewThreadDto[];
  linkedIssues: LinkedIssueDto[];
  /** True iff the authenticated PAT has push (write) access to the PR's
   *  repository — used to gate the merge button on the detail page so we
   *  don't surface a control GitHub will reject. */
  viewerCanWrite: boolean;
  /** Branch name on the head side (e.g. "feat/foo"). Null on legacy rows. */
  headRef: string | null;
  /** "owner/repo" of the head side; differs from baseRepo on fork PRs. */
  headRepo: string | null;
  /** Target branch (almost always the default branch). */
  baseRef: string | null;
  /** "owner/repo" of the target side; same as the PR's repo for in-repo PRs. */
  baseRepo: string | null;
};

/** Lightweight CI-only slice served by /prs/ci. Polled while the detail
 *  page is open and the window is focused so a CI flip and the merge
 *  button's enable/disable refresh without re-running the full detail
 *  orchestration. */
export type PrCiSnapshotDto = {
  ciStatus: CiStatus;
  checkRuns: CheckRunDto[];
  viewerCanWrite: boolean;
};

export type SyncSettingsDto = {
  intervalSeconds: number;
};

export type WatchedRepoDto = {
  id: number;
  owner: string;
  repo: string;
  displayOrder: number;
};

/** Repo-level metadata served by /api/repos/{owner}/{repo}/meta.
 *  Drives the right-pane hero / About / language bar on the repo PR
 *  detail page. */
export type RepoMetaDto = {
  fullName: string;
  htmlUrl: string;
  description: string | null;
  defaultBranch: string | null;
  license: string | null;
  stargazersCount: number;
  forksCount: number;
  watchersCount: number;
  openIssuesCount: number;
  sizeKb: number;
  createdAt: string | null;
  pushedAt: string | null;
  topics: string[];
  /** Map from language → byte count. The language bar computes
   *  percentages client-side. */
  languages: Record<string, number>;
  /** GitHub's owner.avatar_url. Null on legacy rows persisted before
   *  the column existed; the avatar component falls back to a
   *  colour-and-letter placeholder. */
  ownerAvatarUrl: string | null;
  /** GitHub's parent.owner.login. Non-null when this repo is a fork;
   *  null otherwise. Drives the fork → upstream view-focus dropdown
   *  on the repo detail page. */
  parentOwner: string | null;
  /** GitHub's parent.name. Pairs with parentOwner. */
  parentName: string | null;
  /** GitHub's parent.default_branch — the upstream's default branch.
   *  Used as the ref the commits tab queries when the toggle is in
   *  upstream view. */
  parentDefaultBranch: string | null;
};

/** One entry in the right-pane "Recent activity" feed. */
export type RepoActivityItemDto = {
  type: string;
  actor: string | null;
  title: string;
  htmlUrl: string;
  createdAt: string | null;
};

/** Eight named colors that match the Settings → Teams swatch palette
 *  (see docs/mockups/teams/bytequay-team-modal-redesign.html). The
 *  three legacy values (purple / green / orange) stay first so existing
 *  team rows from before the palette expansion keep rendering. */
export type TeamColor =
  | 'purple'
  | 'green'
  | 'orange'
  | 'blue'
  | 'cyan'
  | 'amber'
  | 'red'
  | 'pink'
  | 'slate';

export type TeamSummaryDto = {
  id: number;
  name: string;
  avatar: string;
  color: TeamColor;
  /** One-line description shown under the team name in the sidebar
   *  card. Null when the user didn't supply one. */
  description: string | null;
  memberCount: number;
  inboxCount: number;
};

export type TeamDto = {
  id: number;
  name: string;
  avatar: string;
  color: TeamColor;
  description: string | null;
  members: string[];
  createdAt: string;
  updatedAt: string;
};

export type CreateTeamRequest = {
  name: string;
  avatar: string;
  color: TeamColor;
  description: string | null;
  members: string[];
};

export type UpdateTeamRequest = {
  name: string;
  avatar: string;
  color: TeamColor;
  description: string | null;
};

export type UserOrgDto = {
  login: string;
  avatarUrl: string;
  htmlUrl: string;
  description: string | null;
};

export type UserProfileDto = {
  login: string;
  name: string | null;
  avatarUrl: string;
  htmlUrl: string;
  publicRepos: number;
  followers: number;
  following: number;
  bio: string | null;
  location: string | null;
  /** Free-form GitHub "Company" field — null when unset. */
  company: string | null;
  /** Public email — null when the user has hidden it. */
  email: string | null;
  /** True iff the user has set up a GitHub Sponsors listing (GraphQL-sourced). */
  hasSponsors: boolean;
};

/** One day in the rolling-12-months contribution heatmap. */
export type ContributionDayDto = {
  /** ISO yyyy-MM-dd. */
  date: string;
  contributionCount: number;
  /** GitHub-supplied palette hex (e.g. #ebedf0 .. #216e39). */
  color: string;
};

/** One column (week) in the contribution heatmap. */
export type ContributionWeekDto = {
  days: ContributionDayDto[];
};

export type ContributionCalendarDto = {
  totalContributions: number;
  weeks: ContributionWeekDto[];
};

export type IssueDto = {
  id: number;
  number: number;
  title: string;
  author: string | null;
  /** GitHub state — "open" or "closed". Drives the row's status icon
   *  and lets the Issues tab split into Open / Closed buckets without
   *  a re-fetch round-trip. */
  state: string;
  htmlUrl: string;
  updatedAt: string;
  labels: string[];
};

export type IssueLabelDto = { name: string; color: string };
export type IssueAssigneeDto = { login: string; avatarUrl: string | null };
export type IssueMilestoneDto = { title: string; state: string };
export type IssueCommentDto = {
  id: number;
  author: string | null;
  authorAvatarUrl: string | null;
  body: string;
  createdAt: string;
  /** Aggregated reaction counts. Always present — empty rows arrive
   *  with zeros, never null. */
  reactions: ReactionsDto;
};

export type IssueDetailDto = {
  id: number;
  number: number;
  title: string;
  body: string | null;
  author: string | null;
  authorAvatarUrl: string | null;
  state: string;
  htmlUrl: string;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  labels: IssueLabelDto[];
  assignees: IssueAssigneeDto[];
  milestone: IssueMilestoneDto | null;
  comments: IssueCommentDto[];
};

export type UserRepoDto = {
  owner: string;
  name: string;
  fullName: string;
  description: string | null;
  language: string | null;
  stars: number;
};

/** One row of a GitHub user-search response. Powers the team-editor
 *  member autocomplete so logins are picked from real users instead of
 *  hand-typed (and risk a typo). */
export type GitHubUserMatchDto = {
  login: string;
  avatarUrl: string | null;
  name: string | null;
};

/** One reviewer GitHub recommends for a PR — derived from blame on the
 *  touched files and the actor's review history. Surfaced as one-click
 *  chips above the typeahead in the Add-reviewer UI. Source: GraphQL
 *  pullRequest.suggestedReviewers (REST has no equivalent). */
export type SuggestedReviewerDto = {
  login: string;
  avatarUrl: string | null;
  name: string | null;
  isAuthor: boolean;
  isCommenter: boolean;
};

/** Slack workspace connection status returned by GET /api/slack/connection.
 *  When {@code connected} is false, every other field is absent. */
export type SlackConnectionDto = {
  connected: boolean;
  teamId?: string;
  teamName?: string;
  authedUserId?: string;
};

/** Inbox view DTOs (slice 5). Backed by /api/slack/inbox + /thread. */
export type SlackInboxFilter = 'all' | 'mentions' | 'dms';

export type SlackInboxItemState = 'unread' | 'expanded' | 'responded' | 'bumped';
export type SlackInboxKind = 'mention' | 'dm' | 'channel';

export type SlackInboxItemDto = {
  channelId: string;
  ts: string;
  state: SlackInboxItemState;
  /** ISO-8601 instants; null when the row hasn't reached that transition yet. */
  archivedAt: string | null;
  bumpedAt: string | null;
  respondedAt: string | null;
  expandedAt: string | null;
  userId: string | null;
  text: string | null;
  threadTs: string | null;
  hasAtYou: boolean;
  /** 'channel' is unreachable in the inbox (filtered upstream) but kept on the wire to mirror the DB column. */
  inboxKind: SlackInboxKind;
  /** Drives the BUMPED "N NEW" pill. Always 0 for UNREAD/EXPANDED rows. */
  newReplyCount: number;
};

export type SlackInboxThreadMessageDto = {
  ts: string;
  userId: string | null;
  text: string | null;
  hasAtYou: boolean;
};

export type SlackInboxThreadDto = {
  channelId: string;
  threadTs: string;
  messages: SlackInboxThreadMessageDto[];
};

/** Channel-feed and DM-view payload (slice 6). One row per cached
 *  message in the channel; the renderer groups by threadTs to fold
 *  thread replies under their parent. */
export type SlackFeedMessageDto = {
  ts: string;
  userId: string | null;
  text: string | null;
  threadTs: string | null;
  hasAtYou: boolean;
};

export type SlackChannelFeedDto = {
  channelId: string;
  messages: SlackFeedMessageDto[];
};

/** One row of the channel-picker payload (slice 3). Mirrors
 *  com.bytequay.app.service.slack.SlackChannelService.ChannelRow. */
export type SlackChannelRowDto = {
  channel: {
    id: string;
    name: string;
    isPrivate: boolean;
    /** Slack omits this on the rare row that's missing num_members. */
    memberCount: number | null;
    /** ISO-8601 instant; null when Slack supplies neither latest.ts nor updated. */
    latestActivityAt: string | null;
  };
  /** True when the user has saved this channel as followed. */
  isFollowed: boolean;
  /** True only on first-run (no rows in followed_channels yet) for the top
   *  three by latestActivityAt. The picker pre-toggles these and shows a
   *  "SMART DEFAULT" badge. Always false after the first save. */
  isSmartDefault: boolean;
};

/** Mirror of backend EmailMessageDetail. Includes the parsed body
 *  (text and/or HTML) plus full headers — used inside an
 *  EmailThreadDetailDto. Either bodyText or bodyHtml may be null; the
 *  renderer prefers HTML when both are present. */
export type EmailMessageDetailDto = {
  id: string;
  threadId: string;
  from: string;
  to: string;
  cc: string;
  subject: string;
  receivedAt: string;
  unread: boolean;
  labels: string[];
  bodyText: string | null;
  bodyHtml: string | null;
};

/** Mirror of backend EmailThreadMeta. The unit shown as one card in
 *  the inbox list — represents a Gmail conversation, with the latest
 *  message's sender/subject/snippet plus a count for the (N) badge
 *  on multi-message threads. */
export type EmailThreadMetaDto = {
  id: string;
  latestMessageId: string | null;
  from: string;
  subject: string;
  snippet: string;
  receivedAt: string;
  unread: boolean;
  messageCount: number;
};

/** Mirror of backend LinkedRef — a PR / issue / commit auto-detected
 *  inside an email body. {@code slug} is the displayable identifier:
 *  the number for PR/issue, the abbreviated SHA for commit. */
export type LinkedRefDto = {
  kind: 'PR' | 'ISSUE' | 'COMMIT';
  owner: string;
  repo: string;
  slug: string;
  url: string;
};

/** Mirror of backend EmailThreadDetail. Every message in the thread,
 *  oldest-first; the renderer stacks them in the preview pane.
 *  {@code linkedRefs} are PR/issue refs auto-detected inside the
 *  bodies — shown above the message stack as a context panel. */
export type EmailThreadDetailDto = {
  id: string;
  subject: string;
  messages: EmailMessageDetailDto[];
  linkedRefs: LinkedRefDto[];
};

/** Mirror of backend MyPrColumn enum slugs. The team kanban now
 *  categorizes server-side and paginates per column, so these slugs
 *  cross the wire as both query params and response keys. */
export type MyPrColumnSlug =
  | 'drafting'
  | 'waiting_on_review'
  | 'needs_changes'
  | 'ready_to_merge'
  | 'recently_merged'
  | 'handled';

/** First-paint payload for the team kanban: first N PRs per column +
 *  total count per column so each column header / "+ N more" hint can
 *  render without a second round-trip. */
export type TeamColumnsResponse = {
  columns: Record<MyPrColumnSlug, PullRequestDto[]>;
  totals: Record<MyPrColumnSlug, number>;
  /** PR counts keyed by full {@code owner/repo}. Drives the per-repo
   *  chip row in the team-detail header. */
  repoTotals: Record<string, number>;
};

/** One page (offset + limit) of one column. */
export type ColumnPageDto = {
  column: MyPrColumnSlug;
  total: number;
  offset: number;
  items: PullRequestDto[];
};

export type RecentEventDto = {
  type: string;
  repo: string;
  createdAt: string;
  commitCount: number;
  action: string | null;
  prTitle: string | null;
  prNumber: number;
  refType: string | null;
  actorLogin: string | null;
};

export type StatPeriods = {
  today: number;
  /** [todayStart-1d, todayStart) — powers day-over-day delta on the home page. */
  yesterday: number;
  thisWeek: number;
  thisMonth: number;
  /** [weekStart-7d, weekStart) — powers week-over-week trend deltas. */
  previousWeek: number;
};

export type UserStatsDto = {
  commits: StatPeriods;
  pushes: StatPeriods;
  prsCreated: StatPeriods;
  prsReviewed: StatPeriods;
  comments: StatPeriods;
  prsViewed: StatPeriods;
  prsMarkedReviewed: StatPeriods;
  updatedAt: string;
};

// Coarse category for a stored credential. The pair (type, name) uniquely
// identifies a row in the backend `credentials` table. Conventions:
//   ACCOUNT     — singleton; name is always "github"
//   REPO        — name is the repo full slug "owner/repo"
//   AI          — name is the provider id ("anthropic", "openai", "local", ...)
//   INTEGRATION — name is the integration id ("slack-oauth-app", etc.)
export type CredentialType = 'ACCOUNT' | 'REPO' | 'AI' | 'INTEGRATION';

export type CredentialDto = {
  id: number;
  type: CredentialType;
  name: string;
  /** Sub-name within (type, name). Defaults to "default api" — lets the
   *  user keep multiple keys for the same provider, e.g. two DeepSeek
   *  keys, "personal" and "work". */
  instanceName: string;
  label: string | null;
  preview: string;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string | null;
};

export type UpsertCredentialRequest = {
  type: CredentialType;
  name: string;
  /** Defaults to "default api" on the backend if omitted. */
  instanceName?: string | null;
  value: string;
  label?: string | null;
  notes?: string | null;
};

// Display metadata for the canned credential templates the editor offers.
// These live frontend-side because the backend doesn't dictate UI labels
// — adding a new well-known credential just requires extending this list.
export type CredentialTemplate = {
  type: CredentialType;
  name: string;
  displayName: string;
  usageDescription: string;
};

export const CREDENTIAL_TEMPLATES: CredentialTemplate[] = [
  {
    type: 'ACCOUNT',
    name: 'github',
    displayName: 'GitHub PAT',
    usageDescription: 'Authenticates calls for user profile, repo, org, and other GitHub reads. Singleton — only one allowed.',
  },
  {
    type: 'AI',
    name: 'anthropic',
    displayName: 'Anthropic API key',
    usageDescription: 'Authenticates calls to the Claude API for AI-drafted PR reviews.',
  },
  {
    type: 'AI',
    name: 'openai',
    displayName: 'OpenAI API key',
    usageDescription: 'Authenticates calls to the OpenAI API for AI-drafted PR reviews.',
  },
  {
    type: 'AI',
    name: 'deepseek',
    displayName: 'DeepSeek API key',
    usageDescription: 'Authenticates calls to the DeepSeek chat completions API. Default model is deepseek-chat; deepseek-reasoner is also available.',
  },
  {
    type: 'AI',
    name: 'local',
    displayName: 'Local LLM endpoint',
    usageDescription: 'Base URL for an OpenAI-compatible local model server (e.g. Ollama, LM Studio).',
  },
];

export type AiProviderInfo = {
  providerId: string;
  displayName: string;
  configured: boolean;
  active: boolean;
};

export type AiReviewCommentDto = {
  id: number;
  filePath: string;
  lineNumber: number;
  body: string;
  /** User-edited replacement for {@link body}. When non-null, this is what
   *  publish sends to GitHub; the original {@link body} stays as the
   *  "before" reference. */
  editedBody: string | null;
  severity: 'info' | 'suggestion' | 'warning' | 'blocker' | string;
  /** Soft-deleted: dimmed in the UI and excluded from the publish payload.
   *  Restorable via the Restore button on the inline card. */
  dismissed: boolean;
  /** Origin of the comment — AI-generated finding vs. user-staged inline. */
  source: 'AI' | 'HUMAN';
  /** Diff side: LEFT (deleted) or RIGHT (added). AI comments are RIGHT. */
  side: 'LEFT' | 'RIGHT';
  /** First line of a multi-line range, or null for single-line. */
  startLine: number | null;
  /** Diff side of {@link startLine}, or null for single-line. */
  startSide: 'LEFT' | 'RIGHT' | null;
};

export type AiReviewDraftDto = {
  id: number;
  prId: number;
  summary: string | null;
  providerId: string;
  model: string;
  headSha: string | null;
  status: string;
  createdAt: string;
  updatedAt: string;
  comments: AiReviewCommentDto[];
};

export type AiSettingsDto = {
  provider: string;
  model: string;
};

/** Home-page daily card. Exactly one per day, picked deterministically
 *  by the backend from a curated pool. {@link author} / {@link role} are
 *  populated for {@code quote} cards and null for the other types. */
export type DailyCardDto = {
  type: 'quote' | 'review_tip' | 'open_source_tip' | 'tiny_challenge' | 'joke' | string;
  text: string;
  author: string | null;
  role: string | null;
  date: string;
};

/** A per-repo "skill" — additional system-prompt context the AI reviewer
 *  applies when running against a matching repo. {@link llmProvider} may
 *  be null (skill applies to every provider) or a provider id to lock
 *  the run to a specific reviewer. */
export type ReviewSkillDto = {
  id: number;
  skillName: string;
  repo: string;
  llmProvider: string | null;
  description: string | null;
  context: string | null;
  createdAt: string;
  updatedAt: string;
};

/** One row of the Repos page. Mirrors the backend
 *  {@code LocalRepoStatus} record. */
export type LocalRepoStatusDto = {
  owner: string;
  repo: string;
  /** Filesystem path of the user's working copy, or null if unmapped. */
  localClonePath: string | null;
  state: 'UNMAPPED' | 'CLEAN' | 'MODIFIED' | 'MISSING' | 'GIT_UNAVAILABLE' | 'ERROR';
  /** Currently checked-out branch; null on unmapped or detached HEAD. */
  currentBranch: string | null;
  /** Modified-file count from `git status --porcelain`. Null if unmapped. */
  dirtyFileCount: number | null;
  /** Surface for the ERROR / MISSING / GIT_UNAVAILABLE states. */
  errorMessage: string | null;
  /** Name of the git remote that points at the watched repo when the
   *  user is using a fork-based workflow. Null for direct clones
   *  (origin == watched repo) and for unmapped repos. */
  upstreamRemoteName: string | null;
  /** Repo's default branch as the local clone sees it (read from
   *  origin/HEAD). Drives the Base field's default in Create-PR so
   *  forks of repos that default to `master` (Trino, etc.) don't
   *  surprise the user with `main`. Null when origin/HEAD isn't set. */
  defaultBranch: string | null;
  /** Resolved focus for the repo detail page's commits tab. The
   *  backend defaults to "upstream" when this row has an
   *  upstreamRemoteName and the user hasn't toggled yet, else "fork". */
  viewFocus: 'fork' | 'upstream';
};

/** One row of the branches kanban on the repo detail page. */
export type LocalBranchDto = {
  name: string;
  isCurrent: boolean;
  /** ISO timestamp of the branch tip's commit, or null when git
   *  couldn't parse it (corrupt ref / shallow clone edge case). */
  lastCommitAt: string | null;
  hasUpstream: boolean;
  ahead: number | null;
  behind: number | null;
  /** Open PR whose head ref equals this branch — null until the PR
   *  list-page sync starts capturing head refs. Branches with a
   *  non-null value land in the IN REVIEW column. */
  linkedPrNumber: number | null;
  /** Non-null when the branch is a cleanup candidate — drives
   *  placement into CLEAN UP and authorizes server-side delete. */
  cleanupReason: 'REMOTE_GONE' | 'IDLE_NEVER_PUSHED' | null;
  /** Commits unique to this branch vs the repo's default base — the
   *  size of the work that lives on the branch. Null for the default
   *  branch itself, when origin/HEAD isn't set, or when rev-list
   *  failed. */
  commitCount: number | null;
  /** Outcome of a virtual merge of this branch onto its rebase target
   *  (upstream when behind, default branch otherwise). Null when no
   *  rebase is meaningful for this branch's state. */
  rebasePreview: 'CLEAN' | 'CONFLICTS' | 'UNKNOWN' | null;
  /** True for synthesized IN_REVIEW entries that mirror an open PR
   *  whose head branch isn't checked out in this clone (typically a
   *  branch the user pushed from another machine). The card surfaces
   *  a Check-out CTA instead of the usual action set. */
  remoteOnly: boolean;
};

export type LocalCommitDto = {
  /** Full 40-char SHA — used as React key and for any future
   *  drill-in lookups. */
  sha: string;
  /** Abbreviated SHA (typically 7 chars) for display. */
  shortSha: string;
  /** Commit subject (first line of the message). */
  subject: string;
  authorName: string;
  authorEmail: string;
  /** ISO 8601 strict authored timestamp, or null when git couldn't
   *  parse it. Author (not committer) so rebases/amends preserve
   *  the time the user thinks of as "when I wrote this." */
  authoredAt: string | null;
};

/** One file touched by a commit — middle pane of the Commits tab.
 *  Status mirrors git's --name-status short codes (A/M/D/R/C/T).
 *  additions/deletions are -1 for binary files. */
export type LocalCommitFileDto = {
  path: string;
  status: string;
  additions: number;
  deletions: number;
};

/** Per-file diff at a commit — drives the right pane of the Commits
 *  tab. truncated is true when the patch was capped by the backend. */
export type LocalFileDiffDto = {
  path: string;
  patch: string;
  truncated: boolean;
};

/** Subject + body of a single commit. Lazy-fetched when a commit is
 *  selected in the Commits tab so the listCommits payload stays
 *  small even on branches with long release-note style commits. */
export type LocalCommitDetailDto = {
  sha: string;
  subject: string;
  body: string;
};

/** Branch-point info for the Commits tab: sha = the merge-base of
 *  the active branch and base; base = the resolved base name (after
 *  origin/ fallback) for display. Both are null when there's no
 *  common ancestor or no default branch. */
export type LocalMergeBaseDto = {
  sha: string | null;
  base: string | null;
};

export type LocalActivityEntryDto = {
  sha: string;
  shortSha: string;
  /** `HEAD@{0}` etc. — the relative selector git uses for this
   *  reflog entry. */
  selector: string;
  /** Coarse classification driving the icon and label. */
  kind:
    | 'COMMIT' | 'CHECKOUT' | 'MERGE' | 'PULL' | 'PUSH'
    | 'REBASE' | 'RESET' | 'BRANCH' | 'UNKNOWN';
  /** Full reflog subject — has the descriptive tail after the
   *  classifier prefix (e.g. "checkout: moving from main to feat/foo"). */
  subject: string;
  /** Author timestamp of the commit this entry points at. */
  at: string | null;
};

export type Bridge = {
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  clearPat: () => Promise<boolean>;
  fetchHello: () => Promise<string>;
  fetchPrs: () => Promise<PullRequestDto[]>;
  /** Live GitHub search for the user's full closed-PR history (merged
   *  + closed-without-merge). Used by the merge-history page — pages
   *  through GitHub's `is:closed author:@me sort:closed-desc` results. */
  fetchPrHistory: (page: number, perPage?: number) => Promise<PullRequestHistoryPageDto>;
  fetchPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  /** Force-refresh one PR's detail. Drops the backend's cached snapshot
   *  and re-fetches live from GitHub. Wired to the manual ↻ refresh
   *  button on the PR detail page. */
  refreshPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  /** Lightweight CI snapshot for the focus-driven detail-page poll. */
  fetchPrCi: (repo: string, number: number) => Promise<PrCiSnapshotDto>;
  /** Raw Actions log text for one check-run. Empty string when GitHub
   *  doesn't expose a log (external CI / expired / scope). Lazy-loaded
   *  by the merge bar's failure cards on user click. */
  fetchCheckLog: (repo: string, checkRunId: number) => Promise<{ log: string }>;
  /** Toggle a PR between draft and ready-for-review. true = convert
   *  to draft, false = mark as ready. Routes through GitHub GraphQL. */
  setPrDraft: (repo: string, number: number, draft: boolean) => Promise<{ result: string }>;
  fetchPrDiffFiles: (repo: string, number: number) => Promise<DiffFileDto[]>;
  fetchPrCommits: (repo: string, number: number) => Promise<PullRequestCommitDto[]>;
  /** Diff scoped to a single commit (DiffFileDto[] same as fetchPrDiffFiles). */
  fetchPrCommitDiff: (repo: string, number: number, sha: string) => Promise<DiffFileDto[]>;
  /** Returns a file's full content at a ref, as a list of lines. Powers the
   *  "expand collapsed code" buttons in the diff viewer. */
  fetchFileBlob: (repo: string, path: string, sha: string) => Promise<{ lines: string[] }>;
  getSyncSettings: () => Promise<SyncSettingsDto>;
  setSyncSettings: (settings: SyncSettingsDto) => Promise<SyncSettingsDto>;
  triggerSync: () => Promise<void>;
  markPrViewed: (prId: number) => Promise<void>;
  markPrHandled: (prId: number, action: HandledAction) => Promise<void>;
  reopenPr: (prId: number) => Promise<void>;
  /** Park the PR until the given ISO-8601 instant. Replaces any
   *  existing snooze and clears any pending wake reason. */
  snoozePr: (prId: number, untilIso: string) => Promise<void>;
  /** Wake a snoozed PR ("Wake now" — no auto-wake reason recorded). */
  unsnoozePr: (prId: number) => Promise<void>;
  /** Acknowledge the just-woke alert and clear the stored reason. */
  clearSnoozeWakeReason: (prId: number) => Promise<void>;
  approvePr: (prId: number, repo: string, number: number) => Promise<void>;
  /** Merge with the given strategy. Omitting {@code strategy} keeps the
   *  historical "rebase" default for compatibility. */
  mergePr: (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge') => Promise<{ merged: boolean; message: string }>;
  commentPr: (prId: number, repo: string, number: number, body: string, close: boolean) => Promise<void>;
  /** Adds a single user to the PR's requested reviewers. */
  addRequestedReviewer: (repo: string, number: number, reviewer: string) => Promise<void>;
  /** Removes a single user from the PR's requested reviewers. */
  removeRequestedReviewer: (repo: string, number: number, reviewer: string) => Promise<void>;
  /** GitHub's suggested reviewers for one PR (GraphQL-only). Drives
   *  the one-click chips above the Add-reviewer typeahead. Returns []
   *  on failure — non-essential affordance, never throws. */
  getSuggestedReviewers: (repo: string, number: number) => Promise<SuggestedReviewerDto[]>;
  /** Replies inline to an existing per-line review thread on the PR. */
  replyToReviewThread: (repo: string, number: number, rootCommentId: number, body: string) => Promise<void>;
  /** Edits a top-level issue / PR comment authored by the user.
   *  Backend rejects with 403 for comments authored by someone else. */
  editIssueComment: (repo: string, commentId: number, body: string) => Promise<void>;
  /** Edits a per-line review comment authored by the user. */
  editReviewComment: (repo: string, commentId: number, body: string) => Promise<void>;
  /** Posts a brand-new per-line review comment on a specific diff line.
   *  {@code commitId} should be the PR head SHA. {@code side} is "LEFT"
   *  for the old file, "RIGHT" for the new file. */
  createInlineReviewComment: (
    repo: string,
    number: number,
    body: string,
    path: string,
    line: number,
    side: 'LEFT' | 'RIGHT',
    commitId: string,
    /** Optional first line of a multi-line range. null/omitted for the
     *  single-line case. When set, GitHub creates the comment spanning
     *  startLine through line on the matching side. */
    startLine?: number | null,
    startSide?: 'LEFT' | 'RIGHT' | null,
  ) => Promise<void>;
  updatePrBody: (repo: string, number: number, body: string) => Promise<void>;
  // Phase 2
  getWatchedRepos: () => Promise<WatchedRepoDto[]>;
  addWatchedRepo: (owner: string, repo: string) => Promise<WatchedRepoDto>;
  removeWatchedRepo: (owner: string, repo: string) => Promise<void>;
  getUserProfile: () => Promise<UserProfileDto>;
  /** Last-12-months contribution heatmap for the home-page card. */
  getContributionCalendar: (login: string) => Promise<ContributionCalendarDto>;
  getRepoPulls: (owner: string, repo: string) => Promise<PullRequestDto[]>;
  /** Single-PR fetch — used by the deep-link fallback when a PR isn't in
   *  the (capped) repo list response. */
  getRepoPull: (owner: string, repo: string, number: number) => Promise<PullRequestDto>;
  getRepoIssues: (owner: string, repo: string, state?: 'open' | 'closed') => Promise<IssueDto[]>;
  getIssueDetail: (owner: string, repo: string, number: number) => Promise<IssueDetailDto>;
  createIssueComment: (owner: string, repo: string, number: number, body: string) => Promise<IssueCommentDto>;
  setIssueState: (owner: string, repo: string, number: number, state: 'open' | 'closed') => Promise<IssueDetailDto>;
  /** Adds an emoji reaction to a comment on the in-app Issue detail
   *  page. Disambiguated from the PR-side {@link addIssueCommentReaction}
   *  by routing through the {@code /api/repos/.../issues/comments/...}
   *  endpoint (the PR side uses {@code /api/prs/issue-comments/...}).
   *  {@code content} is one of the eight allowlisted GitHub strings. */
  addIssueDetailCommentReaction: (owner: string, repo: string, commentId: number, content: string) => Promise<{ result: string }>;
  /** Repo-level metadata for the right-pane hero card. */
  getRepoMeta: (owner: string, repo: string) => Promise<RepoMetaDto>;
  /** ~30 most recent events on a repo for the right-pane activity feed. */
  getRepoActivity: (owner: string, repo: string) => Promise<RepoActivityItemDto[]>;
  /** All watched repos plus their local-clone state (CLEAN / MODIFIED /
   *  UNMAPPED / …). Drives the Repos page. */
  listLocalRepos: () => Promise<LocalRepoStatusDto[]>;
  /** Set or clear the local-clone path for a watched repo. Pass null
   *  to unmap. Triggered by the Repos page's clone / locate flows. */
  setLocalClonePath: (owner: string, repo: string, path: string | null) => Promise<void>;
  /** Persists the user's choice of fork-vs-upstream focus for the
   *  repo detail page's commits tab. Returns the refreshed status row
   *  so the caller can update local state without a list refetch. */
  setViewFocus: (
    owner: string,
    repo: string,
    viewFocus: 'fork' | 'upstream',
  ) => Promise<LocalRepoStatusDto>;
  /** Native folder picker. Returns the selected absolute path, or null
   *  when the user cancels. Used by the Locate-existing flow and the
   *  Change-destination button on Clone-fresh. */
  pickFolder: (options?: { defaultPath?: string; title?: string }) => Promise<string | null>;
  /** Server-side suggested clone destination
   *  ({@code ~/Library/Application Support/ByteQuay/repos/{owner}/{repo}}).
   *  Pre-fills the Add-repo modal's destination field. */
  defaultClonePath: (owner: string, repo: string) => Promise<string>;
  /** Runs `git clone` and records the path. Returns the refreshed
   *  status row so the Repos page can update without a list refetch. */
  cloneRepo: (owner: string, repo: string, destination: string) => Promise<LocalRepoStatusDto>;
  /** Verifies the path is a git working tree whose origin matches the
   *  watched repo, then records it. Errors carry the backend's
   *  {@code message} field verbatim ("wrong remote: …"). */
  locateRepo: (owner: string, repo: string, path: string) => Promise<LocalRepoStatusDto>;
  /** Local branches for the repo detail page's kanban. Each entry has
   *  enough metadata to decide column placement and render the inline
   *  ahead/behind + last-commit chips. */
  listLocalBranches: (owner: string, repo: string) => Promise<LocalBranchDto[]>;
  /** Recent commits on `revision` (default HEAD). `limit` is server-
   *  capped at 500. Powers the Commits tab. */
  listLocalCommits: (
    owner: string, repo: string, revision?: string, limit?: number,
  ) => Promise<LocalCommitDto[]>;
  /** Subject + body of a single commit — feeds the patch-detail card
   *  at the top of the Commits tab's middle pane. */
  getLocalCommitDetail: (
    owner: string, repo: string, sha: string,
  ) => Promise<LocalCommitDetailDto>;
  /** Working-tree files (uncommitted: staged + unstaged + untracked).
   *  Powers the Commits tab's "Changes" mode. Returns the same shape
   *  as listLocalCommitFiles so the file-tree pane renders both
   *  uniformly. */
  listLocalWorkingTreeFiles: (
    owner: string, repo: string,
  ) => Promise<LocalCommitFileDto[]>;
  /** Working-tree diff for one file (git diff HEAD -- path, with an
   *  untracked-file fallback). Powers the Commits tab's "Changes"
   *  mode right pane. */
  getLocalWorkingTreeDiff: (
    owner: string, repo: string, path: string,
  ) => Promise<LocalFileDiffDto>;
  /** Files differing between two refs — used by the Commits tab's
   *  compare-branches mode. Both refs may be branch names or shas;
   *  origin/<name> fallback is applied per the listCommits flow. */
  listLocalRangeFiles: (
    owner: string, repo: string, base: string, head: string,
  ) => Promise<LocalCommitFileDto[]>;
  /** Per-file unified diff between two refs — counterpart to
   *  listLocalRangeFiles. Differs from getLocalCommitRangeDiff in
   *  that there's no ^ shift on the base, so branch refs work. */
  getLocalRangeDiff: (
    owner: string, repo: string, base: string, head: string, path: string,
  ) => Promise<LocalFileDiffDto>;
  /** Files touched by a single commit — middle pane of the Commits tab. */
  listLocalCommitFiles: (
    owner: string, repo: string, sha: string,
  ) => Promise<LocalCommitFileDto[]>;
  /** Per-file unified diff at a commit — right pane of the Commits tab. */
  getLocalCommitDiff: (
    owner: string, repo: string, sha: string, path: string,
  ) => Promise<LocalFileDiffDto>;
  /** Per-file unified diff across a commit range (oldest^..newest).
   *  Used when the Commits tab has more than one commit selected so
   *  the user sees the combined patch instead of just the latest
   *  selected commit's changes. */
  getLocalCommitRangeDiff: (
    owner: string, repo: string, oldestSha: string, newestSha: string, path: string,
  ) => Promise<LocalFileDiffDto>;
  /** Merge-base sha of branch and base (default base = origin/HEAD).
   *  Used to render a "branched from <base>" divider in the Commits
   *  tab's commit list. */
  getLocalMergeBase: (
    owner: string, repo: string, branch: string, base?: string,
  ) => Promise<LocalMergeBaseDto>;
  /** Recent reflog entries — HEAD-mutating events (commits, checkouts,
   *  merges, pulls, rebases). Powers the Activity tab. */
  listLocalActivity: (
    owner: string, repo: string, limit?: number,
  ) => Promise<LocalActivityEntryDto[]>;
  /** `git fetch --all --prune` against the watched repo's clone.
   *  Returns the refreshed status row. */
  fetchLocalRepo: (owner: string, repo: string) => Promise<LocalRepoStatusDto>;
  /** Fast-forward-only pull on the current branch. Diverged histories
   *  surface as a thrown Error carrying git's stderr — the UI shows
   *  it inline (e.g. "needs rebase"). */
  pullLocalRepo: (owner: string, repo: string) => Promise<LocalRepoStatusDto>;
  /** Pushes the current branch. First-time pushes auto-set tracking
   *  via `-u origin HEAD`. Non-fast-forward errors carry git's
   *  stderr — surfaced inline so the caller can decide whether to
   *  force-with-lease. */
  pushLocalRepo: (owner: string, repo: string) => Promise<LocalRepoStatusDto>;
  /** `git push --force-with-lease`. Backend rejects unless this IPC
   *  is invoked, so the caller is responsible for confirming with
   *  the user before calling. */
  pushLocalRepoForce: (owner: string, repo: string) => Promise<LocalRepoStatusDto>;
  /** Creates a new local branch from `base` (or current HEAD when
   *  omitted) and switches to it. */
  createLocalBranch: (
    owner: string, repo: string, name: string, base?: string,
  ) => Promise<LocalRepoStatusDto>;
  /** Switches HEAD to an existing local branch. Errors when the
   *  working tree has conflicting uncommitted changes — git's
   *  stderr surfaces inline so the user can stash or commit. */
  switchLocalBranch: (
    owner: string, repo: string, name: string,
  ) => Promise<LocalRepoStatusDto>;
  /** Fetches a branch from origin then switches to it. Backs the
   *  Check-out action on remote-only IN_REVIEW cards. */
  checkoutRemoteBranch: (
    owner: string, repo: string, name: string,
  ) => Promise<LocalRepoStatusDto>;
  /** Opens a pull request on github.com against the watched repo,
   *  with the local clone's HEAD as the source. Returns the new PR
   *  number and html URL so the caller can navigate or toast. */
  createLocalPullRequest: (
    owner: string, repo: string,
    payload: { title: string; body: string; base: string; draft: boolean },
  ) => Promise<{ number: number; htmlUrl: string }>;
  /** Asks the active LLM to draft a PR title + description from the
   *  diff between current HEAD and `base`. Returns the draft so the
   *  Open-PR modal can fill its inputs for the user to refine. */
  draftLocalPullRequest: (
    owner: string, repo: string, base: string, head: string,
  ) => Promise<{ title: string; description: string }>;
  /** Bulk-deletes cleanup-eligible branches. The backend re-validates
   *  the current branch (always refused) and returns the names that
   *  were actually deleted. When `deleteRemote` is true, also runs
   *  `git push origin --delete` for any deleted branch with an
   *  upstream tracking ref. */
  deleteLocalBranches: (
    owner: string, repo: string, names: string[], deleteRemote?: boolean,
  ) => Promise<string[]>;
  /** Opens the repo's working-tree directory in macOS Finder. */
  revealRepoInFinder: (path: string) => Promise<void>;
  /** Opens the repo path in iTerm if installed, else Terminal.app. */
  openRepoInTerminal: (path: string) => Promise<void>;
  /** Opens the repo path in the first installed IDE from a default
   *  list (VS Code → Cursor → JetBrains). User-configurable later. */
  openRepoInIDE: (path: string) => Promise<void>;
  getUserRepos: () => Promise<UserRepoDto[]>;
  getUserOrgs: () => Promise<UserOrgDto[]>;
  searchRepos: (query: string) => Promise<UserRepoDto[]>;
  searchUsers: (query: string) => Promise<GitHubUserMatchDto[]>;
  getRecentActivity: (login: string) => Promise<RecentEventDto[]>;
  getFollowingActivity: (login: string) => Promise<RecentEventDto[]>;
  /** Today's home-page daily card. Stable for the whole day. */
  getDailyCard: () => Promise<DailyCardDto>;
  updateProfile: (name: string, bio: string, location: string) => Promise<UserProfileDto>;
  openExternal: (url: string) => Promise<void>;
  getUserStats: (login: string, force?: boolean) => Promise<UserStatsDto>;
  // Teams
  listTeams: () => Promise<TeamSummaryDto[]>;
  getTeam: (id: number) => Promise<TeamDto>;
  createTeam: (req: CreateTeamRequest) => Promise<TeamDto>;
  updateTeam: (id: number, req: UpdateTeamRequest) => Promise<TeamDto>;
  replaceTeamMembers: (id: number, members: string[]) => Promise<TeamDto>;
  deleteTeam: (id: number) => Promise<void>;
  getTeamPulls: (id: number) => Promise<PullRequestDto[]>;
  /** Initial-paint endpoint for the team kanban: first N per column. */
  getTeamPullsByColumn: (id: number, perColumn: number, force: boolean) => Promise<TeamColumnsResponse>;
  /** Pagination endpoint: next page of one column. */
  getTeamColumnPage: (id: number, column: MyPrColumnSlug, offset: number, limit: number) => Promise<ColumnPageDto>;
  /** Total merged-PR count for a team in the last {@code days} days.
   *  Powers the "Merged this week" stat on the team home page; the
   *  renderer is expected to wrap calls in a ~10-minute TTL cache. */
  countTeamMergedRecently: (id: number, days: number) => Promise<number>;
  // Slack integration
  /** Returns the Slack authorize URL the renderer should open in the
   *  system browser. {@code configured} is false when the backend hasn't
   *  been given SLACK_CLIENT_ID / SLACK_CLIENT_SECRET — in that case
   *  {@code url} is omitted and the renderer should show a hint. */
  getSlackAuthorizeUrl: () => Promise<{ configured: boolean; url?: string }>;
  /** Connection-state snapshot. Cheap; backed by a credential lookup. */
  getSlackConnection: () => Promise<SlackConnectionDto>;
  disconnectSlack: () => Promise<void>;
  /** Subscribes to OAuth-callback completions. Fires after Slack
   *  redirects to bytequay://slack-oauth-callback and the backend has
   *  exchanged the code. {@code success} is false when the exchange
   *  errored (state mismatch, Slack-side rejection, network).
   *  Returns a teardown that removes the listener. */
  onSlackOauthComplete: (callback: (payload: { success: boolean; error?: string }) => void) => () => void;
  /** Lists the user's joined Slack channels with isFollowed +
   *  isSmartDefault flags for the channel-picker. */
  listSlackChannels: () => Promise<SlackChannelRowDto[]>;
  /** Replaces the followed-channel set for the connected workspace.
   *  Returns the refreshed picker payload (smart-default flags drop
   *  off after the first save). */
  replaceFollowedSlackChannels: (channelIds: string[]) => Promise<SlackChannelRowDto[]>;
  /** Inbox view rows for the inbox.png surface (slice 5). Filter
   *  defaults to 'all' (mentions + DMs); 'mentions' or 'dms' narrows
   *  the result to one kind. */
  listSlackInbox: (filter?: SlackInboxFilter) => Promise<SlackInboxItemDto[]>;
  /** Full thread (parent + every reply) for an expanded MENTION item. */
  getSlackInboxThread: (channelId: string, ts: string) => Promise<SlackInboxThreadDto>;
  /** Flips the local row's state to EXPANDED — no Slack call. Idempotent. */
  expandSlackInboxItem: (channelId: string, ts: string) => Promise<{ result: string }>;
  /** Posts a reply to Slack and marks the local row RESPONDED on success. */
  replySlackInboxItem: (channelId: string, ts: string, text: string) => Promise<{ result: string; postedTs?: string }>;
  /** Manual archive-now ("Archive now" link in the responded countdown bar). */
  archiveSlackInboxItem: (channelId: string, ts: string) => Promise<{ result: string }>;
  /** Channel-feed payload (slice 6) — oldest-first stream of cached messages. */
  getSlackChannelFeed: (channelId: string) => Promise<SlackChannelFeedDto>;
  /** Posts to a channel/DM without touching the inbox state machine.
   *  threadTs is null for top-level posts (DM compose box) and the
   *  thread root for thread replies (channel-feed thread expand). */
  postSlackFeedMessage: (channelId: string, text: string, threadTs: string | null) => Promise<{ result: string; postedTs?: string }>;
  /** Issues an authorize URL for the GitHub OAuth + PKCE flow. The renderer
   *  opens it in the system browser. {@code configured} is false when the
   *  backend hasn't been given GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET — in
   *  that case the renderer falls back to the PAT input. */
  getGitHubOAuthAuthorizeUrl: () => Promise<{ configured: boolean; url?: string }>;
  /** Connection-state snapshot for the OAuth-stored token. */
  getGitHubOAuthConnection: () => Promise<{ connected: boolean; login?: string }>;
  disconnectGitHubOAuth: () => Promise<void>;
  /** Subscribes to OAuth-callback completions. Fires after GitHub redirects
   *  to bytequay://github-oauth-callback and the backend has exchanged the
   *  code. Returns a teardown that removes the listener. */
  onGitHubOauthComplete: (callback: (payload: { success: boolean; error?: string; login?: string }) => void) => () => void;
  /** Runs the full Gmail OAuth dance end-to-end: spins up a loopback
   *  HTTP listener, asks the backend for an authorize URL bound to that
   *  port, opens the system browser, captures Google's redirect, and
   *  forwards code+state to the backend's /callback. Resolves once the
   *  backend has stored the refresh token (or once the user gives up).
   *  Email is set on success; error is set on failure. */
  connectGmailAccount: () => Promise<{ success: boolean; error?: string; email?: string }>;
  /** Connects a Gmail account via IMAP + app password. Validates the
   *  credentials by opening an imaps session before persisting; throws
   *  on auth failure. Sister to {@link connectGmailAccount}. */
  connectGmailImap: (email: string, appPassword: string) => Promise<{ email: string }>;
  /** All currently connected Gmail accounts (both OAuth and IMAP),
   *  with the auth mode badge for each row. */
  listGmailAccounts: () => Promise<Array<{ email: string; authMode: 'OAUTH' | 'IMAP' }>>;
  /** Drops the stored credential for a single Gmail account regardless
   *  of auth mode. Idempotent on both sides. */
  disconnectGmailAccount: (email: string) => Promise<void>;
  /** Lists conversation threads in the inbox for the given account,
   *  newest first. pageSize defaults to 50 server-side; capped at
   *  500 (Gmail limit). */
  listEmailThreads: (account: string, pageSize?: number) => Promise<EmailThreadMetaDto[]>;
  /** Force-syncs the local cache from Gmail (incremental, or full
   *  if the watermark expired) and returns the resulting list. The
   *  Refresh button calls this; mount uses the cache-only listEmailThreads. */
  refreshEmailThreads: (account: string, pageSize?: number) => Promise<EmailThreadMetaDto[]>;
  /** Full thread including every message and its parsed body. */
  getEmailThread: (account: string, id: string) => Promise<EmailThreadDetailDto>;
  /** Archive / mark-read / mark-unread operate on the entire thread —
   *  matches Gmail's UI semantics, where these actions apply to the
   *  conversation, not a single message. */
  archiveEmailThread: (account: string, id: string) => Promise<void>;
  markEmailThreadRead: (account: string, id: string) => Promise<void>;
  markEmailThreadUnread: (account: string, id: string) => Promise<void>;
  // Credentials vault
  listCredentials: (type?: CredentialType) => Promise<CredentialDto[]>;
  upsertCredential: (req: UpsertCredentialRequest) => Promise<CredentialDto>;
  deleteCredential: (type: CredentialType, name: string, instanceName?: string) => Promise<void>;
  // AI review
  listAiProviders: () => Promise<AiProviderInfo[]>;
  getAiSettings: () => Promise<AiSettingsDto>;
  setAiSettings: (provider: string, model: string | null) => Promise<AiSettingsDto>;
  /** List every configured per-repo review skill, alphabetised. */
  listReviewSkills: () => Promise<ReviewSkillDto[]>;
  createReviewSkill: (input: {
    skillName: string;
    repo: string;
    llmProvider: string | null;
    description: string | null;
    context: string | null;
  }) => Promise<ReviewSkillDto>;
  updateReviewSkill: (id: number, input: {
    skillName: string;
    repo: string;
    llmProvider: string | null;
    description: string | null;
    context: string | null;
  }) => Promise<ReviewSkillDto>;
  deleteReviewSkill: (id: number) => Promise<void>;
  runAiReview: (prId: number, repo: string, number: number) => Promise<AiReviewDraftDto>;
  /** Sends the user's draft comment text to the active LLM and returns
   *  a polished rewrite. Used by the "Better words" button — UI replaces
   *  the textarea contents with the response. */
  polishCommentText: (text: string) => Promise<string>;
  /** Sends a CI failure log to the active LLM and returns a markdown
   *  root-cause-and-fix reply for the merge bar's "Ask AI to fix"
   *  button. The body is the trimmed last-N-bytes of the Actions log. */
  diagnoseCheckFailure: (checkName: string, log: string) => Promise<string>;
  getLatestAiReview: (prId: number) => Promise<AiReviewDraftDto | null>;
  deleteAiReview: (draftId: number) => Promise<void>;
  /** Async start — backend runs the review on its executor and returns
   *  immediately. Poll {@link getAiReviewStatus} until state is DONE/FAILED,
   *  then fetch the persisted draft via {@link getLatestAiReview}. */
  startAiReview: (prId: number, repo: string, number: number) => Promise<{ state: string }>;
  getAiReviewStatus: (repo: string, number: number) => Promise<{ state: 'IDLE' | 'RUNNING' | 'DONE' | 'FAILED'; error: string | null }>;
  /** Publishes a stored draft to GitHub as a single review. {@code event}
   *  is one of "COMMENT", "APPROVE", or "REQUEST_CHANGES" — controls the
   *  GitHub review action. */
  publishAiReview: (
    draftId: number,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
    body?: string | null,
  ) => Promise<AiReviewDraftDto>;
  /** Submit a verdict-only or mixed review for a PR — backend
   *  finds-or-creates the draft so the user can Approve / Comment
   *  without first staging an inline comment. */
  publishReviewForPr: (payload: {
    prId: number;
    repo: string;
    number: number;
    headSha: string | null;
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
    body: string | null;
  }) => Promise<AiReviewDraftDto>;
  /** Edits a single AI comment's body. Pass null/empty to clear the edit
   *  and revert to the AI's original. Returns the parent draft refreshed. */
  updateAiReviewComment: (draftId: number, commentId: number, editedBody: string | null) => Promise<AiReviewDraftDto>;
  /** Drops a single AI comment from a draft. Returns the parent draft refreshed. */
  deleteAiReviewComment: (draftId: number, commentId: number) => Promise<AiReviewDraftDto>;
  /** Toggles the dismissed flag on a comment. Dismissed comments are kept
   *  on the row but excluded from the publish payload. */
  setAiReviewCommentDismissed: (draftId: number, commentId: number, dismissed: boolean) => Promise<AiReviewDraftDto>;
  /** Adds an emoji reaction to a per-line review comment. {@code content}
   *  is one of the GitHub reaction strings (+1 / -1 / laugh / confused /
   *  heart / hooray / rocket / eyes). Idempotent on the GitHub side. */
  addReviewCommentReaction: (
    repo: string,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ) => Promise<void>;
  /** Adds an emoji reaction to a top-level issue / PR comment (the
   *  "commented" timeline events). Same content allowlist as the
   *  review-comment variant. */
  addIssueCommentReaction: (
    repo: string,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ) => Promise<void>;
  /** Toggles a review thread's resolved state via GitHub's GraphQL
   *  mutations. Identified by the REST root comment id; the backend
   *  translates to the GraphQL node id internally. */
  setReviewThreadResolved: (
    repo: string,
    prId: number,
    rootCommentId: number,
    resolved: boolean,
  ) => Promise<void>;
  /** Stage a human-authored inline comment into the active review draft. */
  stageReviewComment: (payload: {
    prId: number;
    repo: string;
    number: number;
    headSha: string | null;
    filePath: string;
    line: number;
    side: 'LEFT' | 'RIGHT';
    startLine?: number | null;
    startSide?: 'LEFT' | 'RIGHT' | null;
    body: string;
  }) => Promise<AiReviewDraftDto>;
  writeClipboard: (text: string) => Promise<void>;
  // Embedded GitHub review, implemented as a WebContentsView overlaid on the
  // main window's content area. Bounds are in CSS pixels (getBoundingClientRect).
  mountReview: (repo: string, number: number, bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
  setReviewBounds: (bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
  unmountReview: () => Promise<void>;
  // Clear the review partition's cookies and reload /login — escape hatch
  // when github.com is stuck on a passkey-only re-verification page.
  resetReviewSignIn: (repo: string, number: number) => Promise<void>;
  // Fires when the review WebContentsView tried to navigate to a third-party
  // SSO provider (Google, Microsoft, Apple) that refuses embedded browsers.
  // Returns an unsubscribe function.
  onReviewAuthBlocked: (callback: (payload: { provider: string }) => void) => () => void;
  // Fires when the review view lands on a GitHub sign-in page. Used to show
  // a proactive tip banner so the user doesn't pick passkey (which will hang
  // forever — Electron can't drive the macOS platform authenticator).
  onReviewSignInPage: (callback: () => void) => () => void;
  /** Walks the embed's own history one step back/forward — Chrome-style
   *  ←/→ for the review screen so links inside a comment can be
   *  followed and unfollowed without exiting the embed. */
  reviewGoBack: () => Promise<void>;
  reviewGoForward: () => Promise<void>;
  /** Subscribes to the embed's nav-state pings so the toolbar buttons
   *  enable/disable in step with the actual back/forward stack. */
  onReviewNavState: (callback: (s: { canGoBack: boolean; canGoForward: boolean }) => void) => () => void;
  // ─── Generic in-app browser ──────────────────────────────────────
  /** Mount a WebContentsView at the given screen-coords bounds and
   *  load {@code url}. Replaces any existing in-app-browser view. */
  mountInAppBrowser: (url: string, bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
  setInAppBrowserBounds: (bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
  unmountInAppBrowser: () => Promise<void>;
  inAppGoBack: () => Promise<void>;
  inAppGoForward: () => Promise<void>;
  inAppReload: () => Promise<void>;
  inAppLoadUrl: (url: string) => Promise<void>;
  /** Pop the URL out into its own native Electron window — independent
   *  of the main app window, OS-supplied chrome. Lets the user keep
   *  multiple pages open side-by-side without a tab strip. */
  inAppPopOut: (url: string) => Promise<void>;
  /** Fires when main wants the renderer to open a URL in the in-app
   *  browser overlay — triggered by setWindowOpenHandler / will-navigate
   *  on the main window. */
  onInAppOpenRequest: (callback: (payload: { url: string }) => void) => () => void;
  /** Fires when main intercepts a {@code bytequay://} link (currently
   *  only used by enriched email bodies — see EmailHtmlEnricher) and
   *  asks the renderer to navigate inside the app. {@code action} maps
   *  to a route ("pr-diff"); {@code params} carries the query-string
   *  arguments. */
  onAppNavRequest: (callback: (payload: { action: string; params: Record<string, string> }) => void) => () => void;
  /** Push of the in-app browser's current nav state (URL + title +
   *  back/forward + loading) for the toolbar to render against. */
  onInAppNavState: (callback: (s: InAppNavState) => void) => () => void;
  /** Fires whenever the main window enters/leaves macOS native
   *  fullscreen so the renderer can fill the now-vacant traffic-light
   *  reserve with a brand mark. */
  onFullScreenChange: (callback: (payload: { isFullScreen: boolean }) => void) => () => void;
  /** Synchronous pull paired with onFullScreenChange — the renderer
   *  queries this on mount to recover the initial state if the main
   *  process's did-finish-load push raced React's listener registration. */
  getFullScreenState: () => Promise<boolean>;
};

export type InAppNavState = {
  url: string;
  title: string;
  canGoBack: boolean;
  canGoForward: boolean;
  loading: boolean;
};
