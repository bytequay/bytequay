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
import { describe, expect, it, vi } from 'vitest';
import type { TaskDiscussionDto } from '../agents/discussions/contracts';
import type { PrivateThreadSource } from './PrivateThread';
import { mergeReviewCommentHistory, type ReviewCommentHistory } from './useReviewCommentHistory';

const KEY = 'review_comment:4305473611';
const AT = '2026-10-18T01:00:00Z';

function thread(id = 'original', overrides: Partial<TaskDiscussionDto> = {}): TaskDiscussionDto {
  return {
    id, taskId: 'review-task', turnId: 'review-comment', quote: KEY, occurrence: 1, resolved: false, pending: false,
    providerLane: 'claude', version: 4, createdAt: AT, updatedAt: AT,
    comment: { repo: 'owner/repository', number: 31621, key: KEY }, replyDraft: null, replyDraftError: null,
    messages: [
      { id: 'q1', author: 'USER', body: 'Should this use BaseConnectorTest?', createdAt: AT, error: null },
      { id: 'a1', author: 'AGENT', body: 'Yes, with the Hive-specific cases retained.', createdAt: AT, error: null },
      { id: 'q2', author: 'USER', body: 'Why retain them?', createdAt: AT, error: null },
      { id: 'a2', author: 'AGENT', body: 'They cover additional Hive validation paths.', createdAt: AT, error: null },
    ],
    ...overrides,
  };
}

function history(id = 'original-review', discussions = [thread()], state: 'OPEN' | 'CLOSED' = 'OPEN'): ReviewCommentHistory {
  return { review: { id, state, task: { id: `${id}-task`, state: 'OPEN' } }, discussions };
}

function fixture(values: TaskDiscussionDto[] = []) {
  const source: PrivateThreadSource = {
    threads: new Map(values.flatMap(value => value.comment === null ? [] : [[value.comment.key, value] as const])),
    busy: false, busyReason: 'Current task is busy', disabledReason: null,
    ask: vi.fn(async () => {}), resolve: vi.fn(async () => {}), retry: vi.fn(async () => {}), draftReply: vi.fn(async () => 'Current draft'),
  };
  const bridge = {
    askReviewCommentThread: vi.fn(async () => thread()), resolveReviewDiscussion: vi.fn(async () => thread()),
    retryReviewDiscussion: vi.fn(async () => thread()), draftReviewCommentReply: vi.fn(async () => 'Original draft'),
  };
  const refresh = vi.fn(async () => {});
  return { source, bridge, refresh };
}

describe('retained review comment history', () => {
  it('keeps the original four messages when a later development task and task-bound review have none', async () => {
    const { source, bridge, refresh } = fixture();
    const value = mergeReviewCommentHistory(source, [history('new-task-review', []), history()], null, refresh, bridge);
    expect(value.threads.get(KEY)?.messages).toEqual(thread().messages);
    await value.ask(KEY, 'Would the nested case still be Hive-specific?');
    expect(bridge.askReviewCommentThread).toHaveBeenCalledWith('original-review', KEY, 'Would the nested case still be Hive-specific?');
    expect(source.ask).not.toHaveBeenCalled();
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('routes retry, resolution, and reply drafting through the review that owns the history', async () => {
    const { source, bridge, refresh } = fixture();
    const saved = thread();
    const value = mergeReviewCommentHistory(source, [history('original-review', [saved])], null, refresh, bridge);
    await value.retry(saved, 'q2');
    await value.resolve(saved, true);
    expect(await value.draftReply(saved)).toBe('Original draft');
    expect(bridge.retryReviewDiscussion).toHaveBeenCalledWith('original-review', saved.id, 'q2');
    expect(bridge.resolveReviewDiscussion).toHaveBeenCalledWith('original-review', saved.id, true, saved.version);
    expect(bridge.draftReviewCommentReply).toHaveBeenCalledWith('original-review', saved.id);
    expect(refresh).toHaveBeenCalledTimes(3);
  });

  it('keeps the current source preferred and preserves distinct earlier histories without duplicates', async () => {
    const current = thread('current', { taskId: 'development-task' });
    const { source, bridge, refresh } = fixture([current]);
    const value = mergeReviewCommentHistory(source, [history('duplicate-source', [current]), history()], null, refresh, bridge);
    expect(value.threads.get(KEY)).toBe(current);
    expect(value.earlierThreads?.get(KEY)?.map(saved => saved.id)).toEqual(['original']);
    await value.ask(KEY, 'Continue here');
    await value.ask('review_comment:2', 'New comment');
    await value.resolve(current, true);
    expect(source.ask).toHaveBeenNthCalledWith(1, KEY, 'Continue here');
    expect(source.ask).toHaveBeenNthCalledWith(2, 'review_comment:2', 'New comment');
    expect(source.resolve).toHaveBeenCalledWith(current, true);
    expect(bridge.askReviewCommentThread).not.toHaveBeenCalled();
  });

  it('continues the oldest nonempty thread regardless of review listing order', () => {
    const { source, bridge, refresh } = fixture();
    const newer = thread('newer', { createdAt: '2026-10-19T01:00:00Z' });
    const empty = thread('empty', { createdAt: '2026-10-17T01:00:00Z', messages: [] });
    const value = mergeReviewCommentHistory(source, [history('newer-review', [newer]), history('empty-review', [empty]), history()], null, refresh, bridge);
    expect(value.threads.get(KEY)?.id).toBe('original');
    expect(value.earlierThreads?.get(KEY)?.map(saved => saved.id)).toEqual(['newer']);
  });

  it('shows retained history instead of an empty thread left by an interrupted first submission', async () => {
    const { source, bridge, refresh } = fixture([thread('empty-current', { messages: [] })]);
    const value = mergeReviewCommentHistory(source, [history()], null, refresh, bridge);
    expect(value.threads.get(KEY)?.id).toBe('original');
    expect(value.earlierThreads?.get(KEY)).toBeUndefined();
    await value.ask(KEY, 'Continue the existing conversation');
    expect(bridge.askReviewCommentThread).toHaveBeenCalledWith('original-review', KEY, 'Continue the existing conversation');
    expect(source.ask).not.toHaveBeenCalled();
  });

  it('keeps ended history readable and resolves it locally without allowing a new question', async () => {
    const { source, bridge, refresh } = fixture();
    const saved = thread();
    const value = mergeReviewCommentHistory(source, [history('ended', [saved], 'CLOSED')], null, refresh, bridge);
    expect(value.threads.get(KEY)?.messages).toHaveLength(4);
    expect(value.statusFor?.(KEY).disabledReason).toContain('read-only');
    await expect(value.ask(KEY, 'Follow up')).rejects.toThrow('read-only');
    await value.resolve(saved, true);
    expect(bridge.resolveReviewDiscussion).toHaveBeenCalledWith('ended', saved.id, true, saved.version);
    const removed = history();
    removed.review.task.state = 'COMPLETED';
    expect(mergeReviewCommentHistory(source, [removed], null, refresh, bridge).statusFor?.(KEY).disabledReason).toContain('read-only');
  });

  it('uses the selected history host busy state, including its other passage discussions', async () => {
    const { source, bridge, refresh } = fixture();
    source.busy = true;
    const idle = mergeReviewCommentHistory(source, [history()], null, refresh, bridge);
    expect(idle.statusFor?.(KEY).busy).toBe(false);
    expect(idle.statusFor?.('review_comment:2').busy).toBe(true);
    const pending = thread('passage', { comment: null, pending: true });
    const value = mergeReviewCommentHistory(source, [history('original-review', [thread(), pending])], null, refresh, bridge);
    expect(value.statusFor?.(KEY).busy).toBe(true);
    await expect(value.ask(KEY, 'Follow up')).rejects.toThrow('another question on this review');
    expect(bridge.askReviewCommentThread).not.toHaveBeenCalled();
  });

  it.each(['Loading private history…', 'Private history unavailable: connection failed'])(
    'keeps retained messages and blocks duplicate creation while %s', async reason => {
      const { source, bridge, refresh } = fixture();
      const value = mergeReviewCommentHistory(source, [history()], reason, refresh, bridge);
      expect(value.threads.get(KEY)?.messages).toHaveLength(4);
      expect(value.statusFor?.(KEY).sendDisabledReason).toBe(reason);
      await expect(value.ask(KEY, 'Follow up')).rejects.toThrow(reason);
      await expect(value.ask('review_comment:2', 'New comment')).rejects.toThrow(reason);
      expect(bridge.askReviewCommentThread).not.toHaveBeenCalled();
      expect(source.ask).not.toHaveBeenCalled();
    });
});
