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
import type { BacklogItemDto } from '../../types';
import { pickTopBacklog } from './BacklogPrompt';

function item(id: string, priority: string, status: string, createdAt: number): BacklogItemDto {
  return { id, title: id, body: '', tags: [], priority, source: 'trunk-split', status, createdAt } as unknown as BacklogItemDto;
}

describe('pickTopBacklog', () => {
  const backlog = [
    item('old-low', 'low', 'created', 100),
    item('new-high', 'high', 'created', 300),
    item('old-high', 'high', 'created', 200),
    item('done', 'high', 'resolved', 50),
  ];

  it('picks highest priority, then oldest, among unstarted items', () => {
    // old-high beats new-high (same priority, older); both beat old-low; the
    // resolved item is ignored.
    expect(pickTopBacklog(backlog, false, false)?.id).toBe('old-high');
  });

  it('offers nothing while a task is active', () => {
    expect(pickTopBacklog(backlog, true, false)).toBeUndefined();
  });

  it('offers nothing once the prompt is ignored', () => {
    expect(pickTopBacklog(backlog, false, true)).toBeUndefined();
  });

  it('offers nothing when no item is still in created', () => {
    expect(pickTopBacklog([item('done', 'high', 'resolved', 1)], false, false)).toBeUndefined();
  });
});
