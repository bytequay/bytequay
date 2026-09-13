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

import { GitMerge, GitPullRequest, GitPullRequestClosed, GitPullRequestDraft } from 'lucide-react';
import React from 'react';
import type { LocalReviewThreads } from '../pulls/local-review/types';
import PullChanges, { DiffViewer, type CommitRange, type DiffAgentComment } from '../pulls/PullChanges';
import PullOverview, { type PullDetailUpdater } from '../pulls/PullOverview';
import { canSubmitReview } from '../pulls/PullsScreen';
import type { PullRequestDetailSource } from '../pulls/usePullRequestDetail';
import type {
  DiffCommitDto,
  DiffFileDto,
  InlineReviewCommentDto,
  PullRequestDetailDto,
  PullRequestDto,
} from '../types';

/**
 * The one pull request the panel reads for this task's branch.
 *
 * The branch match can return several rows over a task's life — an abandoned attempt, a merged
 * delivery, the current one. An open pull request is the one the task is working on; without one,
 * the merged pull request is what the task delivered, so the task stays linked to it. Only a closed,
 * unmerged row leaves the branch without a pull request, which is the state that offers a fresh
 * draft. Within each state the newest number wins.
 */
export function taskPullRequest(pullRequests: PullRequestDto[]): PullRequestDto | null {
  const newest = (matches: (pr: PullRequestDto) => boolean): PullRequestDto | null => pullRequests
    .filter(matches)
    .reduce<PullRequestDto | null>((best, pr) => (best === null || pr.number > best.number ? pr : best), null);
  return newest((pr) => pr.state !== 'merged' && pr.state !== 'closed') ?? newest((pr) => pr.state === 'merged');
}

/**
 * Whether a transcript link addresses the pull request the panel already holds.
 *
 * The agent writes the URL GitHub gave it, which may carry a comment anchor or tracking query.
 * Only the repository and number decide, so `#issuecomment-1` still resolves to the same row.
 */
export function samePullRequest(href: string | null, pr: Pick<PullRequestDto, 'repo' | 'number'>): boolean {
  if (href === null) return false;
  const path = href.split('#')[0].split('?')[0].replace(/\/+$/, '').toLowerCase();
  return path.endsWith(`/${pr.repo.toLowerCase()}/pull/${pr.number}`);
}

/**
 * A pull request's state in GitHub's shape and colour — open, draft, merged or closed — wherever a
 * task or a review names one. Without a stored pull request it stays a plain grey mark.
 */
export function PullRequestStateIcon({ pullRequest, size, className }: {
  pullRequest: Pick<PullRequestDto, 'state' | 'draft'> | null;
  size: number;
  className?: string;
}): React.JSX.Element {
  const state = pullRequest === null ? null
    : pullRequest.state === 'merged' ? 'merged' : pullRequest.state === 'closed' ? 'closed' : pullRequest.draft ? 'draft' : 'open';
  const Icon = state === 'merged' ? GitMerge : state === 'closed' ? GitPullRequestClosed : state === 'draft' ? GitPullRequestDraft : GitPullRequest;
  return <Icon className={['agt-pr-icon', state === null ? null : `is-${state}`, className].filter(Boolean).join(' ')} size={size} aria-hidden="true" />;
}

export type TaskPullRequestDetail = PullRequestDetailSource;

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

/**
 * The task's pull request, read in the panel beside the conversation.
 *
 * This mounts the same detail surface the full-width page mounts; the panel supplies its own
 * reads and leaves navigation alone, so opening a task's pull request never leaves the task.
 */
export function TaskPullRequestView({
  pr, workspaceId, detail, loading, error, onRefresh, onDetailUpdate, onDetailChange, localReview,
}: {
  pr: PullRequestDto;
  localReview?: LocalReviewThreads;
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
        localReview={localReview}
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

/**
 * The pull request's diff in the panel — the same diff the full-width page renders.
 *
 * This mounts `PullChanges` rather than the bare `DiffViewer` underneath it, so reviewing a task's
 * own pull request beside its conversation offers what reviewing anyone else's does: the commit
 * range picker, the inline comment gutter, and the review threads already on the diff. Without the
 * detail there is nothing to hang threads on, so it falls back to the plain viewer.
 */
export function TaskChangesView({
  pr, detail, changes, workspaceId, commitRange, pendingComments,
  onCommitRangeChange, onPendingCommentsChange, onRefresh, onDetailUpdate, onDetailChange, onAskAgent, onAskAgentDisabled, localReview,
}: {
  pr: PullRequestDto | null;
  localReview?: LocalReviewThreads;
  detail: PullRequestDetailDto | null;
  changes: TaskPullRequestChanges;
  workspaceId?: string;
  commitRange: CommitRange;
  pendingComments: InlineReviewCommentDto[];
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
  onAskAgentDisabled?: string | null;
  onCommitRangeChange: (range: CommitRange) => void;
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  onDetailChange: (detail: PullRequestDetailDto) => void;
}): React.JSX.Element {
  if (pr === null) {
    return <div className="agt-muted agt-panel__empty">No changes yet — this task&apos;s branch has no pull request.</div>;
  }
  const pullRequestReview = localReview === undefined ? undefined : { ...localReview,
    threads: localReview.threads.filter(thread => thread.repo.toLowerCase() === pr.repo.toLowerCase() && thread.number === pr.number),
  };
  const loadFileContent = (path: string, baseSha?: string, headSha?: string) =>
    window.bridge.refreshPrDiffFileContent(pr.repo, pr.number, path, baseSha, headSha);
  return (
    <>
      {changes.error !== null && <div className="agt-note" role="alert">{changes.error}</div>}
      {detail === null ? (
        <DiffViewer
          files={changes.files}
          commits={changes.commits}
          commitRange={commitRange}
          loading={changes.loading}
          onCommitRangeChange={onCommitRangeChange}
          loadFileContent={loadFileContent}
          localReview={pullRequestReview}
          onAskAgent={onAskAgent}
          onAskAgentDisabled={onAskAgentDisabled}
        />
      ) : (
        <PullChanges
          repo={detail.repo}
          number={detail.number}
          workspaceId={workspaceId}
          files={changes.files}
          commits={changes.commits}
          commitRange={commitRange}
          loading={changes.loading}
          canReview={canSubmitReview(pr, detail)}
          localReview={pullRequestReview}
          onAskAgent={onAskAgent}
          onAskAgentDisabled={onAskAgentDisabled}
          pendingComments={pendingComments}
          onCommitRangeChange={onCommitRangeChange}
          reviewThreads={{
            activities: detail.recentActivity,
            pr,
            workspaceId,
            unavailable: detail.unavailableSections.includes('reviewThreads'),
            onRefresh,
            onDetailUpdate,
          }}
          onPendingCommentsChange={onPendingCommentsChange}
          onDetailChange={onDetailChange}
        />
      )}
    </>
  );
}

/**
 * The PR tab before the branch has one.
 *
 * Create draft PR is the only button in the panel, and the click is what authorizes ByteQuay to
 * push the task's committed work and open the draft. It commits nothing, so the backend refuses a
 * task that still has uncommitted changes.
 */
export function NoPullRequestView({ branch, repository, onCreate, createBlocked = null }: {
  branch: string | null;
  repository: string | null;
  /** Absent in a static render. */
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
          title={createBlocked ?? 'Asks ByteQuay to push this branch and open a draft pull request from the repository template'}
          onClick={onCreate}
        >
          Create draft PR
        </button>
      )}
      <div className="agt-nopr__note">
        Asks ByteQuay to publish this task&apos;s committed work. It commits nothing; ByteQuay
        pushes the branch and opens the draft.
      </div>
    </div>
  );
}
