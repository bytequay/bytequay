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
  CiHarnessBootstrapProfileDto,
  CiHarnessFailureDto,
  CiHarnessMilestoneDto,
  CiHarnessRuleDto,
  CiHarnessWatchSnapshotDto,
} from './workspaceApi';

export function HarnessDashboard({ snapshot, rules, busy, onApproveRule }: {
  snapshot: CiHarnessWatchSnapshotDto;
  rules: CiHarnessRuleDto[];
  busy: boolean;
  onApproveRule: (ruleId: string) => void;
}) {
  const stats = snapshot.stats;
  const candidates = rules.filter(rule => rule.status === 'candidate');
  const hasEscalation = snapshot.failures.some(row => {
    const status = row.status.toLowerCase();
    return status.includes('escalat') || status.includes('attention');
  });
  const showBootstrapProfile = snapshot.bootstrapProfile !== null && (
    snapshot.status === 'bootstrap'
    || (snapshot.status === 'watching' && snapshot.cycles.length === 0 && snapshot.failures.length === 0)
  );
  return (
    <main className="ci-harness-dashboard">
      <div className="ci-harness-pinned">
        <HarnessBanner snapshot={snapshot} hasEscalation={hasEscalation} />
        <section className="ci-harness-stats" aria-label="Watch statistics">
          <Stat label="Failures" value={String(sumFailures(stats.failuresByState))}
            detail={failureStateSummary(stats.failuresByState)} />
          <Stat label="Knowledge base" value={String(stats.activeRules)} detail={`${stats.candidateRules} candidates`} />
          <Stat label="Cycle cost" value={money(stats.cycleCostMilliUsd)} detail={snapshot.activeCycle?.id ?? ''} />
          <Stat label="Watch cost" value={money(stats.watchCostMilliUsd)}
            detail={snapshot.budget.limitMilliUsd <= 0 ? 'no budget cap' : `${money(snapshot.budget.limitMilliUsd)} budget`} />
        </section>
      </div>
      {showBootstrapProfile && <BootstrapTrustCard profile={snapshot.bootstrapProfile} />}
      {hasEscalation && <EscalationCard failures={snapshot.failures} />}
      {snapshot.status === 'handoff' && <HandoffProofCard snapshot={snapshot} />}
      {snapshot.status === 'green' && <GreenCompletionCard />}
      {snapshot.failures.length > 0 && <FailureTable rows={snapshot.failures} />}
      {candidates.length > 0 && (
        <section className="ci-harness-rules">
          <header><h2>Candidate rules</h2><span>{candidates.length}</span></header>
          {candidates.map(rule => (
            <article key={rule.id}>
              <span><strong>{rule.bucket}</strong><code>{rule.binding}</code>
                <small>{rule.scope ?? 'repository'} · {rule.origin} · priority {rule.priority}</small></span>
              <code className="ci-harness-rules__matcher">{rule.matcherPattern}</code>
              <small>{rule.hits} evidence hit{rule.hits === 1 ? '' : 's'}</small>
              <button type="button" disabled={busy} onClick={() => onApproveRule(rule.id)}>Approve rule</button>
            </article>
          ))}
        </section>
      )}
      <section className="ci-harness-feed">
        <header><h2>Milestones</h2><span>{snapshot.milestones.length}</span></header>
        {snapshot.milestones.map(item => <Milestone key={item.id} item={item} />)}
        {snapshot.milestones.length === 0 && <p className="ci-harness-empty-feed">No milestones recorded yet.</p>}
      </section>
      {(snapshot.runStatusTail ?? '').length > 0 && (
        <details className="ci-harness-run-tail"><summary>Latest run status</summary><pre>{snapshot.runStatusTail}</pre></details>
      )}
    </main>
  );
}

function HarnessBanner({ snapshot, hasEscalation }: {
  snapshot: CiHarnessWatchSnapshotDto;
  hasEscalation: boolean;
}) {
  if (snapshot.status === 'bootstrap') return (
    <section className="ci-harness-banner is-bootstrap"><strong>Bootstrapping project knowledge</strong>
      <span>Reading the repository's CI and build configuration before the first probe.</span></section>
  );
  if (snapshot.status === 'needs_attention' || hasEscalation) return (
    <section className="ci-harness-banner is-warning"><strong>Needs you</strong>
      <span>The harness paused instead of guessing. Review the escalated failure below.</span></section>
  );
  if (snapshot.status === 'handoff') return (
    <section className="ci-harness-banner is-handoff"><strong>Ready for handoff · nothing was pushed</strong>
      {snapshot.handoffCommand !== null && <CopyCommand command={snapshot.handoffCommand} />}
    </section>
  );
  if (snapshot.status === 'green') return (
    <section className="ci-harness-banner is-green"><strong>All checks are green</strong>
      <span>The watch is complete. The local branch is ready for your review and push.</span></section>
  );
  return null;
}

function GreenCompletionCard() {
  return (
    <section className="ci-harness-green-card">
      <span aria-hidden>✓</span>
      <div><h2>CI is green</h2>
        <p>The latest remote head passed every non-aggregator check. Monitoring stays on until you unwatch it.</p></div>
    </section>
  );
}

function BootstrapTrustCard({ profile }: { profile: CiHarnessBootstrapProfileDto }) {
  const entries = Object.entries(profile).filter(([, value]) => value !== null && value !== undefined);
  return (
    <section className="ci-harness-bootstrap-card">
      <header><span aria-hidden>⌾</span><div><h2>Bootstrap trust profile</h2>
        <small>Derived from this repository's CI and build configuration.</small></div></header>
      <div>
        {entries.map(([key, value]) => (
          <article key={key} className={key.toLowerCase().includes('warning') ? 'is-warning' : ''}>
            <strong>{humanize(key)}</strong><ProfileValue value={value} />
          </article>
        ))}
        {entries.length === 0 && <p>No bootstrap facts were recorded.</p>}
      </div>
    </section>
  );
}

function ProfileValue({ value }: { value: unknown }) {
  if (Array.isArray(value)) return (
    <span>{value.map((item, index) => <code key={`${String(item)}-${index}`}>{formatValue(item)}</code>)}</span>
  );
  if (typeof value === 'object' && value !== null) return (
    <pre>{JSON.stringify(value, null, 2)}</pre>
  );
  return <span>{formatValue(value)}</span>;
}

function EscalationCard({ failures }: { failures: CiHarnessFailureDto[] }) {
  const failure = failures.find(row => row.status.toLowerCase().includes('escalat')
    || row.status.toLowerCase().includes('attention')) ?? failures[0];
  if (failure === undefined) return null;
  return (
    <section className="ci-harness-escalation-card">
      <header><span aria-hidden>!</span><div><h2>{failure.jobName}</h2><small>{failure.module} · {failure.bucket}</small></div></header>
      <strong>{failure.signature}</strong>
      {failure.targetSubject !== null && <p>Proposed semantic owner <code>{failure.targetSubject}</code></p>}
      <pre>{failure.logExcerpt}</pre>
      <small>The harness paused for judgement; it did not apply an unverified guess.</small>
    </section>
  );
}

function HandoffProofCard({ snapshot }: { snapshot: CiHarnessWatchSnapshotDto }) {
  const proof = snapshot.netNeutralProof;
  return (
    <section className="ci-harness-proof-card">
      <header><h2>History rewrite proof</h2><span>local handoff</span></header>
      {snapshot.backupRef !== null && <div><small>Backup ref</small><code>{snapshot.backupRef}</code></div>}
      {proof === null ? <p>No history rewrite proof was recorded.</p> : (
        <>
          <div className="ci-harness-proof-card__heads">
            <span><small>Before</small><code>{proof.beforeHead}</code><code>{proof.beforeTree}</code></span>
            <b aria-hidden>→</b>
            <span><small>After</small><code>{proof.afterHead}</code><code>{proof.afterTree}</code></span>
          </div>
          <div className="ci-harness-proof-card__checks">
            <ProofCheck passed={proof.emptyTreeDiff} label="Tree diff empty" />
            <ProofCheck passed={proof.rangeEquivalent} label="Range equivalent" />
            <ProofCheck passed={proof.remoteUndiverged} label="Remote undiverged" />
          </div>
          {proof.detail !== null && <p>{proof.detail}</p>}
        </>
      )}
      {snapshot.handoffCommand !== null && <CopyCommand command={snapshot.handoffCommand} />}
    </section>
  );
}

function ProofCheck({ passed, label }: { passed: boolean; label: string }) {
  return <span className={passed ? 'passed' : 'failed'}>{passed ? '✓' : '×'} {label}</span>;
}

function CopyCommand({ command }: { command: string }) {
  const [copied, setCopied] = useState(false);
  return <button type="button" onClick={() => {
    void navigator.clipboard.writeText(command).then(() => setCopied(true));
  }}><code>{command}</code><span>{copied ? 'Copied' : 'Copy'}</span></button>;
}

function Stat({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <div><small>{label}</small><strong>{value}</strong><span>{detail}</span></div>;
}

function FailureTable({ rows }: { rows: CiHarnessFailureDto[] }) {
  return (
    <section className="ci-harness-failures">
      <header><h2>Failures</h2><span>{rows.length}</span></header>
      <div role="table">
        {rows.map(row => (
          <div role="row" key={row.id}>
            <code>{row.module}</code><strong>{row.signature}</strong><i>{row.bucket}</i>
            <span>{row.ruleId ?? row.targetSubject ?? 'unrouted'}</span>
            <b className={`is-${row.status.toLowerCase()}`}>{row.status.replace('_', ' ')}</b>
          </div>
        ))}
      </div>
    </section>
  );
}

function Milestone({ item }: { item: CiHarnessMilestoneDto }) {
  return (
    <article className={`ci-harness-milestone is-${milestoneTone(item.kind)}`}>
      <span className="ci-harness-milestone__dot" />
      <div><strong>{item.message}</strong>{item.detailJson !== null && <p>{detailText(item.detailJson)}</p>}
        <small>{formatTime(item.createdAtMs)}{item.phase === null ? '' : ` · ${item.phase}`}</small></div>
    </article>
  );
}

function sumFailures(failures: Record<string, number>): number {
  return Object.values(failures).reduce((sum, value) => sum + value, 0);
}

function failureStateSummary(failures: Record<string, number>): string {
  const states = Object.entries(failures).filter(([, count]) => count > 0);
  if (states.length === 0) return 'No failures recorded';
  return states.map(([state, count]) => `${count} ${humanize(state.toLowerCase())}`).join(' · ');
}

function money(milliUsd: number): string {
  return `$${(milliUsd / 1000).toFixed(2)}`;
}

function formatTime(value: number): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

function milestoneTone(kind: string): 'info' | 'success' | 'warning' | 'error' {
  const normalized = kind.toLowerCase();
  if (normalized.includes('fail') || normalized.includes('error')) return 'error';
  if (normalized.includes('escalat') || normalized.includes('pause')) return 'warning';
  if (normalized.includes('pass') || normalized.includes('verify') || normalized.includes('commit')) return 'success';
  return 'info';
}

function detailText(value: string): string {
  try {
    const parsed = JSON.parse(value) as unknown;
    return typeof parsed === 'string' ? parsed : JSON.stringify(parsed);
  }
  catch {
    return value;
  }
}

function humanize(value: string): string {
  return value.replace(/([a-z])([A-Z])/g, '$1 $2').replaceAll('_', ' ');
}

function formatValue(value: unknown): string {
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}
