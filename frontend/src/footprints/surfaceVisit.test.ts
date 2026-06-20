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
import type { Nav } from '../App';
import { navToSurfaceVisit, visitKey } from './surfaceVisit';

describe('navToSurfaceVisit', () => {
  it('maps the my-prs inbox to the PR kanban surface', () => {
    expect(navToSurfaceVisit({ view: 'my-prs' })).toEqual({
      surfaceType: 'PR_KANBAN', surfaceId: 'my-prs', title: 'PR kanban', context: null,
    });
  });

  it('maps an individual PR with owner/repo/number in the surfaceId', () => {
    const nav: Nav = { view: 'repo', owner: 'trinodb', repo: 'trino', prNumber: 5680 };
    expect(navToSurfaceVisit(nav)).toEqual({
      surfaceType: 'PR',
      surfaceId: 'trinodb/trino#5680',
      title: 'trinodb/trino #5680',
      context: 'trinodb/trino',
    });
  });

  it('does not track bare repo browsing (no PR number)', () => {
    expect(navToSurfaceVisit({ view: 'repo', owner: 'trinodb', repo: 'trino' })).toBeNull();
  });

  it('maps a task (thread-detail with taskId) to the TASK surface', () => {
    const nav: Nav = { view: 'thread-detail', threadId: 't1', taskId: 'k1' };
    expect(navToSurfaceVisit(nav)).toEqual({
      surfaceType: 'TASK', surfaceId: 't1/k1', title: 'Task', context: 't1',
    });
  });

  it('maps the task-brain view to the same TASK surface as the task page', () => {
    const brain = navToSurfaceVisit({ view: 'task-brain', threadId: 't1', taskId: 'k1' });
    const detail = navToSurfaceVisit({ view: 'thread-detail', threadId: 't1', taskId: 'k1' });
    expect(brain).not.toBeNull();
    expect(visitKey(brain!)).toBe(visitKey(detail!));
  });

  it('maps a thread trunk (no taskId) to the THREAD surface', () => {
    expect(navToSurfaceVisit({ view: 'thread-detail', threadId: 't1' })).toEqual({
      surfaceType: 'THREAD', surfaceId: 't1', title: 'Thread', context: null,
    });
  });

  it('returns null for untracked surfaces', () => {
    expect(navToSurfaceVisit({ view: 'home' })).toBeNull();
    expect(navToSurfaceVisit({ view: 'settings' })).toBeNull();
    expect(navToSurfaceVisit({ view: 'email' })).toBeNull();
  });
});
