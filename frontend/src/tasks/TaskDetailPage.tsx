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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { TaskDto, TaskMessageDto, TaskStatusDto } from '../types';
import { ConversationPane, type PendingPermission } from './ConversationPane';

type Props = {
  taskId: string;
  onBack: () => void;
};

const POLL_MS_RUNNING = 1_000;
const POLL_MS_IDLE = 5_000;
const POLL_MS_TERMINAL = 0;

/**
 * Two-column task detail surface — terminal pane + sticky sidebar
 * (stage / metrics / color legend / quick actions). Faithfully
 * follows the layout in
 * {@code docs/mockups/design/tasks/task-detail-terminal.png}.
 *
 * <p>SSE through Electron is deferred; we poll on a status-aware
 * cadence (1s while RUNNING, 5s otherwise, off when terminal).
 */
export default function TaskDetailPage({ taskId, onBack }: Props) {
  const [task, setTask] = useState<TaskDto | null>(null);
  const [messages, setMessages] = useState<TaskMessageDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [t, m] = await Promise.all([
        window.bridge.getTask(taskId),
        window.bridge.getTaskMessages(taskId),
      ]);
      setTask(t);
      setMessages(m);
      setError(null);
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

  const onDecide = useCallback(async (callId: string, decision: 'ALLOW' | 'DENY') => {
    try {
      await window.bridge.decideTaskPermission(taskId, callId, decision);
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

  if (task === null && error) {
    return (
      <section style={pageStyle}>
        <BackBar onBack={onBack} title="(failed to load)" />
        <div style={errorBannerStyle}>{error}</div>
      </section>
    );
  }
  if (task === null) {
    return (
      <section style={pageStyle}>
        <BackBar onBack={onBack} title="loading…" />
      </section>
    );
  }

  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';

  return (
    <section style={pageStyle}>
      <KeyframesStyles />

      <BreadcrumbRow title={task.title} onBack={onBack} />

      <TaskHeader
        task={task}
        onPause={undefined /* pause not wired through MCP yet */}
        onStop={onStop}
        canStop={!isTerminal}
      />

      <div style={bodyGridStyle}>
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
        />

        <Sidebar task={task} stage={stage} />
      </div>

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
  onPause,
  onStop,
  canStop,
}: {
  task: TaskDto;
  onPause: (() => void) | undefined;
  onStop: () => void;
  canStop: boolean;
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
        <div style={thTitleStyle}>{task.title}</div>
        <div style={thMetaStyle}>
          {task.workingDir && (
            <>
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
        {onPause && (
          <button type="button" onClick={onPause} style={aBtnStyle}>⏸ Pause</button>
        )}
        {canStop && (
          <button type="button" onClick={onStop} style={{ ...aBtnStyle, color: '#b91c1c' }}>
            ⏹ Stop
          </button>
        )}
      </div>
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
}: {
  task: TaskDto;
  messages: TaskMessageDto[];
  pendingPermission: PendingPermission | null;
  onDecide: (callId: string, decision: 'ALLOW' | 'DENY') => void;
  stage: Stage;
  draft: string;
  onDraft: (s: string) => void;
  onSend: () => void;
  onInterrupt: () => void;
  sending: boolean;
  isTerminal: boolean;
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
          <span style={sessionIdStyle}> {shortenPath(task.workingDir)}</span>
          {task.branchName && (
            <span style={sessionIdStyle}> · {task.branchName}</span>
          )}
        </span>
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
      <span style={statStyle}>
        {isRunning && <span className="bytequay-pulse" style={runningDotStyle} />}
        <strong style={statStrongStyle}>{task.status}</strong>
      </span>
      <span style={statStyle}>⏱ <strong style={statStrongStyle}>{formatRuntime(task)}</strong></span>
      <span style={statStyle}>💰 <strong style={statStrongStyle}>{formatCost(task.costUsdMilli)}</strong></span>
      <span style={statStyle}>tokens <strong style={statStrongStyle}>{formatNum(task.tokensIn + task.tokensOut)}</strong></span>
      {stage.toolName && (
        <span style={statStyle}>
          {stage.glyph} <strong style={statStrongStyle}>{stage.toolName}</strong>
        </span>
      )}
      <span style={statRightStyle}>
        {isRunning ? 'press Cancel to interrupt' : ''}
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
  return (
    <div style={termInputStyle}>
      <div style={termInputRowStyle}>
        <span style={termPromptStyle}>›</span>
        <textarea
          value={draft}
          onChange={e => onDraft(e.target.value)}
          placeholder={
            isRunning
              ? 'queued — sends after current turn (or press Cancel to interrupt)'
              : 'send a follow-up turn…'
          }
          disabled={sending}
          rows={Math.min(6, Math.max(1, draft.split('\n').length))}
          onKeyDown={e => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              e.preventDefault();
              onSend();
            }
          }}
          style={termTextareaStyle}
        />
      </div>
      <div style={termInputFooterStyle}>
        <span><Kbd>⌘</Kbd>+<Kbd>↵</Kbd> send · <Kbd>⇧</Kbd>+<Kbd>↵</Kbd> newline</span>
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

function Sidebar({ task, stage }: { task: TaskDto; stage: Stage }) {
  return (
    <div style={sidebarStyle}>
      <SideCard>
        <StageCard task={task} stage={stage} />
      </SideCard>

      <SideCard>
        <h4 style={sideCardHeadingStyle}>Metrics</h4>
        <div style={metricListStyle}>
          <Metric label="Runtime" value={formatRuntime(task)} live={task.status === 'RUNNING'} />
          <Metric label="Cost so far" value={formatCost(task.costUsdMilli)} sub="CLI-reported" />
          <Metric label="Tokens in → out" value={`${formatNum(task.tokensIn)} → ${formatNum(task.tokensOut)}`} />
          <Metric label="Model" value={task.model} mono />
          {task.branchName && <Metric label="Branch" value={task.branchName} mono />}
          {task.agentSessionId && <Metric label="Session" value={shortId(task.agentSessionId)} mono />}
          <Metric label="Status" value={task.status} />
        </div>
      </SideCard>

      <SideCard>
        <h4 style={sideCardHeadingStyle}>Output color legend</h4>
        <div style={legendStyle}>
          {LEGEND.map(({ color, label }) => (
            <div key={label} style={legendItemStyle}>
              <span style={{ ...legendSwatchStyle, background: color }} />
              <span style={legendLabelStyle}>{label}</span>
            </div>
          ))}
        </div>
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

function SideCard({ children }: { children: React.ReactNode }) {
  return <div style={sideCardStyle}>{children}</div>;
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
  return (
    <div style={metricRowStyle}>
      <span style={metricLabelStyle}>{label}</span>
      <span style={{
        ...metricValueStyle,
        color: live ? '#047857' : '#1F2937',
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
    case 'IDLE':      return { background: '#f3f4f6', color: '#374151' };
    case 'COMPLETED': return { background: '#d1fae5', color: '#047857' };
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
  return `${h}h ${m % 60}m`;
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

const LEGEND: Array<{ color: string; label: string }> = [
  { color: '#b794f4', label: 'User input' },
  { color: '#6e7681', label: 'Thinking · dim' },
  { color: '#79c0ff', label: 'Read · classes' },
  { color: '#f0883e', label: 'Write · `inline`' },
  { color: '#ffd33d', label: 'Edit' },
  { color: '#f85149', label: 'Bash · errors' },
  { color: '#56d364', label: 'Paths · success' },
  { color: '#d2a8ff', label: 'Line refs' },
];

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

const monoFont = '"SF Mono", "JetBrains Mono", Menlo, Consolas, monospace';

const pageStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  minHeight: 'calc(100vh - 56px)',
  boxSizing: 'border-box',
  background: '#fafbfc',
};

const breadcrumbRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '14px 36px 0',
  fontSize: 13,
  color: '#6B7280',
  marginBottom: 12,
};
const crumbBackStyle: React.CSSProperties = {
  background: 'transparent', border: 'none', padding: 0,
  color: '#7C3AED', fontWeight: 500, fontSize: 14, cursor: 'pointer',
};
const crumbLinkStyle: React.CSSProperties = {
  background: 'transparent', border: 'none', padding: 0,
  color: '#7C3AED', cursor: 'pointer', fontSize: 13,
};
const crumbSepStyle: React.CSSProperties = { color: '#D1D5DB' };
const crumbCurrentStyle: React.CSSProperties = {
  color: '#111827', fontWeight: 600,
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
  fontSize: 17, fontWeight: 700, color: '#111827',
  letterSpacing: '-0.012em',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const thMetaStyle: React.CSSProperties = {
  fontSize: 12, color: '#6B7280', marginTop: 2,
  display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap',
};
const repoStyle: React.CSSProperties = { fontFamily: monoFont, color: '#374151', fontWeight: 500 };
const metaSepStyle: React.CSSProperties = { color: '#D1D5DB' };
const modelChipStyle: React.CSSProperties = {
  fontSize: 10.5, background: '#F3F4F6', border: '1px solid #E5E7EB',
  padding: '1px 7px', borderRadius: 999, color: '#374151',
};
const sessionIdStyle: React.CSSProperties = { color: '#6e7681', fontFamily: monoFont, fontSize: 11.5 };
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
  background: '#fff', border: '1px solid #E5E7EB',
  borderRadius: 999,
  fontSize: 12.5, color: '#111827', fontWeight: 500,
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
  background: '#0d1117',
  border: '1px solid #21262d',
  borderRadius: 12,
  boxShadow: '0 4px 14px rgba(13,17,23,0.18), 0 1px 3px rgba(13,17,23,0.10)',
  overflow: 'hidden',
  display: 'flex', flexDirection: 'column',
  height: 'calc(100vh - 220px)',
  minHeight: 480,
};
const termToolbarStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 12,
  padding: '9px 18px',
  background: 'linear-gradient(180deg, #161b22 0%, #13181f 100%)',
  borderBottom: '1px solid #21262d',
  fontSize: 12, color: '#8b949e',
  flexShrink: 0,
};
const trafficStyle: React.CSSProperties = { display: 'flex', gap: 5, marginRight: 6 };
const trafficDotStyle: React.CSSProperties = {
  width: 10, height: 10, borderRadius: '50%',
  boxShadow: 'inset 0 0 0 0.5px rgba(0,0,0,0.18)',
};
const termNameStyle: React.CSSProperties = {
  color: '#c9d1d9', fontFamily: monoFont, fontSize: 11.5,
};
const termBadgeStyle: React.CSSProperties = {
  background: 'rgba(124,92,255,0.16)',
  color: '#b794f4',
  padding: '1px 7px', borderRadius: 999,
  fontSize: 10, fontWeight: 700, letterSpacing: '0.04em',
  marginLeft: 6,
  fontFamily: 'system-ui, sans-serif',
};

const statusBarStyle: React.CSSProperties = {
  padding: '8px 18px',
  background: 'linear-gradient(180deg, #161b22 0%, #13181f 100%)',
  borderTop: '1px solid #21262d',
  fontFamily: monoFont, fontSize: 11.5, color: '#8b949e',
  display: 'flex', alignItems: 'center', gap: 16,
  flexShrink: 0,
};
const statStyle: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 4 };
const statStrongStyle: React.CSSProperties = { color: '#f0f6fc', fontWeight: 600 };
const statRightStyle: React.CSSProperties = { marginLeft: 'auto', color: '#6e7681', fontStyle: 'italic' };
const runningDotStyle: React.CSSProperties = {
  width: 7, height: 7, borderRadius: '50%',
  background: '#56d364', display: 'inline-block',
};

const termInputStyle: React.CSSProperties = {
  padding: '12px 18px 14px',
  background: '#0d1117',
  borderTop: '1px solid #21262d',
  flexShrink: 0,
};
const termInputRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 10,
  fontFamily: monoFont, fontSize: 13.5,
};
const termPromptStyle: React.CSSProperties = {
  color: '#d2a8ff', fontWeight: 700,
  flexShrink: 0, lineHeight: 1.55, userSelect: 'none', fontSize: 15,
};
const termTextareaStyle: React.CSSProperties = {
  flex: 1, minWidth: 0,
  background: 'transparent',
  color: '#c9d1d9',
  border: 'none',
  outline: 'none',
  resize: 'none',
  fontFamily: monoFont,
  fontSize: 13.5,
  lineHeight: 1.55,
  padding: 0,
};
const termInputFooterStyle: React.CSSProperties = {
  marginTop: 10,
  paddingTop: 8,
  borderTop: '1px solid #1c2228',
  display: 'flex', alignItems: 'center', gap: 10,
  fontFamily: monoFont, fontSize: 10.5, color: '#6e7681',
};
const kbdStyle: React.CSSProperties = {
  background: '#1c2128', border: '1px solid #30363d',
  padding: '1px 5px', borderRadius: 3, color: '#c9d1d9',
  fontSize: 9.5,
};
const cancelChipStyle: React.CSSProperties = {
  padding: '2px 9px',
  background: 'transparent',
  border: '1px solid #C2410C',
  borderRadius: 999,
  fontSize: 10.5, color: '#FED7AA',
  cursor: 'pointer',
  fontFamily: 'inherit',
};
const sendBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'linear-gradient(135deg, #b794f4, #7c5cff)',
  color: '#0d1117',
  border: 'none',
  borderRadius: 999,
  fontSize: 11, fontWeight: 700,
  cursor: 'pointer',
  fontFamily: 'system-ui, sans-serif',
};

const sidebarStyle: React.CSSProperties = {
  alignSelf: 'start',
  position: 'sticky',
  top: 14,
};
const sideCardStyle: React.CSSProperties = {
  background: '#fff',
  border: '1px solid #E5E7EB',
  borderRadius: 12,
  boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
  marginBottom: 14,
  overflow: 'hidden',
};
const sideCardHeadingStyle: React.CSSProperties = {
  fontSize: 11, fontWeight: 700, letterSpacing: '0.06em',
  textTransform: 'uppercase', color: '#6B7280',
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
  background: '#F9FAFB', border: '1px solid #E5E7EB',
  borderRadius: 6, padding: '10px 12px',
  fontFamily: monoFont, fontSize: 11.5,
  color: '#374151', lineHeight: 1.55,
};
const stageArrowStyle: React.CSSProperties = { color: '#7C3AED' };
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
  padding: '6px 0', borderBottom: '1px solid #F3F4F6',
};
const metricLabelStyle: React.CSSProperties = {
  color: '#6B7280', fontSize: 12, width: 110, flexShrink: 0,
};
const metricValueStyle: React.CSSProperties = {
  fontWeight: 500, fontVariantNumeric: 'tabular-nums',
  flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const metricSubStyle: React.CSSProperties = {
  fontSize: 11, color: '#6B7280', marginLeft: 4, fontWeight: 400,
};

const legendStyle: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '1fr 1fr',
  gap: '6px 12px', fontSize: 11.5,
  padding: '0 18px 14px',
};
const legendItemStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 6 };
const legendSwatchStyle: React.CSSProperties = {
  width: 10, height: 10, borderRadius: 2, flexShrink: 0,
};
const legendLabelStyle: React.CSSProperties = { color: '#374151' };

const quickActionsStyle: React.CSSProperties = {
  padding: '12px 18px 16px', display: 'flex', flexDirection: 'column', gap: 6,
};
const qaBtnStyle: React.CSSProperties = {
  width: '100%', padding: '7px 12px',
  background: '#fff', border: '1px solid #E5E7EB',
  borderRadius: 6, color: '#111827',
  fontSize: 12.5, fontWeight: 500,
  textAlign: 'left',
  display: 'flex', alignItems: 'center', gap: 8,
};
const qaBtnIconStyle: React.CSSProperties = { color: '#6B7280', fontSize: 13 };

const errorBannerStyle: React.CSSProperties = {
  padding: '12px 16px', margin: '0 36px 24px',
  background: '#FEF2F2', color: '#991B1B',
  border: '1px solid #FCA5A5', borderRadius: 6,
};
