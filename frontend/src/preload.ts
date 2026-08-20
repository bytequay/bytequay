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
import type { Bridge, InAppNavState } from './types';

const bridge: Bridge = {
  savePat: pat => ipcRenderer.invoke('pat:save', pat),
  hasPat: () => ipcRenderer.invoke('pat:has'),
  fetchPrs: () => ipcRenderer.invoke('prs:list'),
  syncPrs: () => ipcRenderer.invoke('prs:sync'),
  fetchPullRequestDetail: (repo, number) => ipcRenderer.invoke('prs:detail', repo, number),
  fetchRepoUsers: repo => ipcRenderer.invoke('prs:users', repo),
  fetchMergeMode: (repo, branch) => ipcRenderer.invoke('prs:merge-mode', repo, branch),
  refreshPullRequestDetail: (repo, number) =>
    ipcRenderer.invoke('prs:detail:refresh', repo, number),
  fetchPrDiffFiles: (repo, number) => ipcRenderer.invoke('prs:diff', repo, number),
  commentPr: (repo, number, body) => ipcRenderer.invoke('prs:comment', repo, number, body),
  replyToReviewComment: (repo, number, commentId, body) =>
    ipcRenderer.invoke('prs:review-comment:reply', repo, number, commentId, body),
  approvePr: (prId, repo, number) => ipcRenderer.invoke('prs:approve', prId, repo, number),
  mergePr: (prId, repo, number, strategy) =>
    ipcRenderer.invoke('prs:merge', prId, repo, number, strategy),

  getWatchedRepos: () => ipcRenderer.invoke('repos:list'),
  addWatchedRepo: (owner, repo) => ipcRenderer.invoke('repos:add', owner, repo),
  removeWatchedRepo: (owner, repo) => ipcRenderer.invoke('repos:remove', owner, repo),
  getUserProfile: () => ipcRenderer.invoke('repos:profile'),
  getContributionCalendar: login => ipcRenderer.invoke('repos:contribution', login),
  getUserCommitsOnDate: (login, date) => ipcRenderer.invoke('repos:contribution-day', login, date),
  getRepoPull: (owner, repo, number) => ipcRenderer.invoke('repos:pull', owner, repo, number),
  getRepoMeta: (owner, repo) => ipcRenderer.invoke('repos:meta', owner, repo),
  getUserRepos: () => ipcRenderer.invoke('repos:user'),
  searchRepos: query => ipcRenderer.invoke('repos:search', query),
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

  previewCherryPickRange: request => ipcRenderer.invoke('cherrypick:preview', request),
  getCherryPickPreview: previewId => ipcRenderer.invoke('cherrypick:preview:get', previewId),
  getAgentProviderStatuses: () => ipcRenderer.invoke('cherrypick:providers'),
  startCherryPickExecution: request => ipcRenderer.invoke('cherrypick:execution:start', request),
  getCherryPickExecution: executionId => ipcRenderer.invoke('cherrypick:execution:get', executionId),
  sendCherryPickMessage: (executionId, message) =>
    ipcRenderer.invoke('cherrypick:execution:message', executionId, message),
  cancelCherryPickExecution: (executionId, repository) =>
    ipcRenderer.invoke('cherrypick:execution:cancel', executionId, repository),
  chooseDirectory: title => ipcRenderer.invoke('cherrypick:choose-directory', title),
};

contextBridge.exposeInMainWorld('bridge', bridge);
