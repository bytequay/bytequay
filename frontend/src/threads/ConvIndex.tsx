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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ConvIndexEntryDto, WorkUnitTaskDto } from '../types';
import { useConvIndex } from './useConvIndex';
import { useThreadTasks } from './useThreadTasks';

type Variant = 'light' | 'dark';

type Props = {
  threadId: string;
  /** The scrollable container that hosts the rendered transcript.
   *  Clicking a row scrolls this element to the matching turn (the
   *  user-message row carries a {@code data-seq=<seq>} attribute). */
  scrollContainerRef: React.RefObject<HTMLElement | null>;
  /** Optional: pass through the upstream SSE event name so the index
   *  refetches its tail window on UserMessage / TurnDone. The parent
   *  is the canonical SSE subscriber; this hook just borrows its
   *  trigger so we don't open a second stream per thread. */
  onSseEvent?: React.MutableRefObject<((name: string) => void) | null>;
  /** Visual variant — terminal mode uses inverted colours so the rail
   *  reads against the dark/light terminal background instead of the
   *  structured view's plain surface. Defaults to {@code light}. */
  variant?: Variant;
  /** When set, restrict the rail to prompts whose seq is in this set —
   *  i.e. the prompts the host pane actually renders. The backend index
   *  is thread-wide (trunk + every task), but a focused Task window only
   *  draws *its* slice, so an unrestricted rail would list trunk and
   *  sibling-task prompts that have no row to scroll to. Passing the
   *  pane's own prompt seqs keeps every rail row clickable and scopes
   *  the index to "that Task's prompts" per the design. Omit in the
   *  trunk, where the thread-wide list is intended. */
  restrictToSeqs?: ReadonlySet<number>;
  /** When set, the rail lists exactly these entries and never talks to
   *  the thread-wide backend index. The stage pages use this: a stage's
   *  transcript lives in its own per-stage message store whose seqs
   *  don't exist in the task thread, so the thread index can't describe
   *  it — but the host already has the full transcript loaded and can
   *  hand the prompts over directly. */
  localEntries?: ConvIndexEntryDto[];
  /** Which gutter the rail floats in. Defaults to {@code right}; the
   *  trunk anchors it left, clear of its right task panel. */
  side?: 'left' | 'right';
};

/**
 * Floating Codex-style conversation-index rail. Default state is a
 * narrow right-gutter stack of dash marks — one per loaded user prompt.
 * Hovering anywhere on the rail expands it into the full preview panel
 * so the user can read prompt previews and click-jump to a turn.
 *
 * <p>Rows are clickable. Each row's click handler runs
 * {@code scrollIntoView} on the user-message row in the agent
 * transcript that carries the matching {@code data-seq} attribute.
 *
 * <p>The "current" row is whatever index entry has the highest seq
 * — i.e. the most recent prompt — highlighted with the accent colour.
 */
export function ConvIndex({
  threadId, scrollContainerRef, onSseEvent, variant = 'light', restrictToSeqs, localEntries, side = 'right',
}: Props) {
  const idx = useConvIndex(threadId);
  const { tasks } = useThreadTasks(threadId);
  const local = localEntries !== undefined;
  // Scope the thread-wide index to the host pane's own prompts when the
  // caller asks. A focused Task passes its loaded prompt seqs so the
  // rail lists only that Task's prompts (all clickable); the trunk omits
  // it and keeps the full thread-wide list. localEntries bypasses the
  // backend index outright (per-stage transcripts).
  const scoped = local || restrictToSeqs !== undefined;
  const entries = useMemo(
    () => local
      ? localEntries
      : restrictToSeqs !== undefined
        ? idx.entries.filter(e => restrictToSeqs.has(e.seq))
        : idx.entries,
    [local, localEntries, idx.entries, restrictToSeqs]);
  // Always start collapsed. The rail is a peripheral hint — hover or
  // focus it to expand into the full prompt-preview panel.
  const [expanded, setExpanded] = useState(false);
  // The user-picked row stays bound to that seq. Until they click one,
  // it's null and the highlight follows the newest prompt.
  const [pickedSeq, setPickedSeq] = useState<number | null>(null);

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
    setPickedSeq(seq);
    const el = host.querySelector(`[data-seq="${seq}"]`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [scrollContainerRef]);

  // While loading initial state or if the pane genuinely has no
  // prompts yet, render nothing — the rail is meant to surface
  // navigation for content that exists, not to flash an empty strip.
  if (entries.length === 0) {
    return null;
  }

  const palette = variant === 'dark' ? DARK_PALETTE : LIGHT_PALETTE;
  // A picked row stays bound to its seq (as long as it's still in the
  // list); otherwise the highlight tracks the newest prompt.
  const currentSeq = pickedSeq !== null && entries.some(e => e.seq === pickedSeq)
    ? pickedSeq
    : entries.length > 0
      ? entries[entries.length - 1].seq
      : null;
  const loaded = entries.length;
  // When scoped to a single pane, "of M" is just the loaded count —
  // there's no larger thread-wide total to page toward, and pulling
  // older thread-wide prompts wouldn't add to this pane's slice.
  const total = scoped ? entries.length : idx.total;
  const remaining = Math.max(0, total - loaded);
  const olderCount = Math.min(50, remaining);
  const canLoadMore = scoped ? false : idx.canLoadMore;

  return (
    <aside
      style={{
        ...(expanded ? panelExpandedStyle(palette) : panelCollapsedStyle(palette)),
        ...(side === 'left' ? { left: 6, right: 'auto' } : {}),
      }}
      onMouseEnter={() => setExpanded(true)}
      onMouseLeave={() => setExpanded(false)}
      onFocusCapture={() => setExpanded(true)}
      onBlurCapture={e => {
        // Only collapse when focus leaves the rail entirely; child
        // tab moves between rows should keep us open.
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
          setExpanded(false);
        }
      }}
      aria-label="Conversation index"
    >
      {expanded ? (
        <ExpandedPanel
          entries={entries}
          total={total}
          loaded={loaded}
          currentSeq={currentSeq}
          canLoadMore={canLoadMore}
          loadingMore={idx.loadingMore}
          olderCount={olderCount}
          error={idx.error}
          onLoadOlder={() => { void idx.loadOlder(); }}
          onPick={scrollToSeq}
          palette={palette}
          tasks={scoped ? [] : (tasks ?? [])}
        />
      ) : (
        <CollapsedStrip
          entries={entries}
          currentSeq={currentSeq}
          onPick={scrollToSeq}
          palette={palette}
        />
      )}
    </aside>
  );
}

function CollapsedStrip({
  entries, currentSeq, onPick, palette,
}: {
  entries: ConvIndexEntryDto[];
  currentSeq: number | null;
  onPick: (seq: number) => void;
  palette: Palette;
}) {
  return (
    <div style={collapsedListStyle}>
      {entries.map(e => {
        const isCurrent = e.seq === currentSeq;
        return (
          <button
            key={e.seq}
            type="button"
            onClick={ev => {
              // Prevent the rail's outer onMouseEnter/Leave from
              // bouncing — the click should land on the row, not
              // expand the panel as a side effect.
              ev.stopPropagation();
              onPick(e.seq);
            }}
            title={e.preview}
            aria-label={`Jump to: ${e.preview}`}
            style={collapsedTickStyle(palette)}
          >
            <span
              aria-hidden
              style={isCurrent
                ? { ...collapsedBarStyle(palette), ...collapsedBarCurrentStyle(palette) }
                : collapsedBarStyle(palette)}
            />
          </button>
        );
      })}
    </div>
  );
}

function ExpandedPanel({
  entries, total, loaded, currentSeq,
  canLoadMore, loadingMore, olderCount,
  error, onLoadOlder, onPick, palette, tasks,
}: {
  entries: ConvIndexEntryDto[];
  total: number;
  loaded: number;
  currentSeq: number | null;
  canLoadMore: boolean;
  loadingMore: boolean;
  olderCount: number;
  error: string | null;
  onLoadOlder: () => void;
  onPick: (seq: number) => void;
  palette: Palette;
  tasks: WorkUnitTaskDto[];
}) {
  const groups = useMemo(() => groupEntriesByTask(entries, tasks), [entries, tasks]);
  return (
    <>
      <div style={headStyle(palette)}>
        <span>Conversation</span>
        <span style={headRightStyle(palette)}>{loaded} of {total}</span>
      </div>
      {canLoadMore && (
        <button
          type="button"
          onClick={onLoadOlder}
          disabled={loadingMore}
          style={loadHintStyle(palette)}
        >
          {loadingMore
            ? 'loading…'
            : `↑ load earlier ${olderCount} prompt${olderCount === 1 ? '' : 's'}`}
        </button>
      )}
      {error !== null && entries.length === 0 && (
        <div style={errorRowStyle}>{error}</div>
      )}
      <div style={rowsStyle}>
        {groups.map(group => (
          <div key={group.key}>
            {group.header !== null && (
              <div style={taskHeaderStyle(palette)}>{group.header}</div>
            )}
            {group.entries.map(e => (
              <ConvIndexRow
                key={e.seq}
                entry={e}
                isCurrent={e.seq === currentSeq}
                onClick={() => onPick(e.seq)}
                palette={palette}
              />
            ))}
          </div>
        ))}
      </div>
    </>
  );
}

type EntryGroup = {
  key: string;
  /** Rendered above the group when non-null. Suppressed for the
   *  brainstorm bucket on a thread that never created a task. */
  header: string | null;
  entries: ConvIndexEntryDto[];
};

/**
 * Bucket index entries by which task owns each one — derived from
 * the tasks' {@code firstMsgSeq} / {@code lastMsgSeq} ranges. Entries
 * with no covering task fall into a brainstorm bucket; the bucket
 * gets a header only when the thread also has at least one
 * materialised task, so a pure-brainstorm thread looks identical to
 * the pre-grouping rail.
 */
function groupEntriesByTask(
  entries: ConvIndexEntryDto[],
  tasks: WorkUnitTaskDto[],
): EntryGroup[] {
  if (entries.length === 0) {
    return [];
  }
  // Tasks that don't know their message range can't claim entries;
  // skip them silently rather than guessing.
  type Indexed = WorkUnitTaskDto & {
    firstMsgSeq?: number | null;
    lastMsgSeq?: number | null;
  };
  const ranged = (tasks as Indexed[])
    .filter(t => typeof t.firstMsgSeq === 'number' && typeof t.lastMsgSeq === 'number')
    .sort((a, b) => (a.firstMsgSeq ?? 0) - (b.firstMsgSeq ?? 0));

  function ownerFor(seq: number): Indexed | null {
    for (const t of ranged) {
      const first = t.firstMsgSeq ?? 0;
      const last = t.lastMsgSeq ?? Number.POSITIVE_INFINITY;
      if (seq >= first && seq <= last) return t;
    }
    return null;
  }

  const buckets = new Map<string, EntryGroup>();
  const orderedKeys: string[] = [];
  for (const e of entries) {
    const owner = ownerFor(e.seq);
    const key = owner === null ? '__brainstorm__' : owner.id;
    let bucket = buckets.get(key);
    if (bucket === undefined) {
      const header = owner === null
        ? null
        : `Task ${owner.seq}${owner.branchName !== null ? ` · ${owner.branchName}` : ''}`;
      bucket = { key, header, entries: [] };
      buckets.set(key, bucket);
      orderedKeys.push(key);
    }
    bucket.entries.push(e);
  }
  // Single brainstorm bucket on a thread with no materialised tasks
  // → fall back to the original flat rendering by clearing the
  // header. Matches the pre-grouping pre-Task behaviour.
  if (orderedKeys.length === 1
      && orderedKeys[0] === '__brainstorm__'
      && ranged.length === 0) {
    const only = buckets.get('__brainstorm__')!;
    return [{ ...only, header: null }];
  }
  // Brainstorm bucket on a thread that *does* have tasks → label it
  // explicitly so the rail makes the prefix readable.
  const brainstorm = buckets.get('__brainstorm__');
  if (brainstorm !== undefined && brainstorm.header === null) {
    brainstorm.header = 'Brainstorm · before first task';
  }
  return orderedKeys.map(k => buckets.get(k)!);
}

function ConvIndexRow({
  entry, isCurrent, onClick, palette,
}: {
  entry: ConvIndexEntryDto;
  isCurrent: boolean;
  onClick: () => void;
  palette: Palette;
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
      style={isCurrent
        ? { ...rowStyle(palette), ...rowCurrentStyle }
        : rowStyle(palette)}
    >
      <span style={isCurrent
        ? { ...dashStyle(palette), ...dashCurrentStyle, alignSelf: 'start', paddingTop: 2 }
        : { ...dashStyle(palette), alignSelf: 'start', paddingTop: 2 }}>−</span>
      <span style={textStyle}>{entry.preview}</span>
    </div>
  );
}

// ── palette + styles ─────────────────────────────────────────────────

type Palette = {
  background: string;
  /** Lighter wash for the collapsed strip — readable backdrop for
   *  the "−" ticks without dominating the conversation underneath. */
  collapsedBackground: string;
  border: string;
  headColor: string;
  subColor: string;
  rowColor: string;
  dashColor: string;
  currentDashColor: string;
  shadow: string;
};

const LIGHT_PALETTE: Palette = {
  background: 'rgba(255, 255, 255, 0.95)',
  collapsedBackground: 'rgba(255, 255, 255, 0.72)',
  border: 'rgba(0, 0, 0, 0.10)',
  headColor: '#6e7681',
  subColor: '#afb8c1',
  rowColor: '#57606a',
  dashColor: '#d4d4d4',
  currentDashColor: '#737373',
  shadow: '0 6px 18px rgba(0, 0, 0, 0.18), 0 1px 2px rgba(0, 0, 0, 0.12)',
};

const DARK_PALETTE: Palette = {
  background: 'rgba(20, 22, 28, 0.88)',
  collapsedBackground: 'rgba(20, 22, 28, 0.65)',
  border: 'rgba(255, 255, 255, 0.10)',
  headColor: '#c8d3e0',
  subColor: '#7c8794',
  rowColor: '#c8d3e0',
  dashColor: 'rgba(255, 255, 255, 0.34)',
  currentDashColor: 'rgba(255, 255, 255, 0.72)',
  shadow: '0 6px 18px rgba(0, 0, 0, 0.45), 0 1px 2px rgba(0, 0, 0, 0.30)',
};

// The rail is anchored to the vertical centre of the host pane so
// it sits in the user's natural focal area regardless of how tall
// the conversation is. translateY(-50%) keeps "centre" honest as
// the rail's own height changes (collapsed vs expanded).
//
// Codex-style: the collapsed scrubber sits in the right conversation
// gutter as a naked stack of dash marks; hover expands the preview panel.
const baseAnchorStyle: React.CSSProperties = {
  position: 'absolute',
  right: -4,
  top: '50%',
  transform: 'translateY(-50%)',
  zIndex: 10,
  display: 'flex',
  flexDirection: 'column',
  // Hard cap so a long list scrolls inside the rail rather than
  // pushing it off the viewport.
  maxHeight: '70%',
  overflowY: 'auto',
  // 120ms feels responsive without ghosting the hover-out collapse.
  transition: 'width 120ms ease, padding 120ms ease, background 120ms ease',
};

function panelCollapsedStyle(_p: Palette): React.CSSProperties {
  return {
    ...baseAnchorStyle,
    width: 22,
    background: 'transparent',
    border: 'none',
    borderRadius: 0,
    padding: '4px 0',
    boxShadow: 'none',
    backdropFilter: 'none',
    WebkitBackdropFilter: 'none',
  };
}

function panelExpandedStyle(p: Palette): React.CSSProperties {
  return {
    ...baseAnchorStyle,
    width: 200,
    background: p.background,
    backdropFilter: 'blur(8px)',
    WebkitBackdropFilter: 'blur(8px)',
    border: `1px solid ${p.border}`,
    borderRadius: 8,
    padding: '6px 4px 8px',
    boxShadow: p.shadow,
  };
}

const collapsedListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 6,
  padding: 0,
};

function collapsedTickStyle(_p: Palette): React.CSSProperties {
  return {
    width: 18,
    height: 6,
    padding: 0,
    margin: 0,
    border: 'none',
    background: 'transparent',
    cursor: 'pointer',
    borderRadius: 0,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  };
}

function collapsedBarStyle(p: Palette): React.CSSProperties {
  return {
    width: 10,
    height: 2,
    borderRadius: 1,
    background: p.dashColor,
    display: 'block',
  };
}

function collapsedBarCurrentStyle(p: Palette): React.CSSProperties {
  return {
    width: 13,
    background: p.currentDashColor,
  };
}

function headStyle(p: Palette): React.CSSProperties {
  return {
    fontSize: 9.5,
    fontWeight: 700,
    letterSpacing: '0.06em',
    textTransform: 'uppercase',
    color: p.headColor,
    padding: '4px 10px 8px',
    display: 'flex',
    alignItems: 'baseline',
  };
}

function headRightStyle(p: Palette): React.CSSProperties {
  return {
    marginLeft: 'auto',
    fontWeight: 500,
    textTransform: 'none',
    letterSpacing: 0,
    fontSize: 10,
    color: p.subColor,
  };
}

function loadHintStyle(p: Palette): React.CSSProperties {
  return {
    margin: '0 6px 6px',
    padding: '5px 6px',
    fontSize: 9.5,
    textAlign: 'center',
    color: p.subColor,
    borderTop: `1px dashed ${p.border}`,
    borderBottom: 'none',
    borderLeft: 'none',
    borderRight: 'none',
    background: 'transparent',
    cursor: 'pointer',
    fontStyle: 'italic',
  };
}

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

function taskHeaderStyle(p: Palette): React.CSSProperties {
  return {
    fontSize: 10,
    fontWeight: 700,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    color: p.subColor,
    padding: '6px 10px 2px',
  };
}

function rowStyle(p: Palette): React.CSSProperties {
  return {
    display: 'grid',
    gridTemplateColumns: '12px 1fr',
    gap: 6,
    padding: '4px 8px',
    color: p.rowColor,
    cursor: 'pointer',
    borderRadius: 4,
    alignItems: 'center',
    fontSize: 11,
    border: 'none',
    background: 'transparent',
    textAlign: 'left',
  };
}

const rowCurrentStyle: React.CSSProperties = {
  color: 'var(--accent-dark)',
  fontWeight: 600,
  background: 'var(--accent-a10)',
};

function dashStyle(p: Palette): React.CSSProperties {
  return {
    color: p.dashColor,
  };
}

const dashCurrentStyle: React.CSSProperties = {
  color: 'var(--accent)',
};

const textStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  lineHeight: 1.35,
};
