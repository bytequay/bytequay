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
import { parseNotices, type MailNotice, type TaskNotice } from './contracts';
import { NoticeStore } from './store';

const now = Date.parse('2026-09-27T10:00:00Z');
const notice = (id: string, kind: TaskNotice['kind'] = 'TURN_FINISHED', taskId = 'task'): TaskNotice => ({
  id, kind, taskId, workspaceId: 'workspace', turnId: 'turn', title: 'Fix recovery', message: 'Ready', repository: 'owner/repo', laneId: 'codex', occurredAt: new Date(now).toISOString(),
});

it('baselines startup, retains unresolved requests, and never replays dismissed popups', () => {
  const store = new NoticeStore();
  const old = notice('old'); const question = notice('question', 'QUESTION', 'other');
  store.update([old, question], now);
  expect(store.snapshot(true)).toMatchObject({ notices: [question], popupIds: [] });
  const fresh = notice('new'); store.update([fresh, question], now);
  expect(store.snapshot(true).popupIds).toEqual(['new']);
  store.dismiss('new'); store.update([fresh, question], now);
  expect(store.snapshot(true).popupIds).toEqual([]);
  store.update([fresh], now);
  expect(store.snapshot(true).notices).toEqual([fresh]);
  store.update([fresh, question], now);
  expect(store.snapshot(true).popupIds).toEqual([]);
  expect(store.snapshot(true).notices).toContainEqual(question);
});

it('replaces a completion with its question and prioritizes requests', () => {
  const store = new NoticeStore(); store.update([], now);
  store.update([notice('done'), notice('other', 'TURN_FINISHED', 'other')], now);
  const question = notice('question', 'QUESTION');
  store.update([question, notice('other', 'TURN_FINISHED', 'other')], now);
  expect(store.snapshot(true).notices.map(item => item.id)).toEqual(['question', 'other']);
  store.dismiss('question');
  expect(store.snapshot(true).notices[0]).toEqual(question);
  expect(store.snapshot(true).popupIds).toEqual(['other']);
});

it('waits for shutdown to remain blocked, without replaying startup or transient recovery', () => {
  const store = new NoticeStore(); store.update([], now);
  const blocked = notice('blocked', 'RECOVERY');
  store.update([blocked], now); store.update([blocked], now + 5000);
  expect(store.snapshot(true).notices).toEqual([]);
  store.update([], now + 8000); store.update([blocked], now + 9000); store.update([blocked], now + 19000);
  expect(store.snapshot(true).popupIds).toEqual(['blocked']);
  store.update([], now + 20000);
  expect(store.snapshot(true).notices).toEqual([]);
  const startup = new NoticeStore(); startup.update([blocked], now); startup.update([blocked], now + 10000);
  expect(startup.snapshot(true)).toMatchObject({ notices: [blocked], popupIds: [] });
});

it('bounds recent completions, retains actionable requests, and keeps background popups pending', () => {
  const store = new NoticeStore(); store.update([], now);
  const waiting = notice('approval', 'APPROVAL');
  store.update([waiting, ...Array.from({ length: 70 }, (_, i) => notice(`done-${i}`, 'TURN_FINISHED', `t-${i}`))], now);
  expect(store.snapshot(false).notices).toHaveLength(51);
  expect(store.snapshot(false).active).toBe(false);
  expect(store.snapshot(true).popupIds).toContain('approval');
  store.update([waiting], now + 3_600_001);
  expect(store.snapshot(true).notices).toEqual([waiting]);
  store.update([waiting, notice('unseen-old-completion')], now + 3_600_001);
  expect(store.snapshot(true).notices).toEqual([waiting]);
});

it('rejects malformed data at the desktop boundary', () => {
  expect(parseNotices([notice('one')])).toEqual([notice('one')]);
  for (const value of [{}, [notice('one'), notice('one')], [{ ...notice('one'), kind: 'RAW_ERROR' }], [{ ...notice('one'), occurredAt: 'unknown' }]]) {
    expect(() => parseNotices(value)).toThrow();
  }
});

it('keeps mail distinct by message and account, suppresses duplicates, and clears read mail', () => {
  const mail: MailNotice = { id: 'mail:account:one', kind: 'MAIL', profile: 'account', messageId: 'one', threadId: 'thread',
    title: 'Hello', message: 'A new message', sender: 'Alex', email: 'me@example.com', occurredAt: new Date(now).toISOString() };
  const other = { ...mail, id: 'mail:other:one', profile: 'other', email: 'other@example.com' };
  const next = { ...mail, id: 'mail:account:two', messageId: 'two' };
  const task = notice('question', 'QUESTION');
  const store = new NoticeStore(); store.update([], now);
  store.update(parseNotices([mail, other, next, task]), now);
  expect(store.snapshot(true).notices).toHaveLength(4);
  expect(store.snapshot(true).popupIds).toHaveLength(4);
  store.dismiss(mail.id); store.update([mail, other, next, task], now + 5000);
  expect(store.snapshot(true).popupIds).not.toContain(mail.id);
  store.update([next, task], now + 10000);
  expect(store.snapshot(true).notices).toHaveLength(2);
  for (const invalid of [{ ...mail, profile: '' }, { ...mail, messageId: null }, { ...mail, threadId: {} }]) {
    expect(() => parseNotices([invalid])).toThrow();
  }
});
