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
import type { WorkUnitTaskDto } from '../types';
import { toTaskCard } from './taskCardData';

function task(over: Partial<WorkUnitTaskDto> = {}): WorkUnitTaskDto {
  return {
    id: 't1', name: 'Replace two lambdas', branchName: 'dev/replace-two-lambdas',
    status: 'IN_REVIEW', prNumber: 42, prState: 'OPEN',
    createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    ...over,
  } as unknown as WorkUnitTaskDto;
}

describe('toTaskCard', () => {
  it('carries the PR number, branch, created time, and shipped status', () => {
    const c = toTaskCard(task(), false);
    expect(c.prNumber).toBe(42);
    expect(c.branch).toBe('dev/replace-two-lambdas');
    expect(c.status).toBe('shipped');
    expect(c.createdLabel).toBeDefined();
    expect(c.mergeReady).toBe(false);
    expect(c.pr).toBe('open');
  });

  it('reflects the caller-supplied merge-ready flag (kept in sync across surfaces)', () => {
    expect(toTaskCard(task(), true).mergeReady).toBe(true);
  });

  it('omits the PR number and glyph before a PR exists', () => {
    const c = toTaskCard(task({ prNumber: null, prState: null, status: 'RUNNING' }), false);
    expect(c.prNumber).toBeUndefined();
    expect(c.pr).toBeUndefined();
  });
});
