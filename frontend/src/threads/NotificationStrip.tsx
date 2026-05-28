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

/** Compact strip of unread or interrupted notifications scoped to this
 *  thread, shown above the agent terminal. RESOLVING rows remain visible
 *  until their local cleanup decision is recorded. */
export default function NotificationStrip({ threadId }: Props) {
  const [items, setItems] = useState<NotificationDto[]>([]);
  /** Transient hint shown when the backend refuses a dismiss (e.g. a
   *  task that still needs attention). Cleared on the next refresh. */
  const [note, setNote] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const all = await window.bridge.listNotificationsForThread(threadId);
      setItems(all.filter(n => n.status === 'UNREAD' || n.status === 'RESOLVING'));
      setNote(null);
    }
    catch { /* non-fatal — leave the previous list */ }
  }, [threadId]);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => { void refresh(); }, 20_000);
    return () => window.clearInterval(id);
  }, [refresh]);

  const onMarkRead = async (n: NotificationDto) => {
    // Unresolved parked work (an AWAITING_REVIEW proposal or a
    // NEEDS_ATTENTION task) must not be quieted by a passive click — it
    // stays in the bell + strip until it's actually resolved
    // (approve/discard, or fixing the stuck task). Only informational
    // AUTO_FIX_DONE rows clear on click.
    if (n.kind !== 'AUTO_FIX_DONE') return;
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
      void refresh();
    }
    catch {
      // The backend refuses to dismiss a row that still needs action
      // (a task stuck in NEEDS_ATTENTION). Surface a hint rather than a
      // dead click; the row stays so the user can Jump in. Don't
      // refresh here — nothing changed and refresh would clear the note.
      setNote(n.kind === 'NEEDS_ATTENTION'
        ? 'Resolve this task in its thread before dismissing.'
        : 'Resolve this from its review flow before dismissing.');
    }
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
          title={n.kind === 'AWAITING_REVIEW'
            ? 'Awaiting your review — approve or discard from the notification center'
            : 'Click to mark as read'}
        >
          <span style={chipIconStyle}>{kindIcon(n.kind)}</span>
          <span style={chipBodyStyle}>
            <span style={chipTitleStyle}>{titleFor(n.kind)}</span>
            <span style={chipMetaStyle}>{previewFor(n)}</span>
          </span>
          {/* Two predicates, not one. NEEDS_ATTENTION rows want
              both — Jump-in to take the lease, Dismiss to clear
              the bell badge without acting. AWAITING_REVIEW rows
              still suppress Dismiss to force the approve/discard
              flow. AUTO_FIX_DONE shows Dismiss only. */}
          {hasJumpInAffordance(n) && (
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
          {!isOpenParked(n) && (
            <button
              type="button"
              style={chipDismissStyle}
              onClick={e => { void onDismiss(n, e); }}
              title="Dismiss"
              aria-label="Dismiss notification"
            >
              ✕
            </button>
          )}
        </div>
      ))}
      {note && <div style={noteStyle} role="status">{note}</div>}
    </div>
  );
}

function isOpenParked(n: NotificationDto): boolean {
  // NEEDS_ATTENTION rows are informational and must be dismissible;
  // only AWAITING_REVIEW carries the approve/discard flow that the
  // strip's generic dismiss would side-step.
  return n.kind === 'AWAITING_REVIEW'
    && (n.status === 'UNREAD' || n.status === 'READ' || n.status === 'RESOLVING');
}

/** Jump-in is meaningful for any parked row that's still attached to
 *  a live agent — both AWAITING_REVIEW (publish gate) and
 *  NEEDS_ATTENTION (stuck headless run). Distinct from
 *  {@link isOpenParked} so NEEDS_ATTENTION can carry both Jump-in
 *  and Dismiss; AWAITING_REVIEW stays Jump-in-only because dismiss
 *  must go through the approve / discard surface. */
function hasJumpInAffordance(n: NotificationDto): boolean {
  return (n.kind === 'AWAITING_REVIEW' || n.kind === 'NEEDS_ATTENTION')
    && (n.status === 'UNREAD' || n.status === 'READ' || n.status === 'RESOLVING');
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

const noteStyle: React.CSSProperties = {
  flexBasis: '100%',
  fontSize: 11,
  color: '#9a3412',
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
