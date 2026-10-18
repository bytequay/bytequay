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
import { createElement, Fragment, useEffect } from 'react';
import type { TaskDiscussionDto } from '../agents/discussions/contracts';
import type { Bridge, PullRequestDto } from '../types';
import { useThreadSnapshot } from '../useThreadSnapshot';
import type { AgentReviewDto } from './agent-review/contracts';
import type { PrivateThreadSource } from './PrivateThread';

export type ReviewCommentHistory = {
  review: Pick<AgentReviewDto, 'id' | 'state'> & { task: Pick<AgentReviewDto['task'], 'id' | 'state'> };
  discussions: TaskDiscussionDto[];
};
type HistoryBridge = Pick<Bridge, 'askReviewCommentThread' | 'resolveReviewDiscussion' | 'retryReviewDiscussion' | 'draftReviewCommentReply'>;
type HistorySnapshot = { id: string; version: number; createdAt: string; histories: ReviewCommentHistory[] };

/** The current host owns new threads; retained conversations keep the host that already answered them. */
export function mergeReviewCommentHistory(source: PrivateThreadSource, histories: ReviewCommentHistory[], blockedReason: string | null,
  refresh: () => Promise<void>, bridge: HistoryBridge): PrivateThreadSource {
  const threads = new Map(source.threads);
  const earlierThreads = new Map([...source.earlierThreads ?? []].map(([key, values]) => [key, [...values]]));
  const sourceIds = new Set([...source.threads.values(), ...[...earlierThreads.values()].flat()].map(thread => thread.id));
  const owners = new Map<string, ReviewCommentHistory>();
  const retained = histories.flatMap(history => history.discussions
    .filter(thread => thread.comment !== null && thread.messages.length > 0)
    .map(thread => ({ thread, history })))
    .sort((left, right) => left.thread.createdAt.localeCompare(right.thread.createdAt) || left.thread.id.localeCompare(right.thread.id));
  for (const { thread, history } of retained) {
    if (thread.comment === null) continue;
    owners.set(thread.id, history);
    const key = thread.comment.key;
    const selected = threads.get(key);
    if (selected === undefined || selected.messages.length === 0) threads.set(key, thread);
    else if (selected.id !== thread.id) {
      const earlier = earlierThreads.get(key) ?? [];
      if (!earlier.some(value => value.id === thread.id)) earlierThreads.set(key, [...earlier, thread]);
    }
  }

  const statusForThread = (key: string, thread = threads.get(key)) => {
    const history = thread === undefined || sourceIds.has(thread.id) ? undefined : owners.get(thread.id);
    const status = history === undefined ? source.statusFor?.(key) ?? source : {
      busy: history.discussions.some(discussion => discussion.pending),
      busyReason: 'The agent is answering another question on this review',
      disabledReason: history.review.state === 'CLOSED' || history.review.task.state !== 'OPEN'
        ? 'This review has ended, so its threads are read-only' : null,
      sendDisabledReason: null,
    };
    return { busy: status.busy, busyReason: status.busyReason, disabledReason: status.disabledReason,
      sendDisabledReason: blockedReason ?? status.sendDisabledReason };
  };
  const statusFor = (key: string) => statusForThread(key);
  const requireWritable = (key: string, thread = threads.get(key)) => {
    const status = statusForThread(key, thread);
    const reason = status.disabledReason ?? status.sendDisabledReason ?? (status.busy ? status.busyReason : null);
    if (reason) throw new Error(reason);
  };
  const owner = (thread: TaskDiscussionDto) => sourceIds.has(thread.id) ? undefined : owners.get(thread.id)?.review.id;
  const write = async <T,>(action: () => Promise<T>): Promise<T> => {
    const result = await action();
    await refresh();
    return result;
  };
  return {
    ...source,
    threads,
    earlierThreads,
    statusFor,
    sendDisabledReason: blockedReason ?? source.sendDisabledReason,
    ask: async (key, question) => {
      requireWritable(key);
      const thread = threads.get(key);
      const reviewId = thread === undefined ? undefined : owner(thread);
      await write(async () => { await (reviewId === undefined ? source.ask(key, question) : bridge.askReviewCommentThread(reviewId, key, question)); });
    },
    resolve: async (thread, resolved) => {
      const reviewId = owner(thread);
      await write(async () => { await (reviewId === undefined ? source.resolve(thread, resolved)
        : bridge.resolveReviewDiscussion(reviewId, thread.id, resolved, thread.version)); });
    },
    retry: async (thread, messageId) => {
      if (thread.comment === null) throw new Error('This discussion is not a comment thread.');
      requireWritable(thread.comment.key, thread);
      const reviewId = owner(thread);
      await write(async () => { await (reviewId === undefined ? source.retry(thread, messageId) : bridge.retryReviewDiscussion(reviewId, thread.id, messageId)); });
    },
    draftReply: async thread => {
      if (thread.comment === null) throw new Error('This discussion is not a comment thread.');
      requireWritable(thread.comment.key, thread);
      const reviewId = owner(thread);
      return write(() => reviewId === undefined ? source.draftReply(thread) : bridge.draftReviewCommentReply(reviewId, thread.id));
    },
  };
}

export function useReviewCommentHistory(source: PrivateThreadSource | undefined, pr: Pick<PullRequestDto, 'repo' | 'number'> | null,
  workspaceId: string | undefined, active: boolean): PrivateThreadSource | undefined {
  const query = source === undefined || pr === null || workspaceId === undefined ? null : { workspaceId, repo: pr.repo.toLowerCase(), number: pr.number };
  const { items, error, setError, refresh } = useThreadSnapshot<typeof query, HistorySnapshot>(query, active, async target => {
    const reviews = (await window.bridge.listAgentReviews(target.workspaceId))
      .filter(review => review.workspaceId === target.workspaceId && review.repo.toLowerCase() === target.repo
        && review.number === target.number && review.replay === undefined);
    const histories = await Promise.all(reviews.map(async review => ({ review, discussions: (await window.bridge.listReviewDiscussions(review.id))
      .filter(thread => thread.comment === null || (thread.comment.repo.toLowerCase() === target.repo && thread.comment.number === target.number)) })));
    return [{ id: 'review-comment-history', version: 0, createdAt: '', histories }];
  });
  const scope = JSON.stringify(query);
  useEffect(() => { setError(null); }, [scope, setError]);
  if (source === undefined || query === null) return source;
  const blockedReason = error !== null ? `Private history unavailable: ${error}` : items.length === 0 ? 'Loading private history…' : null;
  const result = mergeReviewCommentHistory(source, items[0]?.histories ?? [], blockedReason, refresh, window.bridge);
  return error === null ? result : { ...result, setup: createElement(Fragment, null, source.setup,
    createElement('button', { type: 'button', onClick: () => { void refresh(); } }, 'Retry private history')) };
}
