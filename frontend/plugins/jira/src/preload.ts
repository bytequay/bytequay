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

import type { IpcRenderer } from 'electron';
import type { JiraBridge } from './contracts';

export function createJiraBridge(ipcRenderer: Pick<IpcRenderer, 'invoke'>): JiraBridge {
  return {
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
    recordJiraRecentTouch: (location, dwellSeconds) => ipcRenderer.invoke('jira:issue:recent', location, dwellSeconds),
    refreshJiraIssueByKey: (connectionId, cloudId, key) => ipcRenderer.invoke('jira:key:refresh', connectionId, cloudId, key),
    updateJiraIssue: (location, fields, expectedUpdatedAt) => ipcRenderer.invoke('jira:issue:update', location, fields, expectedUpdatedAt),
    transitionJiraIssue: (location, transitionId, fields, expectedUpdatedAt) => ipcRenderer.invoke('jira:issue:transition', location, transitionId, fields, expectedUpdatedAt),
    moveJiraIssue: (location, sprintId, boardId, expectedUpdatedAt) => ipcRenderer.invoke('jira:issue:sprint', location, sprintId, boardId, expectedUpdatedAt),
    commentJiraIssue: (location, body, operationId, parentId) => ipcRenderer.invoke('jira:issue:comment', location, body, operationId, parentId),
    editJiraComment: (location, commentId, body, expectedUpdated) => ipcRenderer.invoke('jira:comment:edit', location, commentId, body, expectedUpdated),
    setJiraEpic: (location, isEpic) => ipcRenderer.invoke('jira:issue:epic', location, isEpic),
    assignJiraWorkspace: (location, workspaceId) => ipcRenderer.invoke('jira:issue:workspace', location, workspaceId),
    listJiraAttention: () => ipcRenderer.invoke('jira:attention'),
    listJiraLinkTypes: (connectionId, cloudId) => ipcRenderer.invoke('jira:link-types', connectionId, cloudId),
    linkJiraIssue: (location, typeId, outward, key) => ipcRenderer.invoke('jira:issue:link', location, typeId, outward, key),
  };
}
