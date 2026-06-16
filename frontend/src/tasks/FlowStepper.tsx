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
import type { MilestoneSummaryDto, TaskTraceDto } from '../types';

/**
 * The task page's lifecycle flow display. Reads the
 * {@code GET /api/tasks/{id}/trace} read-model and renders the collapsed
 * six-bucket milestone overview. The expanded sequential timeline, the
 * sticky mode toggle, the parallel sub-status block, and the next-line
 * land on the same component in later steps — one component, two modes.
 */
export function FlowStepper({ taskId }: { taskId: string }) {
  const { data } = useTaskTrace(taskId);
  if (data === null) {
    return <div style={skeletonStyle}>Loading flow…</div>;
  }
  return (
    <div style={flowStyle}>
      <FlowHead trace={data} />
      <MilestoneBuckets summary={data.milestoneSummary} />
    </div>
  );
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
