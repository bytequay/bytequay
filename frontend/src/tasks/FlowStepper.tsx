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
import { Fragment, useCallback, useEffect, useState } from 'react';
import type {
  LinkedActivePrDto, MilestoneSummaryDto, NextPossibleDto, TaskTraceDto, TraceEventDto,
} from '../types';

type Mode = 'collapsed' | 'expanded';

/**
 * The task page's lifecycle flow display. Reads the
 * {@code GET /api/tasks/{id}/trace} read-model and renders the collapsed
 * six-bucket milestone overview. The expanded sequential timeline, the
 * sticky mode toggle, the parallel sub-status block, and the next-line
 * land on the same component in later steps — one component, two modes.
 */
export function FlowStepper({ taskId }: { taskId: string }) {
  const { data } = useTaskTrace(taskId);
  const [mode, toggleMode] = useLocalStorageMode(taskId);
  if (data === null) {
    return <div style={skeletonStyle}>Loading flow…</div>;
  }
  // Collapsed (default) is a single compact strip — the conversation is
  // the primary content, so "where am I" costs one ~50px row. Expanding
  // opens the full sequential timeline + sub-status + next-line + trace.
  if (mode === 'collapsed') {
    return <PhaseStrip trace={data} onExpand={toggleMode} />;
  }
  return <FlowDetail trace={data} hiddenCount={data.events.length} onToggle={toggleMode} />;
}

/** The expanded detail: sequential timeline + sub-status + next-line +
 *  trace. Rendered below the strip when the user opens the timeline. */
export function FlowDetail({ trace, hiddenCount, onToggle }: {
  trace: TaskTraceDto;
  hiddenCount: number;
  onToggle: () => void;
}) {
  return (
    <div style={flowStyle}>
      <FlowHead trace={trace} />
      <SequentialNodes trace={trace} />
      {trace.currentPhase !== null && WAIT_PHASES.has(trace.currentPhase)
        && trace.linkedActivePr !== null && (
        <ParallelStatus pr={trace.linkedActivePr} enteredAt={waitEnteredAt(trace)} />
      )}
      <NextLine options={trace.nextPossible} />
      <ModeToggle mode="expanded" hiddenCount={hiddenCount} onToggle={onToggle} />
      <TracePanel events={trace.events} />
    </div>
  );
}

// ── collapsed: one compact phase strip ────────────────────────────────

export function PhaseStrip({ trace, onExpand, expanded = false }: {
  trace: TaskTraceDto;
  onExpand: () => void;
  expanded?: boolean;
}) {
  // A settled task shows its journey as a one-line text trail (the stepper
  // is done), tinted green so a done task reads as done at a glance.
  if (trace.currentPhase === 'COMPLETED') {
    const trail = trace.milestoneSummary
      .filter(m => m.visits > 0).map(m => m.label).join(' → ');
    return (
      <div style={stripDoneStyle} data-testid="phase-strip">
        <span style={doneBadgeStyle}>✓ Done</span>
        {trail !== '' && <span style={trailStyle}>{trail}</span>}
        <span style={{ flex: 1, minWidth: 0 }} />
        <TimelineToggle onExpand={onExpand} expanded={expanded} />
      </div>
    );
  }
  return (
    <div style={stripStyle} data-testid="phase-strip">
      <MilestoneBuckets summary={trace.milestoneSummary} onClick={onExpand} />
      <span style={stripContextStyle}>{phaseContext(trace)}</span>
      <TimelineToggle onExpand={onExpand} expanded={expanded} />
    </div>
  );
}

function TimelineToggle({ onExpand, expanded = false }: { onExpand: () => void; expanded?: boolean }) {
  return (
    <button type="button" style={timelineBtnStyle} onClick={onExpand}
      title={expanded ? 'Collapse the timeline' : 'Expand the full sequential timeline'}>
      {expanded ? '▴' : '▾'} Timeline
    </button>
  );
}

/** Short "what's happening" line for the compact strip — derived from the
 *  trace, no new field. Prefers a live wait-state hint, else the latest
 *  transition's reason, else the active milestone. */
function phaseContext(trace: TaskTraceDto): string {
  const pr = trace.linkedActivePr;
  if (pr !== null && trace.currentPhase !== null && WAIT_PHASES.has(trace.currentPhase)) {
    if (pr.ciStatus === 'FAILING') return 'CI failing';
    if (pr.ciStatus === 'PENDING') return 'CI running';
    if (pr.draft) return 'draft — awaiting mark-ready';
    if (pr.changesRequestedCount > 0) return 'changes requested';
    return 'awaiting remote review';
  }
  const last = trace.events.length > 0 ? trace.events[trace.events.length - 1] : null;
  if (last !== null && last.reason !== null && last.reason !== '') {
    return last.reason;
  }
  return trace.milestoneSummary.find(m => m.active)?.label ?? '';
}

/** Sticky per-task collapsed/expanded preference, persisted in
 *  localStorage keyed by taskId so it survives reloads and never leaks
 *  across tasks. Returns the mode and a toggle. */
export function useLocalStorageMode(taskId: string): [Mode, () => void] {
  const key = `flowStepperMode:${taskId}`;
  const [mode, setMode] = useState<Mode>(() => readMode(key));
  // Re-read when the task changes (the component may be reused).
  useEffect(() => { setMode(readMode(key)); }, [key]);
  useEffect(() => { localStorage.setItem(key, mode); }, [key, mode]);
  const toggle = useCallback(
    () => setMode(m => (m === 'collapsed' ? 'expanded' : 'collapsed')), []);
  return [mode, toggle];
}

function readMode(key: string): Mode {
  return localStorage.getItem(key) === 'expanded' ? 'expanded' : 'collapsed';
}

const TERMINAL_PHASES = new Set(['COMPLETED']);

/** Polls the trace every 3s while the phase is non-terminal. */
export function useTaskTrace(taskId: string): {
  data: TaskTraceDto | null;
  error: string | null;
} {
  const [data, setData] = useState<TaskTraceDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (taskId === '') {
      setData(null);
      return;
    }
    try {
      const trace = await window.bridge.getTaskTrace(taskId);
      setData(trace);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [taskId]);

  useEffect(() => { void load(); }, [load]);

  const phase = data?.currentPhase ?? null;
  useEffect(() => {
    if (taskId === '' || (phase !== null && TERMINAL_PHASES.has(phase))) {
      return;
    }
    const handle = window.setInterval(() => { void load(); }, 3000);
    return () => window.clearInterval(handle);
  }, [taskId, phase, load]);

  return { data, error };
}

// ── flow head: current state pill + last transition ───────────────────

function FlowHead({ trace }: { trace: TaskTraceDto }) {
  const last = trace.events.length > 0 ? trace.events[trace.events.length - 1] : null;
  const milestoneLabel = trace.milestoneSummary.find(m => m.active)?.label
    ?? humanize(trace.currentMilestone);
  return (
    <div style={headStyle}>
      <span style={statePillStyle}>
        <span aria-hidden style={pulseDotStyle} />
        {(milestoneLabel ?? '—').toUpperCase()}
        {trace.currentPhase !== null && (
          <span style={pillSubStyle}>· precise: {trace.currentPhase}</span>
        )}
      </span>
      {last !== null && (
        <span style={headTsStyle}>
          → entered {relativeTime(last.transitionedAt)}
          {last.fromPhase !== null && ` · from ${last.fromPhase}`}
        </span>
      )}
    </div>
  );
}

// ── collapsed view: compact six-bucket mini-stepper ───────────────────

function MilestoneBuckets({ summary, onClick }: {
  summary: MilestoneSummaryDto[];
  onClick?: () => void;
}) {
  return (
    <div
      style={miniStepperStyle}
      role={onClick !== undefined ? 'button' : undefined}
      tabIndex={onClick !== undefined ? 0 : undefined}
      onClick={onClick}
      onKeyDown={onClick !== undefined
        ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }
        : undefined}
      title={onClick !== undefined ? 'Expand the full timeline' : undefined}
    >
      {summary.map((bucket, i) => (
        <Fragment key={bucket.milestone}>
          <Bucket bucket={bucket} />
          {i < summary.length - 1 && (
            <span aria-hidden style={miniConnStyle(connectorState(bucket, summary[i + 1]))} />
          )}
        </Fragment>
      ))}
    </div>
  );
}

type BucketState = 'active' | 'skipped' | 'reached' | 'future';

function bucketState(b: MilestoneSummaryDto): BucketState {
  if (b.active) return 'active';
  if (b.skipped) return 'skipped';
  if (b.visits > 0) return 'reached';
  return 'future';
}

function Bucket({ bucket }: { bucket: MilestoneSummaryDto }) {
  const state = bucketState(bucket);
  const terminal = bucket.milestone === 'MERGE' && bucket.visits > 0 && !bucket.active;
  return (
    <div style={miniStepStyle} data-milestone={bucket.milestone}
      data-state={terminal ? 'terminal' : state}>
      <span style={miniDotStyle(state, terminal)} aria-hidden />
      <span style={miniNameStyle(state)}>{bucket.label}</span>
      {bucket.visits > 1 && <span style={miniBadgeStyle(state)}>×{bucket.visits}</span>}
    </div>
  );
}

type ConnState = 'reached' | 'skipped' | 'future';

function connectorState(a: MilestoneSummaryDto, b: MilestoneSummaryDto): ConnState {
  if (a.skipped || b.skipped) return 'skipped';
  if (b.visits > 0 || b.active) return 'reached';
  return 'future';
}

// ── expanded view: one node per phase-event, in order ─────────────────

type SeqNodeModel = { key: string; label: string; state: BucketState; terminal: boolean };

function SequentialNodes({ trace }: { trace: TaskTraceDto }) {
  const { events, currentPhase, nextPossible } = trace;
  let activeIdx = events.length - 1;
  for (let i = events.length - 1; i >= 0; i--) {
    if (events[i].toPhase === currentPhase) { activeIdx = i; break; }
  }
  const terminalReached = currentPhase !== null && TERMINAL_PHASES.has(currentPhase);

  const nodes: SeqNodeModel[] = events.map((e, i) => ({
    key: `e-${i}`,
    label: e.label,
    state: i === activeIdx ? 'active' : 'reached',
    terminal: e.toPhase === 'COMPLETED',
  }));
  // Predicted future nodes (dim) — where the sequence could go next.
  if (!terminalReached) {
    for (const n of nextPossible) {
      nodes.push({ key: `f-${n.trigger}`, label: n.label, state: 'future', terminal: n.trigger === 'COMPLETED' });
    }
  }

  return (
    <div style={stepperStyle}>
      {nodes.map((node, i) => (
        <div key={node.key} style={stepGroupStyle}>
          <div style={stepStyle} data-node={node.label} data-state={node.terminal ? 'terminal' : node.state}>
            <span style={dotStyle(node.state, node.terminal)} aria-hidden>
              {dotGlyph(node.state, node.terminal)}
            </span>
            <span style={nameStyle(node.state)}>{node.label}</span>
          </div>
          {i < nodes.length - 1 && (
            <span aria-hidden style={connectorStyle(
              nodes[i + 1].state === 'future' ? 'future' : 'reached')} />
          )}
        </div>
      ))}
    </div>
  );
}

// ── parallel sub-status block (shared; only in wait-states) ───────────

const WAIT_PHASES = new Set(['PUSHED_AWAITING_CI', 'AWAITING_READY', 'AWAITING_REMOTE_REVIEW']);

type AxisTone = 'live' | 'good' | 'warn' | 'muted';
type AxisModel = { tone: AxisTone; glyph: string; text: string; det?: string };

function ParallelStatus({ pr, enteredAt }: { pr: LinkedActivePrDto; enteredAt: string | null }) {
  // Re-render each second so the CI elapsed counter ticks live between
  // the 3s trace polls.
  useSecondTick();
  const ci = ciAxis(pr, enteredAt);
  const reviewers = reviewersAxis(pr);
  return (
    <div style={parStatusStyle} data-testid="parallel-status">
      <div style={parHeadStyle}>
        <span aria-hidden>⊞</span>
        Wait on PR · parallel statuses
        <span style={parWhyStyle}>whichever event fires first drives the next transition</span>
      </div>
      <div style={parPillsStyle}>
        <span style={parLblStyle}>CI</span>
        <Axis axis={ci} />
        <span style={parLblStyle}>Reviewers</span>
        <Axis axis={reviewers} />
        <span style={parLblStyle}>PR state</span>
        <Axis axis={{ tone: pr.draft ? 'muted' : 'good', glyph: pr.draft ? '○' : '✓',
          text: pr.draft ? 'draft' : 'ready' }} />
        <span style={parLblStyle}>Approvals</span>
        <Axis axis={{
          tone: pr.approvalCount > 0 ? 'good' : 'muted',
          glyph: pr.approvalCount > 0 ? '✓' : '○',
          text: `${pr.approvalCount} approval${pr.approvalCount === 1 ? '' : 's'}`,
          det: pr.changesRequestedCount > 0
            ? `${pr.changesRequestedCount} change${pr.changesRequestedCount === 1 ? '' : 's'} requested`
            : undefined,
        }} />
      </div>
    </div>
  );
}

function Axis({ axis }: { axis: AxisModel }) {
  return (
    <span style={axisPillStyle(axis.tone)}>
      <span aria-hidden style={{ lineHeight: 1 }}>{axis.glyph}</span>
      {axis.text}
      {axis.det !== undefined && <span style={axisDetStyle}>· {axis.det}</span>}
    </span>
  );
}

function ciAxis(pr: LinkedActivePrDto, enteredAt: string | null): AxisModel {
  switch (pr.ciStatus) {
    case 'PASSING': return { tone: 'good', glyph: '✓', text: 'passing' };
    case 'FAILING': return { tone: 'warn', glyph: '✕', text: 'failing' };
    case 'PENDING':
      return {
        tone: 'live', glyph: '⏳', text: 'running',
        det: enteredAt !== null ? elapsed(enteredAt) : undefined,
      };
    default: return { tone: 'muted', glyph: '○', text: 'no CI gate' };
  }
}

function reviewersAxis(pr: LinkedActivePrDto): AxisModel {
  const names = pr.requestedReviewers.length > 0
    ? pr.requestedReviewers.slice(0, 2).map(r => `@${r}`).join(', ')
      + (pr.requestedReviewers.length > 2 ? ` +${pr.requestedReviewers.length - 2}` : '')
    : undefined;
  if (pr.changesRequestedCount > 0) {
    return { tone: 'warn', glyph: '↺', text: 'changes requested', det: names };
  }
  if (pr.pendingReviewerCount > 0) {
    return { tone: 'live', glyph: '◷', text: `${pr.pendingReviewerCount} awaiting`, det: names };
  }
  if (names !== undefined) {
    return { tone: 'muted', glyph: '◷', text: 'requested', det: names };
  }
  return { tone: 'muted', glyph: '○', text: 'none requested' };
}

/** Timestamp the task entered its current phase — the start of the
 *  current wait, used for the live CI counter. */
function waitEnteredAt(trace: TaskTraceDto): string | null {
  for (let i = trace.events.length - 1; i >= 0; i--) {
    if (trace.events[i].toPhase === trace.currentPhase) {
      return trace.events[i].transitionedAt;
    }
  }
  return null;
}

function elapsed(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const s = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

function useSecondTick(): void {
  const [, setTick] = useState(0);
  useEffect(() => {
    const handle = window.setInterval(() => setTick(t => t + 1), 1000);
    return () => window.clearInterval(handle);
  }, []);
}

// ── next-possible line (shared across both modes) ─────────────────────

function NextLine({ options }: { options: NextPossibleDto[] }) {
  if (options.length === 0) {
    return null;
  }
  return (
    <div style={nextLineStyle}>
      <span style={nextLineLblStyle}>Next node will be:</span>
      {options.map((o, i) => (
        <Fragment key={o.trigger}>
          {i > 0 && <span style={nextSepStyle}>·</span>}
          <span style={nextOptStyle}>
            {o.label}<span style={nextCondStyle}> · {o.cond}</span>
          </span>
        </Fragment>
      ))}
    </div>
  );
}

// ── mode toggle (sticky per task) ─────────────────────────────────────

function ModeToggle({ mode, hiddenCount, onToggle }: {
  mode: Mode;
  hiddenCount: number;
  onToggle: () => void;
}) {
  return (
    <button type="button" style={modeToggleStyle} onClick={onToggle}>
      <span aria-hidden style={modeGlyphStyle}>{mode === 'collapsed' ? '▾' : '▴'}</span>
      <span style={modeLblStyle}>
        {mode === 'collapsed' ? 'View full timeline' : 'Collapse to milestones'}
      </span>
      <span style={modeCtStyle}>
        {mode === 'collapsed'
          ? `${hiddenCount} individual node${hiddenCount === 1 ? '' : 's'} hidden`
          : 'return to 6-bucket overview'}
      </span>
    </button>
  );
}

// ── trace panel (expanded only): timestamp · actor · reason ───────────

function TracePanel({ events }: { events: TraceEventDto[] }) {
  return (
    <div style={traceStyle}>
      {events.map((e, i) => (
        <div key={i} style={traceRowStyle}>
          <span style={traceNStyle}>{e.n}.</span>
          <span style={traceTsStyle}>{formatClock(e.transitionedAt)}</span>
          <span style={traceArrowStyle}>
            <span style={{ color: 'var(--text-3)' }}>{e.fromPhase ?? 'CREATED'}</span>
            <span style={{ color: 'var(--text-4)' }}>→</span>
            <span style={{ color: 'var(--text-1)', fontWeight: 700 }}>{e.toPhase}</span>
          </span>
          <span style={actorPillStyle(e.actor)}>{actorLabel(e.actor)}</span>
          <span style={traceReasonStyle}>{e.reason}</span>
        </div>
      ))}
    </div>
  );
}

function actorLabel(actor: string | null): string {
  if (actor === 'HUMAN') return 'you';
  return (actor ?? '').toLowerCase();
}

function formatClock(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

// ── helpers ───────────────────────────────────────────────────────────

function humanize(value: string | null): string | null {
  if (value === null) return null;
  const spaced = value.replace(/_/g, ' ').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return 'recently';
  const delta = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (delta < 60) return `${delta}s ago`;
  const m = Math.floor(delta / 60);
  if (m < 60) return `${m}m ${delta % 60}s ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

function dotGlyph(state: BucketState, terminal: boolean): string {
  if (terminal) return '⏏';
  switch (state) {
    case 'reached': return '✓';
    case 'active': return '◼';
    case 'skipped': return '↷';
    default: return '';
  }
}

// ── styles ────────────────────────────────────────────────────────────

const flowStyle: React.CSSProperties = { padding: '4px 0 0' };

const skeletonStyle: React.CSSProperties = {
  padding: '14px 8px', fontSize: 12, color: 'var(--text-4)',
};

const headStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 14,
  padding: '0 6px 12px', marginBottom: 14,
  borderBottom: '1px dashed rgba(0,0,0,0.08)',
};
const statePillStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 8,
  padding: '5px 13px 5px 11px', borderRadius: 999,
  fontSize: 11.5, fontWeight: 800, letterSpacing: '0.04em',
  background: 'rgba(148,163,184,0.12)', color: '#475569',
  border: '1px solid rgba(100,116,139,0.30)',
};
const pulseDotStyle: React.CSSProperties = {
  width: 8, height: 8, borderRadius: '50%', background: '#475569',
};
const pillSubStyle: React.CSSProperties = { fontWeight: 500, opacity: 0.85 };
const headTsStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-3)',
  fontFamily: 'var(--font-mono)',
};

const stepperStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 0,
  padding: '4px 8px 6px', overflowX: 'auto',
};
const stepGroupStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', flex: '0 0 auto',
};
const stepStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
  flex: '0 0 auto', minWidth: 76,
};

function dotStyle(state: BucketState, terminal: boolean): React.CSSProperties {
  const base: React.CSSProperties = {
    width: state === 'active' ? 24 : 20, height: state === 'active' ? 24 : 20,
    borderRadius: '50%', display: 'inline-flex', alignItems: 'center',
    justifyContent: 'center', fontSize: 9, fontWeight: 800, color: '#fff',
    border: '2px solid', boxSizing: 'border-box',
  };
  if (terminal) {
    return { ...base, background: 'linear-gradient(135deg,#34d399,#10b981)', borderColor: '#047857' };
  }
  switch (state) {
    case 'reached':
      return { ...base, background: 'linear-gradient(135deg,#86efac,#22c55e)', borderColor: '#16a34a' };
    case 'active':
      return {
        ...base, background: 'linear-gradient(135deg,#fbbf24,#d97706)', borderColor: '#92400e',
        boxShadow: '0 0 0 4px rgba(245,158,11,0.18)',
      };
    case 'skipped':
      return { ...base, background: '#fff', border: '2px dashed #d4d4d8', color: '#a1a1aa' };
    default:
      return { ...base, background: '#fff', borderColor: 'rgba(0,0,0,0.22)', color: 'var(--text-4)' };
  }
}

function nameStyle(state: BucketState): React.CSSProperties {
  const base: React.CSSProperties = { fontSize: 11, fontWeight: 600, whiteSpace: 'nowrap' };
  switch (state) {
    case 'active': return { ...base, color: '#92400e', fontWeight: 800 };
    case 'reached': return { ...base, color: 'var(--text-2)' };
    case 'skipped':
      return { ...base, color: 'var(--text-3)', fontStyle: 'italic', textDecoration: 'line-through' };
    default: return { ...base, color: 'var(--text-3)' };
  }
}

function connectorStyle(state: ConnState): React.CSSProperties {
  const base: React.CSSProperties = { height: 2, minWidth: 18, flex: 1, marginTop: 10 };
  switch (state) {
    case 'reached': return { ...base, background: 'linear-gradient(90deg,#22c55e,#16a34a)' };
    case 'skipped':
      return {
        ...base,
        background: 'repeating-linear-gradient(90deg,transparent 0 5px,rgba(0,0,0,0.22) 5px 10px)',
      };
    default: return { ...base, background: 'rgba(0,0,0,0.10)' };
  }
}

// ── compact strip + mini-stepper styles (collapsed default) ───────────

const stripStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 14, padding: '7px 4px',
};
const stripDoneStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 12, padding: '8px 12px',
  borderRadius: 10, background: 'rgba(16,185,129,0.04)',
  border: '1px solid rgba(16,185,129,0.18)',
};
const doneBadgeStyle: React.CSSProperties = {
  flexShrink: 0, fontSize: 11.5, fontWeight: 800, color: '#047857',
  letterSpacing: '0.02em',
};
const trailStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--text-2)', whiteSpace: 'nowrap',
  overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0,
};
const stripContextStyle: React.CSSProperties = {
  flex: 1, minWidth: 0, fontSize: 11.5, color: 'var(--text-3)',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const timelineBtnStyle: React.CSSProperties = {
  flexShrink: 0, display: 'inline-flex', alignItems: 'center', gap: 4,
  padding: '3px 10px', borderRadius: 999, cursor: 'pointer',
  fontSize: 10.5, fontWeight: 700, color: '#92400e',
  background: 'rgba(245,158,11,0.06)', border: '1px solid #fcd34d',
};

const miniStepperStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 3, flexShrink: 0,
  overflowX: 'auto', cursor: 'pointer', padding: '2px 0',
};
const miniStepStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3, flexShrink: 0,
};

function miniDotStyle(state: BucketState, terminal: boolean): React.CSSProperties {
  const size = state === 'active' ? 14 : 11;
  const base: React.CSSProperties = {
    width: size, height: size, borderRadius: '50%', border: '2px solid', boxSizing: 'border-box',
  };
  if (terminal) {
    return { ...base, background: 'linear-gradient(135deg,#34d399,#10b981)', borderColor: '#047857' };
  }
  switch (state) {
    case 'reached':
      return { ...base, background: 'linear-gradient(135deg,#86efac,#22c55e)', borderColor: '#16a34a' };
    case 'active':
      return {
        ...base, background: 'linear-gradient(135deg,#fbbf24,#d97706)', borderColor: '#92400e',
        boxShadow: '0 0 0 3px rgba(245,158,11,0.18)',
      };
    case 'skipped':
      return { ...base, background: '#fff', border: '2px dashed #d4d4d8' };
    default:
      return { ...base, background: '#fff', borderColor: 'rgba(0,0,0,0.20)' };
  }
}

function miniNameStyle(state: BucketState): React.CSSProperties {
  const base: React.CSSProperties = { fontSize: 9, fontWeight: 600, whiteSpace: 'nowrap' };
  switch (state) {
    case 'active': return { ...base, color: '#92400e', fontWeight: 800 };
    case 'reached': return { ...base, color: 'var(--text-2)' };
    case 'skipped':
      return { ...base, color: 'var(--text-3)', fontStyle: 'italic', textDecoration: 'line-through' };
    default: return { ...base, color: 'var(--text-4)' };
  }
}

function miniBadgeStyle(state: BucketState): React.CSSProperties {
  const skipped = state === 'skipped';
  return {
    fontSize: 8, fontWeight: 800, padding: '0 4px', borderRadius: 999, lineHeight: 1.5,
    color: skipped ? '#6b7280' : '#b45309',
    background: skipped ? 'rgba(228,228,231,0.5)' : 'rgba(245,158,11,0.10)',
    border: `1px solid ${skipped ? 'rgba(0,0,0,0.08)' : 'rgba(245,158,11,0.32)'}`,
  };
}

function miniConnStyle(state: ConnState): React.CSSProperties {
  const base: React.CSSProperties = { height: 2, width: 12, flexShrink: 0, marginTop: 8 };
  switch (state) {
    case 'reached': return { ...base, background: 'linear-gradient(90deg,#22c55e,#16a34a)' };
    case 'skipped':
      return {
        ...base,
        background: 'repeating-linear-gradient(90deg,transparent 0 4px,rgba(0,0,0,0.22) 4px 8px)',
      };
    default: return { ...base, background: 'rgba(0,0,0,0.10)' };
  }
}

const monoFont = 'var(--font-mono)';

const parStatusStyle: React.CSSProperties = {
  marginTop: 14, padding: '11px 14px', borderRadius: 13,
  background: 'rgba(255,255,255,0.5)', border: '1px solid rgba(0,0,0,0.08)',
};
const parHeadStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 9, marginBottom: 9,
  fontSize: 10.5, fontWeight: 800, color: '#92400e',
  letterSpacing: '0.04em', textTransform: 'uppercase',
};
const parWhyStyle: React.CSSProperties = {
  marginLeft: 'auto', fontSize: 10, fontWeight: 500, color: 'var(--text-3)',
  textTransform: 'none', letterSpacing: 0, fontStyle: 'italic',
};
const parPillsStyle: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '72px 1fr 72px 1fr', gap: 9, alignItems: 'center',
};
const parLblStyle: React.CSSProperties = {
  fontSize: 10, fontWeight: 800, color: 'var(--text-4)',
  textTransform: 'uppercase', letterSpacing: '0.05em',
};

const axisDetStyle: React.CSSProperties = {
  fontSize: 10, opacity: 0.7, fontStyle: 'normal', fontFamily: monoFont,
};

function axisPillStyle(tone: AxisTone): React.CSSProperties {
  const base: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 7, padding: '6px 11px',
    borderRadius: 10, fontSize: 11.5, border: '1px solid',
  };
  switch (tone) {
    case 'good':
      return { ...base, background: 'rgba(16,185,129,0.06)', borderColor: '#86efac', color: '#047857' };
    case 'warn':
      return { ...base, background: 'rgba(220,38,38,0.06)', borderColor: '#fca5a5', color: '#b91c1c' };
    case 'live':
      return { ...base, background: 'rgba(245,158,11,0.06)', borderColor: '#fcd34d', color: '#92400e' };
    default:
      return {
        ...base, background: 'rgba(0,0,0,0.03)', borderColor: 'rgba(0,0,0,0.08)',
        color: 'var(--text-3)', fontStyle: 'italic',
      };
  }
}

const nextLineStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap',
  marginTop: 14, padding: '10px 14px', borderRadius: 11,
  background: 'var(--accent-a4)', border: '1px dashed var(--accent-border)',
  fontSize: 11.5, color: 'var(--text-2)',
};
const nextLineLblStyle: React.CSSProperties = {
  fontSize: 10, fontWeight: 800, letterSpacing: '0.05em',
  textTransform: 'uppercase', color: 'var(--text-3)',
};
const nextSepStyle: React.CSSProperties = { color: 'var(--text-4)' };
const nextOptStyle: React.CSSProperties = {
  padding: '2px 9px', borderRadius: 999, fontSize: 11, fontWeight: 600,
  background: 'var(--bg-card)', border: '1px solid var(--accent-border)',
  color: 'var(--accent-deep)', whiteSpace: 'nowrap',
};
const nextCondStyle: React.CSSProperties = {
  color: 'var(--text-4)', fontWeight: 500, fontSize: 10.5,
};

const modeToggleStyle: React.CSSProperties = {
  marginTop: 14, width: '100%', display: 'flex', alignItems: 'center', gap: 8,
  padding: '9px 14px', borderRadius: 11, cursor: 'pointer', textAlign: 'left',
  background: 'rgba(245,158,11,0.04)', border: '1px solid #fcd34d',
  fontSize: 11.5, color: '#92400e',
};
const modeGlyphStyle: React.CSSProperties = { fontWeight: 800 };
const modeLblStyle: React.CSSProperties = { fontWeight: 700 };
const modeCtStyle: React.CSSProperties = {
  marginLeft: 'auto', fontFamily: monoFont, fontSize: 10.5, color: 'var(--text-3)',
};

const traceStyle: React.CSSProperties = {
  marginTop: 12, background: 'rgba(255,255,255,0.5)',
  border: '1px solid rgba(0,0,0,0.08)', borderRadius: 13, padding: '4px 0',
  maxHeight: 320, overflowY: 'auto',
};
const traceRowStyle: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '28px 70px 1fr 84px 1.05fr', gap: 12,
  alignItems: 'center', padding: '8px 16px', fontSize: 11.5,
  borderBottom: '1px solid rgba(0,0,0,0.06)',
};
const traceNStyle: React.CSSProperties = {
  color: 'var(--text-4)', fontFamily: monoFont, fontSize: 10, textAlign: 'right',
};
const traceTsStyle: React.CSSProperties = {
  color: 'var(--text-3)', fontFamily: monoFont, fontSize: 10.5,
};
const traceArrowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 7, fontFamily: monoFont, fontSize: 11,
};
const traceReasonStyle: React.CSSProperties = {
  color: 'var(--text-2)', fontSize: 11, lineHeight: 1.4,
};

function actorPillStyle(actor: string | null): React.CSSProperties {
  const base: React.CSSProperties = {
    fontSize: 9.5, fontWeight: 800, letterSpacing: '0.04em', padding: '2px 8px',
    borderRadius: 999, textTransform: 'uppercase', textAlign: 'center',
    border: '1px solid',
  };
  switch (actor) {
    case 'AGENT':
      return { ...base, background: 'var(--accent-soft)', color: 'var(--accent-deep)', borderColor: 'var(--accent-border)' };
    case 'HUMAN':
      return { ...base, background: 'rgba(16,185,129,0.12)', color: '#047857', borderColor: '#86efac' };
    case 'WEBHOOK':
      return { ...base, background: 'rgba(37,99,235,0.10)', color: '#1d4ed8', borderColor: '#93c5fd' };
    default:
      return { ...base, background: 'rgba(0,0,0,0.04)', color: 'var(--text-3)', borderColor: 'rgba(0,0,0,0.08)' };
  }
}
