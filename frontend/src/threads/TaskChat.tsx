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
import { renderChatMarkdown } from '../markdown';
import { highlightShell } from './shellHighlight';
import { useTypewriter } from './useTypewriter';
import type { ThreadMessageDto } from '../types';

type Props = {
  /** Optional ref forwarded onto the chat's scroll container so the
   *  floating ConvIndex panel mounted by TaskDetailPage can run
   *  scrollIntoView on the data-seq-tagged user bubbles inside. */
  outerRef?: React.MutableRefObject<HTMLDivElement | null>;
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
  /** Live, not-yet-persisted assistant text streamed off the SSE channel.
   *  When non-empty it renders as a growing in-flight bubble (token-by-
   *  token), replacing the "working…" pulse until the durable message
   *  lands and the parent clears it. */
  liveText?: string;
  /** Fired from the Stop button in the working… card. Parent owns
   *  the interrupt RPC + optimistic state. */
  onInterrupt?: () => void;
  /** True while the interrupt request is in flight — flips the
   *  button to "Stopping…" and disables it. */
  interrupting?: boolean;
  /** Whether older messages exist on the server that the parent
   *  hasn't fetched yet. When true a "↑ Load earlier" button renders
   *  at the top of the scrollback. */
  canLoadOlder?: boolean;
  /** Load-older request in flight — disables the button + flips its
   *  label so a double-click can't fire two windows in parallel. */
  loadingOlder?: boolean;
  /** Fired when the user clicks the "Load earlier" button. */
  onLoadOlder?: () => void;
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
  liveText = '', onInterrupt, interrupting = false, outerRef,
  canLoadOlder = false, loadingOlder = false, onLoadOlder,
}: Props) {
  const streaming = liveText.length > 0;
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const assignScrollRef = (el: HTMLDivElement | null) => {
    scrollRef.current = el;
    if (outerRef !== undefined) outerRef.current = el;
  };
  // Pin the stick-to-bottom only when the user is already near the
  // bottom of the scrollback; otherwise a stream-in would yank them
  // away from older content they're reading. The "Load earlier"
  // click paths set the snap ref so the post-prepend layout effect
  // can restore scroll position.
  const beforeLoadRef = useRef<{ height: number; top: number } | null>(null);
  const handleLoadOlder = () => {
    if (loadingOlder || onLoadOlder === undefined) return;
    const el = scrollRef.current;
    if (el !== null) {
      beforeLoadRef.current = { height: el.scrollHeight, top: el.scrollTop };
    }
    onLoadOlder();
  };
  useEffect(() => {
    const snap = beforeLoadRef.current;
    const el = scrollRef.current;
    if (el === null) return;
    if (snap !== null) {
      el.scrollTop = snap.top + (el.scrollHeight - snap.height);
      beforeLoadRef.current = null;
      return;
    }
    // Default stream-in behaviour: stick to the bottom — also as the
    // live streaming bubble grows (deltas don't change messages.length).
    el.scrollTop = el.scrollHeight;
  }, [messages.length, liveText]);

  // Pair tool_call rows with their matching tool_result so the
  // renderer can show the result inline on the card.
  const items = useMemo(
    () => buildItems(messages), [messages]);

  const baseLabel = baseBranch !== null && baseBranch.length > 0
    ? `off ${baseBranch}` : 'off main';

  // A task only carries a "plan" when it was actually seeded with an opening
  // prompt (or has since produced a conversation). A task cut with a blank
  // prompt lands here empty and idle — claiming "seeded with the plan" then is
  // misleading, so say it's waiting for the user's first message instead.
  const hasContent = items.length > 0 || streaming || isInFlight;
  const forkedHint = hasContent
    ? `seeded with the plan · ${baseLabel}`
    : `waiting for your first message · ${baseLabel}`;

  return (
    <div ref={assignScrollRef} style={scrollStyle}>
      <div style={forkedRowStyle}>
        <span style={forkedBadgeStyle}>⑂ forked from the thread</span>
        <span style={forkedHintStyle}>{forkedHint}</span>
      </div>

      {canLoadOlder && (
        <button
          type="button"
          onClick={handleLoadOlder}
          disabled={loadingOlder}
          style={loadOlderBtnStyle}
        >
          {loadingOlder ? 'Loading earlier…' : '↑ Load earlier messages'}
        </button>
      )}

      {items.map((item, i) => {
        if (item.kind === 'user') {
          return (
            <UserBubble
              key={item.message.id}
              text={item.text}
              initials={userInitials}
              seq={item.message.seq}
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
      {streaming && <StreamingAssistantBlock text={liveText} taskSeq={taskSeq} />}
      {isInFlight && !streaming && (
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
              {onInterrupt !== undefined && (
                <button
                  type="button"
                  onClick={onInterrupt}
                  disabled={interrupting}
                  style={thinkingStopBtnStyle}
                  title="Stop the in-progress agent turn"
                >
                  {interrupting ? 'Stopping…' : '⊘ Stop'}
                </button>
              )}
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
@keyframes bq-stream-cursor {
  0%, 45% { opacity: 1; }
  50%, 95% { opacity: 0; }
  100% { opacity: 1; }
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
    else if (toolName === 'AskUserQuestion') {
      // Show the question itself, not the raw schema JSON.
      const qs = inObj.questions;
      if (Array.isArray(qs) && qs.length > 0) {
        const first = qs[0];
        const text = first !== null && typeof first === 'object'
          ? String((first as Record<string, unknown>).question ?? '')
          : '';
        detail = qs.length > 1 ? `${text} (+${qs.length - 1} more)` : text;
      }
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

function UserBubble({ text, initials, seq }: { text: string; initials: string; seq: number }) {
  return (
    // data-seq lets the floating ConvIndex rail scroll this row into
    // view when the user clicks an index entry.
    <div style={userRowStyle} data-seq={seq}>
      <div
        className="bq-chat-md bq-chat-md--user"
        style={userBubbleStyle}
        dangerouslySetInnerHTML={{ __html: renderChatMarkdown(text) }}
      />
      <div style={userAvatarStyle}>{initials}</div>
    </div>
  );
}

/** Small chevron button to fold/unfold a message card. ▾ open, ▸ folded. */
function CardFoldToggle({ collapsed, onToggle }: { collapsed: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      style={cardFoldToggleStyle}
      onClick={onToggle}
      aria-expanded={!collapsed}
      aria-label={collapsed ? 'Expand message' : 'Collapse message'}
      title={collapsed ? 'Expand' : 'Collapse'}
    >
      {collapsed ? '▸' : '▾'}
    </button>
  );
}

const cardFoldToggleStyle: React.CSSProperties = {
  marginLeft: 'auto',
  flexShrink: 0,
  width: 20,
  height: 20,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 11,
  color: '#475569',
  background: 'rgba(15,23,42,0.05)',
  border: '1px solid rgba(15,23,42,0.08)',
  borderRadius: 6,
  cursor: 'pointer',
};

function AssistantBlock({
  text, taskSeq, ts,
}: {
  text: string;
  taskSeq: number | null;
  ts: number;
}) {
  const [collapsed, setCollapsed] = useState(false);
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
          <CardFoldToggle collapsed={collapsed} onToggle={() => setCollapsed(c => !c)} />
        </div>
        {!collapsed && (
          <div
            className="bq-chat-md"
            style={assistantBlockStyle}
            dangerouslySetInnerHTML={{ __html: renderChatMarkdown(text) }}
          />
        )}
      </div>
    </div>
  );
}

/** The in-flight assistant bubble while text streams in. Mirrors
 *  {@link AssistantBlock} but reads from the live SSE buffer (no durable
 *  message / timestamp yet) and trails a blinking cursor. The parent
 *  clears {@code liveText} once the assembled message lands, at which
 *  point the normal AssistantBlock takes over. */
function StreamingAssistantBlock({ text, taskSeq }: { text: string; taskSeq: number | null }) {
  // Ease the reveal so bursty deltas type in smoothly instead of popping.
  const shown = useTypewriter(text);
  return (
    <div style={assistantRowStyle}>
      <div style={claudeAvatarStyle}>C</div>
      <div style={assistantColStyle}>
        <div style={assistantHeaderStyle}>
          <span style={assistantNameStyle}>Claude</span>
          {taskSeq !== null && (
            <span style={assistantMetaStyle}>Task {taskSeq}</span>
          )}
          <span style={assistantMetaStyle}>· streaming</span>
        </div>
        <div className="bq-chat-md" style={assistantBlockStyle}>
          <span dangerouslySetInnerHTML={{ __html: renderChatMarkdown(shown) }} />
          <span style={streamingCursorStyle} aria-hidden>▍</span>
        </div>
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
  const [collapsed, setCollapsed] = useState(false);
  // Shell commands are multi-line and long; show them as a wrapping,
  // syntax-highlighted block on their real lines instead of one
  // truncated row — and let the whole card fold to just its header.
  if ((toolName === 'Bash' || toolName === 'run_shell') && detail.length > 0) {
    return (
      <div style={toolShellRowStyle}>
        <div style={toolShellHeadStyle}>
          <div style={toolBadgeStyle(toolName, isError)}>{toolName}</div>
          {isRunning && <span style={runningDotStyle}>● Running</span>}
          {footer !== null && <div style={toolFooterStyle}>{footer}</div>}
          <CardFoldToggle collapsed={collapsed} onToggle={() => setCollapsed(c => !c)} />
        </div>
        {!collapsed && <pre style={toolShellCodeStyle}>{highlightShell(detail)}</pre>}
      </div>
    );
  }
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

const loadOlderBtnStyle: React.CSSProperties = {
  alignSelf: 'center',
  margin: '0 auto 4px',
  padding: '4px 12px',
  fontSize: 11,
  border: '1px solid rgba(0,0,0,0.10)',
  background: 'rgba(255,255,255,0.85)',
  color: 'var(--text-2)',
  borderRadius: 999,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const scrollStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  overflowY: 'auto',
  // Never scroll sideways: a wide child should wrap, not push a
  // horizontal scrollbar. (overflowY:auto alone would compute
  // overflowX to auto, which is what let the column scroll left-right.)
  overflowX: 'hidden',
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
  fontSize: 15,
  // line-height comes from .bq-chat-md so the markdown helper can
  // tighten it without fighting an inline style.
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
  // Wider so the Stop button can sit flush right.
  maxWidth: '90%',
  minWidth: 240,
  padding: '10px 14px',
  background: 'linear-gradient(180deg, rgba(234, 88, 12, 0.10), rgba(234, 88, 12, 0.04))',
  border: '1px solid rgba(234, 88, 12, 0.30)',
  borderRadius: 14,
  borderTopLeftRadius: 4,
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const thinkingStopBtnStyle: React.CSSProperties = {
  marginLeft: 'auto',
  padding: '3px 10px',
  fontSize: 11,
  fontWeight: 600,
  border: '1px solid rgba(207, 19, 34, 0.55)',
  background: '#fff',
  color: '#cf1322',
  borderRadius: 6,
  cursor: 'pointer',
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
  minWidth: 0,
  color: 'var(--text-1)',
  fontSize: 15,
  // Long unbreakable tokens (paths, URLs) wrap instead of forcing
  // the column wider.
  overflowWrap: 'anywhere',
  // line-height comes from .bq-chat-md.
};

const streamingCursorStyle: React.CSSProperties = {
  display: 'inline-block',
  marginLeft: 1,
  color: 'var(--text-3)',
  animation: 'bq-stream-cursor 1s step-end infinite',
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
  // Allow the flex item to shrink below its content width so the
  // ellipsis kicks in instead of stretching the row.
  minWidth: 0,
  fontSize: 12,
  color: 'var(--text-2)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

// Multi-line shell-command variant: a header row (badge + status) with
// the full command wrapped below.
const toolShellRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  marginLeft: 38,
  paddingLeft: 0,
  paddingRight: 4,
  paddingTop: 6,
  paddingBottom: 6,
};
const toolShellHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};
const toolShellCodeStyle: React.CSSProperties = {
  margin: 0,
  padding: '8px 10px',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  lineHeight: 1.5,
  color: 'var(--text-1)',
  background: 'rgba(255, 247, 237, 0.6)',
  border: '1px solid rgba(234, 88, 12, 0.14)',
  borderRadius: 10,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
  wordBreak: 'break-word',
  // Very long commands (huge heredocs) get a cap + scroll rather than
  // dominating the transcript.
  maxHeight: 260,
  overflowY: 'auto',
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
