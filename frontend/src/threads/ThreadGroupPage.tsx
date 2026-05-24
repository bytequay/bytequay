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
import { useEffect, useMemo, useState } from 'react';
import GroupThreadGrid from './GroupThreadGrid';
import ThreadsGroupSidebar from './ThreadsGroupSidebar';
import ThreadZoomModal from './ThreadZoomModal';
import type { ThreadDto, ThreadGroupDto } from '../types';
import type { PendingPermission } from './ConversationPane';
import type { ActiveTurnSummary } from './threadTurnSummary';

/**
 * threads-group page shell. Replaces the regular {@code ThreadsPage}
 * chrome (left rail / breadcrumb / header / layout selector) with
 * a compact 200px sidebar plus a chrome-free, tmux-style tile grid
 * that fills the rest of the viewport.
 *
 * <p>Follows {@code docs/mockups/design/threads/threads-group.png}
 * (default) and {@code threads-group-immersive.png} (immersive mode
 * collapses both the topnav and the rail down to 4px slivers; Esc
 * exits). The zoom modal in {@code threads-group-zoom.png} is a
 * follow-up and not wired here yet.
 */
export type ThreadGroupPageProps = {
  group: ThreadGroupDto;
  /** Threads belonging to this group. The parent has already filtered
   *  to group membership before passing them in. */
  threads: ThreadDto[];
  /** Active scheduler state keyed by thread id. Queued follow-ups can
   *  exist while the thread row itself still says IDLE. */
  activeTurnsByThreadId: Map<string, ActiveTurnSummary>;
  busyId: string | null;
  onSelectTask: (threadId: string) => void;
  onStop: (threadId: string) => void | Promise<void>;
  onSend: (threadId: string, input: string) => void | Promise<void>;
  onInterrupt: (threadId: string) => void | Promise<void>;
  onDecide: (
    threadId: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void | Promise<void>;
  onAddTask: () => void;
  onOpenGroupSettings: () => void;
  onRefresh: () => void | Promise<void>;
  /** Toggled by the sidebar's ⛶ button. The parent owns the state
   *  so it can also lift the topnav into immersive mode (which lives
   *  outside this shell). Esc inside the page exits. */
  immersive: boolean;
  onChangeImmersive: (next: boolean) => void;
  /** Per-tile visual mode — Chat (default) or Terminal. Owned by
   *  ThreadsPage so the bit survives navigating away from the page;
   *  toggled by {@code ⌘T} from inside the group view. */
  tileMode: 'chat' | 'terminal';
  onChangeTileMode: (next: 'chat' | 'terminal') => void;
  /** Pass-through for the per-tile PR / Issue chip clicks. Parent
   *  (ThreadsPage) resolves the thread's working dir into an owner/repo
   *  via the watched-repos list and navigates accordingly. */
  onOpenPr: (thread: ThreadDto, prNumber: number) => void;
  onOpenIssue: (thread: ThreadDto, issueNumber: number) => void;
  /** Clears the group filter and returns to the full thread list view.
   *  Surfaced both in the topnav breadcrumb and in the rail's back
   *  link so the user always has an exit. */
  onBackToAll: () => void;
};

const GROUP_MAX_MEMBERS = 4;

export default function ThreadGroupPage(props: ThreadGroupPageProps) {
  const {
    group, threads, activeTurnsByThreadId, busyId,
    onSelectTask, onStop, onSend, onInterrupt, onDecide,
    onAddTask, onOpenGroupSettings, onRefresh,
    immersive, onChangeImmersive, tileMode, onChangeTileMode,
    onOpenPr, onOpenIssue, onBackToAll,
  } = props;
  void onChangeTileMode; // toggling lives at ThreadsPage level (⌘T)

  const [zoomedTaskId, setZoomedTaskId] = useState<string | null>(null);
  // Selection lives at the page level (not GroupThreadGrid) so the
  // Esc precedence chain — modal → immersive → deselect — can read
  // and clear the active tile from one place.
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const zoomedTask = useMemo(
    () => zoomedTaskId === null ? null : threads.find(t => t.id === zoomedTaskId) ?? null,
    [zoomedTaskId, threads]);

  // Drop the zoom whenever the underlying thread disappears from the
  // group — keeps a stale modal from lingering after a stop+delete.
  useEffect(() => {
    if (zoomedTaskId !== null && zoomedTask === null) {
      setZoomedTaskId(null);
    }
  }, [zoomedTaskId, zoomedTask]);

  // First-tile default selection: when the page first has threads (or
  // the previously selected tile is gone), focus the first one so the
  // user can start typing without an explicit click. Esc later clears
  // this; clicking another tile picks it.
  useEffect(() => {
    if (threads.length === 0) {
      if (selectedId !== null) setSelectedId(null);
      return;
    }
    if (selectedId !== null && !threads.some(t => t.id === selectedId)) {
      setSelectedId(threads[0].id);
      return;
    }
    if (selectedId === null) {
      setSelectedId(threads[0].id);
    }
  }, [threads, selectedId]);

  // PermissionRequested pre-rendering — the tiles read pendingPermission
  // from the messages they fetch internally; we don't surface it at the
  // shell level.
  void ([] as PendingPermission[]);

  // Esc precedence: modal owns it when open (ThreadZoomModal binds its
  // own handler), otherwise immersive exits, otherwise the active
  // tile deselects. We register a single handler here that walks the
  // chain in the right order; the modal's own handler runs in
  // parallel but our `if (zoomedTaskId !== null) return` keeps us
  // from also exiting immersive while it's closing.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      if (zoomedTaskId !== null) return; // modal owns this Esc
      if (immersive) {
        e.preventDefault();
        onChangeImmersive(false);
        return;
      }
      if (selectedId !== null) {
        e.preventDefault();
        // Blur the active input so type-to-reply doesn't keep going
        // after the visual selection is gone.
        const focused = document.activeElement;
        if (focused instanceof HTMLElement) focused.blur();
        setSelectedId(null);
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [immersive, onChangeImmersive, zoomedTaskId, selectedId]);

  // Page fills the full viewport while immersive (the topbar is
  // hidden at the App level so app-content gets the whole window).
  // Otherwise we keep the historical 56px reserve the rest of the
  // app uses for threads-pages. Terminal mode also flips the page
  // background to the Warp/tmux dark `#0d1117` so the rail and the
  // gaps between tiles read as one continuous monospace surface.
  const baseShell: React.CSSProperties = tileMode === 'terminal'
    ? { ...shellStyle, background: '#0d1117' }
    : shellStyle;
  const sectionStyle: React.CSSProperties = immersive
    ? { ...baseShell, height: '100vh' }
    : baseShell;

  return (
    <section style={sectionStyle}>
      {!immersive && (
        <ThreadsGroupSidebar
          group={group}
          threads={threads}
          canAddTask={threads.length < GROUP_MAX_MEMBERS}
          onAddTask={onAddTask}
          onOpenGroupSettings={onOpenGroupSettings}
          onRefresh={() => void onRefresh()}
          onToggleImmersive={() => onChangeImmersive(!immersive)}
          immersive={immersive}
          onBackToAll={onBackToAll}
        />
      )}
      <main style={mainStyle}>
        {immersive && (
          <div style={immersiveTopBarStyle}>
            <span style={immBrandStyle}>ByteQuay</span>
            <span style={immGroupChipStyle}>
              <span style={immGroupDotStyle} aria-hidden />
              {group.name}
              <span style={immGroupCountStyle}>
                {threads.length}/{GROUP_MAX_MEMBERS}
              </span>
            </span>
            <div style={immModeTogStyle}>
              <button
                type="button"
                onClick={() => onChangeTileMode('chat')}
                style={immModeBtnStyle(tileMode === 'chat')}
              >
                Conversation
              </button>
              <button
                type="button"
                onClick={() => onChangeTileMode('terminal')}
                style={immModeBtnStyle(tileMode === 'terminal')}
              >
                Terminal
              </button>
            </div>
            <span style={immSpacerStyle} />
            <span style={immHintStyle}>
              <kbd style={immKbdStyle}>↑↓</kbd> {threads.length} panes
              <span style={{ margin: '0 6px', opacity: 0.4 }}>·</span>
              <kbd style={immKbdStyle}>⌘K</kbd> jump
            </span>
            <button
              type="button"
              onClick={() => onChangeImmersive(false)}
              style={immExitBtnStyle}
              title="Exit immersive (esc)"
            >
              ✕ Exit immersive
            </button>
          </div>
        )}
        <GroupThreadGrid
          threads={threads}
          activeTurnsByThreadId={activeTurnsByThreadId}
          busyId={busyId}
          immersive={immersive}
          tileMode={tileMode}
          selectedId={selectedId}
          onSelectTile={setSelectedId}
          // Tiles open the zoom modal — the design intentionally
          // hides the direct path to the full detail page from the
          // tile level. The modal's ⛶ button is the only way to
          // navigate into /threads/:id.
          onOpen={threadId => setZoomedTaskId(threadId)}
          onOpenPr={onOpenPr}
          onOpenIssue={onOpenIssue}
          onStop={onStop}
          onSend={onSend}
          onInterrupt={onInterrupt}
          onDecide={onDecide}
        />
      </main>

      {/* Always-on mode pill — bottom-right reminder of how to exit
          (immersive) AND how to switch visual modes. Pointer-events
          off so it never intercepts clicks on the tiles below. */}
      {(immersive || tileMode === 'terminal') && (
        <div style={exitPillStyle} aria-hidden>
          {tileMode === 'terminal'
            ? (immersive ? '⌨ TERMINAL · ⌘T → Chat · Esc to exit' : '⌨ TERMINAL · ⌘T → Chat')
            : '⛶ Esc to exit immersive'}
        </div>
      )}

      {zoomedTask !== null && (
        <ThreadZoomModal
          thread={zoomedTask}
          onClose={() => setZoomedTaskId(null)}
          onExpandToDetail={onSelectTask}
        />
      )}
    </section>
  );
}

const shellStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'row',
  alignItems: 'stretch',
  // App hides the 44px topbar in immersive mode, so the page can
  // claim the full viewport height. CSS calc keeps it pixel-correct
  // either way — the variable is set via the `--topbar-h` custom
  // property when the topbar is hidden (falls back to the historical
  // 56px the rest of the layout uses).
  height: 'calc(100vh - 56px)',
  background: 'var(--bg-base)',
  position: 'relative',
};

const immersiveTopBarStyle: React.CSSProperties = {
  // Slim dark bar across the top of the immersive board — replaces
  // the workspace rail + top-nav while the user is driving N agents
  // in parallel. Matches docs/mockups/design/tasks/thread-group-immersive.png.
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '6px 14px',
  background: '#0d1117',
  color: '#cdd6f4',
  borderBottom: '1px solid #1f2937',
  fontSize: 11,
  flexShrink: 0,
};

const immBrandStyle: React.CSSProperties = {
  fontWeight: 700,
  color: '#22c55e',
  letterSpacing: '0.02em',
};

const immGroupChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '2px 8px',
  background: 'rgba(124, 58, 237, 0.15)',
  border: '1px solid rgba(124, 58, 237, 0.40)',
  borderRadius: 6,
  color: '#c4b5fd',
  fontSize: 11,
};

const immGroupDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  borderRadius: 999,
  background: '#7c3aed',
};

const immGroupCountStyle: React.CSSProperties = {
  color: '#94a3b8',
  fontSize: 10,
  marginLeft: 4,
};

const immModeTogStyle: React.CSSProperties = {
  display: 'inline-flex',
  gap: 2,
  background: '#1f2937',
  padding: 2,
  borderRadius: 6,
};

function immModeBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '2px 10px',
    fontSize: 11,
    border: 'none',
    background: active ? '#0d9488' : 'transparent',
    color: active ? '#0a0e14' : '#94a3b8',
    borderRadius: 4,
    cursor: 'pointer',
    fontWeight: active ? 700 : 500,
  };
}

const immSpacerStyle: React.CSSProperties = { flex: 1 };

const immHintStyle: React.CSSProperties = {
  color: '#94a3b8',
  fontSize: 10,
};

const immKbdStyle: React.CSSProperties = {
  background: '#1f2937',
  color: '#cdd6f4',
  padding: '1px 6px',
  borderRadius: 3,
  fontSize: 9,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const immExitBtnStyle: React.CSSProperties = {
  border: '1px solid rgba(34, 197, 94, 0.30)',
  background: 'rgba(34, 197, 94, 0.12)',
  color: '#22c55e',
  padding: '3px 10px',
  fontSize: 11,
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const mainStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  // No padding — the mockup is explicit that tiles butt right up
  // against the rail and screen edges like tmux panes.
};

// Quiet bottom-right reminder when immersive is on. Pointer-events
// off so it never intercepts clicks; the user dismisses immersive
// via Esc (or ⌘\).
const exitPillStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 12,
  right: 16,
  padding: '4px 10px',
  background: 'rgba(13, 17, 23, 0.85)',
  color: 'rgba(255, 255, 255, 0.85)',
  borderRadius: 999,
  fontSize: 10.5,
  fontWeight: 600,
  letterSpacing: '0.02em',
  pointerEvents: 'none',
  zIndex: 5,
  boxShadow: '0 2px 8px rgba(0, 0, 0, 0.18)',
};
