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
import { buildTrunkTimeline, extractText, parseToolCall } from '../threads/trunkTimeline';
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

const DIRECT_CONTINUATION = /^(?:(?:do you )?want (?:me|us) to|would you like (?:me|us) to|shall (?:i|we)|should (?:i|we)|may i|can (?:i|we)|ready for (?:me|us) to|is it (?:ok|okay) if (?:i|we)|(?:do you want|would you like|are you ready) to (?:continue|proceed|start))\b/i;
const CUT_TASK_CONFIRMATION = /^cut\s+(?:this|that|it)\s+(?:as|into)\s+(?:(?:an?|the)\s+)?[^?]*\btask(?:s)?\b(?:\s+(?:now|next))?$/i;

/** True only for a direct yes/no offer to continue. A generic trailing
 *  question ("Which branch?") needs a real answer, not "go ahead". */
function offersToContinue(text: string): boolean {
  const normalized = text.replace(/[*_`~>]/g, '').replace(/\s+/g, ' ').trim();
  if (!normalized.endsWith('?')) return false;
  const beforeQuestion = normalized.slice(0, -1);
  let questionStart = 0;
  for (const separator of ['. ', '! ', '? ', ': ', ' — ', ' – ']) {
    const at = beforeQuestion.lastIndexOf(separator);
    if (at >= 0) questionStart = Math.max(questionStart, at + separator.length);
  }
  const question = beforeQuestion.slice(questionStart).trim();
  return !/\bor\b/i.test(question)
    && (DIRECT_CONTINUATION.test(question) || CUT_TASK_CONFIRMATION.test(question));
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
  const [resuming, setResuming] = useState(false);
  const [resumeError, setResumeError] = useState<string | null>(null);

  // The caller switches threads by passing a new id (no remount, since
  // <TrunkRoute> isn't keyed). Reset synchronously so a stale-while-
  // revalidate flash never shows the PREVIOUS thread's content under the
  // new one's header, and stamp the id `load()` checks against below.
  // True until this TrunkRoute instance unmounts (the user navigates away
  // from thread-detail entirely, not just to a different thread — that case
  // is shownIdRef's job below). load()'s fetch can resolve after that, and
  // onWorkspaceResolved is a PARENT callback: calling it post-navigation
  // would silently overwrite the sidebar's workspace with this stale
  // thread's, with nothing left to ever reset it back since the sidebar
  // only clears it when the viewed thread id itself changes.
  const mountedRef = useRef(true);
  useEffect(() => () => { mountedRef.current = false; }, []);
  const shownIdRef = useRef(threadId);
  if (shownIdRef.current !== threadId) {
    shownIdRef.current = threadId;
    setThread(null);
    setMessages([]);
    setTasks([]);
    setTaskArtifacts(null);
    setMergeReadyIds(new Set());
    setResuming(false);
    setResumeError(null);
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
  // The trunk is working only while its OWN turn is in flight. thread.status
  // alone is wrong: a cut task and its trunk share one threads row, so a running
  // task STAGE flips thread.status to RUNNING even after the trunk's turn ended —
  // which lit "Trunk is working" for the task's work. Gate on BOTH the shared
  // row being RUNNING and the newest trunk-scope (taskId === null) row not being
  // turn-terminal: once the trunk turn closes it leaves a turn_done as its last
  // trunk row, so subsequent stage activity (which appends only task-scoped
  // rows) can't masquerade as the trunk working. Still holds the indicator up
  // across a multi-step trunk turn, since that leaves a non-terminal tail.
  const lastTrunkRow = [...messages].reverse().find(m => m.taskId === null);
  const trunkTurnRunning = thread?.status === 'RUNNING'
    && lastTrunkRow !== undefined
    && lastTrunkRow.type !== 'turn_done'
    && lastTrunkRow.type !== 'session_ended'
    && lastTrunkRow.type !== 'error';
  const working = busy || awaitedAt !== null || trunkTurnRunning;

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
      if (shownIdRef.current !== requestedId || !mountedRef.current) return;
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

  const resume = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.resumeTask === undefined) return;
    setResuming(true);
    setResumeError(null);
    try {
      await bridge.resumeTask(threadId);
      await load();
    }
    catch (error) {
      setResumeError(error instanceof Error ? error.message : String(error));
    }
    finally {
      setResuming(false);
    }
  }, [threadId, load]);

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
  const submit = (override?: string) => {
    const body = (override ?? text).trim();
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

  // Plain-text fallback for older/non-structured agent questions like
  // "Want me to put up the plan?". Ignore terminal markers, but let any
  // newer user/tool/permission/error row suppress the stale offer.
  const latestTrunkRow = [...messages].reverse().find(message =>
    message.taskId === null && message.type !== 'turn_done' && message.type !== 'session_ended');
  const suggestedReply = latestTrunkRow?.role === 'assistant'
    && latestTrunkRow.type === 'text'
    && offersToContinue(extractText(latestTrunkRow.contentJson))
    ? 'go ahead'
    : undefined;

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
        errorMessage: thread?.errorMessage,
        branch: tasks.find(task => !TERMINAL_TASK_STATUSES.has(task.status))?.branchName ?? null,
        workspaceId: thread?.workspaceId,
      }}
      onResume={resume}
      resuming={resuming}
      resumeError={resumeError}
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
        suggestedReply,
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
