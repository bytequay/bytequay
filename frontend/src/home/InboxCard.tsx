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
import type { DashboardPR } from '../types/dashboardPr';
import { relativeTime } from '../notificationDisplay';
import { RepoAv } from '../pulls/atoms';
import { prRefFromNotification } from '../threads/notificationNav';
import RepoAvatar from '../threads/RepoAvatar';
import type { InboxItem } from './inboxItems';

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
  ack?: (item: InboxItem) => void;
  opened?: (item: InboxItem) => void;
  prTitle?: (owner: string, repo: string, prNumber: number) => string | null;
  taskTitle?: (taskId: string) => string | null;
  taskWorkingDir?: (taskId: string) => string | null;
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
  const title = itemTitle(item, ref, handlers);
  const repoLabel = ref?.repo ?? workspace?.name ?? 'bytequay';
  const taskWorkingDir = item.source.kind === 'notification'
    && item.source.notification.taskId !== null
    ? handlers.taskWorkingDir?.(item.source.notification.taskId) ?? null
    : null;
  const icon = item.icon ?? legacyIcon(item.type);
  const actionRequired = item.actionRequired
    ?? (item.type === 'review' || item.type === 'approval' || item.type === 'blocked');

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
        <span className={`home-inbox-tile home-inbox-tile--${item.type} home-inbox-tile--icon-${icon}`} aria-hidden>
          <InboxIcon icon={icon} />
        </span>
        <span className="home-inbox-card__text">
          <span className="home-inbox-card__title-line">
            <span className="home-inbox-card__title">{title}</span>
            <time className="home-inbox-card__time">{relativeTime(item.time).replace(' ago', '')}</time>
          </span>
          <small className="home-inbox-card__sub">{item.sub}</small>
        </span>
        <span className="home-inbox-card__scope" title={ref === null ? repoLabel : `${ref.owner}/${ref.repo}`}>
          {ref !== null ? (
            <RepoAv repo={`${ref.owner}/${ref.repo}`} size={16} />
          ) : taskWorkingDir !== null ? (
            <RepoAvatar workingDir={taskWorkingDir} size={16} fallbackGradient="#0969da" />
          ) : (
            <span className="home-inbox-card__repo-initial" aria-hidden="true">{repoLabel.charAt(0).toUpperCase()}</span>
          )}
          {repoLabel}
        </span>
        {actionRequired ? (
          <button
            type="button"
            className="home-inbox-card__action"
            onClick={event => {
              event.stopPropagation();
              open();
            }}
          >
            Review <ArrowIcon />
          </button>
        ) : item.read ? (
          <span className="home-inbox-card__acked"><CheckIcon /> Acked</span>
        ) : (
          <button
            type="button"
            className="home-inbox-card__ack"
            title="Acknowledge — mark read without opening"
            onClick={event => {
              event.stopPropagation();
              handlers.ack?.(item);
            }}
          >
            <CheckIcon /> Ack
          </button>
        )}
        <i className={`home-inbox-card__dot${item.read ? ' home-inbox-card__dot--clear' : ''}`} aria-label={item.read ? undefined : 'unread'} />
      </div>
    </article>
  );
}

function itemTitle(
  item: InboxItem,
  ref: ReturnType<typeof itemRef>,
  handlers: InboxHandlers,
): string {
  if (item.source.kind === 'pr') return item.source.pr.title;
  if (ref !== null) {
    const prTitle = handlers.prTitle?.(ref.owner, ref.repo, ref.prNumber);
    if (prTitle) return prTitle;
  }
  if (item.source.kind === 'notification' && item.source.notification.taskId !== null) {
    const taskTitle = handlers.taskTitle?.(item.source.notification.taskId);
    if (taskTitle) return taskTitle;
  }
  return item.title;
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

function legacyIcon(type: InboxItem['type']): NonNullable<InboxItem['icon']> {
  if (type === 'review' || type === 'approval' || type === 'mention') return 'pr';
  if (type === 'blocked') return 'attention';
  return type === 'done' ? 'check' : 'task';
}

function InboxIcon({ icon }: { icon: NonNullable<InboxItem['icon']> }) {
  if (icon === 'pr') {
    return <svg viewBox="0 0 24 24"><circle cx="18" cy="18" r="2.3" /><circle cx="6" cy="5.5" r="2.3" /><circle cx="6" cy="18.5" r="2.3" /><path d="M6 7.8v8.4M11.3 5.5H15a3 3 0 0 1 3 3v7.7" /></svg>;
  }
  if (icon === 'attention') {
    return <svg viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18" /></svg>;
  }
  if (icon === 'task') {
    return <svg viewBox="0 0 24 24"><rect x="4.5" y="4.5" width="15" height="15" rx="2" /><path d="m8.4 12.3 2.4 2.4 4.8-5.2" /></svg>;
  }
  return <svg viewBox="0 0 24 24"><path d="M20 6.5 9.4 17.1 4.2 11.9" /></svg>;
}

function CheckIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6.5 9.4 17.1 4.2 11.9" /></svg>;
}

function ArrowIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>;
}

export default InboxCard;
