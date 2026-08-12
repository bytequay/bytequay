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
import {
  workspaceApi,
  type WorkspaceRelationDto,
  type WorkspaceRepositoryDto,
} from './workspaceApi';
import { useUpstreamSyncs } from './useUpstreamSyncs';
import WorkspaceSyncRunPage from './WorkspaceSyncRunPage';
import WorkspaceSyncsHome from './WorkspaceSyncsHome';

const PR_RETRY_MS = 3_000;

/**
 * Every upstream cherry-pick run in the workspace, the selected run, and its
 * pull request beside it.
 */
export default function WorkspaceSyncsPage({
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
  const [relation, setRelation] = useState<WorkspaceRelationDto | null>(null);
  const [prRow, setPrRow] = useState<PullRow | null>(null);

  // No run named means the list, not the newest run. Landing straight in a
  // cockpit put the other runs inside the one you happened to get; they live on
  // the home page now.
  const selected = jobId;
  const job = syncs?.find(row => row.jobId === selected);
  const prNumber = job?.prNumber ?? null;

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.repository(workspaceId)
      .then(next => { if (!cancelled) setRepository(next); })
      .catch(() => { /* the run still reads without its pull request */ });
    // The header's "upstream → fork" line. A workspace with no relation has no
    // syncs either, so a failure here is not worth surfacing.
    void workspaceApi.relation(workspaceId)
      .then(next => { if (!cancelled) setRelation(next); })
      .catch(() => {});
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
    if (!loaded) {
      return <div className="sr-loading" role="status">Loading sync runs…</div>;
    }
    return (
      <WorkspaceSyncsHome
        runs={syncs}
        upstreamRepo={relation?.upstreamRepoFullName}
        targetRepo={repository === null
          ? undefined : `${repository.owner}/${repository.repo}`}
        onOpenSync={onOpenSync}
        onNewSync={onNewSync}
      />
    );
  }

  return (
    <div className="workspace-syncs-page">
      <WorkspaceSyncRunPage
        workspaceId={workspaceId}
        jobId={selected}
        onBack={onBack}
        rightPane={prRow === null ? undefined : <PullDetailPane key={prRow.id} row={prRow} />}
      />
    </div>
  );
}
