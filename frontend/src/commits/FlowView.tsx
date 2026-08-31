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
import { useEffect, useMemo, useRef, useState } from 'react';
import { relativeTime } from '../relativeTime';
import { providerAvailable } from '../settings/model';
import type {
  AgentProviderStatus,
  CherryPickExecution,
  CherryPickPreview,
  CherryPickPreviewCommit,
} from '../types';
import { shortSha } from './model';

type FlowItem = {
  commit: CherryPickPreviewCommit;
  position: number;
  already: boolean;
  alreadyWhy: string | null;
  viaPr: boolean;
  unchecked: boolean;
  effective: boolean;
  conflict: string | null;
};

function alreadyReason(commit: CherryPickPreviewCommit): string | null {
  if (!commit.alreadyOnTarget) return null;
  return commit.targetMatchKind === 'DIRECT_ANCESTOR' ? 'ancestor of target' : '-x line in target history';
}

export default function FlowView({
  preview,
  workspaceId,
  upstreamLabel,
  onStarted,
}: {
  preview: CherryPickPreview;
  workspaceId: string;
  /** e.g. "trinodb/trino" */
  upstreamLabel: string;
  onStarted: (execution: CherryPickExecution) => void;
}) {
  const [filter, setFilter] = useState('');
  const [unchecked, setUnchecked] = useState<ReadonlySet<string>>(new Set());
  const [skippedPrs, setSkippedPrs] = useState<ReadonlySet<number>>(new Set());
  const [prPanel, setPrPanel] = useState(false);
  const [prSearch, setPrSearch] = useState('');
  const [dryRun, setDryRun] = useState(false);
  const [prTitle, setPrTitle] = useState<string | null>(null);
  const [prBody, setPrBody] = useState<string | null>(null);
  const [providers, setProviders] = useState<AgentProviderStatus[]>([]);
  const [laneId, setLaneId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const idempotencyKey = useRef<string | null>(null);

  useEffect(() => {
    let active = true;
    window.bridge.getAgentProviderStatuses()
      .then(statuses => {
        if (!active) return;
        setProviders(statuses);
        const preferred = statuses.find(status => providerAvailable(status.readiness)) ?? statuses[0];
        setLaneId(current => current || preferred?.laneId || '');
      })
      .catch(failure => {
        if (active) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!prPanel) return;
    const close = () => setPrPanel(false);
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [prPanel]);

  const items: FlowItem[] = useMemo(() => preview.commits.map(commit => {
    const already = commit.alreadyOnTarget;
    const viaPr = !already && commit.pullRequestNumber != null
      && skippedPrs.has(commit.pullRequestNumber);
    const skipped = !already && unchecked.has(commit.sourceSha);
    return {
      commit,
      position: commit.position,
      already,
      alreadyWhy: alreadyReason(commit),
      viaPr,
      unchecked: skipped,
      effective: !already && !viaPr && !skipped,
      conflict: commit.conflictStatus === 'CONFLICT'
        ? `conflict likely — ${commit.conflictFiles.map(file => file.path.split('/').pop()).join(' · ')}`
        : null,
    };
  }), [preview, unchecked, skippedPrs]);

  const applyItems = items.filter(item => item.effective);
  const skipItems = items.filter(item => !item.effective && !item.already);
  const alreadyItems = items.filter(item => item.already);

  const query = filter.trim().toLowerCase();
  const visible = query === ''
    ? items
    : items.filter(item => item.commit.title.toLowerCase().includes(query));

  const prsInRange = useMemo(() => {
    const groups = new Map<number, { title: string; count: number; mergedAt: string | null }>();
    for (const commit of preview.commits) {
      if (commit.pullRequestNumber == null || commit.alreadyOnTarget) continue;
      const existing = groups.get(commit.pullRequestNumber);
      if (existing) existing.count++;
      else {
        groups.set(commit.pullRequestNumber, {
          title: commit.pullRequestTitle ?? `#${commit.pullRequestNumber}`,
          count: 1,
          mergedAt: commit.pullRequestMergedAt ?? null,
        });
      }
    }
    return [...groups.entries()].map(([number, value]) => ({ number, ...value }));
  }, [preview]);

  const prQuery = prSearch.trim().toLowerCase();
  const prRows = prsInRange.filter(pr => prQuery === ''
    || pr.title.toLowerCase().includes(prQuery)
    || String(pr.number).includes(prQuery));

  const titleValue = prTitle
    ?? `Cherry-pick ${applyItems.length} upstream commits into ${preview.targetBranch}`;
  const bodyValue = prBody ?? [
    `Cherry-picks ${upstreamLabel} ${shortSha(preview.sourceBaseSha)}…${shortSha(preview.sourceTipSha)} into ${preview.targetBranch} via ByteQuay.`,
    '',
    `* ${applyItems.length} commits applied, each carrying its (cherry picked from commit …) line`,
    `* skipped by you: ${skipItems.length > 0 ? skipItems.map(item => shortSha(item.commit.sourceSha)).join(', ') : 'none'}`,
    `* already on target: ${alreadyItems.length > 0 ? alreadyItems.map(item => shortSha(item.commit.sourceSha)).join(', ') : 'none'}`,
  ].join('\n');

  const toggleCommit = (sha: string) => {
    setUnchecked(current => {
      const next = new Set(current);
      if (next.has(sha)) next.delete(sha);
      else next.add(sha);
      return next;
    });
  };

  const togglePr = (number: number) => {
    setSkippedPrs(current => {
      const next = new Set(current);
      if (next.has(number)) next.delete(number);
      else next.add(number);
      return next;
    });
  };

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      idempotencyKey.current ??= crypto.randomUUID();
      const execution = await window.bridge.startCherryPickExecution({
        workspaceId,
        previewId: preview.previewId,
        expectedSourceBaseSha: preview.sourceBaseSha,
        expectedSourceTipSha: preview.sourceTipSha,
        expectedTargetSha: preview.targetSha,
        // PR-level skips travel as the commit SHAs they resolved to.
        userSkippedSourceShas: skipItems.map(item => item.commit.sourceSha),
        laneId,
        idempotencyKey: idempotencyKey.current,
        pullRequestTitle: titleValue,
        pullRequestBody: bodyValue,
      });
      onStarted(execution);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const selectedProvider = providers.find(provider => provider.laneId === laneId);
  const canStart = applyItems.length > 0
    && selectedProvider !== undefined
    && providerAvailable(selectedProvider.readiness)
    && !busy;

  const startButton = (
    <button
      type="button"
      className="cmt-btn-green"
      disabled={!canStart}
      onClick={() => { void start(); }}
    >
      {busy ? 'Starting…' : `Start cherry-pick (${applyItems.length})`}
    </button>
  );

  return (
    <div className="cmt-flow">
      <div className="cmt-flow-body">
        <div className="cmt-flow-route">
          {upstreamLabel} <span className="is-upstream">({preview.sourceRemote} · read-only)</span>
          {' → '}{preview.repository} · {preview.targetRemote}/{preview.targetBranch}
        </div>
        <h1 className="cmt-flow-title">
          Cherry-pick {items.length} commits → <span className="cmt-flow-branch">{preview.targetBranch}</span>
        </h1>
        <div className="cmt-flow-frozen">
          Frozen preview <code>{preview.previewId}</code>
          {' · source '}<code>{shortSha(preview.sourceBaseSha)}…{shortSha(preview.sourceTipSha)}</code>
          {' · target '}<code>{shortSha(preview.targetSha)}</code>
          {' '}— if {preview.targetBranch} moves before start, this preview goes stale and must be
          reviewed again.
        </div>

        {preview.warnings.map(warning => (
          <div key={warning} className="cmt-flow-warning">{warning}</div>
        ))}

        <div className="cmt-count-chips">
          <span className="is-apply">{applyItems.length} will apply</span>
          <span className="is-skip">{skipItems.length} skipped by you</span>
          <span className="is-already">{alreadyItems.length} already on target</span>
        </div>

        <div className="cmt-flow-controls">
          <input
            className="cmt-flow-filter"
            value={filter}
            placeholder="⌕ Filter commits by title…"
            onChange={event => setFilter(event.target.value)}
          />
          <div className="cmt-pr-skip" onMouseDown={event => event.stopPropagation()}>
            <button type="button" onClick={() => setPrPanel(open => !open)}>
              Skip by pull request <span className="cmt-pr-skip-count">{skippedPrs.size}</span>{' '}
              <span className="cmt-caret">▾</span>
            </button>
            {prPanel && (
              <div className="cmt-pr-panel">
                <div className="cmt-pr-panel-search">
                  <input
                    value={prSearch}
                    placeholder="⌕ Search pull requests in this range…"
                    onChange={event => setPrSearch(event.target.value)}
                  />
                </div>
                {prRows.map(pr => (
                  <label key={pr.number} className={`cmt-pr-row${skippedPrs.has(pr.number) ? ' is-skipped' : ''}`}>
                    <input
                      type="checkbox"
                      checked={skippedPrs.has(pr.number)}
                      onChange={() => togglePr(pr.number)}
                    />
                    <span className="cmt-pr-row-main">
                      <span className="cmt-pr-row-title">#{pr.number} {pr.title}</span>
                      <span className="cmt-pr-row-meta">
                        {pr.count} commit{pr.count === 1 ? '' : 's'} in range
                        {pr.mergedAt ? ` · merged ${relativeTime(pr.mergedAt)}` : ''}
                      </span>
                    </span>
                    {skippedPrs.has(pr.number) && <span className="cmt-pr-row-flag">SKIP</span>}
                  </label>
                ))}
                {prRows.length === 0 && (
                  <div className="cmt-pr-row-meta cmt-pr-none">No pull requests in this range.</div>
                )}
                <div className="cmt-pr-panel-foot">
                  <span>
                    Skipping a PR unchecks every commit in it. Skips travel to the backend as commit
                    SHAs, never as PR numbers.
                  </span>
                  <button type="button" onClick={() => setPrPanel(false)}>Done</button>
                </div>
              </div>
            )}
          </div>
          <div className="cmt-tasks-spacer" />
          <button type="button" className="cmt-btn-plain" onClick={() => { setDryRun(true); setPrPanel(false); }}>
            Dry run
          </button>
          {startButton}
        </div>

        <div className="cmt-flow-settings">
          <label>
            Conflict resolver
            <select value={laneId} onChange={event => setLaneId(event.target.value)}>
              {providers.map(provider => (
                <option key={provider.laneId} value={provider.laneId}>
                  {provider.laneId}
                  {!providerAvailable(provider.readiness) ? ' · unavailable' : ''}
                </option>
              ))}
            </select>
          </label>
        </div>

        {error && <div className="cmt-error" role="alert">{error}</div>}

        {query !== '' && (
          <div className="cmt-filter-note">
            Showing {visible.length} of {items.length} commits — the filter changes the view only;
            the plan and skips are unchanged.
          </div>
        )}

        <div className="cmt-pr-card">
          <div className="cmt-pr-card-head">
            <span>Pull request</span>
            <span className="cmt-pr-card-sub">
              title and description for the PR opened when you later push the work branch — nothing
              is sent to GitHub now
            </span>
          </div>
          <div className="cmt-pr-card-body">
            <input value={titleValue} onChange={event => setPrTitle(event.target.value)} />
            <textarea rows={5} value={bodyValue} onChange={event => setPrBody(event.target.value)} />
          </div>
        </div>

        <div className="cmt-order-card">
          <div className="cmt-order-head">
            Application order · oldest first <span>— checked commits will be cherry-picked</span>
          </div>
          {visible.map(item => (
            <div
              key={item.commit.sourceSha}
              className={`cmt-order-row${item.effective ? '' : ' is-off'}`}
            >
              <input
                type="checkbox"
                checked={item.effective}
                disabled={item.already}
                onChange={() => toggleCommit(item.commit.sourceSha)}
              />
              <span className="cmt-order-pos">{item.position}</span>
              <div className="cmt-order-main">
                <div className="cmt-order-title-row">
                  <span className="cmt-order-title">{item.commit.title}</span>
                  <span className="cmt-order-sha">{shortSha(item.commit.sourceSha)}</span>
                  {item.commit.pullRequestNumber != null && (
                    <span className="cmt-chip is-pr">
                      #{item.commit.pullRequestNumber} {item.commit.pullRequestTitle ?? ''}
                    </span>
                  )}
                </div>
                <div className="cmt-order-chips">
                  {item.alreadyWhy && (
                    <span className="cmt-chip is-already">already on target · {item.alreadyWhy}</span>
                  )}
                  {item.viaPr && (
                    <span className="cmt-chip is-skip">skipped via PR #{item.commit.pullRequestNumber}</span>
                  )}
                  {!item.viaPr && item.unchecked && (
                    <span className="cmt-chip is-skip">skipped by you</span>
                  )}
                  {item.conflict && item.effective && (
                    <span className="cmt-chip is-conflict">⚠ {item.conflict}</span>
                  )}
                </div>
              </div>
              <span className="cmt-order-when">
                {item.commit.author} · {relativeTime(item.commit.committedAt)}
              </span>
            </div>
          ))}
        </div>
      </div>

      {dryRun && (
        <div className="cmt-modal-overlay" onClick={() => setDryRun(false)}>
          <div className="cmt-modal" onClick={event => event.stopPropagation()}>
            <div className="cmt-modal-head">
              <span className="cmt-modal-title">Dry run — execution plan</span>
              <div className="cmt-tasks-spacer" />
              <button type="button" className="cmt-modal-close" onClick={() => setDryRun(false)}>×</button>
            </div>
            <div className="cmt-modal-body">
              <div className="cmt-dry-label is-apply">
                WILL CHERRY-PICK ({applyItems.length}) — in this order
              </div>
              <div className="cmt-dry-list">
                {applyItems.map(item => (
                  <div key={item.commit.sourceSha} className="cmt-dry-row">
                    <span className="cmt-order-pos">{item.position}</span>
                    <span className="cmt-order-sha">{shortSha(item.commit.sourceSha)}</span>
                    <span className="cmt-dry-title">{item.commit.title}</span>
                    {item.conflict && <span className="cmt-dry-conflict">⚠ conflict likely</span>}
                  </div>
                ))}
              </div>
              <div className="cmt-dry-label is-skip">SKIPPED BY YOU ({skipItems.length})</div>
              <div className="cmt-dry-list">
                {skipItems.map(item => (
                  <div key={item.commit.sourceSha} className="cmt-dry-row is-skip">
                    <span className="cmt-order-pos">{item.position}</span>
                    <span className="cmt-order-sha">{shortSha(item.commit.sourceSha)}</span>
                    <span className="cmt-dry-title">{item.commit.title}</span>
                    <span className="cmt-dry-reason">
                      {item.viaPr ? `skipped via PR #${item.commit.pullRequestNumber}` : 'skipped by you'}
                    </span>
                  </div>
                ))}
              </div>
              <div className="cmt-dry-label is-already">
                ALREADY ON TARGET ({alreadyItems.length}) — detected automatically, never executed
              </div>
              <div className="cmt-dry-list">
                {alreadyItems.map(item => (
                  <div key={item.commit.sourceSha} className="cmt-dry-row is-already">
                    <span className="cmt-order-pos">{item.position}</span>
                    <span className="cmt-order-sha">{shortSha(item.commit.sourceSha)}</span>
                    <span className="cmt-dry-title">{item.commit.title}</span>
                    <span className="cmt-dry-reason">{item.alreadyWhy}</span>
                  </div>
                ))}
              </div>
            </div>
            <div className="cmt-modal-foot">
              <div className="cmt-tasks-spacer" />
              <button type="button" className="cmt-btn-plain" onClick={() => setDryRun(false)}>Back</button>
              {startButton}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
