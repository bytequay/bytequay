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
export type HandledAction =
  | 'APPROVED' | 'MERGED' | 'COMMENTED' | 'CHANGES_REQUESTED'
  | 'DISMISSED' | 'MANUAL';

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
  viewedAt: string | null;
  reviewedAt: string | null;
  handledAction: HandledAction | null;
  requestedReviewers: string[];
  ciStatus: CiStatus | null;
  additions: number;
  deletions: number;
  commentCount: number;
  attentionReason: string | null;
  state: string | null;
  closedAt: string | null;
  mergedAt: string | null;
  mergeable: boolean | null;
  mergeableState: string | null;
  headPushedAt: string | null;
  reviewerVerdicts: Record<string, string> | null;
  snoozedUntil: string | null;
  snoozeWakeReason: string | null;
  headRef: string | null;
};

export type DiffFileDto = {
  filename: string;
  status: string;
  additions: number;
  deletions: number;
  patch: string | null;
};

export type PullRequestDetailDto = {
  repo: string;
  number: number;
  body: string | null;
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
  }>;
  checkRuns: Array<{
    githubId: number | null;
    name: string | null;
    status: string | null;
    conclusion: string | null;
  }>;
  viewerCanWrite: boolean;
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
  hasSponsors: boolean;
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
  language: string | null;
  stars: number;
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

export type Bridge = {
  savePat: (pat: string) => Promise<boolean>;
  hasPat: () => Promise<boolean>;
  fetchPrs: () => Promise<PullRequestDto[]>;
  syncPrs: () => Promise<void>;
  fetchPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  refreshPullRequestDetail: (repo: string, number: number) => Promise<PullRequestDetailDto>;
  fetchPrDiffFiles: (repo: string, number: number) => Promise<DiffFileDto[]>;
  commentPr: (repo: string, number: number, body: string) => Promise<void>;
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

  getGitHubOAuthAuthorizeUrl: () => Promise<{ configured: boolean; url?: string }>;
  getGitHubCliAvailable: () => Promise<{ available: boolean }>;
  importGitHubCliToken: () => Promise<{ login: string }>;
  onGitHubOauthComplete: (
    callback: (payload: { success: boolean; error?: string; login?: string }) => void,
  ) => () => void;

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
};
