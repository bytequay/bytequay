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
  id: number;
  owner: string;
  repo: string;
  displayOrder: number;
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

export type UserRepoDto = {
  owner: string;
  name: string;
  fullName: string;
  description: string | null;
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
  repository: string;
  sourceRemote: string;
  sourceBaseRef: string;
  sourceTipRef: string;
  targetRemote: string;
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
  repository: string;
  workBranch: string;
  localTargetBranch: string;
  /** RUNNING | PAUSED | FINISHED */
  status: string;
  currentStep?: string | null;
  pauseReason?: string | null;
  finishReason?: string | null;
  summary?: string | null;
  initialTargetSha: string;
  currentTargetSha: string;
  currentPosition?: number | null;
  items: CherryPickExecutionItem[];
};

export type CherryPickExecutionRequest = {
  repository: string;
  worktreeParent: string;
  previewId: string;
  expectedSourceBaseSha: string;
  expectedSourceTipSha: string;
  expectedTargetSha: string;
  userSkippedSourceShas: string[];
  laneId: string;
  idempotencyKey: string;
};

export type Bridge = {
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  fetchPrs: () => Promise<PullRequestDto[]>;
  syncPrs: () => Promise<void>;
  fetchPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchRepoUsers: (repo: string) => Promise<RepoUserDto[]>;
  fetchMergeMode: (repo: string, branch: string) => Promise<MergeModeDto>;
  refreshPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchPrDiffFiles: (repo: string, number: number) => Promise<DiffFileDto[]>;
  commentPr: (repo: string, number: number, body: string) => Promise<void>;
  replyToReviewComment: (repo: string, number: number, commentId: number, body: string) => Promise<void>;
  approvePr: (prId: number, repo: string, number: number) => Promise<void>;
  mergePr: (prId: number, repo: string, number: number, strategy: string) => Promise<void>;

  getWatchedRepos: () => Promise<WatchedRepoDto[]>;
  addWatchedRepo: (owner: string, repo: string) => Promise<WatchedRepoDto>;
  removeWatchedRepo: (owner: string, repo: string) => Promise<void>;
  getUserProfile: () => Promise<UserProfileDto>;
  getContributionCalendar: (login: string) => Promise<ContributionCalendarDto>;
  getUserCommitsOnDate: (login: string, date: string) => Promise<UserCommitDto[]>;
  getRepoPull: (owner: string, repo: string, number: number) => Promise<PullRequestDto>;
  getRepoMeta: (owner: string, repo: string) => Promise<RepoMetaDto>;
  getUserRepos: () => Promise<UserRepoDto[]>;
  searchRepos: (query: string) => Promise<UserRepoDto[]>;
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
  sendCherryPickMessage: (executionId: string, message: string) => Promise<CherryPickExecution>;
  cancelCherryPickExecution: (executionId: string, repository: string) => Promise<CherryPickExecution>;
  chooseDirectory: (title: string) => Promise<string | null>;
};
