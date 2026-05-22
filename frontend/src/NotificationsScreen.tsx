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
import { kindIcon, previewFor, relativeTime, titleFor } from './notificationDisplay';
import PublishGatePane from './PublishGatePane';
import type { NotificationDto } from './types';

type Props = {
  /** Click-to-thread navigation. The dispatch lives in App.tsx so
   *  it can flip top-level nav state. */
  onOpenThread?: (threadId: string) => void;
  /** Same as {@link onOpenThread}, but routes to the review-thread
   *  page instead of the build-thread detail. Used when a
   *  notification's payload is tagged {@code source:
   *  'scheduled-review'} so the click lands on the panel UI. */
  onOpenReviewThread?: (threadId: string) => void;
};

/** Newest-first list of notifications backed by the bridge. Empty
 *  state explains what kinds of events will land here once the
 *  automation runtime starts producing them. */
function NotificationsScreen({ onOpenThread, onOpenReviewThread }: Props) {
  const [items, setItems] = useState<NotificationDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** Id of the AWAITING_REVIEW row whose publish pane is open. Only
   *  one expands at a time — the alternative (multiple open panes
   *  with editable textareas) just confuses the user about which
   *  Approve they're about to click. */
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await window.bridge.listNotifications();
      setItems(next);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    // Poll while the screen is open. The bell badge in the global
    // nav does its own short-interval poll independently.
    const id = window.setInterval(() => { void refresh(); }, 15_000);
    return () => window.clearInterval(id);
  }, [refresh]);

  const handleOpen = async (n: NotificationDto) => {
    if (n.status === 'UNREAD') {
      try {
        await window.bridge.markNotificationRead(n.id);
      }
      catch {
        // The thread-open is the user-visible action; a missed
        // markRead just leaves the badge unchanged until next poll.
      }
    }
    if (n.threadId) {
      // Scheduled-review notifications carry source='scheduled-review'
      // in their payload — those threads use the panel UI, so route
      // the click to the review-thread page instead of build-thread
      // detail. Other AWAITING_REVIEW payloads (push / post_comment)
      // stay on the build-thread route they already use.
      if (isScheduledReviewNotification(n) && onOpenReviewThread) {
        onOpenReviewThread(n.threadId);
      }
      else if (onOpenThread) {
        onOpenThread(n.threadId);
      }
    }
    void refresh();
  };

  const handleDismiss = async (n: NotificationDto, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await window.bridge.dismissNotification(n.id);
    }
    catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
    void refresh();
  };

  return (
    <section className="notifications-screen calm-page">
      <header className="notifications-screen__head calm-page-header">
        <h1 className="notifications-screen__title">Notifications</h1>
        <p className="notifications-screen__subtitle">
          Headless-fix progress and parked work shows up here. Click an
          item to jump to its thread.
        </p>
      </header>

      {error && <div className="notifications-screen__error">{error}</div>}

      {loading && items.length === 0 ? (
        <div className="settings-stub">Loading…</div>
      ) : items.length === 0 ? (
        <div className="settings-stub">
          <div className="settings-stub__title">Nothing yet</div>
          <div>
            New rows arrive on ship-and-continue completion and (soon)
            when a headless run parks at AWAITING REVIEW / NEEDS ATTENTION.
          </div>
        </div>
      ) : (
        <ul className="notifications-list">
          {items.map(n => {
            const reviewable = isPublishGateNotification(n);
            const expanded = expandedId === n.id;
            return (
              <li
                key={n.id}
                className={`notifications-list__row notifications-list__row--${n.status.toLowerCase()}`}
                onClick={() => { void handleOpen(n); }}
              >
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                  <div className="notifications-list__icon">{kindIcon(n.kind)}</div>
                  <div className="notifications-list__body" style={{ flex: 1 }}>
                    <div className="notifications-list__title">{titleFor(n)}</div>
                    <div className="notifications-list__meta">{previewFor(n)}</div>
                    <div className="notifications-list__time">{relativeTime(n.createdAt)}</div>
                  </div>
                  {reviewable && (
                    <button
                      type="button"
                      onClick={e => {
                        e.stopPropagation();
                        setExpandedId(expanded ? null : n.id);
                      }}
                    >
                      {expanded ? 'Hide' : 'Review'}
                    </button>
                  )}
                  <button
                    type="button"
                    className="notifications-list__dismiss"
                    onClick={e => { void handleDismiss(n, e); }}
                    title="Dismiss"
                  >
                    ✕
                  </button>
                </div>
                {reviewable && expanded && (
                  <div onClick={e => e.stopPropagation()}>
                    <PublishGatePane
                      notification={n}
                      onResolved={() => {
                        setExpandedId(null);
                        void refresh();
                      }}
                    />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** True when the notification is tagged source='scheduled-review' —
 *  i.e. one of the panel passes the ScheduledReviewService kicked
 *  off. The click target is the review-thread page rather than the
 *  build-thread detail. */
function isScheduledReviewNotification(n: NotificationDto): boolean {
  if (n.kind !== 'AWAITING_REVIEW') return false;
  if (!n.payloadJson) return false;
  try {
    const raw = JSON.parse(n.payloadJson) as { source?: unknown };
    return raw.source === 'scheduled-review';
  }
  catch {
    return false;
  }
}

/** True when the row should show a Review button that expands the
 *  publish gate pane. AWAITING_REVIEW notifications without a
 *  recognised action (e.g. a plain request_review summary) keep the
 *  current click-to-thread behaviour. */
function isPublishGateNotification(n: NotificationDto): boolean {
  if (n.kind !== 'AWAITING_REVIEW') return false;
  if (!n.payloadJson) return false;
  try {
    const raw = JSON.parse(n.payloadJson) as { action?: unknown };
    return raw.action === 'push' || raw.action === 'post_comment';
  }
  catch {
    return false;
  }
}

export default NotificationsScreen;
