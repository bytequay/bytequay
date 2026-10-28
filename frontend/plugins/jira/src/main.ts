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

import type { IpcMain } from 'electron';
import type { JiraLocation, JiraQuery, JiraTokenRequest } from './contracts';

export type JiraMainHost = {
  ipc: Pick<IpcMain, 'handle'>;
  backend: <T>(path: string, init?: RequestInit) => Promise<T>;
};

const jsonInit = (method: string, body: unknown): RequestInit => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

export function registerJiraIpc({ ipc, backend }: JiraMainHost): void {
  const jiraSitePath = (connectionId: string, cloudId: string) => `/api/jira/connections/${encodeURIComponent(connectionId)}/sites/${encodeURIComponent(cloudId)}`;
  const jiraIssuePath = (location: JiraLocation) => `${jiraSitePath(location.connectionId, location.cloudId)}/issues/${encodeURIComponent(location.issueId)}`;
  ipc.handle('jira:config', () => backend(`/api/jira/config`));
  ipc.handle('jira:reconnect', (_event, id: string) => backend(`/api/jira/connections/${encodeURIComponent(id)}/reconnect`, { method: 'POST' }));
  ipc.handle('jira:connections', () => backend(`/api/jira/connections`));
  ipc.handle('jira:connect', () => backend(`/api/jira/connections`, { method: 'POST' }));
  ipc.handle('jira:token:connect', (_event, request: JiraTokenRequest) => backend(`/api/jira/connections/token`, jsonInit('POST', request)));
  ipc.handle('jira:token:replace', (_event, id: string, request: JiraTokenRequest) => backend(`/api/jira/connections/${encodeURIComponent(id)}/token`, jsonInit('PUT', request)));
  ipc.handle('jira:connection:refresh', (_event, id: string) => backend(`/api/jira/connections/${encodeURIComponent(id)}/refresh`, { method: 'POST' }));
  ipc.handle('jira:disconnect', (_event, id: string) => backend(`/api/jira/connections/${encodeURIComponent(id)}`, { method: 'DELETE' }));
  ipc.handle('jira:boards', (_event, connectionId: string, cloudId: string, refresh: boolean) =>
    backend(`${jiraSitePath(connectionId, cloudId)}/boards${refresh ? '/refresh' : ''}`, { method: refresh ? 'POST' : 'GET' }));
  ipc.handle('jira:sprints', (_event, connectionId: string, cloudId: string, boardId: number, refresh: boolean) =>
    backend(`${jiraSitePath(connectionId, cloudId)}/boards/${encodeURIComponent(String(boardId))}/sprints${refresh ? '/refresh' : ''}`, { method: refresh ? 'POST' : 'GET' }));
  ipc.handle('jira:issues', (_event, query: JiraQuery, refresh: boolean) => {
    const params = new URLSearchParams({ boardId: String(query.boardId), mine: String(query.mine) });
    if (query.sprintId !== null) params.set('sprintId', String(query.sprintId));
    if (query.coassigned) params.set('coassigned', 'true');
    if (query.completed) params.set('completed', 'true');
    if (query.nextPageToken !== undefined) params.set('nextPageToken', query.nextPageToken);
    return backend(`${jiraSitePath(query.connectionId, query.cloudId)}/issues${refresh ? '/refresh' : ''}?${params}`, {
      method: refresh ? 'POST' : 'GET',
      // A page can read custom fields, tickets, and descriptions, with OAuth token refreshes.
      ...(refresh ? { signal: AbortSignal.timeout(3 * 60_000) } : {}),
    });
  });
  ipc.handle('jira:workspace:issues', (_event, workspaceId: string, refresh: boolean) =>
    backend(`/api/jira/workspaces/${encodeURIComponent(workspaceId)}/issues${refresh ? '/refresh' : ''}`, {
      method: refresh ? 'POST' : 'GET', ...(refresh ? { signal: AbortSignal.timeout(3 * 60_000) } : {}),
    }));
  ipc.handle('jira:issue', (_event, location: JiraLocation, refresh: boolean, automatic: boolean) =>
    backend(`${jiraIssuePath(location)}${refresh ? '/refresh' : ''}${refresh && automatic ? '?automatic=true' : ''}`, { method: refresh ? 'POST' : 'GET' }));
  ipc.handle('jira:key:refresh', (_event, connectionId: string, cloudId: string, key: string) =>
    backend(`${jiraSitePath(connectionId, cloudId)}/keys/${encodeURIComponent(key)}/refresh`, { method: 'POST' }));
  ipc.handle('jira:issue:recent', (_event, location: JiraLocation, dwellSeconds: number) =>
    backend(`${jiraIssuePath(location)}/recent`, jsonInit('POST', { dwellSeconds })));
  ipc.handle('jira:issue:update', (_event, location: JiraLocation, fields: Record<string, unknown>, expectedUpdatedAt: string) =>
    backend(jiraIssuePath(location), jsonInit('PATCH', { fields, expectedUpdatedAt })));
  ipc.handle('jira:issue:transition', (_event, location: JiraLocation, transitionId: string, fields: Record<string, unknown>, expectedUpdatedAt: string) =>
    backend(`${jiraIssuePath(location)}/transitions`, jsonInit('POST', { transitionId, fields, expectedUpdatedAt })));
  ipc.handle('jira:issue:sprint', (_event, location: JiraLocation, sprintId: number | null, boardId: number, expectedUpdatedAt: string) =>
    backend(`${jiraIssuePath(location)}/sprint`, jsonInit('POST', { sprintId, boardId, expectedUpdatedAt })));
  ipc.handle('jira:issue:comment', (_event, location: JiraLocation, body: string, operationId: string, parentId?: string) =>
    backend(`${jiraIssuePath(location)}/comments`, jsonInit('POST', { body, operationId, parentId: parentId ?? null })));
  ipc.handle('jira:comment:edit', (_event, location: JiraLocation, commentId: string, body: string, expectedUpdated: string) =>
    backend(`${jiraIssuePath(location)}/comments/${encodeURIComponent(commentId)}`, jsonInit('PUT', { body, expectedUpdated })));
  ipc.handle('jira:issue:epic', (_event, location: JiraLocation, isEpic: boolean) =>
    backend(`${jiraIssuePath(location)}/epic`, jsonInit('PUT', { isEpic })));
  ipc.handle('jira:issue:workspace', (_event, location: JiraLocation, workspaceId: string) =>
    backend(`${jiraIssuePath(location)}/workspace`, jsonInit('PUT', { workspaceId })));
  ipc.handle('jira:attention', () => backend(`/api/jira/attention`));
  ipc.handle('jira:link-types', (_event, connectionId: string, cloudId: string) =>
    backend(`${jiraSitePath(connectionId, cloudId)}/link-types`, { method: 'POST' }));
  ipc.handle('jira:issue:link', (_event, location: JiraLocation, typeId: string, outward: boolean, key: string) =>
    backend(`${jiraIssuePath(location)}/links`, jsonInit('POST', { typeId, outward, key })));
}
