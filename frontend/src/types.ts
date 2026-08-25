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

export type CiStatus = 'PASSING' | 'FAILING' | 'PENDING' | 'NONE';
export type PullRequestDto = {
  id: number;
  repo: string;
  number: number;
  title: string;
  author: string | null;
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
  attentionReason: string | null;
  state: string | null;
  mergeable: boolean | null;
  mergeableState: string | null;
  headPushedAt: string | null;
  headRef: string | null;
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

export type InlineReviewCommentDto = {
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

export type MergeModeDto = { mergeQueue: boolean };

export type PullRequestDetailDto = {
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

export type PullReactionDto = { content: string; count: number };

export type PullReactionTarget = 'pull_request' | 'issue_comment' | 'review_comment';
export type PullReactionContent = '+1' | '-1' | 'laugh' | 'hooray' | 'confused' | 'heart' | 'rocket' | 'eyes';

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

export type WatchedRepoDto = {
  id: string;
  owner: string;
  repo: string;
  displayOrder: number;
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
  cloneUrl: string;
  upstream: RepoRefDto | null;
  viewerCanPush: boolean;
  existingWorkspaceId: string | null;
};

export type WatchRepositoryPreviewDto = {
  selectedRepositoryId: number;
  selectedRepository: RepoRefDto;
  visibility: string;
  topology: 'DIRECT' | 'FORK';
  upstreamRepositoryId: number | null;
  upstreamRepository: RepoRefDto | null;
  monitoredRepositoryId: number;
  monitoredRepository: RepoRefDto;
  defaultBranch: string;
  developmentBase: string;
  pushRemote: string;
  pushWritable: boolean;
  pullRequestBase: RepoRefDto;
  fingerprint: string;
  existingWorkspaceId: string | null;
};

export type WatchRepositoryCommand = {
  selectedRepositoryId: number;
  previewFingerprint: string;
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

export type RepositoryIssueSnapshotDto = {
  repositoryId: number;
  repository: { owner: string; repo: string };
  issues: Array<{
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
  languages: Record<string, number>;
  ownerAvatarUrl: string | null;
  parentOwner: string | null;
  parentName: string | null;
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
  company: string | null;
  email: string | null;
};

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

/** One conflicted path a simulated pick would hit. */
export type CherryPickConflictFile = {
  path: string;
  /** CONTENT | ADD_ADD | MODIFY_DELETE | BINARY | SUBMODULE | UNKNOWN */
  type: string;
  module?: string;
  /** Real `[startLine, endLine]` marker ranges; empty when Git gave none. */
  sections: Array<[number, number]>;
};

export type CherryPickPreviewCommit = {
  position: number;
  sourceSha: string;
  sourceParentSha: string;
  title: string;
  author: string;
  committedAt: string;
  changedPaths: string[];
  originallyEmpty: boolean;
  /** Backed by ancestry or an exact `-x` line only, never a title or a guess. */
  alreadyOnTarget: boolean;
  targetMatchKind?: string;
  targetMatchCommitSha?: string;
  /** Advisory: such a commit is still selectable and never skipped for you. */
  patchEquivalent: boolean;
  /** CLEAN | CONFLICT | NOT_ANALYZED */
  conflictStatus: string;
  /** AFTER_CLEAN_PREFIX | AT_FROZEN_TARGET | ESTIMATE */
  conflictBasis: string;
  conflictFiles: CherryPickConflictFile[];
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

export type AgentProviderStatus = {
  laneId: string;
  /** AVAILABLE | DEGRADED | UNAVAILABLE */
  health: string;
  version?: string | null;
  diagnostic: string;
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
  workspaceId?: string | null;
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
};

export type WorkspacePushResult = {
  remote: string;
  head: string;
  sha: string;
};

export type WorkspacePullRequestResult = {
  providerId: number;
  number: number;
  url: string;
  baseRepository: RepoRefDto;
  baseBranch: string;
  head: string;
};

export type WorkspaceDeliveryStatus = {
  pushWritable: boolean;
  disabledReason?: string | null;
  baseRepository: RepoRefDto;
  baseBranch: string;
  head: string;
  push?: WorkspacePushResult | null;
  pullRequest?: WorkspacePullRequestResult | null;
};

export type CherryPickExecutionRequest = {
  workspaceId: string;
  previewId: string;
  expectedSourceBaseSha: string;
  expectedSourceTipSha: string;
  expectedTargetSha: string;
  userSkippedSourceShas: string[];
  laneId: string;
  idempotencyKey: string;
  pullRequestTitle: string;
  pullRequestBody: string;
};

export type GitRemoteDto = {
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
  authoredAt: string;
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

export type CommitHistorySnapshotDto = {
  commits: GitCommitDto[];
  refresh: CommitRefreshStatusDto;
};

export type WorkspaceRemoteDto = {
  name: string;
  url: string;
  role: 'ORIGIN' | 'UPSTREAM' | 'CUSTOM';
};

export type WorkspaceAgentModelDto = {
  configId: string;
  label: string;
  model: string;
  effort?: string | null;
};

export type WorkspaceTokenDto = {
  maskedToken: string;
  login: string;
};

export type WorkspaceSettingsDto = {
  remotes: WorkspaceRemoteDto[];
  agentModelOverride?: WorkspaceAgentModelDto | null;
  mainModel: WorkspaceAgentModelDto;
  githubToken?: WorkspaceTokenDto | null;
};

export type ModelOptionDto = { name: string; description: string };

export type ModelProviderDto = {
  id: string;
  label: string;
  /** AVAILABLE | DEGRADED | UNAVAILABLE */
  health: string;
  version?: string | null;
  diagnostic: string;
  models: ModelOptionDto[];
  efforts: string[];
  selectedModel: string;
  selectedEffort: string;
  main: boolean;
};

export type ModelApiKeyDto = {
  id: string;
  alias: string;
  provider: string;
  maskedKey: string;
  createdAt: string;
  models: string[];
  selectedModel: string;
  main: boolean;
};

export type ModelSettingsDto = {
  mainId: string;
  providers: ModelProviderDto[];
  apiKeys: ModelApiKeyDto[];
  keyProviders: Array<{ name: string; models: string[] }>;
};

export type QuietHoursDto = {
  enabled: boolean;
  startMinute: number;
  endMinute: number;
};

export type GitHubAccountDto = {
  configured: boolean;
  maskedToken?: string | null;
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

export type Bridge = {
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  fetchPrs: (workspaceId: string | undefined, page: number, pageSize: number, search?: string) => Promise<PullRequestPageDto>;
  syncPrs: (workspaceId?: string) => Promise<void>;
  fetchPullRequestDetail: (prId: number) => Promise<PullRequestDetailDto>;
  fetchPullRequestDetailByRef: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchRepoUsers: (repo: string, workspaceId?: string) => Promise<RepoUserDto[]>;
  fetchRepoLabels: (repo: string) => Promise<RepoLabelDto[]>;
  updatePrMetadata: (repo: string, number: number, assignees: string[], labels: string[]) => Promise<void>;
  updatePrReviewer: (repo: string, number: number, login: string, requested: boolean) => Promise<void>;
  fetchMergeMode: (repo: string, branch: string) => Promise<MergeModeDto>;
  refreshPullRequestDetail: (prId: number) => Promise<PullRequestDetailRefreshDto>;
  refreshPullRequestDetailByRef: (repo: string, number: number) => Promise<PullRequestDetailRefreshDto>;
  refreshAllPullRequestDetail: (prId: number) => Promise<PullRequestDetailDto>;
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
  fetchPrDiffFileContent: (
    repo: string,
    number: number,
    path: string,
    baseSha?: string,
    headSha?: string,
  ) => Promise<DiffFileContentDto>;
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
  ) => Promise<void>;
  commentPr: (repo: string, number: number, body: string) => Promise<void>;
  addPrReaction: (
    repo: string,
    number: number,
    target: PullReactionTarget,
    commentId: number | null,
    content: PullReactionContent,
  ) => Promise<void>;
  closePr: (repo: string, number: number) => Promise<void>;
  replyToReviewComment: (repo: string, number: number, commentId: number, body: string) => Promise<void>;
  setReviewThreadResolved: (repo: string, number: number, commentId: number, resolved: boolean) => Promise<void>;
  rerunFailedPrChecks: (repo: string, number: number) => Promise<void>;
  approvePr: (prId: number, repo: string, number: number) => Promise<void>;
  submitReview: (
    prId: number,
    repo: string,
    number: number,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
    body: string,
    comments?: InlineReviewCommentDto[],
  ) => Promise<void>;
  mergePr: (prId: number, repo: string, number: number, strategy: string) => Promise<void>;

  getWatchedRepos: () => Promise<WatchedRepoDto[]>;
  removeWatchedRepo: (owner: string, repo: string) => Promise<void>;
  searchRepositories: (query: string, limit: number) => Promise<RepositorySearchResultDto[]>;
  previewWatch: (selectedRepositoryId: number) => Promise<WatchRepositoryPreviewDto>;
  watchRepository: (command: WatchRepositoryCommand) => Promise<WorkspaceDto>;
  listWorkspaces: () => Promise<WorkspaceDto[]>;
  getWorkspace: (workspaceId: string) => Promise<{ summary: WorkspaceDto }>;
  getWorkspaceMetadata: (workspaceId: string) => Promise<RepositoryMetadataDto>;
  fetchWorkspaceIssues: (workspaceId: string, page: number, pageSize: number) => Promise<RepositoryIssueSnapshotDto>;
  fetchWorkspaceIssue: (workspaceId: string, issueNumber: number) => Promise<RepositoryIssueSnapshotDto['issues'][number]>;
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
  getUserProfile: () => Promise<UserProfileDto>;
  getContributionCalendar: (login: string) => Promise<ContributionCalendarDto>;
  getUserCommitsOnDate: (login: string, date: string) => Promise<UserCommitDto[]>;
  getRepoPull: (owner: string, repo: string, number: number) => Promise<PullRequestDto>;
  getRepoMeta: (owner: string, repo: string) => Promise<RepoMetaDto>;
  getRecentActivity: (login: string) => Promise<RecentEventDto[]>;
  getFollowingActivity: (login: string) => Promise<RecentEventDto[]>;

  getGitHubCliAvailable: () => Promise<{ available: boolean }>;
  importGitHubCliToken: () => Promise<{ login: string }>;

  openInAppBrowser: (url: string) => Promise<void>;
  openExternal: (url: string) => Promise<void>;
  mountInAppBrowser: (
    url: string,
    bounds: { x: number; y: number; width: number; height: number },
  ) => Promise<void>;
  setInAppBrowserBounds: (
    bounds: { x: number; y: number; width: number; height: number },
  ) => Promise<void>;
  unmountInAppBrowser: () => Promise<void>;
  inAppGoBack: () => Promise<void>;
  inAppGoForward: () => Promise<void>;
  inAppReload: () => Promise<void>;
  inAppPopOut: (url: string) => Promise<void>;
  onInAppNavState: (callback: (state: InAppNavState) => void) => () => void;
  onInAppOpenRequest: (callback: (payload: { url: string }) => void) => () => void;

  controlWindow: (action: 'close' | 'minimize' | 'zoom') => Promise<void>;
  getFullScreen: () => Promise<boolean>;
  onFullScreenChange: (callback: (fullScreen: boolean) => void) => () => void;
  getAppVersion: () => Promise<{ version: string }>;

  previewCherryPickRange: (request: CherryPickPreviewRequest) => Promise<CherryPickPreview>;
  getCherryPickPreview: (previewId: string) => Promise<CherryPickPreview>;
  getAgentProviderStatuses: () => Promise<AgentProviderStatus[]>;
  startCherryPickExecution: (request: CherryPickExecutionRequest) => Promise<CherryPickExecution>;
  getCherryPickExecution: (executionId: string) => Promise<CherryPickExecution>;
  listCherryPickExecutions: (limit: number) => Promise<CherryPickExecution[]>;
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

  getModelSettings: () => Promise<ModelSettingsDto>;
  updateModelSelection: (configId: string, model: string, effort: string | null) => Promise<ModelSettingsDto>;
  setMainModel: (configId: string) => Promise<ModelSettingsDto>;
  addModelApiKey: (provider: string, alias: string, key: string) => Promise<ModelSettingsDto>;
  removeModelApiKey: (alias: string) => Promise<ModelSettingsDto>;
  getQuietHours: () => Promise<QuietHoursDto>;
  saveQuietHours: (quietHours: QuietHoursDto) => Promise<QuietHoursDto>;
  getGitHubAccount: () => Promise<GitHubAccountDto>;
  /** Copies the stored token to the clipboard in the main process; the renderer never sees it. */
  copyGitHubToken: () => Promise<boolean>;
  deleteGitHubToken: () => Promise<void>;

  getWorkspaceSettings: (workspaceId: string) => Promise<WorkspaceSettingsDto>;
  renameWorkspace: (workspaceId: string, name: string) => Promise<WorkspaceDto>;
  addWorkspaceRemote: (workspaceId: string, name: string, url: string) => Promise<WorkspaceRemoteDto[]>;
  removeWorkspaceRemote: (workspaceId: string, name: string) => Promise<WorkspaceRemoteDto[]>;
  setWorkspaceAgentModel: (
    workspaceId: string,
    configId: string,
    model: string,
    effort: string | null,
  ) => Promise<WorkspaceAgentModelDto>;
  clearWorkspaceAgentModel: (workspaceId: string) => Promise<void>;
  saveWorkspaceGitHubToken: (workspaceId: string, value: string) => Promise<{ login: string }>;
  deleteWorkspaceGitHubToken: (workspaceId: string) => Promise<void>;
  copyWorkspaceGitHubToken: (workspaceId: string) => Promise<boolean>;
};

export type WorkspaceIssueMetadataDto = {
  assignees: string[];
  labels: string[];
};
