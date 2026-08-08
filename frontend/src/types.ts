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
import type {
  AgentRunDto, BrainMessageResult, LocalPublishBaseSyncApprovalDto,
  ReviewRoundDto, StageDetailData, TaskBrainViewData,
} from './types/brainView';
import type { LocalPR, LocalPRBundle, LocalPRCheck, LocalPRComment } from './types/localPr';
import type { DashboardPR } from './types/dashboardPr';
import type { AgentReviewData } from './review/agentReviewTypes';

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
  /** GitHub head branch name. Present on workspace PR façades and null on
   *  rows captured before branch metadata was added. */
  headRef?: string | null;
  /** Workspace review round currently attached to this pull request. */
  reviewRound?: number | null;
  /** Workspace task linked to the pull request, when one exists. */
  linkedTaskKey?: string | null;
  /** Replacement PR recorded when this pull request was superseded. */
  supersededBy?: number | null;
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
  /** Label name/color on `labeled`/`unlabeled` events. Null otherwise. */
  labelName: string | null;
  labelColor: string | null;
  /** Milestone title on `milestoned`/`demilestoned` events. Null otherwise. */
  milestoneTitle: string | null;
  /** Login assigned/unassigned on `assigned`/`unassigned` events. Null
   *  otherwise. */
  assigneeLogin: string | null;
  /** The other issue/PR referenced by a `cross-referenced` event. Null on
   *  every other event type. */
  crossRefNumber: number | null;
  crossRefTitle: string | null;
  crossRefUrl: string | null;
  /** True iff the cross-referencing source is a pull request rather than
   *  an issue. Always false on non-`cross-referenced` events. */
  crossRefIsPullRequest: boolean;
};

/** One entry from a check run's GitHub "Annotations" list. `title` is the
 *  workflow step that emitted it ("Upload test results"), `message` the text
 *  underneath ("Expecting actual: 1L to be less than: 1L"), and
 *  `path`/`startLine` the source location — null for annotations GitHub
 *  attaches to the workflow file rather than to code. */
export type CheckAnnotationDto = {
  title: string | null;
  message: string | null;
  path: string | null;
  startLine: number | null;
};

/** Failure detail for one check run. `annotations` wins when non-empty; `log`
 *  is the fallback excerpt for the many jobs whose only annotation is the
 *  contentless "Process completed with exit code 1.". Both empty means GitHub
 *  exposed nothing to show. */
export type CheckFailureDto = {
  annotations: CheckAnnotationDto[];
  log: string;
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
  /** Login of whoever resolved the thread, for the "X marked this
   *  conversation as resolved" attribution. Null/absent when unresolved or
   *  when only a REST pass (no GraphQL) has run. */
  resolvedBy?: string | null;
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
  /** Workspace-owned links shown ahead of GitHub metadata in the redesigned
   *  detail rail. Older sidecars omit this field and the renderer falls back
   *  to linkedIssues. */
  workspaceLinks?: Array<{
    kind: 'trunk' | 'task' | 'agent-review';
    title: string;
    detail: string;
    trunkId?: string | null;
  }>;
  /** Reviewer rows with their latest public state. When absent the renderer
   *  derives a compatible projection from reviewerVerdicts and
   *  requestedReviewers. */
  reviewers?: Array<{
    login: string;
    state: 'commented' | 'approved' | 'requested';
  }>;
  assignees?: string[];
  milestone?: {
    title: string;
    progressPercent: number;
  } | null;
  developmentLinks?: Array<{
    number: number;
    title?: string;
    closes: boolean;
  }>;
  participants?: string[];
  /** Source counts can exceed the cached timeline/check payloads. */
  conversationCount?: number;
  checkCount?: number;
  /** Human-readable last-sync age supplied by the workspace overview
   *  projection. */
  syncedLabel?: string;
  subscriptionReason?: string | null;
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

export type CreationOriginDto =
  | 'unknown'
  | 'user'
  | 'user-report'
  | 'agent'
  | 'automation'
  | 'issue-monitor'
  | 'quality-scan';

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
  commentCount?: number;
  origin: CreationOriginDto;
  /** Workspace linkage supplied by the workspace-scoped issue facade. */
  linkedTrunkId?: string | null;
  linkedTrunkTitle?: string | null;
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
  origin: CreationOriginDto;
  /** Compact workspace-only relationships rendered beside the GitHub issue. */
  linkedWork?: Array<{
    kind: 'trunk' | 'pull-request';
    id: string;
    title: string;
    status: string;
    itemPath?: string | null;
  }>;
  /** GitHub participants shown as an overlapping avatar group. */
  participants?: string[];
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

export type PullRequestMetadataChoicesDto = {
  users: GitHubUserMatchDto[];
  labels: IssueLabelDto[];
  assignees: string[];
  selectedLabels: string[];
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
  /** GitHub event actor.avatar_url. Older cached rows may omit it. */
  actorAvatarUrl?: string | null;
  /** Concrete secondary payload such as a commit subject. */
  detail?: string | null;
  /** Created branch/tag name for CreateEvent. */
  ref?: string | null;
  /** APPROVED / CHANGES_REQUESTED / COMMENTED for review events. */
  reviewState?: string | null;
  /** Set by {@link groupRecentEvents} when consecutive pushes to the same
   *  repo/PR are collapsed into one row; absent for un-merged events. */
  pushCount?: number;
  /** Set by {@link groupRecentEvents} when consecutive PR review comments
   *  on the same repo/PR are collapsed into one row; absent for un-merged
   *  events. */
  commentCount?: number;
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
  /** Request-level reasoning effort for CLI or supporting API models.
   *  Null/absent uses the inherited or model default. */
  reasoningEffort?: string | null;
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
    source: 'STAGE' | 'TASK' | 'THREAD' | 'WORKSPACE' | 'GLOBAL_DEFAULT';
    scopeId: string | null;
    scopeLabel: string;
  };
  /** Historical lock projection. Existing scopes keep their engine snapshot;
   *  effort overrides remain editable for future, not-yet-admitted turns. */
  agentLocked: boolean;
};

export type WorkModelEntryDto = {
  id: string;
  displayName: string;
  isDefault: boolean;
  description?: string | null;
  defaultReasoningEffort?: string | null;
  supportedReasoningEfforts?: WorkModelReasoningEffortDto[];
};

export type WorkModelReasoningEffortDto = {
  id: string;
  description?: string | null;
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

/** Account-level engine choice per kind of agent work. Workspaces start
 *  from these; the account-wide roles (triage, perf) have no workspace
 *  equivalent and always read them. Values are picker choice ids —
 *  "cli:codex", "api:deepseek", "local", … */
export type WorkModelOptionsDto = {
  cliAgents: WorkModelAgentOptionDto[];
  apiProviders: WorkModelProviderOptionDto[];
};

export type CodexCliUpdateResultDto = {
  previousVersion: string;
  version: string;
  output: string;
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

export type ManagedRepoWriteMode = 'FORK' | 'DIRECT';

export type ManagedClonePlanDto = {
  viewerLogin: string;
  directAvailable: boolean;
  forkAvailable: boolean;
  defaultWriteMode: ManagedRepoWriteMode;
  destination: string;
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
   *  parse it. Preserved across rebases and amends — the time the user
   *  thinks of as "when I wrote this." */
  authoredAt: string | null;
  /** ISO 8601 strict committed timestamp — when the commit landed on
   *  this branch. What a history LIST shows, matching github.com: a
   *  maintainer rebasing a contribution lands it now under an author
   *  date from whenever it was first written. */
  committedAt: string | null;
  /** Workspace façade enrichment. Legacy local-repo responses omit these. */
  ciStatus?: 'passed' | 'failed' | 'unknown';
  agentOwned?: boolean;
  onBehalfOf?: string | null;
  displayTime?: string;
  groupLabel?: string;
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

export type ThreadKindDto = 'CLI_AGENT' | 'LOGIC_LOOP';

export type ThreadStatusDto =
  | 'PENDING'
  | 'RUNNING'
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
  /** Optional user remark shown when the trunk name is hovered. */
  description?: string | null;
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
  /** Per-thread work-model override; null means this scope inherits
   *  from workspace or the global default. */
  workModel: WorkModelDto | null;
  /** Concurrent compute slots the thread's tasks may occupy. 1 in v1. */
  parallelSlots: number;
  /** Optional list projection fields used by the unified Trunks surface. */
  activitySummary?: string;
  taskCount?: number;
  pullRequestCount?: number;
  unread?: boolean;
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
 *  server-side from the physical conversation ledgers — never stored — so
 *  the panel can't drift from the rendered transcript. LEGACY seqs remain
 *  positive; promoted-Trunk typed seqs are negative durable Trunk versions.
 *  Order them with the source-aware conversation comparator, not numerically. */
export type ConvIndexEntryDto = {
  seq: number;
  preview: string;
  tsMs: number;
};

/** A workspace-scoped project brain whose markdown is loaded into
 *  threads created inside that workspace. */
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
  /** Sole repository identity and verified clone behind this workspace. */
  repository?: {
    owner: string;
    repo: string;
    fullName: string;
    defaultBaseBranch: string | null;
    clonePath: string | null;
    verified: boolean;
    /** Fork-based clone: origin is the user's fork, `fullName` is upstream. */
    forked?: boolean;
  } | null;
  /** Two newest trunk events, already scoped and deep-linked server-side. */
  recentActivity?: Array<{
    id: string;
    title: string;
    status: string;
    itemPath: string;
    occurredAt: number;
  }>;
  ready?: boolean;
  syncState?: string;
};

/** One repo attached to a workspace. Carries workspace-level settings
 *  that don't belong on the GitHub watched-list row (default base
 *  branch, auto-fix opt-in). */
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
  | 'IMPLEMENTING'
  | 'VALIDATING'
  | 'INTERNAL_REVIEW'
  | 'AWAITING_PUSH'
  | 'ADDRESSING_LOCAL_COMMENTS'
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
  origin: CreationOriginDto;
  /** ISO instant the task's branch first reached the remote, or null if
   *  it hasn't been pushed yet. Set on a push approval and on the
   *  implicit push an open_pr approval performs. Drives the "on remote"
   *  task badge so a parked task no longer looks stuck. */
  pushedAt: string | null;
  /** Dev PR-collaboration lifecycle phase (V106) — one of TaskPhase:
   *  IMPLEMENTING | VALIDATING | INTERNAL_REVIEW | AWAITING_PUSH |
   *  ADDRESSING_LOCAL_COMMENTS | PUSHED_AWAITING_CI | CI_FIXING |
   *  AWAITING_READY | AWAITING_REMOTE_REVIEW | ADDRESSING_COMMENTS |
   *  AGENT_RE_REVIEW | AWAITING_UPDATE_PUSH | COMPLETED | NEEDS_ATTENTION.
   *  Orthogonal to {@link status} (the agent runtime axis). */
  phase: TaskPhaseDto;
  /** Dev-agenda checklist JSON (same shape as a review pass's agenda),
   *  or null until the agent sets it. */
  agendaJson: string | null;
  /** Consecutive auto-pushes — drives the runaway-autonomy cap badge. */
  consecutiveAutoPushes: number;
  /** 'owner/repo#n' this task is permanently linked to, or null. */
  linkedPrRef: string | null;
  /** Opening-prompt accumulator (V110): the agent's first-turn input
   *  when work starts. Null when never accumulated. */
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

/** A parked future-work item on a thread's backlog (the trunk Backlog
 *  tab). {@code createdAt}/{@code startedAt} are epoch-millis;
 *  {@code startedAt} is null until "Start development". */
export type BacklogItemDto = {
  id: string;
  threadId: string;
  workspaceId: string | null;
  title: string;
  body: string;
  tags: string[];
  /** low | medium | high. */
  priority: string;
  /** Broad source category retained for compatibility: manual | agent. */
  source: string;
  /** created | in-progress | resolved | shipped | closed | not-to-proceed. */
  status: string;
  /** user | trunk-agent. */
  createdBy: string;
  /** Immutable server-stamped creation path; never derived from editable tags. */
  origin: CreationOriginDto;
  createdAt: number;
  inProgressAt: number | null;
  startedAt: number | null;
  resolvedAt: number | null;
  rejectedAt: number | null;
  rejectionReason: string | null;
  linkedTaskId: string | null;
  relatedBacklogIds: string[];
  /** Workspace-local public key (BQ-N); absent on pre-migration fixtures. */
  key?: string | null;
  /** Structured workspace fields. Legacy thread callers may omit these. */
  summary?: string;
  detail?: string | null;
  impactRisk?: string | null;
  links?: Array<{ type: string; id: string }>;
};

/** Result of starting development on a backlog item: the updated item
 *  and the materialised task id (null when it queued behind a running
 *  task). */
export type StartDevelopmentResponse = {
  item: BacklogItemDto;
  taskId: string | null;
};

/** One multiple-choice option on an agent question. */
export type AgentQuestionOptionDto = { id: string; label: string; extra: string | null };

/** A clarification an agent asked the user via {@code ask_user_question}.
 *  Timestamps are epoch-millis. */
export type AgentQuestionDto = {
  id: string;
  threadId: string;
  taskId: string | null;
  question: string;
  context: string | null;
  options: AgentQuestionOptionDto[];
  allowFreeForm: boolean;
  status: string;
  answerOptionId: string | null;
  answerFreeForm: string | null;
  answerRevision?: number;
  answerActor?: string | null;
  createdAt: number;
  answeredAt: number | null;
};

/** A passive signal in a thread's Notifications feed. Distinct from the
 *  actionable {@link NotificationDto} gate. {@code createdAt}/{@code readAt}
 *  are epoch-millis; {@code readAt} is null until read. */
export type ThreadSignalDto = {
  id: string;
  threadId: string;
  taskId: string | null;
  sourceKind: 'agent' | 'system' | 'github';
  iconKind: 'info' | 'success' | 'warn' | 'alert';
  title: string;
  body: string | null;
  sourceUrl: string | null;
  createdAt: number;
  readAt: number | null;
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
 *  {@code nextCursor} is the earliest canonical seq to pass to the next
 *  backfill — null when the start of the thread is reached. Canonical order
 *  is LEGACY first and typed second; it is not numeric seq order. */
export type ConvIndexPageDto = {
  threadId: string;
  totalUserMessages: number;
  entries: ConvIndexEntryDto[];
  messages: ThreadMessageDto[];
  loadedFromSeq: number | null;
  nextCursor: number | null;
};

/** Read-only provider work trace for one typed Trunk request. It is kept
 * separate from ThreadMessageDto so log rows never acquire conversation seq
 * values or affect conversation-index cursors. */
export type TrunkTraceEventDto = {
  id: string;
  trunkId: string;
  turnId: string;
  requestMessageId: string;
  executionId: string;
  logSeq: number;
  eventIndex: number;
  type: 'thinking' | 'tool_call' | 'tool_result' | 'error';
  contentJson: string;
  ts: string;
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

/** Durable V2 permission prompt projected from its exact typed Turn. */
export type TypedPermissionRequestDto = {
  id: string;
  callId: string;
  ownerKind: 'THREAD_TURN' | 'TASK_TURN' | 'STAGE_TURN' | 'REVIEW_ASSIGNMENT_TURN';
  turnId: string;
  operationId: string;
  capability: string;
  toolName: string;
  parametersJson: string;
  state: string;
  answerRevision: number;
  requestedAt: number;
};

export type SessionAudienceDto = 'plan' | 'dev' | 'review' | 'ci-fix';

export type NewTaskRequestDto = {
  kind: ThreadKindDto;
  /** Explicit per-session-kind engine changes from the create dialog.
   *  Absent kinds copy the workspace's effective choice at creation;
   *  the backend then freezes all four choices for this thread. */
  engines?: Partial<Record<SessionAudienceDto, string>>;
  /** Optional and advisory — the backend stamps the thread with the
   *  workspace's engine, which is the only thing that decides what
   *  actually runs. */
  provider?: string;
  model?: string;
  /** Owning workspace's id — required. The backend rejects the
   *  create when null/blank so the thread always lands in the right
   *  workspace's slice. */
  workspaceId: string;
  /** User-authored trunk name. */
  title?: string;
  /** Optional remark displayed when the trunk name is hovered. */
  description?: string;
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
  | 'AUTO_FIX_DONE'
  | 'READY_TO_MERGE'
  | 'PASSIVE';

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

/** Restart-safe state of the one-shot remote publication for a review pass. */
export type ReviewPassPublicationDto = {
  reviewPassId: string;
  commandId: string;
  status: 'QUEUED' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED' | 'INDETERMINATE';
  terminal: boolean;
  reviewAction: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
  findingIds: string[];
  externalEffectId: string | null;
  evidence: string | null;
  lastError: string | null;
};

/** Restart-safe state of one explicit review publication on the unified PR
 * surface. PUBLISHED is exposed only after accepted delivery is finalized. */
export type LocalPrReviewPublicationDto = {
  prId: string;
  reviewId: string | null;
  commandId: string;
  status: 'QUEUED' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED' | 'INDETERMINATE';
  terminal: boolean;
  finalized: boolean;
  blocksNewPublication: boolean;
  externalEffectId: string | null;
  lastError: string | null;
};

export type ReviewBuildCommentProposalItemDto = {
  position: number;
  findingId: string;
  kind: 'INLINE' | 'TOP_LEVEL';
  path: string | null;
  line: number | null;
  body: string;
};

/** Frozen comment-only handoff for somebody else's PR. Approval and discard
 *  are durable local decisions; only the V2 dispatcher talks to GitHub. */
export type ReviewBuildCommentProposalDto = {
  threadId: string;
  reviewPassId: string;
  repoFullName: string;
  pullRequestNumber: number;
  expectedHeadSha: string;
  selectionDigest: string;
  status: 'PENDING' | 'APPROVED' | 'PUBLISHED' | 'FAILED' | 'DISCARDED';
  decision: 'APPROVE' | 'DISCARD' | null;
  commandId: string | null;
  actionStatus: string | null;
  externalEffectId: string | null;
  evidence: string | null;
  lastError: string | null;
  items: ReviewBuildCommentProposalItemDto[];
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
  /** Provider API calls only; excludes CLI and locally served models. */
  apiByProvider: { provider: string; callsCount: number; costCents: number }[];
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
  /** Workspace-scoped session usage projected from the canonical AgentRun rows. */
  usageByProvider?: WorkspaceUsageBreakdownDto[];
  usageByKind?: WorkspaceUsageBreakdownDto[];
  /** Latest GitHub REST quota, or null if no call has landed since boot. */
  githubRateLimit: { remaining: number; limit: number; resetAt: string } | null;
};

export type WorkspaceUsageBreakdownDto = {
  key: string;
  costUsdMilli: number;
  tokensIn: number;
  tokensOut: number;
  sessions: number;
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
  | 'create_issue'
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
  /** Proposed remote title (open_pr or create_issue). */
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
  'create_issue',
  'open_pr',
  'publish_review',
  'mark_ready',
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
  workspaceId?: string | null;
  publicType?: string | null;
  title?: string | null;
  summary?: string | null;
  itemPath?: string | null;
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

/** Narrow renderer-to-sidecar request used by redesigned workspace pages. */
export type WorkspaceApiRequest = {
  path: string;
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
};

export type ReadinessAssistanceKind =
  | 'POST_MAINTAINER_NUDGE'
  | 'REQUEST_REVIEWER';

/** Exact owner token projected only for a ready PR the viewer cannot merge. */
export type ReadinessAssistanceAvailability = {
  taskEpoch: number;
  stageId: string;
  stageGeneration: number;
  snapshotId: string;
  readinessId: string;
  policyId: string;
  headSha: string;
  baseSha: string;
  viewerLogin: string;
  actions: ReadinessAssistanceKind[];
};

export type ReadinessAssistanceRequest = {
  commandId: string;
  taskEpoch: number;
  stageGeneration: number;
  snapshotId: string;
  readinessId: string;
  policyId: string;
  headSha: string;
  baseSha: string;
  kind: ReadinessAssistanceKind;
  externalTarget: string | null;
  payload: string;
};

export type Bridge = {
  workspaceApi: <T = unknown>(request: WorkspaceApiRequest) => Promise<T>;
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  isDevLocalDataResetAvailable: () => Promise<boolean>;
  requestDevLocalDataReset: () => Promise<boolean>;
  fetchHello: () => Promise<string>;
  fetchPrs: () => Promise<PullRequestDto[]>;
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
  /** Settings → Concepts catalog. Read-only registry view. */
  listConcepts: (query: { kind?: string; query?: string }) => Promise<ConceptRowDto[]>;
  fetchPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  /** Force-refresh one PR's detail. Probes GitHub with the cached
   *  ETag; on 304 returns the backend's L2 snapshot, on 200 refetches
   *  the full detail. Passing {@code maxAgeSeconds > 0} skips the
   *  ETag probe entirely when our last probe is younger than that —
   *  used by the 10s polling tick so cross-tab opens at most one
   *  probe per cap. The manual ↻ button passes 0 (or omits) to
   *  always probe. */
  refreshPullRequestDetail: (repo: string, number: number, maxAgeSeconds?: number) => Promise<PullRequestDetailDto>;
  /** Best failure text for one check-run, lazy-loaded when the user unfolds a
   *  failing row in the checks card: annotations when GitHub published a
   *  source-located one, otherwise a log excerpt. Both empty means GitHub
   *  exposed nothing (external CI, or an expired log). */
  fetchCheckFailure: (repo: string, checkRunId: number) => Promise<CheckFailureDto>;
  fetchPrDiffFiles: (repo: string, number: number) => Promise<DiffFileDto[]>;
  fetchPrCommits: (repo: string, number: number) => Promise<PullRequestCommitDto[]>;
  /** Diff scoped to a single commit (DiffFileDto[] same as fetchPrDiffFiles). */
  fetchPrCommitDiff: (repo: string, number: number, sha: string) => Promise<DiffFileDto[]>;
  /** Returns a file's full content at a ref, as a list of lines. Powers the
   *  "expand collapsed code" buttons in the diff viewer. */
  fetchFileBlob: (repo: string, path: string, sha: string) => Promise<{ lines: string[] }>;
  /** Removes a PR from its repo's merge queue. Mirrors the "Remove
   *  from queue" button on github.com's merge bar. No-op when the PR
   *  isn't currently in a queue. */
  dequeuePr: (prId: number, repo: string, number: number) => Promise<{ result: string }>;
  commentPr: (prId: number, repo: string, number: number, body: string, close: boolean) => Promise<void>;
  /** Adds a single user to the PR's requested reviewers. */
  addRequestedReviewer: (repo: string, number: number, reviewer: string) => Promise<void>;
  /** Removes a single user from the PR's requested reviewers. */
  removeRequestedReviewer: (repo: string, number: number, reviewer: string) => Promise<void>;
  getPullRequestMetadataChoices: (repo: string, number: number) => Promise<PullRequestMetadataChoicesDto>;
  setPullRequestAssignee: (repo: string, number: number, login: string, selected: boolean) => Promise<void>;
  setPullRequestLabel: (repo: string, number: number, label: string, selected: boolean) => Promise<void>;
  /** Replies inline to an existing per-line review thread on the PR. */
  replyToReviewThread: (repo: string, number: number, rootCommentId: number, body: string) => Promise<void>;
  /** Posts a brand-new per-line review comment on a specific diff line.
   *  The backend resolves the live PR head immediately before posting.
   *  {@code side} is "LEFT" for the old file, "RIGHT" for the new file. */
  createInlineReviewComment: (
    repo: string,
    number: number,
    body: string,
    path: string,
    line: number,
    side: 'LEFT' | 'RIGHT',
    /** Optional first line of a multi-line range. null/omitted for the
     *  single-line case. When set, GitHub creates the comment spanning
     *  startLine through line on the matching side. */
    startLine?: number | null,
    startSide?: 'LEFT' | 'RIGHT' | null,
  ) => Promise<void>;
  /** Commits a review suggestion over lines startLine..line of `path` on
   *  the PR's head branch — the "Apply suggestion" affordance. Rejects
   *  when the head has moved since the comment was written, or when a
   *  fork PR hasn't allowed maintainer edits. */
  applySuggestion: (
    repo: string,
    number: number,
    suggestion: string,
    path: string,
    line: number,
    startLine?: number | null,
  ) => Promise<void>;
  updatePrBody: (repo: string, number: number, body: string) => Promise<void>;
  // Phase 2
  getWatchedRepos: () => Promise<WatchedRepoDto[]>;
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
  /** Files a product bug in bytequay/bytequay regardless of watched repos. */
  reportByteQuayIssue: (title: string, body: string) => Promise<IssueDto>;
  setIssueState: (owner: string, repo: string, number: number, state: 'open' | 'closed') => Promise<IssueDetailDto>;
  /** Repo-level metadata for the right-pane hero card. */
  getRepoMeta: (owner: string, repo: string) => Promise<RepoMetaDto>;
  /** All watched repos plus their local-clone state (CLEAN / MODIFIED /
   *  UNMAPPED / …). Drives the Repos page. */
  listLocalRepos: () => Promise<LocalRepoStatusDto[]>;
  /** Native folder picker. Returns the selected absolute path, or null
   *  when the user cancels. Used by settings/install surfaces. */
  pickFolder: (options?: { defaultPath?: string; title?: string }) => Promise<string | null>;
  /** Reads the managed-clone plan for this watched repo candidate. */
  getManagedClonePlan: (owner: string, repo: string) => Promise<ManagedClonePlanDto>;
  getUserRepos: () => Promise<UserRepoDto[]>;
  searchRepos: (query: string) => Promise<UserRepoDto[]>;
  getRecentActivity: (login: string) => Promise<RecentEventDto[]>;
  getFollowingActivity: (login: string) => Promise<RecentEventDto[]>;
  /** Records a visit to a tracked surface for the footprints trail.
   *  Fire-and-forget — callers don't await it and navigation never
   *  blocks on the write. */
  recordSurfaceVisit: (visit: SurfaceVisitInput) => Promise<void>;
  /** The footprints trail for a calendar day (defaults to today when
   *  {@code date} is omitted). {@code date} is ISO YYYY-MM-DD. */
  getFootprints: (date?: string) => Promise<FootprintsTrailDto>;
  updateProfile: (name: string, bio: string, location: string) => Promise<UserProfileDto>;
  /** Opens an unhandled HTTP(S) link in ByteQuay's browser overlay. */
  openInAppBrowser: (url: string) => Promise<void>;
  /** Explicit escape hatch for OAuth and the browser overlay's Browser button. */
  openExternal: (url: string) => Promise<void>;
  /** Issues an authorize URL for the GitHub OAuth + PKCE flow. The renderer
   *  opens it in the system browser. {@code configured} is false when the
   *  backend hasn't been given GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET — in
   *  that case the renderer falls back to the PAT input. */
  getGitHubOAuthAuthorizeUrl: () => Promise<{ configured: boolean; url?: string }>;
  /** Whether a `gh` binary is on disk. Says nothing about whether the user
   *  is logged in to it — that only surfaces when the import runs. */
  getGitHubCliAvailable: () => Promise<{ available: boolean }>;
  /** Stores the token `gh` already holds in the same slot the PAT/OAuth
   *  paths use. Rejects with gh's own message when it can't produce one. */
  importGitHubCliToken: () => Promise<{ login: string }>;
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
  /** Catalog × credentials × CLI detection — the option tree the
   *  work-model picker walks. Re-fetched when the picker opens so
   *  newly-added credentials / freshly-installed CLI agents show
   *  up without an app restart. */
  getWorkModelOptions: () => Promise<WorkModelOptionsDto>;
  /** Forces the CLI detector to drop its memo and re-probe every
   *  binary. Backs the picker's "refresh" affordance. */
  refreshWorkModelOptions: () => Promise<WorkModelOptionsDto>;
  /** Electron's own `app.getVersion()` — the packaged app version. */
  getAppVersion: () => Promise<{ version: string }>;
  getCodexCliVersion: () => Promise<{ version: string }>;
  updateCodexCli: () => Promise<CodexCliUpdateResultDto>;
  /** Resolve the effective work model for a thread (cascade: thread →
   *  workspace → global default). */
  getThreadWorkModel: (threadId: string) => Promise<ResolvedWorkModelDto>;
  /** Set or clear this trunk's effort override. The engine remains fixed. */
  setThreadWorkModel: (threadId: string, model: WorkModelDto | null) => Promise<ResolvedWorkModelDto>;
  /** Resolve the effective work model for a task (cascade: task →
   *  thread → workspace → global default). */
  getTaskWorkModel: (threadId: string, taskId: string) => Promise<ResolvedWorkModelDto>;
  /** Set or clear this task's effort override. The engine remains fixed. */
  setTaskWorkModel: (
    threadId: string,
    taskId: string,
    model: WorkModelDto | null,
  ) => Promise<ResolvedWorkModelDto>;
  /** Resolve the effective work model for a stage (cascade: stage →
   *  task → thread → workspace → global default) — the most-specific
   *  rung on the cascade. */
  getStageWorkModel: (stageId: string) => Promise<ResolvedWorkModelDto>;
  /** Set or clear this stage's effort override. The engine remains fixed. */
  setStageWorkModel: (stageId: string, model: WorkModelDto | null) => Promise<ResolvedWorkModelDto>;
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
  /** Sends the user's draft comment text to the active LLM and returns
   *  a polished rewrite. Used by the "Better words" button — UI replaces
   *  the textarea contents with the response. */
  polishCommentText: (text: string) => Promise<string>;
  /** Adds an emoji reaction to the pull request description. */
  addPullRequestReaction: (
    repo: string,
    number: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ) => Promise<void>;
  /** Adds an emoji reaction to a per-line review comment. {@code content}
   *  is one of the GitHub reaction strings (+1 / -1 / laugh / confused /
   *  heart / hooray / rocket / eyes). Idempotent on the GitHub side. */
  addReviewCommentReaction: (
    repo: string,
    number: number,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ) => Promise<void>;
  /** Adds an emoji reaction to a top-level issue / PR comment (the
   *  "commented" timeline events). Same content allowlist as the
   *  review-comment variant. */
  addIssueCommentReaction: (
    repo: string,
    number: number,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ) => Promise<void>;
  /** Toggles a review thread's resolved state via GitHub's GraphQL
   *  mutations. Identified by the REST root comment id; the backend
   *  translates to the GraphQL node id internally. */
  setReviewThreadResolved: (
    repo: string,
    number: number,
    prId: number,
    rootCommentId: number,
    resolved: boolean,
  ) => Promise<void>;
  // ─── Generic in-app browser ──────────────────────────────────────
  /** Mount a WebContentsView at the given screen-coords bounds and
   *  load {@code url}. Replaces any existing in-app-browser view. */
  mountInAppBrowser: (url: string, bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
  setInAppBrowserBounds: (bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
  unmountInAppBrowser: () => Promise<void>;
  inAppGoBack: () => Promise<void>;
  inAppGoForward: () => Promise<void>;
  inAppReload: () => Promise<void>;
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
  /** Drive the window from the renderer's fake traffic-light dots — the
   *  only close/minimize/zoom controls now that the native buttons are
   *  permanently hidden (see main.ts createWindow). */
  windowControl: (action: 'close' | 'minimize' | 'zoom') => Promise<void>;
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
  /** Insert one group along with its initial members
   *  ({@code initialTaskIds} must contain ≥ 1 and ≤ 4 ids). */
  createTaskGroup: (request: NewTaskGroupRequestDto) => Promise<ThreadGroupDto>;
  /** Single thread by id; null when no row matches. */
  getTask: (id: string) => Promise<ThreadDto | null>;
  /** One window of the conversation index — user-prompt entries
   *  plus the matching messages, fetched together so the floating
   *  index panel and the agent terminal can't drift. Pass no
   *  cursor for the initial tail window; pass the earliest canonical
   *  seq with {@code direction: 'before'} to backfill on
   *  "↑ load earlier". */
  getTaskIndex: (
    id: string,
    opts?: { cursor?: number; limit?: number; direction?: 'initial' | 'before' },
  ) => Promise<ConvIndexPageDto>;
  /** Provider trace for only the typed request messages in the currently
   * loaded Trunk window. These rows are not conversation messages. */
  getTrunkTraceEvents: (
    id: string,
    requestMessageIds: string[],
  ) => Promise<TrunkTraceEventDto[]>;
  /** Work-unit tasks for a thread, oldest seq first. Drives the Tasks
   *  grouping in the Checkpoints rail and (in time) the Tasks-in-thread
   *  list. The Task model is described in the work-unit design note;
   *  not to be confused with the legacy "task = thread" alias still
   *  in place on most other bridge methods. */
  listTasksForThread: (threadId: string) => Promise<WorkUnitTaskDto[]>;
  /** Top-level Workspaces landing grid feed. One card per workspace
   *  with all the aggregates the landing renders (counts, today's
   *  spend, memory summary). Read-only. */
  listWorkspaces: () => Promise<WorkspaceCardDto[]>;
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
  /** Read a review pass by id with the full transcript + findings. */
  getReviewPass: (passId: string) => Promise<ReviewPassDetailDto | null>;
  /** Read the latest pass on a review thread — the URL the panel UI
   *  lives on uses the thread id, this resolves the pass for it. */
  getReviewPassByThread: (threadId: string) => Promise<ReviewPassDetailDto | null>;
  /** Durable publication projection; null before authorization. */
  getReviewPassPublication: (
    passId: string,
  ) => Promise<ReviewPassPublicationDto | null>;
  /** Workspace Insights aggregation — pulls active-thread + tasks-in-
   *  flight counts, today's spend, the window-wide spend total, and a
   *  per-day spend series for the chart. {@code window} is one of
   *  {@code "24h" | "7d" | "30d"}; the backend defaults to {@code 7d}
   *  on unknown values. */
  getWorkspaceInsights: (workspaceId: string, window: string) => Promise<WorkspaceInsightsDto>;
  /** Monthly AI usage ledger — total spend/calls + per-provider and
   *  per-task-type breakdowns. Month is YYYY-MM ('' = current month). */
  getAiLedger: (month: string) => Promise<AiLedgerDto>;
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
  /** Submit selected (or all) unresolved task-local review comments to the
   *  Development agent. The review stays private; lifecycle dispatch is
   *  observed through task state, so turnId is currently null. */
  submitReview: (
    taskId: string,
    payload?: {
      body?: string;
      verdict?: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
      commentIds?: string[];
    },
  ) => Promise<{ submitted: number; turnId: string | null }>;
  /** Backlog items on a thread, oldest-first (trunk Backlog tab). */
  listBacklog: (threadId: string) => Promise<BacklogItemDto[]>;
  /** Create a backlog item on the thread. */
  createBacklogItem: (
    threadId: string,
    title: string,
    body: string,
    tags: string[],
    priority?: string,
  ) => Promise<BacklogItemDto>;
  /** Partial update of a backlog item (omitted fields unchanged). */
  updateBacklogItem: (
    itemId: string,
    patch: { title?: string; body?: string; tags?: string[] },
  ) => Promise<BacklogItemDto>;
  /** Delete a backlog item. */
  deleteBacklogItem: (itemId: string) => Promise<void>;
  /** Begin trunk exploration of the item (posts its content to the trunk and
   *  marks it in-progress). The returned taskId is null — no task is cut. */
  startBacklogDevelopment: (itemId: string) => Promise<StartDevelopmentResponse>;
  /** Mark a backlog item not-to-proceed, with an optional reason. */
  skipBacklogItem: (itemId: string, reason?: string) => Promise<BacklogItemDto>;
  /** Restore a not-to-proceed item to created. */
  reviveBacklogItem: (itemId: string) => Promise<BacklogItemDto>;
  /** A thread's open agent questions (ask_user_question), oldest-first. */
  listThreadQuestions: (threadId: string) => Promise<AgentQuestionDto[]>;
  /** Answer an agent question (an option id and/or free-form text); the
   *  answer is posted as the next message for the waiting agent. */
  answerQuestion: (
    questionId: string,
    answerOptionId?: string,
    answerFreeForm?: string,
  ) => Promise<AgentQuestionDto>;
  /** A thread's passive signal feed, newest-first (Notifications tab). */
  listThreadSignals: (threadId: string) => Promise<ThreadSignalDto[]>;
  /** Mark a thread signal read. */
  markSignalRead: (signalId: string) => Promise<void>;
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
  /** Authorize the pass's one-shot durable GitHub review publication. */
  publishReviewPass: (
    passId: string,
    verdict: ReviewVerdictDto,
    findingIds: string[],
  ) => Promise<ReviewPassPublicationDto>;
  /** Spawn a build thread from a TERMINATE-d pass to apply its AGREED
   *  findings. {@code mode} is "author_is_reviewer" (forked off
   *  pr.head) or "suggested_change" (a zero-Task comment-only Trunk with
   *  frozen proposals that the user approves or discards). Throws on the
   *  backend's 409 / 422 gates (not TERMINATE, conflicting replay, no
   *  eligible findings, no / ambiguous workspace). */
  spawnBuildFromReview: (
    passId: string,
    opts?: {
      workspaceId?: string;
      openingTitle?: string;
      /** Omitted means all eligible; when present this is the exact subset. */
      selectedFindingIds?: string[];
    },
  ) => Promise<{ threadId: string; taskId: string | null; mode: string }>;
  /** Null for the writable author-is-reviewer path. */
  getReviewBuildCommentProposal: (
    passId: string,
  ) => Promise<ReviewBuildCommentProposalDto | null>;
  approveReviewBuildComments: (
    passId: string,
    commandId: string,
  ) => Promise<ReviewBuildCommentProposalDto>;
  discardReviewBuildComments: (
    passId: string,
    commandId: string,
  ) => Promise<ReviewBuildCommentProposalDto>;
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
  /** Close a task: interrupt the agent, mark it CANCELED, and reap its
   *  worktree + branch. Terminal and destructive — the caller confirms
   *  first. */
  cancelTask: (threadId: string, taskId: string) => Promise<WorkUnitTaskDto>;
  /** Pause an active task: stop its agent and park it at PAUSED with its
   *  worktree + session intact so it can be resumed. The thread won't run a
   *  paused task, freeing the user to work on something else. */
  pauseTask: (threadId: string, taskId: string) => Promise<WorkUnitTaskDto>;
  /** Resume a paused, errored, or archived task back to IDLE so it can run again. */
  resumePausedTask: (threadId: string, taskId: string) => Promise<WorkUnitTaskDto>;
  /** Explicitly restart an exhausted post-ship CI loop. Unlike ordinary
   *  Resume, this action may rerun failed GitHub Actions. */
  retryFailedCi: (threadId: string, taskId: string) => Promise<WorkUnitTaskDto>;
  /** Replace one exact failed Plan draft operation without resuming the Task. */
  recoverV2Plan: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ) => Promise<unknown>;
  /** Replace one exact malformed Development Brain TaskTurn. */
  recoverV2DevelopmentBrainReview: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ) => Promise<unknown>;
  /** Replace one exact failed CI/branch Remote repair Brain TaskTurn. */
  recoverV2RemoteRepairBrainReview: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ) => Promise<unknown>;
  /** Retry one exact accepted failed Local StageTurn from durable context. */
  recoverV2Stage: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ) => Promise<unknown>;
  /** Execute one exact owner-scoped V2 CI recovery command. */
  recoverV2Ci: (
    taskId: string,
    episodeId: string,
    command: {
      commandId: string;
      blockerId?: string | null;
      action: 'EXTEND_BUDGET' | 'CONTINUE_WITH_PER_PUSH_APPROVAL' | 'START_BASE_REPAIR' | 'START_BRANCH_SYNC' | 'RETRY_ONCE' | 'MANUAL_TAKEOVER' | 'STOP_AUTOMATION';
      rerunDelta: number;
      fixDelta: number;
      pushDelta: number;
      reason: string;
    },
  ) => Promise<unknown>;
  /** Suppress one exact exhausted BranchSync episode without calling CI. */
  recoverV2BranchSync: (
    taskId: string,
    episodeId: string,
    command: {
      blockerId: string;
      commandId: string;
      action: 'MANUAL_TAKEOVER' | 'STOP_AUTOMATION';
      reason: string;
    },
  ) => Promise<unknown>;
  /** Arm one exact durable repair for an open worktree quarantine. */
  recoverV2Worktree: (
    taskId: string,
    quarantineId: string,
    command: {
      blockerId: string;
      taskEpoch: number;
      stageId: string;
      stageGeneration: number;
      worktreePath: string;
      expectedBranchName: string;
      expectedCodeFingerprint: string;
      expectedHeadSha: string;
      expectedBaseSha: string;
      commandId: string;
      action: 'REPAIR_WORKTREE';
      reason: string;
    },
  ) => Promise<unknown>;
  /** Approve the exact open first-publish base-sync blocker. */
  approveV2LocalPublishBaseSync: (
    taskId: string,
    blockerId: string,
  ) => Promise<LocalPublishBaseSyncApprovalDto>;
  /** Extend one exact exhausted local base-sync episode by one attempt. */
  extendV2LocalPublishBaseSync: (
    taskId: string,
    episodeId: string,
    blockerId: string,
    command: { commandId: string; reason: string },
  ) => Promise<import('./types/brainView').LocalPublishBaseSyncExtensionDto>;
  /** Execute one exact owner-scoped V2 Cleanup recovery command. */
  recoverV2Cleanup: (
    taskId: string,
    stepId: string,
    command: {
      commandId: string;
      action: 'RETRY' | 'WAIVE_OPTIONAL';
      reason: string;
    },
  ) => Promise<unknown>;
  /** Read the task's auto-approve mode (gates + tool prompts auto-approve,
   *  except the final PR merge). */
  getTaskAutoApprove: (
    threadId: string,
    taskId: string,
  ) => Promise<{ enabled: boolean }>;
  /** Flip the task's auto-approve mode; returns the persisted value. */
  setTaskAutoApprove: (
    threadId: string,
    taskId: string,
    enabled: boolean,
  ) => Promise<{ enabled: boolean }>;
  /** Read the task's auto-merge mode (on top of auto-approve, the final PR
   *  merge itself also approves automatically). */
  getTaskAutoMerge: (
    threadId: string,
    taskId: string,
  ) => Promise<{ enabled: boolean }>;
  /** Flip the task's auto-merge mode; returns the persisted value. Enabling
   *  it 409s unless the task's latest plan reads risk=low/effort=small. */
  setTaskAutoMerge: (
    threadId: string,
    taskId: string,
    enabled: boolean,
  ) => Promise<{ enabled: boolean }>;
  /** Read the task's minimum-approvals gate — write-permission approvals a
   *  shipped PR needs before it counts as merge-ready (0/1/2). */
  getTaskMinApprovals: (
    threadId: string,
    taskId: string,
  ) => Promise<{ minApprovals: number }>;
  /** Set the task's minimum-approvals gate; returns the persisted (clamped) value. */
  setTaskMinApprovals: (
    threadId: string,
    taskId: string,
    minApprovals: number,
  ) => Promise<{ minApprovals: number }>;
  /** Drives the Trunk planning agent for cross-task talk. The persisted row lands
   *  with {@code task_id = null} so it filters into the trunk slice
   *  rather than any Task's segment. */
  sendTrunkMessage: (
    threadId: string,
    input: string,
    images?: string[],
  ) => Promise<ThreadSendResultDto>;
  /** Resolves an attached image's saved path (from a message's `images`
   *  field) into a renderable data URL. */
  readAttachment: (threadId: string, path: string) => Promise<string>;
  /** Recent scheduler turns, newest first. Used to distinguish
   *  queued work from an active CLI/API run. */
  getTaskTurns: (id: string) => Promise<ThreadTurnDto[]>;
  /** Open durable V2 permission prompts owned anywhere under this Trunk. */
  getTypedPermissions: (id: string) => Promise<TypedPermissionRequestDto[]>;
  /** Reply to a {@code permission_request}. When {@code preApprove}
   *  is supplied, the backend records the per-call decision and then
   *  grants an auto-approval budget for future invocations of the same
   *  tool — {@code count} positive sets a finite quota, {@code -1}
   *  means "always for this tool" until the session ends.
   *  {@code status: 'already_resolved'} means this specific prompt had
   *  already timed out (or was already decided) before the click landed —
   *  the backend's decision-timeout window is 2 minutes, so a prompt a
   *  user notices late can resolve itself before they act on it. */
  decideTaskPermission: (
    id: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
    expectedRevision?: number,
  ) => Promise<{ status: 'recorded' | 'already_resolved' }>;
  /** Cancel the in-flight turn (Ctrl+C semantics). The session
   *  itself stays alive — the user can send another turn. */
  interruptTask: (id: string, turnId?: string) => Promise<void>;
  /** Interrupt only the Task agent currently executing this stage. */
  interruptStage: (id: string) => Promise<void>;

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
  /** Subscribe to the runtime that owns this stage; the backend resolves
   *  Stage -> Task -> agent and validates every hop. */
  subscribeStageStream: (
    stageId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => () => void;
  /** Live agent output for a sync run. Each event's `data` is one line of the
   *  CLI's JSONL, so it reads with the same parser as the stored transcript. */
  subscribeSyncRunStream: (
    jobId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => () => void;
  /** The same, for phase 2 — its turns run under the harness watch. */
  subscribeHarnessStream: (
    watchId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => () => void;

  // ── Brain agent (per-task read-only conversational surface) ──────
  /** Full brain-view payload for a task: aggregate strip, stages,
   *  brain feed, right rail, scrubbers. Polled by the brain view. */
  getBrainView: (taskId: string) => Promise<TaskBrainViewData>;
  /** Post a question to the task's brain agent. Returns the answering
   *  turn id and the brain thread id (subscribe to its stream). */
  sendBrainMessage: (taskId: string, text: string, images?: string[]) => Promise<BrainMessageResult>;
  /** Drill-in detail for one stage: iteration log, metrics, realtime CI. */
  getStageDetail: (stageId: string) => Promise<StageDetailData>;
  /** A task's agent runs (live and finished) — the Dev stage feed folds the
   *  `ci_fix`-kind ones (not tied to a review round) into episodes. */
  getTaskRuns: (taskId: string) => Promise<AgentRunDto[]>;
  /** One run's record; stage-backed runs can reuse the stage-detail log. */
  getAgentRun: (runId: string) => Promise<AgentRunDto>;
  /** A task's review rounds, newest-first — the Comments stage feed. */
  getTaskRounds: (taskId: string) => Promise<ReviewRoundDto[]>;
  /** User-gated round approval: posts the round's drafted replies + pushes
   *  its commits. */
  approveRound: (roundId: string) => Promise<ReviewRoundDto>;

  // ── PR (unified local/external aggregate) ─────────────────────────────
  /** Resolver — the task's PR id (as a full `PR`), or null if it has none yet. */
  getPrForTask: (taskId: string) => Promise<LocalPR | null>;
  /** Resolver for the standalone details page — a GitHub PR not tied to a
   *  ByteQuay task. Creates the row (origin=external) on first sight,
   *  syncing on every call after; never null (throws on a bad repo/number). */
  getPrForRepoPull: (owner: string, repo: string, number: number) => Promise<LocalPR>;
  /** The whole PR (row + commits + timeline + checks + comments + strip
   *  count) in one call, keyed by PR id, or null if it doesn't exist. */
  getLocalPrBundle: (prId: string) => Promise<LocalPRBundle | null>;
  /** Edit a local PR's title/description (PATCH /api/prs/{id}) — used to
   *  tweak the body in the push dialog before it opens the PR on GitHub. */
  updateLocalPrDetails: (prId: string, body: { title?: string; description?: string }) => Promise<LocalPR>;
  /** User-gated push: push the branch, open a Draft PR, strip local-only
   *  history, and flip local-open → remote-drafted. */
  pushLocalPr: (prId: string) => Promise<LocalPR>;
  /** User-gated merge of a pushed PR with the chosen method — enqueues via
   *  GitHub's merge queue instead when the target branch requires one. */
  mergeLocalPr: (prId: string, method: string) => Promise<LocalPR>;
  /** User-gated removal of a pushed PR from its repo's merge queue. */
  dequeueLocalPr: (prId: string) => Promise<LocalPR>;
  /** User-gated deletion of a merged PR's head branch on GitHub. */
  deleteLocalPrBranch: (prId: string) => Promise<LocalPR>;
  /** Explicitly post a top-level PR comment to GitHub. */
  postRemotePrComment: (prId: string, body: string) => Promise<LocalPR>;
  /** Batch every unpublished draft comment into one GitHub review
   *  (external PRs only — see {@code PRCapabilities.publishReview}). */
  publishLocalPrReview: (
    prId: string,
    body?: { verdict: 'APPROVE' | 'COMMENT' | 'REQUEST_CHANGES'; findingIds: string[]; comments: string[]; body?: string | null },
  ) => Promise<LocalPR | LocalPrReviewPublicationDto>;
  /** Current durable taskless review publication, if one has been
   * authorized. Used to restore queued and terminal state after restart. */
  getLocalPrReviewPublication: (
    prId: string,
  ) => Promise<LocalPrReviewPublicationDto | null>;
  /** Persisted investigation-review aggregate; null means this PR has never
   *  been reviewed. Every review surface consumes this exact payload. */
  getAgentReview: (prId: string) => Promise<AgentReviewData | null>;
  /** One-seat, diff-only ReviewAssignmentTurn for an unwatched external PR. */
  startQuickReview: (prId: string) => Promise<{ state: 'RUNNING' }>;
  getQuickReviewStatus: (prId: string) => Promise<{
    state: 'IDLE' | 'RUNNING' | 'DONE' | 'FAILED';
    error: string | null;
  }>;
  getLatestQuickReview: (prId: string) => Promise<AgentReviewData | null>;
  startAgentReview: (
    prId: string,
    body?: { runner?: 'api' | 'cli'; providerId?: string; workspaceId?: string },
  ) => Promise<AgentReviewData>;
  getAgentReviewByThread: (threadId: string) => Promise<AgentReviewData | null>;
  continueAgentReview: (
    reviewId: string,
    body: {
      kind: 'continue' | 're-review' | 'continuation';
      findingIds?: string[];
      runner?: 'api' | 'cli';
      providerId?: string;
      seed?: string;
      costCapCents?: number;
    },
  ) => Promise<AgentReviewData>;
  sendAgentReviewRoundMessage: (
    roundId: string,
    body: { target: string; text: string },
  ) => Promise<AgentReviewData>;
  updateAgentReviewRoundBudget: (
    roundId: string,
    body: { costCapCents: number },
  ) => Promise<AgentReviewData>;
  answerAgentReviewFinding: (findingId: string, text: string) => Promise<AgentReviewData>;
  mutateAgentReviewFinding: (
    findingId: string,
    body: { action: 'dismiss' | 'include' | 'exclude' | 'editDraft' | 'reopen' | 'resolve'; text?: string },
  ) => Promise<AgentReviewData>;
  getAgentReviewRoundLog: (roundId: string) => Promise<AgentReviewData>;
  cancelAgentReviewRound: (roundId: string) => Promise<AgentReviewData>;
  /** Add a user comment to the local PR (PR-level or inline file-line).
   *  `side` is 'LEFT'/'RIGHT' (undefined defaults to RIGHT); `startLine`/
   *  `startSide` are set only for a multi-line range. */
  addLocalPrComment: (
    prId: string,
    body: {
      scope: 'pr' | 'file-line';
      filePath?: string | null;
      lineNumber?: number | null;
      side?: 'LEFT' | 'RIGHT';
      startLine?: number | null;
      startSide?: 'LEFT' | 'RIGHT' | null;
      body: string;
      parentCommentId?: string | null;
    },
  ) => Promise<LocalPRComment>;
  /** Mark a local PR comment resolved. */
  resolveLocalPrComment: (commentId: string) => Promise<LocalPRComment>;
  /** Delete an unpublished local draft comment. */
  deleteLocalPrComment: (commentId: string) => Promise<void>;
  /** Dismiss a local PR comment (closed without action). */
  dismissLocalPrComment: (commentId: string) => Promise<LocalPRComment>;
  /** Reopen a resolved/dismissed local PR comment. */
  reopenLocalPrComment: (commentId: string) => Promise<LocalPRComment>;
  /** On-demand local test run (design doc slice 4); returns the local PR's
   *  updated check list. */
  runLocalPrTests: (prId: string) => Promise<LocalPRCheck[]>;

  // ── PR dashboard (unified pr* backing, replacing the legacy pull_requests
  //    sweep — unified-pr-view.md U3) ─────────────────────────────────────
  /** Every PR the last {@code syncList} pass watched, paired with its
   *  triage state. */
  fetchDashboardPrs: () => Promise<DashboardPR[]>;
  /** Explicit user-triggered refresh — always sweeps GitHub. */
  syncDashboardPrs: () => Promise<DashboardPR[]>;
  /** Marks a PR handled with the given action, without any GitHub call. */
  markDashboardPrHandled: (prId: string, action: HandledAction) => Promise<void>;
  /** Submits a GitHub approval review, then records it as handled locally. */
  approveDashboardPr: (prId: string) => Promise<void>;

  /** Steer a stage's dev agent: enqueue the user's message as a turn on the
   *  task's dev thread. `images` are pasted-screenshot data URLs, saved and
   *  folded into the turn the same way trunk/task-brain sends do. Returns
   *  the enqueued turn id. */
  steerStage: (
    stageId: string,
    text: string,
    images?: string[],
    mode?: 'APPEND' | 'CANCEL_AND_REPLACE',
    expectedPredecessorStageTurnId?: string,
  ) => Promise<{ turnId: string }>;
  /** Read the exact ready-but-unmergeable V2 action token, if one exists. */
  getV2ReadinessAssistance: (
    taskId: string,
    stageId: string,
  ) => Promise<ReadinessAssistanceAvailability | null>;
  /** Manually authorize one exact maintainer nudge or reviewer request. */
  authorizeV2ReadinessAssistance: (
    taskId: string,
    stageId: string,
    body: ReadinessAssistanceRequest,
  ) => Promise<{ actionId: string; status: string }>;
  /** Approve the task's plan: closes the PlanStage, opens the
   *  DevelopmentStage, and returns its id (+ redirect path) so the view can
   *  auto-navigate to the dev stage detail page. */
  approvePlan: (planStageId: string) => Promise<{ devStageId: string; redirectUrl: string }>;
  /** Commits authored in the thread's workingDir since thread.createdAt,
   *  most-recent first. Limited to 100. `taskId` disambiguates which of the
   *  thread's tasks to scope to — a thread can carry more than one task, and
   *  omitting it falls back to the thread's latest task, which is wrong once
   *  the viewed task isn't that one. */
  listTaskCommits: (id: string, taskId?: string) => Promise<ThreadCommitDto[]>;
  /** The task's full diff against its base branch, shaped like the PR
   *  review's DiffFileDto so the same diff component renders it. `taskId`
   *  disambiguates which task on the thread — see {@link listTaskCommits}. */
  getTaskCumulativeDiff: (id: string, taskId?: string) => Promise<DiffFileDto[]>;
  /** Full file content from the task worktree, split into lines for diff expansion. */
  fetchTaskFileBlob: (id: string, taskId: string, path: string) => Promise<{ lines: string[] }>;
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

export type InAppNavState = {
  url: string;
  title: string;
  canGoBack: boolean;
  canGoForward: boolean;
  loading: boolean;
};
