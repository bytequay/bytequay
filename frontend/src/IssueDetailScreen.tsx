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
import type { IssueCommentDto, IssueDetailDto, ReactionsDto } from './types';
import Avatar from './Avatar';
import LogoLoading from './LogoLoading';
import PolishButtons from './ai/PolishButtons';
import { renderMarkdown } from './markdown';
import { formatRelative } from './prBuckets';
import { ReactionChips } from './pr/Reactions';
import { REACTION_FIELD, type ReactionContent } from './pr/utils';

type Props = {
  owner: string;
  repo: string;
  number: number;
  /** Required in standalone mode (own page). Optional/ignored when
   *  embedded inside another shell that owns the back affordance. */
  onBack?: () => void;
  /** When mounted inside RepoDetailPage's right pane the surrounding
   *  v2-page chrome already provides nav, so the screen drops its
   *  own breadcrumb and adapts its outer layout to fill the pane. */
  embedded?: boolean;
};

/**
 * In-app issue detail page — read-only first cut. Mirrors the layout
 * from docs/mockups/design/repository/repository-issue-detail.png:
 * title + status + meta header on top, conversation timeline on the
 * left, right rail with Assignees / Labels / Milestone. Skipped from
 * v1: Activity / Linked tabs, reactions, reply composer, close /
 * reopen, subscribe — tracked as I3b/I4.
 */
function IssueDetailScreen({ owner, repo, number, onBack, embedded }: Props) {
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

  // Optimistic reaction toggle: bump the in-state count immediately so
  // the chip animates in without waiting for the round-trip; if the
  // backend call fails, the catch path rolls the count back so the UI
  // never shows phantom reactions. Mirrors PR/optimisticUpdates.
  const addReaction = useCallback(async (commentId: number, content: ReactionContent) => {
    setDetail(prev => prev ? bumpCommentReaction(prev, commentId, content, +1) : prev);
    try {
      await window.bridge.addIssueDetailCommentReaction(owner, repo, commentId, content);
    } catch (e) {
      // Roll the optimistic +1 back. We swallow the rejection (only
      // logging) to match the PR-side reaction path — the chip
      // returning to its previous count is the user-visible feedback.
      setDetail(prev => prev ? bumpCommentReaction(prev, commentId, content, -1) : prev);
      console.warn('addIssueDetailCommentReaction failed', e);
    }
  }, [owner, repo]);

  const wrapperClass = `issue-detail${embedded ? ' issue-detail--embedded' : ''}`;
  const breadcrumb = !embedded && onBack
    ? <IssueDetailBreadcrumb owner={owner} repo={repo} number={detail?.number ?? number} onBack={onBack} />
    : null;

  if (error) {
    return (
      <div className={wrapperClass}>
        {breadcrumb}
        <div className="issue-detail__error">Couldn't load issue: {error}</div>
      </div>
    );
  }
  if (!detail) {
    return (
      <div className={wrapperClass}>
        {breadcrumb}
        <div className="issue-detail__loading">
          <LogoLoading size={48} label={`Loading #${number}`} />
        </div>
      </div>
    );
  }

  const isClosed = detail.state === 'closed';

  return (
    <div className={wrapperClass}>
      {breadcrumb}

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
          <StateToggleButton
            owner={owner}
            repo={repo}
            number={detail.number}
            currentState={detail.state}
            onChanged={(updated) => setDetail(updated)}
          />
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
              commentId={c.id}
              author={c.author}
              avatarUrl={c.authorAvatarUrl}
              createdAt={c.createdAt}
              body={c.body}
              reactions={c.reactions}
              onAddReaction={addReaction}
            />
          ))}
          <ReplyComposer
            owner={owner}
            repo={repo}
            number={detail.number}
            onPosted={(newComment) =>
              setDetail((prev) => prev
                ? { ...prev, comments: [...prev.comments, newComment] }
                : prev)}
          />
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
  commentId,
  author,
  avatarUrl: _avatarUrl,
  createdAt,
  body,
  isAuthor,
  reactions,
  onAddReaction,
}: {
  /** GitHub comment id; absent on the issue body card (the issue's own
   *  description has no comment row, just the issue itself). */
  commentId?: number;
  author: string | null;
  avatarUrl: string | null;
  createdAt: string;
  body: string | null;
  isAuthor?: boolean;
  reactions?: ReactionsDto;
  /** Optional toggle handler. Wired only for real comments — the issue
   *  body card omits it because issue-body reactions go through a
   *  different GitHub endpoint we don't surface yet. */
  onAddReaction?: (commentId: number, content: ReactionContent) => Promise<void>;
}) {
  const safeBody = body && body.trim().length > 0 ? body : '_No description provided._';
  const handlePick = onAddReaction && commentId != null
    ? (content: ReactionContent) => { void onAddReaction(commentId, content); }
    : undefined;
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
      {reactions && <ReactionChips reactions={reactions} onAddReaction={handlePick} />}
    </article>
  );
}

/** Returns a copy of {@code detail} with the named reaction tally on
 *  one comment shifted by {@code delta}. Used by the optimistic
 *  reaction-toggle path — the +1 happens immediately so the chip
 *  animates in, the -1 fires only on backend failure as a rollback. */
function bumpCommentReaction(
  detail: IssueDetailDto,
  commentId: number,
  content: ReactionContent,
  delta: number,
): IssueDetailDto {
  const field = REACTION_FIELD[content];
  const comments = detail.comments.map(c => {
    if (c.id !== commentId) return c;
    const next: ReactionsDto = { ...c.reactions, [field]: Math.max(0, c.reactions[field] + delta) };
    return { ...c, reactions: next };
  });
  return { ...detail, comments };
}

/** Header-row Close / Reopen button. Toggles between the two states
 *  via PATCH and replaces the local detail with the refreshed
 *  payload so the status pill, closed timestamp, and Notes panel all
 *  flip in one render. The list-side state in RepoDetailPage stays
 *  stale for now — closing an issue here doesn't move it from the
 *  Open bucket to Closed in the sidebar until the lists refetch.
 *  Tracked as a follow-up in the I4b commit message. */
function StateToggleButton({
  owner,
  repo,
  number,
  currentState,
  onChanged,
}: {
  owner: string;
  repo: string;
  number: number;
  currentState: string;
  onChanged: (updated: IssueDetailDto) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isClosed = currentState === 'closed';
  const next: 'open' | 'closed' = isClosed ? 'open' : 'closed';
  const label = busy
    ? (isClosed ? 'Reopening…' : 'Closing…')
    : (isClosed ? 'Reopen issue' : 'Close issue');

  const submit = async (): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await window.bridge.setIssueState(owner, repo, number, next);
      onChanged(updated);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <span className="issue-detail__state-action">
      <button
        type="button"
        className={`button button--sm ${isClosed ? 'button--secondary' : 'button--primary'}`}
        onClick={() => { void submit(); }}
        disabled={busy}
      >
        {label}
      </button>
      {error && <span className="issue-detail__state-error" title={error}>Failed: {error.slice(0, 60)}…</span>}
    </span>
  );
}

/** Bottom-of-conversation reply box. Optimistically appends the new
 *  comment via {@code onPosted} on success and clears the textarea;
 *  surfaces network/GitHub errors inline (rate-limit, validation,
 *  permissions) without dropping what the user typed. */
function ReplyComposer({
  owner,
  repo,
  number,
  onPosted,
}: {
  owner: string;
  repo: string;
  number: number;
  onPosted: (comment: IssueCommentDto) => void;
}) {
  const [body, setBody] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (): Promise<void> => {
    if (submitting) return;
    if (body.trim().length === 0) return;
    setSubmitting(true);
    setError(null);
    try {
      const comment = await window.bridge.createIssueComment(owner, repo, number, body);
      onPosted(comment);
      setBody('');
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSubmitting(false);
    }
  };

  const canSubmit = !submitting && body.trim().length > 0;

  return (
    <div className="issue-detail__composer">
      <div className="issue-detail__composer-head">Reply</div>
      <textarea
        className="issue-detail__composer-input"
        value={body}
        onChange={(e) => setBody(e.target.value)}
        placeholder="Leave a comment…"
        rows={4}
        disabled={submitting}
        onKeyDown={(e) => {
          // Cmd/Ctrl + Enter posts — matches GitHub's keyboard shortcut
          // for issue comments.
          if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
            e.preventDefault();
            void submit();
          }
        }}
      />
      {error && <div className="issue-detail__composer-error">{error}</div>}
      <div className="issue-detail__composer-actions">
        <span className="issue-detail__composer-polish">
          <PolishButtons
            value={body}
            onChange={setBody}
            onError={setError}
            disabled={submitting}
          />
        </span>
        <span className="issue-detail__composer-hint">⌘/Ctrl ↵ to send</span>
        <button
          type="button"
          className="button button--primary button--sm"
          onClick={() => { void submit(); }}
          disabled={!canSubmit}
        >
          {submitting ? 'Posting…' : 'Comment'}
        </button>
      </div>
    </div>
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
