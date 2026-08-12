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
import { useCallback, useEffect, useState } from 'react';
import type { AgentQuestionDto, BacklogItemDto, ThreadSignalDto } from '../types';
import type { TrunkActivityDto } from '../workspace/workspaceApi';

/** Poll cadence so the trunk pane reflects live agent activity (a backlog
 *  item flipping in-progress, a new ask_user_question, a triage batch). */
const POLL_MS = 5000;

/** Backlog + signal data for a thread's trunk pane, plus the mutations
 *  the Backlog and Notifications tabs need. Each mutation calls the
 *  bridge then refreshes the affected list. */
export type TrunkPaneState = {
  backlog: BacklogItemDto[];
  signals: ThreadSignalDto[];
  questions: AgentQuestionDto[];
  activity: TrunkActivityDto;
  loading: boolean;
  error: string | null;
  refresh: () => void;
  createItem: (title: string, body: string, tags: string[], priority?: string) => Promise<void>;
  updateItem: (itemId: string, patch: { title?: string; body?: string; tags?: string[] }) => Promise<void>;
  deleteItem: (itemId: string) => Promise<void>;
  skip: (itemId: string, reason?: string) => Promise<void>;
  revive: (itemId: string) => Promise<void>;
  answerQuestion: (questionId: string, answerOptionId?: string, answerFreeForm?: string) => Promise<void>;
  markSignalRead: (signalId: string) => Promise<void>;
};

/**
 * Loads (and mutates) the backlog + signal feed for a thread. The trunk
 * page passes the result straight into {@code <BacklogTabContent>} and
 * {@code <NotificationsTabContent>}.
 */
export function useTrunkPane(threadId: string): TrunkPaneState {
  const [backlog, setBacklog] = useState<BacklogItemDto[]>([]);
  const [signals, setSignals] = useState<ThreadSignalDto[]>([]);
  const [questions, setQuestions] = useState<AgentQuestionDto[]>([]);
  const [activity, setActivity] = useState<TrunkActivityDto>({
    trunkId: threadId,
    pinned: [],
    timeline: [],
    generatedAt: 0,
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listBacklog === undefined || bridge.listThreadSignals === undefined) {
      setLoading(false);
      return;
    }
    try {
      const [b, s, q, a] = await Promise.all([
        bridge.listBacklog(threadId),
        bridge.listThreadSignals(threadId),
        bridge.listThreadQuestions?.(threadId) ?? Promise.resolve([]),
        bridge.workspaceApi?.<TrunkActivityDto>({
          path: `/api/trunks/${encodeURIComponent(threadId)}/activity`,
        }) ?? Promise.resolve({
          trunkId: threadId,
          pinned: [],
          timeline: [],
          generatedAt: 0,
        }),
      ]);
      setBacklog(b);
      setSignals(s);
      setQuestions(q);
      setActivity(a);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load the trunk pane');
    }
    finally {
      setLoading(false);
    }
  }, [threadId]);

  useEffect(() => {
    void load();
    const id = setInterval(() => { void load(); }, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  const refresh = useCallback(() => { void load(); }, [load]);

  const createItem = useCallback(async (title: string, body: string, tags: string[], priority?: string) => {
    await window.bridge.createBacklogItem(threadId, title, body, tags, priority);
    await load();
  }, [threadId, load]);

  const updateItem = useCallback(async (itemId: string, patch: { title?: string; body?: string; tags?: string[] }) => {
    await window.bridge.updateBacklogItem(itemId, patch);
    await load();
  }, [load]);

  const deleteItem = useCallback(async (itemId: string) => {
    await window.bridge.deleteBacklogItem(itemId);
    await load();
  }, [load]);

  const skip = useCallback(async (itemId: string, reason?: string) => {
    await window.bridge.skipBacklogItem(itemId, reason);
    await load();
  }, [load]);

  const revive = useCallback(async (itemId: string) => {
    await window.bridge.reviveBacklogItem(itemId);
    await load();
  }, [load]);

  const answerQuestion = useCallback(
    async (questionId: string, answerOptionId?: string, answerFreeForm?: string) => {
      await window.bridge.answerQuestion(questionId, answerOptionId, answerFreeForm);
      await load();
    }, [load]);

  const markSignalRead = useCallback(async (signalId: string) => {
    await window.bridge.markSignalRead(signalId);
    await load();
  }, [load]);

  return {
    backlog, signals, questions, activity, loading, error, refresh,
    createItem, updateItem, deleteItem, skip, revive, answerQuestion, markSignalRead,
  };
}
