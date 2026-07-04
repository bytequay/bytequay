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
import { Callout, Conv, DensityToggle, EventRow, QueuedMessages, Thought, Working } from '../ui/conv';
import { useMessageQueue } from '../threads/useMessageQueue';
import { useThreadStream } from '../threads/useThreadStream';
import { usePersistentToggle } from '../ui/shell';
import { TrunkFeed } from '../threads/TrunkFeed';
import { parseToolCall } from '../threads/trunkTimeline';
import { toTaskCard } from '../threads/taskCardData';
import { proposalAction } from '../threads/usePendingShipProposal';
import { TrunkPage } from './TrunkPage';

/** Terminal statuses — the task has landed (COMPLETED/merged) or been
 *  closed/reaped (CANCELED / ARCHIVED). These fill the Tasks tab's "Closed"
 *  sub-tab rather than the live "All" list. IN_REVIEW is NOT terminal: a
 *  shipped task is still in-flight (CI-fixing / addressing comments /
 *  awaiting merge). PENDING is the Queued folder. */
const TERMINAL_TASK_STATUSES = new Set(['COMPLETED', 'CANCELED', 'ARCHIVED']);

/** Parse a server ISO timestamp to epoch-ms, or null when absent/invalid.
 *  Anchors the "working" elapsed counter to server time so it keeps ticking
 *  across a tab switch instead of restarting from a local mount time. */
function epochOrNull(ts: string | undefined): number | null {
  if (ts === undefined) {
    return null;
  }
  const ms = Date.parse(ts);
  return Number.isNaN(ms) ? null : ms;
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

  const sendNow = useCallback((body: string) => {
    setBusy(true);
    setAwaitedAt(replyCount);
    window.bridge.sendTrunkMessage(threadId, body)
      .then(() => load())
      .catch(() => { setAwaitedAt(null); })
      .finally(() => setBusy(false));
  }, [threadId, load, replyCount]);
  // Messages typed while the trunk is working queue up and auto-send when it
  // goes idle; the user can pull one back into the composer to edit it.
  const { queue, enqueue, takeForEdit, remove } = useMessageQueue(working, sendNow);
  const submit = () => {
    const body = text.trim();
    if (body.length === 0) return;
    setText('');
    if (working) enqueue(body);
    else sendNow(body);
  };

  // Tasks are cut by the trunk agent (create_task) — proposed to the user via
  // an ask_user_question card and confirmed by selecting it — not by a manual
  // button. So there's no user-driven cut path here.

  // The foreground task — the one actually running now — is echoed as a
  // Label the working indicator with the current activity — if the latest
  // trunk message is a tool call, say which tool is running AND (for a shell
  // command) the command itself, rather than a bare "Running Bash…" that
  // hides what a 10-minute turn is actually doing. The full command shows on
  // hover via the detail tooltip.
  const lastActivity = [...messages].reverse().find(
    m => m.taskId === null && (m.type === 'tool_call' || m.type === 'text' || m.type === 'thinking'));
  const activity = lastActivity?.type === 'tool_call'
    ? parseToolCall(lastActivity.contentJson)
    : null;
  // Elapsed ticks from the last activity's SERVER timestamp (from the polled
  // `messages`), not a local mount time — so leaving the tab and coming back
  // keeps counting instead of restarting at 0s.
  const workingSince = epochOrNull(lastActivity?.ts);
  const workingLabel = activity === null
    ? 'Trunk is working…'
    : activity.summary.length > 0
      ? `Running ${activity.name}: ${activity.summary}`
      : `Running ${activity.name}…`;
  const workingDetail = activity !== null && activity.summary.length > 0
    ? activity.summary
    : undefined;

  // Conversation density (Focused default / Full), persisted per user and
  // shared with the brain feed's toggle.
  const { value: fullDensity, setValue: setFullDensity } = usePersistentToggle('bq.convDensityFull');
  const density = fullDensity ? 'full' : 'focused';

  const conversation = (
    <Conv>
      <div className="sp-controls">
        <DensityToggle value={density} onChange={d => setFullDensity(d === 'full')} />
      </div>
      <TrunkFeed
        messages={messages}
        tasks={tasks}
        density={density}
        onOpenTask={onOpenTask}
        mergeReadyIds={mergeReadyIds}
        onAnswerQuestion={sendNow}
        trailer={(
          <>
            {liveThinking.length > 0 && (
              <Thought label="Thinking…" defaultOpen><Callout>{liveThinking}</Callout></Thought>
            )}
            {liveText.length > 0 && <EventRow kind="brain" who="Agent" markdown={liveText} />}
            <QueuedMessages
              messages={queue}
              onEdit={id => setText(takeForEdit(id))}
              onRemove={remove}
            />
            {working && liveText.length === 0 && (
              <Working
                label={workingLabel}
                detail={workingDetail}
                since={workingSince ?? undefined}
                onStop={() => { void window.bridge?.interruptTask(threadId).then(load).catch(() => { /* poll reconciles */ }); }}
              />
            )}
          </>
        )}
      />
    </Conv>
  );

  const active = tasks
    .filter(t => !TERMINAL_TASK_STATUSES.has(t.status))
    .map(t => toTaskCard(t, mergeReadyIds.has(t.id)));
  const closed = tasks.filter(t => TERMINAL_TASK_STATUSES.has(t.status)).map(t => toTaskCard(t, false));

  return (
    <TrunkPage
      threadId={threadId}
      thread={{ title: thread?.title ?? 'Thread' }}
      conversation={conversation}
      composer={{ value: text, onChange: setText, onSubmit: submit, busy: working, queueWhenBusy: true, placeholder: 'Discuss the next task, ask the brain, or paste an error…' }}
      tasks={{ active, closed }}
      onOpenTask={onOpenTask}
    />
  );
}
