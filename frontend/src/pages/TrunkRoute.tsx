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
import type {
  DiffFileDto, ThreadCommitDto, ThreadDto, ThreadMessageDto, WorkUnitTaskDto,
} from '../types';
import { Callout, Conv, EventRow, QueuedMessages, Thought, Working } from '../ui/conv';
import { useMessageQueue } from '../threads/useMessageQueue';
import { useThreadStream } from '../threads/useThreadStream';
import { ConvIndex } from '../threads/ConvIndex';
import { TrunkFeed } from '../threads/TrunkFeed';
import { buildTrunkTimeline, parseToolCall } from '../threads/trunkTimeline';
import { TERMINAL_TASK_STATUSES, toTaskCard } from '../threads/taskCardData';
import { proposalAction } from '../threads/usePendingShipProposal';
import { TrunkPage } from './TrunkPage';
import { WorkModelPill } from '../workspace/WorkModelPill';
import type { PermissionDecideHandler } from '../threads/PermissionCard';

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
export function TrunkRoute({ threadId, onOpenTask, onReviewTask, onWorkspaceResolved }: {
  threadId: string;
  onOpenTask: (taskId: string) => void;
  onReviewTask?: (taskId: string) => void;
  /** Reports the loaded thread's own workspace id — lets the caller's
   *  sidebar follow whichever workspace this thread actually belongs to
   *  (it may differ from whatever workspace the user last manually
   *  entered, e.g. when arriving here from a PR's linked-task chip). */
  onWorkspaceResolved?: (workspaceId: string) => void;
}) {
  const [thread, setThread] = useState<ThreadDto | null>(null);
  const [messages, setMessages] = useState<ThreadMessageDto[]>([]);
  const [tasks, setTasks] = useState<WorkUnitTaskDto[]>([]);
  const [taskArtifacts, setTaskArtifacts] = useState<{
    taskId: string;
    files: DiffFileDto[];
    commits: ThreadCommitDto[];
  } | null>(null);
  // Task ids whose PR has an open merge gate (ready to merge).
  const [mergeReadyIds, setMergeReadyIds] = useState<ReadonlySet<string>>(new Set());
  const [text, setText] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  // The caller switches threads by passing a new id (no remount, since
  // <TrunkRoute> isn't keyed). Reset synchronously so a stale-while-
  // revalidate flash never shows the PREVIOUS thread's content under the
  // new one's header, and stamp the id `load()` checks against below.
  const shownIdRef = useRef(threadId);
  if (shownIdRef.current !== threadId) {
    shownIdRef.current = threadId;
    setThread(null);
    setMessages([]);
    setTasks([]);
    setTaskArtifacts(null);
    setMergeReadyIds(new Set());
  }
  // Clear the "working" indicator once a new assistant reply lands, not when
  // the user's own message persists into the planning slice.
  const replyCount = messages.filter(
    m => m.taskId === null && m.role === 'assistant' && (m.type === 'text' || m.type === 'thinking')).length;
  const [awaitedAt, setAwaitedAt] = useState<number | null>(null);
  // A turn that ends WITHOUT ever producing a new reply (its tool calls got
  // denied/cancelled mid-flight, the session errored, …) would otherwise
  // leave this flag — and so the Working banner — stuck on forever, since
  // nothing else ever satisfies "a new reply landed". Track having actually
  // seen the backend reach RUNNING for this send; once it drops back out of
  // RUNNING after that with still no new reply, the turn is over, reply or
  // not, so stop waiting for one.
  const sawRunningRef = useRef(false);
  useEffect(() => {
    if (awaitedAt === null) {
      sawRunningRef.current = false;
      return;
    }
    if (replyCount > awaitedAt) {
      setAwaitedAt(null);
      sawRunningRef.current = false;
      return;
    }
    if (thread?.status === 'RUNNING') {
      sawRunningRef.current = true;
      return;
    }
    if (sawRunningRef.current) {
      setAwaitedAt(null);
      sawRunningRef.current = false;
    }
  }, [replyCount, awaitedAt, thread?.status]);
  // The agent is working whenever the thread process is RUNNING — not just
  // right after the user's own submit. An autonomous multi-step turn (cut a
  // task, run tools, reply, run more) keeps the indicator up the whole time,
  // even across intermediate messages, so a quiet gap never reads as dead.
  const working = busy || awaitedAt !== null || thread?.status === 'RUNNING';

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTask === undefined) return;
    const requestedId = threadId;
    try {
      const [t, page, taskList, notifs] = await Promise.all([
        bridge.getTask(threadId),
        bridge.getTaskIndex(threadId, { direction: 'initial' }),
        bridge.listTasksForThread(threadId),
        bridge.listNotificationsForThread(threadId),
      ]);
      // The user may have navigated to a different thread while this fetch
      // was in flight — a slow response for a thread they've since left
      // must never overwrite what's now on screen for the current one.
      if (shownIdRef.current !== requestedId) return;
      setThread(t);
      onWorkspaceResolved?.(t.workspaceId);
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
  }, [threadId, onWorkspaceResolved]);

  // Poll so the agent's reply (and any task it cuts) lands without a manual
  // reload — also a fallback if the live stream can't connect.
  useEffect(() => {
    void load();
    const id = window.setInterval(() => { void load(); }, 3000);
    return () => window.clearInterval(id);
  }, [load]);

  const latestTaskId = tasks.reduce<WorkUnitTaskDto | null>(
    (latest, task) => latest === null || task.seq > latest.seq ? task : latest,
    null,
  )?.id;

  useEffect(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (latestTaskId === undefined
      || bridge?.getTaskCumulativeDiff === undefined
      || bridge.listTaskCommits === undefined) {
      setTaskArtifacts(null);
      return;
    }
    let cancelled = false;
    void Promise.allSettled([
      bridge.getTaskCumulativeDiff(threadId, latestTaskId),
      bridge.listTaskCommits(threadId, latestTaskId),
    ]).then(([files, commits]) => {
      if (cancelled) return;
      setTaskArtifacts({
        taskId: latestTaskId,
        files: files.status === 'fulfilled' ? files.value : [],
        commits: commits.status === 'fulfilled' ? commits.value : [],
      });
    });
    return () => { cancelled = true; };
  }, [latestTaskId, threadId]);

  // Live SSE stream: the agent's reasoning + reply appear token-by-token as
  // they're generated, instead of only when a poll fires after the turn. The
  // canonical messages refresh + the live buffers flush once the turn lands.
  const { liveText, liveThinking, liveActivities } = useThreadStream(threadId, thread?.status, load);

  const sendNow = useCallback((body: string, sendImages: string[] = []) => {
    setBusy(true);
    setAwaitedAt(replyCount);
    window.bridge.sendTrunkMessage(threadId, body, sendImages)
      .then(() => load())
      .catch(() => { setAwaitedAt(null); })
      .finally(() => setBusy(false));
  }, [threadId, load, replyCount]);

  // Answer a permission prompt the trunk agent raised (e.g. a Bash tool call
  // that isn't provably read-only) — previously answerable only by waiting
  // out the backend's own approval timeout, since this screen had no card
  // for it at all.
  const onDecidePermission = useCallback<PermissionDecideHandler>((callId, decision, preApprove) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge) {
      return;
    }
    void bridge.decideTaskPermission(threadId, callId, decision, preApprove)
      .then(() => load())
      .catch(() => { /* the next poll re-reflects the gate state */ });
  }, [threadId, load]);
  // Messages typed while the trunk is working queue up and auto-send when it
  // goes idle; the user can pull one back into the composer to edit it.
  const { queue, enqueue, takeForEdit, remove } = useMessageQueue(working, sendNow);
  const submit = () => {
    const body = text.trim();
    if (body.length === 0 && images.length === 0) return;
    // Queued (while-busy) sends are text-only — the queue auto-sends via
    // sendNow(text) with no images param, and images can't safely wait
    // behind an in-flight turn without also holding the pasted bytes in the
    // queue. Simplest correct behaviour: an image attachment just waits for
    // the composer to free up instead of queueing.
    if (working && images.length > 0) return;
    setText('');
    if (working) enqueue(body);
    else {
      sendNow(body, images);
      setImages([]);
    }
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
  const currentLiveActivity = [...liveActivities].reverse().find(item => !item.done);
  const workingSince = currentLiveActivity?.startedAt ?? epochOrNull(lastActivity?.ts);
  const workingLabel = currentLiveActivity !== undefined
    ? `${currentLiveActivity.label}${currentLiveActivity.detail === null ? '…' : `: ${currentLiveActivity.detail}`}`
    : activity === null
    ? 'Trunk is working…'
    : activity.summary.length > 0
      ? `Running ${activity.name}: ${activity.summary}`
      : `Running ${activity.name}…`;
  const workingDetail = currentLiveActivity?.detail
    ?? (activity !== null && activity.summary.length > 0
    ? activity.summary
    : undefined);

  // Scroll host for the conversation-index rail's click-to-jump.
  const conversationRef = useRef<HTMLDivElement | null>(null);
  // The rail only lists prompts that have a visible row to jump to: the
  // planning segment which produced the newest cut remains open, as does
  // any not-yet-cut conversation after it. Older cut segments stay compact.
  const visibleSeqs = (() => {
    const timeline = buildTrunkTimeline(messages, tasks);
    const cuts = timeline.filter(item => item.kind === 'cut');
    const latestTaskId = cuts[cuts.length - 1]?.cut.task.id;
    const visible: number[] = [];
    let segment: number[] = [];
    for (const item of timeline) {
      if (item.kind === 'round' && item.round.userTurn !== null) {
        segment.push(item.round.userTurn.seq);
      }
      else if (item.kind === 'cut') {
        if (item.cut.task.id === latestTaskId) visible.push(...segment);
        segment = [];
      }
    }
    visible.push(...segment);
    return new Set(visible);
  })();

  const conversation = (
    <Conv scrollRef={conversationRef}>
      <TrunkFeed
        messages={messages}
        tasks={tasks}
        density="focused"
        onOpenTask={onOpenTask}
        mergeReadyIds={mergeReadyIds}
        onAnswerQuestion={sendNow}
        onDecidePermission={onDecidePermission}
        artifactsByTaskId={taskArtifacts === null ? undefined : new Map([[
          taskArtifacts.taskId,
          {
            files: taskArtifacts.files,
            commits: taskArtifacts.commits,
            onReview: onReviewTask === undefined
              ? undefined : () => onReviewTask(taskArtifacts.taskId),
            // decision pending: there is no safe undo mutation yet. Open the
            // existing diff surface instead of silently changing git state.
            onUndo: onReviewTask === undefined
              ? undefined : () => onReviewTask(taskArtifacts.taskId),
          },
        ]])}
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
                activities={liveActivities}
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
  // Every finished task folds into the compact top history — including the
  // newest one. The branch rail below stays expanded only for the newest task
  // still in flight, so a completed task never lingers open in the feed.
  const historyTasks = tasks
    .filter(task => TERMINAL_TASK_STATUSES.has(task.status))
    .map(task => toTaskCard(task, false));

  return (
    <TrunkPage
      threadId={threadId}
      thread={{
        title: thread?.title ?? 'Thread',
        status: thread?.status,
        branch: tasks.find(task => !TERMINAL_TASK_STATUSES.has(task.status))?.branchName ?? null,
        workspaceId: thread?.workspaceId,
      }}
      conversation={conversation}
      conversationIndex={(
        <ConvIndex
          threadId={threadId}
          scrollContainerRef={conversationRef}
          side="left"
          restrictToSeqs={visibleSeqs}
        />
      )}
      composer={{
        value: text, onChange: setText, onSubmit: submit, busy: working, queueWhenBusy: true,
        placeholder: 'Do anything — ask the brain, cut a task, or paste an error…',
        images, onImagesChange: setImages,
        modePill: <WorkModelPill scope={{ kind: 'thread', threadId }} variant="workspace-v2"
          agentLockPending={working} />,
      }}
      tasks={{ active, closed }}
      historyTasks={historyTasks}
      onOpenTask={onOpenTask}
    />
  );
}
