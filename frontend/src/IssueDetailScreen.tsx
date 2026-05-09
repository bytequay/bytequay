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
import type { IssueDetailDto } from './types';
import Avatar from './Avatar';
import LogoLoading from './LogoLoading';
import { renderMarkdown } from './markdown';
import { formatRelative } from './prBuckets';

type Props = {
  owner: string;
  repo: string;
  number: number;
  onBack: () => void;
};

/**
 * In-app issue detail page — read-only first cut. Mirrors the layout
 * from docs/mockups/design/repository/repository-issue-detail.png:
 * title + status + meta header on top, conversation timeline on the
 * left, right rail with Assignees / Labels / Milestone. Skipped from
 * v1: Activity / Linked tabs, reactions, reply composer, close /
 * reopen, subscribe — tracked as I3b/I4.
 */
function IssueDetailScreen({ owner, repo, number, onBack }: Props) {
  const [detail, setDetail] = useState<IssueDetailDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setDetail(null);
    setError(null);
    window.bridge.getIssueDetail(owner, repo, number)
      .then(d => { if (!cancelled) setDetail(d); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo, number]);

  if (error) {
    return (
      <div className="issue-detail">
        <IssueDetailBreadcrumb owner={owner} repo={repo} number={number} onBack={onBack} />
        <div className="issue-detail__error">Couldn't load issue: {error}</div>
      </div>
    );
  }
  if (!detail) {
    return (
      <div className="issue-detail">
        <IssueDetailBreadcrumb owner={owner} repo={repo} number={number} onBack={onBack} />
        <div className="issue-detail__loading">
          <LogoLoading size={48} label={`Loading #${number}`} />
        </div>
      </div>
    );
  }

  const isClosed = detail.state === 'closed';

  return (
    <div className="issue-detail">
      <IssueDetailBreadcrumb owner={owner} repo={repo} number={detail.number} onBack={onBack} />

      <header className="issue-detail__header">
        <h1 className="issue-detail__title">
          {detail.title}
          <span className="issue-detail__num">#{detail.number}</span>
        </h1>
        <div className="issue-detail__meta">
          <span className={`issue-detail__pill issue-detail__pill--${isClosed ? 'closed' : 'open'}`}>
            <span className={`issue-row__status issue-row__status--${isClosed ? 'closed' : 'open'}`} aria-hidden="true" />
            {isClosed ? 'Closed' : 'Open'}
          </span>
          <span>
            opened by{' '}
            <strong>@{detail.author ?? 'unknown'}</strong>
            {' · '}
            {formatRelative(detail.createdAt)}
          </span>
          <span>·</span>
          <span>{detail.comments.length} comment{detail.comments.length === 1 ? '' : 's'}</span>
          <a
            className="issue-detail__github-link"
            href={detail.htmlUrl}
            target="_blank"
            rel="noreferrer"
          >
            View on GitHub ↗
          </a>
        </div>
      </header>

      <div className="issue-detail__body">
        <div className="issue-detail__main">
          {/* Top comment — the issue body itself rendered as a comment
              card, matching GitHub's convention (the author's opening
              post is the first row in the conversation). */}
          <CommentCard
            author={detail.author}
            avatarUrl={detail.authorAvatarUrl}
            createdAt={detail.createdAt}
            body={detail.body}
            isAuthor
          />
          {detail.comments.map(c => (
            <CommentCard
              key={c.id}
              author={c.author}
              avatarUrl={c.authorAvatarUrl}
              createdAt={c.createdAt}
              body={c.body}
            />
          ))}
        </div>

        <aside className="issue-detail__rail">
          <RailPanel title="Assignees">
            {detail.assignees.length === 0
              ? <div className="issue-detail__rail-empty">No one assigned</div>
              : (
                <ul className="issue-detail__rail-list">
                  {detail.assignees.map(a => (
                    <li key={a.login} className="issue-detail__rail-row">
                      <Avatar login={a.login} size={20} />
                      <span>@{a.login}</span>
                    </li>
                  ))}
                </ul>
              )}
          </RailPanel>
          <RailPanel title="Labels">
            {detail.labels.length === 0
              ? <div className="issue-detail__rail-empty">No labels</div>
              : (
                <div className="issue-detail__label-row">
                  {detail.labels.map(l => (
                    <span
                      key={l.name}
                      className="issue-detail__label"
                      style={labelStyle(l.color)}
                    >
                      {l.name}
                    </span>
                  ))}
                </div>
              )}
          </RailPanel>
          <RailPanel title="Milestone">
            {detail.milestone
              ? (
                <div className="issue-detail__rail-row">
                  <span className={`issue-detail__milestone-dot issue-detail__milestone-dot--${detail.milestone.state}`} aria-hidden="true" />
                  <span>{detail.milestone.title}</span>
                </div>
              )
              : <div className="issue-detail__rail-empty">No milestone</div>}
          </RailPanel>
          <RailPanel title="Notes">
            <dl className="issue-detail__notes">
              <dt>Opened</dt>
              <dd>{formatRelative(detail.createdAt)}</dd>
              <dt>Last activity</dt>
              <dd>{formatRelative(detail.updatedAt)}</dd>
              {detail.closedAt && <>
                <dt>Closed</dt>
                <dd>{formatRelative(detail.closedAt)}</dd>
              </>}
            </dl>
          </RailPanel>
        </aside>
      </div>
    </div>
  );
}

function IssueDetailBreadcrumb({ owner, repo, number, onBack }: { owner: string; repo: string; number: number; onBack: () => void }) {
  return (
    <nav className="issue-detail__breadcrumb">
      <button type="button" className="issue-detail__back" onClick={onBack}>
        ← Back
      </button>
      <span className="issue-detail__crumb-sep" aria-hidden="true">/</span>
      <span className="issue-detail__crumb">{owner}/{repo}</span>
      <span className="issue-detail__crumb-sep" aria-hidden="true">/</span>
      <span className="issue-detail__crumb">Issues</span>
      <span className="issue-detail__crumb-sep" aria-hidden="true">/</span>
      <span className="issue-detail__crumb-current">#{number}</span>
    </nav>
  );
}

function CommentCard({
  author,
  avatarUrl: _avatarUrl,
  createdAt,
  body,
  isAuthor,
}: {
  author: string | null;
  avatarUrl: string | null;
  createdAt: string;
  body: string | null;
  isAuthor?: boolean;
}) {
  const safeBody = body && body.trim().length > 0 ? body : '_No description provided._';
  return (
    <article className={`issue-detail__comment${isAuthor ? ' issue-detail__comment--author' : ''}`}>
      <header className="issue-detail__comment-head">
        <Avatar login={author ?? ''} size={28} className="issue-detail__comment-avatar" />
        <div className="issue-detail__comment-meta">
          <strong>@{author ?? 'unknown'}</strong>
          <span> · {formatRelative(createdAt)}</span>
          {isAuthor && <span className="issue-detail__author-pill">author</span>}
        </div>
      </header>
      <div
        className="issue-detail__comment-body"
        dangerouslySetInnerHTML={{ __html: renderMarkdown(safeBody) }}
      />
    </article>
  );
}

function RailPanel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="issue-detail__rail-panel">
      <h2 className="issue-detail__rail-title">{title}</h2>
      {children}
    </section>
  );
}

/** GitHub label colours come as a 6-digit hex (no `#`); we set the
 *  background and pick a readable text colour by luminance. */
function labelStyle(hex: string): React.CSSProperties {
  const cleaned = hex.replace(/^#/, '');
  const bg = `#${cleaned}`;
  const r = parseInt(cleaned.slice(0, 2), 16) || 0;
  const g = parseInt(cleaned.slice(2, 4), 16) || 0;
  const b = parseInt(cleaned.slice(4, 6), 16) || 0;
  // Perceived brightness from the YIQ formula — if it's bright the
  // label needs dark text, otherwise white reads better.
  const yiq = (r * 299 + g * 587 + b * 114) / 1000;
  return {
    background: bg,
    color: yiq >= 160 ? '#1f2328' : '#ffffff',
  };
}

export default IssueDetailScreen;
