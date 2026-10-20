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

import type { Bridge } from '../types';
import type { WorkItemDecision, WorkItemDto } from './contracts';

/** Fired after any goal change so every view of the workspace's goals reads them again. */
export const GOALS_CHANGED = 'bytequay:goals-changed';

/**
 * Sends a decision on a work item. Receipts and summaries record facts and still move an item to its next revision, so a decision the user
 * makes while one lands is sent again once at the new revision when nothing it rests on changed: its state, scope, Goal, priority, order and
 * what it waits on. Any other change still fails, for the user to decide again on what they now see.
 */
export async function decideOnCurrent(bridge: Pick<Bridge, 'decideWorkItem' | 'listWorkItems'>, workspaceId: string, item: WorkItemDto,
  command: WorkItemDecision): Promise<WorkItemDto> {
  try {
    return await bridge.decideWorkItem(workspaceId, item.id, command);
  }
  catch (failure: unknown) {
    if (!(failure instanceof Error) || !failure.message.includes('Work item changed')) throw failure;
    const current = (await bridge.listWorkItems(workspaceId)).find((candidate) => candidate.id === item.id);
    const unchanged = current !== undefined && current.state === item.state && current.title === item.title && current.acceptanceCriteria === item.acceptanceCriteria
      && current.completionRule === item.completionRule && current.goalId === item.goalId && current.priority === item.priority && current.rank === item.rank
      && current.blockedBy.join('\n') === item.blockedBy.join('\n');
    if (!unchanged) throw failure;
    return bridge.decideWorkItem(workspaceId, item.id, { ...command, expectedRevision: current.revision });
  }
}
