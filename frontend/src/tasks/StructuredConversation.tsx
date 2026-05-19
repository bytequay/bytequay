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
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { TaskMessageDto } from '../types';
import { AskQuestionCard } from './AskQuestionCard';
import type { PendingPermission } from './ConversationPane';
import { MarkdownProse } from './MarkdownProse';
import { PermissionCard, type PermissionDecideHandler } from './PermissionCard';
import { useMessageWindow, useScrollAnchoredLoadMore } from './useMessageWindow';

type Props = {
  messages: TaskMessageDto[];
  pendingPermission: PendingPermission | null;
  onDecide: PermissionDecideHandler;
  modelName: string;
  /** Per-token text the SSE stream has delivered but the persisted
   *  log doesn't have yet. Rendered as a transient "streaming" card
   *  at the end of the conversation so the user sees the answer
   *  growing chunk-by-chunk; cleared by the parent once the canonical
   *  assistant message lands. Empty string when nothing is in flight. */
  liveText?: string;
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
  messages, pendingPermission, onDecide, modelName, liveText = '',
}: Props) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true);

  // Find the nearest ancestor that actually scrolls. In the
  // Conversation tab, StructuredConversation is wrapped by a parent
  // <div style={historyScrollStyle}> with overflow:auto — that's the
  // real scroller, not our own root div (which has overflow:auto in
  // its style but no height constraint because its parent isn't a
  // flex container, so it grows past the parent's bounds instead of
  // scrolling internally). Walk up so the stick-to-bottom logic
  // works in either layout.
  const findScroller = useCallback((from: HTMLElement | null): HTMLElement | null => {
    let n: HTMLElement | null = from;
    while (n) {
      if (n.scrollHeight - n.clientHeight > 1) return n;
      n = n.parentElement;
    }
    return from;
  }, []);
  const scrollToBottom = useCallback(() => {
    const el = findScroller(scrollRef.current);
    if (el) el.scrollTop = el.scrollHeight;
  }, [findScroller]);
  // Listen on the real scroller — onScroll on our own div doesn't
  // fire when the outer ancestor is the actual overflow container.
  useLayoutEffect(() => {
    const el = findScroller(scrollRef.current);
    if (!el) return;
    const handler = () => {
      const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
      stickRef.current = distFromBottom < 24;
    };
    el.addEventListener('scroll', handler, { passive: true });
    return () => el.removeEventListener('scroll', handler);
  }, [findScroller]);
  // Sync scroll before paint so a freshly-mounted Conversation tab
  // lands on the latest content instead of flashing at the top.
  // liveText.length is in the dep array so each streamed chunk pins
  // the view to the bottom while the answer grows.
  useLayoutEffect(() => {
    if (!stickRef.current) return;
    scrollToBottom();
  }, [messages.length, pendingPermission, scrollToBottom, liveText.length]);
  // Re-scroll on the next frame in case async content (markdown
  // reflow, code blocks, images) grew the scrollHeight after the
  // layout pass settled.
  useEffect(() => {
    if (!stickRef.current) return;
    const raf = requestAnimationFrame(scrollToBottom);
    return () => cancelAnimationFrame(raf);
  }, [messages.length, pendingPermission, scrollToBottom, liveText.length]);

  // Cap rendered history so a multi-hour task with thousands of
  // messages doesn't re-run the grouping pipeline and re-reconcile
  // every card on every 1s poll.
  const window = useMessageWindow(messages);
  const onLoadMore = useScrollAnchoredLoadMore(
    window.loadMore,
    useCallback(() => findScroller(scrollRef.current), [findScroller]),
  );
  const events = useMemo(() => groupAndPair(window.visible), [window.visible]);
  const cards = useMemo(() => buildDialog(events, modelName), [events, modelName]);

  return (
    <div style={scrollStyle} ref={scrollRef}>
      {window.hasMore && (
        <div style={loadMoreRowStyle}>
          <button type="button" onClick={onLoadMore} style={loadMoreBtnStyle}>
            ↑ Load {window.nextChunk} older message{window.nextChunk === 1 ? '' : 's'}
          </button>
          <span style={loadMoreHintStyle}>
            showing latest {window.visible.length} of {window.total}
          </span>
        </div>
      )}
      {cards.length === 0 && (
        <div style={emptyHintStyle}>
          Waiting for the first turn — send a prompt below to kick off.
        </div>
      )}
      {cards.map(c => renderCard(c, onDecide))}
      {liveText.length > 0 && <StreamingCard text={liveText} modelName={modelName} />}
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
  | { kind: 'permissionDecision'; key: string; message: TaskMessageDto }
  | { kind: 'autoAllow'; key: string; message: TaskMessageDto };
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
    if (m.type === 'permission_auto_allowed') {
      out.push({ kind: 'autoAllow', key: m.id, message: m });
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
  | { kind: 'tool'; key: string; call: TaskMessageDto; result: TaskMessageDto | null }
  | { kind: 'autoAllow'; key: string; message: TaskMessageDto };

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
    if (ev.kind === 'thinking' || ev.kind === 'prose' || ev.kind === 'tool' || ev.kind === 'autoAllow') {
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

function renderCard(c: Card, onDecide: PermissionDecideHandler) {
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
          if (item.kind === 'autoAllow') {
            return <AutoAllowRow key={item.key} message={item.message} />;
          }
          return <ToolRow key={item.key} call={item.call} result={item.result} />;
        })}
        {card.turn && <TurnFooter turn={card.turn} />}
      </div>
    </article>
  );
}

/** Live assistant card driven by AssistantTextDelta SSE events.
 *  Distinct from the persisted AssistantCard so the streaming
 *  affordances (purple streaming pill, blinking cursor) are obvious
 *  and we don't have to mutate the canonical messages array. Parent
 *  clears liveText once the assembled AssistantText lands in
 *  /messages, at which point this card unmounts and the persisted
 *  one takes its place. */
function StreamingCard({ text, modelName }: { text: string; modelName: string }) {
  return (
    <article style={assistantCardStyle}>
      <header style={cardHeaderStyle}>
        <span style={claudeAvatarStyle}>C</span>
        <span style={cardNameStyle}>Claude</span>
        <span style={modelBadgeStyle}>{modelName || 'unknown'}</span>
        <span style={streamingPillStyle}>· streaming</span>
      </header>
      <div style={cardBodyStyle}>
        <div style={proseRowStyle}>
          <MarkdownProse text={text} variant="card" />
          <span style={streamCursorStyle} aria-hidden />
        </div>
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
      <MarkdownProse text={text} variant="card" />
    </div>
  );
}

function AutoAllowRow({ message }: { message: TaskMessageDto }) {
  const c = parseContent(message.contentJson);
  const toolName = String(c.toolName ?? 'tool');
  const remaining = typeof c.remaining === 'number' ? c.remaining : 0;
  const label = remaining < 0
    ? `auto-approved · always for ${toolName}`
    : `auto-approved · ${remaining} left for ${toolName}`;
  return (
    <div style={autoAllowRowStyle}>
      <span style={autoAllowDotStyle}>✓</span>
      <span>{label}</span>
    </div>
  );
}

function ToolRow({ call, result }: { call: TaskMessageDto; result: TaskMessageDto | null }) {
  const callContent = parseContent(call.contentJson);
  const toolName = String(callContent.toolName ?? 'tool');
  if (toolName === 'AskUserQuestion') {
    return <AskQuestionCard input={callContent.input} variant="card" />;
  }
  const argSummary = formatToolArgs(toolName, callContent.input);
  const isStreaming = result == null;
  const resContent = result ? parseContent(result.contentJson) : null;
  const isError = resContent?.isError === true;
  const output = resContent ? formatToolOutput(resContent.output) : '';
  const palette = toolPalette(toolName);
  const [expanded, setExpanded] = useState(false);
  const hasOutput = output.trim().length > 0;
  // Truncate by lines rather than characters so the collapsed view
  // never cuts mid-line of a file dump or a multi-column listing.
  // ~24 lines fits the "give me a sense, click to see the rest"
  // sweet spot — small enough to keep the conversation scrollable,
  // big enough to be informative.
  const COLLAPSED_LINES = 24;
  const lines = useMemo(() => output.split('\n'), [output]);
  const overflow = hasOutput && lines.length > COLLAPSED_LINES;
  const shown = overflow && !expanded
    ? lines.slice(0, COLLAPSED_LINES).join('\n')
    : output;
  const hiddenLineCount = overflow ? lines.length - COLLAPSED_LINES : 0;

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
          <ToolOutputBody toolName={toolName} text={shown} isError={isError} />
          {overflow && (
            <button
              type="button"
              onClick={() => setExpanded(v => !v)}
              style={linkBtnStyle}
            >
              {expanded ? '· collapse' : `· show ${hiddenLineCount} more line${hiddenLineCount === 1 ? '' : 's'}`}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Per-tool output renderer. Plain-text output is barely scannable for
 * the user — a file dump from Read, a list of paths from Bash, or a
 * diff from Edit all read as one undifferentiated mono block in the
 * fallback {@code <pre>}. This dispatches to specialised renderers
 * that pull structure out of each tool's output:
 *
 *  • Read: line-number gutter parsed from {@code cat -n} format.
 *  • Bash / Glob: per-line classification — file paths in accent,
 *    error/warning patterns in red/amber, plain output in default.
 *  • Edit / Write / MultiEdit: diff coloring on +/- lines.
 *  • Grep: split {@code file:line:match} so the path stands out.
 *
 *  Anything else falls back to the original mono <pre>.
 */
function ToolOutputBody({ toolName, text, isError }: { toolName: string; text: string; isError: boolean }) {
  // Errors override per-tool formatting — a tool whose result came
  // back with isError=true is almost always plain stderr / an
  // exception message; surface that in the error palette so the user
  // doesn't miss it.
  if (isError) {
    return <pre style={preErrorStyle}>{text}</pre>;
  }
  if (toolName === 'Read') {
    return <ReadOutput text={text} />;
  }
  if (toolName === 'Edit' || toolName === 'MultiEdit' || toolName === 'Write' || toolName === 'NotebookEdit') {
    return <EditOutput text={text} />;
  }
  if (toolName === 'Grep') {
    return <GrepOutput text={text} />;
  }
  if (toolName === 'Glob') {
    return <PathListOutput text={text} />;
  }
  if (toolName === 'Bash') {
    return <BashOutput text={text} />;
  }
  return <pre style={preStyle}>{text}</pre>;
}

/** Read tool output arrives in {@code cat -n} form: lines like
 *  {@code "   123\tcontent"} or {@code "   123→content"}. Render
 *  the line numbers in a right-aligned dim gutter and the content
 *  in its own column so the eye can scan the file structure
 *  without parsing the prefix on every row. Falls back to the
 *  plain <pre> for any line that doesn't match the pattern, so an
 *  unusual response shape (an error message, a binary refusal)
 *  still renders intact. */
function ReadOutput({ text }: { text: string }) {
  const rows = useMemo(() => {
    const lineRe = /^\s*(\d+)(?:\t|→)(.*)$/;
    return text.split('\n').map((raw, idx) => {
      const m = lineRe.exec(raw);
      if (m) return { kind: 'numbered' as const, key: idx, num: m[1], content: m[2] };
      return { kind: 'plain' as const, key: idx, content: raw };
    });
  }, [text]);
  // Width of the gutter is computed from the largest line number so
  // a 4-digit file doesn't push 1-digit rows out of alignment.
  const gutterCh = useMemo(() => {
    let max = 1;
    for (const r of rows) {
      if (r.kind === 'numbered' && r.num.length > max) max = r.num.length;
    }
    return max;
  }, [rows]);
  return (
    <div style={readBlockStyle}>
      {rows.map(r => r.kind === 'numbered' ? (
        <div key={r.key} style={readRowStyle}>
          <span style={{ ...readGutterStyle, minWidth: `${gutterCh}ch` }}>{r.num}</span>
          <span style={readContentStyle}>{r.content || ' '}</span>
        </div>
      ) : (
        <div key={r.key} style={readPlainStyle}>{r.content || ' '}</div>
      ))}
    </div>
  );
}

/** Bash output is freeform but a few patterns are common enough to
 *  recognise: lines that are just a file path, lines containing
 *  errors/warnings, lines starting with +/- (e.g., a git diff piped
 *  through). Color them so the eye lands on the meaningful row
 *  immediately instead of grepping the whole block.  */
function BashOutput({ text }: { text: string }) {
  return <LineColoredOutput text={text} />;
}

/** Edit / Write / MultiEdit responses often include patch-style
 *  before/after snippets. Color +/− lines green/red and leave the
 *  header text in normal weight so the diff stands apart from the
 *  surrounding prose. */
function EditOutput({ text }: { text: string }) {
  return <LineColoredOutput text={text} />;
}

/** Grep prints rows like {@code path/to/file.ts:42:match text}. Pull
 *  the path and line number out into their own colored spans so the
 *  user can pick a hit by scanning the leftmost column instead of
 *  parsing every row. */
function GrepOutput({ text }: { text: string }) {
  const grepRe = /^([^:\n]+):(\d+):(.*)$/;
  const lines = text.split('\n');
  return (
    <div style={readBlockStyle}>
      {lines.map((line, i) => {
        const m = grepRe.exec(line);
        if (m) {
          return (
            <div key={i} style={readRowStyle}>
              <span style={grepPathStyle}>{m[1]}</span>
              <span style={grepSepStyle}>:</span>
              <span style={grepLineStyle}>{m[2]}</span>
              <span style={grepSepStyle}>:</span>
              <span style={readContentStyle}>{m[3] || ' '}</span>
            </div>
          );
        }
        return <div key={i} style={readPlainStyle}>{line || ' '}</div>;
      })}
    </div>
  );
}

/** Glob output: one path per line. Render each as a styled path
 *  chip so a long list reads as a directory of hits rather than a
 *  wall of slash-separated identifiers. */
function PathListOutput({ text }: { text: string }) {
  const lines = text.split('\n');
  return (
    <div style={readBlockStyle}>
      {lines.map((line, i) => (
        <div key={i} style={readPlainStyle}>
          {line.trim().length > 0
            ? <span style={pathLineStyle}>{line}</span>
            : ' '}
        </div>
      ))}
    </div>
  );
}

/** Shared line-classifier used by Bash + Edit renderers. Picks a
 *  palette per line from a small pattern set and renders each line
 *  on its own row so the colored slabs make the structure of the
 *  output obvious at a glance. Conservative — anything we don't
 *  recognise falls through to the default text color, so we never
 *  miscolour real output. */
function LineColoredOutput({ text }: { text: string }) {
  // Order matters: more-specific patterns first so e.g. a "+++ file"
  // diff-header doesn't get coloured as a plain "+" addition.
  const lines = text.split('\n');
  return (
    <div style={readBlockStyle}>
      {lines.map((line, i) => {
        const cls = classifyLine(line);
        const style = lineClassStyle[cls];
        return (
          <div key={i} style={{ ...readPlainStyle, ...style }}>
            {line || ' '}
          </div>
        );
      })}
    </div>
  );
}

type LineClass = 'diffHeader' | 'diffAdd' | 'diffDel' | 'error' | 'warn' | 'path' | 'success' | 'plain';

function classifyLine(line: string): LineClass {
  if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@')) return 'diffHeader';
  if (line.startsWith('+')) return 'diffAdd';
  if (line.startsWith('-')) return 'diffDel';
  if (/(?:^|[\s:])(?:error|exception|fatal|fail(?:ed|ure)?)\b/i.test(line)) return 'error';
  if (/(?:^|[\s:])(?:warning|warn)\b/i.test(line)) return 'warn';
  // "Found N files" / "X tests passed" — a success-summary line.
  if (/^(?:Found \d+|✓|✔|OK[: ])/i.test(line.trim())) return 'success';
  // A bare path-ish line: starts with a relative or absolute path
  // and has no spaces (so we don't accidentally style a sentence).
  if (/^[./]?[\w./@-]+\.[\w]{1,8}$/.test(line.trim())) return 'path';
  return 'plain';
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
  onDecide: PermissionDecideHandler;
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
  // Blank lines are the canonical paragraph break, but the model often
  // emits numbered/bullet lists either with single newlines (collapsed
  // to a space inside <p>) or fully inline. Treat a list marker that
  // follows a sentence terminator as its own paragraph so "1. … 2. …
  // 3. …" lays out vertically instead of running together.
  return text
    .split(/\n\s*\n|\n(?=\s*(?:\d+\.|[-*•])\s)|(?<=[.!?])[ \t]+(?=\d+\.\s)|(?<=[.!?])[ \t]+(?=[-*•]\s)/g)
    .map(s => s.trim())
    .filter(s => s.length > 0);
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
const loadMoreRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
  padding: '4px 0 10px',
};
const loadMoreBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  color: 'var(--text-2)',
  fontSize: 12, fontWeight: 600,
  cursor: 'pointer',
};
const loadMoreHintStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-4)',
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
const streamCursorStyle: React.CSSProperties = {
  display: 'inline-block',
  width: 7, height: 14,
  marginLeft: 2,
  verticalAlign: 'text-bottom',
  background: 'var(--accent)',
  animation: 'bytequay-stream-cursor-blink 1s steps(2) infinite',
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
const autoAllowRowStyle: React.CSSProperties = {
  display: 'inline-flex', alignSelf: 'flex-start',
  alignItems: 'center', gap: 6,
  padding: '2px 8px',
  background: '#ecfdf5', color: '#047857',
  border: '1px dashed #6ee7b7', borderRadius: 4,
  fontSize: 11, fontWeight: 600, letterSpacing: 0.2,
};
const autoAllowDotStyle: React.CSSProperties = {
  color: '#10b981', fontWeight: 800, fontSize: 12,
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
const preErrorStyle: React.CSSProperties = {
  ...preStyle,
  color: '#991b1b',
  background: '#fef2f2',
  border: '1px solid #fecaca',
  borderRadius: 4,
  padding: '6px 8px',
};

// Read / Grep / Glob / Bash / Edit shared body. Each line is its
// own row so we can colour-classify and still keep selection /
// copy-paste sane (multi-line copy via the renderer's native
// "select across rows" still produces newline-joined text because
// flex children separate naturally).
const readBlockStyle: React.CSSProperties = {
  fontFamily: monoFont,
  fontSize: 11.5,
  lineHeight: 1.55,
  color: 'var(--text-1)',
  // Cap height so even an expanded 600-line file dump doesn't
  // dominate the conversation column. Tuned against a typical
  // viewport — beyond ~320 px the user is better served by a
  // scrolled subwindow than by an endless inline block.
  maxHeight: 320,
  overflow: 'auto',
};
const readRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 10,
  alignItems: 'baseline',
  whiteSpace: 'pre',
};
const readGutterStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  textAlign: 'right',
  flexShrink: 0,
  userSelect: 'none',
  // Subtle right divider so the gutter visually separates from
  // content without needing a literal pipe character.
  borderRight: '1px solid var(--border-hairline)',
  paddingRight: 8,
};
const readContentStyle: React.CSSProperties = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  flex: 1,
  minWidth: 0,
};
const readPlainStyle: React.CSSProperties = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};

// Per-line palettes for Bash / Edit output. Backgrounds stay light
// so the row still reads as part of one continuous transcript;
// foreground colours carry the signal.
const lineClassStyle: Record<LineClass, React.CSSProperties> = {
  diffHeader: { color: 'var(--text-3)', fontWeight: 600 },
  diffAdd:    { color: '#166534', background: '#dcfce7' },
  diffDel:    { color: '#991b1b', background: '#fee2e2' },
  error:      { color: '#991b1b' },
  warn:       { color: '#92400e' },
  success:    { color: '#166534', fontWeight: 600 },
  path:       { color: 'var(--accent-dark)' },
  plain:      {},
};

// Inline path styling for Glob output and the file column of Grep.
const pathLineStyle: React.CSSProperties = {
  color: 'var(--accent-dark)',
};
const grepPathStyle: React.CSSProperties = {
  color: 'var(--accent-dark)',
  fontWeight: 500,
  flexShrink: 0,
};
const grepLineStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  flexShrink: 0,
};
const grepSepStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  userSelect: 'none',
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
