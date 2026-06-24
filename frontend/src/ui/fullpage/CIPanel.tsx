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
import { useState } from 'react';
import type { ReactNode } from 'react';

/** CI check / iteration outcome. */
export type CIStatus = 'pass' | 'fail' | 'run';

const GLYPH: Record<CIStatus, string> = { pass: '✓', fail: '✕', run: '●' };

/** One check inside the current-run card. */
export type CICheck = { status: CIStatus; name: ReactNode; duration?: string };

/** A single CI check row. */
export function CICheckRow({ status, name, duration }: CICheck) {
  return (
    <div className="ci-check-row">
      <span className={`ic ${status}`} aria-hidden>{GLYPH[status]}</span>
      <span className="nm">{name}</span>
      {duration !== undefined && <span className="dur">{duration}</span>}
    </div>
  );
}

/** The current-run card: header + status line + per-check rows. */
export function CICurrentCard({ title, runId, statusLine, checks }: {
  title: ReactNode;
  runId?: string;
  statusLine?: ReactNode;
  checks: CICheck[];
}) {
  return (
    <div className="ci-current-card">
      <div className="hd">
        {title}
        {runId !== undefined && <span className="run-id">{runId}</span>}
      </div>
      {statusLine !== undefined && <div className="status-line">{statusLine}</div>}
      {checks.map((c, i) => <CICheckRow key={i} {...c} />)}
    </div>
  );
}

/** One iteration row inside a history folder. */
export type CIIteration = { id: string; status: CIStatus; name: ReactNode; timestamp?: string };

/** A single iteration row. */
export function CIIterationRow({ iteration, active = false, onClick }: {
  iteration: CIIteration;
  active?: boolean;
  onClick?: () => void;
}) {
  return (
    <button type="button" className={active ? 'ci-iter-row active' : 'ci-iter-row'} onClick={onClick}>
      <span className={`ic ${iteration.status}`} aria-hidden>{GLYPH[iteration.status]}</span>
      <span className="nm">{iteration.name}</span>
      {iteration.timestamp !== undefined && <span className="ts">{iteration.timestamp}</span>}
    </button>
  );
}

/** A collapsible folder of fix iterations (This task / Earlier this thread
 *  / All time). Controlled when `expanded`/`onToggle` are given. */
export function CIIterationFolder({
  label, icon = '📁', iterations, selectedId, onSelect, expanded, onToggle, defaultExpanded = false,
}: {
  label: ReactNode;
  icon?: ReactNode;
  iterations: CIIteration[];
  selectedId?: string;
  onSelect?: (id: string) => void;
  expanded?: boolean;
  onToggle?: () => void;
  defaultExpanded?: boolean;
}) {
  const [selfOpen, setSelfOpen] = useState(defaultExpanded);
  const isControlled = expanded !== undefined;
  const open = isControlled ? expanded : selfOpen;
  const toggle = () => { if (isControlled) onToggle?.(); else setSelfOpen(o => !o); };

  return (
    <div className="ci-history-folder">
      <button type="button" className="folder-h" onClick={toggle} aria-expanded={open}>
        <span className="chev" aria-hidden>{open ? '▾' : '▸'}</span>
        <span className="ic" aria-hidden>{icon}</span>
        <span>{label}</span>
        <span className="count">{iterations.length}</span>
      </button>
      {open && (
        <div className="ci-history-list">
          {iterations.map(it => (
            <CIIterationRow
              key={it.id}
              iteration={it}
              active={it.id === selectedId}
              onClick={onSelect !== undefined ? () => onSelect(it.id) : undefined}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/** A history folder's data: a label + its iterations. */
export type CIIterationGroup = { key: string; label: ReactNode; iterations: CIIteration[] };

/**
 * The middle column of the CI Status view: the current run card on top,
 * then the fix-iteration history folders (This task / Earlier this thread
 * / All time).
 */
export function CIPanel({ current, groups, selectedIterationId, onSelectIteration }: {
  current: { title: ReactNode; runId?: string; statusLine?: ReactNode; checks: CICheck[] };
  groups: CIIterationGroup[];
  selectedIterationId?: string;
  onSelectIteration?: (id: string) => void;
}) {
  return (
    <div className="ci-panel">
      <CICurrentCard {...current} />
      {groups.map((g, i) => (
        <CIIterationFolder
          key={g.key}
          label={g.label}
          iterations={g.iterations}
          selectedId={selectedIterationId}
          onSelect={onSelectIteration}
          defaultExpanded={i === 0}
        />
      ))}
    </div>
  );
}
