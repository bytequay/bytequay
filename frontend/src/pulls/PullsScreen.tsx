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
import { useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react';
import Avatar from '../Avatar';
import type { DiffFileDto, PullRequestDetailDto, PullRequestDto } from '../types';
import PullRowItem, { PullRequestIcon } from './PullRowItem';
import { PULL_TABS, rowsForTab, type PullRow, type PullTab } from './model';
import '../css/pulls.css';

type DetailTab = 'overview' | 'changes';

const DETAIL_MIN = 460;
const DETAIL_MAX = 980;
const DETAIL_DEFAULT = 720;

export default function PullsScreen({
  initialPr,
}: {
  initialPr?: { repo: string; number: number };
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

  const loadList = async () => {
    try {
      const rows = await window.bridge.fetchPrs();
      setPrs(rows);
      setSelected(current => current ?? rows[0]?.id ?? null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadList();
    void window.bridge.syncPrs().then(loadList).catch(() => {});
  }, []);

  useEffect(() => {
    if (!initialPr || prs.length === 0) return;
    const match = prs.find(pr => pr.repo.toLowerCase() === initialPr.repo.toLowerCase()
      && pr.number === initialPr.number);
    if (match) setSelected(match.id);
  }, [initialPr, prs]);

  const rows = useMemo(() => rowsForTab(prs, tab), [prs, tab]);
  const selectedPr = prs.find(pr => pr.id === selected) ?? null;

  useEffect(() => {
    if (!selectedPr) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    setError(null);
    window.bridge.fetchPullRequestDetail(selectedPr.repo, selectedPr.number)
      .then(value => {
        if (!cancelled) setDetail(value);
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedPr]);

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
      setDetail(await window.bridge.refreshPullRequestDetail(selectedPr.repo, selectedPr.number));
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
    const startWidth = detailWidth;
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
            onClick={() => { setLoading(true); void window.bridge.syncPrs().then(loadList).catch(e => {
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
          {!loading && rows.length === 0 && <div className="hp-empty">No pull requests in this view.</div>}
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
          <div className="pull-detail__resize" onMouseDown={resizeDetail} title="Drag to resize" />
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
              onRefresh={refresh}
            />
          )}
        </section>
      )}
    </div>
  );
}

function PullDetailHeader({ pr, detail, tab, onTab, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto | null;
  tab: DetailTab;
  onTab: (tab: DetailTab) => void;
  onRefresh: () => Promise<void>;
}) {
  const state = pr.state === 'merged' ? 'Merged' : pr.state === 'closed' ? 'Closed' : pr.draft ? 'Draft' : 'Open';
  return (
    <header className="pull-detail__header">
      <div className="pull-detail__title-row">
        <h1>{pr.title} <span>#{pr.number}</span></h1>
        <div className="pull-detail__header-actions">
          <button type="button" title="Refresh" aria-label="Refresh" onClick={() => { void onRefresh(); }}>
            <RefreshIcon />
          </button>
          <button type="button" onClick={() => { void window.bridge.openInAppBrowser(pr.htmlUrl); }}>
            Open on GitHub <span aria-hidden="true">↗</span>
          </button>
        </div>
      </div>
      <div className="pull-detail__branches">
        <span className={`pull-detail__state is-${state.toLowerCase()}`}>
          <PullRequestIcon merged={state === 'Merged'} /> {state}
        </span>
        <span className="pull-detail__branch is-dummy">base · dummy</span>
        <span aria-hidden="true">←</span>
        <span className="pull-detail__branch">{pr.headRef ?? 'branch unavailable'}</span>
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
    </header>
  );
}

function DetailBody({
  tab,
  pr,
  detail,
  diffs,
  onRefresh,
}: {
  tab: DetailTab;
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  diffs: DiffFileDto[] | null;
  onRefresh: () => Promise<void>;
}) {
  const [comment, setComment] = useState('');
  const [posting, setPosting] = useState(false);

  if (tab === 'changes') {
    if (diffs === null) return <div className="hp-loading">Loading files…</div>;
    return (
      <div className="pull-detail__body pull-detail__changes">
        {diffs.map(file => (
          <article className="pull-card pull-diff" key={file.filename}>
            <header><strong>{file.filename}</strong><span>+{file.additions} −{file.deletions}</span></header>
            <pre>{file.patch ?? 'Binary file or diff unavailable.'}</pre>
          </article>
        ))}
        {diffs.length === 0 && <div className="hp-empty">No changed files reported.</div>}
      </div>
    );
  }

  const submitComment = async () => {
    if (!comment.trim()) return;
    setPosting(true);
    try {
      await window.bridge.commentPr(pr.repo, pr.number, comment.trim());
      setComment('');
      await onRefresh();
    }
    finally {
      setPosting(false);
    }
  };

  return (
    <div className="pull-detail__body">
      <div className="pull-detail__metadata">
        <span className={detail.ciStatus === 'PASSING' ? 'is-good' : detail.ciStatus === 'FAILING' ? 'is-bad' : ''}>
          CI {detail.ciStatus.toLowerCase()}
        </span>
        <span>{detail.changedFiles} files</span>
        <span>{detail.approvalCount} approvals</span>
        <span>{pr.requestedReviewers.length > 0
          ? `Reviewers · ${pr.requestedReviewers.join(', ')}`
          : 'Reviewers · none'}</span>
        <span className="is-dummy">Assignee · dummy</span>
        {pr.labels.map(label => <span key={label}>{label}</span>)}
      </div>
      <article className="pull-card pull-description">
        <header>
          <span className="pull-card__actor">
            <Avatar login={pr.author ?? 'unknown'} size={24} />
            <span><strong>{pr.author ?? 'Unknown author'}</strong> opened this pull request</span>
          </span>
          <small>{new Date(pr.updatedAt).toLocaleString()}</small>
        </header>
        <p>{detail.body || 'No description provided.'}</p>
      </article>
      <article className={`pull-card pull-ci is-${detail.ciStatus.toLowerCase()}`}>
        <header>
          <span className="pull-ci__title">
            <span aria-hidden="true">{detail.ciStatus === 'PASSING' ? '✓' : detail.ciStatus === 'FAILING' ? '✕' : '●'}</span>
            <strong>Continuous integration</strong>
          </span>
          <span>{detail.checkRuns.length} checks</span>
        </header>
        <div className="pull-ci__summary">
          <strong>{detail.ciStatus === 'PASSING'
            ? 'All checks have passed'
            : detail.ciStatus === 'FAILING'
              ? 'Some checks were not successful'
              : detail.ciStatus === 'PENDING'
                ? 'Checks are still running'
                : 'No CI status reported'}</strong>
        </div>
        {detail.checkRuns.length > 0 && (
          <details className="pull-ci__checks">
            <summary>View check details</summary>
            {detail.checkRuns.map((check, index) => (
              <div className="pull-check" key={check.githubId ?? index}>
                <span>{check.conclusion === 'success' ? '✓' : check.conclusion === 'failure' ? '✕' : '•'}</span>
                <span>{check.name ?? 'Check'}</span>
                <small>{check.conclusion ?? check.status ?? 'unknown'}</small>
              </div>
            ))}
          </details>
        )}
        {detail.checkRuns.length === 0 && <div className="hp-empty">No checks reported.</div>}
      </article>
      <MergeabilityBox pr={pr} detail={detail} onRefresh={onRefresh} />
      <section className="pull-timeline" aria-label="Pull request activity">
        {detail.recentActivity.map((item, index) => (
          <article className="pull-timeline__item" key={item.githubId ?? `${item.eventType}-${index}`}>
            <Avatar login={item.actor || 'github'} size={24} />
            <div className="pull-card">
              <header>
                <strong>{item.actor || 'github'}</strong>
                <span>{item.eventType.replaceAll('_', ' ')}</span>
                {item.timestamp && <small>{new Date(item.timestamp).toLocaleString()}</small>}
              </header>
              {item.body && <p>{item.body}</p>}
            </div>
          </article>
        ))}
        {detail.recentActivity.length === 0 && <div className="hp-empty">No activity cached yet.</div>}
      </section>
      <article className="pull-card pull-composer">
        <header><strong>Leave a comment</strong></header>
        <textarea
          value={comment}
          onChange={event => setComment(event.target.value)}
          placeholder="Leave a comment on GitHub"
          rows={5}
        />
        <div className="pull-composer__actions">
          <button type="button" disabled={posting || !comment.trim()} onClick={() => { void submitComment(); }}>
            {posting ? 'Posting…' : 'Comment'}
          </button>
        </div>
      </article>
    </div>
  );
}

function MergeabilityBox({ pr, detail, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onRefresh: () => Promise<void>;
}) {
  const [running, setRunning] = useState<'approve' | 'merge' | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const terminal = pr.state === 'merged' || pr.state === 'closed';
  const mergeState = pr.mergeableState ?? 'dummy';
  const repositoryBlocked = ['blocked', 'dirty', 'behind', 'unstable']
    .includes(mergeState.toLowerCase());
  const requirementsBlocked = detail.ciStatus === 'FAILING' || repositoryBlocked;
  const mergeBlocked = pr.mergeable === false || requirementsBlocked;
  const canMerge = detail.viewerCanWrite && !terminal
    && pr.mergeable === true && !requirementsBlocked;
  const title = terminal
    ? `Pull request is ${pr.state}`
    : pr.mergeable === null
      ? 'Mergeability is unavailable'
      : mergeBlocked
        ? 'This pull request cannot be merged yet'
        : 'This pull request can be merged';
  const message = pr.mergeable === null
    ? 'Merge state is not supplied by the retained backend.'
    : detail.ciStatus === 'FAILING'
      ? 'Required checks must pass before merging.'
      : repositoryBlocked
        ? 'Repository merge requirements are still blocking this pull request.'
        : pr.mergeable
          ? 'The branch has no reported conflicts.'
          : 'Resolve conflicts before merging.';

  const run = async (action: 'approve' | 'merge') => {
    setRunning(action);
    setActionError(null);
    try {
      if (action === 'approve') await window.bridge.approvePr(pr.id, pr.repo, pr.number);
      else await window.bridge.mergePr(pr.id, pr.repo, pr.number, 'rebase');
      await onRefresh();
    }
    catch (error) {
      setActionError(error instanceof Error ? error.message : String(error));
    }
    finally {
      setRunning(null);
    }
  };

  return (
    <article className={`pull-card pull-merge${pr.mergeable === null ? ' is-dummy' : mergeBlocked ? ' is-blocked' : ''}`}>
      <div className="pull-merge__status" aria-hidden="true">
        {pr.mergeable === null ? '•' : mergeBlocked ? '!' : '✓'}
      </div>
      <div className="pull-merge__body">
        <strong>{title}</strong>
        <span>{message}</span>
        <small className={pr.mergeableState === null ? 'is-dummy' : ''}>
          merge state · {mergeState}
        </small>
        {actionError && <p role="alert">{actionError}</p>}
      </div>
      <div className="pull-merge__actions">
        <button
          type="button"
          disabled={!detail.viewerCanWrite || terminal || running !== null}
          onClick={() => { void run('approve'); }}
        >
          {running === 'approve' ? 'Approving…' : 'Approve'}
        </button>
        <button
          type="button"
          disabled={!canMerge || running !== null}
          onClick={() => { void run('merge'); }}
        >
          {running === 'merge' ? 'Merging…' : 'Merge with rebase'}
        </button>
      </div>
    </article>
  );
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
