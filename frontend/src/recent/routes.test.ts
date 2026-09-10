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
import { recentItemRoute } from './routes';

describe('Recent navigation', () => {
  it('opens the exact task conversation from its item key and workspace', () => {
    const taskId = '622f3ef2-b16a-4b86-9ac7-92e3f80bbc81';
    const route = recentItemRoute({ type: 'TASK', itemKey: `task:${taskId}`, workspaceId: 'workspace/one' });
    expect(workspaceLocationFromRoute(route ?? '')).toMatchObject({
      workspaceId: 'workspace/one', tab: 'tasks', agentTaskId: taskId,
    });
  });

  it('preserves pull-request, issue and commit destinations', () => {
    expect(recentItemRoute({ type: 'PULL_REQUEST', itemKey: 'pr:trinodb/trino#42', workspaceId: null }))
      .toBe('#/pulls/trinodb/trino/42');
    expect(recentItemRoute({ type: 'PULL_REQUEST', itemKey: 'pr:trinodb/trino#42', workspaceId: 'workspace-1' }))
      .toBe('#/workspaces/workspace-1/prs/42');
    expect(recentItemRoute({ type: 'ISSUE', itemKey: 'issue:trinodb/trino#42', workspaceId: 'workspace-1' }))
      .toBe('#/workspaces/workspace-1/issues/42');
    expect(recentItemRoute({ type: 'COMMIT', itemKey: 'commit:trinodb/trino@abc123', workspaceId: 'workspace-1' }))
      .toBe('#/workspaces/workspace-1/commits');
  });

  it('does not send unavailable or malformed tasks to an unrelated task list', () => {
    expect(recentItemRoute({ type: 'TASK', itemKey: 'task:deleted', workspaceId: null })).toBeNull();
    expect(recentItemRoute({ type: 'TASK', itemKey: 'task:', workspaceId: 'workspace-1' })).toBeNull();
    expect(recentItemRoute({ type: 'TASK', itemKey: 'pr:trinodb/trino#42', workspaceId: 'workspace-1' })).toBeNull();
  });
});
