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
import { useCallback, useEffect, useMemo, useRef } from 'react';
import type { ThreadMessageDto } from '../types';
import type { PendingPermission } from './ConversationPane';
import { ToolOutputBody } from './StructuredConversation';

/** Locally re-declared so this file doesn't depend on a private
 *  type alias inside ConversationPane. Matches the inline shape
 *  used by every other tile callsite. */
type PermissionDecideHandler = (
  callId: string,
  decision: 'ALLOW' | 'DENY',
  preApprove?: { toolName: string; count: number },
) => void | Promise<void>;

/**
 * Tile-context conversation renderer for the group page.
 * Where {@code StructuredConversation} is the long-form detail
 * view, this component renders a compact, label-stripped dialog
 * that fits a tile: no per-message author headers, no per-turn
 * footers, small quiet date separators, and a chosen visual
 * mode of either Chat (WeChat-style bubbles) or Terminal
 * (Warp-style monospace pane).
 *
 * <p>Both modes follow the polish notes in
 * {@code docs/mockups/threads-design.md} → "Two visual modes:
 * Chat and Terminal" — same underlying {@code StreamEvent}s,
 * different rendering. Toggled by the parent via {@code mode};
 * persistence + keybinding ({@code ⌘T}) lives in
 * {@code ThreadsPage}.
 */
export type TileConversationMode = 'chat' | 'terminal';

type Props = {
  messages: ThreadMessageDto[];
  pendingPermission: PendingPermission | null;
  onDecide: PermissionDecideHandler;
  mode: TileConversationMode;
};

export function TileConversation({ messages, pendingPermission, onDecide, mode }: Props) {
  // Same stick-to-bottom behaviour as StructuredConversation —
  // tiles need to feel "live" as new turns stream in.
  const rootRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true);
  const findScroller = useCallback((from: HTMLElement | null): HTMLElement | null => {
    let n: HTMLElement | null = from;
    while (n !== null) {
      if (n.scrollHeight - n.clientHeight > 1) return n;
      n = n.parentElement;
    }
    return from;
  }, []);
  const scrollToBottom = useCallback(() => {
    const el = findScroller(rootRef.current);
    if (el !== null) el.scrollTop = el.scrollHeight;
  }, [findScroller]);
  useEffect(() => {
    const el = findScroller(rootRef.current);
    if (el === null) return;
    const onScroll = () => {
      const atBottom = el.scrollHeight - (el.scrollTop + el.clientHeight) < 24;
      stickRef.current = atBottom;
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [findScroller]);
  useEffect(() => {
    if (stickRef.current) scrollToBottom();
  }, [messages, scrollToBottom]);

  const cards = useMemo(() => buildCards(messages), [messages]);

  const containerStyle = mode === 'terminal' ? termContainerStyle : chatContainerStyle;
  return (
    <div ref={rootRef} style={containerStyle}>
      {cards.length === 0 && (
        <div style={mode === 'terminal' ? termEmptyStyle : chatEmptyStyle}>
          Waiting for the first turn…
        </div>
      )}
      {cards.map(c => renderCard(c, mode, onDecide))}
      {pendingPermission !== null && (
        <PermissionPrompt mode={mode} prompt={pendingPermission} onDecide={onDecide} />
      )}
    </div>
  );
}

// ─── Card model ─────────────────────────────────────────────────────

type Card =
  | { kind: 'day'; key: string; iso: string }
  | { kind: 'user'; key: string; message: ThreadMessageDto }
  | { kind: 'assistantProse'; key: string; message: ThreadMessageDto }
  | { kind: 'thinking'; key: string; message: ThreadMessageDto }
  | { kind: 'tool'; key: string; call: ThreadMessageDto; result: ThreadMessageDto | null }
  | { kind: 'error'; key: string; message: ThreadMessageDto };

function buildCards(messages: ThreadMessageDto[]): Card[] {
  const out: Card[] = [];
  const resultByCall = new Map<string, ThreadMessageDto>();
  for (const m of messages) {
    if (m.type === 'tool_result') {
      const callId = String(parseContent(m.contentJson).callId ?? '');
      if (callId) resultByCall.set(callId, m);
    }
  }
  let currentDay: string | null = null;
  function maybeDay(iso: string, anchor: string) {
    const day = iso.slice(0, 10);
    if (day !== currentDay) {
      currentDay = day;
      out.push({ kind: 'day', key: `day-${day}-${anchor}`, iso });
    }
  }
  for (const m of messages) {
    if (m.type === 'session_started') continue;
    if (m.type === 'tool_result') continue;
    if (m.type === 'turn_done') continue; // per-turn footers stripped
    if (m.type === 'permission_decision') continue;
    if (m.type === 'permission_auto_allowed') continue;
    if (m.type === 'session_ended') continue;
    if (m.type === 'tool_call') {
      const callId = String(parseContent(m.contentJson).callId ?? '');
      maybeDay(m.ts, m.id);
      out.push({ kind: 'tool', key: m.id, call: m, result: resultByCall.get(callId) ?? null });
      continue;
    }
    if (m.type === 'text' && m.role === 'user') {
      maybeDay(m.ts, m.id);
      out.push({ kind: 'user', key: m.id, message: m });
      continue;
    }
    if (m.type === 'text') {
      maybeDay(m.ts, m.id);
      out.push({ kind: 'assistantProse', key: m.id, message: m });
      continue;
    }
    if (m.type === 'thinking') {
      maybeDay(m.ts, m.id);
      out.push({ kind: 'thinking', key: m.id, message: m });
      continue;
    }
    if (m.type === 'error') {
      maybeDay(m.ts, m.id);
      out.push({ kind: 'error', key: m.id, message: m });
      continue;
    }
    // Everything else (session lifecycle leftovers) we deliberately
    // skip — the tile view is "messages + dividers" only.
  }
  return out;
}

// ─── Renderers ──────────────────────────────────────────────────────

function renderCard(c: Card, mode: TileConversationMode, onDecide: PermissionDecideHandler) {
  void onDecide; // permission prompt is rendered separately, not from card list
  if (c.kind === 'day') return <DaySeparator key={c.key} iso={c.iso} mode={mode} />;
  if (c.kind === 'user') return <UserBubble key={c.key} message={c.message} mode={mode} />;
  if (c.kind === 'assistantProse') {
    return <AssistantProse key={c.key} message={c.message} mode={mode} />;
  }
  if (c.kind === 'thinking') return <ThinkingRow key={c.key} message={c.message} mode={mode} />;
  if (c.kind === 'tool') return <ToolRow key={c.key} call={c.call} result={c.result} mode={mode} />;
  if (c.kind === 'error') return <ErrorRow key={c.key} message={c.message} mode={mode} />;
  return null;
}

function DaySeparator({ iso, mode }: { iso: string; mode: TileConversationMode }) {
  const d = new Date(iso);
  const today = new Date();
  const isToday = d.toDateString() === today.toDateString();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const isYesterday = d.toDateString() === yesterday.toDateString();
  const label = isToday ? 'Today'
    : isYesterday ? 'Yesterday'
    : d.toLocaleDateString([], { month: 'short', day: 'numeric' });
  const styleSet = mode === 'terminal' ? termDaySepStyle : chatDaySepStyle;
  return (
    <div style={styleSet.row} aria-hidden>
      <span style={styleSet.line} />
      <span style={styleSet.label}>{label}</span>
      <span style={styleSet.line} />
    </div>
  );
}

function UserBubble({ message, mode }: { message: ThreadMessageDto; mode: TileConversationMode }) {
  const text = (parseContent(message.contentJson).text as string) ?? '';
  if (mode === 'terminal') {
    return (
      <div style={termUserRowStyle}>
        <span style={termPromptStyle}>›</span>
        <span style={termUserTextStyle}>{text}</span>
      </div>
    );
  }
  return (
    <div style={chatUserRowStyle}>
      <div style={chatUserBubbleStyle}>
        {paragraphs(text).map((p, i) => (
          <div key={i} style={chatParaStyle}>{renderInline(p)}</div>
        ))}
      </div>
    </div>
  );
}

function AssistantProse({ message, mode }: { message: ThreadMessageDto; mode: TileConversationMode }) {
  const text = (parseContent(message.contentJson).text as string) ?? '';
  if (mode === 'terminal') {
    return (
      <div style={termAssistantRowStyle}>
        {paragraphs(text).map((p, i) => (
          <div key={i} style={termParaStyle}>{renderInline(p)}</div>
        ))}
      </div>
    );
  }
  return (
    <div style={chatAssistantRowStyle}>
      <div style={chatAssistantBubbleStyle}>
        {paragraphs(text).map((p, i) => (
          <div key={i} style={chatParaStyle}>{renderInline(p)}</div>
        ))}
      </div>
    </div>
  );
}

function ThinkingRow({ message, mode }: { message: ThreadMessageDto; mode: TileConversationMode }) {
  const text = (parseContent(message.contentJson).text as string) ?? '';
  const trimmed = text.trim();
  if (trimmed === '') return null;
  if (mode === 'terminal') {
    return (
      <div style={{ ...termAssistantRowStyle, ...termThinkingRowStyle }}>
        <span style={termThinkingLabelStyle}>thinking</span>
        <span style={termThinkingTextStyle}>{trimmed}</span>
      </div>
    );
  }
  return (
    <div style={chatAssistantRowStyle}>
      <div style={{ ...chatAssistantBubbleStyle, ...chatThinkingBubbleStyle }}>
        <span style={chatThinkingLabelStyle}>thinking · </span>
        <span style={chatThinkingTextStyle}>{trimmed}</span>
      </div>
    </div>
  );
}

function ToolRow({
  call, result, mode,
}: {
  call: ThreadMessageDto;
  result: ThreadMessageDto | null;
  mode: TileConversationMode;
}) {
  const content = parseContent(call.contentJson);
  const toolName = String(content.toolName ?? content.name ?? 'tool');
  const args = formatToolArgs(toolName, content.input);
  const barColor = toolBarColor(toolName);
  const label = toolLabel(toolName);
  const resultContent = result === null ? null : parseContent(result.contentJson);
  const resultText = resultContent === null
    ? ''
    : formatToolOutput(resultContent.output ?? resultContent.text ?? resultContent.error ?? '');
  const isError = resultContent?.isError === true || resultContent?.error !== undefined;
  if (mode === 'terminal') {
    return (
      <div style={termAssistantRowStyle}>
        <div style={{ ...termToolRowStyle, boxShadow: `inset 3px 0 0 0 ${barColor}` }}>
          <span style={{ ...termToolTagStyle, color: barColor }}>{label}</span>
          {args !== '' && <span style={termToolArgsStyle}>{args}</span>}
        </div>
        {resultText !== '' && (
          <div style={{
            ...termToolResultStyle,
            color: isError ? '#f87171' : 'rgba(255,255,255,0.7)',
          }}>
            {truncate(resultText, 480)}
          </div>
        )}
      </div>
    );
  }
  return (
    <div style={chatAssistantRowStyle}>
      <div style={{ ...chatToolBubbleStyle, boxShadow: `inset 3px 0 0 0 ${barColor}` }}>
        <div style={chatToolHeadStyle}>
          <span style={{ ...chatToolTagStyle, background: tagBackground(barColor), color: barColor }}>
            {label}
          </span>
          {args !== '' && <span style={chatToolArgsStyle}>{args}</span>}
        </div>
        {resultText !== '' && (
          <div style={{
            ...chatToolResultStyle,
            color: isError ? '#b91c1c' : 'var(--text-2)',
          }}>
            <ToolOutputBody toolName={toolName} text={resultText} isError={isError} />
          </div>
        )}
      </div>
    </div>
  );
}

function ErrorRow({ message, mode }: { message: ThreadMessageDto; mode: TileConversationMode }) {
  const text = (parseContent(message.contentJson).text as string)
    ?? (parseContent(message.contentJson).message as string)
    ?? 'Error';
  if (mode === 'terminal') {
    return (
      <div style={termAssistantRowStyle}>
        <div style={termErrorStyle}>! {text}</div>
      </div>
    );
  }
  return (
    <div style={chatAssistantRowStyle}>
      <div style={chatErrorStyle}>! {text}</div>
    </div>
  );
}

// ─── Permission prompt ──────────────────────────────────────────────

function PermissionPrompt({ prompt, mode, onDecide }: {
  prompt: PendingPermission;
  mode: TileConversationMode;
  onDecide: PermissionDecideHandler;
}) {
  const wrapStyle = mode === 'terminal' ? termPermissionStyle : chatPermissionStyle;
  return (
    <div style={wrapStyle}>
      <div style={permissionTitleStyle}>
        <span>{prompt.toolName}</span>
        <span style={permissionWaitStyle}>awaiting approval</span>
      </div>
      {prompt.summary !== '' && (
        <div style={permissionSummaryStyle}>{prompt.summary}</div>
      )}
      <div style={permissionBtnsStyle}>
        <button
          type="button"
          onClick={() => void onDecide(prompt.callId, 'DENY')}
          style={denyBtnStyle}
        >
          Deny
        </button>
        <button
          type="button"
          onClick={() => void onDecide(prompt.callId, 'ALLOW')}
          style={allowBtnStyle}
        >
          Allow
        </button>
      </div>
    </div>
  );
}

// ─── Tool palette (per design — chat bar colours) ───────────────────

/** 3px inset bar colour per tool, taken from threads-design.md
 *  (Two visual modes section). Differs from StructuredConversation's
 *  pastel palette — those background-tinted pills are for the detail
 *  page; this is a slim coloured accent on a neutral card. */
function toolBarColor(name: string): string {
  switch (toolLabel(name)) {
    case 'READ':  return '#2563eb'; // blue
    case 'WRITE': return '#d97706'; // orange
    case 'EDIT':  return '#ca8a04'; // yellow
    case 'BASH':  return '#dc2626'; // red
    case 'GREP':
    case 'GLOB':  return '#7c3aed'; // purple (search → streaming-adjacent)
    case 'TODOWRITE':
    case 'PLAN':  return '#0891b2'; // cyan
    default:      return '#64748b'; // slate
  }
}

/** Pale background tint matching the bar colour. Used for the tool
 *  tag pill in chat mode so it reads at-a-glance even past the
 *  3px inset stripe. */
function tagBackground(color: string): string {
  const tints: Record<string, string> = {
    '#2563eb': '#dbeafe',
    '#d97706': '#ffedd5',
    '#ca8a04': '#fef9c3',
    '#dc2626': '#fee2e2',
    '#7c3aed': '#ede9fe',
    '#0891b2': '#cffafe',
    '#64748b': '#f1f5f9',
  };
  return tints[color] ?? '#f1f5f9';
}

// ─── Helpers (duplicated from StructuredConversation rather than
//     exported so the detail-page renderer stays independent). ──────

function parseContent(json: string): Record<string, unknown> {
  try {
    const v = JSON.parse(json);
    return v !== null && typeof v === 'object' ? v as Record<string, unknown> : {};
  }
  catch { return {}; }
}

function formatToolArgs(toolName: string, input: unknown): string {
  if (input === null || input === undefined || typeof input !== 'object') return '';
  const obj = input as Record<string, unknown>;
  switch (toolName) {
    case 'Read':
    case 'Write':
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit': {
      const path = String(obj.file_path ?? obj.notebook_path ?? '');
      return path;
    }
    case 'Bash':
    // Codex CLI names its shell tool `command_execution`; without these
    // aliases it fell through to the raw-JSON dump below.
    case 'command_execution':
    case 'shell': {
      const cmd = obj.command;
      return Array.isArray(cmd) ? cmd.map(String).join(' ') : String(cmd ?? '');
    }
    case 'Grep':
      return `${obj.pattern ?? ''}${obj.path !== undefined ? ` · ${obj.path as string}` : ''}`;
    default: {
      const dump = JSON.stringify(obj);
      return truncate(dump, 140);
    }
  }
}

function formatToolOutput(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'string') return v;
  try { return JSON.stringify(v, null, 2); }
  catch { return String(v); }
}

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

function paragraphs(text: string): string[] {
  return text
    .split(/\n\s*\n|\n(?=\s*(?:\d+\.|[-*•])\s)/g)
    .map(s => s.trim())
    .filter(s => s.length > 0);
}

function renderInline(text: string): React.ReactNode {
  if (text === '') return text;
  const nodes: React.ReactNode[] = [];
  const re = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let key = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) nodes.push(text.slice(last, m.index));
    if (m[1] !== undefined) {
      nodes.push(<code key={key++} style={inlineCodeStyle}>{m[1].slice(1, -1)}</code>);
    }
    else if (m[2] !== undefined) {
      nodes.push(<strong key={key++}>{m[2].slice(2, -2)}</strong>);
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
    case 'NOTEBOOKEDIT': return 'EDIT';
    default:             return upper;
  }
}

// ─── Styles — Chat mode (WeChat-style bubbles) ──────────────────────

const monoFont = '"SF Mono", "JetBrains Mono", Menlo, Consolas, monospace';

const chatContainerStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '12px 14px',
  background: 'var(--bg-card)',
};

const chatEmptyStyle: React.CSSProperties = {
  padding: 24,
  color: 'var(--text-3)',
  fontSize: 12,
  textAlign: 'center',
};

const chatUserRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
};
const chatAssistantRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-start',
};

// Both bubbles share the same grammar — all four corners rounded
// 12px, 12px horizontal padding, symmetric vertical padding, capped
// at 80% width so the right-vs-left alignment stays an unambiguous
// signal of who said it.
const bubbleBaseStyle: React.CSSProperties = {
  maxWidth: '80%',
  borderRadius: 12,
  padding: '8px 12px',
  fontSize: 13,
  lineHeight: 1.5,
  wordBreak: 'break-word',
};
const chatUserBubbleStyle: React.CSSProperties = {
  ...bubbleBaseStyle,
  background: '#95EC69',
  color: '#1a1a1a',
};
const chatAssistantBubbleStyle: React.CSSProperties = {
  ...bubbleBaseStyle,
  background: 'var(--bg-elevated)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-hairline)',
};
const chatThinkingBubbleStyle: React.CSSProperties = {
  background: 'rgba(124,92,255,0.04)',
  boxShadow: 'inset 3px 0 0 0 #7c3aed',
  border: 'none',
  fontStyle: 'italic',
  color: 'var(--text-2)',
  fontSize: 12,
};
const chatThinkingLabelStyle: React.CSSProperties = {
  color: '#7c3aed',
  fontWeight: 600,
  fontSize: 10,
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
};
const chatThinkingTextStyle: React.CSSProperties = { color: 'var(--text-2)' };
const chatParaStyle: React.CSSProperties = { marginBottom: 4 };

const chatToolBubbleStyle: React.CSSProperties = {
  ...bubbleBaseStyle,
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border-hairline)',
  padding: '6px 12px',
  fontFamily: monoFont,
  fontSize: 11.5,
  color: 'var(--text-1)',
};
const chatToolHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};
const chatToolTagStyle: React.CSSProperties = {
  padding: '1px 6px',
  borderRadius: 4,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.04em',
};
const chatToolArgsStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflowWrap: 'anywhere',
  whiteSpace: 'normal',
  lineHeight: 1.45,
};
const chatToolResultStyle: React.CSSProperties = {
  marginTop: 4,
  fontSize: 11,
  whiteSpace: 'pre-wrap',
  maxHeight: 120,
  overflow: 'hidden',
};
const chatErrorStyle: React.CSSProperties = {
  ...bubbleBaseStyle,
  background: 'rgba(220,38,38,0.06)',
  border: '1px solid rgba(220,38,38,0.18)',
  color: '#b91c1c',
  fontFamily: monoFont,
  fontSize: 11.5,
  boxShadow: 'inset 3px 0 0 0 #dc2626',
};
const chatPermissionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  margin: '6px 0',
  padding: '10px 12px',
  background: 'rgba(217,119,6,0.06)',
  border: '1px solid rgba(217,119,6,0.2)',
  borderRadius: 8,
  fontSize: 12,
};

const chatDaySepStyle = {
  row: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    margin: '4px 0',
  } as React.CSSProperties,
  line: { flex: 1, height: 1, background: 'var(--border-hairline)' } as React.CSSProperties,
  label: {
    fontSize: 9.5,
    color: 'var(--text-3)',
    textTransform: 'uppercase',
    letterSpacing: '0.08em',
    fontWeight: 600,
  } as React.CSSProperties,
};

// ─── Styles — Terminal mode (Warp / tmux pane) ──────────────────────

const termContainerStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '10px 14px',
  background: '#0d1117',
  color: 'rgba(255,255,255,0.88)',
  fontFamily: monoFont,
  fontSize: 12,
  lineHeight: 1.55,
};

const termEmptyStyle: React.CSSProperties = {
  padding: 24,
  color: 'rgba(255,255,255,0.45)',
  fontSize: 12,
  textAlign: 'center',
};

const termUserRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  padding: '2px 0',
};
const termPromptStyle: React.CSSProperties = {
  color: '#a78bfa',
  fontWeight: 700,
  flexShrink: 0,
};
const termUserTextStyle: React.CSSProperties = {
  whiteSpace: 'pre-wrap',
  flex: 1,
};

const termAssistantRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  padding: '2px 0',
};
const termParaStyle: React.CSSProperties = {
  whiteSpace: 'pre-wrap',
};

const termThinkingRowStyle: React.CSSProperties = {
  color: 'rgba(255,255,255,0.55)',
  fontStyle: 'italic',
};
const termThinkingLabelStyle: React.CSSProperties = {
  color: '#a78bfa',
  marginRight: 6,
};
const termThinkingTextStyle: React.CSSProperties = {};

const termToolRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '2px 8px',
  background: 'rgba(255,255,255,0.03)',
};
const termToolTagStyle: React.CSSProperties = {
  fontWeight: 700,
  fontSize: 10.5,
  letterSpacing: '0.05em',
  flexShrink: 0,
};
const termToolArgsStyle: React.CSSProperties = {
  color: 'rgba(255,255,255,0.75)',
  flex: 1,
  minWidth: 0,
  overflowWrap: 'anywhere',
  whiteSpace: 'normal',
  lineHeight: 1.45,
};
const termToolResultStyle: React.CSSProperties = {
  padding: '2px 8px 2px 18px',
  fontSize: 11.5,
  whiteSpace: 'pre-wrap',
  maxHeight: 120,
  overflow: 'hidden',
};
const termErrorStyle: React.CSSProperties = {
  color: '#f87171',
  padding: '2px 0',
};
const termPermissionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  margin: '6px 0',
  padding: '8px 12px',
  background: 'rgba(217,119,6,0.16)',
  border: '1px solid rgba(217,119,6,0.4)',
  borderRadius: 6,
  fontSize: 12,
  color: '#fbbf24',
};

const termDaySepStyle = {
  row: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    margin: '4px 0',
  } as React.CSSProperties,
  line: { flex: 1, height: 1, background: '#21262d' } as React.CSSProperties,
  label: {
    fontSize: 9.5,
    color: 'rgba(255,255,255,0.5)',
    textTransform: 'uppercase',
    letterSpacing: '0.08em',
    fontWeight: 600,
  } as React.CSSProperties,
};

const inlineCodeStyle: React.CSSProperties = {
  fontFamily: monoFont,
  fontSize: '0.95em',
  padding: '0 4px',
  borderRadius: 3,
  background: 'rgba(127,127,127,0.15)',
};

const permissionTitleStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 8,
  fontWeight: 700,
};
const permissionWaitStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 10,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  color: '#b45309',
};
const permissionSummaryStyle: React.CSSProperties = {
  color: 'inherit',
  whiteSpace: 'pre-wrap',
  fontSize: 11.5,
};
const permissionBtnsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  justifyContent: 'flex-end',
};
const allowBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: '#047857',
  color: '#fff',
  border: 'none',
  borderRadius: 4,
  fontSize: 11.5,
  fontWeight: 600,
  cursor: 'pointer',
};
const denyBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'transparent',
  color: 'inherit',
  border: '1px solid currentColor',
  borderRadius: 4,
  fontSize: 11.5,
  fontWeight: 600,
  cursor: 'pointer',
};
