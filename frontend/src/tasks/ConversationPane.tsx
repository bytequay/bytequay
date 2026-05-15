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
import { useEffect, useRef, useState } from 'react';
import type { TaskMessageDto } from '../types';

export type PendingPermission = {
  callId: string;
  toolName: string;
  summary: string;
};

type Props = {
  messages: TaskMessageDto[];
  pendingPermission: PendingPermission | null;
  onDecide: (callId: string, decision: 'ALLOW' | 'DENY') => void;
};

/**
 * Terminal-styled conversation pane — mirrors what {@code claude code}
 * draws in a real terminal. Each persisted message becomes one row,
 * prefixed with a single-glyph marker color-coded by role.
 *
 * <p>Auto-scrolls to the bottom on new content unless the user has
 * scrolled away — that way an active turn keeps the latest visible
 * but a paused user can still read backlog without being yanked.
 */
export function ConversationPane({ messages, pendingPermission, onDecide }: Props) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickToBottomRef = useRef(true);

  // Track whether the user is parked at the bottom; used to decide if
  // a new-content arrival should scroll. 8px slop absorbs sub-pixel
  // jitter from monospace line metrics.
  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    stickToBottomRef.current = distFromBottom < 8;
  };

  useEffect(() => {
    if (!stickToBottomRef.current) return;
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages.length, pendingPermission]);

  return (
    <div style={paneStyle} ref={scrollRef} onScroll={onScroll}>
      {messages.length === 0 && (
        <div style={emptyHintStyle}>
          No conversation yet. Send a prompt below to kick off the first turn.
        </div>
      )}
      {messages.map(m => (
        <MessageRow key={m.id} message={m} />
      ))}
      {pendingPermission && (
        <PermissionBanner
          permission={pendingPermission}
          onDecide={onDecide}
        />
      )}
    </div>
  );
}

function MessageRow({ message }: { message: TaskMessageDto }) {
  const parsed = parseContent(message.contentJson);
  switch (message.type) {
    case 'session_started':
      return (
        <Line glyph="●" color="#9CA3AF">
          <Muted>session</Muted> {String(parsed.cwd ?? '')} ·{' '}
          <Muted>{String(parsed.model ?? '')}</Muted>
        </Line>
      );
    case 'text': {
      const text = String(parsed.text ?? '');
      const isUser = message.role === 'user';
      return (
        <Line glyph={isUser ? '>' : '●'} color={isUser ? '#7C3AED' : '#10B981'}>
          <Pre>{text}</Pre>
        </Line>
      );
    }
    case 'thinking': {
      const summary = String(parsed.summary ?? '');
      return <ThinkingBlock summary={summary} />;
    }
    case 'tool_call': {
      const toolName = String(parsed.toolName ?? 'tool');
      const input = formatJsonInline(parsed.input);
      return (
        <Line glyph="⏵" color="#2563EB">
          <strong>{toolName}</strong>{input && <Muted> {input}</Muted>}
        </Line>
      );
    }
    case 'tool_result': {
      const isError = parsed.isError === true;
      const output = formatToolOutput(parsed.output);
      return (
        <ToolResultBlock isError={isError} output={output} />
      );
    }
    case 'turn_done': {
      const cost = formatCost(message.costUsdMilli);
      const dur = formatDuration(message.durationMs);
      const tokens = `${formatNum(message.tokensIn ?? 0)} → ${formatNum(message.tokensOut ?? 0)}`;
      return (
        <div style={turnDividerStyle}>
          <span style={turnDividerLabelStyle}>
            turn done · {dur} · {tokens} tokens · {cost}
          </span>
        </div>
      );
    }
    case 'permission_request': {
      const tool = String(parsed.toolName ?? 'tool');
      const summary = String(parsed.summary ?? '');
      return (
        <Line glyph="?" color="#D97706">
          permission asked · <strong>{tool}</strong>
          {summary && <Muted> — {summary}</Muted>}
        </Line>
      );
    }
    case 'permission_decision': {
      const decision = String(parsed.decision ?? '');
      return (
        <Line glyph="✓" color={decision === 'ALLOW' ? '#10B981' : '#DC2626'}>
          <Muted>permission {decision.toLowerCase()}</Muted>
        </Line>
      );
    }
    case 'error': {
      const text = String(parsed.message ?? 'error');
      return (
        <Line glyph="!" color="#DC2626">
          <Pre>{text}</Pre>
        </Line>
      );
    }
    case 'session_ended': {
      const exit = parsed.exitCode ?? 0;
      const note = parsed.errorMessage;
      return (
        <Line glyph="●" color="#6B7280">
          <Muted>session ended · exit {String(exit)}{note ? ` · ${String(note)}` : ''}</Muted>
        </Line>
      );
    }
    default:
      return (
        <Line glyph="·" color="#9CA3AF">
          <Muted>{message.role}/{message.type}</Muted>
        </Line>
      );
  }
}

function ThinkingBlock({ summary }: { summary: string }) {
  const [expanded, setExpanded] = useState(false);
  if (!summary) {
    return (
      <Line glyph="…" color="#9CA3AF"><Muted>thinking</Muted></Line>
    );
  }
  return (
    <div style={lineStyle}>
      <span style={{ ...glyphStyle, color: '#9CA3AF' }}>…</span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <button
          type="button"
          onClick={() => setExpanded(v => !v)}
          style={collapseBtnStyle}
        >
          {expanded ? '▾' : '▸'} thinking
        </button>
        {expanded && (
          <pre style={{ ...preStyle, color: '#6B7280', marginTop: 4 }}>{summary}</pre>
        )}
      </div>
    </div>
  );
}

function ToolResultBlock({ isError, output }: { isError: boolean; output: string }) {
  const [expanded, setExpanded] = useState(false);
  const truncated = output.length > 600 && !expanded
    ? output.slice(0, 600) + '\n…'
    : output;
  return (
    <div style={lineStyle}>
      <span style={{ ...glyphStyle, color: isError ? '#DC2626' : '#059669' }}>↳</span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <pre style={{
          ...preStyle,
          color: isError ? '#991B1B' : '#374151',
          background: isError ? '#FEF2F2' : '#F9FAFB',
          padding: '6px 8px',
          borderRadius: 4,
          margin: 0,
        }}>{truncated}</pre>
        {output.length > 600 && (
          <button type="button" onClick={() => setExpanded(v => !v)} style={collapseBtnStyle}>
            {expanded ? 'collapse' : `show ${output.length - 600} more chars`}
          </button>
        )}
      </div>
    </div>
  );
}

function PermissionBanner({
  permission,
  onDecide,
}: {
  permission: PendingPermission;
  onDecide: (callId: string, decision: 'ALLOW' | 'DENY') => void;
}) {
  return (
    <div style={permissionStyle}>
      <div>
        <div style={permissionTitleStyle}>
          Permission needed for <strong>{permission.toolName}</strong>
        </div>
        {permission.summary && (
          <div style={permissionSummaryStyle}>{permission.summary}</div>
        )}
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button
          type="button"
          onClick={() => onDecide(permission.callId, 'DENY')}
          style={denyBtnStyle}
        >
          Deny
        </button>
        <button
          type="button"
          onClick={() => onDecide(permission.callId, 'ALLOW')}
          style={allowBtnStyle}
        >
          Allow
        </button>
      </div>
    </div>
  );
}

function Line({
  glyph,
  color,
  children,
}: {
  glyph: string;
  color: string;
  children: React.ReactNode;
}) {
  return (
    <div style={lineStyle}>
      <span style={{ ...glyphStyle, color }}>{glyph}</span>
      <div style={{ flex: 1, minWidth: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
        {children}
      </div>
    </div>
  );
}

function Pre({ children }: { children: React.ReactNode }) {
  return (
    <pre style={preStyle}>{children}</pre>
  );
}

function Muted({ children }: { children: React.ReactNode }) {
  return <span style={{ color: '#9CA3AF' }}>{children}</span>;
}

function parseContent(json: string): Record<string, unknown> {
  try {
    const v = JSON.parse(json);
    return (v && typeof v === 'object') ? v as Record<string, unknown> : {};
  }
  catch {
    return {};
  }
}

function formatJsonInline(v: unknown): string {
  if (v == null) return '';
  try {
    const s = JSON.stringify(v);
    if (!s) return '';
    return s.length > 120 ? s.slice(0, 117) + '…' : s;
  }
  catch {
    return '';
  }
}

function formatToolOutput(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v, null, 2);
  }
  catch {
    return String(v);
  }
}

function formatCost(milli: number | null): string {
  if (!milli) return '$0';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

function formatDuration(ms: number | null): string {
  if (!ms) return '0ms';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatNum(n: number): string {
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

const paneStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: 16,
  background: '#0F172A',
  color: '#E5E7EB',
  borderRadius: 8,
  fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
  fontSize: 13,
  lineHeight: 1.5,
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};
const emptyHintStyle: React.CSSProperties = { color: '#6B7280', textAlign: 'center', padding: '40px 0' };
const lineStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'flex-start',
};
const glyphStyle: React.CSSProperties = {
  width: 14,
  flexShrink: 0,
  textAlign: 'center',
  fontWeight: 700,
};
const preStyle: React.CSSProperties = {
  margin: 0,
  fontFamily: 'inherit',
  fontSize: 'inherit',
  lineHeight: 'inherit',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  color: '#F3F4F6',
};
const collapseBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: '#9CA3AF',
  fontFamily: 'inherit',
  fontSize: 12,
  cursor: 'pointer',
  padding: 0,
};
const turnDividerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 0',
  borderTop: '1px dashed #334155',
  marginTop: 6,
};
const turnDividerLabelStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#94A3B8',
  fontStyle: 'italic',
};
const permissionStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 16,
  marginTop: 8,
  padding: '12px 14px',
  background: '#7C2D12',
  border: '1px solid #C2410C',
  borderRadius: 6,
};
const permissionTitleStyle: React.CSSProperties = { color: '#FED7AA', fontSize: 13 };
const permissionSummaryStyle: React.CSSProperties = { color: '#FDBA74', fontSize: 12, marginTop: 2 };
const allowBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: '#10B981',
  color: '#fff',
  border: 'none',
  borderRadius: 4,
  fontWeight: 600,
  cursor: 'pointer',
};
const denyBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: 'transparent',
  color: '#FED7AA',
  border: '1px solid #C2410C',
  borderRadius: 4,
  cursor: 'pointer',
};
