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
import { useCallback, useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react';
import type { DiffFileDto, PullRequestDetailDto, PullRequestDto } from '../types';
import PullChanges from './PullChanges';
import PullOverview, { isQueued } from './PullOverview';
import PullRowItem, { PullRequestIcon } from './PullRowItem';
import { PULL_TABS, reconcilePullRequestDetail, rowsForTab, type PullRow, type PullTab } from './model';
import '../css/pulls.css';

type DetailTab = 'overview' | 'changes';

const DETAIL_MIN = 680;
const DETAIL_MAX = 1600;
const DETAIL_DEFAULT = 1120;

export default function PullsScreen({
  initialPr,
  repositoryScope,
}: {
  initialPr?: { repo: string; number: number };
  repositoryScope?: { workspaceId: string; repository: string };
}) {
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [tab, setTab] = useState<PullTab>('all');
  const [selected, setSelected] = useState<number | null>(null);
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [detailTab, setDetailTab] = useState<DetailTab>('overview');
  const [diffs, setDiffs] = useState<DiffFileDto[] | null>(null);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detailWidth, setDetailWidth] = useState(DETAIL_DEFAULT);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadList = useCallback(async () => {
    setError(null);
    try {
      const rows = await window.bridge.fetchPrs(repositoryScope?.workspaceId);
      setPrs(rows);
      setSelected(current => current ?? rows[0]?.id ?? null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, [repositoryScope?.workspaceId]);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  useEffect(() => {
    if (!initialPr || loading) return;
    const match = prs.find(pr => pr.repo.toLowerCase() === initialPr.repo.toLowerCase()
      && pr.number === initialPr.number);
    if (match) {
      setSelected(match.id);
      return;
    }
    let cancelled = false;
    Promise.all([
      window.bridge.fetchPullRequestDetail(initialPr.repo, initialPr.number),
      window.bridge.getUserProfile().catch((): null => null),
    ]).then(([value, profile]) => {
      if (cancelled) return;
      const remote = remotePullRequest(value, profile?.login);
      setPrs(current => current.some(pr => pr.id === remote.id) ? current : [remote, ...current]);
      setDetail(value);
      setSelected(remote.id);
    }).catch(e => {
      if (!cancelled) setError(e instanceof Error ? e.message : String(e));
    });
    return () => { cancelled = true; };
  }, [initialPr, loading, prs]);

  const rows = useMemo(() => rowsForTab(prs, tab), [prs, tab]);
  const selectedPr = prs.find(pr => pr.id === selected) ?? null;

  useEffect(() => {
    if (!selectedPr) {
      setDetail(null);
      return;
    }
    if (detail?.repo.toLowerCase() === selectedPr.repo.toLowerCase()
        && detail.number === selectedPr.number) return;
    let cancelled = false;
    setDetailLoading(true);
    setError(null);
    window.bridge.fetchPullRequestDetail(selectedPr.repo, selectedPr.number)
      .then(value => {
        if (!cancelled) {
          setDetail(value);
          setPrs(current => current.map(pr => pr.id === selectedPr.id
            ? reconcilePullRequestDetail(pr, value)
            : pr));
        }
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedPr, detail?.repo, detail?.number]);

  useEffect(() => {
    if (detailTab !== 'changes' || !selectedPr || diffs !== null) return;
    window.bridge.fetchPrDiffFiles(selectedPr.repo, selectedPr.number)
      .then(setDiffs)
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, [detailTab, selectedPr, diffs]);

  const pick = (row: PullRow) => {
    setSelected(row.id);
    setDetailTab('overview');
    setDiffs(null);
  };

  const refresh = async () => {
    if (!selectedPr) return;
    setDetailLoading(true);
    try {
      const value = await window.bridge.refreshPullRequestDetail(selectedPr.repo, selectedPr.number);
      setDetail(value);
      setPrs(current => current.map(pr => pr.id === selectedPr.id
        ? reconcilePullRequestDetail(pr, value)
        : pr));
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setDetailLoading(false);
    }
  };

  const resizeDetail = (event: ReactMouseEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = event.currentTarget.parentElement?.getBoundingClientRect().width ?? detailWidth;
    const move = (next: globalThis.MouseEvent) => {
      setDetailWidth(Math.max(DETAIL_MIN, Math.min(
        DETAIL_MAX,
        startWidth + startX - next.clientX,
      )));
    };
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };

  const paneShown = paneOpen && selectedPr !== null;

  return (
    <div className="pulls-page">
      <section className="pulls-list">
        <header className="pulls-list__toolbar">
          {repositoryScope && (
            <span className="pulls-scope" title={`Showing ${repositoryScope.repository}`}>
              {repositoryScope.repository}
            </span>
          )}
          <nav className="pulls-tabs" aria-label="Pull request filters">
          {PULL_TABS.map(item => (
            <button
              key={item.key}
              type="button"
              className={tab === item.key ? 'is-active' : ''}
              onClick={() => setTab(item.key)}
            >
              {item.label}
            </button>
          ))}
          </nav>
          <button
            className="pulls-toolbar-button"
            type="button"
            title="Sync pull requests"
            aria-label="Sync pull requests"
            disabled={loading}
            onClick={() => { setLoading(true); void window.bridge.syncPrs(repositoryScope?.workspaceId).then(loadList).catch(e => {
              setError(e instanceof Error ? e.message : String(e));
              setLoading(false);
            }); }}
          >
            <RefreshIcon />
          </button>
          <button
            className={`pulls-toolbar-button${paneShown ? ' is-active' : ''}`}
            type="button"
            title="Toggle pull request details"
            aria-label="Toggle pull request details"
            aria-pressed={paneShown}
            onClick={() => setPaneOpen(value => !value)}
          >
            <PaneIcon />
          </button>
        </header>
        <div className="pulls-list__rows">
          {loading && <div className="hp-loading">Loading…</div>}
          {!loading && error && rows.length === 0 && (
            <div className="hp-empty" role="alert">
              Could not load pull requests{repositoryScope ? ` for ${repositoryScope.repository}` : ''}. {error}
            </div>
          )}
          {!loading && !error && rows.length === 0 && (
            <div className="hp-empty">
              No pull requests{repositoryScope ? ` for ${repositoryScope.repository}` : ''} in this view.
            </div>
          )}
          {rows.map(row => (
            <PullRowItem
              key={row.id}
              row={row}
              wide={!paneShown}
              selected={row.id === selected}
              onPick={() => pick(row)}
            />
          ))}
        </div>
      </section>

      {paneShown && selectedPr !== null && (
        <section className="pull-detail" style={{ width: detailWidth }}>
          <div className="pull-detail__resize" role="separator" aria-orientation="vertical"
            aria-label="Resize pull request panes" onMouseDown={resizeDetail} title="Drag to resize" />
          <PullDetailHeader
            pr={selectedPr}
            detail={detail}
            tab={detailTab}
            onTab={setDetailTab}
            onRefresh={refresh}
          />
          {error && <div className="pull-detail__error">{error}</div>}
          {detailLoading ? (
            <div className="pull-detail__loading">Loading details…</div>
          ) : detail === null ? (
            !error && <div className="pull-detail__loading">Select a pull request.</div>
          ) : (
            <DetailBody
              tab={detailTab}
              pr={selectedPr}
              detail={detail}
              diffs={diffs}
              contributorsWorkspaceId={repositoryScope?.workspaceId}
              onRefresh={refresh}
            />
          )}
        </section>
      )}
    </div>
  );
}

export function remotePullRequest(detail: PullRequestDetailDto, viewerLogin?: string): PullRequestDto {
  const key = `${detail.repo.toLowerCase()}#${detail.number}`;
  let hash = 0;
  for (const character of key) hash = ((hash * 31) + character.charCodeAt(0)) | 0;
  return {
    id: -(Math.abs(hash) || 1),
    repo: detail.repo,
    number: detail.number,
    title: detail.title ?? `${detail.repo} #${detail.number}`,
    author: detail.author,
    htmlUrl: detail.htmlUrl ?? `https://github.com/${detail.repo}/pull/${detail.number}`,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt ?? detail.createdAt ?? new Date(0).toISOString(),
    origin: viewerLogin && detail.author?.toLowerCase() !== viewerLogin.toLowerCase()
      ? 'REVIEW_REQUESTED'
      : 'AUTHORED',
    labels: detail.labels.map(label => label.name),
    labelColors: Object.fromEntries(detail.labels.map(label => [label.name, label.color ?? 'd0d7de'])),
    draft: detail.draft,
    requestedReviewers: detail.reviewers.map(reviewer => reviewer.login),
    ciStatus: detail.ciStatus,
    additions: detail.additions,
    deletions: detail.deletions,
    commentCount: detail.recentActivity.filter(item =>
      item.eventType === 'commented' || item.eventType === 'reviewed').length,
    attentionReason: null,
    state: detail.state,
    mergeable: detail.mergeable,
    mergeableState: detail.mergeableState,
    headRef: detail.headRef,
  };
}

function PullDetailHeader({ pr, detail, tab, onTab, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto | null;
  tab: DetailTab;
  onTab: (tab: DetailTab) => void;
  onRefresh: () => Promise<void>;
}) {
  const [mergeQueue, setMergeQueue] = useState<boolean | null | undefined>(undefined);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const canReview = canSubmitReview(pr, detail);
  const canMerge = canReview
    && detail !== null
    && detail.state?.toLowerCase() === 'open'
    && !detail.draft
    && detail.viewerCanWrite
    && !isQueued(detail.recentActivity);
  const conflicts = hasMergeConflicts(detail);
  const merge = detail ? mergeStatus(detail) : null;
  const mergeModeReady = typeof mergeQueue === 'boolean';
  const mergeBlocked = merge?.state === 'failed' || merge?.state === 'blocked';
  const mergeLoading = mergeQueue === undefined || mergeQueue === null || merge?.state === 'pending';
  const mergeLabel = conflicts
    ? 'Conflicts'
    : mergeQueue === undefined || mergeQueue === null
      ? mergeActionLabel(mergeQueue)
      : merge?.state === 'failed'
        ? 'Checks failed'
        : merge?.state === 'blocked'
          ? 'Merge blocked'
          : merge?.state === 'pending'
            ? 'Checks running'
            : mergeActionLabel(mergeQueue);

  useEffect(() => {
    setMergeOpen(false);
    setReviewOpen(false);
    setMergeQueue(undefined);
    if (!canMerge || conflicts || !detail?.baseRef) return;
    let cancelled = false;
    void window.bridge.fetchMergeMode(detail.repo, detail.baseRef)
      .then(mode => { if (!cancelled) setMergeQueue(mode.mergeQueue); })
      .catch(() => { if (!cancelled) setMergeQueue(null); });
    return () => { cancelled = true; };
  }, [canMerge, conflicts, detail?.repo, detail?.number, detail?.baseRef]);

  return (
    <header className="pull-detail__header">
      <div className="pull-detail__title-row">
        <h1>{pr.title}</h1>
        <div className="pull-detail__header-actions">
          <button type="button" title="Refresh" aria-label="Refresh" onClick={() => { void onRefresh(); }}>
            <RefreshIcon />
          </button>
          <button className="pull-detail__open-github" type="button" onClick={() => { void window.bridge.openInAppBrowser(pr.htmlUrl); }}>
            Open on GitHub <span aria-hidden="true">↗</span>
          </button>
          {canReview && (
            <button className="pull-detail__review-action" type="button" onClick={() => setReviewOpen(true)}>
              Submit review
            </button>
          )}
          {canMerge && (
            <button
              className={`pull-detail__merge-action${conflicts || mergeBlocked ? ' is-conflicts' : mergeLoading ? ' is-loading' : ''}`}
              type="button"
              disabled={!mergeModeReady || conflicts}
              onClick={() => setMergeOpen(true)}>
              {conflicts || mergeBlocked
                ? <ConflictIcon />
                : mergeLoading
                  ? <MergeReadyIcon />
                  : <PullRequestIcon />} {mergeLabel}
            </button>
          )}
        </div>
      </div>
      <nav className="pull-detail__tabs" aria-label="Pull request detail">
        <button className={tab === 'overview' ? 'is-active' : ''} type="button" onClick={() => onTab('overview')}>
          <CommentIcon /> Overview
          <span>{pr.commentCount}</span>
        </button>
        <button className={tab === 'changes' ? 'is-active' : ''} type="button" onClick={() => onTab('changes')}>
          <ChangesIcon /> Changes
          <span className="is-addition">+{detail?.additions ?? pr.additions}</span>
          <span className="is-deletion">−{detail?.deletions ?? pr.deletions}</span>
        </button>
      </nav>
      {reviewOpen && detail && (
        <ReviewDialog pr={pr} detail={detail}
          onClose={() => setReviewOpen(false)} onRefresh={onRefresh} />
      )}
      {mergeOpen && detail && typeof mergeQueue === 'boolean' && (
        <MergeDialog pr={pr} detail={detail} mergeQueue={mergeQueue}
          onClose={() => setMergeOpen(false)} onRefresh={onRefresh} />
      )}
    </header>
  );
}

export function canSubmitReview(pr: PullRequestDto, detail: PullRequestDetailDto | null) {
  return pr.origin === 'REVIEW_REQUESTED' && detail?.state?.toLowerCase() === 'open';
}

export function hasMergeConflicts(detail: PullRequestDetailDto | null) {
  return ['dirty', 'conflicting', 'conflicts'].includes(detail?.mergeableState?.toLowerCase() ?? '');
}

export function mergeActionLabel(mergeQueue: boolean | null | undefined, conflicts = false) {
  if (conflicts) return 'Conflicts';
  if (mergeQueue === undefined) return 'Loading merge status';
  if (mergeQueue === null) return 'Merge status unavailable';
  return mergeQueue ? 'Merge when ready' : 'Rebase and merge';
}

export function unresolvedReviewCount(detail: PullRequestDetailDto) {
  return detail.recentActivity.filter(activity =>
    activity.eventType === 'review_thread' && !activity.payload.resolved).length;
}

export function mergeStatus(detail: PullRequestDetailDto) {
  const counts = detail.checkRuns.reduce((result, check) => {
    result[check.category] = (result[check.category] ?? 0) + 1;
    return result;
  }, {} as Record<string, number>);
  const pending = counts.pending ?? 0;
  const failing = counts.failing ?? 0;
  const successful = counts.successful ?? 0;
  const unresolved = unresolvedReviewCount(detail);
  const state: 'failed' | 'pending' | 'blocked' | 'ready' = failing > 0
    ? 'failed'
    : pending > 0
      ? 'pending'
      : unresolved > 0
        ? 'blocked'
        : 'ready';
  return { pending, failing, successful, unresolved, checksReported: detail.checkRuns.length > 0, state };
}

type ReviewEvent = 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';

export function ReviewDialog({ pr, detail, onClose, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onClose: () => void;
  onRefresh: () => Promise<void>;
}) {
  const [body, setBody] = useState('');
  const [event, setEvent] = useState<ReviewEvent>('APPROVE');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bodyRequired = event !== 'APPROVE';

  useEffect(() => {
    const close = (keyboardEvent: KeyboardEvent) => {
      if (keyboardEvent.key === 'Escape' && !submitting) onClose();
    };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [onClose, submitting]);

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await window.bridge.submitReview(pr.id, detail.repo, detail.number, event, body.trim());
      await onRefresh();
      onClose();
    }
    catch (value) {
      setError(value instanceof Error ? value.message : String(value));
      setSubmitting(false);
    }
  };

  return (
    <div className="pr-sheet-overlay" onMouseDown={mouseEvent => {
      if (mouseEvent.target === mouseEvent.currentTarget && !submitting) onClose();
    }}>
    <section className="pr-review-sheet" role="dialog" aria-modal="true" aria-labelledby="pr-review-title">
      <div className="pr-review-sheet__header">
        <h2 id="pr-review-title">Submit review</h2>
        <button type="button" aria-label="Close review dialog" disabled={submitting} onClick={onClose}>×</button>
      </div>
      <div className="pr-review-sheet__content">
        <textarea
          value={body}
          placeholder="Leave a comment on this pull request…"
          aria-label="Review comment"
          onChange={change => setBody(change.target.value)} />
        <ReviewChoice
          checked={event === 'COMMENT'}
          title="Comment"
          description="Submit general feedback without explicit approval."
          onChange={() => setEvent('COMMENT')} />
        <ReviewChoice
          checked={event === 'APPROVE'}
          title="Approve"
          description="Submit feedback and approve merging these changes."
          onChange={() => setEvent('APPROVE')} />
        <ReviewChoice
          checked={event === 'REQUEST_CHANGES'}
          title="Request Changes"
          description="Submit feedback that must be addressed before merging."
          onChange={() => setEvent('REQUEST_CHANGES')} />
        {error && <div className="pr-review-sheet__error" role="alert">{error}</div>}
      </div>
      <footer className="pr-review-sheet__footer">
        <button type="button" disabled={submitting} onClick={onClose}>Cancel</button>
        <button className="is-primary" type="button"
          disabled={submitting || (bodyRequired && !body.trim())}
          onClick={() => { void submit(); }}>
          {submitting ? 'Submitting…' : 'Submit review'}
        </button>
      </footer>
    </section>
    </div>
  );
}

function ReviewChoice({ checked, title, description, onChange }: {
  checked: boolean;
  title: string;
  description: string;
  onChange: () => void;
}) {
  return (
    <label className="pr-review-choice">
      <input type="radio" name="review-event" checked={checked} onChange={onChange} />
      <span><strong>{title}</strong><small>{description}</small></span>
    </label>
  );
}

export function MergeDialog({ pr, detail, mergeQueue, onClose, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  mergeQueue: boolean;
  onClose: () => void;
  onRefresh: () => Promise<void>;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { pending, failing, successful, unresolved, checksReported, state } = mergeStatus(detail);
  const blocked = state === 'failed' || state === 'blocked';

  useEffect(() => {
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape' && !submitting) onClose(); };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [onClose, submitting]);

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await window.bridge.mergePr(pr.id, detail.repo, detail.number, mergeQueue ? 'queue' : 'rebase');
      await onRefresh();
      onClose();
    }
    catch (value) {
      setError(value instanceof Error ? value.message : String(value));
      setSubmitting(false);
    }
  };

  return (
    <div className="pr-sheet-overlay" onMouseDown={mouseEvent => {
      if (mouseEvent.target === mouseEvent.currentTarget && !submitting) onClose();
    }}>
    <section className="pr-merge-sheet" role="dialog" aria-modal="true" aria-labelledby="pr-merge-title">
      <div className="pr-merge-sheet__header">
        <h2 id="pr-merge-title">Merge pull request</h2>
        <button type="button" aria-label="Close merge dialog" disabled={submitting} onClick={onClose}>×</button>
      </div>
      <div className="pr-merge-sheet__content">
        <div className={`pr-merge-status-card${blocked ? ' is-danger' : ''}`}>
          {blocked
            ? <ConflictIcon />
            : state === 'pending'
              ? <MergeReadyIcon />
              : <span className={`pr-merge-dot${mergeQueue ? ' is-queue' : ''}`} />}
          <strong>{blocked
            ? 'Merge blocked'
            : state === 'pending'
              ? 'Waiting for checks'
              : mergeQueue ? 'Merge queue is enabled' : 'Ready to rebase and merge'}</strong>
        </div>
        <div className="pr-merge-status-card is-stacked">
          <div>
            {failing > 0
                ? <ConflictIcon />
              : pending > 0
                ? <MergeReadyIcon />
                : checksReported
                  ? <MergeSuccessIcon />
                  : <span className="pr-merge-dot" aria-hidden="true" />}
            <strong>{failing > 0
                ? 'Some checks were not successful'
              : pending > 0
                ? 'Checks are running'
                : checksReported
                  ? 'All checks have passed'
                  : 'No checks reported'}</strong>
          </div>
          <div className="pr-merge-status-card__groups">
            {pending > 0 && <span>In progress ({pending})</span>}
            {failing > 0 && <span>Failing ({failing})</span>}
            {successful > 0 && <span>Successful ({successful})</span>}
            {detail.checkRuns.length === 0 && <span>No checks reported</span>}
          </div>
        </div>
        {unresolved > 0 && (
          <div className="pr-merge-status-card is-danger">
            <span aria-hidden="true">×</span>
            <strong>{unresolved} unresolved review comment{unresolved === 1 ? '' : 's'}</strong>
          </div>
        )}
        {error && <div className="pr-merge-sheet__error" role="alert">{error}</div>}
      </div>
      <footer className="pr-merge-sheet__footer">
        <button type="button" disabled={submitting} onClick={onClose}>Cancel</button>
        <button className="is-primary" type="button" disabled={submitting || state !== 'ready'} onClick={() => { void submit(); }}>
          <PullRequestIcon /> {submitting ? 'Submitting…' : mergeQueue ? 'Queue when ready' : 'Rebase and merge'}
        </button>
      </footer>
    </section>
    </div>
  );
}

function MergeReadyIcon() {
  return <span className="pr-merge-ready-icon" aria-hidden="true"><i /></span>;
}

function MergeSuccessIcon() {
  return <span className="pr-merge-success-icon" aria-hidden="true">
    <svg viewBox="0 0 16 16" width="11" height="11" fill="none" stroke="currentColor"
      strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.4">
      <path d="m3.5 8.2 2.8 2.8 6.2-6" />
    </svg>
  </span>;
}

function ConflictIcon() {
  return <span className="pr-merge-conflict-icon" aria-hidden="true">×</span>;
}

function DetailBody({
  tab,
  pr,
  detail,
  diffs,
  contributorsWorkspaceId,
  onRefresh,
}: {
  tab: DetailTab;
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  diffs: DiffFileDto[] | null;
  contributorsWorkspaceId?: string;
  onRefresh: () => Promise<void>;
}) {
  if (tab === 'changes') {
    if (diffs === null) return <div className="hp-loading">Loading files…</div>;
    return <div className="pull-detail__body pull-detail__changes"><PullChanges files={diffs} /></div>;
  }
  return <div className="pull-detail__body"><PullOverview
    pr={pr}
    detail={detail}
    contributorsWorkspaceId={contributorsWorkspaceId}
    onRefresh={onRefresh}
  /></div>;
}

function RefreshIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 7v5h-5M4 17v-5h5" /><path d="M7.1 7.1a7 7 0 0 1 11.4 2.3M16.9 16.9a7 7 0 0 1-11.4-2.3" />
    </svg>
  );
}

function PaneIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M15 4v16" />
    </svg>
  );
}

function CommentIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.6 8.6 0 0 1-3.3-.7L3 21l1.8-5.7a8.4 8.4 0 1 1 16.2-3.8z" />
    </svg>
  );
}

function ChangesIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M8 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3M16 3h3a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-3M12 8v8M8 12h8" />
    </svg>
  );
}
