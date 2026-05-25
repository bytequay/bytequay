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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  ThreadDto,
  ThreadMessageDto,
  ThreadSettingsDto,
  ThreadTurnDto,
  UserProfileDto,
  WorkUnitTaskDto,
} from '../types';
import TrunkChat from './TrunkChat';
import { ConvIndex } from './ConvIndex';
import { useThreadTasks } from './useThreadTasks';
import { ConfirmDialog } from '../workspace/ConfirmDialog';

type Props = {
  threadId: string;
  onBack: () => void;
  /** Enter a specific task's window (Open → / double-click). Phase 3
   *  replaces the current ThreadDetailPage with the proper task-detail
   *  shell; until then this still routes to that page with the focused
   *  taskId so the user can pick up the agent conversation. */
  onOpenTask: (taskId: string) => void;
};

const ACTIVE_STATUSES = new Set([
  'PENDING', 'RUNNING', 'AWAITING', 'IDLE',
  'AWAITING_REVIEW', 'NEEDS_ATTENTION',
]);

/** Tail-window size for the trunk transcript fetch. Trunk threads
 *  are short by design (planning altitude) so a 200-message window
 *  covers most cases on the first paint; the "Load earlier" button
 *  pulls another window backward when needed. */
const TRUNK_INITIAL_LIMIT = 200;

/** Merge two ordered-by-seq message lists, deduping by seq. Used by
 *  the paginated transcript: refresh overwrites tail entries; load-
 *  older prepends. */
function mergeMessages(
  older: ThreadMessageDto[],
  newer: ThreadMessageDto[],
): ThreadMessageDto[] {
  if (older.length === 0) return newer;
  if (newer.length === 0) return older;
  const bySeq = new Map<number, ThreadMessageDto>();
  for (const m of older) bySeq.set(m.seq, m);
  for (const m of newer) bySeq.set(m.seq, m);
  return Array.from(bySeq.values()).sort((a, b) => a.seq - b.seq);
}

/**
 * Trunk window for a Thread — the planning altitude. Owns no branch
 * and no diff; the conversation here is the cross-task plan, and the
 * left rail is the orchestration surface (tasks lane, Next/Ship,
 * vitals, scheduler). Per the workspace/thread/task design doc, this
 * is one of two configurations of the same shell — the other is the
 * task-detail window (Phase 3).
 *
 * <p>Identity trio (per the design's "made unmistakable at a glance"
 * rule): full-height slate spine, slate altitude band, and a
 * "Replying in the thread · planning" composer anchor.
 */
export default function ThreadTrunkPage({ threadId, onBack, onOpenTask }: Props) {
  const [thread, setThread] = useState<ThreadDto | null>(null);
  const [threadError, setThreadError] = useState<string | null>(null);
  // Resolved cwd the trunk session will spawn the CLI in — first
  // pinned-repo local clone path in the active workspace. Mirrors
  // the backend resolver in ThreadRegistry; shown in the altitude
  // band so the user can confirm the agent is rooted in the right
  // repo at a glance.
  const [trunkCwd, setTrunkCwd] = useState<string | null>(null);
  const { tasks, error: tasksError, refresh: refreshTasks } = useThreadTasks(threadId);
  const [turns, setTurns] = useState<ThreadTurnDto[] | null>(null);
  const [messages, setMessages] = useState<ThreadMessageDto[] | null>(null);
  // Pagination cursor for the transcript — tracks the smallest seq
  // currently loaded. The chat starts with a tail window and the
  // user expands the history via the "Load earlier" button.
  const [loadedFromSeq, setLoadedFromSeq] = useState<number | null>(null);
  const [canLoadOlder, setCanLoadOlder] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  // Captured from TrunkChat via its {@code outerRef} prop so the
  // floating ConvIndex panel can scroll specific user rows into view
  // without TrunkChat having to know about the index panel.
  const chatScrollRef = useRef<HTMLDivElement | null>(null);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [advancing, setAdvancing] = useState<'next' | 'ship' | null>(null);
  const [advanceError, setAdvanceError] = useState<string | null>(null);
  const [interrupting, setInterrupting] = useState<boolean>(false);
  const [composerInput, setComposerInput] = useState<string>(() => {
    // Seed once on mount from a sessionStorage draft the
    // create-thread dialog may have stashed for this thread. The
    // dialog uses the same key shape; clearing here keeps a reload
    // from re-staging the same text.
    try {
      const key = `bq:trunk-draft:${threadId}`;
      const stash = window.sessionStorage.getItem(key);
      if (stash !== null) {
        window.sessionStorage.removeItem(key);
        return stash;
      }
    }
    catch { /* private mode / no storage — fall through */ }
    return '';
  });
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [profile, setProfile] = useState<UserProfileDto | null>(null);

  useEffect(() => {
    void window.bridge.getUserProfile()
      .then(p => setProfile(p))
      .catch(() => { /* avatars fall back to "??" */ });
  }, []);
  const userInitials = useMemo(() => initialsFor(profile), [profile]);

  const loadThread = useCallback(async () => {
    try {
      const t = await window.bridge.getTask(threadId);
      setThread(t);
      setThreadError(null);
    }
    catch (e) {
      setThreadError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId]);

  useEffect(() => {
    void loadThread();
  }, [loadThread]);

  // Resolve the trunk cwd from the thread's workspace once we know it.
  // Mirrors backend logic in ThreadRegistry.resolveTrunkCwdForWorkspace:
  // pick the first repo pinned to this workspace whose watched-repo
  // row has a non-blank localClonePath.
  useEffect(() => {
    const workspaceId = thread?.workspaceId;
    if (workspaceId === undefined || workspaceId === null) {
      setTrunkCwd(null);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const [pinned, watched] = await Promise.all([
          window.bridge.listWorkspaceRepos(workspaceId),
          window.bridge.getWatchedRepos(),
        ]);
        if (cancelled) return;
        const pinnedSet = new Set(pinned.map(p => p.repoFullName));
        const match = watched.find(wr =>
            pinnedSet.has(`${wr.owner}/${wr.repo}`)
            && wr.localClonePath != null
            && wr.localClonePath.trim().length > 0);
        setTrunkCwd(match?.localClonePath ?? null);
      }
      catch {
        if (!cancelled) setTrunkCwd(null);
      }
    })();
    return () => { cancelled = true; };
  }, [thread?.workspaceId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.getTaskTurns(threadId);
        if (!cancelled) setTurns(list);
      }
      catch {
        if (!cancelled) setTurns([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  useEffect(() => {
    let cancelled = false;
    // Reset pagination state on thread switch so stale rows from the
    // previous thread can't briefly render against the new one.
    setMessages(null);
    setLoadedFromSeq(null);
    setCanLoadOlder(false);
    void (async () => {
      try {
        const page = await window.bridge.getTaskIndex(threadId, {
          direction: 'initial',
          limit: TRUNK_INITIAL_LIMIT,
        });
        if (cancelled) return;
        setMessages(page.messages);
        setLoadedFromSeq(page.loadedFromSeq);
        setCanLoadOlder(page.loadedFromSeq !== null && page.loadedFromSeq > 1);
      }
      catch {
        if (!cancelled) setMessages([]);
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // Trunk-planning slice of the transcript. {@code taskId === null}
  // is the documented marker for cross-task planning rows; per-task
  // rows belong to the task-detail window, not the trunk.
  const trunkMessages = useMemo(
    () => messages === null ? [] : messages.filter(m => m.taskId === null),
    [messages]);
  const orderedTasksAsc = useMemo(
    () => tasks === null ? [] : [...tasks].sort((a, b) => a.seq - b.seq),
    [tasks]);

  // Pre-select the foreground (newest non-terminal) task so Next/Ship
  // have a target without the user having to click first. Clear the
  // selection again if the list goes empty — a stale id from a deleted
  // task would otherwise keep the buttons enabled with nothing to act on.
  useEffect(() => {
    if (tasks === null) return;
    if (tasks.length === 0) {
      if (selectedTaskId !== null) setSelectedTaskId(null);
      return;
    }
    if (selectedTaskId !== null && tasks.some(t => t.id === selectedTaskId)) {
      return;
    }
    const foreground = newestActiveTask(tasks);
    if (foreground !== null) {
      setSelectedTaskId(foreground.id);
    }
    else {
      setSelectedTaskId(null);
    }
  }, [tasks, selectedTaskId]);

  const orderedTasks = useMemo(
    () => tasks === null ? [] : [...tasks].sort((a, b) => b.seq - a.seq),
    [tasks]);
  const foreground = useMemo(
    () => tasks === null ? null : newestActiveTask(tasks), [tasks]);
  const parkedCount = useMemo(
    () => tasks === null ? 0 : tasks.filter(
      t => t.status === 'AWAITING_REVIEW' || t.status === 'NEEDS_ATTENTION').length,
    [tasks]);
  const needsAttention = useMemo(
    () => tasks === null ? [] : tasks.filter(t => t.status === 'NEEDS_ATTENTION'),
    [tasks]);
  const scheduler = useMemo(() => summariseScheduler(turns), [turns]);
  const isReviewFlow = thread?.flow === 'review';

  const refreshMessages = useCallback(async () => {
    try {
      // Refresh only re-fetches the tail window. Older rows already
      // loaded via "Load earlier" are preserved via mergeMessages;
      // the latest tail just overwrites by seq.
      const page = await window.bridge.getTaskIndex(threadId, {
        direction: 'initial',
        limit: TRUNK_INITIAL_LIMIT,
      });
      setMessages(prev => mergeMessages(prev ?? [], page.messages));
    }
    catch { /* keep last good list */ }
  }, [threadId]);

  const loadOlderMessages = useCallback(async () => {
    if (loadedFromSeq === null || loadingOlder) return;
    setLoadingOlder(true);
    try {
      const page = await window.bridge.getTaskIndex(threadId, {
        direction: 'before',
        cursor: loadedFromSeq,
        limit: TRUNK_INITIAL_LIMIT,
      });
      setMessages(prev => mergeMessages(page.messages, prev ?? []));
      setLoadedFromSeq(page.loadedFromSeq);
      setCanLoadOlder(page.nextCursor !== null);
    }
    catch { /* keep last good list */ }
    finally {
      setLoadingOlder(false);
    }
  }, [threadId, loadedFromSeq, loadingOlder]);

  const refreshTurns = useCallback(async () => {
    try {
      const list = await window.bridge.getTaskTurns(threadId);
      setTurns(list);
    }
    catch { /* keep last good list */ }
  }, [threadId]);

  // Tail poll while a turn is in flight: the trunk send returns as
  // soon as the row is queued, but the CLI subprocess takes a few
  // seconds to spawn + respond. Without this, the user only ever
  // sees the messages on screen at the moment they hit Send and the
  // AI response never appears until they navigate away and back.
  // Stops as soon as there are no QUEUED or RUNNING turns.
  const hasInFlight = useMemo(
    () => (turns ?? []).some(t => t.status === 'QUEUED' || t.status === 'RUNNING'),
    [turns]);
  useEffect(() => {
    if (!hasInFlight && !sending) return;
    const handle = window.setInterval(() => {
      void refreshMessages();
      void refreshTurns();
      void loadThread();
    }, 2_500);
    return () => window.clearInterval(handle);
  }, [hasInFlight, sending, refreshMessages, refreshTurns, loadThread]);

  const onInterrupt = useCallback(async () => {
    if (interrupting) return;
    setInterrupting(true);
    setSendError(null);
    try {
      await window.bridge.interruptTask(threadId);
      // The interrupt is asynchronous on the backend — the agent
      // process gets SIGINT and the turn flips to a terminal state
      // soon after. Pull the latest turn+message lists so the
      // thinking pulse drops as the in-flight count goes to zero.
      await Promise.all([refreshMessages(), refreshTurns(), loadThread()]);
    }
    catch (e) {
      setSendError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setInterrupting(false);
    }
  }, [interrupting, threadId, refreshMessages, refreshTurns, loadThread]);

  const onSendTrunk = useCallback(async () => {
    const text = composerInput.trim();
    if (text.length === 0 || sending) return;
    setSending(true);
    setSendError(null);
    try {
      await window.bridge.sendTrunkMessage(threadId, text);
      setComposerInput('');
      // Pull both lists so the tail poll's hasInFlight check sees
      // the freshly-enqueued QUEUED turn and kicks the interval in.
      await Promise.all([refreshMessages(), refreshTurns()]);
    }
    catch (e) {
      setSendError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSending(false);
    }
  }, [composerInput, sending, threadId, refreshMessages]);

  const onAdvance = useCallback(async (mode: 'next' | 'ship') => {
    if (foreground === null || advancing !== null) return;
    const verb = mode === 'next' ? 'Park & start next' : 'Ship';
    const ok = window.confirm(
      `${verb}: task ${foreground.seq}`
      + (foreground.branchName !== null ? ` (${foreground.branchName})` : '')
      + (mode === 'next'
        ? ' — parked at AWAITING_REVIEW; new task cut from main.'
        : ' — closes the task and reaps the worktree.'));
    if (!ok) return;
    setAdvancing(mode);
    setAdvanceError(null);
    try {
      if (mode === 'next') {
        await window.bridge.parkAndStartNext(threadId, foreground.id);
      }
      else {
        await window.bridge.shipAndContinue(threadId, foreground.id);
      }
      await Promise.all([loadThread(), refreshTasks()]);
    }
    catch (e) {
      setAdvanceError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setAdvancing(null);
    }
  }, [foreground, advancing, threadId, loadThread, refreshTasks]);

  const title = thread?.title ?? 'Loading…';
  const taskCount = tasks?.length ?? 0;

  return (
    <div style={pageStyle}>
      <div style={meshBgStyle} aria-hidden />
      <div style={noiseBgStyle} aria-hidden />
      <div style={spineStyle} aria-hidden />

      <div style={contentColStyle}>
        <header style={headerStyle}>
          <button type="button" onClick={onBack} style={backBtnStyle}>← Threads</button>
          <span style={titleStyle}>{title}</span>
          {thread !== null && (
            <span style={statusPillStyle(thread.status)}>
              <span style={statusDotStyle(thread.status)} aria-hidden />
              {thread.status}
              {taskCount > 0 && ` · ${taskCount} task${taskCount === 1 ? '' : 's'}`}
            </span>
          )}
        </header>

        <div style={altitudeBandStyle}>
          <span style={bandGlyphStyle}>◆ THREAD · trunk</span>
          <span style={bandTitleStyle}>{title}</span>
          {trunkCwd !== null && (
            <span style={bandCwdStyle} title={trunkCwd}>
              <span style={bandCwdLabelStyle}>cwd</span>
              <span style={bandCwdPathStyle}>{shortCwd(trunkCwd)}</span>
            </span>
          )}
          <span style={bandHintStyle}>
            {thread?.flow === 'review'
              ? 'review flow · references a PR · multi-agent panel'
              : 'planning & orchestration · no branch'}
          </span>
        </div>

        <div style={bodyGridStyle}>
          <aside style={railStyle}>
            {needsAttention.length > 0 && (
              <section style={attentionBannerStyle}>
                <div style={attentionTitleStyle}>
                  ⚠ {needsAttention.length} task{needsAttention.length === 1 ? '' : 's'} need{needsAttention.length === 1 ? 's' : ''} you
                </div>
                <ul style={attentionListStyle}>
                  {needsAttention.map(t => (
                    <li key={t.id} style={attentionRowStyle}>
                      <span style={attentionLabelStyle}>
                        {taskLabel(t)} · seq {t.seq}
                      </span>
                      <button
                        type="button"
                        onClick={() => onOpenTask(t.id)}
                        style={attentionJumpBtnStyle}
                        title="Jump into this task and take the lease"
                      >
                        Jump in →
                      </button>
                    </li>
                  ))}
                </ul>
                <div style={attentionHintStyle}>
                  Automation parked these at NEEDS_ATTENTION. Jump in to take
                  the lease from the headless worker.
                </div>
              </section>
            )}

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>TASKS IN THIS THREAD</span>
                {parkedCount > 0 && <span style={railHeadMutedStyle}>{parkedCount} parked</span>}
              </div>
              {tasksError !== null && (
                <div style={errStyle}>Could not load tasks: {tasksError}</div>
              )}
              {tasks !== null && tasks.length === 0 && (
                <div style={emptyStyle}>
                  No tasks yet — this thread is in brainstorm mode. The first
                  coding turn will materialise <em>Task 1</em>.
                </div>
              )}
              {orderedTasks.length > 0 && (
                <ul style={listStyle}>
                  {orderedTasks.map(t => (
                    <TaskCard
                      key={t.id}
                      task={t}
                      selected={t.id === selectedTaskId}
                      isForeground={foreground?.id === t.id}
                      onSelect={() => setSelectedTaskId(t.id)}
                      onOpen={() => onOpenTask(t.id)}
                    />
                  ))}
                </ul>
              )}
              {isReviewFlow ? (
                <div style={reviewFlowNoticeStyle}>
                  This is a review thread — the panel reviews a PR rather than
                  cutting branches. Next / Ship apply only to build flows.
                </div>
              ) : (() => {
                // Both buttons need a target task. Disable whenever the
                // thread has no foreground (zero tasks, or every task
                // terminal) or the user hasn't picked one.
                const noTarget = foreground === null || selectedTaskId === null;
                const noTargetReason = (tasks?.length ?? 0) === 0
                  ? 'No tasks yet — start one before parking or shipping'
                  : 'Select a task to park or ship';
                return (
                <>
                  <div style={advanceRowStyle}>
                    <button
                      type="button"
                      onClick={() => { void onAdvance('next'); }}
                      disabled={noTarget || advancing !== null}
                      style={nextBtnStyle(noTarget || advancing !== null)}
                      title={noTarget
                        ? noTargetReason
                        : `Next: park task ${foreground!.seq} at AWAITING_REVIEW and start the next from main`}
                    >
                      {advancing === 'next' ? 'Parking…' : 'Next →'}
                    </button>
                    <button
                      type="button"
                      onClick={() => { void onAdvance('ship'); }}
                      disabled={noTarget || advancing !== null}
                      style={shipBtnStyle(noTarget || advancing !== null)}
                      title={noTarget
                        ? noTargetReason
                        : `Ship: finalise task ${foreground!.seq} (worktree reaps)`}
                    >
                      {advancing === 'ship' ? 'Shipping…' : 'Ship'}
                    </button>
                  </div>
                  <div style={advanceHintStyle}>
                    Next parks &amp; starts next · Ship finalises this task
                  </div>
                </>
                );
              })()}
              {advanceError !== null && (
                <div style={errStyle}>{advanceError}</div>
              )}
            </section>

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>THREAD VITALS</span>
                <span style={railHeadMutedStyle}>aggregated</span>
              </div>
              <VitalsTable thread={thread} />
            </section>

            <section style={railSectionStyle}>
              <div style={railHeadStyle}>
                <span>SCHEDULER</span>
                <span style={railHeadMutedStyle}>fair-share</span>
              </div>
              <SchedulerTable summary={scheduler} />
            </section>

            <CheckpointsSection threadId={threadId} />

            <SettingsSection threadId={threadId} />

            <DangerZoneSection threadId={threadId} onDeleted={onBack} />
          </aside>

          <main style={mainStyle}>
            <div style={chatCardStyle}>
              {messages === null ? (
                <div style={planningPlaceholderStyle}>
                  <p style={planningBodyStyle}>Loading planning conversation…</p>
                </div>
              ) : trunkMessages.length === 0 && orderedTasksAsc.length === 0 ? (
                <div style={planningPlaceholderStyle}>
                  <p style={planningBodyStyle}>
                    This is the thread's planning altitude. Talk here to map
                    out the work across tasks — each task forks from this
                    conversation at creation. Start typing below.
                  </p>
                </div>
              ) : (
                <TrunkChat
                  messages={trunkMessages}
                  tasks={orderedTasksAsc}
                  foregroundTaskId={foreground?.id ?? null}
                  userInitials={userInitials}
                  onOpenTask={onOpenTask}
                  isInFlight={hasInFlight || sending}
                  onInterrupt={() => { void onInterrupt(); }}
                  interrupting={interrupting}
                  outerRef={chatScrollRef}
                  canLoadOlder={canLoadOlder}
                  loadingOlder={loadingOlder}
                  onLoadOlder={() => { void loadOlderMessages(); }}
                />
              )}
              {/* Floating right-edge conversation index. Anchored
                  inside the chat card via position:relative on the
                  card; ConvIndex itself self-hides while the thread
                  has no prompts. */}
              <ConvIndex
                threadId={threadId}
                scrollContainerRef={chatScrollRef}
              />
            </div>

            <div style={composerCardStyle}>
              <div style={composerAnchorStyle}>
                ↻ Replying in the thread · planning
              </div>
              <textarea
                value={composerInput}
                onChange={e => setComposerInput(e.target.value)}
                onKeyDown={e => {
                  if (e.key !== 'Enter' || e.nativeEvent.isComposing) return;
                  // Shift+Enter: textarea default — a newline.
                  if (e.shiftKey) return;
                  // Cmd/Ctrl+Enter: insert a newline at the cursor.
                  // (The browser's default for Cmd+Enter in a textarea
                  // doesn't insert one, so we do it ourselves.)
                  if (e.metaKey || e.ctrlKey) {
                    e.preventDefault();
                    const ta = e.currentTarget;
                    const start = ta.selectionStart;
                    const end = ta.selectionEnd;
                    setComposerInput(ta.value.slice(0, start) + '\n' + ta.value.slice(end));
                    requestAnimationFrame(() => {
                      ta.selectionStart = ta.selectionEnd = start + 1;
                    });
                    return;
                  }
                  // Plain Enter: send.
                  if (sending) return;
                  e.preventDefault();
                  void onSendTrunk();
                }}
                placeholder="Plan the next slice, ask about the feature, or start a new task…"
                disabled={sending}
                rows={3}
                style={composerTextareaStyle}
              />
              <div style={composerFooterStyle}>
                <span style={composerScopeStyle}>▸ Thread</span>
                <span style={composerGlyphStyle} title="Slash commands">/</span>
                <span style={composerFooterHintStyle}>
                  ↵ send · ⌘↵ newline · / commands
                </span>
                <span style={composerNoBranchHintStyle}>
                  no branch here — the trunk plans; tasks do the work
                </span>
                {hasInFlight ? (
                  <button
                    type="button"
                    onClick={() => { void onInterrupt(); }}
                    disabled={interrupting}
                    style={composerInterruptBtnStyle}
                    title="Stop the in-progress agent turn"
                  >
                    {interrupting ? 'Stopping…' : '⊘ Stop'}
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => { void onSendTrunk(); }}
                    disabled={sending || composerInput.trim().length === 0}
                    style={composerSendBtnStyle}
                  >
                    {sending ? 'Sending…' : 'Send'}
                  </button>
                )}
              </div>
              {sendError !== null && (
                <div style={errStyle}>{sendError}</div>
              )}
            </div>
          </main>
        </div>
      </div>

      {threadError !== null && (
        <div style={floatErrStyle}>Could not load thread: {threadError}</div>
      )}
    </div>
  );
}

function CheckpointsSection({ threadId }: { threadId: string }) {
  const [checkpoints, setCheckpoints] = useState<
    import('../types').ThreadCheckpointDto[] | null
  >(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.getTaskCheckpoints(threadId);
        if (!cancelled) setCheckpoints(list);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  // Overall + the most-recent segments, capped to keep the rail tight.
  const overall = checkpoints?.find(c => c.isOverall) ?? null;
  const segments = (checkpoints ?? [])
    .filter(c => !c.isOverall && c.supersededAt === null)
    .sort((a, b) => b.seq - a.seq)
    .slice(0, 4);

  return (
    <section style={railSectionStyle}>
      <div style={railHeadStyle}>
        <span>CHECKPOINTS</span>
        <span style={railHeadMutedStyle}>
          {checkpoints === null
            ? '…'
            : `thread × ${(overall ? 1 : 0) + segments.length}`}
        </span>
      </div>
      {error !== null && <div style={errStyle}>{error}</div>}
      {checkpoints !== null && checkpoints.length === 0 && (
        <div style={emptyStyle}>
          No checkpoints yet. The summariser writes one once enough tokens
          have accumulated in the conversation.
        </div>
      )}
      {(overall !== null || segments.length > 0) && (
        <ul style={checkpointListStyle}>
          {overall !== null && (
            <li style={checkpointRowStyle}>
              <span style={{ ...checkpointDotStyle, background: SLATE }} aria-hidden />
              <div style={checkpointBodyStyle}>
                <div style={checkpointTitleStyle}>Thread summary</div>
                <div style={checkpointBlurbStyle} title={overall.summaryMd}>
                  {previewBlurb(overall.summaryMd)}
                </div>
              </div>
            </li>
          )}
          {segments.map(seg => (
            <li key={seg.id} style={checkpointRowStyle}>
              <span style={{ ...checkpointDotStyle, background: '#7c3aed' }} aria-hidden />
              <div style={checkpointBodyStyle}>
                <div style={checkpointTitleStyle}>cp · seq {seg.seq}</div>
                <div style={checkpointBlurbStyle} title={seg.summaryMd}>
                  {previewBlurb(seg.summaryMd)}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function previewBlurb(md: string): string {
  // Take the first non-empty line, trim markdown bullets, cap at 140.
  const line = md.split('\n').map(l => l.trim()).find(l => l.length > 0) ?? '';
  const stripped = line.replace(/^[-*•]\s*/, '').replace(/^#+\s*/, '');
  return stripped.length > 140 ? stripped.slice(0, 137) + '…' : stripped;
}

function DangerZoneSection({
  threadId, onDeleted,
}: {
  threadId: string;
  onDeleted: () => void;
}) {
  const [eligibility, setEligibility] = useState<
    { deletable: boolean; reason?: string } | null
  >(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const e = await window.bridge.getThreadDeleteEligibility(threadId);
        if (!cancelled) setEligibility(e);
      }
      catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, [threadId]);

  const openConfirm = useCallback(() => {
    if (eligibility?.deletable !== true || deleting) return;
    setError(null);
    setConfirmOpen(true);
  }, [eligibility, deleting]);

  const runDelete = useCallback(async () => {
    if (deleting) return;
    setDeleting(true);
    setError(null);
    try {
      await window.bridge.deleteTask(threadId);
      setConfirmOpen(false);
      onDeleted();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setDeleting(false);
      setConfirmOpen(false);
    }
  }, [deleting, threadId, onDeleted]);

  const blocked = eligibility !== null && eligibility.deletable === false;

  return (
    <section style={dangerSectionStyle}>
      <div style={dangerHeadStyle}>
        <span>DANGER ZONE</span>
        <span style={railHeadMutedStyle}>not undoable</span>
      </div>
      <button
        type="button"
        onClick={openConfirm}
        disabled={eligibility === null || blocked || deleting}
        style={blocked || eligibility === null ? deleteBtnDisabledStyle : deleteBtnStyle}
        title={blocked
            ? eligibility.reason
            : eligibility === null
                ? 'Checking eligibility…'
                : 'Permanently delete this thread'}
      >
        <span aria-hidden style={{ marginRight: 6 }}>⌫</span>
        {deleting ? 'Deleting…' : 'Delete thread'}
      </button>
      {blocked && eligibility.reason !== undefined && (
        <div style={dangerHintStyle}>
          {eligibility.reason}
        </div>
      )}
      {!blocked && eligibility?.deletable === true && (
        <div style={dangerHintStyle}>
          Every task has completed — deletion is permitted.
        </div>
      )}
      {error !== null && (
        <div style={errStyle}>{error}</div>
      )}
      {confirmOpen && (
        <ConfirmDialog
          title="Permanently delete this thread?"
          body={'This drops the conversation, every per-task row, and any '
              + 'live worktrees. Only threads whose every task has completed '
              + 'are eligible — anything still in flight is refused '
              + 'server-side.\n\nThis cannot be undone.'}
          confirmLabel={deleting ? 'Deleting…' : 'Delete thread'}
          destructive
          busy={deleting}
          onConfirm={() => { void runDelete(); }}
          onCancel={() => setConfirmOpen(false)}
        />
      )}
    </section>
  );
}

function SettingsSection({ threadId }: { threadId: string }) {
  const [settings, setSettings] = useState<ThreadSettingsDto | null>(null);
  const [editing, setEditing] = useState(false);
  const [maxRunning, setMaxRunning] = useState<string>('');
  const [promptAddendum, setPromptAddendum] = useState<string>('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const s = await window.bridge.getThreadSettings(threadId);
      setSettings(s);
      if (s.overriddenAt === null) {
        setMaxRunning('');
        setPromptAddendum('');
      }
      else {
        setMaxRunning(String(s.maxRunningTasks));
        setPromptAddendum(s.promptAddendum ?? '');
      }
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId]);

  useEffect(() => { void refresh(); }, [refresh]);

  const onSave = useCallback(async () => {
    setSaving(true);
    setError(null);
    try {
      // Cost-cap fields are intentionally omitted — the app doesn't
      // gate work on spend, so we leave the soft / hard caps to
      // inherit from the workspace defaults rather than surface them
      // on the per-thread settings card.
      await window.bridge.putThreadSettings(threadId, {
        maxRunningTasks: maxRunning.trim() === '' ? null : Number(maxRunning),
        promptAddendum: promptAddendum.trim() === '' ? null : promptAddendum,
      });
      await refresh();
      setEditing(false);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  }, [threadId, maxRunning, promptAddendum, refresh]);

  const onReset = useCallback(async () => {
    setSaving(true);
    setError(null);
    try {
      await window.bridge.clearThreadSettings(threadId);
      await refresh();
      setEditing(false);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  }, [threadId, refresh]);

  return (
    <section style={railSectionStyle}>
      <div style={railHeadStyle}>
        <span>THREAD SETTINGS</span>
        <span style={railHeadMutedStyle}>
          {settings === null ? '…' : settings.overriddenAt === null ? 'inherited' : 'overridden'}
        </span>
      </div>
      {!editing && settings !== null && (
        <>
          <dl style={{ margin: 0, padding: 0, display: 'grid', gap: 4 }}>
            <SettingsRow label="Max running tasks" value={String(settings.maxRunningTasks)} />
          </dl>
          {settings.promptAddendum !== null && (
            <div style={addendumPreviewStyle} title={settings.promptAddendum}>
              prompt: {settings.promptAddendum.length > 40
                ? settings.promptAddendum.slice(0, 40) + '…'
                : settings.promptAddendum}
            </div>
          )}
          <button
            type="button"
            onClick={() => setEditing(true)}
            style={settingsEditBtnStyle}
          >
            Edit
          </button>
        </>
      )}
      {editing && (
        <div style={settingsFormStyle}>
          <label style={settingsLabelStyle}>
            Max running tasks
            <input
              type="number"
              min={1}
              value={maxRunning}
              onChange={e => setMaxRunning(e.target.value)}
              placeholder="inherit"
              style={settingsInputStyle}
            />
          </label>
          <label style={settingsLabelStyle}>
            Prompt addendum
            <textarea
              value={promptAddendum}
              onChange={e => setPromptAddendum(e.target.value)}
              placeholder="appended onto workspace memory"
              rows={3}
              style={{ ...settingsInputStyle, fontFamily: 'inherit' }}
            />
          </label>
          <div style={settingsBtnRowStyle}>
            <button
              type="button"
              onClick={() => { void onSave(); }}
              disabled={saving}
              style={settingsSaveBtnStyle}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
            <button
              type="button"
              onClick={() => setEditing(false)}
              disabled={saving}
              style={settingsCancelBtnStyle}
            >
              Cancel
            </button>
            {settings?.overriddenAt !== null && (
              <button
                type="button"
                onClick={() => { void onReset(); }}
                disabled={saving}
                style={settingsResetBtnStyle}
                title="Clear overrides and revert to inheritance"
              >
                Reset
              </button>
            )}
          </div>
        </div>
      )}
      {error !== null && (
        <div style={errStyle}>{error}</div>
      )}
    </section>
  );
}

function SettingsRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
      <dt style={{ margin: 0, color: 'var(--text-3)' }}>{label}</dt>
      <dd style={{ margin: 0, color: 'var(--text-1)', fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </dd>
    </div>
  );
}

const addendumPreviewStyle: React.CSSProperties = {
  marginTop: 6,
  padding: '4px 8px',
  background: 'rgba(0,0,0,0.04)',
  borderRadius: 6,
  fontSize: 10,
  color: 'var(--text-3)',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const settingsEditBtnStyle: React.CSSProperties = {
  marginTop: 8,
  width: '100%',
  padding: '5px 8px',
  fontSize: 11,
  border: '1px dashed rgba(0,0,0,0.12)',
  background: 'transparent',
  borderRadius: 6,
  color: 'var(--text-2)',
  cursor: 'pointer',
};

const settingsFormStyle: React.CSSProperties = {
  display: 'grid',
  gap: 8,
};

const settingsLabelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  fontSize: 10,
  color: 'var(--text-3)',
  fontWeight: 600,
  letterSpacing: '0.04em',
};

const settingsInputStyle: React.CSSProperties = {
  padding: '4px 6px',
  fontSize: 12,
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 6,
  background: '#fff',
  outline: 'none',
};

const settingsBtnRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  marginTop: 4,
};

const settingsSaveBtnStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 8px',
  fontSize: 11,
  border: 'none',
  background: 'linear-gradient(135deg, #7c3aed, #6366f1)',
  color: '#fff',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const settingsCancelBtnStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 8px',
  fontSize: 11,
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  color: 'var(--text-2)',
  borderRadius: 6,
  cursor: 'pointer',
};

const settingsResetBtnStyle: React.CSSProperties = {
  padding: '5px 8px',
  fontSize: 11,
  border: '1px solid rgba(220, 38, 38, 0.30)',
  background: '#fff',
  color: '#dc2626',
  borderRadius: 6,
  cursor: 'pointer',
};

function initialsFor(profile: UserProfileDto | null): string {
  const source = profile?.name ?? profile?.login ?? '';
  if (source.length === 0) return 'JC';
  const parts = source.trim().split(/\s+/);
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

const dangerSectionStyle: React.CSSProperties = {
  background: 'rgba(220, 38, 38, 0.04)',
  border: '1px solid rgba(220, 38, 38, 0.18)',
  borderRadius: 14,
  padding: 12,
  boxShadow: '0 2px 10px rgba(220, 38, 38, 0.06)',
};

const dangerHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: '#991b1b',
  marginBottom: 8,
};

const deleteBtnStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  border: '1px solid rgba(220, 38, 38, 0.40)',
  background: '#fff',
  color: '#dc2626',
  borderRadius: 8,
  fontSize: 12,
  fontWeight: 600,
  cursor: 'pointer',
};

const deleteBtnDisabledStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  border: '1px solid rgba(0,0,0,0.08)',
  background: 'rgba(0,0,0,0.04)',
  color: 'var(--text-4)',
  borderRadius: 8,
  fontSize: 12,
  fontWeight: 600,
  cursor: 'not-allowed',
};

const dangerHintStyle: React.CSSProperties = {
  marginTop: 6,
  fontSize: 10,
  color: 'var(--text-3)',
  lineHeight: 1.5,
};

function newestActiveTask(tasks: WorkUnitTaskDto[]): WorkUnitTaskDto | null {
  return tasks
    .filter(t => ACTIVE_STATUSES.has(t.status))
    .reduce<WorkUnitTaskDto | null>(
      (acc, t) => acc === null || t.seq > acc.seq ? t : acc, null);
}

function summariseScheduler(turns: ThreadTurnDto[] | null) {
  if (turns === null) return { running: 0, queued: 0, cli: 0, api: 0 };
  let running = 0;
  let queued = 0;
  let cli = 0;
  let api = 0;
  for (const t of turns) {
    if (t.status === 'RUNNING') {
      running++;
      if (t.lane === 'CLI') cli++; else api++;
    }
    else if (t.status === 'QUEUED') {
      queued++;
    }
  }
  return { running, queued, cli, api };
}

function TaskCard({
  task, selected, isForeground, onSelect, onOpen,
}: {
  task: WorkUnitTaskDto;
  selected: boolean;
  isForeground: boolean;
  onSelect: () => void;
  onOpen: () => void;
}) {
  const labelText = taskLabel(task);
  // Re-render every 60s so "created N ago" stays roughly current.
  const [, setTick] = useState<number>(0);
  useEffect(() => {
    const id = window.setInterval(() => setTick(n => n + 1), 60_000);
    return () => window.clearInterval(id);
  }, []);

  return (
    <li
      onClick={onSelect}
      onDoubleClick={onOpen}
      style={taskCardStyle(selected, isForeground)}
    >
      <div style={taskCardHeadStyle}>
        <span style={glyphStyle(task)} aria-hidden>{glyphChar(task)}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={taskCardTitleRowStyle}>
            <span style={taskCardTitleStyle} title={labelText}>{labelText}</span>
          </div>
        </div>
        {selected && (
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); onOpen(); }}
            style={openBtnStyle(true)}
            title={`Enter Task ${task.seq}'s window`}
          >
            Open →
          </button>
        )}
      </div>
      <div style={taskMetaRowStyle}>
        {task.branchName !== null && (
          <span style={metaChipStyle} title={task.branchName}>
            <span style={metaIconStyle}>⌥</span>{task.branchName}
          </span>
        )}
        {task.prNumber !== null && (
          <span style={metaChipStyle} title={`PR #${task.prNumber} · ${task.prState ?? 'unknown'}`}>
            <span style={metaIconStyle}>⌗</span>PR #{task.prNumber}
            {task.prState !== null && task.prState !== '' && (
              <span style={metaDimStyle}>{` · ${task.prState}`}</span>
            )}
          </span>
        )}
        <span style={metaChipStyle} title={`Created ${task.createdAt}`}>
          <span style={metaIconStyle}>◷</span>{relativeTime(task.createdAt)}
        </span>
        <span style={taskStatusPillStyle(task.status)}>{statusLabel(task.status)}</span>
      </div>
    </li>
  );
}

function VitalsTable({ thread }: { thread: ThreadDto | null }) {
  if (thread === null) {
    return <div style={emptyStyle}>—</div>;
  }
  const cost = formatCost(thread.costUsdMilli);
  const tokens = `${formatTokens(thread.tokensIn)} → ${formatTokens(thread.tokensOut)}`;
  const runtime = formatRuntime(thread.createdAt, thread.endedAt);
  return (
    <dl style={vitalsListStyle}>
      <VitalRow label="Cost (all tasks)" value={cost} />
      <VitalRow label="Tokens" value={tokens} />
      <VitalRow label="Runtime" value={runtime} />
    </dl>
  );
}

function VitalRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={vitalsRowStyle}>
      <dt style={vitalsLabelStyle}>{label}</dt>
      <dd style={vitalsValueStyle}>{value}</dd>
    </div>
  );
}

function SchedulerTable({
  summary,
}: {
  summary: { running: number; queued: number; cli: number; api: number };
}) {
  // The lanes are hard-capped by Spring properties — match the
  // defaults in AgentScheduler (4 / 4). A future commit can pull
  // these through a /api/scheduler/lanes endpoint.
  const CLI_CAP = 4;
  const API_CAP = 4;
  return (
    <>
      <dl style={vitalsListStyle}>
        <VitalRow label="Running" value={String(summary.running)} />
        <VitalRow label="Queued" value={String(summary.queued)} />
      </dl>
      <div style={{ marginTop: 8, display: 'grid', gap: 6 }}>
        <LaneBar label="CLI lane" used={summary.cli} cap={CLI_CAP} color="#7c3aed" />
        <LaneBar label="API lane" used={summary.api} cap={API_CAP} color="#0d9488" />
      </div>
      <div style={schedulerFooterStyle}>
        within the workspace budget · interleaved across threads
      </div>
    </>
  );
}

function LaneBar({
  label, used, cap, color,
}: {
  label: string;
  used: number;
  cap: number;
  color: string;
}) {
  const pct = Math.min(100, Math.round((used / Math.max(1, cap)) * 100));
  return (
    <div>
      <div style={laneRowStyle}>
        <span style={{ fontSize: 11, color: 'var(--text-3)' }}>{label}</span>
        <span style={{
          fontSize: 11, fontVariantNumeric: 'tabular-nums',
          color: 'var(--text-1)',
        }}>
          {used} / {cap}
        </span>
      </div>
      <div style={laneTrackStyle}>
        <div style={{ ...laneFillStyle, width: `${pct}%`, background: color }} />
      </div>
    </div>
  );
}

function taskLabel(task: WorkUnitTaskDto): string {
  if (task.name !== null && task.name.length > 0) {
    return task.name;
  }
  if (task.branchName !== null && task.branchName.length > 0) {
    return humanizeBranch(task.branchName);
  }
  return `Task ${task.seq}`;
}

function relativeTime(iso: string): string {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return '—';
  const delta = Math.max(0, Math.floor((Date.now() - t) / 1000));
  if (delta < 60) return `${delta}s ago`;
  const m = Math.floor(delta / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

function humanizeBranch(branch: string): string {
  let rest = branch;
  const slash = rest.lastIndexOf('/');
  if (slash >= 0 && slash < rest.length - 1) rest = rest.slice(slash + 1);
  const hex = rest.match(/^[a-f0-9]{8,}-(.+)$/i);
  if (hex !== null) rest = hex[1];
  const spaced = rest.replace(/[-_]+/g, ' ').trim();
  if (spaced.length === 0) return branch;
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function glyphChar(task: WorkUnitTaskDto): string {
  if (task.status === 'COMPLETED') return '✓';
  if (task.status === 'ERRORED') return '⨯';
  if (task.status === 'AWAITING_REVIEW' || task.status === 'NEEDS_ATTENTION') return '⏸';
  if (task.status === 'RUNNING' || task.status === 'AWAITING') return '●';
  return '○';
}

function statusLabel(status: string): string {
  if (status === 'AWAITING_REVIEW') return 'awaiting';
  if (status === 'NEEDS_ATTENTION') return 'needs you';
  return status.toLowerCase();
}

function formatCost(milli: number): string {
  return `$${(milli / 1000).toFixed(2)}`;
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

function formatRuntime(createdAt: string, endedAt: string | null): string {
  const start = Date.parse(createdAt);
  const end = endedAt !== null ? Date.parse(endedAt) : Date.now();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '—';
  const secs = Math.floor((end - start) / 1000);
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = secs % 60;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  if (m > 0) return `${m}m ${s.toString().padStart(2, '0')}s`;
  return `${s}s`;
}

/* ── Styles ────────────────────────────────────────────────────────── */

const pageStyle: React.CSSProperties = {
  position: 'relative',
  minHeight: '100vh',
  background: '#fafafe',
  color: 'var(--text-1)',
  overflow: 'hidden',
};

const meshBgStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  pointerEvents: 'none',
  background: [
    'radial-gradient(circle at 18% 16%, rgba(124, 58, 237, 0.10), transparent 45%)',
    'radial-gradient(circle at 82% 22%, rgba(56, 189, 248, 0.10), transparent 45%)',
    'radial-gradient(circle at 12% 86%, rgba(244, 114, 182, 0.08), transparent 50%)',
    'radial-gradient(circle at 86% 78%, rgba(74, 222, 128, 0.08), transparent 50%)',
  ].join(','),
  zIndex: 0,
};

const noiseBgStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  pointerEvents: 'none',
  opacity: 0.045,
  mixBlendMode: 'overlay',
  backgroundImage:
    'url("data:image/svg+xml;utf8,'
    + '<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'160\' height=\'160\'>'
    + '<filter id=\'n\'><feTurbulence type=\'fractalNoise\' baseFrequency=\'0.8\' numOctaves=\'2\' stitchTiles=\'stitch\'/></filter>'
    + '<rect width=\'100%\' height=\'100%\' filter=\'url(%23n)\'/></svg>")',
  zIndex: 0,
};

const SLATE = '#475569';
const SLATE_BG = 'rgba(71, 85, 105, 0.10)';
const SLATE_BORDER = 'rgba(71, 85, 105, 0.30)';

const spineStyle: React.CSSProperties = {
  position: 'fixed',
  top: 0,
  bottom: 0,
  left: 0,
  width: 4,
  background: SLATE,
  zIndex: 2,
};

const contentColStyle: React.CSSProperties = {
  position: 'relative',
  zIndex: 1,
  paddingLeft: 12,
  display: 'flex',
  flexDirection: 'column',
  minHeight: '100vh',
};

const headerStyle: React.CSSProperties = {
  position: 'sticky',
  top: 0,
  zIndex: 3,
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '10px 18px',
  background: 'rgba(255, 255, 255, 0.66)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  borderBottom: '1px solid rgba(0,0,0,0.05)',
};

const backBtnStyle: React.CSSProperties = {
  border: '1px solid rgba(0,0,0,0.08)',
  background: 'rgba(255,255,255,0.6)',
  padding: '4px 10px',
  fontSize: 12,
  borderRadius: 6,
  cursor: 'pointer',
  color: 'var(--text-2)',
};

const titleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  letterSpacing: '0.005em',
  flex: 1,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

function statusPillStyle(status: string): React.CSSProperties {
  const tone = status === 'RUNNING' ? '#16a34a' : status === 'ERRORED' ? '#b91c1c' : SLATE;
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    fontSize: 10,
    padding: '3px 10px',
    borderRadius: 999,
    border: `1px solid ${tone}40`,
    color: tone,
    background: `${tone}14`,
    fontWeight: 700,
    letterSpacing: '0.06em',
  };
}

function statusDotStyle(status: string): React.CSSProperties {
  const tone = status === 'RUNNING' ? '#16a34a' : status === 'ERRORED' ? '#b91c1c' : SLATE;
  return {
    width: 6,
    height: 6,
    borderRadius: 999,
    background: tone,
    boxShadow: status === 'RUNNING' ? `0 0 0 2px ${tone}30` : 'none',
  };
}

const altitudeBandStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  padding: '8px 18px',
  background: SLATE_BG,
  borderBottom: `1px solid ${SLATE_BORDER}`,
  fontSize: 12,
};

const bandGlyphStyle: React.CSSProperties = {
  fontWeight: 700,
  letterSpacing: '0.08em',
  color: SLATE,
};

const bandTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
};

const bandHintStyle: React.CSSProperties = {
  color: 'var(--text-3)',
};

const bandCwdStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '2px 8px',
  borderRadius: 6,
  border: '1px solid rgba(0,0,0,0.08)',
  background: 'rgba(0,0,0,0.03)',
  fontSize: 11,
  maxWidth: 360,
  overflow: 'hidden',
};

const bandCwdLabelStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'var(--text-4)',
};

const bandCwdPathStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: 'var(--text-2)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
};

/** Shorten a long absolute path for the band: collapse the user's
 *  home prefix to "~" and keep just the trailing segments if the
 *  full path is still too long. Falls back to the raw path. */
function shortCwd(cwd: string): string {
  if (cwd.length === 0) return cwd;
  let p = cwd;
  // Trim trailing slash for a cleaner display.
  if (p.endsWith('/') && p.length > 1) p = p.slice(0, -1);
  // Best-effort home collapse — the path comes from the backend's
  // resolved local clone, so the leading segments mirror $HOME.
  const homeMatch = p.match(/^\/Users\/[^/]+\//);
  if (homeMatch !== null) p = '~/' + p.slice(homeMatch[0].length);
  if (p.length <= 48) return p;
  const segs = p.split('/');
  if (segs.length <= 3) return p;
  return '…/' + segs.slice(-2).join('/');
}

const bodyGridStyle: React.CSSProperties = {
  display: 'grid',
  // Two-column layout — rail on the left, conversation column on the
  // right. The 1px gap renders as a visible vertical divider thanks to
  // the rail's borderRight, giving the page a clean "rail | main"
  // column split per the mockup.
  gridTemplateColumns: '280px 1fr',
  gap: 0,
  padding: 0,
  flex: 1,
  alignItems: 'stretch',
  minHeight: 0,
};

const railStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  padding: '14px 14px 14px 18px',
  // Visible divider between rail and main column.
  borderRight: '1px solid rgba(0,0,0,0.08)',
  // The rail scrolls independently of the conversation column so a
  // long checkpoint/scheduler list never pushes the chat off-screen.
  overflowY: 'auto',
  maxHeight: 'calc(100vh - 96px)',
};

const railSectionStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.72)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.06)',
  borderRadius: 14,
  padding: 12,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
};

const railHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--text-2)',
  marginBottom: 6,
};

const railHeadMutedStyle: React.CSSProperties = {
  fontWeight: 500,
  color: 'var(--text-4)',
  letterSpacing: '0.04em',
};

const listStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

function taskCardStyle(selected: boolean, isForeground: boolean): React.CSSProperties {
  // Refined-texture treatment: a soft gradient base (white → off-white)
  // gives the card surface a hint of dimensionality without the flat
  // look of a single solid colour; the foreground card layers a
  // green-tinted gradient so it reads as "live"; the selected card
  // upgrades to a purple-tinted gradient with a stronger shadow + a
  // tiny inner highlight at the top edge for a lifted feel.
  const base: React.CSSProperties = {
    position: 'relative',
    padding: '14px 16px',
    borderRadius: 14,
    cursor: 'pointer',
    transition: 'transform 160ms ease, box-shadow 160ms ease, background 160ms ease',
    overflow: 'hidden',
  };
  if (selected) {
    return {
      ...base,
      border: '1px solid rgba(124, 58, 237, 0.55)',
      background:
        'linear-gradient(180deg, rgba(124,58,237,0.16) 0%, rgba(99,102,241,0.07) 100%), #ffffff',
      boxShadow:
        '0 12px 32px rgba(124,58,237,0.22),'
        + ' 0 2px 6px rgba(124,58,237,0.12),'
        + ' inset 0 1px 0 rgba(255,255,255,0.8)',
      transform: 'translateY(-1px)',
    };
  }
  if (isForeground) {
    return {
      ...base,
      border: '1px solid rgba(22, 163, 74, 0.38)',
      background:
        'linear-gradient(180deg, rgba(22,163,74,0.11) 0%, rgba(22,163,74,0.03) 100%), #ffffff',
      boxShadow:
        '0 6px 18px rgba(22,163,74,0.10),'
        + ' 0 1px 3px rgba(0,0,0,0.04),'
        + ' inset 0 1px 0 rgba(255,255,255,0.7)',
    };
  }
  return {
    ...base,
    border: '1px solid rgba(99, 102, 241, 0.14)',
    background:
      'linear-gradient(180deg, rgba(124,58,237,0.05) 0%, rgba(255,255,255,0.95) 100%), #ffffff',
    boxShadow:
      '0 3px 10px rgba(76, 29, 149, 0.06),'
      + ' 0 1px 2px rgba(0,0,0,0.04),'
      + ' inset 0 1px 0 rgba(255,255,255,0.85)',
  };
}

const taskCardHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
};

function glyphStyle(task: WorkUnitTaskDto): React.CSSProperties {
  // Coloured dot in a soft tinted disc — same hue as the status pill,
  // so the card carries a single accent colour across glyph + pill.
  let color = 'var(--text-4)';
  let bg = 'rgba(100, 116, 139, 0.10)';
  if (task.status === 'COMPLETED') { color = '#16a34a'; bg = 'rgba(22, 163, 74, 0.12)'; }
  else if (task.status === 'ERRORED') { color = '#b91c1c'; bg = 'rgba(185, 28, 28, 0.12)'; }
  else if (task.status === 'AWAITING_REVIEW' || task.status === 'NEEDS_ATTENTION') {
    color = '#d97706'; bg = 'rgba(217, 119, 6, 0.14)';
  }
  else if (task.status === 'RUNNING' || task.status === 'AWAITING') {
    color = '#2563eb'; bg = 'rgba(37, 99, 235, 0.12)';
  }
  return {
    width: 22,
    height: 22,
    borderRadius: 999,
    background: bg,
    color,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 13,
    flexShrink: 0,
    fontWeight: 700,
  };
}

const taskCardTitleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
};

const taskCardTitleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  letterSpacing: '-0.01em',
  minWidth: 0,
  flex: 1,
};

const taskMetaRowStyle: React.CSSProperties = {
  marginTop: 10,
  paddingLeft: 32,
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 6,
};

const metaChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  fontSize: 10,
  color: 'var(--text-3)',
  background: 'rgba(15, 23, 42, 0.04)',
  border: '1px solid rgba(15, 23, 42, 0.06)',
  borderRadius: 999,
  padding: '2px 7px',
  fontVariantNumeric: 'tabular-nums',
  maxWidth: '100%',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const metaIconStyle: React.CSSProperties = {
  fontSize: 10,
  opacity: 0.75,
};

const metaDimStyle: React.CSSProperties = {
  color: 'var(--text-4)',
};

const laneRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  marginBottom: 2,
};

const laneTrackStyle: React.CSSProperties = {
  height: 4,
  background: 'rgba(0,0,0,0.06)',
  borderRadius: 999,
  overflow: 'hidden',
};

const laneFillStyle: React.CSSProperties = {
  height: '100%',
  borderRadius: 999,
  transition: 'width 140ms ease',
};

const schedulerFooterStyle: React.CSSProperties = {
  marginTop: 8,
  fontSize: 9,
  color: 'var(--text-4)',
  fontStyle: 'italic',
  textAlign: 'center',
};

const composerGlyphStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-4)',
  padding: '0 2px',
  cursor: 'default',
};

const composerNoBranchHintStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 10,
  color: 'var(--text-4)',
  fontStyle: 'italic',
  textAlign: 'right',
  marginRight: 6,
};

function openBtnStyle(selected: boolean): React.CSSProperties {
  return {
    fontSize: 10,
    padding: '2px 6px',
    border: '1px solid ' + (selected ? '#7c3aed55' : 'rgba(0,0,0,0.10)'),
    background: selected ? 'rgba(124,58,237,0.10)' : '#fff',
    color: selected ? '#6d28d9' : 'var(--text-2)',
    borderRadius: 6,
    cursor: 'pointer',
    fontWeight: 600,
  };
}

const taskCardMetaStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  marginTop: 4,
  flexWrap: 'wrap',
};

const branchStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: '60%',
};

const prStyle: React.CSSProperties = {
  fontSize: 10,
  padding: '1px 6px',
  background: 'rgba(0, 0, 0, 0.06)',
  borderRadius: 999,
  color: 'var(--text-2)',
};

function taskStatusPillStyle(status: string): React.CSSProperties {
  let bg = 'rgba(0,0,0,0.06)';
  let color = 'var(--text-2)';
  if (status === 'COMPLETED') { bg = 'rgba(22,163,74,0.12)'; color = '#15803d'; }
  else if (status === 'ERRORED') { bg = 'rgba(185,28,28,0.12)'; color = '#991b1b'; }
  else if (status === 'AWAITING_REVIEW' || status === 'NEEDS_ATTENTION') {
    bg = 'rgba(217,119,6,0.14)'; color = '#9a3412';
  }
  else if (status === 'RUNNING') { bg = 'rgba(37,99,235,0.12)'; color = '#1d4ed8'; }
  return {
    fontSize: 9,
    padding: '1px 6px',
    borderRadius: 999,
    background: bg,
    color,
    fontWeight: 700,
    letterSpacing: '0.04em',
    textTransform: 'lowercase',
  };
}

const reviewFlowNoticeStyle: React.CSSProperties = {
  marginTop: 10,
  padding: 10,
  fontSize: 11,
  color: '#1d4ed8',
  background: 'rgba(37, 99, 235, 0.06)',
  border: '1px solid rgba(37,99,235,0.20)',
  borderRadius: 8,
  lineHeight: 1.5,
};

const attentionBannerStyle: React.CSSProperties = {
  background: 'rgba(217, 119, 6, 0.10)',
  border: '1px solid rgba(217, 119, 6, 0.30)',
  borderRadius: 14,
  padding: 12,
  boxShadow: '0 4px 18px rgba(217,119,6,0.10)',
};

const attentionTitleStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 700,
  color: '#9a3412',
  marginBottom: 8,
};

const attentionListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const attentionRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 11,
};

const attentionLabelStyle: React.CSSProperties = {
  flex: 1,
  color: '#9a3412',
  fontWeight: 600,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const attentionJumpBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  fontSize: 10,
  border: 'none',
  background: '#d97706',
  color: '#fff',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 700,
  letterSpacing: '0.04em',
};

const attentionHintStyle: React.CSSProperties = {
  marginTop: 8,
  fontSize: 10,
  color: '#9a3412',
  lineHeight: 1.5,
};

const advanceRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 6,
  marginTop: 10,
};

function nextBtnStyle(disabled: boolean): React.CSSProperties {
  return {
    padding: '6px 8px',
    fontSize: 12,
    border: 'none',
    background: disabled
      ? 'rgba(124, 58, 237, 0.22)'
      : 'linear-gradient(135deg, #7c3aed, #6366f1)',
    color: disabled ? 'rgba(255,255,255,0.85)' : '#fff',
    borderRadius: 8,
    fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.65 : 1,
  };
}

function shipBtnStyle(disabled: boolean): React.CSSProperties {
  return {
    padding: '6px 8px',
    fontSize: 12,
    border: '1px solid rgba(0,0,0,0.10)',
    background: disabled ? 'rgba(0,0,0,0.04)' : '#fff',
    color: disabled ? 'var(--text-4)' : 'var(--text-1)',
    borderRadius: 8,
    fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.7 : 1,
  };
}

const advanceHintStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  marginTop: 6,
  textAlign: 'center',
};

const vitalsListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  display: 'grid',
  gap: 4,
};

const vitalsRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 12,
};

const vitalsLabelStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-3)',
};

const vitalsValueStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-1)',
  fontFeatureSettings: '"tnum"',
  fontVariantNumeric: 'tabular-nums',
};

const mainStyle: React.CSSProperties = {
  // Main column is its own bounded box — chat grows to fill, composer
  // sits anchored at the bottom of the column (not the viewport).
  // The maxHeight reserve is bumped to 116px (was 96) and the bottom
  // padding to 28 (was 14) so the composer card clears the viewport
  // edge with a comfortable gap instead of sitting flush with it —
  // mirrors the task-detail window's tuning.
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  paddingTop: 14,
  paddingLeft: 14,
  paddingRight: 14,
  paddingBottom: 28,
  minHeight: 0,
  maxHeight: 'calc(100vh - 116px)',
  overflow: 'hidden',
};

const chatCardStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  background: 'rgba(255,255,255,0.78)',
  backdropFilter: 'blur(14px) saturate(125%)',
  WebkitBackdropFilter: 'blur(14px) saturate(125%)',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 14,
  boxShadow: '0 4px 18px rgba(0,0,0,0.04)',
  overflow: 'hidden',
  minHeight: 0,
  // Containing block for the absolutely-positioned floating ConvIndex
  // panel mounted as a sibling of TrunkChat — without this it'd
  // anchor to the viewport instead of the chat card.
  position: 'relative',
};

const composerCardStyle: React.CSSProperties = {
  // Bordered card pinned under the chat. Width tracks the chat
  // column (no fixed/sticky positioning) so the composer never
  // spans the whole page like the previous footer-anchored version.
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '10px 14px 12px',
  background: 'rgba(255,255,255,0.92)',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 14,
  boxShadow: '0 4px 14px rgba(0,0,0,0.04)',
  flexShrink: 0,
};

const planningPlaceholderStyle: React.CSSProperties = {
  // Inside chatCardStyle — fill the card without re-applying the
  // border/shadow chrome the card already supplies.
  flex: 1,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 24,
};

// The transcript scroll container. StructuredConversation walks up to
// find the nearest scrolling ancestor for its stick-to-bottom logic, so
// this wrapper supplies overflow:auto and a bounded max-height anchored
// to the viewport. The composer is sticky at the page bottom and the
// header/altitude band sit at the top, so this max-height keeps the
// chat from pushing the composer off-screen on long histories.
const checkpointListStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const checkpointRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'flex-start',
  fontSize: 11,
};

const checkpointDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  borderRadius: 999,
  marginTop: 6,
  flexShrink: 0,
};

const checkpointBodyStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
};

const checkpointTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
};

const checkpointBlurbStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontSize: 10,
  lineHeight: 1.4,
  marginTop: 2,
  overflow: 'hidden',
  display: '-webkit-box',
  WebkitLineClamp: 2,
  WebkitBoxOrient: 'vertical',
};

const planningBodyStyle: React.CSSProperties = {
  margin: '8px 0',
  fontSize: 13,
  lineHeight: 1.55,
  color: 'var(--text-2)',
};

const composerAnchorStyle: React.CSSProperties = {
  fontSize: 10,
  letterSpacing: '0.04em',
  color: SLATE,
  fontWeight: 600,
  marginBottom: 4,
};

const composerTextareaStyle: React.CSSProperties = {
  width: '100%',
  padding: '12px 14px',
  border: `1px solid ${SLATE_BORDER}`,
  borderRadius: 10,
  background: 'rgba(255,255,255,0.86)',
  fontSize: 17,
  lineHeight: 1.5,
  fontFamily: 'inherit',
  color: 'var(--text-1)',
  resize: 'vertical',
  outline: 'none',
  boxSizing: 'border-box',
};

const composerSendBtnStyle: React.CSSProperties = {
  padding: '4px 14px',
  fontSize: 12,
  border: 'none',
  background: SLATE,
  color: '#fff',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const composerInterruptBtnStyle: React.CSSProperties = {
  padding: '4px 14px',
  fontSize: 12,
  border: '1px solid rgba(207, 19, 34, 0.55)',
  background: '#fff',
  color: '#cf1322',
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const composerFooterStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  marginTop: 4,
  fontSize: 10,
  color: 'var(--text-4)',
};

const composerScopeStyle: React.CSSProperties = {
  padding: '1px 6px',
  background: SLATE_BG,
  borderRadius: 6,
  color: SLATE,
  fontWeight: 600,
  letterSpacing: '0.04em',
};

const composerFooterHintStyle: React.CSSProperties = {
  fontStyle: 'italic',
};

const emptyStyle: React.CSSProperties = {
  padding: '6px 2px',
  fontSize: 11,
  color: 'var(--text-3)',
  lineHeight: 1.5,
};

const errStyle: React.CSSProperties = {
  padding: '6px 8px',
  marginTop: 6,
  fontSize: 11,
  color: '#b91c1c',
  background: 'rgba(185, 28, 28, 0.06)',
  border: '1px solid rgba(185,28,28,0.18)',
  borderRadius: 6,
};

const floatErrStyle: React.CSSProperties = {
  position: 'fixed',
  bottom: 12,
  right: 12,
  padding: '8px 12px',
  background: '#fee2e2',
  border: '1px solid #fecaca',
  color: '#991b1b',
  fontSize: 12,
  borderRadius: 8,
  zIndex: 4,
};
