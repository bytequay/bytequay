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
import { useCallback, useEffect } from 'react';
import type { ConvIndexEntryDto } from '../types';
import { useConvIndex } from './useConvIndex';

type Props = {
  taskId: string;
  /** The scrollable container that hosts the rendered transcript.
   *  Clicking a row scrolls this element to the matching turn (the
   *  user-message row carries a {@code data-seq=<seq>} attribute). */
  scrollContainerRef: React.RefObject<HTMLElement | null>;
  /** Optional: pass through the upstream SSE event name so the index
   *  refetches its tail window on UserMessage / TurnDone. The parent
   *  is the canonical SSE subscriber; this hook just borrows its
   *  trigger so we don't open a second stream per task. */
  onSseEvent?: React.MutableRefObject<((name: string) => void) | null>;
};

/**
 * Floating right-edge conversation-index panel for the agent
 * terminal. Mirrors the {@code .conv-index} card in
 * {@code docs/mockups/v2/tasks/_src/task-detail-tabs.html} —
 * translucent white card, 8 px backdrop-blur, soft shadow, sticky
 * near the top of the agent area, scrollable internally when the
 * list overflows.
 *
 * <p>Rows are clickable. Each row's click handler runs
 * {@code scrollIntoView} on the user-message row in the agent
 * terminal that carries the matching {@code data-seq} attribute —
 * {@link com.bytequay.app.service.tasks.ConvIndexService} returns
 * the seq, the structured-conversation renderer tags the row, and
 * this component connects the two.
 *
 * <p>The "current" row is whatever index entry has the highest seq
 * — i.e. the most recent prompt — highlighted in the primary
 * purple per the mockup's {@code .ci-row.current} rule.
 */
export function ConvIndex({ taskId, scrollContainerRef, onSseEvent }: Props) {
  const idx = useConvIndex(taskId);

  // Expose the hook's SSE callback to the parent without forcing a
  // prop-callback re-render cycle. The parent stashes the callback
  // in a ref and invokes it from its existing SSE handler. Cleared
  // on unmount so a stale handler can't keep firing against the
  // (now-gone) hook state.
  useEffect(() => {
    if (!onSseEvent) return;
    onSseEvent.current = idx.onUpstreamEvent;
    return () => { onSseEvent.current = null; };
  }, [onSseEvent, idx.onUpstreamEvent]);

  const scrollToSeq = useCallback((seq: number) => {
    // The structured-conversation renderer tags each user message
    // row with data-seq=<seq>. We scope the lookup to the host
    // container so a stray identical attribute elsewhere in the
    // page can't hijack the scroll.
    const host = scrollContainerRef.current;
    if (!host) return;
    const el = host.querySelector(`[data-seq="${seq}"]`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [scrollContainerRef]);

  // While loading initial state or if the task genuinely has no
  // prompts yet, render nothing — the panel is meant to surface
  // navigation for content that exists, not to flash an empty box.
  if (idx.loading && idx.entries.length === 0) {
    return null;
  }
  if (!idx.loading && idx.total === 0) {
    return null;
  }

  const currentSeq = idx.entries.length > 0
    ? idx.entries[idx.entries.length - 1].seq
    : null;
  const loaded = idx.entries.length;
  // The doc's label: "↑ load earlier K prompts" where K is the
  // smaller of the page size and the remaining unloaded prompts.
  const remaining = Math.max(0, idx.total - loaded);
  const olderCount = Math.min(50, remaining);

  return (
    <aside
      style={panelStyle}
      aria-label="Conversation index"
    >
      <div style={headStyle}>
        <span>Conversation</span>
        <span style={headRightStyle}>{loaded} of {idx.total}</span>
      </div>
      {idx.canLoadMore && (
        <button
          type="button"
          onClick={() => { void idx.loadOlder(); }}
          disabled={idx.loadingMore}
          style={loadHintStyle}
        >
          {idx.loadingMore
            ? 'loading…'
            : `↑ load earlier ${olderCount} prompt${olderCount === 1 ? '' : 's'}`}
        </button>
      )}
      {idx.error !== null && idx.entries.length === 0 && (
        <div style={errorRowStyle}>{idx.error}</div>
      )}
      <div style={rowsStyle}>
        {idx.entries.map(e => (
          <ConvIndexRow
            key={e.seq}
            entry={e}
            isCurrent={e.seq === currentSeq}
            onClick={() => scrollToSeq(e.seq)}
          />
        ))}
      </div>
    </aside>
  );
}

function ConvIndexRow({
  entry, isCurrent, onClick,
}: {
  entry: ConvIndexEntryDto;
  isCurrent: boolean;
  onClick: () => void;
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={e => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onClick();
        }
      }}
      title={entry.preview}
      style={isCurrent ? { ...rowStyle, ...rowCurrentStyle } : rowStyle}
    >
      <span style={isCurrent ? { ...dashStyle, ...dashCurrentStyle } : dashStyle}>—</span>
      <span style={textStyle}>{entry.preview}</span>
    </div>
  );
}

// CSS lifted verbatim from .conv-index in
// docs/mockups/v2/tasks/_src/task-detail-tabs.html. The mockup is
// the authoritative pixel reference; we keep widths, paddings,
// blur radius, and font sizes in sync with it.
const panelStyle: React.CSSProperties = {
  position: 'absolute',
  top: 64,
  right: 14,
  width: 188,
  maxHeight: 'calc(100% - 220px)',
  background: 'rgba(255, 255, 255, 0.95)',
  backdropFilter: 'blur(8px)',
  WebkitBackdropFilter: 'blur(8px)',
  border: '1px solid rgba(0, 0, 0, 0.10)',
  borderRadius: 8,
  padding: '6px 4px 8px',
  boxShadow: '0 6px 18px rgba(0, 0, 0, 0.18), 0 1px 2px rgba(0, 0, 0, 0.12)',
  zIndex: 10,
  display: 'flex',
  flexDirection: 'column',
  overflowY: 'auto',
};

const headStyle: React.CSSProperties = {
  fontSize: 9.5,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: '#6e7681',
  padding: '4px 10px 8px',
  display: 'flex',
  alignItems: 'baseline',
};

const headRightStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontWeight: 500,
  textTransform: 'none',
  letterSpacing: 0,
  fontSize: 10,
  color: '#afb8c1',
};

const loadHintStyle: React.CSSProperties = {
  margin: '0 6px 6px',
  padding: '5px 6px',
  fontSize: 9.5,
  textAlign: 'center',
  color: '#8b949e',
  borderTop: '1px dashed rgba(0, 0, 0, 0.06)',
  borderBottom: 'none',
  borderLeft: 'none',
  borderRight: 'none',
  background: 'transparent',
  cursor: 'pointer',
  fontStyle: 'italic',
};

const errorRowStyle: React.CSSProperties = {
  margin: '0 6px',
  padding: '6px 10px',
  fontSize: 10.5,
  color: '#b91c1c',
  fontStyle: 'italic',
};

const rowsStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
};

const rowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '12px 1fr',
  gap: 6,
  padding: '4px 8px',
  color: '#57606a',
  cursor: 'pointer',
  borderRadius: 4,
  alignItems: 'center',
  fontSize: 11,
  // Override defaults so the row reads as a label, not a button.
  border: 'none',
  background: 'transparent',
  textAlign: 'left',
};

const rowCurrentStyle: React.CSSProperties = {
  color: 'var(--accent-dark)',
  fontWeight: 600,
  background: 'var(--accent-a10)',
};

const dashStyle: React.CSSProperties = {
  color: '#afb8c1',
};

const dashCurrentStyle: React.CSSProperties = {
  color: 'var(--accent)',
};

const textStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  lineHeight: 1.35,
};
