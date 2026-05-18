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
import { StructuredConversation } from './StructuredConversation';
import { usePersistentDraft, useAutoGrowTextarea } from './draftStore';
import { TaskDiffPane } from './TaskChangesTab';
import type { PendingPermission } from './ConversationPane';
import type { TaskDto, TaskMessageDto } from '../types';

/**
 * Centred zoom modal for one task in a group, opened by
 * double-clicking the tile body or clicking the ⛶ in the tile
 * header. Mirrors {@code docs/mockups/design/tasks/tasks-group-zoom.png}
 * — 3-column layout (per-task vitals, conversation, diff panel)
 * floating over a dimmed-and-blurred copy of the group page.
 *
 * <p>Closing returns the user exactly where they were on the group
 * page (the group page stays mounted underneath; this modal just
 * unmounts). Pressing Esc closes; clicking the modal's ⛶ button
 * navigates to the full task detail page.
 */
export type TaskZoomModalProps = {
  task: TaskDto;
  onClose: () => void;
  /** Open the full task detail page. The modal closes itself first
   *  so the page transition is clean. */
  onExpandToDetail: (taskId: string) => void;
};

const POLL_MS = 3000;
const DIFF_OPEN_STORAGE_KEY = 'bytequay.tasks.zoomDiffOpen';

export default function TaskZoomModal({ task, onClose, onExpandToDetail }: TaskZoomModalProps) {
  const [messages, setMessages] = useState<TaskMessageDto[]>([]);
  const [pendingPermission, setPendingPermission] = useState<PendingPermission | null>(null);
  const [draft, setDraft] = usePersistentDraft(`zoom:${task.id}`);
  const [sending, setSending] = useState(false);
  const [diffOpen, setDiffOpen] = useState<boolean>(loadDiffOpen);
  const replyRef = useAutoGrowTextarea(draft, 140);

  useEffect(() => {
    try { window.localStorage.setItem(DIFF_OPEN_STORAGE_KEY, diffOpen ? '1' : '0'); }
    catch { /* private browsing — fine to skip */ }
  }, [diffOpen]);

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.getTaskMessages(task.id);
      setMessages(list);
      // Latest unresolved permission_request (if any) drives the
      // approve/reject UI in StructuredConversation.
      const pending = findPendingPermission(list);
      setPendingPermission(pending);
    }
    catch {
      // Non-fatal — keep showing what we have until the next tick.
    }
  }, [task.id]);

  useEffect(() => { void refresh(); }, [refresh]);

  // Poll while open. The interval is shorter than the tile grid's
  // 4s so the zoomed-in view feels noticeably more responsive than
  // its tile.
  useEffect(() => {
    const handle = window.setInterval(() => { void refresh(); }, POLL_MS);
    return () => window.clearInterval(handle);
  }, [refresh]);

  // Esc closes. Registered globally so the user can press it from
  // anywhere over the modal (textarea included — preventDefault keeps
  // a stray Escape from doing something else first).
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const submit = useCallback(async () => {
    const text = draft.trim();
    if (text === '' || sending) return;
    setSending(true);
    try {
      await window.bridge.sendTaskMessage(task.id, text);
      setDraft('');
      await refresh();
    }
    catch {
      // Surface backend errors silently here — the tile + full
      // detail page also show them. Re-raising would unmount the
      // modal mid-typing which is worse.
    }
    finally {
      setSending(false);
    }
  }, [draft, sending, task.id, setDraft, refresh]);

  const onDecide = useCallback(async (
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => {
    try {
      await window.bridge.decideTaskPermission(task.id, callId, decision, preApprove);
      await refresh();
    }
    catch {
      // Same reasoning as submit — keep the modal mounted on error.
    }
  }, [task.id, refresh]);

  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';
  const isRunning = task.status === 'RUNNING';

  return (
    <div style={backdropStyle} onClick={onClose}>
      <div
        style={diffOpen ? modalStyleWithDiff : modalStyleNoDiff}
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={`Zoom: ${task.title}`}
      >
        <ZoomSidebar task={task} messages={messages} />

        <div style={mainPaneStyle}>
          <ZoomToolbar
            task={task}
            diffOpen={diffOpen}
            onToggleDiff={() => setDiffOpen(d => !d)}
            onExpandToDetail={() => {
              onClose();
              onExpandToDetail(task.id);
            }}
            onClose={onClose}
          />

          <div style={conversationScrollStyle}>
            <StructuredConversation
              messages={messages}
              pendingPermission={pendingPermission}
              onDecide={onDecide}
              modelName={task.model}
            />
          </div>

          {!isTerminal && (
            <div style={replyRowStyle}>
              <span style={replyPromptStyle}>›</span>
              <textarea
                ref={replyRef}
                value={draft}
                onChange={e => setDraft(e.target.value)}
                placeholder={isRunning
                  ? 'message — will queue for after current turn…'
                  : 'send a follow-up turn…'}
                disabled={sending}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    void submit();
                  }
                }}
                style={replyTextareaStyle}
              />
              <button
                type="button"
                onClick={() => void submit()}
                disabled={!draft.trim() || sending}
                style={sendBtnStyle}
              >
                {sending ? 'Sending…' : 'Send'}
              </button>
            </div>
          )}
        </div>

        {diffOpen && (
          <aside style={diffPaneStyle}>
            <TaskDiffPane taskId={task.id} />
          </aside>
        )}
      </div>
    </div>
  );
}

// ─── Per-task vitals sidebar (220px) ────────────────────────────────

function ZoomSidebar({ task, messages }: { task: TaskDto; messages: TaskMessageDto[] }) {
  const turns = useMemo(
    () => messages.filter(m => m.type === 'turn_done').length,
    [messages]);
  const runtime = useMemo(() => formatRuntime(task), [task]);
  const ctx = useMemo(() => computeContextUsage(messages, task.model), [messages, task.model]);
  return (
    <aside style={sidebarStyle}>
      <StatusPill status={task.status} />

      <div>
        <div style={sectionHeaderStyle}>This task<span style={sectionRightStyle}>live</span></div>
        <div style={vitalsStyle}>
          <VitalsRow label="Runtime"    value={runtime} live={task.status === 'RUNNING'} />
          <VitalsRow label="Cost"       value={formatCost(task.costUsdMilli)} />
          <VitalsRow label="Tokens"     value={`${formatTokens(task.tokensIn)} → ${formatTokens(task.tokensOut)}`} />
          <VitalsRow label="Turns"      value={String(turns)} />
        </div>
      </div>

      <div>
        <div style={sectionHeaderStyle}>Context</div>
        <ContextBar pct={ctx.pct} used={ctx.used} limit={ctx.limit} />
      </div>

      <div>
        <div style={sectionHeaderStyle}>Checkpoints<span style={sectionRightStyle}>—</span></div>
        <div style={checkpointsStubStyle}>
          Auto-summary checkpoints land in a follow-up. See
          {' '}<code>followups/tasks-checkpoints-and-context.md</code>.
        </div>
      </div>
    </aside>
  );
}

function StatusPill({ status }: { status: TaskDto['status'] }) {
  const palette = {
    RUNNING:   { fg: '#fff',    bg: '#047857', label: 'RUNNING'  },
    AWAITING:  { fg: '#fff',    bg: '#d97706', label: 'AWAITING' },
    PENDING:   { fg: '#1f2937', bg: '#e5e7eb', label: 'PENDING'  },
    IDLE:      { fg: '#374151', bg: '#f3f4f6', label: 'IDLE'     },
    COMPLETED: { fg: '#fff',    bg: '#64748b', label: 'DONE'     },
    ERRORED:   { fg: '#fff',    bg: '#dc2626', label: 'ERRORED'  },
  }[status];
  return (
    <span style={{
      ...statusPillStyle,
      background: palette.bg,
      color: palette.fg,
    }}>
      <span style={pulseDotStyle} aria-hidden />
      {palette.label}
    </span>
  );
}

function VitalsRow({ label, value, live }: { label: string; value: string; live?: boolean }) {
  return (
    <div style={vitalsRowStyle}>
      <span style={vitalsLabelStyle}>{label}</span>
      <span style={{ ...vitalsValueStyle, color: live ? '#047857' : 'var(--text-1)' }}>
        {value}
      </span>
    </div>
  );
}

function ContextBar({ pct, used, limit }: { pct: number; used: number; limit: number }) {
  const tone = pct >= 90 ? 'risk' : pct >= 70 ? 'warn' : 'safe';
  return (
    <div style={ctxBarStyle}>
      <div style={ctxBarLabelStyle}>
        <span>
          <strong style={{ color: 'var(--text-1)' }}>{pct.toFixed(0)}%</strong>
          {' · '}{tone}
        </span>
        <span style={{ color: 'var(--text-3)' }}>
          {formatTokens(used)} / {formatTokens(limit)}
        </span>
      </div>
      <div style={ctxBarTrackStyle}>
        <div style={{
          ...ctxBarFillStyle,
          width: `${Math.min(100, Math.max(0, pct))}%`,
          background: tone === 'risk' ? '#dc2626' : tone === 'warn' ? '#d97706' : '#047857',
        }} />
      </div>
    </div>
  );
}

// ─── Middle pane — toolbar above conversation ───────────────────────

function ZoomToolbar({
  task, diffOpen, onToggleDiff, onExpandToDetail, onClose,
}: {
  task: TaskDto;
  diffOpen: boolean;
  onToggleDiff: () => void;
  onExpandToDetail: () => void;
  onClose: () => void;
}) {
  const provider = (task.provider || '').toLowerCase();
  const glyph = provider.startsWith('codex') ? 'X' : 'C';
  const glyphBg = glyph === 'X'
    ? 'linear-gradient(135deg, #1e293b, #0f172a)'
    : 'linear-gradient(135deg, #d97706, #92400e)';
  return (
    <div style={toolbarStyle}>
      <span style={trafficLightsStyle} aria-hidden>
        <span style={{ ...dotStyle, background: '#ff5f57' }} />
        <span style={{ ...dotStyle, background: '#ffbd2e' }} />
        <span style={{ ...dotStyle, background: '#28c840' }} />
      </span>
      <span style={{ ...providerGlyphStyle, background: glyphBg }}>{glyph}</span>
      <span style={titleStyle} title={task.title}>{task.title}</span>
      {task.branchName && (
        <span style={chipStyle} title={`branch ${task.branchName}`}>
          ⎇ {task.branchName}
        </span>
      )}
      <span style={{ flex: 1, minWidth: 0 }} />
      {/* The three icon buttons are pinned to the right and never
          allowed to shrink — without flex-shrink: 0, a long title +
          branch chip + the diff pane squeezing the middle column
          would push them off-screen on smaller viewports, making
          them visually present but un-clickable. */}
      <div style={toolbarActionsStyle}>
        <button
          type="button"
          onClick={onToggleDiff}
          style={iconBtnStyle}
          title={diffOpen ? 'Hide diff panel' : 'Show diff panel'}
          aria-label={diffOpen ? 'Hide diff panel' : 'Show diff panel'}
        >
          ⇄
        </button>
        <button
          type="button"
          onClick={onExpandToDetail}
          style={iconBtnStyle}
          title="Open full detail page"
          aria-label="Open full detail page"
        >
          ⛶
        </button>
        <button
          type="button"
          onClick={onClose}
          style={iconBtnStyle}
          title="Close (Esc)"
          aria-label="Close"
        >
          ✕
        </button>
      </div>
    </div>
  );
}

// ─── Helpers ────────────────────────────────────────────────────────

function findPendingPermission(messages: TaskMessageDto[]): PendingPermission | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type === 'permission_decision') {
      // Any decision after a request resolves it — stop looking.
      return null;
    }
    if (m.type === 'permission_request') {
      try {
        const parsed = JSON.parse(m.contentJson) as {
          callId?: string;
          toolName?: string;
          summary?: string;
        };
        if (parsed.callId !== undefined && parsed.toolName !== undefined) {
          return {
            callId: parsed.callId,
            toolName: parsed.toolName,
            summary: parsed.summary ?? '',
          };
        }
      }
      catch { /* malformed payload — ignore */ }
    }
  }
  return null;
}

function loadDiffOpen(): boolean {
  try { return window.localStorage.getItem(DIFF_OPEN_STORAGE_KEY) === '1'; }
  catch { return false; }
}

function formatRuntime(task: TaskDto): string {
  const start = Date.parse(task.createdAt);
  if (!Number.isFinite(start)) return '—';
  const end = task.endedAt !== null
    ? Date.parse(task.endedAt)
    : Date.now();
  const sec = Math.max(0, Math.floor((end - start) / 1000));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}
function formatCost(milli: number): string {
  const dollars = milli / 1000;
  if (dollars >= 100) return `$${dollars.toFixed(0)}`;
  if (dollars >= 10)  return `$${dollars.toFixed(1)}`;
  return `$${dollars.toFixed(2)}`;
}
function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

/** Local copy of TaskDetailPage's context-usage helper — kept local
 *  so this modal doesn't rely on the detail page being open or its
 *  internals being exported. Cache tokens are excluded (same
 *  approximation as the detail page); the followup in
 *  {@code followups/tasks-checkpoints-and-context.md} plans the fix. */
function computeContextUsage(messages: TaskMessageDto[], model: string | null) {
  const limit = modelContextLimit(model);
  let used = 0;
  // Scan from the end for the latest turn_done — that's the most
  // recent input_tokens snapshot.
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type === 'turn_done' && m.tokensIn != null) {
      used = m.tokensIn;
      break;
    }
  }
  const pct = limit > 0 ? (used / limit) * 100 : 0;
  return { used, limit, pct };
}
function modelContextLimit(model: string | null): number {
  const m = (model ?? '').trim();
  if (/opus|sonnet|haiku/i.test(m)) return 200_000;
  if (/gpt-?5|codex/i.test(m)) return 272_000;
  if (/gpt-?4/i.test(m)) return 128_000;
  return 200_000;
}

// ─── Styles ─────────────────────────────────────────────────────────

const backdropStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(13, 17, 23, 0.45)',
  backdropFilter: 'blur(2px)',
  WebkitBackdropFilter: 'blur(2px)',
  zIndex: 100,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  // Opt the backdrop out of the topbar's drag region too so a
  // backdrop click near the top of the window dismisses the modal
  // instead of starting a window drag.
  WebkitAppRegion: 'no-drag',
} as React.CSSProperties;

// Zoom is meant to be an immersive view of one task — float over
// the group page using almost the whole viewport (just enough inset
// to make the modal feel like a card on top, not a full-screen
// takeover). The fixed 740 height we had before left a lot of dead
// space on larger displays.
//
// `WebkitAppRegion: 'no-drag'` opts the entire modal out of the
// 44px topbar's `-webkit-app-region: drag` zone. Without this, the
// top ~16px of the modal — which is exactly where the toolbar's
// icon buttons sit — overlaps the OS drag handle and clicks slide
// to "drag the window" instead of activating the button.
// Size targets — ~88% × 86% of the viewport. Big enough to still
// feel like a full focus surface, small enough to leave a real
// dimmed-backdrop frame around the card. (Previously: ~97% × 96%,
// which read as "barely a modal".)
const modalBaseStyle: React.CSSProperties = {
  width: 'calc(100vw - 160px)',
  height: 'calc(100vh - 120px)',
  maxWidth: 1600,
  maxHeight: 1040,
  background: 'var(--bg-card)',
  borderRadius: 12,
  border: '1px solid var(--border)',
  boxShadow: '0 24px 60px rgba(0, 0, 0, 0.35), 0 4px 12px rgba(0, 0, 0, 0.2)',
  overflow: 'hidden',
  display: 'grid',
  WebkitAppRegion: 'no-drag',
} as React.CSSProperties;
const modalStyleWithDiff: React.CSSProperties = {
  ...modalBaseStyle,
  // 260px sidebar (a touch wider so the longer "AWAITING" pill
  // doesn't get cropped); diff pane fixed at 720 so the diff stays
  // legible regardless of overall viewport width; conversation takes
  // the rest.
  gridTemplateColumns: '260px 1fr 720px',
};
const modalStyleNoDiff: React.CSSProperties = {
  ...modalBaseStyle,
  gridTemplateColumns: '260px 1fr',
};

const sidebarStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  padding: '14px 12px',
  background: 'var(--bg-elevated)',
  borderRight: '1px solid var(--border)',
  overflowY: 'auto',
  minHeight: 0,
};

const sectionHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  fontSize: 9,
  fontWeight: 700,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  padding: '2px 4px 4px',
};
const sectionRightStyle: React.CSSProperties = {
  marginLeft: 'auto',
  color: 'var(--text-3)',
  fontWeight: 500,
  textTransform: 'none',
  letterSpacing: 0,
  fontSize: 10,
};
const vitalsStyle: React.CSSProperties = {
  background: 'var(--bg-card)',
  border: '1px solid var(--border-hairline)',
  borderRadius: 6,
  padding: '2px 10px',
};
const vitalsRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  padding: '5px 0',
  borderBottom: '1px dashed var(--border-hairline)',
  fontSize: 11.5,
};
const vitalsLabelStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontSize: 10.5,
};
const vitalsValueStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontWeight: 600,
  fontVariantNumeric: 'tabular-nums',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 11,
};

const ctxBarStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '4px 6px 0',
};
const ctxBarLabelStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  fontSize: 11,
  color: 'var(--text-2)',
};
const ctxBarTrackStyle: React.CSSProperties = {
  height: 6,
  background: 'var(--bg-elevated-2, var(--border-hairline))',
  borderRadius: 3,
  overflow: 'hidden',
};
const ctxBarFillStyle: React.CSSProperties = {
  height: '100%',
  transition: 'width 200ms ease, background 200ms ease',
};

const checkpointsStubStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  padding: '6px 8px',
  background: 'var(--bg-card)',
  border: '1px dashed var(--border-hairline)',
  borderRadius: 6,
  lineHeight: 1.5,
};

const statusPillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '3px 10px',
  borderRadius: 999,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.04em',
  alignSelf: 'flex-start',
};
const pulseDotStyle: React.CSSProperties = {
  width: 6, height: 6,
  borderRadius: '50%',
  background: 'currentColor',
};

const mainPaneStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg-card)',
  minWidth: 0,
  minHeight: 0,
};

const toolbarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '8px 14px',
  background: 'var(--bg-elevated)',
  borderBottom: '1px solid var(--border)',
  flexShrink: 0,
  // The middle column is `1fr` between a fixed sidebar and diff
  // pane; minWidth: 0 lets the toolbar's flex items shrink instead
  // of overflowing horizontally and pushing the action cluster
  // off-screen.
  minWidth: 0,
};
const toolbarActionsStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  flexShrink: 0,
  // marginLeft auto would be redundant given the flex:1 spacer
  // earlier in the row, but we keep the cluster as its own flex
  // item so the buttons share a single shrink boundary.
};
const trafficLightsStyle: React.CSSProperties = {
  display: 'inline-flex',
  gap: 6,
  marginRight: 2,
};
const dotStyle: React.CSSProperties = {
  width: 11, height: 11,
  borderRadius: '50%',
};
const providerGlyphStyle: React.CSSProperties = {
  width: 22, height: 22,
  borderRadius: 5,
  color: '#fff',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 11, fontWeight: 700,
  flexShrink: 0,
};
const titleStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  maxWidth: 460,
  // Allow the title to actually shrink below the maxWidth when the
  // middle column is tight (diff pane open on a small viewport),
  // so the action cluster on the right stays reachable.
  minWidth: 0,
  flexShrink: 1,
};
const chipStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 4,
  padding: '2px 8px',
  background: 'var(--bg-card)',
  border: '1px solid var(--border-hairline)',
  borderRadius: 999,
  fontSize: 11,
  color: 'var(--text-2)',
  flexShrink: 0,
};
const iconBtnStyle: React.CSSProperties = {
  width: 28, height: 26,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'transparent',
  border: '1px solid var(--border)',
  borderRadius: 6,
  color: 'var(--text-2)',
  cursor: 'pointer',
  fontSize: 12,
  flexShrink: 0,
};

const conversationScrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '12px 14px',
};

const replyRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  padding: '8px 12px',
  borderTop: '1px solid var(--border)',
  background: 'var(--bg-elevated)',
  flexShrink: 0,
};
const replyPromptStyle: React.CSSProperties = {
  color: 'var(--accent)',
  fontWeight: 700,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 16,
  lineHeight: '24px',
  flexShrink: 0,
};
const replyTextareaStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 24,
  maxHeight: 140,
  resize: 'none',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  padding: '4px 8px',
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  fontSize: 13,
  fontFamily: 'inherit',
  outline: 'none',
};
const sendBtnStyle: React.CSSProperties = {
  padding: '6px 12px',
  background: 'var(--accent)',
  border: 'none',
  borderRadius: 6,
  color: '#fff',
  fontSize: 12,
  fontWeight: 600,
  cursor: 'pointer',
  flexShrink: 0,
};

const diffPaneStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg-card)',
  borderLeft: '1px solid var(--border)',
  overflow: 'hidden',
  minHeight: 0,
};
