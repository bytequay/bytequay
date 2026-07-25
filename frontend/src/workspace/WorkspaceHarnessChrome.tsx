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
import type { PullRequestDto } from '../types';
import { TrafficLights } from '../ui/shell';
import {
  WorkspaceBottomNav,
  WorkspacePrimaryNav,
  WorkspaceSwitcherCard,
  type WsNavKey,
} from '../ui/workspace';
import type {
  CiHarnessCycleDto,
  CiHarnessWatchDto,
  CiHarnessWatchSnapshotDto,
} from './workspaceApi';

export function HarnessSidebar({ workspaceName, repository, watches, selectedId, snapshot, onSelect, onNew, onNavigateGlobal, onSwitchWorkspace }: {
  workspaceName: string;
  repository: string;
  watches: CiHarnessWatchDto[];
  selectedId?: string;
  snapshot: CiHarnessWatchSnapshotDto | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onNavigateGlobal?: (key: WsNavKey) => void;
  onSwitchWorkspace?: () => void;
}) {
  return (
    <aside className="sidebar ci-harness-sidebar">
      <TrafficLights />
      <WorkspacePrimaryNav onNavigate={onNavigateGlobal} />
      <WorkspaceSwitcherCard name={workspaceName} repository={repository} onSwitch={onSwitchWorkspace} />
      <div className="ci-harness-sidebar__identity"><span aria-hidden>↻</span><strong>CI Harness</strong><small>local · never pushes</small></div>
      <div className="ci-harness-sidebar__heading"><span>WATCHES</span><small>{watches.length}</small></div>
      <button type="button" className="ci-harness-sidebar__new" onClick={onNew}>＋ New watch</button>
      <div className="ci-harness-sidebar__watches">
        {watches.map(watch => (
          <button type="button" key={watch.id} className={watch.id === selectedId ? 'selected' : ''}
            onClick={() => onSelect(watch.id)}>
            <span className={`ci-harness-status-dot is-${watch.status}`} />
            <span><strong>#{watch.prNumber} {watch.title ?? `${watch.owner}/${watch.repo}`}</strong>
              <small>{statusLabel(watch.status)}{watch.branch === null ? '' : ` · ${watch.branch}`}</small></span>
          </button>
        ))}
        {watches.length === 0 && <p>No watched pull requests yet.</p>}
      </div>
      {snapshot !== null && snapshot.cycles.length > 0 && (
        <div className="ci-harness-sidebar__cycles">
          <div className="ci-harness-sidebar__heading"><span>CYCLES</span><small>{snapshot.cycles.length}</small></div>
          {snapshot.cycles.map(cycle => <CycleNav key={cycle.id} cycle={cycle} active={cycle.id === snapshot.activeCycle?.id} />)}
        </div>
      )}
      <WorkspaceBottomNav onNavigate={onNavigateGlobal} />
    </aside>
  );
}

function CycleNav({ cycle, active }: { cycle: CiHarnessCycleDto; active: boolean }) {
  return (
    <div className={`ci-harness-cycle-nav${active ? ' active' : ''}`}>
      <strong><span>Cycle {cycle.ordinal}</span><small>{cycle.status}</small></strong>
      {active && cycle.phaseStates.map(phase => (
        <span key={phase.phase} className={`is-${phase.status.toLowerCase()}`}><i />{phase.phase}</span>
      ))}
    </div>
  );
}

export function HarnessHeader({ snapshot, showPr, busy, onTogglePr, onRun, onStop }: {
  snapshot: CiHarnessWatchSnapshotDto | null;
  showPr: boolean;
  busy: boolean;
  onTogglePr?: () => void;
  onRun?: () => void;
  onStop?: () => void;
}) {
  return (
    <header className="ci-harness-header">
      <span className="ci-harness-header__badge">CI HARNESS</span>
      <strong>{snapshot === null ? 'Choose a pull request to watch' : `${snapshot.owner}/${snapshot.repo} #${snapshot.prNumber}`}</strong>
      {snapshot !== null && <span className={`ci-harness-status is-${snapshot.status}`}><i />{statusLabel(snapshot.status)}</span>}
      <span className="ci-harness-header__grow" />
      {snapshot !== null && snapshot.budget.limitMilliUsd > 0 && (
        <span className="ci-harness-header__budget" title="Harness watch budget">
          <span><i style={{ width: `${Math.min(100, Math.round(
            (snapshot.budget.spentMilliUsd / snapshot.budget.limitMilliUsd) * 100,
          ))}%` }} /></span>
          <small>{money(snapshot.budget.spentMilliUsd)} / {money(snapshot.budget.limitMilliUsd)}</small>
        </span>
      )}
      {snapshot !== null && (snapshot.status === 'running' || snapshot.status === 'bootstrap') && (
        <button type="button" disabled={busy} onClick={onStop}>Stop safely</button>
      )}
      {snapshot?.status === 'watching' && (
        <button type="button" disabled={busy} onClick={onRun}>Run cycle</button>
      )}
      {snapshot?.status === 'green' && (
        <button type="button" disabled={busy} onClick={onStop}>Unwatch</button>
      )}
      {onTogglePr !== undefined && (
        <button type="button" className="ci-harness-header__panel" aria-label="Toggle PR panel"
          aria-pressed={showPr} onClick={onTogglePr}>▥</button>
      )}
    </header>
  );
}

export function HarnessIdle({ pulls, selectedPr, budget, onSelectPr, onBudget, busy, onCreate }: {
  pulls: PullRequestDto[];
  selectedPr: number | null;
  budget: string;
  onSelectPr: (number: number | null) => void;
  onBudget: (value: string) => void;
  busy: boolean;
  onCreate: () => void;
}) {
  const failingPulls = pulls.filter(pull => pull.state === 'open' && pull.ciStatus === 'FAILING');
  const parsedBudget = Number(budget);
  const budgetValid = budget.trim().length === 0
    || (Number.isFinite(parsedBudget) && parsedBudget >= 0.10 && parsedBudget <= 100);
  const selectionValid = selectedPr !== null && failingPulls.some(pull => pull.number === selectedPr);
  return (
    <main className="ci-harness-idle">
      <span className="ci-harness-idle__glyph" aria-hidden>↻</span>
      <h1>Keep a pull request green</h1>
      <p>The harness watches CI, proposes fixes, applies and verifies them locally, and hands the result back to you. It never pushes.</p>
      <section>
        <strong className="ci-harness-idle__label">PULL REQUESTS WITH FAILING CI</strong>
        <div className="ci-harness-idle__pulls" role="listbox" aria-label="Pull requests with failing CI">
          {failingPulls.map(pull => (
            <button type="button" role="option" aria-selected={selectedPr === pull.number}
              className={selectedPr === pull.number ? 'selected' : ''} key={pull.number}
              onClick={() => onSelectPr(pull.number)}>
              <span><strong>#{pull.number} · {pull.title}</strong>
                <small>{pull.repo} · CI failing{pull.attentionReason === null ? '' : ` · ${pull.attentionReason.toLowerCase().replaceAll('_', ' ')}`}</small></span>
              <b>{selectedPr === pull.number ? 'Selected' : 'Watch'}</b>
            </button>
          ))}
          {failingPulls.length === 0 && <small>No open pull requests with failing CI.</small>}
        </div>
        <label><strong>WATCH BUDGET</strong><span>$<input inputMode="decimal" placeholder="Optional"
          aria-invalid={!budgetValid} value={budget} onChange={event => onBudget(event.target.value)} /></span></label>
        {!budgetValid && <small className="ci-harness-idle__error">Enter $0.10–$100.00.</small>}
        <button type="button" className="wu-primary-button" disabled={!selectionValid || busy || !budgetValid} onClick={onCreate}>
          {busy ? 'Creating watch…' : 'Watch selected pull request'}
        </button>
      </section>
    </main>
  );
}

function statusLabel(status: CiHarnessWatchDto['status']): string {
  return status.replace('_', ' ');
}

function money(milliUsd: number): string {
  return `$${(milliUsd / 1000).toFixed(2)}`;
}
