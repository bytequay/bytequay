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
/**
 * Ship-button gating for a task. Shipping is a one-time action: once a task
 * has been shipped (IN_REVIEW — pushed with a PR open, awaiting merge), parked
 * for approval (AWAITING_REVIEW), merged/completed, errored, or canceled, it
 * can't be shipped again, so the Ship button must be disabled. The button used
 * to disable only on COMPLETED/ERRORED, leaving a just-shipped IN_REVIEW task
 * offering "Ship — finalize & merge" again.
 */
export type ShipTaskLike = {
  status?: string | null;
  phase?: string | null;
};

export function isTaskShippable(task: ShipTaskLike | null | undefined): boolean {
  if (!task) {
    return false;
  }
  // A merged PR advances the dev-lifecycle phase to COMPLETED even when the
  // runtime status lags — treat either terminal signal as done.
  if ((task.phase ?? '') === 'COMPLETED') {
    return false;
  }
  switch (task.status ?? '') {
    case 'COMPLETED':
    case 'ERRORED':
    case 'CANCELED':
    case 'ARCHIVED':
    case 'IN_REVIEW':       // already shipped — PR open, awaiting merge
    case 'AWAITING_REVIEW': // parked for human approval
      return false;
    default:
      return true;
  }
}
