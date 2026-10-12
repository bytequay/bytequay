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

import type { WorkItemBridge } from './workitems/contracts';
import type { AppNoticeBounds } from './notices/contracts';
import type { GoogleBridge } from './google/contracts';
import type { JiraBridge, JiraSourceRequest, JiraTaskSource } from './jira/contracts';
import type { TeamActivityQuery, TeamActivitySnapshot, TeamCircleInput, TeamCreateInput, TeamDirectory, TeamMemberInput } from './teams/types';
import type { CreateWorkItem, EpicJoinDto, EpicRecordDto, WorkItemDto, WorkItemDecision, GoalDto, GoalExchangeDto, GoalItemDto, GoalItemKind, GoalGapDto, GoalKnowledgeNoteDto, GoalLessonDto, GoalPatch, GoalRecommendationDto, TopicLevel } from './goals/contracts';
import { AgentEventEnvelope } from './agents/events';
import type { AskCommentDiscussionRequest, AskDiscussionRequest, DiscussionQuery, DraftDiscussionReplyRequest, OpenDiscussionRequest, ResolveDiscussionRequest, RetryDiscussionRequest, TaskDiscussionDto } from './agents/discussions/contracts';
import type { CreateDevelopmentLearningCommentRequest, CreateLocalReviewRequest, LocalReviewQuery, ReplyLocalReviewRequest, ResolveLocalReviewRequest, RetryLocalReviewRequest } from './pulls/local-review/contracts';
import type { LocalReviewThreadDto } from './pulls/local-review/types';
import type {
  ContextBaselineDto,
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
import type { WorktreeChangesDto, WorktreeChangesRequest, CodeHostRef, LocalRunDto, LocalRunOutputDto, ReviewCodeFileDto, ReviewCodeFilesDto, SaveConflictDto } from './pulls/code-tests/contracts';

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
  /** Local monitoring baseline and latest observed GitHub activity. */
  monitoredAt?: string | null;
  activityAt?: string | null;
  reviewRequestedAt?: string | null;
  reviewDecision?: 'APPROVED' | 'CHANGES_REQUESTED' | null;
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
  'mergeable' | 'mergeableState' | 'ciStatus' | 'checkRuns' | 'unavailableSections'>;

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

export type PullTimelinePayloadDto = {
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

export type WorkspaceDto = {
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

/** Match counts reported by the window's find-in-page search. */
export type FindResult = {
  activeMatchOrdinal: number;
  matches: number;
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

export type DevelopmentAutomationDto = {
  autoDraftPr: boolean;
  autoCiFix: boolean;
  ciLaneId: string | null;
  /** Task-level: the review summary watcher reads it from task defaults only. */
  reviewSummary: boolean;
};

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
  localReviewHead?: string | null;
  developmentContinuationUnfinished: boolean;
  developmentCi?: AgentDevelopmentCiDto | null;
  /** The pull request the backend picked from the generated branch's stored rows; absent without one. */
  pullRequest?: AgentTaskPullRequestDto | null;
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

export type AgentDevelopmentCiDto = {
  runId: string;
  agentTaskId: string | null;
  currentTurnId: string | null;
  status: 'ACTIVE' | 'PAUSED' | 'FINISHED';
  activity: string;
  attemptsUsed: number;
  maxAttempts: number;
  rerunsUsed: number;
  summary: string | null;
  finishReason: string | null;
  terminalAt: string | null;
  headRef: string;
  expectedRemoteHead: string;
  pullRequestNumber: number;
  pullRequestUrl: string;
  canMessage: boolean;
};

export type CiAutofixRunDto = {
  id: string;
  workspaceId: string;
  repository: string;
  pullRequestNumber: number;
  headRef: string;
  status: 'ACTIVE' | 'PAUSED' | 'FINISHED';
  activity: string;
  version: number;
  authorization: { maxAttempts: number; expiresAt: string };
  attemptsUsed: number;
  rerunsUsed: number;
  agentTaskId: string | null;
  currentTurnId: string | null;
  parentTaskId: string | null;
  canMessage: boolean;
  summary: string | null;
  pauseReason: string | null;
  finishReason: string | null;
  createdAt: string;
  updatedAt: string;
};

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
export type AgentInputDeliveryDto = {
  id: string;
  taskId: string;
  turnId: string;
  state: string;
  failure: string | null;
  createdAt: string;
  updatedAt: string;
};

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
export type AgentQueuedInputDto = {
  id: string;
  taskId: string;
  position: number;
  state: string;
  preview: string;
  settings: AgentTurnSettingsDto;
  turnId: string | null;
  pauseReason: string | null;
  createdAt: string;
  updatedAt: string;
};

/** A stored, verified image the task owns; the renderer only ever sees these facts. */
export type AgentAttachmentDto = {
  id: string;
  name: string;
  mediaType: string;
  byteSize: number;
  width: number;
  height: number;
};

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
export type AgentRuntimeStopImpactDto = {
  taskId: string;
  generation: string;
  activeTurnId: string | null;
  liveProcess: boolean;
};

/** The creation step an in-flight createAgentTask is in; null before the first step and once the call has returned. */
export type AgentTaskStartupPhase = 'PROVIDER' | 'REPOSITORY' | 'NAMING' | 'WORKTREE' | 'ATTACHMENTS' | 'SESSION';
export type AgentTaskStartupDto = { phase: AgentTaskStartupPhase | null };

export type AgentCompletionDto = {
  taskId: string;
  summary: AgentTaskSummaryDto;
  completedAt: string;
  deleteAfter: string;
};

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

export type CherryPickExecution = {
  executionId: string;
  workspaceId: string;
  repository: string;
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
  /** When the execution started and last moved, which the Tasks page groups it by. */
  createdAt: string;
  updatedAt: string;
  terminalAt: string | null;
};

type WorkspacePushResult = {
  remote: string;
  head: string;
  sha: string;
};

type WorkspacePullRequestResult = {
  providerId: number;
  number: number;
  url: string;
  baseRepository: RepoRefDto;
  baseBranch: string;
  head: string;
};

type WorkspaceCiAutofixStatus = {
  runId: string;
  status: 'ACTIVE' | 'PAUSED' | 'FINISHED';
  attemptsUsed: number;
  summary?: string | null;
};

export type WorkspaceDeliveryStatus = {
  pushWritable: boolean;
  disabledReason?: string | null;
  baseRepository: RepoRefDto;
  baseBranch: string;
  head: string;
  push?: WorkspacePushResult | null;
  pullRequest?: WorkspacePullRequestResult | null;
  ciAutofix?: WorkspaceCiAutofixStatus | null;
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

export type WorkspaceRemoteDto = {
  name: string;
  url: string;
  role: 'ORIGIN' | 'UPSTREAM' | 'CUSTOM';
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
    id: string; workspaceId: string; requestingTaskId: string; sourceTaskId: string | null; source: BackportChainRouteDto;
    sourcePullRequest: BackportChainPrDto | null; sourceMerged: boolean;
    acceptances: { operationKey: string; turnId: string; destinations: string[] }[];
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

export type WorkspaceSettingsDto = {
  remotes: WorkspaceRemoteDto[];
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

export type ProviderUsageDto = {
  accountId: string;
  plan: string | null;
  windows: ProviderUsageWindowDto[];
  checkedAt: string;
  message: string | null;
};

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


/** Dark has no palette yet; the picker offers System and Light. */
export type AppThemeDto = 'SYSTEM' | 'LIGHT' | 'DARK';

export type AppPreferencesDto = {
  taskRetentionDays: number;
  recentRetentionDays: number;
  prWarmCount: 10 | 30;
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

export type Bridge = WorkItemBridge & JiraBridge & GoogleBridge & {
  onAppNoticeBounds: (callback: (bounds: AppNoticeBounds) => void) => () => void;
  onAppNoticeOpen: (callback: (target: { taskId: string; workspaceId: string; turnId: string | null }) => void) => () => void;
  setNoticeSource: (key: string, source: { taskId: string; turnId: string | null } | null) => Promise<void>;
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  fetchPrs: (workspaceId: string | undefined, page: number, pageSize: number) => Promise<PullRequestPageDto>;
  fetchAttentionPrs: () => Promise<PullRequestDto[]>;
  findStoredPullRequest: (repo: string, number: number) => Promise<PullRequestDto | null>;
  lookupPullRequest: (repo: string, number: number) => Promise<PullRequestDto>;
  /** The description a detail read cached, without reaching GitHub or marking the PR viewed; null until one did. */
  findStoredPullRequestDescription: (repo: string, number: number) => Promise<{ body: string | null }>;
  setPrMonitored: (prId: number, monitored: boolean) => Promise<PullRequestDto>;
  setPrAttentionHandled: (handled: import('./home/attention').Handled) => Promise<void>;
  setPrAttentionPinned: (keys: string[]) => Promise<void>;
  onPrAttentionUpdated: (callback: (prs: PullRequestDto[]) => void) => () => void;
  onPrNotificationOpen: (callback: (reference: { repo: string; number: number }) => void) => () => void;
  syncPrs: (workspaceId?: string) => Promise<void>;
  onPullRequestsSynced: (callback: () => void) => () => void;
  fetchPullRequestDetail: (prId: number) => Promise<PullRequestDetailDto>;
  fetchPullRequestDetailByRef: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchRepoUsers: (repo: string, workspaceId?: string) => Promise<RepoUserDto[]>;
  fetchRepoLabels: (repo: string) => Promise<RepoLabelDto[]>;
  updatePrMetadata: (repo: string, number: number, assignees: string[], labels: string[]) => Promise<void>;
  updatePrReviewer: (repo: string, number: number, login: string, requested: boolean) => Promise<void>;
  fetchMergeMode: (repo: string, branch: string) => Promise<MergeModeDto>;
  refreshPullRequestDetailByRef: (repo: string, number: number, checksOnly?: boolean, opened?: boolean) => Promise<PullRequestDetailRefreshDto>;
  refreshAllPullRequestDetailByRef: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchPrDiffFiles: (
    repo: string,
    number: number,
    baseSha?: string,
    headSha?: string,
  ) => Promise<DiffFileDto[]>;
  refreshPrDiffFiles: (
    repo: string,
    number: number,
    baseSha?: string,
    headSha?: string,
  ) => Promise<DiffFileDto[]>;
  fetchPrDiffCommits: (repo: string, number: number) => Promise<DiffCommitDto[]>;
  refreshPrDiffFileContent: (
    repo: string,
    number: number,
    path: string,
    baseSha?: string,
    headSha?: string,
  ) => Promise<DiffFileContentDto>;
  postPrReviewComment: (
    repo: string,
    number: number,
    comment: InlineReviewCommentDto,
  ) => Promise<PullRequestDetailDto>;
  updatePrDescription: (repo: string, number: number, body: string) => Promise<void>;
  updatePrTitle: (repo: string, number: number, title: string) => Promise<void>;
  commentPr: (repo: string, number: number, body: string) => Promise<PullRequestDetailDto>;
  setPrReaction: (
    repo: string,
    number: number,
    target: PullReactionTarget,
    commentId: number | null,
    content: PullReactionContent,
    viewerHasReacted: boolean | null,
  ) => Promise<{ viewerHasReacted: boolean }>;
  editPrComment: (repo: string, number: number, target: PullCommentTarget, commentId: number, body: string) => Promise<PullRequestDetailDto>;
  closePr: (repo: string, number: number) => Promise<void>;
  markPrReadyForReview: (repo: string, number: number) => Promise<void>;
  replyToReviewComment: (repo: string, number: number, commentId: number, body: string) => Promise<void>;
  setReviewThreadResolved: (repo: string, number: number, commentId: number, resolved: boolean) => Promise<void>;
  getPrCheckLog: (repo: string, number: number, jobId: number) => Promise<{ text: string }>;
  rerunFailedPrChecks: (repo: string, number: number) => Promise<void>;
  submitReview: (
    prId: number,
    repo: string,
    number: number,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
    body: string,
    comments?: InlineReviewCommentDto[],
  ) => Promise<PullRequestDetailDto>;
  betterWords: (text: string, repository?: string, workspaceId?: string) => Promise<string>;
  mergePr: (prId: number, repo: string, number: number, strategy: string) => Promise<void>;
  removePrFromMergeQueue: (repo: string, number: number) => Promise<void>;

  searchRepositories: (query: string, limit: number) => Promise<RepositorySearchResultDto[]>;
  previewWatch: (selectedRepositoryId: number, topology?: WatchRepositoryTopology) => Promise<WatchRepositoryPreviewDto>;
  watchRepository: (command: WatchRepositoryCommand) => Promise<WorkspaceDto>;
  listWorkspaces: () => Promise<WorkspaceDto[]>;
  getBackportChains: (workspaceId: string) => Promise<BackportChainViewDto[]>;
  selectBackportSource: (workspaceId: string, id: string, selection: { version: number; hopIndex: number; oldest: string; newest: string }) => Promise<BackportChainViewDto>;
  stopBackportChain: (workspaceId: string, id: string) => Promise<BackportChainViewDto>;
  resumeBackportChain: (workspaceId: string, id: string) => Promise<BackportChainViewDto>;
  getLinkedWorkspaceWork: (workspaceId: string) => Promise<LinkedWorkspaceWorkDto[]>;
  getRelatedWorkspaceRequest: (workspaceId: string, taskId: string) => Promise<LinkedWorkspaceWorkDto | null>;
  getWorkspaceRelationships: (workspaceId: string) => Promise<WorkspaceRelationshipDto[]>;
  saveWorkspaceRelationship: (workspaceId: string, relationship: WorkspaceRelationshipDto) => Promise<WorkspaceRelationshipDto[]>;
  removeWorkspaceRelationship: (workspaceId: string, destinationId: string) => Promise<WorkspaceRelationshipDto[]>;
  listRetiredWorkspaces: () => Promise<WorkspaceDto[]>;
  getWorkspace: (workspaceId: string) => Promise<WorkspaceDto>;
  getWorkspaceMetadata: (workspaceId: string) => Promise<RepositoryMetadataDto>;
  fetchWorkspaceIssues: (workspaceId: string, page: number, pageSize: number) => Promise<RepositoryIssueSnapshotDto>;
  fetchWorkspaceIssue: (workspaceId: string, issueNumber: number) => Promise<RepositoryIssueSnapshotDto['issues'][number] | null>;
  fetchWorkspaceIssueAttention: (workspaceId: string) => Promise<RepositoryIssueSnapshotDto>;
  setWorkspaceIssueEpic: (workspaceId: string, issueNumber: number, isEpic: boolean) => Promise<IssueDevelopmentDto>;
  /** The workspace's goals with every item read locally; nothing here writes to GitHub or Jira. */
  listWorkItems: (workspaceId: string) => Promise<WorkItemDto[]>;
  createWorkItem: (workspaceId: string, request: CreateWorkItem) => Promise<WorkItemDto>;
  decideWorkItem: (workspaceId: string, itemId: string, command: WorkItemDecision) => Promise<WorkItemDto>;
  linkWorkItem: (workspaceId: string, itemId: string, revision: number, kind: GoalItemKind, key: string) => Promise<WorkItemDto>;
  orderWorkItems: (workspaceId: string, goalId: string, ids: string[]) => Promise<WorkItemDto[]>;
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
  listGoals: (workspaceId: string) => Promise<GoalDto[]>;
  createGoal: (workspaceId: string, title: string, description: string) => Promise<GoalDto>;
  updateGoal: (workspaceId: string, goalId: string, patch: GoalPatch) => Promise<GoalDto>;
  deleteGoal: (workspaceId: string, goalId: string) => Promise<void>;
  addGoalItem: (workspaceId: string, goalId: string, kind: GoalItemKind, key: string) => Promise<GoalDto>;
  removeGoalItem: (workspaceId: string, goalId: string, kind: GoalItemKind, key: string) => Promise<GoalDto>;
  searchGoalCandidates: (workspaceId: string, query: string) => Promise<GoalItemDto[]>;
  /** Starts a goal from the user's answer to "What do you want to achieve?"; its agent answers in the background. */
  startGoal: (workspaceId: string, operationId: string, message: string) => Promise<GoalDto>;
  listGoalExchanges: (workspaceId: string, goalId: string) => Promise<GoalExchangeDto[]>;
  /** @param lessonId the lesson the user asks about, whose content the agent reads with the question */
  askGoal: (workspaceId: string, goalId: string, operationId: string, message: string, lessonId?: string | null) => Promise<GoalExchangeDto[]>;
  retryGoalExchange: (workspaceId: string, goalId: string, exchangeId: string) => Promise<GoalExchangeDto[]>;
  /** Stops the reply the agent is writing; the message stays to ask again. */
  stopGoalExchange: (workspaceId: string, goalId: string, exchangeId: string) => Promise<GoalExchangeDto[]>;
  /** Update now: asks the goal's agent to reassess the goal from its current facts, also while guidance is paused. */
  reassessGoal: (workspaceId: string, goalId: string, operationId: string) => Promise<GoalExchangeDto[]>;
  /** Writes the summary of the goal's finished work again, after its acceptance did not get one. */
  summarizeGoalWork: (workspaceId: string, goalId: string, workItemId: string) => Promise<void>;
  /** The model the goal's agent answers and writes lessons with; it follows the workspace's Development model until the goal has its own. */
  getGoalModel: (workspaceId: string, goalId: string) => Promise<AgentModelSettingDto>;
  setGoalModel: (workspaceId: string, goalId: string, configId: string, model: string, effort: string | null) => Promise<AgentModelSettingDto>;
  clearGoalModel: (workspaceId: string, goalId: string) => Promise<AgentModelSettingDto>;
  acceptGoalDefinition: (workspaceId: string, goalId: string, exchangeId: string, title: string) => Promise<GoalDto>;
  /** Accepts a proposed course: each lesson becomes a Learn work item of the goal, and nothing starts. */
  acceptGoalCourse: (workspaceId: string, goalId: string, exchangeId: string) => Promise<GoalDto>;
  acceptGoalPlan: (workspaceId: string, goalId: string, exchangeId: string) => Promise<GoalDto>;
  /** Start on a card under the Goal control: the research, check or work it recommends begins. */
  startGoalRecommendation: (workspaceId: string, goalId: string, recommendationId: string) => Promise<GoalRecommendationDto>;
  dismissGoalRecommendation: (workspaceId: string, goalId: string, recommendationId: string) => Promise<GoalRecommendationDto>;
  listGoalKnowledge: (workspaceId: string, goalId: string) => Promise<GoalKnowledgeNoteDto[]>;
  /** Remember on a note citing a specification page: it becomes the user's SPEC memory of the workspace. */
  rememberGoalNote: (workspaceId: string, goalId: string, noteId: string) => Promise<GoalKnowledgeNoteDto>;
  listGoalGaps: (workspaceId: string, goalId: string) => Promise<GoalGapDto[]>;
  /** Adds a reproduced gap to the goal as work; answers with the goal's gaps. */
  addGoalGap: (workspaceId: string, goalId: string, candidateId: string) => Promise<GoalGapDto[]>;
  /** The user's own assessment of a topic, which the agent leaves alone from then on. */
  setGoalTopic: (workspaceId: string, goalId: string, topic: string, level: TopicLevel) => Promise<GoalDto>;
  /** Opens a lesson of the goal's course, writing it the first time; opening it is what Start means on its card. */
  /** Opens a lesson, writing it the first time, or again when asked. */
  openGoalLesson: (workspaceId: string, goalId: string, lessonId: string, again?: boolean) => Promise<GoalLessonDto>;
  readGoalLesson: (workspaceId: string, goalId: string, lessonId: string) => Promise<GoalLessonDto>;
  /** Done is the user's decision: accepts the lesson's work item and tells the goal's agent. */
  finishGoalLesson: (workspaceId: string, goalId: string, lessonId: string, operationId: string) => Promise<GoalDto>;
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
  getUserProfile: () => Promise<UserProfileDto>;
  getGitHubUserProfile: (login: string) => Promise<UserProfileDto>;
  getContributionCalendar: (login: string) => Promise<ContributionCalendarDto>;
  getUserCommitsOnDate: (login: string, date: string) => Promise<UserCommitDto[]>;
  fetchGitHubIssuePreview: (owner: string, repo: string, number: number) => Promise<GitHubIssuePreviewDto>;
  fetchGitHubCodeSnippet: (request: GitHubCodeSnippetRequest) => Promise<GitHubCodeSnippetDto>;
  getRecentActivity: (login: string) => Promise<RecentEventDto[]>;
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

  openInAppBrowser: (url: string) => Promise<void>;
  openExternal: (url: string) => Promise<void>;
  getInAppBrowserState: () => Promise<InAppBrowserState>;
  onInAppBrowserState: (callback: (state: InAppBrowserState) => void) => () => void;
  selectInAppBrowserTab: (id: string) => Promise<void>;
  pinInAppBrowserTab: (id: string, pinned: boolean) => Promise<void>;
  closeInAppBrowserTab: (id: string) => Promise<void>;
  hideInAppBrowser: () => Promise<void>;
  mountInAppBrowser: (id: string, bounds: { x: number; y: number; width: number; height: number }) => Promise<void>;
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
  findInPage: (text: string, options: { forward: boolean; findNext: boolean }) => Promise<void>;
  stopFindInPage: () => Promise<void>;
  onFindResult: (callback: (result: FindResult) => void) => () => void;

  previewCherryPickRange: (request: CherryPickPreviewRequest) => Promise<CherryPickPreview>;
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
  getWorkspaceDeliveryStatus: (
    workspaceId: string,
    executionId: string,
  ) => Promise<WorkspaceDeliveryStatus>;
  pushWorkspaceExecution: (
    workspaceId: string,
    executionId: string,
  ) => Promise<WorkspacePushResult>;
  createWorkspacePullRequest: (
    workspaceId: string,
    executionId: string,
  ) => Promise<WorkspacePullRequestResult>;

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
  refreshModelSettings: (force?: boolean) => Promise<ModelSettingsDto>;
  setAgentModel: (
    type: AgentModelType,
    configId: string,
    model: string,
    effort: string | null,
  ) => Promise<ModelSettingsDto>;
  clearAgentModel: (type: AgentModelType) => Promise<ModelSettingsDto>;
  addModelApiKey: (provider: string, alias: string, key: string) => Promise<ModelSettingsDto>;
  addClaudeAccount: (label: string, email: string) => Promise<ModelSettingsDto>;
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
  getGitHubAccount: () => Promise<GitHubAccountDto>;
  /** Copies the stored token to the clipboard in the main process; the renderer never sees it. */
  copyGitHubToken: () => Promise<boolean>;
  deleteGitHubToken: () => Promise<void>;

  getWorkspaceSettings: (workspaceId: string) => Promise<WorkspaceSettingsDto>;
  getRepositoryContextBaseline: (workspaceId: string) => Promise<ContextBaselineDto | null>;
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
  addWorkspaceRemote: (workspaceId: string, name: string, url: string) => Promise<WorkspaceRemoteDto[]>;
  removeWorkspaceRemote: (workspaceId: string, name: string) => Promise<WorkspaceRemoteDto[]>;
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

  listAgentTasks: (workspaceId: string, limit?: number) => Promise<AgentTaskDto[]>;
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
  listAgentTaskChanges: (workspaceId: string) => Promise<Record<string, AgentTaskChangesDto>>;
  /** Reads what every listed task last said and waits on, by task id, from local stores only. */
  listAgentTaskActivity: (workspaceId: string) => Promise<Record<string, AgentTaskActivityDto>>;
  getCiAutofixRun: (runId: string) => Promise<CiAutofixRunDto>;
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
  saveCodeFile: (host: CodeHostRef, path: string, content: string, expectedHash: string) => Promise<{ file: ReviewCodeFileDto } | { conflict: SaveConflictDto }>;
  /** Attaches to the host's login shell in its working copy, starting one if none is alive; replays its recent output. */
  openCodeTerminal: (host: CodeHostRef, cols: number, rows: number) => Promise<{ terminalId: string; scrollback: string }>;
  /** Runs this lane's own sign-in command in a one-shot login shell; it exits when the sign-in ends. */
  openProviderSignIn: (laneId: string, cols: number, rows: number) => Promise<{ terminalId: string; scrollback: string }>;
  writeReviewTerminal: (terminalId: string, data: string) => void;
  resizeReviewTerminal: (terminalId: string, cols: number, rows: number) => void;
  onReviewTerminalEvent: (listener: (event: { terminalId: string; data: string } | { terminalId: string; exitCode: number }) => void) => () => void;
  /** A private question on the summary or one finding; it queues visibly while the agent is busy. */
  askAgentReview: (
    reviewId: string,
    findingId: string | null,
    targetRevision: number,
    message: string,
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
  addReviewDraftComment: (
    repo: string,
    number: number,
    expectedVersion: number,
    comment: { path: string; side: ReviewSide; line: number; startLine: number | null; body: string; commitId: string | null; replyToCommentId?: number | null },
  ) => Promise<ReviewDraftDto>;
  addReviewDraftFinding: (
    repo: string,
    number: number,
    expectedVersion: number,
    reviewId: string,
    findingId: string,
  ) => Promise<ReviewDraftDto>;
  editReviewDraftComment: (
    repo: string,
    number: number,
    expectedVersion: number,
    itemId: string,
    body: string,
    included: boolean,
  ) => Promise<ReviewDraftDto>;
  removeReviewDraftComment: (repo: string, number: number, expectedVersion: number, itemId: string) => Promise<ReviewDraftDto>;
  saveReviewDraft: (repo: string, number: number, expectedVersion: number, body: string, event: ReviewEvent) => Promise<ReviewDraftDto>;
  /** Publishes one previewed comment at its saved commit; the backend validates the payload first. */
  publishReviewDraftComment: (repo: string, number: number, expectedVersion: number, itemId: string) => Promise<void>;
  submitReviewDraft: (repo: string, number: number, expectedVersion: number, body: string, event: ReviewEvent) => Promise<void>;
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
