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

import type { IpcMainInvokeEvent } from 'electron';
import { describe, expect, it, vi } from 'vitest';
import type { JiraLocation } from './contracts';
import { registerJiraIpc, type JiraMainHost } from './main';
import { createJiraBridge } from './preload';

function connect() {
  const handlers = new Map<string, Parameters<JiraMainHost['ipc']['handle']>[1]>();
  const requests = vi.fn<[path: string, init?: RequestInit], unknown>(() => ({ ok: true }));
  registerJiraIpc({
    ipc: { handle: (channel, handler) => { handlers.set(channel, handler); } },
    backend: async <T,>(path: string, init?: RequestInit): Promise<T> => requests(path, init) as T,
  });
  const bridge = createJiraBridge({ invoke: async (channel, ...args: unknown[]) => {
    const handler = handlers.get(channel);
    if (!handler) throw new Error(`Unregistered Jira channel: ${channel}`);
    return handler({} as IpcMainInvokeEvent, ...args);
  } });
  return { bridge, requests };
}

const location: JiraLocation = { connectionId: 'account / one', cloudId: 'site?two', issueId: 'issue#3' };
const issuePath = '/api/jira/connections/account%20%2F%20one/sites/site%3Ftwo/issues/issue%233';

describe('Jira desktop boundary', () => {
  it('keeps encoded identity and cache/automatic refresh semantics through preload and main', async () => {
    const { bridge, requests } = connect();
    await bridge.getJiraIssue(location);
    expect(requests).toHaveBeenLastCalledWith(issuePath, { method: 'GET' });
    await bridge.getJiraIssue(location, true, true);
    expect(requests).toHaveBeenLastCalledWith(`${issuePath}/refresh?automatic=true`, { method: 'POST' });
    await bridge.listJiraIssues({ ...location, boardId: 7, sprintId: null, mine: false, coassigned: true, completed: true, nextPageToken: 'a+b / c' }, true);
    const [path, init] = requests.mock.calls[requests.mock.calls.length - 1];
    const query = new URL(path, 'http://localhost').searchParams;
    expect(path.split('?')[0]).toBe('/api/jira/connections/account%20%2F%20one/sites/site%3Ftwo/issues/refresh');
    expect(Object.fromEntries(query)).toEqual({ boardId: '7', mine: 'false', coassigned: 'true', completed: 'true', nextPageToken: 'a+b / c' });
    expect(init?.method).toBe('POST');
    expect(init?.signal).toBeInstanceOf(AbortSignal);
  });

  it('preserves stale-write and retry identity across comment and transition requests', async () => {
    const { bridge, requests } = connect();
    await bridge.transitionJiraIssue(location, 'done', { resolution: { id: 'fixed' } }, 'version-1');
    expect(requests).toHaveBeenLastCalledWith(`${issuePath}/transitions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ transitionId: 'done', fields: { resolution: { id: 'fixed' } }, expectedUpdatedAt: 'version-1' }),
    });
    await bridge.commentJiraIssue(location, 'Reply', 'stable-operation', 'comment/parent');
    expect(requests).toHaveBeenLastCalledWith(`${issuePath}/comments`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: 'Reply', operationId: 'stable-operation', parentId: 'comment/parent' }),
    });
    await bridge.editJiraComment(location, 'comment/one', 'Edited', 'version-2');
    expect(requests).toHaveBeenLastCalledWith(`${issuePath}/comments/comment%2Fone`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: 'Edited', expectedUpdated: 'version-2' }),
    });
  });

  it('reads assigned workspace tickets independently of board filters', async () => {
    const { bridge, requests } = connect();
    await bridge.listWorkspaceJiraIssues('workspace / one');
    expect(requests).toHaveBeenLastCalledWith('/api/jira/workspaces/workspace%20%2F%20one/issues', { method: 'GET' });
    await bridge.listWorkspaceJiraIssues('workspace / one', true);
    expect(requests).toHaveBeenLastCalledWith('/api/jira/workspaces/workspace%20%2F%20one/issues/refresh', { method: 'POST', signal: expect.any(AbortSignal) });
  });
});
