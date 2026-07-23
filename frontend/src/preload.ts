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
  ActivityItemDto,
  AiLedgerDto,
  AiProviderInfo,
  AiReviewDraftDto,
  AiSettingsDto,
  Bridge,
  ColumnPageDto,
  CodexCliUpdateResultDto,
  ContributionCalendarDto,
  InAppNavState,
  CreateTeamRequest,
  CredentialDto,
  CredentialType,
  Ds4ConfigDto,
  Ds4ConfigResponseDto,
  Ds4InstallRequestDto,
  Ds4InstallStatusDto,
  Ds4MetricsDto,
  Ds4StatusDto,
  Ds4StopResponseDto,
  EmailTagAction,
  EmailTagArchiveEntryDto,
  EmailTagDto,
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
  PrLinksDto,
  PullRequestDto,
  PullRequestMetadataChoicesDto,
  AssembledContextDto,
  TaskTraceDto,
  ConceptRowDto,
  MemoryItemDto,
  RecentEventDto,
  SurfaceVisitInput,
  FootprintsTrailDto,
  SavedViewBodyDto,
  SkillDraftDto,
  SkillDto,
  SkillInput,
  SuggestedReviewerDto,
  NewTaskGroupRequestDto,
  NewTaskRequestDto,
  SyncSettingsDto,
  ThreadDto,
  ThreadSettingsDto,
  ThreadFileDto,
  ThreadGroupDto,
  ThreadGroupMembershipDto,
  ThreadGroupPatchDto,
  ConvIndexPageDto,
  ThreadCheckpointDto,
  ThreadMessageDto,
  ThreadSendResultDto,
  ThreadStreamEvent,
  ThreadTurnEventDto,
  ThreadTurnDto,
  TeamDto,
  TeamSummaryDto,
  UpdateTeamRequest,
  UpsertCredentialRequest,
  UserCommitDto,
  UserProfileDto,
  UserOrgDto,
  UserRepoDto,
  UserStatsDto,
  WatchedRepoDto,
  WorkUnitTaskDto,
  ReviewRosterEntryDto,
  ResolvedWorkModelDto,
  WorkModelDto,
  WorkModelOptionsDto,
  WorkspaceCardDto,
  WorkspaceDto,
  WorkspaceRepoDto,
  WorkspaceApiRequest,
  CredentialTestResult,
  ReviewCommentDto,
  AgentQuestionDto,
  BacklogItemDto,
  StartDevelopmentResponse,
  ThreadSignalDto,
} from './types';

const bridge: Bridge = {
  workspaceApi: <T = unknown>(request: WorkspaceApiRequest): Promise<T> =>
    ipcRenderer.invoke('workspace:api', request),
  savePat: (pat: string) => ipcRenderer.invoke('pat:save', pat),
  hasPat: () => ipcRenderer.invoke('pat:has'),
  clearPat: () => ipcRenderer.invoke('pat:clear'),
  isDevLocalDataResetAvailable: () => ipcRenderer.invoke('dev:local-data-reset-available'),
  requestDevLocalDataReset: () => ipcRenderer.invoke('dev:reset-local-data'),
  fetchHello: () => ipcRenderer.invoke('backend:hello'),
  fetchPrs: (): Promise<PullRequestDto[]> => ipcRenderer.invoke('backend:listPrs'),
  fetchPrsByFilter: (name: string): Promise<PullRequestDto[]> =>
    ipcRenderer.invoke('backend:listPrsByFilter', name),
  lookupPr: (repo: string, number: number): Promise<PullRequestDto> =>
    ipcRenderer.invoke('backend:lookupPr', repo, number),
  getPrLinks: (repo: string, number: number): Promise<PrLinksDto> =>
    ipcRenderer.invoke('backend:prLinks', repo, number),
  cutTaskNow: (
    threadId: string, kind: string, title: string, workingDir: string,
    initialPrompt: string | null,
  ): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('backend:cutTaskNow', threadId, kind, title, workingDir, initialPrompt),
  setOpeningPrompt: (
    threadId: string, taskId: string, text: string, mode: 'append' | 'replace',
  ): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('backend:setOpeningPrompt', threadId, taskId, text, mode),
  listSavedViews: () => ipcRenderer.invoke('backend:listSavedViews'),
  createSavedView: (body: SavedViewBodyDto) =>
    ipcRenderer.invoke('backend:createSavedView', body),
  deleteSavedView: (name: string) =>
    ipcRenderer.invoke('backend:deleteSavedView', name),
  listPendingMemoryItems: (workspaceId: string): Promise<MemoryItemDto[]> =>
    ipcRenderer.invoke('backend:listPendingMemoryItems', workspaceId),
  applyMemoryItem: (workspaceId: string, itemId: number): Promise<MemoryItemDto> =>
    ipcRenderer.invoke('backend:applyMemoryItem', workspaceId, itemId),
  discardMemoryItem: (workspaceId: string, itemId: number): Promise<void> =>
    ipcRenderer.invoke('backend:discardMemoryItem', workspaceId, itemId),
  getThreadContext: (threadId: string): Promise<AssembledContextDto> =>
    ipcRenderer.invoke('backend:getThreadContext', threadId),
  getTaskContext: (threadId: string, taskId: string): Promise<AssembledContextDto> =>
    ipcRenderer.invoke('backend:getTaskContext', threadId, taskId),
  getTaskTrace: (taskId: string): Promise<TaskTraceDto> =>
    ipcRenderer.invoke('backend:getTaskTrace', taskId),
  listConcepts: (query: { kind?: string; query?: string }): Promise<ConceptRowDto[]> =>
    ipcRenderer.invoke('backend:listConcepts', query),
  fetchPullRequestDetail: (repo: string, number: number): Promise<PullRequestDetailDto> =>
    ipcRenderer.invoke('backend:pullRequestDetail', repo, number),
  refreshPullRequestDetail: (repo: string, number: number, maxAgeSeconds?: number): Promise<PullRequestDetailDto> =>
    ipcRenderer.invoke('backend:refreshPullRequestDetail', repo, number, maxAgeSeconds),
  fetchNewComments: (repo: string, number: number, sinceEpochMs: number): Promise<ActivityItemDto[]> =>
    ipcRenderer.invoke('backend:fetchNewComments', repo, number, sinceEpochMs),
  fetchPrCi: (repo: string, number: number) =>
    ipcRenderer.invoke('backend:prCi', repo, number),
  fetchPrConflictPaths: (owner: string, repo: string, prNumber: number, baseRef: string) =>
    ipcRenderer.invoke('backend:prConflictPaths', owner, repo, prNumber, baseRef),
  fetchCheckLog: (repo: string, checkRunId: number) =>
    ipcRenderer.invoke('backend:prCheckLog', repo, checkRunId),
  setPrDraft: (repo: string, number: number, draft: boolean) =>
    ipcRenderer.invoke('backend:setPrDraft', repo, number, draft),
  updatePrTitle: (repo: string, number: number, title: string): Promise<{ number: number; title: string; updatedAt: string }> =>
    ipcRenderer.invoke('backend:updatePrTitle', repo, number, title),
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
  markPrViewedByRef: (repo: string, number: number): Promise<void> =>
    ipcRenderer.invoke('backend:markPrViewedByRef', repo, number),
  markPrHandled: (prId: number, action: HandledAction): Promise<void> =>
    ipcRenderer.invoke('backend:markPrHandled', prId, action),
  reopenPr: (prId: number): Promise<void> => ipcRenderer.invoke('backend:reopenPr', prId),
  fetchPrHistory: (page: number, perPage?: number) =>
    ipcRenderer.invoke('backend:prHistory', page, perPage),
  fetchPrAnalytics: (scope: string, tz?: string) =>
    ipcRenderer.invoke('backend:prAnalytics', scope, tz),
  fetchMyActivity: (scope: string, tz?: string) =>
    ipcRenderer.invoke('backend:myActivity', scope, tz),
  snoozePr: (prId: number, untilIso: string): Promise<void> =>
    ipcRenderer.invoke('backend:snoozePr', prId, untilIso),
  unsnoozePr: (prId: number): Promise<void> => ipcRenderer.invoke('backend:unsnoozePr', prId),
  clearSnoozeWakeReason: (prId: number): Promise<void> =>
    ipcRenderer.invoke('backend:clearSnoozeWakeReason', prId),
  approvePr: (prId: number, repo: string, number: number): Promise<void> =>
    ipcRenderer.invoke('backend:approvePr', prId, repo, number),
  mergePr: (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge'): Promise<{ merged: boolean; message: string; queued: boolean }> =>
    ipcRenderer.invoke('backend:mergePr', prId, repo, number, strategy),
  rerunChecks: (repo: string, number: number): Promise<{ rerunCount: number }> =>
    ipcRenderer.invoke('backend:rerunChecks', repo, number),
  triggerCi: (repo: string, number: number): Promise<{ triggered: boolean; reason: string | null }> =>
    ipcRenderer.invoke('backend:triggerCi', repo, number),
  enableAutoMerge: (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge'): Promise<{ result: string }> =>
    ipcRenderer.invoke('backend:enableAutoMerge', prId, repo, number, strategy),
  disableAutoMerge: (prId: number, repo: string, number: number): Promise<{ result: string }> =>
    ipcRenderer.invoke('backend:disableAutoMerge', prId, repo, number),
  dequeuePr: (prId: number, repo: string, number: number): Promise<{ result: string }> =>
    ipcRenderer.invoke('backend:dequeuePr', prId, repo, number),
  commentPr: (prId: number, repo: string, number: number, body: string, close: boolean): Promise<void> =>
    ipcRenderer.invoke('backend:commentPr', prId, repo, number, body, close),
  replyToReviewThread: (repo: string, number: number, rootCommentId: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:replyToReviewThread', repo, number, rootCommentId, body),
  editIssueComment: (repo: string, commentId: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:editIssueComment', repo, commentId, body),
  editReviewComment: (repo: string, commentId: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:editReviewComment', repo, commentId, body),
  deleteIssueComment: (repo: string, commentId: number): Promise<void> =>
    ipcRenderer.invoke('backend:deleteIssueComment', repo, commentId),
  deleteReviewComment: (repo: string, commentId: number): Promise<void> =>
    ipcRenderer.invoke('backend:deleteReviewComment', repo, commentId),
  addRequestedReviewer: (repo: string, number: number, reviewer: string): Promise<void> =>
    ipcRenderer.invoke('backend:addRequestedReviewer', repo, number, reviewer),
  removeRequestedReviewer: (repo: string, number: number, reviewer: string): Promise<void> =>
    ipcRenderer.invoke('backend:removeRequestedReviewer', repo, number, reviewer),
  getSuggestedReviewers: (repo: string, number: number): Promise<SuggestedReviewerDto[]> =>
    ipcRenderer.invoke('backend:getSuggestedReviewers', repo, number),
  getPullRequestMetadataChoices: (repo: string, number: number): Promise<PullRequestMetadataChoicesDto> =>
    ipcRenderer.invoke('backend:getPrMetadataChoices', repo, number),
  setPullRequestAssignee: (repo: string, number: number, login: string, selected: boolean): Promise<void> =>
    ipcRenderer.invoke('backend:setPrAssignee', repo, number, login, selected),
  setPullRequestLabel: (repo: string, number: number, label: string, selected: boolean): Promise<void> =>
    ipcRenderer.invoke('backend:setPrLabel', repo, number, label, selected),
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
  getUserCommitsOnDate: (login: string, date: string): Promise<UserCommitDto[]> =>
    ipcRenderer.invoke('repos:contributionGraphDay', login, date),
  getRepoPulls: (owner: string, repo: string): Promise<PullRequestDto[]> =>
    ipcRenderer.invoke('repos:pulls', owner, repo),
  getRepoPull: (owner: string, repo: string, number: number): Promise<PullRequestDto> =>
    ipcRenderer.invoke('repos:pull', owner, repo, number),
  searchRepoPulls: (owner: string, repo: string, query: string): Promise<PullRequestDto[]> =>
    ipcRenderer.invoke('repos:searchPulls', owner, repo, query),
  getRepoMeta: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:meta', owner, repo),
  getRepoActivity: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:activity', owner, repo),
  listLocalRepos: () => ipcRenderer.invoke('repos:listLocal'),
  setViewFocus: (owner: string, repo: string, viewFocus: 'fork' | 'upstream') =>
    ipcRenderer.invoke('repos:setViewFocus', owner, repo, viewFocus),
  pickFolder: (options?: { defaultPath?: string; title?: string }) =>
    ipcRenderer.invoke('repos:pickFolder', options),
  defaultClonePath: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:defaultClonePath', owner, repo),
  getManagedClonePlan: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:managedClonePlan', owner, repo),
  cloneRepo: (owner: string, repo: string, writeMode: 'FORK' | 'DIRECT') =>
    ipcRenderer.invoke('repos:cloneRepo', owner, repo, writeMode),
  ensureWorkspaceForRepo: (owner: string, repo: string) =>
    ipcRenderer.invoke('workspaces:ensureForRepo', owner, repo),
  adoptRemoteReviews: (workspaceId: string) =>
    ipcRenderer.invoke('workspaces:adoptRemoteReviews', workspaceId),
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
  reportByteQuayIssue: (title: string, body: string): Promise<IssueDto> =>
    ipcRenderer.invoke('productIssues:report', title, body),
  getIssueDetail: (owner: string, repo: string, number: number): Promise<IssueDetailDto> =>
    ipcRenderer.invoke('repos:issueDetail', owner, repo, number),
  createIssueComment: (owner: string, repo: string, number: number, body: string): Promise<IssueCommentDto> =>
    ipcRenderer.invoke('repos:createIssueComment', owner, repo, number, body),
  setIssueState: (owner: string, repo: string, number: number, state: 'open' | 'closed'): Promise<IssueDetailDto> =>
    ipcRenderer.invoke('repos:setIssueState', owner, repo, number, state),
  addIssueDetailCommentReaction: (owner: string, repo: string, commentId: number, content: string): Promise<{ result: string }> =>
    ipcRenderer.invoke('repos:addIssueCommentReaction', owner, repo, commentId, content),
  setIssueSubscription: (owner: string, repo: string, number: number, subscribed: boolean): Promise<{ result: string }> =>
    ipcRenderer.invoke('repos:setIssueSubscription', owner, repo, number, subscribed),
  getUserRepos: (): Promise<UserRepoDto[]> => ipcRenderer.invoke('repos:userRepos'),
  getUserOrgs: (): Promise<UserOrgDto[]> => ipcRenderer.invoke('repos:userOrgs'),
  searchRepos: (query: string): Promise<UserRepoDto[]> => ipcRenderer.invoke('repos:searchRepos', query),
  searchUsers: (query: string): Promise<GitHubUserMatchDto[]> => ipcRenderer.invoke('repos:searchUsers', query),
  getRecentActivity: (login: string): Promise<RecentEventDto[]> =>
    ipcRenderer.invoke('repos:recentActivity', login),
  getFollowingActivity: (login: string): Promise<RecentEventDto[]> =>
    ipcRenderer.invoke('repos:followingActivity', login),
  recordSurfaceVisit: (visit: SurfaceVisitInput): Promise<void> =>
    ipcRenderer.invoke('footprints:recordVisit', visit),
  getFootprints: (date?: string): Promise<FootprintsTrailDto> =>
    ipcRenderer.invoke('footprints:get', date),
  updateProfile: (name: string, bio: string, location: string): Promise<UserProfileDto> =>
    ipcRenderer.invoke('repos:updateProfile', name, bio, location),
  openInAppBrowser: (url: string): Promise<void> => ipcRenderer.invoke('inapp:open', url),
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
  testCredential: (type: CredentialType, name: string, instanceName: string): Promise<CredentialTestResult> =>
    ipcRenderer.invoke('credentials:test', type, name, instanceName),
  setDefaultCredential: (type: CredentialType, name: string, instanceName: string): Promise<CredentialDto> =>
    ipcRenderer.invoke('credentials:setDefault', type, name, instanceName),
  listAiProviders: (): Promise<AiProviderInfo[]> => ipcRenderer.invoke('ai:providers'),
  getAiSettings: (): Promise<AiSettingsDto> => ipcRenderer.invoke('ai:getSettings'),
  setAiSettings: (provider: string, model: string | null): Promise<AiSettingsDto> =>
    ipcRenderer.invoke('ai:setSettings', provider, model),
  getWorkModelOptions: (): Promise<WorkModelOptionsDto> =>
    ipcRenderer.invoke('workModels:options'),
  refreshWorkModelOptions: (): Promise<WorkModelOptionsDto> =>
    ipcRenderer.invoke('workModels:refresh'),
  getCodexCliVersion: (): Promise<{ version: string }> =>
    ipcRenderer.invoke('codex:version'),
  updateCodexCli: (): Promise<CodexCliUpdateResultDto> =>
    ipcRenderer.invoke('codex:update'),
  setWorkspaceWorkModel: (workspaceId: string, model: WorkModelDto | null): Promise<WorkspaceDto> =>
    ipcRenderer.invoke('workspaces:setWorkModel', { workspaceId, model }),
  getThreadWorkModel: (threadId: string): Promise<ResolvedWorkModelDto> =>
    ipcRenderer.invoke('threads:getWorkModel', { threadId }),
  setThreadWorkModel: (threadId: string, model: WorkModelDto | null): Promise<ResolvedWorkModelDto> =>
    ipcRenderer.invoke('threads:setWorkModel', { threadId, model }),
  getTaskWorkModel: (threadId: string, taskId: string): Promise<ResolvedWorkModelDto> =>
    ipcRenderer.invoke('threads:getTaskWorkModel', { threadId, taskId }),
  setTaskWorkModel: (
    threadId: string,
    taskId: string,
    model: WorkModelDto | null,
  ): Promise<ResolvedWorkModelDto> =>
    ipcRenderer.invoke('threads:setTaskWorkModel', { threadId, taskId, model }),
  getStageWorkModel: (stageId: string): Promise<ResolvedWorkModelDto> =>
    ipcRenderer.invoke('threads:getStageWorkModel', { stageId }),
  setStageWorkModel: (stageId: string, model: WorkModelDto | null): Promise<ResolvedWorkModelDto> =>
    ipcRenderer.invoke('threads:setStageWorkModel', { stageId, model }),
  getDs4Status: (): Promise<Ds4StatusDto> => ipcRenderer.invoke('ds4:status'),
  startDs4: (): Promise<Ds4StatusDto> => ipcRenderer.invoke('ds4:start'),
  stopDs4: (confirm = false): Promise<Ds4StopResponseDto> =>
    ipcRenderer.invoke('ds4:stop', { confirm }),
  restartDs4: (): Promise<Ds4StatusDto> => ipcRenderer.invoke('ds4:restart'),
  getDs4Config: (): Promise<Ds4ConfigDto> => ipcRenderer.invoke('ds4:getConfig'),
  setDs4Config: (config: Ds4ConfigDto, restart = false): Promise<Ds4ConfigResponseDto> =>
    ipcRenderer.invoke('ds4:setConfig', { config, restart }),
  getDs4Metrics: (): Promise<Ds4MetricsDto> => ipcRenderer.invoke('ds4:metrics'),
  installDs4: (req: Ds4InstallRequestDto): Promise<Ds4InstallStatusDto> =>
    ipcRenderer.invoke('ds4:install', req),
  getDs4InstallStatus: (): Promise<Ds4InstallStatusDto> => ipcRenderer.invoke('ds4:installStatus'),
  getDs4Logs: (limit = 200): Promise<string[]> => ipcRenderer.invoke('ds4:logs', { limit }),
  listSkills: (): Promise<SkillDto[]> => ipcRenderer.invoke('skills:list'),
  createSkill: (input: SkillInput): Promise<SkillDto> => ipcRenderer.invoke('skills:create', input),
  updateSkill: (id: number, input: SkillInput): Promise<SkillDto> =>
    ipcRenderer.invoke('skills:update', id, input),
  deleteSkill: (id: number): Promise<void> => ipcRenderer.invoke('skills:delete', id),
  setSkillEnabled: (id: number, enabled: boolean): Promise<SkillDto> =>
    ipcRenderer.invoke('skills:setEnabled', id, enabled),
  draftSkill: (prompt: string, scope: string): Promise<SkillDraftDto> =>
    ipcRenderer.invoke('skills:draft', prompt, scope),
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
  addPullRequestReaction: (
    repo: string,
    number: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ): Promise<void> => ipcRenderer.invoke('pr:addPullRequestReaction', repo, number, content),
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
  onAppNavRequest: (callback: (payload: { action: string; params: Record<string, string> }) => void) => {
    const listener = (_event: unknown, payload: { action: string; params: Record<string, string> }) => callback(payload);
    ipcRenderer.on('app:nav-request', listener);
    return () => ipcRenderer.removeListener('app:nav-request', listener);
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
  windowControl: (action: 'close' | 'minimize' | 'zoom'): Promise<void> =>
    ipcRenderer.invoke('window:control', action),
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
  connectGmailImap: (email: string, appPassword: string): Promise<{ email: string }> =>
    ipcRenderer.invoke('gmailImap:connect', { email, appPassword }),
  // authMode is always "IMAP" now — kept on the wire so the type union
  // matches whatever future modes might be added later.
  listGmailAccounts: (): Promise<Array<{ email: string; authMode: 'IMAP' }>> =>
    ipcRenderer.invoke('gmail:listAccounts'),
  disconnectGmailAccount: (email: string): Promise<void> =>
    ipcRenderer.invoke('gmail:disconnect', email),
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
  readAndArchiveEmailThread: (account: string, id: string): Promise<void> =>
    ipcRenderer.invoke('email:readAndArchiveThread', { account, id }),
  keepEmailThreadInInbox: (account: string, id: string): Promise<void> =>
    ipcRenderer.invoke('email:keepThreadInInbox', { account, id }),
  replyToEmailThread: (account: string, id: string, body: string): Promise<void> =>
    ipcRenderer.invoke('email:replyThread', { account, id, body }),
  muteEmailSender: (account: string, sender: string): Promise<void> =>
    ipcRenderer.invoke('email:muteSender', { account, sender }),
  unmuteEmailSender: (account: string, sender: string): Promise<void> =>
    ipcRenderer.invoke('email:unmuteSender', { account, sender }),
  listMutedEmailSenders: (account: string): Promise<string[]> =>
    ipcRenderer.invoke('email:listMutedSenders', { account }),
  listEmailTags: (account: string): Promise<EmailTagDto[]> =>
    ipcRenderer.invoke('email:listTags', { account }),
  createEmailTag: (
    account: string,
    input: { name: string; subjectContains: string; action: EmailTagAction },
  ): Promise<EmailTagDto> =>
    ipcRenderer.invoke('email:createTag', { account, input }),
  updateEmailTag: (
    id: string,
    input: { name: string; subjectContains: string; action: EmailTagAction },
  ): Promise<EmailTagDto> =>
    ipcRenderer.invoke('email:updateTag', { id, input }),
  deleteEmailTag: (id: string): Promise<void> =>
    ipcRenderer.invoke('email:deleteTag', { id }),
  listArchivedEmailThreads: (account: string): Promise<EmailTagArchiveEntryDto[]> =>
    ipcRenderer.invoke('email:listArchived', { account }),
  listTasks: (
    opts?: string | { groupId?: string; workspaceId?: string },
  ): Promise<ThreadDto[]> =>
    ipcRenderer.invoke('threads:list',
      typeof opts === 'string' ? { groupId: opts } : (opts ?? null)),
  listActiveTaskTurns: (): Promise<ThreadTurnDto[]> =>
    ipcRenderer.invoke('threads:activeTurns'),
  createTask: (request: NewTaskRequestDto): Promise<ThreadDto> =>
    ipcRenderer.invoke('threads:create', request),
  listTaskGroups: (): Promise<ThreadGroupDto[]> => ipcRenderer.invoke('threadGroups:list'),
  listTaskGroupMemberships: (): Promise<ThreadGroupMembershipDto[]> =>
    ipcRenderer.invoke('threadGroups:listMemberships'),
  createTaskGroup: (request: NewTaskGroupRequestDto): Promise<ThreadGroupDto> =>
    ipcRenderer.invoke('threadGroups:create', request),
  updateTaskGroup: (id: string, patch: ThreadGroupPatchDto): Promise<ThreadGroupDto> =>
    ipcRenderer.invoke('threadGroups:update', { id, patch }),
  deleteTaskGroup: (id: string): Promise<void> => ipcRenderer.invoke('threadGroups:delete', id),
  addTaskToGroup: (groupId: string, threadId: string): Promise<void> =>
    ipcRenderer.invoke('threadGroups:addMember', { groupId, threadId }),
  removeTaskFromGroup: (groupId: string, threadId: string): Promise<void> =>
    ipcRenderer.invoke('threadGroups:removeMember', { groupId, threadId }),
  getTask: (id: string): Promise<ThreadDto | null> =>
    ipcRenderer.invoke('threads:get', id),
  getTaskMessages: (id: string): Promise<ThreadMessageDto[]> =>
    ipcRenderer.invoke('threads:messages', id),
  getTaskIndex: (
    id: string,
    opts?: { cursor?: number; limit?: number; direction?: 'initial' | 'before' },
  ): Promise<ConvIndexPageDto> =>
    ipcRenderer.invoke('threads:index', { id, ...opts }),
  listTasksForThread: (threadId: string): Promise<WorkUnitTaskDto[]> =>
    ipcRenderer.invoke('threads:tasks:list', threadId),
  jumpInThread: (threadId: string): Promise<ThreadDto> =>
    ipcRenderer.invoke('threads:jumpIn', threadId),
  listWorkspaces: (): Promise<WorkspaceCardDto[]> =>
    ipcRenderer.invoke('workspaces:list'),
  getWorkspace: (workspaceId: string): Promise<WorkspaceDto | null> =>
    ipcRenderer.invoke('workspaces:get', workspaceId),
  renameWorkspace: (workspaceId: string, name: string): Promise<WorkspaceDto> =>
    ipcRenderer.invoke('workspaces:rename', { workspaceId, name }),
  deleteWorkspace: (workspaceId: string): Promise<void> =>
    ipcRenderer.invoke('workspaces:delete', workspaceId),
  createWorkspace: (
    req: {
      name: string;
      slug?: string;
      isScratch?: boolean;
      promptContext?: string;
      repoFullNames?: string[];
    },
  ): Promise<WorkspaceDto> =>
    ipcRenderer.invoke('workspaces:create', req),
  listWorkspaceRepos: (workspaceId: string): Promise<WorkspaceRepoDto[]> =>
    ipcRenderer.invoke('workspaces:repos:list', workspaceId),
  getWorkspaceMemory: (workspaceId: string): Promise<{ memoryMd: string }> =>
    ipcRenderer.invoke('workspaces:memory:get', workspaceId),
  setWorkspaceMemory: (workspaceId: string, memoryMd: string): Promise<WorkspaceDto> =>
    ipcRenderer.invoke('workspaces:memory:set', { workspaceId, memoryMd }),
  distillWorkspaceMemory: (workspaceId: string) =>
    ipcRenderer.invoke('workspaces:memory:distill', workspaceId),
  getWorkspaceMemoryProposal: (workspaceId: string) =>
    ipcRenderer.invoke('workspaces:memory:proposal:get', workspaceId),
  applyWorkspaceMemoryProposal: (workspaceId: string) =>
    ipcRenderer.invoke('workspaces:memory:proposal:apply', workspaceId),
  discardWorkspaceMemoryProposal: (workspaceId: string): Promise<void> =>
    ipcRenderer.invoke('workspaces:memory:proposal:discard', workspaceId),

  listReviewRoster: (): Promise<ReviewRosterEntryDto[]> =>
    ipcRenderer.invoke('reviews:roster'),
  getReviewPass: (passId: string) =>
    ipcRenderer.invoke('reviews:get', passId),
  getReviewPassByThread: (threadId: string) =>
    ipcRenderer.invoke('reviews:byThread', threadId),
  getReviewPassForPr: (repo: string, number: number) =>
    ipcRenderer.invoke('reviews:forPr', repo, number),
  publishReviewPass: (passId: string, verdict: string, findingIds: string[]) =>
    ipcRenderer.invoke('reviews:publish', { passId, verdict, findingIds }),
  spawnBuildFromReview: (passId: string, opts?: { workspaceId?: string; openingTitle?: string }) =>
    ipcRenderer.invoke('reviews:spawnBuild', { passId, ...(opts ?? {}) }),
  arbitrateReviewFinding: (passId: string, findingId: string, resolution: 'include' | 'drop') =>
    ipcRenderer.invoke('reviews:arbitrate', { passId, findingId, resolution }),
  editReviewFinding: (passId: string, findingId: string, comment: string) =>
    ipcRenderer.invoke('reviews:editFinding', { passId, findingId, comment }),
  dropReviewFinding: (passId: string, findingId: string) =>
    ipcRenderer.invoke('reviews:dropFinding', { passId, findingId }),
  addReviewFinding: (
    passId: string, severity: string, path: string | null, line: number | null, comment: string,
  ) => ipcRenderer.invoke('reviews:addFinding', { passId, severity, path, line, comment }),
  steerReview: (passId: string, targetParticipantId: string, message: string) =>
    ipcRenderer.invoke('reviews:steer', { passId, targetParticipantId, message }),
  raiseReviewBudget: (passId: string, addCostMilli: number, addRounds: number) =>
    ipcRenderer.invoke('reviews:raiseBudget', { passId, addCostMilli, addRounds }),
  resumeReview: (passId: string) =>
    ipcRenderer.invoke('reviews:resume', passId),
  completeReview: (passId: string) =>
    ipcRenderer.invoke('reviews:complete', passId),
  getReviewThreadPrSummaries: (threadIds: string[]) =>
    ipcRenderer.invoke('reviews:prSummaries', threadIds),
  addReviewComment: (
    taskId: string, file: string, line: number, body: string,
    side?: 'LEFT' | 'RIGHT', startLine?: number, startSide?: 'LEFT' | 'RIGHT',
  ): Promise<ReviewCommentDto> =>
    ipcRenderer.invoke('review:add', { taskId, file, line, body, side, startLine, startSide }),
  listReviewComments: (taskId: string): Promise<ReviewCommentDto[]> =>
    ipcRenderer.invoke('review:list', taskId),
  resolveReviewComment: (id: string): Promise<void> =>
    ipcRenderer.invoke('review:resolve', id),
  reopenReviewComment: (id: string): Promise<void> =>
    ipcRenderer.invoke('review:reopen', id),
  submitReview: (
    taskId: string,
    payload?: {
      body?: string;
      verdict?: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
      commentIds?: string[];
    },
  ): Promise<{ submitted: number; turnId: string | null }> =>
    ipcRenderer.invoke('review:submit', taskId, payload),
  listBacklog: (threadId: string): Promise<BacklogItemDto[]> =>
    ipcRenderer.invoke('backlog:list', threadId),
  listWorkspaceBacklog: (
    workspaceId: string,
    filters?: { status?: string; thread?: string; tag?: string; q?: string },
  ): Promise<BacklogItemDto[]> =>
    ipcRenderer.invoke('backlog:listWorkspace', { workspaceId, ...filters }),
  createBacklogItem: (threadId: string, title: string, body: string, tags: string[], priority?: string): Promise<BacklogItemDto> =>
    ipcRenderer.invoke('backlog:create', { threadId, title, body, tags, priority }),
  updateBacklogItem: (
    itemId: string, patch: { title?: string; body?: string; tags?: string[] },
  ): Promise<BacklogItemDto> =>
    ipcRenderer.invoke('backlog:update', { itemId, ...patch }),
  deleteBacklogItem: (itemId: string): Promise<void> =>
    ipcRenderer.invoke('backlog:delete', itemId),
  skipBacklogItem: (itemId: string, reason?: string): Promise<BacklogItemDto> =>
    ipcRenderer.invoke('backlog:skip', { itemId, reason }),
  reviveBacklogItem: (itemId: string): Promise<BacklogItemDto> =>
    ipcRenderer.invoke('backlog:revive', itemId),
  listThreadQuestions: (threadId: string): Promise<AgentQuestionDto[]> =>
    ipcRenderer.invoke('questions:list', threadId),
  answerQuestion: (questionId: string, answerOptionId?: string, answerFreeForm?: string): Promise<AgentQuestionDto> =>
    ipcRenderer.invoke('questions:answer', { questionId, answerOptionId, answerFreeForm }),
  startBacklogDevelopment: (itemId: string): Promise<StartDevelopmentResponse> =>
    ipcRenderer.invoke('backlog:startDevelopment', itemId),
  listThreadSignals: (threadId: string): Promise<ThreadSignalDto[]> =>
    ipcRenderer.invoke('signals:list', threadId),
  markSignalRead: (signalId: string): Promise<void> =>
    ipcRenderer.invoke('signals:markRead', signalId),
  getScheduledReviewSettings: () =>
    ipcRenderer.invoke('reviews:scheduled:get'),
  setScheduledReviewSettings: (enabled: boolean) =>
    ipcRenderer.invoke('reviews:scheduled:set', enabled),
  getWorkspaceBehavior: () =>
    ipcRenderer.invoke('workspace:behavior:get'),
  setWorkspaceBehavior: (settings: {
    archiveIdleAfter: string;
    autoProposeTask: boolean;
    autoPromoteDecisions: boolean;
    newTopicNudge: boolean;
  }) => ipcRenderer.invoke('workspace:behavior:set', settings),
  getWorkspaceInsights: (workspaceId: string, window: string) =>
    ipcRenderer.invoke('workspace:insights:get', { workspaceId, window }),
  getAiLedger: (month: string): Promise<AiLedgerDto> =>
    ipcRenderer.invoke('ai:ledger:get', month),
  getReviewPersona: () =>
    ipcRenderer.invoke('reviews:persona:get'),
  setReviewPersona: (persona: string) =>
    ipcRenderer.invoke('reviews:persona:set', persona),
  setWorkspaceRepoAutoFix: (
    workspaceId: string, owner: string, repo: string, enabled: boolean,
  ): Promise<WorkspaceRepoDto> =>
    ipcRenderer.invoke('workspaces:repos:autoFix', { workspaceId, owner, repo, enabled }),
  shipAndContinue: (
    threadId: string,
    taskId: string,
    opts?: { nextTitle?: string | null; baseMode?: 'MAIN' | 'STACKED' },
  ): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:ship', { threadId, taskId, opts }),
  cancelTask: (threadId: string, taskId: string): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:cancel', { threadId, taskId }),
  pauseTask: (threadId: string, taskId: string): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:pause', { threadId, taskId }),
  resumePausedTask: (threadId: string, taskId: string): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:resume', { threadId, taskId }),
  parkAndStartNext: (
    threadId: string,
    taskId: string,
    opts?: { nextTitle?: string | null; baseMode?: 'MAIN' | 'STACKED' },
  ): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:next', { threadId, taskId, opts }),
  renameTaskUnit: (
    threadId: string,
    taskId: string,
    name: string,
  ): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:rename', { threadId, taskId, name }),
  getTaskAutoApprove: (
    threadId: string,
    taskId: string,
  ): Promise<{ enabled: boolean }> =>
    ipcRenderer.invoke('threads:tasks:autoApprove:get', { threadId, taskId }),
  setTaskAutoApprove: (
    threadId: string,
    taskId: string,
    enabled: boolean,
  ): Promise<{ enabled: boolean }> =>
    ipcRenderer.invoke('threads:tasks:autoApprove:set', { threadId, taskId, enabled }),
  getTaskAutoMerge: (
    threadId: string,
    taskId: string,
  ): Promise<{ enabled: boolean }> =>
    ipcRenderer.invoke('threads:tasks:autoMerge:get', { threadId, taskId }),
  setTaskAutoMerge: (
    threadId: string,
    taskId: string,
    enabled: boolean,
  ): Promise<{ enabled: boolean }> =>
    ipcRenderer.invoke('threads:tasks:autoMerge:set', { threadId, taskId, enabled }),
  getTaskMinApprovals: (
    threadId: string,
    taskId: string,
  ): Promise<{ minApprovals: number }> =>
    ipcRenderer.invoke('threads:tasks:minApprovals:get', { threadId, taskId }),
  setTaskMinApprovals: (
    threadId: string,
    taskId: string,
    minApprovals: number,
  ): Promise<{ minApprovals: number }> =>
    ipcRenderer.invoke('threads:tasks:minApprovals:set', { threadId, taskId, minApprovals }),
  sendTrunkMessage: (
    threadId: string,
    input: string,
    images?: string[],
  ): Promise<ThreadSendResultDto> =>
    ipcRenderer.invoke('threads:trunk:send', { threadId, input, images }),
  /** Resolves an attached image's saved path (from a message's `images`
   *  field) into a renderable data URL. */
  readAttachment: (threadId: string, path: string): Promise<string> =>
    ipcRenderer.invoke('threads:attachment:read', { threadId, path }),
  getThreadSettings: (threadId: string): Promise<ThreadSettingsDto> =>
    ipcRenderer.invoke('threads:settings:get', threadId),
  putThreadSettings: (
    threadId: string,
    body: {
      maxRunningTasks?: number | null;
      softCostUsdMilli?: number | null;
      hardCostUsdMilli?: number | null;
      promptAddendum?: string | null;
    },
  ): Promise<ThreadSettingsDto> =>
    ipcRenderer.invoke('threads:settings:put', { threadId, body }),
  clearThreadSettings: (threadId: string): Promise<void> =>
    ipcRenderer.invoke('threads:settings:clear', threadId),
  getTaskCheckpoints: (id: string): Promise<ThreadCheckpointDto[]> =>
    ipcRenderer.invoke('threads:checkpoints:list', id),
  generateTaskCheckpoint: (id: string): Promise<ThreadCheckpointDto | null> =>
    ipcRenderer.invoke('threads:checkpoints:generate', id),
  getTaskCheckpointStatus: (id: string): Promise<{ lastError: string | null }> =>
    ipcRenderer.invoke('threads:checkpoints:status', id),
  getTaskTurns: (id: string): Promise<ThreadTurnDto[]> =>
    ipcRenderer.invoke('threads:turns', id),
  getTaskTurnEvents: (id: string): Promise<ThreadTurnEventDto[]> =>
    ipcRenderer.invoke('threads:turnEvents', id),
  getTaskFiles: (id: string): Promise<ThreadFileDto[]> =>
    ipcRenderer.invoke('threads:files', id),
  renameTask: (id: string, title: string): Promise<ThreadDto> =>
    ipcRenderer.invoke('threads:rename', { id, title }),
  sendTaskMessage: (id: string, input: string): Promise<ThreadSendResultDto> =>
    ipcRenderer.invoke('threads:send', { id, input }),
  decideTaskPermission: (
    id: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ): Promise<{ status: 'recorded' | 'already_resolved' }> =>
    ipcRenderer.invoke('threads:decide', { id, callId, decision, preApprove }),
  interruptTask: (id: string): Promise<void> => ipcRenderer.invoke('threads:interrupt', id),
  stopTask: (id: string): Promise<void> => ipcRenderer.invoke('threads:stop', id),
  resumeTask: (id: string): Promise<void> => ipcRenderer.invoke('threads:resume', id),
  deleteTask: (id: string): Promise<void> => ipcRenderer.invoke('threads:delete', id),
  getThreadDeleteEligibility: (id: string): Promise<{ deletable: boolean; reason?: string }> =>
    ipcRenderer.invoke('threads:deleteEligibility', id),

  listNotifications: () => ipcRenderer.invoke('notifications:list'),
  listUnreadNotifications: () => ipcRenderer.invoke('notifications:listUnread'),
  listNotificationsForThread: (threadId: string) =>
      ipcRenderer.invoke('notifications:listForThread', threadId),
  markNotificationRead: (id: string) => ipcRenderer.invoke('notifications:markRead', id),
  dismissNotification: (id: string) => ipcRenderer.invoke('notifications:dismiss', id),
  deleteNotification: (id: string): Promise<void> =>
      ipcRenderer.invoke('notifications:delete', id),
  approveNotification: (id: string, editedBody?: string | null, expectedAction?: string | null) =>
      ipcRenderer.invoke('notifications:approve', {
        id,
        editedBody: editedBody ?? null,
        expectedAction: expectedAction ?? null,
      }),
  discardNotification: (id: string, expectedAction?: string | null) =>
      ipcRenderer.invoke('notifications:discard', { id, expectedAction: expectedAction ?? null }),
  setShipDescription: (notificationId: string, prTitle: string, prBody: string) =>
      ipcRenderer.invoke('notifications:shipDescription', { id: notificationId, prTitle, prBody }),

  listTaskWorkingChanges: (id: string) => ipcRenderer.invoke('threads:workingChanges', id),
  getTaskWorkingDiff: (id: string, path: string) => ipcRenderer.invoke('threads:workingDiff', id, path),
  listTaskCommits: (id: string, taskId?: string) => ipcRenderer.invoke('threads:listCommits', id, taskId),
  listTaskCommitFiles: (id: string, sha: string) => ipcRenderer.invoke('threads:commitFiles', id, sha),
  getTaskCommitDiff: (id: string, sha: string, path: string) => ipcRenderer.invoke('threads:commitDiff', id, sha, path),
  getTaskCumulativeDiff: (id: string, taskId?: string) => ipcRenderer.invoke('threads:cumulativeDiff', id, taskId),
  getTaskCommitDiffFiles: (id: string, sha: string, taskId?: string) =>
      ipcRenderer.invoke('threads:commitDiffFiles', id, sha, taskId),
  fetchTaskFileBlob: (id: string, taskId: string, path: string) =>
      ipcRenderer.invoke('threads:fileBlob', id, taskId, path),

  /** Wire the renderer to the per-thread SSE stream brokered by the
   *  main process. The contract: ask main to open (or join) a
   *  subscription for {@code threadId}; register listeners that filter
   *  by {@code threadId}; on cleanup, drop the listeners and ask main
   *  to release the subscription. Main process ref-counts the
   *  underlying SSE connection so multiple renderers/components can
   *  share one. */
  subscribeTaskStream: (
    threadId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => {
    const eventListener = (
      _e: unknown,
      payload: { threadId: string; event: ThreadStreamEvent },
    ) => {
      if (payload.threadId === threadId) onEvent(payload.event);
    };
    const closeListener = (
      _e: unknown,
      payload: { threadId: string; reason: string },
    ) => {
      if (payload.threadId === threadId) onClose?.(payload.reason);
    };
    ipcRenderer.on('threads:stream:event', eventListener);
    ipcRenderer.on('threads:stream:close', closeListener);
    // Fire-and-forget: main handles the actual SSE lifecycle.
    void ipcRenderer.invoke('threads:stream:start', threadId);
    return () => {
      ipcRenderer.removeListener('threads:stream:event', eventListener);
      ipcRenderer.removeListener('threads:stream:close', closeListener);
      void ipcRenderer.invoke('threads:stream:stop', threadId);
    };
  },

  // ── Brain agent ──────────────────────────────────────────────────
  getBrainView: (taskId: string) => ipcRenderer.invoke('brain:getView', taskId),
  updateTaskGuard: (taskId: string, patch: { enabled?: boolean; schedule?: string }) =>
      ipcRenderer.invoke('tasks:updateGuard', { taskId, ...patch }),
  sendBrainMessage: (taskId: string, text: string, images?: string[]) =>
    ipcRenderer.invoke('brain:sendMessage', taskId, text, images),
  getStageDetail: (stageId: string) => ipcRenderer.invoke('stages:getDetail', stageId),
  getTaskRuns: (taskId: string) => ipcRenderer.invoke('runs:forTask', taskId),
  getAgentRun: (runId: string) => ipcRenderer.invoke('runs:get', runId),
  getTaskRounds: (taskId: string) => ipcRenderer.invoke('rounds:forTask', taskId),
  approveRound: (roundId: string) => ipcRenderer.invoke('rounds:approve', roundId),
  steerStage: (stageId: string, text: string, images?: string[]): Promise<{ turnId: string }> =>
    ipcRenderer.invoke('stages:steer', stageId, text, images),
  approvePlan: (planStageId: string) => ipcRenderer.invoke('plans:approve', planStageId),
  replan: (taskId: string) => ipcRenderer.invoke('plans:replan', taskId),
  updateFollowup: (planStageId: string, followupEventId: string, status: 'addressed' | 'dismissed') =>
    ipcRenderer.invoke('plans:updateFollowup', planStageId, followupEventId, status),
  getPrForTask: (taskId: string) => ipcRenderer.invoke('pr:forTask', taskId),
  getPrForRepoPull: (owner: string, repo: string, number: number) =>
    ipcRenderer.invoke('pr:forRepoPull', owner, repo, number),
  getLocalPrBundle: (prId: string) => ipcRenderer.invoke('pr:bundle', prId),
  pushLocalPr: (prId: string) => ipcRenderer.invoke('pr:push', prId),
  mergeLocalPr: (prId: string, method: string) => ipcRenderer.invoke('pr:merge', prId, method),
  dequeueLocalPr: (prId: string) => ipcRenderer.invoke('pr:dequeue', prId),
  deleteLocalPrBranch: (prId: string) => ipcRenderer.invoke('pr:deleteBranch', prId),
  postRemotePrComment: (prId: string, body: string) =>
    ipcRenderer.invoke('pr:postRemoteComment', prId, body),
  publishLocalPrReview: (
    prId: string,
    body?: { verdict: 'APPROVE' | 'COMMENT' | 'REQUEST_CHANGES'; findingIds: string[]; comments: string[]; body?: string | null },
  ) => ipcRenderer.invoke('pr:publishReview', prId, body),
  getAgentReview: (prId: string) => ipcRenderer.invoke('agentReview:get', prId),
  startQuickReview: (prId: string) => ipcRenderer.invoke('quickReview:start', prId),
  getQuickReviewStatus: (prId: string) => ipcRenderer.invoke('quickReview:status', prId),
  getLatestQuickReview: (prId: string): Promise<AiReviewDraftDto | null> =>
    ipcRenderer.invoke('quickReview:latest', prId),
  startAgentReview: (
    prId: string,
    body?: { runner?: 'api' | 'cli'; providerId?: string; workspaceId?: string },
  ) => ipcRenderer.invoke('agentReview:start', prId, body),
  getAgentReviewByThread: (threadId: string) => ipcRenderer.invoke('agentReview:getByThread', threadId),
  getAgentReviewById: (reviewId: string) => ipcRenderer.invoke('agentReview:getById', reviewId),
  listAgentReviewQueue: (scope?: 'all' | 'remote' | 'local') =>
    ipcRenderer.invoke('agentReview:queue', scope ?? 'all'),
  continueAgentReview: (
    reviewId: string,
    body: {
      kind: 'continue' | 're-review' | 'continuation'; findingIds?: string[];
      runner?: 'api' | 'cli'; providerId?: string; seed?: string; costCapCents?: number;
    },
  ) => ipcRenderer.invoke('agentReview:continue', reviewId, body),
  sendAgentReviewRoundMessage: (roundId: string, body: { target: string; text: string }) =>
    ipcRenderer.invoke('agentReview:sendRoundMessage', roundId, body),
  updateAgentReviewRoundBudget: (roundId: string, body: { costCapCents: number }) =>
    ipcRenderer.invoke('agentReview:updateRoundBudget', roundId, body),
  answerAgentReviewFinding: (findingId: string, text: string) =>
    ipcRenderer.invoke('agentReview:answerFinding', findingId, text),
  mutateAgentReviewFinding: (
    findingId: string,
    body: { action: 'dismiss' | 'include' | 'exclude' | 'editDraft' | 'reopen' | 'resolve'; text?: string },
  ) => ipcRenderer.invoke('agentReview:mutateFinding', findingId, body),
  recordAgentReviewFindingOutcome: (
    findingId: string,
    body: {
      userDisposition: string;
      authorResponse: 'fixed' | 'acknowledged' | 'disagreed' | 'ignored';
      epistemicResolution: 'confirmed' | 'refuted' | 'unresolved';
      utilityAssessment?: string;
      styleEditMagnitude?: number;
    },
  ) => ipcRenderer.invoke('agentReview:recordFindingOutcome', findingId, body),
  getAgentReviewRoundLog: (roundId: string) => ipcRenderer.invoke('agentReview:getRoundLog', roundId),
  cancelAgentReviewRound: (roundId: string) => ipcRenderer.invoke('agentReview:cancelRound', roundId),
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
  ) => ipcRenderer.invoke('pr:addComment', prId, body),
  resolveLocalPrComment: (commentId: string) => ipcRenderer.invoke('pr:resolveComment', commentId),
  deleteLocalPrComment: (commentId: string) => ipcRenderer.invoke('pr:deleteComment', commentId),
  dismissLocalPrComment: (commentId: string) => ipcRenderer.invoke('pr:dismissComment', commentId),
  reopenLocalPrComment: (commentId: string) => ipcRenderer.invoke('pr:reopenComment', commentId),
  runLocalPrTests: (prId: string) => ipcRenderer.invoke('pr:runTests', prId),
  fetchDashboardPrs: () => ipcRenderer.invoke('pr:dashboardList'),
  syncDashboardPrs: () => ipcRenderer.invoke('pr:dashboardSync'),
  markDashboardPrViewed: (prId: string) => ipcRenderer.invoke('pr:dashboardMarkViewed', prId),
  markDashboardPrHandled: (prId: string, action: string) =>
    ipcRenderer.invoke('pr:dashboardMarkHandled', prId, action),
  reopenDashboardPr: (prId: string) => ipcRenderer.invoke('pr:dashboardReopen', prId),
  snoozeDashboardPr: (prId: string, untilIso: string) => ipcRenderer.invoke('pr:dashboardSnooze', prId, untilIso),
  unsnoozeDashboardPr: (prId: string) => ipcRenderer.invoke('pr:dashboardUnsnooze', prId),
  clearDashboardPrSnoozeWakeReason: (prId: string) =>
    ipcRenderer.invoke('pr:dashboardClearSnoozeWakeReason', prId),
  approveDashboardPr: (prId: string) => ipcRenderer.invoke('pr:dashboardApprove', prId),
};

contextBridge.exposeInMainWorld('bridge', bridge);
