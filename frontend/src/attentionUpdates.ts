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
import type { PrAttentionUpdate, PullRequestDto } from './types';

/** Each row's JSON as main last sent it to the page, in the order it was sent. */
export type SentAttention = ReadonlyMap<number, string>;

/**
 * What main sends after a poll: every id in order and only the rows that differ from the last update, so thousands of
 * unchanged rows cross to the page as a short id list. Null when nothing changed. The backend builds each row from
 * several tables, so main compares whole rows rather than asking the backend what changed.
 */
export function attentionUpdate(prs: PullRequestDto[], sent: SentAttention): { update: PrAttentionUpdate | null; sent: SentAttention } {
  const rows = new Map<number, string>();
  const changed: PullRequestDto[] = [];
  for (const pr of prs) {
    const json = JSON.stringify(pr);
    rows.set(pr.id, json);
    if (sent.get(pr.id) !== json) changed.push(pr);
  }
  const ids = prs.map(pr => pr.id);
  const previous = [...sent.keys()];
  const reordered = previous.length !== ids.length || previous.some((id, index) => id !== ids[index]);
  return { update: changed.length > 0 || reordered ? { ids, changed } : null, sent: rows };
}

/** The whole list after an update, keeping unchanged rows; null when a row is unknown because an earlier update was missed. */
export function applyAttentionUpdate(shown: ReadonlyMap<number, PullRequestDto>, update: PrAttentionUpdate): PullRequestDto[] | null {
  const changed = new Map(update.changed.map(pr => [pr.id, pr]));
  const rows: PullRequestDto[] = [];
  for (const id of update.ids) {
    const pr = changed.get(id) ?? shown.get(id);
    if (pr === undefined) return null;
    rows.push(pr);
  }
  return rows;
}
