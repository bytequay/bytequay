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
import { useState } from 'react';
import type { PullRequestDto } from '../types';
import Avatar from '../Avatar';
import { isPublishGateNotification, relativeTime } from '../notificationDisplay';
import { prRefFromNotification } from '../threads/notificationNav';
import PublishGatePane from '../PublishGatePane';
import type { InboxItem, InboxItemType } from './inboxItems';

export type InboxHandlers = {
  openPr: (owner: string, repo: string, prNumber: number) => void;
  openTask?: (threadId: string, taskId: string) => void;
  /** Dismiss the row — the section routes by source: backend dismiss
   *  for app notifications, mark-handled for PR rows (same concept as
   *  the kanban's Handled tab), local hide for provider rows. */
  dismiss: (item: InboxItem) => void;
  /** Approve the PR on GitHub. The section refreshes the list after. */
  approve: (pr: PullRequestDto) => Promise<void>;
  /** A parked approval was resolved via the embedded publish gate. */
  resolved: () => void;
  /** The row was engaged with — expanded (plain AWAITING_REVIEW) or a
   *  view action clicked (AUTO_FIX_DONE) — so the section marks
   *  informational notifications read (same rule as the thread strip). */
  opened?: (item: InboxItem) => void;
  /** Resolve a PR's title from the cached PR list, for the publish-gate
   *  header. Null when the PR isn't in cache. */
  prTitle?: (owner: string, repo: string, prNumber: number) => string | null;
};

/** Icon glyph per row flavour — the type-colored tile's content. */
const TYPE_ICON: Record<InboxItemType, string> = {
  approval: '👁',
  done:     '✓',
  review:   '⇄',
  mention:  '💬',
  blocked:  '✕',
  info:     'ℹ',
};

function splitRepo(full: string): { owner: string; repo: string } | null {
  const slash = full.indexOf('/');
  if (slash <= 0 || slash === full.length - 1) return null;
  return { owner: full.slice(0, slash), repo: full.slice(slash + 1) };
}

/** Who + where badges for the row: the acting user's avatar (PR rows
 *  only — agent notifications have no human actor) and the repo logo. */
function rowBadges(item: InboxItem): { author: string | null; repoOwner: string | null } {
  if (item.source.kind === 'pr') {
    const pr = item.source.pr;
    return { author: pr.author, repoOwner: splitRepo(pr.repo)?.owner ?? null };
  }
  if (item.source.kind === 'notification') {
    const ref = prRefFromNotification(item.source.notification);
    return { author: null, repoOwner: ref?.owner ?? null };
  }
  return { author: null, repoOwner: splitRepo(item.source.deploy.repoFullName)?.owner ?? null };
}

function checksLabel(pr: PullRequestDto): { text: string; ok: boolean } | null {
  switch (pr.ciStatus) {
    case 'PASSING': return { text: 'Checks passing', ok: true };
    case 'FAILING': return { text: 'Checks failing', ok: false };
    case 'PENDING': return { text: 'Checks pending', ok: false };
    default:        return null;
  }
}

/** One expandable inbox row: icon tile + title/sub + unread dot + time
 *  + chevron; the expanded body renders the source-specific detail. */
function InboxCard({ item, handlers }: { item: InboxItem; handlers: InboxHandlers }) {
  const [open, setOpen] = useState(false);
  const [approving, setApproving] = useState(false);
  const badges = rowBadges(item);
  // AUTO_FIX_DONE rows resolve via Dismiss or a view action, not the
  // expand itself — see the viewMarksRead note in body() below.
  const opensReadOnExpand = !(item.source.kind === 'notification' && item.source.notification.kind === 'AUTO_FIX_DONE');

  const openPrRow = (pr: PullRequestDto) => {
    const ref = splitRepo(pr.repo);
    if (ref) handlers.openPr(ref.owner, ref.repo, pr.number);
  };

  const body = () => {
    if (item.source.kind === 'pr') {
      const pr = item.source.pr;
      const checks = checksLabel(pr);
      return (
        <div className="home-inbox-detail">
          <p className="home-inbox-detail__byline">
            by <b>{pr.author ?? 'unknown'}</b> in <span className="home-inbox-detail__ref">{pr.repo} #{pr.number}</span>
          </p>
          <div className="home-inbox-detail__stats">
            {checks && (
              <span className={`home-inbox-checks home-inbox-checks--${checks.ok ? 'ok' : 'bad'}`}>
                {checks.text}
              </span>
            )}
            <span className="home-inbox-delta home-inbox-delta--add">+{pr.additions}</span>
            <span className="home-inbox-delta home-inbox-delta--del">-{pr.deletions}</span>
            <span className="home-inbox-detail__muted">
              {pr.requestedReviewers.length} reviewer{pr.requestedReviewers.length !== 1 ? 's' : ''}
            </span>
          </div>
          <div className="home-inbox-detail__actions">
            {item.type === 'review' && (
              <button
                type="button"
                className="home-inbox-btn home-inbox-btn--primary"
                disabled={approving}
                onClick={() => {
                  setApproving(true);
                  void handlers.approve(pr).finally(() => setApproving(false));
                }}
              >
                {approving ? 'Approving…' : 'Approve'}
              </button>
            )}
            <button type="button" className="home-inbox-btn" onClick={() => openPrRow(pr)}>
              View PR
            </button>
            <button
              type="button"
              className="home-inbox-btn home-inbox-btn--quiet"
              onClick={() => handlers.dismiss(item)}
            >
              Dismiss
            </button>
          </div>
        </div>
      );
    }
    if (item.source.kind === 'notification') {
      const n = item.source.notification;
      if (isPublishGateNotification(n)) {
        const gateRef = prRefFromNotification(n);
        return (
          <div className="home-inbox-detail" onClick={e => e.stopPropagation()}>
            <PublishGatePane
              notification={n}
              onResolved={handlers.resolved}
              onViewPr={gateRef
                ? () => handlers.openPr(gateRef.owner, gateRef.repo, gateRef.prNumber)
                : undefined}
              prTitle={gateRef
                ? handlers.prTitle?.(gateRef.owner, gateRef.repo, gateRef.prNumber) ?? undefined
                : undefined}
            />
          </div>
        );
      }
      const ref = prRefFromNotification(n);
      // AUTO_FIX_DONE rows have no dedicated resolution action, so
      // engaging with a view button is the read signal instead of the
      // row expand (which would clear it before the user chose Dismiss
      // vs. actually viewing) — see the opened() rule in InboxSection.
      const viewMarksRead = n.kind === 'AUTO_FIX_DONE' ? () => handlers.opened?.(item) : () => {};
      return (
        <div className="home-inbox-detail">
          <div className="home-inbox-detail__actions">
            {ref && (
              <button
                type="button"
                className="home-inbox-btn"
                onClick={() => { viewMarksRead(); handlers.openPr(ref.owner, ref.repo, ref.prNumber); }}
              >
                View PR
              </button>
            )}
            {n.threadId && n.taskId && handlers.openTask && (
              <button
                type="button"
                className="home-inbox-btn"
                onClick={() => { viewMarksRead(); handlers.openTask?.(n.threadId as string, n.taskId as string); }}
              >
                View task
              </button>
            )}
            <button
              type="button"
              className="home-inbox-btn home-inbox-btn--quiet"
              onClick={() => handlers.dismiss(item)}
            >
              Dismiss
            </button>
          </div>
        </div>
      );
    }
    const d = item.source.deploy;
    return (
      <div className="home-inbox-detail">
        <div className="home-inbox-detail__grid">
          <span className="home-inbox-detail__muted">Branch</span><b>{d.branch}</b>
          <span className="home-inbox-detail__muted">Commit</span><code>{d.commit}</code>
          <span className="home-inbox-detail__muted">Duration</span><span>{d.durationLabel}</span>
        </div>
        <div className="home-inbox-detail__actions">
          <button
            type="button"
            className="home-inbox-btn home-inbox-btn--quiet"
            onClick={() => handlers.dismiss(item)}
          >
            Dismiss
          </button>
        </div>
      </div>
    );
  };

  return (
    <div className={`home-inbox-card${item.read ? ' home-inbox-card--read' : ''}${open ? ' home-inbox-card--open' : ''}`}>
      <div
        className="home-inbox-card__row"
        role="button"
        tabIndex={0}
        onClick={() => {
          if (!open && opensReadOnExpand) handlers.opened?.(item);
          setOpen(v => !v);
        }}
        onKeyDown={e => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            if (!open && opensReadOnExpand) handlers.opened?.(item);
            setOpen(v => !v);
          }
        }}
      >
        <span className={`home-inbox-tile home-inbox-tile--${item.type}`} aria-hidden="true">
          {TYPE_ICON[item.type]}
        </span>
        <span className="home-inbox-card__text">
          <span className="home-inbox-card__title">{item.title}</span>
          <span className="home-inbox-card__sub">{item.sub}</span>
        </span>
        <span className="home-inbox-card__meta">
          {badges.author && <Avatar login={badges.author} size={16} className="home-inbox-card__author" />}
          {badges.repoOwner && <Avatar login={badges.repoOwner} size={16} className="home-inbox-card__repo" />}
          {!item.read && <span className="home-inbox-card__dot" aria-label="unread" />}
          <span className="home-inbox-card__time">{relativeTime(item.time)}</span>
          <span className={`home-inbox-card__chev${open ? ' home-inbox-card__chev--open' : ''}`} aria-hidden="true">›</span>
        </span>
      </div>
      {open && body()}
    </div>
  );
}

export default InboxCard;
