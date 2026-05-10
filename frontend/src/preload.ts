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
import { contextBridge, ipcRenderer } from 'electron';
import type {
  AiProviderInfo,
  AiReviewDraftDto,
  AiSettingsDto,
  Bridge,
  ColumnPageDto,
  ContributionCalendarDto,
  InAppNavState,
  CreateTeamRequest,
  CredentialDto,
  CredentialType,
  DailyCardDto,
  EmailThreadDetailDto,
  EmailThreadMetaDto,
  GitHubUserMatchDto,
  MyPrColumnSlug,
  TeamColumnsResponse,
  HandledAction,
  IssueCommentDto,
  IssueDetailDto,
  IssueDto,
  PullRequestDetailDto,
  PullRequestDto,
  RecentEventDto,
  ReviewSkillDto,
  SlackChannelRowDto,
  SlackConnectionDto,
  SuggestedReviewerDto,
  SyncSettingsDto,
  TeamDto,
  TeamSummaryDto,
  UpdateTeamRequest,
  UpsertCredentialRequest,
  UserProfileDto,
  UserOrgDto,
  UserRepoDto,
  UserStatsDto,
  WatchedRepoDto,
} from './types';

const bridge: Bridge = {
  savePat: (pat: string) => ipcRenderer.invoke('pat:save', pat),
  hasPat: () => ipcRenderer.invoke('pat:has'),
  clearPat: () => ipcRenderer.invoke('pat:clear'),
  fetchHello: () => ipcRenderer.invoke('backend:hello'),
  fetchPrs: (): Promise<PullRequestDto[]> => ipcRenderer.invoke('backend:listPrs'),
  fetchPullRequestDetail: (repo: string, number: number): Promise<PullRequestDetailDto> =>
    ipcRenderer.invoke('backend:pullRequestDetail', repo, number),
  refreshPullRequestDetail: (repo: string, number: number): Promise<PullRequestDetailDto> =>
    ipcRenderer.invoke('backend:refreshPullRequestDetail', repo, number),
  fetchPrCi: (repo: string, number: number) =>
    ipcRenderer.invoke('backend:prCi', repo, number),
  fetchCheckLog: (repo: string, checkRunId: number) =>
    ipcRenderer.invoke('backend:prCheckLog', repo, checkRunId),
  setPrDraft: (repo: string, number: number, draft: boolean) =>
    ipcRenderer.invoke('backend:setPrDraft', repo, number, draft),
  fetchPrDiffFiles: (repo: string, number: number) =>
    ipcRenderer.invoke('backend:prDiffFiles', repo, number),
  fetchPrCommits: (repo: string, number: number) =>
    ipcRenderer.invoke('backend:prCommits', repo, number),
  fetchPrCommitDiff: (repo: string, number: number, sha: string) =>
    ipcRenderer.invoke('backend:prCommitDiff', repo, number, sha),
  fetchFileBlob: (repo: string, path: string, sha: string) =>
    ipcRenderer.invoke('backend:fileBlob', repo, path, sha),
  getSyncSettings: (): Promise<SyncSettingsDto> => ipcRenderer.invoke('settings:getSyncSettings'),
  setSyncSettings: (settings: SyncSettingsDto): Promise<SyncSettingsDto> =>
    ipcRenderer.invoke('settings:setSyncSettings', settings),
  triggerSync: (): Promise<void> => ipcRenderer.invoke('settings:triggerSync'),
  markPrViewed: (prId: number): Promise<void> => ipcRenderer.invoke('backend:markPrViewed', prId),
  markPrHandled: (prId: number, action: HandledAction): Promise<void> =>
    ipcRenderer.invoke('backend:markPrHandled', prId, action),
  reopenPr: (prId: number): Promise<void> => ipcRenderer.invoke('backend:reopenPr', prId),
  fetchPrHistory: (page: number, perPage?: number) =>
    ipcRenderer.invoke('backend:prHistory', page, perPage),
  snoozePr: (prId: number, untilIso: string): Promise<void> =>
    ipcRenderer.invoke('backend:snoozePr', prId, untilIso),
  unsnoozePr: (prId: number): Promise<void> => ipcRenderer.invoke('backend:unsnoozePr', prId),
  clearSnoozeWakeReason: (prId: number): Promise<void> =>
    ipcRenderer.invoke('backend:clearSnoozeWakeReason', prId),
  approvePr: (prId: number, repo: string, number: number): Promise<void> =>
    ipcRenderer.invoke('backend:approvePr', prId, repo, number),
  mergePr: (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge'): Promise<{ merged: boolean; message: string }> =>
    ipcRenderer.invoke('backend:mergePr', prId, repo, number, strategy),
  commentPr: (prId: number, repo: string, number: number, body: string, close: boolean): Promise<void> =>
    ipcRenderer.invoke('backend:commentPr', prId, repo, number, body, close),
  replyToReviewThread: (repo: string, number: number, rootCommentId: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:replyToReviewThread', repo, number, rootCommentId, body),
  editIssueComment: (repo: string, commentId: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:editIssueComment', repo, commentId, body),
  editReviewComment: (repo: string, commentId: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:editReviewComment', repo, commentId, body),
  addRequestedReviewer: (repo: string, number: number, reviewer: string): Promise<void> =>
    ipcRenderer.invoke('backend:addRequestedReviewer', repo, number, reviewer),
  removeRequestedReviewer: (repo: string, number: number, reviewer: string): Promise<void> =>
    ipcRenderer.invoke('backend:removeRequestedReviewer', repo, number, reviewer),
  getSuggestedReviewers: (repo: string, number: number): Promise<SuggestedReviewerDto[]> =>
    ipcRenderer.invoke('backend:getSuggestedReviewers', repo, number),
  createInlineReviewComment: (
    repo: string,
    number: number,
    body: string,
    path: string,
    line: number,
    side: 'LEFT' | 'RIGHT',
    commitId: string,
    startLine?: number | null,
    startSide?: 'LEFT' | 'RIGHT' | null,
  ): Promise<void> =>
    ipcRenderer.invoke('backend:createInlineReviewComment', repo, number, body, path, line, side, commitId, startLine ?? null, startSide ?? null),
  updatePrBody: (repo: string, number: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:updatePrBody', repo, number, body),
  getWatchedRepos: (): Promise<WatchedRepoDto[]> => ipcRenderer.invoke('repos:list'),
  addWatchedRepo: (owner: string, repo: string): Promise<WatchedRepoDto> =>
    ipcRenderer.invoke('repos:add', owner, repo),
  removeWatchedRepo: (owner: string, repo: string): Promise<void> =>
    ipcRenderer.invoke('repos:remove', owner, repo),
  getUserProfile: (): Promise<UserProfileDto> => ipcRenderer.invoke('repos:profile'),
  getContributionCalendar: (login: string): Promise<ContributionCalendarDto> =>
    ipcRenderer.invoke('repos:contributionGraph', login),
  getRepoPulls: (owner: string, repo: string): Promise<PullRequestDto[]> =>
    ipcRenderer.invoke('repos:pulls', owner, repo),
  getRepoPull: (owner: string, repo: string, number: number): Promise<PullRequestDto> =>
    ipcRenderer.invoke('repos:pull', owner, repo, number),
  getRepoMeta: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:meta', owner, repo),
  getRepoActivity: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:activity', owner, repo),
  listLocalRepos: () => ipcRenderer.invoke('repos:listLocal'),
  setLocalClonePath: (owner: string, repo: string, path: string | null) =>
    ipcRenderer.invoke('repos:setLocalClonePath', owner, repo, path),
  setViewFocus: (owner: string, repo: string, viewFocus: 'fork' | 'upstream') =>
    ipcRenderer.invoke('repos:setViewFocus', owner, repo, viewFocus),
  pickFolder: (options?: { defaultPath?: string; title?: string }) =>
    ipcRenderer.invoke('repos:pickFolder', options),
  defaultClonePath: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:defaultClonePath', owner, repo),
  cloneRepo: (owner: string, repo: string, destination: string) =>
    ipcRenderer.invoke('repos:cloneRepo', owner, repo, destination),
  locateRepo: (owner: string, repo: string, path: string) =>
    ipcRenderer.invoke('repos:locateRepo', owner, repo, path),
  listLocalBranches: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:listLocalBranches', owner, repo),
  listLocalCommits: (owner: string, repo: string, revision?: string, limit?: number) =>
    ipcRenderer.invoke('repos:listLocalCommits', owner, repo, revision, limit),
  getLocalCommitDetail: (owner: string, repo: string, sha: string) =>
    ipcRenderer.invoke('repos:getLocalCommitDetail', owner, repo, sha),
  listLocalWorkingTreeFiles: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:listLocalWorkingTreeFiles', owner, repo),
  getLocalWorkingTreeDiff: (owner: string, repo: string, path: string) =>
    ipcRenderer.invoke('repos:getLocalWorkingTreeDiff', owner, repo, path),
  listLocalRangeFiles: (owner: string, repo: string, base: string, head: string) =>
    ipcRenderer.invoke('repos:listLocalRangeFiles', owner, repo, base, head),
  getLocalRangeDiff: (owner: string, repo: string, base: string, head: string, path: string) =>
    ipcRenderer.invoke('repos:getLocalRangeDiff', owner, repo, base, head, path),
  listLocalCommitFiles: (owner: string, repo: string, sha: string) =>
    ipcRenderer.invoke('repos:listLocalCommitFiles', owner, repo, sha),
  getLocalCommitDiff: (owner: string, repo: string, sha: string, path: string) =>
    ipcRenderer.invoke('repos:getLocalCommitDiff', owner, repo, sha, path),
  getLocalCommitRangeDiff: (
    owner: string, repo: string, oldestSha: string, newestSha: string, path: string,
  ) =>
    ipcRenderer.invoke('repos:getLocalCommitRangeDiff', owner, repo, oldestSha, newestSha, path),
  getLocalMergeBase: (owner: string, repo: string, branch: string, base?: string) =>
    ipcRenderer.invoke('repos:getLocalMergeBase', owner, repo, branch, base),
  listLocalActivity: (owner: string, repo: string, limit?: number) =>
    ipcRenderer.invoke('repos:listLocalActivity', owner, repo, limit),
  fetchLocalRepo: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:fetchLocal', owner, repo),
  pullLocalRepo: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:pullLocal', owner, repo),
  pushLocalRepo: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:pushLocal', owner, repo),
  pushLocalRepoForce: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:pushLocalForce', owner, repo),
  createLocalBranch: (owner: string, repo: string, name: string, base?: string) =>
    ipcRenderer.invoke('repos:createLocalBranch', owner, repo, name, base),
  switchLocalBranch: (owner: string, repo: string, name: string) =>
    ipcRenderer.invoke('repos:switchLocalBranch', owner, repo, name),
  checkoutRemoteBranch: (owner: string, repo: string, name: string) =>
    ipcRenderer.invoke('repos:checkoutRemoteBranch', owner, repo, name),
  createLocalPullRequest: (
    owner: string, repo: string,
    payload: { title: string; body: string; base: string; draft: boolean },
  ) => ipcRenderer.invoke('repos:createPullRequest', owner, repo, payload),
  draftLocalPullRequest: (owner: string, repo: string, base: string, head: string) =>
    ipcRenderer.invoke('repos:draftPullRequest', owner, repo, base, head),
  deleteLocalBranches: (owner: string, repo: string, names: string[], deleteRemote?: boolean) =>
    ipcRenderer.invoke('repos:deleteLocalBranches', owner, repo, names, deleteRemote),
  revealRepoInFinder: (path: string): Promise<void> =>
    ipcRenderer.invoke('repos:revealInFinder', path),
  openRepoInTerminal: (path: string): Promise<void> =>
    ipcRenderer.invoke('repos:openInTerminal', path),
  openRepoInIDE: (path: string): Promise<void> =>
    ipcRenderer.invoke('repos:openInIDE', path),
  getRepoIssues: (owner: string, repo: string, state?: 'open' | 'closed'): Promise<IssueDto[]> =>
    ipcRenderer.invoke('repos:issues', owner, repo, state),
  getIssueDetail: (owner: string, repo: string, number: number): Promise<IssueDetailDto> =>
    ipcRenderer.invoke('repos:issueDetail', owner, repo, number),
  createIssueComment: (owner: string, repo: string, number: number, body: string): Promise<IssueCommentDto> =>
    ipcRenderer.invoke('repos:createIssueComment', owner, repo, number, body),
  setIssueState: (owner: string, repo: string, number: number, state: 'open' | 'closed'): Promise<IssueDetailDto> =>
    ipcRenderer.invoke('repos:setIssueState', owner, repo, number, state),
  getUserRepos: (): Promise<UserRepoDto[]> => ipcRenderer.invoke('repos:userRepos'),
  getUserOrgs: (): Promise<UserOrgDto[]> => ipcRenderer.invoke('repos:userOrgs'),
  searchRepos: (query: string): Promise<UserRepoDto[]> => ipcRenderer.invoke('repos:searchRepos', query),
  searchUsers: (query: string): Promise<GitHubUserMatchDto[]> => ipcRenderer.invoke('repos:searchUsers', query),
  getRecentActivity: (login: string): Promise<RecentEventDto[]> =>
    ipcRenderer.invoke('repos:recentActivity', login),
  getFollowingActivity: (login: string): Promise<RecentEventDto[]> =>
    ipcRenderer.invoke('repos:followingActivity', login),
  getDailyCard: (): Promise<DailyCardDto> => ipcRenderer.invoke('home:dailyCard'),
  updateProfile: (name: string, bio: string, location: string): Promise<UserProfileDto> =>
    ipcRenderer.invoke('repos:updateProfile', name, bio, location),
  openExternal: (url: string): Promise<void> => ipcRenderer.invoke('shell:openExternal', url),
  getUserStats: (login: string, force?: boolean): Promise<UserStatsDto> =>
    ipcRenderer.invoke('repos:getStats', login, force ?? false),
  listTeams: (): Promise<TeamSummaryDto[]> => ipcRenderer.invoke('teams:list'),
  getTeam: (id: number): Promise<TeamDto> => ipcRenderer.invoke('teams:get', id),
  createTeam: (req: CreateTeamRequest): Promise<TeamDto> => ipcRenderer.invoke('teams:create', req),
  updateTeam: (id: number, req: UpdateTeamRequest): Promise<TeamDto> => ipcRenderer.invoke('teams:update', id, req),
  replaceTeamMembers: (id: number, members: string[]): Promise<TeamDto> =>
    ipcRenderer.invoke('teams:replaceMembers', id, members),
  deleteTeam: (id: number): Promise<void> => ipcRenderer.invoke('teams:delete', id),
  getTeamPulls: (id: number): Promise<PullRequestDto[]> => ipcRenderer.invoke('teams:pulls', id),
  getTeamPullsByColumn: (id: number, perColumn: number, force: boolean): Promise<TeamColumnsResponse> =>
    ipcRenderer.invoke('teams:pullsByColumn', id, perColumn, force),
  getTeamColumnPage: (id: number, column: MyPrColumnSlug, offset: number, limit: number): Promise<ColumnPageDto> =>
    ipcRenderer.invoke('teams:pullsColumnPage', id, column, offset, limit),
  countTeamMergedRecently: (id: number, days: number): Promise<number> =>
    ipcRenderer.invoke('teams:mergedRecently', id, days),
  listCredentials: (type?: CredentialType): Promise<CredentialDto[]> =>
    ipcRenderer.invoke('credentials:list', type ?? null),
  upsertCredential: (req: UpsertCredentialRequest): Promise<CredentialDto> =>
    ipcRenderer.invoke('credentials:upsert', req),
  deleteCredential: (type: CredentialType, name: string, instanceName?: string): Promise<void> =>
    ipcRenderer.invoke('credentials:delete', type, name, instanceName),
  listAiProviders: (): Promise<AiProviderInfo[]> => ipcRenderer.invoke('ai:providers'),
  getAiSettings: (): Promise<AiSettingsDto> => ipcRenderer.invoke('ai:getSettings'),
  setAiSettings: (provider: string, model: string | null): Promise<AiSettingsDto> =>
    ipcRenderer.invoke('ai:setSettings', provider, model),
  listReviewSkills: (): Promise<ReviewSkillDto[]> => ipcRenderer.invoke('skills:list'),
  createReviewSkill: (input): Promise<ReviewSkillDto> => ipcRenderer.invoke('skills:create', input),
  updateReviewSkill: (id: number, input): Promise<ReviewSkillDto> =>
    ipcRenderer.invoke('skills:update', id, input),
  deleteReviewSkill: (id: number): Promise<void> => ipcRenderer.invoke('skills:delete', id),
  runAiReview: (prId: number, repo: string, number: number): Promise<AiReviewDraftDto> =>
    ipcRenderer.invoke('ai:run', prId, repo, number),
  polishCommentText: (text: string): Promise<string> =>
    ipcRenderer.invoke('ai:polishComment', text),
  diagnoseCheckFailure: (checkName: string, log: string): Promise<string> =>
    ipcRenderer.invoke('ai:diagnoseCheck', checkName, log),
  getLatestAiReview: (prId: number): Promise<AiReviewDraftDto | null> =>
    ipcRenderer.invoke('ai:latest', prId),
  deleteAiReview: (draftId: number): Promise<void> =>
    ipcRenderer.invoke('ai:delete', draftId),
  startAiReview: (prId: number, repo: string, number: number): Promise<{ state: string }> =>
    ipcRenderer.invoke('ai:start', prId, repo, number),
  getAiReviewStatus: (repo: string, number: number) =>
    ipcRenderer.invoke('ai:status', repo, number),
  publishAiReview: (
    draftId: number,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
    body?: string | null,
  ): Promise<AiReviewDraftDto> => ipcRenderer.invoke('ai:publish', draftId, event, body ?? null),
  /** Verdict-first publish — finds-or-creates the active draft for the
   *  PR, then submits. Used by the Submit panel so the user can ship a
   *  body-only Approve / Comment without first staging a comment. */
  publishReviewForPr: (payload: {
    prId: number;
    repo: string;
    number: number;
    headSha: string | null;
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
    body: string | null;
  }): Promise<AiReviewDraftDto> => ipcRenderer.invoke('ai:publishForPr', payload),
  updateAiReviewComment: (draftId: number, commentId: number, editedBody: string | null): Promise<AiReviewDraftDto> =>
    ipcRenderer.invoke('ai:editComment', draftId, commentId, editedBody),
  deleteAiReviewComment: (draftId: number, commentId: number): Promise<AiReviewDraftDto> =>
    ipcRenderer.invoke('ai:deleteComment', draftId, commentId),
  setAiReviewCommentDismissed: (draftId: number, commentId: number, dismissed: boolean): Promise<AiReviewDraftDto> =>
    ipcRenderer.invoke('ai:dismissComment', draftId, commentId, dismissed),
  addReviewCommentReaction: (
    repo: string,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ): Promise<void> => ipcRenderer.invoke('pr:addReviewReaction', repo, commentId, content),
  addIssueCommentReaction: (
    repo: string,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ): Promise<void> => ipcRenderer.invoke('pr:addIssueReaction', repo, commentId, content),
  setReviewThreadResolved: (
    repo: string,
    prId: number,
    rootCommentId: number,
    resolved: boolean,
  ): Promise<void> => ipcRenderer.invoke('pr:setThreadResolved', repo, prId, rootCommentId, resolved),
  /** Stage a human-authored inline comment into the active review draft.
   *  Returns the refreshed draft so the tray + inline rail can repaint. */
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
  }): Promise<AiReviewDraftDto> => ipcRenderer.invoke('ai:stageComment', payload),
  writeClipboard: (text: string): Promise<void> => ipcRenderer.invoke('shell:writeClipboard', text),
  mountReview: (repo: string, number: number, bounds): Promise<void> =>
    ipcRenderer.invoke('review:mount', repo, number, bounds),
  setReviewBounds: (bounds): Promise<void> => ipcRenderer.invoke('review:setBounds', bounds),
  unmountReview: (): Promise<void> => ipcRenderer.invoke('review:unmount'),
  resetReviewSignIn: (repo: string, number: number): Promise<void> =>
    ipcRenderer.invoke('review:resetSignIn', repo, number),
  onReviewAuthBlocked: (callback: (payload: { provider: string }) => void) => {
    const listener = (_event: unknown, payload: { provider: string }) => callback(payload);
    ipcRenderer.on('review:auth-blocked', listener);
    return () => ipcRenderer.removeListener('review:auth-blocked', listener);
  },
  onReviewSignInPage: (callback: () => void) => {
    const listener = () => callback();
    ipcRenderer.on('review:sign-in-page', listener);
    return () => ipcRenderer.removeListener('review:sign-in-page', listener);
  },
  reviewGoBack: (): Promise<void> => ipcRenderer.invoke('review:goBack'),
  reviewGoForward: (): Promise<void> => ipcRenderer.invoke('review:goForward'),
  onReviewNavState: (callback: (s: { canGoBack: boolean; canGoForward: boolean }) => void) => {
    const listener = (_event: unknown, s: { canGoBack: boolean; canGoForward: boolean }) => callback(s);
    ipcRenderer.on('review:nav-state', listener);
    return () => ipcRenderer.removeListener('review:nav-state', listener);
  },
  // ─── Generic in-app browser ────────────────────────────────────────
  mountInAppBrowser: (url: string, bounds): Promise<void> =>
    ipcRenderer.invoke('inapp:mount', url, bounds),
  setInAppBrowserBounds: (bounds): Promise<void> =>
    ipcRenderer.invoke('inapp:setBounds', bounds),
  unmountInAppBrowser: (): Promise<void> => ipcRenderer.invoke('inapp:unmount'),
  inAppGoBack: (): Promise<void> => ipcRenderer.invoke('inapp:goBack'),
  inAppGoForward: (): Promise<void> => ipcRenderer.invoke('inapp:goForward'),
  inAppReload: (): Promise<void> => ipcRenderer.invoke('inapp:reload'),
  inAppLoadUrl: (url: string): Promise<void> => ipcRenderer.invoke('inapp:loadUrl', url),
  inAppPopOut: (url: string): Promise<void> => ipcRenderer.invoke('inapp:popOut', url),
  onInAppOpenRequest: (callback: (payload: { url: string }) => void) => {
    const listener = (_event: unknown, payload: { url: string }) => callback(payload);
    ipcRenderer.on('inapp:open-request', listener);
    return () => ipcRenderer.removeListener('inapp:open-request', listener);
  },
  onInAppNavState: (callback: (s: InAppNavState) => void) => {
    const listener = (_event: unknown, s: InAppNavState) => callback(s);
    ipcRenderer.on('inapp:nav-state', listener);
    return () => ipcRenderer.removeListener('inapp:nav-state', listener);
  },
  onFullScreenChange: (callback: (payload: { isFullScreen: boolean }) => void) => {
    const listener = (_event: unknown, payload: { isFullScreen: boolean }) => callback(payload);
    ipcRenderer.on('window:fullscreen-state', listener);
    return () => ipcRenderer.removeListener('window:fullscreen-state', listener);
  },
  getFullScreenState: (): Promise<boolean> => ipcRenderer.invoke('window:get-fullscreen'),
  getSlackAuthorizeUrl: (): Promise<{ configured: boolean; url?: string }> =>
    ipcRenderer.invoke('slack:authorizeUrl'),
  getSlackConnection: (): Promise<SlackConnectionDto> =>
    ipcRenderer.invoke('slack:connection'),
  disconnectSlack: (): Promise<void> => ipcRenderer.invoke('slack:disconnect'),
  onSlackOauthComplete: (callback: (payload: { success: boolean; error?: string }) => void) => {
    const listener = (_event: unknown, payload: { success: boolean; error?: string }) => callback(payload);
    ipcRenderer.on('slack:oauth-complete', listener);
    return () => ipcRenderer.removeListener('slack:oauth-complete', listener);
  },
  listSlackChannels: (): Promise<SlackChannelRowDto[]> =>
    ipcRenderer.invoke('slack:listChannels'),
  replaceFollowedSlackChannels: (channelIds: string[]): Promise<SlackChannelRowDto[]> =>
    ipcRenderer.invoke('slack:replaceFollowedChannels', channelIds),
  getGitHubOAuthAuthorizeUrl: (): Promise<{ configured: boolean; url?: string }> =>
    ipcRenderer.invoke('githubOAuth:authorizeUrl'),
  getGitHubOAuthConnection: (): Promise<{ connected: boolean; login?: string }> =>
    ipcRenderer.invoke('githubOAuth:connection'),
  disconnectGitHubOAuth: (): Promise<void> => ipcRenderer.invoke('githubOAuth:disconnect'),
  onGitHubOauthComplete: (callback: (payload: { success: boolean; error?: string; login?: string }) => void) => {
    const listener = (_event: unknown, payload: { success: boolean; error?: string; login?: string }) => callback(payload);
    ipcRenderer.on('github:oauth-complete', listener);
    return () => ipcRenderer.removeListener('github:oauth-complete', listener);
  },
  connectGmailAccount: (): Promise<{ success: boolean; error?: string; email?: string }> =>
    ipcRenderer.invoke('gmailOAuth:connect'),
  connectGmailImap: (email: string, appPassword: string): Promise<{ email: string }> =>
    ipcRenderer.invoke('gmailImap:connect', { email, appPassword }),
  listGmailAccounts: (): Promise<Array<{ email: string; authMode: 'OAUTH' | 'IMAP' }>> =>
    ipcRenderer.invoke('gmailOAuth:listAccounts'),
  disconnectGmailAccount: (email: string): Promise<void> =>
    ipcRenderer.invoke('gmailOAuth:disconnect', email),
  listEmailThreads: (account: string, pageSize?: number): Promise<EmailThreadMetaDto[]> =>
    ipcRenderer.invoke('email:listThreads', { account, pageSize }),
  refreshEmailThreads: (account: string, pageSize?: number): Promise<EmailThreadMetaDto[]> =>
    ipcRenderer.invoke('email:refreshThreads', { account, pageSize }),
  getEmailThread: (account: string, id: string): Promise<EmailThreadDetailDto> =>
    ipcRenderer.invoke('email:getThread', { account, id }),
  archiveEmailThread: (account: string, id: string): Promise<void> =>
    ipcRenderer.invoke('email:archiveThread', { account, id }),
  markEmailThreadRead: (account: string, id: string): Promise<void> =>
    ipcRenderer.invoke('email:markThreadRead', { account, id }),
  markEmailThreadUnread: (account: string, id: string): Promise<void> =>
    ipcRenderer.invoke('email:markThreadUnread', { account, id }),
};

contextBridge.exposeInMainWorld('bridge', bridge);
