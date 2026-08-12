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
import type { AgentReviewData } from './review/agentReviewTypes';
import type {
  AiLedgerDto,
  Bridge,
  ContributionCalendarDto,
  InAppNavState,
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
  IssueDto,
  PullRequestDetailDto,
  PullRequestDto,
  PullRequestMetadataChoicesDto,
  ConceptRowDto,
  MemoryItemDto,
  RecentEventDto,
  SurfaceVisitInput,
  FootprintsTrailDto,
  SavedViewBodyDto,
  SkillDraftDto,
  SkillDto,
  SkillInput,
  NewTaskRequestDto,
  ThreadDto,
  ThreadGroupDto,
  ConvIndexPageDto,
  ThreadSendResultDto,
  ThreadStreamEvent,
  ThreadTurnDto,
  TrunkTraceEventDto,
  TypedPermissionRequestDto,
  UpsertCredentialRequest,
  UserCommitDto,
  UserProfileDto,
  UserRepoDto,
  WatchedRepoDto,
  WorkUnitTaskDto,
  ResolvedWorkModelDto,
  WorkModelDto,
  WorkModelOptionsDto,
  WorkspaceCardDto,
  WorkspaceDto,
  WorkspaceRepoDto,
  WorkspaceApiRequest,
  CredentialTestResult,
  AgentQuestionDto,
  BacklogItemDto,
  ThreadSignalDto,
} from './types';

type AgentStreamScope = 'thread' | 'stage' | 'sync';

function subscribeAgentStream(
  scope: AgentStreamScope,
  id: string,
  onEvent: (event: ThreadStreamEvent) => void,
  onClose?: (reason: string) => void,
): () => void {
  const target = { scope, id };
  const eventListener = (
    _e: unknown,
    payload: { target: typeof target; event: ThreadStreamEvent },
  ) => {
    if (payload.target.scope === scope && payload.target.id === id) onEvent(payload.event);
  };
  const closeListener = (
    _e: unknown,
    payload: { target: typeof target; reason: string },
  ) => {
    if (payload.target.scope === scope && payload.target.id === id) onClose?.(payload.reason);
  };
  ipcRenderer.on('agent-stream:event', eventListener);
  ipcRenderer.on('agent-stream:close', closeListener);
  void ipcRenderer.invoke('agent-stream:start', target);
  return () => {
    ipcRenderer.removeListener('agent-stream:event', eventListener);
    ipcRenderer.removeListener('agent-stream:close', closeListener);
    void ipcRenderer.invoke('agent-stream:stop', target);
  };
}

const bridge: Bridge = {
  workspaceApi: <T = unknown>(request: WorkspaceApiRequest): Promise<T> =>
    ipcRenderer.invoke('workspace:api', request),
  savePat: (pat: string) => ipcRenderer.invoke('pat:save', pat),
  hasPat: () => ipcRenderer.invoke('pat:has'),
  isDevLocalDataResetAvailable: () => ipcRenderer.invoke('dev:local-data-reset-available'),
  requestDevLocalDataReset: () => ipcRenderer.invoke('dev:reset-local-data'),
  fetchHello: () => ipcRenderer.invoke('backend:hello'),
  fetchPrs: (): Promise<PullRequestDto[]> => ipcRenderer.invoke('backend:listPrs'),
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
  listConcepts: (query: { kind?: string; query?: string }): Promise<ConceptRowDto[]> =>
    ipcRenderer.invoke('backend:listConcepts', query),
  fetchPullRequestDetail: (repo: string, number: number): Promise<PullRequestDetailDto> =>
    ipcRenderer.invoke('backend:pullRequestDetail', repo, number),
  refreshPullRequestDetail: (repo: string, number: number, maxAgeSeconds?: number): Promise<PullRequestDetailDto> =>
    ipcRenderer.invoke('backend:refreshPullRequestDetail', repo, number, maxAgeSeconds),
  fetchCheckFailure: (repo: string, checkRunId: number) =>
    ipcRenderer.invoke('backend:prCheckFailure', repo, checkRunId),
  fetchPrDiffFiles: (repo: string, number: number) =>
    ipcRenderer.invoke('backend:prDiffFiles', repo, number),
  fetchPrCommits: (repo: string, number: number) =>
    ipcRenderer.invoke('backend:prCommits', repo, number),
  fetchPrCommitDiff: (repo: string, number: number, sha: string) =>
    ipcRenderer.invoke('backend:prCommitDiff', repo, number, sha),
  fetchFileBlob: (repo: string, path: string, sha: string) =>
    ipcRenderer.invoke('backend:fileBlob', repo, path, sha),
  commentPr: (prId: number, repo: string, number: number, body: string, close: boolean): Promise<void> =>
    ipcRenderer.invoke('backend:commentPr', prId, repo, number, body, close),
  replyToReviewThread: (repo: string, number: number, rootCommentId: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:replyToReviewThread', repo, number, rootCommentId, body),
  addRequestedReviewer: (repo: string, number: number, reviewer: string): Promise<void> =>
    ipcRenderer.invoke('backend:addRequestedReviewer', repo, number, reviewer),
  removeRequestedReviewer: (repo: string, number: number, reviewer: string): Promise<void> =>
    ipcRenderer.invoke('backend:removeRequestedReviewer', repo, number, reviewer),
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
    startLine?: number | null,
    startSide?: 'LEFT' | 'RIGHT' | null,
  ): Promise<void> =>
    ipcRenderer.invoke('backend:createInlineReviewComment', repo, number, body, path, line, side, startLine ?? null, startSide ?? null),
  applySuggestion: (
    repo: string,
    number: number,
    suggestion: string,
    path: string,
    line: number,
    startLine?: number | null,
  ): Promise<void> =>
    ipcRenderer.invoke('backend:applySuggestion', repo, number, suggestion, path, line, startLine ?? null),
  updatePrBody: (repo: string, number: number, body: string): Promise<void> =>
    ipcRenderer.invoke('backend:updatePrBody', repo, number, body),
  getWatchedRepos: (): Promise<WatchedRepoDto[]> => ipcRenderer.invoke('repos:list'),
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
  listLocalRepos: () => ipcRenderer.invoke('repos:listLocal'),
  pickFolder: (options?: { defaultPath?: string; title?: string }) =>
    ipcRenderer.invoke('repos:pickFolder', options),
  getManagedClonePlan: (owner: string, repo: string) =>
    ipcRenderer.invoke('repos:managedClonePlan', owner, repo),
  getRepoIssues: (owner: string, repo: string, state?: 'open' | 'closed'): Promise<IssueDto[]> =>
    ipcRenderer.invoke('repos:issues', owner, repo, state),
  reportByteQuayIssue: (title: string, body: string): Promise<IssueDto> =>
    ipcRenderer.invoke('productIssues:report', title, body),
  getUserRepos: (): Promise<UserRepoDto[]> => ipcRenderer.invoke('repos:userRepos'),
  searchRepos: (query: string): Promise<UserRepoDto[]> => ipcRenderer.invoke('repos:searchRepos', query),
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
  getWorkModelOptions: (): Promise<WorkModelOptionsDto> =>
    ipcRenderer.invoke('workModels:options'),
  refreshWorkModelOptions: (): Promise<WorkModelOptionsDto> =>
    ipcRenderer.invoke('workModels:refresh'),
  getAppVersion: (): Promise<{ version: string }> =>
    ipcRenderer.invoke('app:version'),
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
  listSkills: (): Promise<SkillDto[]> => ipcRenderer.invoke('skills:list'),
  createSkill: (input: SkillInput): Promise<SkillDto> => ipcRenderer.invoke('skills:create', input),
  updateSkill: (id: number, input: SkillInput): Promise<SkillDto> =>
    ipcRenderer.invoke('skills:update', id, input),
  deleteSkill: (id: number): Promise<void> => ipcRenderer.invoke('skills:delete', id),
  setSkillEnabled: (id: number, enabled: boolean): Promise<SkillDto> =>
    ipcRenderer.invoke('skills:setEnabled', id, enabled),
  draftSkill: (prompt: string, scope: string): Promise<SkillDraftDto> =>
    ipcRenderer.invoke('skills:draft', prompt, scope),
  polishCommentText: (text: string): Promise<string> =>
    ipcRenderer.invoke('ai:polishComment', text),
  addPullRequestReaction: (
    repo: string,
    number: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ): Promise<void> => ipcRenderer.invoke('pr:addPullRequestReaction', repo, number, content),
  addReviewCommentReaction: (
    repo: string,
    number: number,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ): Promise<void> => ipcRenderer.invoke('pr:addReviewReaction', repo, number, commentId, content),
  addIssueCommentReaction: (
    repo: string,
    number: number,
    commentId: number,
    content: '+1' | '-1' | 'laugh' | 'confused' | 'heart' | 'hooray' | 'rocket' | 'eyes',
  ): Promise<void> => ipcRenderer.invoke('pr:addIssueReaction', repo, number, commentId, content),
  setReviewThreadResolved: (
    repo: string,
    number: number,
    prId: number,
    rootCommentId: number,
    resolved: boolean,
  ): Promise<void> => ipcRenderer.invoke('pr:setThreadResolved', repo, number, prId, rootCommentId, resolved),
  // ─── Generic in-app browser ────────────────────────────────────────
  mountInAppBrowser: (url: string, bounds): Promise<void> =>
    ipcRenderer.invoke('inapp:mount', url, bounds),
  setInAppBrowserBounds: (bounds): Promise<void> =>
    ipcRenderer.invoke('inapp:setBounds', bounds),
  unmountInAppBrowser: (): Promise<void> => ipcRenderer.invoke('inapp:unmount'),
  inAppGoBack: (): Promise<void> => ipcRenderer.invoke('inapp:goBack'),
  inAppGoForward: (): Promise<void> => ipcRenderer.invoke('inapp:goForward'),
  inAppReload: (): Promise<void> => ipcRenderer.invoke('inapp:reload'),
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
  getGitHubCliAvailable: (): Promise<{ available: boolean }> =>
    ipcRenderer.invoke('githubCli:available'),
  importGitHubCliToken: (): Promise<{ login: string }> =>
    ipcRenderer.invoke('githubCli:import'),
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
  getTask: (id: string): Promise<ThreadDto | null> =>
    ipcRenderer.invoke('threads:get', id),
  getTaskIndex: (
    id: string,
    opts?: { cursor?: number; limit?: number; direction?: 'initial' | 'before' },
  ): Promise<ConvIndexPageDto> =>
    ipcRenderer.invoke('threads:index', { id, ...opts }),
  getTrunkTraceEvents: (
    id: string, requestMessageIds: string[],
  ): Promise<TrunkTraceEventDto[]> =>
    ipcRenderer.invoke('threads:traceEvents', { id, requestMessageIds }),
  listTasksForThread: (threadId: string): Promise<WorkUnitTaskDto[]> =>
    ipcRenderer.invoke('threads:tasks:list', threadId),
  listWorkspaces: (): Promise<WorkspaceCardDto[]> =>
    ipcRenderer.invoke('workspaces:list'),
  deleteWorkspace: (workspaceId: string): Promise<void> =>
    ipcRenderer.invoke('workspaces:delete', workspaceId),
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
  getReviewPass: (passId: string) =>
    ipcRenderer.invoke('reviews:get', passId),
  getReviewPassByThread: (threadId: string) =>
    ipcRenderer.invoke('reviews:byThread', threadId),
  getReviewPassPublication: (passId: string) =>
    ipcRenderer.invoke('reviews:publication:get', passId),
  publishReviewPass: (passId: string, verdict: string, findingIds: string[]) =>
    ipcRenderer.invoke('reviews:publish', { passId, verdict, findingIds }),
  spawnBuildFromReview: (passId: string, opts?: {
    workspaceId?: string;
    openingTitle?: string;
    /** Omitted deliberately means every currently eligible finding. */
    selectedFindingIds?: string[];
  }) =>
    ipcRenderer.invoke('reviews:spawnBuild', { passId, ...(opts ?? {}) }),
  getReviewBuildCommentProposal: (passId: string) =>
    ipcRenderer.invoke('reviews:buildComments:get', passId),
  approveReviewBuildComments: (passId: string, commandId: string) =>
    ipcRenderer.invoke('reviews:buildComments:approve', { passId, commandId }),
  discardReviewBuildComments: (passId: string, commandId: string) =>
    ipcRenderer.invoke('reviews:buildComments:discard', { passId, commandId }),
  arbitrateReviewFinding: (passId: string, findingId: string, resolution: 'include' | 'drop') =>
    ipcRenderer.invoke('reviews:arbitrate', { passId, findingId, resolution }),
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
  listThreadSignals: (threadId: string): Promise<ThreadSignalDto[]> =>
    ipcRenderer.invoke('signals:list', threadId),
  markSignalRead: (signalId: string): Promise<void> =>
    ipcRenderer.invoke('signals:markRead', signalId),
  getWorkspaceInsights: (workspaceId: string, window: string) =>
    ipcRenderer.invoke('workspace:insights:get', { workspaceId, window }),
  getAiLedger: (month: string): Promise<AiLedgerDto> =>
    ipcRenderer.invoke('ai:ledger:get', month),
  setWorkspaceRepoAutoFix: (
    workspaceId: string, owner: string, repo: string, enabled: boolean,
  ): Promise<WorkspaceRepoDto> =>
    ipcRenderer.invoke('workspaces:repos:autoFix', { workspaceId, owner, repo, enabled }),
  cancelTask: (threadId: string, taskId: string): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:cancel', { threadId, taskId }),
  pauseTask: (threadId: string, taskId: string): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:pause', { threadId, taskId }),
  resumePausedTask: (threadId: string, taskId: string): Promise<WorkUnitTaskDto> =>
    ipcRenderer.invoke('threads:tasks:resume', { threadId, taskId }),
  recoverV2Plan: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ): Promise<unknown> => ipcRenderer.invoke(
    'development-flow:plan:recover', { taskId, failedTurnId, command }),
  recoverV2DevelopmentBrainReview: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ): Promise<unknown> => ipcRenderer.invoke(
    'development-flow:development-brain:recover', {
      taskId, failedTurnId, command,
    }),
  recoverV2BranchSyncBrainReview: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ): Promise<unknown> => ipcRenderer.invoke(
    'development-flow:branch-sync-brain:recover', {
      taskId, failedTurnId, command,
    }),
  recoverV2Stage: (
    taskId: string,
    failedTurnId: string,
    command: { blockerId: string; commandId: string; reason: string },
  ): Promise<unknown> => ipcRenderer.invoke(
    'development-flow:local-stage:recover', {
      taskId, failedTurnId, command,
    }),
  recoverV2BranchSync: (
    taskId: string,
    episodeId: string,
    command: {
      blockerId: string;
      commandId: string;
      action: 'MANUAL_TAKEOVER' | 'STOP_AUTOMATION';
      reason: string;
    },
  ): Promise<unknown> => ipcRenderer.invoke(
    'development-flow:branch-sync:recover', { taskId, episodeId, command }),
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
  ): Promise<unknown> => ipcRenderer.invoke(
    'development-flow:worktree:recover', {
      taskId, quarantineId, command,
    }),
  approveV2LocalPublishBaseSync: (
    taskId: string,
    blockerId: string,
  ) => ipcRenderer.invoke(
    'development-flow:local-publish-base-sync:approve', { taskId, blockerId }),
  extendV2LocalPublishBaseSync: (
    taskId: string,
    episodeId: string,
    blockerId: string,
    command: { commandId: string; reason: string },
  ) => ipcRenderer.invoke(
    'development-flow:local-publish-base-sync:extend', {
      taskId, episodeId, blockerId, command,
    }),
  recoverV2Cleanup: (
    taskId: string,
    stepId: string,
    command: {
      commandId: string;
      action: 'RETRY' | 'WAIVE_OPTIONAL';
      reason: string;
    },
  ): Promise<unknown> => ipcRenderer.invoke(
    'development-flow:cleanup:recover', { taskId, stepId, command }),
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
  getTaskTurns: (id: string): Promise<ThreadTurnDto[]> =>
    ipcRenderer.invoke('threads:turns', id),
  getTypedPermissions: (id: string): Promise<TypedPermissionRequestDto[]> =>
    ipcRenderer.invoke('threads:permissions', id),
  decideTaskPermission: (
    id: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
    expectedRevision?: number,
  ): Promise<{ status: 'recorded' | 'already_resolved' }> =>
    ipcRenderer.invoke('threads:decide', {
      id, callId, decision, preApprove, expectedRevision,
    }),
  interruptTask: (id: string, turnId?: string): Promise<void> =>
    ipcRenderer.invoke('threads:interrupt', id, turnId),
  interruptStage: (id: string): Promise<void> => ipcRenderer.invoke('stages:interrupt', id),

  listNotifications: () => ipcRenderer.invoke('notifications:list'),
  listUnreadNotifications: () => ipcRenderer.invoke('notifications:listUnread'),
  listNotificationsForThread: (threadId: string) =>
      ipcRenderer.invoke('notifications:listForThread', threadId),
  markNotificationRead: (id: string) => ipcRenderer.invoke('notifications:markRead', id),
  dismissNotification: (id: string) => ipcRenderer.invoke('notifications:dismiss', id),
  approveNotification: (id: string, editedBody?: string | null, expectedAction?: string | null) =>
      ipcRenderer.invoke('notifications:approve', {
        id,
        editedBody: editedBody ?? null,
        expectedAction: expectedAction ?? null,
      }),
  discardNotification: (id: string, expectedAction?: string | null) =>
      ipcRenderer.invoke('notifications:discard', { id, expectedAction: expectedAction ?? null }),
  listTaskCommits: (id: string, taskId?: string) => ipcRenderer.invoke('threads:listCommits', id, taskId),
  getTaskCumulativeDiff: (id: string, taskId?: string) => ipcRenderer.invoke('threads:cumulativeDiff', id, taskId),
  fetchTaskFileBlob: (id: string, taskId: string, path: string) =>
      ipcRenderer.invoke('threads:fileBlob', id, taskId, path),

  subscribeTaskStream: (
    threadId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => subscribeAgentStream('thread', threadId, onEvent, onClose),
  subscribeStageStream: (
    stageId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => subscribeAgentStream('stage', stageId, onEvent, onClose),
  subscribeSyncRunStream: (
    jobId: string,
    onEvent: (event: ThreadStreamEvent) => void,
    onClose?: (reason: string) => void,
  ) => subscribeAgentStream('sync', jobId, onEvent, onClose),
  // ── Brain agent ──────────────────────────────────────────────────
  getBrainView: (taskId: string) => ipcRenderer.invoke('brain:getView', taskId),
  sendBrainMessage: (taskId: string, text: string, images?: string[]) =>
    ipcRenderer.invoke('brain:sendMessage', taskId, text, images),
  getStageDetail: (stageId: string) => ipcRenderer.invoke('stages:getDetail', stageId),
  getTaskRuns: (taskId: string) => ipcRenderer.invoke('runs:forTask', taskId),
  getAgentRun: (runId: string) => ipcRenderer.invoke('runs:get', runId),
  getTaskRounds: (taskId: string) => ipcRenderer.invoke('rounds:forTask', taskId),
  approveRound: (roundId: string) => ipcRenderer.invoke('rounds:approve', roundId),
  steerStage: (
    stageId: string,
    text: string,
    images?: string[],
    mode: 'APPEND' | 'CANCEL_AND_REPLACE' = 'APPEND',
    expectedPredecessorStageTurnId?: string,
  ): Promise<{ turnId: string }> =>
    ipcRenderer.invoke(
      'stages:steer', stageId, text, images, mode,
      expectedPredecessorStageTurnId,
    ),
  getV2ReadinessAssistance: (taskId: string, stageId: string) =>
    ipcRenderer.invoke('stages:getReadinessAssistance', taskId, stageId),
  authorizeV2ReadinessAssistance: (
    taskId: string,
    stageId: string,
    body: import('./types').ReadinessAssistanceRequest,
  ) => ipcRenderer.invoke(
    'stages:authorizeReadinessAssistance', taskId, stageId, body,
  ),
  approvePlan: (planStageId: string) => ipcRenderer.invoke('plans:approve', planStageId),
  getPrForTask: (taskId: string) => ipcRenderer.invoke('pr:forTask', taskId),
  getPrForRepoPull: (owner: string, repo: string, number: number) =>
    ipcRenderer.invoke('pr:forRepoPull', owner, repo, number),
  getLocalPrBundle: (prId: string) => ipcRenderer.invoke('pr:bundle', prId),
  updateLocalPrDetails: (prId: string, body: { title?: string; description?: string }) =>
      ipcRenderer.invoke('pr:updateDetails', prId, body),
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
  getLocalPrReviewPublication: (prId: string) =>
    ipcRenderer.invoke('pr:reviewPublication:get', prId),
  getAgentReview: (prId: string) => ipcRenderer.invoke('agentReview:get', prId),
  startQuickReview: (prId: string) => ipcRenderer.invoke('quickReview:start', prId),
  getQuickReviewStatus: (prId: string) => ipcRenderer.invoke('quickReview:status', prId),
  getLatestQuickReview: (prId: string): Promise<AgentReviewData | null> =>
    ipcRenderer.invoke('quickReview:latest', prId),
  startAgentReview: (
    prId: string,
    body?: { runner?: 'api' | 'cli'; providerId?: string; workspaceId?: string },
  ) => ipcRenderer.invoke('agentReview:start', prId, body),
  getAgentReviewByThread: (threadId: string) => ipcRenderer.invoke('agentReview:getByThread', threadId),
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
  markDashboardPrHandled: (prId: string, action: string) =>
    ipcRenderer.invoke('pr:dashboardMarkHandled', prId, action),
  approveDashboardPr: (prId: string) => ipcRenderer.invoke('pr:dashboardApprove', prId),
};

contextBridge.exposeInMainWorld('bridge', bridge);
