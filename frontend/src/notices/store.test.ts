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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { NOTICE_KINDS, parseNotices, type AppNotice } from './contracts';
import { NoticeStore } from './store';

const now = Date.parse('2026-09-27T10:00:00Z');
const notice = (id: string, kind: AppNotice['kind'] = 'TURN_FINISHED', taskId = 'task'): AppNotice => ({
  id, kind, taskId, workspaceId: 'workspace', turnId: 'turn', reviewId: null, title: 'Fix recovery', message: 'Ready', repository: 'owner/repo', laneId: 'codex', occurredAt: new Date(now).toISOString(),
});
let directory: string;
let file: string;
beforeEach(() => {
  directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bytequay-notice-store-'));
  file = path.join(directory, 'dismissed-notices.json');
});
afterEach(() => { vi.restoreAllMocks(); fs.rmSync(directory, { recursive: true, force: true }); });

it('retains recent completions at startup without replaying popups', () => {
  const store = new NoticeStore(() => file);
  const old = notice('old'); const question = notice('question', 'QUESTION', 'other');
  store.update([old, question], now);
  expect(store.snapshot(true)).toMatchObject({ notices: [question, old], popupIds: [] });
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

it('shows development and review completions on the first successful refresh and expires old results', () => {
  const store = new NoticeStore(() => file);
  const development = { ...notice('development'), occurredAt: new Date(now - 4 * 3_600_000).toISOString() };
  const review = { ...notice('review', 'TURN_FINISHED', 'review-task'), reviewId: 'review' };
  const expired = { ...notice('expired', 'TURN_FINISHED', 'old-task'), occurredAt: new Date(now - 86_400_001).toISOString() };
  store.update(parseNotices([development, review, expired]), now);
  expect(store.snapshot(true)).toMatchObject({ notices: [review, development], popupIds: [] });
  store.update([development, review, expired], now + 5000);
  expect(store.snapshot(true).notices).toEqual([review, development]);
  expect(store.snapshot(true).popupIds).toEqual([]);
});

it.each(NOTICE_KINDS)('removes a closed %s notice across refreshes and restarts without suppressing the next turn', kind => {
  const store = new NoticeStore(() => file); store.update([], now);
  const closed = notice('closed', kind);
  const other = notice('other', 'TURN_FINISHED', 'other-task');
  store.update([closed, other], now); store.update([closed, other], now + 10_000);
  store.markViewed(closed.id);
  store.remove(closed.id);
  expect(store.snapshot(true)).toMatchObject({ notices: [other], popupIds: [other.id], viewedIds: [] });
  store.update([closed, other], now + 15_000);
  expect(store.snapshot(true).notices).toEqual([other]);
  store.update([other], now + 20_000);
  store.update([closed, other], now + 25_000);
  expect(store.snapshot(true).notices).toEqual([other]);
  store.remove(other.id);
  expect(store.snapshot(true)).toMatchObject({ notices: [], popupIds: [], viewedIds: [] });
  const restarted = new NoticeStore(() => file);
  restarted.update([closed, other], now + 30_000);
  restarted.update([closed, other], now + 40_000);
  expect(restarted.snapshot(true)).toMatchObject({ notices: [], popupIds: [], viewedIds: [] });
  const next = { ...notice('next'), turnId: 'next-turn' };
  restarted.update([next, other], now + 45_000);
  expect(restarted.snapshot(true)).toMatchObject({ notices: [next], popupIds: [next.id] });
  expect(JSON.parse(fs.readFileSync(file, 'utf8'))).toEqual([closed.id, other.id]);
  expect(fs.statSync(file).mode & 0o777).toBe(0o600);
  expect(fs.existsSync(`${file}.tmp`)).toBe(false);
});

it('persists only explicit removals, leaving timed-out and viewed notices available after restart', () => {
  const store = new NoticeStore(() => file); store.update([], now);
  const timedOut = notice('timed-out');
  const viewed = notice('viewed', 'TURN_FINISHED', 'viewed-task');
  const removed = notice('removed', 'TURN_FINISHED', 'removed-task');
  store.update([timedOut, viewed, removed], now);
  store.dismiss(timedOut.id); store.markViewed(viewed.id); store.remove(removed.id);
  const restarted = new NoticeStore(() => file);
  restarted.update([timedOut, viewed, removed], now);
  expect(restarted.snapshot(true)).toMatchObject({ notices: [timedOut, viewed], popupIds: [], viewedIds: [] });
});

it.each(['writeFileSync', 'renameSync'] as const)('keeps the card and previous dismissals when %s fails, then retries safely', operation => {
  const store = new NoticeStore(() => file); store.update([], now);
  const first = notice('first');
  const second = notice('second', 'TURN_FINISHED', 'second-task');
  store.update([first, second], now); store.remove(first.id);
  const saved = fs.readFileSync(file, 'utf8');
  vi.spyOn(fs, operation).mockImplementationOnce(() => { throw new Error('Disk unavailable'); });
  expect(() => store.remove(second.id)).toThrow('Disk unavailable');
  expect(store.snapshot(true)).toMatchObject({ notices: [second], popupIds: [second.id] });
  expect(fs.readFileSync(file, 'utf8')).toBe(saved);
  store.update([first, second], now);
  expect(store.snapshot(true).notices).toEqual([second]);
  store.remove(second.id);
  const restarted = new NoticeStore(() => file);
  restarted.update([first, second], now);
  expect(restarted.snapshot(true).notices).toEqual([]);
});

it.each(['{', '{}', '[null]', '["valid", 1]', '[""]', '[" "]', JSON.stringify(['x'.repeat(1001)])])('preserves invalid dismissal data instead of overwriting it: %s', saved => {
  fs.writeFileSync(file, saved);
  const store = new NoticeStore(() => file);
  expect(() => store.update([notice('current')], now)).toThrow();
  store.remove('current');
  expect(fs.readFileSync(file, 'utf8')).toBe(saved);
  fs.writeFileSync(file, '["current"]');
  store.update([notice('current')], now);
  expect(store.snapshot(true).notices).toEqual([]);
});

it('retains dismissals after the delivery deduplication cache fills', () => {
  const store = new NoticeStore(() => file); store.update([], now);
  const removed = notice('removed', 'APPROVAL');
  store.update([removed], now); store.remove(removed.id);
  for (let i = 0; i < 4100; i++) store.update([notice(`later-${i}`)], now);
  store.update([removed], now);
  expect(store.snapshot(true).notices).not.toContainEqual(removed);
  const restarted = new NoticeStore(() => file);
  restarted.update([removed], now);
  expect(restarted.snapshot(true).notices).toEqual([]);
});

it('replaces a completion with its question and prioritizes requests', () => {
  const store = new NoticeStore(() => file); store.update([], now);
  store.update([notice('done'), notice('other', 'TURN_FINISHED', 'other')], now);
  const question = notice('question', 'QUESTION');
  store.update([question, notice('other', 'TURN_FINISHED', 'other')], now);
  expect(store.snapshot(true).notices.map(item => item.id)).toEqual(['question', 'other']);
  store.dismiss('question');
  expect(store.snapshot(true).notices[0]).toEqual(question);
  expect(store.snapshot(true).popupIds).toEqual(['other']);
});

it('waits for shutdown to remain blocked, without replaying startup or transient recovery', () => {
  const store = new NoticeStore(() => file); store.update([], now);
  const blocked = notice('blocked', 'RECOVERY');
  store.update([blocked], now); store.update([blocked], now + 5000);
  expect(store.snapshot(true).notices).toEqual([]);
  store.update([], now + 8000); store.update([blocked], now + 9000); store.update([blocked], now + 19000);
  expect(store.snapshot(true).popupIds).toEqual(['blocked']);
  store.update([], now + 20000);
  expect(store.snapshot(true).notices).toEqual([]);
  const startup = new NoticeStore(() => file); startup.update([blocked], now); startup.update([blocked], now + 10000);
  expect(startup.snapshot(true)).toMatchObject({ notices: [blocked], popupIds: [] });
});

it('bounds recent completions, retains actionable requests, and keeps background popups pending', () => {
  const store = new NoticeStore(() => file); store.update([], now);
  const waiting = notice('approval', 'APPROVAL');
  store.update([waiting, ...Array.from({ length: 70 }, (_, i) => notice(`done-${i}`, 'TURN_FINISHED', `t-${i}`))], now);
  expect(store.snapshot(false).notices).toHaveLength(51);
  expect(store.snapshot(false).active).toBe(false);
  expect(store.snapshot(true).popupIds).toContain('approval');
  store.update([waiting], now + 86_400_001);
  expect(store.snapshot(true).notices).toEqual([waiting]);
  store.update([waiting, notice('unseen-old-completion')], now + 86_400_001);
  expect(store.snapshot(true).notices).toEqual([waiting]);
});

it('rejects malformed data at the desktop boundary', () => {
  expect(parseNotices([notice('one')])).toEqual([notice('one')]);
  for (const value of [{}, [notice('one'), notice('one')], [{ ...notice('one'), kind: 'RAW_ERROR' }], [{ ...notice('one'), kind: 'MAIL' }], [{ ...notice('one'), occurredAt: 'unknown' }]]) {
    expect(() => parseNotices(value)).toThrow();
  }
  for (const reviewId of [undefined, '', ' ', 1, 'r'.repeat(201)]) expect(() => parseNotices([{ ...notice('one'), reviewId }])).toThrow();
});
