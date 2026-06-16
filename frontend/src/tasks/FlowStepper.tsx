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
import type { MilestoneSummaryDto, TaskTraceDto, TraceEventDto } from '../types';

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
  return (
    <div style={flowStyle}>
      <FlowHead trace={data} />
      {mode === 'collapsed'
        ? <MilestoneBuckets summary={data.milestoneSummary} />
        : <SequentialNodes trace={data} />}
      <ModeToggle mode={mode} hiddenCount={data.events.length} onToggle={toggleMode} />
      {mode === 'expanded' && <TracePanel events={data.events} />}
    </div>
  );
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
    if (phase !== null && TERMINAL_PHASES.has(phase)) {
      return;
    }
    const handle = window.setInterval(() => { void load(); }, 3000);
    return () => window.clearInterval(handle);
  }, [phase, load]);

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

// ── collapsed view: six milestone buckets ─────────────────────────────

function MilestoneBuckets({ summary }: { summary: MilestoneSummaryDto[] }) {
  return (
    <div style={stepperStyle}>
      {summary.map((bucket, i) => (
        <div key={bucket.milestone} style={stepGroupStyle}>
          <Bucket bucket={bucket} />
          {i < summary.length - 1 && (
            <span aria-hidden style={connectorStyle(connectorState(bucket, summary[i + 1]))} />
          )}
        </div>
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
    <div style={stepStyle} data-milestone={bucket.milestone} data-state={terminal ? 'terminal' : state}>
      <span style={dotStyle(state, terminal)} aria-hidden>{dotGlyph(state, terminal)}</span>
      <span style={nameStyle(state)}>{bucket.label}</span>
      {bucket.visits > 1 && <span style={loopBadgeStyle(state)}>×{bucket.visits}</span>}
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
  fontFamily: '"SF Mono", Menlo, monospace',
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

function loopBadgeStyle(state: BucketState): React.CSSProperties {
  const skipped = state === 'skipped';
  return {
    fontSize: 9, fontWeight: 800, padding: '1px 6px', borderRadius: 999,
    color: skipped ? '#6b7280' : '#b45309',
    background: skipped ? 'rgba(228,228,231,0.5)' : 'rgba(245,158,11,0.10)',
    border: `1px solid ${skipped ? 'rgba(0,0,0,0.08)' : 'rgba(245,158,11,0.32)'}`,
  };
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

const monoFont = '"SF Mono", Menlo, Consolas, monospace';

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
      return { ...base, background: 'rgba(124,92,255,0.12)', color: '#5b21b6', borderColor: 'rgba(124,92,255,0.3)' };
    case 'HUMAN':
      return { ...base, background: 'rgba(16,185,129,0.12)', color: '#047857', borderColor: '#86efac' };
    case 'WEBHOOK':
      return { ...base, background: 'rgba(37,99,235,0.10)', color: '#1d4ed8', borderColor: '#93c5fd' };
    default:
      return { ...base, background: 'rgba(0,0,0,0.04)', color: 'var(--text-3)', borderColor: 'rgba(0,0,0,0.08)' };
  }
}
