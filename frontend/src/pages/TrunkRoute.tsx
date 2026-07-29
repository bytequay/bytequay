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
  DiffFileDto, ThreadCommitDto, ThreadDto, ThreadMessageDto, ThreadTurnDto,
  TrunkTraceEventDto, TypedPermissionRequestDto, WorkUnitTaskDto,
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
import { PermissionCard, type PermissionDecideHandler } from '../threads/PermissionCard';

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

const ACTIVE_TURN_STATUSES = new Set<ThreadTurnDto['status']>(['QUEUED', 'RUNNING']);
const TERMINAL_TURN_STATUSES = new Set<ThreadTurnDto['status']>(['COMPLETED', 'FAILED', 'CANCELLED']);

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
  const [traceEvents, setTraceEvents] = useState<TrunkTraceEventDto[]>([]);
  const [turns, setTurns] = useState<ThreadTurnDto[]>([]);
  const [typedPermissions, setTypedPermissions] = useState<TypedPermissionRequestDto[]>([]);
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
  const [awaitedAt, setAwaitedAt] = useState<number | null>(null);
  const [awaitedTurnId, setAwaitedTurnId] = useState<string | null>(null);
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
    setTraceEvents([]);
    setTurns([]);
    setTypedPermissions([]);
    setTasks([]);
    setTaskArtifacts(null);
    setMergeReadyIds(new Set());
    setAwaitedAt(null);
    setAwaitedTurnId(null);
    setResuming(false);
    setResumeError(null);
  }
  // Clear the "working" indicator once a new assistant reply lands, not when
  // the user's own message persists into the planning slice.
  const replyCount = messages.filter(
    m => m.taskId === null && m.role === 'assistant' && (m.type === 'text' || m.type === 'thinking')).length;
  // A turn that ends WITHOUT ever producing a new reply (its tool calls got
  // denied/cancelled mid-flight, the session errored, …) would otherwise
  // leave this flag — and so the Working banner — stuck on forever, since
  // nothing else ever satisfies "a new reply landed". The send receipt names
  // the exact legacy-or-V2 Trunk turn; its own terminal state is authoritative
  // even though V2 deliberately does not mutate the legacy Thread.status row.
  useEffect(() => {
    if (awaitedAt === null) {
      return;
    }
    if (replyCount > awaitedAt) {
      setAwaitedAt(null);
      setAwaitedTurnId(null);
      return;
    }
    const awaitedTurn = turns.find(turn => turn.id === awaitedTurnId);
    if (awaitedTurn !== undefined && TERMINAL_TURN_STATUSES.has(awaitedTurn.status)) {
      setAwaitedAt(null);
      setAwaitedTurnId(null);
    }
  }, [replyCount, awaitedAt, awaitedTurnId, turns]);
  // /turns is the compatibility projection over legacy scheduler turns and
  // typed V2 turns. Only a Trunk-scoped row (taskId === null) can make this
  // route busy; running Task/Stage siblings remain isolated.
  const activeTrunkTurn = turns.find(
    turn => turn.taskId === null && turn.status === 'RUNNING')
    ?? turns.find(
      turn => turn.taskId === null && ACTIVE_TURN_STATUSES.has(turn.status));
  const working = busy || awaitedAt !== null || activeTrunkTurn !== undefined;

  const load = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTask === undefined) return;
    const requestedId = threadId;
    try {
      const pagePromise = bridge.getTaskIndex(threadId, { direction: 'initial' });
      const tracePromise = pagePromise.then(page => {
        const requestMessageIds = page.messages
          .filter(message => message.seq < 0 && message.role === 'user' && message.type === 'text')
          .map(message => message.id);
        if (bridge.getTrunkTraceEvents === undefined) return null;
        return bridge.getTrunkTraceEvents(threadId, requestMessageIds).catch((): null => null);
      });
      const [t, page, taskList, notifs, turnList, permissions, traces] = await Promise.all([
        bridge.getTask(threadId),
        pagePromise,
        bridge.listTasksForThread(threadId),
        bridge.listNotificationsForThread(threadId),
        bridge.getTaskTurns(threadId).catch((): null => null),
        bridge.getTypedPermissions(threadId).catch((): null => null),
        tracePromise,
      ]);
      // The user may have navigated to a different thread while this fetch
      // was in flight — a slow response for a thread they've since left
      // must never overwrite what's now on screen for the current one.
      if (shownIdRef.current !== requestedId || !mountedRef.current) return;
      setThread(t);
      onWorkspaceResolved?.(t.workspaceId);
      setMessages(page.messages);
      if (traces !== null) setTraceEvents(traces);
      setTasks(taskList);
      if (turnList !== null) setTurns(turnList);
      if (permissions !== null) setTypedPermissions(permissions);
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
  const { liveText, liveThinking, liveActivities } = useThreadStream(
    threadId, working ? 'RUNNING' : thread?.status, load);

  const sendNow = useCallback((body: string, sendImages: string[] = []) => {
    setBusy(true);
    setAwaitedAt(replyCount);
    setAwaitedTurnId(null);
    window.bridge.sendTrunkMessage(threadId, body, sendImages)
      .then(result => {
        setAwaitedTurnId(result.turnId);
        return load();
      })
      .catch(() => {
        setAwaitedAt(null);
        setAwaitedTurnId(null);
      })
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
    const revision = typedPermissions.find(
      permission => permission.callId === callId)?.answerRevision;
    void bridge.decideTaskPermission(threadId, callId, decision, preApprove, revision)
      .then(() => load())
      .catch(() => { /* the next poll re-reflects the gate state */ });
  }, [threadId, typedPermissions, load]);
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
  const workingSince = currentLiveActivity?.startedAt
    ?? epochOrNull(activeTrunkTurn?.startedAt ?? activeTrunkTurn?.createdAt ?? lastActivity?.ts);
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

  // Typed permissions are durable domain objects, not transcript messages.
  // Render only this Trunk's own gate here; Task/Stage gates belong to their
  // exact routes even though the endpoint returns every open gate in the tree.
  const typedTrunkPermission = typedPermissions.find(
    permission => permission.ownerKind === 'THREAD_TURN');

  const conversation = (
    <Conv scrollRef={conversationRef}>
      <TrunkFeed
        messages={messages}
        tracesByRequestMessageId={traceEvents.reduce((byRequest, trace) => {
          const rows = byRequest.get(trace.requestMessageId) ?? [];
          rows.push(trace);
          byRequest.set(trace.requestMessageId, rows);
          return byRequest;
        }, new Map<string, TrunkTraceEventDto[]>())}
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
            {typedTrunkPermission !== undefined && (
              <PermissionCard
                key={typedTrunkPermission.id}
                permission={{
                  callId: typedTrunkPermission.callId,
                  toolName: typedTrunkPermission.toolName,
                  summary: typedTrunkPermission.parametersJson,
                }}
                onDecide={onDecidePermission}
              />
            )}
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
                onStop={() => { void window.bridge?.interruptTask(
                  threadId, activeTrunkTurn?.id ?? awaitedTurnId ?? undefined,
                ).then(load).catch(() => { /* poll reconciles */ }); }}
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
        description: thread?.description,
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
