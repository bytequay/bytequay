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
import { pinIsWorking, RunningTaskList, runningTaskRoute } from './RunningTasks';
import { parseRunningTasks, visibleRunningTasks, type RunningTask } from './runningTaskModel';

const now = Date.parse('2026-10-01T10:00:00Z');
const task: RunningTask = {
  id: 'one', workspaceId: 'ws-1', title: 'Fix the connector', repository: 'owner/repo', kind: 'TASK', targetId: 'one',
  status: 'RUNNING', startedAt: new Date(now).toISOString(), expiresAt: null, pullRequestNumber: null, branch: null,
};

describe('Working sidebar', () => {
  it('hides empty sections and places newer running tasks above recent completions', () => {
    expect(renderToStaticMarkup(<RunningTaskList tasks={[]} />)).toBe('');
    const older = { ...task, id: 'old', startedAt: new Date(now - 60_000).toISOString() };
    const eightHours = 8 * 60 * 60 * 1000;
    const done: RunningTask = { ...task, id: 'done', status: 'COMPLETED', expiresAt: new Date(now + eightHours).toISOString() };
    const items = visibleRunningTasks([done, older, task], now);
    expect(items.map(item => item.id)).toEqual(['one', 'old', 'done']);
    const html = renderToStaticMarkup(<RunningTaskList tasks={items} />);
    expect(html).toContain('aria-label="Working tasks" aria-expanded="true"');
    expect(html).toContain('>Working</span>');
    expect(html).toContain('is-completed');
    expect(html).toContain('Finished');
    expect(html).toContain('Fix the connector, Running, owner/repo');
    expect(html).toContain('<span class="app-sidebar__label is-working">Fix the connector<span class="app-sidebar__shimmer" aria-hidden="true"><span>Fix the connector</span></span></span></button>');
    expect(html).not.toContain('aria-label="Running"');
    expect(visibleRunningTasks([done], now + eightHours - 1)).toHaveLength(1);
    expect(visibleRunningTasks([done], now + eightHours)).toEqual([]);
    expect(visibleRunningTasks([task], now + eightHours)).toEqual([task]);
  });

  it('keeps failure, stop, approval and recovery statuses distinct', () => {
    const tasks = (['FAILED', 'STOPPED', 'WAITING', 'BLOCKED'] as const).map(status => ({ ...task, id: status, status }));
    const html = renderToStaticMarkup(<RunningTaskList tasks={tasks} />);
    for (const label of ['Failed', 'Stopped', 'Needs you', 'Needs recovery']) expect(html).toContain(label);
    expect(html).not.toContain('Finished');
  });

  it('marks a review with its icon instead of the word', () => {
    const html = renderToStaticMarkup(<RunningTaskList tasks={[{ ...task, kind: 'REVIEW', title: 'Review owner/repository#31710' }]} />);
    expect(html).toContain('lucide-eye');
    expect(html).toContain('>owner/repository#31710</span>');
  });

  it('lights a pinned task, review or pull request only while its work runs', () => {
    const review: RunningTask = { ...task, id: 'review-task', kind: 'REVIEW', targetId: 'review-1', pullRequestNumber: 7 };
    expect(pinIsWorking({ kind: 'task', workspaceId: 'ws-1', taskId: 'one' }, [task])).toBe(true);
    expect(pinIsWorking({ kind: 'task', workspaceId: 'ws-1', taskId: 'one' }, [{ ...task, status: 'WAITING' }])).toBe(false);
    expect(pinIsWorking({ kind: 'review', workspaceId: 'ws-1', reviewId: 'review-1' }, [review])).toBe(true);
    expect(pinIsWorking({ kind: 'pull', repo: 'Owner/Repo', number: 7 }, [review])).toBe(true);
    expect(pinIsWorking({ kind: 'pull', repo: 'owner/other', number: 7 }, [review])).toBe(false);
    expect(pinIsWorking({ kind: 'issue', repo: 'owner/repo', number: 7, workspaceId: 'ws-1' }, [review])).toBe(false);
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
