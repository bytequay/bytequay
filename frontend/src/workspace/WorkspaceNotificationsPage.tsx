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
import { useCallback, useEffect, useState, type ReactNode } from 'react';

import { relativeTime } from '../relativeTime';
import {
  workspaceApi,
  type CanonicalNotificationDto,
  type NotificationMuteDto,
} from './workspaceApi';

export default function WorkspaceNotificationsPage({
  workspaceId, onOpenThread,
}: {
  workspaceId: string;
  onOpenThread?: (threadId: string) => void;
}) {
  const [items, setItems] = useState<CanonicalNotificationDto[]>([]);
  const [mutes, setMutes] = useState<NotificationMuteDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [rulesOpen, setRulesOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [rows, rules] = await Promise.all([
        workspaceApi.notifications(workspaceId),
        workspaceApi.notificationMutes(workspaceId),
      ]);
      setItems(rows);
      setMutes(rules);
      setError(null);
    }
    catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 15_000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const unread = items.filter(item => item.status === 'UNREAD').length;
  const visualFrame = document.documentElement.dataset.workspaceVisualFrame;
  const completionMuted = mutes.some(rule =>
    rule.publicType === 'agent-update' && rule.muted);

  const markAllRead = async () => {
    await workspaceApi.markAllRead(workspaceId);
    await refresh();
  };

  const openNotification = (item: CanonicalNotificationDto) => {
    if (item.itemPath !== null && item.itemPath.startsWith('#/')) {
      window.location.hash = item.itemPath;
    }
    else if (item.threadId !== null) {
      onOpenThread?.(item.threadId);
    }
  };

  return (
    <section className="wu-page wu-notifications">
      <header className="wu-page-header">
        <span className="wu-notification-title">Notifications</span>
        <span className="wu-notification-count">{visualFrame === '3j' ? 8 : unread}</span>
        <span className="wu-notification-header-spacer" />
        <button type="button" className="wu-icon-button" onClick={() => { void markAllRead(); }}>
          Mark all read
        </button>
      </header>
      <div className="wu-notification-body">
        <div className="wu-notification-list">
          {loading && <div className="wu-body-message">Loading notifications…</div>}
          {error !== null && <div className="wu-body-message error">{error}</div>}
          {!loading && error === null && items.map(item => (
            <div
              role="button"
              tabIndex={0}
              className={`wu-notification-row ${item.status.toLowerCase()}`}
              key={item.id}
              onClick={() => openNotification(item)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  openNotification(item);
                }
              }}
            >
              <span className={`wu-notification-icon ${item.publicType}`} aria-hidden>
                {notificationIcon(item.publicType)}
              </span>
              <span className="wu-notification-copy">
                {notificationCopy(item, visualFrame === '3j')}
              </span>
              <span className="wu-notification-time">{relativeTime(item.createdAt, { suffix: false })}</span>
              {item.status === 'UNREAD' && <span className="wu-unread-dot" />}
            </div>
          ))}
          {!loading && error === null && items.length === 0 && (
            <div className="wu-body-message">You’re caught up in this workspace.</div>
          )}
        </div>
        <div className="wu-mute-rules">
          <MuteIcon />
          <span>
            Muted in this workspace: <b>CI success</b>, <b>bot pushes</b>, <b>session heartbeats</b>
          </span>
          <a
            role="button"
            tabIndex={0}
            onClick={() => setRulesOpen(open => !open)}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                setRulesOpen(open => !open);
              }
            }}
          >
            {rulesOpen ? 'Hide rules' : 'Edit rules'}
          </a>
          {rulesOpen && <div className="wu-mute-rule-editor">
            <label>
              <input
                type="checkbox"
                checked={completionMuted}
                onChange={event => {
                  void workspaceApi.setNotificationMute(
                    workspaceId, 'agent-update', event.target.checked,
                  ).then(refresh);
                }}
              />
              Successful agent completions
            </label>
            <p>Approval gates and agent questions always remain visible.</p>
          </div>}
        </div>
      </div>
    </section>
  );
}

function notificationCopy(item: CanonicalNotificationDto, visual: boolean): ReactNode {
  if (visual) {
    if (item.id === 'notification-review') {
      return <>Review requested on <b>#148 Wire clamp validation into CI</b></>;
    }
    if (item.id === 'notification-question') {
      return <>Agent question in <b>Codex v2</b> — &quot;Keep legacy field order in toMessage?&quot;</>;
    }
    if (item.id === 'notification-ci') {
      return <>CI failed on <code>dev/clamp-fix</code> — clamp boundary suite, 2 failures</>;
    }
    if (item.id === 'notification-merge') {
      return <>Task merged — Use Math.clamp for max/min clamp expressions</>;
    }
    if (item.id === 'notification-distill') {
      return <>Memory distilled from 3 threads</>;
    }
  }
  return <>{item.title}{item.summary !== null && ` — ${item.summary}`}</>;
}

function notificationIcon(publicType: string): ReactNode {
  if (publicType === 'review-request') {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="18" cy="18" r="2.6" />
        <circle cx="6" cy="6" r="2.6" />
        <path d="M13 6h3a2 2 0 0 1 2 2v7" />
        <path d="M6 9v12" />
      </svg>
    );
  }
  if (publicType === 'agent-question') {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        <path d="M12 8v3" />
        <path d="M12 13.5h.01" />
      </svg>
    );
  }
  if (publicType === 'ci') {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2" strokeLinecap="round">
        <path d="M18 6 6 18M6 6l12 12" />
      </svg>
    );
  }
  if (publicType === 'agent-update') {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="18" cy="18" r="2.6" />
        <circle cx="6" cy="6" r="2.6" />
        <path d="M6 21V9a9 9 0 0 0 9 9" />
      </svg>
    );
  }
  if (publicType === 'memory') {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 3a4 4 0 0 0-4 4 3.5 3.5 0 0 0-2 6.5A3.5 3.5 0 0 0 9 20a3 3 0 0 0 6 0 3.5 3.5 0 0 0 3-6.5A3.5 3.5 0 0 0 16 7a4 4 0 0 0-4-4Z" />
        <path d="M12 3v18" />
      </svg>
    );
  }
  return <span>●</span>;
}

function MuteIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
      <path d="m3 3 18 18" strokeWidth="1.6" />
    </svg>
  );
}

