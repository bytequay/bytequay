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
import ResizeHandle from '../ResizeHandle';
import PullDetailPane from '../pulls/PullDetailPane';
import type { PullRow } from '../pulls/model';
import { pullRowFromDto, toDashboardPr } from '../pulls/workspaceModel';
import { Composer, Main, Shell, usePaneWidth } from '../ui/shell';
import type { WsNavKey } from '../ui/workspace';
import {
  workspaceApi,
  type CiHarnessWatchDto,
  type CiHarnessWatchSnapshotDto,
  type CiHarnessRuleDto,
  type WorkspaceRepositoryDto,
} from './workspaceApi';
import { HarnessHeader, HarnessIdle, HarnessSidebar } from './WorkspaceHarnessChrome';
import { HarnessDashboard } from './WorkspaceHarnessDashboard';

const ACTIVE_REFRESH_MS = 3_000;

export default function WorkspaceHarnessPage({
  workspaceId,
  watchId,
  workspaceName,
  workspaceRepository,
  onOpenWatch,
  onNewWatch,
  onNavigateGlobal,
  onSwitchWorkspace,
}: {
  workspaceId: string;
  watchId?: string;
  workspaceName?: string;
  workspaceRepository?: string;
  onOpenWatch?: (watchId: string) => void;
  onNewWatch?: () => void;
  onNavigateGlobal?: (key: WsNavKey) => void;
  onSwitchWorkspace?: () => void;
}) {
  const [watches, setWatches] = useState<CiHarnessWatchDto[]>([]);
  const [snapshot, setSnapshot] = useState<CiHarnessWatchSnapshotDto | null>(null);
  const [repository, setRepository] = useState<WorkspaceRepositoryDto | null>(null);
  const [pulls, setPulls] = useState<Awaited<ReturnType<typeof workspaceApi.pullRequests>>>([]);
  const [selectedPr, setSelectedPr] = useState<number | null>(null);
  const [budget, setBudget] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [prRow, setPrRow] = useState<PullRow | null>(null);
  const [rules, setRules] = useState<CiHarnessRuleDto[]>([]);
  const [prOpen, setPrOpen] = useState(true);
  const { paneWidth, bodyRef, onResize } = usePaneWidth();
  const currentWatchId = snapshot?.watchId;
  const currentWatchStatus = snapshot?.status;
  const currentPrNumber = snapshot?.prNumber;
  const currentLocalPrId = snapshot?.localPrId;
  const currentOwner = snapshot?.owner;
  const currentRepo = snapshot?.repo;

  const loadWatches = useCallback(async () => {
    const next = await workspaceApi.harnessWatches(workspaceId);
    setWatches(next);
    return next;
  }, [workspaceId]);

  const loadSnapshot = useCallback(async (id: string) => {
    const next = await workspaceApi.harnessWatch(workspaceId, id);
    setSnapshot(next);
    return next;
  }, [workspaceId]);

  const loadRules = useCallback(async (id: string) => {
    const next = await workspaceApi.harnessRules(workspaceId, id);
    setRules(next);
    return next;
  }, [workspaceId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    void Promise.all([
      workspaceApi.repository(workspaceId),
      workspaceApi.pullRequests(workspaceId),
      workspaceApi.harnessWatches(workspaceId),
    ]).then(async ([nextRepo, nextPulls, nextWatches]) => {
      if (cancelled) return;
      setRepository(nextRepo);
      setPulls(nextPulls);
      setWatches(nextWatches);
      setSelectedPr(nextPulls.find(row => row.state === 'open' && row.ciStatus === 'FAILING')?.number ?? null);
      const selectedWatchId = watchId;
      if (selectedWatchId !== undefined) {
        const next = await workspaceApi.harnessWatch(workspaceId, selectedWatchId);
        if (!cancelled) setSnapshot(next);
      }
      else {
        setSnapshot(null);
      }
    }).catch(reason => { if (!cancelled) setError(errorMessage(reason)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [watchId, workspaceId]);

  useEffect(() => {
    if (currentWatchId === undefined || currentWatchStatus === undefined || !isPollableHarnessStatus(currentWatchStatus)) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      void Promise.all([
        loadSnapshot(currentWatchId),
        loadWatches(),
        loadRules(currentWatchId),
      ]).catch(() => { /* keep the last complete dashboard */ });
    }, ACTIVE_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [currentWatchId, currentWatchStatus, loadRules, loadSnapshot, loadWatches]);

  useEffect(() => {
    setPrRow(null);
    if (currentWatchId === undefined || currentPrNumber === undefined || currentOwner === undefined || currentRepo === undefined) {
      return undefined;
    }
    let cancelled = false;
    let retry: number | undefined;
    const load = async () => {
      try {
        const dto = await workspaceApi.pullRequest(workspaceId, currentPrNumber);
        const local = currentLocalPrId === null || currentLocalPrId === undefined
          ? await window.bridge.getPrForRepoPull(currentOwner, currentRepo, currentPrNumber)
          : { id: currentLocalPrId };
        if (cancelled) return;
        const row = pullRowFromDto(dto);
        setPrRow({ ...row, id: local.id, dto: { ...toDashboardPr(dto), id: local.id } });
      }
      catch {
        if (!cancelled) retry = window.setTimeout(() => { void load(); }, ACTIVE_REFRESH_MS);
      }
    };
    void load();
    return () => {
      cancelled = true;
      if (retry !== undefined) window.clearTimeout(retry);
    };
  }, [currentLocalPrId, currentOwner, currentPrNumber, currentRepo, currentWatchId, workspaceId]);

  useEffect(() => {
    if (currentWatchId === undefined) {
      setRules([]);
      return undefined;
    }
    let cancelled = false;
    void loadRules(currentWatchId)
      .then(() => { /* loaded */ })
      .catch(() => { if (!cancelled) setRules([]); });
    return () => { cancelled = true; };
  }, [currentWatchId, loadRules]);
  const showPr = prOpen && prRow !== null;
  const currentRepository = workspaceRepository ?? repository?.fullName ?? workspaceName ?? 'Workspace';

  if (loading) return <div className="ci-harness-loading" role="status">Loading CI Harness…</div>;

  return (
    <div className="ci-harness-page">
      <Shell sidebarWidthKey="bytequay.ciHarness.sidebarWidth" sidebarWidthDefault={260}>
        <HarnessSidebar
          workspaceName={workspaceName ?? repository?.repo ?? 'Workspace'}
          repository={currentRepository}
          watches={watches}
          selectedId={snapshot?.watchId}
          snapshot={snapshot}
          onSelect={id => { onOpenWatch?.(id); void loadSnapshot(id); }}
          onNew={() => {
            setSnapshot(null);
            setPrRow(null);
            onNewWatch?.();
          }}
          onNavigateGlobal={onNavigateGlobal}
          onSwitchWorkspace={onSwitchWorkspace}
        />
        <Main topBar={(
          <HarnessHeader snapshot={snapshot} showPr={showPr} busy={busy}
            onTogglePr={prRow === null ? undefined : () => setPrOpen(open => !open)}
            onRun={snapshot === null ? undefined : () => {
              setBusy(true);
              setError(null);
              void workspaceApi.runHarnessWatch(workspaceId, snapshot.watchId)
                .then(next => { setSnapshot(next); setMessage(''); })
                .then(() => loadWatches())
                .catch(reason => setError(errorMessage(reason)))
                .finally(() => setBusy(false));
            }}
            onStop={snapshot === null ? undefined : () => {
              setBusy(true);
              setError(null);
              void workspaceApi.stopHarnessWatch(workspaceId, snapshot.watchId)
                .then(next => setSnapshot(next))
                .then(() => loadWatches())
                .catch(reason => setError(errorMessage(reason)))
                .finally(() => setBusy(false));
            }}
          />
        )}>
          <div ref={bodyRef} className={`ci-harness-body${showPr ? ' with-pr' : ''}`}>
            <div className="ci-harness-center">
              {error !== null && <div className="ci-harness-error" role="alert">{error}</div>}
              {snapshot === null ? (
                <HarnessIdle
                  pulls={pulls}
                  selectedPr={selectedPr}
                  budget={budget}
                  onSelectPr={setSelectedPr}
                  onBudget={setBudget}
                  busy={busy}
                  onCreate={() => {
                    if (selectedPr === null || repository === null) return;
                    const parsedBudget = Number(budget);
                    if (budget.trim().length > 0
                      && (!Number.isFinite(parsedBudget) || parsedBudget < 0.10 || parsedBudget > 100)) {
                      setError('Watch budget must be between $0.10 and $100.00.');
                      return;
                    }
                    setBusy(true);
                    setError(null);
                    const selectedPull = pulls.find(pull => pull.number === selectedPr);
                    void window.bridge.getPrForRepoPull(repository.owner, repository.repo, selectedPr)
                      .catch((): null => null)
                      .then(localPr => workspaceApi.createHarnessWatch(workspaceId, {
                        owner: repository.owner,
                        repo: repository.repo,
                        prNumber: selectedPr,
                        ...(localPr === null ? {} : { localPrId: localPr.id }),
                        ...(repository.local.localClonePath === null ? {} : { localPath: repository.local.localClonePath }),
                        ...(selectedPull?.headRef === undefined || selectedPull.headRef === null
                          ? {} : { branch: selectedPull.headRef }),
                        ...(selectedPull === undefined ? {} : { title: selectedPull.title }),
                        ...(budget.trim().length === 0 || !Number.isFinite(parsedBudget)
                          ? {} : { budgetMilliUsd: Math.round(parsedBudget * 1000) }),
                      })).then(next => {
                      setSnapshot(next);
                      onOpenWatch?.(next.watchId);
                      return loadWatches();
                    }).catch(reason => setError(errorMessage(reason)))
                      .finally(() => setBusy(false));
                  }}
                />
              ) : (
                <HarnessDashboard snapshot={snapshot} rules={rules} busy={busy}
                  onApproveRule={ruleId => {
                    setBusy(true);
                    setError(null);
                    void workspaceApi.approveHarnessRule(workspaceId, snapshot.watchId, ruleId)
                      .then(approved => setRules(current => current.map(rule => rule.id === approved.id ? approved : rule)))
                      .catch(reason => setError(errorMessage(reason)))
                      .finally(() => setBusy(false));
                  }} />
              )}
              {snapshot === null ? <Composer
                variant="workspace-v2"
                value=""
                onChange={() => {}}
                onSubmit={() => {}}
                disabled
                placeholder="Pick a PR to watch — or ask how the harness decides what it can fix…"
                meta="CI Harness"
              /> : <Composer
                variant="workspace-v2"
                value={message}
                onChange={setMessage}
                onSubmit={() => {
                  if (snapshot === null) return;
                  setBusy(true);
                  setError(null);
                  void workspaceApi.runHarnessWatch(workspaceId, snapshot.watchId, message)
                    .then(next => { setSnapshot(next); setMessage(''); })
                    .catch(reason => setError(errorMessage(reason)))
                    .finally(() => setBusy(false));
                }}
                busy={busy || snapshot?.status === 'running'}
                disabled={message.trim().length === 0 || snapshot.status === 'bootstrap' || snapshot.status === 'stopped'}
                onStop={() => {
                  setBusy(true);
                  void workspaceApi.stopHarnessWatch(workspaceId, snapshot.watchId)
                    .then(setSnapshot)
                    .catch(reason => setError(errorMessage(reason)))
                    .finally(() => setBusy(false));
                }}
                placeholder="Give the next diagnosis cycle optional guidance…"
                meta={money(snapshot.budget.spentMilliUsd)}
              />}
            </div>
            {showPr && (
              <aside className="ci-harness-pr" style={{ width: paneWidth }}>
                <ResizeHandle className="pane-resize pl-hov-drag" ariaLabel="Resize pull request panel"
                  onResize={onResize} style={{ position: 'absolute', left: -3, top: 0, bottom: 0, width: 6, zIndex: 5 }} />
                <PullDetailPane key={prRow.id} row={prRow} />
              </aside>
            )}
          </div>
        </Main>
      </Shell>
    </div>
  );
}

export function isPollableHarnessStatus(status: CiHarnessWatchDto['status']): boolean {
  return status !== 'stopped';
}

function money(milliUsd: number): string {
  return `$${(milliUsd / 1000).toFixed(2)}`;
}

function errorMessage(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}
