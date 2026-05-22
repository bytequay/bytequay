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
import type { NotificationDto, NotificationKindDto } from '../types';

type Props = {
  threadId: string;
};

/** Compact strip of UNREAD notifications scoped to this thread, shown
 *  above the agent terminal. Each chip carries the same data the bell
 *  dropdown uses, but inline so the user can act without leaving the
 *  thread. Click a chip → mark read (chip disappears). ✕ → dismiss. */
export default function NotificationStrip({ threadId }: Props) {
  const [items, setItems] = useState<NotificationDto[]>([]);

  const refresh = useCallback(async () => {
    try {
      const all = await window.bridge.listNotificationsForThread(threadId);
      setItems(all.filter(n => n.status === 'UNREAD'));
    }
    catch { /* non-fatal — leave the previous list */ }
  }, [threadId]);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => { void refresh(); }, 20_000);
    return () => window.clearInterval(id);
  }, [refresh]);

  const onMarkRead = async (n: NotificationDto) => {
    try {
      await window.bridge.markNotificationRead(n.id);
    }
    catch { /* fall through to refresh */ }
    void refresh();
  };

  const onDismiss = async (n: NotificationDto, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await window.bridge.dismissNotification(n.id);
    }
    catch { /* fall through to refresh */ }
    void refresh();
  };

  const onJumpIn = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await window.bridge.jumpInThread(threadId);
    }
    catch { /* fall through to refresh; the backend's defensive
                  paths cover partial failures */ }
    void refresh();
  };

  if (items.length === 0) return null;
  return (
    <div style={stripStyle}>
      {items.map(n => (
        <div
          key={n.id}
          style={chipStyle(n.kind)}
          onClick={() => { void onMarkRead(n); }}
          role="button"
          tabIndex={0}
          title="Click to mark as read"
        >
          <span style={chipIconStyle}>{kindIcon(n.kind)}</span>
          <span style={chipBodyStyle}>
            <span style={chipTitleStyle}>{titleFor(n.kind)}</span>
            <span style={chipMetaStyle}>{previewFor(n)}</span>
          </span>
          {isParked(n.kind) && (
            <button
              type="button"
              style={chipJumpInStyle}
              onClick={e => { void onJumpIn(e); }}
              title="Interrupt the headless run and take control of the lease"
              aria-label="Jump in to this thread"
            >
              Jump in
            </button>
          )}
          <button
            type="button"
            style={chipDismissStyle}
            onClick={e => { void onDismiss(n, e); }}
            title="Dismiss"
            aria-label="Dismiss notification"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}

function isParked(kind: NotificationKindDto): boolean {
  return kind === 'NEEDS_ATTENTION' || kind === 'AWAITING_REVIEW';
}

function kindIcon(kind: NotificationKindDto): string {
  switch (kind) {
    case 'AWAITING_REVIEW':  return '👁';
    case 'NEEDS_ATTENTION':  return '⚠';
    case 'AUTO_FIX_DONE':    return '✓';
  }
}

function titleFor(kind: NotificationKindDto): string {
  switch (kind) {
    case 'AWAITING_REVIEW':  return 'Awaiting review';
    case 'NEEDS_ATTENTION':  return 'Needs attention';
    case 'AUTO_FIX_DONE':    return 'Shipped';
  }
}

function previewFor(n: NotificationDto): string {
  if (!n.payloadJson) return '';
  let payload: Record<string, unknown> | null = null;
  try {
    payload = JSON.parse(n.payloadJson);
  }
  catch {
    return '';
  }
  if (!payload) return '';
  if (n.kind === 'NEEDS_ATTENTION') {
    const repo = typeof payload.repoFullName === 'string' ? payload.repoFullName : null;
    const pr = typeof payload.prNumber === 'number' ? `#${payload.prNumber}` : null;
    const reason = typeof payload.reason === 'string' ? payload.reason : null;
    const left = [repo, pr].filter(Boolean).join(' ');
    if (left && reason) return `${left} · ${reason}`;
    return left || reason || '';
  }
  if (n.kind === 'AUTO_FIX_DONE') {
    const repo = typeof payload.repoFullName === 'string' ? payload.repoFullName : null;
    const pr = typeof payload.prNumber === 'number' ? `#${payload.prNumber}` : null;
    const nextTitle = typeof payload.nextTitle === 'string' ? payload.nextTitle : null;
    const left = [repo, pr].filter(Boolean).join(' ');
    if (left && nextTitle) return `${left} · next: ${nextTitle}`;
    return left || (nextTitle ? `next: ${nextTitle}` : '');
  }
  return '';
}

const stripStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 8,
  padding: '8px 12px',
  borderBottom: '1px solid #e5e7eb',
  background: '#fafaff',
};

function chipStyle(kind: NotificationKindDto): React.CSSProperties {
  const accent = kind === 'NEEDS_ATTENTION' ? '#dc2626'
    : kind === 'AWAITING_REVIEW' ? '#7c3aed'
    : '#047857';
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 8,
    padding: '6px 8px 6px 10px',
    background: '#ffffff',
    border: `1px solid ${accent}33`,
    borderLeft: `3px solid ${accent}`,
    borderRadius: 6,
    cursor: 'pointer',
    fontSize: 12,
    color: '#111827',
    maxWidth: 360,
  };
}

const chipIconStyle: React.CSSProperties = {
  fontSize: 14,
  lineHeight: 1,
};

const chipBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  minWidth: 0,
  flex: 1,
};

const chipTitleStyle: React.CSSProperties = {
  fontWeight: 600,
};

const chipMetaStyle: React.CSSProperties = {
  color: '#6b7280',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};

const chipDismissStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: '#9ca3af',
  fontSize: 14,
  cursor: 'pointer',
  padding: 2,
  lineHeight: 1,
};

const chipJumpInStyle: React.CSSProperties = {
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#1f2937',
  fontSize: 11,
  fontWeight: 600,
  padding: '3px 8px',
  borderRadius: 999,
  cursor: 'pointer',
  whiteSpace: 'nowrap',
};
