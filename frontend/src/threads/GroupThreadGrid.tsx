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
import type { ThreadDto, ThreadMessageDto } from '../types';
import { TileConversation, type TileConversationMode } from './TileConversation';
import { useAutoGrowTextarea, usePersistentDraft } from './draftStore';
import {
  type ActiveTurnSummary,
  type SchedulerDisplayStatus,
  displayStatusForTask,
} from './threadTurnSummary';
import { findPendingPermission } from './permissions';
import { threadModelLabel, threadTokenLabel } from './threadDisplay';

export type GroupLayout = 1 | 2 | 3 | 4;

type Props = {
  threads: ThreadDto[];
  /** Active scheduler state keyed by thread id. A tile can be queued
   *  even when the durable thread row still says IDLE. */
  activeTurnsByThreadId: Map<string, ActiveTurnSummary>;
  /** Open the full detail page for a thread. The tile title
   *  becomes the click target so clicks inside the tile body
   *  (typing a reply, scrolling the history) don't navigate. */
  onOpen: (threadId: string) => void;
  /** Stop an active thread from its tile. The page parent serialises
   *  the call and refreshes once the row flips to a terminal state. */
  onStop: (threadId: string) => void | Promise<void>;
  /** Send a follow-up turn to one of the tiles' threads. */
  onSend: (threadId: string, input: string) => void | Promise<void>;
  /** Cancel the in-flight turn on one of the tiles' threads. */
  onInterrupt: (threadId: string) => void | Promise<void>;
  /** Reply to a pending permission_request surfaced in a tile.
   *  Optional {@code preApprove} grants an auto-allow budget for the
   *  same tool — used by the "Allow next 5/10/50/Always" buttons. */
  onDecide: (
    threadId: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void | Promise<void>;
  /** ID of the thread whose Stop is currently in flight, so the tile
   *  can render a busy state and disable the button. */
  busyId: string | null;
  /** When immersive, tiles drop their head bar + bottom status strip
   *  and the conversation fills the whole tile. The reply input
   *  stays — it's the only chrome the user actively uses while
   *  driving 4 agents at once. */
  immersive: boolean;
  /** Per-tile visual mode for the conversation pane — Chat (default,
   *  WeChat bubbles) or Terminal (Warp/tmux monospace). */
  tileMode: TileConversationMode;
  /** Open the GitHub PR linked to a thread in-app (RepoDetailPage with
   *  prNumber). The parent resolves the thread's working dir into an
   *  owner/repo via the watched-repos list before navigating. */
  onOpenPr: (thread: ThreadDto, prNumber: number) => void;
  /** Open the GitHub issue linked to a thread. Today this lands on the
   *  repo's Issues tab (no deep-link route to a single issue yet);
   *  the user picks the row. */
  onOpenIssue: (thread: ThreadDto, issueNumber: number) => void;
  /** ID of the currently selected tile (parent-owned so Esc
   *  precedence and clear-on-deselect can be driven from outside). */
  selectedId: string | null;
  /** Click-to-select callback. The grid never deselects on its own;
   *  the parent handles Esc-driven clearing. */
  onSelectTile: (threadId: string) => void;
};

const POLL_MS = 4000;

/**
 * Fixed-slot grid of large thread tiles, one per group member.
 * Each tile mirrors the structured-detail layout in miniature:
 * status header, scrollable recent-activity bullets, footer with
 * runtime/cost. Click anywhere to jump to the full detail page.
 *
 * <p>The {@code layout} prop picks how many tiles are visible:
 * 1 (full pane), 2 (left | right), 3 (two on top + one centred
 * below), or 4 (2×2). Tiles can be dragged onto another slot to
 * swap positions — useful for re-arranging which thread gets the
 * larger / more central slot. Threads past the visible slot count
 * are hidden until the user swaps one in.
 *
 * <p>Faithfully follows {@code docs/mockups/design/threads/threads-group.png}
 * minus the per-tile send box, which would require running N
 * conversations in parallel and is out of scope for this slice.
 */
export default function GroupThreadGrid({
  threads, activeTurnsByThreadId, onOpen, onStop, onSend, onInterrupt, onDecide,
  busyId, immersive, tileMode, selectedId, onSelectTile,
  onOpenPr, onOpenIssue,
}: Props) {
  // Auto-derive the tile layout from the thread count — the server
  // caps a group at 4 members so the grid is bounded. Earlier the
  // user could pick 1/2/3/4 via a header switcher; the redesign
  // dropped the switcher in favour of an unambiguous layout per
  // count.
  const layout = (Math.max(1, Math.min(4, threads.length)) as GroupLayout);
  const [previews, setPreviews] = useState<Record<string, ThreadMessageDto[]>>({});

  // User-overridden slot order — sticky across renders so a drag swap
  // doesn't get undone by the next status poll. Stored as a list of
  // thread ids whose position in the array IS the slot index. Threads not
  // listed here fall through to the natural ordering.
  const [order, setOrder] = useState<string[]>([]);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);

  // Garbage-collect order entries pointing at threads that left the
  // group (deleted / un-grouped / moved). Stops the slot from going
  // blank or pointing at a stale row.
  useEffect(() => {
    setOrder(prev => prev.filter(id => threads.some(t => t.id === id)));
  }, [threads]);

  // Final visible order: user-pinned ids first (preserving their
  // chosen slot), then unpinned threads fill remaining slots in the
  // parent's already-sorted order (active-first).
  const ordered = useMemo(() => {
    const pinned = order
      .map(id => threads.find(t => t.id === id))
      .filter((t): t is ThreadDto => t != null);
    const rest = threads.filter(t => !order.includes(t.id));
    return [...pinned, ...rest];
  }, [threads, order]);

  // Fan-out: pull recent messages for each visible tile in parallel.
  // The server caps the group at 4, so every thread is always visible.
  const visible = ordered.slice(0, layout);
  // Cache key for the polling effect: a stable join of visible thread
  // ids so re-renders (status polls, hover state, etc.) don't tear
  // down the interval just because the array identity changed.
  const visibleIdsKey = visible.map(t => t.id).join('|');

  useEffect(() => {
    let cancelled = false;
    const ids = visibleIdsKey ? visibleIdsKey.split('|') : [];
    async function refresh() {
      const results = await Promise.all(ids.map(async id => {
        try {
          const ms = await window.bridge.getTaskMessages(id);
          return [id, ms] as const;
        }
        catch {
          return [id, [] as ThreadMessageDto[]] as const;
        }
      }));
      if (cancelled) return;
      const next: Record<string, ThreadMessageDto[]> = {};
      for (const [id, ms] of results) next[id] = ms;
      setPreviews(next);
    }
    void refresh();
    const handle = window.setInterval(() => { void refresh(); }, POLL_MS);
    return () => { cancelled = true; window.clearInterval(handle); };
  }, [visibleIdsKey]);

  // ⌘1 / ⌘2 / ⌘3 / ⌘4 select the tile at that slot — works in both
  // immersive and non-immersive group view. We bind unconditionally
  // (no input-focus skip) so that someone deep in tile #1's textarea
  // can flip to tile #2 with a single chord. The actual focus move
  // to the new tile's reply input is handled by a focus-on-select-
  // transition effect in ThreadTile.
  useEffect(() => {
    if (threads.length === 0) return;
    function onKey(e: KeyboardEvent) {
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.shiftKey || e.altKey) return;
      const idx = parseInt(e.key, 10);
      if (!Number.isInteger(idx) || idx < 1 || idx > 4) return;
      if (idx > visible.length) return;
      e.preventDefault();
      onSelectTile(visible[idx - 1].id);
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [visible, threads.length, onSelectTile]);

  if (threads.length === 0) {
    return (
      <div style={emptyStyle}>
        <div style={emptyTitleStyle}>No threads in this group yet</div>
        <div style={mutedStyle}>
          Use <strong>+ Add thread</strong> above to start one — it'll be
          pinned to this group automatically.
        </div>
      </div>
    );
  }

  function performSwap(fromIdx: number, toIdx: number) {
    if (fromIdx === toIdx) return;
    const next = [...ordered];
    const tmp = next[fromIdx];
    next[fromIdx] = next[toIdx];
    next[toIdx] = tmp;
    // Persist the swap by writing the new ids in slot order. Only the
    // visible slots matter — threads past the layout cap stay hidden,
    // but we keep their ids in the trailing positions so re-expanding
    // the layout preserves their existing order.
    setOrder(next.map(t => t.id));
  }

  return (
    <>
      <div style={layoutGridStyle(layout, tileMode)}>
        {visible.map((t, idx) => (
          <div
            key={t.id}
            style={{
              ...slotStyle(layout, idx),
              ...(dragOver === idx && dragFrom !== idx ? slotDropTargetStyle : null),
            }}
          >
            <ThreadTile
              thread={t}
              scheduler={activeTurnsByThreadId.get(t.id)}
              messages={previews[t.id] ?? []}
              busy={busyId === t.id}
              dragging={dragFrom === idx}
              immersive={immersive}
              tileMode={tileMode}
              slotIndex={idx + 1}
              selected={selectedId === t.id}
              onSelect={() => onSelectTile(t.id)}
              onOpen={() => onOpen(t.id)}
              onOpenPr={onOpenPr}
              onOpenIssue={onOpenIssue}
              onStop={() => onStop(t.id)}
              onSend={input => onSend(t.id, input)}
              onInterrupt={() => onInterrupt(t.id)}
              onDecide={(callId, decision, preApprove) =>
                onDecide(t.id, callId, decision, preApprove)}
              onDragStart={() => setDragFrom(idx)}
              onDragEnter={() => setDragOver(idx)}
              onDragEnd={() => { setDragFrom(null); setDragOver(null); }}
              onDrop={() => {
                if (dragFrom != null) performSwap(dragFrom, idx);
                setDragFrom(null);
                setDragOver(null);
              }}
            />
          </div>
        ))}
      </div>
    </>
  );
}

function ThreadTile({
  thread, scheduler, messages, busy, dragging, immersive, tileMode,
  slotIndex, selected, onSelect,
  onOpen, onOpenPr, onOpenIssue, onStop,
  onSend, onInterrupt, onDecide,
  onDragStart, onDragEnter, onDragEnd, onDrop,
}: {
  thread: ThreadDto;
  scheduler: ActiveTurnSummary | undefined;
  messages: ThreadMessageDto[];
  busy: boolean;
  dragging: boolean;
  /** Slim head shape in both immersive and non-immersive. Footer
   *  follows: hidden in immersive (chat + reply only); the
   *  metadata strip lives below the conversation in non-immersive. */
  immersive: boolean;
  /** Per-tile conversation visual mode (Chat / Terminal). */
  tileMode: TileConversationMode;
  /** 1-based slot position in the visible tile grid; surfaced in the
   *  immersive slim head as the `⌘N` shortcut label. */
  slotIndex: number;
  /** Whether this tile is the active one. Parent guarantees at most
   *  one tile in the grid has selected=true at any time. */
  selected: boolean;
  /** Single-click anywhere on the tile body becomes the selection
   *  signal — focuses this tile so type-to-reply lands here. */
  onSelect: () => void;
  onOpen: () => void;
  onOpenPr: (thread: ThreadDto, prNumber: number) => void;
  onOpenIssue: (thread: ThreadDto, issueNumber: number) => void;
  onStop: () => void | Promise<void>;
  onSend: (input: string) => void | Promise<void>;
  onInterrupt: () => void | Promise<void>;
  onDecide: (
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void | Promise<void>;
  onDragStart: () => void;
  onDragEnter: () => void;
  onDragEnd: () => void;
  onDrop: () => void;
}) {
  const isTerminal = thread.status === 'COMPLETED' || thread.status === 'ARCHIVED' || thread.status === 'ERRORED';
  const displayStatus = displayStatusForTask(thread, scheduler);
  const isRunning = displayStatus === 'RUNNING';
  // Shared key with ThreadDetailPage's reply box — text typed in a tile
  // is also visible if the user pops the same thread into full detail
  // view, since both render the same logical "reply to this thread" input.
  const [draft, setDraft] = usePersistentDraft(`reply:${thread.id}`);
  // Tiles have tight vertical space — cap at ~120px (≈ 6 lines) so a
  // long draft doesn't crowd out the conversation. Overflow scrolls.
  const replyRef = useAutoGrowTextarea(draft, 120);
  const [sending, setSending] = useState(false);
  const pendingPermission = useMemo(() => findPendingPermission(messages), [messages]);

  // Refs mirror the latest draft / sending state so the document-
  // level keydown listener (registered once per selected change) can
  // read fresh values instead of a stale closure snapshot.
  const draftRef = useRef(draft);
  const sendingRef = useRef(sending);
  useEffect(() => { draftRef.current = draft; }, [draft]);
  useEffect(() => { sendingRef.current = sending; }, [sending]);

  // Focus the reply textarea whenever this tile becomes the active
  // one (transition false → true). Covers all paths into selection:
  // click on the tile body, ⌘1–⌘4 from the parent, etc. The onClick
  // handler already focuses synchronously too — this effect is the
  // fallback for keyboard-driven selection paths.
  const prevSelectedRef = useRef(false);
  useEffect(() => {
    if (selected && !prevSelectedRef.current && !isTerminal) {
      replyRef.current?.focus({ preventScroll: true });
    }
    prevSelectedRef.current = selected;
  }, [selected, isTerminal, replyRef]);

  async function doSend(text: string) {
    if (text === '' || sendingRef.current) return;
    setSending(true);
    try {
      await onSend(text);
      setDraft('');
    }
    finally {
      setSending(false);
    }
  }
  async function submit() {
    await doSend(draftRef.current.trim());
  }

  // Type-to-reply: when this tile is the selected one and the user
  // starts typing without an input focused, route the keystroke
  // straight into the reply draft and focus the textarea so the next
  // key lands there natively. Backspace deletes the last character;
  // Enter (without shift) sends. Modifier-shortcut keys (⌘/Ctrl/Alt)
  // are intentionally passed through so global shortcuts still work.
  useEffect(() => {
    if (!selected) return;
    if (isTerminal) return; // terminal tiles have no reply textarea
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      const target = e.target as HTMLElement | null;
      if (target !== null) {
        const tag = target.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
        if (target.isContentEditable) return;
      }
      const ta = replyRef.current;
      if (ta === null) return;
      if (e.key === 'Backspace') {
        e.preventDefault();
        setDraft(draftRef.current.slice(0, -1));
        ta.focus({ preventScroll: true });
        return;
      }
      if (e.key === 'Enter') {
        // Per threads-design.md: `↵` while a tile is selected opens
        // the zoom modal (sending lives on the textarea's own
        // onKeyDown, which only fires when the textarea has focus).
        // Shift+Enter still falls through to a literal newline in
        // the draft so the user can pre-stage a multi-line message
        // without clicking into the textarea first.
        e.preventDefault();
        if (e.shiftKey) {
          setDraft(draftRef.current + '\n');
          ta.focus({ preventScroll: true });
        }
        else {
          onOpen();
        }
        return;
      }
      // Single printable character — everything else (Tab, F-keys,
      // arrows, Esc, etc.) we leave alone so the user can still use
      // the page's broader keyboard navigation.
      if (e.key.length === 1) {
        e.preventDefault();
        setDraft(draftRef.current + e.key);
        ta.focus({ preventScroll: true });
      }
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
    // doSend reads its inputs from refs, so the listener stays
    // correct even though we don't list doSend/setDraft in the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, isTerminal, replyRef, onOpen]);

  // Double-click anywhere on the tile zooms — only inputs are
  // exempt (the reply textarea, in particular). Buttons and links
  // are NOT exempt: the user explicitly asked that every non-input
  // area, including controls, opens the zoom view on double-click.
  // Single-click semantics on those buttons are untouched.
  function onTileDoubleClick(e: React.MouseEvent<HTMLElement>) {
    const target = e.target as HTMLElement | null;
    if (target !== null) {
      const tag = target.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (target.closest('input, textarea, select, [contenteditable="true"]') !== null) return;
    }
    const sel = window.getSelection();
    if (sel !== null && sel.toString().length > 0) return;
    onOpen();
  }

  // Single-click selects this tile AND moves keyboard focus to its
  // reply input (per threads-design.md). Clicks that landed directly
  // on an input (or stopped propagation, e.g. GroupMenu trigger) are
  // already handled correctly — they keep their own focus target.
  function onTileClick(e: React.MouseEvent<HTMLElement>) {
    onSelect();
    const target = e.target as HTMLElement | null;
    if (target !== null) {
      if (target.closest('input, textarea, select, button, a, [contenteditable="true"]') !== null) {
        // The click target already owns focus semantics; leave it
        // alone. Selection is still updated above.
        return;
      }
    }
    if (!isTerminal) {
      replyRef.current?.focus({ preventScroll: true });
    }
  }

  const isTerm = tileMode === 'terminal';
  return (
    <article
      style={{
        ...tileStyle,
        ...(isTerm ? tileTerminalStyle : null),
        ...(selected ? (isTerm ? tileSelectedTerminalStyle : tileSelectedStyle) : null),
        ...(dragging ? tileDraggingStyle : null),
      }}
      onClick={onTileClick}
      onDragEnter={onDragEnter}
      onDragOver={e => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }}
      onDrop={e => { e.preventDefault(); onDrop(); }}
      onDragEnd={onDragEnd}
      onDoubleClick={onTileDoubleClick}
    >
      {/* Slim 22px tmux-style pane head in BOTH immersive and
          non-immersive group view (per the latest threads-design.md
          update). Composition: 6px status dot · title · right-side
          slot. The right-side slot differs:
            - Immersive  → ⌘N shortcut label (keyboard-only verb).
            - Non-immersive → Stop button (when not terminal) and
              the ⛶ zoom button. The ⋯ pin-to-groups menu is
              dropped — that affordance lives in the rail and the
              detail page now. */}
      <header
        style={isTerm ? { ...tileSlimHeadStyle, ...tileSlimHeadTerminalStyle } : tileSlimHeadStyle}
        draggable
        onDragStart={e => {
          e.dataTransfer.effectAllowed = 'move';
          e.dataTransfer.setData('text/plain', thread.id);
          onDragStart();
        }}
      >
        <span style={{ ...slimDotStyle, background: dotColor(displayStatus) }} aria-hidden />
        <span style={flowChipStyle(thread.flow)} aria-label={`flow ${thread.flow}`}>
          {thread.flow === 'review' ? 'REVIEW' : 'BUILD'}
        </span>
        {immersive ? (
          <span
            style={isTerm ? { ...slimTitleStyle, color: '#8b949e' } : slimTitleStyle}
            title={thread.title}
          >
            {thread.title}
          </span>
        ) : (
          <button
            type="button"
            onClick={onOpen}
            style={isTerm ? { ...slimTitleBtnStyle, color: '#8b949e' } : slimTitleBtnStyle}
            title="Zoom in (or double-click anywhere on the tile)"
          >
            {thread.title}
          </button>
        )}
        {immersive ? (
          <span
            style={isTerm ? { ...slimShortcutStyle, color: '#6e7681' } : slimShortcutStyle}
            aria-hidden
          >
            ⌘{slotIndex}
          </span>
        ) : (
          <div style={slimHeadActionsStyle}>
            {!isTerminal && (
              <button
                type="button"
                onClick={e => { e.stopPropagation(); void onStop(); }}
                disabled={busy}
                style={isTerm ? { ...slimStopBtnStyle, ...slimStopBtnTerminalStyle } : slimStopBtnStyle}
                title="Stop and release the agent"
              >
                {busy ? '…' : 'Stop'}
              </button>
            )}
            <button
              type="button"
              onClick={e => { e.stopPropagation(); onOpen(); }}
              style={isTerm ? { ...slimZoomBtnStyle, color: '#8b949e' } : slimZoomBtnStyle}
              title="Zoom in (or double-click the tile)"
              aria-label="Zoom in"
            >
              ⛶
            </button>
          </div>
        )}
      </header>

      <div style={tileConversationStyle}>
        <TileConversation
          messages={messages}
          pendingPermission={pendingPermission}
          onDecide={onDecide}
          mode={tileMode}
        />
      </div>

      {!isTerminal && (
        <div style={isTerm ? { ...tileReplyStyle, ...tileReplyTerminalStyle } : tileReplyStyle}>
          <textarea
            ref={replyRef}
            value={draft}
            onChange={e => setDraft(e.target.value)}
            placeholder={isRunning
              ? 'message — will queue for after current turn…'
              : displayStatus === 'QUEUED'
                ? 'message — will queue behind pending turn…'
              : 'send a follow-up turn…'}
            disabled={sending}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                void submit();
              }
            }}
            style={isTerm
              ? { ...tileReplyTextareaStyle, ...tileReplyTextareaTerminalStyle }
              : tileReplyTextareaStyle}
          />
          <div style={tileReplyActionsStyle}>
            {isRunning && (
              <button
                type="button"
                onClick={() => void onInterrupt()}
                style={tileInterruptBtnStyle}
                title="Cancel the current turn"
              >
                ⏵ Interrupt
              </button>
            )}
            <button
              type="button"
              onClick={() => void submit()}
              disabled={!draft.trim() || sending}
              style={tileSendBtnStyle}
            >
              {sending ? 'sending…' : (isRunning ? 'Queue →' : 'Send →')}
            </button>
          </div>
        </div>
      )}

      {!immersive && (
        // Footer is metadata only now — Stop moved to the slim head
        // alongside the ⛶ zoom button. Keeps the runtime / cost /
        // tokens glance without competing with the destructive action.
        <footer style={tileFooterStyle}>
          <span style={footerMetaStyle}>{threadModelLabel(thread.model)}</span>
          <span style={footerSepStyle}>·</span>
          <span style={footerMetaStyle}>{formatAge(thread.updatedAt)}</span>
          <div style={{ flex: 1 }} />
          <span style={footerMetricStyle}>{formatCost(thread.costUsdMilli)}</span>
          <span style={footerSepStyle}>·</span>
          <span style={footerMetricStyle} title="Total input and output tokens">
            {threadTokenLabel(thread.tokensIn + thread.tokensOut)}
          </span>
        </footer>
      )}
    </article>
  );
}

// ────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────

/** 6px status dot colour for the immersive slim head, per the
 *  threads-design.md immersive bullet (green RUN / amber WAIT /
 *  grey IDLE / red ERROR). PENDING + COMPLETED both read as
 *  "not actively driving" → grey, matching IDLE. */
function dotColor(s: SchedulerDisplayStatus): string {
  switch (s) {
    case 'RUNNING':   return '#10b981';
    case 'QUEUED':    return '#d97706';
    case 'ERRORED':   return '#dc2626';
    default:          return '#9ca3af';
  }
}

function formatAge(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

function formatCost(milli: number): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

/** Tall, viewport-anchored grid for the group view. Heights are
 *  computed so the tiles fill the same vertical slot the conversation
 *  pane occupies on the detail page, regardless of how many slots
 *  the user picks. The {@code minmax(0, ...)} on the row tracks lets
 *  tiles shrink inside their slot instead of pushing the grid taller
 *  than the viewport. */
function layoutGridStyle(layout: GroupLayout, mode: TileConversationMode): React.CSSProperties {
  // tmux-style splits — 1px dividers via gap + background so adjacent
  // tiles share a hairline border without any per-tile chrome. Tiles
  // butt right up against the rail and the screen edges (no padding
  // on the main column either), matching docs/mockups/design/threads/
  // threads-group.png. In Terminal mode the divider colour swaps to
  // the GitHub-dark `#21262d` so the whole pane reads as one
  // continuous monospace surface.
  const base: React.CSSProperties = {
    display: 'grid',
    gap: 1,
    background: mode === 'terminal' ? '#21262d' : 'var(--border)',
    flex: 1,
    minHeight: 0,
  };
  switch (layout) {
    case 1:
      return { ...base, gridTemplateColumns: '1fr', gridTemplateRows: '1fr' };
    case 2:
      return { ...base, gridTemplateColumns: '1fr 1fr', gridTemplateRows: '1fr' };
    case 3:
      // 4 col × 2 row scaffold so the bottom tile (col 2-3) sits
      // centred between the two top tiles (col 1-2 and 3-4). All
      // three render at the same effective width since each
      // visible slot spans two of the four columns.
      return { ...base,
        gridTemplateColumns: 'repeat(4, 1fr)',
        gridTemplateRows: 'minmax(0, 1fr) minmax(0, 1fr)' };
    case 4:
      return { ...base,
        gridTemplateColumns: '1fr 1fr',
        gridTemplateRows: 'minmax(0, 1fr) minmax(0, 1fr)' };
  }
}

function slotStyle(layout: GroupLayout, idx: number): React.CSSProperties {
  // 3-slot layout's bottom tile centres on the 4-col scaffold.
  if (layout === 3) {
    if (idx === 0) return { gridColumn: '1 / span 2', gridRow: '1' };
    if (idx === 1) return { gridColumn: '3 / span 2', gridRow: '1' };
    if (idx === 2) return { gridColumn: '2 / span 2', gridRow: '2' };
  }
  // 1/2/4 layouts: tiles flow naturally — the grid template handles
  // the placement, so each slot is just a plain container.
  return { display: 'flex', minHeight: 0, minWidth: 0 };
}

const slotDropTargetStyle: React.CSSProperties = {
  // tmux panes don't carry borders, so the drop target is an inset
  // accent ring rather than a dashed outline. Matches the focused
  // tile treatment that the redesign calls for.
  boxShadow: 'inset 0 0 0 2px var(--accent)',
  zIndex: 1,
};

const tileStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg-card)',
  // No border or rounded corners — the 1px gap on the grid plus the
  // grid's border-coloured background do the divider work.
  cursor: 'pointer',
  transition: 'box-shadow 0.12s ease, opacity 0.12s ease',
  width: '100%',
  height: '100%',
  minHeight: 0,
  minWidth: 0,
  position: 'relative',
};
const tileDraggingStyle: React.CSSProperties = {
  opacity: 0.5,
};
// Inset accent ring + faint tint so the active tile is unmistakable
// at a glance without adding any chrome to the flat tmux layout. The
// ring matches the drag-target ring (which lives on the slot wrapper,
// not the article, so the two visuals don't collide).
const tileSelectedStyle: React.CSSProperties = {
  boxShadow: 'inset 0 0 0 2px var(--accent)',
  background: 'var(--accent-a05, rgba(124,92,255,0.04))',
};
// Terminal mode: dark base for the tile, so the reply input and any
// gutters blend with the conversation pane. The selected ring stays
// purple but the tint flips so it reads against the dark background.
const tileTerminalStyle: React.CSSProperties = {
  background: '#0d1117',
  color: 'rgba(255,255,255,0.88)',
};
const tileSelectedTerminalStyle: React.CSSProperties = {
  boxShadow: 'inset 0 0 0 2px #a78bfa',
  background: '#10151c',
};
// Slim 25px tmux-style pane title used in both immersive and
// non-immersive group views. Earlier iterations used 22px; we
// nudged to 25 to leave room for the PR / Issue chip pills.
const tileSlimHeadStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  height: 25,
  padding: '0 8px 0 10px',
  borderBottom: '1px solid var(--border-hairline)',
  fontFamily: 'var(--font-mono)',
  cursor: 'grab',
  userSelect: 'none',
  flexShrink: 0,
};
const slimDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  borderRadius: '50%',
  flexShrink: 0,
};

function flowChipStyle(flow: 'build' | 'review'): React.CSSProperties {
  const isReview = flow === 'review';
  return {
    fontSize: 8,
    fontWeight: 700,
    letterSpacing: '0.08em',
    padding: '1px 5px',
    borderRadius: 3,
    color: isReview ? '#1d4ed8' : 'var(--accent)',
    background: isReview ? 'rgba(37, 99, 235, 0.10)' : 'var(--accent-a10)',
    border: `1px solid ${isReview ? 'rgba(37,99,235,0.30)' : 'var(--accent-border)'}`,
    flexShrink: 0,
    textTransform: 'uppercase',
  };
}
// Immersive head: title is plain text, centred per the design.
const slimTitleStyle: React.CSSProperties = {
  flex: 1,
  textAlign: 'center',
  fontSize: 11,
  color: '#57606a',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontWeight: 500,
};
// Non-immersive head: title is a button (single-click → zoom). Left-
// aligned so the right-side action cluster (Stop + ⛶) reads as a
// distinct slot, not overlapping the title.
const slimTitleBtnStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  background: 'transparent',
  border: 'none',
  padding: 0,
  cursor: 'pointer',
  textAlign: 'left',
  fontFamily: 'inherit',
  fontSize: 11,
  color: '#57606a',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontWeight: 500,
};
const slimShortcutStyle: React.CSSProperties = {
  fontSize: 9.5,
  color: '#afb8c1',
  fontWeight: 600,
  flexShrink: 0,
  letterSpacing: '0.02em',
};
// Right-side action slot for non-immersive — Stop button + ⛶ zoom.
const slimHeadActionsStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  flexShrink: 0,
};
const slimStopBtnStyle: React.CSSProperties = {
  padding: '0 6px',
  height: 16,
  display: 'inline-flex',
  alignItems: 'center',
  background: 'transparent',
  color: '#dc2626',
  border: '1px solid #fca5a5',
  borderRadius: 3,
  fontFamily: 'inherit',
  fontSize: 9.5,
  fontWeight: 600,
  cursor: 'pointer',
  letterSpacing: '0.02em',
  lineHeight: 1,
};
const slimStopBtnTerminalStyle: React.CSSProperties = {
  color: '#f87171',
  borderColor: '#7f1d1d',
};
const slimZoomBtnStyle: React.CSSProperties = {
  width: 18, height: 18,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'transparent',
  border: 'none',
  color: '#6e7681',
  borderRadius: 3,
  cursor: 'pointer',
  fontSize: 11,
  padding: 0,
};
// Terminal-mode override: the slim head needs a darker base + lighter
// type so it reads on the `#0d1117` tile background.
const tileSlimHeadTerminalStyle: React.CSSProperties = {
  background: '#0d1117',
  borderBottom: '1px solid #21262d',
};
const tileConversationStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
};
const tileReplyStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-end',
  gap: 6,
  padding: '8px 10px',
  borderTop: '1px solid var(--border-light)',
  background: 'var(--bg-elevated)',
  flexShrink: 0,
};
const tileReplyTextareaStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 28,
  maxHeight: 120,
  resize: 'none',
  overflowY: 'auto',
  padding: '6px 8px',
  fontFamily: 'inherit',
  fontSize: 12,
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  outline: 'none',
};
// Terminal-mode overrides — the reply row blends with the dark tile
// while keeping the input itself readable. Monospace matches the
// conversation pane above.
const tileReplyTerminalStyle: React.CSSProperties = {
  background: '#0d1117',
  borderTop: '1px solid #21262d',
};
const tileReplyTextareaTerminalStyle: React.CSSProperties = {
  background: '#161b22',
  color: 'rgba(255,255,255,0.92)',
  border: '1px solid #30363d',
  fontFamily: 'var(--font-mono)',
};
const tileReplyActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 4,
  flexShrink: 0,
};
const tileInterruptBtnStyle: React.CSSProperties = {
  padding: '4px 8px',
  background: 'transparent',
  color: 'var(--accent-dark)',
  border: '1px solid var(--accent-a40)',
  borderRadius: 5,
  fontSize: 11,
  fontWeight: 600,
  cursor: 'pointer',
};
const tileSendBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'var(--accent)',
  color: '#fff',
  border: 'none',
  borderRadius: 5,
  fontSize: 11.5,
  fontWeight: 600,
  cursor: 'pointer',
};
const tileFooterStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '8px 14px',
  borderTop: '1px solid var(--border-light)',
  background: 'var(--bg-card)',
  fontSize: 11,
};
const footerMetaStyle: React.CSSProperties = { color: 'var(--text-3)' };
const footerSepStyle: React.CSSProperties = { color: 'var(--text-4)' };
const footerMetricStyle: React.CSSProperties = {
  color: 'var(--text-2)',
  fontWeight: 600,
  fontVariantNumeric: 'tabular-nums',
};
const emptyStyle: React.CSSProperties = {
  padding: '40px 24px',
  textAlign: 'center',
  border: '1px dashed #d1d5db',
  borderRadius: 8,
};
const emptyTitleStyle: React.CSSProperties = { fontSize: 16, fontWeight: 600, marginBottom: 4 };
const mutedStyle: React.CSSProperties = { color: '#6b7280', fontSize: 13 };
