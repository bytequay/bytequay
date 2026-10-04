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
import { createAgentStreamReceiver } from './agents/sse';
import type { AgentStreamMessage, Bridge, FindResult, InAppBrowserState, PullRequestDto } from './types';

ipcRenderer.on('github:account-changed', () => window.dispatchEvent(new Event('bytequay:github-account-changed')));

/**
 * One stream subscription over a channel family.
 *
 * Main pushes every stream on one channel per family, so each listener keeps only what it asked for.
 */
function streamSubscriber(prefix: 'agent' | 'review'): Bridge['subscribeAgentTask'] {
  return async (id, after, listener, statusOnly) => {
    const receiver = createAgentStreamReceiver(listener);
    const receive = (_event: unknown, message: AgentStreamMessage): void => receiver.receive(message);
    ipcRenderer.on(`${prefix}:stream:message`, receive);
    let subscriptionId: string | null = null;
    try {
      subscriptionId = await ipcRenderer.invoke(`${prefix}:stream:subscribe`, id, after, statusOnly);
      receiver.activate(subscriptionId);
    }
    catch (failure) {
      ipcRenderer.removeListener(`${prefix}:stream:message`, receive);
      if (subscriptionId !== null) void ipcRenderer.invoke(`${prefix}:stream:unsubscribe`, subscriptionId);
      throw failure;
    }
    return () => {
      ipcRenderer.removeListener(`${prefix}:stream:message`, receive);
      void ipcRenderer.invoke(`${prefix}:stream:unsubscribe`, subscriptionId);
    };
  };
}

const bridge: Bridge = {
  getJiraConfig: () => ipcRenderer.invoke('jira:config'),
  listJiraConnections: () => ipcRenderer.invoke('jira:connections'),
  connectJira: () => ipcRenderer.invoke('jira:connect'),
  connectJiraToken: request => ipcRenderer.invoke('jira:token:connect', request),
  replaceJiraToken: (id, request) => ipcRenderer.invoke('jira:token:replace', id, request),
  reconnectJira: id => ipcRenderer.invoke('jira:reconnect', id),
  refreshJiraConnection: id => ipcRenderer.invoke('jira:connection:refresh', id),
  disconnectJira: id => ipcRenderer.invoke('jira:disconnect', id),
  listJiraBoards: (connectionId, cloudId, refresh) => ipcRenderer.invoke('jira:boards', connectionId, cloudId, refresh),
  listJiraSprints: (connectionId, cloudId, boardId, refresh) => ipcRenderer.invoke('jira:sprints', connectionId, cloudId, boardId, refresh),
  listJiraIssues: (query, refresh) => ipcRenderer.invoke('jira:issues', query, refresh),
  getJiraIssue: (location, refresh, automatic) => ipcRenderer.invoke('jira:issue', location, refresh, automatic),
  refreshJiraIssueByKey: (connectionId, cloudId, key) => ipcRenderer.invoke('jira:key:refresh', connectionId, cloudId, key),
  updateJiraIssue: (location, fields, expectedUpdatedAt) => ipcRenderer.invoke('jira:issue:update', location, fields, expectedUpdatedAt),
  transitionJiraIssue: (location, transitionId, fields, expectedUpdatedAt) => ipcRenderer.invoke('jira:issue:transition', location, transitionId, fields, expectedUpdatedAt),
  moveJiraIssue: (location, sprintId, boardId, expectedUpdatedAt) => ipcRenderer.invoke('jira:issue:sprint', location, sprintId, boardId, expectedUpdatedAt),
  commentJiraIssue: (location, body, operationId, parentId) => ipcRenderer.invoke('jira:issue:comment', location, body, operationId, parentId),
  editJiraComment: (location, commentId, body, expectedUpdated) => ipcRenderer.invoke('jira:comment:edit', location, commentId, body, expectedUpdated),
  setJiraGoal: (location, isGoal) => ipcRenderer.invoke('jira:issue:goal', location, isGoal),
  assignJiraWorkspace: (location, workspaceId) => ipcRenderer.invoke('jira:issue:workspace', location, workspaceId),
  listJiraAttention: () => ipcRenderer.invoke('jira:attention'),
  listJiraChildTypes: location => ipcRenderer.invoke('jira:issue:child-types', location),
  createJiraChild: (location, issueTypeId, summary) => ipcRenderer.invoke('jira:issue:child', location, issueTypeId, summary),
  listJiraLinkTypes: (connectionId, cloudId) => ipcRenderer.invoke('jira:link-types', connectionId, cloudId),
  linkJiraIssue: (location, typeId, outward, key) => ipcRenderer.invoke('jira:issue:link', location, typeId, outward, key),
  savePat: pat => ipcRenderer.invoke('pat:save', pat),
  hasPat: () => ipcRenderer.invoke('pat:has'),
  fetchPrs: (workspaceId, page, pageSize) => ipcRenderer.invoke('prs:list', workspaceId, page, pageSize),
  fetchAttentionPrs: () => ipcRenderer.invoke('prs:attention'),
  findStoredPullRequest: (repo, number) => ipcRenderer.invoke('prs:stored', repo, number),
  findStoredPullRequestDescription: (repo, number) => ipcRenderer.invoke('prs:stored:description', repo, number),
  setPrMonitored: (prId, monitored) => ipcRenderer.invoke('prs:monitor', prId, monitored),
  setPrAttentionHandled: handled => ipcRenderer.invoke('prs:attention:handled', handled),
  setPrAttentionPinned: keys => ipcRenderer.invoke('prs:attention:pinned', keys),
  onPrAttentionUpdated: callback => {
    const listener = (_event: Electron.IpcRendererEvent, prs: PullRequestDto[]) => callback(prs);
    ipcRenderer.on('prs:attention-updated', listener);
    return () => ipcRenderer.removeListener('prs:attention-updated', listener);
  },
  onPrNotificationOpen: callback => {
    let active = true;
    const consume = () => {
      void ipcRenderer.invoke('prs:notification:pending')
        .then((reference: { repo: string; number: number } | null) => {
          if (active && reference !== null) callback(reference);
        })
        .catch(() => {});
    };
    ipcRenderer.on('prs:notification-open', consume);
    consume();
    return () => {
      active = false;
      ipcRenderer.removeListener('prs:notification-open', consume);
    };
  },
  syncPrs: workspaceId => ipcRenderer.invoke('prs:sync', workspaceId),
  onPullRequestsSynced: callback => {
    const listener = () => callback();
    ipcRenderer.on('pull-requests:synced', listener);
    return () => ipcRenderer.removeListener('pull-requests:synced', listener);
  },
  fetchPullRequestDetail: prId => ipcRenderer.invoke('prs:detail', prId),
  fetchPullRequestDetailByRef: (repo, number) => ipcRenderer.invoke('prs:detail:by-ref', repo, number),
  fetchRepoUsers: (repo, workspaceId) => ipcRenderer.invoke('prs:users', repo, workspaceId),
  fetchRepoLabels: repo => ipcRenderer.invoke('prs:labels', repo),
  updatePrMetadata: (repo, number, assignees, labels) =>
    ipcRenderer.invoke('prs:metadata:update', repo, number, assignees, labels),
  updatePrReviewer: (repo, number, login, requested) =>
    ipcRenderer.invoke('prs:reviewer:update', repo, number, login, requested),
  fetchMergeMode: (repo, branch) => ipcRenderer.invoke('prs:merge-mode', repo, branch),
  refreshPullRequestDetailByRef: (repo, number, checksOnly) =>
    ipcRenderer.invoke('prs:detail:refresh:by-ref', repo, number, checksOnly),
  refreshAllPullRequestDetailByRef: (repo, number) =>
    ipcRenderer.invoke('prs:detail:refresh-all:by-ref', repo, number),
  fetchPrDiffFiles: (repo, number, baseSha, headSha) =>
    ipcRenderer.invoke('prs:diff', repo, number, baseSha, headSha),
  refreshPrDiffFiles: (repo, number, baseSha, headSha) =>
    ipcRenderer.invoke('prs:diff:refresh', repo, number, baseSha, headSha),
  fetchPrDiffCommits: (repo, number) => ipcRenderer.invoke('prs:diff-commits', repo, number),
  refreshPrDiffFileContent: (repo, number, path, baseSha, headSha) =>
    ipcRenderer.invoke('prs:diff-content:refresh', repo, number, path, baseSha, headSha),
  postPrReviewComment: (repo, number, comment) =>
    ipcRenderer.invoke('prs:review-comment:create', repo, number, comment),
  updatePrDescription: (repo, number, body) =>
    ipcRenderer.invoke('prs:description:update', repo, number, body),
  updatePrTitle: (repo, number, title) => ipcRenderer.invoke('prs:title:update', repo, number, title),
  commentPr: (repo, number, body) => ipcRenderer.invoke('prs:comment', repo, number, body),
  setPrReaction: (repo, number, target, commentId, content, viewerHasReacted) =>
    ipcRenderer.invoke('prs:reaction:set', repo, number, target, commentId, content, viewerHasReacted),
  editPrComment: (repo, number, target, commentId, body) =>
    ipcRenderer.invoke('prs:comment:edit', repo, number, target, commentId, body),
  closePr: (repo, number) => ipcRenderer.invoke('prs:close', repo, number),
  markPrReadyForReview: (repo, number) => ipcRenderer.invoke('prs:ready-for-review', repo, number),
  replyToReviewComment: (repo, number, commentId, body) =>
    ipcRenderer.invoke('prs:review-comment:reply', repo, number, commentId, body),
  setReviewThreadResolved: (repo, number, commentId, resolved) =>
    ipcRenderer.invoke('prs:review-thread:resolution', repo, number, commentId, resolved),
  getPrCheckLog: (repo, number, jobId) => ipcRenderer.invoke('prs:checks:log', repo, number, jobId),
  rerunFailedPrChecks: (repo, number) => ipcRenderer.invoke('prs:checks:rerun-failed', repo, number),
  submitReview: (prId, repo, number, event, body, comments) =>
    ipcRenderer.invoke('prs:review', prId, repo, number, event, body, comments),
  betterWords: (text, repository, workspaceId) =>
    ipcRenderer.invoke('agent:better-words', text, repository, workspaceId),
  mergePr: (prId, repo, number, strategy) =>
    ipcRenderer.invoke('prs:merge', prId, repo, number, strategy),
  removePrFromMergeQueue: (repo, number) => ipcRenderer.invoke('prs:merge-queue:remove', repo, number),

  searchRepositories: (query, limit) => ipcRenderer.invoke('workspaces:search', query, limit),
  previewWatch: selectedRepositoryId => ipcRenderer.invoke('workspaces:preview', selectedRepositoryId),
  watchRepository: command => ipcRenderer.invoke('workspaces:watch', command),
  listWorkspaces: () => ipcRenderer.invoke('workspaces:list'),
  listRetiredWorkspaces: () => ipcRenderer.invoke('workspaces:list-retired'),
  getWorkspace: workspaceId => ipcRenderer.invoke('workspaces:get', workspaceId),
  getWorkspaceMetadata: workspaceId => ipcRenderer.invoke('workspaces:metadata', workspaceId),
  fetchWorkspaceIssues: (workspaceId, page, pageSize) => ipcRenderer.invoke('workspaces:issues', workspaceId, page, pageSize),
  fetchWorkspaceIssue: (workspaceId, issueNumber) => ipcRenderer.invoke('workspaces:issue', workspaceId, issueNumber),
  fetchWorkspaceIssueAttention: workspaceId => ipcRenderer.invoke('workspaces:issues:attention', workspaceId),
  setWorkspaceIssueGoal: (workspaceId, issueNumber, isGoal) => ipcRenderer.invoke('workspaces:issues:goal', workspaceId, issueNumber, isGoal),
  listWorkItems: workspaceId => ipcRenderer.invoke('work-items:list', workspaceId),
  createWorkItem: (workspaceId, request) => ipcRenderer.invoke('work-items:create', workspaceId, request),
  decideWorkItem: (workspaceId, itemId, command) => ipcRenderer.invoke('work-items:decide', workspaceId, itemId, command),
  linkWorkItem: (workspaceId, itemId, revision, kind, key) => ipcRenderer.invoke('work-items:link', workspaceId, itemId, revision, kind, key),
  listGoals: workspaceId => ipcRenderer.invoke('goals:list', workspaceId),
  createGoal: (workspaceId, title, description) => ipcRenderer.invoke('goals:create', workspaceId, title, description),
  updateGoal: (workspaceId, goalId, patch) => ipcRenderer.invoke('goals:update', workspaceId, goalId, patch),
  deleteGoal: (workspaceId, goalId) => ipcRenderer.invoke('goals:delete', workspaceId, goalId),
  addGoalItem: (workspaceId, goalId, kind, key) => ipcRenderer.invoke('goals:items:add', workspaceId, goalId, kind, key),
  removeGoalItem: (workspaceId, goalId, kind, key) => ipcRenderer.invoke('goals:items:remove', workspaceId, goalId, kind, key),
  searchGoalCandidates: (workspaceId, query) => ipcRenderer.invoke('goals:candidates', workspaceId, query),
  startGoal: (workspaceId, operationId, message) => ipcRenderer.invoke('goals:start', workspaceId, operationId, message),
  listGoalExchanges: (workspaceId, goalId) => ipcRenderer.invoke('goals:exchanges', workspaceId, goalId),
  askGoal: (workspaceId, goalId, operationId, message, lessonId) => ipcRenderer.invoke('goals:ask', workspaceId, goalId, operationId, message, lessonId ?? null),
  retryGoalExchange: (workspaceId, goalId, exchangeId) => ipcRenderer.invoke('goals:retry', workspaceId, goalId, exchangeId),
  stopGoalExchange: (workspaceId, goalId, exchangeId) => ipcRenderer.invoke('goals:stop', workspaceId, goalId, exchangeId),
  reassessGoal: (workspaceId, goalId, operationId) => ipcRenderer.invoke('goals:reassess', workspaceId, goalId, operationId),
  getGoalModel: (workspaceId, goalId) => ipcRenderer.invoke('goals:model', workspaceId, goalId),
  setGoalModel: (workspaceId, goalId, configId, model, effort) => ipcRenderer.invoke('goals:model:set', workspaceId, goalId, configId, model, effort),
  clearGoalModel: (workspaceId, goalId) => ipcRenderer.invoke('goals:model:clear', workspaceId, goalId),
  acceptGoalDefinition: (workspaceId, goalId, exchangeId, title) => ipcRenderer.invoke('goals:accept', workspaceId, goalId, exchangeId, title),
  acceptGoalCourse: (workspaceId, goalId, exchangeId) => ipcRenderer.invoke('goals:course', workspaceId, goalId, exchangeId),
  startGoalRecommendation: (workspaceId, goalId, recommendationId) => ipcRenderer.invoke('goals:recommendations:start', workspaceId, goalId, recommendationId),
  dismissGoalRecommendation: (workspaceId, goalId, recommendationId) => ipcRenderer.invoke('goals:recommendations:dismiss', workspaceId, goalId, recommendationId),
  listGoalKnowledge: (workspaceId, goalId) => ipcRenderer.invoke('goals:knowledge', workspaceId, goalId),
  listGoalGaps: (workspaceId, goalId) => ipcRenderer.invoke('goals:gaps', workspaceId, goalId),
  addGoalGap: (workspaceId, goalId, candidateId) => ipcRenderer.invoke('goals:gaps:add', workspaceId, goalId, candidateId),
  setGoalTopic: (workspaceId, goalId, topic, level) => ipcRenderer.invoke('goals:topics', workspaceId, goalId, topic, level),
  openGoalLesson: (workspaceId, goalId, lessonId) => ipcRenderer.invoke('goals:lessons:open', workspaceId, goalId, lessonId),
  readGoalLesson: (workspaceId, goalId, lessonId) => ipcRenderer.invoke('goals:lessons:read', workspaceId, goalId, lessonId),
  finishGoalLesson: (workspaceId, goalId, lessonId, operationId) => ipcRenderer.invoke('goals:lessons:done', workspaceId, goalId, lessonId, operationId),
  refreshWorkspaceIssues: workspaceId => ipcRenderer.invoke('workspaces:issues:refresh', workspaceId),
  fetchWorkspaceIssueDetail: (workspaceId, issueNumber) =>
    ipcRenderer.invoke('workspaces:issues:detail', workspaceId, issueNumber),
  refreshWorkspaceIssueDetail: (workspaceId, issueNumber) =>
    ipcRenderer.invoke('workspaces:issues:detail:refresh', workspaceId, issueNumber),
  commentWorkspaceIssue: (workspaceId, issueNumber, body) =>
    ipcRenderer.invoke('workspaces:issues:comment', workspaceId, issueNumber, body),
  closeWorkspaceIssue: (workspaceId, issueNumber) =>
    ipcRenderer.invoke('workspaces:issues:close', workspaceId, issueNumber),
  updateWorkspaceIssueType: (workspaceId, issueNumber, type) =>
    ipcRenderer.invoke('workspaces:issues:type:update', workspaceId, issueNumber, type),
  updateWorkspaceIssueMetadata: (workspaceId, issueNumber, assignees, labels) =>
    ipcRenderer.invoke('workspaces:issues:metadata:update', workspaceId, issueNumber, assignees, labels),
  syncWorkspace: workspaceId => ipcRenderer.invoke('workspaces:sync', workspaceId),
  setWorkspaceUpstream: (workspaceId, upstreamRepositoryId) =>
    ipcRenderer.invoke('workspaces:upstream', workspaceId, upstreamRepositoryId),
  removeWorkspace: workspaceId => ipcRenderer.invoke('workspaces:remove', workspaceId),
  deleteWorkspace: (workspaceId, confirmed, repositoryName) =>
    ipcRenderer.invoke('workspaces:delete', workspaceId, confirmed, repositoryName),
  getUserProfile: () => ipcRenderer.invoke('repos:profile'),
  getGitHubUserProfile: login => ipcRenderer.invoke('repos:user-profile', login),
  getContributionCalendar: login => ipcRenderer.invoke('repos:contribution', login),
  getUserCommitsOnDate: (login, date) => ipcRenderer.invoke('repos:contribution-day', login, date),
  fetchGitHubIssuePreview: (owner, repo, number) => ipcRenderer.invoke('repos:issue-preview', owner, repo, number),
  fetchGitHubCodeSnippet: request => ipcRenderer.invoke('repos:code-snippet', request),
  getRecentActivity: login => ipcRenderer.invoke('repos:activity', login),
  getFollowingActivity: login => ipcRenderer.invoke('repos:following', login),
  getTeams: () => ipcRenderer.invoke('teams:list'),
  fetchTeamContributors: () => ipcRenderer.invoke('teams:contributors'),
  createTeam: input => ipcRenderer.invoke('teams:create', input),
  updateTeam: (id, input) => ipcRenderer.invoke('teams:update', id, input),
  removeTeam: id => ipcRenderer.invoke('teams:remove', id),
  addTeamMember: (id, member) => ipcRenderer.invoke('teams:members:add', id, member),
  removeTeamMember: (id, githubId) => ipcRenderer.invoke('teams:members:remove', id, githubId),
  getTeamActivity: query => ipcRenderer.invoke('teams:activity', query),
  refreshTeamActivity: query => ipcRenderer.invoke('teams:refresh', query),

  getGitHubCliAvailable: () => ipcRenderer.invoke('github:cli-available'),
  importGitHubCliToken: () => ipcRenderer.invoke('github:cli-import'),

  openInAppBrowser: url => ipcRenderer.invoke('inapp:open', url),
  openExternal: url => ipcRenderer.invoke('external:open', url),
  getInAppBrowserState: () => ipcRenderer.invoke('inapp:state'),
  selectInAppBrowserTab: id => ipcRenderer.invoke('inapp:select', id),
  pinInAppBrowserTab: (id, pinned) => ipcRenderer.invoke('inapp:pin', id, pinned),
  renameInAppBrowserTab: (id, name) => ipcRenderer.invoke('inapp:rename', id, name),
  closeInAppBrowserTab: id => ipcRenderer.invoke('inapp:close', id),
  hideInAppBrowser: () => ipcRenderer.invoke('inapp:hide'),
  mountInAppBrowser: (id, bounds) => ipcRenderer.invoke('inapp:mount', id, bounds),
  setInAppBrowserBounds: (id, bounds) => ipcRenderer.invoke('inapp:bounds', id, bounds),
  unmountInAppBrowser: id => ipcRenderer.invoke('inapp:unmount', id),
  inAppGoBack: id => ipcRenderer.invoke('inapp:back', id),
  inAppGoForward: id => ipcRenderer.invoke('inapp:forward', id),
  inAppReload: id => ipcRenderer.invoke('inapp:reload', id),
  inAppNavigate: (id, url) => ipcRenderer.invoke('inapp:navigate', id, url),
  inAppPopOut: url => ipcRenderer.invoke('inapp:popout', url),
  onInAppBrowserState: callback => {
    const listener = (_event: Electron.IpcRendererEvent, state: InAppBrowserState) => callback(state);
    ipcRenderer.on('inapp:state', listener);
    return () => ipcRenderer.removeListener('inapp:state', listener);
  },

  controlWindow: action => ipcRenderer.invoke('window:control', action),
  getFullScreen: () => ipcRenderer.invoke('window:fullscreen'),
  onFullScreenChange: callback => {
    const listener = (_event: Electron.IpcRendererEvent, payload: { isFullScreen: boolean }) =>
      callback(payload.isFullScreen);
    ipcRenderer.on('window:fullscreen-state', listener);
    return () => ipcRenderer.removeListener('window:fullscreen-state', listener);
  },
  getAppVersion: () => ipcRenderer.invoke('app:version'),
  findInPage: (text, options) => ipcRenderer.invoke('find:start', text, options),
  stopFindInPage: () => ipcRenderer.invoke('find:stop'),
  onFindResult: callback => {
    const listener = (_event: Electron.IpcRendererEvent, result: FindResult) => callback(result);
    ipcRenderer.on('find:result', listener);
    return () => ipcRenderer.removeListener('find:result', listener);
  },

  previewCherryPickRange: request => ipcRenderer.invoke('cherrypick:preview', request),
  refreshAgentProviderReadiness: () => ipcRenderer.invoke('agent:providers:refresh'),
  startCherryPickExecution: request => ipcRenderer.invoke('cherrypick:execution:start', request),
  getCherryPickExecution: executionId => ipcRenderer.invoke('cherrypick:execution:get', executionId),
  listCherryPickExecutions: limit => ipcRenderer.invoke('cherrypick:execution:list', limit),
  listCiAutofixRuns: limit => ipcRenderer.invoke('ci-autofix:list', limit),
  sendCherryPickMessage: (executionId, message) =>
    ipcRenderer.invoke('cherrypick:execution:message', executionId, message),
  cancelCherryPickExecution: (workspaceId, executionId) =>
    ipcRenderer.invoke('cherrypick:execution:cancel', workspaceId, executionId),
  getWorkspaceDeliveryStatus: (workspaceId, executionId) =>
    ipcRenderer.invoke('cherrypick:delivery:get', workspaceId, executionId),
  pushWorkspaceExecution: (workspaceId, executionId) =>
    ipcRenderer.invoke('cherrypick:delivery:push', workspaceId, executionId),
  createWorkspacePullRequest: (workspaceId, executionId) =>
    ipcRenderer.invoke('cherrypick:delivery:pull-request', workspaceId, executionId),

  gitOverview: workspaceId => ipcRenderer.invoke('git:overview', workspaceId),
  gitRemoteBranches: (workspaceId, remote) => ipcRenderer.invoke('git:branches', workspaceId, remote),
  gitLog: (workspaceId, source, branch, limit, remote) =>
    ipcRenderer.invoke('git:log', workspaceId, source, branch, limit, remote),
  gitDiff: (workspaceId, base, head) => ipcRenderer.invoke('git:diff', workspaceId, base, head),
  gitDiffFileContent: (workspaceId, base, head, path) =>
    ipcRenderer.invoke('git:diff-content', workspaceId, base, head, path),
  gitStatus: workspaceId => ipcRenderer.invoke('git:status', workspaceId),
  getCommitRefreshStatus: workspaceId =>
    ipcRenderer.invoke('git:commit-refresh-status', workspaceId),
  refreshCommitHistory: (workspaceId, refs) =>
    ipcRenderer.invoke('git:refresh-history', workspaceId, refs),
  gitCommit: (workspaceId, summary, description) =>
    ipcRenderer.invoke('git:commit', workspaceId, summary, description),
  gitReword: (workspaceId, branch, sha, message) =>
    ipcRenderer.invoke('git:reword', workspaceId, branch, sha, message),
  gitSquash: (workspaceId, branch, baseSha, tipSha, message) =>
    ipcRenderer.invoke('git:squash', workspaceId, branch, baseSha, tipSha, message),

  getModelSettings: workspaceId => ipcRenderer.invoke('settings:models', workspaceId),
  refreshModelSettings: (force = false) => ipcRenderer.invoke('settings:models:refresh', force),
  setAgentModel: (type, configId, model, effort) =>
    ipcRenderer.invoke('settings:models:agent:set', type, configId, model, effort),
  clearAgentModel: type => ipcRenderer.invoke('settings:models:agent:clear', type),
  addModelApiKey: (provider, alias, key) =>
    ipcRenderer.invoke('settings:api-keys:add', provider, alias, key),
  removeModelApiKey: alias => ipcRenderer.invoke('settings:api-keys:remove', alias),
  getAgentSkills: () => ipcRenderer.invoke('settings:agent-skills'),
  addAgentSkill: skill => ipcRenderer.invoke('settings:agent-skills:add', skill),
  updateAgentSkill: (id, skill) => ipcRenderer.invoke('settings:agent-skills:update', id, skill),
  removeAgentSkill: id => ipcRenderer.invoke('settings:agent-skills:remove', id),
  getQuietHours: () => ipcRenderer.invoke('settings:quiet-hours'),
  saveQuietHours: quietHours => ipcRenderer.invoke('settings:quiet-hours:save', quietHours),
  getDevelopmentAutomation: () => ipcRenderer.invoke('settings:development-automation'),
  saveDevelopmentAutomation: settings => ipcRenderer.invoke('settings:development-automation:save', settings),
  getCodeGraphSetting: () => ipcRenderer.invoke('settings:codegraph'),
  saveCodeGraphSetting: setting => ipcRenderer.invoke('settings:codegraph:save', setting),
  getCodeGraphBaseline: () => ipcRenderer.invoke('settings:codegraph:baseline'),
  getAppPreferences: () => ipcRenderer.invoke('settings:app'),
  saveAppPreferences: preferences => ipcRenderer.invoke('settings:app:save', preferences),
  getGitHubAccount: () => ipcRenderer.invoke('pat:account'),
  copyGitHubToken: () => ipcRenderer.invoke('pat:copy'),
  deleteGitHubToken: () => ipcRenderer.invoke('pat:delete'),

  getBackportChains: (workspaceId: string) => ipcRenderer.invoke('backport-chains:list', workspaceId),
  selectBackportSource: (workspaceId: string, id: string, selection: { version: number; oldest: string; newest: string }) => ipcRenderer.invoke('backport-chains:source', workspaceId, id, selection),
  stopBackportChain: (workspaceId: string, id: string) => ipcRenderer.invoke('backport-chains:stop', workspaceId, id),
  resumeBackportChain: (workspaceId: string, id: string) => ipcRenderer.invoke('backport-chains:resume', workspaceId, id),
  getLinkedWorkspaceWork: (workspaceId: string) => ipcRenderer.invoke('workspaces:linked-work', workspaceId),
  getRelatedWorkspaceRequest: (workspaceId: string, taskId: string) => ipcRenderer.invoke('workspaces:related-request', workspaceId, taskId),
  getWorkspaceRelationships: workspaceId => ipcRenderer.invoke('workspaces:relationships', workspaceId),
  saveWorkspaceRelationship: (workspaceId, relationship) => ipcRenderer.invoke('workspaces:relationships:save', workspaceId, relationship),
  removeWorkspaceRelationship: (workspaceId, destinationId) => ipcRenderer.invoke('workspaces:relationships:remove', workspaceId, destinationId),
  getWorkspaceSettings: workspaceId => ipcRenderer.invoke('workspaces:settings', workspaceId),
  getRepositoryContextBaseline: workspaceId => ipcRenderer.invoke('repository-context:build:baseline', workspaceId),
  getRepositoryContextBuild: workspaceId => ipcRenderer.invoke('repository-context:build:get', workspaceId),
  getRepositoryContextSpecChecks: workspaceId => ipcRenderer.invoke('repository-context:spec-checks:get', workspaceId),
  getRepositoryContextSpecModules: workspaceId => ipcRenderer.invoke('repository-context:spec-checks:modules', workspaceId),
  getRepositoryContextSpecSources: (workspaceId, directory) => ipcRenderer.invoke('repository-context:spec-checks:sources', workspaceId, directory),
  startRepositoryContextSpecCheck: (workspaceId, request) => ipcRenderer.invoke('repository-context:spec-checks:start', workspaceId, request),
  dismissRepositoryContextSpecFinding: (workspaceId, checkId, candidateId, request) =>
    ipcRenderer.invoke('repository-context:spec-checks:dismiss', workspaceId, checkId, candidateId, request),
  stopRepositoryContextBuild: (workspaceId, taskId) => ipcRenderer.invoke('repository-context:build:stop', workspaceId, taskId),
  getRepositoryContextBuildStopImpact: (workspaceId, taskId) => ipcRenderer.invoke('repository-context:build:stop-impact', workspaceId, taskId),
  stopRepositoryContextBuildRuntime: (workspaceId, taskId, generation) => ipcRenderer.invoke('repository-context:build:stop-runtime', workspaceId, taskId, generation),
  failRepositoryContextBuild: (workspaceId, taskId, reason) => ipcRenderer.invoke('repository-context:build:fail', workspaceId, taskId, reason),
  cleanupRepositoryContextBuild: (workspaceId, taskId) => ipcRenderer.invoke('repository-context:build:cleanup', workspaceId, taskId),
  getMemoryPage: workspaceId => ipcRenderer.invoke('memory:page', workspaceId),
  editMemory: (workspaceId, memoryId, expectedVersion, text) => ipcRenderer.invoke('memory:edit', workspaceId, memoryId, expectedVersion, text),
  restoreMemory: (workspaceId, memoryId, expectedVersion) => ipcRenderer.invoke('memory:restore', workspaceId, memoryId, expectedVersion),
  keepMemory: (workspaceId, memoryId) => ipcRenderer.invoke('memory:keep', workspaceId, memoryId),
  deleteMemory: (workspaceId, memoryId) => ipcRenderer.invoke('memory:delete', workspaceId, memoryId),
  undoDeleteMemory: (workspaceId, memoryId) => ipcRenderer.invoke('memory:undo-delete', workspaceId, memoryId),
  undoRememberedMemory: (workspaceId, memoryId) => ipcRenderer.invoke('memory:undo-remember', workspaceId, memoryId),
  saveMemorySwitches: (workspaceId, switches) => ipcRenderer.invoke('memory:switches', workspaceId, switches),
  startMemoryHarvest: (workspaceId, request) => ipcRenderer.invoke('memory:harvest', workspaceId, request),
  listReviewReplays: workspaceId => ipcRenderer.invoke('replay:list', workspaceId),
  startReviewReplay: (workspaceId, request) => ipcRenderer.invoke('replay:start', workspaceId, request),
  markReviewReplay: (workspaceId, replayId, request) => ipcRenderer.invoke('replay:mark', workspaceId, replayId, request),
  deleteReviewReplay: (workspaceId, replayId) => ipcRenderer.invoke('replay:delete', workspaceId, replayId),
  setWorkspaceCommitsTarget: (workspaceId, branch) => ipcRenderer.invoke('workspaces:commits-target:set', workspaceId, branch),
  clearWorkspaceCommitsTarget: workspaceId => ipcRenderer.invoke('workspaces:commits-target:clear', workspaceId),
  renameWorkspace: (workspaceId, name) => ipcRenderer.invoke('workspaces:rename', workspaceId, name),
  addWorkspaceRemote: (workspaceId, name, url) =>
    ipcRenderer.invoke('workspaces:remotes:add', workspaceId, name, url),
  removeWorkspaceRemote: (workspaceId, name) =>
    ipcRenderer.invoke('workspaces:remotes:remove', workspaceId, name),
  setWorkspaceAgentModel: (workspaceId, type, configId, model, effort) =>
    ipcRenderer.invoke('workspaces:agent-model:set', workspaceId, type, configId, model, effort),
  clearWorkspaceAgentModel: (workspaceId, type) =>
    ipcRenderer.invoke('workspaces:agent-model:clear', workspaceId, type),
  saveWorkspaceGitHubToken: (workspaceId, value) =>
    ipcRenderer.invoke('workspaces:token:save', workspaceId, value),
  deleteWorkspaceGitHubToken: workspaceId =>
    ipcRenderer.invoke('workspaces:token:delete', workspaceId),
  copyWorkspaceGitHubToken: workspaceId =>
    ipcRenderer.invoke('workspaces:token:copy', workspaceId),

  fetchRecent: query => ipcRenderer.invoke('recent:list', query),
  searchRecent: (query, limit = 10) => ipcRenderer.invoke('recent:search', query, limit),
  searchRecentWords: (query, limit = 10) => ipcRenderer.invoke('recent:words', query, limit),
  fetchRecentChanges: () => ipcRenderer.invoke('recent:changes'),
  recordRecentTouch: touch => ipcRenderer.invoke('recent:touch', touch),
  saveRecentDraft: draft => ipcRenderer.invoke('recent:draft', draft),
  clearRecent: () => ipcRenderer.invoke('recent:clear'),

  listAgentTasks: (workspaceId, limit) => ipcRenderer.invoke('agent:tasks:list', workspaceId, limit),
  createAgentTask: request => ipcRenderer.invoke('agent:tasks:create', request),
  getAgentTask: (taskId, workspaceId) => ipcRenderer.invoke('agent:tasks:get', taskId, workspaceId),
  getAgentTaskStartup: taskId => ipcRenderer.invoke('agent:tasks:startup', taskId),
  updateAgentTaskDefaults: (taskId, workspaceId, expectedVersion, defaults) =>
    ipcRenderer.invoke('agent:task:defaults', taskId, workspaceId, expectedVersion, defaults),
  listAgentTaskPullRequests: (taskId, workspaceId, refresh) =>
    ipcRenderer.invoke('agent:task:pull-requests', taskId, workspaceId, refresh),
  listAgentTaskChanges: workspaceId => ipcRenderer.invoke('agent:tasks:changes', workspaceId),
  listAgentTaskActivity: workspaceId => ipcRenderer.invoke('agent:tasks:activity', workspaceId),
  getCiAutofixRun: runId => ipcRenderer.invoke('ci-autofix:get', runId),
  sendCiAutofixMessage: (runId, message, idempotencyKey) => ipcRenderer.invoke('ci-autofix:message', runId, message, idempotencyKey),
  renameAgentTask: (taskId, workspaceId, expectedVersion, title) =>
    ipcRenderer.invoke('agent:task:rename', taskId, workspaceId, expectedVersion, title),
  archiveAgentTask: (taskId, workspaceId, expectedVersion, archived) =>
    ipcRenderer.invoke('agent:task:archive', taskId, workspaceId, expectedVersion, archived),
  deleteAgentTask: (taskId, workspaceId) => ipcRenderer.invoke('agent:task:delete', taskId, workspaceId),
  getEffectiveTurnSettings: (taskId, workspaceId) =>
    ipcRenderer.invoke('agent:task:effective-settings', taskId, workspaceId),
  readAgentHistory: (taskId, before, after) => ipcRenderer.invoke('agent:history:read', taskId, before, after),
  readAgentPendingApprovals: (taskId, turnId) => ipcRenderer.invoke('agent:approvals:pending', taskId, turnId),
  listTaskDiscussions: query => ipcRenderer.invoke('agent:discussions:list', query),
  openTaskDiscussion: request => ipcRenderer.invoke('agent:discussions:open', request),
  askTaskDiscussion: request => ipcRenderer.invoke('agent:discussions:ask', request),
  resolveTaskDiscussion: request => ipcRenderer.invoke('agent:discussions:resolve', request),
  retryTaskDiscussion: request => ipcRenderer.invoke('agent:discussions:retry', request),
  askTaskDiscussionComment: request => ipcRenderer.invoke('agent:discussions:ask-comment', request),
  draftTaskDiscussionReply: request => ipcRenderer.invoke('agent:discussions:draft-reply', request),
  listLocalReviewComments: query => ipcRenderer.invoke('agent:local-review:list', query),
  getDevelopmentLearning: (taskId, workspaceId) => ipcRenderer.invoke('agent:learning:get', taskId, workspaceId),
  createDevelopmentLearningComment: request => ipcRenderer.invoke('agent:learning:comment', request),
  createLocalReviewComment: request => ipcRenderer.invoke('agent:local-review:create', request),
  replyLocalReviewComment: request => ipcRenderer.invoke('agent:local-review:reply', request),
  resolveLocalReviewComment: request => ipcRenderer.invoke('agent:local-review:resolve', request),
  retryLocalReviewComment: request => ipcRenderer.invoke('agent:local-review:retry', request),
  startAgentTurn: request => ipcRenderer.invoke('agent:turns:start', request),
  steerAgentTurn: request => ipcRenderer.invoke('agent:turns:steer', request),
  queueAgentInput: request => ipcRenderer.invoke('agent:queue:add', request),
  listAgentQueue: (taskId, workspaceId) => ipcRenderer.invoke('agent:queue:list', taskId, workspaceId),
  removeAgentQueuedInput: (taskId, workspaceId, queuedInputId) =>
    ipcRenderer.invoke('agent:queue:remove', taskId, workspaceId, queuedInputId),
  steerAgentQueuedInput: (taskId, workspaceId, queuedInputId, turnId) =>
    ipcRenderer.invoke('agent:queue:steer', taskId, workspaceId, queuedInputId, turnId),
  resumeAgentQueue: (taskId, workspaceId) => ipcRenderer.invoke('agent:queue:resume', taskId, workspaceId),
  getAgentTurn: turnId => ipcRenderer.invoke('agent:turns:get', turnId),
  cancelAgentTurn: turnId => ipcRenderer.invoke('agent:turns:cancel', turnId),
  resolveAgentApproval: (approvalId, decision, scopedPermission, answers) =>
    ipcRenderer.invoke('agent:approvals:resolve', approvalId, decision, scopedPermission, answers),
  completeAgentTask: (taskId, workspaceId, notes) =>
    ipcRenderer.invoke('agent:tasks:complete', taskId, workspaceId, notes),
  cleanupAgentTaskWorktree: (taskId, workspaceId) =>
    ipcRenderer.invoke('agent:task:cleanup', taskId, workspaceId),
  pickAgentDraftAttachments: () => ipcRenderer.invoke('agent:attachments:pick-drafts'),
  pasteAgentDraftAttachments: () => ipcRenderer.invoke('agent:attachments:paste-drafts'),
  discardAgentDraftAttachments: (attachmentIds) => ipcRenderer.invoke('agent:attachments:discard-drafts', attachmentIds),
  pickAgentAttachments: (taskId, workspaceId) => ipcRenderer.invoke('agent:attachments:pick', taskId, workspaceId),
  pasteAgentAttachments: (taskId, workspaceId) => ipcRenderer.invoke('agent:attachments:paste', taskId, workspaceId),
  readAgentAttachment: (taskId, workspaceId, attachmentId) =>
    ipcRenderer.invoke('agent:attachments:read', taskId, workspaceId, attachmentId),
  removeAgentAttachment: (taskId, workspaceId, attachmentId) =>
    ipcRenderer.invoke('agent:attachments:remove', taskId, workspaceId, attachmentId),
  markAgentTaskFailed: (taskId, workspaceId, reason) =>
    ipcRenderer.invoke('agent:task:mark-failed', taskId, workspaceId, reason),
  getAgentRuntimeStopImpact: (taskId, workspaceId) =>
    ipcRenderer.invoke('agent:task:stop-impact', taskId, workspaceId),
  stopAgentRuntime: (taskId, workspaceId, generation) =>
    ipcRenderer.invoke('agent:task:stop', taskId, workspaceId, generation),
  listAgentReviews: workspaceId => ipcRenderer.invoke('review:list', workspaceId),
  findPullRequestTask: (workspaceId, repo, number) => ipcRenderer.invoke('agent:pr-task:find', workspaceId, repo, number),
  assignPullRequestAgent: (workspaceId, repo, number) => ipcRenderer.invoke('agent:pr-task:assign', workspaceId, repo, number),
  findAgentReview: (workspaceId, repo, number) => ipcRenderer.invoke('review:find', workspaceId, repo, number),
  startAgentReview: (workspaceId, repo, number) => ipcRenderer.invoke('review:start', workspaceId, repo, number),
  findTaskAgentReview: (workspaceId, taskId) => ipcRenderer.invoke('review:task:find', workspaceId, taskId),
  startTaskAgentReview: (workspaceId, taskId) => ipcRenderer.invoke('review:task:start', workspaceId, taskId),
  getAgentReview: reviewId => ipcRenderer.invoke('review:get', reviewId),
  stopAgentReview: reviewId => ipcRenderer.invoke('review:stop', reviewId),
  getAgentReviewRuntimeStopImpact: reviewId => ipcRenderer.invoke('review:runtime:stop-impact', reviewId),
  stopAgentReviewRuntime: (reviewId, generation) => ipcRenderer.invoke('review:runtime:stop', reviewId, generation),
  resumeAgentReview: reviewId => ipcRenderer.invoke('review:resume', reviewId),
  updateAgentReview: reviewId => ipcRenderer.invoke('review:update', reviewId),
  endAgentReview: reviewId => ipcRenderer.invoke('review:end', reviewId),
  listReviewCommentThreads: reviewId => ipcRenderer.invoke('review:comment-threads:list', reviewId),
  askReviewCommentThread: (reviewId, key, question) => ipcRenderer.invoke('review:comment-threads:ask', reviewId, key, question),
  resolveReviewCommentThread: (reviewId, discussionId, resolved, expectedVersion) =>
    ipcRenderer.invoke('review:comment-threads:resolve', reviewId, discussionId, resolved, expectedVersion),
  retryReviewCommentThread: (reviewId, discussionId, messageId) => ipcRenderer.invoke('review:comment-threads:retry', reviewId, discussionId, messageId),
  draftReviewCommentReply: (reviewId, discussionId) => ipcRenderer.invoke('review:comment-threads:draft-reply', reviewId, discussionId),
  readWorktreeChanges: (host, request) => ipcRenderer.invoke('code:changes', host, request),
  listCodeFiles: host => ipcRenderer.invoke('code:files', host),
  readCodeFile: (host, path, expectedRevision) => ipcRenderer.invoke('code:file', host, path, expectedRevision),
  searchCodeSymbols: (host, query, limit) => ipcRenderer.invoke('code:symbols', host, query, limit),
  readCodeOutline: (host, path) => ipcRenderer.invoke('code:outline', host, path),
  listCodeRuns: host => ipcRenderer.invoke('code:runs:list', host),
  startCodeRun: (host, path, method) => ipcRenderer.invoke('code:runs:start', host, path, method),
  stopCodeRun: (host, runId) => ipcRenderer.invoke('code:runs:stop', host, runId),
  readCodeRunOutput: (host, runId, after) => ipcRenderer.invoke('code:runs:output', host, runId, after),
  saveCodeFile: (host, path, content, expectedHash) => ipcRenderer.invoke('code:save', host, path, content, expectedHash),
  discardReviewCodeChanges: (reviewId, expectedHead) => ipcRenderer.invoke('review:code:discard', reviewId, expectedHead),
  syncReviewWorktree: (reviewId, expectedHead) => ipcRenderer.invoke('review:code:sync', reviewId, expectedHead),
  openCodeTerminal: (host, cols, rows) => ipcRenderer.invoke('terminal:open', host, cols, rows),
  openProviderSignIn: (laneId, cols, rows) => ipcRenderer.invoke('provider:signin:open', laneId, cols, rows),
  writeReviewTerminal: (terminalId, data) => ipcRenderer.send('terminal:write', terminalId, data),
  resizeReviewTerminal: (terminalId, cols, rows) => ipcRenderer.send('terminal:resize', terminalId, cols, rows),
  onReviewTerminalEvent: listener => {
    const data = (_event: unknown, message: { terminalId: string; data: string }) => listener({ terminalId: message.terminalId, data: message.data });
    const exit = (_event: unknown, message: { terminalId: string; exitCode: number }) => listener({ terminalId: message.terminalId, exitCode: message.exitCode });
    ipcRenderer.on('terminal:data', data);
    ipcRenderer.on('terminal:exit', exit);
    return () => {
      ipcRenderer.removeListener('terminal:data', data);
      ipcRenderer.removeListener('terminal:exit', exit);
    };
  },
  askAgentReview: (reviewId, findingId, targetRevision, message, cardId) =>
    ipcRenderer.invoke('review:ask', reviewId, findingId, targetRevision, message, cardId),
  messageAgentReview: (reviewId, message, delivery, idempotencyKey) =>
    ipcRenderer.invoke('review:message', reviewId, message, delivery, idempotencyKey),
  removeAgentReviewQueued: (reviewId, queuedInputId) => ipcRenderer.invoke('review:queue:remove', reviewId, queuedInputId),
  steerAgentReviewQueued: (reviewId, queuedInputId, turnId) => ipcRenderer.invoke('review:queue:steer', reviewId, queuedInputId, turnId),
  resolveAgentReviewFinding: (reviewId, findingId, expectedVersion, reason, remember) =>
    ipcRenderer.invoke('review:finding:resolve', reviewId, findingId, expectedVersion, reason, remember),
  reopenAgentReviewFinding: (reviewId, findingId, expectedVersion) =>
    ipcRenderer.invoke('review:finding:reopen', reviewId, findingId, expectedVersion),
  readAgentReviewHistory: (reviewId, taskId, before, after) => ipcRenderer.invoke('review:history:read', reviewId, taskId, before, after),
  readAgentReviewApprovals: (reviewId, taskId, turnId) => ipcRenderer.invoke('review:approvals:pending', reviewId, taskId, turnId),
  resolveAgentReviewApproval: (reviewId, approvalId, decision, answers) =>
    ipcRenderer.invoke('review:approvals:resolve', reviewId, approvalId, decision, answers),
  subscribeAgentReview: streamSubscriber('review'),

  addReviewDraftComment: (repo, number, expectedVersion, comment) =>
    ipcRenderer.invoke('review:draft:add-comment', repo, number, expectedVersion, comment),
  addReviewDraftFinding: (repo, number, expectedVersion, reviewId, findingId) =>
    ipcRenderer.invoke('review:draft:add-finding', repo, number, expectedVersion, reviewId, findingId),
  editReviewDraftComment: (repo, number, expectedVersion, itemId, body, included) =>
    ipcRenderer.invoke('review:draft:edit', repo, number, expectedVersion, itemId, body, included),
  removeReviewDraftComment: (repo, number, expectedVersion, itemId) =>
    ipcRenderer.invoke('review:draft:remove', repo, number, expectedVersion, itemId),
  saveReviewDraft: (repo, number, expectedVersion, body, event) =>
    ipcRenderer.invoke('review:draft:save', repo, number, expectedVersion, body, event),
  publishReviewDraftComment: (repo, number, expectedVersion, itemId) =>
    ipcRenderer.invoke('review:draft:publish', repo, number, expectedVersion, itemId),
  submitReviewDraft: (repo, number, expectedVersion, body, event) =>
    ipcRenderer.invoke('review:draft:submit', repo, number, expectedVersion, body, event),

  subscribeAgentTask: streamSubscriber('agent'),
};

contextBridge.exposeInMainWorld('bridge', bridge);
