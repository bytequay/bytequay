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

import type * as agentContracts from './agents/contracts';
import type { LabBridge } from '@bytequay/lab/contracts';
import type { ArtifactBridge } from './artifacts/contracts';
import type { FocusBreakBackground } from './focus/contracts';
import type { VoiceBridge } from './dictation/contracts';
import type { ZoomedReference } from './githubReference';
import type { WorkItemBridge, CreateWorkItem, EpicDraftMessage, EpicDraftReply, EpicJoinDto, EpicRecordDto, WorkItemDto, WorkItemDecision, GoalItemDto, GoalItemKind } from './workitems/contracts';
import type { NoticeSnapshot } from './notices/contracts';
import type { RunningTask } from './agents/runningTaskModel';
import type { GoogleBridge } from '@bytequay/google/contracts';
import type { JiraBridge, JiraSourceRequest, JiraTaskSource } from './jira/contracts';
import type { TeamActivityQuery, TeamActivitySnapshot, TeamCircleInput, TeamCreateInput, TeamDirectory, TeamMemberInput } from './teams/types';
import { AgentEventEnvelope } from './agents/events';
import type { AskCommentDiscussionRequest, AskDiscussionRequest, DiscussionQuery, DraftDiscussionReplyRequest, OpenDiscussionRequest, ResolveDiscussionRequest, RetryDiscussionRequest, TaskDiscussionDto } from './agents/discussions/contracts';
import type { CreateDevelopmentLearningCommentRequest, CreateLocalReviewRequest, LocalReviewQuery, ReplyLocalReviewRequest, ResolveLocalReviewRequest, RetryLocalReviewRequest } from './pulls/local-review/contracts';
import type { LocalReviewThreadDto } from './pulls/local-review/types';
import type {
  ContextBuildViewDto,
  ContextSpecCheckDto,
  ContextSpecCheckStartedDto,
  ContextSpecModuleDto,
  ContextSpecSourceDto,
  StartContextSpecCheckRequest,
} from './repository-context/contracts';
import type { MemoryHarvestStartedDto, MemoryPageDto, MemoryPullDto, MemorySwitchesDto, StartMemoryHarvestRequest } from './memory/contracts';
import type { MarkReplayRequest, ReplayDto, StartReplayRequest } from './memory/replays';
import type {
  AgentReviewDto,
  ReviewDraftDto,
  ReviewEvent,
  ReviewSide,
} from './pulls/agent-review/contracts';
import type { WorktreeChangesDto, WorktreeChangesRequest, CodeHostRef, LocalRunDto, LocalRunOutputDto, ReviewCodeFileDto, ReviewCodeFilesDto } from './pulls/code-tests/contracts';

type CiStatus = 'PASSING' | 'FAILING' | 'PENDING' | 'NONE';
export type PullRequestDto = {
  id: number;
  repo: string;
  number: number;
  title: string;
  author: string | null;
  authorAvatarUrl?: string | null;
  htmlUrl: string;
  createdAt: string | null;
  updatedAt: string;
  origin: 'AUTHORED' | 'REVIEW_REQUESTED' | 'OTHER';
  labels: string[];
  labelColors: Record<string, string> | null;
  draft: boolean;
  viewedAt: string | null;
  reviewedAt: string | null;
  handledAction: 'APPROVED' | 'MERGED' | 'CHANGES_REQUESTED' | 'COMMENTED' | null;
  requestedReviewers: string[];
  ciStatus: CiStatus | null;
  additions: number;
  deletions: number;
  commentCount: number;
  state: string | null;
  mergeable: boolean | null;
  mergeableState: string | null;
  headPushedAt: string | null;
  headRef: string | null;
  mergedAt?: string | null;
  /** Local monitoring baseline and latest observed GitHub activity. */
  monitoredAt?: string | null;
  activityAt?: string | null;
  reviewRequestedAt?: string | null;
  reviewDecision?: 'APPROVED' | 'CHANGES_REQUESTED' | null;
  /** Latest activity that ends an acknowledgment in Needs attention; pushes, labels and bot comments are not part of it. */
  attentionActivityAt?: string | null;
  /** Latest known GitHub action; local change-detection timestamps are separate. */
  latestActivity?: {
    event: string;
    actor: string | null;
    timestamp: string;
    state: string | null;
    label: string | null;
    requestedReviewer: string | null;
    /** The start of a comment's or review's text, and where an inline comment was left. */
    excerpt?: string | null;
    path?: string | null;
    line?: number | null;
  } | null;
  /** Needs attention only: cached commits after the viewer's last review, newest first. */
  commitsSinceReview?: { sha: string; message: string; actor: string | null; committedAt: string }[];
  /** Needs attention only: the latest checks failing on a PR whose CI fails. */
  failingChecks?: string[];
  /** Needs attention only: the viewer's GitHub inbox files this PR as a direct @mention. */
  mentioned?: boolean;
};

/** An attention poll as main sends it: every row's id in order, and only the rows that changed since the last update. */
export type PrAttentionUpdate = { ids: number[]; changed: PullRequestDto[] };

export type PullRequestPageDto = {
  pullRequests: PullRequestDto[];
  page: number;
  pageSize: number;
  totalPullRequests: number;
  openPullRequests: number;
  hasMore: boolean;
};

export type DiffFileDto = {
  filename: string;
  status: string;
  additions: number;
  deletions: number;
  patch: string | null;
};

export type DiffCommitDto = {
  sha: string;
  parentSha: string | null;
  message: string | null;
  author: string | null;
  authoredAt: string | null;
};

export type DiffFileContentDto = {
  oldContent: string | null;
  newContent: string | null;
};

export type GitHubCodeSnippetRequest = {
  owner: string;
  repo: string;
  commit: string;
  path: string;
  startLine: number;
  endLine: number;
};

export type GitHubCodeSnippetDto = {
  lines: Array<{ number: number; content: string }>;
};

export type GitHubIssuePreviewDto = {
  htmlUrl: string;
  authorAvatarUrl: string;
  title: string;
  body: string;
  author: string;
  labels: string[];
  updatedAt: string;
  state: string;
  pullRequest: boolean;
};

export type InlineReviewCommentDto = {
  replyToCommentId?: number | null;
  path: string;
  body: string;
  line: number;
  side: 'LEFT' | 'RIGHT';
  startLine: number | null;
  startSide: 'LEFT' | 'RIGHT' | null;
  commitId: string | null;
};

export type RepoUserDto = {
  login: string;
  avatarUrl: string | null;
  bot: boolean;
};

export type RepoLabelDto = {
  id: number;
  name: string;
  color: string;
};

type MergeModeDto = { mergeQueue: boolean };

export type PullRequestConflictsDto = {
  baseRef: string;
  files: string[];
};

export type PullRequestViewer = { login: string; avatarUrl: string | null };

export type PullRequestDetailDto = {
  id: number;
  repo: string;
  number: number;
  title: string | null;
  htmlUrl: string | null;
  state: string | null;
  draft: boolean;
  mergeable: boolean | null;
  mergeableState: string | null;
  author: string | null;
  authorAvatarUrl: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  mergedAt: string | null;
  baseRef: string | null;
  headRef: string | null;
  headSha: string | null;
  body: string | null;
  labels: Array<{ name: string; color: string | null }>;
  assignees: string[];
  milestone: string | null;
  reviewers: Array<{ login: string; avatarUrl: string | null; state: string }>;
  additions: number;
  deletions: number;
  changedFiles: number;
  approvalCount: number;
  ciStatus: CiStatus;
  recentActivity: Array<{
    actor: string;
    eventType: string;
    timestamp: string | null;
    body: string | null;
    githubId: number | null;
    payload: PullTimelinePayloadDto;
  }>;
  checkRuns: Array<{
    githubId: number | null;
    name: string | null;
    status: string | null;
    conclusion: string | null;
    detailsUrl: string | null;
    source: string | null;
    startedAt: string | null;
    completedAt: string | null;
    category: 'failing' | 'pending' | 'successful' | 'neutral' | 'skipped' | 'cancelled';
  }>;
  unavailableSections: string[];
  viewerCanWrite: boolean;
};

export type PullRequestStatusRefreshDto = Pick<PullRequestDetailDto,
  'mergeable' | 'mergeableState' | 'ciStatus' | 'checkRuns' | 'unavailableSections' | 'viewerCanWrite'>;

export type PullRequestDetailRefreshDto =
  | { full: true; detail: PullRequestDetailDto; status: null }
  | {
      full: false;
      detail: null;
      status: PullRequestStatusRefreshDto;
    };

export type PullReactionTarget = 'pull_request' | 'issue_comment' | 'review_comment';
export type PullCommentTarget = 'issue_comment' | 'review_comment' | 'review';
export type PullReactionContent = '+1' | '-1' | 'laugh' | 'hooray' | 'confused' | 'heart' | 'rocket' | 'eyes';
export type PullReactionDto = {
  content: PullReactionContent;
  count: number;
  viewerHasReacted?: boolean | null;
};

export type PullTimelineCommentDto = {
  githubId: number | null;
  actor: string | null;
  actorAvatarUrl: string | null;
  actorBot: boolean;
  timestamp: string | null;
  body: string | null;
  htmlUrl: string | null;
  reviewId: number | null;
  inReplyToId: number | null;
  reactions: PullReactionDto[];
};

export type PullMergeQueueRemovalDto = {
  reason: string | null;
  commitUrl: string | null;
  failedChecks: Array<{ name: string; url: string | null }>;
};

export type PullTimelinePayloadDto = {
  mergeQueueRemoval?: PullMergeQueueRemovalDto | null;
  actorAvatarUrl: string | null;
  actorBot: boolean;
  state: string | null;
  htmlUrl: string | null;
  label: string | null;
  labelColor: string | null;
  renameFrom: string | null;
  renameTo: string | null;
  commitSha: string | null;
  commitMessage: string | null;
  sourceTitle: string | null;
  sourceUrl: string | null;
  sourceNumber: number | null;
  milestoneTitle: string | null;
  requestedReviewer: string | null;
  assignee: string | null;
  path: string | null;
  diffHunk: string | null;
  line: number | null;
  originalLine: number | null;
  startLine: number | null;
  originalStartLine: number | null;
  side: string | null;
  resolved: boolean;
  outdated: boolean;
  reactions: PullReactionDto[];
  comments: PullTimelineCommentDto[];
};

export type RepoRefDto = {
  owner: string;
  repo: string;
};

export type RepositorySearchResultDto = {
  id: number;
  repository: RepoRefDto;
  fullName: string;
  description: string | null;
  visibility: string;
  fork: boolean;
  defaultBranch: string;
  upstream: RepoRefDto | null;
  existingWorkspaceId: string | null;
};

export type WatchRepositoryTopology = 'DIRECT' | 'FORK';

export type WatchRepositoryPreviewDto = {
  selectedRepositoryId: number;
  selectedRepository: RepoRefDto;
  visibility: string;
  topology: WatchRepositoryTopology;
  recommendedTopology: WatchRepositoryTopology;
  forkModeAvailable: boolean;
  upstreamRepositoryId: number | null;
  upstreamRepository: RepoRefDto | null;
  monitoredRepositoryId: number;
  monitoredRepository: RepoRefDto;
  defaultBranch: string;
  developmentBase: string;
  pushRemote: string;
  pushRepository: RepoRefDto;
  pushWritable: boolean;
  forkRequired: boolean;
  pullRequestBase: RepoRefDto;
  fingerprint: string;
  existingWorkspaceId: string | null;
};

export type WatchRepositoryCommand = {
  selectedRepositoryId: number;
  topology: WatchRepositoryTopology;
  previewFingerprint: string;
  allowForkCreation: boolean;
  name?: string;
};

export type HostingServerDto = { provider: 'GITHUB' | 'GITLAB'; webBaseUrl: string; apiBaseUrl: string };
export type GitLabCliStatusDto = { ready: boolean; homebrewAvailable: boolean };
export type HostingConnectionDto = {
  account: { id: number; instanceId: number; remoteId: string; login: string };
  instance: HostingServerDto & { id: number };
  connected: boolean;
};
export type HostingRepositoryDto = {
  id: number; instanceId: number; remoteId: string; fullPath: string; webUrl: string;
  httpCloneUrl: string; sshCloneUrl: string | null; defaultBranch: string | null;
};
export type WorkspaceHostingBindingDto = {
  workspaceId: string; accountId: number; selectedRepositoryId: number; upstreamRepositoryId: number | null;
};
export type HostingPullRequestSummaryDto = {
  repo: string; number: number; title: string; author: string; htmlUrl: string;
  createdAt: string; updatedAt: string; origin: PullRequestDto['origin'];
  labels: string[]; labelColors: Record<string, string>; draft: boolean; state: string;
  closedAt: string | null; mergedAt: string | null; requestedReviewers: string[]; headRef: string | null;
};
export type WorkspaceHostingRequestDto = { summary: HostingPullRequestSummaryDto; pullRequest: PullRequestDto };
export type WorkspaceHostingAttentionDto = { login: string | null; pullRequests: PullRequestDto[] };
export type WorkspaceHostingRequestsDto = { items: WorkspaceHostingRequestDto[]; hasNext: boolean };
export type HostingPullRequestMetadataDto = {
  title: string; htmlUrl: string; state: string; author: string; authorAvatarUrl: string | null;
  createdAt: string; updatedAt: string; mergedAt: string | null; body: string | null;
  labels: string[]; labelColors: Record<string, string>; assignees: string[]; milestone: string | null;
  draft: boolean; requestedReviewerCount: number; requestedReviewers: string[]; headRef: string | null; baseRef: string | null;
};
export type HostingPullRequestSnapshotDto = {
  pullRequest: HostingPullRequestSummaryDto; detail: HostingPullRequestMetadataDto; validatedAt: string;
};

export type WorkspaceDto = {
  hostingServer?: HostingServerDto | null;
  id: string;
  name: string;
  selectedRepositoryId: number;
  selectedOwner: string;
  selectedRepo: string;
  upstreamRepositoryId: number | null;
  upstreamOwner: string | null;
  upstreamRepo: string | null;
  monitoredRepositoryId: number;
  monitoredOwner: string;
  monitoredRepo: string;
  topology: 'DIRECT' | 'FORK';
  defaultBranch: string;
  upstreamDefaultBranch: string | null;
  localRepositoryPath: string;
  lastCodeSyncAt: string | null;
  lastMetadataSyncAt: string | null;
  displayOrder: number;
};

export type RepositoryMetadataDto = {
  repositoryId: number;
  repository: { owner: string; repo: string };
  contributors: Array<{ id: number; login: string; avatarUrl: string; bot: boolean }>;
  labels: Array<{ id: number; name: string; color: string }>;
  topics: string[];
  contributorsSyncedAt: string | null;
  labelsTopicsSyncedAt: string | null;
};

export type IssueDevelopmentTaskDto = { taskId: string; workspaceId: string };
export type IssueDevelopmentDto = { isEpic: boolean; developmentTask: IssueDevelopmentTaskDto | null };
export type IssueTaskSourceRequest = {
  issueId: number;
  number: number;
  updatedAt: string;
  /** The issue's ended task a new attempt replaces; without it, the issue's current task opens. */
  replaces?: string;
};
export type AgentIssueSourceDto = IssueTaskSourceRequest & {
  initialTurnId: string | null;
  instructions: string;
  host: string;
  repo: string;
  url: string;
  title: string;
  body: string;
};

/** An open issue the viewer opened, in any repository. */
export type AuthoredIssueDto = {
  repo: string;
  number: number;
  title: string;
  htmlUrl: string;
  labels: string[];
  assignees: string[];
  commentCount: number;
  createdAt: string;
  updatedAt: string;
};

/** A workspace's open issues, or a null snapshot when they are unchanged since the read whose etag the caller passed. */
export type IssueAttentionRead = { etag: string | null; snapshot: RepositoryIssueSnapshotDto | null };

export type RepositoryIssueSnapshotDto = {
  repositoryId: number;
  repository: { owner: string; repo: string };
  issues: Array<{
    isEpic?: boolean;
    developmentTask?: IssueDevelopmentTaskDto | null;
    id: number;
    number: number;
    title: string;
    body: string;
    author: string;
    authorAvatarUrl: string;
    htmlUrl: string;
    labels: string[];
    assignees: string[];
    commentCount: number;
    createdAt: string;
    updatedAt: string;
  }>;
  page: number;
  pageSize: number;
  totalIssues: number;
  hasMore: boolean;
  syncedAt: string | null;
};

export type WorkspaceIssueTimelineEventDto = {
  githubId: number | null;
  event: string;
  actor: string | null;
  timestamp: string | null;
  body: string | null;
  payload: PullTimelinePayloadDto;
};

export type WorkspaceIssueTypeDto = {
  current: string | null;
  available: string[];
};

export type WorkspaceIssueDetailDto = {
  repositoryId: number;
  issueNumber: number;
  timeline: WorkspaceIssueTimelineEventDto[];
  currentType: string | null;
  availableTypes: string[];
  refreshedAt: string | null;
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
  company: string | null;
  email: string | null;
};

export type PullRequestTaskLink = { workspaceId: string; taskId: string };

export type ContributionDayDto = { date: string; contributionCount: number; color: string };
export type ContributionCalendarDto = {
  totalContributions: number;
  weeks: Array<{ days: ContributionDayDto[] }>;
};
export type UserCommitDto = {
  sha: string;
  repoFullName: string;
  shortMessage: string;
  htmlUrl: string;
  authoredAt: string | null;
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
  actorAvatarUrl?: string | null;
  detail?: string | null;
  ref?: string | null;
  reviewState?: string | null;
  pushCount?: number;
  commentCount?: number;
};

export type InAppNavState = {
  url: string;
  title: string;
  canGoBack: boolean;
  canGoForward: boolean;
  loading: boolean;
};

export type InAppBrowserTab = InAppNavState & {
  id: string;
  pinned: boolean;
  /** The name the user gave this page; null while it goes by the page's own title. */
  name: string | null;
  favicon: string | null;
  error: string | null;
};

export type InAppBrowserState = {
  tabs: InAppBrowserTab[];
  activeTabId: string | null;
};

/** Match counts for the app or a browser page's find-in-page search. */
export type FindResult = {
  activeMatchOrdinal: number;
  matches: number;
  tabId?: string;
};

export type CherryPickPreviewCommit = {
  position: number;
  sourceSha: string;
  title: string;
  author: string;
  committedAt: string;
  changedPaths: string[];
  /** Backed by ancestry or an exact `-x` line only, never a title or a guess. */
  alreadyOnTarget: boolean;
  targetMatchKind?: string;
  /** CLEAN | CONFLICT | NOT_ANALYZED */
  conflictStatus: string;
  /** Paths a simulated pick would conflict on. */
  conflictFiles: string[];
  pullRequestNumber?: number;
  pullRequestTitle?: string;
  pullRequestUrl?: string;
  pullRequestMergedAt?: string;
};

export type CherryPickPreview = {
  previewId: string;
  repository: string;
  sourceRemote: string;
  targetRemote: string;
  targetBranch: string;
  localTargetBranch: string;
  sourceBaseSha: string;
  sourceTipSha: string;
  targetSha: string;
  commits: CherryPickPreviewCommit[];
  warnings: string[];
  expiresAt: string;
};

export type CherryPickPreviewRequest = {
  workspaceId: string;
  /** Configured remote to read the range from; the linked upstream when absent. */
  sourceRemote?: string;
  sourceBaseRef: string;
  sourceTipRef: string;
  targetBranch: string;
};

/** A merged pull request of `owner/repo`, read through one of the workspace's source remotes. */
export type CherryPickPullRequestPreviewRequest = {
  workspaceId: string;
  sourceRemote: string;
  owner: string;
  repo: string;
  number: number;
  targetBranch: string;
};

export type AgentProviderReadiness =
  | 'READY'
  | 'AUTHENTICATION_REQUIRED'
  | 'CLI_MISSING'
  | 'UNSUPPORTED'
  | 'DEGRADED'
  | 'TEMPORARILY_UNAVAILABLE';

/** Provider facts that control what the shared task UI may truthfully offer or render. */
export type AgentCapabilitiesDto = {
  structuredPlans: boolean;
  nestedAgentActivity: boolean;
  steering: boolean;
  permissionPolicies: string[];
  attachmentMediaTypes: string[];
};

/** What a turn or task did with tools, folded from persisted events when each turn ended. */
export type ToolCountersDto = {
  toolCalls: number;
  exploreActions: number;
  /** Times the provider compacted the task's own conversation. */
  compactions: number;
};

/** Token and cost totals the backend reports for a task or turn. */
export type AgentMetricsDto = {
  inputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
  cost: number | null;
  elapsed: string;
  counters?: ToolCountersDto;
};

export type ReviewFixSummaryDto = {
  /** Asked of the agent and not yet answered or posted. */
  waiting: number;
  fixed: number;
  declined: number;
  /** Posts that failed or whose outcome is unknown, and comments the agent never answered. */
  needsYou: number;
};

export type AutoMergeSummaryDto = {
  state: 'WAITING' | 'READY' | 'MERGING' | 'MERGED' | 'QUEUED' | 'FAILED' | 'CANCELLED';
  /** When a ready pull request merges. */
  mergeAt: string | null;
  /** Why it waits, or what the merge did. */
  message: string | null;
};

/** How far a task's delivery goes; each level includes every level before it. */
export type AutomationLevel = 'COMMIT' | 'DRAFT_PR' | 'CI_FIX' | 'REVIEW_FIX' | 'MERGE';

export type DevelopmentAutomationDto = ReturnType<typeof agentContracts.parseDevelopmentAutomation>;

export type AgentTaskDefaultsDto = {
  developmentAutomation?: DevelopmentAutomationDto | null;
  model: string | null;
  effort: string | null;
  /** Null or absent uses standard speed; Fast uses the model catalog's opaque tier id. */
  serviceTier?: string | null;
  permissionPolicy: string;
};

export type AgentTaskSummaryDto = {
  finalResponse: string | null;
  resultBranch: string | null;
  resultHead: string | null;
  metrics: AgentMetricsDto;
  notes: string | null;
  /** What is kept about each subagent once the transcript that held its steps is gone. */
  children: AgentChildSummaryDto[];
};

export type AgentChildSummaryDto = {
  threadId: string;
  parentThreadId: string | null;
  label: string;
  purpose: string | null;
  outcome: string;
  result: string | null;
  startedAt: string | null;
  endedAt: string | null;
};

/**
 * The one operation currently allowed to touch the task's checkout.
 *
 * A claim that outlives the task's turns means the provider could not be shown to have stopped
 * writing; every mutation control stays disabled until it is resolved.
 */
export type AgentMutationClaimDto = {
  kind: string;
  ownerId: string;
  acquiredAt: string;
};

/** What a task list row names about its pull request. */
export type AgentTaskPullRequestDto = Pick<PullRequestDto, 'number' | 'title' | 'state' | 'draft'>;

/** What a task's checkout holds beyond its start: files not yet committed, and commits made since. */
export type AgentTaskChangesDto = { uncommitted: boolean; commits: number };

/** What a task last said and waits on, read from local stores without reaching its provider. */
export type AgentTaskActivityDto = {
  /** The start of the newest root message; the task's generated first request carries its turn. */
  lastMessage: { author: 'USER' | 'AGENT'; text: string; at: string; turnId: string | null } | null;
  pendingApproval: Pick<AgentPendingApprovalDto, 'approvalId' | 'occurredAt' | 'title' | 'detail' | 'approvalKind' | 'scope'> | null;
  latestTurn: { id: string; state: string; endedAt: string | null } | null;
};

export type AgentTaskDto = {
  jiraSource?: JiraTaskSource | null;
  issueSource?: AgentIssueSourceDto | null;
  interactive?: boolean;
  developmentContinuationUnfinished: boolean;
  developmentCi?: AgentDevelopmentCiDto | null;
  /** The pull request the backend picked from the generated branch's stored rows; absent without one. */
  pullRequest?: AgentTaskPullRequestDto | null;
  /** How the review comments Auto-Review fix handed to the agent stand; absent before the first one. */
  reviewFix?: ReviewFixSummaryDto | null;
  /** Where Auto-merge stands; absent before the pull request is first checked. */
  autoMerge?: AutoMergeSummaryDto | null;
  id: string;
  workspaceId: string;
  generatedBranch: string | null;
  title: string;
  /** PROVISIONAL until the agent's first answer proposes a name (AGENT); a rename is USER and final. */
  titleSource: string;
  laneId: string;
  defaults: AgentTaskDefaultsDto;
  state: string;
  mutationClaim: AgentMutationClaimDto | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  terminalAt: string | null;
  terminalReason: string | null;
  metrics: AgentMetricsDto;
  summary: AgentTaskSummaryDto | null;
  deleteAfter: string | null;
  checkoutRemovedAt: string | null;
  /** When the task was shelved. An archived task keeps everything but stays off Home and the overview. */
  archivedAt: string | null;
};


/** An image the turn ran with, as the backend names it: an opaque id, its name and type, never bytes or a path. */
export type AgentAttachmentReferenceDto = {
  id: string;
  name: string;
  mediaType: string;
};

export type AgentTurnSettingsDto = AgentTaskDefaultsDto & {
  laneId?: string | null;
  maximumRuntimeMillis: number;
  attachments: AgentAttachmentReferenceDto[];
};

export type DevelopmentLearningSummaryDto = {
  purpose: string;
  commits: { sha: string; title: string; summary: string }[];
  combinedResult: string | null;
  evidence: string;
  limitations: string[];
};

export type DevelopmentResultDto = {
  readyForDelivery: boolean;
  message: string | null;
  learningSummary?: DevelopmentLearningSummaryDto | null;
};

export type AgentTurnDto = {
  /** Display hint from saved workflow metadata; never an authorization. */
  workflowRequest?: 'CHERRY_PICK' | 'REBASE' | 'CONTEXT_BUILD' | null;
  /** Latest root plan, independent of the loaded transcript; an empty list explicitly clears it. */
  plan?: Extract<AgentEventEnvelope, { type: 'plan_updated' }>['payload']['items'] | null;
  developmentResult?: DevelopmentResultDto | null;
  developmentOrigin?: { kind: 'AUTO_COMMIT' | 'CREATE_DRAFT_PR' | 'REFINE_SUMMARY'; parentUserTurnId: string } | null;
  id: string;
  taskId: string;
  state: string;
  version: number;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
  cancellationRequestedAt: string | null;
  /** Whether the provider acknowledged the input; a started but unacknowledged turn is uncertain. */
  delivered: boolean;
  /** Core proved the request failed before the provider call, so an explicit retry is safe. */
  knownUnsent: boolean;
  settings: AgentTurnSettingsDto | null;
  /** Typed operational cause for a failed turn; absent/null on ordinary and legacy failures. */
  failureKind?: 'AUTHENTICATION_REQUIRED' | null;
  terminalReason: string | null;
  metrics: AgentMetricsDto;
  /** Repository guideline files that went with the turn's input, by path; empty when none were read. */
  guidelines?: string[];
  /** Repository memories that went with the turn's input, each as the agent received it. */
  memories?: { id: string; line: string }[];
};

export type AgentDevelopmentCiDto = ReturnType<typeof agentContracts.parseAgentDevelopmentCi>;

export type CiAutofixRunDto = ReturnType<typeof agentContracts.parseCiAutofixRun>;

export type CreateAgentTaskRequest = {
  jiraSource?: JiraSourceRequest;
  issueSource?: IssueTaskSourceRequest;
  /** The work item a task without a source is for; the task becomes its attempt. */
  workItemId?: string;
  developmentAutomation?: DevelopmentAutomationDto;
  /** Client-generated identity makes task provisioning safe to replay after a lost response. */
  taskId: string;
  workspaceId: string;
  /** The first message; it creates the task and names it until the agent answers. */
  message: string;
  laneId: string;
  defaults: AgentTaskDefaultsDto;
  /** Opaque native-picker drafts; Electron resolves their bytes only when sending. */
  draftAttachmentIds?: string[];
  /** The composite request also starts the first turn exactly once. */
  idempotencyKey: string;
};

export type StartAgentTurnRequest = {
  developmentAutomation?: DevelopmentAutomationDto;
  taskId: string;
  message: string;
  /** Defaults must be the version the composer displayed, not a later cross-window update. */
  expectedTaskVersion: number;
  /** Makes a retried start return the original turn instead of creating a second one. */
  idempotencyKey: string;
  /** Opaque references the backend issued for images stored on this task; never paths. */
  attachments?: string[];
};

/** Input for the turn running now; the turn named is the one on screen, any other is refused. */
export type SteerAgentTurnRequest = {
  turnId: string;
  message: string;
  idempotencyKey: string;
  attachments?: string[];
};

/** What became of one steer. UNKNOWN is final: it was sent and nothing says whether it arrived. */
export type AgentInputDeliveryDto = ReturnType<typeof agentContracts.parseAgentInputDelivery>;

/** A request to run after the current turn; the version is the one this screen saw. */
export type QueueAgentInputRequest = {
  developmentAutomation?: DevelopmentAutomationDto;
  taskId: string;
  workspaceId: string;
  message: string;
  expectedVersion: number;
  idempotencyKey: string;
  attachments?: string[];
};

/** A request waiting in the task's queue. The preview is bounded; the whole text reaches the transcript when it runs. */
export type AgentQueuedInputDto = ReturnType<typeof agentContracts.parseAgentQueuedInput>;

export type AgentQueuedInputDraftDto = {
  message: string;
  settings: AgentTurnSettingsDto;
  attachments: AgentAttachmentDto[];
};

/** A stored, verified image the task owns; the renderer only ever sees these facts. */
export type AgentAttachmentDto = ReturnType<typeof agentContracts.parseAgentAttachment>;

export type AgentDraftAttachmentDto = {
  id: string;
  name: string;
  mediaType: string;
  bytes: Uint8Array;
};

type AgentAttachmentBytesDto = {
  mediaType: string;
  bytes: Uint8Array;
};

/** What the user chose on a card; a task-wide allow reaches the backend as an allow that covers the task. */
export type AgentApprovalDecision = 'ALLOW' | 'ALLOW_FOR_TASK' | 'DENY';

export type AgentApprovalDto = {
  approvalId: string;
  decision: 'ALLOW' | 'DENY';
  scopedPermission: string | null;
  /** Whether the allow also answers the task's later commands with the same leading words. */
  forTask: boolean;
  resolvedAt: string;
  timedOut: boolean;
};

/** What stopping a task's provider runtime would interrupt, and the generation the stop must echo. */
export type AgentRuntimeStopImpactDto = ReturnType<typeof agentContracts.parseAgentRuntimeStopImpact>;

/** The creation step an in-flight createAgentTask is in; null before the first step and once the call has returned. */
export type AgentTaskStartupPhase = 'PROVIDER' | 'REPOSITORY' | 'NAMING' | 'WORKTREE' | 'ATTACHMENTS' | 'SESSION';
export type AgentTaskStartupDto = { phase: AgentTaskStartupPhase | null };

export type AgentCompletionDto = ReturnType<typeof agentContracts.parseAgentCompletion>;

/** How the event stream itself is doing, as opposed to what it carries. */
export type AgentStreamConnectionState = 'connecting' | 'open' | 'reconnecting' | 'failed';

/**
 * One push from a task subscription.
 *
 * Carries the subscription and task it belongs to so a renderer that has moved on can ignore
 * events still arriving for the screen it left.
 */
export type AgentStreamPayload =
  | { kind: 'events'; events: AgentEventEnvelope[] }
  | { kind: 'changed'; sequence: number; turnId: string | null }
  | { kind: 'state'; state: AgentStreamConnectionState; detail?: string };

/** A bounded, contiguous slice of ByteQuay's event log, with opaque adjacent-page cursors. */
export type AgentHistoryPageDto = {
  events: AgentEventEnvelope[];
  beforeCursor: string | null;
  afterCursor: string | null;
  latestSequence: number;
};

export type AgentPendingApprovalDto = {
  approvalId: string;
  taskId: string;
  turnId: string;
  source: AgentEventEnvelope['source'];
  occurredAt: string;
  title: string;
  detail: string;
  approvalKind: string;
  scope: string[];
  /** Whether the action stays inside the provider's sandbox. */
  sandboxed: boolean;
  availableDecisions: string[];
  actionable: boolean;
};

export type AgentStreamMessage = {
  subscriptionId: string;
  taskId: string;
} & AgentStreamPayload;

export type AgentProviderStatus = {
  laneId: string;
  readiness: AgentProviderReadiness;
  version?: string | null;
  diagnostic: string;
  capabilities: AgentCapabilitiesDto;
  /** The exact command that signs this lane in, when a generic login would land elsewhere. */
  signInCommand?: string | null;
};

/** Typed preflight refusal: no task/turn mutation happened and the lane remedy is safe to show. */
export type AgentProviderUnavailableDto = {
  timestamp: string;
  status: 503;
  code: 'AGENT_PROVIDER_UNAVAILABLE';
  laneId: string;
  readiness: AgentProviderReadiness;
  diagnostic: string;
  signInCommand: string | null;
  path: string;
};

export type CherryPickExecutionItem = {
  position: number;
  sourceSha: string;
  title: string;
  /** PENDING | ALREADY_ON_TARGET | USER_SKIPPED | APPLIED | EMPTY_SKIPPED */
  result: string;
  targetSha?: string | null;
  pullRequestNumber?: number | null;
};

export type CherryPickStage = 'APPLY' | 'BUILD' | 'PUBLISH' | 'CI_FIX' | 'SQUASH' | 'MERGE';

/** One commit on the work branch: a copied source commit, or the one fixup directly after it. */
export type CherryPickBranchCommit = {
  sha: string;
  subject: string;
  sourceSha?: string | null;
  fixupOf?: string | null;
  origin?: 'CONFLICT' | 'BUILD' | 'CI' | null;
};

/** Where an execution stands after its commits apply, as the backend last saw its branch. */
export type CherryPickLifecycle = {
  stage: CherryPickStage;
  /** False for an execution a backport chain started; it stops after applying. */
  delivers: boolean;
  baseSha: string;
  hold?: {
    reason: 'USER' | 'PUBLISH_FAILED' | 'CI_FAILED' | 'REBASE_FAILED' | 'SQUASH_FAILED' | 'PULL_REQUEST_CLOSED';
    summary: string;
    at: string;
  } | null;
  commits: CherryPickBranchCommit[];
  rebases: Array<{
    number: number;
    baseSha: string;
    targetMoved: number;
    state: 'RUNNING' | 'DONE' | 'FAILED';
    summary?: string | null;
    startedAt: string;
    endedAt?: string | null;
  }>;
  ciRunIds: string[];
  squash?: { approvedHead: string; approvedAt: string; resultHead?: string | null; fixups: CherryPickBranchCommit[] } | null;
  readyForReview: boolean;
  notes: Array<{ text: string; at: string }>;
};

export type CherryPickExecution = {
  executionId: string;
  workspaceId: string;
  repository: string;
  /** `owner/repo` the commits come from, when a related repository supplied them. */
  sourceRepository?: string | null;
  workBranch: string;
  localTargetBranch: string;
  targetBranch: string;
  /** RUNNING | PAUSED | FINISHED */
  status: string;
  currentStep?: string | null;
  pauseReason?: string | null;
  finishReason?: string | null;
  summary?: string | null;
  initialTargetSha: string;
  currentTargetSha: string;
  currentPosition?: number | null;
  pullRequestDraft?: { title: string; body: string } | null;
  delivery?: {
    pushRemote: string;
    head: string;
    sha: string;
    pullRequestId?: number | null;
    pullRequestNumber?: number | null;
    pullRequestUrl?: string | null;
  } | null;
  items: CherryPickExecutionItem[];
  /** The conversation that resolves conflicts, builds and rebases. */
  agentTaskId?: string | null;
  lifecycle: CherryPickLifecycle;
  /** When the execution started and last moved, which the Tasks page groups it by. */
  createdAt: string;
  updatedAt: string;
  terminalAt: string | null;
};

export type CherryPickExecutionRequest = {
  workspaceId: string;
  previewId: string;
  expectedSourceBaseSha: string;
  expectedSourceTipSha: string;
  expectedTargetSha: string;
  userSkippedSourceShas: string[];
  laneId: string;
  /** The conflict resolver's model and effort; null leaves the lane's own defaults. */
  model: string | null;
  effort: string | null;
  idempotencyKey: string;
  pullRequestTitle: string;
  pullRequestBody: string;
};

type GitRemoteDto = {
  name: string;
  url: string;
  /** The remote's default branch, when its HEAD symref is known locally. */
  headBranch?: string | null;
};

export type GitOverviewDto = {
  currentBranch: string;
  branches: string[];
  remotes: GitRemoteDto[];
};

export type GitCommitDto = {
  sha: string;
  title: string;
  body: string;
  author: string;
  authorAvatarUrl?: string | null;
  committer: string;
  committerAvatarUrl?: string | null;
  authoredAt: string;
  committedAt: string;
  pullRequestNumber?: number | null;
  pullRequestTitle?: string | null;
};

export type CommitHistorySourceDto = 'WORKSPACE' | 'UPSTREAM' | 'REMOTE';

export type CommitHistoryRefDto = {
  source: CommitHistorySourceDto;
  branch: string;
  /** Extra remote-source name; present exactly when source is REMOTE. */
  remote?: string;
};

export type CommitRefreshStatusDto = {
  workspaceId: string;
  state: 'IDLE' | 'FAILED';
  lastAttemptAt?: string | null;
  lastSucceededAt?: string | null;
  error?: string | null;
};

type CommitHistorySnapshotDto = {
  commits: GitCommitDto[];
  refresh: CommitRefreshStatusDto;
};

export type AgentModelType = 'DEVELOPMENT' | 'REVIEW';

/**
 * One agent type's resolved model. `explicit` is false when nothing is stored
 * at the level asked for and the value came from the level below — Review
 * inheriting Development, or a workspace inheriting the global value.
 */
export type AgentModelSettingDto = {
  type: AgentModelType;
  explicit: boolean;
  configId: string;
  label: string;
  model: string;
  modelLabel: string;
  effort?: string | null;
};

type WorkspaceTokenDto = {
  maskedToken: string;
  login: string;
};

export type WorkspaceCommitsTargetDto = {
  remote: 'origin' | 'upstream';
  branch: string;
  overridden: boolean;
};

export type BackportChainRouteDto = {
  workspaceId: string; repository: { owner: string; repo: string }; remote: string; branch: string;
};
export type BackportChainPrDto = { owner: string; repo: string; number: number };
export type BackportChainViewDto = {
  chain: {
    /** Null when the chain was started from Commits rather than an agent conversation. */
    id: string; workspaceId: string; requestingTaskId: string | null; sourceTaskId: string | null; source: BackportChainRouteDto;
    sourcePullRequest: BackportChainPrDto | null; sourceMerged: boolean;
    acceptances: { operationKey: string; turnId: string | null; destinations: string[] }[];
    hops: { sourceHop: number; route: BackportChainRouteDto; executionId: string | null; pullRequest: BackportChainPrDto | null;
      selection: { commits: string[]; tipSha: string } | null; landedSource: { commits: string[]; mergeSha: string } | null; completed: boolean; noOp: boolean }[];
    state: 'ACTIVE' | 'BLOCKED' | 'STOPPING' | 'STOPPED' | 'COMPLETED'; reason: string | null; createdAt: string; updatedAt: string; version: number;
  };
  title: string; requestingRepository: string; archived: boolean;
  progress: { taskId: string | null; ciRunId: string | null; status: string; summary: string | null }[];
};

export type LinkedWorkspaceWorkDto = {
  sourceWorkspaceId: string; sourceRepository: string; sourceTaskId: string; sourceTitle: string; sourceTurnId: string;
  targetTaskId: string | null; targetWorkspaceId: string; targetTitle: string; repository: string;
  kind: 'FORK' | 'DEPENDENCY'; purpose: 'INVESTIGATE' | 'FIX'; question: string;
  revision: string; commit: string | null; state: string; turnId: string | null;
  result: string | null; limitation: string | null; createdAt: string; archived: boolean;
};

export type WorkspaceRelationshipDto = {
  unavailableReason?: string | null;
  workspaceId: string;
  kind: 'FORK' | 'DEPENDENCY';
  guidance: string;
  backportByDefault: boolean;
};

/** Where a related workspace sits: own links, links saved to this workspace, and the rest of its repo line. */
export type RelatedWorkspaceDto = {
  workspaceId: string;
  position: 'UPSTREAM' | 'DOWNSTREAM' | 'DEPENDENCY' | 'DEPENDENT';
  /** The git remote this workspace reads that repository through; null when it isn't a source. */
  remote: string | null;
  unavailableReason: string | null;
};

export type RepoLineNodeDto = { workspaceId: string; parentWorkspaceId: string | null };

export type WorkspaceRelationshipsDto = {
  links: WorkspaceRelationshipDto[];
  related: RelatedWorkspaceDto[];
  /** Upstream ancestors oldest first, this workspace, then everything downstream of it. */
  line: RepoLineNodeDto[];
};

export type WorkspaceSettingsDto = {
  commitsTarget: WorkspaceCommitsTargetDto;
  agents: AgentModelSettingDto[];
  githubToken?: WorkspaceTokenDto | null;
};

export type ModelOptionDto = {
  /** Stable value passed to the provider (CLI alias or model slug). */
  id: string;
  name: string;
  description: string;
  efforts: string[];
  defaultEffort?: string | null;
  /** What the model accepts as input; absent on catalogs recorded before this was carried. */
  inputModalities?: string[];
  serviceTiers?: Array<{ id: string; name: string; description: string }>;
};

export type ModelProviderDto = {
  id: string;
  workspaceId?: string | null;
  label: string;
  readiness: AgentProviderReadiness;
  version?: string | null;
  diagnostic: string;
  /** The exact command that signs this lane in, when a generic login would land elsewhere. */
  signInCommand?: string | null;
  capabilities: AgentCapabilitiesDto;
  models: ModelOptionDto[];
  selectedModel: string;
  selectedEffort: string;
};

export type ModelApiKeyDto = {
  id: string;
  alias: string;
  provider: string;
  maskedKey: string;
  createdAt: string;
  models: string[];
  selectedModel: string;
};

export type UsageAccountDto = { id: string; label: string };

export type ProviderUsageWindowDto = {
  id: string;
  label: string;
  usedPercent: number;
  startsAt: string | null;
  resetsAt: string | null;
  amount: { used: number; total: number; currency: string } | null;
};

export type ProviderUsageDto = ReturnType<typeof agentContracts.parseProviderUsage>;

export type ModelSettingsDto = {
  checkedAt?: string | null;
  agents: AgentModelSettingDto[];
  providers: ModelProviderDto[];
  apiKeys: ModelApiKeyDto[];
  keyProviders: Array<{ name: string; models: string[] }>;
};

export type AgentSkillScope = 'GLOBAL' | 'DEVELOPMENT' | 'REVIEW';

export type AgentSkillInput = {
  name: string;
  instructions: string;
  enabled: boolean;
  scope: AgentSkillScope;
};

export type AgentSkillDto = AgentSkillInput & {
  id: string;
};


export type AppThemeDto = 'SYSTEM' | 'LIGHT' | 'DARK';

export type SetupStep = 'APPEARANCE' | 'GITHUB' | 'AGENT' | 'DEFAULTS' | 'AUTOMATION' | 'FEATURES' | 'COMPLETE';

export type SetupPreferencesDto = {
  step: SetupStep;
  assistantEnabled: boolean;
};

export type AppPreferencesDto = {
  taskRetentionDays: number;
  recentRetentionDays: number;
  prWarmCount: 10 | 30;
  /** How long Auto-merge waits once a pull request can merge. */
  autoMergeDelayMinutes: number;
  theme: AppThemeDto;
};

export type QuietHoursDto = {
  enabled: boolean;
  startMinute: number;
  endMinute: number;
};

export type GitHubTokenSource = 'PERSONAL_ACCESS_TOKEN' | 'GITHUB_CLI';

export type GitHubAccountDto = {
  configured: boolean;
  maskedToken?: string | null;
  source: GitHubTokenSource;
};

export type GitDiffFileDto = {
  path: string;
  /** M | A | D | R */
  status: string;
  additions: number;
  deletions: number;
  patch?: string | null;
};

export type GitWorktreeStatusDto = {
  branch: string;
  files: GitDiffFileDto[];
};

/* ── Recent ─────────────────────────────────────────────────────────────── */

export type RecentItemType = 'PULL_REQUEST' | 'ISSUE' | 'JIRA' | 'TASK' | 'COMMIT';

export type RecentReason =
  | 'READ'
  | 'EDITED'
  | 'PROMPTED'
  | 'PUSHED'
  | 'COMMENTED'
  | 'CHANGES_REQUESTED'
  | 'APPROVED'
  | 'MERGED';

type RecentTextKind = 'COMMENT' | 'REVIEW_BODY' | 'TASK_PROMPT' | 'DRAFT';

export type RecentRowDto = {
  id: string;
  type: RecentItemType;
  itemKey: string;
  number: number | null;
  title: string;
  repo: string | null;
  workspaceId: string | null;
  mine: boolean;
  touchedAt: string;
  reason: RecentReason;
  /** Badge text, formatted by the backend so every surface says the same thing. */
  reasonLabel: string;
  htmlUrl: string | null;
  subtitle: string | null;
  /** What the user wrote on this item, when there is any. */
  snippet: string | null;
  textId: string | null;
  /** Current cached status, independent of who owns the PR or the recorded action. */
  pullRequest: (Pick<PullRequestDto, 'state' | 'draft'> & { queued: boolean }) | null;
};

type RecentDayPageDto = {
  /** ISO date, or null when the window holds nothing. */
  day: string | null;
  rows: RecentRowDto[];
  nextCursor: string | null;
  endOfWindow: boolean;
  activeDays: string[];
  retentionDays: number;
};

type RecentMatchRangeDto = { start: number; end: number };

export type RecentWordHitDto = {
  id: string;
  type: RecentItemType;
  itemKey: string;
  workspaceId: string | null;
  kind: RecentTextKind;
  kindLabel: string;
  neverPosted: boolean;
  sentence: string;
  highlights: RecentMatchRangeDto[];
  body: string;
  number: number | null;
  repo: string | null;
  title: string | null;
  htmlUrl: string | null;
  writtenAt: string;
};

export type RecentChangeDto = {
  itemKey: string;
  moved: boolean;
  wantsYou: boolean;
  summary: string | null;
};

/** Where an agenda item opens: the page that already shows its pull request, issue, ticket, task, review or work item. */
export type AgendaTargetDto = {
  kind: 'PULL' | 'ISSUE' | 'JIRA' | 'TASK' | 'REVIEW' | 'WORK';
  workspaceId: string | null;
  repo: string | null;
  number: number | null;
  /** The task, review or work item id, or the Jira ticket's issue id. */
  id: string | null;
  connectionId: string | null;
  cloudId: string | null;
};

export type AgendaAction = 'start' | 'reply' | 'review' | 'fix' | 'merge' | 'answer' | 'decide' | 'follow_up';

export type AgendaItemDto = {
  ref: string;
  action: AgendaAction;
  title: string;
  /** A short name for a composer chip, without the verb its icon already says. */
  label: string;
  why: string;
  /** Since when the item has waited. */
  at: string | null;
  workspaceId: string | null;
  target: AgendaTargetDto;
  /** What the row names beside the reason when the target alone cannot: an Epic and its progress, a task's pull request. */
  where: string | null;
  status: 'open' | 'done' | 'dismissed';
  /** Whether the user marked it, rather than a stored fact closing it. */
  byYou: boolean;
  doneText: string | null;
  doneAt: string | null;
};

export type AgendaLinkDto = { ref: string; target: AgendaTargetDto };

export type AgendaDto = {
  day: string;
  state: 'NONE' | 'RUNNING' | 'READY' | 'FAILED';
  enabled: boolean;
  /** When the schedule makes it, in minutes past midnight. */
  at: number;
  generatedAt: string | null;
  model: string | null;
  summary: string | null;
  items: AgendaItemDto[];
  recap: { text: string; merged: AgendaLinkDto[]; slipped: AgendaLinkDto[] } | null;
  /** How much of the day the user's calendar held, as times only; absent until an agenda is made. */
  calendar: AgendaCalendarDto | null;
  failure: string | null;
};

export type AgendaCalendarDto = {
  state: 'used' | 'off' | 'not_connected' | 'unavailable';
  busyHours: number;
  allDay: boolean;
  busy: { from: string; to: string }[];
  free: { from: string; to: string }[];
};

/** One entry in an Assistant conversation. */
export type AssistantItemDto = {
  id: string;
  conversationId: string;
  at: string;
  kind: 'USER' | 'REPLY' | 'RECEIPT' | 'FAILURE' | 'LIST' | 'DRAFT';
  /** The user's words, the reply's Markdown, a receipt's line, a list's or a draft's title, or why the reply failed. */
  text: string;
  /** Where each ref a reply names opens; only refs the conversation served. */
  targets: Record<string, AgendaTargetDto>;
  /** Whether a failure is the model not being ready, which Settings fixes. */
  notReady: boolean;
  receipt: AssistantReceiptDto | null;
  /** The style a list shows first and its refs in order; their rows come with the conversation. */
  list: AssistantListDto | null;
  /** A new GitHub issue the assistant drafted, which the user edits and creates as an Epic with a click. */
  draft: AssistantDraftDto | null;
};

/** A Needs attention mark as the backend keeps it; home/attention.ts turns it into the list's own entry. */
export type AttentionMarkDto = {
  key: string;
  kind: 'ACK' | 'SNOOZE';
  until: string | null;
  seenUpdatedAt: string;
  seenActivityVersion: string | null;
  label: string;
  /** The list's own JSON of the item, kept for a timed snooze. */
  snoozedItem: string | null;
};

/** The repository a drafted issue goes to, its description as drafted, and the local work item the click creates. */
export type AssistantDraftDto = { workspaceId: string; repository: string; body: string; workItemId: string };

export type AssistantListStyle = 'ROWS' | 'CARDS' | 'TIMELINE';

export type AssistantListDto = { style: AssistantListStyle; refs: string[] };

/** One ref of a list, read from what ByteQuay stores whenever the day is read. Its facts are icons and counts by kind. */
export type AssistantRowDto = {
  ref: string;
  kind: 'PULL' | 'ISSUE' | 'JIRA' | 'TASK' | 'REVIEW' | 'WORK' | 'EPIC';
  title: string;
  /** A pull request's or issue's number, or a ticket's key. */
  key: string | null;
  /** A pull request's open, draft, merged or closed; a task's phase; an issue's or ticket's status; a work item's state. */
  state: string | null;
  /** A pull request's checks: passing, failing or pending. */
  ci: string | null;
  /** A pull request's review decision. */
  review: string | null;
  /** How many reviewers approve a pull request, from its cached reviews. */
  approvals: number | null;
  /** Whether a pull request is the user's own. */
  mine: boolean;
  /** Whether GitHub blocks merging a pull request, as its cached detail says. */
  mergeBlocked: boolean;
  /** Whether the user's own latest review approves a pull request. */
  youApproved: boolean;
  /** The head commit of a pull request's cached detail, which its card's Approve and Merge act on. */
  head: string | null;
  /** The agent a task runs with. */
  agent: string | null;
  /** The Epic a work item belongs to. */
  epic: string | null;
  /** An Epic's finished work items, and those not dropped. */
  done: number | null;
  total: number | null;
  /** When it last moved; for a task, since when it has been in its phase. */
  at: string | null;
  /** What happened last, for a card's body; its text is the source's own, Markdown and lines kept, for the card to fold into one line. */
  latest: { by: string | null; text: string; at: string } | null;
  target: AgendaTargetDto;
};

/** What one action left, done or refused: by the assistant from the user's words, or by a card's own button. */
export type AssistantReceiptDto = {
  operation: 'START_TASK' | 'ANSWER_APPROVAL' | 'ANSWER_QUESTION' | 'REPLY_TO_TASK' | 'OPEN' | 'MARK_EPIC' | 'ADD_TO_EPIC' | 'CREATE_WORK_ITEM' | 'CREATE_EPIC' | 'ACKNOWLEDGE'
    | 'SNOOZE' | 'MAKE_AGENDA';
  outcome: 'DONE' | 'REFUSED' | 'FAILED';
  /** A second line, such as where a started task runs. */
  detail: string | null;
  target: AgendaTargetDto | null;
  /** The app page or Settings section the receipt opens instead, such as `settings/models`. */
  page: string | null;
  /** A started task's first turn, which Stop cancels. */
  turnId: string | null;
  /** Settings holds the switch that is off; the card's buttons answer what words may not. */
  fix: 'SETTINGS' | 'BUTTONS' | null;
  /** The user's words the assistant acted on, exactly as it passed them; none for a card's own button. */
  words: string | null;
};

/** What the Assistant may do from the user's words; each starts off. Changing Epics and work items, and marking Needs attention, stay local. */
export type AssistantPermissionsDto = { startTasks: boolean; answerTasks: boolean; changeWork: boolean; markAttention: boolean };

export type AssistantViewDto = {
  id: string;
  model: string | null;
  /** Whether a reply in this conversation is being written. */
  running: boolean;
  items: AssistantItemDto[];
  /** Each ref the conversation's lists name that still resolves; a ref missing here is gone. */
  rows: Record<string, AssistantRowDto>;
};

/** One conversation in the Assistant's history, titled by its first message. */
export type AssistantSummaryDto = { id: string; title: string; updatedAt: string; running: boolean };

export type AgendaDay = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export type AgendaScheduleDto = {
  enabled: boolean;
  minute: number;
  days: AgendaDay[];
  /** The Google account whose busy times the agenda reads; null keeps the calendar out. */
  calendarAccountId: string | null;
};

export type RecentFilterQuery = {
  from?: string;
  to?: string;
  types?: RecentItemType[];
  action?: RecentReason;
  workspace?: string;
  changedSinceRead?: boolean;
  text?: string;
  cursor?: string;
};

export type RecentTouchRequest = {
  type: RecentItemType;
  repo: string;
  number: number;
  workspaceId?: string | null;
  signal: 'DWELL' | 'DIFF_OPENED';
  dwellSeconds: number;
};

export type RecentDraftRequest = {
  type: RecentItemType;
  repo: string;
  number: number;
  workspaceId?: string | null;
  body: string;
};

export type Bridge = LabBridge & WorkItemBridge & JiraBridge & GoogleBridge & ArtifactBridge & VoiceBridge & {
  onAppNavigation: (callback: (target: import('./appNavigation').AppNavigationTarget) => void) => () => void;
  /** `workItemId` names the Epic work a notice suggests next; it opens instead of the task. With `artifact`, `taskId` is an Artifacts conversation. */
  onAppNoticeOpen: (callback: (target: { taskId: string; workspaceId: string; turnId: string | null; reviewId: string | null; workItemId: string | null;
    artifact: boolean }) => void) => () => void;
  setNoticeSource: (key: string, taskId: string | null) => Promise<void>;
  /** The notices the Assistant panel lists; each new one also shows once as the bottom-right alert. */
  readAppNotices: () => Promise<NoticeSnapshot>;
  onAppNotices: (callback: (state: NoticeSnapshot) => void) => () => void;
  openAppNotice: (id: string) => Promise<void>;
  /** Clears a notice for good. */
  removeAppNotice: (id: string) => Promise<void>;
  /** The open panel lists every notice, so no alert shows meanwhile. */
  setAssistantPanelOpen: (open: boolean) => void;
  /** ⌘J from any page, browser tab or editor. */
  onAssistantToggle: (callback: () => void) => () => void;
  /** Answer on an approval or question alert: the notice whose card the panel opens at. */
  onAssistantOpenCard: (callback: (id: string) => void) => () => void;
  /** Where the floating Assistant over other apps opened something: a page route or a reference to show over the page. */
  onAssistantNavigate: (callback: (target: { hash: string } | { zoom: ZoomedReference }) => void) => () => void;
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  fetchPrs: (workspaceId: string | undefined, page: number, pageSize: number) => Promise<PullRequestPageDto>;
  fetchAttentionPrs: () => Promise<PullRequestDto[]>;
  findStoredPullRequest: (repo: string, number: number, workspaceId?: string) => Promise<PullRequestDto | null>;
  /** Open issues the viewer opened anywhere on GitHub and that changed in the last thirty days. */
  fetchAuthoredIssues: () => Promise<AuthoredIssueDto[]>;
  lookupPullRequest: (repo: string, number: number, workspaceId?: string) => Promise<PullRequestDto>;
  /** The description a detail read cached, without reaching GitHub or marking the PR viewed; null until one did. */
  findStoredPullRequestDescription: (repo: string, number: number, workspaceId?: string) => Promise<{ body: string | null }>;
  setPrMonitored: (prId: number, monitored: boolean, workspaceId?: string) => Promise<PullRequestDto>;
  setPrAttentionHandled: (handled: import('./home/attention').Handled) => Promise<void>;
  /** The Needs attention marks every build and the assistant share. */
  getAttentionMarks: () => Promise<AttentionMarkDto[]>;
  /** Saves marks, replacing any under the same keys, and clears the marks under `removed`, together. */
  changeAttentionMarks: (changes: { saved: AttentionMarkDto[]; removed: string[] }) => Promise<void>;
  setPrAttentionPinned: (keys: string[]) => Promise<void>;
  onPrAttentionUpdated: (callback: (update: PrAttentionUpdate) => void) => () => void;
  onPrNotificationOpen: (callback: (reference: { repo: string; number: number }) => void) => () => void;
  syncPrs: (workspaceId?: string) => Promise<void>;
  onPullRequestsSynced: (callback: () => void) => () => void;
  fetchPullRequestDetailByRef: (repo: string, number: number, workspaceId?: string) => Promise<PullRequestDetailDto>;
  fetchRepoUsers: (repo: string, workspaceId?: string) => Promise<RepoUserDto[]>;
  /** The owner's teams as owner/slug; empty for a personal account. */
  fetchRepoTeams: (repo: string, workspaceId?: string) => Promise<string[]>;
  fetchRepoLabels: (repo: string, workspaceId?: string) => Promise<RepoLabelDto[]>;
  updatePrMetadata: (repo: string, number: number, assignees: string[], labels: string[], workspaceId?: string) => Promise<void>;
  updatePrReviewer: (repo: string, number: number, login: string, requested: boolean, workspaceId?: string) => Promise<void>;
  fetchMergeMode: (repo: string, branch: string, workspaceId?: string) => Promise<MergeModeDto>;
  fetchPrConflicts: (repo: string, number: number, workspaceId?: string) => Promise<PullRequestConflictsDto>;
  refreshPullRequestDetailByRef: (repo: string, number: number, checksOnly?: boolean, opened?: boolean, workspaceId?: string) => Promise<PullRequestDetailRefreshDto>;
  refreshAllPullRequestDetailByRef: (repo: string, number: number, workspaceId?: string) => Promise<PullRequestDetailDto>;
  fetchPrDiffFiles: (
    repo: string,
    number: number,
    baseSha?: string,
    headSha?: string,
    workspaceId?: string,
  ) => Promise<DiffFileDto[]>;
  refreshPrDiffFiles: (
    repo: string,
    number: number,
    baseSha?: string,
    headSha?: string,
    workspaceId?: string,
  ) => Promise<DiffFileDto[]>;
  fetchPrDiffCommits: (repo: string, number: number, workspaceId?: string) => Promise<DiffCommitDto[]>;
  refreshPrDiffFileContent: (
    repo: string,
    number: number,
    path: string,
    baseSha?: string,
    headSha?: string,
    workspaceId?: string,
  ) => Promise<DiffFileContentDto>;
  /** `key` stays the same for every try from one comment box, so a retry after an unknown outcome cannot post twice. */
  postPrReviewComment: (
    repo: string,
    number: number,
    comment: InlineReviewCommentDto,
    key: string,
    workspaceId?: string,
  ) => Promise<PullRequestDetailDto>;
  updatePrDescription: (repo: string, number: number, body: string, workspaceId?: string) => Promise<void>;
  updatePrTitle: (repo: string, number: number, title: string, workspaceId?: string) => Promise<void>;
  commentPr: (repo: string, number: number, body: string, workspaceId?: string) => Promise<PullRequestDetailDto>;
  setPrReaction: (
    repo: string,
    number: number,
    target: PullReactionTarget,
    commentId: number | null,
    content: PullReactionContent,
    viewerHasReacted: boolean | null,
    workspaceId?: string,
  ) => Promise<{ viewerHasReacted: boolean }>;
  editPrComment: (repo: string, number: number, target: PullCommentTarget, commentId: number, body: string, workspaceId?: string) => Promise<PullRequestDetailDto>;
  closePr: (repo: string, number: number, workspaceId?: string) => Promise<void>;
  markPrReadyForReview: (repo: string, number: number, workspaceId?: string) => Promise<void>;
  replyToReviewComment: (repo: string, number: number, commentId: number, body: string, workspaceId?: string) => Promise<void>;
  setReviewThreadResolved: (repo: string, number: number, commentId: number, resolved: boolean, workspaceId?: string) => Promise<void>;
  getPrCheckLog: (repo: string, number: number, jobId: number, workspaceId?: string) => Promise<{ text: string; unavailableReason: string | null }>;
  rerunFailedPrChecks: (repo: string, number: number, workspaceId?: string) => Promise<void>;
  betterWords: (text: string, repository?: string, workspaceId?: string) => Promise<string>;
  mergePr: (prId: number, repo: string, number: number, strategy: string, headSha: string, workspaceId?: string) => Promise<void>;
  removePrFromMergeQueue: (repo: string, number: number, autoMerge: boolean, workspaceId?: string) => Promise<void>;

  listHostingAccounts: () => Promise<HostingConnectionDto[]>;
  connectHostingAccount: (request: HostingServerDto & { token: string }) => Promise<HostingConnectionDto>;
  connectHostingCliAccount: (request: HostingServerDto) => Promise<HostingConnectionDto>;
  getGitLabCliStatus: () => Promise<GitLabCliStatusDto>;
  installGitLabCli: () => Promise<GitLabCliStatusDto>;
  disconnectHostingAccount: (accountId: number) => Promise<void>;
  resolveHostingRepository: (accountId: number, fullPath: string) => Promise<HostingRepositoryDto>;
  createHostingWorkspace: (accountId: number, repositoryId: number) => Promise<WorkspaceDto>;
  getWorkspaceHosting: (workspaceId: string) => Promise<WorkspaceHostingBindingDto | null>;
  setWorkspaceHosting: (workspaceId: string, accountId: number) => Promise<WorkspaceHostingBindingDto>;
  getWorkspaceHostingViewer: (workspaceId: string) => Promise<string | null>;
  fetchWorkspaceHostingAttention: (workspaceId: string) => Promise<WorkspaceHostingAttentionDto>;
  fetchWorkspaceHostingRequests: (workspaceId: string, page: number, pageSize: number) => Promise<WorkspaceHostingRequestsDto>;
  fetchWorkspaceHostingRequest: (workspaceId: string, number: number) => Promise<HostingPullRequestSnapshotDto | null>;
  refreshWorkspaceHostingRequest: (workspaceId: string, number: number) => Promise<HostingPullRequestSnapshotDto>;
  searchRepositories: (query: string, limit: number) => Promise<RepositorySearchResultDto[]>;
  previewWatch: (selectedRepositoryId: number, topology?: WatchRepositoryTopology) => Promise<WatchRepositoryPreviewDto>;
  watchRepository: (command: WatchRepositoryCommand) => Promise<WorkspaceDto>;
  listWorkspaces: () => Promise<WorkspaceDto[]>;
  getBackportChains: (workspaceId: string) => Promise<BackportChainViewDto[]>;
  /** Starts a backport of one pull request of `sourceWorkspaceId`; each destination copies from the source (-1) or an earlier one. */
  startBackportChain: (workspaceId: string, request: { sourceWorkspaceId: string; number: number; destinations: string[]; sourceHops: number[] }) => Promise<BackportChainViewDto>;
  selectBackportSource: (workspaceId: string, id: string, selection: { version: number; hopIndex: number; oldest: string; newest: string }) => Promise<BackportChainViewDto>;
  stopBackportChain: (workspaceId: string, id: string) => Promise<BackportChainViewDto>;
  resumeBackportChain: (workspaceId: string, id: string) => Promise<BackportChainViewDto>;
  getLinkedWorkspaceWork: (workspaceId: string) => Promise<LinkedWorkspaceWorkDto[]>;
  getRelatedWorkspaceRequest: (workspaceId: string, taskId: string) => Promise<LinkedWorkspaceWorkDto | null>;
  getWorkspaceRelationships: (workspaceId: string) => Promise<WorkspaceRelationshipsDto>;
  saveWorkspaceRelationship: (workspaceId: string, relationship: WorkspaceRelationshipDto) => Promise<WorkspaceRelationshipDto[]>;
  removeWorkspaceRelationship: (workspaceId: string, destinationId: string) => Promise<WorkspaceRelationshipDto[]>;
  listRetiredWorkspaces: () => Promise<WorkspaceDto[]>;
  getWorkspace: (workspaceId: string) => Promise<WorkspaceDto>;
  getWorkspaceMetadata: (workspaceId: string) => Promise<RepositoryMetadataDto>;
  fetchWorkspaceIssues: (workspaceId: string, page: number, pageSize: number) => Promise<RepositoryIssueSnapshotDto>;
  fetchWorkspaceIssuePreview: (workspaceId: string, issueNumber: number) => Promise<GitHubIssuePreviewDto>;
  fetchWorkspaceIssue: (workspaceId: string, issueNumber: number) => Promise<RepositoryIssueSnapshotDto['issues'][number] | null>;
  fetchWorkspaceIssueAttention: (workspaceId: string, etag?: string | null) => Promise<IssueAttentionRead>;
  setWorkspaceIssueEpic: (workspaceId: string, issueNumber: number, isEpic: boolean) => Promise<IssueDevelopmentDto>;
  /** The workspace's work items with every link read locally; nothing here writes to GitHub or Jira. */
  listWorkItems: (workspaceId: string) => Promise<WorkItemDto[]>;
  setLocalWorkItemEpic: (workspaceId: string, itemId: string, isEpic: boolean) => Promise<void>;
  /** New Local work that is an Epic from the start; the context is saved under its own heading. */
  createLocalEpic: (workspaceId: string, request: { id: string; title: string; description: string; context: string }) => Promise<void>;
  /** The Development model's next reply in the Create Epic conversation, which ends with the user's message. Nothing is saved. */
  draftEpic: (workspaceId: string, messages: EpicDraftMessage[]) => Promise<EpicDraftReply>;
  assignWorkItemEpic: (workspaceId: string, itemId: string, epicId: string) => Promise<EpicJoinDto>;
  createWorkItem: (workspaceId: string, request: CreateWorkItem) => Promise<WorkItemDto>;
  decideWorkItem: (workspaceId: string, itemId: string, command: WorkItemDecision) => Promise<WorkItemDto>;
  /** Saves the order of an Epic's work, which names each of its items once. */
  orderEpicWork: (workspaceId: string, epicId: string, ids: string[]) => Promise<WorkItemDto[]>;
  /** Puts a source, task or PR in an Epic: the work that owns it joins, leaving any other Epic, or new work takes its title. */
  joinEpic: (workspaceId: string, epicId: string, kind: GoalItemKind, key: string) => Promise<EpicJoinDto>;
  /** Takes work out of its Epic; null when the work existed only for the Epic and was deleted. */
  removeFromEpic: (workspaceId: string, itemId: string) => Promise<WorkItemDto | null>;
  /** An Epic's children as last read, a local read. */
  getEpic: (workspaceId: string, epicId: string) => Promise<EpicRecordDto>;
  /** Reads an Epic's children from its source; a Jira Epic needs the connection that shows it. */
  refreshEpicChildren: (workspaceId: string, epicId: string, connectionId: string | null) => Promise<EpicRecordDto>;
  /** Makes every unsynced child work of the Epic. */
  syncEpicChildren: (workspaceId: string, epicId: string) => Promise<EpicRecordDto>;
  /** Not now: hides the children unsynced now until another one appears. */
  dismissEpicChildren: (workspaceId: string, epicId: string) => Promise<EpicRecordDto>;
  /** Records what an Epic whose work is all done delivered; it then leaves the drawer. */
  concludeEpic: (workspaceId: string, epicId: string, outcome: string) => Promise<EpicRecordDto>;
  /** The workspace's concluded Epics, a local read. */
  listConcludedEpics: (workspaceId: string) => Promise<string[]>;
  /** Asks the Development model to propose work from the Epic's issue or ticket; a Jira Epic needs the connection that shows it. */
  proposeEpicWork: (workspaceId: string, epicId: string, connectionId: string | null) => Promise<EpicRecordDto>;
  /** Makes a proposal the Epic's work. */
  acceptEpicProposal: (workspaceId: string, epicId: string, proposalId: string) => Promise<EpicRecordDto>;
  dismissEpicProposal: (workspaceId: string, epicId: string, proposalId: string) => Promise<EpicRecordDto>;
  searchGoalCandidates: (workspaceId: string, query: string) => Promise<GoalItemDto[]>;
  refreshWorkspaceIssues: (workspaceId: string) => Promise<RepositoryIssueSnapshotDto>;
  fetchWorkspaceIssueDetail: (workspaceId: string, issueNumber: number) => Promise<WorkspaceIssueDetailDto>;
  refreshWorkspaceIssueDetail: (workspaceId: string, issueNumber: number) => Promise<WorkspaceIssueDetailDto>;
  commentWorkspaceIssue: (workspaceId: string, issueNumber: number, body: string) => Promise<WorkspaceIssueTimelineEventDto>;
  closeWorkspaceIssue: (workspaceId: string, issueNumber: number) => Promise<void>;
  updateWorkspaceIssueType: (workspaceId: string, issueNumber: number, type: string | null) => Promise<WorkspaceIssueTypeDto>;
  updateWorkspaceIssueMetadata: (
    workspaceId: string,
    issueNumber: number,
    assignees: string[],
    labels: string[],
  ) => Promise<WorkspaceIssueMetadataDto>;
  syncWorkspace: (workspaceId: string) => Promise<WorkspaceDto>;
  setWorkspaceUpstream: (
    workspaceId: string,
    upstreamRepositoryId: number | null,
  ) => Promise<WorkspaceDto>;
  removeWorkspace: (workspaceId: string) => Promise<void>;
  deleteWorkspace: (workspaceId: string, confirmed: boolean, repositoryName: string) => Promise<void>;
  getRepositoryAvatar: (owner: string, refresh?: boolean) => Promise<string | null>;
  getUserProfile: () => Promise<UserProfileDto>;
  getGitHubUserProfile: (login: string) => Promise<UserProfileDto>;
  getContributionCalendar: (login: string) => Promise<ContributionCalendarDto>;
  getUserCommitsOnDate: (login: string, date: string) => Promise<UserCommitDto[]>;
  fetchGitHubIssuePreview: (owner: string, repo: string, number: number) => Promise<GitHubIssuePreviewDto>;
  fetchGitHubCodeSnippet: (request: GitHubCodeSnippetRequest) => Promise<GitHubCodeSnippetDto>;
  getFollowingActivity: (login: string) => Promise<RecentEventDto[]>;
  getTeams: () => Promise<TeamDirectory>;
  fetchTeamContributors: () => Promise<RepositoryMetadataDto['contributors']>;
  createTeam: (input: TeamCreateInput) => Promise<TeamDirectory>;
  updateTeam: (id: string, input: TeamCircleInput) => Promise<TeamDirectory>;
  removeTeam: (id: string) => Promise<TeamDirectory>;
  addTeamMember: (id: string, member: TeamMemberInput) => Promise<TeamDirectory>;
  removeTeamMember: (id: string, githubId: number) => Promise<TeamDirectory>;
  getTeamActivity: (query?: TeamActivityQuery) => Promise<TeamActivitySnapshot>;
  refreshTeamActivity: (query?: TeamActivityQuery) => Promise<TeamActivitySnapshot>;

  getGitHubCliAvailable: () => Promise<{ available: boolean }>;
  importGitHubCliToken: () => Promise<{ login: string }>;

  captureFocusBreakBackground: () => Promise<FocusBreakBackground[]>;
  openInAppBrowser: (url: string) => Promise<void>;
  newInAppBrowserPage: () => Promise<void>;
  openExternal: (url: string) => Promise<void>;
  getInAppBrowserState: () => Promise<InAppBrowserState>;
  onInAppBrowserState: (callback: (state: InAppBrowserState) => void) => () => void;
  onInAppBrowserFind: (callback: (id: string) => void) => () => void;
  selectInAppBrowserTab: (id: string) => Promise<void>;
  pinInAppBrowserTab: (id: string, pinned: boolean) => Promise<void>;
  closeInAppBrowserTab: (id: string) => Promise<void>;
  hideInAppBrowser: () => Promise<void>;
  mountInAppBrowser: (id: string, bounds: { x: number; y: number; width: number; height: number }, allowFocusRestore?: boolean) => Promise<void>;
  renameInAppBrowserTab: (id: string, name: string) => Promise<void>;
  setInAppBrowserBounds: (id: string, bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
  unmountInAppBrowser: (id: string) => Promise<void>;
  inAppGoBack: (id: string) => Promise<void>;
  inAppGoForward: (id: string) => Promise<void>;
  inAppReload: (id: string) => Promise<void>;
  inAppNavigate: (id: string, url: string) => Promise<void>;
  inAppPopOut: (url: string) => Promise<void>;

  controlWindow: (action: 'close' | 'minimize' | 'zoom') => Promise<void>;
  getFullScreen: () => Promise<boolean>;
  onFullScreenChange: (callback: (fullScreen: boolean) => void) => () => void;
  getAppVersion: () => Promise<{ version: string }>;
  findInPage: (text: string, options: { forward: boolean; findNext: boolean }, tabId?: string) => Promise<void>;
  stopFindInPage: (tabId?: string) => Promise<void>;
  onFindResult: (callback: (result: FindResult) => void) => () => void;

  previewCherryPickRange: (request: CherryPickPreviewRequest) => Promise<CherryPickPreview>;
  previewCherryPickPullRequest: (request: CherryPickPullRequestPreviewRequest) => Promise<CherryPickPreview>;
  /** Merged pull requests stored for a workspace's repository, newest merge first. */
  listMergedPullRequests: (workspaceId: string, search: string) => Promise<PullRequestDto[]>;
  /**
   * Probes every configured provider and returns its readiness.
   *
   * The backend probes on every call, so this is the refresh: there is no second cached path to
   * drift from it.
   */
  refreshAgentProviderReadiness: () => Promise<AgentProviderStatus[]>;
  startCherryPickExecution: (request: CherryPickExecutionRequest) => Promise<CherryPickExecution>;
  getCherryPickExecution: (executionId: string) => Promise<CherryPickExecution>;
  listCherryPickExecutions: (limit: number) => Promise<CherryPickExecution[]>;
  /** The newest CI Autofix runs of every workspace, newest first. */
  listCiAutofixRuns: (limit: number) => Promise<CiAutofixRunDto[]>;
  sendCherryPickMessage: (executionId: string, message: string) => Promise<CherryPickExecution>;
  cancelCherryPickExecution: (workspaceId: string, executionId: string) => Promise<CherryPickExecution>;
  /** Holds the execution after its current step; a running CI round stops so nothing more is pushed. */
  pauseCherryPick: (executionId: string) => Promise<CherryPickExecution>;
  resumeCherryPick: (executionId: string) => Promise<CherryPickExecution>;
  /** Approves folding the fixups into their commits, bound to the branch tip the user reviewed. */
  approveCherryPickSquash: (executionId: string, expectedHead: string) => Promise<CherryPickExecution>;

  gitOverview: (workspaceId: string) => Promise<GitOverviewDto>;
  gitRemoteBranches: (workspaceId: string, remote: string) => Promise<string[]>;
  gitLog: (
    workspaceId: string,
    source: CommitHistorySourceDto,
    branch: string,
    limit: number,
    remote?: string,
  ) => Promise<CommitHistorySnapshotDto>;
  gitDiff: (workspaceId: string, base: string, head: string) => Promise<GitDiffFileDto[]>;
  gitDiffFileContent: (
    workspaceId: string,
    base: string,
    head: string,
    path: string,
  ) => Promise<DiffFileContentDto>;
  gitStatus: (workspaceId: string) => Promise<GitWorktreeStatusDto>;
  getCommitRefreshStatus: (workspaceId: string) => Promise<CommitRefreshStatusDto>;
  refreshCommitHistory: (
    workspaceId: string,
    refs: CommitHistoryRefDto[],
  ) => Promise<CommitRefreshStatusDto>;
  gitCommit: (workspaceId: string, summary: string, description: string) => Promise<{ sha: string }>;
  gitReword: (workspaceId: string, branch: string, sha: string, message: string) => Promise<{ sha: string }>;
  gitSquash: (
    workspaceId: string,
    branch: string,
    baseSha: string,
    tipSha: string,
    message: string,
  ) => Promise<{ sha: string }>;

  getUsageAccounts: () => Promise<UsageAccountDto[]>;
  getProviderUsage: (accountId: string) => Promise<ProviderUsageDto>;
  getModelSettings: (workspaceId?: string) => Promise<ModelSettingsDto>;
  refreshModelSettings: (force?: boolean, workspaceId?: string) => Promise<ModelSettingsDto>;
  setAgentModel: (
    type: AgentModelType,
    configId: string,
    model: string,
    effort: string | null,
  ) => Promise<ModelSettingsDto>;
  clearAgentModel: (type: AgentModelType) => Promise<ModelSettingsDto>;
  addModelApiKey: (provider: string, alias: string, key: string) => Promise<ModelSettingsDto>;
  addClaudeAccount: (label: string, email: string, workspaceId?: string) => Promise<ModelSettingsDto>;
  addClaudeGateway: (label: string, gatewayUrl: string, token: string, workspaceId?: string) => Promise<ModelSettingsDto>;
  restrictClaudeAccount: (laneId: string, workspaceId: string) => Promise<ModelSettingsDto>;
  removeClaudeAccount: (laneId: string) => Promise<ModelSettingsDto>;
  removeModelApiKey: (alias: string) => Promise<ModelSettingsDto>;
  getAgentSkills: () => Promise<AgentSkillDto[]>;
  addAgentSkill: (skill: AgentSkillInput) => Promise<AgentSkillDto[]>;
  updateAgentSkill: (id: string, skill: AgentSkillInput) => Promise<AgentSkillDto[]>;
  removeAgentSkill: (id: string) => Promise<AgentSkillDto[]>;
  getQuietHours: () => Promise<QuietHoursDto>;
  saveQuietHours: (quietHours: QuietHoursDto) => Promise<QuietHoursDto>;
  getDevelopmentAutomation: () => Promise<DevelopmentAutomationDto>;
  saveDevelopmentAutomation: (settings: DevelopmentAutomationDto) => Promise<DevelopmentAutomationDto>;
  getAppPreferences: () => Promise<AppPreferencesDto>;
  saveAppPreferences: (preferences: AppPreferencesDto) => Promise<AppPreferencesDto>;
  getSetupPreferences: () => Promise<SetupPreferencesDto>;
  saveSetupPreferences: (preferences: SetupPreferencesDto) => Promise<SetupPreferencesDto>;
  getGitHubAccount: () => Promise<GitHubAccountDto>;
  /** Copies the stored token to the clipboard in the main process; the renderer never sees it. */
  copyGitHubToken: () => Promise<boolean>;
  deleteGitHubToken: () => Promise<void>;

  getWorkspaceSettings: (workspaceId: string) => Promise<WorkspaceSettingsDto>;
  getRepositoryContextBuild: (workspaceId: string) => Promise<ContextBuildViewDto>;
  /** The latest spec check of each directory; reads only. */
  getRepositoryContextSpecChecks: (workspaceId: string) => Promise<ContextSpecCheckDto[]>;
  /** The pages a directory's code links at the monitored branch head; reads only. */
  /** The repository's modules at the monitored head, those whose own code cites one page in the most sections first. */
  getRepositoryContextSpecModules: (workspaceId: string) => Promise<ContextSpecModuleDto[]>;
  getRepositoryContextSpecSources: (workspaceId: string, directory: string) => Promise<ContextSpecSourceDto[]>;
  /** Starts one check of a directory: a turn that saves candidate gaps, then one turn per candidate that reproduces it with a test. */
  startRepositoryContextSpecCheck: (workspaceId: string, request: StartContextSpecCheckRequest) => Promise<ContextSpecCheckStartedDto>;
  /** Hides a finding from the report and records why; nothing is sent to GitHub. */
  dismissRepositoryContextSpecFinding: (workspaceId: string, checkId: string, candidateId: string, request: { reason: string; rule?: string }) => Promise<ContextSpecCheckDto>;
  stopRepositoryContextBuild: (workspaceId: string, taskId: string) => Promise<ContextBuildViewDto>;
  getRepositoryContextBuildStopImpact: (workspaceId: string, taskId: string) => Promise<AgentRuntimeStopImpactDto>;
  stopRepositoryContextBuildRuntime: (workspaceId: string, taskId: string, generation: string) => Promise<ContextBuildViewDto>;
  failRepositoryContextBuild: (workspaceId: string, taskId: string, reason: string) => Promise<ContextBuildViewDto>;
  cleanupRepositoryContextBuild: (workspaceId: string, taskId: string) => Promise<ContextBuildViewDto>;
  /** The workspace's memories with their flags, the memory switches and the guideline files every turn reads; local reads only. */
  getMemoryPage: (workspaceId: string) => Promise<MemoryPageDto>;
  /** Replaces a memory's text with the user's words, which makes it theirs; fails when it changed since `expectedVersion`. */
  editMemory: (workspaceId: string, memoryId: string, expectedVersion: number, title: string, text: string) => Promise<MemoryPageDto>;
  restoreMemory: (workspaceId: string, memoryId: string, expectedVersion: number) => Promise<MemoryPageDto>;
  /** Settles a memory's reports and keeps it as it is. */
  keepMemory: (workspaceId: string, memoryId: string) => Promise<MemoryPageDto>;
  deleteMemory: (workspaceId: string, memoryId: string) => Promise<MemoryPageDto>;
  undoDeleteMemory: (workspaceId: string, memoryId: string) => Promise<MemoryPageDto>;
  /** Undo on a transcript's Remembered row: what the agent remembered goes, or its previous version comes back. */
  undoRememberedMemory: (workspaceId: string, memoryId: string) => Promise<MemoryPageDto>;
  saveMemorySwitches: (workspaceId: string, switches: MemorySwitchesDto) => Promise<MemoryPageDto>;
  /** Learn from my PRs, or Learn from this PR: reads maintainer threads on GitHub and starts one harvest run over the new ones; GitHub is only read. */
  startMemoryHarvest: (workspaceId: string, request: StartMemoryHarvestRequest) => Promise<MemoryHarvestStartedDto>;
  /** Closed pull requests of the monitored repository with the typed number or title words, read from GitHub, to learn from one. */
  searchMemoryPulls: (workspaceId: string, query: string) => Promise<MemoryPullDto[]>;
  /** The workspace's replays of merged pull requests' reviews, newest first. */
  listReviewReplays: (workspaceId: string) => Promise<ReplayDto[]>;
  /** Runs Agent Review twice on a merged pull request at the head its maintainers first reviewed, with memory and without; nothing is posted. */
  startReviewReplay: (workspaceId: string, request: StartReplayRequest) => Promise<ReplayDto>;
  /** The user's call on whether one side of a replay raised one maintainer comment. */
  markReviewReplay: (workspaceId: string, replayId: string, request: MarkReplayRequest) => Promise<ReplayDto>;
  deleteReviewReplay: (workspaceId: string, replayId: string) => Promise<void>;
  setWorkspaceCommitsTarget: (workspaceId: string, branch: string) => Promise<WorkspaceCommitsTargetDto>;
  clearWorkspaceCommitsTarget: (workspaceId: string) => Promise<WorkspaceCommitsTargetDto>;
  renameWorkspace: (workspaceId: string, name: string) => Promise<WorkspaceDto>;
  setWorkspaceAgentModel: (
    workspaceId: string,
    type: AgentModelType,
    configId: string,
    model: string,
    effort: string | null,
  ) => Promise<AgentModelSettingDto>;
  clearWorkspaceAgentModel: (workspaceId: string, type: AgentModelType) => Promise<AgentModelSettingDto>;
  saveWorkspaceGitHubToken: (workspaceId: string, value: string) => Promise<{ login: string }>;
  deleteWorkspaceGitHubToken: (workspaceId: string) => Promise<void>;
  copyWorkspaceGitHubToken: (workspaceId: string) => Promise<boolean>;

  fetchRecent: (query: RecentFilterQuery) => Promise<RecentDayPageDto>;
  searchRecent: (query: string, limit?: number) => Promise<RecentRowDto[]>;
  searchRecentWords: (query: string, limit?: number) => Promise<RecentWordHitDto[]>;
  fetchRecentChanges: () => Promise<RecentChangeDto[]>;
  recordRecentTouch: (touch: RecentTouchRequest) => Promise<void>;
  saveRecentDraft: (draft: RecentDraftRequest) => Promise<void>;
  clearRecent: () => Promise<void>;

  /** Today's agenda; with a workspace, only its items, without the summary and recap. */
  getAgendaToday: (workspaceId?: string) => Promise<AgendaDto>;
  makeAgendaAgain: () => Promise<AgendaDto>;
  /** An empty mark lets stored facts decide again. */
  markAgendaItem: (ref: string, mark: 'DONE' | 'DISMISSED' | null) => Promise<AgendaDto>;
  /** Starts the task a row suggests, as its item's own Start button does. */
  startAgendaItem: (ref: string) => Promise<AgendaDto>;
  getAgendaSchedule: () => Promise<AgendaScheduleDto>;
  saveAgendaSchedule: (schedule: AgendaScheduleDto) => Promise<AgendaScheduleDto>;
  /** The most recent conversations, newest first. */
  listAssistantConversations: () => Promise<AssistantSummaryDto[]>;
  /** One conversation; one nobody wrote in yet is empty. */
  getAssistantConversation: (conversationId: string) => Promise<AssistantViewDto>;
  /** The reply runs in the background; the same key twice adds the message once. A conversation id the panel made up begins a new one. */
  sendAssistantMessage: (conversationId: string, text: string, visible: string[], onScreen: string | null, key: string) => Promise<{ itemId: string }>;
  stopAssistant: () => Promise<void>;
  /** Runs the conversation's last message again after a failed reply. */
  retryAssistant: (conversationId: string) => Promise<{ itemId: string }>;
  /** Keeps a receipt for a card answered with its own button in the panel, in the conversation on screen. */
  recordAssistantAnswer: (conversationId: string, approvalId: string) => Promise<void>;
  /** Keeps the receipt for a draft the user created with its own button. */
  recordAssistantDraft: (conversationId: string, itemId: string) => Promise<void>;
  getAssistantPermissions: () => Promise<AssistantPermissionsDto>;
  saveAssistantPermissions: (permissions: AssistantPermissionsDto) => Promise<AssistantPermissionsDto>;

  listAgentTasks: (workspaceId: string, limit?: number) => Promise<AgentTaskDto[]>;
  listRunningTasks: () => Promise<RunningTask[]>;
  createAgentTask: (request: CreateAgentTaskRequest) => Promise<AgentTaskDto>;
  getAgentTask: (taskId: string, workspaceId: string) => Promise<AgentTaskDto>;
  /** Polled while createAgentTask is in flight; the task may not exist yet, so it takes no workspace. */
  getAgentTaskStartup: (taskId: string) => Promise<AgentTaskStartupDto>;
  /** Version-checked, so a stale screen cannot overwrite a change it never saw. */
  updateAgentTaskDefaults: (
    taskId: string,
    workspaceId: string,
    expectedVersion: number,
    defaults: AgentTaskDefaultsDto,
  ) => Promise<AgentTaskDto>;
  /** A person's name for the task; final, and version-checked like every other task write. */
  renameAgentTask: (taskId: string, workspaceId: string, expectedVersion: number, title: string) => Promise<AgentTaskDto>;
  archiveAgentTask: (taskId: string, workspaceId: string, expectedVersion: number, archived: boolean) => Promise<AgentTaskDto>;
  /** Removes the task, its runtime files, its checkout and its generated branch; a no-op once it is gone. */
  deleteAgentTask: (taskId: string, workspaceId: string) => Promise<void>;
  /** Reads cached PRs on the task's generated/current branch, or refreshes those branches directly from GitHub. */
  listAgentTaskPullRequests: (taskId: string, workspaceId: string, refresh?: boolean) => Promise<PullRequestDto[]>;
  /** Reads every listed task's live checkout at once, by task id; a checkout that cannot be read is absent. */
  listAgentTaskChanges: (workspaceId: string, taskIds: string[]) => Promise<Record<string, AgentTaskChangesDto>>;
  /** Reads what every listed task last said and waits on, by task id, from local stores only. */
  listAgentTaskActivity: (workspaceId: string) => Promise<Record<string, AgentTaskActivityDto>>;
  getCiAutofixRun: (runId: string) => Promise<CiAutofixRunDto>;
  cancelCiAutofixRun: (runId: string) => Promise<CiAutofixRunDto>;
  sendCiAutofixMessage: (runId: string, message: string, idempotencyKey: string, expectedTurnId?: string) => Promise<CiAutofixRunDto>;
  /** What the next turn on this task would be given, derived from what starting one snapshots. */
  getEffectiveTurnSettings: (taskId: string, workspaceId: string) => Promise<AgentTurnSettingsDto>;
  readAgentHistory: (taskId: string, before?: string, after?: string) => Promise<AgentHistoryPageDto>;
  readAgentPendingApprovals: (taskId: string, turnId: string) => Promise<AgentPendingApprovalDto[]>;
  listTaskDiscussions: (query: DiscussionQuery) => Promise<TaskDiscussionDto[]>;
  openTaskDiscussion: (request: OpenDiscussionRequest) => Promise<TaskDiscussionDto>;
  askTaskDiscussion: (request: AskDiscussionRequest) => Promise<TaskDiscussionDto>;
  resolveTaskDiscussion: (request: ResolveDiscussionRequest) => Promise<TaskDiscussionDto>;
  retryTaskDiscussion: (request: RetryDiscussionRequest) => Promise<TaskDiscussionDto>;
  askTaskDiscussionComment: (request: AskCommentDiscussionRequest) => Promise<TaskDiscussionDto>;
  draftTaskDiscussionReply: (request: DraftDiscussionReplyRequest) => Promise<string>;
  listLocalReviewComments: (query: LocalReviewQuery) => Promise<LocalReviewThreadDto[]>;
  getDevelopmentLearning: (taskId: string, workspaceId: string) => Promise<AgentTurnDto | null>;
  createDevelopmentLearningComment: (request: CreateDevelopmentLearningCommentRequest) => Promise<LocalReviewThreadDto>;
  createLocalReviewComment: (request: CreateLocalReviewRequest) => Promise<LocalReviewThreadDto>;
  replyLocalReviewComment: (request: ReplyLocalReviewRequest) => Promise<LocalReviewThreadDto>;
  resolveLocalReviewComment: (request: ResolveLocalReviewRequest) => Promise<LocalReviewThreadDto>;
  retryLocalReviewComment: (request: RetryLocalReviewRequest) => Promise<LocalReviewThreadDto>;
  startAgentTurn: (request: StartAgentTurnRequest) => Promise<AgentTurnDto>;
  /** Adds input to the running turn; the reply says whether it arrived, and UNKNOWN is never retried. */
  steerAgentTurn: (request: SteerAgentTurnRequest) => Promise<AgentInputDeliveryDto>;
  /** Queues a request behind the current turn; version-checked so it cannot race Complete. */
  queueAgentInput: (request: QueueAgentInputRequest) => Promise<AgentQueuedInputDto>;
  listAgentQueue: (taskId: string, workspaceId: string) => Promise<AgentQueuedInputDto[]>;
  /** Removes a waiting input, or records an uncertain one as abandoned. */
  removeAgentQueuedInput: (taskId: string, workspaceId: string, queuedInputId: string) => Promise<AgentQueuedInputDto>;
  editAgentQueuedInput: (taskId: string, workspaceId: string, queuedInputId: string) => Promise<AgentQueuedInputDraftDto>;
  steerAgentQueuedInput: (taskId: string, workspaceId: string, queuedInputId: string, turnId: string) => Promise<AgentInputDeliveryDto>;
  /** Starts the paused queue head on an idle task. */
  resumeAgentQueue: (taskId: string, workspaceId: string) => Promise<AgentTurnDto>;
  getAgentTurn: (turnId: string) => Promise<AgentTurnDto>;
  cancelAgentTurn: (turnId: string) => Promise<AgentTurnDto>;
  resolveAgentApproval: (
    approvalId: string,
    decision: AgentApprovalDecision,
    scopedPermission?: string,
    /** A question tool's answers, keyed by the question Claude asked. */
    answers?: Record<string, string>,
  ) => Promise<AgentApprovalDto>;
  completeAgentTask: (taskId: string, workspaceId: string, notes?: string) => Promise<AgentCompletionDto>;
  /** Retries immediate removal of a terminal task's verified clean checkout. */
  cleanupAgentTaskWorktree: (taskId: string, workspaceId: string) => Promise<AgentTaskDto>;
  /** Fails a task whose provider could not be shown quiet; its checkout is kept unless clean. */
  markAgentTaskFailed: (taskId: string, workspaceId: string, reason: string) => Promise<AgentTaskDto>;
  /** Stops the task's merge while it waits for its delay; it stays stopped. */
  cancelAgentAutoMerge: (taskId: string, workspaceId: string) => Promise<AgentTaskDto>;
  getAgentRuntimeStopImpact: (taskId: string, workspaceId: string) => Promise<AgentRuntimeStopImpactDto>;
  /** Stops only this task's provider runtime; the generation is the one the user confirmed. */
  stopAgentRuntime: (taskId: string, workspaceId: string, generation: string) => Promise<AgentTaskDto>;
  /** Opens the native image picker; Electron main reads the files and uploads them, the renderer sees facts only. */
  pickAgentDraftAttachments: () => Promise<AgentDraftAttachmentDto[]>;
  /** Reads a native clipboard image only when the user pastes into the composer. */
  pasteAgentDraftAttachments: () => Promise<AgentDraftAttachmentDto[]>;
  discardAgentDraftAttachments: (attachmentIds: string[]) => Promise<void>;
  pickAgentAttachments: (taskId: string, workspaceId: string) => Promise<AgentAttachmentDto[]>;
  pasteAgentAttachments: (taskId: string, workspaceId: string) => Promise<AgentAttachmentDto[]>;
  readAgentAttachment: (taskId: string, workspaceId: string, attachmentId: string) => Promise<AgentAttachmentBytesDto>;
  removeAgentAttachment: (taskId: string, workspaceId: string, attachmentId: string) => Promise<void>;
  findPullRequestTask: (workspaceId: string, repo: string, number: number) => Promise<PullRequestTaskLink | null>;
  assignPullRequestAgent: (workspaceId: string, repo: string, number: number) => Promise<PullRequestTaskLink>;
  /** Private reviews belonging to this workspace, including ended reviews. */
  listAgentReviews: (workspaceId: string) => Promise<AgentReviewDto[]>;
  /** The same list, or null reviews when nothing changed since the read whose etag the caller passed. */
  listAgentReviewsIfChanged: (workspaceId: string, etag: string | null) => Promise<{ etag: string | null; reviews: AgentReviewDto[] | null }>;
  /** The open Agent review of a pull request, or null when it has none. */
  findAgentReview: (workspaceId: string, repo: string, number: number) => Promise<AgentReviewDto | null>;
  /** Starts a private review, or returns the open one: one review per workspace and pull request. */
  startAgentReview: (workspaceId: string, repo: string, number: number) => Promise<AgentReviewDto>;
  prepareReviewWorktree: (workspaceId: string, repo: string, number: number) => Promise<AgentReviewDto>;
  findTaskAgentReview: (workspaceId: string, taskId: string) => Promise<AgentReviewDto | null>;
  startTaskAgentReview: (workspaceId: string, taskId: string) => Promise<AgentReviewDto>;
  getAgentReview: (reviewId: string) => Promise<AgentReviewDto>;
  stopAgentReview: (reviewId: string) => Promise<AgentReviewDto>;
  getAgentReviewRuntimeStopImpact: (reviewId: string) => Promise<AgentRuntimeStopImpactDto>;
  /** Verifies only this review's retained runtime, keeping its conversation and worktree. */
  stopAgentReviewRuntime: (reviewId: string, generation: string) => Promise<AgentReviewDto>;
  resumeAgentReview: (reviewId: string) => Promise<AgentReviewDto>;
  /** Refreshes the pull request now and follows its head. */
  updateAgentReview: (reviewId: string) => Promise<AgentReviewDto>;
  configureAgentReview: (reviewId: string, expectedVersion: number, laneId: string, model: string, effort: string | null, serviceTier: string | null) => Promise<AgentReviewDto>;
  /** Regenerates the change intention and commit explanations at the latest committed head. */
  refreshAgentReviewExplanation: (reviewId: string, runId: string, expectedRevision: number) => Promise<AgentReviewDto>;
  /** Completes the task, closes the review, then cleans the worktree. */
  endAgentReview: (reviewId: string) => Promise<AgentReviewDto>;
  /** Private discussions of the review's answers and GitHub comments. */
  listReviewDiscussions: (reviewId: string) => Promise<TaskDiscussionDto[]>;
  openReviewDiscussion: (reviewId: string, request: Omit<OpenDiscussionRequest, keyof DiscussionQuery>) => Promise<TaskDiscussionDto>;
  askReviewDiscussion: (reviewId: string, discussionId: string, question: string) => Promise<TaskDiscussionDto>;
  /** Asks the review's agent about one comment of the review's own pull request; a blank question asks for the summary. */
  askReviewCommentThread: (reviewId: string, key: string, question: string) => Promise<TaskDiscussionDto>;
  resolveReviewDiscussion: (reviewId: string, discussionId: string, resolved: boolean, expectedVersion: number) => Promise<TaskDiscussionDto>;
  retryReviewDiscussion: (reviewId: string, discussionId: string, messageId: string) => Promise<TaskDiscussionDto>;
  /** Drafts a GitHub reply in the reviewer's voice; nothing is posted. */
  draftReviewCommentReply: (reviewId: string, discussionId: string) => Promise<string>;
  /** Every file of the host's working copy, or of a review's reviewed revision once its working copy is gone. */
  readWorktreeChanges: (host: CodeHostRef, request: WorktreeChangesRequest) => Promise<WorktreeChangesDto>;
  listCodeFiles: (host: CodeHostRef) => Promise<ReviewCodeFilesDto>;
  readCodeFile: (host: CodeHostRef, path: string, expectedRevision?: string) => Promise<ReviewCodeFileDto>;
  /** Test runs of the host's task, newest first; a review's include its agent's mirrored runs. */
  listCodeRuns: (host: CodeHostRef) => Promise<LocalRunDto[]>;
  /** Runs a test class, or one method, from its source path; a refused preflight returns why instead of a run. */
  stopCodeRun: (host: CodeHostRef, runId: string) => Promise<LocalRunDto>;
  readCodeRunOutput: (host: CodeHostRef, runId: string, after: number) => Promise<LocalRunOutputDto>;
  /** Writes an editable file of the working copy only if it still has the hash the edit started from. */
  /** Attaches to the host's login shell in its working copy, starting one if none is alive; replays its recent output. */
  openCodeTerminal: (host: CodeHostRef, cols: number, rows: number) => Promise<{ terminalId: string; scrollback: string }>;
  /** Runs this lane's own sign-in command in a one-shot login shell; it exits when the sign-in ends. */
  openProviderSignIn: (laneId: string, cols: number, rows: number) => Promise<{ terminalId: string; scrollback: string }>;
  writeReviewTerminal: (terminalId: string, data: string) => void;
  resizeReviewTerminal: (terminalId: string, cols: number, rows: number) => void;
  onReviewTerminalEvent: (listener: (event: { terminalId: string; data: string } | { terminalId: string; exitCode: number }) => void) => () => void;
  /** A private question on the summary or one finding; it queues visibly while the agent is busy. The key makes a retry the same question. */
  askAgentReview: (
    reviewId: string,
    findingId: string | null,
    targetRevision: number,
    message: string,
    idempotencyKey: string,
    cardId?: string,
  ) => Promise<AgentReviewDto>;
  /** A free-form message to the review agent: a turn, a steer into the running turn it names, or the queue. */
  messageAgentReview: (
    reviewId: string,
    message: string,
    delivery: 'TURN' | 'STEER' | 'QUEUE',
    idempotencyKey: string,
    turnId?: string,
  ) => Promise<AgentReviewDto>;
  /** Starts the next message of a queue that paused after a turn did not succeed. */
  resumeAgentReviewQueue: (reviewId: string) => Promise<AgentReviewDto>;
  removeAgentReviewQueued: (reviewId: string, queuedInputId: string) => Promise<AgentReviewDto>;
  steerAgentReviewQueued: (reviewId: string, queuedInputId: string, turnId: string) => Promise<AgentReviewDto>;
  resolveAgentReviewFinding: (
    reviewId: string,
    findingId: string,
    expectedVersion: number,
    reason: string,
    remember: boolean,
  ) => Promise<AgentReviewDto>;
  reopenAgentReviewFinding: (reviewId: string, findingId: string, expectedVersion: number) => Promise<AgentReviewDto>;
  readAgentReviewHistory: (reviewId: string, taskId: string, before?: string, after?: string) => Promise<AgentHistoryPageDto>;
  /** Up to 100 review task events after a sequence number, in order. */
  readAgentReviewEvents: (reviewId: string, after: number) => Promise<AgentEventEnvelope[]>;
  readAgentReviewApprovals: (reviewId: string, taskId: string, turnId: string) => Promise<AgentPendingApprovalDto[]>;
  resolveAgentReviewApproval: (
    reviewId: string,
    approvalId: string,
    decision: AgentApprovalDecision,
    /** A question tool's answers, keyed by the question Claude asked. */
    answers?: Record<string, string>,
  ) => Promise<AgentApprovalDto>;
  /** Streams the review task's events; the returned function stops the subscription. */
  subscribeAgentReview: (
    reviewId: string,
    after: number,
    listener: (message: AgentStreamMessage) => void,
    statusOnly?: boolean,
  ) => Promise<() => void>;

  /** The user's local pending review for one pull request; it exists without any agent review. */
  getReviewDraft: (repo: string, number: number, workspaceId?: string) => Promise<ReviewDraftDto>;
  addReviewDraftComment: (
    repo: string,
    number: number,
    expectedVersion: number,
    comment: { path: string; side: ReviewSide; line: number; startLine: number | null; body: string; commitId: string | null; replyToCommentId?: number | null },
    workspaceId?: string,
  ) => Promise<ReviewDraftDto>;
  addReviewDraftFinding: (
    repo: string,
    number: number,
    expectedVersion: number,
    reviewId: string,
    findingId: string,
    workspaceId?: string,
  ) => Promise<ReviewDraftDto>;
  editReviewDraftComment: (
    repo: string,
    number: number,
    expectedVersion: number,
    itemId: string,
    body: string,
    included: boolean,
    workspaceId?: string,
  ) => Promise<ReviewDraftDto>;
  removeReviewDraftComment: (repo: string, number: number, expectedVersion: number, itemId: string, workspaceId?: string) => Promise<ReviewDraftDto>;
  /** Returns an uncertain comment to pending after the user confirms it is absent on GitHub; does not publish it. */
  confirmReviewDraftCommentNotPosted: (repo: string, number: number, expectedVersion: number, itemId: string, workspaceId?: string) => Promise<ReviewDraftDto>;
  /** Records an uncertain comment as posted after the user finds it on GitHub; it is never sent again. */
  confirmReviewDraftCommentPosted: (repo: string, number: number, expectedVersion: number, itemId: string, workspaceId?: string) => Promise<ReviewDraftDto>;
  /** Settles review text whose send has an unknown outcome: posted clears it like a successful send, not posted unlocks it. Never publishes. */
  confirmReviewDraftReview: (repo: string, number: number, expectedVersion: number, posted: boolean, workspaceId?: string) => Promise<ReviewDraftDto>;
  saveReviewDraft: (repo: string, number: number, expectedVersion: number, body: string, event: ReviewEvent, workspaceId?: string) => Promise<ReviewDraftDto>;
  /** Publishes one previewed comment at its saved commit; the backend validates the payload first. */
  publishReviewDraftComment: (repo: string, number: number, expectedVersion: number, itemId: string, workspaceId?: string) => Promise<void>;
  submitReviewDraft: (repo: string, number: number, expectedVersion: number, body: string, event: ReviewEvent, headSha: string, workspaceId?: string) => Promise<void>;
  /** Why each pending comment cannot be sent right now; an eligible one is absent. */

  /** Streams one task's events; the returned function stops the subscription. */
  subscribeAgentTask: (
    taskId: string,
    after: number,
    listener: (message: AgentStreamMessage) => void,
    statusOnly?: boolean,
  ) => Promise<() => void>;
};

type WorkspaceIssueMetadataDto = {
  assignees: string[];
  labels: string[];
};
