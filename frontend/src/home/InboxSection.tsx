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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { NotificationDto, PullRequestDto } from '../types';
import { buildInboxItems, type InboxItem } from './inboxItems';
import { fetchDeployNotices, type DeployNoticeDto } from './homeData';
import InboxCard, { type InboxHandlers } from './InboxCard';

/** Rows shown before "See all" takes over. */
const MAX_ROWS = 5;

/** Persisted "Unread only" filter — survives navigating away and back. */
const UNREAD_ONLY_KEY = 'home:inbox:unreadOnly';

type Props = {
  /** Cached PR list from the page's own fetch — review requests and
   *  attention-flagged PRs derive inbox rows from it. */
  prs: PullRequestDto[] | null;
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
  onOpenTask?: (threadId: string, taskId: string) => void;
  /** "See all" → the notification center. */
  onSeeAll: () => void;
  /** Re-fetch the PR list after an inbox action changed it (approve). */
  onPrsChanged: (next: PullRequestDto[]) => void;
};

/** Home Inbox — app notifications merged with PR rows that need the
 *  user (review requests, failing CI, conflicts, mentions). */
function InboxSection({ prs, onOpenPr, onOpenTask, onSeeAll, onPrsChanged }: Props) {
  const [notifications, setNotifications] = useState<NotificationDto[]>([]);
  const [deploys, setDeploys] = useState<DeployNoticeDto[]>([]);
  const [hiddenIds, setHiddenIds] = useState<string[]>([]);
  const [unreadOnly, setUnreadOnly] = useState(() => localStorage.getItem(UNREAD_ONLY_KEY) === '1');
  /** Transient hint when the backend refuses a dismiss. */
  const [note, setNote] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setNotifications(await window.bridge.listNotifications());
      setNote(null);
    }
    catch { /* non-fatal — keep the previous list */ }
  }, []);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => { void refresh(); }, 20_000);
    return () => window.clearInterval(id);
  }, [refresh]);

  useEffect(() => {
    fetchDeployNotices().then(setDeploys).catch(() => {});
  }, []);

  const items = useMemo(
    () => buildInboxItems(notifications, prs ?? [], deploys),
    [notifications, prs, deploys],
  );
  const unreadCount = items.filter(i => !i.read).length;
  const visible = items
    .filter(i => !hiddenIds.includes(i.id) && (!unreadOnly || !i.read))
    .slice(0, MAX_ROWS);

  const handlers: InboxHandlers = {
    openPr: onOpenPr,
    openTask: onOpenTask,
    dismiss: (item: InboxItem) => {
      if (item.source.kind === 'notification') {
        const n = item.source.notification;
        window.bridge.dismissNotification(n.id)
          .then(() => refresh())
          .catch(() => {
            // The backend refuses to dismiss rows that still need action
            // (a stuck task, an open approval). Hint instead of a dead click.
            setNote('Resolve this from its thread before dismissing.');
          });
        return;
      }
      if (item.source.kind === 'pr') {
        // Same concept as the kanban: dismissing moves the PR to its
        // Handled bucket, which also removes it from this inbox.
        window.bridge.markPrHandled(item.source.pr.id, 'DISMISSED')
          .then(() => window.bridge.fetchPrs())
          .then(onPrsChanged)
          .catch(() => setNote("Couldn't dismiss — try again."));
        return;
      }
      // Provider-backed rows have no backend record — hide locally.
      setHiddenIds(ids => [...ids, item.id]);
    },
    approve: async (pr: PullRequestDto) => {
      // GitHub first; only refresh the local caches once it succeeded.
      try {
        await window.bridge.approvePr(pr.id, pr.repo, pr.number);
      }
      catch (e) {
        setNote(`Couldn't approve: ${e instanceof Error ? e.message : String(e)}`);
        return;
      }
      window.bridge.fetchPrs().then(onPrsChanged).catch(() => {});
    },
    resolved: () => { void refresh(); },
    opened: (item: InboxItem) => {
      if (item.source.kind !== 'notification') return;
      const n = item.source.notification;
      // Informational rows clear on engagement; parked approvals and
      // stuck tasks keep their unread state until actually resolved
      // (same rule as the thread strip).
      if (n.kind === 'AUTO_FIX_DONE' && n.status === 'UNREAD') {
        window.bridge.markNotificationRead(n.id)
          .then(() => refresh())
          .catch(() => { /* best-effort */ });
      }
    },
  };

  return (
    <div className="home-inbox">
      <div className="home-inbox__header">
        <div className="home-inbox__heading">
          <span className="home-inbox__title">Inbox</span>
          {unreadCount > 0 && <span className="home-inbox__badge">{unreadCount}</span>}
        </div>
        <div className="home-inbox__controls">
          <button
            type="button"
            className={`home-inbox__filter${unreadOnly ? ' home-inbox__filter--on' : ''}`}
            onClick={() => setUnreadOnly(v => {
              localStorage.setItem(UNREAD_ONLY_KEY, v ? '0' : '1');
              return !v;
            })}
          >
            <span className="home-inbox__filter-dot" aria-hidden="true" />
            Unread only
          </button>
          <button type="button" className="home-inbox__seeall" onClick={onSeeAll}>
            See all
          </button>
        </div>
      </div>
      {visible.length === 0 ? (
        <p className="home-inbox__empty">
          {unreadOnly ? 'No unread notifications' : 'Nothing needs you right now.'}
        </p>
      ) : (
        <div className="home-inbox__list">
          {visible.map(item => <InboxCard key={item.id} item={item} handlers={handlers} />)}
        </div>
      )}
      {note && <p className="home-inbox__note" role="status">{note}</p>}
    </div>
  );
}

export default InboxSection;
