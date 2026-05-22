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
import type { WorkUnitTaskDto } from '../types';

type Props = {
  threadId: string;
};

/** TaskStatus values that mean "this task is still in flight"; the
 *  Ship & continue button targets the highest-seq task in this set.
 *  Terminal statuses (COMPLETED, ERRORED) never qualify — a shipped
 *  thread that wants more work materialises a fresh task via the
 *  next user prompt rather than rolling over a closed one. */
const ACTIVE_STATUSES = new Set([
  'PENDING', 'RUNNING', 'AWAITING', 'IDLE',
  'AWAITING_REVIEW', 'NEEDS_ATTENTION',
]);

/**
 * Sidebar rail entry that lists the thread's work-unit Tasks in
 * sequence — `✓ Task 1`, `● Task 2 (active)` etc. Makes the
 * Thread → Task split visible to the user. The "Ship & continue"
 * button described in the design ships in a follow-up commit; this
 * cut is the read-only surface that everything else hangs off.
 *
 * <p>Renders nothing during the brief loading window — the rail
 * has more important sections (status, vitals) showing content
 * already, and a "loading…" placeholder for an empty-by-design
 * section ends up more disruptive than just popping in once we
 * have data.
 */
export function TasksInThreadSection({ threadId }: Props) {
  const [tasks, setTasks] = useState<WorkUnitTaskDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [shipping, setShipping] = useState(false);
  const [shipError, setShipError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTasksForThread(threadId);
      setTasks(list);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId]);

  useEffect(() => {
    let cancelled = false;
    setTasks(null);
    setError(null);
    setShipError(null);
    void (async () => {
      if (!cancelled) {
        await refresh();
      }
    })();
    return () => { cancelled = true; };
  }, [threadId, refresh]);

  if (error !== null) {
    return <div style={errorStyle}>Could not load tasks: {error}</div>;
  }
  if (tasks === null) {
    return null;
  }
  if (tasks.length === 0) {
    return (
      <div style={emptyStyle}>
        No tasks yet — this thread is in brainstorm mode. The first
        coding turn will materialise <em>Task 1</em>.
      </div>
    );
  }

  // Tasks come back oldest-first per the backend; reverse so the
  // active / most recent task lands at the top of the rail, matching
  // how segments / checkpoints already render.
  const ordered = [...tasks].reverse();
  // Active = newest task whose status is non-terminal. The button
  // closes this one out and opens the next; greyed out when only
  // terminal tasks remain so the click can't churn closed work.
  const activeTask = tasks
    .filter(t => ACTIVE_STATUSES.has(t.status))
    .reduce<WorkUnitTaskDto | null>(
      (acc, t) => acc === null || t.seq > acc.seq ? t : acc, null);

  const onShipAndContinue = async () => {
    if (activeTask === null || shipping) return;
    if (!window.confirm(
        `Ship Task ${activeTask.seq}`
        + (activeTask.branchName !== null ? ` (${activeTask.branchName})` : '')
        + ` and start the next task on main?`)) {
      return;
    }
    setShipping(true);
    setShipError(null);
    try {
      await window.bridge.shipAndContinue(threadId, activeTask.id);
      await refresh();
    }
    catch (e) {
      setShipError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setShipping(false);
    }
  };

  return (
    <div>
      <ul style={listStyle}>
        {ordered.map(task => (
          <li key={task.id} style={rowStyle}>
            <span style={glyphFor(task)} aria-hidden>{glyphChar(task)}</span>
            <span style={labelStyle}>
              <span style={titleStyle}>Task {task.seq}</span>
              {task.branchName !== null && (
                <span style={branchStyle}>{task.branchName}</span>
              )}
            </span>
            <StatusPill status={task.status} prNumber={task.prNumber} prState={task.prState} />
          </li>
        ))}
      </ul>
      <button
        type="button"
        onClick={() => { void onShipAndContinue(); }}
        disabled={activeTask === null || shipping}
        style={shipBtnStyle}
        title={activeTask === null
          ? 'No active task — start a new one with the next prompt'
          : `Ship Task ${activeTask.seq} and start the next one`}
      >
        {shipping ? '⚡ shipping…' : '⚡ Ship & continue'}
      </button>
      {shipError !== null && (
        <div style={errorStyle}>{shipError}</div>
      )}
    </div>
  );
}

function StatusPill({
  status, prNumber, prState,
}: {
  status: string;
  prNumber: number | null;
  prState: string | null;
}) {
  // Prefer a PR-driven label when a PR exists — that's the
  // user-facing "where did this task land" answer.
  if (prNumber !== null) {
    const merged = (prState ?? '').toLowerCase() === 'merged';
    return (
      <span style={pillStyle(merged ? 'completed' : 'active')}>
        {merged ? 'merged' : `PR #${prNumber}`}
      </span>
    );
  }
  const tone = pillTone(status);
  return <span style={pillStyle(tone)}>{pillLabel(status)}</span>;
}

function glyphChar(task: WorkUnitTaskDto): string {
  if (task.status === 'COMPLETED') return '✓';
  if (task.status === 'ERRORED') return '⨯';
  if (task.status === 'AWAITING_REVIEW' || task.status === 'NEEDS_ATTENTION') return '◐';
  if (task.status === 'RUNNING' || task.status === 'AWAITING') return '●';
  return '○';
}

function glyphFor(task: WorkUnitTaskDto): React.CSSProperties {
  const base: React.CSSProperties = {
    width: 16,
    textAlign: 'center',
    fontFamily: 'inherit',
    fontSize: 12,
  };
  if (task.status === 'COMPLETED') {
    return { ...base, color: 'var(--accent-dark, #2e7d32)' };
  }
  if (task.status === 'ERRORED') {
    return { ...base, color: '#b91c1c' };
  }
  if (task.status === 'AWAITING_REVIEW' || task.status === 'NEEDS_ATTENTION') {
    return { ...base, color: '#9a6700' };
  }
  if (task.status === 'RUNNING' || task.status === 'AWAITING') {
    return { ...base, color: 'var(--accent, #1971c2)' };
  }
  return { ...base, color: 'var(--text-4)' };
}

type Tone = 'active' | 'completed' | 'parked' | 'errored' | 'idle';

function pillTone(status: string): Tone {
  if (status === 'COMPLETED') return 'completed';
  if (status === 'ERRORED') return 'errored';
  if (status === 'AWAITING_REVIEW' || status === 'NEEDS_ATTENTION') return 'parked';
  if (status === 'RUNNING' || status === 'AWAITING') return 'active';
  return 'idle';
}

function pillLabel(status: string): string {
  if (status === 'AWAITING_REVIEW') return 'review';
  if (status === 'NEEDS_ATTENTION') return 'needs you';
  return status.toLowerCase();
}

function pillStyle(tone: Tone): React.CSSProperties {
  const base: React.CSSProperties = {
    fontSize: 10,
    padding: '1px 6px',
    borderRadius: 999,
    whiteSpace: 'nowrap',
    fontWeight: 600,
    letterSpacing: '0.02em',
  };
  switch (tone) {
    case 'active':
      return { ...base, background: 'var(--accent-a10, rgba(25,113,194,0.10))', color: 'var(--accent-dark, #1864ab)' };
    case 'completed':
      return { ...base, background: 'rgba(46, 125, 50, 0.10)', color: '#2e7d32' };
    case 'parked':
      return { ...base, background: 'rgba(255, 197, 0, 0.18)', color: '#9a6700' };
    case 'errored':
      return { ...base, background: 'rgba(185, 28, 28, 0.10)', color: '#b91c1c' };
    case 'idle':
    default:
      return { ...base, background: 'var(--bg-elevated, #f4f4f4)', color: 'var(--text-3)' };
  }
}

const listStyle: React.CSSProperties = {
  margin: 0,
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '4px 6px',
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: 'var(--bg-elevated)',
};

const labelStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  minWidth: 0,
};

const titleStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const branchStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-4)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const emptyStyle: React.CSSProperties = {
  padding: '4px 2px',
  fontSize: 11,
  color: 'var(--text-3)',
  lineHeight: 1.4,
};

const errorStyle: React.CSSProperties = {
  padding: '4px 6px',
  fontSize: 11,
  color: '#b91c1c',
  fontStyle: 'italic',
};

const shipBtnStyle: React.CSSProperties = {
  marginTop: 6,
  width: '100%',
  padding: '5px 8px',
  fontSize: 11,
  border: '1px dashed var(--border)',
  background: 'transparent',
  borderRadius: 4,
  color: 'var(--text-2)',
  cursor: 'pointer',
};
