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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { RunningTaskList, runningTaskRoute } from './RunningTasks';
import { parseRunningTasks, visibleRunningTasks, type RunningTask } from './runningTaskModel';

const now = Date.parse('2026-10-01T10:00:00Z');
const task: RunningTask = {
  id: 'one', workspaceId: 'ws-1', title: 'Fix the connector', repository: 'owner/repo', kind: 'TASK', targetId: 'one',
  status: 'RUNNING', startedAt: new Date(now).toISOString(), expiresAt: null, pullRequestNumber: null, branch: null,
};

describe('Running sidebar', () => {
  it('hides empty sections and places newer running tasks above recent completions', () => {
    expect(renderToStaticMarkup(<RunningTaskList tasks={[]} />)).toBe('');
    const older = { ...task, id: 'old', startedAt: new Date(now - 60_000).toISOString() };
    const done: RunningTask = { ...task, id: 'done', status: 'COMPLETED', expiresAt: new Date(now + 600_000).toISOString() };
    const items = visibleRunningTasks([done, older, task], now);
    expect(items.map(item => item.id)).toEqual(['one', 'old', 'done']);
    const html = renderToStaticMarkup(<RunningTaskList tasks={items} />);
    expect(html).toContain('aria-label="Running tasks" aria-expanded="true"');
    expect(html).toContain('is-completed');
    expect(html).toContain('Finished');
    expect(html).toContain('Fix the connector, Running, owner/repo');
    expect(html).toMatch(/Fix the connector<\/span><span class="app-sidebar__running-status" role="status" aria-label="Running">/);
    expect(visibleRunningTasks([done], now + 599_999)).toHaveLength(1);
    expect(visibleRunningTasks([done], now + 600_000)).toEqual([]);
    expect(visibleRunningTasks([task], now + 600_000)).toEqual([task]);
  });

  it('keeps failure, stop, approval and recovery statuses distinct', () => {
    const tasks = (['FAILED', 'STOPPED', 'WAITING', 'BLOCKED'] as const).map(status => ({ ...task, id: status, status }));
    const html = renderToStaticMarkup(<RunningTaskList tasks={tasks} />);
    for (const label of ['Failed', 'Stopped', 'Needs you', 'Needs recovery']) expect(html).toContain(label);
    expect(html).not.toContain('Finished');
  });

  it('marks a review with its icon instead of the word', () => {
    const html = renderToStaticMarkup(<RunningTaskList tasks={[{ ...task, kind: 'REVIEW', title: 'Review trinodb/trino#31710' }]} />);
    expect(html).toContain('lucide-eye');
    expect(html).toContain('>trinodb/trino#31710</span>');
  });

  it('opens each kind in its owning workspace', () => {
    expect(runningTaskRoute(task)).toBe('#/workspaces/ws-1/tasks/agent/one');
    expect(runningTaskRoute({ ...task, kind: 'REVIEW', targetId: 'review-1', workspaceId: 'ws-2' })).toBe('#/workspaces/ws-2/tasks/review/review-1');
    expect(runningTaskRoute({ ...task, kind: 'CHERRY_PICK', targetId: 'pick-1' })).toBe('#/workspaces/ws-1/tasks/cherry/pick-1');
    expect(runningTaskRoute({ ...task, kind: 'CI_FIX', targetId: '42' })).toBe('#/workspaces/ws-1/prs/42');
  });

  it('validates the IPC boundary and strips fields outside the sidebar contract', () => {
    expect(parseRunningTasks([{ ...task, runtimePath: '/private/path' }])).toEqual([task]);
    for (const input of [null, [{}], [{ ...task, status: 'IDLE' }], [{ ...task, startedAt: 'invalid' }], [{ ...task, expiresAt: 500 }],
      [{ ...task, kind: 'CI_FIX', targetId: 'invalid' }], [{ ...task, pullRequestNumber: 0 }], [{ ...task, branch: ' ' }], [{ ...task, branch: undefined }]]) {
      expect(() => parseRunningTasks(input)).toThrow();
    }
  });
});
