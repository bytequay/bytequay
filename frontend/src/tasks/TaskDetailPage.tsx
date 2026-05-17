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

const POLL_MS_RUNNING = 1_000;
const POLL_MS_IDLE = 5_000;
const POLL_MS_TERMINAL = 0;

/**
 * Two-column task detail surface — terminal pane + sticky sidebar
 * (stage / metrics / quick actions). Faithfully
 * follows the layout in
 * {@code docs/mockups/design/tasks/task-detail-terminal.png}.
 *
 * <p>SSE through Electron is deferred; we poll on a status-aware
 * cadence (1s while RUNNING, 5s otherwise, off when terminal).
 */
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

/** Four tabs in the detail toolbar. Conversation = the structured
 *  three-zone view; Terminal = the power-user stdout mirror; Files
 *  = uncommitted working-tree changes the AI session has made;
 *  Commits = commits authored in the workingDir since the task
 *  started. Persisted across sessions because users settle on one
 *  or two and don't want to keep re-picking. The Conversation/
 *  Terminal split was the original toggle; Files/Commits land on
 *  top of that as new tabs. */
type DetailView = 'conversation' | 'terminal' | 'files' | 'commits';
const VIEW_STORAGE_KEY = 'bytequay.tasks.detailView';
function loadView(): DetailView {
  try {
    const v = window.localStorage.getItem(VIEW_STORAGE_KEY);
    // Migrate the legacy 'structured' value to 'conversation' so the
    // user's stored preference survives the rename.
    if (v === 'terminal') return 'terminal';
    if (v === 'files') return 'files';
    if (v === 'commits') return 'commits';
    return 'conversation';
  }
  catch {
    return 'conversation';
  }
}

export default function TaskDetailPage({
  taskId, onBack, onFilterChange, onProviderChange, onGroupChange, onRepoChange,
  onSelectTask, onOpenSettings,
}: Props) {
  const [task, setTask] = useState<TaskDto | null>(null);
  const [messages, setMessages] = useState<TaskMessageDto[]>([]);
  const [files, setFiles] = useState<TaskFileDto[]>([]);
  const [allTasks, setAllTasks] = useState<TaskDto[]>([]);
  const [groups, setGroups] = useState<TaskGroupDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  // Reply draft persists across navigation for the lifetime of the
  // renderer — leave the page mid-sentence, come back, the text is
  // still here. Keyed by taskId so per-task drafts stay separate.
  const [draft, setDraft] = usePersistentDraft(`reply:${taskId}`);
  const [sending, setSending] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [theme, setTheme] = useState<TermTheme>(loadTheme);
  const [view, setView] = useState<DetailView>(loadView);

  useEffect(() => {
    try { window.localStorage.setItem(THEME_STORAGE_KEY, theme); }
    catch { /* private browsing — fine to skip */ }
  }, [theme]);

  useEffect(() => {
    try { window.localStorage.setItem(VIEW_STORAGE_KEY, view); }
    catch { /* private browsing — fine to skip */ }
  }, [view]);

  const refresh = useCallback(async () => {
    try {
      const [t, m, fs, list, gs] = await Promise.all([
        window.bridge.getTask(taskId),
        window.bridge.getTaskMessages(taskId),
        window.bridge.getTaskFiles(taskId).catch(() => [] as TaskFileDto[]),
        window.bridge.listTasks(),
        window.bridge.listTaskGroups().catch(() => [] as TaskGroupDto[]),
      ]);
      setTask(t);
      setMessages(m);
      setFiles(fs);
      setAllTasks(list);
      setGroups(gs);
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

  const onChangeGroup = useCallback(async (nextGroupId: string | null) => {
    try {
      const updated = await window.bridge.setTaskGroup(taskId, nextGroupId);
      setTask(updated);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId]);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    const interval = pollInterval(task?.status);
    if (!interval) return;
    const id = setInterval(() => { void refresh(); }, interval);
    return () => clearInterval(id);
  }, [task?.status, refresh]);

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

  const rail = (
    <TasksLeftRail
      tasks={allTasks}
      currentTaskId={taskId}
      statusFilter={'ALL'}
      onStatusFilter={onFilterChange}
      providerFilter={null}
      onProviderFilter={onProviderChange}
      groupFilter={task?.groupId ?? null}
      onGroupFilter={onGroupChange}
      repoFilter={task ? repoKey(task.workingDir) : null}
      onRepoFilter={onRepoChange}
      onSelectTask={onSelectTask}
      onNewTask={() => setShowCreate(true)}
      onOpenSettings={onOpenSettings}
    />
  );

  if (task === null && error) {
    return (
      <section style={layoutStyle}>
        {rail}
        <div style={mainColumnStyle}>
          <BackBar onBack={onBack} title="(failed to load)" />
          <div style={errorBannerStyle}>{error}</div>
        </div>
      </section>
    );
  }
  if (task === null) {
    return (
      <section style={layoutStyle}>
        {rail}
        <div style={mainColumnStyle}>
          <BackBar onBack={onBack} title="loading…" />
        </div>
      </section>
    );
  }

  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';

  return (
    <section style={layoutStyle}>
      {rail}
      <div style={mainColumnStyle}>
        <KeyframesStyles />

        <BreadcrumbRow title={task.title} onBack={onBack} />

        <TaskHeader
          task={task}
          view={view}
          onChangeView={setView}
          onRename={onRename}
          onPause={undefined /* pause not wired through MCP yet */}
          onStop={onStop}
          canStop={!isTerminal}
          onDelete={onDelete}
          canDelete={isTerminal}
        />

        <div style={view === 'terminal'
          ? { ...bodyGridStyle, ...termCssVars(theme) }
          : bodyGridStyle}
        >
          {view === 'terminal' && (
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
            />
          )}
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
            />
          )}
          {(view === 'files' || view === 'commits') && (
            <TaskChangesTab taskId={taskId} mode={view} />
          )}

          {view !== 'files' && view !== 'commits' && (
            <Sidebar
              task={task}
              stage={stage}
              files={files}
              messages={messages}
              groups={groups}
              onChangeGroup={onChangeGroup}
            />
          )}
        </div>
      </div>

      {showCreate && (
        <NewTaskDialog
          onClose={() => setShowCreate(false)}
          initialGroupId={task?.groupId ?? null}
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

function BreadcrumbRow({ onBack, title }: { onBack: () => void; title: string }) {
  return <BackBar onBack={onBack} title={title} />;
}

function TaskHeader({
  task,
  view,
  onChangeView,
  onRename,
  onPause,
  onStop,
  canStop,
  onDelete,
  canDelete,
}: {
  task: TaskDto;
  view: DetailView;
  onChangeView: (next: DetailView) => void;
  onRename: (title: string) => void | Promise<void>;
  onPause: (() => void) | undefined;
  onStop: () => void;
  canStop: boolean;
  onDelete: () => void;
  canDelete: boolean;
}) {
  const provider = task.provider || '';
  const glyph = provider.toLowerCase().startsWith('codex') ? 'X' : 'C';
  const glyphBg = glyph === 'X'
    ? 'linear-gradient(135deg, #1e293b, #0f172a)'
    : 'linear-gradient(135deg, #d97706, #92400e)';

  return (
    <div style={taskHeaderStyle}>
      <div style={{ ...thProviderStyle, background: glyphBg }}>{glyph}</div>
      <div style={thTitleBlockStyle}>
        <EditableTitle title={task.title} onRename={onRename} />
        <div style={thMetaStyle}>
          {task.workingDir && (
            <>
              <RepoAvatar workingDir={task.workingDir} size={16} />
              <span style={repoStyle}>{shortenPath(task.workingDir)}</span>
              <span style={metaSepStyle}>·</span>
            </>
          )}
          <span>started {ageOf(task.createdAt)}</span>
          <span style={metaSepStyle}>·</span>
          <span style={modelChipStyle}>{task.model || 'unknown model'}</span>
          {task.agentSessionId && (
            <>
              <span style={metaSepStyle}>·</span>
              <span style={sessionIdStyle}>{shortId(task.agentSessionId)}</span>
            </>
          )}
        </div>
      </div>
      <StatusPill status={task.status} />
      <div style={thActionsStyle}>
        <ViewToggle value={view} onChange={onChangeView} />
        {onPause && (
          <button type="button" onClick={onPause} style={aBtnStyle}>⏸ Pause</button>
        )}
        {canStop && (
          <button type="button" onClick={onStop} style={{ ...aBtnStyle, color: '#b91c1c' }}>
            ⏹ Stop
          </button>
        )}
        {canDelete && (
          <button
            type="button"
            onClick={onDelete}
            style={{ ...aBtnStyle, color: '#b91c1c' }}
            title="Permanently remove this task and its conversation log"
          >
            🗑 Delete
          </button>
        )}
      </div>
    </div>
  );
}

/** Tiny segmented control that flips between the structured detail
 *  view and the terminal mirror. Renders both labels at once so the
 *  active state is obvious. */
/** Click-to-edit task title. Renders as a plain heading by default;
 *  clicking flips to an inline input. Enter / blur saves, Escape
 *  reverts. The pencil glyph is decorative — the whole heading is
 *  the click target so the affordance is generous without crowding
 *  the layout. */
function EditableTitle({ title, onRename }: {
  title: string;
  onRename: (next: string) => void | Promise<void>;
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
  return (
    <button
      type="button"
      onClick={() => setEditing(true)}
      style={titleEditTriggerStyle}
      title="Click to rename — Enter to save, Esc to cancel"
    >
      <span style={thTitleStyle}>{title}</span>
      <span style={titleEditPencilStyle} aria-hidden>✎</span>
    </button>
  );
}

function ViewToggle({ value, onChange }: {
  value: DetailView;
  onChange: (next: DetailView) => void;
}) {
  // Four-tab strip in the detail toolbar. Conversation/Terminal are
  // the two render modes for the live chat; Files/Commits surface
  // git activity inside the task's workingDir so the user can see
  // what the AI session changed without leaving the page.
  const tabs: Array<{ key: DetailView; label: string }> = [
    { key: 'conversation', label: '▤ Conversation' },
    { key: 'terminal',     label: '⌨ Terminal' },
    { key: 'files',        label: '📄 Files' },
    { key: 'commits',      label: '⎇ Commits' },
  ];
  return (
    <div style={viewToggleStyle} role="tablist" aria-label="Detail view">
      {tabs.map(t => (
        <button
          key={t.key}
          type="button"
          role="tab"
          aria-selected={value === t.key}
          onClick={() => onChange(t.key)}
          style={{
            ...viewToggleBtnStyle,
            ...(value === t.key ? viewToggleActiveStyle : null),
          }}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

function TerminalWrap({
  task,
  messages,
  pendingPermission,
  onDecide,
  stage,
  draft,
  onDraft,
  onSend,
  onInterrupt,
  sending,
  isTerminal,
  theme,
  onToggleTheme,
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
}) {
  return (
    <div style={terminalWrapStyle}>
      <div style={termToolbarStyle}>
        <div style={trafficStyle}>
          <span style={{ ...trafficDotStyle, background: '#ff5f57' }} />
          <span style={{ ...trafficDotStyle, background: '#febc2e' }} />
          <span style={{ ...trafficDotStyle, background: '#28c840' }} />
        </div>
        <span style={termNameStyle}>
          claude-code <span style={termBadgeStyle}>stream-json</span>
          <span style={sessionIdStyleTerminal}> {shortenPath(task.workingDir)}</span>
          {task.branchName && (
            <span style={sessionIdStyleTerminal}> · {task.branchName}</span>
          )}
        </span>
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
      />

      <StatusBar task={task} stage={stage} />

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

function StatusBar({ task, stage }: { task: TaskDto; stage: Stage }) {
  const isRunning = task.status === 'RUNNING';
  return (
    <div style={statusBarStyle}>
      {/* Status on the left, on its own so the running pulse stands
          out instead of getting lost in the runtime/cost row. */}
      <span style={statStyle}>
        {isRunning && <span className="bytequay-pulse" style={runningDotStyle} />}
        <strong style={statStrongStyle}>
          {task.status}
          {/* Animated trailing dots: ".", "..", "..." cycle. CSS-only
              via a steps() animation that reveals more of a fixed
              "..." string each frame, so the strong tag stays
              copy-pasteable (no fake content). */}
          {isRunning && <span className="bytequay-running-dots" aria-hidden />}
        </strong>
      </span>
      {/* Everything else hugs the right edge. */}
      <span style={statGroupRightStyle}>
        <span style={statStyle}>⏱ <strong style={statStrongStyle}>{formatRuntime(task)}</strong></span>
        <span style={statStyle}>💰 <strong style={statStrongStyle}>{formatCost(task.costUsdMilli)}</strong></span>
        <span style={statStyle}>tokens <strong style={statStrongStyle}>{formatNum(task.tokensIn + task.tokensOut)}</strong></span>
        {stage.toolName && (
          <span style={statStyle}>
            {stage.glyph} <strong style={statStrongStyle}>{stage.toolName}</strong>
          </span>
        )}
        {isRunning && (
          <span style={statHintStyle}>press Cancel to interrupt</span>
        )}
      </span>
    </div>
  );
}

function TermInput({
  draft,
  onDraft,
  onSend,
  onInterrupt,
  sending,
  status,
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
          placeholder={
            isRunning
              ? 'queued — sends after current turn (or press Cancel to interrupt)'
              : 'send a follow-up turn…'
          }
          disabled={sending}
          onKeyDown={e => {
            // Chat-app convention: Enter sends, Shift+Enter inserts a
            // newline. ⌘/Ctrl+Enter still sends as a no-shift muscle-
            // memory alias. isComposing guards against IME confirmation
            // (CJK input) accidentally firing send.
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              onSend();
            }
          }}
          style={termTextareaStyle}
        />
      </div>
      <div style={termInputFooterStyle}>
        <span><Kbd>↵</Kbd> send · <Kbd>⇧</Kbd>+<Kbd>↵</Kbd> newline</span>
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
            style={sendBtnStyle}
          >
            {sending ? 'sending…' : (isRunning ? 'Queue →' : 'Send →')}
          </button>
        </span>
      </div>
    </div>
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
          <span style={zoneIconStyle}>📜</span>
          <span style={zoneLabelStyle}>HISTORY</span>
          <span style={zoneMetaStyle}>
            · {messages.length} message{messages.length === 1 ? '' : 's'}
            · {turns} turn{turns === 1 ? '' : 's'} completed
          </span>
        </div>
        <div style={historyScrollStyle}>
          <StructuredConversation
            messages={messages}
            pendingPermission={pendingPermission}
            onDecide={onDecide}
            modelName={task.model}
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

function Sidebar({ task, stage, files, messages, groups, onChangeGroup }: {
  task: TaskDto;
  stage: Stage;
  files: TaskFileDto[];
  messages: TaskMessageDto[];
  groups: TaskGroupDto[];
  onChangeGroup: (groupId: string | null) => void | Promise<void>;
}) {
  const toolUsage = useMemo(() => deriveToolUsage(messages), [messages]);
  return (
    <div style={sidebarStyle}>
      <SideCard>
        <StageCard task={task} stage={stage} />
      </SideCard>

      <SideCard>
        <h4 style={sideCardHeadingStyle}>Group</h4>
        <GroupPicker
          task={task}
          groups={groups}
          onChange={onChangeGroup}
        />
      </SideCard>

      <SideCard>
        <h4 style={sideCardHeadingStyle}>Metrics</h4>
        {/* Sliding stripe along the top of the card — pure visual cue
            that something's happening while RUNNING, since the
            seconds-counter on its own is easy to miss as a static read. */}
        {task.status === 'RUNNING' && (
          <div style={progressTrackStyle} aria-hidden>
            <div className="bytequay-progress-bar" style={progressBarStyle} />
          </div>
        )}
        <div style={metricListStyle}>
          <Metric label="Runtime" value={formatRuntime(task)} live={task.status === 'RUNNING'} />
          <Metric label="Cost so far" value={formatCost(task.costUsdMilli)} sub="CLI-reported" />
          <Metric label="Tokens in → out" value={`${formatNum(task.tokensIn)} → ${formatNum(task.tokensOut)}`} />
          <Metric label="Tool calls" value={formatNum(toolUsage.total)} />
          <Metric label="Files touched" value={formatNum(files.length)} />
          <Metric label="Model" value={task.model} mono />
          {task.branchName && <Metric label="Branch" value={task.branchName} mono />}
          {task.agentSessionId && <Metric label="Session" value={shortId(task.agentSessionId)} mono />}
          <div style={metricRowStyle}>
            <span style={metricLabelStyle}>Status</span>
            <StatusPill status={task.status} />
          </div>
        </div>
      </SideCard>

      <SideCard>
        <h4 style={sideCardHeadingStyle}>
          Files touched <span style={cardCountStyle}>· {files.length}</span>
        </h4>
        <FilesList files={files} />
      </SideCard>

      <SideCard>
        <h4 style={sideCardHeadingStyle}>Tools used</h4>
        <ToolsUsed usage={toolUsage} />
      </SideCard>

      <SideCard>
        <h4 style={sideCardHeadingStyle}>Quick actions</h4>
        <div style={quickActionsStyle}>
          <QaBtn icon="↗" label="Open in real Terminal" disabled />
          <QaBtn icon="⊞" label="Open dir in IDE" disabled />
          <QaBtn icon="↓" label="Save checkpoint" disabled />
          <QaBtn icon="↗" label="Export transcript" disabled />
        </div>
      </SideCard>
    </div>
  );
}

function FilesList({ files }: { files: TaskFileDto[] }) {
  if (files.length === 0) {
    return (
      <div style={cardEmptyStyle}>
        No files touched yet.
      </div>
    );
  }
  return (
    <div style={filesListStyle}>
      {files.map(f => (
        <div key={f.path} style={fileRowStyle}>
          <span style={{ ...fileOpTagStyle, ...fileOpPalette(f.operation) }}>
            {fileOpLabel(f.operation)}
          </span>
          <span style={filePathStyle} title={f.path}>{f.path}</span>
          <span style={fileStatsStyle}>
            {f.linesAdded > 0 && <span style={{ color: '#16a34a' }}>+{f.linesAdded}</span>}
            {f.linesAdded > 0 && f.linesRemoved > 0 && ' '}
            {f.linesRemoved > 0 && <span style={{ color: '#dc2626' }}>-{f.linesRemoved}</span>}
          </span>
        </div>
      ))}
    </div>
  );
}

function ToolsUsed({ usage }: { usage: ToolUsage }) {
  if (usage.total === 0) {
    return <div style={cardEmptyStyle}>No tools called yet.</div>;
  }
  return (
    <div style={toolsUsedStyle}>
      {usage.entries.map(([tool, count]) => (
        <span key={tool} style={toolPillStyle}>
          {tool}
          <span style={toolPillCountStyle}>{count}</span>
        </span>
      ))}
    </div>
  );
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

function fileOpLabel(op: string): string {
  switch (op.toLowerCase()) {
    case 'write':  return 'NEW';
    case 'edit':   return 'EDIT';
    case 'read':   return 'READ';
    case 'delete': return 'DEL';
    default:       return op.toUpperCase();
  }
}

function fileOpPalette(op: string): React.CSSProperties {
  switch (op.toLowerCase()) {
    case 'write':  return { background: '#dcfce7', color: '#166534' };
    case 'edit':   return { background: '#ede9fe', color: '#5b21b6' };
    case 'read':   return { background: '#dbeafe', color: '#1e3a8a' };
    case 'delete': return { background: '#fee2e2', color: '#991b1b' };
    default:       return { background: '#f1f5f9', color: '#475569' };
  }
}

function SideCard({ children }: { children: React.ReactNode }) {
  return <div style={sideCardStyle}>{children}</div>;
}

/** Reassigns the task's group inline. Optimistically reflects the
 *  pick — the parent's setTaskGroup pushes through the new row. */
function GroupPicker({ task, groups, onChange }: {
  task: TaskDto;
  groups: TaskGroupDto[];
  onChange: (groupId: string | null) => void | Promise<void>;
}) {
  return (
    <div style={groupPickerWrapStyle}>
      <select
        value={task.groupId ?? ''}
        onChange={e => {
          const next = e.target.value;
          void onChange(next === '' ? null : next);
        }}
        style={groupPickerStyle}
      >
        <option value="">— Ungrouped —</option>
        {groups.map(g => (
          <option key={g.id} value={g.id}>
            {g.glyph ? `${g.glyph}  ` : ''}{g.name}
          </option>
        ))}
      </select>
    </div>
  );
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

function QaBtn({ icon, label, disabled }: { icon: string; label: string; disabled?: boolean }) {
  return (
    <button type="button" disabled={disabled} style={{
      ...qaBtnStyle,
      opacity: disabled ? 0.5 : 1,
      cursor: disabled ? 'not-allowed' : 'pointer',
    }}>
      <span style={qaBtnIconStyle}>{icon}</span> {label}
    </button>
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

// ────────────────────────────────────────────────────────────────────
// Terminal palette — applied as CSS custom properties on the
// terminal-wrap div. ConversationPane reads via var(--term-*) so
// only this top-level component knows the theme.
// ────────────────────────────────────────────────────────────────────

// Dark palette tuned toward GitHub "Dark Dimmed" — softer foreground
// values so long sessions don't fatigue the eye. The user explicitly
// asked for less-bright text and accents; the previous values
// (#f0f6fc text, #b794f4 / #79c0ff / #56d364 / #ffd33d accents) were
// fluorescent against the near-black background.
const DARK_TERM = {
  bg: '#0d1117',
  bgElev1: '#13181f',          // gradient end (toolbar/status bar)
  bgElev2: '#161b22',          // gradient start
  bgResult: '#161b22',         // tool result body
  bgResultHead: '#1c2128',
  bgInput: '#11161e',          // subtle elevation so the input field
                               // reads as its own zone but doesn't pop
  border: '#21262d',
  borderSubtle: '#1c2228',

  text: '#adbac7',             // GitHub Dark Dimmed default fg
  textBright: '#b8c4d0',       // intentionally barely-brighter than
                               // text; emphasis comes from font-weight
                               // rather than luminance, so bold values
                               // never read as fluorescent white.
  textMuted: '#768390',
  textDim: '#636e7b',

  user: '#986ee2',             // softer than #b794f4
  read: '#539bf5',             // softer than #79c0ff
  write: '#e0823d',            // less neon than #f0883e
  edit: '#daaa3f',             // way softer than #ffd33d (was painful)
  bash: '#e5534b',
  ok: '#57ab5a',               // softer than #56d364
  err: '#e5534b',              // softer than #f85149
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

  cursor: '#adbac7',           // same as default text — still reads
                               // as a blinking solid block, but
                               // doesn't pop as a white square
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
  toggleHoverBg: 'rgba(255,255,255,0.12)',
  toggleColor: '#adbac7',

  shadow: '0 4px 14px rgba(13,17,23,0.18), 0 1px 3px rgba(13,17,23,0.10)',
  divider: 'rgba(255,255,255,0.04)',
} as const;

// Light palette mirrors the GitHub Primer light tokens used in
// docs/mockups/v2/tasks/_src/task-detail-terminal-light.html.
const LIGHT_TERM = {
  bg: '#ffffff',
  bgElev1: '#eaeef2',          // status bar gradient end
  bgElev2: '#f6f8fa',          // status bar gradient start
  bgResult: '#f6f8fa',
  bgResultHead: '#eaeef2',
  bgInput: '#fbfcfd',          // very subtle off-white field
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
  toggleHoverBg: 'rgba(0,0,0,0.08)',
  toggleColor: '#57606a',

  shadow: '0 1px 3px rgba(0,0,0,0.06), 0 0 0 1px rgba(0,0,0,0.02)',
  divider: 'rgba(0,0,0,0.04)',
} as const;

function termCssVars(theme: TermTheme): React.CSSProperties {
  const p = theme === 'dark' ? DARK_TERM : LIGHT_TERM;
  // Cast — React.CSSProperties doesn't enumerate custom properties.
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

const layoutStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'stretch',
  minHeight: 'calc(100vh - 56px)',
  boxSizing: 'border-box',
  background: 'var(--bg-base)',
};
const mainColumnStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
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

const taskHeaderStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 14,
  padding: '0 36px 14px',
};
const thProviderStyle: React.CSSProperties = {
  width: 38, height: 38, borderRadius: 8,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  fontSize: 15, fontWeight: 700, color: '#fff', flexShrink: 0,
};
const thTitleBlockStyle: React.CSSProperties = { flex: 1, minWidth: 0 };
const thTitleStyle: React.CSSProperties = {
  fontSize: 17, fontWeight: 700, color: 'var(--text-1)',
  letterSpacing: '-0.012em',
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
  fontSize: 17, fontWeight: 700,
  letterSpacing: '-0.012em',
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
const thMetaStyle: React.CSSProperties = {
  fontSize: 12, color: 'var(--text-3)', marginTop: 2,
  display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap',
};
const repoStyle: React.CSSProperties = { fontFamily: monoFont, color: 'var(--text-2)', fontWeight: 500 };
const metaSepStyle: React.CSSProperties = { color: 'var(--text-4)' };
const modelChipStyle: React.CSSProperties = {
  fontSize: 10.5, background: 'var(--bg-elevated)', border: '1px solid var(--border)',
  padding: '1px 7px', borderRadius: 999, color: 'var(--text-2)',
};
const sessionIdStyle: React.CSSProperties = { color: 'var(--text-3)', fontFamily: monoFont, fontSize: 11.5 };
const thStatusStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5,
  padding: '5px 12px',
  borderRadius: 999,
  fontSize: 11, fontWeight: 700, letterSpacing: '0.04em',
  flexShrink: 0,
};
const thActionsStyle: React.CSSProperties = { display: 'flex', gap: 6, flexShrink: 0 };
const aBtnStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5,
  padding: '6px 13px',
  background: 'var(--bg-card)', border: '1px solid var(--border)',
  borderRadius: 999,
  fontSize: 12.5, color: 'var(--text-1)', fontWeight: 500,
  cursor: 'pointer', lineHeight: 1,
};

const bodyGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(0, 1fr) 360px',
  gap: 18,
  padding: '0 36px 36px',
  alignItems: 'start',
};

const terminalWrapStyle: React.CSSProperties = {
  background: 'var(--term-bg)',
  border: '1px solid var(--term-border)',
  borderRadius: 12,
  boxShadow: 'var(--term-shadow)',
  overflow: 'hidden',
  display: 'flex', flexDirection: 'column',
  height: 'calc(100vh - 220px)',
  minHeight: 480,
};
const termToolbarStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 12,
  padding: '9px 18px',
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
// Used inside the terminal toolbar — themed (separate from the
// page-header sessionIdStyle which stays light-page-chrome gray).
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

const statusBarStyle: React.CSSProperties = {
  padding: '8px 18px',
  background: 'linear-gradient(180deg, var(--term-bg-elev2) 0%, var(--term-bg-elev1) 100%)',
  borderTop: '1px solid var(--term-border)',
  fontFamily: monoFont, fontSize: 11.5, color: 'var(--term-text-muted)',
  display: 'flex', alignItems: 'center', gap: 16,
  flexShrink: 0,
};
const statStyle: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 4 };
const statStrongStyle: React.CSSProperties = { color: 'var(--term-text-bright)', fontWeight: 600 };
const statGroupRightStyle: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'inline-flex', alignItems: 'center', gap: 16,
};
const statHintStyle: React.CSSProperties = {
  color: 'var(--term-text-dim)', fontStyle: 'italic',
};
const runningDotStyle: React.CSSProperties = {
  width: 7, height: 7, borderRadius: '50%',
  background: 'var(--term-ok)', display: 'inline-block',
};

const termInputStyle: React.CSSProperties = {
  padding: '12px 18px 14px',
  // Subtle elevation against the scrollback so the field reads as
  // its own zone — the user asked for "a little bit obvious, not
  // too much", so a single near-invisible step.
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
const kbdStyle: React.CSSProperties = {
  background: 'var(--term-kbd-bg)', border: '1px solid var(--term-kbd-border)',
  padding: '1px 5px', borderRadius: 3, color: 'var(--term-text)',
  fontSize: 9.5,
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
const sendBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'linear-gradient(135deg, var(--term-send-bg-start), var(--term-send-bg-end))',
  color: 'var(--term-send-text)',
  border: 'none',
  borderRadius: 999,
  fontSize: 11, fontWeight: 700,
  cursor: 'pointer',
  fontFamily: 'system-ui, sans-serif',
};

const sidebarStyle: React.CSSProperties = {
  alignSelf: 'start',
  // Match the conversation pane's height (terminalWrapStyle /
  // structuredWrapStyle both use 100vh - 220px) so the sidebar
  // sits inside the same vertical slot and its overflow scrolls
  // *inside* — without this the sidebar's natural height bleeds
  // below the viewport and the bottom cards (Quick actions, Tools
  // used) get clipped because the page itself doesn't scroll.
  maxHeight: 'calc(100vh - 220px)',
  overflowY: 'auto',
  scrollbarWidth: 'thin',
  paddingRight: 4,
};
const sideCardStyle: React.CSSProperties = {
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 12,
  boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
  marginBottom: 14,
  overflow: 'hidden',
};
const groupPickerWrapStyle: React.CSSProperties = { padding: '0 18px 16px' };
const groupPickerStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  fontSize: 13,
  fontFamily: 'inherit',
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  cursor: 'pointer',
};
const sideCardHeadingStyle: React.CSSProperties = {
  fontSize: 11, fontWeight: 700, letterSpacing: '0.06em',
  textTransform: 'uppercase', color: 'var(--text-3)',
  padding: '12px 18px 8px', margin: 0,
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
const progressTrackStyle: React.CSSProperties = {
  position: 'relative',
  height: 3,
  margin: '0 18px 10px',
  background: 'var(--bg-elevated)',
  borderRadius: 2,
  overflow: 'hidden',
};
const progressBarStyle: React.CSSProperties = {
  position: 'absolute',
  top: 0, left: 0, bottom: 0,
  width: '40%',
  background: 'linear-gradient(90deg, transparent, #10b981, transparent)',
  borderRadius: 2,
};
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

const quickActionsStyle: React.CSSProperties = {
  padding: '12px 18px 16px', display: 'flex', flexDirection: 'column', gap: 6,
};
const qaBtnStyle: React.CSSProperties = {
  width: '100%', padding: '7px 12px',
  background: 'var(--bg-card)', border: '1px solid var(--border)',
  borderRadius: 6, color: 'var(--text-1)',
  fontSize: 12.5, fontWeight: 500,
  textAlign: 'left',
  display: 'flex', alignItems: 'center', gap: 8,
};
const qaBtnIconStyle: React.CSSProperties = { color: 'var(--text-3)', fontSize: 13 };

const errorBannerStyle: React.CSSProperties = {
  padding: '12px 16px', margin: '0 36px 24px',
  background: '#FEF2F2', color: '#991B1B',
  border: '1px solid #FCA5A5', borderRadius: 6,
};

// ── View toggle (Structured | Terminal) ─────────────────────────────────
const viewToggleStyle: React.CSSProperties = {
  display: 'inline-flex',
  padding: 2,
  background: 'var(--bg-elevated)',
  borderRadius: 8,
  border: '1px solid var(--border)',
};
const viewToggleBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'transparent',
  color: 'var(--text-2)',
  border: 'none',
  borderRadius: 6,
  fontSize: 12,
  fontWeight: 600,
  cursor: 'pointer',
};
const viewToggleActiveStyle: React.CSSProperties = {
  background: 'var(--bg-card)',
  color: 'var(--text-1)',
  boxShadow: '0 1px 2px rgba(15, 23, 42, 0.1)',
};

// ── Sidebar additions: Files touched / Tools used ───────────────────────
const cardCountStyle: React.CSSProperties = { color: 'var(--text-4)', fontWeight: 500 };
const cardEmptyStyle: React.CSSProperties = {
  padding: '0 18px 16px',
  color: 'var(--text-4)',
  fontSize: 12,
  fontStyle: 'italic',
};
const filesListStyle: React.CSSProperties = {
  padding: '0 14px 14px',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  maxHeight: 220,
  overflowY: 'auto',
};
const fileRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '4px 4px',
  fontSize: 12,
};
const fileOpTagStyle: React.CSSProperties = {
  padding: '1px 6px',
  borderRadius: 3,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: 0.4,
  flexShrink: 0,
};
const filePathStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: 'var(--text-2)',
};
const fileStatsStyle: React.CSSProperties = {
  fontSize: 11,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontVariantNumeric: 'tabular-nums',
  flexShrink: 0,
};
const toolsUsedStyle: React.CSSProperties = {
  padding: '0 14px 14px',
  display: 'flex',
  flexWrap: 'wrap',
  gap: 6,
};
const toolPillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '3px 8px',
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  fontSize: 12,
  fontWeight: 500,
  color: 'var(--text-2)',
};
const toolPillCountStyle: React.CSSProperties = {
  padding: '0 6px',
  background: 'var(--bg-card)',
  borderRadius: 999,
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--text-3)',
  fontVariantNumeric: 'tabular-nums',
};

// ── Structured view ─────────────────────────────────────────────────────
const structuredWrapStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 12,
  padding: 12,
  minHeight: 0,
  minWidth: 0,
  maxHeight: 'calc(100vh - 220px)',
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
  gap: 6,
  padding: '8px 14px',
  borderBottom: '1px solid var(--border)',
  background: 'var(--bg-card)',
  fontSize: 11,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: 'var(--text-2)',
};
const zoneIconStyle: React.CSSProperties = { fontSize: 13 };
const zoneLabelStyle: React.CSSProperties = { color: 'var(--text-2)' };
const zoneMetaStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontWeight: 500,
  textTransform: 'none',
  letterSpacing: 0,
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
