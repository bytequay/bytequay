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
import type { ThreadDto, ThreadMessageDto, WorkUnitTaskDto } from '../types';
import type { ReactNode } from 'react';
import { Callout, Conv, EventRow, Thought, UserMsg, Working } from '../ui/conv';
import type { TaskStatus } from '../ui/conv';
import type { TaskCardData } from '../ui/pane';
import { TrunkPage } from './TrunkPage';

/** Best-effort plain text out of a message's JSON envelope. Thinking rows
 *  carry a {@code summary}; text rows carry {@code text}/{@code content}. */
function extractText(contentJson: string): string {
  try {
    const v: unknown = JSON.parse(contentJson);
    if (typeof v === 'string') return v;
    if (v !== null && typeof v === 'object') {
      const o = v as Record<string, unknown>;
      if (typeof o.text === 'string') return o.text;
      if (typeof o.content === 'string') return o.content;
      if (typeof o.summary === 'string') return o.summary;
    }
  }
  catch { /* non-JSON envelope */ }
  return '';
}

/** Build the trunk conversation rows, grouping consecutive thinking
 *  messages into one collapsible "Thought for Xs" block (the planning
 *  reasoning) and rendering text turns inline. */
function buildRows(messages: ThreadMessageDto[]): ReactNode[] {
  const planning = messages.filter(
    m => m.taskId === null && (m.type === 'text' || m.type === 'thinking'));
  const rows: ReactNode[] = [];
  let i = 0;
  while (i < planning.length) {
    const m = planning[i];
    if (m.type === 'thinking') {
      const group: ThreadMessageDto[] = [];
      while (i < planning.length && planning[i].type === 'thinking') {
        group.push(planning[i]);
        i += 1;
      }
      const next = planning[i];
      const endMs = next !== undefined ? Date.parse(next.ts) : Date.parse(group[group.length - 1].ts);
      const seconds = Math.max(1, Math.round((endMs - Date.parse(group[0].ts)) / 1000));
      const texts = group.map(g => extractText(g.contentJson)).filter(t => t.trim().length > 0);
      rows.push(
        <Thought key={group[0].id} seconds={seconds}>
          {texts.map((t, k) => <Callout key={k}>{t}</Callout>)}
        </Thought>,
      );
    }
    else {
      const txt = extractText(m.contentJson);
      if (txt.trim().length > 0) {
        rows.push(m.role === 'user'
          ? <UserMsg key={m.id} text={txt} />
          : <EventRow key={m.id} kind="brain" who="Agent" markdown={txt} />);
      }
      i += 1;
    }
  }
  return rows;
}

/** Map a work-unit status string to the card's status pill. */
function cardStatus(status: string): TaskStatus {
  switch (status) {
    case 'COMPLETED': case 'IN_REVIEW': return 'shipped';
    case 'ERRORED': return 'errored';
    case 'PAUSED': return 'paused';
    case 'PENDING': return 'pending';
    default: return 'foreground';
  }
}

function toCard(t: WorkUnitTaskDto): TaskCardData {
  return {
    id: t.id,
    title: t.name !== null && t.name !== '' ? `Task ${t.seq} · ${t.name}` : `Task ${t.seq}`,
    status: cardStatus(t.status),
    branch: t.branchName ?? undefined,
  };
}

/**
 * Data adapter mounting the V3 {@link TrunkPage} on the live thread trunk
 * data. Loads the thread, its planning messages → conversation, and its
 * tasks → the Tasks tab; the composer posts a trunk message. The backlog
 * and notifications tabs load themselves via the trunk pane hook. Inline
 * task-launch cards and the full sidebar tree are backfilled later.
 */
export function TrunkRoute({ threadId, onOpenTask }: {
  threadId: string;
  onOpenTask: (taskId: string) => void;
}) {
  const [thread, setThread] = useState<ThreadDto | null>(null);
  const [messages, setMessages] = useState<ThreadMessageDto[]>([]);
  const [tasks, setTasks] = useState<WorkUnitTaskDto[]>([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  // Clear the "working" indicator only when a new assistant reply lands,
  // not when the user's own message persists into the planning slice.
  const replyCount = messages.filter(
    m => m.taskId === null && m.role === 'assistant' && (m.type === 'text' || m.type === 'thinking')).length;
  const [awaitedAt, setAwaitedAt] = useState<number | null>(null);
  useEffect(() => {
    if (awaitedAt !== null && replyCount > awaitedAt) setAwaitedAt(null);
  }, [replyCount, awaitedAt]);
  const working = busy || awaitedAt !== null;

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTask === undefined) return;
    try {
      const [t, page, taskList] = await Promise.all([
        bridge.getTask(threadId),
        bridge.getTaskIndex(threadId, { direction: 'initial' }),
        bridge.listTasksForThread(threadId),
      ]);
      setThread(t);
      setMessages(page.messages);
      setTasks(taskList);
    }
    catch { /* leave the last loaded state */ }
  }, [threadId]);

  useEffect(() => { void load(); }, [load]);

  const submit = () => {
    const body = text.trim();
    if (body.length === 0 || busy) return;
    setText('');
    setBusy(true);
    setAwaitedAt(replyCount);
    window.bridge.sendTrunkMessage(threadId, body)
      .then(() => load())
      .catch(() => { setAwaitedAt(null); })
      .finally(() => setBusy(false));
  };

  // User-confirmed cut: seed the new task from the latest planning prompt
  // and queue it (the scheduler materialises it). The trunk itself never
  // cuts — it only plans.
  const lastUserPrompt = [...messages].reverse().find(
    m => m.taskId === null && m.role === 'user' && m.type === 'text');
  const cutTask = () => {
    const seed = lastUserPrompt !== undefined ? extractText(lastUserPrompt.contentJson) : '';
    const title = (seed.split('\n')[0] || thread?.title || 'New task').slice(0, 80);
    window.bridge.queueAdd(threadId, title, 'MAIN', seed.length > 0 ? seed : null)
      .then(() => load())
      .catch(() => { /* leave state; the queue UI reconciles */ });
  };

  const conversation = (
    <Conv>
      {buildRows(messages)}
      {working && <Working label="Trunk is thinking…" />}
    </Conv>
  );

  const active = tasks.filter(t => t.status !== 'PENDING' && t.status !== 'COMPLETED').map(toCard);
  const queued = tasks.filter(t => t.status === 'PENDING').map(toCard);

  return (
    <TrunkPage
      threadId={threadId}
      thread={{ title: thread?.title ?? 'Thread' }}
      conversation={conversation}
      composer={{ value: text, onChange: setText, onSubmit: submit, busy, placeholder: 'Discuss the next task, ask the brain, or paste an error…' }}
      tasks={{ active, queued }}
      onOpenTask={onOpenTask}
      onCutTask={cutTask}
    />
  );
}
