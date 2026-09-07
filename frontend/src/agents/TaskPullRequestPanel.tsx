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

import { GitPullRequest } from 'lucide-react';
import React, { useEffect, useState } from 'react';
import { DiffViewer } from '../pulls/PullChanges';
import PullOverview, { remoteErrorMessage, type PullDetailUpdater } from '../pulls/PullOverview';
import { usePullRequestDetail, type PullRequestDetailSource } from '../pulls/usePullRequestDetail';
import type { DiffCommitDto, DiffFileDto, PullRequestDetailDto, PullRequestDto } from '../types';

/** Identifies a pull request the way GitHub addresses it, so a re-read can be matched to its row. */
function reference(pr: PullRequestDto | null): string | null {
  return pr === null ? null : `${pr.repo.toLowerCase()}#${pr.number}`;
}

/**
 * The one pull request the panel reads: the open one on this task's branch.
 *
 * The branch match can return several rows over a task's life — an abandoned attempt, a merged
 * predecessor, the current one. Only a pull request that is still open is the one this task is
 * working on; a merged or closed row leaves the branch without an open pull request, which is
 * the state that offers a fresh draft. Among several open rows the newest number wins.
 */
export function taskPullRequest(pullRequests: PullRequestDto[]): PullRequestDto | null {
  return pullRequests
    .filter((pr) => pr.state !== 'merged' && pr.state !== 'closed')
    .reduce<PullRequestDto | null>((best, pr) => (best === null || pr.number > best.number ? pr : best), null);
}

/**
 * Whether a transcript link addresses the pull request the panel already holds.
 *
 * The agent writes the URL GitHub gave it, which may carry a comment anchor or tracking query.
 * Only the repository and number decide, so `#issuecomment-1` still resolves to the same row.
 */
export function samePullRequest(href: string | null, pr: PullRequestDto): boolean {
  if (href === null) return false;
  const path = href.split('#')[0].split('?')[0].replace(/\/+$/, '').toLowerCase();
  return path.endsWith(`/${pr.repo.toLowerCase()}/pull/${pr.number}`);
}

export type TaskPullRequestDetail = PullRequestDetailSource;

/**
 * The task's pull request detail, read and kept current exactly as the pull-request page reads it.
 *
 * The panel used to read the stored snapshot once and stop, on the premise that the branch refresh
 * at the end of a turn would carry new activity in. It does not — that sync writes list rows and
 * never touches the detail snapshot — so the panel showed a timeline frozen at whatever the last
 * refresh happened to write, and merge-queue traffic hours old simply never appeared.
 */
export function useTaskPullRequestDetail(pr: PullRequestDto | null, active: boolean): TaskPullRequestDetail {
  return usePullRequestDetail(pr === null ? null : { repo: pr.repo, number: pr.number }, { active });
}

/** What a panel rendered without a live bridge — a static render, a preview, a test — has read. */
export const NO_PULL_REQUEST_DETAIL: TaskPullRequestDetail = {
  detail: null,
  loading: false,
  error: null,
  refresh: async () => {},
  update: () => {},
  replace: () => {},
};

export interface TaskPullRequestChanges {
  files: DiffFileDto[];
  commits: DiffCommitDto[];
  loading: boolean;
  error: string | null;
}

export const NO_PULL_REQUEST_CHANGES: TaskPullRequestChanges = {
  files: [],
  commits: [],
  loading: false,
  error: null,
};

/** The pull request's diff, read the first time the Changes tab is opened for it. */
export function useTaskPullRequestChanges(pr: PullRequestDto | null, active: boolean): TaskPullRequestChanges {
  const [changes, setChanges] = useState<{ key: string; files: DiffFileDto[]; commits: DiffCommitDto[] } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const repo = pr?.repo ?? null;
  const number = pr?.number ?? null;
  const key = reference(pr);

  useEffect(() => {
    if (!active || repo === null || number === null || key === null || changes?.key === key) return;
    let live = true;
    setLoading(true);
    setError(null);
    Promise.all([
      window.bridge.fetchPrDiffFiles(repo, number),
      window.bridge.fetchPrDiffCommits(repo, number),
    ])
      .then(([files, commits]) => {
        if (live) setChanges({ key, files, commits });
      })
      .catch((failure: unknown) => {
        if (live) setError(remoteErrorMessage(failure));
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => { live = false; };
  }, [active, changes?.key, key, number, repo]);

  const current = changes?.key === key ? changes : null;
  return { files: current?.files ?? [], commits: current?.commits ?? [], loading, error };
}

/**
 * The task's pull request, read in the panel beside the conversation.
 *
 * This mounts the same detail surface the full-width page mounts; the panel supplies its own
 * reads and leaves navigation alone, so opening a task's pull request never leaves the task.
 */
export function TaskPullRequestView({
  pr, workspaceId, detail, loading, error, onRefresh, onDetailUpdate, onDetailChange,
}: {
  pr: PullRequestDto;
  workspaceId?: string;
  detail: PullRequestDetailDto | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  onDetailChange: (detail: PullRequestDetailDto) => void;
}): React.JSX.Element {
  if (detail === null) {
    return (
      <div className="agt-muted agt-panel__empty">
        {error ?? (loading ? 'Reading the pull request…' : `#${pr.number} ${pr.title}`)}
      </div>
    );
  }
  return (
    <>
      {error !== null && <div className="agt-note">{error}</div>}
      <PullOverview
        pr={pr}
        detail={detail}
        contributorsWorkspaceId={workspaceId}
        onRefresh={onRefresh}
        onDetailUpdate={onDetailUpdate}
        onDetailChange={onDetailChange}
      />
    </>
  );
}

/** The pull request's diff in the panel. Findings and inline comments stay on the full-width diff. */
export function TaskChangesView({ pr, changes }: {
  pr: PullRequestDto | null;
  changes: TaskPullRequestChanges;
}): React.JSX.Element {
  if (pr === null) {
    return <div className="agt-muted agt-panel__empty">No changes yet — this task&apos;s branch has no pull request.</div>;
  }
  if (changes.error !== null && changes.files.length === 0) {
    return <div className="agt-muted agt-panel__empty">{changes.error}</div>;
  }
  return (
    <DiffViewer
      files={changes.files}
      commits={changes.commits}
      loading={changes.loading}
      loadFileContent={(path, baseSha, headSha) =>
        window.bridge.refreshPrDiffFileContent(pr.repo, pr.number, path, baseSha, headSha)}
    />
  );
}

/**
 * The PR tab before the branch has one.
 *
 * Create draft PR is the only button in the panel. It sends the task's fixed request as an
 * ordinary turn, so the agent opens the draft under its usual approvals and ByteQuay itself
 * still writes nothing to GitHub.
 */
export function NoPullRequestView({ branch, repository, onCreate, createBlocked = null }: {
  branch: string | null;
  repository: string | null;
  /** Absent when the screen has no workspace to name the pull request's base. */
  onCreate?: () => void;
  createBlocked?: string | null;
}): React.JSX.Element {
  return (
    <div className="agt-nopr">
      <span className="agt-nopr__mark" aria-hidden="true"><GitPullRequest /></span>
      <div className="agt-nopr__title">No pull request yet</div>
      <div className="agt-nopr__body">
        This task&apos;s branch {branch !== null && <><code>{branch}</code> </>}
        has no open PR{repository !== null && <> on <strong>{repository}</strong></>}.
      </div>
      {onCreate !== undefined && (
        <button
          type="button"
          className="agt-nopr__create"
          disabled={createBlocked !== null}
          title={createBlocked ?? 'Asks the agent to commit, push this branch and open a draft pull request from the repository template'}
          onClick={onCreate}
        >
          Create draft PR
        </button>
      )}
      <div className="agt-nopr__note">
        Sends one request into this task&apos;s conversation. The agent commits, pushes and opens
        the draft; ByteQuay never writes to GitHub on its own.
      </div>
    </div>
  );
}
