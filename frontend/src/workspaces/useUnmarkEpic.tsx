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

type Pending = { leaving: number | null; unmark: () => void };

/**
 * Asks before unmarking an Epic that has work, because its Epic view goes with the marker: the work stays in Work items without the Epic. An Epic without work is unmarked at once.
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
      else setPending({ leaving: work.length, unmark });
    }, () => setPending({ leaving: null, unmark }));
  };
  const dialog = pending === null ? null : (
    <ConfirmDialog title="Unmark as Epic?" confirmLabel="Unmark" destructive onCancel={() => setPending(null)} onConfirm={() => {
      setPending(null);
      pending.unmark();
    }}>
      <p>{pending.leaving === null ? 'Its work leaves the Epic.' : `${pending.leaving} work items leave the Epic.`} All work items, descriptions and task history stay in Work items.</p>
    </ConfirmDialog>
  );
  return { request, dialog };
}
