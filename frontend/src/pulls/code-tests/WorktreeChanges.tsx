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
import { useEffect, useRef, useState } from 'react';
import { ChevronDown, RotateCw } from 'lucide-react';
import { DiffViewer, type CommitRange, type DiffViewerProps, type ReviewThreads } from '../PullChanges';
import { parseUnifiedDiff } from '../../diffParse';
import type { DiffCommitDto, DiffFileDto } from '../../types';
import type { LocalReviewThreads } from '../local-review/types';
import { remoteErrorMessage } from '../PullOverview';
import type { ReviewNavigationTarget } from '../agent-review/AgentReviewTab';
import type { AgentReviewDto } from '../agent-review/contracts';
import { codeHostKey, type CodeHostRef, type WorktreeChangesDto, type WorktreeChangesRequest } from './contracts';
import '../../css/workbench-changes.css';

export type WorktreePublishedReview = {
  files: DiffFileDto[];
  commits: DiffCommitDto[];
  loading: boolean;
  error: string | null;
  options: NonNullable<DiffViewerProps['review']>;
  threads?: ReviewThreads;
  localReview?: LocalReviewThreads;
  onAskAgent?: DiffViewerProps['onAskAgent'];
  onAskAgentDisabled?: string | null;
  onOpenPublished: () => void;
};

/** Local Git headers differ from GitHub's patch headers; compare the actual displayed rows and their coordinates. */
function patchRows(file: DiffFileDto) {
  return parseUnifiedDiff(file.patch?.replace(/\r?\n$/, '')).map(({ oldStart, oldCount, newStart, newCount, rows }) =>
    ({ oldStart, oldCount, newStart, newCount, rows }));
}

export function worktreeReviewMapping(data: WorktreeChangesDto, published: Pick<WorktreePublishedReview, 'files' | 'commits' | 'loading' | 'error'>): {
  range: CommitRange;
  reason: string | null;
} {
  const blocked = (reason: string): { range: CommitRange; reason: string } => ({ range: null, reason });
  if (published.loading) return blocked('Checking the published PR before enabling review comments.');
  if (published.error !== null) return blocked('Published PR details could not be verified. Refresh the PR to enable review comments.');
  if (data.truncated) return blocked('This comparison is incomplete. Review the published PR to comment on its lines.');
  if (data.scope === 'UNCOMMITTED') return blocked('Uncommitted changes cannot receive GitHub review comments.');
  if (data.scope === 'COMMIT') {
    const index = published.commits.findIndex(commit => commit.sha === data.selectedCommit);
    const local = data.commits.find(commit => commit.sha === data.selectedCommit);
    if (index >= 0 && local?.parentSha != null && local.parentSha === published.commits[index].parentSha) {
      return { range: { start: index, end: index }, reason: null };
    }
    return blocked('This commit is not in the published PR. Publish it before posting GitHub review comments.');
  }
  if (data.scope === 'ALL' && data.staged.length + data.unstaged.length + data.untracked.length > 0) {
    return blocked('This view includes uncommitted changes. Select a published commit to add GitHub review comments.');
  }
  const matchingRevisions = data.headSha === published.commits.at(-1)?.sha && data.baseSha === published.commits[0]?.parentSha;
  const matchingFiles = data.files.length === published.files.length && data.files.every(file => {
    const other = published.files.find(candidate => candidate.filename === file.filename);
    return other !== undefined && file.status === other.status && file.additions === other.additions && file.deletions === other.deletions
      && JSON.stringify(patchRows(file)) === JSON.stringify(patchRows(other));
  });
  return matchingRevisions && matchingFiles ? { range: null, reason: null }
    : blocked('These changes differ from the published PR. Select a published commit or open Published PR to add review comments.');
}

export default function WorktreeChanges({ host, active = true, selection, reviewNavigation, review, onReviewChange, onOpenInReview, toolbarTarget, defaultTreeOpen = false, publishedReview }: {
  host: CodeHostRef;
  active?: boolean;
  toolbarTarget?: HTMLElement | null;
  defaultTreeOpen?: boolean;
  selection?: { commitSha: string; token: number; headSha?: string; baseSha?: string } | null;
  reviewNavigation?: ReviewNavigationTarget | null;
  review?: AgentReviewDto | null;
  onReviewChange?: (review: AgentReviewDto) => void;
  onOpenInReview?: (findingId: string) => void;
  publishedReview?: WorktreePublishedReview;
}) {
  const [request, setRequest] = useState<WorktreeChangesRequest>({ scope: host.kind === 'review' ? 'COMMITTED' : 'ALL' });
  const [result, setResult] = useState<{ key: string; value: WorktreeChangesDto } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(false);
  const currentHost = useRef(host);
  currentHost.current = host;
  const currentRequest = useRef(request);
  currentRequest.current = request;
  const selectedSha = selection?.commitSha;
  const selectionToken = selection?.token;
  const selectedHead = selection?.headSha;
  const selectedBase = selection?.baseSha;
  const finding = review?.findings.find(item => item.id === reviewNavigation?.findingId);
  const findingRun = finding === undefined ? undefined : review?.runs.filter(run => run.headSha === finding.validatedHeadSha
    && (run.id === finding.runId || run.steps.some(step => step.validation?.candidates.some(candidate => candidate.outcome === 'SUPPORTED'
      && candidate.findingId === finding.id && candidate.findingRevision === finding.revision))))
    .sort((left, right) => right.ordinal - left.ordinal)[0];
  const reviewedHead = findingRun?.headSha;
  const reviewedBase = findingRun?.mergeBaseSha;
  const navigationToken = reviewNavigation?.token;
  useEffect(() => {
    if (selectedSha) setRequest({ scope: 'COMMIT', commit: selectedSha, head: selectedHead, base: selectedBase });
  }, [selectedSha, selectionToken, selectedHead, selectedBase]);
  useEffect(() => {
    if (reviewedHead && reviewedBase) setRequest({ scope: 'COMMITTED', head: reviewedHead, base: reviewedBase });
  }, [reviewedHead, reviewedBase, navigationToken]);
  const key = [codeHostKey(host), request.scope, request.commit, request.base, request.head].join(':');
  useEffect(() => {
    if (!active) return;
    let live = true;
    setLoading(true);
    setError(null);
    void window.bridge.readWorktreeChanges(currentHost.current, currentRequest.current)
      .then(value => { if (live) setResult({ key, value }); })
      .catch(cause => { if (live) setError(remoteErrorMessage(cause)); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [active, key, reload]);
  useEffect(() => {
    if (!active || request.scope === 'COMMIT' || request.head !== undefined) return;
    const timer = window.setInterval(() => setReload(value => value + 1), 5000);
    return () => window.clearInterval(timer);
  }, [active, request.head, request.scope]);
  const data = result?.key === key ? result.value : null;
  const mapping = data === null || publishedReview === undefined ? null : worktreeReviewMapping(data, publishedReview);
  const canPublish = mapping !== null && mapping.reason === null && error === null;
  const commits = data?.commits ?? result?.value.commits ?? [];
  const selected = request.scope === 'COMMIT' ? `commit:${request.commit}` : request.scope;
  const commit = commits.find(item => item.sha === request.commit);
  const frozen = host.kind === 'review' && request.scope === 'COMMITTED';
  const showFindings = frozen && review != null && onReviewChange !== undefined && onOpenInReview !== undefined;
  const sourceLabel = frozen ? 'Reviewed revision' : request.scope === 'COMMIT' || request.scope === 'COMMITTED' ? 'Committed revision' : 'Working copy';
  const revisionLabel = data === null ? 'Revision details' : request.scope === 'COMMIT'
    ? `${commit?.parentSha?.slice(0, 7) ?? ''} → ${data.selectedCommit?.slice(0, 7) ?? ''}`
    : request.scope === 'UNCOMMITTED' ? `${data.headSha.slice(0, 7)} + local`
      : `${data.baseSha.slice(0, 7)} → ${data.headSha.slice(0, 7)}${request.scope === 'ALL' ? ' + local' : ''}`;
  const toolbar = <>
    <label className="wb-changes__selection">
      <select aria-label="Change scope or commit" value={selected} onChange={event => {
        const value = event.target.value;
        setRequest(value.startsWith('commit:') ? { ...request, scope: 'COMMIT', commit: value.slice(7) }
          : { scope: value as WorktreeChangesRequest['scope'] });
      }}>
        <option value="ALL">All changes</option>
        <option value="UNCOMMITTED">Uncommitted changes</option>
        <option value="COMMITTED">{host.kind === 'review' ? 'Reviewed changes' : 'Committed changes'}</option>
        {commits.length > 0 && <optgroup label={`Commits (${commits.length})`}>
          {commits.map(item => <option key={item.sha} value={`commit:${item.sha}`}>
            {item.sha.slice(0, 7)} · {item.message?.split('\n')[0] ?? 'Commit'}
          </option>)}
        </optgroup>}
      </select>
    </label>
    {data !== null && <details key={key} className="wb-changes__details" onKeyDown={event => {
      if (event.key === 'Escape') { event.currentTarget.open = false; event.stopPropagation(); }
    }}>
      <summary aria-label="Revision and commit details" title={`${sourceLabel} · ${data.branch} · ${revisionLabel}`}>
        <code>{revisionLabel}</code><ChevronDown size={12} aria-hidden="true" />
      </summary>
      <div className="wb-changes__details-body">
        <div className="wb-changes__source">
          <strong>{sourceLabel}</strong><code>{data.branch}</code>
          <span>{request.scope === 'COMMIT' ? `Parent ${revisionLabel}` : `${data.baseLabel} · ${revisionLabel}`}</span>
          {(request.scope === 'ALL' || request.scope === 'UNCOMMITTED') && <span>
            {data.staged.length} staged · {data.unstaged.length} unstaged · {data.untracked.length} new files
          </span>}
        </div>
        {commit && <div className="wb-changes__commit">
          <strong>{commit.message?.split('\n')[0] ?? commit.sha}</strong>
          <span>{commit.author} · <code>{commit.sha.slice(0, 7)}</code></span>
          {commit.message?.includes('\n') && <pre>{commit.message.slice(commit.message.indexOf('\n')).trim()}</pre>}
        </div>}
      </div>
    </details>}
    <button type="button" className="ct-icon-button" aria-label="Refresh changes" title="Refresh changes" disabled={loading}
      onClick={() => setReload(value => value + 1)}><RotateCw size={14} aria-hidden="true" /></button>
  </>;
  return <div className="wb-changes">
    {error !== null && <div className="ct-banner is-error" role="alert">{error}
      <button type="button" onClick={() => setReload(value => value + 1)}>Retry</button>
    </div>}
    {data?.truncated && <div className="ct-banner" role="status">This comparison reached the display limit. Some changes are omitted.</div>}
    {publishedReview !== undefined && mapping?.reason != null && <div className="ct-banner" role="status">
      {mapping.reason}<button type="button" onClick={publishedReview.onOpenPublished}>View Published PR</button>
    </div>}
    <DiffViewer files={data?.files ?? []} loading={data === null && loading}
      commits={mapping?.reason === null ? publishedReview?.commits : undefined} commitRange={mapping?.reason === null ? mapping.range : undefined}
      review={canPublish ? publishedReview?.options : undefined} reviewThreads={canPublish ? publishedReview?.threads : undefined}
      localReview={canPublish ? publishedReview?.localReview : undefined}
      onAskAgent={canPublish ? publishedReview?.onAskAgent : undefined} onAskAgentDisabled={publishedReview?.onAskAgentDisabled}
      loadFileContent={canPublish && publishedReview !== undefined ? (path, baseSha, headSha) =>
        window.bridge.refreshPrDiffFileContent(publishedReview.options.repo, publishedReview.options.number, path, baseSha ?? data?.baseSha, headSha ?? data?.headSha) : undefined}
      toolbarTarget={toolbarTarget} toolbarStart={toolbar} toolbarVisible={active} defaultTreeOpen={defaultTreeOpen}
      agentReview={showFindings ? { review, viewer: null, headSha: data?.headSha, navigation: reviewNavigation ?? null, onReviewChange, onOpenInReview } : undefined} />
  </div>;
}
