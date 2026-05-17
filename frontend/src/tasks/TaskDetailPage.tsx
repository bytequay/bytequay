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
import type { TaskDto, TaskFileDto, TaskGroupDto, TaskMessageDto, TaskStatusDto } from '../types';
import { ConversationPane, type PendingPermission } from './ConversationPane';
import { StructuredConversation } from './StructuredConversation';
import TasksLeftRail, {
  repoKey,
  type GroupFilter,
  type ProviderFilter,
  type RepoFilter,
  type StatusFilter,
} from './TasksLeftRail';
import NewTaskDialog from './NewTaskDialog';
import RepoAvatar from './RepoAvatar';
import { useAutoGrowTextarea, usePersistentDraft } from './draftStore';
import TaskChangesTab from './TaskChangesTab';

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
  onOpenSettings: _onOpenSettings,
}: Props) {
  const [task, setTask] = useState<TaskDto | null>(null);
  const [messages, setMessages] = useState<TaskMessageDto[]>([]);
  const [files, setFiles] = useState<TaskFileDto[]>([]);
  const [allTasks, setAllTasks] = useState<TaskDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  // Reply draft persists across navigation for the lifetime of the
  // renderer — leave the page mid-sentence, come back, the text is
  // still here. Keyed by taskId so per-task drafts stay separate.
  const [draft, setDraft] = usePersistentDraft(`reply:${taskId}`);
  const [sending, setSending] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
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
      const [t, m, fs, list] = await Promise.all([
        window.bridge.getTask(taskId),
        window.bridge.getTaskMessages(taskId),
        window.bridge.getTaskFiles(taskId).catch(() => [] as TaskFileDto[]),
        window.bridge.listTasks(),
      ]);
      setTask(t);
      setMessages(m);
      setFiles(fs);
      setAllTasks(list);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId]);

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
    const interval = pollInterval(task?.status);
    if (!interval) return;
    const id = setInterval(() => { void refresh(); }, interval);
    return () => clearInterval(id);
  }, [task?.status, refresh]);

  // Per-turn live text buffer fed by AssistantTextDelta SSE events.
  // The chunk-by-chunk text grows the in-flight assistant card so
  // the user sees the answer streaming in instead of waiting for the
  // whole message envelope to land. Cleared once the canonical
  // AssistantText row arrives via /messages refresh (or the turn
  // ends), so the streaming card swaps to the persisted one without
  // a visible flicker.
  const [liveText, setLiveText] = useState('');
  const liveTextRef = useRef('');

  // Live SSE subscription — split into two paths by event kind:
  //  • AssistantTextDelta: append the chunk to the live buffer, no
  //    refresh (deltas aren't persisted; /messages wouldn't change).
  //  • Everything else: debounced refresh to pull the canonical
  //    state. Once the refresh lands the live buffer is cleared on
  //    the assumption that the assembled AssistantText is now in the
  //    messages array. Same pattern Phase A used; deltas are
  //    additive on top.
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
      if (event.name === 'AssistantTextDelta') {
        const chunk = typeof event.data.textChunk === 'string' ? event.data.textChunk : '';
        if (chunk.length === 0) return;
        liveTextRef.current += chunk;
        setLiveText(liveTextRef.current);
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
          stage={stage}
          messages={messages}
          files={files}
          onBack={onBack}
          onCollapse={() => setSidebarCollapsed(true)}
        />
      )}
      <div style={taskWindowStyle}>
        <div style={taskWindowBodyStyle}>
          <div style={diffOpen ? splitLeftStyle : flexFillStyle}>
            {view === 'conversation' && (
              <StructuredView
                task={task}
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
                view={view}
                onChangeView={setView}
                onRename={onRename}
                onStop={onStop}
                canStop={!isTerminal}
                onDelete={onDelete}
                canDelete={isTerminal}
                liveText={liveText}
              />
            )}
            {view === 'terminal' && (
              // Wrap so the palette (var(--term-*)) flows to
              // ConversationPane + StatusBar + TermInput inside.
              <div style={{ ...terminalScopeStyle, ...termCssVars(theme) }}>
                <TerminalWrap
                  task={task}
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
                  changeStats={changeStats}
                  diffOpen={diffOpen}
                  onReview={onReview}
                  liveText={liveText}
                />
              </div>
            )}
          </div>
          {diffOpen && (
            <div style={splitRightStyle}>
              <TaskChangesTab taskId={taskId} mode="files" />
            </div>
          )}
        </div>
      </div>

      {showCreate && (
        <NewTaskDialog
          onClose={() => setShowCreate(false)}
          onCreated={async () => {
            setShowCreate(false);
            await refresh();
          }}
        />
      )}

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
function EditableTitle({ title, onRename, maxDisplayChars, titleStyleOverride }: {
  title: string;
  onRename: (next: string) => void | Promise<void>;
  /** Optional cap on how many characters of the title are rendered in
   *  the resting state. Anything past the cap is replaced with `…`.
   *  The editor still operates on the full title — the cap only
   *  affects the display chip. Used by the terminal toolbar where
   *  the badge is content-sized; longer names would balloon it. */
  maxDisplayChars?: number;
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
  const displayTitle = maxDisplayChars && title.length > maxDisplayChars
    ? `${title.slice(0, maxDisplayChars - 1)}…`
    : title;
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
  view,
  onChangeView,
  onRename,
  onStop,
  canStop,
  onDelete,
  canDelete,
  liveText,
}: {
  task: TaskDto;
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
  view: DetailView;
  onChangeView: (next: DetailView) => void;
  onRename: (title: string) => void | Promise<void>;
  onStop: () => void;
  canStop: boolean;
  onDelete: () => void;
  canDelete: boolean;
  /** Per-token assistant text growing in the SSE stream that the
   *  persisted log doesn't have yet. Empty when no streaming is in
   *  flight; otherwise the partial assembled text since the last
   *  assistant message envelope landed. */
  liveText: string;
}) {
  const turns = useMemo(
    () => messages.filter(m => m.type === 'turn_done').length,
    [messages]);
  const isRunning = task.status === 'RUNNING';
  const replyRef = useAutoGrowTextarea(draft, 220);

  return (
    <div style={structuredWrapStyle}>
      <div style={historyZoneStyle}>
        <div style={zoneHeaderStyle}>
          <div style={taskTitleBadgeStyle}>
            <EditableTitle title={task.title} onRename={onRename} />
          </div>
          <div style={twHeaderMetaStyle}>
            <RepoAvatar workingDir={task.workingDir} size={14} />
            {task.workingDir && (
              <span style={twHeaderRepoStyle}>{shortenPath(task.workingDir)}</span>
            )}
            {task.branchName && (
              <>
                <span style={twHeaderSepStyle}>·</span>
                <span style={twHeaderChipStyle} title={`branch ${task.branchName}`}>
                  ⎇ {task.branchName}
                </span>
              </>
            )}
            {task.model && (
              <>
                <span style={twHeaderSepStyle}>·</span>
                <span style={twHeaderChipStyle}>{task.model}</span>
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
        <div style={historyScrollStyle}>
          <StructuredConversation
            messages={messages}
            pendingPermission={pendingPermission}
            onDecide={onDecide}
            modelName={task.model}
            liveText={liveText}
          />
        </div>
      </div>

      {isRunning && (
        // Sweep animation lives on the bar's own background (see
        // liveZoneStyle + bytequay-live-sweep keyframe) so the
        // content stays in normal flex flow and isn't pushed around
        // by an absolute overlay.
        <div style={liveZoneStyle} className="bytequay-live-sweep">
          <span style={livePulseStyle} className="bytequay-pulse" />
          <span style={liveLabelStyle}>✦ LIVE</span>
          <span style={liveMetaStyle}>· Claude is responding</span>
          <span style={{ flex: 1 }} />
          <button type="button" onClick={onInterrupt} style={interruptBtnStyle}>
            ⏵ Interrupt
          </button>
        </div>
      )}

      {changeStats.files > 0 && (
        <ReviewStrip stats={changeStats} diffOpen={diffOpen} onReview={onReview} />
      )}

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
  task, stage, messages, files, onBack, onCollapse,
}: {
  task: TaskDto;
  stage: Stage;
  messages: TaskMessageDto[];
  files: TaskFileDto[];
  onBack: () => void;
  onCollapse: () => void;
}) {
  const toolUsage = useMemo(() => deriveToolUsage(messages), [messages]);
  const ctx = useMemo(() => computeContextUsage(messages, task.model), [messages, task.model]);
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

      <SidebarSection label="Today">
        <div style={metricListStyle}>
          <Metric label="Runtime" value={formatRuntime(task)} live={task.status === 'RUNNING'} />
          <Metric label="Cost" value={formatCost(task.costUsdMilli)} />
          <Metric label="Tokens in" value={formatNum(task.tokensIn)} />
          <Metric label="Tokens out" value={formatNum(task.tokensOut)} />
          <Metric label="Tool calls" value={formatNum(toolUsage.total)} />
          <Metric label="Files touched" value={formatNum(files.length)} />
          <Metric label="Model" value={task.model || 'unknown'} mono />
        </div>
      </SidebarSection>

      <SidebarSection label="Context window" hint={ctx.hint}>
        <ContextWindowBar pct={ctx.pct} used={ctx.used} limit={ctx.limit} />
      </SidebarSection>

      <SidebarSection label="Current stage">
        <StageCard task={task} stage={stage} />
      </SidebarSection>

      <SidebarSection label="Checkpoints">
        <CheckpointsStub />
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
      {children}
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


/** "⇄ Diff · N files · +X −Y · ›" strip that sits above the reply
 *  input whenever the working tree has changes. The entire strip is
 *  the click target — toggling expand felt fiddly when only the
 *  trailing button was hot, especially since the strip itself is
 *  what the eye treats as "the diff affordance". The trailing
 *  chevron acts as a visual hint for the action; the label on the
 *  left ("Open diff" / "Hide diff") tells the user which way the
 *  click flips it. */
function ReviewStrip({
  stats, diffOpen, onReview,
}: { stats: ChangeStats; diffOpen: boolean; onReview: () => void }) {
  return (
    <button
      type="button"
      onClick={onReview}
      style={reviewStripStyle}
      title={diffOpen ? 'Hide the diff pane' : 'Open the diff pane'}
      aria-expanded={diffOpen}
    >
      <span style={reviewStripLabelStyle}>
        ⇄ Diff · {stats.files} file{stats.files === 1 ? '' : 's'}
      </span>
      <span style={reviewStripStatsStyle}>
        <span style={{ color: 'var(--term-ok, #16a34a)' }}>+{stats.added}</span>{' '}
        <span style={{ color: 'var(--term-err, #dc2626)' }}>−{stats.removed}</span>
      </span>
      <span style={{ flex: 1 }} />
      <span style={reviewStripActionStyle}>
        {diffOpen ? 'Hide diff' : 'Open diff'}
      </span>
      <span style={reviewStripChevronStyle} aria-hidden="true">
        {diffOpen ? '✕' : '›'}
      </span>
    </button>
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
  task, messages, pendingPermission, onDecide, stage,
  draft, onDraft, onSend, onInterrupt, sending, isTerminal,
  theme, onToggleTheme,
  view, onChangeView, onRename, onStop, canStop, onDelete, canDelete,
  changeStats, diffOpen, onReview, liveText,
}: {
  task: TaskDto;
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
  changeStats: ChangeStats;
  diffOpen: boolean;
  onReview: () => void;
  liveText: string;
}) {
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
            titleStyleOverride={termTitleSpanStyle}
          />
        </div>
        <span style={termNameStyle}>
          <span style={sessionIdStyleTerminal}>{shortenPath(task.workingDir)}</span>
          {task.branchName && (
            <span style={sessionIdStyleTerminal}> · {task.branchName}</span>
          )}
          {task.model && (
            <span style={sessionIdStyleTerminal}> · {task.model}</span>
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

      <ConversationPane
        messages={messages}
        pendingPermission={pendingPermission}
        onDecide={onDecide}
        banner={{
          model: task.model,
          cwd: task.workingDir,
          branch: task.branchName,
          sessionStartedAtIso: task.createdAt,
        }}
        liveText={liveText}
      />

      <TerminalStatusBar task={task} stage={stage} />

      {changeStats.files > 0 && (
        <ReviewStrip stats={changeStats} diffOpen={diffOpen} onReview={onReview} />
      )}

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
    </div>
  );
}

function TerminalStatusBar({ task, stage }: { task: TaskDto; stage: Stage }) {
  const isRunning = task.status === 'RUNNING';
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
        <span style={termStatStyle}>tokens <strong style={termStatStrongStyle}>{formatNum(task.tokensIn + task.tokensOut)}</strong></span>
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

function StageCard({ task, stage }: { task: TaskDto; stage: Stage }) {
  return (
    <div style={stageCardStyle}>
      <span style={{
        ...stageStatusStyle,
        ...statusPillPalette(task.status),
      }}>
        {task.status === 'RUNNING' && <span className="bytequay-pulse" style={runningDotStyle} />}
        {task.status}
      </span>
      <div style={stageCurrentStyle}>
        {stage.toolName ? (
          <>
            <span style={stageArrowStyle}>›</span>{' '}
            <span style={stageToolTagStyle}>{stage.toolName.toUpperCase()}</span>
            <div style={{ marginTop: 6 }}>{stage.detail}</div>
          </>
        ) : (
          <span style={{ color: '#6e7681' }}>{stage.detail}</span>
        )}
      </div>
    </div>
  );
}

function Metric({
  label, value, sub, mono, live,
}: { label: string; value: string; sub?: string; mono?: boolean; live?: boolean }) {
  // `live` keeps a literal positive-green — it's the running indicator,
  // semantic and shouldn't recede in dark mode. Everything else reads
  // from --text-1 via metricValueStyle so the metric values stay legible
  // across themes.
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

function findPendingPermission(messages: TaskMessageDto[]): PendingPermission | null {
  const decided = new Set<string>();
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type === 'permission_decision') {
      try {
        const cid = (JSON.parse(m.contentJson) as { callId?: string }).callId;
        if (cid) decided.add(cid);
      }
      catch { /* ignore */ }
    }
    if (m.type === 'permission_request') {
      try {
        const p = JSON.parse(m.contentJson) as { callId?: string; toolName?: string; summary?: string };
        if (p.callId && !decided.has(p.callId)) {
          return { callId: p.callId, toolName: p.toolName ?? 'tool', summary: p.summary ?? '' };
        }
      }
      catch { /* ignore */ }
    }
  }
  return null;
}

function pollInterval(status: TaskStatusDto | undefined): number {
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
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
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

function shortId(id: string): string {
  return id.length > 10 ? id.slice(0, 8) : id;
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
  display: 'flex', flexDirection: 'column', gap: 8,
  padding: '14px 14px 18px',
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
const twSectionStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 6,
  padding: '10px 0 4px',
  borderTop: '1px solid var(--border-hairline)',
};
const twSectionHeaderStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline', gap: 6,
  paddingBottom: 2,
};
const twSectionLabelStyle: React.CSSProperties = {
  fontSize: 10.5, fontWeight: 700, letterSpacing: '0.08em',
  textTransform: 'uppercase', color: 'var(--text-3)',
};
const twSectionHintStyle: React.CSSProperties = {
  fontSize: 10, color: 'var(--text-4)', fontStyle: 'italic',
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
const reviewStripStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10,
  padding: '8px 14px', marginTop: 8,
  background: 'var(--term-bg-elev1, var(--bg-elevated))',
  border: '1px solid var(--term-border, var(--border))',
  borderRadius: 6,
  cursor: 'pointer',
  textAlign: 'left',
  font: 'inherit',
  width: '100%',
  color: 'var(--term-text, var(--text-1))',
};
const reviewStripLabelStyle: React.CSSProperties = {
  fontSize: 12, fontWeight: 600,
  color: 'var(--term-text-muted, var(--text-2))',
};
const reviewStripStatsStyle: React.CSSProperties = {
  fontSize: 11, fontFamily: '"SF Mono", Menlo, monospace',
};
const reviewStripActionStyle: React.CSSProperties = {
  fontSize: 12, fontWeight: 600,
  color: 'var(--term-user, var(--accent))',
};
const reviewStripChevronStyle: React.CSSProperties = {
  fontSize: 14, lineHeight: 1,
  color: 'var(--term-user, var(--accent))',
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
  fontSize: 13.5,
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

const metricListStyle: React.CSSProperties = { padding: '0 18px 16px', fontSize: 13 };
const metricRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline',
  padding: '6px 0', borderBottom: '1px solid var(--border-hairline)',
};
const metricLabelStyle: React.CSSProperties = {
  color: 'var(--text-3)', fontSize: 12, width: 110, flexShrink: 0,
};
const metricValueStyle: React.CSSProperties = {
  fontWeight: 500, fontVariantNumeric: 'tabular-nums', color: 'var(--text-1)',
  flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const metricSubStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-3)', marginLeft: 4, fontWeight: 400,
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
  flexShrink: 0,
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
  fontSize: 13,
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
