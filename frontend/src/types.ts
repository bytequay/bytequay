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
  origin: 'AUTHORED' | 'REVIEW_REQUESTED';
  labels: string[];
  labelColors: Record<string, string> | null;
  draft: boolean;
  requestedReviewers: string[];
  ciStatus: CiStatus | null;
  additions: number;
  deletions: number;
  commentCount: number;
  attentionReason: string | null;
  state: string | null;
  mergeable: boolean | null;
  mergeableState: string | null;
  headRef: string | null;
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

export type PullReactionDto = { content: string; count: number };

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
  localRepositoryPath: string;
  codeState: 'PENDING' | 'CLONING' | 'READY' | 'SYNCING' | 'FAILED';
  metadataState: 'PENDING' | 'SYNCING' | 'READY' | 'FAILED';
  lastCodeSyncAt: string | null;
  lastMetadataSyncAt: string | null;
  codeError: string | null;
  metadataError: string | null;
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
  syncedAt: string | null;
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

export type GitRemoteDto = { name: string; url: string };

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
  fetchPrs: (workspaceId?: string) => Promise<PullRequestDto[]>;
  syncPrs: (workspaceId?: string) => Promise<void>;
  fetchPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchRepoUsers: (repo: string, workspaceId?: string) => Promise<RepoUserDto[]>;
  fetchMergeMode: (repo: string, branch: string) => Promise<MergeModeDto>;
  refreshPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchPrDiffFiles: (
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
  postPrReviewComment: (
    repo: string,
    number: number,
    comment: InlineReviewCommentDto,
  ) => Promise<void>;
  commentPr: (repo: string, number: number, body: string) => Promise<void>;
  replyToReviewComment: (repo: string, number: number, commentId: number, body: string) => Promise<void>;
  setReviewThreadResolved: (repo: string, number: number, commentId: number, resolved: boolean) => Promise<void>;
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
  fetchWorkspaceIssues: (workspaceId: string) => Promise<RepositoryIssueSnapshotDto>;
  syncWorkspaceIssues: (workspaceId: string) => Promise<RepositoryIssueSnapshotDto>;
  syncWorkspace: (workspaceId: string) => Promise<WorkspaceDto>;
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
  gitLog: (workspaceId: string, ref: string, limit: number) => Promise<GitCommitDto[]>;
  gitDiff: (workspaceId: string, base: string, head: string) => Promise<GitDiffFileDto[]>;
  gitDiffFileContent: (
    workspaceId: string,
    base: string,
    head: string,
    path: string,
  ) => Promise<DiffFileContentDto>;
  gitStatus: (workspaceId: string) => Promise<GitWorktreeStatusDto>;
  gitFetch: (workspaceId: string, remotes: string[]) => Promise<{ fetched: boolean }>;
  gitCommit: (workspaceId: string, summary: string, description: string) => Promise<{ sha: string }>;
  gitReword: (workspaceId: string, branch: string, sha: string, message: string) => Promise<{ sha: string }>;
  gitSquash: (
    workspaceId: string,
    branch: string,
    baseSha: string,
    tipSha: string,
    message: string,
  ) => Promise<{ sha: string }>;
};
