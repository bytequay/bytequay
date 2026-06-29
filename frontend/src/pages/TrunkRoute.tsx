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
import { Callout, Card, Conv, EventRow, Thought, ToolBlock, UserMsg, Working } from '../ui/conv';
import type { TaskStatus } from '../ui/conv';
import { useThreadStream } from '../threads/useThreadStream';
import { isShellTool, shellCommand } from '../threads/toolDisplay';
import type { TaskCardData } from '../ui/pane';
import { proposalAction } from '../threads/usePendingShipProposal';
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

/** Read a tool-call message into a tool name + a one-line summary (the
 *  shell command, or the most telling input field) so the trunk shows what
 *  it's actually doing — grep, a sub-agent delegation, a read — instead of
 *  a blank "thinking". */
function parseToolCall(contentJson: string): { name: string; summary: string } {
  try {
    const c = JSON.parse(contentJson) as { toolName?: unknown; input?: unknown };
    const name = typeof c.toolName === 'string' && c.toolName.length > 0 ? c.toolName : 'Tool';
    let summary = '';
    if (isShellTool(name)) {
      summary = shellCommand(c.input);
    }
    else if (c.input !== null && typeof c.input === 'object') {
      const o = c.input as Record<string, unknown>;
      for (const k of ['description', 'prompt', 'pattern', 'query', 'path', 'file_path', 'url', 'command']) {
        const v = o[k];
        if (typeof v === 'string' && v.length > 0) { summary = v; break; }
      }
    }
    summary = summary.replace(/\s+/g, ' ').trim();
    return { name, summary: summary.length > 160 ? `${summary.slice(0, 160)}…` : summary };
  }
  catch {
    return { name: 'Tool', summary: '' };
  }
}

/** Build the trunk conversation rows, grouping consecutive thinking
 *  messages into one collapsible "Thought for Xs" block (the planning
 *  reasoning), rendering text turns inline, and surfacing tool calls so
 *  the agent's live activity is visible. */
function buildRows(messages: ThreadMessageDto[]): ReactNode[] {
  const planning = messages.filter(
    m => m.taskId === null && (m.type === 'text' || m.type === 'thinking' || m.type === 'tool_call'));
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
      // Show the reasoning expanded by default (the Copilot pattern) so the
      // thought progress is visible without a click. With no extractable
      // text, fall back to the bare "Thought for Xs" line rather than an
      // empty disclosure that expands to nothing.
      rows.push(texts.length > 0
        ? (
          <Thought key={group[0].id} seconds={seconds} defaultOpen>
            {texts.map((t, k) => <Callout key={k}>{t}</Callout>)}
          </Thought>
        )
        : <Thought key={group[0].id} seconds={seconds} />);
    }
    else if (m.type === 'tool_call') {
      const { name, summary } = parseToolCall(m.contentJson);
      rows.push(<ToolBlock key={m.id} tag={name} desc={summary} />);
      i += 1;
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

/** Statuses that don't belong in the Tasks tab's active list: COMPLETED work
 *  is done and lives in the conversation history, and terminal tasks
 *  (CANCELED / ARCHIVED) are closed/reaped — they linger as bogus "foreground"
 *  cards otherwise, since cardStatus maps them to the running pill. IN_REVIEW
 *  is NOT hidden: a shipped task is still in-flight (CI-fixing / addressing
 *  comments / awaiting merge), so it shows as a "shipped" card and counts.
 *  PENDING is handled separately (the Queued folder). */
const HIDDEN_TASK_STATUSES = new Set(['COMPLETED', 'CANCELED', 'ARCHIVED']);

/** "Task 1 · Remove PersonaRequest bean", or just "Task 1" without a rename. */
function cardTitle(t: WorkUnitTaskDto): string {
  return t.name !== null && t.name !== '' ? `Task ${t.seq} · ${t.name}` : `Task ${t.seq}`;
}

function toCard(t: WorkUnitTaskDto, mergeReady: boolean): TaskCardData {
  return {
    id: t.id,
    title: cardTitle(t),
    status: cardStatus(t.status),
    branch: t.branchName ?? undefined,
    mergeReady,
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
  // Task ids whose PR has an open merge gate (ready to merge).
  const [mergeReadyIds, setMergeReadyIds] = useState<ReadonlySet<string>>(new Set());
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
  // The agent is working whenever the thread process is RUNNING — not just
  // right after the user's own submit. An autonomous multi-step turn (cut a
  // task, run tools, reply, run more) keeps the indicator up the whole time,
  // even across intermediate messages, so a quiet gap never reads as dead.
  const working = busy || awaitedAt !== null || thread?.status === 'RUNNING';
  // Start time of the current working period, so the indicator can show a
  // ticking elapsed counter — a long, quiet think shouldn't read as dead.
  const [workingSince, setWorkingSince] = useState<number | null>(null);
  useEffect(() => {
    setWorkingSince(prev => (working ? prev ?? Date.now() : null));
  }, [working]);

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTask === undefined) return;
    try {
      const [t, page, taskList, notifs] = await Promise.all([
        bridge.getTask(threadId),
        bridge.getTaskIndex(threadId, { direction: 'initial' }),
        bridge.listTasksForThread(threadId),
        bridge.listNotificationsForThread(threadId),
      ]);
      setThread(t);
      setMessages(page.messages);
      setTasks(taskList);
      // A task is "ready to merge" when it has an open merge_pr gate.
      setMergeReadyIds(new Set(
        notifs
          .filter(n => n.kind === 'AWAITING_REVIEW' && n.status === 'UNREAD'
            && n.taskId !== null && proposalAction(n) === 'merge_pr')
          .map(n => n.taskId as string),
      ));
    }
    catch { /* leave the last loaded state */ }
  }, [threadId]);

  // Poll so the agent's reply (and any task it cuts) lands without a manual
  // reload — also a fallback if the live stream can't connect.
  useEffect(() => {
    void load();
    const id = window.setInterval(() => { void load(); }, 3000);
    return () => window.clearInterval(id);
  }, [load]);

  // Live SSE stream: the agent's reasoning + reply appear token-by-token as
  // they're generated, instead of only when a poll fires after the turn. The
  // canonical messages refresh + the live buffers flush once the turn lands.
  const { liveText, liveThinking } = useThreadStream(threadId, thread?.status, load);

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

  // Manual cut: seed a task from the latest planning prompt and queue it.
  // The trunk agent can now cut tasks itself via create_task; this button
  // stays as the user's own way to cut from the plan.
  const lastUserPrompt = [...messages].reverse().find(
    m => m.taskId === null && m.role === 'user' && m.type === 'text');
  const cutTask = () => {
    const seed = lastUserPrompt !== undefined ? extractText(lastUserPrompt.contentJson) : '';
    const title = (seed.split('\n')[0] || thread?.title || 'New task').slice(0, 80);
    window.bridge.queueAdd(threadId, title, 'MAIN', seed.length > 0 ? seed : null)
      .then(() => load())
      .catch(() => { /* leave state; the queue UI reconciles */ });
  };

  // The foreground task — the one actually running now — is echoed as a
  // card at the foot of the conversation (matching the trunk design),
  // not only in the Tasks tab, so the in-flight work is visible without
  // leaving the thread. Latest such task wins when more than one is live.
  const foreground = [...tasks].reverse().find(
    t => !HIDDEN_TASK_STATUSES.has(t.status) && cardStatus(t.status) === 'foreground');

  // Label the working indicator with the current activity — if the latest
  // trunk message is a tool call, say which tool is running rather than the
  // generic "thinking" that sits there for minutes during a long tool/turn.
  const lastActivity = [...messages].reverse().find(
    m => m.taskId === null && (m.type === 'tool_call' || m.type === 'text' || m.type === 'thinking'));
  const workingLabel = lastActivity?.type === 'tool_call'
    ? `Running ${parseToolCall(lastActivity.contentJson).name}…`
    : 'Trunk is working…';

  const conversation = (
    <Conv>
      {buildRows(messages)}
      {foreground !== undefined && (
        <Card
          kind="task"
          title={cardTitle(foreground)}
          branch={foreground.branchName ?? undefined}
          status="foreground"
          statusText="Running"
          onClick={() => onOpenTask(foreground.id)}
        />
      )}
      {liveThinking.length > 0 && (
        <Thought label="Thinking…" defaultOpen><Callout>{liveThinking}</Callout></Thought>
      )}
      {liveText.length > 0 && <EventRow kind="brain" who="Agent" markdown={liveText} />}
      {working && liveText.length === 0 && (
        <Working
          label={workingLabel}
          since={workingSince ?? undefined}
          onStop={() => { void window.bridge?.interruptTask(threadId).then(load).catch(() => { /* poll reconciles */ }); }}
        />
      )}
    </Conv>
  );

  const active = tasks
    .filter(t => t.status !== 'PENDING' && !HIDDEN_TASK_STATUSES.has(t.status))
    .map(t => toCard(t, mergeReadyIds.has(t.id)));
  const queued = tasks.filter(t => t.status === 'PENDING').map(t => toCard(t, false));

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
