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
import { useEffect, useMemo, useRef } from 'react';
import type { ThreadMessageDto } from '../types';

type Props = {
  /** Messages already filtered to this task's slice
   *  ({@code task_id = :task}). */
  messages: ThreadMessageDto[];
  /** Friendly task label for the "forked from the thread" sub-line
   *  and the assistant-block "Claude · Task n" header. */
  taskSeq: number | null;
  /** Branch the task was cut from — surfaces in the forked-from-thread
   *  badge as "off main / off jack/feat-x". */
  baseBranch: string | null;
  /** Initials displayed on the user avatar (right-side bubbles). */
  userInitials: string;
  /** When true, a thinking-pulse card renders under the scrollback so
   *  the user has a visible "the agent is working" cue while the CLI
   *  subprocess spawns + spins up. */
  isInFlight?: boolean;
};

/**
 * Rich task-detail conversation per
 * docs/mockups/design/tasks/task-detail-conversation.png — green user
 * bubbles right-aligned with initials avatar, white Claude blocks
 * left-aligned with a "C" avatar and "Claude · Task n · <time>"
 * header, italic thinking lines, and full tool-call cards (Read /
 * Write / Edit / Bash) with path + line counts. The "forked from the
 * thread" badge is pinned at the top of the scrollback.
 */
export default function TaskChat({
  messages, taskSeq, baseBranch, userInitials, isInFlight = false,
}: Props) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (el !== null) el.scrollTop = el.scrollHeight;
  }, [messages.length]);

  // Pair tool_call rows with their matching tool_result so the
  // renderer can show the result inline on the card.
  const items = useMemo(
    () => buildItems(messages), [messages]);

  const baseLabel = baseBranch !== null && baseBranch.length > 0
    ? `off ${baseBranch}` : 'off main';

  return (
    <div ref={scrollRef} style={scrollStyle}>
      <div style={forkedRowStyle}>
        <span style={forkedBadgeStyle}>⑂ forked from the thread</span>
        <span style={forkedHintStyle}>seeded with the plan · {baseLabel}</span>
      </div>

      {items.map((item, i) => {
        if (item.kind === 'user') {
          return (
            <UserBubble
              key={item.message.id}
              text={item.text}
              initials={userInitials}
            />
          );
        }
        if (item.kind === 'assistant') {
          return (
            <AssistantBlock
              key={item.message.id}
              text={item.text}
              taskSeq={taskSeq}
              ts={item.ts}
            />
          );
        }
        if (item.kind === 'thinking') {
          return <ThinkingLine key={item.message.id} summary={item.text} ts={item.ts} />;
        }
        if (item.kind === 'tool') {
          return (
            <ToolCard
              key={item.message.id}
              toolName={item.toolName}
              detail={item.detail}
              footer={item.footer}
              isError={item.isError}
              isRunning={item.isRunning}
            />
          );
        }
        return <SystemNote key={`sys-${i}`} text={item.text} />;
      })}
      {isInFlight && (
        <div style={thinkingRowStyle}>
          <div style={claudeAvatarStyle}>C</div>
          <div style={assistantColStyle}>
            <div style={assistantHeaderStyle}>
              <span style={assistantNameStyle}>Claude</span>
              {taskSeq !== null && (
                <span style={assistantMetaStyle}>Task {taskSeq}</span>
              )}
              <span style={assistantMetaStyle}>· thinking</span>
            </div>
            <div style={thinkingBubbleStyle}>
              <span style={thinkingDotsStyle} aria-hidden>
                <span style={{ ...thinkingDotStyle, animationDelay: '0ms' }} />
                <span style={{ ...thinkingDotStyle, animationDelay: '180ms' }} />
                <span style={{ ...thinkingDotStyle, animationDelay: '360ms' }} />
              </span>
              <span style={thinkingTextStyle}>working…</span>
            </div>
          </div>
        </div>
      )}
      <style>{thinkingKeyframes}</style>
    </div>
  );
}

const thinkingKeyframes = `
@keyframes bq-thinking-pulse {
  0%, 80%, 100% { transform: scale(0.4); opacity: 0.4; }
  40%           { transform: scale(1);   opacity: 1; }
}
`;

/* ── Timeline ───────────────────────────────────────────────────────── */

type Item =
  | { kind: 'user'; message: ThreadMessageDto; text: string; ts: number }
  | { kind: 'assistant'; message: ThreadMessageDto; text: string; ts: number }
  | { kind: 'thinking'; message: ThreadMessageDto; text: string; ts: number }
  | {
      kind: 'tool';
      message: ThreadMessageDto;
      toolName: string;
      detail: string;
      footer: string | null;
      isError: boolean;
      isRunning: boolean;
      ts: number;
    }
  | { kind: 'system'; text: string; ts: number };

function buildItems(messages: ThreadMessageDto[]): Item[] {
  // Index tool_result by callId so a tool_call row can resolve its
  // outcome inline (line count, +N -M diff, error flag).
  const resultsByCall = new Map<string, ThreadMessageDto>();
  for (const m of messages) {
    if (m.role === 'tool' && m.type === 'tool_result') {
      const callId = extract(m, 'callId');
      if (typeof callId === 'string') resultsByCall.set(callId, m);
    }
  }
  const out: Item[] = [];
  for (const m of messages) {
    const ts = Date.parse(m.ts);
    if (!Number.isFinite(ts)) continue;
    if (m.role === 'user' && m.type === 'text') {
      out.push({ kind: 'user', message: m, text: extractText(m), ts });
    }
    else if (m.role === 'assistant' && m.type === 'text') {
      // Skip empty assistant rows — an in-flight turn lands in the
      // ledger before the first delta arrives; the live thinking
      // pulse covers that interval, so an empty bubble is noise.
      const text = extractText(m);
      if (text.trim().length === 0) continue;
      out.push({ kind: 'assistant', message: m, text, ts });
    }
    else if (m.role === 'assistant' && m.type === 'thinking') {
      const text = extractText(m);
      if (text.trim().length === 0) continue;
      out.push({ kind: 'thinking', message: m, text, ts });
    }
    else if (m.role === 'tool' && m.type === 'tool_call') {
      const toolName = (extract(m, 'toolName') as string | undefined) ?? 'tool';
      const callId = extract(m, 'callId') as string | undefined;
      const result = callId ? resultsByCall.get(callId) : undefined;
      const { detail, footer, isError } = summariseToolCall(m, result);
      out.push({
        kind: 'tool',
        message: m,
        toolName,
        detail,
        footer,
        isError,
        isRunning: result === undefined,
        ts,
      });
    }
    // tool_result rows handled inline above; lifecycle/system rows
    // are skipped — they belong in terminal mode, not chat mode.
  }
  return out;
}

function summariseToolCall(
  call: ThreadMessageDto,
  result: ThreadMessageDto | undefined,
): { detail: string; footer: string | null; isError: boolean } {
  const input = parseObj(call.contentJson)?.input;
  // Best-effort prettify per tool name. Conservative — if a key
  // doesn't exist we fall back to a JSON preview so the card still
  // shows what the agent was asked to do.
  const toolName = (extract(call, 'toolName') as string | undefined) ?? '';
  let detail = '';
  if (typeof input === 'object' && input !== null) {
    const inObj = input as Record<string, unknown>;
    if (toolName === 'Read' || toolName === 'Write' || toolName === 'Edit'
        || toolName === 'MultiEdit' || toolName === 'NotebookEdit') {
      detail = String(inObj.file_path ?? inObj.path ?? inObj.notebook_path ?? '');
    }
    else if (toolName === 'Bash') {
      detail = String(inObj.command ?? inObj.cmd ?? '');
    }
    else if (toolName === 'Grep') {
      const pat = inObj.pattern ?? inObj.query ?? '';
      const path = inObj.path ?? '';
      detail = `${pat} ${path}`.trim();
    }
    else {
      try { detail = JSON.stringify(input).slice(0, 120); }
      catch { detail = ''; }
    }
  }

  let footer: string | null = null;
  let isError = false;
  if (result !== undefined) {
    const parsed = parseObj(result.contentJson);
    isError = parsed?.isError === true;
    const out = parsed?.output;
    if (typeof out === 'string') {
      const lines = out.split('\n').length;
      if (toolName === 'Read' && lines > 1) footer = `${lines} lines`;
      else if (toolName === 'Write' && lines > 1) footer = `${lines} lines`;
      else if (toolName === 'Edit' || toolName === 'MultiEdit') {
        const adds = (out.match(/^\+/gm) ?? []).length;
        const dels = (out.match(/^-/gm) ?? []).length;
        if (adds > 0 || dels > 0) footer = `+${adds} -${dels}`;
      }
      else if (toolName === 'Bash') {
        const trimmed = out.trim();
        if (trimmed.length > 0) footer = trimmed.split('\n')[0].slice(0, 80);
      }
    }
  }
  return { detail, footer, isError };
}

function extract(m: ThreadMessageDto, key: string): unknown {
  return parseObj(m.contentJson)?.[key];
}

function parseObj(json: string): Record<string, unknown> | null {
  try { return JSON.parse(json) as Record<string, unknown>; }
  catch { return null; }
}

function extractText(m: ThreadMessageDto): string {
  const parsed = parseObj(m.contentJson);
  if (parsed === null) return m.contentJson;
  if (typeof parsed.text === 'string') return parsed.text;
  if (typeof parsed.summary === 'string') return parsed.summary;
  return m.contentJson;
}

/* ── Subcomponents ──────────────────────────────────────────────────── */

function UserBubble({ text, initials }: { text: string; initials: string }) {
  return (
    <div style={userRowStyle}>
      <div style={userBubbleStyle}>{renderInline(text)}</div>
      <div style={userAvatarStyle}>{initials}</div>
    </div>
  );
}

function AssistantBlock({
  text, taskSeq, ts,
}: {
  text: string;
  taskSeq: number | null;
  ts: number;
}) {
  return (
    <div style={assistantRowStyle}>
      <div style={claudeAvatarStyle}>C</div>
      <div style={assistantColStyle}>
        <div style={assistantHeaderStyle}>
          <span style={assistantNameStyle}>Claude</span>
          {taskSeq !== null && (
            <span style={assistantMetaStyle}>Task {taskSeq}</span>
          )}
          <span style={assistantMetaStyle}>· {relativeTime(ts)}</span>
        </div>
        <div style={assistantBlockStyle}>{renderMarkdown(text)}</div>
      </div>
    </div>
  );
}

function ThinkingLine({ summary, ts }: { summary: string; ts: number }) {
  const secs = Math.max(1, Math.floor((Date.now() - ts) / 1000));
  return (
    <div style={thinkingLineStyle}>
      <span style={thinkingInlineDotStyle}>○</span>
      <span>thinking {secs >= 60 ? `${Math.floor(secs / 60)}m` : `${secs}s`} — {summary || 'working'}</span>
    </div>
  );
}

function ToolCard({
  toolName, detail, footer, isError, isRunning,
}: {
  toolName: string;
  detail: string;
  footer: string | null;
  isError: boolean;
  isRunning: boolean;
}) {
  return (
    <div style={toolRowStyle}>
      <div style={toolBadgeStyle(toolName, isError)}>{toolName}</div>
      <div style={toolDetailStyle} title={detail}>
        {isRunning && <span style={runningDotStyle}>● Running · </span>}
        {detail || '—'}
      </div>
      {footer !== null && <div style={toolFooterStyle}>{footer}</div>}
    </div>
  );
}

function SystemNote({ text }: { text: string }) {
  return <div style={systemNoteStyle}>{text}</div>;
}

/* ── Light-weight markdown / inline rendering ──────────────────────── */

/** Renders a string with `inline code` spans highlighted. Keeps the
 *  rest as plain text so we don't pull in a full markdown lib for the
 *  chat-card surface. */
function renderInline(text: string): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  const re = /`([^`]+)`/g;
  let i = 0;
  let m: RegExpExecArray | null;
  let key = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > i) out.push(text.slice(i, m.index));
    out.push(<code key={key++} style={inlineCodeStyle}>{m[1]}</code>);
    i = re.lastIndex;
  }
  if (i < text.length) out.push(text.slice(i));
  return out;
}

/** A tiny markdown subset for assistant blocks: paragraphs split on
 *  blank lines, "- " bullets become a list, **bold** stays bold,
 *  `code` spans render inline. */
function renderMarkdown(text: string): React.ReactNode {
  const blocks = text.split(/\n\s*\n/);
  return blocks.map((block, bi) => {
    const lines = block.split('\n');
    const bullets = lines.every(l => l.trimStart().startsWith('- ') || l.trim().length === 0)
        && lines.some(l => l.trimStart().startsWith('- '));
    if (bullets) {
      return (
        <ul key={bi} style={bulletListStyle}>
          {lines
            .filter(l => l.trimStart().startsWith('- '))
            .map((l, li) => (
              <li key={li} style={bulletItemStyle}>
                {renderBoldInline(l.trimStart().slice(2))}
              </li>
            ))}
        </ul>
      );
    }
    return (
      <p key={bi} style={paragraphStyle}>{renderBoldInline(block)}</p>
    );
  });
}

function renderBoldInline(text: string): React.ReactNode[] {
  // Pass 1: split on **bold**
  const parts: { text: string; bold: boolean }[] = [];
  const re = /\*\*([^*]+)\*\*/g;
  let i = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > i) parts.push({ text: text.slice(i, m.index), bold: false });
    parts.push({ text: m[1], bold: true });
    i = re.lastIndex;
  }
  if (i < text.length) parts.push({ text: text.slice(i), bold: false });
  // Pass 2: render each segment with inline-code substitution
  return parts.map((p, pi) =>
    p.bold
      ? <strong key={pi}>{renderInline(p.text)}</strong>
      : <span key={pi}>{renderInline(p.text)}</span>);
}

function relativeTime(ts: number): string {
  const diffMs = Date.now() - ts;
  if (diffMs < 60_000) return 'now';
  const mins = Math.floor(diffMs / 60_000);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

/* ── Styles ────────────────────────────────────────────────────────── */

const TEAL = '#0d9488';
const TEAL_BG = 'rgba(13, 148, 136, 0.10)';
const TEAL_BORDER = 'rgba(13, 148, 136, 0.32)';

const scrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '18px 22px',
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};

const forkedRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 10,
  marginBottom: 4,
};

const forkedBadgeStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '4px 12px',
  background: TEAL_BG,
  color: TEAL,
  fontWeight: 600,
  fontSize: 11,
  borderRadius: 999,
  border: `1px solid ${TEAL_BORDER}`,
};

const forkedHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};

const userRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  alignItems: 'flex-start',
  gap: 10,
};

const userBubbleStyle: React.CSSProperties = {
  maxWidth: '72%',
  padding: '10px 14px',
  background: '#22c55e',
  color: '#fff',
  borderRadius: 14,
  borderTopRightRadius: 4,
  fontSize: 13,
  lineHeight: 1.55,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
  boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
};

const userAvatarStyle: React.CSSProperties = {
  width: 28,
  height: 28,
  borderRadius: 999,
  background: 'linear-gradient(135deg, #34d399, #10b981)',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 11,
  fontWeight: 700,
  letterSpacing: '0.02em',
  flexShrink: 0,
};

const assistantRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
};

const thinkingRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  paddingTop: 4,
};

const thinkingBubbleStyle: React.CSSProperties = {
  maxWidth: '60%',
  padding: '10px 14px',
  background: 'linear-gradient(180deg, rgba(234, 88, 12, 0.10), rgba(234, 88, 12, 0.04))',
  border: '1px solid rgba(234, 88, 12, 0.30)',
  borderRadius: 14,
  borderTopLeftRadius: 4,
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const thinkingDotsStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
};

const thinkingDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  background: '#ea580c',
  borderRadius: 999,
  display: 'inline-block',
  animation: 'bq-thinking-pulse 1.2s ease-in-out infinite',
};

const thinkingTextStyle: React.CSSProperties = {
  fontSize: 12,
  color: '#9a3412',
  fontStyle: 'italic',
};

const claudeAvatarStyle: React.CSSProperties = {
  width: 28,
  height: 28,
  borderRadius: 999,
  background: '#ea580c',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 13,
  fontWeight: 700,
  flexShrink: 0,
};

const assistantColStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  minWidth: 0,
};

const assistantHeaderStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  alignItems: 'baseline',
};

const assistantNameStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 700,
  color: '#ea580c',
};

const assistantMetaStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
};

const assistantBlockStyle: React.CSSProperties = {
  maxWidth: '92%',
  color: 'var(--text-1)',
  fontSize: 13,
  lineHeight: 1.6,
};

const paragraphStyle: React.CSSProperties = {
  margin: '4px 0',
};

const bulletListStyle: React.CSSProperties = {
  margin: '4px 0 4px 18px',
  padding: 0,
};

const bulletItemStyle: React.CSSProperties = {
  margin: '2px 0',
};

const inlineCodeStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: '0.9em',
  padding: '1px 5px',
  background: 'rgba(0,0,0,0.05)',
  borderRadius: 4,
  color: '#9a3412',
};

const thinkingLineStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  paddingLeft: 38,
  fontSize: 11,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};

const thinkingInlineDotStyle: React.CSSProperties = {
  fontSize: 10,
};

const toolRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  paddingLeft: 38,
  paddingRight: 4,
  paddingTop: 6,
  paddingBottom: 6,
  background: 'rgba(255, 247, 237, 0.55)',
  border: '1px solid rgba(234, 88, 12, 0.12)',
  borderRadius: 10,
};

function toolBadgeStyle(toolName: string, isError: boolean): React.CSSProperties {
  const color = isError ? '#dc2626' : toolColorFor(toolName);
  return {
    fontSize: 11,
    fontWeight: 700,
    padding: '3px 10px',
    borderRadius: 6,
    background: color,
    color: '#fff',
    letterSpacing: '0.01em',
    flexShrink: 0,
  };
}

function toolColorFor(toolName: string): string {
  if (toolName === 'Read' || toolName === 'Write' || toolName === 'Edit'
      || toolName === 'MultiEdit' || toolName === 'NotebookEdit') {
    return '#ea580c';
  }
  if (toolName === 'Bash') return '#0d9488';
  if (toolName === 'Grep') return '#2563eb';
  return '#475569';
}

const toolDetailStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 12,
  color: 'var(--text-2)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const runningDotStyle: React.CSSProperties = {
  color: '#22c55e',
  fontWeight: 700,
  marginRight: 4,
};

const toolFooterStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  flexShrink: 0,
};

const systemNoteStyle: React.CSSProperties = {
  alignSelf: 'center',
  fontSize: 10,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};
