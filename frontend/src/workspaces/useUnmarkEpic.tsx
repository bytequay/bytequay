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

import React, { useState } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import type { Bridge } from '../types';

type Pending = { leaving: number | null; deleted: number; unmark: () => void };

/**
 * Asks before unmarking an Epic that has work, because its Epic view goes with the marker: the work that exists only there is deleted, and
 * work in a Goal or with a task stays without the Epic. An Epic without work is unmarked at once.
 */
export function useUnmarkEpic(bridge: () => Pick<Bridge, 'listWorkItems'> = () => window.bridge): {
  request: (workspaceId: string | null, epicId: string, unmark: () => void) => void;
  dialog: React.ReactNode;
} {
  const [pending, setPending] = useState<Pending | null>(null);
  const request = (workspaceId: string | null, epicId: string, unmark: () => void): void => {
    if (workspaceId === null) {
      unmark();
      return;
    }
    void Promise.resolve().then(() => bridge().listWorkItems(workspaceId)).then((items) => {
      const work = items.filter((item) => item.epicId === epicId);
      if (work.length === 0) unmark();
      else setPending({ leaving: work.length, deleted: work.filter((item) => item.goalId === null && !item.links.some((link) => link.kind === 'TASK')).length, unmark });
    }, () => setPending({ leaving: null, deleted: 0, unmark }));
  };
  const dialog = pending === null ? null : (
    <ConfirmDialog title="Unmark as Epic?" confirmLabel="Unmark" destructive onCancel={() => setPending(null)} onConfirm={() => {
      setPending(null);
      pending.unmark();
    }}>
      {pending.leaving === null
        ? <p>Its Epic view goes with it. Work that exists only there is deleted; work in a Goal or with a task stays.</p>
        : <p>
          Its Epic view goes with it: {pending.leaving === 1 ? 'its work item leaves' : `its ${pending.leaving} work items leave`} the Epic
          {pending.deleted > 0 && <>, and {pending.deleted === pending.leaving ? (pending.deleted === 1 ? 'it exists' : 'they exist') : `${pending.deleted} that exist`} only
            there {pending.deleted === 1 ? 'is' : 'are'} deleted</>}. Work in a Goal or with a task stays.
        </p>}
    </ConfirmDialog>
  );
  return { request, dialog };
}
