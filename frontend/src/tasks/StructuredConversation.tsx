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
import { useEffect, useMemo, useRef, useState } from 'react';
import type { TaskMessageDto } from '../types';
import type { PendingPermission } from './ConversationPane';

type Props = {
  messages: TaskMessageDto[];
  pendingPermission: PendingPermission | null;
  onDecide: (callId: string, decision: 'ALLOW' | 'DENY') => void;
  modelName: string;
};

/**
 * Chat-card renderer for the Structured detail view. Walks the
 * persisted message log and stitches it into the dialog layout from
 * {@code docs/mockups/design/tasks/task-detail.png}:
 *
 *  • User text → violet-tinted card with avatar + "You" label.
 *  • Assistant content (thinking, prose, tool calls) → claude-orange
 *    card. Consecutive assistant items merge into one card so a turn
 *    reads as a single bubble even when it interleaves multiple
 *    thinking / tool / prose events.
 *  • Tool calls render inline inside the assistant card as compact
 *    cards with a color-coded op tag (READ / WRITE / EDIT / BASH /
 *    GREP) and a paired result row when the tool has returned.
 *  • {@code turn_done} closes the current assistant card; the next
 *    user turn opens a fresh dialog exchange.
 *  • Day separators slot in at the top of each calendar day.
 *
 * Auto-scrolls to the bottom on new content unless the user has
 * scrolled away, mirroring the terminal pane's stickiness.
 */
export function StructuredConversation({
  messages, pendingPermission, onDecide, modelName,
}: Props) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true);

  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    stickRef.current = distFromBottom < 24;
  };
  useEffect(() => {
    if (!stickRef.current) return;
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages.length, pendingPermission]);

  const events = useMemo(() => groupAndPair(messages), [messages]);
  const cards = useMemo(() => buildDialog(events, modelName), [events, modelName]);

  return (
    <div style={scrollStyle} ref={scrollRef} onScroll={onScroll}>
      {cards.length === 0 && (
        <div style={emptyHintStyle}>
          Waiting for the first turn — send a prompt below to kick off.
        </div>
      )}
      {cards.map(c => renderCard(c, onDecide))}
      {pendingPermission && (
        <PermissionCard permission={pendingPermission} onDecide={onDecide} />
      )}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────
// Event grouping (tool_call + tool_result pairing)
// ────────────────────────────────────────────────────────────────────

type ToolEvent = {
  kind: 'tool';
  key: string;
  call: TaskMessageDto;
  result: TaskMessageDto | null;
};
type LeafEvent =
  | { kind: 'user'; key: string; message: TaskMessageDto }
  | { kind: 'thinking'; key: string; message: TaskMessageDto }
  | { kind: 'prose'; key: string; message: TaskMessageDto }
  | { kind: 'turn'; key: string; message: TaskMessageDto }
  | { kind: 'error'; key: string; message: TaskMessageDto }
  | { kind: 'lifecycle'; key: string; message: TaskMessageDto }
  | { kind: 'permissionDecision'; key: string; message: TaskMessageDto };
type Event = LeafEvent | ToolEvent;

function groupAndPair(messages: TaskMessageDto[]): Event[] {
  const out: Event[] = [];
  const resultByCall = new Map<string, TaskMessageDto>();
  for (const m of messages) {
    if (m.type === 'tool_result') {
      const callId = String(parseContent(m.contentJson).callId ?? '');
      if (callId) resultByCall.set(callId, m);
    }
  }
  for (const m of messages) {
    if (m.type === 'session_started') continue;
    if (m.type === 'tool_result') continue;
    if (m.type === 'tool_call') {
      const callId = String(parseContent(m.contentJson).callId ?? '');
      out.push({ kind: 'tool', key: m.id, call: m, result: resultByCall.get(callId) ?? null });
      continue;
    }
    if (m.type === 'text' && m.role === 'user') {
      out.push({ kind: 'user', key: m.id, message: m });
      continue;
    }
    if (m.type === 'text') {
      out.push({ kind: 'prose', key: m.id, message: m });
      continue;
    }
    if (m.type === 'thinking') {
      out.push({ kind: 'thinking', key: m.id, message: m });
      continue;
    }
    if (m.type === 'turn_done') {
      out.push({ kind: 'turn', key: m.id, message: m });
      continue;
    }
    if (m.type === 'error') {
      out.push({ kind: 'error', key: m.id, message: m });
      continue;
    }
    if (m.type === 'permission_decision') {
      out.push({ kind: 'permissionDecision', key: m.id, message: m });
      continue;
    }
    out.push({ kind: 'lifecycle', key: m.id, message: m });
  }
  return out;
}

// ────────────────────────────────────────────────────────────────────
// Dialog stitching — turn events into renderable cards
// ────────────────────────────────────────────────────────────────────

type Card =
  | { kind: 'day'; key: string; iso: string }
  | { kind: 'user'; key: string; message: TaskMessageDto }
  | { kind: 'assistant'; key: string; items: AssistantItem[]; modelName: string;
      tsIso: string; isStreaming: boolean; turn: TaskMessageDto | null }
  | { kind: 'error'; key: string; message: TaskMessageDto }
  | { kind: 'lifecycle'; key: string; message: TaskMessageDto };

type AssistantItem =
  | { kind: 'thinking'; key: string; message: TaskMessageDto }
  | { kind: 'prose'; key: string; message: TaskMessageDto }
  | { kind: 'tool'; key: string; call: TaskMessageDto; result: TaskMessageDto | null };

function buildDialog(events: Event[], modelName: string): Card[] {
  const cards: Card[] = [];
  let currentDay: string | null = null;
  let assistant: Extract<Card, { kind: 'assistant' }> | null = null;

  function flushAssistant() {
    if (assistant) {
      cards.push(assistant);
      assistant = null;
    }
  }
  function maybeDay(iso: string) {
    const day = iso.slice(0, 10); // YYYY-MM-DD bucket
    if (day !== currentDay) {
      currentDay = day;
      cards.push({ kind: 'day', key: `day-${day}`, iso });
    }
  }

  for (const ev of events) {
    if (ev.kind === 'user') {
      flushAssistant();
      maybeDay(ev.message.ts);
      cards.push({ kind: 'user', key: ev.key, message: ev.message });
      continue;
    }
    if (ev.kind === 'thinking' || ev.kind === 'prose' || ev.kind === 'tool') {
      if (!assistant) {
        const ts = ev.kind === 'tool' ? ev.call.ts : ev.message.ts;
        maybeDay(ts);
        assistant = {
          kind: 'assistant',
          key: ev.key,
          items: [],
          modelName,
          tsIso: ts,
          isStreaming: true,
          turn: null,
        };
      }
      assistant.items.push(ev);
      continue;
    }
    if (ev.kind === 'turn') {
      if (assistant) {
        assistant.isStreaming = false;
        assistant.turn = ev.message;
        flushAssistant();
      }
      continue;
    }
    if (ev.kind === 'error') {
      flushAssistant();
      cards.push({ kind: 'error', key: ev.key, message: ev.message });
      continue;
    }
    if (ev.kind === 'lifecycle' || ev.kind === 'permissionDecision') {
      flushAssistant();
      cards.push({ kind: 'lifecycle', key: ev.key, message: ev.message });
      continue;
    }
  }
  flushAssistant();
  return cards;
}

// ────────────────────────────────────────────────────────────────────
// Card components
// ────────────────────────────────────────────────────────────────────

function renderCard(c: Card, onDecide: (callId: string, decision: 'ALLOW' | 'DENY') => void) {
  if (c.kind === 'day') return <DaySeparator key={c.key} iso={c.iso} />;
  if (c.kind === 'user') return <UserCard key={c.key} message={c.message} />;
  if (c.kind === 'assistant') return <AssistantCard key={c.key} card={c} />;
  if (c.kind === 'error') return <ErrorCard key={c.key} message={c.message} />;
  return <LifecycleLine key={c.key} message={c.message} onDecide={onDecide} />;
}

function DaySeparator({ iso }: { iso: string }) {
  const d = new Date(iso);
  const today = new Date();
  const isToday = d.toDateString() === today.toDateString();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const isYesterday = d.toDateString() === yesterday.toDateString();
  const label = isToday ? 'TODAY' : isYesterday ? 'YESTERDAY' : d.toLocaleDateString();
  const tm = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  return (
    <div style={daySepStyle}>
      <span style={daySepLineStyle} />
      <span style={daySepLabelStyle}>{label} · {tm}</span>
      <span style={daySepLineStyle} />
    </div>
  );
}

function UserCard({ message }: { message: TaskMessageDto }) {
  const text = String(parseContent(message.contentJson).text ?? '');
  return (
    <article style={userCardStyle}>
      <header style={cardHeaderStyle}>
        <span style={userAvatarStyle}>Y</span>
        <span style={cardNameStyle}>You</span>
        <span style={cardTsStyle}>{formatTime(message.ts)}</span>
      </header>
      <div style={cardBodyStyle}>
        {paragraphs(text).map((p, i) => (
          <p key={i} style={paraStyle}>{renderInline(p)}</p>
        ))}
      </div>
    </article>
  );
}

function AssistantCard({ card }: { card: Extract<Card, { kind: 'assistant' }> }) {
  return (
    <article style={assistantCardStyle}>
      <header style={cardHeaderStyle}>
        <span style={claudeAvatarStyle}>C</span>
        <span style={cardNameStyle}>Claude</span>
        <span style={modelBadgeStyle}>{card.modelName || 'unknown'}</span>
        {card.isStreaming && <span style={streamingPillStyle}>· streaming</span>}
        <span style={cardTsStyle}>{formatTime(card.tsIso)}</span>
      </header>
      <div style={cardBodyStyle}>
        {card.items.map(item => {
          if (item.kind === 'thinking') {
            return <ThinkingRow key={item.key} message={item.message} />;
          }
          if (item.kind === 'prose') {
            return <ProseRow key={item.key} message={item.message} />;
          }
          return <ToolRow key={item.key} call={item.call} result={item.result} />;
        })}
        {card.turn && <TurnFooter turn={card.turn} />}
      </div>
    </article>
  );
}

function ThinkingRow({ message }: { message: TaskMessageDto }) {
  const summary = String(parseContent(message.contentJson).summary ?? '');
  const [expanded, setExpanded] = useState(false);
  if (!summary) {
    return <div style={thinkingRowStyle}><span style={thinkingGlyphStyle}>›</span> thinking…</div>;
  }
  const overflow = summary.length > 220;
  const shown = overflow && !expanded ? summary.slice(0, 220) + '…' : summary;
  return (
    <div style={thinkingRowStyle}>
      <span style={thinkingGlyphStyle}>›</span> Thought · {shown}
      {overflow && (
        <button
          type="button"
          onClick={() => setExpanded(v => !v)}
          style={linkBtnStyle}
        >
          {expanded ? ' collapse' : ` show ${summary.length - 220} more`}
        </button>
      )}
    </div>
  );
}

function ProseRow({ message }: { message: TaskMessageDto }) {
  const text = String(parseContent(message.contentJson).text ?? '');
  return (
    <div style={proseRowStyle}>
      {paragraphs(text).map((p, i) => (
        <p key={i} style={paraStyle}>{renderInline(p)}</p>
      ))}
    </div>
  );
}

function ToolRow({ call, result }: { call: TaskMessageDto; result: TaskMessageDto | null }) {
  const callContent = parseContent(call.contentJson);
  const toolName = String(callContent.toolName ?? 'tool');
  const argSummary = formatToolArgs(toolName, callContent.input);
  const isStreaming = result == null;
  const resContent = result ? parseContent(result.contentJson) : null;
  const isError = resContent?.isError === true;
  const output = resContent ? formatToolOutput(resContent.output) : '';
  const palette = toolPalette(toolName);
  const [expanded, setExpanded] = useState(false);
  const hasOutput = output.trim().length > 0;
  const overflow = hasOutput && output.length > 600;
  const shown = overflow && !expanded ? output.slice(0, 600) + '\n…' : output;

  return (
    <div style={toolRowStyle}>
      <div style={toolHeadStyle}>
        <span style={{ ...opTagStyle, ...palette }}>{toolLabel(toolName)}</span>
        <span style={toolArgStyle} title={argSummary}>{argSummary || toolName}</span>
        <span style={{ flex: 1 }} />
        {isStreaming
          ? <span style={runningPillStyle}>RUNNING</span>
          : isError
            ? <span style={errPillStyle}>ERR</span>
            : <span style={okPillStyle}>OK</span>}
      </div>
      {hasOutput && (
        <div style={toolOutputStyle}>
          <pre style={preStyle}>{shown}</pre>
          {overflow && (
            <button
              type="button"
              onClick={() => setExpanded(v => !v)}
              style={linkBtnStyle}
            >
              {expanded ? 'collapse' : `· ${output.length - 600} more chars`}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function TurnFooter({ turn }: { turn: TaskMessageDto }) {
  const cost = formatCost(turn.costUsdMilli);
  const dur = formatDuration(turn.durationMs);
  const inTok = formatNum(turn.tokensIn ?? 0);
  const outTok = formatNum(turn.tokensOut ?? 0);
  return (
    <div style={turnFooterStyle}>
      turn done · {dur} · {inTok} → {outTok} tokens · {cost}
    </div>
  );
}

function ErrorCard({ message }: { message: TaskMessageDto }) {
  const text = String(parseContent(message.contentJson).message ?? 'error');
  return (
    <article style={errorCardStyle}>
      <span style={errGlyphStyle}>✕</span>
      <span>{text}</span>
    </article>
  );
}

function LifecycleLine({
  message,
}: {
  message: TaskMessageDto;
  onDecide: (callId: string, decision: 'ALLOW' | 'DENY') => void;
}) {
  const content = parseContent(message.contentJson);
  if (message.type === 'session_ended') {
    const exit = content.exitCode ?? 0;
    const note = content.errorMessage;
    return (
      <div style={lifecycleStyle}>
        ● session ended · exit {String(exit)}{note ? ` · ${String(note)}` : ''}
      </div>
    );
  }
  if (message.type === 'permission_request') {
    const tool = String(content.toolName ?? 'tool');
    const summary = String(content.summary ?? '');
    return (
      <div style={lifecycleStyle}>
        ? permission asked · <strong>{tool}</strong>{summary && ` — ${summary}`}
      </div>
    );
  }
  if (message.type === 'permission_decision') {
    const decision = String(content.decision ?? '');
    return (
      <div style={lifecycleStyle}>
        · permission {decision.toLowerCase()}
      </div>
    );
  }
  return null;
}

function PermissionCard({ permission, onDecide }: {
  permission: PendingPermission;
  onDecide: (callId: string, decision: 'ALLOW' | 'DENY') => void;
}) {
  return (
    <article style={permissionCardStyle}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={permissionTitleStyle}>
          ⚠ Permission needed for <strong>{permission.toolName}</strong>
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
        >Deny</button>
        <button
          type="button"
          onClick={() => onDecide(permission.callId, 'ALLOW')}
          style={allowBtnStyle}
        >Allow</button>
      </div>
    </article>
  );
}

// ────────────────────────────────────────────────────────────────────
// Helpers — duplicated from ConversationPane intentionally so this
// renderer can evolve without churning the terminal view.
// ────────────────────────────────────────────────────────────────────

function parseContent(json: string): Record<string, unknown> {
  try {
    const v = JSON.parse(json);
    return v && typeof v === 'object' ? v as Record<string, unknown> : {};
  }
  catch {
    return {};
  }
}

function formatToolArgs(toolName: string, input: unknown): string {
  if (input == null || typeof input !== 'object') return '';
  const obj = input as Record<string, unknown>;
  switch (toolName) {
    case 'Read':
    case 'Write':
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit': {
      const path = String(obj.file_path ?? obj.notebook_path ?? '');
      const offset = obj.offset != null ? `offset: ${obj.offset}` : null;
      const limit = obj.limit != null ? `limit: ${obj.limit}` : null;
      const extra = [offset, limit].filter(Boolean).join(', ');
      return extra ? `${path} · ${extra}` : path;
    }
    case 'Bash':
      return truncate(String(obj.command ?? ''), 160);
    case 'Grep':
      return `${obj.pattern ?? ''}${obj.path ? ` · ${obj.path}` : ''}`;
    default: {
      const dump = JSON.stringify(obj);
      return truncate(dump, 160);
    }
  }
}

function formatToolOutput(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  try { return JSON.stringify(v, null, 2); }
  catch { return String(v); }
}

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

function formatCost(milli: number | null): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

function formatDuration(ms: number | null): string {
  if (!ms) return '0ms';
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  return `${Math.floor(s / 60)}m ${Math.round(s % 60)}s`;
}

function formatNum(n: number): string {
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function paragraphs(text: string): string[] {
  return text.split(/\n\s*\n/);
}

/** Inline markup pass: backticked `code`, **bold**, and path:line refs. */
function renderInline(text: string): React.ReactNode {
  if (!text) return text;
  const nodes: React.ReactNode[] = [];
  const re = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|((?:[\w./_-]+):(\d+(?:[-:]\d+)?))/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let key = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) nodes.push(text.slice(last, m.index));
    if (m[1]) {
      nodes.push(<code key={key++} style={inlineCodeStyle}>{m[1].slice(1, -1)}</code>);
    }
    else if (m[2]) {
      nodes.push(<strong key={key++}>{m[2].slice(2, -2)}</strong>);
    }
    else if (m[3]) {
      const [path, line] = m[3].split(':');
      nodes.push(
        <span key={key++} style={pathInlineStyle}>
          {path}<span style={lineRefStyle}>:{line}</span>
        </span>);
    }
    last = m.index + m[0].length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

function toolLabel(name: string): string {
  const upper = name.toUpperCase();
  switch (upper) {
    case 'MULTIEDIT':
    case 'NOTEBOOKEDIT':
      return 'EDIT';
    default:
      return upper;
  }
}

function toolPalette(name: string): React.CSSProperties {
  switch (toolLabel(name)) {
    case 'READ':  return { background: '#dbeafe', color: '#1e3a8a' };
    case 'WRITE': return { background: '#dcfce7', color: '#166534' };
    case 'EDIT':  return { background: '#ede9fe', color: '#5b21b6' };
    case 'BASH':  return { background: '#ccfbf1', color: '#115e59' };
    case 'GREP':  return { background: '#e0e7ff', color: '#3730a3' };
    case 'GLOB':  return { background: '#e0e7ff', color: '#3730a3' };
    case 'TODOWRITE':
    case 'PLAN':  return { background: '#fef3c7', color: '#92400e' };
    default:      return { background: '#f1f5f9', color: '#475569' };
  }
}

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

const monoFont = '"SF Mono", "JetBrains Mono", Menlo, Consolas, monospace';

const scrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '16px 18px 12px',
  background: 'var(--bg-elevated)',
  color: 'var(--text-1)',
  fontSize: 13.5,
  lineHeight: 1.55,
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};
const emptyHintStyle: React.CSSProperties = {
  textAlign: 'center',
  padding: '40px 0',
  color: 'var(--text-4)',
  fontStyle: 'italic',
};

const daySepStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10,
  margin: '4px 0',
};
const daySepLineStyle: React.CSSProperties = {
  flex: 1, height: 1, background: 'var(--border)',
};
const daySepLabelStyle: React.CSSProperties = {
  fontSize: 10.5, fontWeight: 700, letterSpacing: '0.08em',
  color: 'var(--text-4)',
};

const cardHeaderStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '10px 14px 8px',
};
const cardNameStyle: React.CSSProperties = {
  fontWeight: 700, color: 'var(--text-1)', fontSize: 13,
};
const modelBadgeStyle: React.CSSProperties = {
  fontSize: 10, fontWeight: 700, letterSpacing: '0.05em',
  padding: '1px 6px',
  background: '#FEF3C7', color: '#92400E',
  borderRadius: 3,
  textTransform: 'uppercase',
};
const streamingPillStyle: React.CSSProperties = {
  fontSize: 10.5, color: 'var(--accent)', fontWeight: 600,
};
const cardTsStyle: React.CSSProperties = {
  marginLeft: 'auto', fontSize: 11, color: 'var(--text-4)',
  fontVariantNumeric: 'tabular-nums',
};
const cardBodyStyle: React.CSSProperties = {
  padding: '0 14px 12px',
  display: 'flex', flexDirection: 'column', gap: 8,
};

const userAvatarStyle: React.CSSProperties = {
  width: 22, height: 22, borderRadius: '50%',
  background: 'linear-gradient(135deg, var(--accent), var(--accent-dark))',
  color: '#fff', fontWeight: 700, fontSize: 11,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  flexShrink: 0,
};
const claudeAvatarStyle: React.CSSProperties = {
  width: 22, height: 22, borderRadius: '50%',
  background: 'linear-gradient(135deg, #d97706, #92400e)',
  color: '#fff', fontWeight: 700, fontSize: 11,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  flexShrink: 0,
};

const userCardStyle: React.CSSProperties = {
  background: 'var(--accent-a7)',
  border: '1px solid var(--accent-a10)',
  borderLeft: '3px solid var(--accent)',
  borderRadius: 8,
};
const assistantCardStyle: React.CSSProperties = {
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 8,
};

const paraStyle: React.CSSProperties = {
  margin: 0, lineHeight: 1.6, color: 'var(--text-1)',
};

const thinkingRowStyle: React.CSSProperties = {
  fontStyle: 'italic',
  color: 'var(--text-3)',
  fontSize: 12.5,
  padding: '4px 10px',
  background: 'var(--bg-elevated)',
  border: '1px dashed var(--border)',
  borderRadius: 4,
};
const thinkingGlyphStyle: React.CSSProperties = {
  color: 'var(--accent)', fontWeight: 700, marginRight: 4, fontStyle: 'normal',
};

const proseRowStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 8,
};

const toolRowStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: 'var(--bg-elevated)',
  overflow: 'hidden',
};
const toolHeadStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '6px 10px',
};
const opTagStyle: React.CSSProperties = {
  display: 'inline-block',
  padding: '1px 7px',
  borderRadius: 3,
  fontSize: 10.5, fontWeight: 700, letterSpacing: 0.4,
  flexShrink: 0,
};
const toolArgStyle: React.CSSProperties = {
  fontFamily: monoFont, fontSize: 12,
  color: 'var(--text-2)',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  minWidth: 0, flex: 1,
};
const runningPillStyle: React.CSSProperties = {
  padding: '1px 7px', borderRadius: 3,
  background: 'var(--accent-a10)', color: 'var(--accent-dark)',
  fontSize: 10, fontWeight: 700, letterSpacing: 0.4,
};
const okPillStyle: React.CSSProperties = {
  padding: '1px 7px', borderRadius: 3,
  background: '#dcfce7', color: '#166534',
  fontSize: 10, fontWeight: 700, letterSpacing: 0.4,
};
const errPillStyle: React.CSSProperties = {
  padding: '1px 7px', borderRadius: 3,
  background: '#fee2e2', color: '#991b1b',
  fontSize: 10, fontWeight: 700, letterSpacing: 0.4,
};
const toolOutputStyle: React.CSSProperties = {
  borderTop: '1px solid var(--border)',
  background: 'var(--bg-card)',
  padding: '6px 10px',
};
const preStyle: React.CSSProperties = {
  margin: 0,
  fontFamily: monoFont,
  fontSize: 11.5,
  lineHeight: 1.55,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  color: 'var(--text-1)',
};

const turnFooterStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-4)', fontStyle: 'italic',
  paddingTop: 4, borderTop: '1px dashed var(--border)', marginTop: 4,
};

const errorCardStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 8,
  padding: '10px 14px',
  background: '#FEF2F2',
  border: '1px solid #FCA5A5',
  borderRadius: 8,
  color: '#991B1B',
};
const errGlyphStyle: React.CSSProperties = { fontWeight: 700 };

const lifecycleStyle: React.CSSProperties = {
  fontSize: 11.5, color: 'var(--text-4)',
  textAlign: 'center', padding: '2px 0',
};

const permissionCardStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 14,
  padding: '12px 14px',
  background: '#FFFBEB',
  border: '1px solid #FCD34D',
  borderRadius: 8,
};
const permissionTitleStyle: React.CSSProperties = {
  color: '#92400E', fontSize: 13, fontWeight: 600,
};
const permissionSummaryStyle: React.CSSProperties = {
  color: '#78350F', fontSize: 12, marginTop: 2,
};
const allowBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: '#10B981', color: '#fff',
  border: 'none', borderRadius: 4,
  fontWeight: 600, cursor: 'pointer',
};
const denyBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: 'transparent', color: '#92400E',
  border: '1px solid #FCD34D', borderRadius: 4,
  cursor: 'pointer',
};

const linkBtnStyle: React.CSSProperties = {
  background: 'transparent', border: 'none',
  color: 'var(--text-3)', fontSize: 11, cursor: 'pointer',
  padding: '2px 0', marginLeft: 4,
};

const inlineCodeStyle: React.CSSProperties = {
  fontFamily: monoFont, fontSize: 12,
  background: 'var(--bg-elevated)', color: 'var(--text-1)',
  padding: '1px 5px', borderRadius: 3,
  border: '1px solid var(--border)',
};
const pathInlineStyle: React.CSSProperties = {
  fontFamily: monoFont, fontSize: 12,
  color: 'var(--accent-dark)',
  background: 'var(--accent-a10)',
  padding: '1px 5px', borderRadius: 3,
};
const lineRefStyle: React.CSSProperties = { color: 'var(--accent)', fontWeight: 600 };
