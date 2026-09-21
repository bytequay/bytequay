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


import { useCallback, useEffect, useRef, useState } from 'react';

import { remoteErrorMessage } from '../../pulls/PullOverview';
import type {
  AskDiscussionRequest, DiscussionQuery, OpenDiscussionRequest, ResolveDiscussionRequest,
  RetryDiscussionRequest, TaskDiscussionDto,
} from './contracts';

export type DiscussionBridgePort = {
  listTaskDiscussions: (query: DiscussionQuery) => Promise<TaskDiscussionDto[]>;
  openTaskDiscussion: (request: OpenDiscussionRequest) => Promise<TaskDiscussionDto>;
  askTaskDiscussion: (request: AskDiscussionRequest) => Promise<TaskDiscussionDto>;
  resolveTaskDiscussion: (request: ResolveDiscussionRequest) => Promise<TaskDiscussionDto>;
  retryTaskDiscussion: (request: RetryDiscussionRequest) => Promise<TaskDiscussionDto>;
};

export type TaskDiscussions = {
  discussions: TaskDiscussionDto[];
  error: string | null;
  /** Absent while the task can still be asked; a reason once its files are released. */
  disabledReason: string | null;
  refresh: () => Promise<void>;
  open: (request: Omit<OpenDiscussionRequest, keyof DiscussionQuery>) => Promise<TaskDiscussionDto>;
  ask: (discussionId: string, question: string) => Promise<void>;
  resolve: (discussionId: string, resolved: boolean, expectedVersion: number) => Promise<void>;
  retry: (discussionId: string, messageId: string) => Promise<void>;
};

const EMPTY: TaskDiscussionDto[] = [];

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
  const key = JSON.stringify(query);
  const [snapshot, setSnapshot] = useState<{ key: string; discussions: TaskDiscussionDto[] }>({ key, discussions: EMPTY });
  const [error, setError] = useState<string | null>(null);
  const latest = useRef({ key, query, options, discussions: snapshot.key === key ? snapshot.discussions : EMPTY });
  latest.current = { key, query, options, discussions: snapshot.key === key ? snapshot.discussions : EMPTY };
  const epoch = useRef(0);
  const live = useCallback((): DiscussionBridgePort => latest.current.options.bridge ?? window.bridge, []);

  const refresh = useCallback(async () => {
    const current = latest.current;
    if (current.query === null) return;
    const version = ++epoch.current;
    try {
      const discussions = await live().listTaskDiscussions(current.query);
      if (latest.current.key === current.key && epoch.current === version) {
        latest.current.discussions = discussions;
        setSnapshot({ key: current.key, discussions });
        setError(null);
      }
    }
    catch (failure) {
      if (latest.current.key === current.key && epoch.current === version) setError(remoteErrorMessage(failure));
    }
  }, [live]);

  useEffect(() => {
    if (options.active === false || query === null) return;
    let cancelled = false;
    const pendingEpoch = epoch;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async (): Promise<void> => {
      await refresh();
      if (!cancelled) timer = setTimeout(() => { void poll(); }, 3000);
    };
    void poll();
    return () => { cancelled = true; clearTimeout(timer); pendingEpoch.current++; };
  // Query identity is its serialized value; caller object identity must not restart polling.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, options.active, refresh]);

  const accept = useCallback((discussion: TaskDiscussionDto, scope: string): void => {
    if (latest.current.key !== scope) return;
    epoch.current++;
    // A poll in flight may carry an older row; never let it undo what a write just returned.
    if (latest.current.discussions.some(item => item.id === discussion.id && item.version > discussion.version)) return;
    const discussions = [...latest.current.discussions.filter(item => item.id !== discussion.id), discussion]
      .sort((left, right) => left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id));
    latest.current.discussions = discussions;
    setSnapshot({ key: scope, discussions });
    setError(null);
  }, []);

  const write = useCallback(async (
    action: (bridge: DiscussionBridgePort, target: DiscussionQuery) => Promise<TaskDiscussionDto>,
  ): Promise<TaskDiscussionDto> => {
    const current = latest.current;
    if (current.query === null) throw new Error('This task is no longer open.');
    try {
      const discussion = await action(live(), current.query);
      accept(discussion, current.key);
      return discussion;
    }
    catch (failure) {
      setError(remoteErrorMessage(failure));
      throw failure;
    }
  }, [accept, live]);

  return {
    discussions: snapshot.key === key ? snapshot.discussions : EMPTY,
    error,
    disabledReason: options.disabledReason ?? null,
    refresh,
    open: request => write((bridge, target) => bridge.openTaskDiscussion({ ...target, ...request })),
    ask: async (discussionId, question) => {
      await write((bridge, target) => bridge.askTaskDiscussion({ ...target, discussionId, question }));
    },
    resolve: async (discussionId, resolved, expectedVersion) => {
      await write((bridge, target) => bridge.resolveTaskDiscussion({ ...target, discussionId, resolved, expectedVersion }));
    },
    retry: async (discussionId, messageId) => {
      await write((bridge, target) => bridge.retryTaskDiscussion({ ...target, discussionId, messageId }));
    },
  };
}
