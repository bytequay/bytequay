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
import { describe, expect, it } from 'vitest';
import { workspaceLocationFromRoute } from '../workspaces/model';
import { recentItemTarget } from './routes';
import type { JiraConnection } from '../jira/contracts';

describe('Recent navigation', () => {
  it('reopens tickets by cloud and immutable issue id using a current connection', () => {
    const connection: JiraConnection = { id: 'new-connection', authType: 'api_token', tokenEmail: null, state: 'connected', account: null,
      sites: [{ id: 'cloud-one', name: 'Site', url: 'https://example.atlassian.net' }], authorizationUrl: null, pairingCode: null, error: null, duplicateOf: null };
    const item: Parameters<typeof recentItemTarget>[0] = { type: 'JIRA', itemKey: 'jira:cloud-one:1001', workspaceId: null, repo: null, number: null };
    expect(recentItemTarget(item, [connection])).toEqual({ kind: 'jira', connectionId: 'new-connection', cloudId: 'cloud-one', issueId: '1001' });
    expect(recentItemTarget({ ...item, itemKey: 'jira:cloud-two:1001' }, [connection])).toBeNull();
    expect(recentItemTarget(item, [{ ...connection, state: 'error' }])).toBeNull();
    expect(recentItemTarget({ ...item, itemKey: 'jira:cloud-one:ENG-7' }, [connection])).toBeNull();
  });
  it('opens the exact task conversation from its item key and workspace', () => {
    const taskId = '622f3ef2-b16a-4b86-9ac7-92e3f80bbc81';
    const route = recentItemTarget({ type: 'TASK', itemKey: `task:${taskId}`, workspaceId: 'workspace/one', repo: null, number: null });
    expect(typeof route).toBe('string');
    expect(workspaceLocationFromRoute(typeof route === 'string' ? route : '')).toMatchObject({
      workspaceId: 'workspace/one', tab: 'tasks', agentTaskId: taskId,
    });
  });

  it('zooms pull requests and issues over the page instead of leaving Recent', () => {
    const reference = { repo: 'owner/repository', number: 42 };
    expect(recentItemTarget({ ...reference, type: 'PULL_REQUEST', itemKey: 'pr:owner/repository#42', workspaceId: null }))
      .toEqual({ kind: 'pull', repo: 'owner/repository', number: 42, workspaceId: undefined });
    expect(recentItemTarget({ ...reference, type: 'PULL_REQUEST', itemKey: 'pr:owner/repository#42', workspaceId: 'workspace-1' }))
      .toEqual({ kind: 'pull', repo: 'owner/repository', number: 42, workspaceId: 'workspace-1' });
    expect(recentItemTarget({ ...reference, type: 'ISSUE', itemKey: 'issue:owner/repository#42', workspaceId: 'workspace-1' }))
      .toEqual({ kind: 'issue', repo: 'owner/repository', number: 42, workspaceId: 'workspace-1' });
    // Only a watched repository's issue has a view to zoom.
    expect(recentItemTarget({ ...reference, type: 'ISSUE', itemKey: 'issue:owner/repository#42', workspaceId: null })).toBeNull();
  });

  it('opens a hosted pull request from its repository and number instead of its local cache id', () => {
    expect(recentItemTarget({ type: 'PULL_REQUEST', itemKey: 'pr:123', repo: 'group/subgroup/project', number: 7, workspaceId: 'gitlab-workspace' }))
      .toEqual({ kind: 'pull', repo: 'group/subgroup/project', number: 7, workspaceId: 'gitlab-workspace' });
  });

  it('opens a GitLab issue without including the workspace scope from its key in the repository', () => {
    expect(recentItemTarget({ type: 'ISSUE', itemKey: 'issue:gitlab-workspace:group/subgroup/project#7',
      repo: 'group/subgroup/project', number: 7, workspaceId: 'gitlab-workspace' }))
      .toEqual({ kind: 'issue', repo: 'group/subgroup/project', number: 7, workspaceId: 'gitlab-workspace' });
  });

  it('keeps commits on their workspace page', () => {
    expect(recentItemTarget({ type: 'COMMIT', itemKey: 'commit:owner/repository@abc123', workspaceId: 'workspace-1', repo: 'owner/repository', number: null }))
      .toBe('#/workspaces/workspace-1/commits');
  });

  it('does not send unavailable or malformed tasks to an unrelated task list', () => {
    const reference = { repo: null, number: null };
    expect(recentItemTarget({ ...reference, type: 'TASK', itemKey: 'task:deleted', workspaceId: null })).toBeNull();
    expect(recentItemTarget({ ...reference, type: 'TASK', itemKey: 'task:', workspaceId: 'workspace-1' })).toBeNull();
    expect(recentItemTarget({ ...reference, type: 'TASK', itemKey: 'pr:owner/repository#42', workspaceId: 'workspace-1' })).toBeNull();
  });
});
