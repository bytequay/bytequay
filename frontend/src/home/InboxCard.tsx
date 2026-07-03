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
import { isPublishGateNotification, relativeTime } from '../notificationDisplay';
import { prRefFromNotification } from '../threads/notificationNav';
import PublishGatePane from '../PublishGatePane';
import type { InboxItem, InboxItemType } from './inboxItems';

export type InboxHandlers = {
  openPr: (owner: string, repo: string, prNumber: number) => void;
  openTask?: (threadId: string, taskId: string) => void;
  /** Dismiss the row — the section routes by source (backend dismiss
   *  for app notifications, local hide for provider rows). Absent on
   *  PR-derived rows: they have no backing dismiss action, they leave
   *  the inbox when the PR itself is viewed/handled. */
  dismiss: (item: InboxItem) => void;
  /** Approve the PR on GitHub. The section refreshes the list after. */
  approve: (pr: PullRequestDto) => Promise<void>;
  /** A parked approval was resolved via the embedded publish gate. */
  resolved: () => void;
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
          </div>
        </div>
      );
    }
    if (item.source.kind === 'notification') {
      const n = item.source.notification;
      if (isPublishGateNotification(n)) {
        return (
          <div className="home-inbox-detail" onClick={e => e.stopPropagation()}>
            <PublishGatePane notification={n} onResolved={handlers.resolved} />
          </div>
        );
      }
      const ref = prRefFromNotification(n);
      return (
        <div className="home-inbox-detail">
          <div className="home-inbox-detail__actions">
            {ref && (
              <button
                type="button"
                className="home-inbox-btn"
                onClick={() => handlers.openPr(ref.owner, ref.repo, ref.prNumber)}
              >
                View PR
              </button>
            )}
            {n.threadId && n.taskId && handlers.openTask && (
              <button
                type="button"
                className="home-inbox-btn"
                onClick={() => handlers.openTask?.(n.threadId as string, n.taskId as string)}
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
        onClick={() => setOpen(v => !v)}
        onKeyDown={e => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
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
