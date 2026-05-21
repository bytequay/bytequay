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
  TaskDto,
  TaskFileDto,
  TaskGroupDto,
  TaskGroupMembershipDto,
  TaskMessageDto,
  TaskStatusDto,
  TaskTurnDto,
  TaskTurnEventDto,
  WatchedRepoDto,
} from '../types';
import GroupMenu from './GroupMenu';
import { ConversationPane, type PendingPermission } from './ConversationPane';
import { StructuredConversation } from './StructuredConversation';
import TasksLeftRail, {
  repoKey,
  type GroupFilter,
  type ProviderFilter,
  type RepoFilter,
  type StatusFilter,
} from './TasksLeftRail';
import RepoAvatar from './RepoAvatar';
import { useAutoGrowTextarea, usePersistentDraft } from './draftStore';
import { ConvIndex } from './ConvIndex';
import { CheckpointsSection } from './CheckpointsSection';
import { DiffModeToggle, TaskDiffPane, useTaskDiffState, type DiffMode } from './TaskChangesTab';
import { findPendingPermission } from './permissions';
import { taskAgentCwd, taskDisplayBranch } from './taskDisplay';

type Props = {
  taskId: string;
  onBack: () => void;
  /** Clicking a status row on the rail jumps to the list page,
   *  pre-filtered. */
  onFilterChange: (filter: StatusFilter) => void;
  /** Clicking a provider row jumps to the list page filtered by
   *  that provider. */
  onProviderChange: (provider: ProviderFilter) => void;
  /** Clicking a group row jumps to the list page filtered by that
   *  group. */
  onGroupChange: (group: GroupFilter) => void;
  /** Clicking a repo row jumps to the list page filtered by that
   *  repo. */
  onRepoChange: (repo: RepoFilter) => void;
  /** Swap to another task's detail page from the rail's Recent list. */
  onSelectTask: (taskId: string) => void;
  /** Open the linked PR in the repo detail page. The sidebar
   *  surfaces a click target only when {@code linkedPrNumber} is
   *  set AND the parent provides this callback. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
  /** Footer "Defaults & integrations" row. */
  onOpenSettings: () => void;
};

// Stream-fed refresh cadence. SSE delivers per-event pings near
// real-time (see subscribeTaskStream + the debounced refresh in
// useEffect), so the poll only needs to act as a safety net in case
// the stream disconnects. Picking 5s for RUNNING (was 1s, but the
// stream eats the gap) and 5s for idle.
const POLL_MS_RUNNING = 5_000;
const POLL_MS_IDLE = 5_000;
const POLL_MS_TERMINAL = 0;
// Coalesce bursts of SSE events into a single refresh — 5 deltas
// inside a 120ms window collapse into one /messages roundtrip.
const STREAM_REFRESH_DEBOUNCE_MS = 120;

/**
 * Two-column task detail surface — terminal pane + sticky sidebar
 * (stage / metrics / quick actions). Faithfully
 * follows the layout in
 * {@code docs/mockups/design/tasks/task-detail-terminal.png}.
 *
 * <p>SSE through Electron is deferred; we poll on a status-aware
 * cadence (1s while RUNNING, 5s otherwise, off when terminal).
 */
/** Renderer for the task window body. Two modes — the structured
 *  Conversation view (default) and the dark/light-themed Terminal
 *  view that mirrors the raw stream-json output. Toggled from the
 *  task-window header bar. Diff (working-tree changes) opens
 *  side-by-side via the strip above the reply input, not a tab. */
type DetailView = 'conversation' | 'terminal';
const VIEW_STORAGE_KEY = 'bytequay.tasks.detailView';

/** Whether the working-tree diff pane is currently open beside the
 *  conversation/terminal. Persisted so a user who keeps it pinned
 *  doesn't have to re-open it after every navigation. */
const DIFF_OPEN_STORAGE_KEY = 'bytequay.tasks.detailDiffOpen';
function loadDiffOpen(): boolean {
  try { return window.localStorage.getItem(DIFF_OPEN_STORAGE_KEY) === '1'; }
  catch { return false; }
}

/** Persisted left-pane fraction (0..1) for the conversation / diff
 *  split. 0.5 means equal halves; the user can drag the splitter to
 *  taste and the value survives a reload. Clamped to [0.2, 0.85] so
 *  neither side can be dragged off-screen. */
const DIFF_SPLIT_FRAC_KEY = 'bytequay.tasks.detailDiffSplitFrac';
const DEFAULT_DIFF_SPLIT_FRAC = 0.5;
const MIN_DIFF_SPLIT_FRAC = 0.2;
const MAX_DIFF_SPLIT_FRAC = 0.85;
function clampSplitFrac(f: number): number {
  if (!Number.isFinite(f)) return DEFAULT_DIFF_SPLIT_FRAC;
  return Math.max(MIN_DIFF_SPLIT_FRAC, Math.min(MAX_DIFF_SPLIT_FRAC, f));
}
function useDiffSplitFrac(): [number, (next: number) => void] {
  const [frac, setFrac] = useState<number>(() => {
    try {
      const raw = window.localStorage.getItem(DIFF_SPLIT_FRAC_KEY);
      return clampSplitFrac(raw == null ? DEFAULT_DIFF_SPLIT_FRAC : parseFloat(raw));
    }
    catch { return DEFAULT_DIFF_SPLIT_FRAC; }
  });
  const set = useCallback((next: number) => setFrac(clampSplitFrac(next)), []);
  useEffect(() => {
    try { window.localStorage.setItem(DIFF_SPLIT_FRAC_KEY, String(frac)); }
    catch { /* private browsing — fine to skip */ }
  }, [frac]);
  return [frac, set];
}

/** Terminal-view theme. Persisted across sessions so a user who
 *  prefers a light terminal doesn't keep re-toggling. Only the
 *  Terminal tab consults this; the rest of the page reads from the
 *  global theme tokens. */
type TermTheme = 'dark' | 'light';
const THEME_STORAGE_KEY = 'bytequay.tasks.terminalTheme';
function loadTheme(): TermTheme {
  try {
    const v = window.localStorage.getItem(THEME_STORAGE_KEY);
    return v === 'light' ? 'light' : 'dark';
  }
  catch {
    return 'dark';
  }
}

const SIDEBAR_COLLAPSED_STORAGE_KEY = 'bytequay.tasks.detailSidebarCollapsed';
function loadSidebarCollapsed(): boolean {
  try { return window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY) === '1'; }
  catch { return false; }
}

/** Context-window size (in tokens) per model family. Used by the
 *  sidebar's CONTEXT WINDOW bar to compute "% used = latest turn's
 *  input_tokens / limit". Approximate today — see followups/
 *  tasks-checkpoints-and-context.md for the parser change that will
 *  also count cache_read / cache_creation tokens. */
const MODEL_CONTEXT_LIMITS: Array<{ match: RegExp; limit: number }> = [
  { match: /opus/i,    limit: 200_000 },
  { match: /sonnet/i,  limit: 200_000 },
  { match: /haiku/i,   limit: 200_000 },
  { match: /gpt-?5/i,  limit: 272_000 },
  { match: /gpt-?4/i,  limit: 128_000 },
  { match: /codex/i,   limit: 272_000 },
];
function modelContextLimit(model: string | null | undefined): number {
  const m = (model ?? '').trim();
  for (const { match, limit } of MODEL_CONTEXT_LIMITS) {
    if (match.test(m)) return limit;
  }
  return 200_000; // safe default for any "claude-*"
}
function loadView(): DetailView {
  try {
    const v = window.localStorage.getItem(VIEW_STORAGE_KEY);
    // Two modes. 'terminal' passes through; everything else (the
    // legacy 'ask' / 'diff' / 'files' / 'comments' tab values and
    // the long-ago 'conversation' value) folds into the default
    // structured Conversation view. Diff/Files are no longer tabs
    // — Diff opens via the strip above the reply input, Files and
    // Comments were removed.
    if (v === 'terminal') return 'terminal';
    return 'conversation';
  }
  catch {
    return 'conversation';
  }
}

export default function TaskDetailPage({
  taskId, onBack,
  // The list-view rail filters are routed through for API symmetry
  // but the detail view replaces the rail with TaskWindowSidebar,
  // so they're not consumed here.
  onFilterChange: _onFilterChange,
  onProviderChange: _onProviderChange,
  onGroupChange: _onGroupChange,
  onRepoChange: _onRepoChange,
  onSelectTask: _onSelectTask,
  onOpenPr,
  onOpenSettings: _onOpenSettings,
}: Props) {
  const [task, setTask] = useState<TaskDto | null>(null);
  const [messages, setMessages] = useState<TaskMessageDto[]>([]);
  const [turns, setTurns] = useState<TaskTurnDto[]>([]);
  const [turnEvents, setTurnEvents] = useState<TaskTurnEventDto[]>([]);
  const [files, setFiles] = useState<TaskFileDto[]>([]);
  const [allTasks, setAllTasks] = useState<TaskDto[]>([]);
  const [groups, setGroups] = useState<TaskGroupDto[]>([]);
  const [memberships, setMemberships] = useState<TaskGroupMembershipDto[]>([]);
  const [watchedRepos, setWatchedRepos] = useState<WatchedRepoDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  // Reply draft persists across navigation for the lifetime of the
  // renderer — leave the page mid-sentence, come back, the text is
  // still here. Keyed by taskId so per-task drafts stay separate.
  const [draft, setDraft] = usePersistentDraft(`reply:${taskId}`);
  const [sending, setSending] = useState(false);
  const [theme, setTheme] = useState<TermTheme>(loadTheme);
  const [view, setView] = useState<DetailView>(loadView);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(loadSidebarCollapsed);

  useEffect(() => {
    try { window.localStorage.setItem(THEME_STORAGE_KEY, theme); }
    catch { /* private browsing — fine to skip */ }
  }, [theme]);

  useEffect(() => {
    try { window.localStorage.setItem(VIEW_STORAGE_KEY, view); }
    catch { /* private browsing — fine to skip */ }
  }, [view]);

  useEffect(() => {
    try { window.localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, sidebarCollapsed ? '1' : '0'); }
    catch { /* private browsing — fine to skip */ }
  }, [sidebarCollapsed]);

  const refresh = useCallback(async () => {
    try {
      const [t, m, ts, tes, fs, list, gs, ms, wrs] = await Promise.all([
        window.bridge.getTask(taskId),
        window.bridge.getTaskMessages(taskId),
        window.bridge.getTaskTurns(taskId).catch(() => [] as TaskTurnDto[]),
        window.bridge.getTaskTurnEvents(taskId).catch(() => [] as TaskTurnEventDto[]),
        window.bridge.getTaskFiles(taskId).catch(() => [] as TaskFileDto[]),
        window.bridge.listTasks(),
        window.bridge.listTaskGroups().catch(() => [] as TaskGroupDto[]),
        window.bridge.listTaskGroupMemberships().catch(() => [] as TaskGroupMembershipDto[]),
        window.bridge.getWatchedRepos().catch(() => [] as WatchedRepoDto[]),
      ]);
      setTask(t);
      setMessages(m);
      setTurns(ts);
      setTurnEvents(tes);
      setFiles(fs);
      setAllTasks(list);
      setGroups(gs);
      setMemberships(ms);
      setWatchedRepos(wrs);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId]);

  /** Same workingDir → owner/repo resolver TasksPage uses: walk the
   *  path segments looking for a watched-repo name match so worktrees
   *  rooted under the watched repo resolve correctly. */
  const resolveTaskRepo = useCallback(
    (t: TaskDto): { owner: string; repo: string } | null => {
      const segments = (t.workingDir ?? '').split('/').filter(Boolean).map(s => s.toLowerCase());
      if (segments.length === 0) return null;
      for (const wr of watchedRepos) {
        if (segments.includes(wr.repo.toLowerCase())) {
          return { owner: wr.owner, repo: wr.repo };
        }
      }
      return null;
    }, [watchedRepos]);

  /** Click handler for the sidebar's PR chip. No-op when we can't
   *  determine the owner — the chip itself hides in that case. */
  const onSidebarOpenPr = useCallback((prNumber: number) => {
    if (task === null) return;
    const ctx = resolveTaskRepo(task);
    if (ctx === null) return;
    onOpenPr?.(ctx.owner, ctx.repo, prNumber);
  }, [task, resolveTaskRepo, onOpenPr]);

  /** Add or remove this task from a group. Mirrors the toggle on the
   *  TasksPage so the affordance feels the same wherever you find it.
   *  Membership refresh is implicit: refresh() reloads memberships. */
  const onToggleGroup = useCallback(
    async (id: string, nextGroupId: string, present: boolean) => {
      try {
        if (present) {
          await window.bridge.addTaskToGroup(nextGroupId, id);
        }
        else {
          await window.bridge.removeTaskFromGroup(nextGroupId, id);
        }
        await refresh();
      }
      catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }, [refresh]);

  /** Group IDs the current task belongs to. */
  const currentGroupIds = useMemo(
    () => memberships.filter(m => m.taskId === taskId).map(m => m.groupId),
    [memberships, taskId],
  );
  const hasActiveTurn = useMemo(
    () => turns.some(turn => turn.status === 'RUNNING' || turn.status === 'QUEUED'),
    [turns],
  );

  const onRename = useCallback(async (nextTitle: string) => {
    const trimmed = nextTitle.trim();
    if (!trimmed || trimmed === task?.title) return;
    try {
      const updated = await window.bridge.renameTask(taskId, trimmed);
      setTask(updated);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId, task?.title]);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    const interval = pollInterval(task?.status, hasActiveTurn);
    if (!interval) return;
    const id = setInterval(() => { void refresh(); }, interval);
    return () => clearInterval(id);
  }, [task?.status, hasActiveTurn, refresh]);

  // Per-turn live text buffer fed by AssistantTextDelta SSE events.
  // The chunk-by-chunk text grows the in-flight assistant card so
  // the user sees the answer streaming in instead of waiting for the
  // whole message envelope to land. Cleared once the canonical
  // AssistantText row arrives via /messages refresh (or the turn
  // ends), so the streaming card swaps to the persisted one without
  // a visible flicker.
  const [liveText, setLiveText] = useState('');
  const liveTextRef = useRef('');

  // Running in-flight usage overlay fed by UsageUpdated SSE events.
  // The metrics panel adds these on top of the persisted per-turn
  // totals so token counts climb visibly as the model streams; they
  // clear once the next refresh pulls in the closing TurnDone row.
  const [liveUsage, setLiveUsage] = useState<{ tokensIn: number; tokensOut: number } | null>(null);
  const liveUsageRef = useRef<{ tokensIn: number; tokensOut: number } | null>(null);

  // The ConvIndex panel borrows this ref to register its own SSE
  // callback (UserMessage / TurnDone → re-fetch the tail window).
  // We route through the same ref the floating panel populates so
  // we don't open a second SSE stream per task — one subscriber,
  // multiple consumers. Null when the panel isn't mounted (e.g.
  // empty task or terminal view).
  const convIndexSseRef = useRef<((name: string) => void) | null>(null);
  // Sibling of convIndexSseRef — CheckpointsSection registers its
  // refetch trigger here so it picks up scheduler-generated segments
  // on TurnDone without opening a second SSE stream.
  const checkpointsSseRef = useRef<((name: string) => void) | null>(null);

  // Scroll container for the agent transcript. The ConvIndex's
  // click handler runs scrollIntoView on the user-message row in
  // here that matches the clicked entry's seq.
  const historyScrollRef = useRef<HTMLDivElement | null>(null);

  // Live SSE subscription — split into two paths by event kind:
  //  • AssistantTextDelta: append the chunk to the live buffer, no
  //    refresh (deltas aren't persisted; /messages wouldn't change).
  //  • UsageUpdated: replace the live usage overlay, no refresh
  //    (deltas aren't persisted; turn boundary writes the row).
  //  • Everything else: debounced refresh to pull the canonical
  //    state. Once the refresh lands the live buffers are cleared on
  //    the assumption that the assembled AssistantText / TurnDone
  //    rows are now in the messages array. Same pattern Phase A
  //    used; deltas are additive on top.
  // Only opens while the task is still live; terminal-status
  // sessions don't emit anything new. Re-runs only when the task
  // *status* flips, not on every refresh tick, so the SSE channel
  // stays open across polls.
  const status = task?.status;
  useEffect(() => {
    if (!status) return;
    if (status === 'COMPLETED' || status === 'ERRORED') return;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let disposed = false;
    const flushBuffer = () => {
      liveTextRef.current = '';
      setLiveText('');
      liveUsageRef.current = null;
      setLiveUsage(null);
    };
    const schedulePing = () => {
      if (timer || disposed) return;
      timer = setTimeout(() => {
        timer = null;
        void refresh().then(() => {
          if (!disposed) flushBuffer();
        });
      }, STREAM_REFRESH_DEBOUNCE_MS);
    };
    const onEvent = (event: { name: string; data: Record<string, unknown> }) => {
      // Fan out to the floating ConvIndex panel first so its
      // tail-window refetch runs in parallel with the parent's
      // own refresh. The panel ignores everything except
      // UserMessage / TurnDone, so this is cheap.
      convIndexSseRef.current?.(event.name);
      checkpointsSseRef.current?.(event.name);
      if (event.name === 'AssistantTextDelta') {
        const chunk = typeof event.data.textChunk === 'string' ? event.data.textChunk : '';
        if (chunk.length === 0) return;
        liveTextRef.current += chunk;
        setLiveText(liveTextRef.current);
        return;
      }
      if (event.name === 'UsageUpdated') {
        // Anthropic's streaming format splits usage across two events:
        // message_start carries input_tokens (the dominant number for a
        // turn — the full prompt cost) and a tiny output_tokens; the
        // later message_delta(s) carry the growing output_tokens but
        // often omit input_tokens. A naive replace would lose the input
        // count after the first delta lands. Merge with a running max
        // instead — input_tokens monotonically grows across messages in
        // a turn anyway, and output_tokens within a single message is
        // already cumulative.
        const tIn = typeof event.data.tokensIn === 'number' ? event.data.tokensIn : 0;
        const tOut = typeof event.data.tokensOut === 'number' ? event.data.tokensOut : 0;
        const prev = liveUsageRef.current;
        const merged = {
          tokensIn: Math.max(tIn, prev?.tokensIn ?? 0),
          tokensOut: Math.max(tOut, prev?.tokensOut ?? 0),
        };
        liveUsageRef.current = merged;
        setLiveUsage(merged);
        return;
      }
      schedulePing();
    };
    const unsubscribe = window.bridge.subscribeTaskStream(taskId, onEvent);
    return () => {
      disposed = true;
      if (timer) clearTimeout(timer);
      flushBuffer();
      unsubscribe();
    };
  }, [taskId, status, refresh]);

  // 1-second wall-clock tick while RUNNING. The /tasks/{id} poll
  // runs at 5s so without this the Lifetime · Runtime metric jumps
  // 5s at a time; the LIVE bar's elapsed-time counter would also
  // pause. Bumping a ticker forces a re-render so anything that
  // reads Date.now() inline (formatRuntime, the elapsed display
  // below) refreshes once per second. Tick stops the moment the
  // task leaves RUNNING.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (status !== 'RUNNING' && !hasActiveTurn) return;
    const id = setInterval(() => setTick(n => n + 1), 1000);
    return () => clearInterval(id);
  }, [status, hasActiveTurn]);

  const pendingPermission = useMemo<PendingPermission | null>(
    () => findPendingPermission(messages),
    [messages]);

  const stage = useMemo(() => deriveStage(messages, task?.status), [messages, task?.status]);

  const onSend = useCallback(async () => {
    const text = draft.trim();
    if (!text || sending) return;
    setSending(true);
    try {
      await window.bridge.sendTaskMessage(taskId, text);
      setDraft('');
      await refresh();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSending(false);
    }
  }, [taskId, draft, sending, refresh]);

  const onDecide = useCallback(async (
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => {
    try {
      await window.bridge.decideTaskPermission(taskId, callId, decision, preApprove);
      await refresh();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId, refresh]);

  const onInterrupt = useCallback(async () => {
    try {
      await window.bridge.interruptTask(taskId);
      await refresh();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId, refresh]);

  const onStop = useCallback(async () => {
    try {
      await window.bridge.stopTask(taskId);
      await refresh();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId, refresh]);

  const onResume = useCallback(async () => {
    try {
      await window.bridge.resumeTask(taskId);
      await refresh();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId, refresh]);

  const onDelete = useCallback(async () => {
    // Destructive — confirm before we drop the row + its conversation
    // log. Native confirm() is fine here; the dialog gets dismissed
    // on cancel and we just no-op.
    if (!confirm(
      `Delete this task permanently?\n\nThe conversation log and per-file rollup will be removed and can't be recovered.`)) {
      return;
    }
    try {
      await window.bridge.deleteTask(taskId);
      onBack();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId, onBack]);

  // Hooks must run unconditionally on every render — declare these
  // before the loading / error early-returns so React's hook ordering
  // stays stable (the prior placement after the guards triggered
  // "Rendered more hooks than during the previous render" when task
  // flipped from null → loaded between mounts).
  const changeStats = useMemoChangeStats(files);
  const [diffOpen, setDiffOpen] = useState<boolean>(loadDiffOpen);
  useEffect(() => {
    try { window.localStorage.setItem(DIFF_OPEN_STORAGE_KEY, diffOpen ? '1' : '0'); }
    catch { /* private browsing — fine to skip */ }
  }, [diffOpen]);
  const onReview = useCallback(() => setDiffOpen(open => !open), []);
  const diffState = useTaskDiffState(taskId);
  const [diffSplitFrac, setDiffSplitFrac] = useDiffSplitFrac();
  const splitContainerRef = useRef<HTMLDivElement | null>(null);

  if (task === null && error) {
    return (
      <section style={layoutStyle}>
        <div style={emptyShellStyle}>
          <BackBar onBack={onBack} title="(failed to load)" />
          <div style={errorBannerStyle}>{error}</div>
        </div>
      </section>
    );
  }
  if (task === null) {
    return (
      <section style={layoutStyle}>
        <div style={emptyShellStyle}>
          <BackBar onBack={onBack} title="loading…" />
        </div>
      </section>
    );
  }

  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';
  const modelName = resolvedModelName(task.model, messages);

  return (
    <section style={layoutStyle}>
      <KeyframesStyles />
      {sidebarCollapsed ? (
        <SidebarCollapsedRail
          onBack={onBack}
          onExpand={() => setSidebarCollapsed(false)}
        />
      ) : (
        <TaskWindowSidebar
          task={task}
          modelName={modelName}
          messages={messages}
          turns={turns}
          turnEvents={turnEvents}
          files={files}
          liveUsage={liveUsage}
          groups={groups}
          currentGroupIds={currentGroupIds}
          onToggleGroup={onToggleGroup}
          onOpenPr={onOpenPr ? onSidebarOpenPr : undefined}
          onBack={onBack}
          onCollapse={() => setSidebarCollapsed(true)}
          checkpointsSseRef={checkpointsSseRef}
        />
      )}
      <div style={taskWindowStyle}>
        <div style={taskWindowBodyStyle} ref={splitContainerRef}>
          <div
            style={diffOpen
              ? { ...splitLeftStyle, flex: `${diffSplitFrac} 1 0` }
              : flexFillStyle}
          >
            {view === 'conversation' && (
              <StructuredView
                task={task}
                modelName={modelName}
                messages={messages}
                pendingPermission={pendingPermission}
                onDecide={onDecide}
                draft={draft}
                onDraft={setDraft}
                onSend={onSend}
                onInterrupt={onInterrupt}
                sending={sending}
                isTerminal={isTerminal}
                changeStats={changeStats}
                diffOpen={diffOpen}
                onReview={onReview}
                diffMode={diffState.mode}
                onChangeDiffMode={diffState.setMode}
                workingCount={diffState.workingCount}
                commitsCount={diffState.commitsCount}
                view={view}
                onChangeView={setView}
                onRename={onRename}
                onStop={onStop}
                canStop={!isTerminal}
                onDelete={onDelete}
                canDelete={isTerminal}
                onResume={onResume}
                liveText={liveText}
                liveUsage={liveUsage}
                taskId={taskId}
                historyScrollRef={historyScrollRef}
                convIndexSseRef={convIndexSseRef}
              />
            )}
            {view === 'terminal' && (
              // Wrap so the palette (var(--term-*)) flows to
              // ConversationPane + StatusBar + TermInput inside.
              <div style={{ ...terminalScopeStyle, ...termCssVars(theme) }}>
                <TerminalWrap
                  task={task}
                  modelName={modelName}
                  messages={messages}
                  pendingPermission={pendingPermission}
                  onDecide={onDecide}
                  stage={stage}
                  draft={draft}
                  onDraft={setDraft}
                  onSend={onSend}
                  onInterrupt={onInterrupt}
                  sending={sending}
                  isTerminal={isTerminal}
                  theme={theme}
                  onToggleTheme={() => setTheme(t => t === 'dark' ? 'light' : 'dark')}
                  view={view}
                  onChangeView={setView}
                  onRename={onRename}
                  onStop={onStop}
                  canStop={!isTerminal}
                  onDelete={onDelete}
                  canDelete={isTerminal}
                  onResume={onResume}
                  changeStats={changeStats}
                  diffOpen={diffOpen}
                  onReview={onReview}
                  diffMode={diffState.mode}
                  onChangeDiffMode={diffState.setMode}
                  workingCount={diffState.workingCount}
                  commitsCount={diffState.commitsCount}
                  liveText={liveText}
                  liveUsage={liveUsage}
                  taskId={taskId}
                  convIndexSseRef={convIndexSseRef}
                />
              </div>
            )}
          </div>
          {diffOpen && (
            <OuterSplitter
              containerRef={splitContainerRef}
              frac={diffSplitFrac}
              onChange={setDiffSplitFrac}
            />
          )}
          {diffOpen && (
            <div
              style={{ ...splitRightStyle, flex: `${1 - diffSplitFrac} 1 0` }}
            >
              <TaskDiffPane
                taskId={taskId}
                mode={diffState.mode}
                onChangeMode={diffState.setMode}
                workingCount={diffState.workingCount}
                commitsCount={diffState.commitsCount}
              />
            </div>
          )}
        </div>
      </div>

      {error && <div style={errorBannerStyle}>{error}</div>}
    </section>
  );
}

/** Sums the per-file deltas so the bottom tab bar and the Review
 *  strip can show a single "+X / -Y" pair. Memoised on file array
 *  identity so every keystroke in the reply input doesn't reduce
 *  the list. */
function useMemoChangeStats(files: TaskFileDto[]) {
  return useMemo(() => {
    let added = 0;
    let removed = 0;
    for (const f of files) {
      added += f.linesAdded;
      removed += f.linesRemoved;
    }
    return { files: files.length, added, removed };
  }, [files]);
}

type ChangeStats = { files: number; added: number; removed: number };

// ────────────────────────────────────────────────────────────────────
// Sub-components
// ────────────────────────────────────────────────────────────────────

function BackBar({ onBack, title }: { onBack: () => void; title: string }) {
  return (
    <div style={breadcrumbRowStyle}>
      <button type="button" onClick={onBack} style={crumbBackStyle}>←</button>
      <button type="button" onClick={onBack} style={crumbLinkStyle}>Tasks</button>
      <span style={crumbSepStyle}>/</span>
      <span style={crumbCurrentStyle}>{title}</span>
    </div>
  );
}

/** Click-to-edit task title. Renders as a plain heading by default;
 *  clicking flips to an inline input. Enter / blur saves, Escape
 *  reverts. The pencil glyph is decorative — the whole heading is
 *  the click target so the affordance is generous without crowding
 *  the layout. */
function EditableTitle({ title, onRename, maxDisplayWords, titleStyleOverride }: {
  title: string;
  onRename: (next: string) => void | Promise<void>;
  /** Optional cap on how many whitespace-separated words of the title
   *  are rendered in the resting state. Anything past the cap is
   *  replaced with `…`. The editor still operates on the full title —
   *  the cap only affects the display chip. Used by the terminal
   *  toolbar where the badge is content-sized; a 25-word essay-style
   *  title would balloon it. */
  maxDisplayWords?: number;
  /** Style merged into the inner title span. The shared default
   *  (`thTitleStyle`) carries `overflow:hidden + ellipsis + nowrap`,
   *  which interacts badly with the terminal-mode badge that wants
   *  to size to its full content — passing `{ overflow: 'visible',
   *  textOverflow: 'clip' }` here suppresses that truncation. */
  titleStyleOverride?: React.CSSProperties;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(title);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => { if (!editing) setDraft(title); }, [title, editing]);
  useEffect(() => {
    if (editing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [editing]);

  function commit() {
    const trimmed = draft.trim();
    if (trimmed && trimmed !== title) {
      void onRename(trimmed);
    }
    setEditing(false);
  }

  if (editing) {
    return (
      <input
        ref={inputRef}
        type="text"
        value={draft}
        onChange={e => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={e => {
          if (e.key === 'Enter') { e.preventDefault(); commit(); }
          else if (e.key === 'Escape') { e.preventDefault(); setDraft(title); setEditing(false); }
        }}
        style={titleEditInputStyle}
      />
    );
  }
  const displayTitle = (() => {
    if (!maxDisplayWords) return title;
    const words = title.trim().split(/\s+/).filter(Boolean);
    if (words.length <= maxDisplayWords) return title;
    return `${words.slice(0, maxDisplayWords).join(' ')}…`;
  })();
  return (
    <button
      type="button"
      onClick={() => setEditing(true)}
      style={titleEditTriggerStyle}
      title={title.length > displayTitle.length
        ? `${title} — click to rename (Enter saves, Esc cancels)`
        : 'Click to rename — Enter to save, Esc to cancel'}
    >
      <span style={{ ...thTitleStyle, ...titleStyleOverride }}>{displayTitle}</span>
      <span style={titleEditPencilStyle} aria-hidden>✎</span>
    </button>
  );
}

/**
 * Structured detail view — three deliberately distinct zones:
 *
 *  • HISTORY: scrollable recessed pane that hosts the full
 *    {@link ConversationPane}. Past exchanges read as "below" the
 *    active work.
 *  • LIVE: callout strip that appears above the History scroll when
 *    the task is RUNNING, surfacing the streaming-in indicator and
 *    an Interrupt button so the user can jump in.
 *  • REPLY: sticky bottom input with mode chips and a Send / Queue
 *    button (label changes mid-stream).
 *
 * Follows {@code docs/mockups/design/tasks/task-detail.png}. Reuses
 * the existing {@code ConversationPane} renderer rather than
 * physically splitting the message stream — the visual zones convey
 * the past/live/your-turn distinction without re-parsing turns.
 */
function StructuredView({
  task,
  modelName,
  messages,
  pendingPermission,
  onDecide,
  draft,
  onDraft,
  onSend,
  onInterrupt,
  sending,
  isTerminal,
  changeStats,
  diffOpen,
  onReview,
  diffMode,
  onChangeDiffMode,
  workingCount,
  commitsCount,
  view,
  onChangeView,
  onRename,
  onStop,
  canStop,
  onDelete,
  canDelete,
  onResume,
  liveText,
  liveUsage,
  taskId,
  historyScrollRef,
  convIndexSseRef,
}: {
  task: TaskDto;
  modelName: string;
  messages: TaskMessageDto[];
  pendingPermission: PendingPermission | null;
  onDecide: (
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void;
  draft: string;
  onDraft: (s: string) => void;
  onSend: () => void;
  onInterrupt: () => void;
  sending: boolean;
  isTerminal: boolean;
  changeStats: ChangeStats;
  diffOpen: boolean;
  onReview: () => void;
  diffMode: DiffMode;
  onChangeDiffMode: (next: DiffMode) => void;
  workingCount: number | null;
  commitsCount: number | null;
  view: DetailView;
  onChangeView: (next: DetailView) => void;
  onRename: (title: string) => void | Promise<void>;
  onStop: () => void;
  canStop: boolean;
  onDelete: () => void;
  canDelete: boolean;
  /** Flip an ERRORED task back to IDLE so the user can keep typing.
   *  The next turn reuses the agent's CLI session id via
   *  `claude --resume <id>`, so the conversation continues without
   *  losing context — handy after a token-quota reset. */
  onResume: () => void;
  /** Per-token assistant text growing in the SSE stream that the
   *  persisted log doesn't have yet. Empty when no streaming is in
   *  flight; otherwise the partial assembled text since the last
   *  assistant message envelope landed. */
  liveText: string;
  /** Streaming in-flight token totals for the current turn. Used to
   *  show "↑/↓ N tokens" next to the elapsed counter in the LIVE
   *  bar, matching what Claude Code's CLI shows while thinking. */
  liveUsage: { tokensIn: number; tokensOut: number } | null;
  /** Task id used by the floating ConvIndex panel to fetch its
   *  own /index window. Passed through rather than re-derived so
   *  the parent owns the source of truth. */
  taskId: string;
  /** The agent transcript's scroll container — ConvIndex calls
   *  {@code scrollIntoView()} on the matching {@code data-seq}
   *  element inside it when the user clicks an index row. */
  historyScrollRef: React.RefObject<HTMLDivElement | null>;
  /** Where ConvIndex stuffs its SSE callback. The parent invokes
   *  the callback from its existing stream handler so we don't
   *  open a second SSE per task. */
  convIndexSseRef: React.MutableRefObject<((name: string) => void) | null>;
}) {
  const turns = useMemo(
    () => messages.filter(m => m.type === 'turn_done').length,
    [messages]);
  const isRunning = task.status === 'RUNNING';
  const replyRef = useAutoGrowTextarea(draft, 220);

  // Captures the wall-clock moment this turn went RUNNING, so the
  // LIVE bar can show "5s · 1.2k tokens" the way Claude Code's CLI
  // does while it's thinking. Each false→true transition starts a
  // fresh clock; we never read this until isRunning is true, so the
  // initial null is fine. Page-level 1s tick re-renders us, so the
  // displayed elapsed advances even when no deltas are arriving.
  const turnStartRef = useRef<number | null>(null);
  if (isRunning && turnStartRef.current === null) {
    turnStartRef.current = Date.now();
  }
  if (!isRunning && turnStartRef.current !== null) {
    turnStartRef.current = null;
  }
  const elapsedLabel = isRunning && turnStartRef.current !== null
    ? formatElapsedSeconds(Math.max(0, Math.floor((Date.now() - turnStartRef.current) / 1000)))
    : null;
  const liveTokenTotal = (liveUsage?.tokensIn ?? 0) + (liveUsage?.tokensOut ?? 0);
  const liveUsageLabel = formatLiveUsage(liveUsage);
  const agentCwd = taskAgentCwd(task);
  const displayBranch = taskDisplayBranch(task);

  return (
    <div style={structuredWrapStyle}>
      <div style={historyZoneStyle}>
        <div style={zoneHeaderStyle}>
          <div style={taskTitleBadgeStyle}>
            {/* Same shape the terminal toolbar uses: a 20-word JS
                cap + a `titleStyleOverride` that turns OFF the
                shared `thTitleStyle` ellipsis. Without the
                override, the inner span's `overflow:hidden +
                ellipsis + nowrap` collapses the title to "let's
                …" the moment the surrounding flex row gets tight
                (the bug in docs/mockups/issue/tasks/name.png). */}
            <EditableTitle
              title={task.title}
              onRename={onRename}
              maxDisplayWords={20}
              titleStyleOverride={termTitleSpanStyle}
            />
          </div>
          <div style={twHeaderMetaStyle}>
            <RepoAvatar workingDir={task.workingDir} size={14} />
            {agentCwd && (
              <span style={twHeaderRepoStyle} title={agentCwd}>{shortenPath(agentCwd)}</span>
            )}
            {displayBranch && (
              <>
                <span style={twHeaderSepStyle}>·</span>
                <span style={twHeaderChipStyle} title={`branch ${displayBranch}`}>
                  ⎇ {displayBranch}
                </span>
              </>
            )}
            {modelName && (
              <>
                <span style={twHeaderSepStyle}>·</span>
                <span style={twHeaderChipStyle}>{modelName}</span>
              </>
            )}
            <span style={twHeaderSepStyle}>·</span>
            <span style={zoneMetaStyle}>
              {messages.length} message{messages.length === 1 ? '' : 's'} · {turns} turn{turns === 1 ? '' : 's'}
            </span>
          </div>
          <span style={{ flex: 1 }} />
          <ViewToggle view={view} onChangeView={onChangeView} />
          <div style={twHeaderActionsStyle}>
            {canStop && (
              <button type="button" onClick={onStop} style={twHeaderStopBtnStyle}>
                ⏹ Stop
              </button>
            )}
            {canDelete && (
              <button type="button" onClick={onDelete} style={twHeaderDeleteBtnStyle}>
                🗑 Delete
              </button>
            )}
          </div>
        </div>
        <div ref={historyScrollRef} style={historyScrollStyle}>
          <StructuredConversation
            messages={messages}
            pendingPermission={pendingPermission}
            onDecide={onDecide}
            modelName={modelName}
            liveText={liveText}
          />
        </div>
        {/* Floating right-edge index panel — anchored to the
            history zone (position: relative on the parent). Hidden
            automatically when the task has no user prompts yet. */}
        <ConvIndex
          taskId={taskId}
          scrollContainerRef={historyScrollRef}
          onSseEvent={convIndexSseRef}
        />
      </div>

      {isRunning && (
        // Sweep animation lives on the bar's own background (see
        // liveZoneStyle + bytequay-live-sweep keyframe) so the
        // content stays in normal flex flow and isn't pushed around
        // by an absolute overlay.
        <div style={liveZoneStyle} className="bytequay-live-sweep">
          <span style={livePulseStyle} className="bytequay-pulse" />
          <span style={liveLabelStyle}>LIVE</span>
          <span style={liveMetaStyle}>· Claude is responding</span>
          {elapsedLabel !== null && (
            <span style={liveStatStyle} title="Time since this turn started">
              · {elapsedLabel}
            </span>
          )}
          {liveTokenTotal > 0 && (
            <span style={liveStatStyle} title="Live token usage reported by the CLI for this turn">
              · {liveUsageLabel}
            </span>
          )}
          <span style={{ flex: 1 }} />
          <button type="button" onClick={onInterrupt} style={interruptBtnStyle}>
            ⏵ Interrupt
          </button>
        </div>
      )}

      {/* Always render the strip so the user can toggle the diff pane
          regardless of whether the working tree is dirty — the pane's
          smart default falls back to Commits when clean. The strip
          tints yellow when there's something to see. */}
      <ReviewStrip
        hasChanges={changeStats.files > 0}
        diffOpen={diffOpen}
        onReview={onReview}
        diffMode={diffMode}
        onChangeDiffMode={onChangeDiffMode}
        workingCount={workingCount}
        commitsCount={commitsCount}
      />

      {!isTerminal && (
        <div style={replyZoneStyle}>
          <div style={replyHeaderStyle}>
            <span style={replyIconStyle}>⌨</span>
            <span style={replyLabelStyle}>REPLY</span>
            <span style={replyMetaStyle}>
              · {isRunning
                ? 'queue while Claude finishes, or interrupt to jump in'
                : 'send a follow-up turn'}
            </span>
          </div>
          <textarea
            ref={replyRef}
            value={draft}
            onChange={e => onDraft(e.target.value)}
            placeholder={isRunning
              ? 'message will be queued for after current turn…'
              : 'send a follow-up turn…'}
            disabled={sending}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                onSend();
              }
            }}
            style={replyTextareaStyle}
          />
          <div style={replyFooterStyle}>
            <span style={replyHintStyle}>
              <Kbd>↵</Kbd> send · <Kbd>⇧</Kbd>+<Kbd>↵</Kbd> newline
            </span>
            <span style={{ flex: 1 }} />
            <button
              type="button"
              onClick={onSend}
              disabled={!draft.trim() || sending}
              style={replySendBtnStyle}
            >
              {sending ? 'sending…' : (isRunning ? 'Queue →' : 'Send →')}
            </button>
          </div>
        </div>
      )}
      {/* When the CLI turn ends in ERRORED (token-quota reset,
          network blip, agent crash) we hide the reply zone — but
          stopping the conversation there forces the user to recreate
          the task to keep going. Resume flips the task back to IDLE
          and reuses the persisted agentSessionId, so the next turn
          spawns `claude --resume <id>` and the model picks up with
          its prior context. COMPLETED stays as-is: the user already
          decided that conversation is done. */}
      {isTerminal && task.status === 'ERRORED' && (
        <ResumeBanner
          message={task.errorMessage}
          onResume={onResume}
        />
      )}
    </div>
  );
}

function ResumeBanner({
  message, onResume,
}: { message: string | null | undefined; onResume: () => void }) {
  return (
    <div style={resumeBannerStyle}>
      <div style={resumeBannerCopyStyle}>
        <span style={resumeBannerTitleStyle}>Turn ended in error</span>
        {message && message.length > 0 && (
          <span style={resumeBannerMsgStyle} title={message}>
            {message.length > 180 ? message.slice(0, 177) + '…' : message}
          </span>
        )}
      </div>
      <button type="button" onClick={onResume} style={resumeBannerBtnStyle}>
        ↻ Resume conversation
      </button>
    </div>
  );
}

/**
 * Left-rail sidebar for the task detail page. Replaces the global
 * TasksLeftRail while a task is open. Layout follows
 * docs/mockups/design/tasks/task-detail-tabs.png:
 *
 *  • Back button (returns to the task list)
 *  • Status badge (● RUNNING / IDLE / COMPLETED …)
 *  • TODAY — runtime, cost, tokens, tool calls, files touched, model
 *  • CONTEXT WINDOW — approximate "% used" derived from the latest
 *    turn's input_tokens vs the model's context limit
 *  • CURRENT STAGE — the in-flight tool (if any) plus a one-line
 *    input summary
 *  • CHECKPOINTS — stub for now; the auto-summarisation feature is
 *    captured in followups/tasks-checkpoints-and-context.md
 */
function TaskWindowSidebar({
  task, modelName, messages, turns, turnEvents, files, liveUsage,
  groups, currentGroupIds, onToggleGroup,
  onOpenPr,
  onBack, onCollapse,
  checkpointsSseRef,
}: {
  task: TaskDto;
  modelName: string;
  messages: TaskMessageDto[];
  turns: TaskTurnDto[];
  turnEvents: TaskTurnEventDto[];
  files: TaskFileDto[];
  liveUsage: { tokensIn: number; tokensOut: number } | null;
  groups: TaskGroupDto[];
  currentGroupIds: string[];
  onToggleGroup: (taskId: string, groupId: string, present: boolean) => void | Promise<void>;
  /** Open the linked PR in the repo detail view. Hidden when
   *  omitted (e.g. the linked PR's owner/repo can't be resolved
   *  from the task's working dir). */
  onOpenPr?: (prNumber: number) => void;
  onBack: () => void;
  onCollapse: () => void;
  /** Bridge to the parent's SSE handler — CheckpointsSection
   *  registers a TurnDone-triggered refetch here. */
  checkpointsSseRef: React.MutableRefObject<((name: string) => void) | null>;
}) {
  const toolUsage = useMemo(() => deriveToolUsage(messages), [messages]);
  const ctx = useMemo(() => computeContextUsage(messages, modelName), [messages, modelName]);
  const scheduler = useMemo(() => summarizeTurnState(turns, task.status), [turns, task.status]);
  const agentCwd = taskAgentCwd(task);
  const displayBranch = taskDisplayBranch(task);
  const [sessionActionError, setSessionActionError] = useState<string | null>(null);
  const openAgentCwd = useCallback(async (target: 'finder' | 'terminal' | 'ide') => {
    setSessionActionError(null);
    try {
      if (target === 'finder') {
        await window.bridge.revealRepoInFinder(agentCwd);
        return;
      }
      if (target === 'terminal') {
        await window.bridge.openRepoInTerminal(agentCwd);
        return;
      }
      await window.bridge.openRepoInIDE(agentCwd);
    }
    catch (e) {
      setSessionActionError(e instanceof Error ? e.message : String(e));
    }
  }, [agentCwd]);
  // Overlay running deltas while a turn is in flight; falls back to
  // the persisted totals once liveUsage clears at the next refresh.
  const tokensInDisplay = (task.tokensIn ?? 0) + (liveUsage?.tokensIn ?? 0);
  const tokensOutDisplay = (task.tokensOut ?? 0) + (liveUsage?.tokensOut ?? 0);
  return (
    <aside style={twSidebarStyle}>
      <div style={twSidebarBackRowStyle}>
        <button type="button" onClick={onBack} style={twBackBtnStyle}>
          ← Back to all tasks
        </button>
        <button
          type="button"
          onClick={onCollapse}
          style={twSidebarCollapseBtnStyle}
          title="Collapse sidebar"
          aria-label="Collapse sidebar"
        >
          ‹
        </button>
      </div>

      <div style={twStatusRowStyle}>
        <StatusPill status={task.status} />
      </div>

      {/* Section labelled "Lifetime" — the metrics here are
          task-lifetime totals, not today-only. The old "Today" label
          implied a daily roll-up the backend never does, which
          surprised users who saw 36 files but tokens that exceeded
          a single day. */}
      <SidebarSection label="Lifetime">
        <div style={metricListStyle}>
          <Metric label="Runtime" value={formatRuntime(task)} live={task.status === 'RUNNING'} />
          <Metric label="Cost" value={formatCost(task.costUsdMilli)} />
          <Metric
            label="Tokens in"
            value={formatNum(tokensInDisplay)}
            live={liveUsage !== null}
          />
          <Metric
            label="Tokens out"
            value={formatNum(tokensOutDisplay)}
            live={liveUsage !== null}
          />
          <Metric label="Tool calls" value={formatNum(toolUsage.total)} />
          <Metric label="Files touched" value={formatNum(files.length)} />
          <Metric label="Model" value={formatModelLabel(modelName)} mono />
        </div>
      </SidebarSection>

      <SidebarSection label="Scheduler" hint="Submitted waits; started runs">
        <div style={metricListStyle}>
          <Metric label="Turn state" value={scheduler.state} live={scheduler.live} />
          <Metric label="Lane" value={scheduler.lane} mono />
          <Metric label="Waiting turns" value={String(scheduler.queued)} />
          {scheduler.latestInput !== '' && (
            <Metric label="Latest input" value={scheduler.latestInput} wrap />
          )}
          <SchedulerEventHistory events={turnEvents} turns={turns} />
        </div>
      </SidebarSection>

      {/* Session card — identifying metadata for the run itself.
          Session ID gets a mono ellipsis; branch + PR show up as
          inline chips so the user can copy / click into them. */}
      {/* Session ID = our internal task UUID (the route key,
          /tasks/:id). The Claude Code CLI's own session id stays
          on the Task row (`agentSessionId`) for backend-only use —
          we need it to `claude --resume <id>` on restart, but
          surfacing it in the UI confused the question of "which id
          am I looking at?". */}
      <SidebarSection label="Session">
        <div style={metricListStyle}>
          <Metric label="Session ID" value={task.id} mono wrap />
          <CopyableMetric label="Agent cwd" value={agentCwd} mono wrap />
          <div style={sessionPathActionRowStyle}>
            <button
              type="button"
              style={sessionPathActionBtnStyle}
              onClick={() => { void openAgentCwd('finder'); }}
            >
              Finder
            </button>
            <button
              type="button"
              style={sessionPathActionBtnStyle}
              onClick={() => { void openAgentCwd('terminal'); }}
            >
              Terminal
            </button>
            <button
              type="button"
              style={sessionPathActionBtnStyle}
              onClick={() => { void openAgentCwd('ide'); }}
            >
              IDE
            </button>
          </div>
          {sessionActionError !== null && (
            <div style={sessionActionErrorStyle}>{sessionActionError}</div>
          )}
          {displayBranch !== null && displayBranch !== '' && (
            <CopyableMetric
              label="Branch"
              value={`⎇ ${displayBranch}`}
              copyValue={displayBranch}
              mono
              wrap
            />
          )}
          {task.linkedPrNumber !== null && (
            <div style={metricRowStyle}>
              <span style={metricLabelStyle}>PR</span>
              <span style={{ ...metricValueStyle, textAlign: 'right' }}>
                {onOpenPr ? (
                  <button
                    type="button"
                    onClick={() => onOpenPr(task.linkedPrNumber as number)}
                    style={prChipBtnStyle}
                    title={`Open PR #${task.linkedPrNumber}`}
                  >
                    #{task.linkedPrNumber}
                  </button>
                ) : (
                  <span style={{ fontFamily: '"SF Mono", Menlo, monospace', fontSize: 12 }}>
                    #{task.linkedPrNumber}
                  </span>
                )}
              </span>
            </div>
          )}
          {task.linkedIssueNumber !== null && (
            <Metric
              label="Issue"
              value={`#${task.linkedIssueNumber}`}
              mono
            />
          )}
        </div>
      </SidebarSection>

      <SidebarSection label="Groups">
        <div style={groupsRowStyle}>
          {currentGroupIds.length === 0 ? (
            <span style={groupsEmptyStyle}>No groups pinned</span>
          ) : (
            currentGroupIds.map(id => {
              const g = groups.find(x => x.id === id);
              if (!g) return null;
              return (
                <span key={id} style={groupChipStyle} title={g.name}>
                  <span aria-hidden style={{ ...groupChipGlyphStyle, background: groupSwatchBg(g.color) }}>
                    {g.glyph || '•'}
                  </span>
                  <span style={groupChipLabelStyle}>{g.name}</span>
                </span>
              );
            })
          )}
          <GroupMenu
            task={task}
            groups={groups}
            currentGroupIds={currentGroupIds}
            onToggle={onToggleGroup}
          />
        </div>
      </SidebarSection>

      <SidebarSection label="Context window" hint={ctx.hint}>
        <ContextWindowBar pct={ctx.pct} used={ctx.used} limit={ctx.limit} />
      </SidebarSection>

      <SidebarSection label="Checkpoints">
        <CheckpointsSection taskId={task.id} sseRef={checkpointsSseRef} />
      </SidebarSection>
    </aside>
  );
}

/** Thin vertical rail shown in place of the full sidebar when the
 *  user collapses it. Surfaces just the back-button and an expand
 *  chevron — everything else (metrics, context window, stage,
 *  checkpoints) returns when the user expands again. */
function SidebarCollapsedRail({
  onBack, onExpand,
}: {
  onBack: () => void;
  onExpand: () => void;
}) {
  return (
    <aside style={twSidebarRailStyle}>
      <button
        type="button"
        onClick={onBack}
        style={twSidebarRailBtnStyle}
        title="Back to all tasks"
        aria-label="Back to all tasks"
      >
        ←
      </button>
      <button
        type="button"
        onClick={onExpand}
        style={twSidebarRailBtnStyle}
        title="Expand sidebar"
        aria-label="Expand sidebar"
      >
        ›
      </button>
      <span style={twSidebarRailLabelStyle}>Sidebar</span>
    </aside>
  );
}

function SidebarSection({
  label, hint, children,
}: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div style={twSectionStyle}>
      <div style={twSectionHeaderStyle}>
        <span style={twSectionLabelStyle}>{label}</span>
        {hint && <span style={twSectionHintStyle}>{hint}</span>}
      </div>
      <div style={twSectionBodyStyle} className="bytequay-sidebar-card">
        {children}
      </div>
    </div>
  );
}

/** Shared Conversation / Terminal pill-toggle, rendered inside each
 *  view's own header strip (zone header in conversation mode, term
 *  toolbar in terminal mode). The top-level TaskWindowHeader used to
 *  hold the only copy, but the title + meta + actions now live in
 *  the per-view headers, so this is the canonical placement. */
function ViewToggle({
  view, onChangeView,
}: {
  view: DetailView;
  onChangeView: (next: DetailView) => void;
}) {
  return (
    <div style={twHeaderViewToggleStyle} role="tablist" aria-label="View">
      {(['conversation', 'terminal'] as const).map(key => {
        const active = view === key;
        return (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChangeView(key)}
            style={{
              ...twHeaderViewBtnStyle,
              ...(active ? twHeaderViewBtnActiveStyle : null),
            }}
          >
            {key === 'conversation' ? '💬 Conversation' : '⌨ Terminal'}
          </button>
        );
      })}
    </div>
  );
}


/** "⇄ Diff" affordance above the reply input. Earlier this strip
 *  surfaced lifetime numbers (`N files · +X −Y`) from getTaskFiles,
 *  but those didn't match what the diff pane actually showed after
 *  the agent committed (working tree → 0 files, lifetime → 36). To
 *  remove the confusion we dropped the counts entirely and instead
 *  tint the strip yellow whenever changes exist — the colour is the
 *  signal. Click anywhere on the strip to toggle the pane. */
function ReviewStrip({
  hasChanges, diffOpen, onReview,
  diffMode, onChangeDiffMode, workingCount, commitsCount,
}: {
  hasChanges: boolean;
  diffOpen: boolean;
  onReview: () => void;
  diffMode: DiffMode;
  onChangeDiffMode: (next: DiffMode) => void;
  workingCount: number | null;
  commitsCount: number | null;
}) {
  // The whole strip is the click target — clicking anywhere folds /
  // unfolds the diff pane. The inner toggle pills stop propagation on
  // their own click so flipping Working tree ↔ Commits doesn't also
  // collapse the pane.
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onReview}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onReview();
        }
      }}
      style={hasChanges
        ? { ...reviewStripStyle, ...reviewStripChangesStyle }
        : reviewStripStyle}
      title={diffOpen ? 'Hide the diff pane' : 'Show the diff pane'}
      aria-expanded={diffOpen}
    >
      <span style={reviewStripChevronStyle} aria-hidden="true">
        {diffOpen ? '▾' : '▸'}
      </span>
      <span style={reviewStripLabelStyle}>⇄ Diff</span>
      {diffOpen && (
        <span
          onClick={(e) => e.stopPropagation()}
          style={{ display: 'inline-flex' }}
        >
          <DiffModeToggle
            mode={diffMode}
            onChangeMode={onChangeDiffMode}
            workingCount={workingCount}
            commitsCount={commitsCount}
            dense
          />
        </span>
      )}
      <span style={{ flex: 1 }} />
    </div>
  );
}

/** Draggable splitter between the conversation pane and the diff
 *  pane. Owns its own active-drag state so the parent doesn't
 *  re-render on every mouse move. {@code containerRef} is the parent
 *  whose width we measure to convert the pointer delta into a 0..1
 *  fraction. */
function OuterSplitter({
  containerRef, frac, onChange,
}: {
  containerRef: React.RefObject<HTMLDivElement | null>;
  frac: number;
  onChange: (next: number) => void;
}) {
  const [dragging, setDragging] = useState(false);
  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => {
      const el = containerRef.current;
      if (!el) return;
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0) return;
      onChange((e.clientX - rect.left) / rect.width);
    };
    const onUp = () => setDragging(false);
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    const prevCursor = document.body.style.cursor;
    const prevSelect = document.body.style.userSelect;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    return () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.cursor = prevCursor;
      document.body.style.userSelect = prevSelect;
    };
  }, [dragging, onChange, containerRef]);
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      title="Drag to resize"
      onMouseDown={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDoubleClick={() => onChange(0.5)}
      style={{
        flex: '0 0 6px',
        cursor: 'col-resize',
        background: dragging ? 'var(--accent)' : 'var(--border)',
        opacity: dragging ? 1 : 0.5,
        transition: 'opacity 100ms ease, background 100ms ease',
      }}
      onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.opacity = '1'; }}
      onMouseLeave={(e) => {
        if (!dragging) (e.currentTarget as HTMLDivElement).style.opacity = '0.5';
      }}
    />
  );
}

/** Horizontal "x% of N tokens" bar. Pure presentation — the math
 *  lives in computeContextUsage(). Capped visually at 100% so a
 *  model that overflows its window (rare; the agent will refuse
 *  before this) still draws a sane bar. */
function ContextWindowBar({
  pct, used, limit,
}: { pct: number; used: number; limit: number }) {
  const clamped = Math.max(0, Math.min(100, pct));
  const tone = clamped < 60 ? '#16a34a' : clamped < 85 ? '#d97706' : '#dc2626';
  return (
    <div style={ctxBarWrapStyle}>
      <div style={ctxBarRowStyle}>
        <span style={ctxBarPctStyle}>{Math.round(clamped)}%</span>
        <span style={ctxBarRemainStyle}>
          {formatNum(used)} / {formatNum(limit)}
        </span>
      </div>
      <div style={ctxBarTrackStyle}>
        <div style={{ ...ctxBarFillStyle, width: `${clamped}%`, background: tone }} />
      </div>
    </div>
  );
}

/** Placeholder for the checkpoints feature. The model will
 *  auto-summarise once history crosses a threshold; checkpoints
 *  become nav anchors back to the corresponding point in the
 *  conversation. Until that lands, render a hint so the UI doesn't
 *  look broken. See followups/tasks-checkpoints-and-context.md. */
function CheckpointsStub() {
  return (
    <div style={checkpointsStubStyle}>
      Auto-summary checkpoints land in a follow-up. They'll show up
      here once history grows past the summarisation threshold.
    </div>
  );
}

/** Approximate context-window usage. The latest persisted
 *  turn_done message carries the input_tokens that the model
 *  received for that turn — i.e. the full conversation it had to
 *  process. Divided by the model's context limit, that's a decent
 *  proxy for "how full" the window is. Cache tokens are not yet
 *  counted (parser drops them today); the hint reflects that. */
function computeContextUsage(messages: TaskMessageDto[], model: string | null) {
  const limit = modelContextLimit(model);
  let used = 0;
  // Walk backwards for the most recent turn_done with a positive
  // input_tokens — older turns are stale, and a 0 means the run
  // ended before usage was reported.
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type !== 'turn_done') continue;
    if ((m.tokensIn ?? 0) > 0) {
      used = m.tokensIn ?? 0;
      break;
    }
  }
  const pct = limit > 0 ? (used / limit) * 100 : 0;
  return {
    used,
    limit,
    pct,
    hint: used > 0 ? 'approximate (cache excluded)' : 'no turn data yet',
  };
}

// ────────────────────────────────────────────────────────────────────
// Terminal view — dark/light themed raw stream-json mirror. Restored
// after the sidebar refactor so power users keep the option to see
// the unprettified output exactly as Claude Code's TUI does.
// ────────────────────────────────────────────────────────────────────

function TerminalWrap({
  task, modelName, messages, pendingPermission, onDecide, stage,
  draft, onDraft, onSend, onInterrupt, sending, isTerminal,
  theme, onToggleTheme,
  view, onChangeView, onRename, onStop, canStop, onDelete, canDelete,
  onResume,
  changeStats, diffOpen, onReview,
  diffMode, onChangeDiffMode, workingCount, commitsCount,
  liveText, liveUsage,
  taskId, convIndexSseRef,
}: {
  task: TaskDto;
  modelName: string;
  messages: TaskMessageDto[];
  pendingPermission: PendingPermission | null;
  onDecide: (
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void;
  stage: Stage;
  draft: string;
  onDraft: (s: string) => void;
  onSend: () => void;
  onInterrupt: () => void;
  sending: boolean;
  isTerminal: boolean;
  theme: TermTheme;
  onToggleTheme: () => void;
  view: DetailView;
  onChangeView: (next: DetailView) => void;
  onRename: (title: string) => void | Promise<void>;
  onStop: () => void;
  canStop: boolean;
  onDelete: () => void;
  canDelete: boolean;
  /** See {@link StructuredView} props — flips ERRORED → IDLE so the
   *  user can keep typing into the same CLI session. */
  onResume: () => void;
  changeStats: ChangeStats;
  diffOpen: boolean;
  onReview: () => void;
  diffMode: DiffMode;
  onChangeDiffMode: (next: DiffMode) => void;
  workingCount: number | null;
  commitsCount: number | null;
  liveText: string;
  liveUsage: { tokensIn: number; tokensOut: number } | null;
  taskId: string;
  convIndexSseRef: React.MutableRefObject<((name: string) => void) | null>;
}) {
  // Anchor for the floating ConvIndex rail. Sized to wrap the
  // ConversationPane so the rail's "absolute" coordinates resolve
  // against the visible transcript zone, not the whole window.
  const termHistoryRef = useRef<HTMLDivElement | null>(null);
  const agentCwd = taskAgentCwd(task);
  const displayBranch = taskDisplayBranch(task);
  return (
    <div style={terminalWrapStyle}>
      <div style={termToolbarStyle}>
        {/* macOS-style traffic-light dots used to live here. Removed —
            they were pure decoration that ate ~50px of toolbar width
            the title needed for itself. */}
        <div style={taskTitleBadgeTermStyle}>
          <EditableTitle
            title={task.title}
            onRename={onRename}
            maxDisplayWords={20}
            titleStyleOverride={termTitleSpanStyle}
          />
        </div>
        <span style={termNameStyle}>
          <span style={sessionIdStyleTerminal} title={agentCwd}>{shortenPath(agentCwd)}</span>
          {displayBranch && (
            <span style={sessionIdStyleTerminal}> · {displayBranch}</span>
          )}
          {modelName && (
            <span style={sessionIdStyleTerminal}> · {modelName}</span>
          )}
        </span>
        <span style={{ flex: 1 }} />
        <ViewToggle view={view} onChangeView={onChangeView} />
        <div style={twHeaderActionsStyle}>
          {canStop && (
            <button type="button" onClick={onStop} style={twHeaderStopBtnStyle}>
              ⏹ Stop
            </button>
          )}
          {canDelete && (
            <button type="button" onClick={onDelete} style={twHeaderDeleteBtnStyle}>
              🗑 Delete
            </button>
          )}
        </div>
        <button
          type="button"
          onClick={onToggleTheme}
          style={themeToggleStyle}
          title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        >
          {theme === 'dark' ? '☀' : '☾'}
        </button>
      </div>

      <div ref={termHistoryRef} style={termHistoryAnchorStyle}>
        <ConversationPane
          messages={messages}
          pendingPermission={pendingPermission}
          onDecide={onDecide}
          banner={{
            model: modelName,
            cwd: agentCwd,
            branch: displayBranch,
            sessionStartedAtIso: task.createdAt,
          }}
          liveText={liveText}
        />
        {/* Floating right-edge index rail. Collapsed by default to a
            strip of "−" markers; hover expands to full previews.
            Dark variant matches the terminal palette. */}
        <ConvIndex
          taskId={taskId}
          scrollContainerRef={termHistoryRef}
          onSseEvent={convIndexSseRef}
          variant={theme === 'dark' ? 'dark' : 'light'}
        />
      </div>

      <TerminalStatusBar task={task} stage={stage} liveUsage={liveUsage} />

      {/* Always render the strip so the user can toggle the diff pane
          regardless of whether the working tree is dirty — the pane's
          smart default falls back to Commits when clean. The strip
          tints yellow when there's something to see. */}
      <ReviewStrip
        hasChanges={changeStats.files > 0}
        diffOpen={diffOpen}
        onReview={onReview}
        diffMode={diffMode}
        onChangeDiffMode={onChangeDiffMode}
        workingCount={workingCount}
        commitsCount={commitsCount}
      />

      {!isTerminal && (
        <TermInput
          draft={draft}
          onDraft={onDraft}
          onSend={onSend}
          onInterrupt={onInterrupt}
          sending={sending}
          status={task.status}
        />
      )}
      {isTerminal && task.status === 'ERRORED' && (
        <ResumeBanner
          message={task.errorMessage}
          onResume={onResume}
        />
      )}
    </div>
  );
}

function TerminalStatusBar({
  task, stage, liveUsage,
}: {
  task: TaskDto;
  stage: Stage;
  liveUsage: { tokensIn: number; tokensOut: number } | null;
}) {
  const isRunning = task.status === 'RUNNING';
  const tokensTotal = (task.tokensIn ?? 0) + (task.tokensOut ?? 0)
      + (liveUsage ? liveUsage.tokensIn + liveUsage.tokensOut : 0);
  return (
    <div style={termStatusBarStyle}>
      <span style={termStatStyle}>
        {isRunning && <span className="bytequay-pulse" style={termRunningDotStyle} />}
        <strong style={termStatStrongStyle}>
          {task.status}
          {isRunning && <span className="bytequay-running-dots" aria-hidden />}
        </strong>
      </span>
      <span style={termStatGroupRightStyle}>
        <span style={termStatStyle}>⏱ <strong style={termStatStrongStyle}>{formatRuntime(task)}</strong></span>
        <span style={termStatStyle}>💰 <strong style={termStatStrongStyle}>{formatCost(task.costUsdMilli)}</strong></span>
        <span style={termStatStyle} title="Total input and output tokens">
          <strong style={termStatStrongStyle}>{formatTokenLabel(tokensTotal)}</strong>
        </span>
        {stage.toolName && (
          <span style={termStatStyle}>
            {stage.glyph} <strong style={termStatStrongStyle}>{stage.toolName}</strong>
          </span>
        )}
        {isRunning && (
          <span style={termStatHintStyle}>press Cancel to interrupt</span>
        )}
      </span>
    </div>
  );
}

function TermInput({
  draft, onDraft, onSend, onInterrupt, sending, status,
}: {
  draft: string;
  onDraft: (s: string) => void;
  onSend: () => void;
  onInterrupt: () => void;
  sending: boolean;
  status: TaskStatusDto;
}) {
  const isRunning = status === 'RUNNING';
  const textareaRef = useAutoGrowTextarea(draft, 180);
  return (
    <div style={termInputStyle}>
      <div style={termInputRowStyle}>
        <span style={termPromptStyle}>›</span>
        <textarea
          ref={textareaRef}
          className="bytequay-term-caret"
          value={draft}
          onChange={e => onDraft(e.target.value)}
          placeholder={isRunning
            ? 'queued — sends after current turn (or press Cancel to interrupt)'
            : 'send a follow-up turn…'}
          disabled={sending}
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              onSend();
            }
          }}
          style={termTextareaStyle}
        />
      </div>
      <div style={termInputFooterStyle}>
        <span style={termKbdHintStyle}>
          <span style={termKbdStyle}>↵</span> send · <span style={termKbdStyle}>⇧</span>+<span style={termKbdStyle}>↵</span> newline
        </span>
        {isRunning && (
          <button type="button" onClick={onInterrupt} style={cancelChipStyle}>
            ⏵ Cancel current turn
          </button>
        )}
        <span style={{ marginLeft: 'auto' }}>
          <button
            type="button"
            onClick={onSend}
            disabled={!draft.trim() || sending}
            style={termSendBtnStyle}
          >
            {sending ? 'sending…' : (isRunning ? 'Queue →' : 'Send →')}
          </button>
        </span>
      </div>
    </div>
  );
}

// Dark/light terminal palettes. Tuned toward GitHub Dark-Dimmed
// (dark) and GitHub Primer (light) so a long session doesn't fatigue
// the eye. termCssVars(theme) exposes each palette as CSS custom
// properties on the terminal-wrap div so ConversationPane (which
// reads var(--term-*) inside) doesn't need to know the theme.
const DARK_TERM = {
  bg: '#0d1117',
  bgElev1: '#13181f',
  bgElev2: '#161b22',
  bgResult: '#161b22',
  bgResultHead: '#1c2128',
  bgInput: '#11161e',
  border: '#21262d',
  borderSubtle: '#1c2228',
  text: '#adbac7',
  textBright: '#b8c4d0',
  textMuted: '#768390',
  textDim: '#636e7b',
  user: '#986ee2',
  read: '#539bf5',
  write: '#e0823d',
  edit: '#daaa3f',
  bash: '#e5534b',
  ok: '#57ab5a',
  err: '#e5534b',
  warn: '#c69026',
  pathFg: '#57ab5a',
  bannerCwd: '#539bf5',
  bannerMod: '#e0823d',
  userBg: 'rgba(152,110,226,0.07)',
  errorBg: 'rgba(229,83,75,0.06)',
  toolBg: 'rgba(255,255,255,0.022)',
  pillFg: '#e0823d',
  pillBg: 'rgba(224,130,61,0.09)',
  pillBorder: 'rgba(224,130,61,0.20)',
  pathBg: 'rgba(87,171,90,0.08)',
  pathBorder: 'rgba(87,171,90,0.20)',
  cursor: '#adbac7',
  kbdBg: '#1c2128',
  kbdBorder: '#30363d',
  permissionBg: '#5c2510',
  permissionBorder: '#a3461d',
  permissionText: '#f4b78f',
  permissionTextStrong: '#fcd9b6',
  sendBgStart: '#986ee2',
  sendBgEnd: '#6f56c2',
  sendText: '#0d1117',
  toggleBg: 'rgba(255,255,255,0.06)',
  toggleColor: '#adbac7',
  shadow: '0 4px 14px rgba(13,17,23,0.18), 0 1px 3px rgba(13,17,23,0.10)',
  divider: 'rgba(255,255,255,0.04)',
} as const;

const LIGHT_TERM = {
  bg: '#ffffff',
  bgElev1: '#eaeef2',
  bgElev2: '#f6f8fa',
  bgResult: '#f6f8fa',
  bgResultHead: '#eaeef2',
  bgInput: '#fbfcfd',
  border: '#d0d7de',
  borderSubtle: '#eaeef2',
  text: '#1f2328',
  textBright: '#0e1116',
  textMuted: '#57606a',
  textDim: '#6e7781',
  user: '#8250df',
  read: '#0969da',
  write: '#9a6700',
  edit: '#bf8700',
  bash: '#cf222e',
  ok: '#1a7f37',
  err: '#cf222e',
  warn: '#9a6700',
  pathFg: '#1a7f37',
  bannerCwd: '#0969da',
  bannerMod: '#9a6700',
  userBg: '#fbf7ff',
  errorBg: '#ffebe9',
  toolBg: '#f6f8fa',
  pillFg: '#cf222e',
  pillBg: '#fff',
  pillBorder: '#d0d7de',
  pathBg: '#dafbe1',
  pathBorder: '#1a7f37',
  cursor: '#1f2328',
  kbdBg: '#f6f8fa',
  kbdBorder: '#d0d7de',
  permissionBg: '#fff7ed',
  permissionBorder: '#fed7aa',
  permissionText: '#9a3412',
  permissionTextStrong: '#7c2d12',
  sendBgStart: '#8250df',
  sendBgEnd: '#6f42c1',
  sendText: '#ffffff',
  toggleBg: 'rgba(0,0,0,0.04)',
  toggleColor: '#57606a',
  shadow: '0 1px 3px rgba(0,0,0,0.06), 0 0 0 1px rgba(0,0,0,0.02)',
  divider: 'rgba(0,0,0,0.04)',
} as const;

function termCssVars(theme: TermTheme): React.CSSProperties {
  const p = theme === 'dark' ? DARK_TERM : LIGHT_TERM;
  return {
    '--term-bg': p.bg,
    '--term-bg-elev1': p.bgElev1,
    '--term-bg-elev2': p.bgElev2,
    '--term-bg-result': p.bgResult,
    '--term-bg-result-head': p.bgResultHead,
    '--term-bg-input': p.bgInput,
    '--term-border': p.border,
    '--term-border-subtle': p.borderSubtle,
    '--term-text': p.text,
    '--term-text-bright': p.textBright,
    '--term-text-muted': p.textMuted,
    '--term-text-dim': p.textDim,
    '--term-user': p.user,
    '--term-read': p.read,
    '--term-write': p.write,
    '--term-edit': p.edit,
    '--term-bash': p.bash,
    '--term-ok': p.ok,
    '--term-err': p.err,
    '--term-warn': p.warn,
    '--term-path': p.pathFg,
    '--term-banner-cwd': p.bannerCwd,
    '--term-banner-mod': p.bannerMod,
    '--term-user-bg': p.userBg,
    '--term-error-bg': p.errorBg,
    '--term-tool-bg': p.toolBg,
    '--term-pill-fg': p.pillFg,
    '--term-pill-bg': p.pillBg,
    '--term-pill-border': p.pillBorder,
    '--term-path-bg': p.pathBg,
    '--term-path-border': p.pathBorder,
    '--term-cursor': p.cursor,
    '--term-kbd-bg': p.kbdBg,
    '--term-kbd-border': p.kbdBorder,
    '--term-permission-bg': p.permissionBg,
    '--term-permission-border': p.permissionBorder,
    '--term-permission-text': p.permissionText,
    '--term-permission-text-strong': p.permissionTextStrong,
    '--term-send-bg-start': p.sendBgStart,
    '--term-send-bg-end': p.sendBgEnd,
    '--term-send-text': p.sendText,
    '--term-toggle-bg': p.toggleBg,
    '--term-toggle-color': p.toggleColor,
    '--term-shadow': p.shadow,
    '--term-divider': p.divider,
  } as React.CSSProperties;
}

type ToolUsage = {
  total: number;
  entries: Array<readonly [string, number]>;
};

function deriveToolUsage(messages: TaskMessageDto[]): ToolUsage {
  const counts = new Map<string, number>();
  let total = 0;
  for (const m of messages) {
    if (m.type !== 'tool_call') continue;
    let tool = 'tool';
    try {
      const parsed = JSON.parse(m.contentJson) as { toolName?: string };
      if (parsed.toolName) tool = parsed.toolName;
    }
    catch {
      // ignore — the renderer treats malformed payloads as generic
    }
    counts.set(tool, (counts.get(tool) ?? 0) + 1);
    total++;
  }
  return {
    total,
    entries: Array.from(counts.entries())
      .sort((a, b) => b[1] - a[1]),
  };
}

function Metric({
  label, value, sub, mono, live, wrap,
}: { label: string; value: string; sub?: string; mono?: boolean; live?: boolean;
     /** Switch the row to a stacked layout (label on top, value below
      *  on its own line with break-anywhere wrapping). For values that
      *  don't fit the narrow right column — UUIDs, full-sentence
      *  "latest input" snippets, long branch names — this beats
      *  truncation, since the side-by-side ellipsis hides the part
      *  the user actually wants to read. */
     wrap?: boolean }) {
  // `live` keeps a literal positive-green — it's the running indicator,
  // semantic and shouldn't recede in dark mode. Everything else reads
  // from --text-1 via metricValueStyle so the metric values stay legible
  // across themes.
  if (wrap) {
    return (
      <div style={metricRowWrapStyle}>
        <span style={metricLabelStyle}>{label}</span>
        <span
          style={{
            ...metricValueWrapStyle,
            ...(live ? { color: '#10b981' } : null),
            fontFamily: mono ? '"SF Mono", Menlo, monospace' : 'inherit',
            fontSize: mono ? 11.5 : 13,
          }}
          title={value}
        >
          {value}
          {sub && <span style={metricSubStyle}> {sub}</span>}
        </span>
      </div>
    );
  }
  return (
    <div style={metricRowStyle}>
      <span style={metricLabelStyle}>{label}</span>
      <span style={{
        ...metricValueStyle,
        ...(live ? { color: '#10b981' } : null),
        fontFamily: mono ? '"SF Mono", Menlo, monospace' : 'inherit',
        fontSize: mono ? 11.5 : 13,
      }}>
        {value}
        {sub && <span style={metricSubStyle}> {sub}</span>}
      </span>
    </div>
  );
}

function CopyableMetric({
  label, value, copyValue = value, mono, wrap,
}: {
  label: string;
  value: string;
  copyValue?: string;
  mono?: boolean;
  wrap?: boolean;
}) {
  const [copied, setCopied] = useState(false);
  const onCopy = useCallback(() => {
    void navigator.clipboard.writeText(copyValue)
      .then(() => {
        setCopied(true);
        window.setTimeout(() => setCopied(false), 1200);
      })
      .catch(() => {});
  }, [copyValue]);
  const valueStyle: React.CSSProperties = {
    ...(wrap ? metricValueWrapStyle : metricValueStyle),
    fontFamily: mono ? '"SF Mono", Menlo, monospace' : 'inherit',
    fontSize: mono ? 11.5 : 13,
  };
  if (wrap) {
    return (
      <div style={metricRowWrapStyle}>
        <span style={metricLabelStyle}>{label}</span>
        <span style={copyMetricValueRowStyle}>
          <span style={valueStyle} title={value}>{value}</span>
          <CopyButton copied={copied} onClick={onCopy} />
        </span>
      </div>
    );
  }
  return (
    <div style={metricRowStyle}>
      <span style={metricLabelStyle}>{label}</span>
      <span style={copyMetricValueRowStyle}>
        <span style={valueStyle} title={value}>{value}</span>
        <CopyButton copied={copied} onClick={onCopy} />
      </span>
    </div>
  );
}

function CopyButton({ copied, onClick }: { copied: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={copyMetricButtonStyle}
      title={copied ? 'Copied' : 'Copy'}
      aria-label={copied ? 'Copied' : 'Copy'}
    >
      {copied ? '✓' : 'Copy'}
    </button>
  );
}

function SchedulerEventHistory({ events, turns }: { events: TaskTurnEventDto[]; turns: TaskTurnDto[] }) {
  const recent = events.slice(0, 3);
  const turnById = useMemo(() => new Map(turns.map(turn => [turn.id, turn])), [turns]);
  if (recent.length === 0) {
    return <Metric label="Transitions" value="none" />;
  }
  return (
    <div style={metricRowWrapStyle}>
      <span style={metricLabelStyle}>Events</span>
      <div style={schedulerEventListStyle}>
        {recent.map(event => {
          const meta = schedulerEventMeta(event.event);
          const turn = turnById.get(event.turnId) ?? null;
          return (
            <div key={event.id} style={schedulerEventRowStyle} title={schedulerEventTitle(event, turn)}>
              <span style={{ ...schedulerEventNameStyle, color: meta.color }}>{meta.label}</span>
              <span style={schedulerEventTimeStyle}>{schedulerEventTime(event, turn)}</span>
              <span style={schedulerEventMsgStyle}>{event.message ?? schedulerEventHint(event, turn, meta)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function StatusPill({ status }: { status: TaskStatusDto }) {
  const palette = statusPillPalette(status);
  return (
    <span style={{ ...thStatusStyle, ...palette }}>
      {status === 'RUNNING' && <span className="bytequay-pulse" style={runningDotStyle} />}
      {status}
    </span>
  );
}

function Kbd({ children }: { children: React.ReactNode }) {
  return <span style={kbdStyle}>{children}</span>;
}

function KeyframesStyles() {
  return (
    <style>{`
      @keyframes bytequay-pulse {
        0%, 100% { opacity: 1; transform: scale(1); }
        50% { opacity: 0.4; transform: scale(0.85); }
      }
      .bytequay-pulse { animation: bytequay-pulse 1.6s ease-in-out infinite; }
      @keyframes bytequay-blink { 50% { opacity: 0.2; } }
      @keyframes bytequay-stream-cursor-blink { 50% { opacity: 0.25; } }
      /* Indeterminate progress: a 40% wide bar slides left→right
         continuously. translateX(-100%)→250% so it fully clears
         before reappearing, giving an unambiguous "still working"
         signal even when the metric values aren't visibly changing. */
      @keyframes bytequay-progress {
        0%   { transform: translateX(-100%); }
        100% { transform: translateX(250%); }
      }
      .bytequay-progress-bar {
        animation: bytequay-progress 1.4s linear infinite;
      }
      /* LIVE bar sweep: animate background-position so the gradient
         glides across the bar without an absolute overlay that
         could displace the text. The element has two layered
         backgrounds (sweep + solid base); only the first one's
         x-position moves. */
      @keyframes bytequay-live-sweep {
        0%   { background-position: -40% 0, 0 0; }
        100% { background-position: 140% 0, 0 0; }
      }
      .bytequay-live-sweep {
        animation: bytequay-live-sweep 1.6s linear infinite;
      }
      /* Trailing "...": render the three dots in a fixed-width inline
         box and animate the visible width in four discrete steps so
         the dots appear one at a time, like terminal loading text. */
      @keyframes bytequay-running-dots {
        0%   { width: 0;    }
        25%  { width: 0.4em; }
        50%  { width: 0.8em; }
        75%  { width: 1.2em; }
        100% { width: 1.2em; }
      }
      .bytequay-running-dots {
        display: inline-block;
        overflow: hidden;
        vertical-align: bottom;
        white-space: nowrap;
        width: 1.2em;
        animation: bytequay-running-dots 1.2s steps(4, end) infinite;
      }
      .bytequay-running-dots::before {
        content: '...';
      }
      /* Terminal-mode caret. caret-shape: block (Chromium 132+) turns
         the I-beam into the chunky filled rectangle Claude Code's CLI
         uses; the colored caret reads off the same --term-user that
         the prompt ">" uses so the input feels like one continuous
         terminal line. */
      .bytequay-term-caret {
        caret-color: var(--term-user);
        caret-shape: block;
      }
      /* Sidebar cards (see SidebarSection) — drop the dashed divider
         on the last row inside each card so the visible bottom edge
         is the card's own border, not a free-floating dash above it.
         Two selectors cover both the wrapped-list pattern (metric
         list, scheduler events) and the direct-child pattern (single
         body component like the context bar). */
      .bytequay-sidebar-card > *:last-child,
      .bytequay-sidebar-card > * > *:last-child {
        border-bottom: 0;
      }
    `}</style>
  );
}

// ────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────

type Stage = {
  toolName: string | null;
  glyph: string;
  detail: string;
};

type SchedulerSummary = {
  state: string;
  lane: string;
  queued: number;
  latestInput: string;
  live: boolean;
};

function summarizeTurnState(turns: TaskTurnDto[], taskStatus: TaskStatusDto | undefined): SchedulerSummary {
  const running = turns.find(t => t.status === 'RUNNING') ?? null;
  const queued = turns.filter(t => t.status === 'QUEUED');
  // Once a task is alive again (IDLE / AWAITING / RUNNING / PENDING)
  // but no new turn has been kicked off yet, the historical "latest
  // turn" is whatever finished last — typically a COMPLETED or FAILED
  // row. Showing that as "Turn state" right after Resume confuses
  // the user into thinking the task is still dead. Anchor the state
  // on the task itself in that case and only borrow the turn's
  // status when the scheduler actually has work for this task.
  const liveTaskStatus = taskStatus === 'IDLE'
      || taskStatus === 'AWAITING'
      || taskStatus === 'RUNNING'
      || taskStatus === 'PENDING';
  if (running !== null) {
    return {
      state: running.status,
      lane: running.lane,
      queued: queued.length,
      latestInput: oneLineInput(running.input),
      live: true,
    };
  }
  if (queued.length > 0) {
    const first = queued[0];
    return {
      state: first.status,
      lane: first.lane,
      queued: queued.length,
      latestInput: oneLineInput(first.input),
      live: false,
    };
  }
  // No in-flight work. Reflect the task's own status when it's still
  // alive (so resumed tasks read "ready"), and fall back to the most
  // recent finished turn only for terminal tasks.
  if (liveTaskStatus) {
    return {
      state: 'ready',
      lane: turns[0]?.lane ?? 'CLI',
      queued: 0,
      latestInput: '',
      live: taskStatus === 'RUNNING',
    };
  }
  const latest = turns[0] ?? null;
  if (latest === null) {
    return {
      state: 'none',
      lane: '-',
      queued: 0,
      latestInput: '',
      live: false,
    };
  }
  return {
    state: latest.status,
    lane: latest.lane,
    queued: 0,
    latestInput: oneLineInput(latest.input),
    live: false,
  };
}

function deriveStage(messages: TaskMessageDto[], status: TaskStatusDto | undefined): Stage {
  // Most recent tool_call without a matching tool_result = the
  // current step. Matches what the mockup labels "in this step for Xs".
  const seenResults = new Set<string>();
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type === 'tool_result') {
      try {
        const cid = (JSON.parse(m.contentJson) as { callId?: string }).callId;
        if (cid) seenResults.add(cid);
      }
      catch { /* ignore */ }
    }
    if (m.type === 'tool_call') {
      try {
        const c = JSON.parse(m.contentJson) as { callId?: string; toolName?: string; input?: unknown };
        if (c.callId && !seenResults.has(c.callId)) {
          return {
            toolName: c.toolName ?? 'tool',
            glyph: '⚒',
            detail: oneLineInput(c.input),
          };
        }
      }
      catch { /* ignore */ }
    }
  }
  if (status === 'RUNNING') {
    return { toolName: null, glyph: '✦', detail: 'thinking…' };
  }
  return { toolName: null, glyph: '·', detail: 'idle — waiting for the next prompt' };
}

function oneLineInput(input: unknown): string {
  if (input == null) return '';
  if (typeof input === 'string') return input.length > 80 ? input.slice(0, 79) + '…' : input;
  try {
    const s = JSON.stringify(input);
    return s.length > 80 ? s.slice(0, 79) + '…' : s;
  }
  catch {
    return '';
  }
}

function resolvedModelName(taskModel: string | null, messages: TaskMessageDto[]): string {
  const stored = (taskModel ?? '').trim();
  if (stored !== '') {
    return stored;
  }
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (message.type !== 'session_started') {
      continue;
    }
    try {
      const parsed = JSON.parse(message.contentJson) as { model?: unknown };
      const model = typeof parsed.model === 'string' ? parsed.model.trim() : '';
      if (model !== '') {
        return model;
      }
    }
    catch {
      return '';
    }
  }
  return '';
}

function pollInterval(status: TaskStatusDto | undefined, hasActiveTurn: boolean): number {
  if (hasActiveTurn) return POLL_MS_RUNNING;
  if (!status) return POLL_MS_IDLE;
  if (status === 'RUNNING') return POLL_MS_RUNNING;
  if (status === 'COMPLETED' || status === 'ERRORED') return POLL_MS_TERMINAL;
  return POLL_MS_IDLE;
}

function statusPillPalette(status: TaskStatusDto): React.CSSProperties {
  switch (status) {
    case 'RUNNING':   return { background: '#d1fae5', color: '#047857' };
    case 'AWAITING':  return { background: '#fef3c7', color: '#92400e' };
    case 'PENDING':   return { background: '#e5e7eb', color: '#1f2937' };
    case 'IDLE':      return { background: '#fef9c3', color: '#854d0e' };
    case 'COMPLETED': return { background: '#e2e8f0', color: '#475569' };
    case 'ERRORED':   return { background: '#fee2e2', color: '#b91c1c' };
  }
}

function formatRuntime(task: TaskDto): string {
  const start = new Date(task.createdAt).getTime();
  const end = task.endedAt ? new Date(task.endedAt).getTime() : Date.now();
  const ms = Math.max(0, end - start);
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  // Keep the seconds column at the hour scale too so the indicator
  // visibly ticks every second instead of pausing for ~60s between
  // minute rollovers — the page polls /tasks/{id} every 1s while
  // RUNNING, so the recomputed value already lands per-second.
  return `${h}h ${m % 60}m ${s % 60}s`;
}

function ageOf(iso: string): string {
  const d = new Date(iso).getTime();
  const ms = Date.now() - d;
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, '0')}s`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ${String(m % 60).padStart(2, '0')}m`;
  return `${Math.round(h / 24)}d ago`;
}

function schedulerEventMeta(event: TaskTurnEventDto['event']): { label: string; hint: string; color: string } {
  switch (event) {
    case 'TURN_QUEUED':
      return {
        label: 'Submitted',
        hint: 'Accepted by the scheduler; waits here until a lane slot is free.',
        color: '#b45309',
      };
    case 'WAITING_FOR_CAPACITY':
      return {
        label: 'Queued',
        hint: 'Still waiting because the lane or this task is busy.',
        color: '#b45309',
      };
    case 'TURN_STARTED':
      return {
        label: 'Started',
        hint: 'A scheduler slot was acquired and the agent began work.',
        color: '#047857',
      };
    case 'TURN_FINISHED':
      return {
        label: 'Finished',
        hint: 'The agent turn completed successfully.',
        color: '#64748b',
      };
    case 'TURN_FAILED':
      return {
        label: 'Failed',
        hint: 'The agent turn ended with an error.',
        color: '#dc2626',
      };
    case 'TURN_CANCELLED':
      return {
        label: 'Cancelled',
        hint: 'The queued turn was cancelled before it ran.',
        color: '#64748b',
      };
  }
}

function schedulerEventTime(event: TaskTurnEventDto, turn: TaskTurnDto | null): string {
  if (event.event === 'TURN_QUEUED' || event.event === 'WAITING_FOR_CAPACITY') {
    return `${queuedDurationLabel(event, turn)} wait`;
  }
  return ageOf(event.createdAt);
}

function schedulerEventHint(
  event: TaskTurnEventDto,
  turn: TaskTurnDto | null,
  meta: { label: string; hint: string; color: string },
): string {
  if (event.event === 'TURN_QUEUED') {
    if (turn?.startedAt) {
      return `Submitted, then executed after ${queuedDurationLabel(event, turn)} in queue.`;
    }
    return 'Submitted; not executing yet; waiting for a scheduler lane.';
  }
  if (event.event === 'WAITING_FOR_CAPACITY') {
    return `Still queued for ${queuedDurationLabel(event, turn)}; waiting for resources.`;
  }
  if (event.event === 'TURN_STARTED' && turn?.createdAt && turn.startedAt) {
    return `Execution began after ${queuedDurationLabel(event, turn)} in queue.`;
  }
  return meta.hint;
}

function queuedDurationLabel(event: TaskTurnEventDto, turn: TaskTurnDto | null): string {
  const queuedAt = Date.parse(turn?.createdAt ?? event.createdAt);
  const endedAt = turn?.startedAt ?? turn?.finishedAt ?? null;
  const endMs = endedAt ? Date.parse(endedAt) : Date.now();
  const seconds = Math.max(0, Math.floor((endMs - queuedAt) / 1000));
  return formatElapsedSeconds(seconds);
}

function schedulerEventTitle(event: TaskTurnEventDto, turn: TaskTurnDto | null): string {
  const message = event.message ? ` · ${event.message}` : '';
  const meta = schedulerEventMeta(event.event);
  return `${meta.label} · ${schedulerEventHint(event, turn, meta)} · ${new Date(event.createdAt).toLocaleString()}${message}`;
}

function formatCost(milli: number | null): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

function formatNum(n: number): string {
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

function formatTokenLabel(n: number): string {
  return `${formatNum(n)} ${n === 1 ? 'token' : 'tokens'}`;
}

function formatModelLabel(model: string | null | undefined): string {
  return model && model.trim() !== '' ? model : 'model pending';
}

/** Compact elapsed-time formatter for the LIVE bar. Mirrors the
 *  shape Claude Code's CLI uses ("12s", "1m 3s", "1h 4m"). Tuned
 *  to stay narrow — the bar shares its row with the Interrupt
 *  button so we can't afford a long "1 hour 4 minutes 30 seconds"
 *  string. */
function formatElapsedSeconds(s: number): string {
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function formatLiveUsage(liveUsage: { tokensIn: number; tokensOut: number } | null): string {
  if (liveUsage === null) {
    return '';
  }
  if (liveUsage.tokensOut <= 0) {
    return `${formatNum(liveUsage.tokensIn)} input tokens`;
  }
  return `${formatNum(liveUsage.tokensIn)} in / ${formatNum(liveUsage.tokensOut)} out tokens`;
}

function shortenPath(path: string): string {
  const home = '/Users/jack.chen';
  return path.startsWith(home) ? '~' + path.slice(home.length) : path;
}

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

const monoFont = '"SF Mono", "JetBrains Mono", Menlo, Consolas, monospace';


const layoutStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'stretch',
  // Lock to viewport height (not min-height) so the inner flex chain
  // — task window → body → terminal/structured pane → ConversationPane
  // — has an actual upper bound to bound their internal scrollers
  // against. Without this the body grew to the natural height of its
  // contents, pushing the bottom tab bar below the viewport and
  // making the whole page scroll instead of just the conversation.
  height: 'calc(100vh - 56px)',
  boxSizing: 'border-box',
  background: 'var(--bg-base)',
};
const mainColumnStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
};

// ────────────────────────────────────────────────────────────────────
// New detail-page shell — sidebar + task window + bottom tabs.
// Follows docs/mockups/design/tasks/task-detail-tabs.png.
// ────────────────────────────────────────────────────────────────────

const emptyShellStyle: React.CSSProperties = {
  flex: 1, minWidth: 0, padding: '24px 36px',
  display: 'flex', flexDirection: 'column', gap: 12,
};
const taskWindowStyle: React.CSSProperties = {
  flex: 1, minWidth: 0,
  display: 'flex', flexDirection: 'column',
  background: 'var(--bg-card)',
  borderLeft: '1px solid var(--border)',
};
const taskWindowBodyStyle: React.CSSProperties = {
  flex: 1, minHeight: 0,
  display: 'flex', gap: 12,
  padding: 12,
};
const flexFillStyle: React.CSSProperties = {
  flex: 1, minWidth: 0, minHeight: 0,
  display: 'flex', flexDirection: 'column',
};
const splitLeftStyle: React.CSSProperties = {
  flex: 1, minWidth: 0, minHeight: 0,
  display: 'flex', flexDirection: 'column',
};
const splitRightStyle: React.CSSProperties = {
  flex: 1, minWidth: 0, minHeight: 0,
  display: 'flex', flexDirection: 'column',
  border: '1px solid var(--border)', borderRadius: 8,
  overflow: 'hidden',
  background: 'var(--bg-elevated)',
};
const stubPanelStyle: React.CSSProperties = {
  flex: 1, minWidth: 0,
  display: 'flex', flexDirection: 'column',
  justifyContent: 'center', alignItems: 'center',
  padding: 24, gap: 6,
  color: 'var(--text-3)',
};
const stubPanelTitleStyle: React.CSSProperties = {
  fontSize: 16, fontWeight: 600, color: 'var(--text-2)',
};
const stubPanelBodyStyle: React.CSSProperties = {
  fontSize: 13, maxWidth: 360, textAlign: 'center', lineHeight: 1.55,
};

// Left sidebar (TaskWindowSidebar) ──────────────────────────────────
const twSidebarStyle: React.CSSProperties = {
  width: 280, flexShrink: 0,
  // Card-per-section layout: 6 px between cards keeps the rail
  // scannable without the airy 14 px gaps that pushed Checkpoints
  // below the fold on a 13" laptop.
  display: 'flex', flexDirection: 'column', gap: 6,
  padding: '12px 12px 14px',
  background: 'var(--bg-elevated)',
  borderRight: '1px solid var(--border)',
  overflowY: 'auto',
  maxHeight: 'calc(100vh - 56px)',
  scrollbarWidth: 'thin',
};
const twSidebarBackRowStyle: React.CSSProperties = {
  marginBottom: 2,
  display: 'flex', alignItems: 'center', gap: 8,
};
const twBackBtnStyle: React.CSSProperties = {
  background: 'transparent', border: 'none', padding: '4px 0',
  color: 'var(--accent)', fontSize: 13, fontWeight: 500,
  cursor: 'pointer',
  flex: 1, textAlign: 'left',
};
const twSidebarCollapseBtnStyle: React.CSSProperties = {
  background: 'transparent', border: '1px solid var(--border-hairline)',
  borderRadius: 4,
  padding: '0 6px', height: 22, lineHeight: '20px',
  color: 'var(--text-3)', fontSize: 14,
  cursor: 'pointer',
};
const twSidebarRailStyle: React.CSSProperties = {
  flex: '0 0 28px', width: 28,
  display: 'flex', flexDirection: 'column',
  alignItems: 'center', gap: 10,
  padding: '10px 0',
  background: 'var(--bg-elevated)',
  borderRight: '1px solid var(--border)',
  maxHeight: 'calc(100vh - 56px)',
};
const twSidebarRailBtnStyle: React.CSSProperties = {
  background: 'transparent', border: 'none', padding: '4px 0',
  color: 'var(--text-2)', fontSize: 14, cursor: 'pointer',
  width: '100%',
};
const twSidebarRailLabelStyle: React.CSSProperties = {
  writingMode: 'vertical-rl',
  transform: 'rotate(180deg)',
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  fontSize: 10,
  color: 'var(--text-3)',
};
const twStatusRowStyle: React.CSSProperties = {
  padding: '4px 0 6px',
};
// Lifted from the .lm-section / .vitals / .ctx-card pattern in
// docs/mockups/v2/tasks/_src/task-detail-tabs.html. Each rail row is
// a small surface card with its own border so the section bodies
// read as discrete units instead of a long flat list. The old
// border-top-between-sections separator is dropped — the card edge
// does the demarcation.
const twSectionStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 4,
};
const twSectionHeaderStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline', gap: 6,
  padding: '0 2px',
};
const twSectionLabelStyle: React.CSSProperties = {
  fontSize: 10, fontWeight: 700, letterSpacing: '0.06em',
  textTransform: 'uppercase', color: 'var(--text-3)',
};
const twSectionHintStyle: React.CSSProperties = {
  fontSize: 10, color: 'var(--text-4)', fontStyle: 'italic',
};
const twSectionBodyStyle: React.CSSProperties = {
  // Mirrors .vitals / .ctx-card in the HTML mockup — surface card with
  // a soft border + small radius. Padding lives here so individual
  // body components (metric list, context bar, groups chips) don't
  // have to repeat it.
  background: 'var(--bg-card)',
  border: '1px solid var(--border-hairline)',
  borderRadius: 6,
  padding: '4px 12px',
};

// Per-view header bar bits (used in zone header + term toolbar) ─────
const twHeaderMetaStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap',
  fontSize: 12, color: 'var(--text-3)',
};
const twHeaderRepoStyle: React.CSSProperties = {
  fontFamily: '"SF Mono", Menlo, monospace',
  color: 'var(--text-2)', fontWeight: 500,
};
const twHeaderSepStyle: React.CSSProperties = { color: 'var(--text-4)' };
const twHeaderChipStyle: React.CSSProperties = {
  fontSize: 11, padding: '1px 7px',
  background: 'var(--bg-elevated)', border: '1px solid var(--border)',
  borderRadius: 999, color: 'var(--text-2)',
};
const twHeaderActionsStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0,
};

// Groups section in the sidebar: chips for pinned groups + a `⋯`
// trigger that opens the standard GroupMenu popover.
const groupsRowStyle: React.CSSProperties = {
  display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6,
};
const groupsEmptyStyle: React.CSSProperties = {
  fontSize: 12, color: 'var(--text-3)', fontStyle: 'italic',
};
// PR chip in the Session sidebar — pill with the accent colour so
// it reads as a hyperlink without the underline noise.
const prChipBtnStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center',
  padding: '1px 8px',
  background: 'rgba(124,92,255,0.10)',
  color: 'var(--accent-dark, #5b21b6)',
  border: '1px solid rgba(124,92,255,0.25)',
  borderRadius: 999,
  fontFamily: '"SF Mono", Menlo, monospace',
  fontSize: 11,
  fontWeight: 700,
  cursor: 'pointer',
  letterSpacing: '0.02em',
};
const groupChipStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 4,
  padding: '2px 6px 2px 4px',
  border: '1px solid var(--border)',
  borderRadius: 999,
  background: 'var(--bg-card)',
  fontSize: 11,
  color: 'var(--text-1)',
  maxWidth: '100%',
};
const groupChipGlyphStyle: React.CSSProperties = {
  width: 14, height: 14, borderRadius: 3,
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  fontSize: 8, fontWeight: 700, color: '#fff',
  flexShrink: 0,
};
const groupChipLabelStyle: React.CSSProperties = {
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  maxWidth: 110,
};

function groupSwatchBg(color: string | undefined): string {
  switch (color) {
    case 'violet': return 'linear-gradient(135deg, #7c3aed, #4c1d95)';
    case 'amber': return 'linear-gradient(135deg, #d97706, #92400e)';
    case 'green': return 'linear-gradient(135deg, #10b981, #047857)';
    case 'blue': return 'linear-gradient(135deg, #2563eb, #1e3a8a)';
    case 'rose': return 'linear-gradient(135deg, #e11d48, #9f1239)';
    default: return 'linear-gradient(135deg, #64748b, #334155)';
  }
}
const twHeaderBtnStyle: React.CSSProperties = {
  padding: '5px 10px', fontSize: 12,
  background: 'var(--bg-elevated)', border: '1px solid var(--border)',
  borderRadius: 6, color: 'var(--text-2)', cursor: 'pointer',
};
const twHeaderStopBtnStyle: React.CSSProperties = {
  ...twHeaderBtnStyle, color: '#b91c1c', borderColor: '#fecaca',
};
const twHeaderDeleteBtnStyle: React.CSSProperties = {
  ...twHeaderBtnStyle, color: '#b91c1c',
};

// In-header view toggle (Conversation | Terminal) ──────────────────
const twHeaderViewToggleStyle: React.CSSProperties = {
  display: 'inline-flex',
  padding: 2,
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  flexShrink: 0,
};
const twHeaderViewBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'transparent',
  border: 'none',
  borderRadius: 6,
  color: 'var(--text-2)',
  fontSize: 12, fontWeight: 600,
  cursor: 'pointer',
};
const twHeaderViewBtnActiveStyle: React.CSSProperties = {
  background: 'var(--bg-card)',
  color: 'var(--text-1)',
  boxShadow: '0 1px 2px rgba(15, 23, 42, 0.1)',
};

// Review strip + context bar + checkpoints stub ─────────────────────
// The strip itself is the <button>, so this style strips the browser's
// default button chrome (background, focus ring inherits) and turns
// the strip into a clickable surface. The trailing label + chevron
// act as the action affordance.
//
// Tokens use the terminal palette (--term-*) with a fallback to the
// app-level palette (--bg-elevated, --border, etc.). Inside the
// terminal scope (TerminalWrap → terminalScopeStyle wraps a div that
// publishes --term-* via termCssVars), the strip picks up the
// theme-correct dark/light colors. Outside (structured view), the
// fallback kicks in and the strip uses the app theme.
// Card-style strip between the conversation history and the reply
// input. Matches the surrounding zone cards (history / live / reply)
// so the surface reads as one consistent stack — same radius, same
// border, same elevated background — with a purple left edge for
// accent (echoes liveZoneStyle's treatment). The whole strip is the
// click target; the toggle pills inside stop propagation.
const reviewStripStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10,
  padding: '6px 12px', marginTop: 8,
  background: 'var(--term-bg-elev1, var(--bg-elevated))',
  border: '1px solid var(--term-border, var(--border))',
  borderLeft: '3px solid var(--term-user, var(--accent))',
  borderRadius: 8,
  font: 'inherit',
  width: '100%',
  color: 'var(--term-text, var(--text-1))',
  cursor: 'pointer',
  transition: 'background 120ms ease, border-color 120ms ease',
};
// Amber tint when the task has touched files (lifetime). Replaces
// just the purple accent edge with amber so the colour stays the
// signal without fighting the rest of the chrome.
const reviewStripChangesStyle: React.CSSProperties = {
  background: 'rgba(217, 119, 6, 0.06)',
  borderColor: 'rgba(217, 119, 6, 0.35)',
  borderLeftColor: 'rgba(217, 119, 6, 0.85)',
};
const reviewStripLabelStyle: React.CSSProperties = {
  fontSize: 12, fontWeight: 600,
  color: 'var(--term-text, var(--text-1))',
  letterSpacing: '0.02em',
};
const reviewStripChevronStyle: React.CSSProperties = {
  fontSize: 11, lineHeight: 1,
  color: 'var(--term-user, var(--accent))',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: 14,
};
const ctxBarWrapStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 4,
};
const ctxBarRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline', gap: 6,
};
const ctxBarPctStyle: React.CSSProperties = {
  fontSize: 14, fontWeight: 700, color: 'var(--text-1)',
  fontVariantNumeric: 'tabular-nums',
};
const ctxBarRemainStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-4)',
  marginLeft: 'auto', fontVariantNumeric: 'tabular-nums',
};
const ctxBarTrackStyle: React.CSSProperties = {
  height: 6, background: 'var(--bg-base)',
  borderRadius: 3, overflow: 'hidden',
};
const ctxBarFillStyle: React.CSSProperties = {
  height: '100%', borderRadius: 3,
  transition: 'width 0.4s ease-out',
};
const checkpointsStubStyle: React.CSSProperties = {
  fontSize: 11.5, color: 'var(--text-4)', lineHeight: 1.5,
  fontStyle: 'italic',
};

// ── Terminal view (restored after the sidebar refactor) ─────────────
// The terminalScopeStyle is the wrapper that publishes --term-* CSS
// vars so the ConversationPane reads through the right palette
// without having to know about the theme.
const terminalScopeStyle: React.CSSProperties = {
  flex: 1, minWidth: 0, minHeight: 0,
  display: 'flex', flexDirection: 'column',
};
const terminalWrapStyle: React.CSSProperties = {
  background: 'var(--term-bg)',
  border: '1px solid var(--term-border)',
  borderRadius: 12,
  boxShadow: 'var(--term-shadow)',
  overflow: 'hidden',
  display: 'flex', flexDirection: 'column',
  flex: 1, minHeight: 0,
};
// position: relative anchors the floating ConvIndex rail to the
// terminal transcript zone. flex: 1 fills the gap between toolbar
// and status bar; minHeight: 0 lets the inner ConversationPane
// scroll instead of growing the whole wrap vertically.
const termHistoryAnchorStyle: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
};
const termToolbarStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10,
  padding: '4px 14px',
  background: 'linear-gradient(180deg, var(--term-bg-elev2) 0%, var(--term-bg-elev1) 100%)',
  borderBottom: '1px solid var(--term-border)',
  fontSize: 12, color: 'var(--term-text-muted)',
  flexShrink: 0,
};
const trafficStyle: React.CSSProperties = { display: 'flex', gap: 5, marginRight: 6 };
const trafficDotStyle: React.CSSProperties = {
  width: 10, height: 10, borderRadius: '50%',
  boxShadow: 'inset 0 0 0 0.5px rgba(0,0,0,0.18)',
};
const termNameStyle: React.CSSProperties = {
  color: 'var(--term-text)', fontFamily: monoFont, fontSize: 11.5,
};
const termBadgeStyle: React.CSSProperties = {
  background: 'rgba(124,92,255,0.16)',
  color: 'var(--term-user)',
  padding: '1px 7px', borderRadius: 999,
  fontSize: 10, fontWeight: 700, letterSpacing: '0.04em',
  marginLeft: 6,
  fontFamily: 'system-ui, sans-serif',
};
const sessionIdStyleTerminal: React.CSSProperties = {
  color: 'var(--term-text-dim)', fontFamily: monoFont, fontSize: 11.5, marginLeft: 6,
};
const themeToggleStyle: React.CSSProperties = {
  marginLeft: 'auto',
  padding: '3px 9px',
  background: 'var(--term-toggle-bg)',
  color: 'var(--term-toggle-color)',
  border: '1px solid var(--term-border)',
  borderRadius: 999,
  fontSize: 13,
  cursor: 'pointer',
  lineHeight: 1,
};
const termStatusBarStyle: React.CSSProperties = {
  padding: '8px 18px',
  background: 'linear-gradient(180deg, var(--term-bg-elev2) 0%, var(--term-bg-elev1) 100%)',
  borderTop: '1px solid var(--term-border)',
  fontFamily: monoFont, fontSize: 11.5, color: 'var(--term-text-muted)',
  display: 'flex', alignItems: 'center', gap: 16,
  flexShrink: 0,
};
const termStatStyle: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 4 };
const termStatStrongStyle: React.CSSProperties = { color: 'var(--term-text-bright)', fontWeight: 600 };
const termStatGroupRightStyle: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'inline-flex', alignItems: 'center', gap: 16,
};
const termStatHintStyle: React.CSSProperties = {
  color: 'var(--term-text-dim)', fontStyle: 'italic',
};
const termRunningDotStyle: React.CSSProperties = {
  width: 7, height: 7, borderRadius: '50%',
  background: 'var(--term-ok)', display: 'inline-block',
};
const termInputStyle: React.CSSProperties = {
  padding: '12px 18px 14px',
  background: 'var(--term-bg-input)',
  borderTop: '1px solid var(--term-border)',
  flexShrink: 0,
};
const termInputRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 10,
  padding: '8px 12px',
  background: 'var(--term-bg)',
  border: '1px solid var(--term-border)',
  borderRadius: 8,
  fontFamily: monoFont, fontSize: 13.5,
};
const termPromptStyle: React.CSSProperties = {
  color: 'var(--term-user)', fontWeight: 700,
  flexShrink: 0, lineHeight: 1.55, userSelect: 'none', fontSize: 15,
};
const termTextareaStyle: React.CSSProperties = {
  flex: 1, minWidth: 0,
  background: 'transparent',
  color: 'var(--term-text)',
  border: 'none',
  outline: 'none',
  resize: 'none',
  overflowY: 'auto',
  fontFamily: monoFont,
  fontSize: 15,
  lineHeight: 1.55,
  padding: 0,
};
const termInputFooterStyle: React.CSSProperties = {
  marginTop: 10,
  paddingTop: 8,
  borderTop: '1px solid var(--term-border-subtle)',
  display: 'flex', alignItems: 'center', gap: 10,
  fontFamily: monoFont, fontSize: 10.5, color: 'var(--term-text-dim)',
};
const termKbdHintStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 4,
};
const termKbdStyle: React.CSSProperties = {
  background: 'var(--term-kbd-bg)', border: '1px solid var(--term-kbd-border)',
  padding: '1px 5px', borderRadius: 3, color: 'var(--term-text)',
  fontSize: 9.5,
  fontFamily: monoFont,
};
const cancelChipStyle: React.CSSProperties = {
  padding: '2px 9px',
  background: 'transparent',
  border: '1px solid var(--term-permission-border)',
  borderRadius: 999,
  fontSize: 10.5, color: 'var(--term-permission-text-strong)',
  cursor: 'pointer',
  fontFamily: 'inherit',
};
const termSendBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'linear-gradient(135deg, var(--term-send-bg-start), var(--term-send-bg-end))',
  color: 'var(--term-send-text)',
  border: 'none',
  borderRadius: 999,
  fontSize: 11, fontWeight: 700,
  cursor: 'pointer',
  fontFamily: 'system-ui, sans-serif',
};

const breadcrumbRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '14px 36px 0',
  fontSize: 13,
  color: 'var(--text-3)',
  marginBottom: 12,
};
const crumbBackStyle: React.CSSProperties = {
  background: 'transparent', border: 'none', padding: 0,
  color: 'var(--accent)', fontWeight: 500, fontSize: 14, cursor: 'pointer',
};
const crumbLinkStyle: React.CSSProperties = {
  background: 'transparent', border: 'none', padding: 0,
  color: 'var(--accent)', cursor: 'pointer', fontSize: 13,
};
const crumbSepStyle: React.CSSProperties = { color: 'var(--text-4)' };
const crumbCurrentStyle: React.CSSProperties = {
  color: 'var(--text-1)', fontWeight: 600,
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  maxWidth: 600,
};

const thTitleStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600, color: 'var(--text-1)',
  letterSpacing: '-0.005em',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const titleEditTriggerStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 8,
  background: 'transparent', border: '1px dashed transparent',
  padding: '2px 6px',
  margin: '-2px -6px',
  borderRadius: 6,
  cursor: 'text',
  color: 'var(--text-1)',
  maxWidth: '100%',
  textAlign: 'left',
};
const titleEditPencilStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-4)',
  opacity: 0.6,
  flexShrink: 0,
};
const titleEditInputStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600,
  letterSpacing: '-0.005em',
  color: 'var(--text-1)',
  background: 'var(--bg-input)',
  border: '1px solid var(--accent-a40)',
  borderRadius: 6,
  padding: '2px 6px',
  margin: '-3px -7px',
  outline: 'none',
  width: '100%',
  fontFamily: 'inherit',
};
const thStatusStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5,
  padding: '5px 12px',
  borderRadius: 999,
  fontSize: 11, fontWeight: 700, letterSpacing: '0.04em',
  flexShrink: 0,
};



const runningDotStyle: React.CSSProperties = {
  width: 7, height: 7, borderRadius: '50%',
  background: '#10b981', display: 'inline-block',
};

const kbdStyle: React.CSSProperties = {
  background: 'var(--bg-elevated)', border: '1px solid var(--border)',
  padding: '1px 5px', borderRadius: 3, color: 'var(--text-2)',
  fontSize: 9.5,
  fontFamily: '"SF Mono", Menlo, monospace',
};

const stageCardStyle: React.CSSProperties = { padding: '14px 18px 16px' };
const stageStatusStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6,
  padding: '4px 12px', borderRadius: 999,
  fontSize: 11, fontWeight: 700, letterSpacing: '0.04em',
  marginBottom: 10,
};
const stageCurrentStyle: React.CSSProperties = {
  background: 'var(--bg-elevated)', border: '1px solid var(--border)',
  borderRadius: 6, padding: '10px 12px',
  fontFamily: monoFont, fontSize: 11.5,
  color: 'var(--text-2)', lineHeight: 1.55,
};
const stageArrowStyle: React.CSSProperties = { color: 'var(--accent)' };
const stageToolTagStyle: React.CSSProperties = {
  background: '#fef3c7', color: '#92400e',
  fontFamily: 'system-ui, sans-serif',
  fontSize: 9.5, fontWeight: 700,
  padding: '1px 6px', borderRadius: 999,
  letterSpacing: '0.04em', textTransform: 'uppercase',
};

// Metric list sits inside a SidebarSection card body so it no longer
// needs its own padding — the card handles the inset. The class is
// only used so the :last-child border tweak in KeyframesStyles can
// drop the dangling divider on the bottom row of every card.
const metricListStyle: React.CSSProperties = { fontSize: 13 };
const metricRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline',
  padding: '4px 0', borderBottom: '1px dashed var(--border-hairline)',
};
const metricLabelStyle: React.CSSProperties = {
  color: 'var(--text-3)', fontSize: 12, width: 110, flexShrink: 0,
};
const metricValueStyle: React.CSSProperties = {
  fontWeight: 500, fontVariantNumeric: 'tabular-nums', color: 'var(--text-1)',
  flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
// Stacked variant used by Metric({ wrap: true }) — label on top,
// value on its own line below with break-anywhere wrapping so UUIDs
// and long prompt previews render in full instead of being clipped
// against the narrow right column.
const metricRowWrapStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 3,
  padding: '4px 0', borderBottom: '1px dashed var(--border-hairline)',
};
const metricValueWrapStyle: React.CSSProperties = {
  fontWeight: 500, color: 'var(--text-1)',
  overflowWrap: 'anywhere', whiteSpace: 'normal',
  lineHeight: 1.4,
};
const metricSubStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-3)', marginLeft: 4, fontWeight: 400,
};
const copyMetricValueRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6,
  minWidth: 0, flex: 1,
};
const copyMetricButtonStyle: React.CSSProperties = {
  flexShrink: 0,
  border: '1px solid var(--border-hairline)',
  borderRadius: 6,
  background: 'var(--panel)',
  color: 'var(--text-2)',
  padding: '1px 6px',
  fontSize: 11,
  fontWeight: 650,
  cursor: 'pointer',
};
const sessionPathActionRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  padding: '5px 0 7px',
  borderBottom: '1px dashed var(--border-hairline)',
};
const sessionPathActionBtnStyle: React.CSSProperties = {
  border: '1px solid var(--border-hairline)',
  borderRadius: 6,
  background: 'var(--panel)',
  color: 'var(--text-2)',
  padding: '3px 7px',
  fontSize: 11,
  fontWeight: 650,
  cursor: 'pointer',
};
const sessionActionErrorStyle: React.CSSProperties = {
  color: '#dc2626',
  fontSize: 11,
  lineHeight: 1.35,
  padding: '3px 0 6px',
  borderBottom: '1px dashed var(--border-hairline)',
};

const schedulerEventListStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 5,
};
const schedulerEventRowStyle: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '92px 88px minmax(0, 1fr)',
  alignItems: 'baseline', gap: 6,
  fontSize: 11.5, lineHeight: 1.35,
};
const schedulerEventNameStyle: React.CSSProperties = {
  color: 'var(--text-1)', fontWeight: 600,
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const schedulerEventTimeStyle: React.CSSProperties = {
  color: 'var(--text-3)', fontVariantNumeric: 'tabular-nums',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const schedulerEventMsgStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};


const errorBannerStyle: React.CSSProperties = {
  padding: '12px 16px', margin: '0 36px 24px',
  background: '#FEF2F2', color: '#991B1B',
  border: '1px solid #FCA5A5', borderRadius: 6,
};



// ── Structured view ─────────────────────────────────────────────────────
const structuredWrapStyle: React.CSSProperties = {
  // flex:1 + minHeight:0 + height:100% so the wrap stretches to fill
  // its body slot (flexFillStyle is a flex column with flex:1 itself).
  // Without it the wrap sized to its content height and the reply
  // input sat wherever the conversation happened to end rather than
  // pinned to the visible bottom.
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  flex: 1,
  height: '100%',
  minHeight: 0,
  minWidth: 0,
};
const historyZoneStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  flex: 1,
  minHeight: 0,
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  overflow: 'hidden',
  // position: relative anchors the floating ConvIndex panel (which
  // uses position: absolute) to this zone's bounds instead of the
  // viewport — without it the panel would jump around when the
  // surrounding layout reflowed.
  position: 'relative',
};
const zoneHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '4px 12px',
  borderBottom: '1px solid var(--border)',
  background: 'var(--bg-card)',
  fontSize: 12,
  color: 'var(--text-2)',
};
const taskTitleBadgeStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center',
  padding: '2px 8px',
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  // Mirror `taskTitleBadgeTermStyle` exactly: `width: max-content`
  // sizes the badge to the full title length so the flex container
  // can't squeeze it back to "let's …" (see
  // docs/mockups/issue/tasks/name.png). The caller pairs this with
  // `maxDisplayWords` on EditableTitle to JS-truncate essay-long
  // titles before they get to the CSS layer.
  flexShrink: 0,
  width: 'max-content',
};
const taskTitleBadgeTermStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center',
  padding: '1px 8px',
  background: 'var(--term-bg-elev1)',
  border: '1px solid var(--term-border)',
  borderRadius: 6,
  // `width: max-content` sizes the badge to the full title length —
  // no JS truncation, no CSS ellipsis. `flexShrink: 0` keeps the
  // surrounding flex from squeezing it back to "Let's …" (see
  // docs/mockups/issue/tasks/name.png — that was the bug being fixed).
  flexShrink: 0,
  width: 'max-content',
};

/** Override for the title span when used inside the terminal toolbar
 *  — turns off the shared `thTitleStyle` ellipsis rules. Those rules
 *  were causing the badge to collapse to "Let's …" even when the
 *  full title was only 11 chars long. With overflow:visible + nowrap
 *  the title renders in full and the badge's `width: max-content`
 *  sizes around it. */
const termTitleSpanStyle: React.CSSProperties = {
  overflow: 'visible',
  textOverflow: 'clip',
  whiteSpace: 'nowrap',
};
const zoneMetaStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontWeight: 500,
};
const historyScrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
};
const liveZoneStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 14px',
  border: '1px solid var(--accent-a40)',
  borderLeft: '3px solid var(--accent)',
  borderRadius: 6,
  // Solid violet base + a moving translucent gradient on top via
  // multiple backgrounds. background-position is animated by the
  // .bytequay-live-sweep class so the whole bar visibly moves
  // without an absolute overlay covering the text.
  backgroundImage:
    'linear-gradient(90deg, transparent 0%, var(--accent-a40) 50%, transparent 100%), '
    + 'linear-gradient(var(--accent-a7), var(--accent-a7))',
  backgroundRepeat: 'no-repeat, no-repeat',
  backgroundSize: '40% 100%, 100% 100%',
  backgroundPosition: '-40% 0, 0 0',
};
const livePulseStyle: React.CSSProperties = {
  width: 8,
  height: 8,
  borderRadius: '50%',
  background: 'var(--accent)',
};
const liveLabelStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--accent-dark)',
};
const liveMetaStyle: React.CSSProperties = { fontSize: 12, color: 'var(--accent-dark)' };
const liveStatStyle: React.CSSProperties = {
  fontSize: 11.5,
  fontVariantNumeric: 'tabular-nums',
  color: 'var(--accent-dark)',
  opacity: 0.85,
};
const interruptBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  background: 'var(--bg-card)',
  border: '1px solid var(--accent-a40)',
  borderRadius: 6,
  fontSize: 11.5,
  fontWeight: 600,
  color: 'var(--accent-dark)',
  cursor: 'pointer',
};
const replyZoneStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: 12,
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 8,
};
const replyHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
};
const replyIconStyle: React.CSSProperties = { fontSize: 13, color: 'var(--text-3)' };
const replyLabelStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
};
const replyMetaStyle: React.CSSProperties = {
  fontSize: 11.5,
  color: 'var(--text-4)',
};
const replyTextareaStyle: React.CSSProperties = {
  width: '100%',
  // Height is driven by useAutoGrowTextarea; vertical resize handles
  // would fight the hook so they're disabled. minHeight sets the
  // floor (~2 lines), maxHeight cap is enforced in the hook.
  resize: 'none',
  overflowY: 'auto',
  minHeight: 44,
  padding: '10px 12px',
  fontFamily: 'inherit',
  fontSize: 15,
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  outline: 'none',
  boxSizing: 'border-box',
};
const replyFooterStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};
const replyHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-4)',
};
const replySendBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: 'var(--accent)',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  fontSize: 12.5,
  cursor: 'pointer',
};

// Resume banner palette — amber border + tinted background reads as
// "attention needed, not destructive". The structured and terminal
// views both render this in the same vertical slot the reply zone
// would occupy when the task is live, so its sizing intentionally
// matches replyZoneStyle's padding rhythm.
const resumeBannerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  padding: '12px 14px',
  background: 'rgba(217, 119, 6, 0.08)',
  border: '1px solid rgba(217, 119, 6, 0.4)',
  borderLeft: '3px solid #d97706',
  borderRadius: 6,
  marginTop: 8,
};
const resumeBannerCopyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  flex: 1,
  minWidth: 0,
};
const resumeBannerTitleStyle: React.CSSProperties = {
  fontSize: 12.5,
  fontWeight: 700,
  color: '#92400e',
};
const resumeBannerMsgStyle: React.CSSProperties = {
  fontSize: 11.5,
  color: '#78350f',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const resumeBannerBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: '#d97706',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  fontSize: 12.5,
  cursor: 'pointer',
  flexShrink: 0,
};
