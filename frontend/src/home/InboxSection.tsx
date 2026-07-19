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
import type { NotificationDto, WorkspaceRepoDto } from '../types';
import type { DashboardPR } from '../types/dashboardPr';
import { buildInboxItems, type InboxItem } from './inboxItems';
import { fetchDeployNotices, type DeployNoticeDto } from './homeData';
import { taskLabel } from '../threads/taskLabel';
import InboxCard, {
  type InboxHandlers,
  type WorkspaceInboxTarget,
} from './InboxCard';

const MAX_GROUP_ROWS = 3;

/** Persisted "Unread only" filter — survives navigating away and back. */
const UNREAD_ONLY_KEY = 'home:inbox:unreadOnly';

type Props = {
  /** Cached PR list from the page's own fetch — review requests and
   *  attention-flagged PRs derive inbox rows from it. */
  prs: DashboardPR[] | null;
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
  onOpenWorkspacePr?: (workspaceId: string, prNumber: number) => void;
  onOpenRemoteReview?: (owner: string, repo: string, prNumber: number) => void;
  onOpenTask?: (threadId: string, taskId: string) => void;
  /** Kept for older visual fixtures; See all is now the local filter. */
  onSeeAll?: () => void;
  /** Re-fetch the PR list after an inbox action changed it (approve). */
  onPrsChanged: (next: DashboardPR[]) => void;
};

/** Home Inbox — app notifications merged with PR rows that need the
 *  user (review requests, failing CI, conflicts, mentions). */
function InboxSection({
  prs,
  onOpenPr,
  onOpenWorkspacePr,
  onOpenRemoteReview,
  onOpenTask,
  onPrsChanged,
}: Props) {
  const [notifications, setNotifications] = useState<NotificationDto[]>([]);
  const [deploys, setDeploys] = useState<DeployNoticeDto[]>([]);
  const [hiddenIds, setHiddenIds] = useState<string[]>([]);
  const [workspaceRepos, setWorkspaceRepos] = useState<Map<string, WorkspaceInboxTarget>>(new Map());
  const [tasksById, setTasksById] = useState<Map<string, {
    title: string;
    workingDir: string | null;
  }>>(new Map());
  const [unreadOnly, setUnreadOnly] = useState(() => localStorage.getItem(UNREAD_ONLY_KEY) !== '0');
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

  useEffect(() => {
    const threadIds = [...new Set(notifications
      .filter(notification => notification.threadId !== null && notification.taskId !== null)
      .map(notification => notification.threadId as string))];
    if (threadIds.length === 0) return;
    let cancelled = false;
    void Promise.all(threadIds.map(async threadId => {
      const tasks = await window.bridge.listTasksForThread(threadId)
        .catch((): Awaited<ReturnType<typeof window.bridge.listTasksForThread>> => []);
      return tasks.map(task => [task.id, {
        title: taskLabel(task),
        workingDir: task.workingDir,
      }] as const);
    })).then(rows => {
      if (!cancelled) setTasksById(new Map(rows.flat()));
    });
    return () => { cancelled = true; };
  }, [notifications]);

  useEffect(() => {
    if (typeof window.bridge.listWorkspaces !== 'function'
        || typeof window.bridge.listWorkspaceRepos !== 'function') return;
    let cancelled = false;
    void window.bridge.listWorkspaces()
      .then(async workspaces => {
        const rows = await Promise.all(workspaces.map(async workspace => ({
          workspace,
          repos: await window.bridge.listWorkspaceRepos(workspace.id)
            .catch((): WorkspaceRepoDto[] => []),
        })));
        if (cancelled) return;
        const mapped = new Map<string, WorkspaceInboxTarget>();
        rows.forEach(({ workspace, repos }) => repos.forEach(repo => {
          mapped.set(repo.repoFullName.toLowerCase(), {
            workspaceId: workspace.id,
            name: workspace.name,
          });
        }));
        setWorkspaceRepos(mapped);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const items = useMemo(
    () => buildInboxItems(notifications, prs ?? [], deploys),
    [notifications, prs, deploys],
  );
  const available = items.filter(item => !hiddenIds.includes(item.id));
  const unreadCount = available.filter(item => !item.read).length;
  const needsAction = available.filter(item => item.actionRequired);
  const notificationsToShow = available
    .filter(item => !item.actionRequired && (!unreadOnly || !item.read));
  const visibleNeedsAction = needsAction.slice(0, MAX_GROUP_ROWS);
  const visibleNotifications = notificationsToShow.slice(0, MAX_GROUP_ROWS);

  const ack = (item: InboxItem) => {
    if (item.read || item.actionRequired || item.source.kind !== 'notification') return;
    const id = item.source.notification.id;
    const readAt = new Date().toISOString();
    setNotifications(current => current.map(notification => notification.id === id
      ? { ...notification, status: 'READ', readAt }
      : notification));
    window.bridge.markNotificationRead(id)
      .catch(() => { void refresh(); });
  };

  const ackAll = () => {
    const ids = available
      .filter(item => !item.actionRequired && !item.read && item.source.kind === 'notification')
      .map(item => item.source.kind === 'notification' ? item.source.notification.id : '')
      .filter(Boolean);
    if (ids.length === 0) return;
    const readAt = new Date().toISOString();
    const idSet = new Set(ids);
    setNotifications(current => current.map(notification => idSet.has(notification.id)
      ? { ...notification, status: 'READ', readAt }
      : notification));
    void Promise.allSettled(ids.map(id => window.bridge.markNotificationRead(id)))
      .then(results => {
        if (results.some(result => result.status === 'rejected')) void refresh();
      });
  };

  const handlers: InboxHandlers = {
    openPr: onOpenPr,
    openWorkspacePr: onOpenWorkspacePr,
    openRemoteReview: onOpenRemoteReview,
    workspaceForRepo: (owner, repo) =>
      workspaceRepos.get(`${owner}/${repo}`.toLowerCase()) ?? null,
    openTask: onOpenTask,
    ack,
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
        window.bridge.markDashboardPrHandled(item.source.pr.id, 'DISMISSED')
          .then(() => window.bridge.fetchDashboardPrs())
          .then(onPrsChanged)
          .catch(() => setNote("Couldn't dismiss — try again."));
        return;
      }
      // Provider-backed rows have no backend record — hide locally.
      setHiddenIds(ids => [...ids, item.id]);
    },
    approve: async (pr: DashboardPR) => {
      // GitHub first; only refresh the local caches once it succeeded.
      try {
        await window.bridge.approveDashboardPr(pr.id);
      }
      catch (e) {
        setNote(`Couldn't approve: ${e instanceof Error ? e.message : String(e)}`);
        return;
      }
      window.bridge.fetchDashboardPrs().then(onPrsChanged).catch(() => {});
    },
    resolved: () => { void refresh(); },
    prTitle: (owner: string, repo: string, prNumber: number) => {
      const full = `${owner}/${repo}`;
      return (prs ?? []).find(p => p.repo === full && p.number === prNumber)?.title ?? null;
    },
    taskTitle: taskId => tasksById.get(taskId)?.title ?? null,
    taskWorkingDir: taskId => tasksById.get(taskId)?.workingDir ?? null,
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
            onClick={() => {
              localStorage.setItem(UNREAD_ONLY_KEY, '1');
              setUnreadOnly(true);
            }}
          >
            <span className="home-inbox__filter-dot" aria-hidden="true" />
            Unread only
          </button>
          <button
            type="button"
            className={`home-inbox__filter${!unreadOnly ? ' home-inbox__filter--on' : ''}`}
            onClick={() => {
              localStorage.setItem(UNREAD_ONLY_KEY, '0');
              setUnreadOnly(false);
            }}
          >
            See all
          </button>
        </div>
      </div>
      <div className="home-inbox__list">
        <div className="home-inbox__group-header">
          Needs your action
          <span className="home-inbox__group-count">{needsAction.length}</span>
        </div>
        {visibleNeedsAction.map(item => <InboxCard key={item.id} item={item} handlers={handlers} />)}
        {visibleNeedsAction.length === 0 && (
          <p className="home-inbox__empty">Nothing needs you right now.</p>
        )}
        <div className="home-inbox__group-header home-inbox__group-header--notifications">
          Notifications · just so you know
          <span className="home-inbox__group-count">{notificationsToShow.length}</span>
          <button type="button" className="home-inbox__ack-all" onClick={ackAll}>Ack all</button>
        </div>
        {visibleNotifications.map(item => <InboxCard key={item.id} item={item} handlers={handlers} />)}
        {visibleNotifications.length === 0 && (
          <p className="home-inbox__empty">
            {unreadOnly ? 'All caught up — nothing left to acknowledge.' : 'No notifications yet.'}
          </p>
        )}
      </div>
      {note && <p className="home-inbox__note" role="status">{note}</p>}
    </div>
  );
}

export default InboxSection;
