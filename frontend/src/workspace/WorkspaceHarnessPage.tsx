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
import { useEffect, useState } from 'react';
import PullDetailPane from '../pulls/PullDetailPane';
import type { PullRow } from '../pulls/model';
import { pullRowFromDto, toDashboardPr } from '../pulls/workspaceModel';
import { workspaceApi, type WorkspaceRepositoryDto } from './workspaceApi';
import { useUpstreamSyncs } from './useUpstreamSyncs';
import WorkspaceSyncRunPage from './WorkspaceSyncRunPage';

const PR_RETRY_MS = 3_000;

/**
 * The CI Harness surface: every sync run in the workspace, the run you are
 * looking at, and its pull request beside it.
 *
 * <p>There is no separate watch dashboard any more. Phase 1 and phase 2 are one
 * run by design, so the run's own cockpit is the whole page — the harness shows
 * through it as the phase 2 status in the queue column rather than as a second
 * screen with its own vocabulary.
 */
export default function WorkspaceHarnessPage({
  workspaceId,
  jobId,
  onOpenSync,
  onNewSync,
  onBack,
}: {
  workspaceId: string;
  /** The run to show; without one the newest is opened. */
  jobId?: string;
  onOpenSync?: (jobId: string) => void;
  onNewSync?: () => void;
  onBack?: () => void;
}) {
  const syncs = useUpstreamSyncs(workspaceId);
  const loaded = syncs !== null;
  const [repository, setRepository] = useState<WorkspaceRepositoryDto | null>(null);
  const [prRow, setPrRow] = useState<PullRow | null>(null);

  // Landing on the surface with no run named opens the newest rather than an
  // empty frame; the list is right there to move off it.
  const selected = jobId ?? syncs?.at(0)?.jobId;
  const job = syncs?.find(row => row.jobId === selected);
  const prNumber = job?.prNumber ?? null;

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.repository(workspaceId)
      .then(next => { if (!cancelled) setRepository(next); })
      .catch(() => { /* the run still reads without its pull request */ });
    return () => { cancelled = true; };
  }, [workspaceId]);

  const owner = repository?.owner;
  const repo = repository?.repo;
  useEffect(() => {
    setPrRow(null);
    if (prNumber === null || owner === undefined || repo === undefined) return undefined;
    let cancelled = false;
    let retry: number | undefined;
    const load = async () => {
      try {
        const dto = await workspaceApi.pullRequest(workspaceId, prNumber);
        const local = await window.bridge.getPrForRepoPull(owner, repo, prNumber);
        if (cancelled) return;
        const row = pullRowFromDto(dto);
        setPrRow({ ...row, id: local.id, dto: { ...toDashboardPr(dto), id: local.id } });
      }
      catch {
        // A PR opened seconds ago has not been synced locally yet.
        if (!cancelled) retry = window.setTimeout(() => { void load(); }, PR_RETRY_MS);
      }
    };
    void load();
    return () => {
      cancelled = true;
      if (retry !== undefined) window.clearTimeout(retry);
    };
  }, [owner, prNumber, repo, workspaceId]);

  if (selected === undefined) {
    return (
      <div className="sr-loading" role="status">
        {loaded ? 'No sync run in this workspace yet.' : 'Loading sync runs…'}
        {loaded && onNewSync !== undefined && (
          <button type="button" onClick={onNewSync}>Start a sync run</button>
        )}
      </div>
    );
  }

  return (
    <div className="ci-harness-page">
      <WorkspaceSyncRunPage
        workspaceId={workspaceId}
        jobId={selected}
        syncs={syncs ?? []}
        onOpenSync={onOpenSync}
        onNewSync={onNewSync}
        onBack={onBack}
        rightPane={prRow === null ? undefined : <PullDetailPane key={prRow.id} row={prRow} />}
      />
    </div>
  );
}
