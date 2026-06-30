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
import type { NotificationDto } from '../types';

/** The parked `action` carried by a proposal notification, or null when
 *  the payload is missing/unparseable. Lets a surface branch on the gate
 *  kind (e.g. `mark_ready` vs `ship_task`) without re-parsing inline. */
export function proposalAction(notification: NotificationDto | null): string | null {
  if (notification === null) return null;
  try {
    const action = JSON.parse(notification.payloadJson)?.action;
    return typeof action === 'string' && action.length > 0 ? action : null;
  }
  catch { return null; }
}

/**
 * Polls for a pending `ship_task` proposal on a task — the AWAITING_REVIEW
 * notification the dev agent parks when development finishes and the diff
 * is ready for the user to review + approve. Returns the proposal (or
 * null). The full review surface (diff, PR description, Approve & ship)
 * lives on {@code TaskCodePage}; this hook lets the stage and brain pages
 * surface a prompt that routes there. Mirrors {@code TaskCodePage}'s own
 * poll so both stay in sync.
 */
export function usePendingShipProposal(threadId: string, taskId: string): NotificationDto | null {
  const [proposal, setProposal] = useState<NotificationDto | null>(null);

  const refresh = useCallback(async () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.listNotificationsForThread === undefined) return;
    try {
      const list = await bridge.listNotificationsForThread(threadId);
      const matches = list.filter((n) => {
        if (n.kind !== 'AWAITING_REVIEW' || n.taskId !== taskId) return false;
        if (n.status !== 'UNREAD' && n.status !== 'RESOLVING') return false;
        // Any parked publish proposal — ship_task, open_pr, push, merge_pr … —
        // is an approval gate the user must see; matching only ship_task left
        // open_pr/push parks invisible. A parked proposal always carries a
        // non-empty `action`, which distinguishes it from a bare notice.
        try {
          const action = JSON.parse(n.payloadJson)?.action;
          return typeof action === 'string' && action.length > 0;
        }
        catch { return false; }
      });
      // The LATEST parked proposal is the live gate. An older un-superseded
      // one — e.g. a stale `ship_task` left behind after the task already
      // shipped — must never win over the current `merge_pr`, or the
      // "approve the dev result" card re-appears on the CI-fixing stage.
      const next = matches.reduce<NotificationDto | null>(
        (latest, n) => (latest === null || n.createdAt > latest.createdAt ? n : latest), null);
      setProposal(next);
    }
    catch { /* non-fatal — the prompt simply stays hidden */ }
  }, [threadId, taskId]);

  useEffect(() => {
    void refresh();
    const t = setInterval(() => { void refresh(); }, 6000);
    return () => clearInterval(t);
  }, [refresh]);

  return proposal;
}
