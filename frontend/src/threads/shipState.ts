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
import type { LocalPR } from '../types/localPr';

type TaskPromotionBridge = Pick<
  Bridge,
  'getPrForTask' | 'pushLocalPr'
>;

/** Fail-closed readiness check shared by Ship affordances and execution. */
export function isTaskOwnedLocalOpenPr(pr: LocalPR | null, taskId: string): boolean {
  return pr !== null
    && pr.taskId === taskId
    && pr.origin === 'task'
    && pr.status === 'local-open';
}

/** Route a visible Task Ship action through the V2 local-PR authority. */
export async function approveAndShipTask(
  bridge: TaskPromotionBridge,
  taskId: string,
): Promise<void> {
  const pr = await bridge.getPrForTask(taskId);
  if (pr === null) {
    throw new Error(`Task ${taskId} has no local PR to ship`);
  }
  if (pr.taskId !== taskId) {
    throw new Error(`Local PR ${pr.id} does not belong to Task ${taskId}`);
  }
  if (!isTaskOwnedLocalOpenPr(pr, taskId)) {
    throw new Error(`Task ${taskId} has no local PR ready to ship`);
  }
  await bridge.pushLocalPr(pr.id);
}
