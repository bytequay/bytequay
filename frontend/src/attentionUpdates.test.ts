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
import { expect, it } from 'vitest';
import { applyAttentionUpdate, attentionUpdate, type SentAttention } from './attentionUpdates';
import { pr } from './pulls/fixtures/pullRequest';
import type { PullRequestDto } from './types';

it('sends only changed rows and rebuilds the whole list on the page', () => {
  let sent: SentAttention = new Map();
  let shown = new Map<number, PullRequestDto>();
  // Rows cross IPC as copies, so every poll hands main and the page new objects.
  const poll = (prs: PullRequestDto[]) => {
    const next = attentionUpdate(structuredClone(prs), sent);
    sent = next.sent;
    const update = next.update === null ? null : structuredClone(next.update);
    const rows = update === null ? null : applyAttentionUpdate(shown, update);
    if (rows !== null) shown = new Map(rows.map(row => [row.id, row]));
    return { update, rows };
  };
  const one = pr({ id: 1, number: 1 });
  const two = pr({ id: 2, number: 2 });
  const three = pr({ id: 3, number: 3 });

  const first = poll([one, two, three]);
  expect(first.update?.changed.map(row => row.id)).toEqual([1, 2, 3]);
  expect(first.rows).toEqual([one, two, three]);
  const kept = first.rows?.[0];

  expect(poll([one, two, three]).update).toBeNull();

  const failing = { ...two, ciStatus: 'FAILING' as const };
  const changed = poll([one, failing, three]);
  expect(changed.update).toEqual({ ids: [1, 2, 3], changed: [failing] });
  expect(changed.rows).toEqual([one, failing, three]);
  expect(changed.rows?.[0]).toBe(kept);

  const reordered = poll([three, one]);
  expect(reordered.update).toEqual({ ids: [3, 1], changed: [] });
  expect(reordered.rows).toEqual([three, one]);
});

it('reports a missed update instead of dropping rows it does not know', () => {
  const { update } = attentionUpdate([pr({ id: 1 }), pr({ id: 2 })], new Map([[1, JSON.stringify(pr({ id: 1 }))]]));
  expect(update).toEqual({ ids: [1, 2], changed: [pr({ id: 2 })] });
  expect(update && applyAttentionUpdate(new Map(), update)).toBeNull();
});
