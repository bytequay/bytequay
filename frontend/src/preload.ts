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
import { createJiraBridge } from '@bytequay/jira/preload';
import { createLabBridge } from '@bytequay/lab/preload';
import { subscribe } from './ipcSubscribe';
import { createGoogleBridge } from '@bytequay/google/preload';
import type { IdeDesktopBridge } from './ide/contracts';
import { contextBridge, ipcRenderer } from 'electron';
import { createAgentStreamReceiver } from './agents/sse';
import type { AgentStreamMessage, Bridge } from './types';
import { observeNoticeOcclusion } from './notices/occlusion';

ipcRenderer.on('github:account-changed', () => window.dispatchEvent(new Event('bytequay:github-account-changed')));


/**
 * One stream subscription over a channel family.
 *
 * Main pushes every stream on one channel per family, so each listener keeps only what it asked for.
 */
function streamSubscriber(prefix: 'agent' | 'review'): Bridge['subscribeAgentTask'] {
  return async (id, after, listener, statusOnly) => {
    const receiver = createAgentStreamReceiver(listener);
    const unsubscribe = subscribe<AgentStreamMessage>(`${prefix}:stream:message`, message => receiver.receive(message));
    let subscriptionId: string | null = null;
    try {
      subscriptionId = await ipcRenderer.invoke(`${prefix}:stream:subscribe`, id, after, statusOnly);
      receiver.activate(subscriptionId);
    }
    catch (failure) {
      unsubscribe();
      if (subscriptionId !== null) void ipcRenderer.invoke(`${prefix}:stream:unsubscribe`, subscriptionId);
      throw failure;
    }
    return () => {
      unsubscribe();
      void ipcRenderer.invoke(`${prefix}:stream:unsubscribe`, subscriptionId);
    };
  };
}

const invoke = (channel: string) => (...args: unknown[]) => ipcRenderer.invoke(channel, ...args);

const bridge: Bridge = {
  onAppNoticeOpen: callback => subscribe('notices:open-task', callback),
  setNoticeSource: invoke('notices:source'),
  readAppNotices: invoke('notices:read'),
  onAppNotices: callback => subscribe('notices:changed', callback),
  openAppNotice: invoke('notices:open'),
  removeAppNotice: invoke('notices:remove'),
  setAssistantPanelOpen: open => ipcRenderer.send('notices:suppress', open),
  onAssistantToggle: callback => subscribe('assistant:toggle', callback),
  onAssistantOpenCard: callback => subscribe('assistant:open-card', callback),
  ...createGoogleBridge(ipcRenderer),
  ...createJiraBridge(ipcRenderer),
  createLocalJiraChild: invoke('jira:issue:local-child'),
  savePat: invoke('pat:save'),
  hasPat: invoke('pat:has'),
  fetchPrs: invoke('prs:list'),
  fetchAttentionPrs: invoke('prs:attention'),
  findStoredPullRequest: invoke('prs:stored'),
  findStoredPullRequestDescription: invoke('prs:stored:description'),
  setPrMonitored: invoke('prs:monitor'),
  setPrAttentionHandled: invoke('prs:attention:handled'),
  getAttentionMarks: invoke('attention:marks'),
  changeAttentionMarks: invoke('attention:marks:change'),
  setPrAttentionPinned: invoke('prs:attention:pinned'),
  onPrAttentionUpdated: callback => subscribe('prs:attention-updated', callback),
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
  syncPrs: invoke('prs:sync'),
  onPullRequestsSynced: callback => subscribe('pull-requests:synced', () => callback()),
  fetchPullRequestDetailByRef: invoke('prs:detail:by-ref'),
  lookupPullRequest: invoke('prs:lookup'),
  fetchRepoUsers: invoke('prs:users'),
  fetchRepoTeams: invoke('prs:teams'),
  fetchRepoLabels: invoke('prs:labels'),
  updatePrMetadata: invoke('prs:metadata:update'),
  updatePrReviewer: invoke('prs:reviewer:update'),
  fetchMergeMode: invoke('prs:merge-mode'),
  fetchPrConflicts: invoke('prs:conflicts'),
  refreshPullRequestDetailByRef: invoke('prs:detail:refresh:by-ref'),
  refreshAllPullRequestDetailByRef: invoke('prs:detail:refresh-all:by-ref'),
  fetchPrDiffFiles: invoke('prs:diff'),
  refreshPrDiffFiles: invoke('prs:diff:refresh'),
  fetchPrDiffCommits: invoke('prs:diff-commits'),
  refreshPrDiffFileContent: invoke('prs:diff-content:refresh'),
  postPrReviewComment: invoke('prs:review-comment:create'),
  updatePrDescription: invoke('prs:description:update'),
  updatePrTitle: invoke('prs:title:update'),
  commentPr: invoke('prs:comment'),
  setPrReaction: invoke('prs:reaction:set'),
  editPrComment: invoke('prs:comment:edit'),
  closePr: invoke('prs:close'),
  markPrReadyForReview: invoke('prs:ready-for-review'),
  replyToReviewComment: invoke('prs:review-comment:reply'),
  setReviewThreadResolved: invoke('prs:review-thread:resolution'),
  getPrCheckLog: invoke('prs:checks:log'),
  rerunFailedPrChecks: invoke('prs:checks:rerun-failed'),
  betterWords: invoke('agent:better-words'),
  mergePr: invoke('prs:merge'),
  removePrFromMergeQueue: invoke('prs:merge-queue:remove'),

  searchRepositories: invoke('workspaces:search'),
  previewWatch: invoke('workspaces:preview'),
  watchRepository: invoke('workspaces:watch'),
  listWorkspaces: invoke('workspaces:list'),
  listRetiredWorkspaces: invoke('workspaces:list-retired'),
  getWorkspace: invoke('workspaces:get'),
  getWorkspaceMetadata: invoke('workspaces:metadata'),
  fetchWorkspaceIssues: invoke('workspaces:issues'),
  fetchWorkspaceIssue: invoke('workspaces:issue'),
  fetchWorkspaceIssueAttention: invoke('workspaces:issues:attention'),
  setWorkspaceIssueEpic: invoke('workspaces:issues:epic'),
  listUnifiedWorkItems: invoke('work-items:catalog'),
  listWorkItemContextLinks: invoke('work-items:context-links'),
  linkWorkItemContext: invoke('work-items:link-context'),
  unlinkWorkItemContext: invoke('work-items:unlink-context'),
  publishWorkItem: invoke('work-items:publish'),
  reconcileWorkItem: invoke('work-items:reconcile'),
  workItemJiraTypes: invoke('work-items:jira-types'),
  specWorkItem: invoke('work-items:spec'),
  listWorkItems: invoke('work-items:list'),
  setLocalWorkItemEpic: invoke('work-items:mark-epic'),
  createLocalEpic: invoke('epics:create-local'),
  draftEpic: invoke('epics:draft'),
  assignWorkItemEpic: invoke('work-items:assign-epic'),
  createWorkItem: invoke('work-items:create'),
  decideWorkItem: invoke('work-items:decide'),
  orderEpicWork: invoke('work-items:epic-order'),
  joinEpic: invoke('work-items:join-epic'),
  removeFromEpic: invoke('work-items:leave-epic'),
  getEpic: invoke('epics:get'),
  listArtifacts: invoke('artifacts:list'),
  startArtifact: invoke('artifacts:start'),
  viewArtifact: invoke('artifacts:view'),
  sendArtifact: invoke('artifacts:send'),
  artifactEvents: invoke('artifacts:events'),
  answerArtifactOffer: invoke('artifacts:offer'),
  answerArtifactQuestion: invoke('artifacts:question'),
  stopArtifact: invoke('artifacts:stop'),
  showArtifact: invoke('artifacts:show'),
  refreshEpicChildren: invoke('epics:children'),
  syncEpicChildren: invoke('epics:sync'),
  dismissEpicChildren: invoke('epics:dismiss'),
  concludeEpic: invoke('epics:conclude'),
  listConcludedEpics: invoke('epics:concluded'),
  proposeEpicWork: invoke('epics:propose'),
  acceptEpicProposal: invoke('epics:accept-proposal'),
  dismissEpicProposal: invoke('epics:dismiss-proposal'),
  searchGoalCandidates: invoke('goals:candidates'),
  refreshWorkspaceIssues: invoke('workspaces:issues:refresh'),
  fetchWorkspaceIssueDetail: invoke('workspaces:issues:detail'),
  refreshWorkspaceIssueDetail: invoke('workspaces:issues:detail:refresh'),
  commentWorkspaceIssue: invoke('workspaces:issues:comment'),
  closeWorkspaceIssue: invoke('workspaces:issues:close'),
  updateWorkspaceIssueType: invoke('workspaces:issues:type:update'),
  updateWorkspaceIssueMetadata: invoke('workspaces:issues:metadata:update'),
  syncWorkspace: invoke('workspaces:sync'),
  setWorkspaceUpstream: invoke('workspaces:upstream'),
  removeWorkspace: invoke('workspaces:remove'),
  deleteWorkspace: invoke('workspaces:delete'),
  getRepositoryAvatar: invoke('repos:avatar'),
  getUserProfile: invoke('repos:profile'),
  getGitHubUserProfile: invoke('repos:user-profile'),
  getContributionCalendar: invoke('repos:contribution'),
  getUserCommitsOnDate: invoke('repos:contribution-day'),
  fetchGitHubIssuePreview: invoke('repos:issue-preview'),
  fetchGitHubCodeSnippet: invoke('repos:code-snippet'),
  getRecentActivity: invoke('repos:activity'),
  getFollowingActivity: invoke('repos:following'),
  getTeams: invoke('teams:list'),
  fetchTeamContributors: invoke('teams:contributors'),
  createTeam: invoke('teams:create'),
  updateTeam: invoke('teams:update'),
  removeTeam: invoke('teams:remove'),
  addTeamMember: invoke('teams:members:add'),
  removeTeamMember: invoke('teams:members:remove'),
  getTeamActivity: invoke('teams:activity'),
  refreshTeamActivity: invoke('teams:refresh'),

  getGitHubCliAvailable: invoke('github:cli-available'),
  importGitHubCliToken: invoke('github:cli-import'),

  ...createLabBridge(channel => ipcRenderer.invoke(channel), subscribe),
  openInAppBrowser: invoke('inapp:open'),
  openExternal: invoke('external:open'),
  getInAppBrowserState: invoke('inapp:state'),
  selectInAppBrowserTab: invoke('inapp:select'),
  pinInAppBrowserTab: invoke('inapp:pin'),
  renameInAppBrowserTab: invoke('inapp:rename'),
  closeInAppBrowserTab: invoke('inapp:close'),
  hideInAppBrowser: invoke('inapp:hide'),
  mountInAppBrowser: invoke('inapp:mount'),
  setInAppBrowserBounds: invoke('inapp:bounds'),
  unmountInAppBrowser: invoke('inapp:unmount'),
  inAppGoBack: invoke('inapp:back'),
  inAppGoForward: invoke('inapp:forward'),
  inAppReload: invoke('inapp:reload'),
  inAppNavigate: invoke('inapp:navigate'),
  inAppPopOut: invoke('inapp:popout'),
  onInAppBrowserState: callback => subscribe('inapp:state', callback),

  controlWindow: invoke('window:control'),
  getFullScreen: invoke('window:fullscreen'),
  onFullScreenChange: callback => subscribe<{ isFullScreen: boolean }>('window:fullscreen-state', payload => callback(payload.isFullScreen)),
  getAppVersion: invoke('app:version'),
  findInPage: invoke('find:start'),
  stopFindInPage: invoke('find:stop'),
  onFindResult: callback => subscribe('find:result', callback),

  previewCherryPickRange: invoke('cherrypick:preview'),
  previewCherryPickPullRequest: invoke('cherrypick:preview-pull-request'),
  listMergedPullRequests: invoke('prs:merged'),
  refreshAgentProviderReadiness: invoke('agent:providers:refresh'),
  startCherryPickExecution: invoke('cherrypick:execution:start'),
  getCherryPickExecution: invoke('cherrypick:execution:get'),
  listCherryPickExecutions: invoke('cherrypick:execution:list'),
  listCiAutofixRuns: invoke('ci-autofix:list'),
  sendCherryPickMessage: invoke('cherrypick:execution:message'),
  cancelCherryPickExecution: invoke('cherrypick:execution:cancel'),
  getWorkspaceDeliveryStatus: invoke('cherrypick:delivery:get'),
  pushWorkspaceExecution: invoke('cherrypick:delivery:push'),
  createWorkspacePullRequest: invoke('cherrypick:delivery:pull-request'),

  gitOverview: invoke('git:overview'),
  gitRemoteBranches: invoke('git:branches'),
  gitLog: invoke('git:log'),
  gitDiff: invoke('git:diff'),
  gitDiffFileContent: invoke('git:diff-content'),
  gitStatus: invoke('git:status'),
  getCommitRefreshStatus: invoke('git:commit-refresh-status'),
  refreshCommitHistory: invoke('git:refresh-history'),
  gitCommit: invoke('git:commit'),
  gitReword: invoke('git:reword'),
  gitSquash: invoke('git:squash'),

  getUsageAccounts: invoke('usage:accounts'),
  getProviderUsage: invoke('usage:read'),
  getModelSettings: invoke('settings:models'),
  addClaudeAccount: invoke('settings:claude-accounts:add'),
  restrictClaudeAccount: invoke('settings:claude-accounts:restrict'),
  removeClaudeAccount: invoke('settings:claude-accounts:remove'),
  refreshModelSettings: (force = false, workspaceId) => ipcRenderer.invoke('settings:models:refresh', force, workspaceId),
  setAgentModel: invoke('settings:models:agent:set'),
  clearAgentModel: invoke('settings:models:agent:clear'),
  addModelApiKey: invoke('settings:api-keys:add'),
  removeModelApiKey: invoke('settings:api-keys:remove'),
  getAgentSkills: invoke('settings:agent-skills'),
  addAgentSkill: invoke('settings:agent-skills:add'),
  updateAgentSkill: invoke('settings:agent-skills:update'),
  removeAgentSkill: invoke('settings:agent-skills:remove'),
  getQuietHours: invoke('settings:quiet-hours'),
  saveQuietHours: invoke('settings:quiet-hours:save'),
  getDevelopmentAutomation: invoke('settings:development-automation'),
  saveDevelopmentAutomation: invoke('settings:development-automation:save'),
  getAppPreferences: invoke('settings:app'),
  saveAppPreferences: invoke('settings:app:save'),
  getSetupPreferences: invoke('settings:setup'),
  saveSetupPreferences: invoke('settings:setup:save'),
  getGitHubAccount: invoke('pat:account'),
  copyGitHubToken: invoke('pat:copy'),
  deleteGitHubToken: invoke('pat:delete'),

  getBackportChains: invoke('backport-chains:list'),
  startBackportChain: invoke('backport-chains:start'),
  selectBackportSource: invoke('backport-chains:source'),
  stopBackportChain: invoke('backport-chains:stop'),
  resumeBackportChain: invoke('backport-chains:resume'),
  getLinkedWorkspaceWork: invoke('workspaces:linked-work'),
  getRelatedWorkspaceRequest: invoke('workspaces:related-request'),
  getWorkspaceRelationships: invoke('workspaces:relationships'),
  saveWorkspaceRelationship: invoke('workspaces:relationships:save'),
  removeWorkspaceRelationship: invoke('workspaces:relationships:remove'),
  getWorkspaceSettings: invoke('workspaces:settings'),
  getRepositoryContextBuild: invoke('repository-context:build:get'),
  getRepositoryContextSpecChecks: invoke('repository-context:spec-checks:get'),
  getRepositoryContextSpecModules: invoke('repository-context:spec-checks:modules'),
  getRepositoryContextSpecSources: invoke('repository-context:spec-checks:sources'),
  startRepositoryContextSpecCheck: invoke('repository-context:spec-checks:start'),
  dismissRepositoryContextSpecFinding: invoke('repository-context:spec-checks:dismiss'),
  stopRepositoryContextBuild: invoke('repository-context:build:stop'),
  getRepositoryContextBuildStopImpact: invoke('repository-context:build:stop-impact'),
  stopRepositoryContextBuildRuntime: invoke('repository-context:build:stop-runtime'),
  failRepositoryContextBuild: invoke('repository-context:build:fail'),
  cleanupRepositoryContextBuild: invoke('repository-context:build:cleanup'),
  getMemoryPage: invoke('memory:page'),
  editMemory: invoke('memory:edit'),
  restoreMemory: invoke('memory:restore'),
  keepMemory: invoke('memory:keep'),
  deleteMemory: invoke('memory:delete'),
  undoDeleteMemory: invoke('memory:undo-delete'),
  undoRememberedMemory: invoke('memory:undo-remember'),
  saveMemorySwitches: invoke('memory:switches'),
  startMemoryHarvest: invoke('memory:harvest'),
  searchMemoryPulls: invoke('memory:pulls'),
  listReviewReplays: invoke('replay:list'),
  startReviewReplay: invoke('replay:start'),
  markReviewReplay: invoke('replay:mark'),
  deleteReviewReplay: invoke('replay:delete'),
  setWorkspaceCommitsTarget: invoke('workspaces:commits-target:set'),
  clearWorkspaceCommitsTarget: invoke('workspaces:commits-target:clear'),
  renameWorkspace: invoke('workspaces:rename'),
  setWorkspaceAgentModel: invoke('workspaces:agent-model:set'),
  clearWorkspaceAgentModel: invoke('workspaces:agent-model:clear'),
  saveWorkspaceGitHubToken: invoke('workspaces:token:save'),
  deleteWorkspaceGitHubToken: invoke('workspaces:token:delete'),
  copyWorkspaceGitHubToken: invoke('workspaces:token:copy'),

  fetchRecent: invoke('recent:list'),
  searchRecent: (query, limit = 10) => ipcRenderer.invoke('recent:search', query, limit),
  searchRecentWords: (query, limit = 10) => ipcRenderer.invoke('recent:words', query, limit),
  fetchRecentChanges: invoke('recent:changes'),
  recordRecentTouch: invoke('recent:touch'),
  saveRecentDraft: invoke('recent:draft'),
  clearRecent: invoke('recent:clear'),

  getAgendaToday: invoke('agenda:today'),
  makeAgendaAgain: invoke('agenda:again'),
  markAgendaItem: invoke('agenda:mark'),
  startAgendaItem: invoke('agenda:start'),
  getAgendaSchedule: invoke('settings:agenda'),
  saveAgendaSchedule: invoke('settings:agenda:save'),
  getAssistantToday: invoke('assistant:today'),
  sendAssistantMessage: invoke('assistant:send'),
  stopAssistant: invoke('assistant:stop'),
  retryAssistant: invoke('assistant:retry'),
  recordAssistantAnswer: invoke('assistant:answered'),
  recordAssistantDraft: invoke('assistant:drafted'),
  getAssistantPermissions: invoke('settings:assistant'),
  saveAssistantPermissions: invoke('settings:assistant:save'),

  listAgentTasks: invoke('agent:tasks:list'),
  listRunningTasks: invoke('agent:tasks:running'),
  createAgentTask: invoke('agent:tasks:create'),
  getAgentTask: invoke('agent:tasks:get'),
  getAgentTaskStartup: invoke('agent:tasks:startup'),
  updateAgentTaskDefaults: invoke('agent:task:defaults'),
  listAgentTaskPullRequests: invoke('agent:task:pull-requests'),
  listAgentTaskChanges: invoke('agent:tasks:changes'),
  listAgentTaskActivity: invoke('agent:tasks:activity'),
  getCiAutofixRun: invoke('ci-autofix:get'),
  cancelCiAutofixRun: invoke('ci-autofix:cancel'),
  sendCiAutofixMessage: invoke('ci-autofix:message'),
  renameAgentTask: invoke('agent:task:rename'),
  archiveAgentTask: invoke('agent:task:archive'),
  deleteAgentTask: invoke('agent:task:delete'),
  getEffectiveTurnSettings: invoke('agent:task:effective-settings'),
  readAgentHistory: invoke('agent:history:read'),
  readAgentPendingApprovals: invoke('agent:approvals:pending'),
  listTaskDiscussions: invoke('agent:discussions:list'),
  openTaskDiscussion: invoke('agent:discussions:open'),
  askTaskDiscussion: invoke('agent:discussions:ask'),
  resolveTaskDiscussion: invoke('agent:discussions:resolve'),
  retryTaskDiscussion: invoke('agent:discussions:retry'),
  askTaskDiscussionComment: invoke('agent:discussions:ask-comment'),
  draftTaskDiscussionReply: invoke('agent:discussions:draft-reply'),
  listLocalReviewComments: invoke('agent:local-review:list'),
  getDevelopmentLearning: invoke('agent:learning:get'),
  createDevelopmentLearningComment: invoke('agent:learning:comment'),
  createLocalReviewComment: invoke('agent:local-review:create'),
  replyLocalReviewComment: invoke('agent:local-review:reply'),
  resolveLocalReviewComment: invoke('agent:local-review:resolve'),
  retryLocalReviewComment: invoke('agent:local-review:retry'),
  startAgentTurn: invoke('agent:turns:start'),
  steerAgentTurn: invoke('agent:turns:steer'),
  queueAgentInput: invoke('agent:queue:add'),
  listAgentQueue: invoke('agent:queue:list'),
  editAgentQueuedInput: invoke('agent:queue:edit'),
  removeAgentQueuedInput: invoke('agent:queue:remove'),
  steerAgentQueuedInput: invoke('agent:queue:steer'),
  resumeAgentQueue: invoke('agent:queue:resume'),
  getAgentTurn: invoke('agent:turns:get'),
  cancelAgentTurn: invoke('agent:turns:cancel'),
  resolveAgentApproval: invoke('agent:approvals:resolve'),
  completeAgentTask: invoke('agent:tasks:complete'),
  cleanupAgentTaskWorktree: invoke('agent:task:cleanup'),
  pickAgentDraftAttachments: invoke('agent:attachments:pick-drafts'),
  pasteAgentDraftAttachments: invoke('agent:attachments:paste-drafts'),
  discardAgentDraftAttachments: invoke('agent:attachments:discard-drafts'),
  pickAgentAttachments: invoke('agent:attachments:pick'),
  pasteAgentAttachments: invoke('agent:attachments:paste'),
  readAgentAttachment: invoke('agent:attachments:read'),
  removeAgentAttachment: invoke('agent:attachments:remove'),
  markAgentTaskFailed: invoke('agent:task:mark-failed'),
  cancelAgentAutoMerge: invoke('agent:task:auto-merge:cancel'),
  getAgentRuntimeStopImpact: invoke('agent:task:stop-impact'),
  stopAgentRuntime: invoke('agent:task:stop'),
  listAgentReviews: invoke('review:list'),
  findPullRequestTask: invoke('agent:pr-task:find'),
  assignPullRequestAgent: invoke('agent:pr-task:assign'),
  findAgentReview: invoke('review:find'),
  startAgentReview: invoke('review:start'),
  prepareReviewWorktree: invoke('review:worktree:prepare'),
  findTaskAgentReview: invoke('review:task:find'),
  startTaskAgentReview: invoke('review:task:start'),
  getAgentReview: invoke('review:get'),
  stopAgentReview: invoke('review:stop'),
  getAgentReviewRuntimeStopImpact: invoke('review:runtime:stop-impact'),
  stopAgentReviewRuntime: invoke('review:runtime:stop'),
  resumeAgentReview: invoke('review:resume'),
  updateAgentReview: invoke('review:update'),
  configureAgentReview: invoke('review:settings'),
  refreshAgentReviewExplanation: invoke('review:explanation:refresh'),
  endAgentReview: invoke('review:end'),
  listReviewDiscussions: invoke('review:discussions:list'),
  openReviewDiscussion: invoke('review:discussions:open'),
  askReviewDiscussion: invoke('review:discussions:ask'),
  askReviewCommentThread: invoke('review:discussions:ask-comment'),
  resolveReviewDiscussion: invoke('review:discussions:resolve'),
  retryReviewDiscussion: invoke('review:discussions:retry'),
  draftReviewCommentReply: invoke('review:discussions:draft-reply'),
  readWorktreeChanges: invoke('code:changes'),
  listCodeFiles: invoke('code:files'),
  readCodeFile: invoke('code:file'),
  listCodeRuns: invoke('code:runs:list'),
  stopCodeRun: invoke('code:runs:stop'),
  readCodeRunOutput: invoke('code:runs:output'),
  openCodeTerminal: invoke('terminal:open'),
  openProviderSignIn: invoke('provider:signin:open'),
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
  askAgentReview: invoke('review:ask'),
  messageAgentReview: invoke('review:message'),
  resumeAgentReviewQueue: invoke('review:queue:resume'),
  removeAgentReviewQueued: invoke('review:queue:remove'),
  steerAgentReviewQueued: invoke('review:queue:steer'),
  resolveAgentReviewFinding: invoke('review:finding:resolve'),
  reopenAgentReviewFinding: invoke('review:finding:reopen'),
  readAgentReviewHistory: invoke('review:history:read'),
  readAgentReviewApprovals: invoke('review:approvals:pending'),
  resolveAgentReviewApproval: invoke('review:approvals:resolve'),
  subscribeAgentReview: streamSubscriber('review'),

  getReviewDraft: invoke('review:draft:get'),
  addReviewDraftComment: invoke('review:draft:add-comment'),
  addReviewDraftFinding: invoke('review:draft:add-finding'),
  editReviewDraftComment: invoke('review:draft:edit'),
  removeReviewDraftComment: invoke('review:draft:remove'),
  confirmReviewDraftCommentNotPosted: invoke('review:draft:confirm-not-posted'),
  confirmReviewDraftCommentPosted: invoke('review:draft:confirm-posted'),
  confirmReviewDraftReview: invoke('review:draft:confirm-review'),
  saveReviewDraft: invoke('review:draft:save'),
  publishReviewDraftComment: invoke('review:draft:publish'),
  submitReviewDraft: invoke('review:draft:submit'),

  subscribeAgentTask: streamSubscriber('agent'),
};

contextBridge.exposeInMainWorld('bridge', bridge);

window.addEventListener('DOMContentLoaded', () => {
  observeNoticeOcclusion(obscured => ipcRenderer.send('notices:obscured', obscured));
});

const ide: IdeDesktopBridge = {
  resume: invoke('ide:resume'),
  open: invoke('ide:open'),
  present: invoke('ide:present'),
  update: invoke('ide:update'),
  close: invoke('ide:close'),
  reply: reply => ipcRenderer.send('ide:reply', reply),
  onRequest: listener => subscribe('ide:request', listener),
  onEvent: listener => subscribe('ide:event', listener),
};
contextBridge.exposeInMainWorld('ide', ide);
