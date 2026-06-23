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
import PublishGatePane from '../PublishGatePane';
import type { NotificationDto } from '../types';

/** Friendly label for the parked action, read from the notification payload. */
function actionLabel(payloadJson: string): string {
  let action = '';
  try { action = String(JSON.parse(payloadJson)?.action ?? ''); }
  catch { /* unparseable → generic label */ }
  switch (action) {
    case 'push': return 'push to the remote';
    case 'ship_task': return 'ship (push + open a draft PR)';
    case 'open_pr': return 'open a pull request';
    case 'update_pr_body': return 'update the PR description';
    case 'post_comment':
    case 'reply_review_thread': return 'post a comment';
    case 'request_review': return 'request a review';
    default: return 'publish a change';
  }
}

/**
 * Banner shown on the task brain + stage pages when the task's agent has
 * parked a proposal (an {@code AWAITING_REVIEW} notification) awaiting the
 * user's approval — so the gate is reachable from where the agent runs, not
 * only from the Notifications screen. Clicking "Review & approve" reveals the
 * shared {@link PublishGatePane} (the same diff + Approve/Discard used
 * everywhere). Polls so a freshly-parked proposal appears without a reload.
 */
export function PendingApprovalToast({ threadId, onResolved, onReview }: {
  threadId: string;
  /** Called after the proposal is approved/discarded so the host can refresh. */
  onResolved?: () => void;
  /** When set and the proposal is a ship_task, "Review & approve" routes to
   *  the task code-diff page (the full review surface) instead of expanding
   *  the inline gate here. */
  onReview?: () => void;
}) {
  const [pending, setPending] = useState<NotificationDto | null>(null);
  const [open, setOpen] = useState(false);

  const refresh = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listNotificationsForThread === undefined) return;
    try {
      const list = await bridge.listNotificationsForThread(threadId);
      const next = list.find(n => n.kind === 'AWAITING_REVIEW'
        && (n.status === 'UNREAD' || n.status === 'RESOLVING')) ?? null;
      setPending(next);
    }
    catch { /* non-fatal — just don't show the toast */ }
  }, [threadId]);

  useEffect(() => {
    void refresh();
    const t = setInterval(() => { void refresh(); }, 6000);
    return () => clearInterval(t);
  }, [refresh]);

  if (pending === null) return null;

  // A ship_task proposal is reviewed on the code-diff page (diff + PR
  // description + inline comments); route there. Other proposals keep the
  // lightweight inline gate.
  let isShip = false;
  try { isShip = JSON.parse(pending.payloadJson)?.action === 'ship_task'; }
  catch { /* leave false */ }
  const routeToReview = isShip && onReview !== undefined;

  return (
    <div className="approval-toast" role="status">
      <div className="approval-toast__bar">
        <span className="approval-toast__icon" aria-hidden>🔥</span>
        <span className="approval-toast__msg">
          The agent is waiting for your approval to <strong>{actionLabel(pending.payloadJson)}</strong>.
        </span>
        <button
          type="button"
          className="approval-toast__btn"
          onClick={() => { if (routeToReview) onReview!(); else setOpen(o => !o); }}
        >
          {routeToReview ? 'Review on the code-diff page →' : open ? 'Hide' : 'Review & approve'}
        </button>
      </div>
      {open && !routeToReview && (
        <div className="approval-toast__gate">
          <PublishGatePane
            notification={pending}
            onResolved={() => {
              setOpen(false);
              void refresh();
              onResolved?.();
            }}
          />
        </div>
      )}
    </div>
  );
}
