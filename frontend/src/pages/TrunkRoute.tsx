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
import { Conv, EventRow, UserMsg } from '../ui/conv';
import type { TaskStatus } from '../ui/conv';
import type { TaskCardData } from '../ui/pane';
import { CurrentThreadSidebar } from './CurrentThreadSidebar';
import { TrunkPage } from './TrunkPage';

/** Best-effort plain text out of a message's JSON envelope. */
function extractText(contentJson: string): string {
  try {
    const v: unknown = JSON.parse(contentJson);
    if (typeof v === 'string') return v;
    if (v !== null && typeof v === 'object') {
      const o = v as Record<string, unknown>;
      if (typeof o.text === 'string') return o.text;
      if (typeof o.content === 'string') return o.content;
    }
  }
  catch { /* non-JSON envelope */ }
  return '';
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
    window.bridge.sendTrunkMessage(threadId, body)
      .then(() => load())
      .catch(() => { /* reload reconciles */ })
      .finally(() => setBusy(false));
  };

  const conversation = (
    <Conv>
      {messages
        .filter(m => m.taskId === null && (m.type === 'text' || m.type === 'thinking'))
        .map(m => {
          const txt = extractText(m.contentJson);
          if (txt.trim().length === 0) return null;
          return m.role === 'user'
            ? <UserMsg key={m.id} text={txt} />
            : <EventRow key={m.id} kind="brain" who="Agent" markdown={txt} />;
        })
        .filter(Boolean)}
    </Conv>
  );

  const active = tasks.filter(t => t.status !== 'PENDING' && t.status !== 'COMPLETED').map(toCard);
  const queued = tasks.filter(t => t.status === 'PENDING').map(toCard);

  return (
    <TrunkPage
      threadId={threadId}
      thread={{ title: thread?.title ?? 'Thread' }}
      sidebar={<CurrentThreadSidebar threadLabel={thread?.title ?? 'Thread'} />}
      conversation={conversation}
      composer={{ value: text, onChange: setText, onSubmit: submit, busy, placeholder: 'Discuss the next task, ask the brain, or paste an error…' }}
      tasks={{ active, queued }}
      onOpenTask={onOpenTask}
    />
  );
}
