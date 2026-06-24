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
import type { ReactNode } from 'react';

/** Notification severity — drives the icon colour. */
export type NotifIconKind = 'info' | 'success' | 'warn' | 'alert';

const DEFAULT_GLYPH: Record<NotifIconKind, string> = {
  info: 'i', success: '✓', warn: '!', alert: '⚠',
};

export type NotifData = {
  id: string;
  iconKind: NotifIconKind;
  iconGlyph?: ReactNode;
  title: ReactNode;
  sub?: ReactNode;
  timestamp?: ReactNode;
  unread?: boolean;
};

/** A single notification row: severity icon + title/sub + timestamp. */
export function NotificationRow({ notif, onClick }: { notif: NotifData; onClick?: () => void }) {
  return (
    <button type="button" className={notif.unread === true ? 'notif-row unread' : 'notif-row'} onClick={onClick}>
      <span className={`ic ${notif.iconKind}`} aria-hidden>{notif.iconGlyph ?? DEFAULT_GLYPH[notif.iconKind]}</span>
      <span className="body">
        <span className="nm">{notif.title}</span>
        {notif.sub !== undefined && <span className="sub">{notif.sub}</span>}
      </span>
      {notif.timestamp !== undefined && <span className="ts">{notif.timestamp}</span>}
    </button>
  );
}

/** The Notifications tab — a per-thread stream of agent + system signals. */
export function NotificationsTabContent({ notifications, onOpen }: {
  notifications: NotifData[];
  onOpen?: (id: string) => void;
}) {
  if (notifications.length === 0) {
    return <div className="pane-meta-row" style={{ border: 0 }}>No notifications yet.</div>;
  }
  return (
    <>
      {notifications.map(n => (
        <NotificationRow key={n.id} notif={n} onClick={onOpen !== undefined ? () => onOpen(n.id) : undefined} />
      ))}
    </>
  );
}
