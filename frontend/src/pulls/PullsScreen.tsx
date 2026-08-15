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
import { useEffect, useMemo, useState } from 'react';
import type { DiffFileDto, PullRequestDetailDto, PullRequestDto } from '../types';
import PullRowItem from './PullRowItem';
import { PULL_TABS, rowsForTab, type PullRow, type PullTab } from './model';
import '../css/pulls.css';

type DetailTab = 'overview' | 'files' | 'activity';

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
    if (detailTab !== 'files' || !selectedPr || diffs !== null) return;
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

  return (
    <div className="pulls-clean">
      <section className="pulls-clean__list">
        <header className="pulls-clean__header">
          <div>
            <h1>Pull requests</h1>
            <p>GitHub pull requests that you authored or were asked to review.</p>
          </div>
          <button type="button" onClick={() => { setLoading(true); void window.bridge.syncPrs().then(loadList); }}>
            Sync
          </button>
        </header>
        <nav className="pulls-clean__tabs" aria-label="Pull request filters">
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
        <div className="pulls-clean__rows">
          {loading && <div className="hp-loading">Loading…</div>}
          {!loading && rows.length === 0 && <div className="hp-empty">No pull requests in this view.</div>}
          {rows.map(row => (
            <PullRowItem
              key={row.id}
              row={row}
              selected={row.id === selected}
              onPick={() => pick(row)}
            />
          ))}
        </div>
      </section>

      <section className="pulls-clean__detail">
        {selectedPr === null ? (
          <div className="hp-empty">Select a pull request.</div>
        ) : (
          <>
            <header className="pulls-clean__detail-head">
              <div>
                <span className="pulls-clean__repo">{selectedPr.repo} · #{selectedPr.number}</span>
                <h2>{selectedPr.title}</h2>
                <p>{selectedPr.author ?? 'Unknown author'} · {selectedPr.headRef ?? 'branch unavailable'}</p>
              </div>
              <div className="pulls-clean__actions">
                <button type="button" onClick={() => { void refresh(); }}>Refresh</button>
                <button type="button" onClick={() => { void window.bridge.openInAppBrowser(selectedPr.htmlUrl); }}>
                  Open on GitHub
                </button>
              </div>
            </header>
            <nav className="pulls-clean__tabs" aria-label="Pull request detail">
              {(['overview', 'files', 'activity'] as DetailTab[]).map(value => (
                <button
                  type="button"
                  key={value}
                  className={detailTab === value ? 'is-active' : ''}
                  onClick={() => setDetailTab(value)}
                >
                  {value[0].toUpperCase() + value.slice(1)}
                </button>
              ))}
            </nav>
            {error && <div className="pulls-clean__error">{error}</div>}
            {detailLoading || detail === null ? (
              <div className="hp-loading">Loading details…</div>
            ) : (
              <DetailBody
                tab={detailTab}
                pr={selectedPr}
                detail={detail}
                diffs={diffs}
                onRefresh={refresh}
              />
            )}
          </>
        )}
      </section>
    </div>
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

  if (tab === 'files') {
    if (diffs === null) return <div className="hp-loading">Loading files…</div>;
    return (
      <div className="pulls-clean__content">
        {diffs.map(file => (
          <article className="pulls-clean__card" key={file.filename}>
            <header><strong>{file.filename}</strong><span>+{file.additions} −{file.deletions}</span></header>
            <pre className="pulls-clean__diff">{file.patch ?? 'Binary file or diff unavailable.'}</pre>
          </article>
        ))}
      </div>
    );
  }

  if (tab === 'activity') {
    return (
      <div className="pulls-clean__content">
        {detail.recentActivity.map((item, index) => (
          <article className="pulls-clean__card" key={item.githubId ?? `${item.eventType}-${index}`}>
            <header>
              <strong>{item.actor || 'github'}</strong>
              <span>{item.eventType.replaceAll('_', ' ')}</span>
            </header>
            {item.body && <p className="pulls-clean__markdown">{item.body}</p>}
            {item.timestamp && <small>{new Date(item.timestamp).toLocaleString()}</small>}
          </article>
        ))}
        {detail.recentActivity.length === 0 && <div className="hp-empty">No activity cached yet.</div>}
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
    <div className="pulls-clean__content">
      <div className="pulls-clean__stats">
        <span className={detail.ciStatus === 'PASSING' ? 'is-good' : detail.ciStatus === 'FAILING' ? 'is-bad' : ''}>
          CI {detail.ciStatus.toLowerCase()}
        </span>
        <span>+{detail.additions}</span>
        <span>−{detail.deletions}</span>
        <span>{detail.changedFiles} files</span>
        <span>{detail.approvalCount} approvals</span>
      </div>
      <article className="pulls-clean__card">
        <header><strong>Description</strong></header>
        <p className="pulls-clean__markdown">{detail.body || 'No description provided.'}</p>
      </article>
      <article className="pulls-clean__card">
        <header><strong>Checks</strong><span>{detail.checkRuns.length}</span></header>
        {detail.checkRuns.map((check, index) => (
          <div className="pulls-clean__check" key={check.githubId ?? index}>
            <span>{check.conclusion === 'success' ? '✓' : check.conclusion === 'failure' ? '✕' : '•'}</span>
            <span>{check.name ?? 'Check'}</span>
            <small>{check.conclusion ?? check.status ?? 'unknown'}</small>
          </div>
        ))}
        {detail.checkRuns.length === 0 && <div className="hp-empty">No checks reported.</div>}
      </article>
      <article className="pulls-clean__card">
        <header><strong>Comment</strong></header>
        <textarea
          value={comment}
          onChange={event => setComment(event.target.value)}
          placeholder="Leave a comment on GitHub"
          rows={5}
        />
        <div className="pulls-clean__actions">
          <button type="button" disabled={posting || !comment.trim()} onClick={() => { void submitComment(); }}>
            {posting ? 'Posting…' : 'Comment'}
          </button>
          <button
            type="button"
            disabled={!detail.viewerCanWrite}
            onClick={() => { void window.bridge.approvePr(pr.id, pr.repo, pr.number).then(onRefresh); }}
          >
            Approve
          </button>
          <button
            type="button"
            disabled={!detail.viewerCanWrite || detail.ciStatus === 'FAILING'}
            onClick={() => { void window.bridge.mergePr(pr.id, pr.repo, pr.number, 'rebase').then(onRefresh); }}
          >
            Merge
          </button>
        </div>
      </article>
    </div>
  );
}
