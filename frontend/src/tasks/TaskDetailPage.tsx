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
import type { TaskDto, TaskMessageDto, TaskStatusDto } from '../types';
import { ConversationPane, type PendingPermission } from './ConversationPane';

type Props = {
  taskId: string;
  onBack: () => void;
};

/** Live cadence — fast while a turn is in flight so the conversation
 *  pane feels responsive, slow when the agent is parked so we don't
 *  burn the SQLite read budget for nothing. */
const POLL_MS_RUNNING = 1_000;
const POLL_MS_IDLE = 5_000;
const POLL_MS_TERMINAL = 0;

/**
 * Single-task surface — header strip with metrics, terminal-styled
 * conversation pane, and an input box for follow-up turns. Polls
 * task + messages on a status-aware cadence: 1s while a turn is in
 * flight, 5s when parked, off when terminal. SSE plumbing through
 * Electron is deferred — at human speed an LLM turn doesn't make a
 * 1s poll feel laggy.
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

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Status-aware polling.
  useEffect(() => {
    const interval = pollInterval(task?.status);
    if (!interval) return;
    const id = setInterval(() => { void refresh(); }, interval);
    return () => clearInterval(id);
  }, [task?.status, refresh]);

  const pendingPermission = useMemo<PendingPermission | null>(
    () => findPendingPermission(messages),
    [messages]);

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
        <button type="button" onClick={onBack} style={backBtnStyle}>← Tasks</button>
        <div style={errorBannerStyle}>{error}</div>
      </section>
    );
  }
  if (task === null) {
    return (
      <section style={pageStyle}>
        <button type="button" onClick={onBack} style={backBtnStyle}>← Tasks</button>
        <div style={mutedTextStyle}>Loading…</div>
      </section>
    );
  }

  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';

  return (
    <section style={pageStyle}>
      <header style={headerStyle}>
        <div style={headerLeftStyle}>
          <button type="button" onClick={onBack} style={backBtnStyle}>← Tasks</button>
          <div>
            <h1 style={titleStyle}>{task.title}</h1>
            <div style={metaRowStyle}>
              <StatusPill status={task.status} />
              <span style={mutedTextStyle}>{task.model}</span>
              <span style={mutedTextStyle}>{task.workingDir}</span>
              {task.branchName && <span style={branchPillStyle}>{task.branchName}</span>}
            </div>
          </div>
        </div>
        <div style={headerRightStyle}>
          <Metric label="Cost" value={formatCost(task.costUsdMilli)} />
          <Metric label="Tokens" value={`${formatNum(task.tokensIn)} → ${formatNum(task.tokensOut)}`} />
          {!isTerminal && (
            <button type="button" onClick={() => void onStop()} style={dangerBtnStyle}>
              Stop
            </button>
          )}
        </div>
      </header>

      {error && <div style={errorBannerStyle}>{error}</div>}

      <ConversationPane
        messages={messages}
        pendingPermission={pendingPermission}
        onDecide={(callId, decision) => void onDecide(callId, decision)}
      />

      {!isTerminal && (
        <div style={composerStyle}>
          <textarea
            value={draft}
            onChange={e => setDraft(e.target.value)}
            placeholder={
              task.status === 'RUNNING'
                ? 'A turn is in flight — wait for it to finish before sending the next.'
                : 'Send a follow-up turn… (Cmd+Enter to send)'
            }
            disabled={task.status === 'RUNNING' || sending}
            onKeyDown={e => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                void onSend();
              }
            }}
            rows={3}
            style={composerInputStyle}
          />
          <div style={composerActionsStyle}>
            <span style={composerHintStyle}>
              {task.status === 'AWAITING' && 'Waiting for input.'}
            </span>
            <button
              type="button"
              onClick={() => void onSend()}
              disabled={!draft.trim() || sending || task.status === 'RUNNING'}
              style={primaryBtnStyle}
            >
              {sending ? 'Sending…' : 'Send'}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function pollInterval(status: TaskStatusDto | undefined): number {
  if (!status) return POLL_MS_IDLE;
  if (status === 'RUNNING') return POLL_MS_RUNNING;
  if (status === 'COMPLETED' || status === 'ERRORED') return POLL_MS_TERMINAL;
  return POLL_MS_IDLE;
}

/** Walk back from the newest message; the first {@code permission_request}
 *  with no matching {@code permission_decision} after it is the one the
 *  user has to answer. */
function findPendingPermission(messages: TaskMessageDto[]): PendingPermission | null {
  const decided = new Set<string>();
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type === 'permission_decision') {
      try {
        const parsed = JSON.parse(m.contentJson) as { callId?: string };
        if (parsed.callId) decided.add(parsed.callId);
      }
      catch {
        // Ignore — malformed rows are skipped.
      }
    }
    if (m.type === 'permission_request') {
      try {
        const parsed = JSON.parse(m.contentJson) as { callId?: string; toolName?: string; summary?: string };
        if (parsed.callId && !decided.has(parsed.callId)) {
          return {
            callId: parsed.callId,
            toolName: parsed.toolName ?? 'unknown',
            summary: parsed.summary ?? '',
          };
        }
      }
      catch {
        // Ignore — malformed rows are skipped.
      }
    }
  }
  return null;
}

function StatusPill({ status }: { status: TaskStatusDto }) {
  const palette: Record<TaskStatusDto, { fg: string; bg: string }> = {
    RUNNING:   { fg: '#ffffff', bg: '#7C3AED' },
    AWAITING:  { fg: '#ffffff', bg: '#D97706' },
    PENDING:   { fg: '#1F2937', bg: '#E5E7EB' },
    IDLE:      { fg: '#374151', bg: '#F3F4F6' },
    COMPLETED: { fg: '#ffffff', bg: '#10B981' },
    ERRORED:   { fg: '#ffffff', bg: '#DC2626' },
  };
  const { fg, bg } = palette[status];
  return (
    <span style={{
      display: 'inline-block',
      padding: '2px 8px',
      borderRadius: 999,
      fontSize: 11,
      fontWeight: 600,
      letterSpacing: 0.3,
      color: fg,
      background: bg,
    }}>{status}</span>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div style={metricStyle}>
      <div style={metricLabelStyle}>{label}</div>
      <div style={metricValueStyle}>{value}</div>
    </div>
  );
}

function formatCost(milli: number): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

function formatNum(n: number): string {
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

const pageStyle: React.CSSProperties = {
  padding: '24px 32px 32px',
  maxWidth: 1100,
  margin: '0 auto',
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
  height: 'calc(100vh - 56px)',
  boxSizing: 'border-box',
};
const headerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
  gap: 16,
};
const headerLeftStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minWidth: 0 };
const headerRightStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 16 };
const backBtnStyle: React.CSSProperties = {
  alignSelf: 'flex-start',
  background: 'transparent',
  border: 'none',
  color: '#6B7280',
  fontSize: 13,
  cursor: 'pointer',
  padding: 0,
};
const titleStyle: React.CSSProperties = { margin: '8px 0 4px', fontSize: 20, fontWeight: 700 };
const metaRowStyle: React.CSSProperties = { display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' };
const mutedTextStyle: React.CSSProperties = { color: '#6B7280', fontSize: 13 };
const branchPillStyle: React.CSSProperties = {
  padding: '2px 8px',
  background: '#EFF6FF',
  color: '#1E40AF',
  border: '1px solid #BFDBFE',
  borderRadius: 4,
  fontSize: 11,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};
const metricStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', alignItems: 'flex-end' };
const metricLabelStyle: React.CSSProperties = { fontSize: 10, color: '#9CA3AF', textTransform: 'uppercase', letterSpacing: 0.6 };
const metricValueStyle: React.CSSProperties = { fontSize: 14, fontWeight: 600, color: '#111827' };
const dangerBtnStyle: React.CSSProperties = {
  padding: '6px 12px',
  background: 'transparent',
  color: '#DC2626',
  border: '1px solid #FCA5A5',
  borderRadius: 4,
  fontSize: 13,
  cursor: 'pointer',
};
const errorBannerStyle: React.CSSProperties = {
  padding: '12px 16px',
  background: '#FEF2F2',
  color: '#991B1B',
  border: '1px solid #FCA5A5',
  borderRadius: 6,
};
const composerStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  padding: 12,
  background: '#F9FAFB',
  border: '1px solid #E5E7EB',
  borderRadius: 8,
};
const composerInputStyle: React.CSSProperties = {
  padding: '8px 10px',
  border: '1px solid #D1D5DB',
  borderRadius: 6,
  fontSize: 13,
  fontFamily: 'inherit',
  width: '100%',
  boxSizing: 'border-box',
  resize: 'vertical',
};
const composerActionsStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
};
const composerHintStyle: React.CSSProperties = { fontSize: 12, color: '#6B7280' };
const primaryBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: '#7C3AED',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'pointer',
};
