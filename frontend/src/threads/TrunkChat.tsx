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
import { renderChatMarkdown } from '../markdown';
import type { ThreadDto, ThreadMessageDto, WorkUnitTaskDto } from '../types';
import { assistantLabel, type AssistantLabel } from './assistantLabel';

const SLATE = '#475569';

type Props = {
  /** Optional ref forwarded onto the chat's scroll container so the
   *  floating ConvIndex panel can call scrollIntoView on the
   *  data-seq-tagged user bubbles inside it. Owning the ref in the
   *  parent (rather than {@code forwardRef} on this component) keeps
   *  the parent's "find a user row by seq" lookup co-located with
   *  the mount that hosts the index panel. */
  outerRef?: React.MutableRefObject<HTMLDivElement | null>;
  /** Messages already filtered to the trunk slice ({@code task_id IS NULL}). */
  messages: ThreadMessageDto[];
  /** Every task on the thread so we can interleave task-launch cards
   *  inline at their createdAt timestamps. */
  tasks: WorkUnitTaskDto[];
  /** Foreground task id (newest non-terminal) — drives the
   *  "FOREGROUND" pill on the matching launch card. */
  foregroundTaskId: string | null;
  /** Initials displayed on the user avatar (right-side bubbles). */
  userInitials: string;
  /** Click-through on a task-launch card. */
  onOpenTask: (taskId: string) => void;
  /** When true, a thinking-pulse card renders at the bottom of the
   *  scrollback so the user has a visible "the agent is working"
   *  cue while the CLI subprocess spawns + spins up. */
  isInFlight?: boolean;
  /** Fired from the Stop button inside the working… card. The parent
   *  owns the actual interrupt call so optimistic UI + error states
   *  stay co-located with the composer's send flow. Omit to hide
   *  the button (e.g., on a read-only viewer). */
  onInterrupt?: () => void;
  /** True while the interrupt request is in flight — flips the Stop
   *  button label to "Stopping…" and disables it to prevent double-
   *  presses. */
  interrupting?: boolean;
  /** Whether older messages exist on the server that the parent
   *  hasn't fetched yet. When true, a "↑ Load earlier" button renders
   *  at the top of the scrollback. */
  canLoadOlder?: boolean;
  /** Load-older request in flight — disables the button + flips the
   *  label so a double-click can't fire two windows in parallel. */
  loadingOlder?: boolean;
  /** Fired when the user clicks the "Load earlier" button. The parent
   *  owns the cursor + merge logic; this is the trigger. */
  onLoadOlder?: () => void;
  /** Source for the assistant name / glyph / accent on the left-side
   *  bubble. The renderer reads the resolved work-model off the thread
   *  so a DeepSeek-Local turn isn't mislabelled as "Claude". */
  thread?: Pick<ThreadDto, 'workModel' | 'provider' | 'model'> | null;
};

/**
 * WeChat-style trunk planning chat per
 * docs/mockups/design/tasks/thread-trunk.png — green user bubbles
 * right-aligned with the user's initials, white Claude blocks left-
 * aligned with a "C" avatar and a "Claude · trunk · planning" header,
 * "Today · planning" date dividers between calendar days, and inline
 * task-launch cards (◆ Started Task N · branch · PR # · [STATUS →])
 * interleaved at their createdAt timestamps so a click jumps into the
 * task's window.
 */
export default function TrunkChat({
  messages, tasks, foregroundTaskId, userInitials, onOpenTask, isInFlight = false,
  onInterrupt, interrupting = false, outerRef,
  canLoadOlder = false, loadingOlder = false, onLoadOlder,
  thread = null,
}: Props) {
  const speaker = useMemo(() => assistantLabel(thread), [thread]);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  // Assign to both the local ref (used by the stick-to-bottom effect)
  // and the optional ref the parent supplied so ConvIndex can scroll
  // into specific user rows from outside this component.
  const assignScrollRef = (el: HTMLDivElement | null) => {
    scrollRef.current = el;
    if (outerRef !== undefined) outerRef.current = el;
  };
  // Stick to the bottom on new content — the trunk chat is short by
  // design (planning, not transcripts) so the chat-style stick suffices.
  useEffect(() => {
    const el = scrollRef.current;
    if (el !== null) el.scrollTop = el.scrollHeight;
  }, [messages.length, tasks.length]);

  const timeline = useMemo(
    () => buildTimeline(messages, tasks),
    [messages, tasks]);

  // Preserve the user's scroll position when older messages are
  // prepended — without anchoring, the scroll container jumps because
  // its scrollHeight grows above the current viewport. Snapshot the
  // height + top before the click; after layout settles, restore the
  // delta so the rows the user was reading stay put.
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
    if (snap === null) return;
    const el = scrollRef.current;
    if (el === null) return;
    el.scrollTop = snap.top + (el.scrollHeight - snap.height);
    beforeLoadRef.current = null;
  }, [messages.length]);

  return (
    <div ref={assignScrollRef} style={scrollStyle}>
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
      {timeline.map((item, i) => {
        if (item.kind === 'date') {
          return <DateDivider key={`date-${item.label}`} label={item.label} />;
        }
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
              ts={item.ts}
              speaker={speaker}
              activity={item.activity}
            />
          );
        }
        if (item.kind === 'task-launch') {
          const isForeground = item.task.id === foregroundTaskId;
          return (
            <TaskLaunchCard
              key={`launch-${item.task.id}`}
              task={item.task}
              isForeground={isForeground}
              onOpen={() => onOpenTask(item.task.id)}
            />
          );
        }
        return <SystemLine key={`sys-${i}`} text={item.text} />;
      })}
      {isInFlight && <ThinkingCard onInterrupt={onInterrupt} interrupting={interrupting} speaker={speaker} />}
    </div>
  );
}

function ThinkingCard({
  onInterrupt, interrupting, speaker,
}: {
  onInterrupt?: () => void;
  interrupting: boolean;
  speaker: AssistantLabel;
}) {
  return (
    <div style={thinkingRowStyle}>
      <div style={{ ...claudeAvatarStyle, background: speaker.color }}>{speaker.glyph}</div>
      <div style={assistantColStyle}>
        <div style={assistantHeaderStyle}>
          <span style={assistantNameStyle}>{speaker.name}</span>
          <span style={assistantMetaStyle}>trunk</span>
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

/** Per-turn tool activity rolled up onto the turn's final answer. The
 *  trunk hides individual tool_call / tool_result rows (it's the
 *  planning view), so this badge is the only signal that a turn quietly
 *  ran N tools / touched M files. */
type TurnActivity = { tools: number; files: number };

type AssistantItem = {
  kind: 'assistant';
  message: ThreadMessageDto;
  text: string;
  ts: number;
  activity?: TurnActivity;
};

type TimelineItem =
  | { kind: 'date'; label: string; ts: number }
  | { kind: 'user'; message: ThreadMessageDto; text: string; ts: number }
  | AssistantItem
  | { kind: 'task-launch'; task: WorkUnitTaskDto; ts: number }
  | { kind: 'system'; text: string; ts: number };

function buildTimeline(
  messages: ThreadMessageDto[],
  tasks: WorkUnitTaskDto[],
): TimelineItem[] {
  const items: TimelineItem[] = [];
  // Tool activity accumulates across a turn and lands on the turn's
  // last answer as a count badge. A turn boundary is the next user
  // prompt (or the end of the window).
  let turnTools = 0;
  const turnFiles = new Set<string>();
  let lastAnswer: AssistantItem | null = null;
  const flushTurn = () => {
    if (lastAnswer !== null && (turnTools > 0 || turnFiles.size > 0)) {
      lastAnswer.activity = { tools: turnTools, files: turnFiles.size };
    }
    turnTools = 0;
    turnFiles.clear();
    lastAnswer = null;
  };
  // Promote each message to a typed item, folding system/lifecycle
  // rows into a single SystemLine so the chat reads as a chat.
  for (const m of messages) {
    const ts = Date.parse(m.ts);
    if (!Number.isFinite(ts)) continue;
    if (m.role === 'user' && m.type === 'text') {
      flushTurn();
      items.push({ kind: 'user', message: m, text: extractText(m), ts });
    }
    else if (m.role === 'assistant' && (m.type === 'text' || m.type === 'thinking')) {
      // Drop empty assistant rows. Thinking messages occasionally
      // persist with no body, and an in-flight text turn lands in
      // the ledger before the first delta arrives — rendering them
      // as empty bubbles is just noise next to the live thinking
      // pulse below.
      const text = extractText(m);
      if (text.trim().length === 0) continue;
      const item: AssistantItem = { kind: 'assistant', message: m, text, ts };
      items.push(item);
      lastAnswer = item;
    }
    else if (m.type === 'tool_call') {
      // Hidden from the timeline, but counted for the badge. A file
      // path in the input also bumps the "files touched" tally.
      turnTools += 1;
      const path = extractToolFilePath(m.contentJson);
      if (path !== null) turnFiles.add(path);
    }
    // tool_result / permission_request / lifecycle: skip — trunk chat is
    // planning, not transcripts of tool I/O (those live in the task window).
  }
  flushTurn();
  // Inject a task-launch card at each task's createdAt so the user
  // sees the same chronology as the agent: "I'll start task N → card".
  for (const t of tasks) {
    const ts = Date.parse(t.createdAt);
    if (!Number.isFinite(ts)) continue;
    items.push({ kind: 'task-launch', task: t, ts });
  }
  items.sort((a, b) => a.ts - b.ts);
  // Date dividers: insert one before each calendar-day boundary.
  return interleaveDateDividers(items);
}

function interleaveDateDividers(items: TimelineItem[]): TimelineItem[] {
  const out: TimelineItem[] = [];
  let lastDay: string | null = null;
  for (const item of items) {
    const day = new Date(item.ts).toDateString();
    if (day !== lastDay) {
      out.push({ kind: 'date', label: friendlyDateLabel(item.ts), ts: item.ts });
      lastDay = day;
    }
    out.push(item);
  }
  return out;
}

function friendlyDateLabel(ts: number): string {
  const d = new Date(ts);
  const today = new Date();
  const yest = new Date(); yest.setDate(today.getDate() - 1);
  const isSameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
  if (isSameDay(d, today)) return 'Today · planning';
  if (isSameDay(d, yest)) return 'Yesterday · planning';
  return d.toLocaleDateString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
  }) + ' · planning';
}

function extractText(m: ThreadMessageDto): string {
  try {
    const parsed = JSON.parse(m.contentJson) as Record<string, unknown>;
    if (typeof parsed.text === 'string') return parsed.text;
    if (typeof parsed.summary === 'string') return parsed.summary;
  }
  catch { /* fall through */ }
  return m.contentJson;
}

/** Pull a file path out of a tool_call's input, if it's a file tool.
 *  Read/Edit/Write style tools carry the path under one of these keys;
 *  everything else (Bash, list_prs, create_task, …) has no path and
 *  only bumps the tool count, not the files-touched tally. */
function extractToolFilePath(contentJson: string): string | null {
  try {
    const parsed = JSON.parse(contentJson) as { input?: Record<string, unknown> };
    const input = parsed.input;
    if (input === undefined || input === null) return null;
    for (const key of ['file_path', 'path', 'notebook_path']) {
      const v = input[key];
      if (typeof v === 'string' && v.length > 0) return v;
    }
  }
  catch { /* fall through */ }
  return null;
}

function DateDivider({ label }: { label: string }) {
  return (
    <div style={dateDividerWrapStyle}>
      <span style={dateDividerStyle}>{label}</span>
    </div>
  );
}

function UserBubble({ text, initials, seq }: { text: string; initials: string; seq: number }) {
  return (
    // data-seq lets the floating ConvIndex rail find this row when
    // the user clicks an index entry. Scoped to the chat's scroll
    // container in the parent so collisions across pages can't hijack
    // the lookup.
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

function AssistantBlock({ text, ts, speaker, activity }: {
  text: string;
  ts: number;
  speaker: AssistantLabel;
  activity?: TurnActivity;
}) {
  return (
    <div style={assistantRowStyle}>
      <div style={{ ...claudeAvatarStyle, background: speaker.color }}>{speaker.glyph}</div>
      <div style={assistantColStyle}>
        <div style={assistantHeaderStyle}>
          <span style={assistantNameStyle}>{speaker.name}</span>
          <span style={assistantMetaStyle}>trunk</span>
          <span style={assistantMetaStyle}>· {relativeTime(ts)}</span>
        </div>
        <div
          className="bq-chat-md"
          style={assistantBubbleStyle}
          dangerouslySetInnerHTML={{ __html: renderChatMarkdown(text) }}
        />
        {activity !== undefined && activity.tools > 0 && (
          <div style={activityBadgeStyle}>· {formatActivity(activity)}</div>
        )}
      </div>
    </div>
  );
}

function formatActivity({ tools, files }: TurnActivity): string {
  const toolPart = `${tools} tool call${tools === 1 ? '' : 's'}`;
  if (files === 0) {
    return toolPart;
  }
  return `${toolPart} · ${files} file${files === 1 ? '' : 's'} touched`;
}

const activityBadgeStyle: React.CSSProperties = {
  marginTop: 4,
  fontSize: 11,
  color: '#94a3b8',
  fontVariantNumeric: 'tabular-nums',
};

function TaskLaunchCard({
  task, isForeground, onOpen,
}: {
  task: WorkUnitTaskDto;
  isForeground: boolean;
  onOpen: () => void;
}) {
  const branch = task.branchName ?? '—';
  const pr = task.prNumber !== null ? ` · PR #${task.prNumber}` : '';
  // A COMPLETED dev-lifecycle phase is terminal even when the runtime
  // status lags (e.g. IDLE after a remote merge), so the launch card
  // reads "SHIPPED" rather than a stale "RUNNING".
  const status = task.phase === 'COMPLETED' ? 'COMPLETED' : task.status;
  return (
    <div style={launchRowStyle}>
      <div style={launchCardStyle} onClick={onOpen} role="button" tabIndex={0}
        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') onOpen(); }}>
        <div style={launchGlyphStyle}>◆</div>
        <div style={launchBodyStyle}>
          <div style={launchTitleStyle}>
            Started Task {task.seq} · {humanizeBranch(task.branchName ?? `task-${task.seq}`)}
          </div>
          <div style={launchSubStyle}>{branch}{pr}</div>
        </div>
        <span style={launchPillStyle(isForeground, status)}>
          {launchLabel(isForeground, status)} →
        </span>
      </div>
    </div>
  );
}

function SystemLine({ text }: { text: string }) {
  return <div style={systemLineStyle}>{text}</div>;
}

function launchLabel(isForeground: boolean, status: string): string {
  if (isForeground) return 'FOREGROUND';
  if (status === 'AWAITING_REVIEW') return 'AWAITING';
  if (status === 'NEEDS_ATTENTION') return 'NEEDS YOU';
  if (status === 'COMPLETED') return 'SHIPPED';
  if (status === 'ERRORED') return 'ERRORED';
  return status;
}

function humanizeBranch(branch: string): string {
  let rest = branch;
  const slash = rest.lastIndexOf('/');
  if (slash >= 0 && slash < rest.length - 1) rest = rest.slice(slash + 1);
  const hex = rest.match(/^[a-f0-9]{8,}-(.+)$/i);
  if (hex !== null) rest = hex[1];
  const spaced = rest.replace(/[-_]+/g, ' ').trim();
  if (spaced.length === 0) return branch;
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
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
  // Lives inside ThreadTrunkPage.chatCardStyle (bg, border, shadow);
  // this is just the inner scroll surface — fill the parent and
  // scroll within it so the chat card's boundary stays visible.
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '18px 22px',
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};

const dateDividerWrapStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'center',
  margin: '6px 0',
};

const dateDividerStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-4)',
  background: 'rgba(0,0,0,0.04)',
  padding: '3px 12px',
  borderRadius: 999,
  letterSpacing: '0.02em',
};

const userRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  alignItems: 'flex-start',
  gap: 8,
};

const userBubbleStyle: React.CSSProperties = {
  maxWidth: '70%',
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
  gap: 8,
};

const thinkingRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  paddingTop: 4,
};

const thinkingBubbleStyle: React.CSSProperties = {
  // Stretch so the Stop button can sit flush against the right edge.
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

const assistantBubbleStyle: React.CSSProperties = {
  maxWidth: '90%',
  padding: '10px 14px',
  background: '#fff',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 14,
  borderTopLeftRadius: 4,
  fontSize: 15,
  // line-height comes from .bq-chat-md.
  overflowWrap: 'anywhere',
  color: 'var(--text-1)',
};

const launchRowStyle: React.CSSProperties = {
  display: 'flex',
  paddingLeft: 36,
};

const launchCardStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '8px 12px',
  background: '#fff',
  border: '1px solid rgba(124, 58, 237, 0.25)',
  borderLeft: '3px solid #7c3aed',
  borderRadius: 10,
  cursor: 'pointer',
  transition: 'background 140ms ease, transform 140ms ease',
  width: '70%',
  boxShadow: '0 1px 4px rgba(124,58,237,0.06)',
};

const launchGlyphStyle: React.CSSProperties = {
  fontSize: 12,
  color: '#7c3aed',
  fontWeight: 700,
};

const launchBodyStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  minWidth: 0,
};

const launchTitleStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const launchSubStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

function launchPillStyle(isForeground: boolean, status: string): React.CSSProperties {
  let bg = 'rgba(217, 119, 6, 0.14)';
  let color = '#9a3412';
  if (isForeground) {
    bg = 'rgba(34, 197, 94, 0.18)';
    color = '#166534';
  }
  else if (status === 'COMPLETED') {
    bg = 'rgba(71, 85, 105, 0.12)';
    color = SLATE;
  }
  else if (status === 'ERRORED') {
    bg = 'rgba(220, 38, 38, 0.12)';
    color = '#991b1b';
  }
  return {
    fontSize: 9,
    padding: '3px 9px',
    borderRadius: 999,
    background: bg,
    color,
    fontWeight: 700,
    letterSpacing: '0.06em',
    whiteSpace: 'nowrap',
  };
}

const systemLineStyle: React.CSSProperties = {
  alignSelf: 'center',
  fontSize: 10,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};
