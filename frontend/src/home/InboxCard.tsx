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
import type { DashboardPR } from '../types/dashboardPr';
import { relativeTime } from '../notificationDisplay';
import { prRefFromNotification } from '../threads/notificationNav';
import type { InboxItem, InboxItemType } from './inboxItems';

export type WorkspaceInboxTarget = {
  workspaceId: string;
  name: string;
};

export type InboxHandlers = {
  openPr: (owner: string, repo: string, prNumber: number) => void;
  openWorkspacePr?: (workspaceId: string, prNumber: number) => void;
  openRemoteReview?: (owner: string, repo: string, prNumber: number) => void;
  workspaceForRepo?: (owner: string, repo: string) => WorkspaceInboxTarget | null;
  openTask?: (threadId: string, taskId: string) => void;
  dismiss: (item: InboxItem) => void;
  approve: (pr: DashboardPR) => Promise<void>;
  resolved: () => void;
  opened?: (item: InboxItem) => void;
  prTitle?: (owner: string, repo: string, prNumber: number) => string | null;
};

/**
 * Exact compact Home Inbox row from frame 6b. Rows navigate directly to
 * their canonical home instead of expanding a second interaction surface.
 */
function InboxCard({ item, handlers }: { item: InboxItem; handlers: InboxHandlers }) {
  const ref = itemRef(item);
  const workspace = ref === null
    ? null
    : handlers.workspaceForRepo?.(ref.owner, ref.repo) ?? null;
  const actionableReview = item.type === 'review' || item.type === 'approval';

  const open = () => {
    handlers.opened?.(item);
    if (ref !== null) {
      if (workspace !== null && handlers.openWorkspacePr !== undefined) {
        handlers.openWorkspacePr(workspace.workspaceId, ref.prNumber);
      }
      else if (handlers.openRemoteReview !== undefined) {
        handlers.openRemoteReview(ref.owner, ref.repo, ref.prNumber);
      }
      else {
        handlers.openPr(ref.owner, ref.repo, ref.prNumber);
      }
      return;
    }
    if (item.source.kind === 'notification') {
      const notification = item.source.notification;
      if (notification.threadId !== null
          && notification.taskId !== null
          && handlers.openTask !== undefined) {
        handlers.openTask(notification.threadId, notification.taskId);
      }
    }
  };

  return (
    <article className={`home-inbox-card home-inbox-card--${item.type}${
      item.read ? ' home-inbox-card--read' : ''}`}>
      <div
        className="home-inbox-card__row"
        role="button"
        tabIndex={0}
        onClick={open}
        onKeyDown={event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            open();
          }
        }}
      >
        <span className={`home-inbox-tile home-inbox-tile--${item.type}`} aria-hidden>
          <InboxIcon type={item.type} />
        </span>
        <span className="home-inbox-card__text">
          <strong className="home-inbox-card__title">{item.title}</strong>
          <small className="home-inbox-card__sub">{item.sub}</small>
        </span>
        <span className={`home-inbox-card__scope ${workspace === null ? 'remote' : ''}`}>
          {workspace === null
            ? <GlobeIcon />
            : <WorkspaceScopeIcon owner={ref?.owner ?? ''} />}
          {workspace?.name ?? (ref === null ? 'bytequay' : 'remote')}
        </span>
        {actionableReview && (
          <button
            type="button"
            className={`home-inbox-card__action${workspace === null ? ' remote' : ''}`}
            onClick={event => {
              event.stopPropagation();
              open();
            }}
          >
            {workspace === null ? 'Open in Reviews' : 'Review →'}
          </button>
        )}
        <time className="home-inbox-card__time">{relativeTime(item.time).replace(' ago', '')}</time>
        {!item.read && <i className="home-inbox-card__dot" aria-label="unread" />}
      </div>
    </article>
  );
}

function itemRef(item: InboxItem): {
  owner: string;
  repo: string;
  prNumber: number;
} | null {
  if (item.source.kind === 'pr') {
    const slash = item.source.pr.repo.indexOf('/');
    if (slash < 1) return null;
    return {
      owner: item.source.pr.repo.slice(0, slash),
      repo: item.source.pr.repo.slice(slash + 1),
      prNumber: item.source.pr.number,
    };
  }
  if (item.source.kind === 'notification') {
    return prRefFromNotification(item.source.notification);
  }
  return null;
}

function InboxIcon({ type }: { type: InboxItemType }) {
  if (type === 'review' || type === 'approval') {
    return <svg viewBox="0 0 24 24"><circle cx="18" cy="18" r="2.5" /><circle cx="6" cy="6" r="2.5" /><path d="M6 21V9M13 6h3a2 2 0 0 1 2 2v7" /></svg>;
  }
  if (type === 'blocked') {
    return <svg viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18" /></svg>;
  }
  if (type === 'info') {
    return <svg viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /><path d="M12 8v3M12 13.5h.01" /></svg>;
  }
  if (type === 'mention') {
    return <svg viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>;
  }
  return <svg viewBox="0 0 24 24"><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" /><path d="M6 21V9a9 9 0 0 0 9 9" /></svg>;
}

function GlobeIcon() {
  return <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5" /><path d="M3.5 12h17M12 3.5c2.2 2.4 3.3 5.2 3.3 8.5S14.2 18.1 12 20.5M12 3.5C9.8 5.9 8.7 8.7 8.7 12s1.1 6.1 3.3 8.5" /></svg>;
}

function WorkspaceScopeIcon({ owner }: { owner: string }) {
  const [failed, setFailed] = useState(false);
  if (failed || owner === '') {
    return <svg className="home-inbox-card__workspace-icon" viewBox="0 0 24 24">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </svg>;
  }
  return <img
    className="home-inbox-card__workspace-icon"
    src={`https://github.com/${encodeURIComponent(owner)}.png?size=24`}
    alt=""
    onError={() => setFailed(true)}
  />;
}

export default InboxCard;
