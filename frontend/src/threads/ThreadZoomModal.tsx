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
import { useCallback, useEffect, useState } from 'react';
import ThreadTrunkPage from './ThreadTrunkPage';
import { TaskBrainRoute } from '../pages/TaskBrainRoute';
import type { ThreadDto } from '../types';

/**
 * Zoom overlay for a thread tile on the group board. Per the
 * workspace/thread/task design's "Thread groups · Zoom" section, the
 * zoom shows the thread's <strong>real</strong> trunk or task-detail
 * window (not a bespoke layout), framed by a slim board-context bar.
 *
 * <p>The thread remembers its last-active window — trunk vs which
 * task — between zoom sessions via {@code localStorage}, so re-opening
 * a zoomed thread resumes where the user left off rather than always
 * dropping back to the trunk.
 *
 * <p>{@code esc} returns to the board; the ⤢ button breaks out into
 * the full-window route (the same one the workspace's threads list
 * uses), which keeps the previous "expand to detail" affordance.
 */
export type ThreadZoomModalProps = {
  thread: ThreadDto;
  onClose: () => void;
  /** Open the full thread detail page — i.e. exit the overlay and
   *  route to the in-app thread window. */
  onExpandToDetail: (threadId: string) => void;
};

function focusKey(threadId: string): string {
  return `bytequay.threads.zoom.lastTaskId.${threadId}`;
}

function loadLastTaskId(threadId: string): string | null {
  try { return window.localStorage.getItem(focusKey(threadId)); }
  catch { return null; }
}

function storeLastTaskId(threadId: string, taskId: string | null) {
  try {
    if (taskId === null) window.localStorage.removeItem(focusKey(threadId));
    else window.localStorage.setItem(focusKey(threadId), taskId);
  }
  catch { /* private mode — best effort */ }
}

export default function ThreadZoomModal({
  thread, onClose, onExpandToDetail,
}: ThreadZoomModalProps) {
  // Per-thread last-active-window pointer. The board can re-open
  // whatever the user was last on (trunk vs a specific task) instead
  // of always dropping back to the trunk — the design explicitly calls
  // this out as a requirement of zoom.
  const [taskId, setTaskId] = useState<string | null>(() => {
    // Prefer an explicit prior choice; otherwise default to the
    // foreground task so the user lands on the active surface.
    const stored = loadLastTaskId(thread.id);
    if (stored !== null) return stored;
    return null;
  });

  useEffect(() => {
    storeLastTaskId(thread.id, taskId);
  }, [thread.id, taskId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const onOpenTask = useCallback((next: string) => {
    setTaskId(next);
  }, []);

  const onBackToTrunk = useCallback(() => {
    setTaskId(null);
  }, []);

  return (
    <div style={backdropStyle} onClick={onClose} role="dialog" aria-modal>
      <div
        style={frameStyle}
        onClick={e => e.stopPropagation()}
      >
        <div style={contextBarStyle}>
          {taskId === null ? (
            <span style={ctxTypeBadgeStyle('thread')}>◆ THREAD</span>
          ) : (
            <span style={ctxTypeBadgeStyle('task')}>
              ● TASK
            </span>
          )}
          {taskId !== null && (
            <button
              type="button"
              style={ctxLinkBtnStyle}
              onClick={() => setTaskId(null)}
              title="Back to the trunk (still zoomed)"
            >
              ← Thread
            </button>
          )}
          <span style={ctxTitleStyle}>{thread.title}</span>
          <span style={ctxHintStyle}>
            {taskId === null ? 'zoomed from the board' : 'switched in from the trunk'}
          </span>
          <span style={ctxSpacerStyle} />
          <span style={ctxPaneIndicatorStyle} title="Pane on the board">
            pane 1 of 4
            <span style={paneDotsStyle}>●●●●</span>
          </span>
          <button type="button" style={ctxArrowBtnStyle} title="Previous pane (←)">‹</button>
          <button type="button" style={ctxArrowBtnStyle} title="Next pane (→)">›</button>
          <button
            type="button"
            style={ctxOpenFullStyle}
            onClick={() => { onClose(); onExpandToDetail(thread.id); }}
            title="Open the full window (leave the board)"
          >
            ⤢ Open full
          </button>
          <button
            type="button"
            style={ctxCloseStyle}
            onClick={onClose}
            title="Return to the board (esc)"
          >
            ✕
          </button>
        </div>
        <div style={innerFrameStyle}>
          {taskId === null ? (
            <ThreadTrunkPage
              threadId={thread.id}
              onBack={onClose}
              onOpenTask={onOpenTask}
            />
          ) : (
            <TaskBrainRoute
              threadId={thread.id}
              taskId={taskId}
              onOpenStage={() => {}}
              onClosed={() => setTaskId(null)}
            />
          )}
          {/* Floating "reopens here next time" pill — sits on top-right
              of the embedded shell to echo the zoom-task mockup's
              persistence affordance. The actual pointer is written by
              the focusKey localStorage helper at the top of the file. */}
          {taskId !== null && (
            <div style={reopensHerePillStyle} aria-hidden>
              ↗ reopens here next time
            </div>
          )}
        </div>
        <div style={bottomHintStyle}>
          {taskId === null
            ? '← → walk threads on the board · click a task → its detail (also zoomed) · esc back to board'
            : '↑ Thread · back to the trunk (zoomed) · esc back to board · this thread reopens here next time'}
        </div>
      </div>
    </div>
  );
}

const backdropStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15, 23, 42, 0.55)',
  backdropFilter: 'blur(4px)',
  WebkitBackdropFilter: 'blur(4px)',
  zIndex: 1000,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 24,
};

const frameStyle: React.CSSProperties = {
  position: 'relative',
  width: 'min(1280px, 96vw)',
  height: 'min(900px, 92vh)',
  background: '#fafafe',
  borderRadius: 16,
  boxShadow: '0 30px 60px rgba(0,0,0,0.25)',
  overflow: 'hidden',
  display: 'flex',
  flexDirection: 'column',
};

const contextBarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '7px 14px',
  borderBottom: '1px solid rgba(0,0,0,0.08)',
  background: 'rgba(255, 255, 255, 0.96)',
  fontSize: 11,
};

function ctxTypeBadgeStyle(kind: 'thread' | 'task'): React.CSSProperties {
  const isTask = kind === 'task';
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    fontSize: 10,
    fontWeight: 700,
    letterSpacing: '0.06em',
    padding: '3px 8px',
    borderRadius: 6,
    color: isTask ? '#0d9488' : '#475569',
    background: isTask ? 'rgba(13,148,136,0.10)' : 'rgba(71,85,105,0.10)',
    border: `1px solid ${isTask ? 'rgba(13,148,136,0.30)' : 'rgba(71,85,105,0.30)'}`,
  };
}

const ctxLinkBtnStyle: React.CSSProperties = {
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  padding: '3px 8px',
  fontSize: 11,
  borderRadius: 6,
  cursor: 'pointer',
  color: 'var(--text-2)',
};

const ctxTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  maxWidth: 320,
};

const ctxHintStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontStyle: 'italic',
  fontSize: 11,
};

const ctxSpacerStyle: React.CSSProperties = { flex: 1 };

const ctxPaneIndicatorStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  color: 'var(--text-3)',
  fontSize: 11,
};

const paneDotsStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  letterSpacing: '2px',
  fontSize: 8,
};

const ctxArrowBtnStyle: React.CSSProperties = {
  width: 22,
  height: 22,
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  borderRadius: 6,
  fontSize: 12,
  color: 'var(--text-3)',
  cursor: 'pointer',
};

const ctxOpenFullStyle: React.CSSProperties = {
  border: '1px solid rgba(13,148,136,0.30)',
  background: '#fff',
  color: '#0d9488',
  padding: '3px 10px',
  fontSize: 11,
  borderRadius: 6,
  cursor: 'pointer',
  fontWeight: 600,
};

const ctxCloseStyle: React.CSSProperties = {
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  padding: '3px 8px',
  fontSize: 12,
  borderRadius: 6,
  cursor: 'pointer',
  color: 'var(--text-3)',
};

const reopensHerePillStyle: React.CSSProperties = {
  position: 'absolute',
  top: 90,
  right: 14,
  zIndex: 5,
  padding: '3px 10px',
  fontSize: 10,
  fontWeight: 600,
  letterSpacing: '0.02em',
  borderRadius: 999,
  background: 'rgba(13, 148, 136, 0.12)',
  color: '#0d9488',
  border: '1px solid rgba(13, 148, 136, 0.30)',
  pointerEvents: 'none',
  fontStyle: 'italic',
};

const bottomHintStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: 'rgba(15, 23, 42, 0.06)',
  borderTop: '1px solid rgba(0,0,0,0.06)',
  color: 'var(--text-3)',
  fontSize: 10,
  textAlign: 'center',
  fontStyle: 'italic',
};

const _legacyCtxThreadStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  maxWidth: 360,
};

const ctxSepStyle: React.CSSProperties = { color: 'var(--text-4)' };

const ctxTaskStyle: React.CSSProperties = {
  color: '#0d9488',
  fontWeight: 600,
};

const ctxModeStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontStyle: 'italic',
};

const innerFrameStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'auto',
  position: 'relative',
};
