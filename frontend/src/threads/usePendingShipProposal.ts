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
      const next = list.find((n) => {
        if (n.kind !== 'AWAITING_REVIEW' || n.taskId !== taskId) return false;
        if (n.status !== 'UNREAD' && n.status !== 'RESOLVING') return false;
        // Any parked publish proposal — ship_task, open_pr, push, … — is an
        // approval gate the user must see; matching only ship_task left
        // open_pr/push parks invisible. A parked proposal always carries a
        // non-empty `action`, which distinguishes it from a bare notice.
        try {
          const action = JSON.parse(n.payloadJson)?.action;
          return typeof action === 'string' && action.length > 0;
        }
        catch { return false; }
      }) ?? null;
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
