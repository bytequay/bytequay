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
import type { BrainMessageResult, SpawnReviewResult, StageDetailData, TaskBrainViewData } from './types/brainView';

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

export type PrAnalyticsScope = '7d' | '30d' | '90d' | 'all';

export type PrAnalyticsKpiCardDto = {
  /** Raw scalar — null when the card is in a pending / placeholder state. */
  value: number | null;
  /** Rendered string the page shows when {@link pendingNote} is null. */
  displayValue: string;
  /** True for KPIs whose underlying data lives only in the cached
   *  PR-detail blob and may therefore under-count. The card renders
   *  a ¹ marker and the footer card explains it. */
  partial: boolean;
  /** When non-null, the card renders this copy in an empty state
   *  instead of {@link displayValue}. */
  pendingNote: string | null;
};

export type PrAnalyticsStaleAuthoredPrDto = {
  id: number;
  repo: string;
  number: number;
  title: string;
  createdAt: string;
  ageDays: number;
};

export type PrAnalyticsOutcomeSliceDto = {
  /** Canonical GitHub review state — "APPROVED", "CHANGES_REQUESTED",
   *  "COMMENTED", "DISMISSED", or rare states pass-through. */
  state: string;
  count: number;
};

export type PrAnalyticsSizeBucketDto = {
  label: string;
  count: number;
};

export type PrAnalyticsRepoReviewCountDto = {
  repo: string;
  count: number;
};

export type PrAnalyticsDailyActivityDto = {
  /** ISO yyyy-MM-dd in the user's local timezone. */
  date: string;
  approved: number;
  changesRequested: number;
  commented: number;
  dismissed: number;
};

export type PrAnalyticsHeatmapCellDto = {
  /** 0 = Sunday, 6 = Saturday. */
  dayOfWeek: number;
  /** 0 = midnight, 23 = 11pm — local time. */
  hour: number;
  count: number;
};

export type PrAnalyticsCoReviewerDto = {
  login: string;
  count: number;
};

export type MyActivityDailyAuthoredDto = {
  date: string;
  opened: number;
  merged: number;
};

export type MyActivityRepoActivityCountDto = {
  repo: string;
  prsOpened: number;
  prsMerged: number;
};

export type MyActivitySummaryDto = {
  scope: PrAnalyticsScope;
  watchedRepoCount: number;
  currentLogin: string | null;
  prsOpened: PrAnalyticsKpiCardDto;
  prsMerged: PrAnalyticsKpiCardDto;
  commitsMade: PrAnalyticsKpiCardDto;
  commentsPosted: PrAnalyticsKpiCardDto;
  dailyAuthored: MyActivityDailyAuthoredDto[];
  reposByActivity: MyActivityRepoActivityCountDto[];
  currentStreakDays: number | null;
  longestStreakDays: number | null;
};

export type PrAnalyticsSummaryDto = {
  scope: PrAnalyticsScope;
  watchedRepoCount: number;
  currentLogin: string | null;
  prsReviewed: PrAnalyticsKpiCardDto;
  approvalRate: PrAnalyticsKpiCardDto;
  linesReviewed: PrAnalyticsKpiCardDto;
  responseToReviewRequest: PrAnalyticsKpiCardDto;
  reviewOutcomes: PrAnalyticsOutcomeSliceDto[];
  sizeDistribution: PrAnalyticsSizeBucketDto[];
  reposByReview: PrAnalyticsRepoReviewCountDto[];
  dailyActivity: PrAnalyticsDailyActivityDto[];
  reviewHeatmap: PrAnalyticsHeatmapCellDto[];
  reviewNetwork: PrAnalyticsCoReviewerDto[];
  staleAuthoredPrs: PrAnalyticsStaleAuthoredPrDto[];
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
  /** GitHub merge-queue entry state — "QUEUED", "MERGEABLE",
   *  "UNMERGEABLE", etc. when the PR currently has an entry in the
   *  repo's merge queue; null when no entry exists or the repo
   *  doesn't use a merge queue. GraphQL-sourced (REST doesn't expose
   *  this per-PR). Drives the "Queued" status pill. */
  mergeQueueState: string | null;
  /** True when the PR's base branch has a merge queue configured — i.e.
   *  it's possible to add this PR to the queue (regardless of whether it
   *  currently has an entry). Authoritative, GraphQL-sourced from
   *  `pullRequest.mergeQueue`. Drives the "Add to merge queue" button
   *  mode, replacing the old client-side heuristic. */
  mergeQueueEnabled: boolean;
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

/** Result envelope for the conflict-paths enumeration. `available:false`
 *  means the local-clone path couldn't produce a definitive list — the
 *  pill should fall back to the github.com link without rendering a
 *  count or expandable file list. */
export type MergeConflictPathsDto = {
  available: boolean;
  /** Stable token when !available: 'no_local_clone' | 'no_base_ref' |
   *  'invalid_pr_number' | 'fetch_failed' | 'merge_tree_failed' */
  reason: string | null;
  paths: string[];
};

export type SyncSettingsDto = {
  intervalSeconds: number;
};

export type WatchedRepoDto = {
  id: number;
  owner: string;
  repo: string;
  displayOrder: number;
  /** Absolute path to the local clone. Set when the user has cloned
   *  the repo locally; null when the repo is watched read-only. The
   *  new-thread dialog uses this as the agent's working directory. */
  localClonePath: string | null;
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

/** One commit attributed to a user on one calendar day. Powers the
 *  heatmap-cube popover that unfolds a count into its actual commits. */
export type UserCommitDto = {
  sha: string;
  repoFullName: string;
  shortMessage: string;
  htmlUrl: string;
  /** ISO instant or null when GitHub omitted the timestamp. */
  authoredAt: string | null;
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
  /** Structural timeline events backing the Activity + Linked tabs.
   *  Commented events live on {@link comments} — the timeline carries
   *  everything else (labeled, assigned, milestoned, closed, reopened,
   *  renamed, mentioned, cross-referenced, …). */
  timeline: IssueTimelineEventDto[];
  /** True iff the viewer has explicitly subscribed to the issue. Drives
   *  the Subscribe / Unsubscribe button in the header. */
  subscribed: boolean;
};

/** One row of the issue timeline. Most fields are populated only for
 *  matching event types — {@code label} fills in for {@code labeled}
 *  / {@code unlabeled}, {@code assignee} for assignment events, etc.
 *  The renderer dispatches on {@link event}. */
export type IssueTimelineEventDto = {
  event: string;
  actor: string | null;
  timestamp: string | null;
  label: { name: string; color: string } | null;
  assignee: string | null;
  milestone: string | null;
  rename: { from: string; to: string } | null;
  crossReference: {
    number: number;
    title: string;
    state: string;
    isPullRequest: boolean;
    repoFullName: string | null;
    htmlUrl: string | null;
  } | null;
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
 *  on multi-message threads.
 *
 *  `matchedTagId` / `view` are stamped by the tag classification pass
 *  on the backend. Threads with no matching rule arrive as
 *  `matchedTagId === null` and `view === 'INBOX'`. */
export type EmailThreadMetaDto = {
  id: string;
  latestMessageId: string | null;
  from: string;
  subject: string;
  snippet: string;
  receivedAt: string;
  unread: boolean;
  messageCount: number;
  matchedTagId: string | null;
  view: EmailThreadView;
};

/** Mirror of backend EmailThreadMeta.View. Drives which left-nav
 *  bucket a thread renders under. ARCHIVE-classified threads are
 *  removed from inbox-listing responses by the backend, so the
 *  frontend only ever sees INBOX, FOCUS, or IGNORE here. */
export type EmailThreadView = 'INBOX' | 'FOCUS' | 'ARCHIVE' | 'IGNORE';

/** Mirror of backend EmailTag. A user-defined classification rule
 *  that case-insensitively substring-matches the email subject. */
export type EmailTagDto = {
  id: string;
  accountEmail: string;
  name: string;
  subjectContains: string;
  action: EmailTagAction;
  createdAt: string;
  updatedAt: string;
};

/** Mirror of backend EmailTag.Action.
 *  - FOCUS: show in inbox; surfaced under the tag in the left nav.
 *  - ARCHIVE: archived on Gmail at match time, recorded in the
 *    archive log, browsable under "Archived" in the left nav.
 *  - IGNORE: hidden from the app entirely; no Gmail-side change. */
export type EmailTagAction = 'FOCUS' | 'ARCHIVE' | 'IGNORE';

/** Mirror of backend EmailTagArchiveEntry. One row in the local
 *  audit log of archives — what the "Archived" view in the email
 *  left nav reads from. {@code tagId} is null for manual archives
 *  (user clicked the archive button or opened an unread thread);
 *  non-null for tag-rule-driven archives. */
export type EmailTagArchiveEntryDto = {
  accountEmail: string;
  gmailThreadId: string;
  tagId: string | null;
  subject: string | null;
  fromAddr: string | null;
  snippet: string | null;
  receivedAt: string;
  archivedAt: string;
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

/** The kinds of surface a footprint visit can land on — mirrors the
 *  backend SurfaceType enum. */
export type SurfaceType = 'PR_KANBAN' | 'PR' | 'TASK' | 'THREAD';

/** Payload for recording one visit to a tracked surface. {@code surfaceId}
 *  is the renderer's navigable key (e.g. "owner/repo#5680",
 *  "threadId/taskId"); the resume handler parses it back. */
export type SurfaceVisitInput = {
  surfaceType: SurfaceType;
  surfaceId: string;
  title?: string | null;
  context?: string | null;
};

/** One stop on the footprints trail — a merged surface with its visit
 *  count and the latest-visit time that fixes its position. */
export type FootprintStopDto = {
  surfaceType: SurfaceType;
  surfaceId: string;
  title: string | null;
  context: string | null;
  latestVisitAt: string;
  visitCount: number;
};

/** A calendar day's footprints trail. {@code stops} are capped to the
 *  most recent few, ordered oldest-first; {@code totalStops} is the
 *  pre-cap distinct-surface count for the "showing N of M" line. */
export type FootprintsTrailDto = {
  date: string;
  stops: FootprintStopDto[];
  totalStops: number;
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
//   INTEGRATION — name is the integration id ("github-oauth-app", etc.)
//   MCP         — name is the MCP service id ("slack", "linear", …);
//                 per-service extras (transport / auth-kind / URL /
//                 env-var) ride along in {@code configJson}.
export type CredentialType = 'ACCOUNT' | 'REPO' | 'AI' | 'INTEGRATION' | 'MCP';

/** Structured payload that lives in {@code CredentialDto.configJson}
 *  for MCP rows. Encoded as a JSON string on the wire so the
 *  existing credentials table can keep one column for all kinds;
 *  the frontend serializes + parses around the bridge boundary. */
export type McpCredentialConfig = {
  /** Remote = network MCP server (HTTP + auth). Local = stdio
   *  subprocess; the secret is injected into the child's env. */
  transport: 'remote' | 'local';
  /** Remote only — how the server authenticates the bearer. OAuth
   *  rows are status-only (no Test, Re-auth from the row); bearer
   *  rows show the standard ✓/⚠ probe. */
  authKind?: 'oauth' | 'bearer';
  /** Remote only — server endpoint URL. */
  serverUrl?: string;
  /** Local only — launch command (e.g., "mcp-server-slack"). */
  command?: string;
  /** Local only — env var name the secret value gets injected as. */
  envVarName?: string;
};

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
  /** True when this row is the ★ default for its (type, name)
   *  group. Resolvers that don't name an instance pick this one. */
  isDefault: boolean;
  /** Kind-specific structured config (raw JSON string, may be
   *  parsed with {@link McpCredentialConfig} for MCP rows). Null
   *  for ACCOUNT / AI / REPO / INTEGRATION rows. */
  configJson: string | null;
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
  /** MCP-only structured config (serialised as JSON). Null / omit
   *  for the other kinds. */
  configJson?: string | null;
};

// Display metadata for the canned credential templates the editor offers.
// These live frontend-side because the backend doesn't dictate UI labels
// — adding a new well-known credential just requires extending this list.
export type CredentialTemplate = {
  type: CredentialType;
  name: string;
  displayName: string;
  usageDescription: string;
  /** Concrete features that consult this credential when it fires.
   *  Rendered as a chip list on each row so the user can see "if I
   *  delete this, X breaks" without spelunking the code. */
  poweredBy?: string[];
  /** Set when the credential is required for some features even if
   *  the user has chosen a different active provider — i.e. those
   *  features are hard-coded to this credential, not driven by the
   *  provider picker. Surfaced as a separate "always-on" group on
   *  the row so the user understands why the key matters even when
   *  Claude isn't the active LLM. */
  alwaysOnFeatures?: string[];
};

export const CREDENTIAL_TEMPLATES: CredentialTemplate[] = [
  {
    type: 'ACCOUNT',
    name: 'github',
    displayName: 'GitHub PAT',
    usageDescription: 'Authenticates calls for user profile, repo, org, and other GitHub reads. Singleton — only one allowed.',
    poweredBy: [
      'PR list + sync',
      'Repo, issue, PR detail',
      'Posting comments + reviews',
      'PR / issue search',
      'Branch + commit reads',
    ],
  },
  {
    type: 'AI',
    name: 'anthropic',
    displayName: 'Anthropic API key',
    usageDescription: 'Authenticates calls to the Claude API.',
    poweredBy: [
      'AI PR review (when Claude is the active provider)',
    ],
    alwaysOnFeatures: [
      'Checkpoint summaries (Haiku)',
      'PR description draft (Opus)',
      'Comment polish (Opus)',
      'CI failure diagnose (Opus)',
    ],
  },
  {
    type: 'AI',
    name: 'openai',
    displayName: 'OpenAI API key',
    usageDescription: 'Authenticates calls to the OpenAI API.',
    poweredBy: [
      'AI PR review (when OpenAI is the active provider)',
    ],
  },
  {
    type: 'AI',
    name: 'deepseek',
    displayName: 'DeepSeek API key',
    usageDescription: 'Authenticates calls to the DeepSeek chat completions API. Default model is deepseek-chat; deepseek-reasoner is also available.',
    poweredBy: [
      'AI PR review (when DeepSeek is the active provider)',
    ],
  },
  {
    type: 'AI',
    name: 'local',
    displayName: 'Local LLM endpoint',
    usageDescription: 'Base URL for an OpenAI-compatible local model server (e.g. Ollama, LM Studio).',
    poweredBy: [
      'AI PR review (when Local is the active provider)',
    ],
  },
];

export type AiProviderInfo = {
  providerId: string;
  displayName: string;
  configured: boolean;
  active: boolean;
};

/** Result of the Settings → AI review credential "Test" button. The
 *  backend probe ran ok iff {@code ok=true}; otherwise {@code message}
 *  carries the upstream's error response truncated to ~200 chars. */
export type CredentialTestResult = {
  ok: boolean;
  message: string;
  /** Round-trip latency of the probe in ms. Null when the call
   *  didn't fire (e.g. no stored value). */
  latencyMs: number | null;
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

/* ── Work-model axis ──────────────────────────────────────────────
 *
 * One choice on the agent + model cascade. Mirrors the backend
 * {@code WorkModel} record. Each scope (workspace, thread, task,
 * review-seat) carries one of these; the resolver walks the cascade
 * most-specific-wins. */

export type WorkModelKindDto = 'CLI' | 'API';

export type WorkModelDto = {
  kind: WorkModelKindDto;
  /** CLI agent id (e.g. {@code "claude-code"}) or API provider id
   *  (e.g. {@code "anthropic"}). Joins back into the catalog. */
  agentOrProvider: string;
  /** Explicit model override, or null to inherit the agent's default. */
  model: string | null;
  /** API-only — credential instance name. Null = the ★ default
   *  account for this provider. Ignored on CLI kinds (the agent
   *  manages its own auth). */
  account: string | null;
};

/** Lifecycle state of the local ds4 inference subprocess. Mirrors
 *  com.bytequay.app.service.local.ds4.Ds4State. */
export type Ds4StateDto =
  | 'DISABLED'
  | 'NOT_CONFIGURED'
  | 'STOPPED'
  | 'STARTING'
  | 'RUNNING'
  | 'STOPPING'
  | 'CRASHED';

/** Snapshot returned by getDs4Status and embedded in other responses. */
export type Ds4StatusDto = {
  state: Ds4StateDto;
  endpoint: string;
  pid: number;
  startedAt: string | null;
  spawnedByUs: boolean;
  restartAttempts: number;
  uptimeSec: number;
  lastError: string | null;
};

/** Apply-on-restart config for the ds4 server. */
export type Ds4ConfigDto = {
  binaryPath: string | null;
  port: number;
  model: string;
  quant: string;
  contextTokens: number;
  kvCacheDir: string;
  kvDiskBudgetMb: number;
  thinkingDefault: boolean;
  trace: boolean;
  /** Path of the ds4 git checkout. The binary lives at
   *  {@code <repoDir>/ds4-server}; the installer either clones into
   *  this dir or validates a user-supplied existing checkout. */
  repoDir: string | null;
  /** Argument passed to {@code ./download_model.sh} (e.g.
   *  {@code q2-imatrix} for 96–128 GB Macs). */
  modelVariant: string;
  installUrl: string;
  autoRestartOnCrash: boolean;
  autoStartOnBoot: boolean;
  attachIfRunning: boolean;
  /** Master switch for local AI. When false the backend never spawns,
   *  attaches, or restarts the ds4 server, and holds no GPU resources. */
  enabled: boolean;
};

/** Body of POST /api/ds4/install — drives clone+build, model
 *  download, and binary_path stamping in one supervisor run. */
export type Ds4InstallRequestDto = {
  /** Either the destination for {@code git clone} or the path of an
   *  existing ds4 checkout. Omitted → app-owned default. */
  repoDir?: string | null;
  /** True when the user already has ds4 built — installer skips
   *  clone + make, only validates the binary and downloads the
   *  model if missing. */
  reuseExisting: boolean;
  /** Argument for download_model.sh; defaults to q2-imatrix. */
  modelVariant?: string | null;
};

/** Response shape for POST /api/ds4/stop carrying the "stopping an
 *  attached server hits other clients too" confirm flag. */
export type Ds4StopResponseDto = {
  requiresConfirm: boolean;
  status: Ds4StatusDto;
  message: string | null;
};

/** Response shape for PUT /api/ds4/config. The restartRequired flag
 *  drives the "applies on restart" banner in the management tab. */
export type Ds4ConfigResponseDto = {
  config: Ds4ConfigDto;
  restartRequired: boolean;
  status: Ds4StatusDto;
};

/** Metrics envelope rendered by the Metrics tab. v1 only includes
 *  ByteQuay's own calls; the front-door proxy follow-up will fold
 *  in external clients' traffic. */
export type Ds4MetricsDto = {
  memory: {
    weightsBytes: number;
    kvCacheBytes: number;
    freeBytes: number;
    ceilingBytes: number;
    pct: number;
  };
  throughput: { currentTps: number; avg1mTps: number; peakTodayTps: number };
  latency: { firstTokenMs: number; avg1mMs: number };
  kvOnDisk: { usedBytes: number; budgetBytes: number; pct: number };
  requestsToday: { count: number; tokensIn: number; tokensOut: number };
  memorySpark30m: Array<{ atMs: number; bytes: number }>;
  recentRequests: Array<{
    tsMs: number;
    workspaceId: string;
    caller: string;
    route: string;
    tokensIn: number;
    tokensOut: number;
    tps: number;
    status: string;
  }>;
};

/** Progress shape for the multi-step installer. */
export type Ds4InstallStatusDto = {
  phase: 'IDLE' | 'CLONING' | 'BUILDING' | 'DOWNLOADING_MODEL' | 'READY' | 'FAILED';
  /** Install directory (set as soon as the install starts). */
  repoDir: string | null;
  modelVariant: string | null;
  /** Human-readable description of the step currently running. */
  currentStep: string | null;
  error: string | null;
};

/** Resolved cascade result returned by the thread/task work-model GET and
 *  PUT endpoints. Carries both the raw override set on the queried scope
 *  (nullable) and the cascade winner so the pill and rail section can render
 *  "Inherited from workspace ByteQuay" without a follow-up fetch. */
export type ResolvedWorkModelDto = {
  /** The override set directly on this thread or task; null when the scope
   *  has no override of its own. */
  override: WorkModelDto | null;
  /** The effective model after walking the full cascade (never null). */
  effective: WorkModelDto;
  /** Where the effective model came from. */
  provenance: {
    source: 'TASK' | 'THREAD' | 'WORKSPACE' | 'GLOBAL_DEFAULT';
    scopeId: string | null;
    scopeLabel: string;
  };
};

export type WorkModelEntryDto = {
  id: string;
  displayName: string;
  isDefault: boolean;
};

export type WorkModelAccountDto = {
  name: string;
  isDefault: boolean;
  /** Cached probe outcome — true reachable, false failed, null
   *  never probed. The picker renders ✓ / ⚠ / neutral chip from
   *  this. */
  valid: boolean | null;
};

export type WorkModelAgentOptionDto = {
  id: string;
  displayName: string;
  installed: boolean;
  authed: boolean;
  defaultModel: string;
  models: WorkModelEntryDto[];
};

export type WorkModelProviderOptionDto = {
  id: string;
  displayName: string;
  defaultModel: string;
  models: WorkModelEntryDto[];
  accounts: WorkModelAccountDto[];
};

export type WorkModelOptionsDto = {
  cliAgents: WorkModelAgentOptionDto[];
  apiProviders: WorkModelProviderOptionDto[];
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

/** A skill row — the model-triggered chunk of context the agent
 *  loads on demand via the list_skills / load_skill tools, or — for
 *  rubrics — an always-applied hint the review path resolves up front.
 *
 *  - scope='global' rows are loaded for every workspace.
 *  - scope='repo' carries a non-null repo (owner/name) so the rubric
 *    lookup can target it.
 *  - scope='thread' carries a non-null threadId so it lives with one
 *    specific thread.
 *  - roleTag binds the row to a specific agent role independently of
 *    scope so a global persona can target "reviewer" without inventing
 *    a sentinel.
 *  - kind separates library skills (model picks them up), personas
 *    (always-on identity per role), and rubrics (deterministic review-
 *    time rules).
 */
export type SkillDto = {
  id: number;
  scope: 'global' | 'repo' | 'thread';
  repo: string | null;
  threadId: string | null;
  name: string;
  description: string;
  body: string;
  kind: 'library' | 'persona' | 'rubric';
  /** Surface the skill belongs to: 'review' rows are selectable as
   *  reviewer roles in the assign-review dialog (and only there);
   *  'build' rows feed the build/task agents' skill tools. */
  usage: 'build' | 'review';
  roleTag: string | null;
  /** Persisted enable toggle. The Skills surface mutes disabled
   *  rows; the runtime lookups skip them. */
  enabled: boolean;
  /** When true the row is the default for its (scope, repo, kind,
   *  roleTag) group — used to pick a persona per repo. */
  isDefault: boolean;
  source: 'authored' | 'ai_drafted';
  provenance: string | null;
  contentHash: string;
  createdAt: string;
  updatedAt: string;
};

/** Payload for POST /skills and PUT /skills/{id}.
 *
 *  {@link source} and {@link provenance} are only consulted at create
 *  time. Set source to 'ai_drafted' + provenance to the user's prompt
 *  when saving an AI-drafted proposal so the row keeps a paper trail
 *  of where it came from. Both default to undefined on a manual write
 *  (the backend stamps source='authored', provenance=null). */
export type SkillInput = {
  scope: 'global' | 'repo' | 'thread';
  repo: string | null;
  threadId: string | null;
  name: string;
  description: string;
  body: string;
  kind: 'library' | 'persona' | 'rubric';
  usage?: 'build' | 'review';
  roleTag: string | null;
  isDefault: boolean;
  source?: 'authored' | 'ai_drafted';
  provenance?: string | null;
};

/** Result of POST /skills/draft — a proposed name + trigger
 *  description + body the user reviews + edits in the modal before
 *  saving. The trigger is a "loads when …" condition, not a title. */
export type SkillDraftDto = {
  name: string;
  description: string;
  body: string;
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

export type ThreadKindDto = 'CLI_AGENT' | 'LOGIC_LOOP';

export type ThreadStatusDto =
  | 'PENDING'
  | 'RUNNING'
  | 'AWAITING'
  | 'IDLE'
  /** Parked: the active task finished with a proposed diff and is
   *  holding at the publish gate. Surfaces a notification. */
  | 'AWAITING_REVIEW'
  /** Parked: the active task is stuck on a conflict / push rejection /
   *  judgment-call comment and needs the human to weigh in. */
  | 'NEEDS_ATTENTION'
  | 'COMPLETED'
  /** Auto-archived for inactivity (IdleThreadArchiver). Hidden from the
   *  default list like COMPLETED, but shown as "Archived" — dormant, not
   *  finished — and resumable. */
  | 'ARCHIVED'
  | 'ERRORED';

export type ThreadResourceLaneDto = 'CLI' | 'API';

export type ThreadTurnStatusDto =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

/** Structural flow discriminator on a thread. {@code build} threads
 *  own a branch via their tasks and mutate code; {@code review}
 *  threads reference a PR read-only and host a (possibly multi-agent)
 *  review panel. Set at create time and never silently flipped. */
export type ThreadFlowDto = 'build' | 'review';

export type ThreadDto = {
  id: string;
  kind: ThreadKindDto;
  provider: string;
  agentSessionId: string | null;
  title: string;
  status: ThreadStatusDto;
  /** Structural discriminator; see {@link ThreadFlowDto}. Defaults to
   *  {@code 'build'} on legacy rows. */
  flow: ThreadFlowDto;
  model: string;
  costUsdMilli: number;
  tokensIn: number;
  tokensOut: number;
  createdAt: string;
  updatedAt: string;
  endedAt: string | null;
  errorMessage: string | null;
  /** Owning workspace id. Drives the workspace-scoped thread list and
   *  the trunk cwd resolver — never null for threads created after
   *  the workspaceId-required write path landed. */
  workspaceId: string;
  /** The most recent non-terminal work-unit task for this thread.
   *  Null on 0-Task brainstorm threads. Carries the per-task
   *  execution surface (workingDir, branchName, worktreePath,
   *  linkedPrNumber, etc.) that used to live as flattened scalars
   *  on Thread before the bridge teardown. */
  activeTask: WorkUnitTaskDto | null;
  /** Per-thread work-model override; null means this scope inherits
   *  from workspace or the global default. */
  workModel: WorkModelDto | null;
  /** The trunk-owned queue of planned future tasks (V110). Empty on most
   *  threads; the head materialises into a task when the active task
   *  completes. */
  queue: QueuedTaskDto[];
  /** Concurrent compute slots the thread's tasks may occupy. 1 in v1. */
  parallelSlots: number;
};

export type ThreadGroupDto = {
  id: string;
  name: string;
  /** Single character (or short emoji) shown in the rail badge. */
  glyph: string;
  /** Free-form CSS-compatible color string; the renderer maps a small
   *  set of named swatches and falls back to {@code slate}. */
  color: string;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
};

/** One row of the {@code thread_group_members} join table. Threads and
 *  groups are many-to-many — one thread can live in several groups, and
 *  the new threads-group page caps a group at 4 members. The frontend
 *  pulls the full membership snapshot once and joins it in memory. */
export type ThreadGroupMembershipDto = {
  threadId: string;
  groupId: string;
  addedAt: string;
};

export type NewTaskGroupRequestDto = {
  name: string;
  glyph?: string;
  color?: string;
  sortOrder?: number;
  /** Required — at least one existing thread id. A group can never sit
   *  empty under the new invariant, and the cap of 4 is enforced on
   *  the backend. */
  initialTaskIds: string[];
};

/** Patch payload — only non-null/blank fields update on the backend. */
export type ThreadGroupPatchDto = {
  name?: string;
  glyph?: string;
  color?: string;
};

/** Per-(thread, path) rollup row. Powers the Files touched sidebar
 *  card on the detail page. */
export type ThreadFileDto = {
  threadId: string;
  path: string;
  /** {@code read} | {@code write} | {@code edit} | {@code delete} */
  operation: string;
  /** How many times this file was touched. */
  count: number;
  linesAdded: number;
  linesRemoved: number;
  lastTouchedAt: string;
};

/** One queued/running/completed scheduler turn. This is separate from
 *  ThreadMessageDto: messages are the transcript, turns are scheduler
 *  capacity state. */
export type ThreadTurnDto = {
  id: string;
  threadId: string;
  /** The focused Task this turn ran under, or null for a trunk-scope
   *  (planning) turn. The trunk's thinking indicator keys off this so a
   *  background Task turn can't make the trunk look perpetually busy. */
  taskId: string | null;
  lane: ThreadResourceLaneDto;
  status: ThreadTurnStatusDto;
  input: string;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  errorMessage: string | null;
};

export type ThreadTurnEventTypeDto =
  | 'TURN_QUEUED'
  | 'WAITING_FOR_CAPACITY'
  | 'TURN_STARTED'
  | 'TURN_FINISHED'
  | 'TURN_FAILED'
  | 'TURN_CANCELLED';

/** Durable scheduler event for one thread turn. Complements
 *  ThreadTurnDto with a chronological "why did this happen?" trail. */
export type ThreadTurnEventDto = {
  id: string;
  turnId: string;
  threadId: string;
  event: ThreadTurnEventTypeDto;
  createdAt: string;
  message: string | null;
};

export type ThreadSendResultDto = {
  status: 'queued';
  turnId: string;
};

/** One event delivered over the {@code /api/threads/:id/stream} SSE
 *  channel. {@code name} is the Java class's simple name (e.g.
 *  {@code AssistantText}, {@code ToolCallStarted}, {@code TurnDone},
 *  {@code PermissionRequested}, {@code PermissionAutoAllowed},
 *  {@code ErrorOccurred}, {@code SessionEnded}). {@code data} is the
 *  parsed JSON payload — the exact shape mirrors the corresponding
 *  Java record. */
export type ThreadStreamEvent = {
  name: string;
  data: Record<string, unknown>;
};

/** One row of the floating conversation-index panel. Derived
 *  server-side from {@code thread_messages} — never stored — so
 *  the panel can't drift from the rendered transcript. */
export type ConvIndexEntryDto = {
  seq: number;
  preview: string;
  tsMs: number;
};

/** AI-written summary of a conversation chunk. Two flavours share
 *  this shape: the Overall rollup (seq=0, isOverall=true, regenerated
 *  on each new segment) and per-segment summaries (seq>=1,
 *  immutable). All token / cost / model fields come from the
 *  Anthropic Haiku call that produced the summary. */
export type ThreadCheckpointDto = {
  id: string;
  threadId: string;
  seq: number;
  isOverall: boolean;
  firstMsgSeq: number;
  lastMsgSeq: number;
  tokensCovered: number;
  summaryMd: string;
  bulletTitles: string[];
  modelUsed: string;
  promptTokens: number;
  completionTokens: number;
  costUsdMilli: number;
  generatedAt: string;
  /** Stamped on Overall rows when a newer Overall replaces them.
   *  Null on per-segment rows and on the currently-active Overall. */
  supersededAt: string | null;
  /** FK to the Task this segment belongs to. Null on Overall rows
   *  (always thread-scoped) and on segments produced before the
   *  thread materialised its first Task (the 0-Task brainstorm state). */
  taskId: string | null;
};

/** The persistent project brain — a single ambient row in v1
 *  ({@code ws-default}) that holds the markdown blob loaded into
 *  every thread's context. */
export type WorkspaceDto = {
  id: string;
  name: string;
  /** The markdown blob loaded into every thread's context. Kept
   *  intentionally small (target ~2k tokens / 8 000 chars); the
   *  distillation pass keeps it that way by promoting durable
   *  decisions and demoting noise. */
  memoryMd: string;
  /** Scratch workspaces never accrue durable memory — the
   *  distillation pass skips them by design. */
  isScratch: boolean;
  /** The workspace's default pick on the work-model cascade. Null
   *  means no override is set; the resolver falls back to the
   *  global default in that case. */
  workModel: WorkModelDto | null;
  createdAt: string;
  updatedAt: string;
};

/** Card-shaped projection of a workspace for the top-level landing
 *  grid. Aggregates the thread / task counts and the memory summary
 *  the user picks between on, so the landing renders in one round-
 *  trip. Backed by {@code GET /api/workspaces}. */
export type WorkspaceCardDto = {
  id: string;
  name: string;
  /** Hex colour the gradient avatar uses. Derived from the name on
   *  the backend so a hand-typed name keeps the same colour across
   *  restarts. */
  color: string;
  /** Scratch workspaces accrue no durable memory and render the muted
   *  card variant ("throwaway · no durable memory"). */
  isScratch: boolean;
  /** Short repo names ({@code "bytequay"}, {@code "backend"}) — the
   *  card chips skip the owner prefix to keep rows scannable. */
  repos: string[];
  /** Threads in non-terminal status — what the design calls the
   *  workspace's "live" surface. */
  activeThreadCount: number;
  /** Tasks not yet COMPLETED or ERRORED across all of the workspace's
   *  threads — branches the agent is still touching. */
  tasksInFlight: number;
  /** Milli-USD spent on tasks created since local midnight.
   *  Approximation — no per-day cost ledger yet, so a long task
   *  counts on its create date. */
  spendTodayMilliUsd: number;
  /** Tasks parked at AWAITING_REVIEW or NEEDS_ATTENTION — drives the
   *  amber "N needs you" chip on the card footer. */
  needsAttentionCount: number;
  /** Decisions / blockers / token budget pulled from
   *  {@code memoryMd}'s named H2 sections. */
  memory: {
    decisionCount: number;
    blockerCount: number;
    tokensUsed: number;
    tokensCap: number;
  };
  /** Newest {@code updated_at_ms} across the workspace's threads;
   *  null for an empty workspace. Drives the relative "edited Nm ago"
   *  text. */
  lastActivityMs: number | null;
};

/** One repo attached to a workspace. v1 ships a single ambient
 *  workspace ({@code ws-default}), so for now the list mirrors the
 *  watched-repos table 1:1; the row carries the workspace-level
 *  settings that don't belong on the GitHub watched-list row
 *  (default base branch, auto-fix opt-in). */
export type WorkspaceRepoDto = {
  workspaceId: string;
  repoFullName: string;
  defaultBaseBranch: string | null;
  /** Off by default per CLAUDE.md; only when this is true will the
   *  automation coordinator queue a headless agent turn against a
   *  failing-CI candidate in this repo. */
  autoFixEnabled: boolean;
  addedAt: string;
};

/** One unit of work inside a {@link ThreadDto} — a branch + worktree +
 *  agent run + (eventually) a PR. A thread accumulates these as it
 *  rolls through "ship & continue" hops; at most one is non-terminal
 *  at a time. The DTO mirrors the backend {@code Task} record one-to-
 *  one. Field set is intentionally narrow for the rail's grouping
 *  needs: callers that need full details fetch the dedicated row.
 *
 *  Distinct from the older "task = thread" usage on the rest of the
 *  bridge (kept until the Phase-4 rename ships). */
/** The dev PR-collaboration lifecycle phase (backend TaskPhase, V106). */
export type TaskPhaseDto =
  | 'QUEUED'
  | 'IMPLEMENTING'
  | 'VALIDATING'
  | 'INTERNAL_REVIEW'
  | 'AWAITING_PUSH'
  | 'PUSHED_AWAITING_CI'
  | 'CI_FIXING'
  | 'AWAITING_READY'
  | 'AWAITING_REMOTE_REVIEW'
  | 'ADDRESSING_COMMENTS'
  | 'AGENT_RE_REVIEW'
  | 'AWAITING_UPDATE_PUSH'
  | 'COMPLETED'
  | 'NEEDS_ATTENTION';

/** Coarse trunk-card grouping over {@link TaskPhaseDto} (backend
 *  TaskPhaseGroup). */
export type TaskPhaseGroupDto = 'IN_PROGRESS' | 'AWAITING_YOU' | 'IDLE' | 'DONE';

// ── Task lifecycle flow trace (GET /api/tasks/{id}/trace) ──────────────

/** One node of the expanded sequential timeline — a phase-event row plus
 *  its derived milestone and friendly label. */
export type TraceEventDto = {
  n: number;
  fromPhase: string | null;
  toPhase: string;
  fromMilestone: string | null;
  toMilestone: string;
  actor: string | null;
  reason: string | null;
  transitionedAt: string;
  label: string;
};

/** One of the six canonical milestone buckets in the collapsed view. */
export type MilestoneSummaryDto = {
  milestone: string;
  label: string;
  visits: number;
  active: boolean;
  skipped: boolean;
  position: number;
};

/** One option on the next-possible line under the stepper. */
export type NextPossibleDto = {
  trigger: string;
  label: string;
  cond: string;
};

/** Live PR axes for the wait-state sub-status block. */
export type LinkedActivePrDto = {
  prNumber: number;
  ciStatus: 'PASSING' | 'FAILING' | 'PENDING' | 'NONE';
  draft: boolean;
  approvalCount: number;
  changesRequestedCount: number;
  pendingReviewerCount: number;
  requestedReviewers: string[];
};

/** The flow-display read model for a task. */
export type TaskTraceDto = {
  taskId: string;
  currentPhase: TaskPhaseDto | null;
  currentMilestone: string | null;
  events: TraceEventDto[];
  milestoneSummary: MilestoneSummaryDto[];
  nextPossible: NextPossibleDto[];
  linkedActivePr: LinkedActivePrDto | null;
};

/** How a queued task's branch is cut (backend BranchBase). Serialised
 *  by enum name. */
export type BranchBaseDto = 'MAIN' | 'STACKED_ON_PREVIOUS';

/** Lifecycle of one queue entry (backend QueuedTaskStatus). */
export type QueuedTaskStatusDto = 'PENDING' | 'MATERIALIZED' | 'COMPLETED' | 'DROPPED';

/** One planned future task on a thread's trunk-owned queue (backend
 *  QueuedTask). PENDING entries are editable / reorderable / droppable;
 *  MATERIALIZED entries are frozen (their plan sealed into a task). */
export type QueuedTaskDto = {
  /** 1-indexed; the run order. */
  position: number;
  title: string;
  branchBase: BranchBaseDto;
  initialPrompt: string | null;
  status: QueuedTaskStatusDto;
  /** The tasks.id this entry materialised into, or null while PENDING. */
  materializedTaskId: string | null;
  createdAt: string;
};

/** Compact active-task ref on a PR row (from {@code /prs/linked-tasks}). */
export type TaskRefDto = {
  id: string;
  /** Owning thread id — lets the UI jump to the thread without parsing
   *  it out of the task id. */
  threadId: string;
  title: string;
  phaseGroup: TaskPhaseGroupDto;
};

/** Compact active-review-pass ref on a PR row. */
export type ReviewPassRefDto = {
  passId: string;
  phase: string;
  hostKind: 'THREAD' | 'TASK_PHASE';
  round: number;
  roundCap: number;
  costSpentMilli: number;
  costCapMilli: number;
};

/** The dev-task + review links for one PR — backs the dashboard row's
 *  authorship-gated affordance. {@code linkedActiveReviewRef} is set only
 *  for THREAD-hosted (standalone) reviews. */
export type PrLinksDto = {
  linkedActiveTask: TaskRefDto | null;
  linkedCompletedTasks: TaskRefDto[];
  linkedActiveReviewRef: ReviewPassRefDto | null;
};

export type WorkUnitTaskDto = {
  id: string;
  threadId: string;
  /** 1..N within the thread; sequence in which tasks were created. */
  seq: number;
  /** PENDING | RUNNING | AWAITING | IDLE | AWAITING_REVIEW |
   *  NEEDS_ATTENTION | IN_REVIEW | COMPLETED | ERRORED. IN_REVIEW =
   *  shipped (PR open) but not yet merged. */
  status: string;
  branchName: string | null;
  worktreePath: string | null;
  baseBranch: string | null;
  /** Repo root the worktree was cut from. The thread's repo identity
   *  derives from this; readers that used to read {@code thread.workingDir}
   *  now go through here. */
  workingDir: string | null;
  prNumber: number | null;
  prState: string | null;
  ciState: string | null;
  taskType: string;
  linkedPrNumber: number | null;
  linkedIssueNumber: number | null;
  /** ISO instant the task's branch first reached the remote, or null if
   *  it hasn't been pushed yet. Set on a push approval and on the
   *  implicit push an open_pr approval performs. Drives the "on remote"
   *  task badge so a parked task no longer looks stuck. */
  pushedAt: string | null;
  /** Dev PR-collaboration lifecycle phase (V106) — one of TaskPhase:
   *  IMPLEMENTING | VALIDATING | INTERNAL_REVIEW | AWAITING_PUSH |
   *  PUSHED_AWAITING_CI | CI_FIXING | AWAITING_READY |
   *  AWAITING_REMOTE_REVIEW | ADDRESSING_COMMENTS | AGENT_RE_REVIEW |
   *  AWAITING_UPDATE_PUSH | COMPLETED | NEEDS_ATTENTION. Orthogonal to
   *  {@link status} (the agent runtime axis). */
  phase: TaskPhaseDto;
  /** Dev-agenda checklist JSON (same shape as a review pass's agenda),
   *  or null until the agent sets it. */
  agendaJson: string | null;
  /** Consecutive auto-pushes — drives the runaway-autonomy cap badge. */
  consecutiveAutoPushes: number;
  /** 'owner/repo#n' this task is permanently linked to, or null. */
  linkedPrRef: string | null;
  /** Opening-prompt accumulator for a task materialised from the queue
   *  (V110). Seeded from the queue entry; the composer on a QUEUED task
   *  appends here; the agent reads it as its first turn on the
   *  QUEUED → IMPLEMENTING promotion. Null for non-queued tasks. */
  openingPrompt: string | null;
  /** Rolled-up cost / token usage for the task. Backend Task record
   *  carries these (mirrored from the StreamEvent.TurnDone rows); the
   *  rail surfaces them in the TASK METRICS card. */
  costUsdMilli: number;
  tokensIn: number;
  tokensOut: number;
  /** ISO instant when the task row was first inserted. Surfaces in
   *  the trunk chat as the timestamp on the inline "Started Task N"
   *  launch card and as the task's runtime start. */
  createdAt: string;
  /** User-supplied rename, e.g. "Cost & tokens parser". Null means
   *  fall back to the humanised branch name. */
  name: string | null;
  /** Per-task work-model override; null means this scope inherits
   *  from the thread or workspace. */
  workModel: WorkModelDto | null;
};

/** A local pre-push inline review comment on a Task's diff. {@code source}
 *  is one of LOCAL_USER | LOCAL_AGENT | REMOTE_REVIEWER; the pre-push flow
 *  creates LOCAL_USER rows. {@code createdAt} is epoch-millis. */
export type ReviewCommentDto = {
  id: string;
  taskId: string;
  file: string;
  line: number;
  body: string;
  createdAt: number;
  source: string;
  resolved: boolean;
};

/** Conversation-index window response. Carries both the user-prompt
 *  index entries and the matching {@code thread_messages} rows in a
 *  single round-trip so the index and the agent terminal stay in
 *  lockstep — fetching one without the other would let them desync.
 *
 *  Two modes share the same shape:
 *  - {@code initial}: tail window for the page-open render.
 *  - {@code before} (with cursor): older window prepended on
 *    "↑ load earlier".
 *
 *  {@code nextCursor} is the smallest seq strictly less than the
 *  loaded window — null when the start of the thread is reached. */
export type ConvIndexPageDto = {
  threadId: string;
  totalUserMessages: number;
  entries: ConvIndexEntryDto[];
  messages: ThreadMessageDto[];
  loadedFromSeq: number | null;
  nextCursor: number | null;
};

export type ThreadMessageDto = {
  id: string;
  threadId: string;
  /** FK to the Task this row was written for; {@code null} marks a
   *  trunk planning row. Used by the task-detail conversation to
   *  scope its scrollback ({@code WHERE task_id = :task}), and by the
   *  trunk to render only the planning slice ({@code IS NULL}). */
  taskId: string | null;
  seq: number;
  /** {@code user} | {@code assistant} | {@code tool} | {@code system} */
  role: string;
  /** Free-form, evolves with new event shapes — see the SQL migration
   *  for the documented set ({@code text}, {@code thinking},
   *  {@code tool_call}, {@code tool_result}, {@code turn_done},
   *  {@code error}, {@code session_started}, {@code session_ended},
   *  {@code permission_request}, {@code permission_decision}). */
  type: string;
  /** JSON envelope; shape depends on {@code type}. The renderer parses
   *  on demand. */
  contentJson: string;
  /** Per-turn cost / token snapshot — only set on {@code turn_done}. */
  durationMs: number | null;
  tokensIn: number | null;
  tokensOut: number | null;
  costUsdMilli: number | null;
  ts: string;
};

/** Per-thread scope settings — the resolved view the trunk shows.
 *  {@code overriddenAt} is null for zero-config threads (silent
 *  inheritance); a timestamp means the thread tightens or relaxes one
 *  of the inherited values. */
export type ThreadSettingsDto = {
  threadId: string;
  maxRunningTasks: number;
  softCostUsdMilli: number;
  hardCostUsdMilli: number;
  promptAddendum: string | null;
  overriddenAt: string | null;
};

export type NewTaskRequestDto = {
  kind: ThreadKindDto;
  provider?: string;
  model: string;
  /** Owning workspace's id — required. The backend rejects the
   *  create when null/blank so the thread always lands in the right
   *  workspace's slice. */
  workspaceId: string;
  /** Optional — when omitted, the backend auto-titles from the first
   *  trunk message. Threads are never named by the user up front. */
  title?: string;
  /** Optional — a 0-Task thread has no working directory because it
   *  has no worktree. Only the materialise-task path needs this. */
  workingDir?: string;
  branchName?: string | null;
  initialPrompt?: string;
  /** Optional — pin the new thread into one or more existing groups.
   *  Each must have room (the cap is enforced server-side). */
  initialGroupIds?: string[];
  /** Free-form thread type — "DEVELOP" (default) or "FIX" today.
   *  Server-side defaults to "DEVELOP" when omitted. */
  taskType?: string;
  /** Optional GitHub PR number, scoped to the thread's repo. */
  linkedPrNumber?: number | null;
  /** Optional GitHub issue number, scoped to the thread's repo. */
  linkedIssueNumber?: number | null;
  /** Optional per-thread work-model override. Null / omitted inherits
   *  from the workspace default. */
  workModel?: WorkModelDto | null;
};

/** What a notification is about; matches the backend NotificationKind
 *  enum. AWAITING_REVIEW / NEEDS_ATTENTION are written by the (future)
 *  headless runtime; AUTO_FIX_DONE is the ship-and-continue success
 *  ping. The bell maps each to an icon + body template. */
export type NotificationKindDto =
  | 'AWAITING_REVIEW'
  | 'NEEDS_ATTENTION'
  | 'AUTO_FIX_DONE';

export type NotificationStatusDto = 'UNREAD' | 'READ' | 'RESOLVING' | 'RESOLVED' | 'DISMISSED';

/** Phase of a {@link ReviewPassDto} — mirrors the backend
 *  ReviewPhase enum. Phase 1 walks KICKOFF → INDEPENDENT →
 *  TERMINATE; the cross-review / consensus / debate / arbitrate
 *  values are reserved for the multi-reviewer commits. */
export type ReviewPhaseDto =
  | 'KICKOFF'
  | 'INDEPENDENT'
  | 'CROSS_REVIEW'
  | 'CONSENSUS'
  | 'DEBATE'
  | 'TERMINATE'
  | 'ARBITRATE'
  | 'PUBLISHED'
  | 'COMPLETED';

export type ReviewParticipantKindDto = 'LEAD' | 'REVIEWER' | 'HUMAN';
export type ReviewFindingSeverityDto = 'BLOCKER' | 'MAJOR' | 'NIT' | 'QUESTION';
export type ReviewFindingStatusDto =
  | 'REPORTED' | 'AGREED' | 'DISPUTED' | 'RESOLVED' | 'ARBITRATED' | 'DROPPED' | 'POSTED';
export type ReviewVerdictDto = 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT';

export type ReviewPassDto = {
  id: string;
  threadId: string;
  repoFullName: string;
  prNumber: number;
  /** Commit reviewed; null while the kickoff fetch is in flight. */
  headSha: string | null;
  phase: ReviewPhaseDto;
  round: number;
  roundCap: number;
  costCapMilli: number;
  costUsdMilli: number;
  /** Suggested verdict; null until the moderator finishes. */
  verdict: ReviewVerdictDto | null;
  createdAt: string;
  endedAt: string | null;
  /** The build thread this pass spawned to apply its AGREED findings
   *  ("→ Spawn build thread"), or null. One spawn per pass. */
  spawnedBuildThreadId: string | null;
  /** The lead's agenda as raw JSON; prefer the parsed
   *  {@link ReviewPassDetailDto.agenda}. Null before kickoff. */
  agendaJson: string | null;
  /** What hosts this pass: a standalone review thread (THREAD) or the dev
   *  task's own internal review (TASK_PHASE). The build handoff is hidden
   *  for TASK_PHASE — the dev task is the build. */
  hostKind: 'THREAD' | 'TASK_PHASE';
};

export type AgendaPhaseStatusDto = 'OPEN' | 'IN_PROGRESS' | 'DONE';

/** One entry on the lead's phase TODO list, rendered by the agenda
 *  widget above the panel transcript. */
export type AgendaPhaseDto = {
  id: string;
  title: string;
  status: AgendaPhaseStatusDto;
};

export type ReviewParticipantDto = {
  id: string;
  reviewPassId: string;
  kind: ReviewParticipantKindDto;
  /** Backing AI credential id for reviewer rows; null for moderator + human. */
  credentialId: string | null;
  personaLabel: string;
  model: string | null;
  color: string | null;
  createdAt: string;
};

/** Named with the {@code Panel} prefix to disambiguate from the
 *  legacy {@link ReviewMessageDto} that models GitHub PR review-
 *  thread comments — different concept entirely. */
export type ReviewPanelMessageDto = {
  id: string;
  reviewPassId: string;
  participantId: string;
  phase: ReviewPhaseDto;
  round: number;
  body: string;
  /** Participant ids this message addresses; empty array when broadcast. */
  mentions: string[];
  /** Refs quoted via #ref as "kind:id" (finding / msg); empty when none. */
  refs: string[];
  /** Shape of the message: 'prose' (plain text), 'cross_review',
   *  'consensus', or 'debate_turn'. */
  payloadKind: string;
  /** Structured envelope JSON for non-prose messages, or null. */
  payloadJson: string | null;
  costUsdMilli: number;
  createdAt: string;
};

export type ReviewFindingDto = {
  id: string;
  reviewPassId: string;
  /** File the finding anchors to; null for whole-PR notes. */
  path: string | null;
  /** Line number; null for whole-file findings or whole-PR notes. */
  line: number | null;
  severity: ReviewFindingSeverityDto;
  status: ReviewFindingStatusDto;
  body: string;
  resolution: string | null;
  postedCommentId: string | null;
  createdAt: string;
};

/** Aggregated panel state — what the controller hands back to the
 *  page in one round-trip. */
/** A review thread's reviewed-PR label (repo + number + cached title/author). */
export type ReviewThreadPrSummaryDto = {
  threadId: string;
  repoFullName: string;
  prNumber: number;
  prTitle: string | null;
  prAuthor: string | null;
  /** Panel seat labels (lead + reviewers) for the review-thread row. */
  reviewers: string[];
};

export type ReviewPassDetailDto = {
  pass: ReviewPassDto;
  /** The reviewed PR's title, resolved from the local PR cache; null
   *  when the PR isn't cached, so the header falls back to repo#number. */
  prTitle: string | null;
  /** The lead's agenda, parsed; empty for passes without one. */
  agenda: AgendaPhaseDto[];
  participants: ReviewParticipantDto[];
  messages: ReviewPanelMessageDto[];
  findings: ReviewFindingDto[];
};

/** One LLM reviewer the assign-review-task dialog renders as a
 *  panel chip. {@code configured} mirrors whether an API key is set
 *  — unconfigured rows surface as disabled chips with a hint. */
export type ReviewRosterEntryDto = {
  providerId: string;
  displayName: string;
  configured: boolean;
};

/** A pending workspace-memory edit the Haiku distiller wants the
 *  user to approve before {@code memory_md} actually changes. Mirrors
 *  the backend WorkspaceMemoryProposal record. The banner inside
 *  WorkspaceMemoryPage renders the diff and the apply/discard
 *  buttons. */
/** Workspace Settings → Behavior toggles. {@code archiveIdleAfter} is
 *  one of {@code "1h" | "1d" | "1w" | "never"}; the booleans drive
 *  the three feature toggles directly. Persistence only — each
 *  consumer reads its own key when it makes a decision. */
export type WorkspaceBehaviorDto = {
  archiveIdleAfter: string;
  autoProposeTask: boolean;
  autoPromoteDecisions: boolean;
  newTopicNudge: boolean;
};

/** Workspace Insights — counts + per-day spend series for the
 *  Insights surface. Per-repo shipped-tasks breakdown is not yet
 *  served (the work-unit Task doesn't carry repo today); the
 *  frontend continues to render placeholder data for that card. */
export type AiLedgerDto = {
  month: string;  // YYYY-MM
  totalCents: number;
  totalCalls: number;
  byProvider: { provider: string; callsCount: number; costCents: number }[];
  byTaskType: { type: string; callsCount: number; costCents: number }[];
};

export type WorkspaceInsightsDto = {
  window: string;
  activeThreads: number;
  tasksInFlight: number;
  reposInWorkspace: number;
  spendTodayMilli: number;
  spendInWindowMilli: number;
  /** Count of tasks created inside the window that carry a linked
   *  PR — the "shipped" signal. */
  tasksShippedInWindow: number;
  spendByDay: { date: string; label: string; costUsdMilli: number }[];
  /** Per-repo split of PR-linked tasks (attributed via the link ref):
   *  shipped (reached COMPLETED in window) vs still-open. */
  tasksByRepo: { repoFullName: string; tasksShipped: number; tasksOpen: number }[];
  /** Latest GitHub REST quota, or null if no call has landed since boot. */
  githubRateLimit: { remaining: number; limit: number; resetAt: string } | null;
};

export type WorkspaceMemoryProposalDto = {
  workspaceId: string;
  /** memory_md as it was when the proposal was generated — apply uses
   *  this to drift-check against the live workspace memory and refuse
   *  when a user hand-edit landed in between. */
  currentMd: string;
  /** Haiku's proposed body. Written wholesale to memory_md on apply. */
  proposedMd: string;
  summariserModel: string;
  promptTokens: number;
  completionTokens: number;
  costUsdMilli: number;
  createdAt: string;
};

/** Payload shape for an AWAITING_REVIEW notification produced by the
 *  `push` MCP tool. The agent parked here so the user can review the
 *  unified diff before any branch hits the remote. `diff` is null when
 *  git couldn't produce one (no base ref, fetch needed); `diffError`
 *  carries the human-readable reason so the pane can still let the
 *  user approve or discard. */
export type PushParkedPayload = {
  action: 'push';
  branch: string | null;
  baseBranch: string | null;
  worktreePath: string;
  diffBase?: string;
  diff?: string | null;
  diffError?: string;
  source: string;
};

/** Payload shape for an AWAITING_REVIEW notification produced by the
 *  `post_comment` MCP tool. The parked body is editable in the review
 *  pane so the user can tweak copy before it posts to GitHub. */
export type PostCommentParkedPayload = {
  action: 'post_comment';
  body: string;
  pr: { owner: string; repo: string; number: number };
  source: string;
};

/** Locally resolved review-ready proposal. Approving acknowledges the
 *  work as reviewed; it does not perform a remote publish. */
export type RequestReviewParkedPayload = {
  action: 'request_review';
  summary: string;
  draftReply?: string;
  branch?: string | null;
  baseBranch?: string | null;
  worktreePath?: string;
  diffBase?: string;
  diff?: string | null;
  diffError?: string;
  source: string;
};

/** Proposal for the user's Next action. Remote push / PR creation and
 *  next-task creation occur only after approval. */
export type NextTaskParkedPayload = {
  action: 'next_task';
  branch: string | null;
  baseBranch: string | null;
  worktreePath: string;
  diffBase?: string;
  diff?: string | null;
  diffError?: string;
  nextTitle: string;
  baseMode: 'main' | 'stacked';
  source: string;
};

/** Proposal for the user's terminal Ship action. The current branch
 *  is published and its task is closed only after approval. */
export type ShipTaskParkedPayload = {
  action: 'ship_task';
  branch: string | null;
  baseBranch: string | null;
  worktreePath: string;
  diffBase?: string;
  diff?: string | null;
  diffError?: string;
  nextTitle: string;
  baseMode: 'main' | 'stacked';
  /** The agent's drafted PR title/body for this ship. Reviewable and
   *  editable on the task code page before approval; persisted back via
   *  {@link Bridge.setShipDescription}. */
  prTitle?: string;
  prBody?: string;
  source: string;
};

/** The remaining backend-known publish actions that PublishService
 *  can resolve. The picker renders a generic review card for them so
 *  they don't get stranded in the bell with no Approve / Discard
 *  affordance — each carries enough metadata (a PR or issue ref, an
 *  optional editable body) to read what the agent intended to do. */
export type GenericPublishAction =
  | 'reply_review_thread'
  | 'approve_pr'
  | 'merge_pr'
  | 'create_review_comment'
  | 'update_pr_body'
  | 'request_reviewer'
  | 'comment_on_issue'
  | 'set_issue_state'
  | 'open_pr'
  | 'publish_review';

export type GenericParkedPayload = {
  action: GenericPublishAction;
  /** Optional editable copy — set for actions like comment_on_issue /
   *  update_pr_body / create_review_comment / reply_review_thread that
   *  let the user tweak the body before posting. */
  body?: string | null;
  /** PR or issue context, or a bare repo ref (used by open_pr which
   *  doesn't have a PR number yet). The fields are loose-typed because
   *  each action populates a different subset; the gate card renders
   *  whatever is present. */
  pr?: { owner: string; repo: string; number: number };
  issue?: { owner: string; repo: string; number: number };
  repo?: { owner: string; repo: string };
  /** open_pr metadata. Populated only when action === 'open_pr'. */
  title?: string;
  head?: string;
  base?: string;
  /** create_review_comment anchor. Populated only when
   *  action === 'create_review_comment' so the reviewer can see which
   *  file/line/side the inline comment lands on before approving. */
  filePath?: string;
  line?: number;
  side?: string;
  /** Free-form one-liner the backend wrote for human consumption. */
  summary?: string;
  source: string;
};

export type ParkedPublishPayload =
  | PushParkedPayload
  | PostCommentParkedPayload
  | RequestReviewParkedPayload
  | NextTaskParkedPayload
  | ShipTaskParkedPayload
  | GenericParkedPayload;

/** Every action the publish gate knows how to resolve via the
 *  `/notifications/{id}/approve` and `/discard` endpoints. Used by
 *  the notification-center allow-list. */
export const PUBLISH_GATE_ACTIONS = [
  'push',
  'post_comment',
  'request_review',
  'next_task',
  'ship_task',
  'reply_review_thread',
  'approve_pr',
  'merge_pr',
  'create_review_comment',
  'update_pr_body',
  'request_reviewer',
  'comment_on_issue',
  'set_issue_state',
  'open_pr',
  'publish_review',
] as const;
export type PublishGateAction = typeof PUBLISH_GATE_ACTIONS[number];

/** Server response from POST /api/notifications/{id}/approve and
 *  /discard. Mirrors PublishService.PublishResult on the backend.
 *  {@code resolution} is what the frontend dispatches on for gate
 *  recovery and toast colour / inline copy. */
export type PublishResultDto = {
  ok: boolean;
  resolution: 'approved' | 'discarded' | 'failed' | 'interrupted' | 'recovered';
  message: string;
  action: string;
};

export type NotificationDto = {
  id: string;
  kind: NotificationKindDto;
  /** Thread the event came from; null when the notification isn't
   *  scoped to a specific conversation. */
  threadId: string | null;
  /** Task the event is about; null for thread-level events. */
  taskId: string | null;
  status: NotificationStatusDto;
  /** Free-form JSON1 string — the renderer dispatches on {@code kind}
   *  to know what fields to look for inside (PR number, branch, etc.). */
  payloadJson: string;
  createdAt: string;
  readAt: string | null;
};

/** Row in the Settings → Concepts catalog. Read-only; concepts
 *  are defined elsewhere (in code via @Concept, or in the brain
 *  glossary). */
export type ConceptRowDto = {
  name: string;
  kind: 'NOUN' | 'STATE' | 'FILTER' | 'VERB';
  definition: string;
  aka: string[];
  sources: string[];
  relatedTools: string[];
  relatedConcepts: string[];
  scope: 'APP' | 'REPO' | 'WORKSPACE' | 'USER';
};

/** Prompt-context inspector wire row. Read-only view of what
 *  would be in the agent's prompt; never used to send anything. */
export type AssembledContextDto = {
  scope: 'TRUNK' | 'TASK';
  scopeId: string;
  meta: {
    model: string;
    providerShape: string;
    assembledAt: string;
    totalTokens: number;
    cacheHitPredicted: boolean;
  };
  sections: Array<{
    kind: 'TOOLS' | 'ROLE' | 'BRAIN' | 'CONCEPT_PREAMBLE'
        | 'SKILL_MANIFEST' | 'MEMORY' | 'HISTORY' | 'NEW_TURN';
    label: string;
    body: string;
    tokenCount: number;
    sources: Array<{
      kind: string;
      label: string;
      href: string | null;
      byteRange: string | null;
    }>;
  }>;
  wire: {
    tools: string[];
    systemBlocks: string[];
    historyMessages: string[];
    newTurn: string;
  };
};

/** One typed memory item the agent's recall_memory tool surfaces.
 *  Mirror of MemoryItem on the backend. v1 carries only the fields
 *  the proposal banner needs to render. */
export type MemoryItemDto = {
  id: number;
  scopeKind: 'WORKSPACE' | 'THREAD';
  scopeId: string;
  kind: 'DECISION' | 'BLOCKER' | 'CONVENTION'
      | 'FOCUS_SHIFT' | 'OPEN_QUESTION' | 'RECURRING_PATTERN';
  text: string;
  sources: Array<{
    threadId?: string;
    taskId?: string;
    prRef?: string;
    messageStart?: number;
    messageEnd?: number;
  }>;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  tags: string[];
  supersededBy: number | null;
  resolvedAt: string | null;
  proposedAt: string;
  appliedAt: string | null;
  source: 'DISTILL' | 'INLINE' | 'USER_TYPED';
};

/** Wire row from the backend Saved Views endpoint. */
export type SavedViewDto = {
  name: string;
  kind: string;
  definition: string;
  aka: string[];
  criteriaJson: string | null;
  createdAtMs: number;
  updatedAtMs: number;
};

/** Request body for create/update of a saved view. */
export type SavedViewBodyDto = {
  name: string;
  kind?: string;
  definition: string;
  aka?: string[];
  criteriaJson?: string | null;
};

export type Bridge = {
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  clearPat: () => Promise<boolean>;
  fetchHello: () => Promise<string>;
  fetchPrs: () => Promise<PullRequestDto[]>;
  /** Server-side named PR filter — urgent, awaiting_me, stale,
   *  blocked, mine_open. The dashboard's Urgent tab calls this with
   *  name="urgent" so the predicate is defined exactly once on the
   *  backend (UrgentPrFilter) instead of being mirrored in TS. */
  fetchPrsByFilter: (name: string) => Promise<PullRequestDto[]>;
  /** On-demand single-PR lookup straight from GitHub by repo + number,
   *  bypassing the cached dashboard list. Backs the assign-review
   *  dialog's "type a number / paste a URL" path. Rejects (GitHub 404)
   *  when no such PR exists. */
  lookupPr: (repo: string, number: number) => Promise<PullRequestDto>;
  getPrLinks: (repo: string, number: number) => Promise<PrLinksDto>;
  /** Trunk task queue (V110). Append a planned task; returns the new
   *  entry. branchBase is 'MAIN' or 'STACKED_ON_PREVIOUS'. */
  queueAdd: (
    threadId: string, title: string, branchBase: BranchBaseDto, initialPrompt: string | null,
  ) => Promise<QueuedTaskDto>;
  /** Reorder the PENDING queue entries — pass the desired permutation of
   *  their current positions. Returns the resulting queue. */
  queueReorder: (threadId: string, positions: number[]) => Promise<QueuedTaskDto[]>;
  /** Edit a PENDING queue entry's plan (title / branch base / opening
   *  prompt). MATERIALIZED entries reject — their plan is sealed. */
  queueEdit: (
    threadId: string, position: number, title: string, branchBase: BranchBaseDto,
    initialPrompt: string | null,
  ) => Promise<QueuedTaskDto>;
  /** Drop a PENDING queue entry by position (flips it to DROPPED). */
  queueDrop: (threadId: string, position: number) => Promise<QueuedTaskDto>;
  /** Append to (or replace) a QUEUED task's opening prompt — the agent's
   *  first-turn input once a slot opens. 422 unless the task is QUEUED. */
  setOpeningPrompt: (
    threadId: string, taskId: string, text: string, mode: 'append' | 'replace',
  ) => Promise<WorkUnitTaskDto>;
  /** Saved Views — user-authored concepts (scope=USER) visible
   *  alongside the workspace and APP-scoped seeds. */
  listSavedViews: () => Promise<SavedViewDto[]>;
  createSavedView: (body: SavedViewBodyDto) => Promise<SavedViewDto>;
  deleteSavedView: (name: string) => Promise<void>;
  /** Typed memory items pending in the proposal banner for a
   *  workspace. The blob proposal at GET /memory/proposal stays the
   *  canonical surface; these are the structured shape the agent
   *  reads via recall_memory / lookup_memory. */
  listPendingMemoryItems: (workspaceId: string) => Promise<MemoryItemDto[]>;
  applyMemoryItem: (workspaceId: string, itemId: number) => Promise<MemoryItemDto>;
  discardMemoryItem: (workspaceId: string, itemId: number) => Promise<void>;
  /** Read-only assembled prompt context for the thread's trunk
   *  turn. Always hits the dryRun endpoint server-side. */
  getThreadContext: (threadId: string) => Promise<AssembledContextDto>;
  /** Read-only assembled prompt context for one task. */
  getTaskContext: (threadId: string, taskId: string) => Promise<AssembledContextDto>;
  /** Settings → Concepts catalog. Read-only registry view. */
  listConcepts: (query: { kind?: string; query?: string }) => Promise<ConceptRowDto[]>;
  /** Live GitHub search for the user's full closed-PR history (merged
   *  + closed-without-merge). Used by the merge-history page — pages
   *  through GitHub's `is:closed author:@me sort:closed-desc` results. */
  fetchPrHistory: (page: number, perPage?: number) => Promise<PullRequestHistoryPageDto>;
  /** Aggregated KPIs for the PR-review Analytics page. Pure local read
   *  — no PAT, no GitHub call. {@code tz} is an IANA zone id; the
   *  renderer passes its own so the daily-bars and heatmap bucket in
   *  the user's local time. */
  fetchPrAnalytics: (scope: PrAnalyticsScope, tz?: string) => Promise<PrAnalyticsSummaryDto>;
  /** "What did I author" companion of {@link fetchPrAnalytics}.
   *  Same local-only contract, separate endpoint so the page can
   *  switch views without refetching the heavy reviews aggregation. */
  fetchMyActivity: (scope: PrAnalyticsScope, tz?: string) => Promise<MyActivitySummaryDto>;
  fetchPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  /** Force-refresh one PR's detail. Probes GitHub with the cached
   *  ETag; on 304 returns the backend's L2 snapshot, on 200 refetches
   *  the full detail. Passing {@code maxAgeSeconds > 0} skips the
   *  ETag probe entirely when our last probe is younger than that —
   *  used by the 10s polling tick so cross-tab opens at most one
   *  probe per cap. The manual ↻ button passes 0 (or omits) to
   *  always probe. */
  refreshPullRequestDetail: (repo: string, number: number, maxAgeSeconds?: number) => Promise<PullRequestDetailDto>;
  /** Lightweight CI snapshot for the focus-driven detail-page poll. */
  fetchPrCi: (repo: string, number: number) => Promise<PrCiSnapshotDto>;
  /** Enumerates the file paths that would conflict between a PR's
   *  head and its base. Routes through the local clone (git merge-tree)
   *  — `available: false` means we couldn't compute the list (no local
   *  clone, fetch failed, etc.) and the renderer should fall back to
   *  the github.com conflict-editor link. */
  fetchPrConflictPaths: (
    owner: string,
    repo: string,
    prNumber: number,
    baseRef: string,
  ) => Promise<MergeConflictPathsDto>;
  /** Raw Actions log text for one check-run. Empty string when GitHub
   *  doesn't expose a log (external CI / expired / scope). Lazy-loaded
   *  by the merge bar's failure cards on user click. */
  fetchCheckLog: (repo: string, checkRunId: number) => Promise<{ log: string }>;
  /** Toggle a PR between draft and ready-for-review. true = convert
   *  to draft, false = mark as ready. Routes through GitHub GraphQL. */
  setPrDraft: (repo: string, number: number, draft: boolean) => Promise<{ result: string }>;
  /** Rename a PR on GitHub. Returns the updated {number, title, updatedAt}.
   *  Rejects (throws) on validation / permission / GitHub failure. */
  updatePrTitle: (repo: string, number: number, title: string) => Promise<{ number: number; title: string; updatedAt: string }>;
  getTaskTrace: (taskId: string) => Promise<TaskTraceDto>;
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
   *  historical "rebase" default for compatibility. When the target
   *  branch has merge queue enabled the backend dispatches to
   *  GraphQL {@code enqueuePullRequest} instead; the resolved value
   *  carries {@code queued: true} (and {@code merged: false}) so the
   *  caller can roll back any optimistic "merged" state and show a
   *  queue indicator. */
  mergePr: (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge') => Promise<{ merged: boolean; message: string; queued: boolean }>;
  /** Re-run the PR's failed CI jobs (GitHub "re-run failed jobs").
   *  Resolves with how many workflow runs were re-triggered. */
  rerunChecks: (repo: string, number: number) => Promise<{ rerunCount: number }>;
  /** Push an empty commit to the PR's branch to re-trigger push-driven CI.
   *  {@code triggered} is false (with a reason) when there's no local task
   *  worktree to commit on. */
  triggerCi: (repo: string, number: number) => Promise<{ triggered: boolean; reason: string | null }>;
  /** Enables GitHub's auto-merge — the PR merges automatically once
   *  required checks pass and approvals are in place. Mirrors
   *  github.com's "Merge when ready" button. Goes through a GraphQL
   *  mutation; rejected by GitHub if the repo doesn't allow auto-merge. */
  enableAutoMerge: (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge') => Promise<{ result: string }>;
  /** Cancels a previously-enabled auto-merge. Idempotent on GitHub's
   *  side (no-op when auto-merge isn't enabled), so callers don't have
   *  to track local state. */
  disableAutoMerge: (prId: number, repo: string, number: number) => Promise<{ result: string }>;
  /** Removes a PR from its repo's merge queue. Mirrors the "Remove
   *  from queue" button on github.com's merge bar. No-op when the PR
   *  isn't currently in a queue. */
  dequeuePr: (prId: number, repo: string, number: number) => Promise<{ result: string }>;
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
  /** Deletes a top-level issue / PR comment. Allowed for the comment's
   *  author or a user with repo write access; backend rejects otherwise. */
  deleteIssueComment: (repo: string, commentId: number) => Promise<void>;
  /** Deletes a per-line review comment. Same permission rules as
   *  {@link deleteIssueComment}. */
  deleteReviewComment: (repo: string, commentId: number) => Promise<void>;
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
  /** Commits authored by {@code login} on one UTC calendar day. Powers
   *  the cube-click popover on the heatmap. Date is {@code yyyy-MM-dd}. */
  getUserCommitsOnDate: (login: string, date: string) => Promise<UserCommitDto[]>;
  getRepoPulls: (owner: string, repo: string) => Promise<PullRequestDto[]>;
  /** Single-PR fetch — used by the deep-link fallback when a PR isn't in
   *  the (capped) repo list response. */
  getRepoPull: (owner: string, repo: string, number: number) => Promise<PullRequestDto>;
  /** Title-search PRs in a repo across all states. Powers the
   *  create-thread linker's text-search fallback for old/closed PRs that
   *  aren't in the 30 most-recent open PRs returned by getRepoPulls. */
  searchRepoPulls: (owner: string, repo: string, query: string) => Promise<PullRequestDto[]>;
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
  /** Flips the viewer's subscription on the issue. {@code subscribed=true}
   *  subscribes (PUT); {@code false} returns to GitHub's default state
   *  (DELETE). */
  setIssueSubscription: (owner: string, repo: string, number: number, subscribed: boolean) => Promise<{ result: string }>;
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
  /** Records a visit to a tracked surface for the footprints trail.
   *  Fire-and-forget — callers don't await it and navigation never
   *  blocks on the write. */
  recordSurfaceVisit: (visit: SurfaceVisitInput) => Promise<void>;
  /** The footprints trail for a calendar day (defaults to today when
   *  {@code date} is omitted). {@code date} is ISO YYYY-MM-DD. */
  getFootprints: (date?: string) => Promise<FootprintsTrailDto>;
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
  /** Connects a Gmail account via IMAP + app password. Validates the
   *  credentials by opening an imaps session before persisting; throws
   *  on auth failure. The only Gmail-connect path now — the OAuth flow
   *  was removed in favour of a single, predictable local-only path. */
  connectGmailImap: (email: string, appPassword: string) => Promise<{ email: string }>;
  /** All currently connected Gmail accounts. {@code authMode} is
   *  always {@code 'IMAP'} today; the field is kept on the wire so
   *  whatever future modes might be added later (e.g. shipped-OAuth
   *  client) can slot in without a breaking change. */
  listGmailAccounts: () => Promise<Array<{ email: string; authMode: 'IMAP' }>>;
  /** Drops the stored credential for a single Gmail account.
   *  Idempotent. */
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
  /** Combined "open and dismiss" — removes both INBOX and UNREAD in
   *  one Gmail call. Fires automatically when the user opens an
   *  unread thread, matching the "reading is archiving" gesture. */
  readAndArchiveEmailThread: (account: string, id: string) => Promise<void>;
  /** Re-adds INBOX (and clears UNREAD) — reverses an auto-archive.
   *  Driven by the "Keep in inbox" button on the detail pane. */
  keepEmailThreadInInbox: (account: string, id: string) => Promise<void>;
  /** Sends a plain-text reply to the latest message in the thread.
   *  Backend handles MIME assembly, threading headers, and the
   *  post-send incremental sync that pulls the sent message into
   *  the local mirror. */
  replyToEmailThread: (account: string, id: string, body: string) => Promise<void>;
  /** Adds the sender's address to the per-account mute list — future
   *  inbox listings filter the sender's threads out. Local-only; does
   *  not propagate to gmail.com. Accepts either a raw address or a
   *  full {@code "Name <addr>"} header; the backend normalises. */
  muteEmailSender: (account: string, sender: string) => Promise<void>;
  unmuteEmailSender: (account: string, sender: string) => Promise<void>;
  listMutedEmailSenders: (account: string) => Promise<string[]>;
  /** Per-account subject-matching tag rules. Alphabetised on return.
   *  Tags drive the left-nav classification of inbox threads. */
  listEmailTags: (account: string) => Promise<EmailTagDto[]>;
  /** Create a new tag. UUID minted server-side. */
  createEmailTag: (
    account: string,
    input: { name: string; subjectContains: string; action: EmailTagAction },
  ) => Promise<EmailTagDto>;
  /** Update an existing tag by id. */
  updateEmailTag: (
    id: string,
    input: { name: string; subjectContains: string; action: EmailTagAction },
  ) => Promise<EmailTagDto>;
  /** Delete a tag by id. Existing archive-log entries linked to the
   *  tag are preserved (they still describe the historical action). */
  deleteEmailTag: (id: string) => Promise<void>;
  /** Audit-log entries for the Archived left-nav view. Newest first. */
  listArchivedEmailThreads: (account: string) => Promise<EmailTagArchiveEntryDto[]>;
  // Credentials vault
  listCredentials: (type?: CredentialType) => Promise<CredentialDto[]>;
  upsertCredential: (req: UpsertCredentialRequest) => Promise<CredentialDto>;
  deleteCredential: (type: CredentialType, name: string, instanceName?: string) => Promise<void>;
  /** Verify a stored credential against its upstream by firing a
   *  lightweight probe. Powers the Settings → AI review → "Test" button. */
  testCredential: (type: CredentialType, name: string, instanceName: string) => Promise<CredentialTestResult>;
  /** Promote (type, name, instanceName) to the ★ default for its
   *  group. The backend clears the previous default in the same
   *  transaction; unnamed lookups (PatResolver, AI key) follow it. */
  setDefaultCredential: (type: CredentialType, name: string, instanceName: string) => Promise<CredentialDto>;
  // AI review
  listAiProviders: () => Promise<AiProviderInfo[]>;
  getAiSettings: () => Promise<AiSettingsDto>;
  setAiSettings: (provider: string, model: string | null) => Promise<AiSettingsDto>;
  /** Catalog × credentials × CLI detection — the option tree the
   *  work-model picker walks. Re-fetched when the picker opens so
   *  newly-added credentials / freshly-installed CLI agents show
   *  up without an app restart. */
  getWorkModelOptions: () => Promise<WorkModelOptionsDto>;
  /** Forces the CLI detector to drop its memo and re-probe every
   *  binary. Backs the picker's "refresh" affordance. */
  refreshWorkModelOptions: () => Promise<WorkModelOptionsDto>;
  /** Set (or clear) the workspace's default work model. Pass null
   *  to remove the override, after which the resolver falls back
   *  to the global default. Returns the updated workspace. */
  setWorkspaceWorkModel: (workspaceId: string, model: WorkModelDto | null) => Promise<WorkspaceDto>;
  /** Resolve the effective work model for a thread (cascade: thread →
   *  workspace → global default). */
  getThreadWorkModel: (threadId: string) => Promise<ResolvedWorkModelDto>;
  /** Set (or clear) the thread's work-model override and return the
   *  resolved outcome — the caller does not need a follow-up get. */
  setThreadWorkModel: (threadId: string, model: WorkModelDto | null) => Promise<ResolvedWorkModelDto>;
  /** Resolve the effective work model for a task (cascade: task →
   *  thread → workspace → global default). */
  getTaskWorkModel: (threadId: string, taskId: string) => Promise<ResolvedWorkModelDto>;
  /** Set (or clear) the task's work-model override and return the
   *  resolved outcome. */
  setTaskWorkModel: (
    threadId: string,
    taskId: string,
    model: WorkModelDto | null,
  ) => Promise<ResolvedWorkModelDto>;
  /** Local ds4 inference server lifecycle. Status is the cheap poll
   *  every page surface (widget + Settings) shares; the rest drive
   *  the management actions. */
  getDs4Status: () => Promise<Ds4StatusDto>;
  startDs4: () => Promise<Ds4StatusDto>;
  stopDs4: (confirm?: boolean) => Promise<Ds4StopResponseDto>;
  restartDs4: () => Promise<Ds4StatusDto>;
  getDs4Config: () => Promise<Ds4ConfigDto>;
  /** Save the apply-on-restart config. Pass {@code restart=true} to
   *  trigger a Stop+Start in one call. */
  setDs4Config: (config: Ds4ConfigDto, restart?: boolean) => Promise<Ds4ConfigResponseDto>;
  getDs4Metrics: () => Promise<Ds4MetricsDto>;
  /** Kick off the multi-step installer: clone + build (or validate
   *  an existing checkout) → download model if missing → stamp
   *  binary_path. The lifecycle service is auto-configured to point
   *  at {@code <repoDir>/ds4-server} on success. */
  installDs4: (req: Ds4InstallRequestDto) => Promise<Ds4InstallStatusDto>;
  getDs4InstallStatus: () => Promise<Ds4InstallStatusDto>;
  getDs4Logs: (limit?: number) => Promise<string[]>;
  /** List every configured skill, alphabetised by name. The Settings
   *  → Skills page slices client-side on scope / roleTag. */
  listSkills: () => Promise<SkillDto[]>;
  createSkill: (input: SkillInput) => Promise<SkillDto>;
  updateSkill: (id: number, input: SkillInput) => Promise<SkillDto>;
  deleteSkill: (id: number) => Promise<void>;
  /** Flip the per-skill enable toggle. The backend filters review-
   *  time consumption by the flag; the row stays in the vault when
   *  disabled so it can be flipped back on later. */
  setSkillEnabled: (id: number, enabled: boolean) => Promise<SkillDto>;
  /** Ask the active LLM provider to draft a skill from a short user
   *  prompt. Returns name + trigger + body for the modal to render
   *  pre-filled — the user confirms / edits before saving. */
  draftSkill: (prompt: string, scope: string) => Promise<SkillDraftDto>;
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
  /** All threads across every status, newest-updated first; the page
   *  groups by status itself. Pass {@code groupId} to restrict to a
   *  single group (drives the group detail view). */
  /** List threads. Backward-compatible: a plain string is treated
   *  as a groupId, or an options object can specify groupId +
   *  workspaceId. When workspaceId is passed, only threads pinned
   *  to that workspace are returned. */
  listTasks: (opts?: string | { groupId?: string; workspaceId?: string }) => Promise<ThreadDto[]>;
  /** Queued/running turns across all threads, oldest first. Lets list
   *  and group pages show scheduler pressure without N+1 reads. */
  listActiveTaskTurns: () => Promise<ThreadTurnDto[]>;
  /** Create + start one thread. Returns the persisted row with the
   *  agent's session id if the first turn already populated it. */
  createTask: (request: NewTaskRequestDto) => Promise<ThreadDto>;
  /** User-defined groups in display order. */
  listTaskGroups: () => Promise<ThreadGroupDto[]>;
  /** Full thread ↔ group membership snapshot. Threads and groups are
   *  many-to-many — render the page once you have both lists and
   *  this index. */
  listTaskGroupMemberships: () => Promise<ThreadGroupMembershipDto[]>;
  /** Insert one group along with its initial members
   *  ({@code initialTaskIds} must contain ≥ 1 and ≤ 4 ids). */
  createTaskGroup: (request: NewTaskGroupRequestDto) => Promise<ThreadGroupDto>;
  /** Partial update — null/blank fields keep the current value. */
  updateTaskGroup: (id: string, patch: ThreadGroupPatchDto) => Promise<ThreadGroupDto>;
  /** Drop a group. The membership rows cascade away in the schema;
   *  the threads themselves survive — they simply leave the group. */
  deleteTaskGroup: (id: string) => Promise<void>;
  /** Add an existing thread to an existing group. Rejected when the
   *  group is at the 4-member cap; idempotent on existing members. */
  addTaskToGroup: (groupId: string, threadId: string) => Promise<void>;
  /** Remove a thread from a group. Rejected when the thread is the
   *  group's only remaining member — callers must
   *  {@link #deleteTaskGroup deleteTaskGroup} instead. */
  removeTaskFromGroup: (groupId: string, threadId: string) => Promise<void>;
  /** Single thread by id; null when no row matches. */
  getTask: (id: string) => Promise<ThreadDto | null>;
  /** Persisted conversation log, oldest first by {@code seq}. The
   *  detail page polls this while the thread is live. */
  getTaskMessages: (id: string) => Promise<ThreadMessageDto[]>;
  /** One window of the conversation index — user-prompt entries
   *  plus the matching messages, fetched together so the floating
   *  index panel and the agent terminal can't drift. Pass no
   *  cursor for the initial tail window; pass the smallest loaded
   *  seq with {@code direction: 'before'} to backfill on
   *  "↑ load earlier". */
  getTaskIndex: (
    id: string,
    opts?: { cursor?: number; limit?: number; direction?: 'initial' | 'before' },
  ) => Promise<ConvIndexPageDto>;
  /** Work-unit tasks for a thread, oldest seq first. Drives the Tasks
   *  grouping in the Checkpoints rail and (in time) the Tasks-in-thread
   *  list. The Task model is described in the work-unit design note;
   *  not to be confused with the legacy "task = thread" alias still
   *  in place on most other bridge methods. */
  listTasksForThread: (threadId: string) => Promise<WorkUnitTaskDto[]>;
  /** Take control of a thread away from any in-flight headless run.
   *  Drives the "Jump in" button on parked notifications: interrupts
   *  the live session, releases the active task's worktree lease,
   *  and marks parked notifications for the thread as read. Returns
   *  the refreshed thread row. */
  jumpInThread: (threadId: string) => Promise<ThreadDto>;
  /** Top-level Workspaces landing grid feed. One card per workspace
   *  with all the aggregates the landing renders (counts, today's
   *  spend, memory summary). Read-only. */
  listWorkspaces: () => Promise<WorkspaceCardDto[]>;
  /** Fetch one workspace by id. Null when no row matches — drives
   *  the shell's title + rail brand so a workspace switch updates
   *  the visible name. */
  getWorkspace: (workspaceId: string) => Promise<WorkspaceDto | null>;
  /** Rename a workspace. The display name surfaces on the landing
   *  card and the rail; the id is stable. Trimmed server-side. */
  renameWorkspace: (workspaceId: string, name: string) => Promise<WorkspaceDto>;
  /** Drop a workspace entirely. Threads pointing at it are left
   *  orphaned — the UI warns the user before calling this. */
  deleteWorkspace: (workspaceId: string) => Promise<void>;
  /** Create a new workspace. The optional {@code promptContext} is
   *  appended to {@code memoryMd} so every thread reads it first;
   *  {@code repoFullNames} pins the picked watched repos. {@code slug}
   *  is the immutable id segment (without the {@code ws-} prefix); the
   *  dialog derives it live from {@code name} and lets the user
   *  override before commit, then locks. Omit to let the backend
   *  derive from {@code name}. */
  createWorkspace: (req: {
    name: string;
    slug?: string;
    isScratch?: boolean;
    promptContext?: string;
    repoFullNames?: string[];
  }) => Promise<WorkspaceDto>;
  /** List the repos attached to a workspace. Used by the watched-repos
   *  settings page to read each repo's auto-fix flag — the data lives
   *  on workspace_repos, not on the watched-repos table itself. */
  listWorkspaceRepos: (workspaceId: string) => Promise<WorkspaceRepoDto[]>;
  /** Fetch a workspace's persistent {@code memory_md} blob. Empty
   *  string before the first distillation pass runs. */
  getWorkspaceMemory: (workspaceId: string) => Promise<{ memoryMd: string }>;
  /** Replace a workspace's {@code memory_md} wholesale. Caller is
   *  responsible for keeping it inside the soft 8 000 char target;
   *  the backend hard-caps at 32 000 chars and 413s past that. */
  setWorkspaceMemory: (
    workspaceId: string, memoryMd: string,
  ) => Promise<WorkspaceDto>;
  /** Force a fresh Haiku distillation pass. The result lands as a
   *  pending proposal (not a direct edit to memory_md) — the user
   *  confirms via approveWorkspaceMemoryProposal before anything
   *  changes. Resolves to the upserted proposal, or null when nothing
   *  was queued (no Overalls yet, scratch workspace, or proposed body
   *  identical to current memory). */
  distillWorkspaceMemory: (workspaceId: string) => Promise<WorkspaceMemoryProposalDto | null>;
  /** Read the pending memory proposal for a workspace, or null when
   *  there isn't one. The banner in WorkspaceMemoryPage polls this. */
  getWorkspaceMemoryProposal: (workspaceId: string) => Promise<WorkspaceMemoryProposalDto | null>;
  /** Apply the pending proposal: write its body back to memory_md and
   *  clear the row. Rejects (409) when memory_md has drifted since
   *  the proposal was queued. */
  applyWorkspaceMemoryProposal: (workspaceId: string) => Promise<WorkspaceDto>;
  /** Drop the pending proposal without writing anything. */
  discardWorkspaceMemoryProposal: (workspaceId: string) => Promise<void>;

  /** Kick off a new single-reviewer review pass against {@code prNumber}
   *  in {@code repoFullName}. Creates a {@code flow=REVIEW} thread,
   *  runs the active LLM reviewer synchronously, and returns the
   *  populated panel state. */
  startReview: (
    repoFullName: string,
    prNumber: number,
    opts?: {
      panelProviderIds?: string[];
      roundCap?: number;
      costCapMilli?: number;
      independentFirst?: boolean;
      /** Workspace the review thread is created in, so it surfaces in
       *  that workspace's thread list. */
      workspaceId?: string;
      /** Per-run lead override — a providerId. Null/omitted falls back
       *  to the first panel member. The lead runs consensus + the
       *  convergence moderator. */
      leadId?: string | null;
      /** Explicit panel composition — one entry per reviewer seat, each
       *  pairing a model with an optional review-skill voice or typed
       *  prompt. When set, this is the authoritative panel. Exactly one
       *  seat should be flagged lead. */
      seats?: {
        providerId: string;
        customPrompt?: string | null;
        /** A review-usage skill row used as this seat's voice; mutually
         *  exclusive with customPrompt. */
        roleSkillId?: number | null;
        lead?: boolean;
      }[];
    },
  ) => Promise<ReviewPassDetailDto>;
  /** List configured LLM reviewers (and unconfigured ones the
   *  assign-review-task dialog surfaces as disabled chips). */
  listReviewRoster: () => Promise<ReviewRosterEntryDto[]>;
  /** Read a review pass by id with the full transcript + findings. */
  getReviewPass: (passId: string) => Promise<ReviewPassDetailDto | null>;
  /** Read the latest pass on a review thread — the URL the panel UI
   *  lives on uses the thread id, this resolves the pass for it. */
  getReviewPassByThread: (threadId: string) => Promise<ReviewPassDetailDto | null>;
  /** Read the active review pass for a PR (by {@code owner/repo} + number)
   *  so the code-diff page can overlay its AGREED findings at their line
   *  positions. Null when the PR has no review pass. */
  getReviewPassForPr: (repo: string, number: number) => Promise<ReviewPassDetailDto | null>;
  /** Read the scheduled-reviews opt-in toggle. */
  getScheduledReviewSettings: () => Promise<{ enabled: boolean }>;
  /** Flip the scheduled-reviews opt-in toggle. The backend reads
   *  it each tick so a flip takes effect on the next sweep without
   *  a restart. */
  setScheduledReviewSettings: (enabled: boolean) => Promise<{ enabled: boolean }>;
  /** Read the Workspace Settings → Behavior toggles. Persistence
   *  only — enforcement lands with each consumer (auto-archive
   *  sweeper, propose-task hook, etc.). */
  getWorkspaceBehavior: () => Promise<WorkspaceBehaviorDto>;
  setWorkspaceBehavior: (settings: WorkspaceBehaviorDto) => Promise<WorkspaceBehaviorDto>;
  /** Workspace Insights aggregation — pulls active-thread + tasks-in-
   *  flight counts, today's spend, the window-wide spend total, and a
   *  per-day spend series for the chart. {@code window} is one of
   *  {@code "24h" | "7d" | "30d"}; the backend defaults to {@code 7d}
   *  on unknown values. */
  getWorkspaceInsights: (workspaceId: string, window: string) => Promise<WorkspaceInsightsDto>;
  /** Monthly AI usage ledger — total spend/calls + per-provider and
   *  per-task-type breakdowns. Month is YYYY-MM ('' = current month). */
  getAiLedger: (month: string) => Promise<AiLedgerDto>;
  /** Workspace-level reviewer persona — a user-editable nudge that
   *  prepends to every panel reviewer's skill-context at request
   *  time. Empty string when unset. */
  getReviewPersona: () => Promise<{ persona: string }>;
  setReviewPersona: (persona: string) => Promise<{ persona: string }>;
  /** Resolve one DISPUTED finding via the arbitration ballot.
   *  {@code resolution} = "include" flips it to ARBITRATED;
   *  "drop" flips it to DROPPED. When no DISPUTED findings remain
   *  the pass transitions out of ARBITRATE and the publish form
   *  unlocks. */
  arbitrateReviewFinding: (
    passId: string,
    findingId: string,
    resolution: 'include' | 'drop',
  ) => Promise<ReviewPassDetailDto>;
  /** Edit a finding's comment body before it publishes to GitHub. Returns
   *  the updated pass detail. */
  editReviewFinding: (
    passId: string,
    findingId: string,
    comment: string,
  ) => Promise<ReviewPassDetailDto>;
  /** Drop a finding (soft-remove → DROPPED): takes it off the diff overlay,
   *  the findings rail, and the publish selection. Returns the updated
   *  detail. */
  dropReviewFinding: (
    passId: string,
    findingId: string,
  ) => Promise<ReviewPassDetailDto>;
  /** Add a finding by hand (created AGREED) — to capture one the panel
   *  described in prose but never recorded. Returns the updated detail. */
  addReviewFinding: (
    passId: string,
    severity: string,
    path: string | null,
    line: number | null,
    comment: string,
  ) => Promise<ReviewPassDetailDto>;
  /** Steer the panel: inject a human message addressed to a reviewer or
   *  the lead and run that seat's reply unbudgeted. Returns the updated
   *  detail (the new human message + the seat's reply on the transcript). */
  steerReview: (
    passId: string,
    targetParticipantId: string,
    message: string,
  ) => Promise<ReviewPassDetailDto>;
  /** Add a local pre-push inline review comment on a task's diff at
   *  file:line (1-based). Returns the persisted comment. */
  addReviewComment: (
    taskId: string,
    file: string,
    line: number,
    body: string,
  ) => Promise<ReviewCommentDto>;
  /** Every review comment on the task, oldest-first, for the diff page. */
  listReviewComments: (taskId: string) => Promise<ReviewCommentDto[]>;
  /** Mark a review comment resolved. */
  resolveReviewComment: (id: string) => Promise<void>;
  /** Re-open a resolved review comment. */
  reopenReviewComment: (id: string) => Promise<void>;
  /** Submit the task's unresolved local review comments to its dev agent
   *  as a steering turn. Returns how many were submitted and the enqueued
   *  turn id (null when there was nothing to submit). */
  submitReview: (taskId: string) => Promise<{ submitted: number; turnId: string | null }>;
  /** Raise a running pass's budget so the panel keeps reviewing: bumps the
   *  cost cap by addCostMilli and the debate-round cap by addRounds. Returns
   *  the updated detail. */
  raiseReviewBudget: (
    passId: string,
    addCostMilli: number,
    addRounds: number,
  ) => Promise<ReviewPassDetailDto>;
  /** Re-run the full review loop (independent reviews → cross-review →
   *  consensus → debate → wrap-up) on an existing pass — the proper
   *  "continue reviewing". Returns the current detail; progress streams
   *  via the transcript poll. */
  resumeReview: (passId: string) => Promise<ReviewPassDetailDto>;
  /** Mark a pass completed by hand — finished without posting to GitHub.
   *  Terminal but reversible (resumeReview re-runs). Returns the updated
   *  detail with phase COMPLETED. */
  completeReview: (passId: string) => Promise<ReviewPassDetailDto>;
  /** Light PR title + author per review thread, for labelling review
   *  threads in thread lists without loading the transcript. */
  getReviewThreadPrSummaries: (
    threadIds: string[],
  ) => Promise<ReviewThreadPrSummaryDto[]>;
  /** Post the pass to GitHub as a PR review. {@code findingIds} is
   *  the subset of findings the user has confirmed for posting; the
   *  rest stay on the pass as AGREED but never reach GitHub.
   *  Returns the updated detail (pass.phase = PUBLISHED, findings
   *  flipped to POSTED). */
  publishReviewPass: (
    passId: string,
    verdict: ReviewVerdictDto,
    findingIds: string[],
  ) => Promise<ReviewPassDetailDto>;
  /** Spawn a build thread from a TERMINATE-d pass to apply its AGREED
   *  findings. {@code mode} is "author_is_reviewer" (forked off
   *  pr.head) or "suggested_change" (comment-only). Throws on the
   *  backend's 409 / 422 gates (not TERMINATE, already spawned, no
   *  eligible findings, no / ambiguous workspace). */
  spawnBuildFromReview: (
    passId: string,
    opts?: { workspaceId?: string; openingTitle?: string },
  ) => Promise<{ threadId: string; taskId: string | null; mode: string }>;
  /** Flip the headless auto-fix opt-in for one repo. Off by default
   *  per CLAUDE.md; only when this is explicitly true does the
   *  automation coordinator queue a headless turn against a failing-
   *  CI candidate in that repo. */
  setWorkspaceRepoAutoFix: (
    workspaceId: string,
    owner: string,
    repo: string,
    enabled: boolean,
  ) => Promise<WorkspaceRepoDto>;
  /** Close out one task on a thread and roll over to a fresh task,
   *  the "Ship & continue" action. The backend commits + pushes the
   *  current task's branch, opens its PR if not already up, then
   *  cuts the next task on either {@code main} (default) or stacked
   *  on the current branch. Returns the newly-created next task. */
  shipAndContinue: (
    threadId: string,
    taskId: string,
    opts?: { nextTitle?: string | null; baseMode?: 'MAIN' | 'STACKED' },
  ) => Promise<WorkUnitTaskDto>;
  /** Close a task: interrupt the agent, mark it CANCELED, and reap its
   *  worktree + branch. Terminal and destructive — the caller confirms
   *  first. */
  cancelTask: (threadId: string, taskId: string) => Promise<WorkUnitTaskDto>;
  /** Pause an active task: stop its agent and park it at PAUSED with its
   *  worktree + session intact so it can be resumed. The thread won't run a
   *  paused task, freeing the user to work on something else. */
  pauseTask: (threadId: string, taskId: string) => Promise<WorkUnitTaskDto>;
  /** Resume a paused task back to IDLE so the thread runs it again.
   *  ({@link resumeTask} is the thread-level revive; this is per-task.) */
  resumePausedTask: (threadId: string, taskId: string) => Promise<WorkUnitTaskDto>;
  /** Next → park the current task at AWAITING_REVIEW (worktree
   *  preserved) and start a fresh task cut from main. The trunk
   *  window's Next button calls this; differs from
   *  {@link shipAndContinue} which is terminal — task closes,
   *  worktree reaps. */
  parkAndStartNext: (
    threadId: string,
    taskId: string,
    opts?: { nextTitle?: string | null; baseMode?: 'MAIN' | 'STACKED' },
  ) => Promise<WorkUnitTaskDto>;
  /** Rename a task. Trims the value; an empty string clears the
   *  override and reverts to the humanised branch label. */
  renameTaskUnit: (
    threadId: string,
    taskId: string,
    name: string,
  ) => Promise<WorkUnitTaskDto>;
  /** Read the task's "accept edits in worktree" toggle. When on, the
   *  agent's file edits inside the task's worktree are auto-approved;
   *  Bash / push / out-of-worktree writes still prompt. */
  getTaskAcceptEdits: (
    threadId: string,
    taskId: string,
  ) => Promise<{ enabled: boolean }>;
  /** Flip the task's "accept edits in worktree" toggle; returns the
   *  persisted value. */
  setTaskAcceptEdits: (
    threadId: string,
    taskId: string,
    enabled: boolean,
  ) => Promise<{ enabled: boolean }>;
  /** Trunk-scope counterpart of {@link sendTaskMessage} — drives the
   *  trunk planning agent for cross-task talk. The persisted row lands
   *  with {@code task_id = null} so it filters into the trunk slice
   *  rather than any Task's segment. */
  sendTrunkMessage: (
    threadId: string,
    input: string,
  ) => Promise<ThreadSendResultDto>;
  /** Effective per-thread scope settings — global merged with the
   *  thread's overrides (caps, prompt addendum). Always returns a
   *  payload, even for zero-config threads. */
  getThreadSettings: (threadId: string) => Promise<ThreadSettingsDto>;
  /** Upsert this thread's overrides. {@code null} fields clear the
   *  override and revert to inheritance. Returns the post-merge view. */
  putThreadSettings: (
    threadId: string,
    body: {
      maxRunningTasks?: number | null;
      softCostUsdMilli?: number | null;
      hardCostUsdMilli?: number | null;
      promptAddendum?: string | null;
    },
  ) => Promise<ThreadSettingsDto>;
  /** Drop the thread's settings row — reverts to silent inheritance. */
  clearThreadSettings: (threadId: string) => Promise<void>;
  /** Active checkpoints for a thread — Overall first, then segments by
   *  descending seq. Drives the sidebar Checkpoints section and the
   *  cross-thread seed loader. */
  getTaskCheckpoints: (id: string) => Promise<ThreadCheckpointDto[]>;
  /** Force-generate a checkpoint segment for any messages added
   *  since the last segment, regardless of the token threshold.
   *  Resolves to the new checkpoint or null when there's nothing
   *  new to summarise. */
  generateTaskCheckpoint: (id: string) => Promise<ThreadCheckpointDto | null>;
  /** Last scheduler outcome for the thread. Non-null {@code lastError}
   *  means a recent background summarisation attempt failed (most
   *  commonly because the Anthropic key isn't configured) so the UI
   *  can surface a banner instead of an unexplained empty list. */
  getTaskCheckpointStatus: (id: string) => Promise<{ lastError: string | null }>;
  /** Recent scheduler turns, newest first. Used to distinguish
   *  queued work from an active CLI/API run. */
  getTaskTurns: (id: string) => Promise<ThreadTurnDto[]>;
  /** Recent scheduler events, newest first. Explains queued/running/
   *  cancelled transitions without reading backend logs. */
  getTaskTurnEvents: (id: string) => Promise<ThreadTurnEventDto[]>;
  /** Per-(thread, path) rollup rows for the detail-page sidebar. */
  getTaskFiles: (id: string) => Promise<ThreadFileDto[]>;
  /** Rename a thread. Trimmed and non-blank — empty / whitespace
   *  values are rejected on the backend. Returns the updated row. */
  renameTask: (id: string, title: string) => Promise<ThreadDto>;
  /** Send a follow-up turn to a non-terminal thread and return its
   *  durable scheduler turn id. */
  sendTaskMessage: (id: string, input: string) => Promise<ThreadSendResultDto>;
  /** Reply to a {@code permission_request}. When {@code preApprove}
   *  is supplied, the backend records the per-call decision and then
   *  grants an auto-approval budget for future invocations of the same
   *  tool — {@code count} positive sets a finite quota, {@code -1}
   *  means "always for this tool" until the session ends. */
  decideTaskPermission: (
    id: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => Promise<void>;
  /** Cancel the in-flight turn (Ctrl+C semantics). The session
   *  itself stays alive — the user can send another turn. */
  interruptTask: (id: string) => Promise<void>;
  /** Terminal — releases the underlying agent loop and removes the
   *  thread from the live registry. */
  stopTask: (id: string) => Promise<void>;
  /** Resume an ERRORED (or AWAITING) thread back to IDLE so the user
   *  can send another turn. The agent's CLI session id is preserved
   *  on the thread row, so the next {@code sendTask} call spawns a
   *  fresh subprocess with {@code claude --resume <id>} and picks
   *  up where the previous turn died — useful after a token-quota
   *  reset or transient agent error. */
  resumeTask: (id: string) => Promise<void>;
  /** Permanent removal — only allowed for COMPLETED / ERRORED threads.
   *  Drops the thread row, its conversation log, and per-file rollups.
   *  Rejects with an error from the backend if the thread is still
   *  live. */
  deleteTask: (id: string) => Promise<void>;
  /** Pre-flight check: returns {@code deletable: true} when the
   *  thread can be removed, or a {@code reason} string when blocked
   *  (e.g. shipped tasks). The trunk's Delete button uses this to
   *  surface the block reason without making the user click first. */
  getThreadDeleteEligibility: (id: string) => Promise<{ deletable: boolean; reason?: string }>;

  /** All notifications, newest-first. Drives the bell dropdown +
   *  notification center. */
  listNotifications: () => Promise<NotificationDto[]>;
  /** Unread only — the badge count + active toast list. */
  listUnreadNotifications: () => Promise<NotificationDto[]>;
  /** Per-thread feed (the auto* row in the threads list). */
  listNotificationsForThread: (threadId: string) => Promise<NotificationDto[]>;
  /** Flip UNREAD → READ + stamp readAt. */
  markNotificationRead: (id: string) => Promise<NotificationDto>;
  /** Soft-hide via DISMISSED status. */
  dismissNotification: (id: string) => Promise<NotificationDto>;
  /** Hard delete — the row is gone. */
  deleteNotification: (id: string) => Promise<void>;
  /** Approve a parked AWAITING_REVIEW proposal: the backend claims
   *  the row once, runs its deferred action, and writes an audit row.
   *  {@code expectedAction} prevents a rendered approval control from
   *  resolving a payload that changed after display. */
  approveNotification: (
    id: string,
    editedBody?: string | null,
    expectedAction?: string | null,
  ) => Promise<PublishResultDto>;
  /** Discard a parked AWAITING_REVIEW proposal without running its
   *  deferred side effect. */
  discardNotification: (id: string, expectedAction?: string | null) => Promise<PublishResultDto>;
  /** Persist an edited PR title/body onto a parked {@code ship_task}
   *  proposal before it's approved. Returns the updated notification (its
   *  payloadJson carries the new prTitle/prBody). */
  setShipDescription: (
    notificationId: string,
    prTitle: string,
    prBody: string,
  ) => Promise<NotificationDto>;

  /** Open a Server-Sent Events subscription to the backend for one
   *  thread. Each {@link StreamEvent} the session emits is delivered
   *  through {@code onEvent}; lifecycle / error conditions fire
   *  {@code onClose}. The returned function tears down both the
   *  backend connection (when the last subscriber for that thread
   *  unsubscribes) and the renderer-side listener.
   *
   *  The renderer should treat this as a "wake me up" signal —
   *  call {@code refresh()} on each event to pull the canonical
   *  state from {@code /messages}, /{@code threads/{id}}, etc. The
   *  event payload is included in case finer-grained handling
   *  becomes useful later (e.g., appending streamed text deltas
   *  to an in-flight assistant card). */
  subscribeTaskStream: (
    threadId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => () => void;

  // ── Brain agent (per-task read-only conversational surface) ──────
  /** Full brain-view payload for a task: aggregate strip, stages,
   *  brain feed, right rail, scrubbers. Polled by the brain view. */
  getBrainView: (taskId: string) => Promise<TaskBrainViewData>;
  /** Post a question to the task's brain agent. Returns the answering
   *  turn id and the brain thread id (subscribe to its stream). */
  sendBrainMessage: (taskId: string, text: string) => Promise<BrainMessageResult>;
  /** Drill-in detail for one stage: iteration log, metrics, realtime CI. */
  getStageDetail: (stageId: string) => Promise<StageDetailData>;
  /** Spawn a panel review as a callable sub-stage of {@code parentStageId}.
   *  Returns the opened review stage, the seated pass, and the review
   *  thread the panel page navigates to. */
  spawnReview: (parentStageId: string) => Promise<SpawnReviewResult>;
  /** Steer a stage's dev agent: enqueue the user's message as a turn on the
   *  task's dev thread. Returns the enqueued turn id. */
  steerStage: (stageId: string, text: string) => Promise<{ turnId: string }>;
  /** Approve the task's plan: closes the PlanStage, opens the
   *  DevelopmentStage, and returns its id (+ redirect path) so the view can
   *  auto-navigate to the dev stage detail page. */
  approvePlan: (planStageId: string) => Promise<{ devStageId: string; redirectUrl: string }>;
  /** Open a fresh PlanStage after a prior plan was approved (re-plan). */
  replan: (taskId: string) => Promise<{ planStageId: string }>;
  /** Mark a plan follow-up note addressed / dismissed. */
  updateFollowup: (
    planStageId: string, followupEventId: string, status: 'addressed' | 'dismissed',
  ) => Promise<void>;

  // ── Thread tabs: working-tree changes + commits ──────────────────
  /** Files modified by the AI session but not yet committed. Returns
   *  paths + single-char status (M, A, D, R, ...). Empty when nothing
   *  has changed or the workingDir isn't a git repo. */
  listTaskWorkingChanges: (id: string) => Promise<ThreadWorkingFileDto[]>;
  /** Unified diff for one uncommitted file. Truncated at 256 KB. */
  getTaskWorkingDiff: (id: string, path: string) => Promise<string>;
  /** Commits authored in the thread's workingDir since thread.createdAt,
   *  most-recent first. Limited to 100. */
  listTaskCommits: (id: string) => Promise<ThreadCommitDto[]>;
  /** Per-file rollup (path + status + +/-) for one of the thread's commits. */
  listTaskCommitFiles: (id: string, sha: string) => Promise<ThreadCommitFileDto[]>;
  /** Unified diff for one file at one of the thread's commits. */
  getTaskCommitDiff: (id: string, sha: string, path: string) => Promise<string>;
  /** The task's full diff against its base branch, shaped like the PR
   *  review's DiffFileDto so the same diff component renders it. */
  getTaskCumulativeDiff: (id: string) => Promise<DiffFileDto[]>;
  /** One commit's diff as DiffFileDto rows for the shared diff view. */
  getTaskCommitDiffFiles: (id: string, sha: string) => Promise<DiffFileDto[]>;
};

/** Mirror of GitRunner.WorkingTreeFile — uncommitted change in a
 *  thread's workingDir. {@code status} is a single git porcelain char
 *  (M = modified, A = added/untracked, D = deleted, R = renamed). */
export type ThreadWorkingFileDto = {
  path: string;
  status: string;
};

/** Mirror of GitRunner.CommitEntry — one commit in the thread's history. */
export type ThreadCommitDto = {
  sha: string;
  shortSha: string;
  authorName: string;
  authorEmail: string;
  authoredAt: string;
  subject: string;
};

/** Mirror of GitRunner.CommitFileChange — per-file rollup inside a
 *  commit, with status char and line counts. */
export type ThreadCommitFileDto = {
  path: string;
  status: string;
  additions: number;
  deletions: number;
};

export type InAppNavState = {
  url: string;
  title: string;
  canGoBack: boolean;
  canGoForward: boolean;
  loading: boolean;
};
