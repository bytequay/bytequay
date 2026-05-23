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
import TaskDetailPage from './TaskDetailPage';
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
    return thread.activeTask?.id ?? null;
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
          <button
            type="button"
            style={ctxBtnStyle}
            onClick={() => setTaskId(null)}
            disabled={taskId === null}
            title="Jump to the thread trunk"
          >
            ↑ Thread
          </button>
          <span style={ctxPathStyle}>
            <span style={ctxThreadStyle}>{thread.title}</span>
            {taskId !== null && <span style={ctxSepStyle}>›</span>}
            {taskId !== null && (
              <span style={ctxTaskStyle}>
                Task {thread.activeTask?.id === taskId
                  ? `${thread.activeTask?.seq ?? ''}` : ''}
              </span>
            )}
            <span style={ctxModeStyle}>
              {taskId === null ? '· planning' : '· working'}
            </span>
          </span>
          <button
            type="button"
            style={ctxBtnStyle}
            onClick={() => { onClose(); onExpandToDetail(thread.id); }}
            title="Open the full window (leave the board)"
          >
            ⤢ Open full
          </button>
          <button
            type="button"
            style={ctxBtnStyle}
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
            <TaskDetailPage
              threadId={thread.id}
              taskId={taskId}
              onBackToTrunk={onBackToTrunk}
            />
          )}
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
  gap: 8,
  padding: '6px 12px',
  borderBottom: '1px solid rgba(0,0,0,0.06)',
  background: 'rgba(15, 23, 42, 0.04)',
  fontSize: 11,
};

const ctxBtnStyle: React.CSSProperties = {
  border: '1px solid rgba(0,0,0,0.10)',
  background: '#fff',
  padding: '3px 8px',
  fontSize: 11,
  borderRadius: 6,
  cursor: 'pointer',
  color: 'var(--text-2)',
};

const ctxPathStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  gap: 6,
  alignItems: 'center',
  overflow: 'hidden',
  whiteSpace: 'nowrap',
};

const ctxThreadStyle: React.CSSProperties = {
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
