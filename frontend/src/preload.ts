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
import type { AgentStreamMessage, Bridge, FindResult, InAppNavState } from './types';

const bridge: Bridge = {
  savePat: pat => ipcRenderer.invoke('pat:save', pat),
  hasPat: () => ipcRenderer.invoke('pat:has'),
  fetchPrs: (workspaceId, page, pageSize, search) => ipcRenderer.invoke('prs:list', workspaceId, page, pageSize, search),
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
  refreshPullRequestDetailByRef: (repo, number) =>
    ipcRenderer.invoke('prs:detail:refresh:by-ref', repo, number),
  refreshAllPullRequestDetailByRef: (repo, number) =>
    ipcRenderer.invoke('prs:detail:refresh-all:by-ref', repo, number),
  fetchPrDiffFiles: (repo, number, baseSha, headSha) =>
    ipcRenderer.invoke('prs:diff', repo, number, baseSha, headSha),
  refreshPrDiffFiles: (repo, number, baseSha, headSha) =>
    ipcRenderer.invoke('prs:diff:refresh', repo, number, baseSha, headSha),
  fetchPrDiffCommits: (repo, number) => ipcRenderer.invoke('prs:diff-commits', repo, number),
  fetchPrDiffFileContent: (repo, number, path, baseSha, headSha) =>
    ipcRenderer.invoke('prs:diff-content', repo, number, path, baseSha, headSha),
  refreshPrDiffFileContent: (repo, number, path, baseSha, headSha) =>
    ipcRenderer.invoke('prs:diff-content:refresh', repo, number, path, baseSha, headSha),
  postPrReviewComment: (repo, number, comment) =>
    ipcRenderer.invoke('prs:review-comment:create', repo, number, comment),
  updatePrDescription: (repo, number, body) =>
    ipcRenderer.invoke('prs:description:update', repo, number, body),
  commentPr: (repo, number, body) => ipcRenderer.invoke('prs:comment', repo, number, body),
  setPrReaction: (repo, number, target, commentId, content, viewerHasReacted) =>
    ipcRenderer.invoke('prs:reaction:set', repo, number, target, commentId, content, viewerHasReacted),
  closePr: (repo, number) => ipcRenderer.invoke('prs:close', repo, number),
  replyToReviewComment: (repo, number, commentId, body) =>
    ipcRenderer.invoke('prs:review-comment:reply', repo, number, commentId, body),
  setReviewThreadResolved: (repo, number, commentId, resolved) =>
    ipcRenderer.invoke('prs:review-thread:resolution', repo, number, commentId, resolved),
  rerunFailedPrChecks: (repo, number) => ipcRenderer.invoke('prs:checks:rerun-failed', repo, number),
  submitReview: (prId, repo, number, event, body, comments) =>
    ipcRenderer.invoke('prs:review', prId, repo, number, event, body, comments),
  betterWords: (text, repository, workspaceId) =>
    ipcRenderer.invoke('agent:better-words', text, repository, workspaceId),
  mergePr: (prId, repo, number, strategy) =>
    ipcRenderer.invoke('prs:merge', prId, repo, number, strategy),

  getWatchedRepos: () => ipcRenderer.invoke('repos:list'),
  removeWatchedRepo: (owner, repo) => ipcRenderer.invoke('repos:remove', owner, repo),
  searchRepositories: (query, limit) => ipcRenderer.invoke('workspaces:search', query, limit),
  previewWatch: selectedRepositoryId => ipcRenderer.invoke('workspaces:preview', selectedRepositoryId),
  watchRepository: command => ipcRenderer.invoke('workspaces:watch', command),
  listWorkspaces: () => ipcRenderer.invoke('workspaces:list'),
  getWorkspace: workspaceId => ipcRenderer.invoke('workspaces:get', workspaceId),
  getWorkspaceMetadata: workspaceId => ipcRenderer.invoke('workspaces:metadata', workspaceId),
  fetchWorkspaceIssues: (workspaceId, page, pageSize) => ipcRenderer.invoke('workspaces:issues', workspaceId, page, pageSize),
  fetchWorkspaceIssue: (workspaceId, issueNumber) => ipcRenderer.invoke('workspaces:issue', workspaceId, issueNumber),
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
  getUserProfile: () => ipcRenderer.invoke('repos:profile'),
  getGitHubUserProfile: login => ipcRenderer.invoke('repos:user-profile', login),
  getContributionCalendar: login => ipcRenderer.invoke('repos:contribution', login),
  getUserCommitsOnDate: (login, date) => ipcRenderer.invoke('repos:contribution-day', login, date),
  getRepoPull: (owner, repo, number) => ipcRenderer.invoke('repos:pull', owner, repo, number),
  getRepoMeta: (owner, repo) => ipcRenderer.invoke('repos:meta', owner, repo),
  fetchGitHubCodeSnippet: request => ipcRenderer.invoke('repos:code-snippet', request),
  getRecentActivity: login => ipcRenderer.invoke('repos:activity', login),
  getFollowingActivity: login => ipcRenderer.invoke('repos:following', login),

  getGitHubCliAvailable: () => ipcRenderer.invoke('github:cli-available'),
  importGitHubCliToken: () => ipcRenderer.invoke('github:cli-import'),

  openInAppBrowser: url => ipcRenderer.invoke('inapp:open', url),
  openExternal: url => ipcRenderer.invoke('external:open', url),
  mountInAppBrowser: (url, bounds) => ipcRenderer.invoke('inapp:mount', url, bounds),
  setInAppBrowserBounds: bounds => ipcRenderer.invoke('inapp:bounds', bounds),
  unmountInAppBrowser: () => ipcRenderer.invoke('inapp:unmount'),
  inAppGoBack: () => ipcRenderer.invoke('inapp:back'),
  inAppGoForward: () => ipcRenderer.invoke('inapp:forward'),
  inAppReload: () => ipcRenderer.invoke('inapp:reload'),
  inAppPopOut: url => ipcRenderer.invoke('inapp:popout', url),
  onInAppNavState: callback => {
    const listener = (_event: Electron.IpcRendererEvent, state: InAppNavState) => callback(state);
    ipcRenderer.on('inapp:nav-state', listener);
    return () => ipcRenderer.removeListener('inapp:nav-state', listener);
  },
  onInAppOpenRequest: callback => {
    const listener = (_event: Electron.IpcRendererEvent, payload: { url: string }) => callback(payload);
    ipcRenderer.on('inapp:open-request', listener);
    return () => ipcRenderer.removeListener('inapp:open-request', listener);
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
  getCherryPickPreview: previewId => ipcRenderer.invoke('cherrypick:preview:get', previewId),
  refreshAgentProviderReadiness: () => ipcRenderer.invoke('agent:providers:refresh'),
  startCherryPickExecution: request => ipcRenderer.invoke('cherrypick:execution:start', request),
  getCherryPickExecution: executionId => ipcRenderer.invoke('cherrypick:execution:get', executionId),
  listCherryPickExecutions: limit => ipcRenderer.invoke('cherrypick:execution:list', limit),
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

  getModelSettings: () => ipcRenderer.invoke('settings:models'),
  updateModelSelection: (configId, model, effort) =>
    ipcRenderer.invoke('settings:models:select', configId, model, effort),
  setMainModel: configId => ipcRenderer.invoke('settings:models:main', configId),
  addModelApiKey: (provider, alias, key) =>
    ipcRenderer.invoke('settings:api-keys:add', provider, alias, key),
  removeModelApiKey: alias => ipcRenderer.invoke('settings:api-keys:remove', alias),
  getQuietHours: () => ipcRenderer.invoke('settings:quiet-hours'),
  saveQuietHours: quietHours => ipcRenderer.invoke('settings:quiet-hours:save', quietHours),
  getCodeGraphSetting: () => ipcRenderer.invoke('settings:codegraph'),
  saveCodeGraphSetting: setting => ipcRenderer.invoke('settings:codegraph:save', setting),
  getGitHubAccount: () => ipcRenderer.invoke('pat:account'),
  copyGitHubToken: () => ipcRenderer.invoke('pat:copy'),
  deleteGitHubToken: () => ipcRenderer.invoke('pat:delete'),

  getWorkspaceSettings: workspaceId => ipcRenderer.invoke('workspaces:settings', workspaceId),
  renameWorkspace: (workspaceId, name) => ipcRenderer.invoke('workspaces:rename', workspaceId, name),
  addWorkspaceRemote: (workspaceId, name, url) =>
    ipcRenderer.invoke('workspaces:remotes:add', workspaceId, name, url),
  removeWorkspaceRemote: (workspaceId, name) =>
    ipcRenderer.invoke('workspaces:remotes:remove', workspaceId, name),
  setWorkspaceAgentModel: (workspaceId, configId, model, effort) =>
    ipcRenderer.invoke('workspaces:agent-model:set', workspaceId, configId, model, effort),
  clearWorkspaceAgentModel: workspaceId =>
    ipcRenderer.invoke('workspaces:agent-model:clear', workspaceId),
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
  clearRecentRow: id => ipcRenderer.invoke('recent:clear-row', id),

  listAgentTasks: (workspaceId, limit) => ipcRenderer.invoke('agent:tasks:list', workspaceId, limit),
  createAgentTask: request => ipcRenderer.invoke('agent:tasks:create', request),
  getAgentTask: (taskId, workspaceId) => ipcRenderer.invoke('agent:tasks:get', taskId, workspaceId),
  updateAgentTaskDefaults: (taskId, workspaceId, expectedVersion, defaults) =>
    ipcRenderer.invoke('agent:task:defaults', taskId, workspaceId, expectedVersion, defaults),
  listAgentTaskPullRequests: (taskId, workspaceId) =>
    ipcRenderer.invoke('agent:task:pull-requests', taskId, workspaceId),
  renameAgentTask: (taskId, workspaceId, expectedVersion, title) =>
    ipcRenderer.invoke('agent:task:rename', taskId, workspaceId, expectedVersion, title),
  getEffectiveTurnSettings: (taskId, workspaceId) =>
    ipcRenderer.invoke('agent:task:effective-settings', taskId, workspaceId),
  readAgentEvents: (taskId, after, limit) => ipcRenderer.invoke('agent:events:read', taskId, after, limit),
  startAgentTurn: request => ipcRenderer.invoke('agent:turns:start', request),
  steerAgentTurn: request => ipcRenderer.invoke('agent:turns:steer', request),
  queueAgentInput: request => ipcRenderer.invoke('agent:queue:add', request),
  listAgentQueue: (taskId, workspaceId) => ipcRenderer.invoke('agent:queue:list', taskId, workspaceId),
  removeAgentQueuedInput: (taskId, workspaceId, queuedInputId) =>
    ipcRenderer.invoke('agent:queue:remove', taskId, workspaceId, queuedInputId),
  resumeAgentQueue: (taskId, workspaceId) => ipcRenderer.invoke('agent:queue:resume', taskId, workspaceId),
  getAgentTurn: turnId => ipcRenderer.invoke('agent:turns:get', turnId),
  cancelAgentTurn: turnId => ipcRenderer.invoke('agent:turns:cancel', turnId),
  resolveAgentApproval: (approvalId, decision, scopedPermission) =>
    ipcRenderer.invoke('agent:approvals:resolve', approvalId, decision, scopedPermission),
  completeAgentTask: (taskId, workspaceId, notes) =>
    ipcRenderer.invoke('agent:tasks:complete', taskId, workspaceId, notes),
  cleanupAgentTaskWorktree: (taskId, workspaceId) =>
    ipcRenderer.invoke('agent:task:cleanup', taskId, workspaceId),
  pickAgentAttachments: (taskId, workspaceId) => ipcRenderer.invoke('agent:attachments:pick', taskId, workspaceId),
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
  subscribeAgentTask: async (taskId, after, listener) => {
    // Main pushes every task on one channel, so each listener keeps only what it asked for.
    const receiver = createAgentStreamReceiver(listener);
    const receive = (_event: unknown, message: AgentStreamMessage): void => receiver.receive(message);
    ipcRenderer.on('agent:stream:message', receive);
    let subscriptionId: string | null = null;
    try {
      subscriptionId = await ipcRenderer.invoke('agent:stream:subscribe', taskId, after);
      receiver.activate(subscriptionId);
    }
    catch (failure) {
      ipcRenderer.removeListener('agent:stream:message', receive);
      if (subscriptionId !== null) void ipcRenderer.invoke('agent:stream:unsubscribe', subscriptionId);
      throw failure;
    }
    return () => {
      ipcRenderer.removeListener('agent:stream:message', receive);
      void ipcRenderer.invoke('agent:stream:unsubscribe', subscriptionId);
    };
  },
};

contextBridge.exposeInMainWorld('bridge', bridge);
