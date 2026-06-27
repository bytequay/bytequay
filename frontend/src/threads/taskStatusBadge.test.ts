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
import { headlineStatus, taskStatusBadge } from './taskStatusBadge';

describe('taskStatusBadge', () => {
  it('reads COMPLETED as a green Done (not "shipped")', () => {
    expect(taskStatusBadge('COMPLETED')).toEqual({ label: 'Done', tone: 'done' });
  });
  it('marks a running task orange and review states blue', () => {
    expect(taskStatusBadge('RUNNING').tone).toBe('running');
    expect(taskStatusBadge('IN_REVIEW').tone).toBe('review');
    expect(taskStatusBadge('AWAITING_REVIEW').tone).toBe('review');
  });
  it('flags needs-attention and errored as attention', () => {
    expect(taskStatusBadge('NEEDS_ATTENTION').tone).toBe('attention');
    expect(taskStatusBadge('ERRORED').tone).toBe('attention');
  });
});

describe('headlineStatus', () => {
  it('surfaces the most salient status across a thread', () => {
    expect(headlineStatus(['COMPLETED', 'RUNNING'])).toBe('RUNNING');
    expect(headlineStatus(['IN_REVIEW', 'NEEDS_ATTENTION'])).toBe('NEEDS_ATTENTION');
  });
  it('reports Done when every task is finished', () => {
    expect(headlineStatus(['COMPLETED', 'COMPLETED'])).toBe('COMPLETED');
  });
  it('is null for a thread with no tasks', () => {
    expect(headlineStatus([])).toBeNull();
  });
});
