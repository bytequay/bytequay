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


import { useCallback, useMemo, useRef } from 'react';
import { useThreadSnapshot } from '../../useThreadSnapshot';

import { recordAttentionAction } from '../../home/useAttentionVisit';
import { remoteErrorMessage } from '../../pulls/PullOverview';
import type {
  AskCommentDiscussionRequest, AskDiscussionRequest, DiscussionQuery, DraftDiscussionReplyRequest, OpenDiscussionRequest,
  ResolveDiscussionRequest, RetryDiscussionRequest, TaskDiscussionDto,
} from './contracts';

export type DiscussionBridgePort = {
  listTaskDiscussions: (query: DiscussionQuery) => Promise<TaskDiscussionDto[]>;
  openTaskDiscussion: (request: OpenDiscussionRequest) => Promise<TaskDiscussionDto>;
  askTaskDiscussion: (request: AskDiscussionRequest) => Promise<TaskDiscussionDto>;
  resolveTaskDiscussion: (request: ResolveDiscussionRequest) => Promise<TaskDiscussionDto>;
  retryTaskDiscussion: (request: RetryDiscussionRequest) => Promise<TaskDiscussionDto>;
  askTaskDiscussionComment: (request: AskCommentDiscussionRequest) => Promise<TaskDiscussionDto>;
  draftTaskDiscussionReply: (request: DraftDiscussionReplyRequest) => Promise<string>;
};

export type TaskDiscussions = {
  /** Passage discussions only. */
  discussions: TaskDiscussionDto[];
  /** Private threads on the task's pull-request comments. */
  commentThreads: TaskDiscussionDto[];
  /** A question is in flight on any discussion of the task, and the backend answers one at a time. */
  busy: boolean;
  error: string | null;
  /** Absent while the task can still be asked; a reason once its files are released. */
  disabledReason: string | null;
  refresh: () => Promise<void>;
  open: (request: Omit<OpenDiscussionRequest, keyof DiscussionQuery>) => Promise<TaskDiscussionDto>;
  ask: (discussionId: string, question: string) => Promise<void>;
  resolve: (discussionId: string, resolved: boolean, expectedVersion: number) => Promise<void>;
  retry: (discussionId: string, messageId: string) => Promise<void>;
  askComment: (request: Omit<AskCommentDiscussionRequest, keyof DiscussionQuery>) => Promise<TaskDiscussionDto>;
  draftReply: (discussionId: string) => Promise<string>;
};

/** The single filter point: passage surfaces must never see a private thread. */
export function splitDiscussions(list: TaskDiscussionDto[]): { discussions: TaskDiscussionDto[]; commentThreads: TaskDiscussionDto[] } {
  return {
    discussions: list.filter(discussion => discussion.comment === null),
    commentThreads: list.filter(discussion => discussion.comment !== null),
  };
}

/**
 * Every discussion on one task, polled while the screen is open.
 *
 * <p>A discussion answers on a virtual thread the renderer cannot observe, so the poll is how an
 * answer arrives. A write returns the updated discussion and is accepted immediately, which keeps
 * the thread responsive between polls.
 */
export function useTaskDiscussions(query: DiscussionQuery | null, options: {
  active?: boolean; bridge?: DiscussionBridgePort; disabledReason?: string | null;
} = {}): TaskDiscussions {
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const live = useCallback((): DiscussionBridgePort => optionsRef.current.bridge ?? window.bridge, []);
  const { items, latest, error, setError, refresh, accept } = useThreadSnapshot(query, options.active, target => live().listTaskDiscussions(target));

  const write = useCallback(async (
    action: (bridge: DiscussionBridgePort, target: DiscussionQuery) => Promise<TaskDiscussionDto>,
    attentionAction: 'prompt' | 'update' = 'prompt',
  ): Promise<TaskDiscussionDto> => {
    const current = latest.current;
    if (current.query === null) throw new Error('This task is no longer open.');
    try {
      const discussion = await action(live(), current.query);
      accept(discussion, current.key);
      recordAttentionAction(`task:${current.query.taskId}`, attentionAction);
      return discussion;
    }
    catch (failure) {
      setError(remoteErrorMessage(failure));
      throw failure;
    }
  }, [accept, latest, live, setError]);

  // The screen re-renders far more often than a poll lands; hand down the same lists between polls.
  const split = useMemo(() => splitDiscussions(items), [items]);
  return {
    ...split,
    busy: items.some(discussion => discussion.pending),
    error,
    disabledReason: options.disabledReason ?? null,
    refresh,
    open: request => write((bridge, target) => bridge.openTaskDiscussion({ ...target, ...request })),
    ask: async (discussionId, question) => {
      await write((bridge, target) => bridge.askTaskDiscussion({ ...target, discussionId, question }));
    },
    resolve: async (discussionId, resolved, expectedVersion) => {
      await write((bridge, target) => bridge.resolveTaskDiscussion({ ...target, discussionId, resolved, expectedVersion }), 'update');
    },
    retry: async (discussionId, messageId) => {
      await write((bridge, target) => bridge.retryTaskDiscussion({ ...target, discussionId, messageId }));
    },
    askComment: request => write((bridge, target) => bridge.askTaskDiscussionComment({ ...target, ...request })),
    // No row comes back, so nothing is accepted and the hook's error stays the thread's own concern.
    draftReply: async discussionId => {
      const target = latest.current.query;
      if (target === null) throw new Error('This task is no longer open.');
      return live().draftTaskDiscussionReply({ ...target, discussionId });
    },
  };
}
