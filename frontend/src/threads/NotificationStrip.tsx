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
import { isPublishGateNotification } from '../notificationDisplay';
import { prRefFromNotification } from './notificationNav';
import PublishGatePane from '../PublishGatePane';
import type { NotificationDto, NotificationKindDto } from '../types';

type Props = {
  threadId: string;
  /** Navigate to a PR's in-app detail page. When a notification points at a
   *  PR (e.g. a NEEDS_ATTENTION about failing CI), Jump in opens it. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
};

/** Compact strip of unread or interrupted notifications scoped to this
 *  thread, shown above the agent terminal and in the task window.
 *  AWAITING_REVIEW rows expand the publish gate inline so the user can
 *  approve / discard without leaving for the notification center.
 *  RESOLVING rows remain visible until their local cleanup decision is
 *  recorded. */
export default function NotificationStrip({ threadId, onOpenPr }: Props) {
  const [items, setItems] = useState<NotificationDto[]>([]);
  /** Transient hint shown when the backend refuses a dismiss (e.g. a
   *  task that still needs attention). Cleared on the next refresh. */
  const [note, setNote] = useState<string | null>(null);
  /** Id of the AWAITING_REVIEW row whose publish gate is expanded. Only
   *  one opens at a time — mirrors the notification center so the user
   *  is never unsure which Approve they're about to click. */
  const [expandedId, setExpandedId] = useState<string | null>(null);

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

  const onJumpIn = async (n: NotificationDto, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await window.bridge.jumpInThread(threadId);
    }
    catch { /* fall through; the backend's defensive paths cover partial
                  failures, and we still want to navigate to the PR below */ }
    // When the notification points at a PR (e.g. failing CI), Jump in opens
    // it so the user lands on the actual problem instead of nowhere.
    const prRef = prRefFromNotification(n);
    if (prRef !== null && onOpenPr !== undefined) {
      onOpenPr(prRef.owner, prRef.repo, prRef.prNumber);
      return;
    }
    void refresh();
  };

  if (items.length === 0) return null;
  return (
    <div style={stripStyle}>
      {items.map(n => {
        const reviewable = isPublishGateNotification(n);
        const expanded = expandedId === n.id;
        return (
          <div key={n.id} style={itemWrapStyle(expanded)}>
            <div
              style={chipStyle(n.kind)}
              onClick={() => { void onMarkRead(n); }}
              role="button"
              tabIndex={0}
              title={n.kind === 'AWAITING_REVIEW'
                ? 'Awaiting your review — open Review to approve or discard'
                : 'Click to mark as read'}
            >
              {/* Animated dot: a live "this is waiting on you" cue so a
                  paused task is obvious at a glance, not a static badge. */}
              {isWaitingKind(n.kind) && (
                <span style={pulseDotStyle(n.kind)} aria-hidden />
              )}
              <span style={chipIconStyle}>{kindIcon(n.kind)}</span>
              <span style={chipBodyStyle}>
                <span style={chipTitleStyle}>{titleFor(n.kind)}</span>
                <span style={chipMetaStyle}>{previewFor(n)}</span>
              </span>
              {/* Inline approve/discard: expand the publish gate right
                  here so a parked push / PR / comment can be resolved
                  without leaving the task window. */}
              {reviewable && (
                <button
                  type="button"
                  style={chipReviewStyle(expanded)}
                  onClick={e => {
                    e.stopPropagation();
                    setExpandedId(expanded ? null : n.id);
                  }}
                  title={expanded
                    ? 'Hide the approval pane'
                    : 'Review, then approve or discard'}
                >
                  {expanded ? 'Hide' : 'Review'}
                </button>
              )}
              {/* Two predicates, not one. NEEDS_ATTENTION rows want
                  both — Jump-in to take the lease, Dismiss to clear
                  the bell badge without acting. AWAITING_REVIEW rows
                  still suppress Dismiss to force the approve/discard
                  flow. AUTO_FIX_DONE shows Dismiss only. */}
              {hasJumpInAffordance(n) && (
                <button
                  type="button"
                  style={chipJumpInStyle}
                  onClick={e => { void onJumpIn(n, e); }}
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
            {reviewable && expanded && (
              <div style={paneWrapStyle} onClick={e => e.stopPropagation()}>
                <PublishGatePane
                  notification={n}
                  onResolved={() => {
                    setExpandedId(null);
                    void refresh();
                  }}
                />
              </div>
            )}
          </div>
        );
      })}
      {note && <div style={noteStyle} role="status">{note}</div>}
      <style>{pulseKeyframes}</style>
    </div>
  );
}

const pulseKeyframes = `
@keyframes bq-status-dot {
  0%, 100% { opacity: 0.35; transform: scale(0.7); }
  50%      { opacity: 1;    transform: scale(1); }
}
@keyframes bq-await-glow {
  0%   { box-shadow: 0 0 0 0 rgba(124, 58, 237, 0.40); }
  70%  { box-shadow: 0 0 0 6px rgba(124, 58, 237, 0); }
  100% { box-shadow: 0 0 0 0 rgba(124, 58, 237, 0); }
}
@keyframes bq-attn-glow {
  0%   { box-shadow: 0 0 0 0 rgba(220, 38, 38, 0.40); }
  70%  { box-shadow: 0 0 0 6px rgba(220, 38, 38, 0); }
  100% { box-shadow: 0 0 0 0 rgba(220, 38, 38, 0); }
}`;

function isOpenParked(n: NotificationDto): boolean {
  // NEEDS_ATTENTION rows are informational and must be dismissible;
  // only AWAITING_REVIEW carries the approve/discard flow that the
  // strip's generic dismiss would side-step.
  return n.kind === 'AWAITING_REVIEW'
    && (n.status === 'UNREAD' || n.status === 'READ' || n.status === 'RESOLVING');
}

/** Jump-in (a lease takeover) only makes sense for a genuinely stuck /
 *  running task — NEEDS_ATTENTION. A cleanly parked AWAITING_REVIEW row is
 *  resolved via its Review button (approve / discard), so Jump-in there did
 *  nothing visible; it's no longer offered for that kind. */
export function hasJumpInAffordance(n: Pick<NotificationDto, 'kind' | 'status'>): boolean {
  return n.kind === 'NEEDS_ATTENTION'
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
    case 'AUTO_FIX_DONE':    return 'Done';
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
  if (n.kind === 'AWAITING_REVIEW') {
    // Spell out *why the task isn't progressing*: it's paused on the
    // user's approval. Name the parked action so the reminder is
    // concrete ("approve the push", not just "awaiting review").
    const action = typeof payload.action === 'string' ? payload.action : null;
    return `Paused — needs your approval to ${actionLabel(action)}`;
  }
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

/** Human verb for a parked publish-gate action, used in the paused-task
 *  reminder. Unknown / null actions fall back to a generic phrase. */
function actionLabel(action: string | null): string {
  switch (action) {
    case 'push':                  return 'push the branch';
    case 'open_pr':               return 'open the PR';
    case 'ship_task':             return 'ship the task';
    case 'next_task':             return 'start the next task';
    case 'post_comment':          return 'post the comment';
    case 'create_review_comment': return 'post the review comment';
    case 'reply_review_thread':   return 'reply on the review thread';
    case 'request_review':        return 'request review';
    case 'request_reviewer':      return 'request a reviewer';
    case 'approve_pr':            return 'approve the PR';
    case 'merge_pr':              return 'merge the PR';
    case 'update_pr_body':        return 'update the PR description';
    case 'comment_on_issue':      return 'comment on the issue';
    case 'set_issue_state':       return 'change the issue state';
    case 'publish_review':        return 'publish the review';
    default:                      return 'continue';
  }
}

/** Waiting states that explain "no progress" — both pause the task on
 *  the user, so both get the pulsing reminder treatment. */
function isWaitingKind(kind: NotificationKindDto): boolean {
  return kind === 'AWAITING_REVIEW' || kind === 'NEEDS_ATTENTION';
}

const stripStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 8,
  padding: '8px 12px',
  borderBottom: '1px solid #e5e7eb',
  background: '#fafaff',
};

// An expanded row claims the full strip width so the publish gate pane
// has room; collapsed rows keep their natural chip width and flow.
function itemWrapStyle(expanded: boolean): React.CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    gap: 8,
    flexBasis: expanded ? '100%' : 'auto',
    minWidth: 0,
  };
}

const paneWrapStyle: React.CSSProperties = {
  // Constrain the reused pane so its textarea-bearing actions wrap
  // inside the (sometimes narrow) task column instead of overflowing.
  minWidth: 0,
  maxWidth: 560,
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
  const waiting = isWaitingKind(kind);
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 8,
    padding: '6px 8px 6px 10px',
    // Waiting rows get a faint accent wash so the paused state reads as
    // "needs you", not just another grey notification.
    background: waiting ? `${accent}0d` : '#ffffff',
    border: `1px solid ${accent}33`,
    borderLeft: `3px solid ${accent}`,
    borderRadius: 6,
    cursor: 'pointer',
    fontSize: 12,
    color: '#111827',
    maxWidth: 360,
    // Slow halo pulse so it's noticeable in peripheral vision without
    // being a distracting strobe. Honours reduced-motion via the dot's
    // shared keyframe being subtle; the glow is a gentle 2.4s cycle.
    animation: waiting
      ? `${kind === 'AWAITING_REVIEW' ? 'bq-await-glow' : 'bq-attn-glow'} 2.4s ease-out infinite`
      : undefined,
  };
}

function pulseDotStyle(kind: NotificationKindDto): React.CSSProperties {
  const accent = kind === 'NEEDS_ATTENTION' ? '#dc2626' : '#7c3aed';
  return {
    width: 8,
    height: 8,
    borderRadius: 999,
    background: accent,
    flexShrink: 0,
    animation: 'bq-status-dot 1.4s ease-in-out infinite',
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

function chipReviewStyle(expanded: boolean): React.CSSProperties {
  return {
    border: '1px solid #7c3aed',
    background: expanded ? '#7c3aed' : '#ffffff',
    color: expanded ? '#ffffff' : '#7c3aed',
    fontSize: 11,
    fontWeight: 700,
    padding: '3px 10px',
    borderRadius: 999,
    cursor: 'pointer',
    whiteSpace: 'nowrap',
  };
}

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
