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
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import type { ThreadMessageDto, WorkUnitTaskDto } from '../types';
import { isShellTool, shellCommand } from './toolDisplay';
import { AskQuestionCard } from './AskQuestionCard';
import { CodexUpdateAction } from './CodexUpdateAction';
import { MarkdownProse } from './MarkdownProse';
import { PermissionCard } from './PermissionCard';
import { formatDuration, ToolOutputBody } from './StructuredConversation';
import { threadModelLabel } from './threadDisplay';
import { useMessageWindow, useScrollAnchoredLoadMore } from './useMessageWindow';

export type PendingPermission = {
  callId: string;
  toolName: string;
  summary: string;
};

type Props = {
  messages: ThreadMessageDto[];
  pendingPermission: PendingPermission | null;
  /** Decide the current call, optionally granting an auto-approval
   *  budget for the same tool name. Raw view ignores the
   *  {@code preApprove} argument and only ever sends Allow/Deny —
   *  pre-approval lives in the Structured view. */
  onDecide: (
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void;
  /** Welcome banner inputs — model + cwd + branch the agent is
   *  running against, so the scrollback opens with the same context
   *  Claude Code prints in a real terminal. */
  banner: {
    model: string;
    cwd: string;
    branch: string | null;
    sessionStartedAtIso: string;
  };
  /** Per-token assistant text the SSE stream has delivered but
   *  /messages doesn't have yet. Rendered as a transient line at the
   *  bottom of the scrollback so the user sees the answer growing
   *  chunk-by-chunk; the parent clears it once the canonical
   *  AssistantText lands. */
  liveText?: string;
  /** Work-unit task sequence in the thread, oldest-seq first. Used to
   *  inject a tmux-styled task-boundary marker at the seam where
   *  ship-&-continue rolled one task into the next. Optional — when
   *  the thread has 0 or 1 tasks no markers render. */
  tasks?: WorkUnitTaskDto[];
};

/** Tools we color-code per the design legend. Anything else falls
 *  through to the neutral default. */
type ToolKind = 'read' | 'write' | 'edit' | 'bash' | 'grep' | 'plan' | 'other';

const TOOL_KIND: Record<string, ToolKind> = {
  Read: 'read',
  Write: 'write',
  Edit: 'edit',
  MultiEdit: 'edit',
  NotebookEdit: 'edit',
  Bash: 'bash',
  Grep: 'grep',
  TodoWrite: 'plan',
  Plan: 'plan',
};

const TOOL_COLOR: Record<ToolKind, string> = {
  read: 'var(--term-read)',
  write: 'var(--term-write)',
  edit: 'var(--term-edit)',
  bash: 'var(--term-bash)',
  grep: 'var(--term-ok)',
  plan: 'var(--term-user)',
  other: 'var(--term-text-muted)',
};

/**
 * GitHub-dark terminal scrollback that mirrors Claude Code's TUI
 * output. Each persisted {@link ThreadMessageDto} renders as one of a
 * handful of block types — user, thinking, tool call, tool result,
 * assistant prose, turn divider, error, lifecycle line — colored per
 * the legend in {@code docs/mockups/design/threads/SUMMARY.md}.
 *
 * <p>Auto-scrolls to bottom on new content unless the user has
 * scrolled away. The composer + status bar live in the host surface so
 * they can frame the scroll area without resizing it.
 */
export function ConversationPane({
  messages, pendingPermission, onDecide, banner, liveText = '', tasks,
}: Props) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickToBottomRef = useRef(true);

  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    stickToBottomRef.current = distFromBottom < 8;
  };

  const scrollToBottom = useCallback(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, []);
  // Sync scroll before paint so a freshly-mounted Terminal tab lands
  // on the latest content instead of flashing at the top.
  useLayoutEffect(() => {
    if (!stickToBottomRef.current) return;
    scrollToBottom();
  }, [messages.length, pendingPermission, scrollToBottom]);
  // Re-scroll on the next frame in case async content (markdown
  // reflow, code blocks, images) grew the scrollHeight after layout.
  useEffect(() => {
    if (!stickToBottomRef.current) return;
    const raf = requestAnimationFrame(scrollToBottom);
    return () => cancelAnimationFrame(raf);
  }, [messages.length, pendingPermission, scrollToBottom]);

  // Cap rendered history so a long-running thread doesn't re-run the
  // grouping pipeline and reconcile every block on every poll.
  const window = useMessageWindow(messages);
  const onLoadMore = useScrollAnchoredLoadMore(
    window.loadMore,
    useCallback(() => scrollRef.current, []),
  );
  // Pair tool_call with tool_result so we render the call + its
  // outcome together, the way the mockup groups them.
  const rendered = useMemo(() => groupToolCalls(window.visible), [window.visible]);

  // seq → boundary descriptor for the rollover marker. Drawn inline
  // just before the first message of each task ≥ 2. Empty when the
  // thread has 0 or 1 tasks (no rollover seam to mark).
  const boundaries = useMemo(() => computeTaskBoundaries(tasks ?? []), [tasks]);
  // Memoized so a streaming liveText token doesn't rebuild the block
  // elements — identical element references let React skip
  // reconciling the whole persisted scrollback on every chunk.
  const renderedBlocks = useMemo(
    () => rendered.flatMap(item => renderItemWithBoundary(item, boundaries)),
    [rendered, boundaries],
  );

  return (
    <div style={scrollStyle} ref={scrollRef} onScroll={onScroll}>
      <Banner banner={banner} />
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
      {rendered.length === 0 && (
        <div style={emptyHintStyle}>
          ⏵ waiting for the first turn — send a prompt below to kick off
        </div>
      )}
      {renderedBlocks}
      {liveText.length > 0 && <StreamingBlock text={liveText} />}
      {pendingPermission && (
        <PermissionCard permission={pendingPermission} onDecide={onDecide} />
      )}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────
// Render dispatch
// ────────────────────────────────────────────────────────────────────

/** Tmux-styled rollover marker between two consecutive tasks. Mirrors
 *  the chat-bubble TaskBoundaryDivider in StructuredConversation, but
 *  rendered in the terminal's green-on-dark palette so it reads as
 *  part of the scrollback rather than a chat element. */
function TaskBoundaryLine({ from, to }: { from: WorkUnitTaskDto; to: WorkUnitTaskDto }) {
  const fromBranch = from.branchName ? `branch ${from.branchName}` : `task ${from.seq}`;
  const toBranch = to.branchName ? ` on ${to.branchName}` : '';
  const prPart = from.prNumber != null
      ? ` · PR #${from.prNumber} ${stateForLabel(from.prState)}`
      : '';
  return (
    <div style={taskBoundaryRowStyle} aria-label="task rollover marker">
      <span style={taskBoundaryRuleStyle} />
      <span style={taskBoundaryLabelStyle}>
        ━ ✓ Shipped Task {from.seq}{prPart} · started Task {to.seq}{toBranch} ━
      </span>
      <span style={taskBoundaryRuleStyle} />
    </div>
  );
}

/** "Merged" / "open" / "draft" → short phrase the marker prefixes
 *  with so the user reads `PR #123 merged` rather than the raw enum. */
function stateForLabel(prState: string | null | undefined): string {
  if (!prState) return 'open';
  switch (prState.toLowerCase()) {
    case 'merged':   return 'merged';
    case 'closed':   return 'closed';
    case 'draft':    return 'draft';
    default:         return 'open';
  }
}

function computeTaskBoundaries(
    tasks: WorkUnitTaskDto[]): Map<number, { from: WorkUnitTaskDto; to: WorkUnitTaskDto }> {
  const out = new Map<number, { from: WorkUnitTaskDto; to: WorkUnitTaskDto }>();
  if (tasks.length < 2) {
    return out;
  }
  // Sort by seq ascending so consecutive pairs line up. Each `to`
  // task with a known firstMsgSeq pegs the marker just before its
  // first message in the scrollback.
  const sorted = [...tasks].sort((a, b) => a.seq - b.seq);
  for (let i = 1; i < sorted.length; i++) {
    const from = sorted[i - 1];
    const to = sorted[i];
    const seq = (to as WorkUnitTaskDto & { firstMsgSeq?: number | null }).firstMsgSeq;
    if (seq != null) {
      out.set(seq, { from, to });
    }
  }
  return out;
}

/** Returns the seq of the first message a render item carries — used
 *  by the boundary loop to decide where to inject the rollover marker.
 *  Tool calls report their call's seq (the result lives later but the
 *  group renders at the call's slot). */
function seqOf(item: RenderItem): number {
  if (item.kind === 'tool') {
    return item.call.seq;
  }
  return item.message.seq;
}

function renderItemWithBoundary(
    item: RenderItem,
    boundaries: Map<number, { from: WorkUnitTaskDto; to: WorkUnitTaskDto }>): ReactElement[] {
  const out: ReactElement[] = [];
  const boundary = boundaries.get(seqOf(item));
  if (boundary) {
    out.push(
      <TaskBoundaryLine
        key={`boundary-${boundary.to.id}`}
        from={boundary.from}
        to={boundary.to}
      />,
    );
  }
  out.push(renderItem(item));
  return out;
}

function renderItem(item: RenderItem): ReactElement {
  if (item.kind === 'lifecycle') {
    return <LifecycleLine key={item.key} message={item.message} />;
  }
  if (item.kind === 'user') {
    return <UserBlock key={item.key} message={item.message} />;
  }
  if (item.kind === 'thinking') {
    return <ThinkingBlock key={item.key} message={item.message} />;
  }
  if (item.kind === 'prose') {
    return <ProseBlock key={item.key} message={item.message} />;
  }
  if (item.kind === 'tool') {
    return <ToolBlock key={item.key} call={item.call} result={item.result} />;
  }
  if (item.kind === 'turn') {
    return <TurnDivider key={item.key} message={item.message} />;
  }
  if (item.kind === 'error') {
    return <ErrorBlock key={item.key} message={item.message} />;
  }
  if (item.kind === 'permissionDecision') {
    return <PermissionDecisionLine key={item.key} message={item.message} />;
  }
  if (item.kind === 'autoAllow') {
    return <AutoAllowLine key={item.key} message={item.message} />;
  }
  return <UnknownLine key={item.key} message={item.message} />;
}

// ────────────────────────────────────────────────────────────────────
// Block components
// ────────────────────────────────────────────────────────────────────

function Banner({ banner }: { banner: Props['banner'] }) {
  const startedAt = formatTime(banner.sessionStartedAtIso);
  return (
    <div style={bannerStyle}>
      <span style={bannerNameStyle}>claude-code</span>{' '}
      <span style={bannerVerStyle}>stream-json</span> · session started {startedAt} ·
      model <span style={bannerModStyle}>{threadModelLabel(banner.model)}</span> · cwd{' '}
      <span style={bannerCwdStyle}>{banner.cwd}</span>
      {banner.branch && (
        <> · branch <span style={bannerCwdStyle}>{banner.branch}</span></>
      )}
      <br />
      <span style={dimStyle}>
        Type your prompt below. Press <Kbd>↵</Kbd> to send (<Kbd>⇧</Kbd>+<Kbd>↵</Kbd>{' '}
        for a newline), <Kbd>Ctrl</Kbd>+<Kbd>C</Kbd> via the Cancel button to interrupt.
      </span>
    </div>
  );
}

function UserBlock({ message }: { message: ThreadMessageDto }) {
  const text = String(parseContent(message.contentJson).text ?? '');
  return (
    <div style={userBlockStyle} data-seq={message.seq}>
      <span style={userGlyphStyle}>⏺ </span>
      {renderInline(text)}
    </div>
  );
}

function ThinkingBlock({ message }: { message: ThreadMessageDto }) {
  const summary = String(parseContent(message.contentJson).summary ?? '');
  const [expanded, setExpanded] = useState(false);
  if (!summary) {
    return (
      <div style={thinkingStyle}>
        <span style={thinkingGlyphStyle}>✦ </span>thinking…
      </div>
    );
  }
  const truncated = summary.length > 220 && !expanded ? summary.slice(0, 220) + '…' : summary;
  return (
    <div style={thinkingStyle}>
      <span style={thinkingGlyphStyle}>✦ </span>
      {truncated}
      {summary.length > 220 && (
        <button
          type="button"
          onClick={() => setExpanded(v => !v)}
          style={collapseBtnStyle}
        >
          {expanded ? ' collapse' : ` show ${summary.length - 220} more`}
        </button>
      )}
    </div>
  );
}

/** Live assistant text driven by AssistantTextDelta SSE events.
 *  The parent clears liveText once the assembled AssistantText
 *  lands in /messages, so this block flips to the persisted
 *  ProseBlock without a visible flicker. */
function StreamingBlock({ text }: { text: string }) {
  return (
    <div style={proseStyle}>
      <MarkdownProse text={text} variant="terminal" />
      <span style={streamCursorStyle} aria-hidden />
    </div>
  );
}

function ProseBlock({ message }: { message: ThreadMessageDto }) {
  const text = String(parseContent(message.contentJson).text ?? '');
  return (
    <div style={proseStyle}>
      <MarkdownProse text={text} variant="terminal" />
    </div>
  );
}

function ToolBlock({ call, result }: { call: ThreadMessageDto; result: ThreadMessageDto | null }) {
  const callContent = parseContent(call.contentJson);
  const toolName = String(callContent.toolName ?? 'tool');
  if (toolName === 'AskUserQuestion') {
    return <AskQuestionCard input={callContent.input} variant="terminal" />;
  }
  const inputJson = callContent.input;
  const kind = TOOL_KIND[toolName] ?? 'other';
  const color = TOOL_COLOR[kind];
  const argSummary = formatToolArgs(toolName, inputJson);
  const isStreaming = result == null;

  return (
    <div style={toolBlockStyle}>
      <div style={{
        ...toolLineStyle,
        borderLeftColor: color,
        background: isStreaming ? 'var(--term-user-bg)' : 'var(--term-tool-bg)',
      }}>
        <span style={toolGlyphStyle}>{isStreaming ? '▶' : '⎿'}</span>
        <span style={{ ...toolNameStyle, color }}>{toolName}</span>
        {argSummary && <span style={toolArgsStyle}>({argSummary})</span>}
        {isStreaming && <span style={cursorStyle} />}
      </div>
      {result && <ToolResult result={result} toolName={toolName} />}
    </div>
  );
}

function ToolResult({ result, toolName }: { result: ThreadMessageDto; toolName: string }) {
  const content = parseContent(result.contentJson);
  const isError = content.isError === true;
  const output = formatToolOutput(content.output);
  const [expanded, setExpanded] = useState(false);
  const truncated = output.length > 600 && !expanded ? output.slice(0, 600) + '\n…' : output;

  // No output → just a one-line "ok" or "error" tag, like the mockup
  // shows for write/edit successes ("Edited · +2 / −1 lines").
  if (!output.trim()) {
    return (
      <div style={toolResultLineStyle}>
        <span style={isError ? errorGlyphStyle : okGlyphStyle}>{isError ? '✕' : '✓'}</span>
        {isError ? 'error' : 'ok'}
      </div>
    );
  }
  return (
    <div style={toolResultBlockStyle}>
      <div style={toolResultHeadStyle}>
        <span style={isError ? errorGlyphStyle : okGlyphStyle}>{isError ? '✕' : '✓'}</span>
        {isError ? 'error' : 'output'}
        {output.length > 600 && (
          <button
            type="button"
            onClick={() => setExpanded(v => !v)}
            style={collapseBtnStyle}
          >
            {expanded ? ' collapse' : ` · ${output.length - 600} more chars`}
          </button>
        )}
      </div>
      <div style={toolResultBodyStyle}>
        <ToolOutputBody toolName={toolName} text={truncated} isError={isError} />
      </div>
    </div>
  );
}

function TurnDivider({ message }: { message: ThreadMessageDto }) {
  const cost = formatCost(message.costUsdMilli);
  const dur = formatDuration(message.durationMs);
  const tokensIn = formatNum(message.tokensIn ?? 0);
  const tokensOut = formatNum(message.tokensOut ?? 0);
  return (
    <div style={turnDividerStyle}>
      <span style={turnDividerLabelStyle}>
        — turn done · {dur} · {tokensIn} → {tokensOut} tokens · {cost} —
      </span>
    </div>
  );
}

function ErrorBlock({ message }: { message: ThreadMessageDto }) {
  const text = String(parseContent(message.contentJson).message ?? 'error');
  return (
    <div style={errorBlockStyle}>
      <span style={errorGlyphStyle}>✕ </span>
      <span>{text}</span>
      <CodexUpdateAction message={text} />
    </div>
  );
}

function LifecycleLine({ message }: { message: ThreadMessageDto }) {
  const content = parseContent(message.contentJson);
  if (message.type === 'session_started') {
    return null; // Banner already covers this.
  }
  if (message.type === 'session_ended') {
    const exit = content.exitCode ?? 0;
    const note = content.errorMessage;
    return (
      <div style={lifecycleStyle}>
        <span style={dimStyle}>● session ended · exit {String(exit)}{note ? ` · ${String(note)}` : ''}</span>
      </div>
    );
  }
  if (message.type === 'permission_request') {
    const tool = String(content.toolName ?? 'tool');
    const summary = String(content.summary ?? '');
    return (
      <div style={lifecycleStyle}>
        <span style={warnGlyphStyle}>? </span>
        permission asked · <strong style={boldStyle}>{tool}</strong>
        {summary && <span style={dimStyle}> — {summary}</span>}
      </div>
    );
  }
  return <UnknownLine message={message} />;
}

function PermissionDecisionLine({ message }: { message: ThreadMessageDto }) {
  const decision = String(parseContent(message.contentJson).decision ?? '');
  return (
    <div style={lifecycleStyle}>
      <span style={dimStyle}>· permission {decision.toLowerCase()}</span>
    </div>
  );
}

function UnknownLine({ message }: { message: ThreadMessageDto }) {
  return (
    <div style={lifecycleStyle}>
      <span style={dimStyle}>· {message.role}/{message.type}</span>
    </div>
  );
}

function AutoAllowLine({ message }: { message: ThreadMessageDto }) {
  const c = parseContent(message.contentJson);
  const toolName = String(c.toolName ?? 'tool');
  const remaining = typeof c.remaining === 'number' ? c.remaining : 0;
  const label = remaining < 0
    ? `auto-approved · always for ${toolName}`
    : `auto-approved · ${remaining} left for ${toolName}`;
  return (
    <div style={autoAllowLineStyle}>
      <span style={autoAllowGlyphStyle}>✓</span>{label}
    </div>
  );
}

function Kbd({ children }: { children: React.ReactNode }) {
  return <span style={kbdStyle}>{children}</span>;
}

// ────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────

type RenderItem =
  | { kind: 'lifecycle'; key: string; message: ThreadMessageDto }
  | { kind: 'user'; key: string; message: ThreadMessageDto }
  | { kind: 'thinking'; key: string; message: ThreadMessageDto }
  | { kind: 'prose'; key: string; message: ThreadMessageDto }
  | { kind: 'tool'; key: string; call: ThreadMessageDto; result: ThreadMessageDto | null }
  | { kind: 'turn'; key: string; message: ThreadMessageDto }
  | { kind: 'error'; key: string; message: ThreadMessageDto }
  | { kind: 'permissionDecision'; key: string; message: ThreadMessageDto }
  | { kind: 'autoAllow'; key: string; message: ThreadMessageDto }
  | { kind: 'unknown'; key: string; message: ThreadMessageDto };

function groupToolCalls(messages: ThreadMessageDto[]): RenderItem[] {
  const out: RenderItem[] = [];
  // Index tool_results by callId so when we hit the matching call we
  // can pair them. Walk forward; each call consumes its result if
  // present further along in the stream, otherwise renders as the
  // streaming "in-flight" variant.
  const resultByCall = new Map<string, ThreadMessageDto>();
  for (const m of messages) {
    if (m.type === 'tool_result') {
      const callId = parseContent(m.contentJson).callId;
      if (typeof callId === 'string') resultByCall.set(callId, m);
    }
  }

  for (const m of messages) {
    const key = m.id;
    if (m.type === 'session_started' || m.type === 'session_ended' || m.type === 'permission_request') {
      out.push({ kind: 'lifecycle', key, message: m });
      continue;
    }
    if (m.type === 'permission_decision') {
      out.push({ kind: 'permissionDecision', key, message: m });
      continue;
    }
    if (m.type === 'permission_auto_allowed') {
      out.push({ kind: 'autoAllow', key, message: m });
      continue;
    }
    if (m.type === 'turn_done') {
      out.push({ kind: 'turn', key, message: m });
      continue;
    }
    if (m.type === 'error') {
      out.push({ kind: 'error', key, message: m });
      continue;
    }
    if (m.type === 'thinking') {
      out.push({ kind: 'thinking', key, message: m });
      continue;
    }
    if (m.type === 'tool_call') {
      const callId = String(parseContent(m.contentJson).callId ?? '');
      out.push({ kind: 'tool', key, call: m, result: resultByCall.get(callId) ?? null });
      continue;
    }
    if (m.type === 'tool_result') {
      // Skip — already paired with its call above.
      continue;
    }
    if (m.type === 'text') {
      if (m.role === 'user') {
        out.push({ kind: 'user', key, message: m });
      }
      else {
        out.push({ kind: 'prose', key, message: m });
      }
      continue;
    }
    out.push({ kind: 'unknown', key, message: m });
  }
  return out;
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

/** Best-effort one-liner summary of a tool's input. Pulls the most
 *  identifying field for each common tool; falls back to a truncated
 *  JSON dump for everything else. */
function formatToolArgs(toolName: string, input: unknown): string {
  if (input == null || typeof input !== 'object') return '';
  if (isShellTool(toolName)) return shellCommand(input);
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
    case 'Grep':
      return `${obj.pattern ?? ''}${obj.path ? ` · ${obj.path}` : ''}`;
    default: {
      const dump = JSON.stringify(obj);
      return truncate(dump, 140);
    }
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

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
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

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Inline markup pass: highlight backticked `code`, *italics*, and
 *  paths-with-line-refs (e.g. {@code src/foo.ts:42}). Deliberately
 *  tiny — we don't ship a markdown parser for v1. */
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
      nodes.push(<strong key={key++} style={boldStyle}>{m[2].slice(2, -2)}</strong>);
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

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

const monoFont = 'var(--font-mono)';

// All theme-sensitive colors are CSS custom properties set by the host.
// ConversationPane just reads them via var(--term-*).

const scrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  // Right padding is slim — just the overlay scrollbar's width.
  // The floating ConvIndex rail occupies the rightmost ~30px so
  // text gets to use the wider column.
  padding: '16px 8px 4px 22px',
  background: 'var(--term-bg)',
  color: 'var(--term-text)',
  fontFamily: monoFont,
  fontSize: 13,
  lineHeight: 1.65,
};
const emptyHintStyle: React.CSSProperties = {
  color: 'var(--term-text-dim)', textAlign: 'center', padding: '40px 0',
};

/* Task-boundary marker rendered between two consecutive tasks in
 * terminal mode. tmux-styled rule + a centred label in the terminal
 * green so it reads as part of the scrollback, not a chat element. */
const taskBoundaryRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  margin: '14px 0 12px',
  fontFamily: monoFont,
  fontSize: 12,
  color: 'var(--term-ok)',
  letterSpacing: '0.02em',
};
const taskBoundaryRuleStyle: React.CSSProperties = {
  flex: 1,
  height: 1,
  background: 'var(--term-text-dim)',
  opacity: 0.45,
};
const taskBoundaryLabelStyle: React.CSSProperties = {
  flexShrink: 0,
  whiteSpace: 'nowrap',
};
const loadMoreRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
  padding: '4px 0 10px',
  fontFamily: 'inherit',
};
const loadMoreBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'var(--term-bg-elev1)',
  border: '1px solid var(--term-border)',
  borderRadius: 999,
  color: 'var(--term-text-bright)',
  fontFamily: 'inherit', fontSize: 11, fontWeight: 600,
  cursor: 'pointer',
};
const loadMoreHintStyle: React.CSSProperties = {
  fontSize: 10.5, color: 'var(--term-text-dim)',
};
const bannerStyle: React.CSSProperties = {
  borderBottom: '1px dashed var(--term-border)',
  paddingBottom: 10,
  marginBottom: 12,
  fontSize: 11.5,
  color: 'var(--term-text-dim)',
  lineHeight: 1.7,
};
const bannerNameStyle: React.CSSProperties = { color: 'var(--term-text-bright)', fontWeight: 700 };
const bannerVerStyle: React.CSSProperties = { color: 'var(--term-user)' };
const bannerModStyle: React.CSSProperties = { color: 'var(--term-banner-mod)' };
const bannerCwdStyle: React.CSSProperties = { color: 'var(--term-banner-cwd)' };
const dimStyle: React.CSSProperties = { color: 'var(--term-text-dim)' };
const boldStyle: React.CSSProperties = { color: 'var(--term-text-bright)', fontWeight: 700 };
const kbdStyle: React.CSSProperties = {
  background: 'var(--term-kbd-bg)', border: '1px solid var(--term-kbd-border)',
  padding: '0 5px', borderRadius: 3, color: 'var(--term-text)',
  fontSize: 9.5, margin: '0 1px',
};

const userBlockStyle: React.CSSProperties = {
  background: 'var(--term-user-bg)',
  borderLeft: '3px solid var(--term-user)',
  borderRadius: '0 6px 6px 0',
  padding: '8px 14px',
  margin: '12px 0',
  color: 'var(--term-text-bright)',
  // Preserve newlines the user typed in the reply — without
  // pre-wrap the default whitespace handling collapses every \n to
  // a single space, so a multi-line prompt renders on one line.
  whiteSpace: 'pre-wrap',
};
const userGlyphStyle: React.CSSProperties = {
  color: 'var(--term-user)', fontWeight: 700, marginRight: 2,
};

const thinkingStyle: React.CSSProperties = {
  color: 'var(--term-text-dim)',
  fontStyle: 'italic',
  padding: '4px 0',
  margin: '6px 0',
  fontSize: 12.5,
};
const thinkingGlyphStyle: React.CSSProperties = {
  color: 'var(--term-user)', fontStyle: 'normal', marginRight: 2,
};

const proseStyle: React.CSSProperties = {
  color: 'var(--term-text)', padding: '6px 0', margin: '10px 0',
};
const streamCursorStyle: React.CSSProperties = {
  display: 'inline-block',
  width: 7, height: 14,
  marginLeft: 2,
  verticalAlign: 'text-bottom',
  background: 'var(--term-user)',
  animation: 'bytequay-stream-cursor-blink 1s steps(2) infinite',
};
const proseParaStyle: React.CSSProperties = {
  margin: '0 0 10px', lineHeight: 1.7,
};

const toolBlockStyle: React.CSSProperties = { margin: '10px 0' };
const toolLineStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline', gap: 6,
  padding: '5px 10px',
  borderLeft: '3px solid transparent',
  borderRadius: '0 4px 4px 0',
};
const toolGlyphStyle: React.CSSProperties = { color: 'var(--term-text-dim)', flexShrink: 0 };
const toolNameStyle: React.CSSProperties = {
  fontWeight: 700, textTransform: 'lowercase', fontSize: 12.5,
};
const toolArgsStyle: React.CSSProperties = {
  color: 'var(--term-path)',
  flex: 1, minWidth: 0,
  overflowWrap: 'anywhere',
  whiteSpace: 'normal',
  lineHeight: 1.45,
};
const toolResultLineStyle: React.CSSProperties = {
  color: 'var(--term-text-muted)',
  padding: '3px 10px 5px 32px',
  fontSize: 12.5,
  display: 'flex', alignItems: 'center', gap: 6,
};
const toolResultBlockStyle: React.CSSProperties = {
  margin: '4px 10px 6px 32px',
  background: 'var(--term-bg-result)',
  border: '1px solid var(--term-border)',
  borderRadius: 6,
  overflow: 'hidden',
  fontSize: 11.5,
};
const toolResultHeadStyle: React.CSSProperties = {
  padding: '5px 12px',
  background: 'var(--term-bg-result-head)',
  borderBottom: '1px solid var(--term-border)',
  display: 'flex', alignItems: 'center', gap: 6,
  color: 'var(--term-text-muted)', fontSize: 11,
};
const toolResultBodyStyle = {
  padding: 8,
  background: 'var(--term-bg-result)',
  '--text-1': 'var(--term-text)',
  '--text-3': 'var(--term-text-muted)',
  '--text-4': 'var(--term-text-dim)',
  '--bg-elevated': 'var(--term-bg-result)',
  '--border-hairline': 'var(--term-border)',
  '--accent-dark': 'var(--term-path)',
  '--tool-error-fg': 'var(--term-err)',
  '--tool-error-bg': 'var(--term-error-bg)',
  '--tool-error-border': 'var(--term-err)',
  '--tool-warn-fg': 'var(--term-warn)',
  '--tool-success-fg': 'var(--term-ok)',
  '--tool-diff-add-fg': '#7ee787',
  '--tool-diff-add-bg': 'rgba(46, 160, 67, 0.18)',
  '--tool-diff-del-fg': '#ffa198',
  '--tool-diff-del-bg': 'rgba(248, 81, 73, 0.18)',
  '--tool-code-keyword': '#a78bfa',
  '--tool-code-string': '#7ee787',
  '--tool-code-comment': 'var(--term-text-dim)',
  '--tool-code-number': '#f2cc60',
  '--tool-code-annotation': 'var(--term-read)',
} as React.CSSProperties;
const okGlyphStyle: React.CSSProperties = { color: 'var(--term-ok)', fontWeight: 700, marginRight: 4 };
const errorGlyphStyle: React.CSSProperties = { color: 'var(--term-err)', fontWeight: 700, marginRight: 4 };
const warnGlyphStyle: React.CSSProperties = { color: 'var(--term-warn)', fontWeight: 700, marginRight: 4 };

const inlineCodeStyle: React.CSSProperties = {
  color: 'var(--term-pill-fg)',
  background: 'var(--term-pill-bg)',
  border: '1px solid var(--term-pill-border)',
  padding: '1px 5px',
  borderRadius: 3,
  fontSize: 12,
  fontFamily: monoFont,
};
const pathInlineStyle: React.CSSProperties = {
  color: 'var(--term-path)',
  background: 'var(--term-path-bg)',
  border: '1px solid var(--term-path-border)',
  padding: '1px 5px',
  borderRadius: 3,
  fontSize: 12,
  fontFamily: monoFont,
};
const lineRefStyle: React.CSSProperties = { color: 'var(--term-user)', fontWeight: 600 };

const collapseBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: 'var(--term-text-dim)',
  fontFamily: monoFont,
  fontSize: 11,
  cursor: 'pointer',
  padding: 0,
};
const cursorStyle: React.CSSProperties = {
  display: 'inline-block',
  width: 8,
  height: 14,
  background: 'var(--term-cursor)',
  marginLeft: 2,
  verticalAlign: 'text-bottom',
  animation: 'bytequay-blink 1s steps(2) infinite',
};

const turnDividerStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  padding: '8px 0', margin: '14px -8px',
  borderTop: '1px dashed var(--term-border)',
};
const turnDividerLabelStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--term-text-dim)', fontStyle: 'italic',
  background: 'var(--term-bg)', padding: '0 12px', marginTop: -16,
};

const errorBlockStyle: React.CSSProperties = {
  color: 'var(--term-err)',
  background: 'var(--term-error-bg)',
  border: '1px solid var(--term-permission-border)',
  borderRadius: 4,
  padding: '6px 10px',
  margin: '8px 0',
};

const lifecycleStyle: React.CSSProperties = {
  color: 'var(--term-text-muted)', fontSize: 12, padding: '2px 0',
};

const autoAllowLineStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6,
  margin: '4px 0', padding: '2px 8px',
  background: 'rgba(16, 185, 129, 0.12)',
  border: '1px dashed rgba(16, 185, 129, 0.55)',
  borderRadius: 4,
  color: 'var(--term-ok, #34d399)',
  fontSize: 11.5, fontWeight: 600, letterSpacing: 0.2,
};
const autoAllowGlyphStyle: React.CSSProperties = {
  fontWeight: 800, fontSize: 12,
};
