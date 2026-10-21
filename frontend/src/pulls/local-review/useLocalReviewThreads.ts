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

import { useCallback, useRef } from 'react';
import { useThreadSnapshot } from '../../useThreadSnapshot';
import { recordAttentionAction } from '../../home/useAttentionVisit';
import type { Bridge } from '../../types';
import type { DiffAgentComment } from '../PullChanges';
import { useViewer } from '../PullOverview';
import type { CreateDevelopmentLearningCommentRequest, LocalReviewQuery } from './contracts';
import type { LocalReviewThreadDto, LocalReviewThreads } from './types';

export type LocalReviewBridgePort = Pick<Bridge,
  'listLocalReviewComments' | 'createLocalReviewComment' | 'replyLocalReviewComment'
  | 'resolveLocalReviewComment' | 'retryLocalReviewComment'> & Partial<Pick<Bridge, 'createDevelopmentLearningComment'>>;

/** The diff, PR timeline and task transcript all read the same durable discussions. */
export function useLocalReviewThreads(query: LocalReviewQuery | null, options: {
  active?: boolean; bridge?: LocalReviewBridgePort; disabledReason?: string | null; learningDisabledReason?: string | null; onDispatch?: () => Promise<void>;
} = {}): LocalReviewThreads & {
  refresh: () => Promise<void>;
  create: (comment: DiffAgentComment, target: { taskId: string; workspaceId: string; repo: string; number: number; expectedTaskVersion: number }) => Promise<void>;
  createLearning: (request: Omit<CreateDevelopmentLearningCommentRequest, 'id' | 'messageId' | 'idempotencyKey'>) => Promise<void>;
} {
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const operations = useRef(new Map<string, { key: string; messageId: string; threadId: string }>());
  const live = useCallback((): LocalReviewBridgePort => optionsRef.current.bridge ?? window.bridge, []);
  // A failed poll keeps the last threads and tries again; replies and saves report their own failures.
  const { items, latest, refresh, accept } = useThreadSnapshot(query, options.active, target => live().listLocalReviewComments(target));
  const viewer = useViewer(query !== null && items.length > 0);
  const operation = useCallback((signature: string) => {
    const existing = operations.current.get(signature);
    if (existing !== undefined) return existing;
    const created = { key: crypto.randomUUID(), messageId: crypto.randomUUID(), threadId: crypto.randomUUID() };
    operations.current.set(signature, created);
    return created;
  }, []);
  const find = useCallback((id: string): LocalReviewThreadDto => {
    const thread = latest.current.items.find(item => item.id === id);
    if (thread === undefined) throw new Error('Refresh to load this local review comment.');
    return thread;
  }, [latest]);
  const dispatched = useCallback(async () => {
    await optionsRef.current.onDispatch?.().catch(() => {});
  }, []);
  const create = useCallback(async (comment: DiffAgentComment, target: {
    taskId: string; workspaceId: string; repo: string; number: number; expectedTaskVersion: number;
  }) => {
    const scope = latest.current.key;
    const { expectedTaskVersion, ...identity } = target;
    const signature = JSON.stringify(['create', identity, comment]);
    const request = operation(signature);
    const thread = await live().createLocalReviewComment({
      ...target, id: request.threadId, messageId: request.messageId, idempotencyKey: request.key,
      path: comment.comment.path, side: comment.comment.side, startLine: comment.comment.startLine ?? comment.comment.line,
      line: comment.comment.line, headSha: comment.comment.commitId ?? null, baseSha: comment.baseSha,
      code: comment.code, body: comment.comment.body, expectedTaskVersion,
    });
    accept(thread, scope);
    operations.current.delete(signature);
    // The saved thread is authoritative; a slow task refresh must not keep its editor open.
    void dispatched();
  }, [accept, dispatched, latest, live, operation]);
  const onReply = useCallback(async (threadId: string, body: string) => {
    const scope = latest.current.key;
    await refresh();
    if (latest.current.key !== scope) return;
    const thread = find(threadId);
    const signature = JSON.stringify(['reply', threadId, body]);
    const request = operation(signature);
    const updated = await live().replyLocalReviewComment({
      taskId: thread.taskId, workspaceId: thread.workspaceId, threadId,
      messageId: request.messageId, idempotencyKey: request.key, body, expectedTaskVersion: thread.taskVersion,
    });
    accept(updated, scope);
    operations.current.delete(signature);
    void dispatched();
  }, [accept, dispatched, find, latest, live, operation, refresh]);
  const createLearning = useCallback(async (input: Omit<CreateDevelopmentLearningCommentRequest, 'id' | 'messageId' | 'idempotencyKey'>) => {
    const createComment = live().createDevelopmentLearningComment;
    if (createComment === undefined) throw new Error('Learning discussions are unavailable.');
    const scope = latest.current.key;
    const { expectedTaskVersion, ...identity } = input;
    const signature = JSON.stringify(['learning', identity]);
    const request = operation(signature);
    accept(await createComment({ ...input, expectedTaskVersion, id: request.threadId, messageId: request.messageId, idempotencyKey: request.key }), scope);
    operations.current.delete(signature);
    void dispatched();
  }, [accept, dispatched, latest, live, operation]);
  const onResolve = useCallback(async (threadId: string, resolved: boolean) => {
    const thread = find(threadId);
    const scope = latest.current.key;
    const signature = JSON.stringify(['resolve', threadId, resolved]);
    const request = operation(signature);
    try {
      accept(await live().resolveLocalReviewComment({
        taskId: thread.taskId, workspaceId: thread.workspaceId, threadId,
        idempotencyKey: request.key, resolved, expectedVersion: thread.version,
      }), scope);
      recordAttentionAction(`task:${thread.taskId}`, 'update');
      operations.current.delete(signature);
    }
    catch (failure) { await refresh(); throw failure; }
  }, [accept, find, latest, live, operation, refresh]);
  const onRetry = useCallback(async (threadId: string, messageId: string) => {
    const scope = latest.current.key;
    await refresh();
    if (latest.current.key !== scope) return;
    const thread = find(threadId);
    accept(await live().retryLocalReviewComment({
      taskId: thread.taskId, workspaceId: thread.workspaceId, threadId, messageId, expectedTaskVersion: thread.taskVersion,
    }), scope);
    void dispatched();
  }, [accept, dispatched, find, latest, live, refresh]);
  return { threads: items, viewer, onReply, onResolve, onRetry,
    disabledReason: options.disabledReason, learningDisabledReason: options.learningDisabledReason, refresh, create, createLearning };
}
