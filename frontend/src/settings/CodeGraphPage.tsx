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
import type {
  CodeGraphArmComparisonDto,
  CodeGraphBaselineDto,
  CodeGraphMode,
  CodeGraphRepositoryComparisonDto,
} from '../types';
import { relativeTime } from '../relativeTime';
import RepoAvatar from '../RepoAvatar';
import { Badge, Segmented, SelectPill, SettingsCard, SettingsRow } from './primitives';

const MODE_HINTS: Record<CodeGraphMode, string> = {
  OFF: 'Tool hidden and indexing stopped',
  ON: 'Every task gets callers, callees, and cross-file structure in one call',
  COMPARE: 'New tasks get the tool at random, so the table below can show whether it helps',
};

/** Fewer than this many tasks in an arm is too few to read anything into the medians. */
const LOW_SAMPLE = 5;

type MetricRow = {
  label: string;
  read: (arm: CodeGraphArmComparisonDto) => number | null;
  kind: 'number' | 'tokens';
  /** Graph calls only exist on the graph arm, so a delta would compare a number with nothing. */
  neutral?: boolean;
};

const METRIC_ROWS: MetricRow[] = [
  { label: 'Turns per task', read: arm => arm.turnsPerTask, kind: 'number' },
  { label: 'Input tokens', read: arm => arm.inputTokens, kind: 'tokens' },
  { label: 'Output tokens', read: arm => arm.outputTokens, kind: 'tokens' },
  { label: 'Tool calls', read: arm => arm.toolCalls, kind: 'number' },
  { label: 'Files explored', read: arm => arm.exploreActions, kind: 'number' },
  { label: 'Minutes', read: arm => arm.minutes, kind: 'number' },
  { label: 'Graph calls', read: arm => arm.graphCalls, kind: 'number', neutral: true },
];

export function formatMetric(value: number | null, kind: 'number' | 'tokens'): string {
  if (value === null) return '—';
  if (kind !== 'tokens') return value.toFixed(1);
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return String(Math.round(value));
}

/** Graph against no graph as a percentage; lower is cheaper, so a negative delta is the good one. */
export function formatDelta(graph: number | null, plain: number | null): { text: string; tone: string } {
  if (graph === null || plain === null || plain === 0) return { text: '—', tone: 'is-none' };
  const percent = Math.round(((graph - plain) / plain) * 100);
  if (percent === 0) return { text: '0%', tone: 'is-none' };
  return { text: `${percent > 0 ? '+' : ''}${percent}%`, tone: percent < 0 ? 'is-better' : 'is-worse' };
}

/** Comparison rows are keyed by "owner/name"; the avatar needs the halves. */
function ownerOf(repository: string): string {
  return repository.split('/')[0] ?? repository;
}

function nameOf(repository: string): string {
  return repository.split('/')[1] ?? '';
}

function armOf(entry: CodeGraphRepositoryComparisonDto, arm: 'ON' | 'OFF'): CodeGraphArmComparisonDto | null {
  return entry.arms.find(candidate => candidate.arm === arm) ?? null;
}

export default function CodeGraphPage({ mode, baseline, comparison, error, onMode }: {
  mode: CodeGraphMode;
  baseline: CodeGraphBaselineDto | null;
  comparison: CodeGraphRepositoryComparisonDto[];
  error: string | null;
  onMode: (mode: CodeGraphMode) => void;
}) {
  const [repository, setRepository] = useState<string | null>(null);
  const selected = comparison.find(entry => entry.repository === repository) ?? comparison[0] ?? null;
  const graph = selected === null ? null : armOf(selected, 'ON');
  const plain = selected === null ? null : armOf(selected, 'OFF');
  const graphTasks = graph?.taskCount ?? 0;
  const plainTasks = plain?.taskCount ?? 0;

  return (
    <div className="setw-page">
      <h1>Code graph</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <SettingsCard>
        <SettingsRow title="Give tasks a code graph of their worktree" subtitle={MODE_HINTS[mode]}>
          <Segmented<CodeGraphMode>
            label="Code graph mode"
            value={mode}
            options={[
              { value: 'OFF', label: 'Off' },
              { value: 'ON', label: 'On' },
              { value: 'COMPARE', label: 'Compare' },
            ]}
            onChange={onMode}
          />
        </SettingsRow>
        <SettingsRow title="Baseline index">
          <span className="setw-row-sub setw-mono">
            {baseline === null
              ? 'not read yet'
              : `${baseline.repositories} ${baseline.repositories === 1 ? 'repo' : 'repos'}`
                + (baseline.updatedAt ? ` · updated ${relativeTime(baseline.updatedAt)}` : '')}
          </span>
        </SettingsRow>
      </SettingsCard>

      <div className="setw-section">
        <span className="setw-section-title">Does it help?</span>
        {comparison.length > 0 && (
          <SelectPill
            quiet
            ariaLabel="Comparison repository"
            value={selected?.repository ?? ''}
            label={selected?.repository ?? '—'}
            options={comparison.map(entry => ({ value: entry.repository, label: entry.repository }))}
            onSelect={setRepository}
          />
        )}
      </div>

      {selected === null ? (
        <SettingsCard>
          <div className="setw-row-empty">No task has been stamped with an arm yet.</div>
        </SettingsCard>
      ) : (
        <div className="setw-compare">
          <div className="setw-compare-head">
            <span>
              <RepoAvatar owner={ownerOf(selected.repository)} repo={nameOf(selected.repository)} size={17} radius={4} />
              {' '}graph {graphTasks} tasks · no graph {plainTasks} tasks
            </span>
            {(graphTasks < LOW_SAMPLE || plainTasks < LOW_SAMPLE) && <Badge tone="warn">low sample</Badge>}
          </div>
          <div className="setw-compare-grid is-header">
            <span>Median per task</span>
            <span>Graph</span>
            <span>No graph</span>
            <span>Δ</span>
          </div>
          {METRIC_ROWS.map(row => {
            const graphValue = graph === null ? null : row.read(graph);
            const plainValue = plain === null ? null : row.read(plain);
            const delta = row.neutral === true
              ? { text: '—', tone: 'is-none' }
              : formatDelta(graphValue, plainValue);
            return (
              <div className="setw-compare-grid" key={row.label}>
                <span>{row.label}</span>
                <span className="setw-mono">{formatMetric(graphValue, row.kind)}</span>
                <span className="setw-mono is-plain">{formatMetric(plainValue, row.kind)}</span>
                <span className={`setw-compare-delta setw-mono ${delta.tone}`}>{delta.text}</span>
              </div>
            );
          })}
          <div className="setw-compare-foot">
            {plain === null
              ? 'No no-graph tasks yet in this repository, so there is nothing to compare against. Keep Compare on to collect them.'
              : 'Observational, not a trial — tasks, models, and prompts differ. Δ is graph versus no graph; lower is cheaper.'}
          </div>
        </div>
      )}
    </div>
  );
}
